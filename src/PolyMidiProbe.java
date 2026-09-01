// Keyboard MIDI note lifecycle in emitted firmware.  Every press and lift
// runs the real pressure state machine at 0x800053ac - the only caller of
// which is the physical scan at 0x80005300 - and all four factory note
// senders run in full, so the factory active-note table at 0x3924 is
// maintained by firmware rather than modelled here.  Only the two leaves are
// stubbed: the queue writer both port-one senders hand their three bytes to,
// and the vendor-bus note routines port two calls, whose busy-waits have no
// peripheral in the emulator.
//
// Reading the messages off the queue writer means the status byte under test
// is the one the firmware actually assembled, note and channel included.
// Usage: -postScript PolyMidiProbe.java
//@category Buchla218.Tests
import java.util.*;

public class PolyMidiProbe extends ControlRegression {
    static final long QUEUE=0x80009a64L,          // DIN/USB three-byte enqueue
        PORT2_ON=0x80007f5cL, PORT2_OFF=0x80007fc8L,  // port two's own link
        BUS_ON=0x8000f2c0L, BUS_OFF=0x8000f3a8L,      // the optional 208 bus
        PRESSURE=0x800053acL;                     // the physical pressure scan
    final List<String> midi=new ArrayList<>();
    final List<String> failures=new ArrayList<>();
    int sustain;

