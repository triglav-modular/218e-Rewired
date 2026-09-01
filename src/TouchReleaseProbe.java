// Investigation probe: on the first boot after a DFU, touching a key stages
// one pitch and RELEASING it stages a different one; a power cycle fixes
// both.  Model both boots, run the same touch/release gesture, diff the
// staged target and DAC word, and print the cells the release path leans on.
// Usage: -postScript TouchReleaseProbe.java
//@category Buchla218.Tests
import java.util.*;

public class TouchReleaseProbe extends ControlRegression {

    void bench() throws Exception {
        w(S+0x342,1,1); w(S+0x343,1,0); w(S+0x344,4,2); w(S+0x2ef,1,1);
        w(S+0x340,1,0); w(S+0x341,1,0);          // arp off, as reported
        w(S+0x306,2,0); w(S+0x30a,2,0); w(S+0x30c,2,0);
        w(S+0x30e,2,0); w(S+0x310,2,0);
    }

    void scans(int n) throws Exception {
        for(int i=0;i<n;i++) { controlScan(); musicalScan(); }
    }

    String pitchState(String tag) throws Exception {
        long own9=r(0x6521+9,1);
        long w=own9>0 ? r(0x6540+2*(own9-1),2) : -1;
        long pin=own9>0 ? r(0x6504+(own9-1),1) : -1;
        return tag+" t352="+(short)r(S+0x352,2)+" dac358="+(short)r(S+0x358,2)
             +" base350="+(short)r(S+0x350,2)+" heldN="+r(S+0x21a,1)
             +" blendT="+(short)r(0x60e0,2)+" applied="+(short)r(0x60e2,2)
             +" own9="+own9+" slotW="+w+" pin="+pin
             +" cache9="+r(0x6100+18,2)+" latchMir="+r(0x608e,1)
             +" mem60ef="+r(0x60ef,1)+" lastArp34d="+r(S+0x34d,1)
             +" flag9="+r(S+0x21b+9,1)+" touch9="+r(S+0x239+9,1);
    }

    String gesture(String tag) throws Exception {
        bench(); scans(8);
        w(S+0x306,2,900);                // the portamento knob is UP - the report's context
        scans(4);
        touchOn(9);                      // the factory contact path, note-on included
        w(0x6100+2*9,2,600);             // a real finger: pressure in the cache
        scans(8);
        String touchKey=(short)r(S+0x352,2)+"/"+(short)r(S+0x358,2)
                       +"/"+(short)r(0x60e0,2);
        println(pitchState(tag+" touched "));
        w(0x6100+2*9,2,0);               // the finger lifts
        touchOff(9);                     // the factory lift path
        scans(8);
        String relKey=(short)r(S+0x352,2)+"/"+(short)r(S+0x358,2);
        println(pitchState(tag+" released"));
        return touchKey+" | "+relKey;
    }

    byte[] junk, snap;

    void dirtyBoot() throws Exception {
        e.writeMemory(toAddr(0x3000), junk);
        e.writeMemory(toAddr(8), e.readMemory(toAddr(0x80015d28L), 0x2ecc));
        w(0x2ed4,4,0xffffffffL);
        w(0x602a,2,0);
        w(0x2ee6,2,1023);
        call(0x80007bf4L,0x80007bf8L);
        call(0x8001ab60L);
        w(S+0x238,1,0);
        for(int k=0;k<29;k++) w(S+0x239+k,1,0);   // no fingers at boot
        w(S+0x21a,1,0);
    }

    void restore(int from,int to) throws Exception {
        byte[] part=new byte[to-from];
        System.arraycopy(snap, from-0x3000, part, 0, to-from);
        e.writeMemory(toAddr(from), part);
    }

    public void run() throws Exception {
        setup(0,false,0);
        // A settled clean baseline, snapshotted just before its gesture.
        bench(); scans(8);
        snap=e.readMemory(toAddr(0x3000),0x5000);
        String clean=gesture("CLEAN");
        java.util.Random rng=new java.util.Random(218);
        junk=new byte[0x5000]; rng.nextBytes(junk);
        dirtyBoot();
        String dirty=gesture("DIRTY");
        println((clean.equals(dirty) ? "MATCH" : "DIVERGENCE")
                +": clean "+clean+"  dirty "+dirty);
        println("TOUCH RELEASE PROBE DONE");
    }
}
