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
    static final int LEN=0x2a8, PAY=0x20, END=0x288;
    static final String[] NUMBERS={"tie_glide_rate","strip_halfway_units","clock_min_ms",
        "clock_rearm_us","clock_lock_pulses","transpose_cv_period","transpose_cv_zero",
        "transpose_cv_hysteresis","chord_hold_scans","latch_state_hold_scans"};
    static final int[] DEFAULTS={60,2048,4,250,5,123,0,2,300,200};
    Properties props=new Properties();
    byte[] record;
    boolean keepSlots, stubChain;

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
    @Override void step() throws Exception {
        if(stubChain&&pc()==CHAIN) { ret(); return; }
        super.step();
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
            defaults(); loads(); rejections(); slots();
            println("SETTINGS REGRESSION PASS: "+mode+", "+checks+" assertions; no physical flash testing.");
        } finally { if(e!=null)e.dispose(); }
    }
}
