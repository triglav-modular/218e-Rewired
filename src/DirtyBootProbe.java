// Investigation probe: the first boot after a DFU inherits whatever the
// bootloader left in SRAM above 0x3000.  Owner: on that boot, raising the
// portamento knob steps the RESTING CV up by one fixed amount; lowering it
// brings the CV back; a power cycle cures it.  Arp off, nothing touched.
// Full pipeline: 1 kHz bank/service/flush + 5 ms ADC pass and pitch scan.
// Usage: -postScript DirtyBootProbe.java
//@category Buchla218.Tests
import java.util.*;

public class DirtyBootProbe extends ClockRegression {

    void bench() throws Exception {
        // What the hardware rescans every pass, at the bench's positions:
        // ADD TO PITCH on octaves, pad 2, every knob down, arp off, no touch.
        w(S+0x342,1,1); w(S+0x343,1,0); w(S+0x344,4,2); w(S+0x2ef,1,1);
        w(S+0x340,1,0); w(S+0x341,1,0);
        w(S+0x306,2,0); w(S+0x30a,2,0); w(S+0x30c,2,0);
        w(S+0x30e,2,0); w(S+0x310,2,0);
        w(S+0x238,1,0);
        for(int k=0;k<29;k++) w(S+0x239+k,1,0);
    }

    String cells() throws Exception {
        int flags=0; for(int k=0;k<29;k++) flags+=r(S+0x21b+k,1)==1?1:0;
        return "t352="+(short)r(S+0x352,2)+" dac358="+(short)r(S+0x358,2)
             +" blendT="+(short)r(0x60e0,2)+" applied="+(short)r(0x60e2,2)
             +" f6="+(short)r(0x60f6,2)+" f8="+(short)r(0x60f8,2)
             +" t60a0="+(short)r(0x60a0,2)+" heldN="+r(S+0x21a,1)
             +" flags="+flags+" base350="+(short)r(S+0x350,2)
             +" bend216="+(short)r(S+0x216,2)+" glide2eee="+(short)r(0x2eee,2)
             +" mode="+r(0x6158,1);
    }

    void run2s(long from) throws Exception {
        for (long t=from; t<from+2000000; t+=1000) {
            bank(t); service(t); flush(t);
            if (t%5000==0) { time(t); call(0x80003590L,0x100); scan(t); }
        }
    }

    public void run() throws Exception {
        sequencer=false; fresh(1,25000000);
        // The DFU model: bootloader-scribbled SRAM above 0x3000, .data fresh.
        java.util.Random rng=new java.util.Random(218);
        byte[] junk=new byte[0x5000]; rng.nextBytes(junk);
        e.writeMemory(toAddr(0x3000), junk);
        w(GPIO+0x60,4,0); w(GPIO+0xd0,4,0);
        call(0x80007bf4L,0x80007bf8L);
        call(0x8000737eL,0x80007386L);
        w(GPIO+0xc4,4,32); w(0xffff1c08L,4,1);
        w(0xffff2404L,4,0); w(0xffff2410L,4,0x202);
        w(S+0x2fc,2,0); w(0x2ee0,2,20); w(0x2ee6,2,1023);
        bench();
        run2s(10000);
        println("BOOT SETTLED "+cells());
        long t0=r(S+0x352,2), d0=r(S+0x358,2);
        w(S+0x306,2,900);
        run2s(2100000);
        println("KNOB UP      "+cells());
        long t1=r(S+0x352,2), d1=r(S+0x358,2);
        w(S+0x306,2,0);
        run2s(4300000);
        println("KNOB DOWN    "+cells());
        long t2=r(S+0x352,2), d2=r(S+0x358,2);
        println((t1==t0&&d1==d0 ? "NO SHIFT" : "SHIFT REPRODUCED")
                +": target "+t0+"->"+t1+"->"+t2+" dac "+d0+"->"+d1+"->"+d2);
        println("DIRTY BOOT PROBE DONE");
    }
}
