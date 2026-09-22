// The settings loader: the RAM mirror the readers point at, filled at boot
// from the image's own tables and overridden by the newer valid record in
// one of two flash slots.  Runs on PersistenceRegression's harness - its
// cold boots, flash model and factory startup - and reads this image's own
// build.properties and settings.bin, so what it compares against is what
// tools/build.py wrote for the very image under emulation.  Never flashes.
// Run via tools/test_persistence.py; see docs/PLAN-SETTINGS.md.
//@category Buchla218.Tests
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32;

public class SettingsRegression extends PersistenceRegression {
    static final long SLOT0=0x8003d000L, SLOT1=0x8003d800L, MIRROR=0x6800, STATE=0x6a70;
    static final long SNEWEST=0x8001f150L, APPLIER=0x80019a40L, REMAP=0x80019980L, CHAIN=0x8001a2e8L;
    static final long PARSER=0x8000831cL, SENDER=0x80008034L, WRITER=0x800108fcL, SETSCAN=0x8001f780L;
    static final long USB=0x34b0, NRPN=0x6a68;
    static final int LEN=0x2a8, PAY=0x20, END=0x288;
    static final String[] NUMBERS={"tie_glide_rate","strip_halfway_units","clock_min_ms",
        "clock_rearm_us","clock_lock_pulses","transpose_cv_period","transpose_cv_zero",
        "transpose_cv_hysteresis","chord_hold_scans","latch_state_hold_scans"};
    static final int[] DEFAULTS={60,2048,4,250,5,123,0,2,300,200};
    Properties props=new Properties();
    byte[] record;
    boolean keepSlots, stubChain, failWrite;
    int slotWrites;
    final List<int[]> sent=new ArrayList<>();

