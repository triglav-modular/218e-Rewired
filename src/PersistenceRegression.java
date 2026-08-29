// Executes the emitted persistence code AND the factory flash-copy wrapper.
// Only peripheral commands/page-buffer semantics are modeled. Never flashes.
// Run via tools/test_persistence.py; see docs/PERSISTENCE.md for limitations.
//@category Buchla218.Tests
import ghidra.app.script.GhidraScript;
import ghidra.app.emulator.EmulatorHelper;
import java.util.*;
import java.util.zip.CRC32;

public class PersistenceRegression extends GhidraScript {
    static final long S=0x3560, BASE=0x8003e000L;
    static final long CRC=0x8001cc00L, VALID=0x8001cce0L, NEWEST=0x8001ce00L,
        SAVE=0x8001d100L, CAPTURE=0x8001d280L, TICK=0x8001d400L,
        SHIM=0x8001d520L, ENTER=0x8001b660L, SCAN=0x8001a480L;
    EmulatorHelper e;
    boolean seq, clock, locked, allBad, badCommit, clockExercise;
    int outputs;
    int checks, writes, erases, programs, stores, cutBytes;
    String cut="";
    long page, sizeFn, clearFn, eraseFn, programFn, errorCell;
    byte[] buffer=new byte[512];
    final Set<Integer> badPages=new HashSet<>();
    final List<Long> targets=new ArrayList<>();
    static class PowerCut extends Exception {}

