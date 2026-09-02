// Investigation probe: with the arp off and the portamento knob up, a single
// key played AFTER key 1 has been pressed and released sounds sharp.  Model
// the gesture the owner described - key 1, release, another key alone - and
// print the cells the blend's anchor leans on, next to the same key played
// on a fresh instrument.
// Usage: -postScript BlendAnchorProbe.java
//@category Buchla218.Tests
import java.util.*;

public class BlendAnchorProbe extends ControlRegression {

    void bench() throws Exception {
        w(S+0x342,1,1); w(S+0x343,1,0); w(S+0x344,4,2); w(S+0x2ef,1,1);
        w(S+0x340,1,0); w(S+0x341,1,0);          // arp off
        w(S+0x306,2,0); w(S+0x30a,2,0); w(S+0x30c,2,0);
        w(S+0x30e,2,0); w(S+0x310,2,0);
    }

    void scans(int n) throws Exception {
        for(int i=0;i<n;i++) { controlScan(); musicalScan(); }
    }

    void press(int k,int raw) throws Exception {
        touchOn(k);                      // the factory contact path, note-on included
        w(0x3490+k,1,2); w(0x3686+2*k,2,raw);
        call(0x8001aa10L);               // the pressure cache pass
    }

    void lift(int k) throws Exception {
        w(0x3490+k,1,0); w(0x3686+2*k,2,110);
        call(0x8001aa10L);
        touchOff(k);                     // the factory lift path
    }

    String state(String tag) throws Exception {
        return tag+" base350="+(short)r(S+0x350,2)+" t352="+(short)r(S+0x352,2)
             +" dac358="+(short)r(S+0x358,2)
             +" blendT="+(short)r(0x60e0,2)+" applied="+(short)r(0x60e2,2)
             +" lastArp34d="+r(S+0x34d,1)
             +" own0="+r(0x6521,1)+" own9="+r(0x6521+9,1)
             +" held0="+r(S+0x21b,1)+" held9="+r(S+0x21b+9,1)
             +" cache0="+r(0x6100,2)+" cache9="+r(0x6100+18,2)
             +" table0="+r(0x854,2)+" table9="+r(0x854+18,2);
    }

    public void run() throws Exception {
        setup(0,false,0);
        bench(); scans(8);
        w(S+0x306,2,900);                // portamento knob up
        scans(4);
        println(state("FRESH idle    "));
        press(9,900); scans(60);
        println(state("FRESH key9    "));
        long freshTarget=r(S+0x352,2), freshApplied=(short)r(0x60e2,2);
        lift(9); scans(30);
        println(state("FRESH lifted  "));

        press(0,900); scans(30);
        println(state("KEY1 held     "));
        lift(0); scans(30);
        println(state("KEY1 lifted   "));
        press(9,900); scans(60);
        println(state("AFTER key9    "));
        long afterTarget=r(S+0x352,2), afterApplied=(short)r(0x60e2,2);
        lift(9); scans(30);
        println(state("AFTER lifted  "));

        // The chord the owner did not report: key 1 still down under a second key.
        press(0,900); scans(10); press(9,900); scans(60);
        println(state("CHORD 1+9     "));
        lift(9); lift(0); scans(30);

        println((freshTarget==afterTarget&&freshApplied==afterApplied ? "MATCH" : "DIVERGENCE")
                +": fresh key 9 target/applied "+freshTarget+"/"+freshApplied
                +"  after key 1: "+afterTarget+"/"+afterApplied);
        println("BLEND ANCHOR PROBE DONE");
    }
}
