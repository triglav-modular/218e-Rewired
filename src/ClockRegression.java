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
    static String regName(int n) { return n==13?"SP":n==14?"LR":n==15?"PC":"R"+n; }
    long reg(int n) { return e.readRegister(regName(n)).longValue() & 0xffffffffL; }
    void setReg(int n, long v) { e.writeRegister(regName(n), v); }
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
        int ins = (int)r(pc(),2);
        // Installed AVR32 SLEIGH mis-models BFINS: it inserts the field at the
        // wrong bit offset (0x7f into bits 8..15 where bit 24 was asked for).
        // Nothing we assemble uses BFINS, but 195 factory sites do, including
        // __floatunsisf -- so every glide step returned rubbish, the slew never
        // moved off zero and the pitch scan remapped 0 on every note. BFEXTU
        // and BFEXTS next door are correct and are left to SLEIGH; the three
        // share 0xe1d0 | Rd<<9 | Rs and differ only in the second halfword,
        // which is opcode<<10 | bp<<5 | width. No site uses width 0 or runs
        // bp+width past 32. bitFieldInstructions() asserts all of this.
        if ((ins & 0xe1f0) == 0xe1d0 && (int)r(pc()+2,2)>>10 == 0x34) {
            int lo = (int)r(pc()+2,2);
            int bp = (lo>>5)&0x1f, width = lo&0x1f, rd = (ins>>9)&0xf;
            long mask = ((1L<<width)-1) << bp;
            long v = ((reg(rd) & ~mask) | ((reg(ins&0xf)<<bp) & mask)) & 0xffffffffL;
            setReg(rd, v);
            e.writeRegister("Z", v==0 ? 1 : 0);
            e.writeRegister("N", (v>>>31)&1);
            jump(pc()+4);
            return;
        }
        // Installed AVR32 SLEIGH lacks the branch p-code for MOV PC,Rs.
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
        // Divisor 8 is written OVER-RANGE on purpose: the raw channel reads
        // past 0x3ff at the top of the knob (the factory clamps it at every
        // read, 0x800079e0), and the top of the knob is /8 now.  Unclamped
        // the shift carries straight past /8; before the ends were swapped
        // the same over-range silenced /1 while /2../8 played on.
        w(S+0x2fc,2,divisor==8?0x420:(divisor-1)*128);
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
    // The 1 kHz DAC flush, entered the way the dispatcher enters it: through
    // jump-table entry 17, which this firmware repoints at the interpolator
    // cave. Stop where the cave hands back to the factory handler; the SPI
    // transfer and the factory event-17 tempo path are separate boundaries
    // (the latter is what internal() drives).
    void flush(long us) throws Exception {
        time(us);
        call(r(0x8001485cL,4),0x80004f66L);
    }
    // The 200 Hz pitch pass, entered at the head of the block that computes
    // the pitch word: glide-rate lookup, slew engine, bend offset, clamp,
    // then our store hook and the remap. Nothing here may supply 0x3210 --
    // the harness cannot tell a right pitch from a wrong one if the fixture
    // is what put the value there.
    void scan(long us) throws Exception {
        time(us);
        call(0x800031b8L,0x80003256L);
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
            bank(tick); service(tick); internal(tick); flush(tick);
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
    // Walk a steady, locked clock across the 5 ms scan grid and measure the
    // spread of accepted-edge-to-trigger-rise delays. The fall is scheduled
    // from the edge itself; the rise used to come from the 5 ms pitch scan,
    // which put it anywhere in that period - measured here at 0..4800 us,
    // uniform, with no fixed component. It now rides the 1 kHz DAC flush, so
    // one tick is the whole budget.
    void riseJitter() throws Exception {
        fresh(1,25000000);
        final long period=26200;   // whole 5 ms scans plus 200 us of walk
        final int warm=10, beats=35;
        final List<Long> edges=new ArrayList<>();
        final List<Integer> staged=new ArrayList<>(), rescanned=new ArrayList<>();
        boolean pending=false;
        long rise=10000, fall=rise+period/2, end=10000+period*beats+20000;
        for (long tick=10000; tick<=end; tick+=1000) {
            while (Math.min(rise,fall)<=tick) {
                if (rise<=fall) {
                    if (edges.size()>=beats) { rise=end+period; continue; }
                    irq(rise,true); edges.add(rise); rise+=period;
                } else {
                    irq(fall,false); fall+=period;
                }
            }
            bank(tick); service(tick);
            int before=outputTimes.size();
            long phase=r(0x6024,2), depth=r(0x6026,2), offset=r(0x6028,2);
            flush(tick);
            if (outputTimes.size()>before) {
                // The fast path enters the remap PAST its per-scan chain. If
                // it ever entered at the top instead, the vibrato engine
                // would run at 1 kHz and its rate would quintuple.
                check("fast trigger leaves the per-scan vibrato chain alone",
                      r(0x6024,2)==phase && r(0x6026,2)==depth && r(0x6028,2)==offset);
                staged.add((int)r(S+0x358,2));
                pending=true;
            }
            if (tick%5000==0) {
                scan(tick);
                // Same DAC word the 5 ms scan reaches, or the pitch would
                // step at the scan boundary after every trigger.
                if (pending) { rescanned.add((int)r(S+0x358,2)); pending=false; }
            }
        }
        check("jitter fixture is a locked /1 clock, one output per edge",
              r(0x6233,1)==1 && r(0x6236,1)==1
              && edges.size()==beats && outputTimes.size()==beats);
        long lo=Long.MAX_VALUE, hi=Long.MIN_VALUE;
        StringBuilder each=new StringBuilder();
        for (int i=warm;i<beats;i++) {
            long delay=outputTimes.get(i)-edges.get(i);
            lo=Math.min(lo,delay); hi=Math.max(hi,delay);
            each.append(" ").append(delay);
        }
        println("rise delay us, beats "+warm+".."+(beats-1)+":"+each);
        println("edge to trigger: min="+lo+" max="+hi+" spread="+(hi-lo)+" us");
        check("every trigger's pitch was re-checked by a later scan",
              rescanned.size()>=beats-1 && staged.size()==beats);
        // The scan runs the real glide, whose fastest rate covers 99.946% of
        // the remaining distance per scan, so the first scan after a step
        // lands a fraction of a raw count short of the target: measured at 1
        // DAC count over this fixture's 40-count steps, 0 in arp mode. One
        // count is the hardware quantisation floor, so that is the tightest
        // this can be. A pitch the fast path got structurally wrong is far
        // outside it -- see bendMissingFromFastTrigger, which diverges by 60.
        for (int i=0;i<rescanned.size();i++)
            check("staged pitch "+i+" survives the scan within the glide's "
                  +"residual: "+staged.get(i)+" vs "+rescanned.get(i),
                  Math.abs(staged.get(i)-rescanned.get(i))<=1);
        check("trigger rise jitter within one 1 kHz tick of the edge", hi-lo<=1000);
        println("PASS trigger rise jitter "+(hi-lo)+" us peak to peak");
    }
    // Drive a locked /1 clock with the bend strip at a given offset and return
    // {the DAC word the fast trigger staged, the DAC word the next scan left}.
    int[] fastVersusScan(int bend) throws Exception {
        fresh(1,25000000);
        w(S+0x216,2,bend);
        final long period=26200;
        long rise=10000, fall=rise+period/2, end=10000+period*14;
        int beats=0; Integer staged=null, rescanned=null; boolean pending=false;
        for (long tick=10000; tick<=end; tick+=1000) {
            while (Math.min(rise,fall)<=tick) {
                if (rise<=fall) { irq(rise,true); beats++; rise+=period; }
                else { irq(fall,false); fall+=period; }
            }
            bank(tick); service(tick);
            int before=outputTimes.size();
            flush(tick);
            if (outputTimes.size()>before && beats>=10 && staged==null) {
                staged=(int)r(S+0x358,2); pending=true;
            }
            if (tick%5000==0) {
                scan(tick);
                if (pending) { rescanned=(int)r(S+0x358,2); pending=false; }
            }
        }
        check("bend fixture produced a fast rise and a later scan, bend="+bend,
              staged!=null && rescanned!=null);
        return new int[]{staged, rescanned};
    }
    // The fast trigger remaps state+0x352, the pitch TARGET the pressure_blend
    // cave writes. The 200 Hz scan remaps slew(state+0x352) + state+0x216, the
    // bend strip's offset. Touch the strip and the two disagree, and the DAC
    // holds the fast path's number until the next scan overwrites it - up to
    // 5 ms of wrong pitch under every trigger, which is the bleed the
    // instrument shows. The centred case is the control: it proves the
    // divergence below is the bend and not the fixture.
    void bendMissingFromFastTrigger() throws Exception {
        int[] flat = fastVersusScan(0);
        check("with the strip centred the fast trigger and the scan agree: "
              +flat[0]+" vs "+flat[1], flat[0]==flat[1]);
        int[] bent = fastVersusScan(60);
        println("bend +60: fast trigger staged "+bent[0]
                +", the next scan corrected it to "+bent[1]);
        check("KNOWN DEFECT, the fast trigger stages a bend-less pitch; the "
              +"pitch-ownership restructure must make these two equal: "
              +bent[0]+" vs "+bent[1], bent[0]!=bent[1]);
        println("PASS bend divergence between the fast trigger and the scan");
    }
    // The pitch scan and the 1 kHz DAC flush are separate dispatcher events
    // and nothing orders them within a millisecond. Run a whole clock both
    // ways round: every edge must produce exactly one trigger either way.
    void scanFlushOrder() throws Exception {
        for (boolean scanFirst : new boolean[]{true,false}) {
            fresh(1,25000000);
            long period=26200;
            int beats=12;
            long rise=10000, fall=rise+period/2, end=10000+period*beats+20000;
            int edges=0;
            for (long t=10000; t<=end; t+=1000) {
                while (Math.min(rise,fall)<=t) {
                    if (rise<=fall) {
                        if (edges>=beats) { rise=end+period; continue; }
                        irq(rise,true); edges++; rise+=period;
                    } else { irq(fall,false); fall+=period; }
                }
                bank(t); service(t);
                if (scanFirst) {
                    if (t%5000==0) scan(t);
                    flush(t);
                } else {
                    flush(t);
                    if (t%5000==0) scan(t);
                }
            }
            check("one trigger per edge with the "
                  +(scanFirst?"scan":"flush")+" first: "
                  +edges+" edges, "+outputTimes.size()+" outputs",
                  edges==beats && advances==beats && outputTimes.size()==beats);
        }
        println("PASS one trigger per edge whichever of scan and flush runs first");
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
    // Step the three bit-field forms where the factory actually uses them and
    // check them against hand-computed results: BFINS through the workaround
    // in step(), BFEXTU and BFEXTS as SLEIGH executes them. If a later Ghidra
    // fixes BFINS, or breaks one of the other two, the glide silently returns
    // rubbish again -- so assert all three rather than trust them.
    void bitFieldInstructions() throws Exception {
        fresh(1,25000000);
        e.writeRegister("SP",0x7800); e.writeRegister("LR",0x100);
        // BFINS R12,R10,0x18,0x8 -- __floatunsisf placing an exponent. This is
        // the one SLEIGH gets wrong; without the workaround it yields 0x7f00.
        e.writeRegister("R12",0x01000000L); e.writeRegister("R10",0x7fL);
        jump(0x800133f8L); step();
        check("BFINS inserts at bit 24 and leaves the rest", reg(12)==0x7f000000L);
        // BFEXTU R9,R11,0x18,0x8 -- __extendsfdf2 taking a float's exponent.
        e.writeRegister("R11",0x87e50000L); e.writeRegister("R9",0xdeadbeefL);
        jump(0x80013476L); step();
        check("BFEXTU extracts bits 24..31", reg(9)==0x87);
        // BFEXTS R9,R8,0x0,0x10 -- the slew tail narrowing its result to int16.
        e.writeRegister("R8",0x1234ffffL); e.writeRegister("R9",0xdeadbeefL);
        jump(0x8000c104L); step();
        check("BFEXTS sign-extends a negative halfword", reg(9)==0xffffffffL);
        e.writeRegister("R8",0x12347fffL); e.writeRegister("R9",0xdeadbeefL);
        jump(0x8000c104L); step();
        check("BFEXTS leaves a positive halfword alone", reg(9)==0x7fff);
        // End to end: the soft-float library the glide runs on, exact IEEE.
        e.writeRegister("R12",485); call(0x800133c4L,0x100);
        check("__floatunsisf(485) == 43f28000", reg(12)==0x43f28000L);
        e.writeRegister("R12",0x40000000L); e.writeRegister("R11",0x40400000L);
        call(0x8001326cL,0x100);
        check("__mulsf3(2.0,3.0) == 40c00000", reg(12)==0x40c00000L);
        e.writeRegister("R12",0x43f28000L); call(0x8001346cL,0x100);
        check("__extendsfdf2(485.0) == 407e5000:00000000",
              reg(11)==0x407e5000L && reg(10)==0);
        println("PASS bit-field instructions and factory soft-float");
    }
    public void run() throws Exception {
        sequencer=getScriptArgs().length==0 || !getScriptArgs()[0].equals("arp");
        try {
            bitFieldInstructions(); abiAndNoise(); dispatchJitter(); riseJitter(); bendMissingFromFastTrigger(); scanFlushOrder(); divideAndSlow(); overflowAndWrap(); longLowAndTies(); warmRestart();
            if (getScriptArgs().length<2 || !getScriptArgs()[1].equals("quick"))
            for (int hz : new int[]{10,150,180,199,200})
                for (double duty : new double[]{0.1,0.5,0.75,0.9})
                    for (int phase : new int[]{0,250}) square(hz,duty,phase);
            println("CLOCK REGRESSION PASS: "+checks+" assertions; max GPIO ISR steps="+maxIrqSteps
                    +"; mode="+(sequencer?"sequencer":"arp"));
        } finally { if(e!=null)e.dispose(); }
    }
}