    @Override void step() throws Exception {
        long p=pc();
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
        // The 208 bus is a third destination, and the one this probe used to
        // miss: the contact and lift handlers reach it BEFORE their poly/mono
        // fork, so a "silent" claim that never watched it was only a claim
        // about MIDI.
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
    void down(int key) throws Exception { scanKey(key,sustain); scanKey(key,sustain); }
    void up(int key) throws Exception { scanKey(key,0); }
    long activeNote(int note) { return r(0x3924+note*8L,4); }

    // Pad 2 through the emitted transport, never a poke at the mode byte:
    // that byte is what the mute reads, so the fixture must not be its author.
    void button(int pad) throws Exception {
        e.writeRegister("R11",pad); call(ENTER); call(TICK);
    }
    void play() throws Exception {
        button(1); check("the real transport reached PLAY",r(0x6158,1)==2);
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
    // different place and was the half this probe used to miss.
    int bus(String kind,int note) {
        int n=0;
        for(String m:midi) if(m.equals(kind+" bus n"+note)) n++;
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

    // Stopped, the guard is not in the way.
    void stoppedStillSounds() throws Exception {
        for(boolean poly:new boolean[]{false,true}) {
            bench(); armed(poly);
            down(4);
            check("a stopped press sounds on both ports (poly="+poly+"): "+seen(),
                count("on",40)==2&&activeNote(40)!=0);
            check("and on the 208 bus (poly="+poly+"): "+seen(),bus("on",40)==1);
            up(4);
            check("and ends (poly="+poly+"): "+seen(),count("off",40)==2&&activeNote(40)==0);
            check("on the bus too (poly="+poly+"): "+seen(),bus("off",40)>=1);
        }
        println("PASS the stopped keyboard is untouched in both modes");
    }

    // Poly is the mode for playing over a running take - on MIDI, where the
    // receiver has the voices.  The 208 bus is one monophonic instrument, so
    // it goes quiet with everything else the take owns.
    void polyPlaysOverTheSequence() throws Exception {
        bench(); play(); armed(true);
        down(4); down(9);
        check("a poly chord over a running take reaches both MIDI ports: "+seen(),
            count("on",40)==2&&count("on",45)==2);
        check("but nothing reaches the 208 bus: "+seen(),
            bus("on",40)==0&&bus("on",45)==0&&bus("off",40)==0&&bus("off",45)==0);
        up(9); up(4);
        check("and both keys end when they are let go: "+seen(),
            count("off",40)==2&&count("off",45)==2);
        check("still nothing on the bus: "+seen(),bus("off",40)==0&&bus("off",45)==0);
        stop();
        println("PASS poly plays over a take on MIDI, and leaves the 208 bus alone");
    }

    // A key already sounding when the transport starts.  Whatever the mute
    // is about to take is ended at the transition: the bus in both modes,
    // MIDI only in mono.  A poly key keeps its MIDI note and ends it on its
    // own release, because poly stays live through the take.
    void heldAcrossPlay() throws Exception {
        bench(); armed(true);
        down(4);
        check("the poly press before PLAY sounded on MIDI and the bus: "+seen(),
            count("on",40)==2&&bus("on",40)==1&&activeNote(40)!=0);
        midi.clear();
        play();
        check("entering PLAY ends its bus note and leaves its MIDI: "+seen(),
            bus("off",40)==1&&count("off",40)==0);
        armed(true);
        up(4);
        check("its lift during PLAY still ends the MIDI note: "+seen(),
            count("off",40)==2&&bus("off",40)==0);
        check("and clears its active-note record",activeNote(40)==0);
        stop();

        bench(); armed(false);
        down(4);
        check("the mono press before PLAY sounded on MIDI and the bus: "+seen(),
            count("on",40)==2&&bus("on",40)==1&&activeNote(40)!=0);
        midi.clear();
        play();
        check("entering PLAY ends it on all three: "+seen(),
            count("off",40)==2&&bus("off",40)==1&&activeNote(40)==0);
        midi.clear();
        up(4);
        check("and its lift then has nothing left to send: "+seen(),midi.isEmpty());
        stop();
        println("PASS a held note ends at the boundary: the bus always, MIDI in mono");
    }

    // Mono is the mode that must NOT play over a take.  The sequencer sends
    // its own notes on the same channel, one at a time, so a second
    // monophonic line there collides with it: a keyboard note-off can end
    // the note the sequence is holding.  So the mono keyboard is silent on
    // MIDI for the whole take - press, lift, and the retrigger release a
    // second key would otherwise send for the first.
    void monoSilentDuringPlay() throws Exception {
        // Both release orders, because letting the NEWER key go hands the
        // note back to the one still held - a fresh Note On from its own
        // call site, which is how the first version of this guard leaked.
        for(boolean newestFirst:new boolean[]{true,false}) {
            bench(); play(); armed(false);
            down(4);
            check("a mono press during PLAY sends nothing: "+seen(),midi.isEmpty());
            down(9);
            check("nor does the retrigger a second key makes: "+seen(),midi.isEmpty());
            down(14);
            if(newestFirst) { up(14); up(9); up(4); } else { up(4); up(9); up(14); }
            check("nor do the lifts, in either order (newest first="+newestFirst+"): "+seen(),
                midi.isEmpty());
            check("nothing reached the 208 bus either",bus("on",40)==0&&bus("off",40)==0);
            stop();
        }
        println("PASS the mono keyboard is silent on MIDI for the whole take");
    }

    // What the take itself does has to survive all of that: the sequencer
    // uses different pool words, and its own notes still go out.
    void theSequenceStillSounds() throws Exception {
        bench(4); play(); armed(false);
        for(int i=0;i<4;i++) externalBeat();
        int on=0, off=0;
        for(String m:midi) { if(m.startsWith("on ")) on++; if(m.startsWith("off ")) off++; }
        check("the running take still reaches MIDI: "+seen(),on>=4&&off>=2);
        // ...and a mono key pressed underneath it changes none of that.
        int before=midi.size();
        down(4); up(4);
        check("a mono key underneath adds nothing: "+seen(),midi.size()==before);
        stop();
        println("PASS the sequence keeps its own MIDI with a key pressed underneath");
    }

    @Override public void run() throws Exception {
        try {
            try { stoppedStillSounds(); } catch(Exception ex) { failures.add(ex.toString()); println(ex.toString()); }
            try { overlap(); } catch(Exception ex) { failures.add(ex.toString()); println(ex.toString()); }
            try { polyPlaysOverTheSequence(); } catch(Exception ex) { failures.add(ex.toString()); println(ex.toString()); }
            try { heldAcrossPlay(); } catch(Exception ex) { failures.add(ex.toString()); println(ex.toString()); }
            try { monoSilentDuringPlay(); } catch(Exception ex) { failures.add(ex.toString()); println(ex.toString()); }
            try { theSequenceStillSounds(); } catch(Exception ex) { failures.add(ex.toString()); println(ex.toString()); }
            if(!failures.isEmpty())throw new Exception("POLY MIDI PROBE FAIL: "+failures);
            println("POLY MIDI PROBE PASS: "+checks+" assertions");
        } finally { if(e!=null)e.dispose(); }
    }
}