    // The chip's slots are erased flash until something writes them; the
    // imported image has no bytes there, so say so before every boot that
    // is not meant to find a record.
    @Override void cold() throws Exception {
        if(!keepSlots) {
            byte[] ff=new byte[0x800]; Arrays.fill(ff,(byte)255);
            e.writeMemory(toAddr(SLOT0),ff); e.writeMemory(toAddr(SLOT1),ff);
        }
        super.cold();
    }
    // The pitch remap begins by calling the per-scan chain (applier, latch
    // watch, vibrato); the remap's own table read is what is under test.
    // The USB-MIDI sender is recorded rather than run - what the firmware
    // asked to send is the claim - and the factory flash writer, when it is
    // aimed at a settings slot, is modelled at the level persistence has
    // already proved it at: an erase-and-write fills the page with 0xff
    // and copies; a write without erase can only clear bits.
    @Override void step() throws Exception {
        long p=pc();
        if(stubChain&&p==CHAIN) { ret(); return; }
        if(p==SENDER) { sent.add(new int[]{(int)reg("R12"),(int)reg("R11"),(int)reg("R10")}); ret(); return; }
        if(p==WRITER&&reg("R12")>=SLOT0&&reg("R12")<SLOT1+0x800) {
            long dest=reg("R12"), src=reg("R11"), len=reg("R10"), erase=reg("R9");
            check("a settings write stays inside one page",(dest&~511L)==((dest+len-1)&~511L));
            slotWrites++;
            if(!failWrite) {
                if(erase!=0) { byte[] ff=new byte[512]; Arrays.fill(ff,(byte)255); e.writeMemory(toAddr(dest&~511L),ff); }
                byte[] from=e.readMemory(toAddr(src),(int)len), to=e.readMemory(toAddr(dest),(int)len);
                for(int i=0;i<len;i++) to[i]&=from[i];
                e.writeMemory(toAddr(dest),to);
            }
            ret(); return;
        }
        super.step();
    }
    // One USB-MIDI packet into the receive ring, then the factory parser
    // over it - the dispatcher's own drain, one packet at a time.
    void feed(int status,int d1,int d2) throws Exception {
        long idx=r(USB+0x84,4);
        w(USB+4+idx,1,status); w(USB+5+idx,1,d1); w(USB+6+idx,1,d2); w(USB+7+idx,1,0);
        w(USB+0x84,4,idx+4>0x7f?0:idx+4);
        call(PARSER);
    }
    void nrpn(int param,int value) throws Exception {
        feed(0xbf,99,param>>7); feed(0xbf,98,param&0x7f); feed(0xbf,6,value>>7); feed(0xbf,38,value&0x7f);
    }
    // Decode what the firmware sent into (parameter, value) pairs, the
    // page's decoder mirrored: independent bytes, complete on the data LSB.
    List<int[]> decoded() {
        List<int[]> out=new ArrayList<>(); int pm=0,pl=0,dm=0;
        for(int[] m:sent) {
            check2("every packet is a Control Change on channel 16",m[2]==15);
            if(m[0]==99)pm=m[1]; else if(m[0]==98)pl=m[1]; else if(m[0]==6)dm=m[1];
            else if(m[0]==38) out.add(new int[]{(pm<<7)|pl,(dm<<7)|m[1]});
        }
        return out;
    }
    void check2(String name,boolean ok) { if(!ok) throw new RuntimeException("FAIL "+name); }
    // The value the mirror holds for a parameter, worked out here from
    // the map in docs/PLAN-SETTINGS.md rather than by asking the firmware.
    long expect(int param) throws Exception {
        if(param<10) return r(MIRROR+2*param,2);
        if(param>=0x80&&param<0xcf) return r(0x6840+2*(param-0x80),2);
        if(param>=0x100&&param<0x160) return r(0x68e0+2*(param-0x100),2);
        if(param>=0x160&&param<0x163) return r(0x69a0+2*(param-0x160),2);
        if(param>=0x180&&param<0x1e0) {
            int i=param-0x180; long m=r(0x69a8+4*(i/3),2)|(r(0x69aa+4*(i/3),2)<<16);
            return (m>>>(14*(i%3)))&0x3fff;
        }
        if(param>=0x1e0&&param<0x200) return r(0x6a28+2*(param-0x1e0),2);
        return -1;
    }
    void receive() throws Exception {
        fresh();
        long marker=num("init_marker",0)&0xffff;
        nrpn(0,61);
        check("an NRPN on channel 16 writes its number cell",r(0x6800,2)==61);
        nrpn(0,1025);
        check("a number past its bound is ignored, not clamped",r(0x6800,2)==61);
        nrpn(0x83,1234);
        check("a pitch entry lands",r(0x6840+6,2)==1234);
        nrpn(0x83,0x1000);
        check("a pitch entry past the DAC is ignored",r(0x6840+6,2)==1234);
        w(0x60e4,2,0xa5a0); nrpn(0x105,999);
        check("a tuning entry lands and clears the applier's guard",r(0x68e0+10,2)==999&&r(0x60e4,2)==0);
        nrpn(0x161,7);
        check("keys per period land",r(0x69a2,2)==7);
        nrpn(0x161,0); nrpn(0x161,props.getProperty("block.preset_entry","0").trim().equals("1")?33:128);
        check("keys per period outside their bound are ignored",r(0x69a2,2)==7);
        nrpn(0x180,0x1234); nrpn(0x181,0x2abc); nrpn(0x182,0x9);
        long mask=0x1234L|(0x2abcL<<14)|(0x9L<<28);
        check("three thirds make a mask, low halfword first",
            r(0x69a8,2)==(mask&0xffff)&&r(0x69aa,2)==(mask>>>16));
        nrpn(0x181,0x0001);
        check("a third replaces only its own bits",r(0x69a8,2)==0x5234&&r(0x69aa,2)==0x9000);
        nrpn(0x1e0,16);
        check("a length lands",r(0x6a28,2)==16);
        nrpn(0x1e0,33);
        check("a length past 32 is ignored",r(0x6a28,2)==16);
        byte[] before=mirror(); nrpn(0x200,5); nrpn(0x0a,5); nrpn(0x3000,5);
        check("a parameter that names nothing changes nothing",Arrays.equals(before,mirror()));
        // The factory's own Control Change path, in front of which this
        // sits: controller 5 is the portamento time, stored shifted with a
        // flag, on the instrument's channel and on no other.
        w(S+0x2e7,1,2); w(S+0x3a2,2,0); w(S+0x1fc,1,0);
        feed(0xb2,5,40);
        check("controller 5 on the instrument's channel still reaches the factory",
            r(S+0x3a2,2)==(40<<3)&&r(S+0x1fc,1)==1);
        w(S+0x3a2,2,0); w(S+0x1fc,1,0); feed(0xb3,5,40);
        check("and on another channel it does not",r(S+0x3a2,2)==0&&r(S+0x1fc,1)==0);
        w(S+0x2e7,1,15); w(S+0x3a2,2,0);
        feed(0xbf,5,41);
        check("a channel-16 controller that is not NRPN still reaches the factory",
            r(S+0x3a2,2)==(41<<3));
        long state=r(NRPN,4); feed(0xb3,99,7); feed(0xb3,38,7);
        check("NRPN on another channel is not ours",r(NRPN,4)==state);
        check("nothing was sent",sent.isEmpty());
        println("PASS NRPN on channel 16 lands in the mirror, bounded; the factory's path is untouched");
    }
    void commits() throws Exception {
        fresh(); keepSlots=true; slotWrites=0;
        nrpn(0,61); nrpn(0x3f00,0x1111);
        check("a commit needs its key",r(STATE,1)==0);
        nrpn(0x3f00,0x2a2a);
        check("a commit is requested, not done, on receive",r(STATE,1)==1&&slotWrites==0);
        call(SETSCAN);
        check("the scan commits into slot 0: two page writes and the marker",
            r(STATE,1)==2&&r(STATE+1,1)==0&&r(STATE+4,4)==1&&slotWrites==3);
        long p=call(SNEWEST);
        check("the record is the newest and carries the edit",p==SLOT0&&r(SLOT0+0x20,2)==61&&r(SLOT0+8,4)==1);
        call(SETSCAN);
        check("a scan without a request writes nothing",slotWrites==3);
        nrpn(0,62); nrpn(0x3f00,0x2a2a); call(SETSCAN);
        check("the next commit takes the other slot and the next generation",
            r(STATE,1)==2&&r(STATE+1,1)==1&&r(STATE+4,4)==2&&call(SNEWEST)==SLOT1&&slotWrites==6);
        check("slot 0 still holds the previous record",r(SLOT0+0x20,2)==61&&r(SLOT0,4)==0x32313853L);
        w(0x6800,2,0); cold();
        check("a power cycle boots the committed record",r(0x6800,2)==62&&r(STATE+1,1)==1&&r(STATE+4,4)==2);
        failWrite=true; nrpn(0,63); nrpn(0x3f00,0x2a2a); call(SETSCAN);
        check("a write that does not take leaves state 3 and the old record newest",
            r(STATE,1)==3&&call(SNEWEST)==SLOT1&&r(SLOT1+0x20,2)==62&&r(0x6800,2)==63);
        failWrite=false; nrpn(0x3f00,0x2a2a); call(SETSCAN);
        check("the next request tries again",r(STATE,1)==2&&r(STATE+1,1)==0&&r(STATE+4,4)==3&&r(SLOT0+0x20,2)==63);
        nrpn(0x3f01,0); w(0x6800,2,0); nrpn(0x3f01,0);
        check("0x3f01 reloads the mirror from flash",r(0x6800,2)==63&&r(STATE,1)==0);
        nrpn(0x3f02,0);
        check("0x3f02 puts the image's own settings back",Arrays.equals(mirror(),payloadOf(record)));
        keepSlots=false;
        println("PASS commit: alternating slots, generations, a failed write, reload and defaults");
    }
    void dumps() throws Exception {
        fresh(); keepSlots=true; sent.clear();
        nrpn(0x180,0x1234); nrpn(0x181,0x2abc); nrpn(0x182,0x9); nrpn(0x1e5,9); nrpn(0x3,77);
        nrpn(0x3f03,0);
        int scans=0, most=0;
        while(r(NRPN+4,2)!=0x4000&&scans<400) { int before=sent.size(); call(SETSCAN); scans++; most=Math.max(most,sent.size()-before); }
        check("a dump ends by itself",r(NRPN+4,2)==0x4000);
        check("at most eight packets a scan",most<=8);
        List<int[]> got=decoded();
        check("316 parameters and the ten of the identity block: "+got.size(),got.size()==326);
        boolean order=true, values=true;
        int[] walk=new int[316]; int n=0;
        for(int q=0;q<10;q++)walk[n++]=q; for(int q=0x80;q<0xcf;q++)walk[n++]=q; for(int q=0x100;q<0x163;q++)walk[n++]=q;
        for(int q=0x180;q<0x200;q++)walk[n++]=q;
        for(int i=0;i<316;i++) { if(got.get(i)[0]!=walk[i])order=false; if(got.get(i)[1]!=expect(walk[i]))values=false; }
        check("in the instrument's order",order);
        check("every value is the mirror's",values);
        long marker=num("init_marker",0)&0xffff;
        check("the identity block follows: firmware version, marker top bits, period, slot, state, generation, marker, layout version",
            got.get(316)[0]==0x3f76&&got.get(316)[1]==num("firmware_version_code",0x300)
            &&got.get(317)[0]==0x3f77&&got.get(317)[1]==(marker>>14)
            &&got.get(318)[0]==0x3f78&&got.get(318)[1]==num("octave_units",484)
            &&got.get(319)[0]==0x3f79&&got.get(319)[1]==0xff
            &&got.get(320)[0]==0x3f7a&&got.get(320)[1]==0
            &&got.get(321)[0]==0x3f7b&&got.get(322)[0]==0x3f7c&&got.get(323)[0]==0x3f7d
            &&got.get(324)[0]==0x3f7e&&got.get(324)[1]==(marker&0x3fff)
            &&got.get(325)[0]==0x3f7f&&got.get(325)[1]==1);
        // The generation in three parts, after a commit gave it one.
        sent.clear(); nrpn(0x3f00,0x2a2a); call(SETSCAN);
        w(STATE+4,4,0x12345678L);
        nrpn(0x3f7f,0); while(r(NRPN+4,2)!=0x4000) call(SETSCAN);
        got=decoded();
        check("an identity request sends the ten alone",got.size()==10);
        long gen=got.get(7)[1]|((long)got.get(6)[1]<<14)|((long)got.get(5)[1]<<28);
        check("the generation rides in three parts",gen==0x12345678L&&got.get(4)[1]==2&&got.get(3)[1]==0);
        // A marker past 0x3fff needs its top bits: check the split, not the luck of this build's marker.
        check("the marker rides in two parts",(got.get(1)[1]<<14|got.get(8)[1])==marker);
        check("the firmware version leads the block",got.get(0)[0]==0x3f76&&got.get(0)[1]==num("firmware_version_code",0x300));
        keepSlots=false;
        println("PASS dump: every parameter, paced, in order, then the identity block");
    }
    byte[] mirror() { return e.readMemory(toAddr(MIRROR),END-PAY); }
    static byte[] payloadOf(byte[] rec) { return Arrays.copyOfRange(rec,PAY,END); }
    static void stamp(byte[] rec) {
        CRC32 c=new CRC32(); c.update(rec,4,8); c.update(rec,0x10,LEN-0x10);
        long v=c.getValue(); for(int i=0;i<4;i++) rec[12+i]=(byte)(v>>>(24-8*i));
    }
    static void setGen(byte[] rec,long g) { for(int i=0;i<4;i++) rec[8+i]=(byte)(g>>>(24-8*i)); }
    static void setHalf(byte[] rec,int off,int v) { rec[off]=(byte)(v>>>8); rec[off+1]=(byte)v; }
    static int half(byte[] b,int off) { return ((b[off]&255)<<8)|(b[off+1]&255); }
    void plant(long slot,byte[] rec) { e.writeMemory(toAddr(slot),rec); }
    int num(String name,int fallback) {
        String v=props.getProperty("number."+name,"").trim();
        return v.isEmpty()?fallback:Integer.parseInt(v);
    }
    // The build's record with a value changed in every section, so a load
    // that copied only part of it, or from the wrong offset, shows.
    byte[] edited() {
        byte[] rec=record.clone();
        setHalf(rec,0x20,61);                          // tie_glide_rate
        setHalf(rec,0x24,2);                           // clock_min_ms
        setHalf(rec,0x2a,700);                         // transpose_cv_period
        setHalf(rec,0x60+2*3,half(rec,0x60+2*3)+5);    // pitch entry 3
        setHalf(rec,0x100,777);                        // tuning slot 0, key 0
        setHalf(rec,0x180+2*5,1234);                   // tuning slot 2, key 5
        setHalf(rec,0x1c4,7);                          // keys per period, slot 2
        setHalf(rec,0x1c8,0x1234); setHalf(rec,0x248,16); // a mask and a length
        setGen(rec,3); stamp(rec); return rec;
    }
    void defaults() throws Exception {
        fresh();
        check("a fresh boot mirrors the record the build serialized",
            Arrays.equals(mirror(),payloadOf(record)));
        for(int i=0;i<NUMBERS.length;i++)
            check("cell "+i+" holds "+NUMBERS[i],r(MIRROR+2*i,2)==num(NUMBERS[i],DEFAULTS[i]));
        check("nothing loaded: state clean, no slot, generation zero",
            r(STATE,1)==0&&r(STATE+1,1)==0xff&&r(STATE+4,4)==0);
        check("the pitch curve is the emitted one",
            Arrays.equals(e.readMemory(toAddr(0x6840),158),e.readMemory(toAddr(0x80019bc0L),158)));
        check("the tuning tables are the emitted ones",
            Arrays.equals(e.readMemory(toAddr(0x68e0),192),e.readMemory(toAddr(0x80019af8L),192)));
        check("keys per period are the emitted ones",
            Arrays.equals(e.readMemory(toAddr(0x69a0),6),e.readMemory(toAddr(0x8001e2d0L),6)));
        check("settings_newest finds nothing in erased slots",call(SNEWEST)==0);
        println("PASS a fresh boot mirrors the image's own tables and numbers");
    }
    void loads() throws Exception {
        fresh(); byte[] rec=edited(); plant(SLOT0,rec); keepSlots=true; cold();
        check("a valid record in slot 0 overrides the mirror",Arrays.equals(mirror(),payloadOf(rec)));
        check("state names slot 0 and generation 3",
            r(STATE,1)==0&&r(STATE+1,1)==0&&r(STATE+4,4)==3);
        // The readers: the applier copies a slot's table out of the mirror
        // into RAM 0x854 when its guard is clear.
        w(0x6090,1,2); w(0x60e4,2,0); call(APPLIER);
        check("the applier copies slot 2 out of the mirror",r(0x854+2*5,2)==1234);
        w(0x6090,1,0); w(0x60e4,2,0); call(APPLIER);
        check("and slot 0",r(0x854,2)==777);
        // The pitch remap interpolates between neighbours, so a flat mirror
        // curve remaps every raw pitch to that one value.
        for(int i=0;i<79;i++) w(0x6840+2*i,2,1000);
        w(0x6028,2,0); e.writeRegister("R12",0x300); stubChain=true; call(REMAP); stubChain=false;
        check("the pitch remap reads the mirror",r(0x3212,2)==1000);
        for(int i=0;i<79;i++) w(0x6840+2*i,2,0);
        boot();
        check("a warm reset reloads the record",Arrays.equals(mirror(),payloadOf(rec)));
        if(clock) {
            long perMs=r(0x6244,4);
            check("clock_init scales clock_min_ms from the mirror",perMs!=0&&r(0x624c,4)==perMs*2);
        }
        if(seq) {
            // seq_glide, playing with a tie in hand and the knob up: the
            // rate it stores is the tie's, off cell 0.
            w(0x6158,1,2); w(0x3866,2,0x100); w(0x61e5,1,1); call(0x8001b610L);
            check("seq_glide takes the tie rate from the mirror",r(0x2eee,2)==61);
            w(0x6158,1,0); w(0x61e5,1,0);
        }
        keepSlots=false;
        println("PASS a record loads, the readers read the mirror, a warm reset reloads");
    }
    void rejections() throws Exception {
        fresh(); byte[] good=edited(); keepSlots=true;
        // offset, width, value: each one thing the validator refuses, with
        // the CRC restamped so only that check can be the reason.
        // Keys per period: 33 is past the rotation's table, 128 past a .kbm.
        long keys=props.getProperty("block.preset_entry","0").trim().equals("1")?33:128;
        long[][] bad={{0,1,0xff},{4,2,2},{6,2,0x299},{8,4,0},{0x10,2,0x1234},{0x12,2,485},
            {0x20,2,0},{0x20,2,1025},{0x24,2,5},{0x2e,2,65},{0x60,2,0x1000},{0xfe,2,0x1000},
            {0x100,2,0x1000},{0x1c0,2,0},{0x1c4,2,keys},{0x248,2,33}};
        for(long[] b:bad) {
            byte[] rec=good.clone();
            for(int i=0;i<b[1];i++) rec[(int)b[0]+i]=(byte)(b[2]>>>(8*(b[1]-1-i)));
            stamp(rec); plant(SLOT0,rec); cold();
            check("rejected: "+b[1]+" byte(s) at 0x"+Long.toHexString(b[0])+" = "+b[2],
                Arrays.equals(mirror(),payloadOf(record))&&r(STATE+1,1)==0xff);
        }
        byte[] rec=good.clone(); rec[0x61]^=1; plant(SLOT0,rec); cold();
        check("rejected: a flipped payload bit",r(STATE+1,1)==0xff);
        plant(SLOT0,good); cold();
        check("and the good record loads again",r(STATE+1,1)==0);
        keepSlots=false;
        println("PASS header, CRC, image, period and every bound refuse a record");
    }
    void slots() throws Exception {
        fresh(); keepSlots=true;
        byte[] a=edited(); setGen(a,4); stamp(a);
        byte[] b=edited(); setHalf(b,0x20,62); setGen(b,5); stamp(b);
        plant(SLOT0,a); plant(SLOT1,b); cold();
        check("the newer generation wins, in slot 1",
            r(0x6800,2)==62&&r(STATE+1,1)==1&&r(STATE+4,4)==5);
        setGen(a,6); stamp(a); plant(SLOT0,a); cold();
        check("and in slot 0",r(0x6800,2)==61&&r(STATE+1,1)==0&&r(STATE+4,4)==6);
        setGen(a,0xffffffffL); stamp(a); setGen(b,1); stamp(b); plant(SLOT0,a); plant(SLOT1,b); cold();
        check("generation wraps: 1 is newer than 0xffffffff",r(STATE+1,1)==1&&r(STATE+4,4)==1);
        b[0x61]^=1; plant(SLOT1,b); cold();
        check("a corrupt newer slot falls back to the older valid one",
            r(STATE+1,1)==0&&r(STATE+4,4)==0xffffffffL);
        keepSlots=false;
        println("PASS two slots: the newest wins, across the wrap, and the older stands in");
    }
    @Override public void run() throws Exception {
        String[] args=getScriptArgs();
        String mode=args[0]; seq=mode.contains("seq"); clock=mode.contains("clock");
        props.load(Files.newBufferedReader(Paths.get(args[1])));
        record=Files.readAllBytes(Paths.get(args[2]));
        try {
            defaults(); loads(); rejections(); slots(); receive(); commits(); dumps();
            println("SETTINGS REGRESSION PASS: "+mode+", "+checks+" assertions; no physical flash testing.");
        } finally { if(e!=null)e.dispose(); }
    }
}
