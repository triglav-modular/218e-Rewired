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
    // Entries to the factory DAC transfer clock_deadline performs at phase A.
    // Counting it is how a test tells a settle build from a held one without
    // being told: the deadline calls it only when a settle was configured for
    // the source, which is the same thing as the pitch being MEANT to leave
    // ahead of the gate.
    int transfers;
    // What DAC slot 2 held at each of those transfers.  A settle is a wait
    // for the OUTPUT to travel, so the value it is spent on is the whole
    // point: a transfer that carried the previous note's pitch spends the
    // interval on the wrong one and the new pitch gets no settling at all.
    final List<Integer> transferPitches = new ArrayList<>();
    long nowUs;
    final List<Integer> pitches = new ArrayList<>();
    final List<Integer> dac = new ArrayList<>();
    final List<Long> outputTimes = new ArrayList<>();
    final List<Long> beatTimes = new ArrayList<>();

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
            + " claim=" + r(0x625b,1) + " target=" + r(0x60dc,4)
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
        if (pc() == 0x8000ba9cL) { transfers++; transferPitches.add((int)r(S+0x358,2)); }
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
        if (pc() == 0x800022deL && periodic) { periodicAdvances++; beatTimes.add(nowUs); }
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
        advances=0; periodicAdvances=0; periodic=false; transfers=0;
        transferPitches.clear();
        pitches.clear(); dac.clear(); outputTimes.clear(); beatTimes.clear();
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
    // ONE dispatch of event 17, both halves in the order the instrument runs
    // them: the wrapper's (where the fast trigger lives) and then the
    // factory's (where the arp advance claims the next beat). The two are not
    // separable on hardware -- the dispatcher pops one event and runs the
    // whole handler -- so any test that withholds dispatches must withhold
    // both halves together.
    void dispatch(long us) throws Exception { flush(us); internal(us); }
    // Let a step that has been selected actually complete. Which context
    // finishes it depends on the build: with no settle configured the next
    // flush does, with one configured the flush stages the pitch and the 1 ms
    // timer spends the wait before a later flush raises the gate, and if the
    // glide declines the whole thing the pitch scan still does. Ticking all
    // three for a few milliseconds covers every one of those.
    void finishStep(long until) throws Exception {
        for (long tick=((nowUs/1000)+1)*1000; tick<=until; tick+=1000) {
            bank(tick); service(tick); flush(tick);
            if (tick%5000==0) scan(tick);
        }
    }
    // Tick the two parts of the main loop that COMPLETE a claimed step -- the
    // 1 ms timer, which spends the wait, and the flush, which gates it --
    // without dequeuing anything new or running a scan.
    //
    // A fixture that drives only service() and scan() models a machine whose
    // DAC never flushes. That used to go unnoticed because an external step
    // with no settle was claim 1 and the pitch scan could finish it; with a
    // deadline the flush is the only context that may, since a scan reaching
    // it first would put the gate back on the 5 ms grid the deadline exists
    // to remove. So the tests below say so explicitly.
    void settleStep(long from, long until) throws Exception {
        for (long t=((from/1000)+1)*1000; t<=until; t+=1000) { bank(t); flush(t); }
    }
    // Phase A and then the gate: flush at `from`, and if the gate did not go
    // out there, run the 1 ms timer and the flush on until it does. Returns
    // the microsecond it went out on. Without a deadline that is always
    // `from`; with one it is the edge plus the deadline, a few milliseconds
    // later, and tests that need to know when the gate happened must ask
    // rather than assume the flush they called.
    long gateAt(long from, long until) throws Exception {
        int before=outputTimes.size();
        flush(from);
        if (outputTimes.size()>before) return from;
        for (long t=((from/1000)+1)*1000; t<=until; t+=1000) {
            bank(t); flush(t);
            if (outputTimes.size()>before) return t;
        }
        throw new Exception("no gate between "+from+" and "+until
                            +" (claim="+r(0x625b,1)+" pending="+r(0x60ee,1)+")");
    }
    // One bare pending-output pass: the call the main-loop wrapper makes
    // around the dispatcher on a deadline build, without the dequeue and
    // without popping an event. Entered exactly as the wrapper enters it.
    void pending(long us) throws Exception {
        time(us);
        call(0x8001c100L,0x100);
    }
    // What settle the emitted image gives the internal beat, in milliseconds,
    // asked of the firmware rather than assumed. A deadline build spends the
    // wait as a COUNT target computed at phase A from the actual DAC
    // transfer, so claim a beat and let one flush run phase A, then read the
    // target back; an older build writes the milliseconds at the claim.
    // Returns 0 when the build claims 1 and holds no gate at all. Runs on a
    // fresh machine and leaves it dirty -- call before the fixture's own
    // fresh().
    int askInternalSettleMs() throws Exception {
        fresh(1,25000000);
        w(0x6236,1,0); w(0x60ee,1,0); w(0x625b,1,0); w(S+0x340,1,1);
        time(50000);
        call(0x8001c700L,0x100);        // the claim
        if (r(0x625b,1)!=2) return 0;
        if (!deadlineBuild()) return (int)r(0x60ee,1);
        flush(50000);                    // phase A stores the target
        long target=r(0x60dc,4);
        long now=(50000L*frequency)/1000000L;
        return (int)((target-now)/(frequency/1000));
    }
    void abiAndNoise() throws Exception {
        fresh(1,25000000);
        e.writeRegister("R0",0x685b); e.writeRegister("R1",0x12345678);
        irq(10000,true); service(10000); finishStep(25000);
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
        irq(80000,false); irq(81000,true); service(81000); finishStep(95000);
        check("fresh qualified low recovers immediately", advances==2 && outputTimes.size()==2);
        irq(95100,false); irq(95200,true); service(95200);
        check("low interval cannot be reused", advances==2);
        // Event 10 is no longer an unqualified ingress.
        call(0x80004e58L,0x800051b0L);
        check("synthetic legacy event cannot step", advances==2);
        for (long sr : new long[]{0,0x10000}) {
            e.writeRegister("SR",sr); service(96000);
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
    // Dispatches are withheld and delivered by hand here. They are delivered
    // WHOLE: this used to drive the factory half alone, and passed because a
    // beat left on the scan's countdown could be completed by the scan. With
    // a deadline the external beat is claimed for the flush and only the
    // flush completes it -- which costs nothing on the instrument, since the
    // scan is event 3 out of the same ring and a dispatcher that is not
    // running event 17 is not running the scan either.
    void dispatchJitter() throws Exception {
        fresh(1,25000000);
        irq(1004000,true); service(1004000); scan(1005000);
        irq(1006500,false); irq(1009000,true);
        scan(1010000); service(1011000);
        dispatch(1011000);
        irq(1011500,false); dispatch(1012000); dispatch(1013000);
        irq(1014000,true); service(1014000); dispatch(1014000);
        // The second beat gated at 1011000 -- on the service pass, since a
        // pending service places the gate at its target instead of waiting
        // for a dispatch -- so its spike is three milliseconds old here and
        // the attack-age guard must still be holding the countdown off.
        check("factory countdown cannot cut a just-emitted attack", r(S+0x354,2)==0xfff);
        scan(1015000); dispatch(1015000);
        // At 1015000 the spike is exactly four milliseconds old and the
        // countdown MAY legitimately cut it: that is the nominal spike
        // width, not a truncation.  The old expectation of a still-high gate
        // here was the dispatch-starved fixture stretching the spike, since
        // the countdown only fell when a dispatch ran.
        service(1016000); dispatch(1016000); dispatch(1017000);
        // With the pending service the third edge dequeues AND gates at
        // 1016000 (it is past its 2 ms period-bounded deadline); without one
        // it is the second edge that dequeues here.  Either way the newest
        // spike is a millisecond old and must be intact.
        check("queued step cannot truncate previous attack",
              advances==(deadlineBuild()?3:2) && r(S+0x354,2)==0xfff);
        // The 1 ms task is on the TIMER, not the ring: a stalled dispatcher
        // does not stop it, and it is what spends a deadline. Run both on,
        // banking every millisecond, far enough for the third beat to gate
        // and for the spikes to fall -- the deadline moves each of those
        // later by at most its own length, and a beat that was already late
        // is not moved at all.
        int gateOffs=0, wasHigh=(int)r(S+0x354,2);
        for (long t=1018000; t<=1044000; t+=1000) {
            bank(t); dispatch(t);
            // clock_service refuses to dequeue while a pulse is pending, and
            // a deadline is pending for as long as it runs. The main loop
            // calls it every pass and so retries; calling it once, on one
            // hand-picked microsecond, does not.
            if (t>=1019000) service(t);
            if (t==1020000) scan(t);
            int now=(int)r(S+0x354,2);
            if (wasHigh!=0 && now==0) gateOffs++;
            wasHigh=now;
        }
        check("deferred countdown gate-off still happens",
              gateOffs>=1 && r(S+0x354,2)==0);
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
            bank(tick);
            // The gate can go out inside service() now -- the wrapper calls
            // the pending-output service around the dispatcher -- so the
            // sample window covers service and flush together.
            int before=outputTimes.size();
            long phase=r(0x6024,2), depth=r(0x6026,2), offset=r(0x6028,2);
            service(tick);
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
        check("under a 1 ms fixture the trigger rides the flush claim, not the "
              +"5 ms scan (fixture-bounded: NOT a hardware jitter figure)", hi-lo<=1000);
        println("PASS trigger on the flush claim, "+(hi-lo)+" us under a 1 ms "
                +"fixture -- see loopModelJitter for the queue term");
    }
    // Drive a locked /1 clock with the bend strip at a given offset and return
    // {the DAC word the fast trigger staged, the DAC word the next scan left}.
    int[] fastVersusScan(int bend) throws Exception {
        fresh(1,25000000);
        w(S+0x216,2,bend & 0xffff);
        final long period=26200;
        long rise=10000, fall=rise+period/2, end=10000+period*14;
        int beats=0; Integer staged=null, rescanned=null; boolean pending=false;
        for (long tick=10000; tick<=end; tick+=1000) {
            while (Math.min(rise,fall)<=tick) {
                if (rise<=fall) { irq(rise,true); beats++; rise+=period; }
                else { irq(fall,false); fall+=period; }
            }
            bank(tick);
            int before=outputTimes.size();
            service(tick);
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
    // The fast trigger stages the step's pitch on the 1 kHz flush; the 200 Hz
    // scan reaches its own answer up to 5 ms later. They have to be the same
    // DAC word, or every trigger drops a wrong pitch under the gate until the
    // scan overwrites it - which is the bleed the instrument showed when the
    // fast path staged state+0x352 without the bend strip's offset. The
    // centred case is the control; the pushed cases carry the defect, and the
    // last two drive the sum past each end of the scan's clamp.
    void bendAgreesWithTheScan() throws Exception {
        for (int bend : new int[]{0, 60, -240, 4000, -600}) {
            int[] r = fastVersusScan(bend);
            println("bend "+bend+": fast trigger staged "+r[0]
                    +", the scan reached "+r[1]);
            check("the fast trigger and the scan stage the same DAC word at "
                  +"bend "+bend+": "+r[0]+" vs "+r[1], r[0]==r[1]);
        }
        println("PASS fast trigger and scan agree across the bend range");
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
                irq(t,true); service(t); scan(t+5000);
                settleStep(t+5000,t+15000);
                irq(t+50000,false); t+=100000;
            }
            check("clock acquired", r(0x6233,1)==1);
            w(0x61ed,1,0);
            int before=advances, outBefore=outputTimes.size();
            for (int i=0;i<16;i++) {
                irq(t,true); service(t); scan(t+5000);
                settleStep(t+5000,t+15000);
                irq(t+50000,false); t+=100000;
            }
            check("exact /"+divisor, advances-before==16/divisor && outputTimes.size()-outBefore==16/divisor);
        }
        fresh(8,25000000);
        irq(1000000,true); service(1000000); scan(1005000);
        // Let this one finish too, or it is still in flight when the loop
        // below asks for the next dequeue and every edge shifts by one.
        settleStep(1005000,1015000);
        w(0x61ec,1,5); w(0x6233,1,1); w(0x61ed,1,3); w(0x61ea,2,100);
        int before=advances; long t=1000000;
        for (int interval : new int[]{115,150,100,100,100}) {
            irq(t+10000,false); t+=interval*1000;
            irq(t,true); service(t); scan(t+5000);
            settleStep(t+5000,t+15000);
            check("divider remains latched through disagreement", r(0x6233,1)==1);
        }
        check("jitter retains /8 phase", advances-before==1);
        fresh(1,25000000);
        t=10000;
        for (int i=0;i<9;i++) {
            irq(t,true); service(t); scan(t+5000);
            settleStep(t+5000,t+15000);
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
        // These edges are hundreds of milliseconds old, so every one of them
        // is already past its deadline and gates on the first flush.
        for (int i=0;i<31;i++) {
            service(225000+i*5000); scan(225000+i*5000);
            settleStep(225000+i*5000,225000+i*5000+4000);
        }
        check("overflow preserved every queued entry", advances==31 && outputTimes.size()==31);
        check("FIFO drained safely", r(0x6234,1)==r(0x6235,1));
        fresh(1,60000000);
        long start=0xffffffffL*1000000L/frequency-2000;
        irq(start,true); service(start); scan(start+1000);
        // The falling edge first, then the flushes: this beat IS fresh, so a
        // deadline holds its gate for a few milliseconds and clock_service
        // will not dequeue the next edge until it has gone out.
        settleStep(start+1000,start+2400);
        irq(start+2500,false);
        settleStep(start+2500,start+4800);
        irq(start+5000,true);
        // clock_service will not cut a 4 ms attack, and the deadline moved
        // the previous gate later, so this dequeue has to be RETRIED the way
        // the main loop retries it -- every pass -- and not attempted once on
        // a hand-picked microsecond.
        finishStep(start+26000);
        check("COUNT wrap keeps 5 ms interval", r(0x61ea,2)==5 && advances==2 && outputTimes.size()==2);
        println("PASS bounded FIFO overrun/drain and COUNT wrap at 60 MHz");
    }
    void longLowAndTies() throws Exception {
        fresh(1,25000000);
        bank(1000);
        check("continuous long low banked", r(0x6232,1)==2);
        long wrapped=0x100000000L*1000000L/frequency+100;
        irq(wrapped,true); service(wrapped); scan(wrapped+5000);
        settleStep(wrapped+5000,wrapped+5500);
        check("first pulse after idle COUNT wrap is kept", advances==1 && outputTimes.size()==1);
        w(GPIO+0x60,4,0); bank(wrapped+6000);
        check("spent low cannot be re-banked", r(0x6232,1)==0);
        irq(wrapped+7000,false);
        w(GPIO+0xd0,4,32); bank(wrapped+8000);
        check("pending transition cannot qualify low", r(0x6232,1)==1);
        fresh(1,25000000);
        irq(10000,true); service(10000); scan(15000);
        settleStep(15000,25000);
        service(3000000); irq(3001000,false);
        bank(3002000);
        irq(100000000,true); service(100000000); scan(100005000);
        settleStep(100005000,100015000);
        check("long output age is unsigned", advances==2 && outputTimes.size()==2);
        if (sequencer) {
            fresh(1,25000000);
            w(0x61e0,1,4);
            w(0x6160,2,485); w(0x6162,2,0x7fff);
            w(0x6164,2,525); w(0x6166,2,0x7ffe);
            irq(10000,true); service(10000); scan(15000);
            settleStep(15000,25000);
            w(S+0x354,2,0x7ff); // factory spike has fallen to sustain
            irq(60000,false); irq(110000,true); service(110000);
            check("tie's input does not cut gate", r(S+0x354,2)==0x7ff);
            scan(115000);
            settleStep(115000,125000);
            check("tie does not add a trigger", outputTimes.size()==1 && r(0x6237,1)==0);
            irq(160000,false); irq(210000,true); service(210000);
            // The retrigger is marked by the CLAIM now. With a deadline the
            // countdown is written at phase A, not here, so 0x60ee is still
            // zero at this point and is no longer the thing that says a pulse
            // is owed.
            check("note after tie explicitly retriggers",
                  r(S+0x354,2)==0 && (r(0x60ee,1)==1 || r(0x625b,1)!=0));
            scan(215000);
            settleStep(215000,225000);
            irq(260000,false); irq(310000,true); service(310000); scan(315000);
            settleStep(315000,325000);
            check("rest completes without a trigger", outputTimes.size()==2 && r(0x6237,1)==0);
        }
        println("PASS long-idle qualification, pending-edge race, rests/ties");
    }
    void warmRestart() throws Exception {
        fresh(1,25000000);
        // A first beat has no acquired period and completes at once, so the
        // held trigger has to be a SECOND beat: with a deadline built it sits
        // as claim 3 on a stored COUNT target, without one as the claim or
        // the scan countdown, depending on where the build parks the wait.
        irq(10000,true); service(10000); finishStep(19000);
        irq(35000,false); irq(60000,true); service(60000);
        int outs=outputTimes.size();
        check("restart fixture has an in-flight trigger",
              (r(0x60ee,1)==1 || r(0x625b,1)!=0) && r(0x6237,1)==1);
        w(0x6234,1,7); w(0x6235,1,5); w(0x6258,2,29);
        w(GPIO+0x60,4,0); time(61000);
        call(0x80007bf4L,0x80007bf8L);
        check("warm restart clears FIFO and ownership", r(0x6234,4)==0 && r(0x6258,2)==0);
        check("warm restart cancels pre-restart trigger",
              r(0x60ee,1)==0 && r(0x625b,1)==0);
        // Every completing context: the scan, a bare pending pass, and a
        // whole flush. None may emit the cancelled trigger.
        scan(65000); pending(66000); flush(67000); bank(68000);
        check("warm restart cannot emit a stale trigger",
              outputTimes.size()==outs);
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
    // The internal clock's beat is the factory tempo path inside event 17,
    // not a GPIO edge, so clock_settle takes its "no external clock" branch:
    // a wait of gate_settle_scans+1 SCANS, and no claim on the 1 kHz flush.
    // The wait itself is a measured RC fact and stays - the output pole is
    // tau 0.9 ms, so a gate raised with the pitch store sits 132 cents short
    // on an octave jump. What it should not ALSO cost is the scan grid: the
    // settle is milliseconds, and quantising it to 5 ms scans is the whole
    // of the beat's jitter. Walk the tempo across that grid and measure.
    void internalJitter() throws Exception {
        // Ask the FIRMWARE what settle this build was configured with rather
        // than assuming the default. On a deadline build the wait is the
        // COUNT target phase A stores; on an older one it is the countdown
        // clock_settle writes. askInternalSettleMs() reads whichever this
        // image is.
        final int settleMs=askInternalSettleMs();
        println("internal settle this build asks for: "+settleMs+" ms");
        fresh(1,25000000);
        // 26 ms is a whole number of milliseconds and NOT a multiple of the
        // 5 ms scan, so successive beats land on every phase of the grid.
        w(S+0x34a,2,26); w(S+0x38e,2,1);
        for (long tick=10000; tick<=760000; tick+=1000) {
            bank(tick); service(tick); internal(tick); flush(tick);
            if (tick%5000==0) scan(tick);
        }
        // ...and again with the scan AHEAD of the flush. The claim is dropped
        // by whichever context completes the step, so a scan that reaches a
        // beat first must fire it once and leave nothing for the flush; the
        // count below is what catches a second spike if it does not.
        int beforeBeats=beatTimes.size(), beforeOut=outputTimes.size();
        for (long tick=761000; tick<=1200000; tick+=1000) {
            bank(tick); service(tick); internal(tick);
            if (tick%5000==0) scan(tick);
            flush(tick);
        }
        check("scan before flush: still one trigger per internal beat",
              beatTimes.size()-beforeBeats>=10
              && outputTimes.size()-beforeOut==beatTimes.size()-beforeBeats);
        check("jitter fixture is the INTERNAL clock, no external presence",
              r(0x6236,1)==0 && r(0x6233,1)==0 && periodicAdvances>=20);
        check("one trigger per internal beat",
              outputTimes.size()==beatTimes.size() && beatTimes.size()>=20);
        long lo=Long.MAX_VALUE, hi=Long.MIN_VALUE;
        StringBuilder each=new StringBuilder();
        for (int i=5;i<beforeBeats;i++) {
            long delay=outputTimes.get(i)-beatTimes.get(i);
            lo=Math.min(lo,delay); hi=Math.max(hi,delay);
            each.append(" ").append(delay);
        }
        println("internal beat to trigger us, beats 5..."+(beforeBeats-1)+":"+each);
        println("internal beat to trigger: min="+lo+" max="+hi+" spread="+(hi-lo)+" us");
        // The settle is not given up to buy the promptness.
        check("every internal beat still gets its full "+settleMs+" ms settle",
              lo>=settleMs*1000L);
        // The target is 1-2 ms peak to peak, on every build, on both clocks.
        check("under a 1 ms fixture the internal beat rides the flush claim "
              +"(fixture-bounded: NOT a hardware jitter figure)", hi-lo<=2000);
        println("PASS internal clock trigger jitter "+(hi-lo)+" us peak to peak");
    }
    // clock_settle is reached from the arp's own advance AND from three
    // key-scan sites, and both take the "no external clock" branch. Only the
    // BEAT is claimed for the flush: with the arp and the sequencer both off
    // the note is a key under a finger, its latency is the player's own, and
    // it is left on the scan exactly as it was. Drive the routine at both
    // settings and check which of them arms.
    void keyboardKeepsTheScan() throws Exception {
        fresh(1,25000000);
        w(0x6236,1,0); w(0x60ee,1,0); w(0x625b,1,0);
        w(S+0x340,1,0); w(S+0x341,1,0);
        call(0x8001c700L,0x100);
        // Unclaimed, the countdown is what it always was: SCANS, one more
        // than the settle asked for, and the pitch scan is what spends it.
        // The count itself depends on the build; that it is the SCAN's does
        // not, and the claim being clear is what says so.
        check("a key with the arp and sequencer off is not claimed by the flush",
              r(0x625b,1)==0 && r(0x60ee,1)>=1);
        // Claimed, the flush owns it: claim 1 fires on the next flush, claim
        // 2 stages the pitch and holds the gate for the settle.  What holds
        // the wait depends on the build: a deadline build leaves 0x60ee at
        // ZERO -- phase A computes the whole wait as a COUNT target from the
        // actual transfer, and anything spent before that transfer would be
        // the settle-before-pitch defect -- while an older build writes the
        // settle in MILLISECONDS here, not in scans.
        w(0x60ee,1,0); w(0x625b,1,0); w(S+0x340,1,1);
        call(0x8001c700L,0x100);
        int beatClaim=(int)r(0x625b,1);
        check("the internal beat is claimed by the flush, however built",
              beatClaim==1 || beatClaim==2);
        check("a held claim leaves the wait where this build spends it",
              beatClaim!=2 || (deadlineBuild() ? r(0x60ee,1)==0
                                               : r(0x60ee,1)>=1));
        w(0x60ee,1,0); w(0x625b,1,0); w(S+0x340,1,0); w(S+0x341,1,1);
        call(0x8001c700L,0x100);
        check("a playing sequence is a beat too", r(0x625b,1)==beatClaim);
        println("PASS the keyboard alone keeps the scan; the beat takes the flush");
    }
    // ---- The factory main loop, modeled -----------------------------------
    //
    // riseJitter() and internalJitter() above drive service() and flush()
    // every 1000 us, so the spread they measure cannot exceed one tick
    // whatever the firmware does.  What they prove is that the trigger takes
    // the flush CLAIM instead of waiting for the 5 ms pitch scan; they were
    // never a measurement of its latency, and the bounds they assert are the
    // fixture's tick rather than the firmware's behaviour.
    //
    // The instrument runs no 1 kHz loop.  0x80007c5a calls clock_service and
    // then the factory dispatcher at 0x80004c64, which takes ONE event from
    // its 32-entry ring per pass.  Event 17 is POSTED at 1 kHz by the timer
    // and the 200 Hz pitch pass is a separate ring event, so 1200 posts per
    // second compete for one pop per pass and the trigger waits behind
    // whatever is ahead of it. Queue depth is one real term, but the scan-rate
    // experiment bounded it as small and moving the dequeue onto the punctual
    // 1 ms task did not change the hardware distribution. The fixture sets
    // both waits to zero and cannot localise synchronous note selection.
    //
    // Hardware for comparison -- image 1a5b8110, external clock, n=1150:
    // min 258 us, mean 1.55 ms, max 3.62 ms, sigma 1.04 ms, so 3.36 ms peak
    // to peak.  Identical for a square wave and a descending saw, and not
    // Gaussian (range/sigma 3.23 where noise at that count gives ~6.5), so
    // the spread is not analog and not the input conditioning.
    static final int EV_FLUSH = 17, EV_SCAN = 3;

    // competingHz is the factory traffic this harness cannot execute -- MIDI,
    // USB, the keyboard scan, pressure -- posted into the same ring.  Only the
    // SLOT is modeled, not the handler's run time, so this is a lower bound on
    // what real traffic costs the trigger.  It is a free parameter precisely
    // because nothing in the repo pins it; the sweep exists to find what value
    // reproduces the instrument, not to assert one.
    //
    // {min, max, spread, mean, outputs} in microseconds, or null if the ring
    // overflowed: a loop that cannot drain its posts is not a model of an
    // instrument that plays.
    long[] loopModel(int loopHz, int competingHz) throws Exception {
        return loopModel(loopHz,competingHz,loopHz);
    }

    // serviceHz is an artificial rate for clock_service polling the FIFO.
    // This sweep once motivated moving the dequeue to the 1 ms task because a
    // 250-300 Hz value reproduces the scale. Hardware directly refuted that
    // wait: the move left the 3.4 ms range unchanged. Keep the sweep as the
    // honest limit of the model, not as evidence that this is the real rate.
    long[] loopModel(int loopHz, int competingHz, int serviceHz) throws Exception {
        fresh(1,25000000);
        final long period=26200;   // whole 5 ms scans plus 200 us of walk
        final int warm=6, beats=20;
        final long loopUs=1000000L/loopHz;
        final List<Integer> ring=new ArrayList<>();
        final List<Long> edges=new ArrayList<>();
        long rise=10000, fall=rise+period/2, end=10000+period*beats+20000;
        long tTimer=10000, tLoop=10000;
        final long competeUs=competingHz>0?1000000L/competingHz:0;
        long tCompete=competeUs>0?10000:Long.MAX_VALUE;
        final long serviceUs=1000000L/serviceHz;
        long tService=10000;
        boolean overflow=false;
        while (tTimer<=end || tLoop<=end) {
            long t=Math.min(Math.min(tTimer<=end?tTimer:Long.MAX_VALUE,
                                     tLoop<=end?tLoop:Long.MAX_VALUE),
                   Math.min(tCompete<=end?tCompete:Long.MAX_VALUE,
                            tService<=end?tService:Long.MAX_VALUE));
            while (Math.min(rise,fall)<=t) {
                if (rise<=fall) {
                    if (edges.size()>=beats) { rise=end+period; continue; }
                    irq(rise,true); edges.add(rise); rise+=period;
                } else { irq(fall,false); fall+=period; }
            }
            if (t==tTimer) {
                bank(t);
                // The timer POSTS; it does not dispatch.
                if (ring.size()>=32) overflow=true; else ring.add(EV_FLUSH);
                if (t%5000==0) {
                    if (ring.size()>=32) overflow=true; else ring.add(EV_SCAN);
                }
                tTimer+=1000;
            }
            if (t==tCompete) {
                if (ring.size()>=32) overflow=true; else ring.add(0);
                tCompete+=competeUs;
            }
            if (t==tService) {
                service(t);                    // the FIFO dequeue, on its own rate
                tService+=serviceUs;
            }
            if (t==tLoop) {
                if (!ring.isEmpty()) {         // the dispatcher takes exactly one
                    int ev=ring.remove(0);
                    // A foreign handler costs the trigger its slot; the
                    // harness cannot run the factory's, so it costs only that.
                    if (ev==EV_FLUSH) flush(t); else if (ev==EV_SCAN) scan(t);
                }
                // A deadline build's wrapper services pending output around
                // the dispatcher on every pass, so the model must too:
                // phase A runs a pass behind the claim and the gate goes out
                // on the pass its target expires, whatever the ring holds.
                if (deadlineBuild()) pending(t);
                tLoop+=loopUs;
            }
        }
        if (overflow) return null;
        if (outputTimes.size()<beats || edges.size()<beats) return null;
        long lo=Long.MAX_VALUE, hi=Long.MIN_VALUE, sum=0;
        int n=0;
        for (int i=warm;i<beats;i++) {
            long delay=outputTimes.get(i)-edges.get(i);
            lo=Math.min(lo,delay); hi=Math.max(hi,delay); sum+=delay; n++;
        }
        return new long[]{lo,hi,hi-lo,sum/n,outputTimes.size()};
    }

    // Hardware, image 1a5b8110, n=1150.
    static final long HW_MIN=258, HW_MEAN=1550, HW_MAX=3620;

    // The fast path declines when 0x2eee != 0 -- a real portamento time --
    // because it stages the step's TARGET while the scan stages wherever the
    // glide has got to. Declining sends the beat back to the 5 ms scan. No
    // other test in this file ever writes 0x2eee, so every jitter figure here
    // is for a snapping glide; on the instrument the portamento knob decides
    // it. This measures what the decline costs, with the dispatcher punctual
    // at 1 kHz the way the internal-clock measurement showed it to be.
    void declinedGlideJitter() throws Exception {
        // Is the decline branch reachable in THIS build at all?  0x2eee is
        // derived by the scan, not stored, so it has to be provoked from the
        // portamento sources rather than written directly.
        for (int src : new int[]{0,64,256,512,1023}) {
            fresh(1,25000000);
            w(0x2ee0,2,src); w(0x2ee6,2,src);
            scan(10000);
            println("    portamento sources="+src+" -> 0x2eee="+r(0x2eee,2));
        }
        for (int glide : new int[]{0,8,64}) {
            fresh(1,25000000);
            w(0x2eee,2,glide);
            final long period=26200;
            final int warm=6, beats=20;
            final List<Long> edges=new ArrayList<>();
            long rise=10000, fall=rise+period/2, end=10000+period*beats+20000;
            for (long tick=10000; tick<=end; tick+=1000) {
                while (Math.min(rise,fall)<=tick) {
                    if (rise<=fall) {
                        if (edges.size()>=beats) { rise=end+period; continue; }
                        irq(rise,true); edges.add(rise); rise+=period;
                    } else { irq(fall,false); fall+=period; }
                }
                bank(tick); service(tick); flush(tick);
                if (tick%5000==0) {
                    long before=r(0x2eee,2);
                    scan(tick);
                    if (tick==10000) println("    0x2eee before scan="+before
                                             +" after scan="+r(0x2eee,2));
                }
            }
            println("    0x2eee at end="+r(0x2eee,2));
            if (outputTimes.size()<beats) { println("  glide "+glide+": lost an output"); continue; }
            long lo=Long.MAX_VALUE, hi=Long.MIN_VALUE, sum=0; int n=0;
            for (int i=warm;i<beats;i++) {
                long d=outputTimes.get(i)-edges.get(i);
                lo=Math.min(lo,d); hi=Math.max(hi,d); sum+=d; n++;
            }
            println("  glide 0x2eee="+glide+(glide==0?" (snapping)":" (written, then"
                    +" zeroed by the scan)")+": min="+lo+" max="+hi+" spread="
                    +(hi-lo)+" mean="+(sum/n)+" us");
            if (glide!=0) glideDeclineSpread=Math.max(glideDeclineSpread,hi-lo);
        }
        // A decline puts the beat back on the 5 ms scan, so it must cost more
        // than the flush's tick. If it does not, this test is not reaching the
        // decline branch and proves nothing.
        // Reported, not asserted.  In a blend build the scan derives 0x2eee=0
        // from every source tried, so the decline branch is unreachable and
        // there is nothing here to bound.  The value of this probe is that it
        // states the glide's condition alongside the jitter, so no future
        // jitter figure gets quoted without saying whether the glide was
        // snapping when it was taken.  A build where the sources DO derive a
        // nonzero 0x2eee will show a spread of a whole scan period here.
        println("PASS glide probe: decline unreachable from the modeled "
                +"sources, spread unchanged at "+glideDeclineSpread+" us");
    }
    long glideDeclineSpread=0;

    void loopModelJitter() throws Exception {
        println("main-loop model: one dispatcher pop per pass; hardware was"
                +" min="+HW_MIN+" mean="+HW_MEAN+" max="+HW_MAX+" us");
        long widest=0;
        int matches=0;
        for (int loopHz : new int[]{2500,2000,1600,1400}) {
            for (int competingHz : new int[]{0,400,800,1200}) {
                long[] m=loopModel(loopHz,competingHz);
                if (m==null) {
                    println("  loop "+loopHz+" Hz + "+competingHz+" Hz other:"
                            +" ring overflowed or lost an output");
                    continue;
                }
                // Within 25% on all three moments: close enough to say the
                // queue reproduces the instrument, loose enough not to
                // pretend a four-parameter model is a measurement.
                boolean near=Math.abs(m[0]-HW_MIN)<=HW_MIN/4+100
                          && Math.abs(m[3]-HW_MEAN)<=HW_MEAN/4
                          && Math.abs(m[1]-HW_MAX)<=HW_MAX/4;
                if (near) matches++;
                println("  loop "+loopHz+" Hz + "+competingHz+" Hz other: min="+m[0]
                        +" max="+m[1]+" spread="+m[2]+" mean="+m[3]+" us"
                        +(near?"   <== reproduces the instrument":""));
                widest=Math.max(widest,m[2]);
            }
        }
        println("  models reproducing all three hardware moments: "+matches);
        // The instrument says the flush is punctual and only the external
        // clock spreads.  The FIFO dequeue is the one stage the internal beat
        // never uses, so sweep it alone against a 1 kHz dispatcher.
        println("dequeue-starved model: dispatcher punctual at 1 kHz,"
                +" clock_service polling slower");
        for (int serviceHz : new int[]{1000,600,400,300,250,200}) {
            long[] m=loopModel(2000,0,serviceHz);
            if (m==null) { println("  service "+serviceHz+" Hz: lost an output"); continue; }
            boolean near=Math.abs(m[0]-HW_MIN)<=HW_MIN/4+100
                      && Math.abs(m[3]-HW_MEAN)<=HW_MEAN/4
                      && Math.abs(m[1]-HW_MAX)<=HW_MAX/4;
            if (near) matches++;
            println("  service "+serviceHz+" Hz ("+(1000000/serviceHz)+" us): min="+m[0]
                    +" max="+m[1]+" spread="+m[2]+" mean="+m[3]+" us"
                    +(near?"   <== reproduces the instrument":""));
            widest=Math.max(widest,m[2]);
        }
        println("  models reproducing all three hardware moments: "+matches);
        // Two different properties, depending on what was built.
        //
        // Without a deadline the model must be ABLE to show a spread the 1 ms
        // fixture cannot, or this test is as blind as the one it supplements
        // and the instrument's 3.36 ms has no explanation here.
        //
        // With one, the same model is the measurement of the fix: the gate is
        // placed a fixed time after the ACCEPTED EDGE, so every queueing
        // configuration that reproduced the instrument must now come in under
        // the 1-2 ms target. These are model microseconds and the model
        // charges the dispatch slot but not the handler's run time, so the
        // absolute figure is a lower bound -- but before and after are the
        // same model, and the ratio is what this asserts.
        if (deadlineBuild())
            check("the deadline brings every modeled loop rate inside the"
                  +" 1-2 ms target", widest<=TARGET_SPREAD_US);
        else
            check("the loop model can exceed the 1 ms fixture's bound", widest>1000);
        println("PASS main-loop model, widest spread "+widest+" us"
                +(deadlineBuild() ? " (deadline built; was 3.4-4.2 ms without one)"
                                  : ""));
    }

    // Where the internal beat's settle actually starts: at the DAC TRANSFER.
    // An earlier build spent the countdown from the claim -- deliberately,
    // to take the phase-A dispatch out of the mean -- and that was the
    // audit's P1: the settle is a wait for the output RC, and spending it
    // before the pitch has been staged, let alone transferred, meant a
    // delayed dispatch could exhaust the whole configured interval while the
    // OLD pitch was still at the output, and the first flush then staged the
    // pitch and raised the gate on the same millisecond.  This test's
    // predecessor asserted that behaviour as a success.
    //
    // On a deadline build the wait is a COUNT target computed at phase A,
    // after the transfer clock_deadline performs there, and the main-loop
    // wrapper's pending-output service both runs phase A without any
    // dispatch and raises the gate when the target expires.  On a countdown
    // build the 1 ms task spends 0x60ee under claim 3 only, so the wait
    // still cannot begin before phase A's dispatch has sent the pitch out.
    void settleStartsAtTheTransfer() throws Exception {
        fresh(1,25000000);
        w(S+0x340,1,1); w(S+0x34a,2,26); w(S+0x38e,2,1);
        long t=10000;
        internal(t);                      // the arp advance claims the beat
        int claim=(int)r(0x625b,1);
        println("internal claim="+claim+" countdown="+r(0x60ee,1)
                +" target="+r(0x60dc,4));
        if (claim!=2) {
            println("SKIP settle start: this build claims "+claim
                    +", so there is no held gate to start");
            return;
        }
        if (!deadlineBuild()) {
            // Countdown build: the same property in its legacy form.
            long settle=r(0x60ee,1);
            check("a claimed beat carries its wait in the countdown", settle>=1);
            for (long k=1;k<=settle+3;k++) bank(t+k*1000);
            check("the countdown is not spent before phase A has transferred",
                  r(0x60ee,1)==settle && r(0x625b,1)==2 && outputTimes.isEmpty());
            long tA=t+(settle+4)*1000;
            flush(tA);                    // phase A: stage, transfer, claim 3
            check("phase A hands the countdown to the timer", r(0x625b,1)==3);
            for (long k=1;k<=settle;k++) bank(tA+k*1000);
            check("the countdown runs from phase A", r(0x60ee,1)==0);
            check("an expired countdown still waits for a flush",
                  outputTimes.isEmpty());
            long g=gateAt(tA+(settle+1)*1000, tA+(settle+3)*1000);
            check("the settle is spent after the transfer, in full",
                  g-tA>=settle*1000L);
            println("PASS the settle starts at phase A's transfer"
                    +" (countdown build)");
            return;
        }
        check("nothing is spent at the claim itself",
              r(0x60ee,1)==0 && r(0x60dc,4)==0);
        // The 1 ms task alone, for twice the settle.  This is the audit's
        // reproduction: it used to deplete the countdown here and gate with
        // a zero RC interval on the next flush.  Now there is nothing for
        // it to spend.
        for (long k=1;k<=10;k++) bank(t+k*1000);
        check("no wait can be spent before the pitch is transferred",
              r(0x625b,1)==2 && outputTimes.isEmpty());
        // One main-loop pass: phase A stages the pitch, transfers it, and
        // stores transfer + settle as the gate's target.  No dispatch.
        long tA=t+11000;
        service(tA);
        check("phase A ran from the main loop, no dispatch needed",
              r(0x625b,1)==3 && outputTimes.isEmpty());
        long target=r(0x60dc,4);
        long settleMs=(target-(tA*frequency/1000000L))/(frequency/1000);
        println("target is "+settleMs+" ms after the transfer");
        check("the stored target is a real settle past the transfer",
              settleMs>=1);
        // A pass before the target holds the gate...
        service(tA+1000);
        check("a pass before the target holds the gate", outputTimes.isEmpty());
        // ...and banks alone can never raise it, target expired or not.
        for (long k=2;k<=settleMs+3;k++) bank(tA+k*1000);
        check("an expired target still waits for a service point",
              outputTimes.isEmpty());
        long tB=tA+(settleMs+4)*1000;
        service(tB);
        check("the next main-loop pass gates it", outputTimes.size()==1);
        check("and the claim is released", r(0x625b,1)==0);
        long transferToGate=outputTimes.get(0)-tA;
        println("transfer to gate with dispatches withheld: "+transferToGate
                +" us against a "+settleMs+" ms settle");
        check("the RC settle is spent from the transfer, in full",
              transferToGate>=settleMs*1000L);
        println("PASS the settle starts at the DAC transfer; a main-loop"
                +" pass gates it");
    }

    // The bleed the instrument showed on the first deadline image, and the
    // hole this harness had: nothing here ever asked WHEN the pitch reaches
    // DAC slot 2 relative to the gate.  A deadline holds the GATE at
    // edge + D and the pitch escaped by two routes -- phase A staged it for
    // the next flush's transfer, and the 200 Hz scan wrote its own answer
    // into the same slot, which with a snap glide is the full new pitch on
    // the first scan after the step.  Either put the new note at the output
    // while the PREVIOUS note's gate was still up.
    //
    // So: what the DAC is showing must not move between the claim and the
    // gate, and must move on the gate's own pass.  The scan is run every
    // millisecond here, far harder than the 5 ms it really gets.
    void pitchWaitsForItsGate() throws Exception {
        fresh(1,25000000);
        if (!deadlineBuild()) {
            println("SKIP pitch hold: no deadline built");
            return;
        }
        // The deadline needs an acquired period, so spend four whole beats
        // first and leave the output showing the last one's pitch.
        long t=10000;
        for (int i=0;i<4;i++) {
            irq(t,true); service(t); finishStep(t+9000);
            irq(t+25000,false); t+=50000;
        }
        scan(t-1000);
        int shown=(int)r(S+0x358,2);
        // Give the coming beat a pitch of its own.  Not every config's
        // fixture steps to a different note - a single held key repeats one -
        // so push the bend strip, which every writer adds before it clamps.
        // Both the scan and the gate's staging now WANT a different word than
        // the one at the output, which is what makes a leak visible; without
        // it this test could pass on a fixture that had nothing to leak.  No
        // scan runs between here and the claim, so the output still shows the
        // old word.
        w(S+0x216,2,0x100);
        int before=outputTimes.size(), sent=transfers;
        irq(t,true);
        service(t);                     // the dequeue pass: claim, then phase A
        check("the beat is claimed and targeted on the dequeue pass",
              r(0x625b,1)==3 && r(0x60dc,4)!=0);
        if (transfers>sent) {
            // A settle IS configured for the external source, so phase A
            // transferred the pitch on purpose: the wait exists for the CV to
            // travel before the trigger.  Assert that instead - the pitch out
            // first, the gate a real settle behind it - rather than skipping,
            // which would leave this config asserting nothing at all.
            check("a settle build puts the pitch out at phase A",
                  r(S+0x358,2)!=shown);
            long due=(r(0x60dc,4)-t*(frequency/1000000L))/(frequency/1000000L);
            long g=-1;
            for (long k=1;k<=due/1000+3;k++) {
                long u=t+k*1000;
                bank(u); pending(u);
                if (outputTimes.size()>before) { g=u; break; }
            }
            check("and its gate waits the settle out", g>0 && g-t>=due);
            println("PASS a configured settle sends the pitch "+(g-t)
                    +" us ahead of its gate, which is what it is for");
            return;
        }
        check("phase A left the output where it was", r(S+0x358,2)==shown);
        long dueUs=(r(0x60dc,4)-t*(frequency/1000000L))/(frequency/1000000L);
        long gate=-1;
        for (long k=1;k<=dueUs/1000+3;k++) {
            long u=t+k*1000;
            bank(u);
            scan(u);                    // the other writer, at five times its rate
            if (outputTimes.size()==before)
                check("the pitch has not moved at ms "+k+" of a "+(dueUs/1000)
                      +" ms wait: "+r(S+0x358,2)+" vs "+shown,
                      r(S+0x358,2)==shown);
            pending(u);
            if (outputTimes.size()>before) { gate=u; break; }
        }
        check("the gate went out", gate>0);
        int after=(int)r(S+0x358,2);
        println("pitch held "+(gate-t)+" us, then moved "+shown+" -> "+after
                +" on the gate's own pass");
        check("the pitch and the gate left together, so the hold was real "
              +"and not a fixture with nothing to leak", after!=shown);
        w(S+0x216,2,0);
        println("PASS the pitch waits for its gate; the scan cannot leak it");
    }

    // Sweep real 200 Hz scan phases inside successive external claims. No
    // unclaimed scan may refresh the cache between those gates and claims.
    // Compare directly with the last gate's DAC word, including warm-up gates;
    // a separate counter once made this assertion permanently unreachable.
    void heldPitchIsNeverOlderThanTheLastGate() throws Exception {
        fresh(1,25000000);
        if (!deadlineBuild()) {
            println("SKIP held pitch age: no deadline built");
            return;
        }
        irq(10000,true); service(10000); finishStep(19000);
        if (transfers>0) {
            // With external RC settling the pitch deliberately precedes its
            // gate. pitchWaitsForItsGate() asserts that separate contract.
            println("SKIP held pitch age: external RC settle sends pitch early");
            return;
        }
        for (int hz : new int[]{25000000,60000000})
        for (int phase : new int[]{1000,1250,1750}) {
            fresh(1,hz);
            int edges=0, claimedScans=0, changedGates=0;
            for (long t=1000;t<=120000;t+=250) {
                if (t>=10000 && t<110000 && (t-10000)%5000==0) {
                    // Arp fixtures repeat a held key; bend gives those beats
                    // distinct pitches too, without synthesizing a DAC value.
                    w(S+0x216,2,0x40*(edges%8));
                    irq(t,true); edges++;
                }
                if (t>=12500 && t<112500 && (t-12500)%5000==0) irq(t,false);
                if (t%1000==0) bank(t);
                int before=outputTimes.size();
                service(t);
                if (t%1000==500) dispatch(t);
                if (outputTimes.size()>before && dac.size()>1
                    && !dac.get(dac.size()-1).equals(dac.get(dac.size()-2)))
                    changedGates++;
                boolean claimed=r(0x625b,1)>1;
                if (t%5000==phase) {
                    scan(t);
                    if (claimed && !dac.isEmpty()) {
                        claimedScans++;
                        check("held scan preserves last gate at "+hz+" Hz, phase "
                              +phase+", t="+t+": "+r(S+0x358,2)+" vs "
                              +dac.get(dac.size()-1),
                              r(S+0x358,2)==dac.get(dac.size()-1));
                        check("held scan preserves pitch mirror",
                              r(0x3212,2)==r(S+0x358,2));
                    }
                }
                if (claimed && !dac.isEmpty())
                    check("pending output/flush retains the committed pitch",
                          r(S+0x358,2)==dac.get(dac.size()-1));
            }
            check("every 200 Hz edge gates exactly once", edges==20 && outputTimes.size()==20);
            check("pitch changes make the hold observable", changedGates>=15);
            check("scans exercise successive held claims", claimedScans>=16);
            check("the FIFO drains without overrun", r(0x6234,1)==r(0x6235,1)
                  && r(0x6258,2)==0 && r(0x625b,1)==0);
            println("PASS held pitch: "+hz+" Hz, scan phase "+phase
                    +" us, "+claimedScans+" claimed scans, 20 gates");
        }
    }

    // A configured settle is a wait for the OUTPUT to travel, so the transfer
    // that starts it has to carry the note the gate is about to sound.  The
    // held-pitch reorder is decided by the EXTERNAL settle setting, and the
    // internal beat shares the path: deciding first for it transferred
    // whatever was already in the buffer and spent the whole interval on the
    // OLD note, leaving the new one no settling at all.  Timestamps alone
    // could not see that, which is why this asserts the VALUE.
    void internalSettleTransfersTheNewPitch() throws Exception {
        int settleMs=askInternalSettleMs();
        fresh(1,25000000);
        if (settleMs<=0) {
            println("SKIP internal settle pitch: this build holds no gate");
            return;
        }
        w(S+0x340,1,1); w(S+0x34a,2,26); w(S+0x38e,2,1);
        long t=10000;
        scan(t-1000);
        int shown=(int)r(S+0x358,2);
        w(S+0x216,2,0x100);             // give the beat a pitch of its own
        int sent=transfers;
        internal(t);
        check("the internal beat is claimed", r(0x625b,1)==2);
        long g=-1;
        for (long k=1;k<=settleMs+12;k++) {
            long u=t+k*1000;
            bank(u); service(u);
            if (!outputTimes.isEmpty()) { g=u; break; }
        }
        w(S+0x216,2,0);
        check("the beat gated", g>0);
        check("phase A transferred something", transfers>sent);
        int carried=transferPitches.get(transferPitches.size()-1);
        int gated=(int)r(S+0x358,2);
        println("phase A transferred "+carried+"; the gate sounded "+gated
                +"; the output had been showing "+shown);
        check("the settle was spent on the note about to sound, not the one "
              +"already out: transferred "+carried+" vs gated "+gated,
              carried==gated);
        check("and the fixture had a pitch change to get wrong",
              gated!=shown);
        println("PASS the internal settle transfers the new pitch");
    }

    // Keep the selected beat, its pitch, its RC interval and its diagnostic
    // source when GPIO presence changes. Exercise both sides of phase A;
    // merely checking the FIFO after phase A missed both source races.
    void pendingStepKeepsItsSource(boolean edgeBeforePhaseA, int hz, int settleMs)
            throws Exception {
        fresh(1,hz);
        boolean diagnostic=r(0x8001c6b0L,4)==0x8001bbc0L;
        w(S+0x340,1,1); w(S+0x34a,2,26); w(S+0x38e,2,1);
        scan(9000);
        long oldPitch=r(S+0x358,2);
        w(S+0x216,2,0x100);
        internal(10000);
        check("internal beat owns the claim", r(0x625b,1)==2 && r(0x6237,1)==0);
        long note=r(S+0x352,2), consumer=r(0x6235,1);
        long phaseAt=edgeBeforePhaseA ? 11000 : 10000;
        if (!edgeBeforePhaseA) pending(phaseAt);
        irq(edgeBeforePhaseA ? 10500 : 11000,true);
        service(11000);
        check("presence changes without changing the step's owner",
              r(0x6236,1)==1 && r(0x6237,1)==0);
        check("external arrival cannot gate before the internal settle",
              outputTimes.isEmpty() && r(0x625b,1)==3);
        check("phase A transfers the new pitch even after GPIO presence changes",
              transfers==1 && transferPitches.get(0)!=oldPitch);
        long staged=transferPitches.get(0);
        long due=phaseAt+settleMs*1000L;
        check("target retains the internal transfer-relative settle",
              r(0x60dc,4)==due*(frequency/1000000L));
        check("external edge stays queued behind the internal note",
              r(0x6235,1)==consumer && r(S+0x352,2)==note);
        scan(11500);
        check("takeover scan leaves the settling pitch and mirror intact",
              r(S+0x358,2)==staged && r(0x3212,2)==staged);
        boolean internalGated=false;
        for (long t=12000;t<=due+30000;t+=250) {
            if (t%1000==0) bank(t);
            service(t);
            if (t%1000==0) flush(t);
            if (t%5000==1500) scan(t);
            if (outputTimes.isEmpty()) {
                check("a pending internal step keeps its note and pitch",
                      r(S+0x352,2)==note && r(S+0x358,2)==staged);
                check("no dequeue before the internal gate", r(0x6235,1)==consumer);
            } else if (!internalGated) {
                internalGated=true;
                check("internal gate follows its complete RC interval",
                      outputTimes.get(0)==due && dac.get(0)==staged);
                if (diagnostic) {
                    check("internal gate is measured as internal despite input presence",
                          r(0x6038,1)==0 && r(0x603c,2)==1 && r(0x62f0,4)==0);
                    check("internal sample uses its own claim timestamp",
                          r(0x6032,2)==(((due-10000)*frequency/1000000L)>>5));
                }
            }
            if (outputTimes.size()==2 && r(0x6234,1)==r(0x6235,1)
                && r(0x625b,1)==0 && r(0x6237,1)==0) break;
        }
        check("the pending beat and queued external edge both gate once",
              internalGated && outputTimes.size()==2 && r(0x6234,1)==r(0x6235,1)
              && r(0x625b,1)==0 && r(0x6237,1)==0 && r(0x6258,2)==0);
        if (diagnostic) check("the external gate changes diagnostic population",
                              r(0x6038,1)==1);
        println("PASS pending source: "+hz+" Hz, edge "
                +(edgeBeforePhaseA ? "before phase A" : "during settling")
                +", internal gate "+due+" us, queued edge drained");
    }

    void anEdgeWaitsForAPendingStep() throws Exception {
        int settleMs=askInternalSettleMs();
        if (!deadlineBuild() || settleMs<=0) {
            println("SKIP pending takeover: no held internal step to take over");
            return;
        }
        for (int hz : new int[]{25000000,60000000})
            for (boolean before : new boolean[]{true,false})
                pendingStepKeepsItsSource(before,hz,settleMs);
    }

    // The lever the audit asked for, proven end to end: a ready output no
    // longer waits for event 17.  The wrapper services pending output around
    // the dispatcher, so with every dispatch withheld -- no flush, no
    // event-17 pop, nothing -- a claimed external beat still gates at
    // edge + deadline, on the first bare pass after its target expires.
    void pendingGatesWithoutADispatch() throws Exception {
        fresh(1,25000000);
        if (!deadlineBuild()) {
            println("SKIP pending service: no deadline built");
            return;
        }
        // Acquire a period with completed beats first; the deadline does not
        // apply before one is acquired.
        long t=10000;
        for (int i=0;i<4;i++) {
            irq(t,true); service(t); finishStep(t+9000);
            irq(t+25000,false); t+=50000;
        }
        int before=outputTimes.size();
        irq(t,true);
        service(t);
        check("the beat is claimed and targeted on the dequeue pass",
              r(0x625b,1)==3 && r(0x60dc,4)!=0);
        check("no gate yet: the deadline is ahead",
              outputTimes.size()==before);
        // The stored target says when the gate is due -- the deadline plus
        // any configured settle, edge-relative in this fixture since the
        // edge, the dequeue and the transfer share one microsecond here.
        long dueUs=(r(0x60dc,4)-t*(frequency/1000000L))/(frequency/1000000L);
        long gate=-1;
        for (long k=1;k<=dueUs/1000+3;k++) {
            bank(t+k*1000); pending(t+k*1000);
            if (outputTimes.size()>before) { gate=t+k*1000; break; }
        }
        check("the pending service raised the gate with every dispatch"
              +" withheld", gate>0);
        println("gate at edge + "+(gate-t)+" us under a withheld dispatcher;"
                +" the target asked for "+dueUs);
        check("and it landed on the target, not before or a dispatch after",
              gate-t==dueUs && dueUs>=4000);
        check("the claim is released", r(0x625b,1)==0);
        println("PASS a ready output does not wait for event 17");
    }

    // The internal beat under the same ring model loopModel() runs for the
    // external one, and for the same reason: the instrument has no 1 kHz
    // loop, so a fixture that ticks flush() every millisecond cannot show
    // what dispatch queueing costs.  The internal beat is claimed in the
    // factory half of an event-17 dispatch (internal()) whose wrapper half
    // (flush()) has already run, so both halves are driven from one pop.
    //
    // Hardware for comparison -- image f0353987, arp at 8.15 Hz, n=731:
    // min 4.23 ms, max 8.54 ms, spread 4.31 ms claim to gate.
    //
    // {min, max, spread, mean, beats} in microseconds, or null if the ring
    // overflowed or a beat lost its trigger.
    long[] internalLoopModel(int loopHz, int competingHz) throws Exception {
        fresh(1,25000000);
        w(S+0x340,1,1);
        // 26 ms is a whole number of milliseconds and not a multiple of the
        // 5 ms scan, so successive beats land on every phase of the grid.
        w(S+0x34a,2,26); w(S+0x38e,2,1);
        final int warm=6;
        final long loopUs=1000000L/loopHz;
        final long competeUs=competingHz>0?1000000L/competingHz:0;
        final List<Integer> ring=new ArrayList<>();
        final long end=1210000;
        long tTimer=10000, tLoop=10000;
        long tCompete=competeUs>0?10000:Long.MAX_VALUE;
        boolean overflow=false;
        while (tTimer<=end || tLoop<=end) {
            long t=Math.min(Math.min(tTimer<=end?tTimer:Long.MAX_VALUE,
                                     tLoop<=end?tLoop:Long.MAX_VALUE),
                            tCompete<=end?tCompete:Long.MAX_VALUE);
            if (t==tTimer) {
                // The 1 ms task is on the timer and is punctual: it is what
                // spends the settle, and it is the one stage here that does
                // not queue.
                bank(t);
                if (ring.size()>=32) overflow=true; else ring.add(EV_FLUSH);
                if (t%5000==0) { if (ring.size()>=32) overflow=true; else ring.add(EV_SCAN); }
                tTimer+=1000;
            }
            if (t==tCompete) {
                if (ring.size()>=32) overflow=true; else ring.add(0);
                tCompete+=competeUs;
            }
            if (t==tLoop) {
                if (!ring.isEmpty()) {
                    int ev=ring.remove(0);
                    // One pop is one event 17: the wrapper's half, where the
                    // fast trigger runs, and then the factory's, where the
                    // arp advance claims the next beat.
                    if (ev==EV_FLUSH) { flush(t); internal(t); }
                    else if (ev==EV_SCAN) scan(t);
                }
                // The wrapper's pending-output service, on every pass, as on
                // the instrument: the claim made in the factory half of this
                // very pop gets its phase A here, without waiting for the
                // next event-17 pop.
                if (deadlineBuild()) pending(t);
                tLoop+=loopUs;
            }
        }
        // A beat claimed in the last dispatch of the run has no gate yet, so
        // the two lists are allowed to differ by the one still in flight --
        // but by no more, or a trigger really was lost and the pairing below
        // would be charging one beat's gate to another beat's claim.
        modelNote=" beats="+beatTimes.size()+" outputs="+outputTimes.size()
                  +(overflow?" ring OVERFLOWED":"");
        if (overflow) return null;
        if (outputTimes.size()!=beatTimes.size()
            && outputTimes.size()!=beatTimes.size()-1) return null;
        if (outputTimes.size()<warm+8) return null;
        long lo=Long.MAX_VALUE, hi=Long.MIN_VALUE, sum=0; int n=0;
        for (int i=warm;i<outputTimes.size();i++) {
            long d=outputTimes.get(i)-beatTimes.get(i);
            lo=Math.min(lo,d); hi=Math.max(hi,d); sum+=d; n++;
        }
        return new long[]{lo,hi,hi-lo,sum/n,n};
    }

    // Hardware, image f0353987, arp at 8.15 Hz, n=731, claim to gate.
    // BEFORE the deadline and before clock_ms_tick counted under claim 2.
    static final long HW_INT_MIN=4230, HW_INT_MAX=8540;
    String modelNote="";

    // Detected from the emitted image, not a build flag: clock_fast_trigger
    // grows a fourth pool word naming clock_deadline only when one is built.
    boolean deadlineBuild() { return r(0x8001c1fcL,4)==0x8001bd40L; }

    // The owner's target, and the whole point of the deadline: 1-2 ms peak to
    // peak on both clocks under every build option.
    static final long TARGET_SPREAD_US=2000;

    void internalDispatchModel() throws Exception {
        // Ask the firmware what it was built with rather than assuming the
        // shipped settle: no-gate-settle claims 1 and holds no gate at all,
        // and the two-dispatch structure is not what that build does.
        fresh(1,25000000);
        w(0x6236,1,0); w(0x60ee,1,0); w(0x625b,1,0); w(S+0x340,1,1);
        call(0x8001c700L,0x100);
        final int claim=(int)r(0x625b,1);
        final long settleMs=askInternalSettleMs();
        println("internal beat under the ring model: hardware was min="
                +HW_INT_MIN+" max="+HW_INT_MAX+" spread="+(HW_INT_MAX-HW_INT_MIN)
                +" us; this build claims "+claim+" with a "+settleMs+" ms settle");
        long widest=0, nominal=Long.MAX_VALUE;
        int matches=0;
        for (int loopHz : new int[]{2500,2000,1600,1400}) {
            for (int competingHz : new int[]{0,400,800,1200}) {
                long[] m=internalLoopModel(loopHz,competingHz);
                if (m==null) {
                    println("  loop "+loopHz+" Hz + "+competingHz+" Hz other:"
                            +" no usable pairing --"+modelNote);
                    continue;
                }
                // Strict on purpose, and stricter than loopModelJitter's
                // percentage bands: the model has to REACH both hardware
                // bounds, not merely land within a quarter of each. A band
                // that wide called a 6.4 ms maximum a match for an 8.5 ms
                // one, which is the whole quantity in question.
                boolean near=m[0]<=HW_INT_MIN && m[1]>=HW_INT_MAX;
                if (near) matches++;
                println("  loop "+loopHz+" Hz + "+competingHz+" Hz other: min="+m[0]
                        +" max="+m[1]+" spread="+m[2]+" mean="+m[3]+" us over "+m[4]+" beats"
                        +(near?"   <== brackets the instrument":""));
                widest=Math.max(widest,m[2]);
                nominal=Math.min(nominal,m[3]);
            }
        }
        println("  models bracketing both hardware bounds: "+matches);
        // Two separate readings, and only the first of them is settled here.
        //
        // The MEAN is structural and the model pins it: about 6 ms for a
        // nominal 5 ms settle, because phase A costs a whole dispatch before
        // the countdown starts at all. That is the correction to the doc.
        //
        // The SPREAD is not explained. Queueing alone tops out around 1.4 ms
        // before the ring overflows, against 4.31 ms on the instrument, and
        // the instrument's 4.23 ms minimum is BELOW the floor this structure
        // can produce (one dispatch plus a 4 ms countdown). A term is
        // missing, and saying so is the result -- so this asserts only what
        // the model does establish and reports the shortfall rather than
        // dressing it up as agreement.
        // The wait starts at phase A's TRANSFER now -- anything earlier was
        // the settle-before-pitch defect -- and the wrapper's pending
        // service runs phase A on the pass right behind the claim, so the
        // mean is the settle plus main-loop service and nothing else.
        // Before the pending service this model returned 5961-6000 us for a
        // nominal 5000 (a whole event-17 dispatch sat before phase A); a
        // mean back above settle + 1.5 ms is that dispatch having returned,
        // and a mean BELOW the settle is the defect having returned.
        if (claim==2 && deadlineBuild())
            check("the internal wait is the settle plus main-loop service",
                  nominal>=settleMs*1000L && nominal<=settleMs*1000L+1500);
        check("the internal beat is inside the 1-2 ms target under the model",
              !deadlineBuild() || widest<=TARGET_SPREAD_US);
        println("PASS internal ring model: mean "+nominal+" us against a"
                +" nominal "+(settleMs*1000L)+" us settle; widest modeled spread "
                +widest+" us, against "+(HW_INT_MAX-HW_INT_MIN)
                +" us measured on the instrument before the deadline");
    }

    // The clock-latency diagnostic's accumulators sit OUTSIDE the
    // 0x6232..0x62df sweep the startup initialiser runs, and nothing else
    // clears them. On the instrument they came up holding old RAM, so the
    // running sum and count were seeded with garbage and the published mean
    // came out ABOVE the published max. Nothing here could have caught that:
    // fresh() zeroes RAM 0..0x8000 before every test, so these cells only
    // ever started clean. This seeds them deliberately and re-runs startup.
    //
    // Detected from the emitted image rather than a build flag: the scan
    // path's gate pool at 0x8001c6b0 names the shim only in a diagnostic
    // build, so an ordinary build skips this without pretending to pass.
    //
    // The scan profiler keeps its accumulators in these same cells and says
    // it needs no initialisation, which is the opposite requirement. Both
    // statements hold because the two are never in one build: tools/options.py
    // refuses that pair, since the profiler would also overwrite the numbers
    // this diagnostic publishes.
    void latencyCellsCleared() throws Exception {
        fresh(1,25000000);
        if (r(0x8001c6b0L,4)!=0x8001bbc0L) {
            println("SKIP clock-latency cells: not a diagnostic build");
            return;
        }
        long[] cells={0x6032,0x6034,0x6038,0x603a,0x603c,0x6040,0x6042,
                      0x6044,0x62f0,0x62f2};
        for (long a : cells) w(a,2,0xbeef);
        call(0x80007bf4L,0x80007bf8L);   // the real startup hook
        for (long a : cells)
            check("clock-latency cell 0x"+Long.toHexString(a)
                  +" cleared at startup", r(a,2)==0);
        println("PASS clock-latency accumulators start from zero");
    }

    // Split diagnostic samples at clock_settle.  The fixture gives the FIFO
    // 750 us before service/note selection claims the step and another 1250 us
    // before the flush raises the gate, so both boundaries have independent,
    // exact expectations.
    //
    // Then three beats that a single sample cannot distinguish.  This shim
    // published MEANS until the mean split was taken on hardware; the cells,
    // the units, the ordering guard and the one-sample expectations above are
    // identical either way, so a test that feeds one beat passes unchanged
    // against either firmware.  A smaller beat is what separates them: it
    // drags a mean down and leaves a maximum alone.  A larger beat then has
    // to move both cells, or they are merely latched by the first sample.
    //
    // Finally, corrupt the per-step claim age so it lands after the gate:
    // that negative control must be discarded, proving the ordering guard is
    // exercised rather than merely asserted in the test.
    void latencySplitsAtClaim() throws Exception {
        fresh(1,25000000);
        if (r(0x8001c6b0L,4)!=0x8001bbc0L) {
            println("SKIP split clock latency: not a diagnostic build");
            return;
        }
        irq(10000,true);
        int g0=outputTimes.size();
        service(10750);
        long wantClaim=((750L*frequency)/1000000L)>>5;
        check("claim stamp measures edge through note selection",
              r(0x6044,2)==wantClaim);
        // The first beat of a session has no acquired period, so no deadline
        // applies -- and the wrapper's pending service then gates it on the
        // dequeue pass itself. Without a deadline it rides the next flush.
        long gate1=outputTimes.size()>g0 ? 10750 : gateAt(12000,26000);
        long wantTotal=(((gate1-10000)*frequency)/1000000L)>>5;
        check("both maxima share one completed sample", r(0x603c,2)==1);
        check("published edge-to-claim maximum reaches the claim boundary",
              r(0x6032,2)==wantClaim);
        check("published edge-to-gate maximum reaches the physical gate call",
              r(0x6034,2)==wantTotal);
        check("claim-to-gate is the remainder of the directly measured path",
              r(0x6034,2)-r(0x6032,2)==wantTotal-wantClaim);

        // Smaller at the CLAIM: a mean would fall, a maximum holds. The
        // whole-path figure is a different matter now -- a deadline gives
        // every beat that meets it the same edge-to-gate, which is the fix
        // itself and is asserted here on the firmware's own diagnostic.
        irq(50000,false); irq(60000,true); service(60400);
        long gate2=gateAt(61000,76000);
        check("a smaller beat is counted", r(0x603c,2)==2);
        check("a smaller beat does not move the claim maximum",
              r(0x6032,2)==wantClaim);
        // The FIRST edge of a session has no acquired period, and the
        // deadline does not apply until there is one -- so beat one gates
        // promptly and beat two is held to the deadline. That makes beat two
        // the longer whole path, not the shorter one, and the shorter case
        // moves to beat four below where both beats are under a deadline.
        long wantTotal2=(((gate2-60000)*frequency)/1000000L)>>5;
        check("the second beat's whole path is published",
              r(0x6034,2)==Math.max(wantTotal,wantTotal2));
        check("a deadline lengthens the whole path of a beat that meets it",
              !deadlineBuild() || wantTotal2>wantTotal);

        // Larger on both boundaries. A beat serviced well past its deadline
        // is the way to get one now: it gates at once rather than being held,
        // so its whole path is longer than an on-time beat's.
        irq(100000,false); irq(110000,true);
        int g2=outputTimes.size();
        service(115500);
        // Serviced past its deadline, the beat gates on that same pass.
        long gate3=outputTimes.size()>g2 ? 115500 : gateAt(116000,132000);
        long wantClaim3=((5500L*frequency)/1000000L)>>5;
        long wantTotal3=(((gate3-110000)*frequency)/1000000L)>>5;
        check("a larger beat is counted", r(0x603c,2)==3);
        check("a larger beat raises the claim maximum",
              r(0x6032,2)==wantClaim3);
        check("a larger beat raises the whole-path maximum",
              r(0x6034,2)==wantTotal3 && wantTotal3>wantTotal2);
        check("a beat already past its deadline gates on the service pass",
              !deadlineBuild() || gate3==115500);

        // And a fourth, on time and under the same deadline as beat two: a
        // mean would fall on both boundaries, a maximum holds both.
        irq(150000,false); irq(160000,true); service(160400);
        long gate4=gateAt(161000,177000);
        long wantTotal4=(((gate4-160000)*frequency)/1000000L)>>5;
        check("a fourth beat is counted", r(0x603c,2)==4);
        check("an on-time beat is shorter than the late one",
              wantTotal4<wantTotal3);
        check("a smaller beat does not move the claim maximum",
              r(0x6032,2)==wantClaim3);
        check("a smaller beat does not move the whole-path maximum",
              r(0x6034,2)==wantTotal3);
        check("the deadline gives two on-time beats the same edge-to-gate",
              !deadlineBuild() || gate4-160000==gate2-60000);

        long samples=r(0x603c,2), upstream=r(0x6032,2), total=r(0x6034,2);
        irq(200000,false); irq(210000,true); service(210750);
        w(0x6044,2,0x3fff);              // negative control: claim after gate
        int before=outputTimes.size();
        gateAt(212000,228000);
        check("negative control still raises the physical gate",
              outputTimes.size()==before+1);
        check("claim-after-gate negative control is rejected from both maxima",
              r(0x603c,2)==samples && r(0x6032,2)==upstream
              && r(0x6034,2)==total);
        println("PASS latency maxima track at the claim; "
                +"smaller beat held, larger beat followed, control discarded");
    }

    // The internal beat has no accepted edge -- 0x6240 belongs to the external
    // path and is stale here -- so the shim times it from the claim stamp
    // clock_settle leaves at 0x62f0, and publishes the MINIMUM and MAXIMUM of
    // claim-to-gate rather than a whole path it cannot see.  That pair is the
    // shared downstream half measured on a source carrying none of the FIFO,
    // clock_service or note selection the external path spends before its
    // claim, which is the half a deadline computed at the claim cannot fix.
    //
    // Then the reason those cells are cleared on a source change.  The bench
    // procedure holds a key BEFORE starting the external clock, so the arp
    // beats internally first and leaves its claim-to-gate samples in the very
    // cells the external capture then accumulates into.  A maximum never comes
    // back down, so without the reset the external figures would quietly carry
    // an internal population -- and with this build's 5 ms internal settle the
    // internal samples are the LARGER ones, so they would win outright.
    void latencyTimesTheInternalBeat() throws Exception {
        fresh(1,25000000);
        if (r(0x8001c6b0L,4)!=0x8001bbc0L) {
            println("SKIP internal-beat latency: not a diagnostic build");
            return;
        }
        w(S+0x34a,2,26); w(S+0x38e,2,1); w(S+0x340,1,1);
        for (long tick=10000; tick<=400000; tick+=1000) {
            bank(tick); service(tick); internal(tick); flush(tick);
            if (tick%5000==0) scan(tick);
        }
        long samples=r(0x603c,2), lo=r(0x6032,2), hi=r(0x6034,2);
        check("no external presence during the internal capture",
              r(0x6236,1)==0);
        check("internal beats are timed at all", samples>0 && hi>0);
        check("the internal minimum is a real sample, not the cleared cell",
              lo>0);
        check("internal minimum does not exceed internal maximum", lo<=hi);
        check("internal claim-to-gate fits the published field", hi<0x3fff);
        println("  internal claim-to-gate: min="+lo+" max="+hi+" units over "
                +samples+" samples");

        // One settle repeated at a punctual fixture cannot separate a minimum
        // from a maximum -- both cells hold the same number above.  Drive one
        // more beat and doctor its claim stamp between the claim and the gate,
        // the way the external negative control doctors 0x6044, so this beat
        // is genuinely SHORTER than every real one before it.
        // The loop above can leave its last claim in flight at the boundary;
        // let it finish on its own target first, or the doctored beat below
        // never gets claimed and the doctored stamp is never consumed.
        for (long tick=401000; tick<=406000; tick+=1000) {
            bank(tick); service(tick); flush(tick);
        }
        // Force a fresh claim wherever the tempo phase happens to be.
        long tc=407000;
        while (r(0x625b,1)==0 && tc<440000) { internal(tc); tc+=1000; }
        check("the doctored beat was claimed", r(0x625b,1)!=0);
        if (deadlineBuild()) {
            // Phase A first, so the gate's COUNT target is known; then
            // doctor the claim stamp to two milliseconds before it, making
            // this beat genuinely shorter than every settle-length sample
            // before it.
            flush(tc);
            w(0x62f0,4,r(0x60dc,4)-2L*(frequency/1000));
        } else {
            w(0x62f0,4,((tc+3000L)*frequency)/1000000L);
        }
        for (long tick=tc+1000; tick<=tc+9000; tick+=1000) {
            bank(tick); flush(tick);
            if (tick%5000==0) scan(tick);
        }
        check("a shorter internal sample lowers the published minimum",
              r(0x6032,2)<lo);
        check("a shorter internal sample leaves the published maximum",
              r(0x6034,2)==hi);
        println("  after the short beat: min="+r(0x6032,2)+" max="+r(0x6034,2));

        // The external clock arrives mid-session, exactly as the protocol has
        // it arrive.  Let the internal step in flight finish first -- without
        // that the edge below lands on a busy step and never reaches a gate.
        for (long tick=tc+10000; tick<=tc+50000; tick+=1000) {
            bank(tick); service(tick); flush(tick);
            if (tick%5000==0) scan(tick);
        }
        long internalMax=r(0x6034,2);
        long tEdge=tc+60000;
        irq(tEdge,true);
        int gX=outputTimes.size();
        service(tEdge+750);
        // No period is acquired from a first edge, so a deadline build's
        // pending service gates it on the dequeue pass; an older build
        // rides the next flush.
        long gate=outputTimes.size()>gX ? tEdge+750
                                        : gateAt(tEdge+2000,tEdge+16000);
        long wantClaim=((750L*frequency)/1000000L)>>5;
        long wantTotal=(((gate-tEdge)*frequency)/1000000L)>>5;
        check("an external edge is now the source", r(0x6236,1)!=0);
        check("the source change restarts the count at its own first sample",
              r(0x603c,2)==1);
        check("the external claim maximum carries no internal sample",
              r(0x6032,2)==wantClaim);
        check("the external whole-path maximum carries no internal sample",
              r(0x6034,2)==wantTotal);
        check("the internal maximum really was the larger of the two, so the "
              +"reset is what removed it", internalMax>wantTotal);
        println("PASS internal beat timed from its claim; "
                +"source change clears the pair");
    }

    // A mis-attributed sample used to destroy the published maximum for a
    // whole session. The instrument published 16383 -- 0x3fff exactly --
    // because the first version timed a drained backlog against 0x623c, the
    // ISR's NEWEST accepted stamp, which belonged to another edge.
    //
    // Two changes, both checked here. The shim now times against 0x6240, the
    // stamp of the edge the dequeue is actually acting on, and a sample that
    // will not fit the field is DISCARDED rather than clamped into the max.
    // So a stall may cost its own measurement; it can no longer peg the
    // figure the owner reads for the rest of the power-up.
    void latencyIgnoresABacklog() throws Exception {
        fresh(1,25000000);
        if (r(0x8001c6b0L,4)!=0x8001bbc0L) {
            println("SKIP backlogged latency: not a diagnostic build");
            return;
        }
        long t=10000;
        for (int i=0;i<4;i++) {
            irq(t,true); service(t); finishStep(t+9000); irq(t+50000,false); t+=100000;
        }
        long settledClaim=r(0x6032,2), settledTotal=r(0x6034,2);
        long samples=r(0x603c,2);
        check("ordinary beats are timed at all",
              samples>0 && settledTotal>0);
        check("the claim boundary is inside the whole path",
              settledClaim<=settledTotal && settledTotal<0x3fff);
        // Queue more edges than the main loop takes, then drain them all at
        // once, the way a flash write or any other stall makes it drain.
        for (int i=0;i<40;i++) { irq(t+i*5000,true); irq(t+2500+i*5000,false); }
        check("the backlog really did overrun the FIFO", r(0x6258,2)>0);
        // Drain with the WHOLE completing machinery -- timer, flush and scan,
        // exactly what finishes a step on the instrument. An earlier version
        // drove service() and scan() alone, which a deadline build leaves as
        // claim 2 forever: it reported PASS with thirty entries still queued
        // and no backlogged gate ever reaching the diagnostic, so its
        // unchanged-maxima assertion was also satisfied by measuring nothing.
        long drain=t+400000;
        int outBefore=outputTimes.size();
        for (int i=0;i<31;i++) {
            long tD=drain+i*5000;
            service(tD); bank(tD); flush(tD);
            if (i%5==0) scan(tD);
        }
        println("  after the drain: claim/total maxima "+r(0x6032,2)+"/"
                +r(0x6034,2)+" units, were "+settledClaim+"/"+settledTotal
                +"; "+(outputTimes.size()-outBefore)+" backlogged gates");
        check("the backlogged gates actually happened",
              outputTimes.size()==outBefore+31);
        check("the drain emptied the FIFO and left nothing in flight",
              r(0x6234,1)==r(0x6235,1) && r(0x6237,1)==0 && r(0x625b,1)==0);
        check("a drained backlog moves neither published maximum",
              r(0x6032,2)==settledClaim && r(0x6034,2)==settledTotal);
        check("a drained backlog is not counted as samples",
              r(0x603c,2)==samples);
        println("PASS backlogged edges discarded; claim/total maxima "
                +r(0x6032,2)+"/"+r(0x6034,2)+" units over "
                +r(0x603c,2)+" samples");
    }

    // The shared sample counter is a halfword and the shim used to increment
    // it blind: ST.H stored bit 16 away, the next increment read 1, and the
    // internal half's first-sample branch reseeded BOTH extrema even though
    // the source never changed -- about 44 minutes in at 25 beats/second.
    // The count saturates at 0xffff now. Seed the reachable pre-wrap state
    // and prove the counter pins while the extrema stay monotonic.
    void latencyCountSaturates() throws Exception {
        fresh(1,25000000);
        if (r(0x8001c6b0L,4)!=0x8001bbc0L) {
            println("SKIP counter saturation: not a diagnostic build");
            return;
        }
        // Internal beats, so the pair is (min,max) and a reseed is visible
        // as a minimum that jumps UP to whatever sample reseeded it.
        w(S+0x34a,2,26); w(S+0x38e,2,1); w(S+0x340,1,1);
        for (long tick=10000; tick<=120000; tick+=1000) {
            bank(tick); service(tick); internal(tick); flush(tick);
            if (tick%5000==0) scan(tick);
        }
        long lo=r(0x6032,2), hi=r(0x6034,2), n=r(0x603c,2);
        check("saturation fixture accumulated samples", n>1 && lo>0 && lo<=hi);
        w(0x603c,2,0xffff);
        for (long tick=121000; tick<=200000; tick+=1000) {
            bank(tick); service(tick); internal(tick); flush(tick);
            if (tick%5000==0) scan(tick);
        }
        println("  count "+r(0x603c,2)+", min "+r(0x6032,2)+" (was "+lo
                +"), max "+r(0x6034,2)+" (was "+hi+")");
        check("the sample counter saturates instead of wrapping",
              r(0x603c,2)==0xffff);
        check("saturated samples cannot reseed the published minimum",
              r(0x6032,2)<=lo && r(0x6032,2)>0);
        check("saturated samples keep the published maximum monotonic",
              r(0x6034,2)>=hi);
        println("PASS sample counter saturates at 0xffff; extrema monotonic");
    }

    public void run() throws Exception {
        sequencer=getScriptArgs().length==0 || !getScriptArgs()[0].equals("arp");
        try {
            // The settle variants exist to hold the TRIGGER to its bound at
            // settings the shipped build does not use. The rest of the suite
            // is fixture-timed for the shipped settle and says nothing extra
            // under them, so those builds run the jitter set alone.
            boolean jitterOnly = List.of(getScriptArgs()).contains("jitter");
            if (jitterOnly) {
                bitFieldInstructions(); latencyCellsCleared(); latencySplitsAtClaim(); latencyTimesTheInternalBeat(); latencyIgnoresABacklog(); latencyCountSaturates(); riseJitter(); internalJitter(); declinedGlideJitter(); loopModelJitter(); settleStartsAtTheTransfer(); pitchWaitsForItsGate(); heldPitchIsNeverOlderThanTheLastGate(); internalSettleTransfersTheNewPitch(); anEdgeWaitsForAPendingStep(); pendingGatesWithoutADispatch(); internalDispatchModel(); keyboardKeepsTheScan();
            } else {
            bitFieldInstructions(); latencyCellsCleared(); latencySplitsAtClaim(); latencyTimesTheInternalBeat(); latencyIgnoresABacklog(); latencyCountSaturates(); abiAndNoise(); dispatchJitter(); riseJitter(); internalJitter(); declinedGlideJitter(); loopModelJitter(); settleStartsAtTheTransfer(); pitchWaitsForItsGate(); heldPitchIsNeverOlderThanTheLastGate(); internalSettleTransfersTheNewPitch(); anEdgeWaitsForAPendingStep(); pendingGatesWithoutADispatch(); internalDispatchModel(); keyboardKeepsTheScan(); bendAgreesWithTheScan(); scanFlushOrder(); divideAndSlow(); overflowAndWrap(); longLowAndTies(); warmRestart();
            }
            if (!jitterOnly && (getScriptArgs().length<2 || !getScriptArgs()[1].equals("quick")))
            for (int hz : new int[]{10,150,180,199,200})
                for (double duty : new double[]{0.1,0.5,0.75,0.9})
                    for (int phase : new int[]{0,250}) square(hz,duty,phase);
            println("CLOCK REGRESSION PASS: "+checks+" assertions; max GPIO ISR steps="+maxIrqSteps
                    +"; mode="+(sequencer?"sequencer":"arp"));
        } finally { if(e!=null)e.dispose(); }
    }
}
