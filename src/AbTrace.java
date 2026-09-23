// A black-box trace of one scripted session through the factory entry
// points every Rewired image shares - boot, the touch handlers, the ADC
// event, the pitch passes, the tempo routine, the GPIO ISR - recording
// what reaches the DAC slots, the USB-MIDI sender and the pulse routine
// after every step.  Run on two images by tools/ab_trace.py and diffed:
// criterion 1 (2026-09-23), "everything works as in 2.4", as evidence
// rather than reading.  Nothing here names a cave, so the same script
// runs on 2.4 and on 3.0; the owner classifies each difference.
//
// On PersistenceRegression's machine for its flash model, its BFINS and
// MOV PC,Rs repairs and its peripheral stubs, and SequenceEditRegression's
// soft-float repair for the tempo routine, copied.  Its custom-RAM checks
// are not run: they name cells of this image.  Never flashes.
//@category Buchla218.Tests
import java.util.*;

public class AbTrace extends PersistenceRegression {
    long toDoubleFn, mulDoubleFn, toIntFn;
    final List<String> events=new ArrayList<>();
    int steps;
    @Override void cold() throws Exception {
        e.writeMemory(toAddr(0),new byte[0x8000]);
        e.writeMemory(toAddr(8),e.readMemory(toAddr(0x80015d28L),0x2ecc)); w(0x2ed4,4,0xffffffffL);
        for(int i=0;i<=12;i++) e.writeRegister("R"+i,0);
        e.writeRegister("SR",0); for(String f:new String[]{"N","Z","V","C"}) e.writeRegister(f,0);
        w(0x29cc,4,25000000); w(S+0x20c,4,1); time(0);
        w(0xffff1060L,4,0); w(0xffff10d0L,4,0);
        w(0xffff2404L,4,0); w(0xffff2410L,4,0x202);
        w(0xffff10c4L,4,32); w(0xffff1c08L,4,1);
        boot();
    }
    double getDouble(int low) { return Double.longBitsToDouble((reg("R"+(low+1))<<32)|reg("R"+low)); }
    void putDouble(double value) {
        long bits=Double.doubleToRawLongBits(value);
        e.writeRegister("R10",bits&0xffffffffL); e.writeRegister("R11",bits>>>32);
    }
    @Override void step() throws Exception {
        long p=pc(), lr=reg("LR");
        if(p==toDoubleFn&&lr==0x80002bceL) { putDouble((int)reg("R12")); ret(); return; }
        if(p==mulDoubleFn&&lr==0x80002be2L) { putDouble(getDouble(10)*getDouble(8)); ret(); return; }
        if(p==toIntFn&&lr==0x80002beeL) { e.writeRegister("R12",(int)getDouble(10)); ret(); return; }
        if(p==0x80008034L) { events.add(String.format("midi %02x %02x %02x",reg("R12"),reg("R11"),reg("R10"))); ret(); return; }
        if(p==0x80008104L||p==0x80007efcL) { events.add(String.format("out%d %02x %02x %02x",p==0x80008104L?1:2,reg("R12"),reg("R11"),reg("R10"))); ret(); return; }
        if(p==0x800077f8L) { events.add("pulse-high"); ret(); return; }
        if(p==0x8000ba9cL) events.add(String.format("dac-transfer %03x",r(S+0x358,2)));
        super.step();
    }
    // The observable state after a step: the pitch target, the gate and
    // pitch DAC slots, the strip and preset slots, then what left the box.
    void trace(String label) {
        StringBuilder b=new StringBuilder(String.format("%3d %-28s",++steps,label));
        for(int i=0;i<8;i++) b.append(String.format(" %04x",r(S+0x350+2*i,2)));
        b.append("  live=").append(Long.toHexString(r(S+0x2e1,1)));
        if(!events.isEmpty()) b.append("  ").append(String.join(",",events));
        events.clear();
        println("TRACE "+b);
    }
    void musical() throws Exception { call(0x80004d4eL,0x80004d52L); call(0x80003590L); call(0x8000307cL); call(0x800031b8L,0x80003256L); }
    void touchOn(int k) throws Exception { e.writeRegister("R12",k); call(0x80005b6aL); }
    void touchOff(int k) throws Exception { e.writeRegister("R12",k); e.writeRegister("R11",0); call(0x80005edcL); }
    void pad(int p) throws Exception { e.writeRegister("R12",p); call(0x8000698cL); }
    void edge(long ms,boolean high) throws Exception {
        time(ms); w(0xffff1060L,4,high?32:0); w(0xffff10d0L,4,32);
        call(0x800072e4L,0x80007328L); w(0xffff10d0L,4,0);
    }
    @Override public void run() throws Exception {
        try {
            fresh();
            toDoubleFn=r(0x80002cf4L,4); mulDoubleFn=r(0x80002cf8L,4); toIntFn=r(0x80002cfcL,4);
            call(0x8000737eL,0x80007386L);
            // The other image's tables into this one's settings mirror, when
            // the driver hands them over: the pitch table, the three tuning
            // slots, the keys per period; and the applier's guard cleared so
            // the live copy at 0x854 is refreshed from them on the first pass.
            String[] args=getScriptArgs();
            if(args.length>0) {
                String[] words=new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(args[0]))).trim().split("\\s+");
                int i=0;
                for(int k=0;k<79;k++) w(0x6840+2*k,2,Integer.parseInt(words[i++],16));
                for(int k=0;k<96;k++) w(0x68e0+2*k,2,Integer.parseInt(words[i++],16));
                for(int k=0;k<3;k++) w(0x69a0+2*k,2,Integer.parseInt(words[i++],16));
                w(0x60e4,2,0);
                println("TABLES planted: "+i+" halfwords");
            }
            trace("boot");
            w(S+0x342,1,1); w(S+0x343,1,0); w(S+0x340,1,0); w(S+0x341,1,0);
            w(0x2ee6,2,1023); w(S+0x308,2,1000); w(S+0x2f2,2,0); w(0x2ee0,2,20); w(S+0x34a,2,20);
            for(int k=0;k<4;k++) w(S+0x30a+2*k,2,512);
            musical(); trace("switches set, knobs mid");
            // One key, pressed and released, with pressure rising and falling.
            touchOn(9); w(0x3490+9,1,2); for(int q:new int[]{110,300,600,600,300}) { w(0x3686+18,2,q); musical(); trace("key 9 held, raw "+q); }
            w(0x3490+9,1,0); w(0x3686+18,2,0); touchOff(9); musical(); trace("key 9 lifted");
            // A chord held through the octave pads.
            touchOn(4); w(0x3490+4,1,2); w(0x3686+8,2,400); touchOn(11); w(0x3490+11,1,2); w(0x3686+22,2,400); musical(); trace("keys 4 and 11");
            for(int p:new int[]{2,3,0,1}) { pad(p); musical(); trace("octave pad "+p); }
            w(0x3490+4,1,0); w(0x3686+8,2,0); touchOff(4); musical(); trace("key 4 lifted");
            w(0x3490+11,1,0); w(0x3686+22,2,0); touchOff(11); musical(); trace("key 11 lifted");
            // The arp in both switch positions over three held keys, the tempo routine stepping it.
            for(int position:new int[]{1,2}) {
                w(S+0x340,1,position==1?1:0); w(S+0x341,1,position==2?1:0);
                touchOn(2); touchOn(6); touchOn(9); musical(); trace("arp "+position+", three keys");
                for(int i=0;i<6;i++) {
                    // A beat as the instrument runs one: the 1 ms task, the 1 kHz flush, then event 17's two halves.
                    time(20*(i+1)); e.writeRegister("R12",0x7010); call(r(0x80007da0L,4),0x100);
                    call(r(0x8001485cL,4),0x80004f66L); call(0x80004f66L,0x80004faeL);
                    musical(); trace("arp "+position+" beat "+i);
                }
                touchOff(2); touchOff(6); touchOff(9); musical(); trace("arp "+position+" released");
            }
            w(S+0x340,1,0); w(S+0x341,1,0);
            // The knobs, each swept, with a key down.
            touchOn(7); musical();
            for(int k=0;k<4;k++) for(int v:new int[]{0,300,700,1023,512}) { w(S+0x30a+2*k,2,v); musical(); trace("knob "+(k+1)+" at "+v); }
            touchOff(7); musical(); trace("key 7 lifted");
            // The strip, touched and moved.
            w(S+0x206,1,1); for(int v:new int[]{100,1000,2000,3000}) { w(S+0x1fe,2,v); musical(); trace("strip at "+v); }
            w(S+0x206,1,0); musical(); trace("strip lifted");
            // The clock input: edges 20 ms apart, and the main-loop service between them.
            for(int i=0;i<6;i++) { long t=1000+20*i; edge(t-2,false); edge(t,true); time(t+1); call(0x80007c66L,0x80007c6aL); musical(); trace("clock edge "+i); }
            println("AB TRACE DONE: "+steps+" steps");
        } finally { if(e!=null)e.dispose(); }
    }
}
