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
        SAVE=0x8001d100L, SAFE=0x8001d280L, TICK=0x8001d400L,
        SHIM=0x8001d520L, ENTER=0x8001b660L, SCAN=0x8001a480L;
    EmulatorHelper e;
    boolean seq, clock, locked, allBad, badCommit;
    int checks, writes, erases, programs, stores, cutBytes;
    String cut="";
    long page, sizeFn, clearFn, eraseFn, programFn, errorCell;
    byte[] buffer=new byte[512];
    final Set<Integer> badPages=new HashSet<>();
    final List<Long> targets=new ArrayList<>();
    static class PowerCut extends Exception {}

    long pc() { return e.getExecutionAddress().getOffset(); }
    void jump(long v) { e.writeRegister(e.getPCRegister(),v); }
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
        // The installed SLEIGH omits the MOV PC,Rs branch p-code.
        int ins=(int)r(p,2);
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
        locked=false; allBad=false; badCommit=false; cut=""; badPages.clear(); targets.clear();
        writes=0; erases=0; programs=0; stores=0; cold();
        check("settings page untouched",r(0x968,4)==0x8003f000L);
    }
    void seed() throws Exception {
        for(int i=0;i<4;i++) { w(0x613a+2*i,2,400+100*i); w(0x6160+2*i,2,500+100*i); w(0x61ee+i,1,i); }
        w(0x61e0,1,4); w(0x62e2,1,4);
        check("initial save succeeds",call(SAVE)==0&&writes==2&&erases==1&&programs==2);
        check("first slot and generation",call(NEWEST)==BASE&&r(0x62e1,1)==0&&r(0x62e4,4)==1);
    }
    void idle() {
        w(S+0x340,2,0); w(S+0x21a,1,0); w(S+0x238,1,0); w(S+0x354,2,0);
        w(0x6158,1,0); w(0x60ee,1,0); w(0x46f0,4,0); w(0x614a,4,0);
    }
    void settle(long start) throws Exception { idle(); time(start); call(TICK); time(start+2601); call(TICK); }
    void basic() throws Exception {
        fresh();
        e.writeMemory(toAddr(0x7000),"123456789".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        e.writeRegister("R12",0xffffffffL); e.writeRegister("R11",0x7000); e.writeRegister("R10",9);
        check("CRC32 standard check vector",(call(CRC)^0xffffffffL)==0xcbf43926L);
        seed(); check("unchanged skips flash",call(SAVE)==0&&writes==2);
        // Musical equality excludes modes, pickup, old steps and rest keys.
        w(0x6158,1,1); w(0x614a,1,1); w(0x61e1,1,3); w(0x6168,2,1234); w(0x61f2,1,12);
        check("runtime and unused steps do not cause writes",call(SAVE)==0&&writes==2);
        for(int n=0;n<10;n++) { w(0x613a,2,401+n); check("ring save",call(SAVE)==0); }
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
            check("failure returns",call(SAVE)==1);
            check("bounded seven candidate pages",writes-before==(badCommit?14:7)&&r(0x62e0,1)==2);
            check("newest good page never targeted",!targets.subList(before,targets.size()).contains(BASE));
            check("last record survives",call(NEWEST)==BASE);
            before=writes; settle(3000);
            for(int i=0;i<10;i++) { time(6000+i*5); call(TICK); }
            check("failure latched, not retried at scan rate",writes==before);
            locked=false; allBad=false; badCommit=false;
            w(0x62f9,1,1); call(TICK);
            check("new edit rearms save",writes==before+2&&r(0x62e0,1)==0);
        }
        fresh(); allBad=true; w(0x613a,2,123);
        check("empty ring failure returns after eight",call(SAVE)==1&&writes==8&&call(NEWEST)==0);
        fresh(); seed(); badPages.add(1); w(0x613a,2,402); call(SAVE);
        check("skip one bad page",writes==5&&call(NEWEST)==BASE+1024);
        println("PASS bounded body/commit/locked failures, last-copy protection, latched retry, bad-page skip");
    }
    void powerCuts() throws Exception {
        for(String point:new String[]{"erase","body-partial","body","before-commit","commit-partial","commit"}) {
            int[] lengths=point.equals("body-partial")?new int[]{0,8,16,32,128,223}
                :point.equals("commit-partial")?new int[]{0,1,2,3}:new int[]{0};
            for(int n:lengths) {
                fresh(); seed(); w(0x613a,2,902); cut=point; cutBytes=n;
                try { call(SAVE); throw new Exception("power cut not reached: "+point); }
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
        fresh(); seed(); w(0x613a,2,0); call(SAVE); long p=BASE+512;
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
        w(0x613a,2,1); call(SAVE);
        check("wrap skips generation zero",r(0x62e4,4)==1&&call(NEWEST)==BASE+1024);
        cold(); check("wrapped generation restores",r(0x613a,2)==1);
        // Full-length sequence with pitches, rest and tie survives exactly.
        fresh(); for(int i=0;i<64;i++) { w(0x6160+2*i,2,i<62?i*60:i==62?0x7ffe:0x7fff); w(0x61ee+i,1,i%29); }
        w(0x61e0,1,64); call(SAVE); cold();
        check("64 steps, rest and tie",r(0x61e0,1)==64&&r(0x61dc,2)==0x7ffe&&r(0x61de,2)==0x7fff);
        check("rest/tie keys canonical zero",r(0x622c,2)==0);
        println("PASS CRC/semantic corruption rejection, legacy rejection, generation wrap, 64-step rest/tie");
    }
    void safety() throws Exception {
        fresh(); seed(); w(0x613a,2,903); w(0x62e0,1,1);
        long[][] busy={{S+0x340,1},{S+0x341,1},{S+0x21a,1},{S+0x238,1},{S+0x354,2},
            {0x6158,1},{0x60ee,1},{0x46f0,1},{0x46f3,1},{0x614a,1},{0x614d,1}};
        for(long[] v:busy) {
            idle(); w(v[0],(int)v[1],1); time(5000); call(TICK); time(9000); call(TICK);
            check("busy input inhibits flash "+Long.toHexString(v[0]),writes==2&&r(0x62e0,1)==1);
        }
        idle(); time(10000); call(TICK); time(12600); call(TICK);
        check("strict idle interval",writes==2);
        if(clock) {
            w(0x623c,4,12599*25000L); time(12601); call(TICK);
            check("recent external clock inhibits flash",writes==2);
            time(15200); call(TICK);
        } else { time(12601); call(TICK); }
        check("idle eventually commits",writes==4&&r(0x62e0,1)==0);
        // Both idle and COUNT-minus-last-clock arithmetic must cross wrap.
        w(0x613a,2,904); w(0x62e0,1,1); w(0x62ec,1,0);
        time(170000); w(0x623c,4,170000*25000L); call(TICK);
        time(172601); call(TICK); check("idle COUNT wrap",writes==6);
        w(0x29cc,4,0); w(0x62e0,1,1); time(180000); call(TICK);
        check("missing CPU timebase prevents flash",writes==6);
        println("PASS safe-save gates, exact idle boundary, recent clock, COUNT wrap, missing timebase");
    }
    void gestures() throws Exception {
        if(!seq) return;
        for(int mode:new int[]{0,1,2}) {
            fresh(); seed(); w(0x6158,1,mode); call(TICK);
            e.writeRegister("R11",2); call(ENTER); call(TICK);
            check("clear queued from mode "+mode,r(0x61e0,1)==0&&r(0x62e0,1)==1&&writes==2);
            settle(3000); cold(); check("clear survives restart",r(0x61e0,1)==0&&r(0x613a,2)==400);
        }
        fresh(); w(0x61e0,1,1); w(0x6160,2,500);
        e.writeRegister("R11",0); call(ENTER); call(TICK);
        check("record borrows strip",r(0x6158,1)==1&&r(S+0x20c,4)==0&&r(0x622e,2)==2);
        w(S+0x206,1,1); w(S+0x1fe,2,0); call(0x8001b590L);
        w(0x46f0,1,2); w(S+0x30a,2,333); call(SHIM);
        check("actual preset pickup",r(0x614a,1)==1&&r(0x613a,2)==333);
        w(0x46f0,1,0); call(SHIM); time(5000); call(TICK);
        check("preset edit stays pending during record",writes==0&&r(0x62e0,1)==1);
        e.writeRegister("R11",0); call(ENTER); call(TICK); settle(6000);
        int length=(int)r(0x61e0,1); check("record exit saved musical data",writes==2);
        cold(); call(SCAN);
        check("no phantom rest or recording on startup",r(0x6158,1)==0&&r(0x61e0,1)==length
            &&r(S+0x20c,4)==1&&r(0x622e,2)==0&&r(0x613a,2)==333);
        println("PASS actual clear gestures (idle/record/play), preset release in record, no phantom restart step");
    }
    public void run() throws Exception {
        String mode=getScriptArgs().length>0?getScriptArgs()[0]:"seq-clock";
        seq=mode.contains("seq"); clock=mode.contains("clock");
        try {
            basic(); retries(); powerCuts(); corruption(); safety(); gestures();
            println("PERSISTENCE REGRESSION PASS: "+mode+", "+checks+" assertions; no physical flash/analog testing.");
        } finally { if(e!=null)e.dispose(); }
    }
}
