// Execute the emitted firmware, including the GPIO ISR and output hook.
// Run with tools/test_clock.py. No board is accessed.
//@category Buchla218.Tests
import ghidra.app.script.GhidraScript;
import ghidra.app.emulator.EmulatorHelper;
import java.util.ArrayList;
import java.util.List;

public class ClockRegression extends GhidraScript {
    static final long S = 0x3560, GPIO = 0xffff1000L;
    EmulatorHelper e;
    int frequency, advances, periodicAdvances, checks, maxIrqSteps, dispatches, callbacks;
    boolean sequencer, periodic;
    long nowUs;
    final List<Integer> pitches = new ArrayList<>();
    final List<Integer> dac = new ArrayList<>();
    final List<Long> outputTimes = new ArrayList<>();

    long pc() { return e.getExecutionAddress().getOffset(); }
    void jump(long p) { e.writeRegister(e.getPCRegister(), p); }
    void w(long a, int n, long v) { e.writeMemoryValue(toAddr(a), n, v); }
    long r(long a, int n) {
        long v = 0;
        for (byte b : e.readMemory(toAddr(a), n)) v = (v << 8) | (b & 255);
        return v;
    }
    void check(String name, boolean ok) throws Exception {
        checks++;
        if (!ok) throw new Exception("FAIL " + name + " at us=" + nowUs
            + " advances=" + advances + " outputs=" + outputTimes.size()
            + " head/tail=" + r(0x6234,1) + "/" + r(0x6235,1)
            + " busy=" + r(0x6237,1) + " pending=" + r(0x60ee,1)
            + " period=" + r(0x61ea,2) + " run=" + r(0x61ec,1));
    }
    void step() throws Exception {
        if (pc() == 0x800021e8L) throw new Exception("executed clock-gate literal");
        // Hardware-only boundaries: interrupt-controller registration, SPI
        // transfer, and physical pulse/timer peripherals. Gate-low state
        // writes, selectors, period/division and pitch remap execute normally.
        if (pc() == 0x80007340L) { jump(e.readRegister("LR").longValue()); return; }
        // The main-loop hook must chain to the factory dispatcher once. Its
        // event-17 and pitch-store paths are driven separately below.
        if (pc() == 0x80004c64L) { dispatches++; jump(e.readRegister("LR").longValue()); return; }
        if (pc() == 0x800076b0L) {
            check("1 ms hook preserves task argument", e.readRegister("R12").longValue()==0x7010);
            callbacks++; jump(e.readRegister("LR").longValue()); return;
        }
        if (pc() == 0x80002456L) { jump(0x8000245aL); return; }
        if (pc() == 0x80007572L) { jump(0x80007576L); return; }
        if (pc() == 0x800077f8L) {
            pitches.add((int)r(0x61e2,2));
            dac.add((int)r(S+0x358,2));
            outputTimes.add(nowUs);
            w(S+0x354,2,0xfff);
            jump(e.readRegister("LR").longValue());
            return;
        }
        if (pc() == 0x8001c914L) advances++;
        if (pc() == 0x800022deL && periodic) periodicAdvances++;
        // Installed AVR32 SLEIGH lacks the branch p-code for MOV PC,Rs.
        int ins = (int)r(pc(),2);
        if ((ins & 0xe1ff) == 0x009f) {
            int n = (ins >> 9) & 15;
            jump(e.readRegister(n==13 ? "SP" : n==14 ? "LR" : n==15 ? "PC" : "R"+n).longValue());
            return;
        }
        if (!e.step(monitor)) throw new Exception("PC=" + Long.toHexString(pc()) + " " + e.getLastError());
    }
    int call(long entry, long end) throws Exception {
        e.writeRegister("SP",0x7800);
        e.writeRegister("LR",0x100);
        e.writeRegister("R7",0x7600);
        jump(entry);
        for (int i=0; i<20000; i++) {
            if (pc()==end) return i;
            step();
        }
        throw new Exception("instruction budget at " + Long.toHexString(pc()));
    }
    void time(long us) {
        nowUs=us;
        e.writeRegister("COUNT", ((us * frequency) / 1000000L) & 0xffffffffL);
        // The 1 ms scheduled task increments 0x61e6 on hardware; the release
        // is timed against it (not COUNT), so the model must advance it too.
        w(0x61e6,2,(us/1000L)&0xffffL);
    }
    void fresh(int divisor, int hz) throws Exception {
        if (e != null) e.dispose();
        e = new EmulatorHelper(currentProgram);
        e.writeMemory(toAddr(0),new byte[0x8000]);
        e.writeMemory(toAddr(8),e.readMemory(toAddr(0x80015d28L),0x2ecc));
        w(0x2ed4,4,0xffffffffL);
        for (int i=0;i<=12;i++) e.writeRegister("R"+i,0);
        e.writeRegister("SR",0);
        for (String f : new String[]{"C","N","V","Z"}) e.writeRegister(f,0);
        frequency=hz; time(0);
        w(0x29cc,4,hz); w(GPIO+0x60,4,0); w(GPIO+0xd0,4,0);
        call(0x80007bf4L,0x80007bf8L); // actual startup hook/pool
        check("CPU-frequency-derived timebase", r(0x6244,4)==hz/1000);
        check("clock RAM initialized", r(0x6234,4)==0 && r(0x6258,2)==0);
        // Run the real mode-setting call (not interrupt-controller setup).
        call(0x8000737eL,0x80007386L);
        check("both edge mode explicitly selected", r(GPIO+0xa8,4)==32 && r(GPIO+0xb8,4)==32);
        w(S+0x340,1,1); w(S+0x21a,1,1); w(S+0x21b,1,1);
        w(S+0x34a,2,20); w(S+0x38e,2,100);
        // Divisor 1 is written OVER-RANGE on purpose: the raw channel reads
        // past 0x3ff at the top of the knob (the factory clamps it at every
        // read, 0x800079e0), and unclamped that made the divisor negative-
        // huge and silenced /1 on the instrument while /2../8 played on.
        w(S+0x2fc,2,divisor==1?0x420:1023-(divisor-1)*128);
        w(0x2ee0,2,20); w(0x2ee6,2,1023);
        if (sequencer) {
            w(0x6158,1,2); w(0x61e0,1,16);
            for (int k=0;k<16;k++) { w(0x6160+2*k,2,485+40*k); w(0x61ee+k,1,k); }
        } else {
            // A held key must also be present in the press-order list, just
            // as the real note-on wrapper appends it before arp selection.
            e.writeRegister("R12",0); call(0x8001a020L,0x100);
        }
        w(GPIO+0xc4,4,32); w(0xffff1c08L,4,1);
        w(0xffff2404L,4,0); w(0xffff2410L,4,0x202); // SPI TX ready/empty
        advances=0; periodicAdvances=0; periodic=false;
        pitches.clear(); dac.clear(); outputTimes.clear();
    }
    void irq(long us, boolean high) throws Exception {
        time(us);
        w(GPIO+0x60,4,high?32:0); w(GPIO+0xd0,4,32);
        // Stop at RETE: hardware's exception frame is not synthesized here.
        maxIrqSteps=Math.max(maxIrqSteps,call(0x800072e4L,0x80007328L));
        check("ISR restores its stack", e.readRegister("SP").longValue()==0x7800);
        check("ISR acknowledged pin 5", r(GPIO+0xd8,4)==32);
        w(GPIO+0xd0,4,0); // model write-one-to-clear hardware
    }
    void service(long us) throws Exception {
        time(us); int before=dispatches;
        call(0x80007c66L,0x80007c6aL); // actual main-loop hook/pool
        check("clock service chains to dispatcher once", dispatches==before+1);
    }
    void bank(long us) throws Exception {
        time(us); int before=callbacks;
        e.writeRegister("R12",0x7010);
        call(r(0x80007da0L,4),0x100); // real 1 ms callback pointer
        check("1 ms callback chained once", callbacks==before+1);
    }
    void scan(long us) throws Exception {
        time(us);
        // Zero-portamento fixture: supply the selected pitch to the real
        // remap/DAC-slot/output hook. The factory floating-point glide and
        // analog RC are not modeled by this clock regression.
        w(0x3210,2,r(S+0x352,2));
        call(0x80003236L,0x80003256L);
    }
    void internal(long us) throws Exception {
        time(us); periodic=true;
        call(0x80004f66L,0x80004faeL);
        periodic=false;
    }
    void abiAndNoise() throws Exception {
        fresh(1,25000000);
        e.writeRegister("R0",0x685b); e.writeRegister("R1",0x12345678);
        irq(10000,true); service(10000); scan(15000);
        check("no literal-pool register corruption", e.readRegister("R1").longValue()==0x12345678);
        check("callee-saved R0 preserved", e.readRegister("R0").longValue()==0x685b);
        check("one actual note/trigger", advances==1 && outputTimes.size()==1);
        w(S+0x354,2,0xfff);
        for (int i=0;i<12;i++) {
            irq(20000+i*2000,true); service(nowUs);
            check("unqualified high cannot clear gate", r(S+0x354,2)==0xfff);
        }
        for (int i=0;i<12;i++) {
            irq(50000+i*2000,false); irq(50100+i*2000,true); service(nowUs);
        }
        check("no fail-open after repeated chatter", advances==1 && outputTimes.size()==1);
        irq(80000,false); irq(81000,true); service(81000); scan(85000);
        check("fresh qualified low recovers immediately", advances==2 && outputTimes.size()==2);
        irq(85100,false); irq(85200,true); service(85200);
        check("low interval cannot be reused", advances==2);
        // Event 10 is no longer an unqualified ingress.
        call(0x80004e58L,0x800051b0L);
        check("synthetic legacy event cannot step", advances==2);
        for (long sr : new long[]{0,0x10000}) {
            e.writeRegister("SR",sr); service(86000);
            check("caller interrupt mask preserved", (e.readRegister("SR").longValue()&0x10000)==sr);
        }
        e.writeRegister("SR",0);
        println("PASS ABI, IRQ gate ownership, chatter, spent lows, SR");
    }
    void square(int hz, double duty, int phase) throws Exception {
        fresh(1,25000000);
        long period=1000000L/hz;
        long rise=10000+phase, fall=rise+Math.round(1000000.0/hz*duty);
        int edges=0;
        long until=10000+phase+1000000L;
        for (long tick=10000; tick<=until+20000; tick+=1000) {
            while (Math.min(rise,fall)<=tick && Math.min(rise,fall)<until) {
                if (rise<=fall) {
                    irq(rise,true); edges++;
                    rise=10000+phase+Math.round(edges*1000000.0/hz);
                } else {
                    irq(fall,false);
                    fall=10000+phase+Math.round((edges-1+duty)*1000000.0/hz);
                    if (fall<=nowUs) fall=10000+phase+Math.round((edges+duty)*1000000.0/hz);
                }
            }
            bank(tick); service(tick); internal(tick);
            if (tick%5000==0) scan(tick);
            if (!outputTimes.isEmpty() && tick-outputTimes.get(outputTimes.size()-1)<3000)
                check("range attack survives first 3 ms", r(S+0x354,2)==0xfff);
        }
        check("range "+hz+" Hz duty="+duty+" phase="+phase+" edges="+edges,
              edges==hz && advances==edges && outputTimes.size()==edges && periodicAdvances==0 && r(0x6258,2)==0);
        if (sequencer) for (int i=0;i<edges;i++)
            check("ordered pitch at output "+i, pitches.get(i)==485+40*(i%16));
        println("PASS range "+hz+" Hz duty="+duty+" phase="+phase+" inputs/outputs="+edges+"/"+outputTimes.size());
    }
    void dispatchJitter() throws Exception {
        fresh(1,25000000);
        irq(1004000,true); service(1004000); scan(1005000);
        irq(1006500,false); irq(1009000,true);
        scan(1010000); service(1011000);
        internal(1011000);
        irq(1011500,false); internal(1012000); internal(1013000);
        irq(1014000,true); service(1014000); internal(1014000);
        scan(1015000); internal(1015000);
        check("factory countdown cannot cut a just-emitted attack", r(S+0x354,2)==0xfff);
        service(1016000); internal(1016000); internal(1017000);
        check("queued step cannot truncate previous attack", advances==2 && r(S+0x354,2)==0xfff);
        internal(1018000); internal(1019000);
        check("deferred countdown gate-off still happens", r(S+0x354,2)==0);
        service(1019000); scan(1020000);
        check("jitter does not merge outputs", advances==3 && outputTimes.size()==3);
        check("period is edge time, not dispatch time", r(0x61ea,2)==5);
        if (sequencer) check("jitter preserves pitches", pitches.equals(List.of(485,525,565)));
        check("pitch DAC updated between notes", !sequencer || (dac.get(0)<dac.get(1) && dac.get(1)<dac.get(2)));
        println("PASS delayed dispatch: three edges, three ordered pitches/triggers");
    }
    void divideAndSlow() throws Exception {
        for (int divisor : new int[]{1,2,8}) {
            fresh(divisor,25000000);
            // Establish once, then test an exact phase independently of
            // acquisition's intentional initial /1 pulses.
            long t=10000;
            for (int i=0;i<8;i++) {
                irq(t,true); service(t); scan(t+5000); irq(t+50000,false); t+=100000;
            }
            check("clock acquired", r(0x6233,1)==1);
            w(0x61ed,1,0);
            int before=advances, outBefore=outputTimes.size();
            for (int i=0;i<16;i++) {
                irq(t,true); service(t); scan(t+5000); irq(t+50000,false); t+=100000;
            }
            check("exact /"+divisor, advances-before==16/divisor && outputTimes.size()-outBefore==16/divisor);
        }
        fresh(8,25000000);
        irq(1000000,true); service(1000000); scan(1005000);
        w(0x61ec,1,5); w(0x6233,1,1); w(0x61ed,1,3); w(0x61ea,2,100);
        int before=advances; long t=1000000;
        for (int interval : new int[]{115,150,100,100,100}) {
            irq(t+10000,false); t+=interval*1000;
            irq(t,true); service(t); scan(t+5000);
            check("divider remains latched through disagreement", r(0x6233,1)==1);
        }
        check("jitter retains /8 phase", advances-before==1);
        fresh(1,25000000);
        t=10000;
        for (int i=0;i<9;i++) {
            irq(t,true); service(t); scan(t+5000);
            irq(t+1000000,false);
            service(t+2000000);
            check("0.5 Hz presence does not time out", r(0x6236,1)==1);
            t+=2001000;
        }
        check("0.5 Hz + jitter clock remains acquired", r(0x6233,1)==1 && advances==9 && outputTimes.size()==9);
        service(t+700000);
        check("absence releases divider and confidence", r(0x6236,1)==0 && r(0x6233,1)==0 && r(0x61ec,1)==0);
        w(S+0x38e,2,0); internal(t+701000);
        check("internal clock resumes after release", periodicAdvances>0);
        println("PASS /1 /2 /8, phase under jitter, 0.5 Hz and timeout");
    }
    void overflowAndWrap() throws Exception {
        fresh(1,25000000);
        for (int i=0;i<40;i++) { irq(10000+i*5000,true); irq(12500+i*5000,false); }
        check("bounded FIFO reports overflow", r(0x6258,2)==9 && r(0x6234,1)==31 && r(0x6235,1)==0);
        for (int i=0;i<31;i++) { service(225000+i*5000); scan(225000+i*5000); }
        check("overflow preserved every queued entry", advances==31 && outputTimes.size()==31);
        check("FIFO drained safely", r(0x6234,1)==r(0x6235,1));
        fresh(1,60000000);
        long start=0xffffffffL*1000000L/frequency-2000;
        irq(start,true); service(start); scan(start+1000);
        irq(start+2500,false); irq(start+5000,true); service(start+5000); scan(start+6000);
        check("COUNT wrap keeps 5 ms interval", r(0x61ea,2)==5 && advances==2 && outputTimes.size()==2);
        println("PASS bounded FIFO overrun/drain and COUNT wrap at 60 MHz");
    }
    void longLowAndTies() throws Exception {
        fresh(1,25000000);
        bank(1000);
        check("continuous long low banked", r(0x6232,1)==2);
        long wrapped=0x100000000L*1000000L/frequency+100;
        irq(wrapped,true); service(wrapped); scan(wrapped+5000);
        check("first pulse after idle COUNT wrap is kept", advances==1 && outputTimes.size()==1);
        w(GPIO+0x60,4,0); bank(wrapped+6000);
        check("spent low cannot be re-banked", r(0x6232,1)==0);
        irq(wrapped+7000,false);
        w(GPIO+0xd0,4,32); bank(wrapped+8000);
        check("pending transition cannot qualify low", r(0x6232,1)==1);
        fresh(1,25000000);
        irq(10000,true); service(10000); scan(15000);
        service(3000000); irq(3001000,false);
        bank(3002000);
        irq(100000000,true); service(100000000); scan(100005000);
        check("long output age is unsigned", advances==2 && outputTimes.size()==2);
        if (sequencer) {
            fresh(1,25000000);
            w(0x61e0,1,4);
            w(0x6160,2,485); w(0x6162,2,0x7fff);
            w(0x6164,2,525); w(0x6166,2,0x7ffe);
            irq(10000,true); service(10000); scan(15000);
            w(S+0x354,2,0x7ff); // factory spike has fallen to sustain
            irq(60000,false); irq(110000,true); service(110000);
            check("tie's input does not cut gate", r(S+0x354,2)==0x7ff);
            scan(115000);
            check("tie does not add a trigger", outputTimes.size()==1 && r(0x6237,1)==0);
            irq(160000,false); irq(210000,true); service(210000);
            check("note after tie explicitly retriggers", r(S+0x354,2)==0 && r(0x60ee,1)==1);
            scan(215000);
            irq(260000,false); irq(310000,true); service(310000); scan(315000);
            check("rest completes without a trigger", outputTimes.size()==2 && r(0x6237,1)==0);
        }
        println("PASS long-idle qualification, pending-edge race, rests/ties");
    }
    void warmRestart() throws Exception {
        fresh(1,25000000);
        irq(10000,true); service(10000);
        check("restart fixture has an in-flight trigger", r(0x60ee,1)==1 && r(0x6237,1)==1);
        w(0x6234,1,7); w(0x6235,1,5); w(0x6258,2,29);
        w(GPIO+0x60,4,0); time(11000);
        call(0x80007bf4L,0x80007bf8L);
        check("warm restart clears FIFO and ownership", r(0x6234,4)==0 && r(0x6258,2)==0);
        check("warm restart cancels pre-restart trigger", r(0x60ee,1)==0);
        scan(15000);
        check("warm restart cannot emit a stale trigger", outputTimes.isEmpty());
        println("PASS warm restart clears queued and pending clock work");
    }
    public void run() throws Exception {
        sequencer=getScriptArgs().length==0 || !getScriptArgs()[0].equals("arp");
        try {
            abiAndNoise(); dispatchJitter(); divideAndSlow(); overflowAndWrap(); longLowAndTies(); warmRestart();
            if (getScriptArgs().length<2 || !getScriptArgs()[1].equals("quick"))
            for (int hz : new int[]{10,150,180,199,200})
                for (double duty : new double[]{0.1,0.5,0.75,0.9})
                    for (int phase : new int[]{0,250}) square(hz,duty,phase);
            println("CLOCK REGRESSION PASS: "+checks+" assertions; max GPIO ISR steps="+maxIrqSteps
                    +"; mode="+(sequencer?"sequencer":"arp"));
        } finally { if(e!=null)e.dispose(); }
    }
}
