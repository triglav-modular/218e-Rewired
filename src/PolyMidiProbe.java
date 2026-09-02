// The keyboard over a running take, in emitted firmware.  Every press and
// lift runs the real pressure state machine at 0x800053ac - the only caller
// of which is the physical scan at 0x80005300 - so the contact and lift
// handlers, the four factory note senders and the arp step engine all run in
// full, and the factory active-note table at 0x3924 is maintained by firmware
// rather than modelled here.  Only the two leaves are stubbed: the queue
// writer both port-one senders hand their three bytes to, and the vendor-bus
// note routines port two calls, whose busy-waits have no peripheral in the
// emulator.
//
// Reading the messages off the queue writer means the status byte under test
// is the one the firmware actually assembled, note and channel included.
//
// What it pins: with the arp switch OFF the keyboard plays over a take on
// MIDI, the 208 bus and the CV, and has priority - a press ends the note the
// sequencer is sounding, a held key silences the take on every output while
// it keeps time, holds the gate through the take's rests and half-step drops,
// and keeps it through STOP; its own lift is what ends it.  With the arp
// switch ON a press still means nothing.
// Usage: -postScript PolyMidiProbe.java
//@category Buchla218.Tests
import java.util.*;

public class PolyMidiProbe extends ControlRegression {
    static final long QUEUE=0x80009a64L,          // DIN/USB three-byte enqueue
        PORT2_ON=0x80007f5cL, PORT2_OFF=0x80007fc8L,  // port two's own link
        BUS_ON=0x8000f2c0L, BUS_OFF=0x8000f3a8L,      // the optional 208 bus
        PRESSURE=0x800053acL,                     // the physical pressure scan
        PUBLISH=0x8001d670L,                      // seq_transport's ST.B R12[0x4],R1
        SEQ_GATE=0x8001b4f0L, ARP_STEP=0x8000210cL;
    final List<String> midi=new ArrayList<>();
    final List<String> failures=new ArrayList<>();
    int sustain;
    // R12 as seq_transport publishes the new mode through it.  The call it
    // makes just before - seq_release, on the way into PLAY - may run the
    // senders, and they leave their own R12 behind; the store once went
    // through a note number instead of the block.  The senders are stubbed
    // here, so the register is read at the store itself.
    long publishR12=-1;

    @Override void step() throws Exception {
        long p=pc();
        if(p==PUBLISH) publishR12=reg("R12");
        if(p==QUEUE) {
            long buf=reg("R11");
            int status=(int)r(buf,1);
            midi.add(((status&0xf0)==0x90?"on":(status&0xf0)==0x80?"off":"other")
                +" p1 n"+r(buf+1,1)+" v"+r(buf+2,1)+" c"+(status&0xf));
            ret(); return;
        }
        if(p==PORT2_ON||p==PORT2_OFF) {
            midi.add((p==PORT2_ON?"on":"off")+" p2 n"+reg("R12")+" v"+reg("R11")+" c"+reg("R10"));
            ret(); return;
        }
        // The 208 bus is a third destination: the contact and lift handlers
        // reach it BEFORE their poly/mono fork, so a claim about "the
        // keyboard's outputs" that never watched it is only a claim about
        // MIDI.
        if(p==BUS_ON||p==BUS_OFF) {
            midi.add((p==BUS_ON?"on":"off")+" bus n"+reg("R12"));
            ret(); return;
        }
        super.step();
    }

