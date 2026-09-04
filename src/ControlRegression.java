// Knob ownership and note-order regressions in emitted firmware.
// The entire applier/preset chain and real clock/selector paths execute;
// only the peripheral model inherited from SequenceEditRegression is used.
//@category Buchla218.Tests
import java.util.*;

public class ControlRegression extends SequenceEditRegression {
    static final long APPLIER=0x8001a2e8L;
    boolean transpose, orders, lean, quantized, gridRhythm;
    int zones=9;

    void controlScan() throws Exception { call(APPLIER); }
    @Override void setup(int length,boolean internal,int position) throws Exception {
        if(seq) { super.setup(length,internal,position); return; }
        fresh(); arp(position); now=0; clockExercise=true;
        w(S+0x308,2,0); w(S+0x2f2,2,0); w(S+0x2da,1,0); w(0x2ee6,2,1023);
        resetTrace();
    }
    @Override void command(int pad) throws Exception { if(seq)super.command(pad); }
    @Override void step() throws Exception {
        // These helpers only update LED RAM. Execute them too, so a changed
        // call chain cannot accidentally rely on the peripheral stub's ABI.
        if(pc()==0x80006808L||pc()==0x800068ccL) {
            if(!e.step(monitor))throw new Exception(e.getLastError());
            return;
        }
        super.step();
    }
    void musicalScan() throws Exception {
        // The actual ADC-event call site, not a direct call to our helper:
        // knob decoder -> pitch target -> output/applier/preset housekeeping.
        call(0x80004d4eL,0x80004d52L);
        call(0x80003590L); call(0x8000307cL);
        pitch(); // same explicit zero-glide fixture as the sequencer tests
    }
    void transposeOutput() throws Exception {
        for(int initial:new int[]{200,900}) {
            setup(0,false,0); command(2);
            w(S+0x350,2,1000); w(S+0x342,1,1); w(S+0x310,2,initial);
            controlScan(); musicalScan();
            long target=r(S+0x352,2), dac=r(S+0x358,2);
            int before=writes;
            w(0x46f3,1,2); musicalScan();
            for(int raw:initial==200?new int[]{400,600,900}:new int[]{700,400,200}) {
                w(S+0x310,2,raw);
                for(int i=0;i<2;i++) {
                    musicalScan();
                    check("transpose edit freezes actual target and DAC, including first movement: "+raw,
                        r(S+0x352,2)==target&&r(S+0x358,2)==dac);
                    check("preset still follows without an early save",r(0x614d,1)==1
                        &&r(0x6140,2)==raw&&writes==before);
                }
            }
            w(0x46f3,1,0); musicalScan();
            check("release keeps actual transpose parked until the knob moves",r(S+0x352,2)==target
                &&r(S+0x358,2)==dac&&r(0x614d,1)==0);
            check("early ownership check preserves release-save edge",persistent?writes>before:writes==before);
            w(S+0x310,2,initial==200?909:191); musicalScan();
            check("the knob moving resumes transpose in this ADC event",r(S+0x352,2)!=target
                &&r(S+0x358,2)!=dac);
            target=r(S+0x352,2); dac=r(S+0x358,2);
            w(S+0x39,1,1); w(S+0x310,2,initial); musicalScan();
            check("global edit freezes actual transpose output",r(S+0x352,2)==target&&r(S+0x358,2)==dac);
            w(S+0x39,1,0); musicalScan();
            check("leaving global edit resumes transpose output",r(S+0x352,2)!=target);
        }
        println("PASS transpose ownership before real pitch target/DAC, first movement, global edit and release save");
    }
    int setting(int raw) { return transpose?raw*zones/1024:raw; }
    long setting() { return r(transpose?S+0x6b:0x60f0,transpose?1:2); }
    void presetOwnership() throws Exception {
        for(int direction:new int[]{1,-1}) {
            setup(0,false,0); command(2);
            int initial=direction==1?200:900;
            w(S+0x310,2,initial); controlScan();
            check("unheld knob follows its role",setting()==setting(initial));
            w(0x46f3,1,2); controlScan();
            check("pad press alone does not acquire preset pickup",r(0x614d,1)==0);
            // The first movement must be frozen too, not just later scans
            // after the following flag has already become visible.
            for(int raw:direction==1?new int[]{400,800,1023}:new int[]{700,300,0}) {
                w(S+0x310,2,raw);
                // The stock ADC decoder also writes state+0x6b. Skipping our
                // store is not a freeze: preserve a private latch and restore
                // it after this real factory writer, not a synthetic poke.
                if(transpose)call(0x80004a00L);
                controlScan();
                check("preset voltage follows while its musical role stays frozen: initial="+initial+" raw="+raw,
                    r(0x614d,1)==1&&r(0x6140,2)==raw&&setting()==setting(initial));
                if(transpose)check("transpose remains enabled during preset edit",r(S+0x6a,1)==1);
            }
            w(0x46f3,1,0); controlScan();
            int parked=direction==1?1023:0;
            check("pad release keeps the parked knob frozen",r(0x614d,1)==0&&setting()==setting(initial));
            w(S+0x310,2,parked+8*direction*-1); controlScan();
            check("eight units from the parked spot is still frozen",setting()==setting(initial));
            w(S+0x310,2,parked+9*direction*-1); controlScan();
            check("the knob moving returns its role",setting()==setting(parked+9*direction*-1));
            // Editing another pad must not freeze knob 4.
            w(0x46f0,1,2); w(S+0x30a,2,600); w(S+0x310,2,500); controlScan();
            check("preset isolation is per knob",r(0x614a,1)==1&&setting()==setting(500));
            w(0x46f0,1,0); controlScan();
            w(S+0x39,1,1); w(S+0x310,2,900); controlScan();
            check("global edit still freezes musical knob value",setting()==setting(500));
            w(S+0x39,1,0); controlScan();
            check("leaving global edit resumes knob role",setting()==setting(900));
        }
        println("PASS "+(transpose?"transpose":"vibrato")+" preset ownership: first/later movement, both directions, release, other pads, global edit");
    }
    void orderFixture(int zone,int direction,int... keys) throws Exception {
        setup(0,false,1); command(1); command(1); // sequence stopped, arp active
        w(S+0x21a,1,keys.length);
        for(int key:keys) {
            w(S+0x21b+key,1,1); e.writeRegister("R12",key); call(0x8001a020L);
        }
        w(S+0x34d,1,4); w(S+0x30a,2,zone*176+40); w(0x614e,1,direction);
        w(S+0x2fc,2,0); // /1 even after acquisition: zero is the fast end now
    }
    void noteOrders() throws Exception {
        // Non-sorted press order distinguishes the pitch walks from the
        // as-played walks. Start at the middle pitch for mirror, so neither
        // starting direction needs a wrap before its first end turn.
        int[][] expected={{9,14,4,9,14,4,9,14}, {14,9,4,14,9,4,14,9},
            {4,9,14,9,4,9,14,9}, {14,9,4,14,9,4,14,9}, {9,14,4,9,14,4,9,14}};
        for(int zone=0;zone<6;zone++) {
            orderFixture(zone,0,4,14,9);
            if(zone==2)w(S+0x34d,1,9);
            Set<Integer> randomKeys=new HashSet<>();
            for(int i=0;i<(zone==5?32:8);i++) {
                externalBeat(); int key=(int)r(S+0x34d,1);
                if(zone<5)check("order zone "+zone+" step "+i+" got "+key,key==expected[zone][i]);
                else { check("random selects only held keys",key==4||key==9||key==14); randomKeys.add(key); }
            }
            if(zone==5)check("random reaches all held keys",randomKeys.size()==3);
            if(zone!=2)check("non-mirror orders leave mirror direction alone",r(0x614e,1)==0);
        }
        orderFixture(2,1,4,9,14); w(S+0x34d,1,9);
        for(int key:new int[]{14,9,4,9,14,9,4,9}) {
            externalBeat(); check("mirror reverses from ascending too",r(S+0x34d,1)==key);
        }
        orderFixture(2,0,9); w(S+0x34d,1,9);
        for(int i=0;i<4;i++) { externalBeat(); check("single-key mirror remains playable",r(S+0x34d,1)==9); }
        println("PASS all six note-order zones, both mirror directions, held-key endpoints and single-key mirror");
    }
    void key(int key) throws Exception { e.writeRegister("R12",key); call(0x80018d00L); }
    void octavePad(int pad) throws Exception { e.writeRegister("R12",pad); call(0x8000698cL); call(0x80003590L); }
    // A recorded step is RELATIVE to the take's reference at 0x62f4 - the
    // transpose the take was born under, adopted from 0x60a0 by the first
    // note into an empty take.  So the absolute pitch a step STANDS FOR is
    // the step plus that reference, and the pitch it PLAYS is the step plus
    // today's transpose: equal where the take was recorded, and moved by
    // exactly how far the pad has walked since.
    long step(int index) { return (short)r(0x6160+2*index,2); }
    long sounds(int index) { return step(index)+(short)r(0x62f4,2); }
    long livePad() { return (short)r(0x60a0,2); }
    // What the factory target preparation does to a finished pitch.
    long dac(long v) { return v<0?0:v>4095?4095:v; }
    void releasedOrders() throws Exception {
        for(int position:lean?new int[]{2}:new int[]{1,2})for(int zone:new int[]{3,4}) {
            setup(0,false,position); command(1); command(1);
            for(int k:new int[]{4,14,9})key(k);
            e.writeRegister("R12",14); call(position==1?0x80018d00L:0x8001a280L);
            check("real release/unlatch removes target",r(S+0x21b+14,1)==0&&r(S+0x21a,1)==2);
            w(S+0x34d,1,9); w(S+0x30a,2,zone*176+40); w(S+0x2fc,2,0);
            for(int i=0;i<6;i++) {
                externalBeat(); int k=(int)r(S+0x34d,1);
                check("press order skips removed history: position="+position+" zone="+zone,
                    k==(i%2==0?4:9)&&r(S+0x21b+k,1)==1);
            }
        }
        orderFixture(4,0,4,14,9);
        for(int k:new int[]{4,14,9})w(S+0x21b+k,1,0);
        check("reverse history with no held notes terminates",select(4,9)==-1);
        w(0x6000,1,32);
        for(int i=1;i<=32;i++)w(0x6000+i,1,255);
        check("reverse rejects invalid history slots",select(4,255)==-1);
        w(0x6000,1,255);
        check("reverse rejects invalid history count",select(4,255)==-1);
        println("PASS forward/reverse after real release"+(lean?"":" and unlatch")+"; empty and corrupt history bounded");
    }
    // A quick tap: the contact is confirmed on one scan and the key is gone on
    // the next.  The deferred trigger then expires AFTER the lift, and before
    // pulse_guard it raised the gate over a key nobody was holding; the
    // factory drop then parked it at the sustain with no release left to end
    // it.  One pitch pass per scan here, as the instrument runs it.
    void tapScan() throws Exception {
        controlScan(); call(0x80004d4eL,0x80004d52L);
        call(0x80003590L); call(0x8000307cL);
    }
    void quickTapGate() throws Exception {
        setup(0,false,0);
        w(S+0x342,1,1); w(S+0x343,1,0); w(S+0x344,4,2); w(S+0x2ef,1,1);
        w(S+0x306,2,0); w(S+0x30a,2,0); w(S+0x30c,2,0); w(S+0x30e,2,0); w(S+0x310,2,0);
        for(int i=0;i<8;i++) tapScan();
        check("gate at rest",r(S+0x354,2)==0&&r(0x60ee,1)==0);
        touchOn(9); w(0x6100+18,2,600); tapScan();
        check("the note-on leaves its trigger pending",r(0x60ee,1)==1&&r(S+0x21a,1)==1);
        w(0x6100+18,2,0); touchOff(9); tapScan();
        check("a lift on the next scan leaves the gate down",
            r(S+0x354,2)==0&&r(0x60ee,1)==0&&r(S+0x21a,1)==0&&r(S+0x238,1)==0);
        for(int i=0;i<3;i++) tapScan();
        check("and nothing raises it afterwards",r(S+0x354,2)==0);
        touchOn(9); w(0x6100+18,2,600); tapScan(); tapScan();
        check("a key held two scans still gets its trigger",r(S+0x354,2)==0xfff&&r(0x60ee,1)==0);
        w(0x6100+18,2,0); touchOff(9); tapScan();
        check("and its release drops the gate",r(S+0x354,2)==0);
        println("PASS quick tap: a trigger expiring after the lift is dropped, a held key keeps its trigger");
    }
    // The factory touch scan, which is what actually knows where the
    // fingers are: 0x80005b6a on contact, 0x80005edc on lift.  Neither is
    // latch-aware, so they stay true across the switch while the note pair
    // is deliberately held open by the latch.
    void touchOn(int k) throws Exception { e.writeRegister("R12",k); call(0x80005b6aL); }
    void touchOff(int k) throws Exception {
        e.writeRegister("R12",k); e.writeRegister("R11",0); call(0x80005edcL);
    }
    void latchExitHold() throws Exception {
        // Leaving latch with a key still under a finger keeps the arp on it.
        // Both destinations, because the switch leaves latch in two
        // directions and a key held through either is still being played.
        for(int destination:new int[]{0,2}) {
            setup(0,false,0); command(1); command(1);
            touchOn(9); touchOn(4);
            check("both keys register as held and as touched",
                r(S+0x21a,1)==2&&r(S+0x238,1)==2
                &&r(S+0x21b+9,1)==1&&r(S+0x239+9,1)==1);
            arp(1); controlScan();
            touchOff(4);
            check("the latch holds a lifted key open, the touch scan does not",
                r(S+0x21b+4,1)==1&&r(S+0x239+4,1)==0&&r(S+0x21a,1)==2);
            arp(destination); controlScan();
            check("leaving latch drops the released key: destination="+destination,
                r(S+0x21b+4,1)==0);
            check("leaving latch keeps the key still under a finger: destination="+destination,
                r(S+0x21b+9,1)==1&&r(S+0x21a,1)==1);
            // The count has to agree with the flags it was rebuilt from, or
            // release_count_guard can never walk it back down again.
            touchOff(9);
            check("the surviving key still releases cleanly: destination="+destination,
                r(S+0x21b+9,1)==0&&r(S+0x21a,1)==0&&r(S+0x239+9,1)==0);
        }
        // Nothing under a finger: leaving latch still releases everything,
        // which is the behaviour the latch has always had.
        setup(0,false,0); command(1); command(1);
        touchOn(9); arp(1); controlScan(); touchOff(9);
        check("a fully released latch still holds its keys",r(S+0x21a,1)==1);
        arp(0); controlScan();
        check("and leaving latch with no finger down clears them",
            r(S+0x21a,1)==0&&r(S+0x21b+9,1)==0);
        // And the selector keeps running on the survivor, which is the
        // symptom the report was actually about.
        setup(0,false,0); command(1); command(1);
        touchOn(9); touchOn(4); arp(1); controlScan(); touchOff(4);
        arp(0); controlScan();
        w(S+0x34d,1,9); w(S+0x30a,2,40); w(S+0x2fc,2,0);
        for(int i=0;i<4;i++) {
            externalBeat();
            check("the arp keeps advancing on the held key after leaving latch",
                r(S+0x34d,1)==9);
        }
        println("PASS leaving latch keeps keys still under a finger, releases the rest, and the arp keeps running");
    }
    int select(int zone,int last) throws Exception {
        w(S+0x34d,1,last); w(0x60f2,1,(zone*128+5)/6);
        e.writeRegister("R12",S+0x21b); call(0x8001aec0L);
        return (int)reg("R12");
    }
    int rank(int key,boolean latched) {
        return 32*((int)r(0x854+2*key,2)+(latched?(short)r(0x60a2+2*key,2):0))+key;
    }
    void latchedOrders() throws Exception {
        int[][] expected={{12,6,0,12,6,0,12,6},{6,12,0,6,12,0,6,12},{12,6,12,0,12,6,12,0}};
        for(int zone=0;!lean&&zone<3;zone++) {
            setup(0,false,1); command(1); command(1);
            w(S+0x342,1,1); w(S+0x343,1,0); w(S+0x310,2,200); controlScan();
            octavePad(1); key(0); octavePad(3); key(6); octavePad(1); key(12);
            check("fixture stamps pitches out of key-slot order",rank(0,true)<rank(12,true)&&rank(12,true)<rank(6,true));
            w(S+0x34d,1,0); w(S+0x30a,2,zone*176+40); w(S+0x2fc,2,0); w(0x614e,1,1);
            for(int k:expected[zone]) {
                externalBeat(); call(0x80003590L); pitch();
                check("order follows real latched pitch zone="+zone+" expected="+k,r(S+0x34d,1)==k);
                check("selected pitch reaches real target",r(S+0x352,2)==rank(k,true)/32);
            }
        }
        // Independent rank oracle: mapped equal pitches, extreme signed
        // stamps, empty/full sets, missing current note, both directions,
        // and regular arp ignoring stamps left behind by latch mode.
        Random random=new Random(218);
        for(boolean latched:new boolean[]{false,true}) {
            boolean stamped=latched&&!lean;
            orderFixture(0,1); arp(latched?1:2);
            for(int sample=0;sample<12;sample++) {
                List<Integer> held=new ArrayList<>();
                for(int k=0;k<29;k++) {
                    boolean down=sample==0?false:sample==1?true:random.nextBoolean();
                    w(S+0x21b+k,1,down?1:0); if(down)held.add(k);
                    w(0x854+2*k,2,1000+100*random.nextInt(8));
                    w(0x60a2+2*k,2,sample<4?0:random.nextInt(65536));
                }
                held.sort(Comparator.comparingInt(k->rank(k,stamped)));
                for(int zone=0;zone<3;zone++)for(int direction:new int[]{0,1}) {
                    int last=sample%3==0?255:random.nextInt(29);
                    w(0x614e,1,direction);
                    for(int step=0;step<5;step++) {
                        int dir=zone==0?1:zone==1?-1:direction==1?1:-1;
                        int expectedKey=-1;
                        if(!held.isEmpty()) {
                            int ref=last<29?rank(last,stamped):dir>0?Integer.MIN_VALUE:Integer.MAX_VALUE;
                            List<Integer> walk=new ArrayList<>(held); if(dir<0)Collections.reverse(walk);
                            for(int k:walk)if(dir>0?rank(k,stamped)>ref:rank(k,stamped)<ref){expectedKey=k;break;}
                            if(expectedKey<0&&zone==2) {
                                dir=-dir; Collections.reverse(walk);
                                for(int k:walk)if(dir>0?rank(k,stamped)>ref:rank(k,stamped)<ref){expectedKey=k;break;}
                            }
                            if(expectedKey<0)expectedKey=dir>0?held.get(0):held.get(held.size()-1);
                            if(zone==2)direction=expectedKey==held.get(held.size()-1)?0:expectedKey==held.get(0)?1:dir>0?1:0;
                        }
                        int actual=select(zone,last);
                        check("rank oracle latched="+latched+" sample="+sample+" zone="+zone+" last="+last+" expected="+expectedKey+" got="+actual,actual==expectedKey);
                        if(zone==2&&!held.isEmpty())check("mirror direction follows pitch endpoint",r(0x614e,1)==direction);
                        last=actual<0?255:actual;
                    }
                }
            }
        }
        println("PASS pitch-aware up/down/mirror: "+(lean?"factory arp ignores stamps":"real octave latching and signed stamps")
            +", equal pitches, all/none held, missing current, regular arp");
    }
    void sound() throws Exception { controlScan(); call(0x80003590L); pitch(); }
    void noteUp(int key) throws Exception { e.writeRegister("R12",key); call(0x8001a280L); }
    void latchFixture() throws Exception {
        w(S+0x342,1,1); w(S+0x343,1,0); w(S+0x310,2,0); controlScan();
    }
    void latchRecording() throws Exception {
        // WRITE entered from the latching arp: every physical press records
        // the pitch it SOUNDS - the key table plus the transpose published
        // at 0x60a0 - never a slot stamp, which does not exist yet for a
        // fresh press and is somebody else's note for a reused slot.  What
        // lands in the store is that sum measured from the take's own
        // reference, so the checks below read a step through sounds().  The
        // published transpose can walk one unit between scans (the latch
        // toggle's own tolerance exists for the same reason), so repeats
        // are compared one unit wide.
        setup(0,false,1); latchFixture(); octavePad(3);
        key(0); sound();
        long high=r(S+0x352,2);
        check("fresh latch sounds above its table pitch",high>r(0x854,2));
        // The first note into an empty take adopts today's transpose as the
        // reference, so it stores a bare table pitch that still STANDS FOR
        // the pitch it sounded.
        check("the first note adopts today's transpose as the take's reference",
            r(0x61e0,1)==1&&Math.abs((short)r(0x62f4,2)-livePad())<=1);
        check("fresh latch records the pitch it sounds",r(0x61e0,1)==1&&sounds(0)==high);
        noteUp(0); octavePad(1); key(0); sound();
        check("repeat press records today's octave, not the old stamp",
            r(0x61e0,1)==2&&sounds(1)==r(0x854,2)+livePad());
        // The audition must sound what was recorded.  A repeat press at a
        // new octave allocates a fresh latch slot; auditioning the physical
        // key re-based off the OLD slot's stamp and sounded the old octave.
        check("audition sounds the pitch it recorded",r(S+0x352,2)==sounds(1));
        // A third press at the same octave toggles that slot OFF.  The
        // press is still recorded - it is what was played - and the
        // audition still sounds the stored pitch: the physical slot's old
        // stamp, which is where the un-pinned audition fell back to, is
        // two octaves away.
        noteUp(0); key(0); sound();
        check("a toggle-off press is recorded",
            r(0x61e0,1)==3&&Math.abs((short)r(0x6164,2)-(short)r(0x6162,2))<=1);
        check("and its audition sounds the stored pitch",
            r(S+0x21b+1,1)==0&&Math.abs((short)r(S+0x352,2)-sounds(2))<=1);
        // A slot latched and unlatched OUTSIDE the take keeps its stamp;
        // recording that key afterwards must not resurrect it.
        setup(0,false,1); command(2); latchFixture();
        octavePad(3); key(0); sound(); noteUp(0); key(0); sound();
        check("fixture unlatches slot zero with its stamp left",
            r(S+0x21b,1)==0&&(short)r(0x60a2,2)!=0);
        octavePad(1); command(0); key(0); sound();
        check("a reused slot records the pitch it sounds",
            r(0x61e0,1)==1&&sounds(0)==r(S+0x352,2));
        // Playback adds the live pad transpose once, and a still-held latch
        // slot must not re-base it again.
        setup(0,false,1); latchFixture(); octavePad(3);
        key(0); sound(); noteUp(0);
        long wanted=r(S+0x352,2);
        key(0); sound(); noteUp(0); key(0); sound();
        check("every repeat press is recorded, the last still held",
            r(0x61e0,1)==3&&r(S+0x21b,1)==1);
        check("repeat presses record the pitch they sound",
            Math.abs(sounds(1)-wanted)<=1&&Math.abs(sounds(2)-wanted)<=1);
        // Presses that never moved the pad are one value: a step drifting
        // against its neighbour at the SAME pad is the recorder, not the
        // reference.
        check("and presses at one pad record one step",
            Math.abs(step(1)-step(2))<=1&&Math.abs(step(0)-step(1))<=1);
        command(1); octavePad(1); w(S+0x2fc,2,0);
        externalBeat(); sound(); externalBeat(); sound();
        check("playback in latch mode plays the step it recorded",
            r(S+0x352,2)==r(0x61e2,2));
        octavePad(3); externalBeat(); sound();
        check("the pad transposes playback exactly once",
            r(S+0x352,2)==r(0x61e2,2)+(short)r(0x60a0,2));
        octavePad(1); arp(2); sound();
        check("leaving latch mode does not move the step",r(S+0x352,2)==r(0x61e2,2));
        // A one-shot preview auditions the take AS RECORDED: the pad that
        // transposes playback must leave a preview alone.  seq_preview_pin
        // hands the step up carrying its own reference and minus the live
        // transpose, so the factory adder's re-add cancels out.  This matters
        // most because the bare pad that STARTS a preview is itself an octave
        // chooser - it moves the very thing it plays.
        arp(1); command(1); command(0); octavePad(3); bare(1);
        externalBeat(); sound();
        check("a preview sounds the take as recorded, not where the pad is",
            r(S+0x352,2)==dac((short)r(0x61e2,2)+(short)r(0x62f4,2)));
        // And the pin itself, driven at both flag positions with no fixture
        // timing in play: playback hands the step up untouched, a preview
        // hands it up carrying its reference and minus the live transpose,
        // so the preparation's re-add lands back on the recorded pitch.
        long keepRef=r(0x62f4,2), keepPad=r(0x60a0,2);
        w(0x62f4,2,969); w(0x60a0,2,0xfe1b);
        e.writeRegister("R8",485); w(0x62fe,1,0); call(0x8001b944L);
        long plainStep=(short)reg("R8");
        e.writeRegister("R8",485); w(0x62fe,1,1); call(0x8001b944L);
        check("the pin leaves playback alone and re-bases a preview",
            plainStep==485&&(short)reg("R8")==485+969+485);
        w(0x62fe,1,0); w(0x62f4,2,keepRef); w(0x60a0,2,keepPad);
        // And keeps sounding it when the pad moves under a running preview.

        println("PASS latch recording: fresh press, repeat, toggle-off audition, reused slot, relative store, transposed playback, pinned preview");
    }
    void recordedOctaves() throws Exception {
        // The octave switch reaches a recording in EVERY arp position, the
        // way it reaches the latches: the same key entered at two octaves
        // is two different recorded pitches, and neutral playback plays
        // the interval that was played.  The first octave is the take's
        // reference rather than a number in the store, so the interval -
        // not the opening pitch - is what the store has to carry.  With the arp OFF the keyboard
        // itself sounds the press, so no audition is armed on top of it -
        // that sent the same MIDI note twice with one note-off to share.
        for(int position:new int[]{0,2}) {
            setup(0,false,position); latchFixture(); octavePad(3);
            key(0); sound();
            long high=r(0x854,2)+(short)r(0x60a0,2);
            check("the octave is in the pitch the step stands for, arp position "+position,
                Math.abs(sounds(0)-high)<=1&&r(0x61e0,1)==1);
            if(position==0)
                check("the arp OFF keyboard needs no audition",
                    r(0x6230,2)==0&&r(0x2eed,1)==0);
            else
                check("the regular-arp audition sounds the stored pitch",
                    Math.abs((short)r(S+0x352,2)-sounds(0))<=1);
            noteUp(0); octavePad(1); key(0); sound();
            // Read the pad the press itself saw: 0x60a0 walks a unit or two
            // over the scans that follow, and every comparison here is
            // against the transpose that was actually recorded against.
            long neutral=livePad();
            check("the neutral repeat stores its own octave",
                Math.abs(sounds(1)-(r(0x854,2)+neutral))<=1);
            // Which is where the interval lives now: the reference carries
            // the first octave, so the store has to carry the DISTANCE
            // between the two presses, whatever reference was adopted.
            check("and the two steps are the octave apart that was played",
                Math.abs((step(0)-step(1))-((short)r(0x62f4,2)-neutral))<=1);
            noteUp(0);
            long opening=step(0), following=step(1);
            // Neutral playback is the whole take shifted down by its own
            // reference, so the lower step falls under the DAC: it has to
            // clamp at the rail the way any other target does, not wrap.
            command(1); octavePad(1); w(S+0x2fc,2,0);
            externalBeat(); sound();
            check("playback opens on the recorded octave",r(S+0x352,2)==dac(step(0)+livePad()));
            externalBeat(); sound();
            check("and a step under the rail plays clamped, not wrapped",
                r(S+0x352,2)==dac(step(1)+livePad()));
            // The pad transposes PLAY, and only play: a preview is pinned.
            octavePad(3); externalBeat(); sound();
            check("the pad transposes playback",
                r(S+0x352,2)==r(0x61e2,2)+(short)r(0x60a0,2));
            // A preview armed here is armed mid-PLAY, on a two-step take that
            // reaches its end sentinel on the next beat: the note sounding is
            // still the one PLAY staged, chosen before the flag went up, so
            // what this fixture measures is transport timing rather than the
            // pin.  The pin is checked where it can be seen cleanly -- driven
            // directly in latchRecording, and once through a preview there.
            command(1); command(0); bare(1); externalBeat(); sound();
            externalBeat(); externalBeat();
            if(persistent) {
                command(0);
                long page=call(NEWEST);
                check("the octave take is saved",page!=0&&r(page+24,1)==2);
                cold();
                check("and survives a power cycle",
                    r(0x61e0,1)==2&&step(0)==opening&&step(1)==following);
            }
        }
        println("PASS recorded octaves: stored per press in OFF and regular arp, keyboard-only OFF sounding, pinned preview, transposed play");
    }
    void capacityAudition() throws Exception {
        // A press the recorder cannot take must not repaint the pending
        // audition: at 64 steps the take is full, and the note waiting to
        // be heard belongs to the last press that was RECORDED.
        setup(0,false,1); latchFixture(); octavePad(1);
        w(0x61e0,1,63);
        key(4);
        check("step 64 records and arms its audition",
            r(0x61e0,1)==64&&r(0x6230,2)==5);
        key(12);
        check("a rejected press leaves the pending audition alone",
            r(0x61e0,1)==64&&r(0x6230,2)==5);
        sound();
        check("what sounds is what was recorded",
            Math.abs((short)r(S+0x352,2)-(short)r(0x6160+126,2))<=1);
        println("PASS full-take audition: a 65th press neither records nor re-aims the pending note");
    }
    void pressureOwnership() throws Exception {
        // A finger's pressure belongs to the note under it.  A repeat
        // press at a new octave lives in an allocated slot: the pressure
        // follows it there, and the old note still latched in the
        // finger's own slot number - nobody's finger - pulls nothing.
        // In WRITE, where the audition holds the sounding note still -
        // the same footing the audit's reproduction measured on.
        setup(0,false,1); latchFixture(); octavePad(3);
        key(0); sound(); noteUp(0);
        octavePad(1); key(0); sound();
        long alone=r(S+0x352,2);
        w(0x3490,1,2);
        for(int k=0;k<29;k++)w(0x3686+2*k,2,k==0?900:110);
        call(0x8001aa10L); w(S+0x306,2,900);
        for(int i=0;i<50;i++)sound();
        check("one finger on its own note bends nothing",
            r(0x60e2,2)==0&&Math.abs((short)r(S+0x352,2)-(short)alone)<=1);
        // A second finger pulls toward ITS note - downward, which the old
        // two-octave note still latched in slot 0 would have overwhelmed.
        key(4); w(0x3490+4,1,2);
        for(int k=0;k<29;k++)w(0x3686+2*k,2,(k==0||k==4)?900:110);
        call(0x8001aa10L);
        for(int i=0;i<50;i++)sound();
        check("a second finger pulls toward its own note",
            (short)r(0x60e2,2)<0);
        w(0x3490,1,0); w(0x3490+4,1,0); call(0x8001aa10L);
        for(int i=0;i<50;i++)sound();
        check("release clears the blend",r(0x60e2,2)==0);

        // One physical key can own several octave-latched slots.  Toggling
        // an OLDER one off must clear this press's current ownership instead
        // of leaving the finger attached to a different surviving octave.
        setup(0,false,1); command(0); latchFixture();
        octavePad(3); key(0); sound(); noteUp(0); // slot 0, high
        octavePad(1); key(0); sound(); noteUp(0); // slot 1, neutral
        key(4); sound();                           // another held note/anchor
        octavePad(3); key(0); sound();             // remove older slot 0
        w(0x3490,1,2); w(0x3490+4,1,2);
        for(int k=0;k<29;k++)w(0x3686+2*k,2,(k==0||k==4)?900:110);
        call(0x8001aa10L); w(S+0x306,2,900);
        for(int i=0;i<50;i++)sound();
        check("older octave is removed while the other latches remain",
            r(S+0x21b,1)==0&&r(S+0x21c,1)==1&&r(S+0x21f,1)==1);
        check("older toggle clears the physical key's current slot",r(0x6521,1)==0);
        check("only the genuinely held note receives pressure",
            r(0x6542,2)==0&&r(0x6548,2)>0);
        short olderToggleTarget=(short)r(0x60e0,2);
        short olderToggleBlend=(short)r(0x60e2,2);
        // Change only the removed key's pressure.  The still-held key 4 can
        // legitimately steer from the current sounding anchor, so its blend
        // need not be zero; the orphaned key 0 must have no influence on
        // either the raw target or the settled applied offset.
        w(0x3686,2,200);
        call(0x8001aa10L); w(S+0x306,2,900);
        for(int i=0;i<50;i++)sound();
        check("the removed note cannot bend a surviving octave",
            (short)r(0x60e0,2)==olderToggleTarget&&
            Math.abs((short)r(0x60e2,2)-olderToggleBlend)<=1);
        println("PASS pressure ownership: allocated slots carry their finger's weight, orphaned and older-toggle latches carry none");
    }
    void staleAnchor() throws Exception {
        // With the arp off, the last arp key is never written: it holds
        // whatever the last step left, or key 0 from a cold boot.  The
        // blend's anchor used to be translated through that key's map
        // entry, and the entry outlived the press - so once key 1 had been
        // pressed and released, every later single key was measured
        // against key 1's pitch, thresholded as a non-anchor, and bent
        // sharp by the whole interval (BlendAnchorProbe).  The arp-off
        // pressure fixture is the probe's: contact, raw reading, cache pass.
        setup(0,false,0);
        w(S+0x342,1,1); w(S+0x343,1,0); w(S+0x344,4,2); w(S+0x2ef,1,1);
        w(S+0x306,2,900); w(S+0x310,2,0);
        for(int i=0;i<8;i++){ controlScan(); musicalScan(); }
        touchOn(9); w(0x3490+9,1,2); w(0x3686+18,2,900); call(0x8001aa10L);
        for(int i=0;i<60;i++){ controlScan(); musicalScan(); }
        long fresh=r(S+0x358,2);
        check("a single key on a fresh instrument bends nothing",
            r(0x60e2,2)==0&&r(S+0x352,2)==r(0x854+18,2));
        w(0x3490+9,1,0); w(0x3686+18,2,110); call(0x8001aa10L); touchOff(9);
        for(int i=0;i<30;i++){ controlScan(); musicalScan(); }
        check("a release gives up the key's slot ownership",r(0x6521+9,1)==0);
        touchOn(0); w(0x3490,1,2); w(0x3686,2,900); call(0x8001aa10L);
        for(int i=0;i<30;i++){ controlScan(); musicalScan(); }
        check("key 1 alone bends nothing either",r(0x60e2,2)==0);
        w(0x3490,1,0); w(0x3686,2,110); call(0x8001aa10L); touchOff(0);
        for(int i=0;i<30;i++){ controlScan(); musicalScan(); }
        check("key 1's ownership is gone with its finger",r(0x6521,1)==0&&r(S+0x34d,1)==0);
        touchOn(9); w(0x3490+9,1,2); w(0x3686+18,2,900); call(0x8001aa10L);
        for(int i=0;i<60;i++){ controlScan(); musicalScan(); }
        check("a single key after key 1 sounds exactly as it did before",
            r(0x60e2,2)==0&&r(0x60e0,2)==0&&r(S+0x358,2)==fresh);
        w(0x3490+9,1,0); w(0x3686+18,2,110); call(0x8001aa10L); touchOff(9);
        for(int i=0;i<30;i++){ controlScan(); musicalScan(); }
        // Key 1 held, then key 9 on top: with the arp off the last key
        // touched sounds, and it is the anchor, so the finger underneath
        // pulls toward its own, lower, note - never above the top.  The
        // stale anchor had it the other way round, pushing above key 9.
        touchOn(0); w(0x3490,1,2); w(0x3686,2,900); call(0x8001aa10L);
        for(int i=0;i<10;i++){ controlScan(); musicalScan(); }
        touchOn(9); w(0x3490+9,1,2); w(0x3686+18,2,900); call(0x8001aa10L);
        for(int i=0;i<60;i++){ controlScan(); musicalScan(); }
        check("a lower key held underneath pulls the pitch down, not up",
            r(S+0x350,2)==r(0x854+18,2)&&(short)r(0x60e2,2)<0&&r(S+0x358,2)<fresh);
        w(0x3490,1,0); w(0x3490+9,1,0); w(0x3686,2,110); w(0x3686+18,2,110);
        call(0x8001aa10L); touchOff(0); touchOff(9);
        for(int i=0;i<30;i++){ controlScan(); musicalScan(); }
        check("releasing both clears the blend",r(0x60e2,2)==0);
        println("PASS stale anchor: a released key no longer anchors the blend, with the arp off");
    }
    void previewBoundaries() throws Exception {
        // The end of a one-shot preview is a sentinel, not a wrap: no step
        // follows it, so the last gate falls and the last MIDI note ends,
        // whatever the take BEGINS with.  An ordinary loop still ties.
        setup(0,false,0);
        w(0x6160,2,0x7fff); w(0x6162,2,600); w(0x61e0,1,2); w(0x61e1,1,2);
        w(0x6158,1,2); w(0x62fe,1,1); w(0x2eed,1,1);
        call(0x8001b4f0L);
        check("no tie follows the end of a preview",reg("R8")==3);
        call(0x8001b8f0L);
        check("and the sounding note is ended",reg("R12")==1);
        w(0x62fe,1,0);
        call(0x8001b4f0L);
        check("a loop still carries the gate into a leading tie",((int)reg("R8"))<0);
        call(0x8001b8f0L);
        check("and holds its MIDI note across the wrap",reg("R12")==0);
        // Leaving WRITE ends an audition still ringing, note-off included.
        w(0x6158,1,1); w(0x2eed,1,1); command(0);
        check("leaving WRITE releases the sounding note",
            r(0x6158,1)==0&&r(0x2eed,1)==0);
        println("PASS preview boundaries: sentinel gates, loop wrap ties, WRITE exit releases");
    }
    // The pitch adder's middle position, driven from the preset store the
    // four getters read: the stored 0..1023 becomes int((store << 2) * 0.33f)
    // through the factory soft float, then - in a quantising build - the
    // nearest interval of the live key table above its bottom entry, whole
    // periods stripped and restored.  The period is a build constant; every
    // variant this suite builds repeats at the octave.
    static final int PERIOD=484;
    int presetUnits(int store) { return (int)(float)((double)(store<<2)*0.33); }
    int quantised(int units) {
        // A transcription of the cave, in the cave's own order: entries from
        // the bottom up, first strictly nearer candidate wins.
        if(units<0)return units;
        int whole=units/PERIOD*PERIOD, rem=units%PERIOD;
        int best=PERIOD, bestd=PERIOD-rem, t0=(int)r(0x854,2);
        for(int k=0;k<32;k++) {
            int c=(int)r(0x854+2*k,2)-t0;
            while(c<0)c+=PERIOD;
            while(c>=PERIOD)c-=PERIOD;
            int d=Math.abs(c-rem);
            if(d<bestd) { bestd=d; best=c; }
        }
        return best+whole;
    }
    long presetTarget(int base,int store) throws Exception {
        w(S+0x350,2,base); w(0x613a,2,store); musicalScan(); return r(S+0x352,2);
    }
    void presetQuantize() throws Exception {
        setup(0,false,0);
        // Toggle in the middle, pad 1 active: the adder adds preset 1.
        w(S+0x342,1,0); w(S+0x343,1,1); w(S+0x344,4,1); w(S+0x2ef,1,0);
        // A build that forces transpose mode adds a constant period as well
        // (-484 with the toggle off the octave position); measure it, then
        // sit the fixture so the targets clear the factory's floor clamp at
        // 9 and its +-1 fix-ups at 0x1e0 and 0x78a: base+K at the bottom
        // key, and the whole knob span under 1930.
        int k0=(int)presetTarget(1000,0)-1000;
        int base=485-k0;
        long rest=presetTarget(base,0);
        check("preset 1 at zero adds nothing",rest==presetTarget(base,0));
        Set<Integer> offsets=new TreeSet<>();
        for(int store=0;store<=1023;store+=store<64?1:store<200?7:13) {
            // The factory soft float truncates where Java rounds, so where
            // (store << 2) * 0.33 lands within a rounding error of a whole
            // number the firmware can read one unit under the model (store
            // 25: 32 against 33).  Accept the model and the model one under.
            int units=presetUnits(store);
            long a=rest+(quantized?quantised(units):units);
            long b=rest+(quantized?quantised(units-1):units-1);
            long got=presetTarget(base,store);
            check((quantized?"quantised":"free")+" preset offset at store "+store+": got "
                +(got-rest)+", expected "+(a-rest),got==a||got==b);
            offsets.add((int)(got-rest));
        }
        check("the top of the knob still reaches the same span",
            presetTarget(base,1023)-rest>=1300&&presetTarget(base,1023)-rest<=1352);
        if(quantized) {
            // Every offset the knob produced is an interval of the live
            // table, so the transposed pitch is one the keys themselves
            // reach: the same raw value, the same remap, the same DAC.
            for(int off:offsets) {
                int rem=off%PERIOD, t0=(int)r(0x854,2); boolean member=rem==0;
                for(int k=0;k<32&&!member;k++) {
                    int c=(int)r(0x854+2*k,2)-t0; while(c<0)c+=PERIOD; while(c>=PERIOD)c-=PERIOD;
                    member=c==rem;
                }
                check("offset "+off+" is a degree of the live table",member);
            }
            check("the knob reaches more than one degree",offsets.size()>=12);
            int[] probes={1,3,5,7,12,17,24,31};
            for(int k:probes) {
                int target=(int)r(0x854+2*k,2), want=target-(int)r(0x854,2);
                // Find a store whose quantised offset is this key's interval.
                int store=-1;
                for(int s=0;s<=1023&&store<0;s++)if(quantised(presetUnits(s))==want)store=s;
                if(store<0)continue;
                presetTarget(target-k0,0);
                // A key whose direct route crosses a factory fix-up lands a
                // unit off; it cannot serve as the reference, so skip it.
                if(r(S+0x352,2)!=target)continue;
                long direct=r(S+0x358,2);
                presetTarget(base,store);
                check("key "+k+" reached through the preset sounds the key's own DAC value",
                    r(S+0x352,2)==target&&r(S+0x358,2)==direct);
            }
        } else {
            // About 150 stores are sampled above; a snapped knob would give
            // a few dozen distinct offsets, a free one nearly one per store.
            check("a free preset is not snapped to the table",offsets.size()>100);
        }
        println("PASS preset voltage "+(quantized?"quantised to the live key table, whole periods kept, key-exact DAC":"added unquantised"));
    }
    void recordedBounds() throws Exception {
        // A relative step is signed and deliberately unclamped: the DAC
        // guard belongs to the playback target, where the factory
        // preparation clamps 0..4095 AFTER re-adding the live transpose.
        // What the store still has to promise is that a step stays inside
        // the window the persistence validator accepts (+-0x2000 around the
        // reference), so a take recorded against the rails saves and
        // reloads - one out-of-range step used to make the whole take
        // unsaveable.
        setup(0,false,1);
        w(S+0x342,1,1); w(S+0x343,1,0); w(S+0x310,2,1023); musicalScan(); octavePad(3);
        key(28); sound(); noteUp(28); key(28); sound();
        check("stacked transpose sounds at the DAC limit",r(S+0x352,2)==4095);
        check("recorded steps stay inside the store's valid window",
            r(0x61e0,1)==2&&Math.abs(step(0))<=0x2000&&Math.abs(step(1))<=0x2000);
        check("both presses at one pad record one step",Math.abs(step(0)-step(1))<=1);
        check("a step stands for the unclamped pitch its press was worth",
            Math.abs(sounds(0)-(r(0x854+2*28,2)+livePad()))<=1);
        int before=writes; command(0);
        if(persistent) {
            long page=call(NEWEST);
            check("the take against the rail saves",writes>before&&r(page+24,1)==2);
            cold();
            check("and survives a power cycle",r(0x61e0,1)==2);
        }
        // The leaf itself, driven at pad positions the panel cannot reach:
        // no clamp is left on the store, and both far ends stay inside the
        // validator's window.
        arp(1);
        w(0x60a0,2,5000); e.writeRegister("R12",0); call(0x8001dce0L);
        check("a far-high pad records unclamped",
            (short)reg("R11")==r(0x854,2)+5000-(short)r(0x62f4,2)
            &&Math.abs((short)reg("R11"))<=0x2000);
        w(0x60a0,2,0xf448); e.writeRegister("R12",0); call(0x8001dce0L);
        check("a far-low pad records signed",
            (short)reg("R11")==r(0x854,2)-3000-(short)r(0x62f4,2)
            &&(short)reg("R11")<0&&Math.abs((short)reg("R11"))<=0x2000);
        println("PASS recorded pitch bounds: rail take records, plays clamped, saves and restores; both far pad ends unclamped and saveable");
    }
    void playbackPressure() throws Exception {
        // Live key pressure must not bend a playing sequence: in PLAY the
        // portamento knob means note-to-note time and the keys no longer
        // choose the notes, so the blend's target parks at zero and any
        // blend already applied slews away.
        setup(1,false,2); w(S+0x342,1,1); w(S+0x343,1,0); w(S+0x310,2,0);
        command(1); octavePad(1); w(S+0x2fc,2,0); w(S+0x306,2,0);
        externalBeat(); sound(); long base=r(S+0x352,2), dac=r(S+0x358,2);
        key(12); w(0x3490+12,1,2);
        for(int k=0;k<29;k++)w(0x3686+2*k,2,k==12?900:110);
        call(0x8001aa10L); w(S+0x306,2,900);
        for(int i=0;i<50;i++)sound();
        check("held-key pressure leaves the playing step alone",
            r(S+0x352,2)==base&&r(S+0x358,2)==dac&&r(0x60e2,2)==0);
        w(0x3490+12,1,0); call(0x8001aa10L);
        for(int i=0;i<50;i++)sound();
        check("release changes nothing either",r(S+0x358,2)==dac);
        // A blend applied before PLAY starts glides out instead of stepping.
        w(0x60e2,2,64);
        sound();
        long applied=r(0x60e2,2);
        check("the transition slews rather than snapping",applied>0&&applied<64);
        for(int i=0;i<50;i++)sound();
        check("and settles at zero",r(0x60e2,2)==0&&r(S+0x358,2)==dac);
        // A glide between steps moves the base every scan; that is the
        // factory walking, not a note handover, so with the knob engaged
        // the re-base must fold none of it into the offset or the
        // conditioner cells - folded steps sent every transition off in
        // the wrong direction first.
        w(S+0x306,2,900);
        for(int i=0;i<10;i++) {
            w(0x60f4,2,(r(S+0x350,2)+0x10000-15)&0xffff);
            sound();
        }
        check("a walking glide base folds nothing while playing",
            r(0x60e2,2)==0&&r(0x60f6,2)==0&&r(0x60f8,2)==0);
        println("PASS playback ignores live pressure: held keys, release, a pre-applied blend slewing out, and no glide re-base");
    }
    void heldPresetEdit() throws Exception {
        // Holding a pad while its knob edits the preset is a voltage
        // gesture: the editor's following flag declines the bare-pad hold,
        // so an edit during recording neither previews nor deletes.  A
        // hold without an edit still acts.
        for(int pad:new int[]{1,2}) {
            setup(4,false,0);
            w(S+0x30a+2*pad,2,200); controlScan();
            press(pad,0); w(S+0x30a+2*pad,2,600);
            for(int i=0;i<70;i++)controlScan();
            check("the editor is following the held pad",
                r(0x614a+pad,1)==1&&r(0x613a+2*pad,2)==600);
            check("a preset edit is not a sequencer command",
                r(0x6158,1)==1&&r(0x61e0,1)==4&&r(0x62fe,1)==0);
            // A partial touch (2 -> 1 -> 2) is the same gesture: ownership
            // holds until the finger truly leaves, so the interrupted hold
            // cannot rearm and fire mid-edit.
            w(0x46f0+pad,1,1); controlScan();
            check("a partial touch keeps the editor's ownership",r(0x614a+pad,1)==1);
            w(0x46f0+pad,1,2);
            for(int i=0;i<70;i++)controlScan();
            check("the interrupted hold still declines",
                r(0x6158,1)==1&&r(0x61e0,1)==4&&r(0x62fe,1)==0);
            release(pad);
            press(pad,0);
            for(int i=0;i<70;i++)controlScan();
            check("an editless hold still previews or deletes",
                pad==1?r(0x62fe,1)==1:r(0x61e0,1)==3);
            release(pad);
        }
        println("PASS preset edits during recording decline the bare-pad hold; editless holds still act");
    }
    // Knob 2 as quantized randomness: the reload the rhythm hook stores is
    // always a whole number of eighths of the beat, the byte at 0x6152 says
    // which eighth the hit fell on, and the deadzone is the square reload.
    static final long GRID=0x8001e440L;
    long gridReload(long beat) throws Exception {
        e.writeRegister("R12",beat); call(GRID); return r(S+0x38e,2);
    }
    // Hits by eighth of the beat over a run, at one knob setting: [0] is the
    // beat, [4] the half, [2] and [6] the quarters, the odd ones eighths.
    // Also records the total eighths stepped and whether the grid held.
    long gridTotal; boolean gridHeld, gridFollows;
    int[] gridHits(int knob,int hits,long beat) throws Exception {
        w(0x60e6,2,knob); w(0x6152,1,0);
        int[] at=new int[8]; int pos=0; gridTotal=0; gridHeld=true; gridFollows=true;
        for(int i=0;i<hits;i++) {
            long cd=gridReload(beat); int p=(int)r(0x6152,1);
            if(cd%(beat/8)!=0||cd<beat/8||cd>4*beat) { gridHeld=false; break; }
            int n=(int)(cd/(beat/8)); gridTotal+=n;
            if(p!=((pos+n)&7)) { gridFollows=false; break; }
            pos=p; at[p]++;
        }
        return at;
    }
    void quantizedRhythm() throws Exception {
        fresh();
        check("the rhythm hook's pool word names the quantized cave",r(0x80019d40L,4)==GRID);
        long beat=400;
        w(0x60e6,2,0); w(0x6152,1,0);
        check("below the deadzone the reload is the beat itself",gridReload(beat)==beat&&r(0x6152,1)==0);
        w(0x6152,1,4);
        check("a hit standing on the half steps the other half back onto the beat",
            gridReload(beat)==beat/2&&r(0x6152,1)==0);
        w(0x6152,1,7);
        check("a hit on the last eighth steps one eighth back onto the beat",
            gridReload(beat)==beat/8&&r(0x6152,1)==0);
        w(0x6152,1,0x0b);
        check("only the low three bits of the position are read",
            gridReload(beat)==beat*5/8&&r(0x6152,1)==0);
        w(0x60e6,2,0x2f); w(0x6152,1,0);
        check("the deadzone reaches the randomiser's own 0x30",gridReload(beat)==beat);
        // Full travel: the beat one hit in two, the half one in eight, each
        // quarter and each eighth one in sixteen, and the mean spacing still
        // one beat.
        int[] at=gridHits(1023,2000,beat);
        check("full travel: every reload is one to thirty-two eighths",gridHeld);
        check("full travel: the position follows the eighths stepped",gridFollows);
        check("full travel: the beat keeps half the hits: "+at[0],at[0]>900&&at[0]<1100);
        check("full travel: the half takes one in eight: "+at[4],at[4]>190&&at[4]<310);
        int quarters=at[2]+at[6], eighths=at[1]+at[3]+at[5]+at[7];
        check("full travel: the quarters take one in eight between them: "+quarters,quarters>190&&quarters<310);
        check("full travel: the eighths take one in four between them: "+eighths,eighths>400&&eighths<600);
        check("full travel: the mean spacing stays one beat: "+gridTotal,gridTotal>14500&&gridTotal<17500);
        // Halfway: three hits in four on the beat, most of the rest on the
        // half, the quarters and eighths only just arriving.
        at=gridHits(512,2000,beat);
        quarters=at[2]+at[6]; eighths=at[1]+at[3]+at[5]+at[7];
        check("halfway: the grid holds",gridHeld&&gridFollows);
        check("halfway: three hits in four land on the beat: "+at[0],at[0]>1350&&at[0]<1650);
        check("halfway: the half takes most of the rest: "+at[4],at[4]>280&&at[4]<470);
        check("halfway: quarters and eighths are rare but present: "+quarters+"/"+eighths,
            quarters>20&&quarters<120&&eighths>20&&eighths<120);
        // An eighth of the travel: a few hits on the half, none finer.
        at=gridHits(128,2000,beat);
        quarters=at[2]+at[6]; eighths=at[1]+at[3]+at[5]+at[7];
        check("low: the grid holds",gridHeld&&gridFollows);
        check("low: the half takes about one hit in sixteen: "+at[4],at[4]>70&&at[4]<180);
        check("low: a stray quarter or two and no eighths yet: "+quarters+"/"+eighths,quarters<20&&eighths==0);
        // The randomiser's own limits, in scans.
        w(0x60e6,2,0); w(0x6152,1,7);
        check("the reload never drops below eight scans",gridReload(4)==8);
        w(0x6152,1,0);
        check("the reload never exceeds 0xfff scans",gridReload(5000)==0xfff);
        println("PASS quantized rhythm: eighth grid, position, shares at three settings, deadzone and clamps");
    }
    void retainedStartup() throws Exception {
        // SRAM survives a DFU: another image's pickup stamps must not
        // freeze the knobs of a build without sequencer or persistence,
        // whose first-use fill used to stop short of the stamp cells.
        fresh();
        w(0x602a,2,0);
        for(int k=0;k<4;k++) { w(0x62e8+2*k,2,601); w(S+0x30a+2*k,2,600); }
        call(0x8001ab60L);
        check("first use clears every retained pickup stamp",
            r(0x62e8,2)==0&&r(0x62ea,2)==0&&r(0x62ec,2)==0&&r(0x62ee,2)==0);
        controlScan(); call(0x8000307cL); call(0x80019d44L);
        check("the knobs follow their physical positions from the first scan",
            r(0x60f2,1)==75&&r(0x60e6,2)==600&&r(0x60ea,2)==600);
        println("PASS retained-SRAM startup: pickup stamps cleared, no knob freeze");
    }
    @Override public void run() throws Exception {
        String[] args=getScriptArgs();
        transpose=args.length>0&&args[0].equals("trn");
        orders=args.length>1&&args[1].equals("orders");
        zones=args.length>3?Integer.parseInt(args[3]):9;
        lean=args.length>4&&args[4].equals("lean");
        quantized=args.length>5&&args[5].equals("quantized");
        gridRhythm=args.length>6&&args[6].equals("quantized");
        seq=!lean; clock=!lean; persistent=args.length>2&&args[2].equals("persist");
        List<String> failures=new ArrayList<>();
        try {
            try { presetOwnership(); } catch(Exception ex) { failures.add(ex.toString()); println(ex.toString()); }
            try { quickTapGate(); } catch(Exception ex) { failures.add(ex.toString()); println(ex.toString()); }
            try { presetQuantize(); } catch(Exception ex) { failures.add(ex.toString()); println(ex.toString()); }
            if(transpose)try { transposeOutput(); } catch(Exception ex) { failures.add(ex.toString()); println(ex.toString()); }
            if(orders)try { noteOrders(); } catch(Exception ex) { failures.add(ex.toString()); println(ex.toString()); }
            if(orders)try { releasedOrders(); } catch(Exception ex) { failures.add(ex.toString()); println(ex.toString()); }
            if(orders)try { latchedOrders(); } catch(Exception ex) { failures.add(ex.toString()); println(ex.toString()); }
            if(!lean)try { latchExitHold(); } catch(Exception ex) { failures.add(ex.toString()); println(ex.toString()); }
            if(seq)try { stripCarry(); } catch(Exception ex) { failures.add(ex.toString()); println(ex.toString()); }
            if(seq&&!transpose)try { latchRecording(); } catch(Exception ex) { failures.add(ex.toString()); println(ex.toString()); }
            if(seq&&!transpose)try { recordedOctaves(); } catch(Exception ex) { failures.add(ex.toString()); println(ex.toString()); }
            if(seq&&!transpose)try { capacityAudition(); } catch(Exception ex) { failures.add(ex.toString()); println(ex.toString()); }
            if(seq&&!transpose)try { pressureOwnership(); } catch(Exception ex) { failures.add(ex.toString()); println(ex.toString()); }
            if(!lean)try { staleAnchor(); } catch(Exception ex) { failures.add(ex.toString()); println(ex.toString()); }
            if(seq&&!transpose)try { previewBoundaries(); } catch(Exception ex) { failures.add(ex.toString()); println(ex.toString()); }
            if(seq&&transpose)try { recordedBounds(); } catch(Exception ex) { failures.add(ex.toString()); println(ex.toString()); }
            if(seq)try { playbackPressure(); } catch(Exception ex) { failures.add(ex.toString()); println(ex.toString()); }
            if(seq)try { heldPresetEdit(); } catch(Exception ex) { failures.add(ex.toString()); println(ex.toString()); }
            if(lean)try { retainedStartup(); } catch(Exception ex) { failures.add(ex.toString()); println(ex.toString()); }
            if(gridRhythm)try { quantizedRhythm(); } catch(Exception ex) { failures.add(ex.toString()); println(ex.toString()); }
            if(!failures.isEmpty())throw new Exception("CONTROL REGRESSION FAIL: "+failures);
            println("CONTROL REGRESSION PASS: "+checks+" assertions; transpose="+transpose+", orders="+orders+", persist="+persistent+", lean="+lean+", quantized="+quantized+", gridRhythm="+gridRhythm);
        } finally { if(e!=null)e.dispose(); }
    }
}
