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
// and keeps it through STOP; its own lift is what ends it.  Poly MIDI stays
// poly through a take: a chord is a chord, every lift ends its own note, the
// sustain goes out as CC 64.  With the arp switch ON a press still means
// nothing.
//
// state+0x85, the factory's arp-engaged byte, is the firmware's: PLAY sets it
// through the tempo routine, and nothing here writes it.  Once this probe
// cleared it after PLAY "for the poly path", and every poly scenario then ran
// in a state the instrument never holds (audit 038711a, F12).
// Usage: -postScript PolyMidiProbe.java
//@category Buchla218.Tests
import java.util.*;

public class PolyMidiProbe extends ControlRegression {
    static final long QUEUE=0x80009a64L,          // DIN/USB three-byte enqueue
        PORT2_ON=0x80007f5cL, PORT2_OFF=0x80007fc8L,  // port two's own link
        PORT2_CC=0x80008034L,                     // and its controller change
        BUS_ON=0x8000f2c0L, BUS_OFF=0x8000f3a8L,      // the optional 208 bus
        PRESSURE=0x800053acL,                     // the physical pressure scan
        PUBLISH=0x8001d670L,                      // seq_transport's ST.B R12[0x4],R1
        SEQ_GATE=0x8001b4f0L, ARP_STEP=0x8000210cL,
        TEMPO=0x80002b28L,                        // the scan's tempo pass: the arp enable's edges
        PULSE_JACK=0x80004afcL,                   // the scan's pulse-jack read: the sustain
        OCTAVE_PAD=0x8000a784L;                   // an octave pad pressed, R12 the pad
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
        // Port two's controller change runs in the emulator, so it is only
        // watched: R12 the controller, R11 the value, R10 the channel.
        if(p==PORT2_CC) midi.add("other p2 n"+reg("R12")+" v"+reg("R11")+" c"+reg("R10"));
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
        check("and set the arp-engaged byte, as the instrument does in a take: "
            +r(S+0x85,1),r(S+0x85,1)==1);
    }
    void stop() throws Exception {
        button(1); check("the real transport reached STOP",r(0x6158,1)==0);
        check("and gave the arp-engaged byte back to the switch: "+r(S+0x85,1),
            r(S+0x85,1)==(r(S+0x340,2)!=0?1:0));
    }
    // The switch moved, then the tempo pass the main loop runs every scan:
    // the one routine that writes the arp-engaged byte, which the transport
    // calls as well.
    void tempo() throws Exception { call(TEMPO); }
    void flip(int position) throws Exception { arp(position); tempo(); settle(); }

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
    // Not state+0x85: the arp-engaged byte is the firmware's, and it is
    // asserted here instead - 1 in a take or with the switch engaged, as
    // seq_clock_enabled answers, 0 otherwise.
    void arm(boolean poly) throws Exception {
        w(S+0x84,1,poly?1:0);   // the edit-mode poly setting
        w(S+0x349,1,1);         // port two's own link, so it is observable
        w(0x2efa,1,1); w(S+0x4,4,0x1234);   // and the 208 bus, present and open
        w(S+0x2e7,1,3);         // a channel that is not the default
        long engaged=r(0x6158,1)==2||r(S+0x340,2)!=0?1:0;
        check("the arp-engaged byte is the firmware's own: "+r(S+0x85,1)
            +" in mode "+r(0x6158,1),r(S+0x85,1)==engaged);
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
    // Only the two MIDI ports, whatever the note: the bus is reached from
    // outside the poly/mono fork and says nothing about it.
    int ports(String kind) {
        int n=0;
        for(String m:midi) if(m.startsWith(kind+" p1 ")||m.startsWith(kind+" p2 ")) n++;
        return n;
    }
    // A controller change with a given value, on both ports.
    int cc(int controller,int value) {
        int n=0;
        for(String m:midi)
            if((m.startsWith("other p1 ")||m.startsWith("other p2 "))
                &&m.contains(" n"+controller+" v"+value+" ")) n++;
        return n;
    }
    // Every note sounded on the ports was ended there exactly once, and the
    // factory's active-note table agrees.  The log is the whole scenario's.
    boolean balanced(List<String> log,int... notes) {
        for(int note:notes)
            for(String port:new String[]{" p1 "," p2 "}) {
                int on=0, off=0;
                for(String m:log) {
                    if(!m.contains(port)||!m.contains(" n"+note+" ")) continue;
                    if(m.startsWith("on ")) on++;
                    if(m.startsWith("off ")) off++;
                }
                if(on!=1||off!=1||activeNote(note)!=0) return false;
            }
        return true;
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

    // A chord over a take is two notes, as it is stopped in overlap().  One
    // key sounds the same on either path, so only two can tell poly from
    // mono: the second press must not end the first, and each lift ends its
    // own note and only that.  The CV is the one voice it always was: the
    // newer key has it, and letting the older one go leaves it there.
    void polyChordOverATake() throws Exception {
        bench(4); play(); armed(true);
        down(4); down(9);
        check("a chord over a take sounds both notes on both ports: "+seen(),
            count("on",40)==2&&count("on",45)==2);
        check("and the second press ends nothing on the ports: "+seen(),ports("off")==0);
        check("the CV is the newer key's: "+state(),base()==table(9)&&gate()!=0);
        midi.clear();
        up(4);
        check("letting the older key go ends its own note on both ports: "+seen(),
            count("off",40)==2&&ports("off")==2&&ports("on")==0
            &&activeNote(40)==0&&activeNote(45)!=0);
        check("and leaves the CV where it was: "+state(),base()==table(9)&&gate()!=0);
        midi.clear();
        up(9);
        check("the other lift ends the other note and sounds nothing: "+seen(),
            count("off",45)==2&&ports("off")==2&&ports("on")==0&&activeNote(45)==0);
        check("and drops the gate: "+state(),gate()==0);
        stop();
        println("PASS a poly chord over a take is two notes, each ended by its own lift");
    }

    // Two poly keys held into PLAY, let go in either order: each lift ends
    // its own note on both ports and sounds nothing new.  Older first is the
    // audit's stranded note: the older key is not the note the mono path
    // remembers, so its lift sent nothing and the note rang until STOP.
    // Newer first, the mono path hands the voice back to the key still held,
    // which on the ports is a second note-on for a note already sounding.
    // The CV does what it always did: the hand-back moves it to that key.
    void twoHeldIntoPlay() throws Exception {
        for(boolean olderFirst:new boolean[]{true,false}) {
            bench(4); armed(true);
            down(4); down(9);
            check("two keys held before PLAY are two notes (older first="+olderFirst+"): "+seen(),
                count("on",40)==2&&count("on",45)==2&&ports("off")==0);
            midi.clear();
            play(); settle();
            check("entering PLAY ends neither: "+state(),
                midi.isEmpty()&&gate()!=0&&base()==table(9));
            armed(true);
            int first=olderFirst?4:9, second=olderFirst?9:4;
            up(first);
            check("the first lift ends its own note on both ports and nothing else"
                +" (older first="+olderFirst+"): "+seen(),
                count("off",36+first)==2&&ports("off")==2&&ports("on")==0
                &&activeNote(36+first)==0&&activeNote(36+second)!=0);
            check("the CV is the held key's, gate up (older first="+olderFirst+"): "+state(),
                base()==table(second)&&gate()!=0);
            midi.clear();
            up(second);
            check("the second lift ends the other (older first="+olderFirst+"): "+seen(),
                count("off",36+second)==2&&ports("off")==2&&ports("on")==0
                &&activeNote(36+second)==0);
            check("and drops the gate: "+state(),gate()==0);
            stop();
        }
        println("PASS two poly keys held into PLAY each end on their own lift, in either order");
    }

    // The arp switch to latch and back with two poly keys down, stopped and
    // in a take, then the keys let go.  Stopped, the switch's return is the
    // factory's arp-off edge, which ends every note it knows is sounding.
    // In a take the take holds the enable, so there is no edge and nothing
    // ends until the lifts.  Either way every note sounded on the ports
    // ends there exactly once.
    void latchToggleWithKeysHeld() throws Exception {
        for(boolean playing:new boolean[]{false,true}) {
            bench(4); if(playing) play(); armed(true);
            down(4); down(9);
            check("two keys, two notes (playing="+playing+"): "+seen(),
                count("on",40)==2&&count("on",45)==2&&ports("off")==0);
            flip(1);
            check("the latch position engages the arp byte (playing="+playing+"): "
                +r(S+0x85,1),r(S+0x85,1)==1);
            flip(0);
            check("and its return gives it back (playing="+playing+"): "+r(S+0x85,1),
                r(S+0x85,1)==(playing?1:0));
            if(playing) check("in a take nothing has ended yet: "+seen(),ports("off")==0);
            else check("stopped, the arp-off edge ended both notes on both ports: "+seen(),
                count("off",40)==2&&count("off",45)==2&&activeNote(40)==0&&activeNote(45)==0);
            int ended=ports("off");
            up(4);
            if(playing) check("in a take the older key's lift ends its note: "+seen(),
                count("off",40)==2&&ports("off")==ended+2);
            up(9);
            check("every note sounded on the ports ended there once (playing="+playing+"): "
                +seen(),balanced(midi,40,45)&&ports("on")==4);
            if(playing) stop();
        }
        println("PASS a latch toggle with keys down ends every note once, stopped and in a take");
    }

    // The pulse jack's sustain, read as the scan reads it: the jack's
    // polarity is taken once at boot (0x800077bc), and with the jack low
    // then, high is the sustain.
    void pedal(boolean down) throws Exception {
        long high=r(S+0x2e8,1)==0?0xfff:0;
        w(S+0x2f4,2,down?high:0xfff-high); call(PULSE_JACK);
        check("the sustain follows the jack",r(S+0x2e9,1)==(down?1:0));
    }

    // The sustain over a poly take is poly's, as it is stopped: it goes out
    // as CC 64 on both ports, a key let go under it sends its note-off at
    // once for the receiver to hold, and the sustain's release sends CC 64
    // = 0 and no second note-off.  The gate keeps the factory's hold: up
    // under the sustain, down at its release.
    void sustainOverATake() throws Exception {
        bench(4); play(); armed(true);
        check("no sustain yet",r(S+0x2e9,1)==0&&r(S+0x2ea,1)==0);
        pedal(true);
        check("the sustain goes out as CC 64 = 127 on both ports: "+seen(),cc(64,127)==2);
        midi.clear();
        down(4); up(4);
        check("a key let go under it sends its note-off at once: "+seen(),
            count("on",40)==2&&count("off",40)==2&&activeNote(40)==0);
        check("the gate holds for the sustain: "+state(),gate()!=0);
        midi.clear();
        pedal(false);
        check("its release sends CC 64 = 0 on both ports and no second note-off: "+seen(),
            cc(64,0)==2&&ports("off")==0&&ports("on")==0);
        check("and drops the gate: "+state(),gate()==0);
        stop();
        println("PASS the sustain over a poly take is CC 64, and no note ends twice");
    }

    // The gate under the sustain in a poly take, CV only, so that it holds
    // on the image before the fix as well: the fix keeps the MIDI off the
    // mono path and must leave what the CV does alone.  The sustain's
    // release drops the gate for every note the sustain was holding - a
    // byte per note at 0x32d0 that the mono path's port-two note-off
    // clears - so a note-off kept off the ports without that clear would
    // drop the gate of a key still held, the next time the sustain lets go.
    void sustainGateInATake() throws Exception {
        for(boolean cleared:new boolean[]{false,true}) {
            bench(4); play(); armed(true);
            pedal(true);
            down(4); up(4);
            check("the sustain holds the gate after the lift: "+state(),gate()!=0);
            if(cleared) {
                // The sustain lets go first, so the note it held ends there.
                pedal(false);
                check("its release drops the gate: "+state(),gate()==0);
                down(9); pedal(true);
            } else {
                // Another key takes the voice first, ending the held note.
                down(9);
            }
            check("the held key has the voice: "+state(),base()==table(9)&&gate()!=0);
            pedal(false);
            check("a sustain let go over a held key leaves its gate up (cleared="
                +cleared+"): "+state(),base()==table(9)&&gate()!=0);
            up(9);
            check("and the key's own lift drops it: "+state(),gate()==0);
            stop();
        }
        println("PASS the sustain's release never drops the gate of a held key in a take");
    }

    // With the arp switch engaged the keyboard's MIDI is the factory's, poly
    // or not.  A poly note let go under the sustain with the switch off,
    // the switch then engaged, the sustain let go: the factory sends no
    // CC 64 with the arp engaged, and its release sends the mono note-off
    // for the note the sustain held - a second note-off, the factory's own,
    // kept as it was.  So this passes before the fix too; it is here to
    // fail if the fix ever reaches past the switch.
    void arpOnKeepsTheFactoryMidi() throws Exception {
        bench(); armed(true);
        pedal(true);
        check("stopped, poly MIDI sends the sustain as CC 64: "+seen(),cc(64,127)==2);
        down(4); up(4);
        check("a lift under it sends its note-off at once: "+seen(),
            count("on",40)==2&&count("off",40)==2);
        flip(2);
        check("engaging the arp lets the receiver's sustain go, as the factory's"
            +" edge does: "+seen(),r(S+0x85,1)==1&&cc(64,0)==2);
        midi.clear();
        pedal(false);
        check("with the arp engaged the release sends no CC 64 and the factory's"
            +" mono note-off: "+seen(),cc(64,0)==0&&cc(64,127)==0&&count("off",40)==2);
        flip(0);
        println("PASS with the arp switch engaged the keyboard's MIDI is the factory's");
    }

    // An octave pad under a poly key in a take: the poly note keeps
    // sounding, as it does stopped, and its lift ends it under the name it
    // was sent with.  The mono path's retune - a note-off for the old name
    // and a note-on for the new one - reaches the 208 bus, which is mono,
    // and stays off the ports.
    void octaveUnderAPolyKey() throws Exception {
        bench(4); play(); armed(true);
        // The add-to-pitch switch in its octave position, as its reader at
        // 0x800039f0 leaves it: the pads then move the note by octaves, and
        // a pad pressed with a key down retunes the note that key sounds.
        w(S+0x342,1,1); w(S+0x343,1,0); w(S+0x344,4,2);
        check("pad 1 is selected",r(S+0x2ef,1)==0);
        down(4);
        check("the key sounds, an octave under the table's note: "+seen(),count("on",28)==2);
        midi.clear();
        e.writeRegister("R12",1); call(OCTAVE_PAD); settle();
        check("pad 2 moved the mono path's note, on the bus: "+seen(),
            r(S+0x2ef,1)==1&&bus("off",28)==1&&bus("on",40)==1);
        check("and sent nothing for it on the ports: "+seen(),
            count("off",28)==0&&count("on",40)==0&&count("off",40)==0);
        midi.clear();
        up(4);
        check("the lift ends the note under the name it was sent with: "+seen(),
            count("off",28)==2&&count("off",40)==0&&ports("on")==0
            &&activeNote(28)==0&&activeNote(40)==0);
        stop();
        println("PASS an octave under a poly key moves the bus and leaves the note to its lift");
    }

    // Where in the log the first message of a kind is, or -1.
    int first(String prefix) {
        for(int i=0;i<midi.size();i++) if(midi.get(i).startsWith(prefix)) return i;
        return -1;
    }

    // Keyboard priority holds in poly: a press over a sounding take ends
    // the take's note on both ports and only then sounds its own, as the
    // mono press does in pressCutsTheSequencerNote().  The order is the
    // point when the two are one note: a note-on for the key followed by
    // the take's note-off for the same note leaves the receiver silent,
    // and clears the port's active-note record the key's own lift needs.
    // Key 0 plays the take's first note; key 4 another.
    void polyPressCutsTheSequencerNote() throws Exception {
        for(int key:new int[]{4,0}) {
            bench(4); play(); armed(true);
            externalBeat();
            int taken=(int)r(S+0x34e,1), note=36+key;
            check("a sequenced note is sounding (key "+key+"): "+state(),
                seqSounding()&&count("on",taken)==2);
            midi.clear();
            down(key);
            check("the press ends the take's note on both ports, then sounds its own"
                +" (key "+key+"): "+seen(),!seqSounding()&&ports("off")==2&&ports("on")==2
                &&first("off p1 n"+taken+" ")>=0&&first("off p2 n"+taken+" ")>=0
                &&first("on p1 n"+note+" ")>first("off p1 n"+taken+" ")
                &&first("on p2 n"+note+" ")>first("off p2 n"+taken+" ")
                &&activeNote(note)!=0);
            check("and on the bus (key "+key+"): "+seen(),
                midi.indexOf("on bus n"+note)>midi.indexOf("off bus n"+taken));
            check("the CV is the key's: "+state(),base()==table(key)&&gate()!=0);
            midi.clear();
            up(key);
            check("its lift ends its own note on both ports (key "+key+"): "+seen(),
                count("off",note)==2&&ports("off")==2&&ports("on")==0&&activeNote(note)==0);
            check("and drops the gate: "+state(),gate()==0);
            stop();
        }
        println("PASS a poly press ends the take's note before it sounds its own, one note or two");
    }

    // A poly note ends at its own lift whatever the arp switch says by
    // then: the release follows the path the press took.  Engaged before
    // the lift, the handler would refuse the note-off, and in a take no
    // arp-off edge follows the switch's return, so the note rang until
    // STOP.  Let go first, the switch has nothing left to end.  Engaged
    // and back with the key down is latchToggleWithKeysHeld().  And a key
    // the switch swallowed sent nothing, so its lift ends nothing - not
    // even the note the take is sounding under the number the handler last
    // stored for that key.
    void polyReleaseFollowsItsPress() throws Exception {
        for(boolean playing:new boolean[]{false,true}) {
            bench(4); if(playing) play(); armed(true);
            down(4);
            check("the key sounds (playing="+playing+"): "+seen(),count("on",40)==2);
            flip(1);
            midi.clear();
            up(4);
            check("let go with the switch engaged, it ends its note on both ports"
                +" (playing="+playing+"): "+seen(),
                count("off",40)==2&&ports("off")==2&&ports("on")==0&&activeNote(40)==0);
            midi.clear();
            flip(0);
            check("and the switch's return ends nothing twice (playing="+playing+"): "+seen(),
                ports("off")==0&&ports("on")==0);
            midi.clear();
            down(9); up(9);
            flip(1); flip(0);
            check("let go before the switch moves, the lift ends it and the switch nothing"
                +" (playing="+playing+"): "+seen(),
                count("on",45)==2&&count("off",45)==2&&ports("off")==2&&ports("on")==2);
            if(playing) stop();
        }
        bench(4); play(); armed(true);
        for(int i=0;i<4;i++) w(0x61ee+i,1,0);    // every step plays key 0's note
        down(0); up(0);
        check("key 0's own note came and went: "+seen(),count("on",36)==2&&count("off",36)==2);
        externalBeat();
        check("the take sounds the same note: "+state(),
            seqSounding()&&r(S+0x34e,1)==36&&activeNote(36)!=0);
        flip(1);
        midi.clear();
        down(0);
        check("a press with the switch engaged sends nothing: "+seen(),ports("on")==0&&ports("off")==0);
        flip(0);
        up(0);
        check("and its lift ends nothing, the take's note included: "+seen(),
            ports("off")==0&&activeNote(36)!=0);
        stop();
        println("PASS a poly note ends at its own lift whatever the switch, and a swallowed press ends nothing");
    }

    // LED 9 (2026-09-28, the audit's follow-ups): lit while poly MIDI is on,
    // blinking while the arpeggiator overrides it.  The routine that draws
    // it steady and the edit-mode pass that blinks it read state+0x85,
    // which PLAY sets, so in a take with the switch off the lamp blinked
    // though poly MIDI is live there since 3.1.  Both read the switch now.
    // The steady state is drawn by the routine the edit keys call, through
    // key 29's poly toggle, pressed twice so poly ends on as it began; the
    // blink by event 4's own case, the edit-mode LED pass at 0x80004d82,
    // run past its call for LED 9 in each of its two phases.  Stopped, the
    // byte is the switch's own, so every outcome there is 3.0.2's.
    static final long LED_WORD=0x2ef4L, EDIT_KEY=0x80003c24L, LED_PASS=0x80004d82L, LED_PASS_DONE=0x80004e08L;
    long led9() { return (r(LED_WORD,2)>>14)&1; }
    void polyKey() throws Exception { e.writeRegister("R12",0x1c); call(EDIT_KEY); }
    void drawPoly() throws Exception {
        polyKey(); check("key 29 turns poly MIDI off",r(S+0x84,1)==0);
        polyKey(); check("and on again",r(S+0x84,1)==1);
    }
    String led9Shows() throws Exception {
        long edit=r(S+0x39,1), gate=r(S+0x390,1), phase=r(S+0x3c,1);
        w(S+0x39,1,1); w(S+0x390,1,0);
        long[] seen=new long[2];
        for(int p=0;p<2;p++) { w(S+0x3c,1,p); call(LED_PASS,LED_PASS_DONE); seen[p]=led9(); }
        w(S+0x39,1,edit); w(S+0x390,1,gate); w(S+0x3c,1,phase);
        return seen[0]==seen[1]?(seen[0]==1?"steady":"dark"):seen[0]==1?"blinking":"inverted";
    }
    void polyLed() throws Exception {
        bench(); arm(true); drawPoly();
        String stoppedOff=led9Shows();
        flip(2); String stoppedArp=led9Shows();
        flip(1); String stoppedLatch=led9Shows();
        flip(0); String stoppedBack=led9Shows();
        polyKey(); String stoppedMono=led9Shows(); polyKey();
        check("stopped, as 3.0.2: steady with the switch off, blinking on arp and on latch, steady again, dark with poly off: "
            +stoppedOff+", "+stoppedArp+", "+stoppedLatch+", "+stoppedBack+", "+stoppedMono,
            stoppedOff.equals("steady")&&stoppedArp.equals("blinking")&&stoppedLatch.equals("blinking")
            &&stoppedBack.equals("steady")&&stoppedMono.equals("dark"));
        play(); arm(true); drawPoly();
        String takeOff=led9Shows();
        check("in a take with the switch off LED 9 is steady, poly MIDI being live: "+takeOff,takeOff.equals("steady"));
        flip(2); drawPoly();
        String takeArp=led9Shows();
        check("in a take with the switch engaged it blinks: "+takeArp,takeArp.equals("blinking"));
        flip(0); drawPoly();
        String takeBack=led9Shows();
        check("and steady again with the switch back off: "+takeBack,takeBack.equals("steady"));
        stop(); arm(true); drawPoly();
        String stopped=led9Shows();
        check("stopped after the take, steady: "+stopped,stopped.equals("steady"));
        println("PASS LED 9 shows poly MIDI live in a take with the switch off, overridden with it engaged, and as 3.0.2 stopped");
    }

    @Override public void run() throws Exception {
        try {
            String[] names={"stoppedStillSounds","overlap","keysPlayOverTheSequence","monoHandBackDuringPlay",
                "heldAcrossPlay","pressCutsTheSequencerNote","heldKeySilencesTheTake","heldKeyHoldsTheGate",
                "stopEndsEverything","theSequenceStillSounds","arpOnStaysMuted",
                "polyChordOverATake","twoHeldIntoPlay","latchToggleWithKeysHeld","sustainOverATake",
                "sustainGateInATake","arpOnKeepsTheFactoryMidi","octaveUnderAPolyKey",
                "polyPressCutsTheSequencerNote","polyReleaseFollowsItsPress","polyLed"};
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