    // R12 is the key, R11 the pressure count.  Contact takes two visits
    // (idle -> rising -> held) and the note-on lands on the second, which is
    // what two consecutive scans under a finger do.
    void scanKey(int key,int pressure) throws Exception {
        e.writeRegister("R11",pressure); e.writeRegister("R12",key); call(PRESSURE);
    }
    void down(int key) throws Exception { scanKey(key,sustain); scanKey(key,sustain); settle(); }
    void up(int key) throws Exception { scanKey(key,0); settle(); }
    // A key's pulse is left on the scan, so the gate it fires goes out on
    // the next pitch scans rather than inside the contact handler.
    void settle() throws Exception { for(int i=0;i<4;i++) { pitch(); scan(); } }
    long activeNote(int note) { return r(0x3924+note*8L,4); }
    long gate() { return r(S+0x354,2); }
    long base() { return (short)r(S+0x352,2); }
    long table(int key) { return (short)r(0x854+2L*key,2); }
    long cursor() { return r(0x61e1,1); }
    boolean seqSounding() { return r(0x2eed,1)!=0; }
    String state() { return "gate="+gate()+" base="+base()+" seq="+r(0x2eed,1)+" cursor="+cursor()+" midi="+midi; }

    // Pad 2 through the emitted transport, never a poke at the mode byte:
    // that byte is what every cave here reads, so the fixture must not be
    // its author.
    void button(int pad) throws Exception {
        e.writeRegister("R11",pad); call(ENTER); call(TICK);
    }
    void play() throws Exception {
        publishR12=-1;
        button(1); check("the real transport reached PLAY",r(0x6158,1)==2);
        check("and published the mode through the sequencer's block, not a"
            +" register a sender left behind: R12="+Long.toHexString(publishR12),
            publishR12==0x6154);
    }
    void stop() throws Exception {
        button(1); check("the real transport reached STOP",r(0x6158,1)==0);
    }

    void bench() throws Exception { bench(0); }
    void bench(int steps) throws Exception {
        seq=true; clock=true; persistent=true;
        setup(steps,false,0);                 // the real chord into WRITE
        command(0);                           // and out again: transport stopped
        check("the fixture starts stopped",r(0x6158,1)==0);
        sustain=(int)(short)r(S+0x396,2)+0x50+200;
    }
    // A take of notes with a rest at the given step, poked after the
    // transport has settled so the persistence tick cannot reload over it.
    void rest(int step) throws Exception {
        w(0x6160+2L*step,2,0x7ffe);
        check("the rest is in the store",r(0x6160+2L*step,2)==0x7ffe);
    }
    // The settings the keyboard's MIDI path reads.  Set AFTER any transport
    // call: the persistence tick reloads the saved record, and poly MIDI
    // defaults off, so arming before a transport gesture silently disarms.
    void arm(boolean poly) throws Exception {
        w(S+0x84,1,poly?1:0);   // the edit-mode poly setting
        w(S+0x85,1,0);          // arpeggiator off, which the poly path requires
        w(S+0x349,1,1);         // port two's own link, so it is observable
        w(0x2efa,1,1); w(S+0x4,4,0x1234);   // and the 208 bus, present and open
        w(S+0x2e7,1,3);         // a channel that is not the default
    }
    // Arming does NOT clear the log - one test needs the messages the
    // transport itself sends - so every test opens its own window.
    void armed(boolean poly) throws Exception { arm(poly); midi.clear(); }
    String seen() { return midi.toString(); }
    // The two MIDI ports, so a fully sounded note counts two...
    int count(String kind,int note) {
        int n=0;
        for(String m:midi)
            if(m.startsWith(kind+" ")&&m.contains(" n"+note+" ")) n++;
        return n;
    }
    // ...and the 208 bus counted on its own, because it is reached from a
    // different place.
    int bus(String kind,int note) {
        int n=0;
        for(String m:midi) if(m.equals(kind+" bus n"+note)) n++;
        return n;
    }
    int all(String kind) {
        int n=0;
        for(String m:midi) if(m.startsWith(kind+" ")) n++;
        return n;
    }

    // Ordinary poly use: two overlapping keys keep independent lifecycles and
    // each lift ends only its own note, on both outputs.
    void overlap() throws Exception {
        bench(); armed(true);
        down(4); down(9);
        check("both presses sound on both ports: "+seen(),
            count("on",40)==2&&count("on",45)==2&&count("off",40)==0&&count("off",45)==0);
        up(4);
        check("the first lift ends only its own note: "+seen(),
            count("off",40)==2&&count("off",45)==0);
        up(9);
        check("the second lift ends the other: "+seen(),count("off",45)==2);
        check("nothing is left active",activeNote(40)==0&&activeNote(45)==0);
        println("PASS overlapping poly presses keep independent note lifecycles");
    }

