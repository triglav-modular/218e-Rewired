// Real pad transport, factory tempo/setup and clock dispatch with arp OFF.
// Loaded by test_persistence.py for sequencer variants. Never flashes.
//@category Buchla218.Tests
import ghidra.app.emulator.EmulatorHelper;

public class SequenceTransportRegression extends ClockRegression {
    boolean clock, autoStart=true, rangeFixture;
    int selected;
    long toDoubleFn, mulDoubleFn, toIntFn;
    @Override void step() throws Exception {
        long p=pc();
        // The installed AVR32 p-code returns zero from the untouched factory
        // soft-float conversion chain (even for nonzero factory inputs).
        // Model ONLY these three calls at their original call sites. Rate
        // gating, raw-input conditioning, table lookup, stored period and
        // factory start/stop setup still execute their actual instructions.
        if(p==toDoubleFn||p==mulDoubleFn||p==toIntFn) {
            long lr=e.readRegister("LR").longValue()&0xffffffffL;
            if(p==toDoubleFn&&lr==0x80002bceL) {
                putDouble((int)e.readRegister("R12").longValue()); jump(lr); return;
            }
            if(p==mulDoubleFn&&lr==0x80002be2L) {
                putDouble(getDouble(10)*getDouble(8)); jump(lr); return;
            }
            if(p==toIntFn&&lr==0x80002beeL) {
                e.writeRegister("R12",(int)getDouble(10)); jump(lr); return;
            }
        }
        if(p==0x8001b38aL) selected++;
        if(p==0x80006808L||p==0x800068ccL||p==0x8000673cL
            ||p==0x80008104L||p==0x80007efcL) {
            jump(e.readRegister("LR").longValue()); return;
        }
        if(p==0x800108fcL) throw new Exception("flash entered during sequencer transport test");
        super.step();
    }
    double getDouble(int low) {
        long bits=(e.readRegister("R"+(low+1)).longValue()<<32)
            |(e.readRegister("R"+low).longValue()&0xffffffffL);
        return Double.longBitsToDouble(bits);
    }
    void putDouble(double value) {
        long bits=Double.doubleToRawLongBits(value);
        e.writeRegister("R10",bits&0xffffffffL); e.writeRegister("R11",bits>>>32);
    }
    void button(int pad) throws Exception {
        e.writeRegister("R11",pad); call(0x8001b660L,0x100);
        check("transport stack restored",e.readRegister("SP").longValue()==0x7800);
    }
    void arp(int position) {
        w(S+0x340,1,position==1?1:0); w(S+0x341,1,position==2?1:0);
    }
    void tempo() throws Exception { call(0x80002b28L,0x100); }
    @Override void fresh(int divisor,int hz) throws Exception {
        if(clock) super.fresh(divisor,hz);
        else {
            if(e!=null)e.dispose(); e=new EmulatorHelper(currentProgram);
            e.writeMemory(toAddr(0),new byte[0x8000]);
            e.writeMemory(toAddr(8),e.readMemory(toAddr(0x80015d28L),0x2ecc)); w(0x2ed4,4,0xffffffffL);
            for(int i=0;i<=12;i++)e.writeRegister("R"+i,0);
            e.writeRegister("SR",0); for(String f:new String[]{"C","N","V","Z"})e.writeRegister(f,0);
            frequency=hz; time(0); w(0x29cc,4,hz);
            w(GPIO+0x60,4,0); w(GPIO+0xd0,4,0);
            call(0x80007bf4L,0x80007bf8L); call(0x8001ab60L,0x100);
            w(S+0x34a,2,20); w(S+0x38e,2,100); w(0x2ee0,2,20); w(0x2ee6,2,1023);
            w(0x61e0,1,16);
            for(int k=0;k<16;k++) { w(0x6160+2*k,2,485+40*k); w(0x61ee+k,1,k); }
            w(0xffff2404L,4,0); w(0xffff2410L,4,0x202);
        }
        arp(0); w(0x6158,1,0); w(S+0x21a,1,0); w(S+0x238,1,0);
        toDoubleFn=r(0x80002cf4L,4); mulDoubleFn=r(0x80002cf8L,4); toIntFn=r(0x80002cfcL,4);
        w(S+0x308,2,1000); w(S+0x2f2,2,0); w(S+0x2da,1,0); w(0x62e0,1,0);
        if(autoStart)button(1);
        if(rangeFixture) {
            // PLAY now starts the internal clock immediately. Establish
            // external ownership before counting the input/output sweep,
            // otherwise its 250 us phase case counts a legitimate internal
            // beat before the FIRST external edge as an extra input pulse.
            irq(1000,true); bank(1000); scan(5000); irq(6000,false);
            w(0x61e1,1,0);
        }
        selected=0; advances=0; periodicAdvances=0; periodic=false;
        pitches.clear(); dac.clear(); outputTimes.clear();
    }
    @Override void square(int hz,double duty,int phase) throws Exception {
        rangeFixture=true;
        try { super.square(hz,duty,phase); } finally { rangeFixture=false; }
    }
    void ticks(long start,int count) throws Exception {
        for(int i=1;i<=count;i++) {
            long t=start+i*1000L;
            // The 1 kHz DAC flush is dispatched by the same main loop as the
            // scan and runs far more often; leaving it out of the model was
            // harmless while the pitch scan alone completed a step, but the
            // beat's settle is spent by the millisecond timer now and its
            // gate goes out on the flush, so both have to be here.
            if(clock) { bank(t); service(t); flush(t); }
            internal(t); if(i%5==0)scan(t);
        }
    }
    void transport() throws Exception {
        autoStart=false;
        for(int position=0;position<3;position++) {
            fresh(1,25000000); arp(position); tempo();
            // Exercise the emitted pad-2 path, not a direct mode-byte write.
            button(1);
            check("PLAY starts independently of switch "+position,r(0x6158,1)==2&&r(S+0x34c,1)==0);
            check("factory clock setup observes PLAY",r(S+0x85,1)==1);
            check("transport does not rewrite switch",r(S+0x340,1)==(position==1?1:0)
                &&r(S+0x341,1)==(position==2?1:0));
            w(S+0x34a,2,20); w(0x2ee0,2,20); ticks(0,100);
            check("internal sequence advances with no keys",selected>=3&&outputTimes.size()>=3);
            // Changing the physical switch must not tear down a playing sequence.
            for(int other:new int[]{1,2,0}) {
                arp(other); w(S+0x354,2,0x7ff); tempo();
                check("switch change cannot tear down sequence",r(0x6158,1)==2
                    &&r(S+0x85,1)==1&&r(S+0x354,2)==0x7ff);
            }
            arp(position); tempo();
            if(clock) { w(0x6234,1,5); w(0x6235,1,3); w(0x6237,1,1); }
            w(0x60ee,1,1); button(1);
            check("STOP ends sequence and pending trigger",r(0x6158,1)==0&&r(S+0x354,2)==0&&r(0x60ee,1)==0);
            if(clock)check("STOP discards stale queued steps",r(0x6234,1)==r(0x6235,1)&&r(0x6237,1)==0);
            check("clock setup returns to physical arp",r(S+0x85,1)==(position==0?0:1));
            int before=selected, out=outputTimes.size();
            ticks(100000,100);
            check("stopped sequence never advances",selected==before&&outputTimes.size()==out);
            if(position!=0) {
                w(S+0x21a,1,1); w(S+0x21b,1,1);
                e.writeRegister("R12",0); call(0x8001a020L,0x100);
                w(S+0x34a,2,20); w(S+0x38e,2,0); w(0x2ee0,2,20); ticks(200000,100);
                check("normal arp works after STOP",outputTimes.size()>out&&selected==before);
            }
        }
        fresh(1,25000000); button(1);
        w(S+0x308,2,300); tempo(); long slow=r(S+0x34a,2);
        w(S+0x308,2,1000); tempo(); long fast=r(S+0x34a,2);
        check("real RATE conditioning works with arp OFF",slow>fast&&fast>0);
        w(S+0x308,2,0); tempo(); check("RATE minimum retains external-only mode",r(S+0x34c,1)==1);
        w(S+0x308,2,1000); tempo(); check("RATE recovers from minimum",r(S+0x34c,1)==0);
        button(2); check("CLEAR stops clock ownership",r(0x6158,1)==0&&r(0x61e0,1)==0&&r(S+0x85,1)==0);
        autoStart=true;
        println("PASS real PLAY/STOP/CLEAR, three switch positions, switch changes, factory setup, RATE, arp handback");
    }
    void restart() throws Exception {
        fresh(1,25000000);
        irq(10000,true); bank(10000); scan(15000);
        irq(20000,false); irq(30000,true); // queued, not dispatched
        w(0x60ee,1,1); button(1);
        int before=outputTimes.size(); bank(31000); scan(35000);
        check("no queued beat after STOP",outputTimes.size()==before&&r(0x6237,1)==0);
        button(1); check("restart begins at first step",r(0x61e1,1)==0);
        bank(36000); scan(40000); check("restart cannot replay old FIFO",outputTimes.size()==before);
        irq(41000,false); irq(50000,true); bank(50000); scan(55000);
        check("first new edge after restart plays first step",outputTimes.size()==before+1&&pitches.get(before)==485);
        println("PASS external STOP/restart discards pending output and stale FIFO beats");
    }
    void legacyInputs() throws Exception {
        for(long entry:new long[]{0x80004e58L,0x80004efcL}) {
            fresh(1,25000000);
            w(S+0x308,2,0); tempo(); // external-only, arp OFF, no held keys
            int before=selected;
            call(entry,0x800051b0L); scan(5000); scan(10000); // non-clock builds retain one settle scan
            check("external event advances with arp OFF at "+Long.toHexString(entry),
                selected==before+1&&outputTimes.size()==1);
            button(1); before=selected;
            call(entry,0x800051b0L); scan(15000); scan(20000);
            check("external event cannot restart stopped sequence",selected==before&&outputTimes.size()==1);
        }
        println("PASS factory GPIO/MIDI event paths with arp OFF and clock divider disabled");
    }
    @Override public void run() throws Exception {
        sequencer=true; clock=getScriptArgs().length>0&&getScriptArgs()[0].contains("clock");
        try {
            transport();
            if(!clock)legacyInputs();
            if(clock) {
                restart(); abiAndNoise(); dispatchJitter(); divideAndSlow(); overflowAndWrap(); longLowAndTies();
                if(getScriptArgs().length<2||!getScriptArgs()[1].equals("quick"))
                    for(int hz:new int[]{10,150,180,199,200})
                        for(double duty:new double[]{0.1,0.5,0.75,0.9})
                            for(int phase:new int[]{0,250})square(hz,duty,phase);
            }
            println("SEQUENCE TRANSPORT PASS: "+checks+" assertions; arp OFF external sweep="+clock);
        } finally { if(e!=null)e.dispose(); }
    }
}