    long pc() { return e.getExecutionAddress().getOffset(); }
    void jump(long v) { e.writeRegister(e.getPCRegister(),v); }
    static String regName(int n) { return n==13?"SP":n==14?"LR":n==15?"PC":"R"+n; }
    long reg(int n) { return reg(regName(n)); }
    void setReg(int n,long v) { e.writeRegister(regName(n),v); }
    long reg(String n) { return e.readRegister(n).longValue()&0xffffffffL; }
    void w(long a,int n,long v) { e.writeMemoryValue(toAddr(a),n,v); }
    long r(long a,int n) {
        long v=0; for(byte b:e.readMemory(toAddr(a),n)) v=(v<<8)|(b&255); return v;
    }
    void check(String name,boolean ok) throws Exception {
        checks++; if(!ok) throw new Exception("FAIL "+name+" PC="+Long.toHexString(pc())
            +" writes="+writes+" request="+r(0x62e0,1));
    }
    void ret() { jump(reg("LR")); }
    void step() throws Exception {
        long p=pc();
        if(clockExercise) {
            if(p==0x80004c64L) { ret(); return; } // dispatch separately driven events
            if(p==0x800077f8L) { outputs++; w(S+0x354,2,0xfff); ret(); return; }
        }
        if(p==sizeFn) { e.writeRegister("R12",0x40000); ret(); return; }
        if(p==clearFn) { Arrays.fill(buffer,(byte)255); w(errorCell,4,0); ret(); return; }
        if(p==eraseFn) {
            erases++;
            if(!locked) { byte[] ff=new byte[512]; Arrays.fill(ff,(byte)255); e.writeMemory(toAddr(page),ff); }
            w(errorCell,4,locked?4:0);
            if(cut.equals("erase")) throw new PowerCut();
            ret(); return;
        }
        if(p==programFn) {
            programs++;
            boolean commit=(buffer[0]&255)!=255;
            byte[] old=e.readMemory(toAddr(page),512), next=old.clone();
            for(int i=0;i<512;i++) next[i]&=buffer[i];
            // UC3B 14.4.7 permits changes only to completely erased words.
            // The no-erase commit may reprogram identical existing words.
            for(int i=0;i<512;i+=4) {
                boolean changed=false, erased=true;
                for(int j=0;j<4;j++) { changed|=old[i+j]!=next[i+j]; erased&=old[i+j]==(byte)255; }
                check("only erased words change",!changed||erased);
            }
            boolean bad=allBad||badPages.contains((int)((page-BASE)/512));
            if(!commit&&bad) next[4]=(byte)255; // body cell fails to program
            if(commit&&badCommit) next[0]=(byte)255;
            if(cut.equals(commit?"commit-partial":"body-partial")) {
                System.arraycopy(next,0,old,0,cutBytes);
                e.writeMemory(toAddr(page),old); throw new PowerCut();
            }
            if(!locked) e.writeMemory(toAddr(page),next);
            w(errorCell,4,locked?4:0);
            if(cut.equals(commit?"commit":"body")) throw new PowerCut();
            ret(); return;
        }
        if(p==0x800108fcL) {
            long len=reg("R10"), erase=reg("R9"); page=reg("R12");
            check("aligned reserved-page driver arguments",page>=BASE&&page<BASE+4096
                &&page%512==0&&reg("R11")==0x6300
                &&((len==224&&erase==1)||(len==8&&erase==0)));
            if(len==8) {
                check("body verified before commit",r(page,4)==0xffffffffL
                    &&Arrays.equals(e.readMemory(toAddr(page+4),220),e.readMemory(toAddr(0x6304),220)));
                if(cut.equals("before-commit")) throw new PowerCut();
            }
            writes++; targets.add(page);
        }
        // Actual paired-register ST.D operations fill a write-only buffer;
        // reads keep seeing the old flash until the page-program command.
        if(p==0x80010bbcL||p==0x80010d90L) {
            long dest=reg(p==0x80010bbcL?"R11":"R10");
            check("page-buffer store in target page",(dest&~511L)==page);
            byte[] old=e.readMemory(toAddr(dest),8);
            if(!e.step(monitor)) throw new Exception(e.getLastError());
            byte[] pair=e.readMemory(toAddr(dest),8);
            for(int i=0;i<8;i++) buffer[(int)(dest&511)+i]&=pair[i];
            e.writeMemory(toAddr(dest),old); stores++; return;
        }
        // Physical GPIO setup, LEDs, serial output and SPI only. Gestures,
        // mode transitions, strip borrowing and pickup execute normally.
        if(p==0x80007340L||p==0x80006808L||p==0x800068ccL||p==0x8000673cL
            ||p==0x80008104L||p==0x80007efcL) { ret(); return; }
        if(p==0x80002456L) { jump(0x8000245aL); return; }
        if(p==0x80007572L) { jump(0x80007576L); return; }
        int ins=(int)r(p,2);
        // The installed AVR32 SLEIGH inserts BFINS at the wrong bit offset.
        // The real pitch pass reaches factory soft-float sites that depend on
        // it, so model the instruction here instead of supplying its result
        // through a synthetic 0x3210 pitch fixture.
        if((ins&0xe1f0)==0xe1d0&&(int)r(p+2,2)>>10==0x34) {
            int lo=(int)r(p+2,2);
            int bp=(lo>>5)&0x1f, width=lo&0x1f, rd=(ins>>9)&0xf;
            long mask=((1L<<width)-1)<<bp;
            long v=((reg(rd)&~mask)|((reg(ins&0xf)<<bp)&mask))&0xffffffffL;
            setReg(rd,v);
            e.writeRegister("Z",v==0?1:0); e.writeRegister("N",(v>>>31)&1);
            jump(p+4); return;
        }
        // The installed SLEIGH omits the MOV PC,Rs branch p-code.
        if((ins&0xe1ff)==0x009f) {
            int n=(ins>>9)&15; jump(reg(n==13?"SP":n==14?"LR":n==15?"PC":"R"+n)); return;
        }
        if(!e.step(monitor)) throw new Exception("PC="+Long.toHexString(pc())+" "+e.getLastError());
    }
    long call(long entry,long end) throws Exception {
        e.writeRegister("SP",0x7800); e.writeRegister("R7",0x7600); e.writeRegister("LR",0x100); jump(entry);
        for(int i=0;i<500000;i++) {
            if(pc()==end) { check("stack restored",reg("SP")==0x7800); return reg("R12"); }
            step();
        }
        throw new Exception("instruction budget at "+Long.toHexString(pc()));
    }
    long call(long entry) throws Exception { return call(entry,0x100); }
    long capture(int mask) throws Exception {
        e.writeRegister("R12",mask); return call(CAPTURE);
    }
    // Low-level flash tests explicitly finish all synthetic edits. Gesture
    // tests below enter TICK/SHIM and never bypass the firmware's selection.
    long saveLive() throws Exception { capture(31); return call(SAVE); }
    void time(long ms) { e.writeRegister("COUNT",(ms*25000)&0xffffffffL); }
    void boot() throws Exception { call(0x80007bf4L,0x80007bf8L); }
    void cold() throws Exception {
        e.writeMemory(toAddr(0),new byte[0x8000]);
        e.writeMemory(toAddr(8),e.readMemory(toAddr(0x80015d28L),0x2ecc)); w(0x2ed4,4,0xffffffffL);
        for(int i=0;i<=12;i++) e.writeRegister("R"+i,0);
        e.writeRegister("SR",0); for(String f:new String[]{"N","Z","V","C"}) e.writeRegister(f,0);
        w(0x29cc,4,25000000); w(S+0x20c,4,1); time(0);
        w(0xffff1060L,4,0); w(0xffff10d0L,4,0);
        w(0xffff2404L,4,0); w(0xffff2410L,4,0x202);
        boot();
        check("boot primed before first scan",r(0x62fd,1)==1&&r(0x6158,1)==0&&r(0x62e0,1)==0);
        check("factory strip mode preserved",r(S+0x20c,4)==1);
    }
    void fresh() throws Exception {
        if(e!=null)e.dispose(); e=new EmulatorHelper(currentProgram);
        sizeFn=r(0x80010e80L,4); clearFn=r(0x80010e84L,4); errorCell=r(0x80010e88L,4);
        eraseFn=r(0x80010e8cL,4); programFn=r(0x80010e90L,4);
        byte[] ff=new byte[4096]; Arrays.fill(ff,(byte)255); e.writeMemory(toAddr(BASE),ff);
        locked=false; allBad=false; badCommit=false; clockExercise=false; cut=""; badPages.clear(); targets.clear();
        writes=0; erases=0; programs=0; stores=0; cold();
        check("settings page untouched",r(0x968,4)==0x8003f000L);
    }
    void seed() throws Exception {
        for(int i=0;i<4;i++) { w(0x613a+2*i,2,400+100*i); w(0x6160+2*i,2,500+100*i); w(0x61ee+i,1,i); }
        w(0x61e0,1,4);
        check("initial save succeeds",saveLive()==0&&writes==2&&erases==1&&programs==2);
        check("first slot and generation",call(NEWEST)==BASE&&r(0x62e1,1)==0&&r(0x62e4,4)==1);
    }
    void basic() throws Exception {
        fresh();
        e.writeMemory(toAddr(0x7000),"123456789".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        e.writeRegister("R12",0xffffffffL); e.writeRegister("R11",0x7000); e.writeRegister("R10",9);
        check("CRC32 standard check vector",(call(CRC)^0xffffffffL)==0xcbf43926L);
        seed(); check("unchanged skips flash",saveLive()==0&&writes==2);
        // Musical equality excludes modes, pickup, old steps and rest keys.
        w(0x6158,1,1); w(0x614a,1,1); w(0x61e1,1,3); w(0x6168,2,1234); w(0x61f2,1,12);
        check("runtime and unused steps do not cause writes",saveLive()==0&&writes==2);
        for(int n=0;n<10;n++) { w(0x613a,2,401+n); check("ring save",saveLive()==0); }
        check("rotation wraps",r(0x62e1,1)==2&&r(0x62e4,4)==11);
        cold(); check("only musical state restored",r(0x613a,2)==410&&r(0x61e0,1)==4
            &&r(0x6158,1)==0&&r(0x614a,4)==0&&r(0x61e1,1)==0&&r(0x6168,2)==0);
        // A matching first-use marker must not bypass a warm-reset restore.
        w(0x613a,2,999); w(0x6158,1,1); w(0x615f,1,1); w(0x622e,2,2); boot();
        check("warm reset restores stopped and clears transients",r(0x613a,2)==410
            &&r(0x6158,1)==0&&r(0x615f,1)==0&&r(0x622e,2)==0);
        println("PASS CRC vector, real factory wrapper, equality, ring wrap, cold/warm startup");
    }
    void retries() throws Exception {
        for(String fault:new String[]{"locked","body","commit"}) {
            fresh(); seed(); w(0x613a,2,901); int before=writes;
            locked=fault.equals("locked"); allBad=fault.equals("body"); badCommit=fault.equals("commit");
            check("failure returns",saveLive()==1);
            check("bounded seven candidate pages",writes-before==(badCommit?14:7)&&r(0x62e0,1)==2);
            check("newest good page never targeted",!targets.subList(before,targets.size()).contains(BASE));
            check("last record survives",call(NEWEST)==BASE);
            before=writes;
            for(int i=0;i<10;i++) { time(6000+i*5); call(TICK); }
            check("failure latched, not retried at scan rate",writes==before);
            locked=false; allBad=false; badCommit=false;
            w(0x62f9,1,1); call(TICK);
            check("unchanged gesture does not retry failed write",writes==before&&r(0x62e0,1)==2);
            w(0x613a,2,902); w(0x62f9,1,1); call(TICK);
            check("new edit rearms save",writes==before+2&&r(0x62e0,1)==0);
        }
        fresh(); allBad=true; w(0x613a,2,123);
        check("empty ring failure returns after eight",saveLive()==1&&writes==8&&call(NEWEST)==0);
        fresh(); seed(); badPages.add(1); w(0x613a,2,402); saveLive();
        check("skip one bad page",writes==5&&call(NEWEST)==BASE+1024);
        println("PASS bounded body/commit/locked failures, last-copy protection, latched retry, bad-page skip");
    }
    void powerCuts() throws Exception {
        for(String point:new String[]{"erase","body-partial","body","before-commit","commit-partial","commit"}) {
            int[] lengths=point.equals("body-partial")?new int[]{0,8,16,32,128,223}
                :point.equals("commit-partial")?new int[]{0,1,2,3}:new int[]{0};
            for(int n:lengths) {
                fresh(); seed(); w(0x613a,2,902); cut=point; cutBytes=n;
                try { saveLive(); throw new Exception("power cut not reached: "+point); }
                catch(PowerCut expected) { /* inspect retained flash at restart */ }
                cut=""; cold();
                check("power-cut fallback "+point+"/"+n,r(0x613a,2)==(point.equals("commit")?902:400));
                check("old record retained after cut",r(BASE,4)==0x32313850L);
            }
        }
        println("PASS power cuts at erase, partial body, body, pre-commit, partial marker, committed record");
    }
    void fixCrc(long p) {
        CRC32 crc=new CRC32(); crc.update(e.readMemory(toAddr(p+4),8)); crc.update(e.readMemory(toAddr(p+16),204));
        w(p+12,4,crc.getValue());
    }
    void corruption() throws Exception {
        fresh(); seed(); w(0x613a,2,0); saveLive(); long p=BASE+512;
        byte[] good=e.readMemory(toAddr(p),512);
        for(int off:new int[]{0,4,6,8,12,16,17,24,25,28,155,156,219}) {
            e.writeMemory(toAddr(p),good); w(p+off,1,r(p+off,1)^1);
            check("CRC/metadata rejects corruption at "+off,call(NEWEST)==BASE);
        }
        e.writeMemory(toAddr(p),good);
        long sum=r(p+12,4); int bit=0; while((sum&(1L<<bit))!=0)bit++;
        check("collision fixture bit available",bit<32);
        w(p+16,2,1L<<bit); w(p+12,4,sum|(1L<<bit));
        check("old additive-checksum collision rejected",call(NEWEST)==BASE);
        // Semantic checks remain necessary even with a correctly formed CRC.
        for(long[] invalid:new long[][]{{16,2,1024},{24,1,65},{28,2,0x4000},{156,1,29},{4,2,1},{8,4,0}}) {
            e.writeMemory(toAddr(p),good); w(p+invalid[0],(int)invalid[1],invalid[2]); fixCrc(p);
            check("out-of-range record rejected",call(NEWEST)==BASE);
        }
        e.writeMemory(toAddr(p),good); w(p+8,4,0xffffffffL); fixCrc(p);
        w(BASE+8,4,0xfffffffeL); fixCrc(BASE);
        check("newest near generation wrap",call(NEWEST)==p);
        w(0x613a,2,1); saveLive();
        check("wrap skips generation zero",r(0x62e4,4)==1&&call(NEWEST)==BASE+1024);
        cold(); check("wrapped generation restores",r(0x613a,2)==1);
        // Full-length sequence with pitches, rest and tie survives exactly.
        fresh(); for(int i=0;i<64;i++) { w(0x6160+2*i,2,i<62?i*60:i==62?0x7ffe:0x7fff); w(0x61ee+i,1,i%29); }
        w(0x61e0,1,64); saveLive(); cold();
        check("64 steps, rest and tie",r(0x61e0,1)==64&&r(0x61dc,2)==0x7ffe&&r(0x61de,2)==0x7fff);
        check("rest/tie keys canonical zero",r(0x622c,2)==0);
        println("PASS CRC/semantic corruption rejection, legacy rejection, generation wrap, 64-step rest/tie");
    }
    void gesturePolicy() throws Exception {
        fresh(); seed();
        byte[] initial=e.readMemory(toAddr(0x6400),204);
        for(int i=0;i<4;i++)w(0x613a+2*i,2,450+100*i);
        w(0x6160,2,999);
        for(int mask=0;mask<32;mask++) {
            e.writeMemory(toAddr(0x6400),initial);
            check("capture reports only selected changes",capture(mask)==(mask==0?0:1));
            for(int i=0;i<4;i++)check("independent preset mask",r(0x6400+2*i,2)
                ==400+100*i+((mask&(1<<i))!=0?50:0));
            check("independent sequence mask",r(0x640c,2)==((mask&16)!=0?999:500));
        }
        // Everything that used to inhibit writes is active. Only pad 1 is
        // released; pad 2 is still editing. No idle time is advanced.
        for(int mode:new int[]{0,1,2}) {
            fresh(); seed(); w(0x6158,1,mode); w(0x62f8,1,mode);
            w(S+0x340,1,1); w(S+0x341,1,1); w(S+0x21a,1,2); w(S+0x238,1,2);
            w(S+0x354,2,0xaaa); w(0x60ee,1,1); w(0x46f1,1,2); w(0x614b,1,1);
            w(0x613a,2,903); w(0x613c,2,904); w(0x62f9,1,1);
            w(0x623c,4,12345); w(0x29cc,4,0);
            byte[] clockState=e.readMemory(toAddr(0x6232),0xae);
            call(TICK);
            check("release commits immediately while busy, mode "+mode,writes==4&&r(0x62e0,1)==0);
            long p=call(NEWEST);
            check("held other pad excluded",r(p+16,2)==903&&r(p+18,2)==500&&r(0x613c,2)==904);
            check("save preserves musical transport state",r(0x6158,1)==mode&&r(S+0x354,2)==0xaaa
                &&r(0x60ee,1)==1&&Arrays.equals(clockState,e.readMemory(toAddr(0x6232),0xae)));
            call(TICK); check("no repeat write on next scan",writes==4);
        }
        println("PASS selective capture and immediate saves with arp/record/play, held controls, active gate/clock, no timebase");
    }
    void presets() throws Exception {
        fresh(); seed();
        for(int i=0;i<4;i++)w(S+0x30a+2*i,2,400+100*i);
        call(SHIM);
        w(0x46f0,1,2); call(SHIM); w(0x46f0,1,0); call(SHIM);
        check("pad tap without a new value never writes",writes==2);
        w(0x46f0,1,2); w(0x46f1,1,2); w(S+0x30a,2,450); w(S+0x30c,2,550); call(SHIM);
        check("continuous edits stay in RAM",writes==2&&r(0x613a,2)==450&&r(0x613c,2)==550);
        w(0x46f0,1,1); call(SHIM);
        check("still touched is not a full release",writes==2&&r(0x62f9,1)==1);
        w(0x46f0,1,0); call(SHIM);
        long p=call(NEWEST);
        check("only released preset committed",writes==4&&r(p+16,2)==450&&r(p+18,2)==500);
        w(0x46f1,1,0); call(SHIM); p=call(NEWEST);
        check("second release commits its new value",writes==6&&r(p+18,2)==550);
        call(SHIM); check("repeated scans never repeat save",writes==6);
        w(0x46f2,1,2); w(S+0x30e,2,650); call(SHIM);
        w(S+0x30e,2,600); call(SHIM); w(0x46f2,1,0); call(SHIM);
        check("return to old preset value skips flash",writes==6);
        w(0x46f2,1,2); w(0x46f3,1,2); w(S+0x30e,2,625); w(S+0x310,2,725); call(SHIM);
        w(0x46f2,2,0); call(SHIM); p=call(NEWEST);
        check("simultaneous releases coalesce into one record",writes==8&&r(p+20,2)==625&&r(p+22,2)==725);
        cold(); check("all released preset values survive",r(0x613a,2)==450&&r(0x613c,2)==550
            &&r(0x613e,2)==625&&r(0x6140,2)==725&&capture(31)==0);
        fresh(); w(0x46f0,1,2); w(S+0x30a,2,50); call(SHIM);
        w(S+0x30a,2,0); call(SHIM); w(0x46f0,1,0); call(SHIM);
        check("unchanged preset never creates a first record",writes==0&&call(NEWEST)==0);
        println("PASS real pickup/release, partial release, no-op/reverted values, independent and simultaneous pads");
    }
    void gestures() throws Exception {
        if(!seq) return;
        for(int mode:new int[]{0,1,2}) {
            fresh(); seed(); w(0x6158,1,mode); call(TICK);
            e.writeRegister("R11",2); call(ENTER); call(TICK);
            check("clear saved immediately from mode "+mode,r(0x61e0,1)==0&&r(0x62e0,1)==0&&writes==4);
            cold(); check("clear survives restart",r(0x61e0,1)==0&&r(0x613a,2)==400);
        }
        fresh(); seed();
        e.writeRegister("R11",0); call(ENTER); call(TICK);
        check("record borrows strip",r(0x6158,1)==1&&r(S+0x20c,4)==0&&r(0x622e,2)==2);
        w(0x61e0,1,5); w(0x6168,2,950); w(0x61f2,1,4);
        w(S+0x206,1,1); w(S+0x1fe,2,0); call(0x8001b590L);
        w(0x46f0,1,2); w(S+0x30a,2,333); call(SHIM);
        check("actual preset pickup",r(0x614a,1)==1&&r(0x613a,2)==333);
        w(0x46f0,1,0); call(SHIM);
        long p=call(NEWEST);
        check("preset saves during record without saving unfinished take",writes==4&&r(0x6158,1)==1
            &&r(p+16,2)==333&&r(p+24,1)==4&&r(0x61e0,1)==5);
        e.writeRegister("R11",1); call(ENTER); call(TICK);
        int length=(int)r(0x61e0,1);
        check("record-to-play saves at once",writes==6&&r(0x6158,1)==2&&r(call(NEWEST)+24,1)==length);
        cold(); call(SCAN);
        check("no phantom rest or recording on startup",r(0x6158,1)==0&&r(0x61e0,1)==length
            &&r(S+0x20c,4)==1&&r(0x622e,2)==0&&r(0x613a,2)==333);

        fresh(); seed(); w(0x613a,2,777); w(0x614a,1,1); w(0x46f0,1,2);
        e.writeRegister("R11",0); call(ENTER); call(TICK);
        e.writeRegister("R11",1); call(ENTER); call(TICK);
        check("unchanged record exit ignores a held preset edit",writes==2);
        w(0x614a,1,0); w(0x46f0,1,0); call(TICK);
        check("held preset later saves on its own release",writes==4&&r(call(NEWEST)+16,2)==777);
        fresh(); e.writeRegister("R11",2); call(ENTER); call(TICK);
        e.writeRegister("R11",0); call(ENTER); call(TICK);
        e.writeRegister("R11",1); call(ENTER); call(TICK);
        check("empty clear and unchanged take do not create a record",writes==0);

        // Exercise the real scan order: chord edits must be seen by
        // persistence in THIS scan, while pad 4 remains held and armed.
        fresh(); seed(); w(0x6154,2,200); w(0x6156,1,1);
        w(0x46f3,1,2); w(0x46f0,1,2); call(SCAN);
        check("real scan enters record",r(0x6158,1)==1&&writes==2);
        w(0x61e0,1,5); w(0x6168,2,999); w(0x61f2,1,5);
        w(0x46f0,1,0); call(SCAN);
        w(0x46f1,1,2); call(SCAN);
        check("record exit saved in same chord scan",r(0x6158,1)==2&&writes==4
            &&r(call(NEWEST)+24,1)==5&&r(0x46f3,1)==2);
        w(0x46f1,1,0); call(SCAN); w(0x46f2,1,2); call(SCAN);
        check("clear saved in same chord scan",r(0x61e0,1)==0&&writes==6&&r(call(NEWEST)+24,1)==0);
        w(0x46f2,1,0); call(SCAN); w(0x46f2,1,2); call(SCAN);
        check("repeated empty clear skips flash",writes==6);
        println("PASS real clear/record-exit saves, unfinished-edit isolation, same-scan chords, no-op gestures and restart");
    }
    void edge(long ms,boolean high) throws Exception {
        time(ms); w(0xffff1060L,4,high?32:0); w(0xffff10d0L,4,32);
        call(0x800072e4L,0x80007328L); w(0xffff10d0L,4,0);
    }
    void serviceAndOutput(long ms) throws Exception {
        time(ms); call(0x80007c66L,0x80007c6aL);
        call(0x80004f66L,0x80004faeL);
        call(0x800031b8L,0x80003256L);
    }
    void playbackSave() throws Exception {
        if(!clock)return;
        fresh(); seed(); clockExercise=true; outputs=0;
        call(0x8000737eL,0x80007386L);
        w(S+0x340,1,1); w(S+0x21a,1,1); w(S+0x21b,1,1);
        w(S+0x34a,2,20); w(S+0x38e,2,100); w(S+0x2fc,2,0);
        w(0x2ee0,2,20); w(0x2ee6,2,1023);
        if(seq)w(0x6158,1,2);
        else { e.writeRegister("R12",0); call(0x8001a020L); }
        for(int i=0;i<8;i++) {
            long ms=10+5*i; edge(ms-2,false); edge(ms,true); serviceAndOutput(ms);
            check("one physical output per pre-save pulse",outputs==i+1);
        }
        w(0x46f0,1,2); w(S+0x30a,2,777); call(SHIM);
        check("held edit does not write during playback",writes==2);
        byte[] clockState=e.readMemory(toAddr(0x6232),0xae);
        w(0x46f0,1,0); call(SHIM);
        check("released preset commits during playback",writes==4&&r(call(NEWEST)+16,2)==777);
        check("save leaves captured-clock state intact",Arrays.equals(clockState,e.readMemory(toAddr(0x6232),0xae)));
        // A modeled scheduling pause, not a claim of physical flash latency.
        // No ISR events are invented for transitions the CPU could not see.
        serviceAndOutput(70); check("save cannot invent a catch-up trigger",outputs==8);
        for(int i=0;i<8;i++) {
            long ms=80+5*i; edge(ms-2,false); edge(ms,true); serviceAndOutput(ms);
            check("fresh pulses resume without repeats after save",outputs==9+i);
        }
        clockExercise=false;
        println("PASS actual clock/output before and after a release save; no reset or synthetic catch-up triggers");
    }
    public void run() throws Exception {
        String mode=getScriptArgs().length>0?getScriptArgs()[0]:"seq-clock";
        seq=mode.contains("seq"); clock=mode.contains("clock");
        try {
            basic(); retries(); powerCuts(); corruption(); gesturePolicy(); presets(); gestures(); playbackSave();
            println("PERSISTENCE REGRESSION PASS: "+mode+", "+checks+" assertions; no physical flash/analog testing.");
        } finally { if(e!=null)e.dispose(); }
    }
}