    // Stopped, nothing here is in the way.
    void stoppedStillSounds() throws Exception {
        for(boolean poly:new boolean[]{false,true}) {
            bench(); armed(poly);
            down(4);
            check("a stopped press sounds on both ports (poly="+poly+"): "+seen(),
                count("on",40)==2&&activeNote(40)!=0);
            check("and on the 208 bus (poly="+poly+"): "+seen(),bus("on",40)==1);
            check("and on the CV (poly="+poly+"): "+state(),base()==table(4)&&gate()!=0);
            up(4);
            check("and ends (poly="+poly+"): "+seen(),count("off",40)==2&&activeNote(40)==0);
            check("on the bus too (poly="+poly+"): "+seen(),bus("off",40)>=1);
            check("and the gate falls (poly="+poly+"): "+state(),gate()==0);
        }
        println("PASS the stopped keyboard is untouched in both modes");
    }

    // A press during PLAY reaches every output the stopped keyboard does.
    void keysPlayOverTheSequence() throws Exception {
        for(boolean poly:new boolean[]{false,true}) {
            bench(4); play(); armed(poly);
            down(4);
            check("a press during PLAY reaches both MIDI ports (poly="+poly+"): "+seen(),
                count("on",40)==2&&activeNote(40)!=0);
            check("and the 208 bus (poly="+poly+"): "+seen(),bus("on",40)==1);
            check("and the CV (poly="+poly+"): "+state(),base()==table(4)&&gate()!=0);
            up(4);
            check("its lift ends the note everywhere (poly="+poly+"): "+seen(),
                count("off",40)==2&&bus("off",40)>=1&&activeNote(40)==0);
            check("and drops the gate (poly="+poly+"): "+state(),gate()==0);
            stop();
        }
        println("PASS the keyboard plays over a take on MIDI, the bus and the CV, in both modes");
    }

    // Mono's own hand-back still works under a take: letting the newer key
    // go returns the voice to the older one, on every output.
    void monoHandBackDuringPlay() throws Exception {
        bench(4); play(); armed(false);
        down(4); down(9);
        check("the second press retriggers on MIDI: "+seen(),count("on",45)==2&&count("off",40)==2);
        check("and takes the CV: "+state(),base()==table(9));
        midi.clear();
        up(9);
        check("letting the newer key go hands the note back on MIDI: "+seen(),
            count("off",45)==2&&count("on",40)==2&&bus("on",40)==1);
        check("and on the CV, gate still up: "+state(),base()==table(4)&&gate()!=0);
        up(4);
        check("and the last lift ends it: "+state(),gate()==0&&count("off",40)==2);
        stop();
        println("PASS mono hand-back between held keys works under a take");
    }

    // A key already sounding when the transport starts keeps its note: no
    // note-off at the boundary on any output, the gate stays up, and its
    // own lift ends it.
    void heldAcrossPlay() throws Exception {
        for(boolean poly:new boolean[]{false,true}) {
            bench(4); armed(poly);
            down(4);
            check("the press before PLAY sounded everywhere (poly="+poly+"): "+seen(),
                count("on",40)==2&&bus("on",40)==1&&activeNote(40)!=0&&gate()!=0);
            midi.clear();
            play(); settle();
            check("entering PLAY ends nothing of the keyboard's (poly="+poly+"): "+state(),
                midi.isEmpty()&&gate()!=0&&base()==table(4));
            armed(poly);
            up(4);
            check("its lift during PLAY ends the note everywhere (poly="+poly+"): "+seen(),
                count("off",40)==2&&bus("off",40)>=1&&activeNote(40)==0);
            check("and drops the gate (poly="+poly+"): "+state(),gate()==0);
            stop();
        }
        println("PASS a held note crosses into PLAY untouched and ends on its own lift");
    }

