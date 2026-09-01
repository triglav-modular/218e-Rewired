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
        BUS_ON=0x80007f5cL, BUS_OFF=0x80007fc8L,  // the optional 208 bus
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
        if(p==BUS_ON||p==BUS_OFF) {
            midi.add((p==BUS_ON?"on":"off")+" p2 n"+reg("R12")+" v"+reg("R11")+" c"+reg("R10"));
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

    void bench() throws Exception {
        seq=true; clock=true; persistent=true;
        setup(0,false,0);                     // the real chord into WRITE
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
        w(S+0x349,1,1);         // the optional 208 bus, so port two is observable
        w(S+0x2e7,1,3);         // a channel that is not the default
        midi.clear();
    }
    String seen() { return midi.toString(); }
    int count(String kind,int note) {
        int n=0;
        for(String m:midi) if(m.startsWith(kind+" ")&&m.contains(" n"+note+" ")) n++;
        return n;
    }

    // Ordinary poly use: two overlapping keys keep independent lifecycles and
    // each lift ends only its own note, on both outputs.
    void overlap() throws Exception {
        bench(); arm(true);
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
            bench(); arm(poly);
            down(4);
            check("a stopped press sounds on both ports (poly="+poly+"): "+seen(),
                count("on",40)==2&&activeNote(40)!=0);
            up(4);
            check("and ends (poly="+poly+"): "+seen(),count("off",40)==2&&activeNote(40)==0);
        }
        println("PASS the stopped keyboard is untouched in both modes");
    }

    // The audited defect.  With poly MIDI enabled and the arpeggiator off, a
    // key pressed during PLAY used to reach both outputs even though the
    // press makes no sound: the factory poly handler sends its Note On before
    // the pressure state machine ever reaches the muted contact call.
    void polyMutedDuringPlay() throws Exception {
        bench(); play(); arm(true);
        down(4);
        check("a poly press during PLAY sends no MIDI: "+seen(),midi.isEmpty());
        check("and leaves no active note",activeNote(40)==0);
        up(4);
        check("its lift sends no MIDI either: "+seen(),midi.isEmpty());
        stop();
        // STOP's own defensive all-notes-off controller is the only thing on
        // the wire; what must not be there is a keyboard note of either kind.
        check("STOP adds no keyboard note and leaves nothing active: "+seen(),
            activeNote(40)==0&&count("on",40)==0&&count("off",40)==0);
        println("PASS a poly press during PLAY reaches neither MIDI output");
    }

    // A key already sounding when the transport starts still owns its note on
    // the channel it began on, so its lift has to reach MIDI even though
    // presses no longer do.
    void heldAcrossPlay() throws Exception {
        bench(); arm(true);
        down(4);
        check("the press before PLAY sounded: "+seen(),count("on",40)==2&&activeNote(40)!=0);
        play(); arm(true);
        up(4);
        check("the lift during PLAY still ends the note: "+seen(),count("off",40)==2);
        check("and clears its active-note record",activeNote(40)==0);
        stop();
        println("PASS a note held into PLAY is still ended by its own release");
    }

    // Recorded, not endorsed.  The contact handler at 0x80005b6c sends the
    // MONO Note On itself, from 0x80005e3a and 0x80005e5c, AFTER the note-on
    // pool word seq_noteon_mute owns - so PLAY silences the mono keyboard's
    // sound but not its MIDI.  The pair is balanced, so nothing sticks in the
    // receiver; this pins the behaviour until it is decided on.
    void monoStillSendsDuringPlay() throws Exception {
        bench(); play(); arm(false);
        down(4);
        check("mono PLAY still sends the press: "+seen(),count("on",40)==2);
        up(4);
        check("but the pair is balanced, so nothing sticks: "+seen(),
            count("off",40)==2&&activeNote(40)==0);
        stop();
        println("NOTE mono keyboard MIDI is not muted by PLAY; the pair is balanced");
    }

    @Override public void run() throws Exception {
        try {
            try { stoppedStillSounds(); } catch(Exception ex) { failures.add(ex.toString()); println(ex.toString()); }
            try { overlap(); } catch(Exception ex) { failures.add(ex.toString()); println(ex.toString()); }
            try { polyMutedDuringPlay(); } catch(Exception ex) { failures.add(ex.toString()); println(ex.toString()); }
            try { heldAcrossPlay(); } catch(Exception ex) { failures.add(ex.toString()); println(ex.toString()); }
            try { monoStillSendsDuringPlay(); } catch(Exception ex) { failures.add(ex.toString()); println(ex.toString()); }
            if(!failures.isEmpty())throw new Exception("POLY MIDI PROBE FAIL: "+failures);
            println("POLY MIDI PROBE PASS: "+checks+" assertions");
        } finally { if(e!=null)e.dispose(); }
    }
}