    // The keyboard has priority: a press under a sounding sequenced note
    // ends that note on the bus and both ports, so a receiver holding both
    // lines hears the keyboard alone, as the CV does.
    void pressCutsTheSequencerNote() throws Exception {
        bench(4); play(); armed(false);
        externalBeat();
        check("a sequenced note is sounding: "+state(),seqSounding()&&count("on",36)==2&&bus("on",36)==1);
        midi.clear();
        down(4);
        check("the press ends the sequencer's note first: "+seen(),
            count("off",36)==2&&bus("off",36)==1&&!seqSounding());
        check("then sounds its own: "+seen(),count("on",40)==2&&bus("on",40)==1
            &&midi.indexOf("on bus n40")>midi.indexOf("off bus n36"));
        check("and the CV is the key's: "+state(),base()==table(4)&&gate()!=0);
        up(4);
        // Five note-offs and the one the factory sends the bus on every
        // press, unmatched, for a note it never had (n0).
        check("the lift ends the key's note and nothing else: "+seen(),
            count("off",40)==2&&bus("off",40)>=1&&all("off")-bus("off",0)==6);
        check("and drops the gate: "+state(),gate()==0);
        stop();
        println("PASS a press ends the note the sequencer was sounding");
    }

    // While a key is held the take is silent on every output and only keeps
    // time: no pitch, no spike, no MIDI, the cursor moving on.  Let go, and
    // the next step sounds again.
    void heldKeySilencesTheTake() throws Exception {
        bench(4); play(); armed(false);
        down(4);
        long at=cursor(); int on=all("on"), off=all("off");
        externalBeat(); externalBeat();
        check("two steps under a held key sound nothing new: "+seen(),all("on")==on&&all("off")==off);
        check("leave the CV to the key: "+state(),base()==table(4)&&gate()!=0&&!seqSounding());
        check("and still move the cursor on: "+state(),cursor()==(at+2)%4);
        up(4);
        check("the lift ends the key's note: "+state(),gate()==0&&count("off",40)==2);
        midi.clear();
        externalBeat();
        check("and the next step sounds again: "+state(),
            seqSounding()&&all("on")==3&&base()!=table(4)&&gate()!=0);
        stop();
        println("PASS a held key silences the take while it keeps time");
    }

    // A held key holds the gate at both of the arp engine's drops: the
    // gate clear it makes as a step fires, and the countdown compare that
    // ends an untied step at its half.  Each is shown dropping with nothing
    // held first, so the fixture is known to see the drop at all.
    void heldKeyHoldsTheGate() throws Exception {
        // The clear as a step fires: a REST next, so no spike follows it.
        for(boolean held:new boolean[]{false,true}) {
            bench(4); play(); rest(1); armed(false);
            if(held) down(4); else w(S+0x354,2,0x7ff);
            externalBeat();                       // step 0: the note, or nothing under the key
            check("the gate is up before the rest (held="+held+"): "+state(),gate()!=0);
            externalBeat();                       // step 1: the rest
            if(held) check("a held key keeps the gate through the rest: "+state(),gate()!=0);
            else check("with nothing held the rest clears the gate: "+state(),gate()==0);
            stop();
        }
        // The half-step compare: the arp engine stepped at the countdown
        // the gate length names, with the interval it already knows so
        // nothing is reloaded.
        for(boolean held:new boolean[]{false,true}) {
            bench(4); play(); armed(false);
            externalBeat();                       // a step is sounding, the next is a note
            long length=lengthAsked(); if(held) down(4);
            long asked=lengthAsked();
            if(held) check("seq_gate answers a count the countdown never reaches: "+asked,asked==-0x8000);
            else check("seq_gate answers a length: "+asked,asked>0&&asked<0x4000);
            w(S+0x38e,2,length+1); w(S+0x34c,1,0);
            e.writeRegister("R12",r(0x2ee0,2)); call(ARP_STEP);
            check("the countdown reached the drop (held="+held+")",r(S+0x38e,2)==length);
            if(held) check("and a held key keeps the gate: "+state(),gate()!=0);
            else check("and with nothing held the gate drops: "+state(),gate()==0);
            stop();
        }
        println("PASS a held key holds the gate at the step clear and the half-step drop");
    }
    long lengthAsked() throws Exception { call(SEQ_GATE); return (short)reg("R8"); }

    // STOP is the factory's own arp-off transition, which ends every note
    // and drops the gate whatever is under a finger - the same silence the
    // arp switch makes when it is turned off under a held key.  Pinned so
    // that the claim above about a key held INTO play is not mistaken for
    // one about a key held out of it.
    void stopEndsEverything() throws Exception {
        bench(4); play(); armed(false);
        externalBeat(); down(4); midi.clear();
        stop(); settle();
        check("STOP ends the held key's MIDI note and sends all-notes-off: "+seen(),
            count("off",40)==2&&midi.toString().contains("other p1 n123"));
        check("and drops the gate under the finger: "+state(),gate()==0);
        up(4);
        // The flush ends the note from the active-note table and leaves the
        // keyboard's own "sounding" flag (0x33c5) set, so the lift sends a
        // second pair - the factory's duplicate, the same one an arp-off
        // under a held key makes.
        check("the lift then sends the factory's own second note-off: "+seen(),count("off",40)==4);
        println("PASS STOP ends everything, a held key included, as the factory's arp-off does");
    }

    // What the take itself does has to survive all of that: its own notes
    // still go out, and a key underneath adds exactly its own - plus the
    // note-off for the sequenced note it cuts, when one is sounding.
    void theSequenceStillSounds() throws Exception {
        bench(4); play(); armed(false);
        for(int i=0;i<4;i++) externalBeat();
        int on=all("on"), off=all("off");
        check("the running take still reaches MIDI: "+seen(),on>=4&&off>=2);
        int cut=seqSounding()?3:0, unmatched=bus("off",0);
        down(4); up(4);
        // ...and the factory's unmatched bus note-off (n0) on the press.
        check("a mono key underneath adds its own note and nothing else: "+seen(),
            all("on")==on+3&&all("off")-bus("off",0)==off-unmatched+3+cut
            &&count("on",40)==2&&count("off",40)==2);
        stop();
        println("PASS the sequence keeps its own MIDI with a key pressed underneath");
    }

    // With the arp switch ON the keyboard is not live in any mode, and a
    // press must not reach the take: no note, no held count, no stolen step.
    void arpOnStaysMuted() throws Exception {
        bench(4); play(); armed(false);
        w(S+0x341,1,1);                       // the regular arp position
        externalBeat();
        long before=cursor(), pitch=base(); midi.clear();
        down(4);
        check("a press with the arp on sends nothing: "+seen(),midi.isEmpty());
        check("registers no held key, and steals no step: held="+r(S+0x21a,1)+" "+state(),
            r(S+0x21a,1)==0&&cursor()==before&&base()==pitch&&seqSounding());
        up(4);
        check("nor does its lift send anything: "+seen(),midi.isEmpty());
        w(S+0x341,1,0);
        stop();
        println("PASS the arp-on keyboard stays off the take");
    }

    @Override public void run() throws Exception {
        try {
            String[] names={"stoppedStillSounds","overlap","keysPlayOverTheSequence","monoHandBackDuringPlay",
                "heldAcrossPlay","pressCutsTheSequencerNote","heldKeySilencesTheTake","heldKeyHoldsTheGate",
                "stopEndsEverything","theSequenceStillSounds","arpOnStaysMuted"};
            for(String name:names) {
                try { getClass().getDeclaredMethod(name).invoke(this); }
                catch(java.lang.reflect.InvocationTargetException ex) {
                    failures.add(name+": "+ex.getCause()); println(name+": "+ex.getCause());
                }
            }
            if(!failures.isEmpty())throw new Exception("POLY MIDI PROBE FAIL: "+failures);
            println("POLY MIDI PROBE PASS: "+checks+" assertions");
        } finally { if(e!=null)e.dispose(); }
    }
}
