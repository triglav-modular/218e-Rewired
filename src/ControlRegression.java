// Knob ownership and note-order regressions in emitted firmware.
// The entire applier/preset chain and real clock/selector paths execute;
// only the peripheral model inherited from SequenceEditRegression is used.
//@category Buchla218.Tests
import java.util.*;

public class ControlRegression extends SequenceEditRegression {
    static final long APPLIER=0x8001a2e8L;
    boolean transpose, orders, lean, quantized, gridRhythm;
    int zones=9;
    // Instructions executed, for the scan-budget figures below: counted in
    // step(), reset by whoever measures.
    long steps;
    long rawBase=Long.MIN_VALUE, rawTarget=Long.MIN_VALUE;
    // Every note-on sent on MIDI port 1 (0x80007de8, R12 the note), in
    // order.  The arp step sends one for each note it sounds, a sequencer
    // step and a latched note alike.
    final List<Integer> midiOn=new ArrayList<>();
    // And the velocity each of them carried (R11), in the same order.
    final List<Integer> midiVel=new ArrayList<>();
    // midi_arp_note, the arp's word for a key's MIDI note since audit
    // 038711a (F07, F08).
    static final long ARP_NOTE=0x80020580L;

    void controlScan() throws Exception { call(APPLIER); }
    @Override void setup(int length,boolean internal,int position) throws Exception {
        if(seq) { super.setup(length,internal,position); return; }
        fresh(); arp(position); now=0; clockExercise=true;
        w(S+0x308,2,0); w(S+0x2f2,2,0); w(S+0x2da,1,0); w(0x2ee6,2,1023);
        resetTrace();
    }
    @Override void command(int pad) throws Exception { if(seq)super.command(pad); }
    @Override void step() throws Exception {
        steps++;
        // The factory PRNG answered with a chosen value while forcePrng is
        // set, and every call counted either way (randomOctaveFloor).
        if(pc()==0x80013e04L) { prngCalls++; if(forcePrng) { e.writeRegister("R12",prngValue); ret(); return; } }
        if(pc()==0x80019980L) {
            remapIn=(int)reg("R12");    // what the scan hands the remap
            if(skipRemap) { ret(); return; }
        }
        if(pc()==0x8001c0e0L) fastIn=(int)reg("R12");    // and the clock's fast stage, clock_remap_bare
        if(pc()==0x80019d1eL) blendOut=(int)reg("R0");   // the offset pressure_blend publishes
        // The MIDI note-on's two reads of the key table by the note itself,
        // each just after it lands in R8: a base and a target the routine
        // overwrites before it returns, so they are caught here.
        if(pc()==0x80006478L) rawBase=(int)reg("R8");
        if(pc()==0x80006488L) rawTarget=(int)reg("R8");
        if(pc()==0x80007de8L) { midiOn.add((int)(reg("R12")&0xff)); midiVel.add((int)(reg("R11")&0xff)); }
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
    // Knob 4's zones follow number cell 10, not the period the image was
    // built with: three that mean no transpose, then one per period in six
    // octaves, thirteen at most.  Nine at the octave, six for a tritave, one
    // step at the top of the cell's range, and the cap below it.  Written
    // out here from the rule rather than read off the image, and driven
    // through the real control scan with the knob unheld.  (Audit
    // 2026-09-24, finding 1: the count was baked in, so a record with
    // another period could not be sent to this image at all.)
    void knob4Zones() throws Exception {
        setup(0,false,0); command(2);
        for(int p:new int[]{484,767,2000,300,100}) {
            int expect=3+Math.min(13,2904/p);
            w(0x6814,2,p);
            for(int raw:new int[]{0,1023,512,1000,300}) {
                w(S+0x310,2,raw); controlScan();
                check("knob 4 at "+raw+" with the period cell at "+p+": zone "+setting()+" of "+expect,
                    setting()==raw*expect/1024);
            }
        }
        w(0x6814,2,PERIOD); w(S+0x310,2,1023); controlScan();
        check("back at the octave, the top of the knob is zone 8",setting()==8);
        println("PASS knob 4's zones follow the period cell: 9, 6, 4, 12 and 16 at the cap");
    }
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
    // What the factory target preparation does to a finished pitch: held
    // at 0xfff, and at -0x78, the pitch table's entry 0 (0x800038cc).
    long dac(long v) { return v<-0x78?-0x78:v>4095?4095:v; }
    void releasedOrders() throws Exception {
        for(int position:lean?new int[]{2}:new int[]{1,2})for(int zone:new int[]{3,4}) {
            setup(0,false,position); command(1); command(1);
            for(int k:new int[]{4,14,9})key(k);
            e.writeRegister("R12",14); call(position==1?0x80018d00L:r(0x80005b18L,4));
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
    // Through the pool word, as the factory releases a key: the latch's
    // wrapper or the factory's note-off, by the latch's live byte.
    void noteUp(int key) throws Exception { e.writeRegister("R12",key); call(r(0x80005b18L,4)); }
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
        // The bare hold leaves the octave where it was in this fixture, so
        // the pad is put two periods under the take before the first step
        // is chosen, and published, as the pin reads it at step selection.
        octavePad(1); sound();
        midiOn.clear(); externalBeat(); sound();
        check("a preview sounds the take as recorded, not where the pad is",
            (short)r(S+0x352,2)==dac((short)r(0x61e2,2)+(short)r(0x62f4,2)));
        // Its MIDI note is pinned the same way (audit 038711a, F08): every
        // step was played at pad 3, so the preview names key 0 at pad 3
        // whatever pad stands now.
        long pinned=lastNote("the preview's first step");
        check("a preview's MIDI note is the one recorded, not the live pad's: "+pinned+" with pad "+r(S+0x2ef,1)+" down",
            r(S+0x2ef,1)==1&&pinned==jackNote(0,notePeriods()+2));
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
    // Aim the arp at a latched key the way a real step leaves it: the
    // last arp key AND the published base (state+0x350, the key's table
    // pitch) that the transpose shim measures the live transpose from.
    void aim(int key) throws Exception { w(S+0x34d,1,key); w(S+0x350,2,r(0x854+2*key,2)); }
    void latchTransposeState() throws Exception {
        // The latch has two states, toggled by pads 2 and 3 held together
        // for latch_state_hold_scans with pad 4 up.  The difference is
        // whether the octave pads act BEFORE a note is entered or AFTER.
        // Before, the default: the pads choose where each new note goes
        // in, and held notes stay put.  After: the pads move everything
        // already held, a new note still enters at the pad's pitch, and a
        // press on a pitch that is sounding still releases it.  The toggle
        // restores the octave the pads chose on the way in, lights both
        // pads for a moment, saves once both are up, and never reaches
        // the sequencer's preview or backspace.
        setup(0,false,1); command(0); latchFixture();   // stopped, latch position
        octavePad(1); key(0); aim(0); sound();
        long entered=r(S+0x352,2);
        octavePad(3); sound();
        check("hold is the default state, and a latched note keeps its transpose in it",
            r(0x62e2,1)==0&&Math.abs(r(S+0x352,2)-entered)<=1);
        // Octave 2 is in force.  The pads choose 2 then 3 on the way in -
        // the factory selects on the press edge - and the octave from
        // before comes back, with the transpose it stands for as the
        // set's reference: the toggle itself moves nothing.
        octavePad(1); sound(); long preTranspose=livePad(); controlScan();
        check("the octave and its transpose are shadowed while pads 2-4 are up",
            r(0x615d,1)==1&&Math.abs((short)r(0x6584,2)-preTranspose)<=1);
        octavePad(2); w(0x46f1,1,2); w(0x46f2,1,2);
        int before=writes;
        for(int i=0;i<199;i++)controlScan();
        check("199 scans of pads 2 and 3 toggle nothing yet",
            r(0x62e2,1)==0&&r(0x6582,2)==199&&r(0x615d,1)==1&&r(S+0x2ef,1)==2);
        controlScan();
        check("the 200th scan toggles into the transpose state",r(0x62e2,1)==1);
        check("the reference is the transpose from before the gesture, not the gesture's own",
            Math.abs((short)r(0x6580,2)-preTranspose)<=1&&Math.abs((short)r(0x609e,2)-preTranspose)<=1);
        check("the octave from before the gesture is restored",r(S+0x2ef,1)==1);
        check("the acknowledgment is counting",r(0x62e3,1)==0x2f);
        check("nothing saves while the pads are held",writes==before);
        sound();
        check("the toggle itself moves nothing",Math.abs(r(S+0x352,2)-entered)<=1);
        for(int i=0;i<50;i++)controlScan();
        check("the hold saturates and fires once",r(0x6582,2)==201&&r(0x62e2,1)==1);
        check("the acknowledgment ends on the restored octave",r(0x62e3,1)==0&&r(S+0x2ef,1)==1);
        w(0x46f1,1,0); w(0x46f2,1,0); controlScan();
        check("release clears the count",r(0x6582,2)==0);
        check("the state saves once both pads are up",persistent?writes>before:writes==before);
        if(persistent)check("the record carries the state",r(call(NEWEST)+25,1)==1);
        // TRANSPOSE: the set follows the pad.
        octavePad(3); sound();
        long ref=(short)r(0x6580,2);
        check("transpose state: the latched note follows the pad",
            Math.abs(r(S+0x352,2)-(entered+(livePad()-ref)))<=1);
        key(6); aim(6); sound();
        check("a note entered in the transpose state sounds at the pad's pitch",
            Math.abs(r(S+0x352,2)-(r(0x854+12,2)+livePad()))<=1);
        octavePad(1); aim(0); sound();
        check("the set moves together: the first note is back where it was entered",
            Math.abs(r(S+0x352,2)-entered)<=1);
        aim(6); sound();
        check("and the new note moved with it",
            Math.abs(r(S+0x352,2)-(r(0x854+12,2)+livePad()))<=1);
        // And through a real arp step, which selects the key and its base
        // itself: whichever held note it lands on sounds its stamped pitch
        // plus the set's shift.
        w(S+0x2fc,2,0); externalBeat(); sound();
        int stepped=(int)r(S+0x34d,1);
        check("a real arp step sounds the stamped pitch plus the set's shift: key "+stepped,
            (stepped==0||stepped==6)&&Math.abs(r(S+0x352,2)
                -(r(0x854+2*stepped,2)+(short)r(0x60a2+2*stepped,2)+(livePad()-ref)))<=1);
        octavePad(3); sound(); key(0);
        check("a press on a pitch the pad moved a note onto releases that note",r(S+0x21b,1)==0);
        // Back to BEFORE: nothing jumps, and the pad no longer reaches the set.
        aim(6); sound(); long parked=r(S+0x352,2);
        controlScan(); w(0x46f1,1,2); w(0x46f2,1,2);
        for(int i=0;i<200;i++)controlScan();
        check("the second hold toggles back to the hold state, octave kept",r(0x62e2,1)==0&&r(S+0x2ef,1)==3);
        w(0x46f1,1,0); w(0x46f2,1,0); controlScan();
        aim(6); sound();
        check("toggling back bakes the shift: the note keeps sounding where the pad had put it",
            Math.abs(r(S+0x352,2)-parked)<=1);
        octavePad(1); sound();
        check("and in the hold state the pad no longer reaches it",Math.abs(r(S+0x352,2)-parked)<=1);
        if(persistent) {
            controlScan(); w(0x46f1,1,2); w(0x46f2,1,2);
            for(int i=0;i<200;i++)controlScan();
            w(0x46f1,1,0); w(0x46f2,1,0); controlScan();
            check("a third toggle saves again",r(0x62e2,1)==1&&r(call(NEWEST)+25,1)==1);
            cold();
            check("the state survives a power cycle",r(0x62e2,1)==1&&r(0x6649,1)==1);
        }
        // In WRITE a bare pad 2 or 3 held a third of a second previews or
        // backspaces; two pads held for the toggle must do neither, and
        // nor must a finger that lingers after it.
        setup(2,false,1); latchFixture();
        w(0x46f1,1,2); w(0x46f2,1,2);
        for(int i=0;i<200;i++)controlScan();
        check("the toggle fires in WRITE",r(0x62e2,1)==1);
        check("without a preview or a backspace",r(0x62fe,1)==0&&r(0x61e0,1)==2&&r(0x6158,1)==1);
        w(0x46f2,1,0);
        for(int i=0;i<70;i++)controlScan();
        check("a finger lingering after the toggle previews nothing",
            r(0x62fe,1)==0&&r(0x61e0,1)==2&&r(0x6582,2)>=200&&r(0x625d,1)==0);
        w(0x46f1,1,0); controlScan();
        check("both up ends the gesture",r(0x6582,2)==0);
        press(2,0); for(int i=0;i<59;i++)controlScan();
        check("a bare hold on its own still backspaces",r(0x61e0,1)==1);
        release(2);
        // Pad 4 down makes pads 2 and 3 transport, not this gesture.
        w(0x46f3,1,2); w(0x46f1,1,2); w(0x46f2,1,2);
        for(int i=0;i<210;i++)controlScan();
        check("pads 2 and 3 under a pad-4 hold count nothing",r(0x6582,2)==0&&r(0x62e2,1)==1);
        w(0x46f3,1,0); w(0x46f1,1,0); w(0x46f2,1,0); controlScan();
        // In edit mode the factory gives the same chord its own meaning and
        // kept its latch out of it; so does this.  And a pad whose knob is
        // setting a preset voltage is editing, not gesturing.
        w(S+0x39,1,1); w(0x46f1,1,2); w(0x46f2,1,2);
        for(int i=0;i<210;i++)controlScan();
        check("the chord in edit mode counts nothing",r(0x6582,2)==0&&r(0x62e2,1)==1);
        w(S+0x39,1,0); w(0x46f1,1,0); w(0x46f2,1,0); controlScan();
        w(0x614b,1,1); w(0x46f1,1,2); w(0x46f2,1,2);
        for(int i=0;i<210;i++)controlScan();
        check("a pad editing its preset declines the gesture",r(0x6582,2)==0&&r(0x62e2,1)==1);
        w(0x614b,1,0); w(0x46f1,1,0); w(0x46f2,1,0); controlScan();
        // The sequencer's pad-4 hold restores the octave the same way: the
        // press chose octave 4 on its way in, and arming puts back what
        // stood before it.
        setup(0,false,1); command(0); octavePad(1); controlScan();
        octavePad(3); w(0x46f3,1,2);
        for(int i=0;i<199;i++)controlScan();
        check("pad 4 chose its octave on the way in",r(S+0x2ef,1)==3&&r(0x6156,1)==0);
        controlScan();
        check("arming restores the octave from before pad 4 went down",
            r(0x6156,1)==1&&r(0x6159,1)==1&&r(S+0x2ef,1)==1);
        w(0x46f0,1,2); controlScan(); w(0x46f0,1,0); controlScan();
        check("a chord press keeps it there",r(S+0x2ef,1)==1&&r(0x6158,1)==1);
        w(0x46f3,1,0); controlScan();
        check("and the release leaves it there",r(S+0x2ef,1)==1);
        println("PASS latch transpose state: pads act before or after entry, toggle by pads 2 & 3, octave restored, saved on release, no preview or backspace; pad-4 hold restores the octave");
    }
    // MIDI out names the note the pitch CV plays (audit 038711a: F07, F08,
    // F13).  A period is the live slot's keys per period on MIDI, read out of
    // the settings mirror as the firmware reads it, and number cell 10 in
    // pitch.
    int keysPerPeriod() { int slot=(int)r(0x6090,1); if(slot>2) slot=0; return (int)r(0x69a0+2*slot,2); }
    int periodUnits() { return (int)r(0x6814,2); }
    // The whole periods the factory's key-to-note routine (0x800057a8) adds
    // to key + 36, off its listing: trn names zone z at 12z, which is three
    // periods under key + 36, and the add-to-pitch switch on octaves adds 12
    // per pad, from pad 1 off trn and from pad 0 in it.  midiPeriod() checks
    // this against the routine itself.
    int notePeriods() {
        int m=0;
        if(r(S+0x6a,1)!=0) m=(int)r(S+0x6b,1)-3;
        if(r(S+0x342,1)!=0) m+=(int)r(S+0x2ef,1)-(r(S+0x6a,1)!=0?0:1);
        return m;
    }
    // A MIDI note is named in one sum and held to 0..127 once (the
    // clamp-chain scan, 2026-09-28): key + 36, m periods of this image's map
    // in keys, and the degrees on top.  These oracles held the key's note
    // first and added the jack after, the defect the scan found in the
    // firmware, so they agreed with it.
    long clampNote(long v) { return Math.max(0,Math.min(127,v)); }
    long rawNote(int key,int m) { return key+36+(long)keysPerPeriod()*m; }
    // A key's note at m periods of this image's map, before the jack.
    long noteAt(int key,int m) { return clampNote(rawNote(key,m)); }
    // The jack's degrees as midi_transpose adds them: only once the
    // transposer has run.
    int jackN() { long v=r(0x60fa,2); return (v>>12)==0xa?(int)(v&0xff):0; }
    // The same key with the jack's N and a step's term, in the one sum.
    long jackNote(int key,int m) { return jackNote(key,m,0); }
    long jackNote(int key,int m,long term) { return clampNote(rawNote(key,m)+jackN()+term); }
    // Whole periods in a pitch offset, halves away from zero.
    long periodsIn(long units) {
        long p=periodUnits();
        return units<0?-((-units+p/2)/p):(units+p/2)/p;
    }
    int lastNote(String what) throws Exception {
        check(what+": a note-on was sent",!midiOn.isEmpty());
        return midiOn.get(midiOn.size()-1);
    }
    // F13: a period of the octave pads and of the trn zones is the live
    // slot's keys per period on MIDI, as a period of the jack already was.
    // With twelve keys that is the factory's routine note for note, which
    // is what the twelve-key variants pin: every key at every pad, both
    // switch positions and the trn zones, through midi_transpose's three
    // entries with the transposer's word down, so each answers the key's
    // own note.  The kbm variant carries 24TET.scl with 24TET-full.kbm, and
    // there a pad and a zone step 24 notes.
    // The pad octave mode: with pad 4 active, hold the top key for
    // latch_state_hold_scans and tap pad 4, and the octave pads stand one
    // period higher from then on; with pad 1 active, hold the bottom key and
    // tap pad 1 to put them back.  The hold counts only on its own pad, the
    // tap is a press released before the chord's hold time with no knob
    // moved, it re-selects the pad standing, the tapped pad flashes, the
    // CV, the term and MIDI move a period together, and the mode rides in
    // the record's byte 0x1b.  The press goes the way the factory's handler
    // sends it, through the pool word at 0x8000a810.
    void pressPad(int pad) throws Exception { w(0x46f0+pad,1,2); e.writeRegister("R12",pad); call(r(0x8000a810L,4)); }
    void liftPad(int pad) throws Exception { w(0x46f0+pad,1,0); }
    void holdKey(int key,int scans) throws Exception { w(S+0x239+key,1,1); for(int i=0;i<scans;i++) controlScan(); }
    // A key's MIDI note through midi_transpose's three entries, which agree.
    long midiNote(int key) throws Exception {
        long note=-1;
        for(long entry:new long[]{0x8001e740L,0x8001e744L,0x8001e748L}) {
            e.writeRegister("R12",key); long got=call(entry);
            check("the three MIDI entries agree on key "+key+": "+got+" after "+note,note<0||got==note);
            note=got;
        }
        return note;
    }
    void padOctaveMode() throws Exception {
        setup(0,false,0); command(0); latchFixture();   // arp off, ADD TO PITCH on octaves
        int period=periodUnits(), chord=bootedHalf(0x6810);
        w(0x60fa,2,0);                                  // the transposer's word down: a key's own note
        octavePad(1); aim(0); sound();
        long base=r(S+0x352,2), term=livePad();
        check("the mode starts off, and the note is the factory's",r(0x6586,1)==0&&midiNote(0)==noteAt(0,notePeriods()));
        // The hold counts only with pad 4 active: with pad 2 active the top
        // key counts nothing, and a tap of pad 4 is the selection it always was.
        holdKey(28,220);
        check("with pad 2 active the top key's hold counts nothing",r(0x658a,2)==0);
        pressPad(3); controlScan();
        check("and a tap of pad 4 under it selects pad 4, as any tap does",r(S+0x2ef,1)==3&&r(0x658f,1)==0);
        liftPad(3); controlScan();
        check("the count runs once pad 4 is active, and nothing switched",r(0x658a,2)==2&&r(0x6586,1)==0);
        for(int i=0;i<197;i++) controlScan();
        check("199 scans arm nothing yet",r(0x658a,2)==199&&r(0x6586,1)==0);
        pressPad(3); controlScan(); liftPad(3); controlScan();
        check("a tap before the hold completes begins nothing",r(0x6586,1)==0&&r(0x658f,1)==0&&r(S+0x2ef,1)==3);
        for(int i=0;i<20;i++) controlScan();
        check("the hold saturates at the threshold",r(0x658a,2)==200);
        pressPad(2); controlScan(); liftPad(2); controlScan();
        check("another pad resets the count",r(0x658a,2)==0&&r(S+0x2ef,1)==2&&r(0x6586,1)==0);
        pressPad(3); controlScan(); liftPad(3); controlScan();
        for(int i=0;i<200;i++) controlScan();
        check("back on pad 4 the count runs again",r(0x658a,2)==200);
        // A hold of pad 4 as long as the chord's is not a tap, and nor is a
        // press under which the pad's knob moved.
        pressPad(3); controlScan();
        check("the press begins a tap",r(0x658f,1)==4&&r(0x6586,1)==0);
        for(int i=0;i<chord;i++) controlScan();
        check("held to the chord's time the tap is forgotten",r(0x658f,1)==0&&r(0x6586,1)==0);
        liftPad(3); controlScan();
        check("and its release switches nothing",r(0x6586,1)==0);
        pressPad(3); controlScan(); w(0x614a+3,1,1); controlScan();
        check("a knob moved under the press forgets the tap",r(0x658f,1)==0);
        liftPad(3); controlScan(); w(0x614a+3,1,0); controlScan();
        check("and its release switches nothing",r(0x6586,1)==0);
        int before=writes;
        aim(0); sound(); long padFour=r(S+0x352,2);
        pressPad(3); controlScan();
        check("the tap begins on the press and switches nothing yet",r(0x658f,1)==4&&r(0x6586,1)==0&&r(S+0x2ef,1)==3);
        liftPad(3); controlScan();
        check("the release switches the pads one period up, pad 4 standing",r(0x6586,1)==1&&r(S+0x2ef,1)==3&&r(0x658f,1)==0);
        check("the acknowledgment counts on pad 4",r(0x658e,1)==0x2f&&r(0x6587,1)==3);
        sound();
        check("the same key under pad 4 sounds one period higher",Math.abs(r(S+0x352,2)-(padFour+period))<=1);
        check("the transpose term carries it: three periods over pad 2",Math.abs(livePad()-(term+3*period))<=1);
        check("MIDI names the note a period up with the CV",midiNote(0)==noteAt(0,notePeriods()+1));
        check("nothing saves while the gesture key is held",writes==before);
        for(int i=0;i<0x30;i++) controlScan();
        check("the acknowledgment ends on pad 4",r(0x658e,1)==0&&r(S+0x2ef,1)==3);
        pressPad(3); controlScan(); liftPad(3); controlScan();
        check("a second tap keeps the mode",r(0x6586,1)==1&&r(S+0x2ef,1)==3&&r(0x658e,1)==0x2f);
        for(int i=0;i<0x30;i++) controlScan();
        w(S+0x239+28,1,0); controlScan();
        check("lifting the key clears the hold",r(0x658a,2)==0);
        check("and saves the mode",writes>before&&r(call(NEWEST)+27,1)==1);
        // Pad 1 under the top key's hold is a selection, and the bottom key
        // counts nothing with pad 4 active.
        holdKey(28,200); pressPad(0); controlScan(); liftPad(0); controlScan(); w(S+0x239+28,1,0); controlScan();
        check("the top key's hold with pad 4 does not take pad 1",r(S+0x2ef,1)==0&&r(0x6586,1)==1&&r(0x658f,1)==0);
        octavePad(3); holdKey(0,220);
        check("the bottom key counts nothing with pad 4 active",r(0x6588,2)==0);
        w(S+0x239,1,0); controlScan();
        // In edit mode the keys are settings: neither hold counts there.
        octavePad(0); holdKey(0,100);
        check("with pad 1 active the bottom key counts",r(0x6588,2)==100);
        w(S+0x39,1,1); controlScan();
        check("edit mode clears a hold in progress",r(0x6588,2)==0);
        holdKey(0,200); pressPad(0); controlScan(); liftPad(0); controlScan();
        check("and the gesture does nothing there",r(0x6586,1)==1&&r(0x6588,2)==0&&r(S+0x2ef,1)==0&&r(0x658f,1)==0);
        w(S+0x39,1,0); w(S+0x239,1,0); controlScan();
        // Pad 1 under a held pad 4 is the chord's RECORD, not a tap.
        octavePad(0); holdKey(0,200); w(0x46f3,1,2); pressPad(0); controlScan();
        check("pad 1 under a held pad 4 begins no tap",r(0x658f,1)==0);
        liftPad(0); w(0x46f3,1,0); controlScan(); controlScan();
        check("and nothing switched",r(0x6586,1)==1&&r(S+0x2ef,1)==0);
        // Back: pad 1 active, the bottom key held, pad 1 tapped.  Key 14
        // for the pitch, which pad 1 without the mode puts a period under
        // key 0's: well above the adder's floor.
        aim(14); sound(); long upAtOne=r(S+0x352,2);
        check("in the mode pad 1 stands where its key's table entry does",Math.abs(upAtOne-r(0x854+28,2))<=1);
        w(S+0x239,1,0); controlScan(); holdKey(0,200);
        check("the bottom key's hold counts with pad 1 active, apart from the top key's",r(0x6588,2)==200&&r(0x658a,2)==0);
        pressPad(0); controlScan(); liftPad(0); controlScan();
        check("the tap puts the pads back, pad 1 standing",r(0x6586,1)==0&&r(S+0x2ef,1)==0&&r(0x6587,1)==0&&r(0x658e,1)==0x2f);
        sound();
        check("the key sounds a period under where the mode had it",Math.abs(r(S+0x352,2)-(upAtOne-period))<=1);
        w(S+0x239,1,0);
        for(int i=0;i<0x30;i++) controlScan();
        check("the record carries the mode back",r(call(NEWEST)+27,1)==0);
        // The mode survives a power cycle; the counts, the flash and the tap do not.
        octavePad(3); holdKey(28,200); pressPad(3); controlScan(); liftPad(3); controlScan(); w(S+0x239+28,1,0); controlScan();
        w(0x6588,2,0x1234); w(0x658a,2,0x5678); w(0x658e,1,0x20); w(0x658f,1,4); w(0x6590,2,7);
        cold();
        check("the mode survives a power cycle; the counts, the flash and the tap do not",
            r(0x6586,1)==1&&r(0x664b,1)==1&&r(0x6588,2)==0&&r(0x658a,2)==0&&r(0x658e,1)==0&&r(0x658f,1)==0&&r(0x6590,2)==0);
        // With the arp off a held key's MIDI note moves with its CV: the
        // switch runs the factory's retune, as a pad change does, and a
        // second tap in the same hold sends nothing more.
        setup(0,false,0); command(0); latchFixture(); w(0x60fa,2,0); octavePad(3);
        w(0x2efa,1,1);                                  // the 208's bus present: the factory's retune sends only then
        midiOn.clear(); touchOn(28); key(28); aim(28); sound();
        check("a live key names its note on MIDI before the switch",
            !midiOn.isEmpty()&&r(S+0x2e1,1)==noteAt(28,notePeriods())&&midiOn.get(midiOn.size()-1)==noteAt(28,notePeriods()));
        int sent=midiOn.size(); long cvBefore=r(S+0x352,2);
        holdKey(28,200); pressPad(3); controlScan(); liftPad(3); controlScan();
        check("the switch renames the held key's MIDI note a period up, with its CV: mode "+r(0x6586,1)+", sent "+(midiOn.size()-sent)
            +(midiOn.size()>sent?" last "+midiOn.get(midiOn.size()-1):"")+", live "+r(S+0x2e1,1)+", want "+noteAt(28,notePeriods()+1),
            r(0x6586,1)==1&&midiOn.size()==sent+1&&midiOn.get(sent)==noteAt(28,notePeriods()+1)&&r(S+0x2e1,1)==noteAt(28,notePeriods()+1));
        sound();
        check("and the CV moved the period",Math.abs(r(S+0x352,2)-(cvBefore+period))<=1);
        for(int i=0;i<0x30;i++) controlScan();
        pressPad(3); controlScan(); liftPad(3); controlScan();
        check("a second tap in the same hold sends nothing more",midiOn.size()==sent+1&&r(0x6586,1)==1);
        touchOff(28); w(S+0x239+28,1,0); controlScan();
        // Off octaves the pads are not octave pads: the gesture begins
        // nothing, and the mode changes nothing.
        w(S+0x342,1,0); w(S+0x343,1,0); controlScan();
        holdKey(28,200); pressPad(3); controlScan();
        check("off octaves the gesture begins no tap",r(0x658f,1)==0);
        liftPad(3); controlScan(); w(S+0x239+28,1,0); controlScan();
        check("and switches nothing",r(0x6586,1)==1);
        aim(0); sound();
        long off=r(S+0x352,2);
        w(0x6586,1,0); controlScan(); aim(0); sound();
        check("with ADD TO PITCH off octaves the mode changes nothing",Math.abs(r(S+0x352,2)-off)<=1);
        // In the latch's hold state the mode acts as a pad does: a note
        // already entered stays put.
        if(!lean) {
            setup(0,false,1); command(0); latchFixture();
            octavePad(3); key(0); aim(0); sound(); long entered=r(S+0x352,2);
            holdKey(28,200); pressPad(3); controlScan(); liftPad(3); controlScan(); w(S+0x239+28,1,0); controlScan();
            sound();
            check("in HOLD a latched note keeps its pitch through the switch, as through a pad",
                r(0x6586,1)==1&&Math.abs(r(S+0x352,2)-entered)<=1);
        }
        println("PASS pad octave mode: armed by the top key's hold on pad 4, taken by a tap of pad 4 on its release on the CV, the term and MIDI, not by a hold, back by the bottom key on pad 1, saved once the key lifts and restored");
    }
    void midiPeriod() throws Exception {
        setup(0,false,0); command(0);
        int k=keysPerPeriod();
        println("MIDI PERIOD "+k+" keys per period, "+periodUnits()+" units a period");
        w(0x60fa,2,0);
        int calls=0;
        for(int trn=0;trn<2;trn++)for(int zone:trn==0?new int[]{0}:new int[]{0,1,2,3,4,8})
            for(int oct=0;oct<2;oct++)for(int pad=0;pad<4;pad++) {
                w(S+0x6a,1,trn); w(S+0x6b,1,zone); w(S+0x342,1,oct); w(S+0x2ef,1,pad);
                int m=notePeriods();
                for(int key=0;key<29;key++) {
                    e.writeRegister("R12",key); long factory=call(0x800057a8L);
                    check("the count is the factory routine's: key "+key+" at "+m+" periods names "+factory,
                        factory==Math.min(127,key+36+12*m));
                    long want=k==12?factory:noteAt(key,m);
                    for(long entry:new long[]{0x8001e740L,0x8001e744L,0x8001e748L}) {
                        e.writeRegister("R12",key); long got=call(entry); calls++;
                        check("key "+key+", trn "+trn+" zone "+zone+", octaves "+oct+", pad "+pad
                            +", entry "+Long.toHexString(entry)+": note "+got+", want "+want,got==want);
                    }
                }
            }
        e.writeRegister("R12",29);
        check("a key past 28 is still not a key",call(0x8001e740L)==0xff);
        w(S+0x6a,1,0); w(S+0x6b,1,0);
        if(k!=12) {
            w(S+0x342,1,1); w(S+0x2ef,1,1);
            e.writeRegister("R12",9); long one=call(0x8001e740L);
            w(S+0x2ef,1,2);
            e.writeRegister("R12",9); long two=call(0x8001e740L);
            check("a pad steps the map's "+k+" keys: "+one+" -> "+two,two-one==k);
        }
        // A period from the jack and a period from a pad name one note.
        if(r(0x6d32,1)!=0) {
            setup(0,false,0); command(0); latchFixture(); octavePad(1);
            cvFiltered=r(0x8001a348L,4)==0x8001eb20L;
            cv((int)r(0x680a,2)); sound();
            check("a period of the jack is the map's "+k+" degrees: "+jackN(),jackN()==k);
            e.writeRegister("R12",9); long byJack=call(0x8001e740L);
            cv(0); sound(); octavePad(2); sound();
            check("and the jack is back at none",jackN()==0);
            e.writeRegister("R12",9); long byPad=call(0x8001e740L);
            check("a period of the pads names the note a period of the jack names: pad "+byPad+", jack "+byJack,
                byPad==byJack);
        }
        println("PASS MIDI period: "+calls+" key notes, "+(k==12?"the factory's routine at twelve keys":k+" keys a pad")
            +(r(0x6d32,1)!=0?", a pad and the jack alike":""));
    }
    // F07: a latched note's MIDI note is the note its CV plays.  A press at
    // a second octave stacks into a borrowed slot, and names the key that
    // was pressed at the octave it went in at.  In HOLD a held note keeps
    // that octave on MIDI as its CV does; in AFTER the pad moves the set on
    // both.  Every note-on is checked against the CV of its own step: the
    // pressed key named at the live pad, plus the whole periods between that
    // CV and the key's own pitch at the live pad.
    long latchedNote(int slot,long cv) {
        int owner=(int)r(0x6504+slot,1), key=owner>0&&owner<=29?owner-1:slot;
        long off=cv-(short)r(0x854+2*key,2)-livePad();
        return jackNote(key,notePeriods()+(int)periodsIn(off));
    }
    // Four sounded steps.  A step pattern answers a rest with no note at
    // all, and moves the arp on only on the beats that sound, so a beat
    // with no note-on is passed over - up to sixteen of them.
    Map<Integer,long[]> latchedRound(String when) throws Exception {
        Map<Integer,long[]> heard=new TreeMap<>();
        int sounded=0;
        for(int i=0;i<16&&sounded<4;i++) {
            midiOn.clear(); externalBeat(); sound();
            if(midiOn.isEmpty()) continue;
            sounded++;
            int slot=(int)r(S+0x34d,1);
            long note=lastNote(when), cv=r(S+0x352,2), want=latchedNote(slot,cv);
            check(when+": slot "+slot+" sends note "+note+" for a CV of "+cv+", which is note "+want,note==want);
            heard.put(slot,new long[]{note,cv});
        }
        check(when+": four steps sound in sixteen beats, "+sounded+" did",sounded==4);
        return heard;
    }
    // Each scenario builds its own set, so each can fail on its own.
    //
    // A press of a latched key at another octave stacks into the lowest free
    // slot, and names the key that was pressed, a period over its first note.
    void latchStackMidi() throws Exception {
        int k=keysPerPeriod(), key=9;
        setup(0,false,1); command(0); latchFixture(); w(S+0x2fc,2,0);
        octavePad(1); key(key); sound();
        octavePad(2); key(key); sound();
        check("a press of the same key at another octave stacks into the lowest free slot",
            r(S+0x21b+key,1)==1&&r(S+0x21b,1)==1&&r(0x6504,1)==key+1&&r(0x6504+key,1)==key+1);
        Map<Integer,long[]> stack=latchedRound("a stacked octave");
        check("the arp sounds both notes of the stack",stack.containsKey(0)&&stack.containsKey(key));
        long own=stack.get(key)[0], borrowed=stack.get(0)[0];
        check("the borrowed slot names the key pressed, a period over the first: "+own+" and "+borrowed,
            borrowed-own==k&&Math.floorMod(own-key-36-jackN(),k)==0);
        println("PASS latched MIDI, a stacked octave: the borrowed slot names the key pressed, "+k+" notes up");
    }
    // In HOLD the pads choose where a note goes in and a held note stays:
    // two periods of the pad move neither its CV nor its MIDI note.
    void latchHoldMidi() throws Exception {
        int key=9;
        setup(0,false,1); command(0); latchFixture(); w(S+0x2fc,2,0);
        octavePad(1); key(key); sound();
        Map<Integer,long[]> entered=latchedRound("HOLD, as entered");
        octavePad(3); sound();
        Map<Integer,long[]> moved=latchedRound("HOLD, two periods of the pad later");
        check("in HOLD the pad moves the note neither on MIDI nor on the CV: "+entered.get(key)[0]+" -> "+moved.get(key)[0],
            moved.get(key)[0]==entered.get(key)[0]&&Math.abs(moved.get(key)[1]-entered.get(key)[1])<=1);
        // On the middle position with the quantiser off the pad adds its
        // preset voltage as it is, and no MIDI note has ever carried it.  A
        // note latched there, with nothing moved since, names the note a
        // live key names: what the pad adds now comes off, the voltage with
        // it, rather than the voltage being counted in whole periods.
        boolean free=r(0x6d31,1)==0;
        if(free) {
            setup(0,false,1); command(0); latchFixture(); w(S+0x2fc,2,0);
            presetSwitch(1,700);
            key(key); sound();
            Map<Integer,long[]> middle=latchedRound("HOLD, under a preset voltage");
            long live=jackNote(key,notePeriods());
            check("a note latched under a preset voltage names the note a live key names: "
                +middle.get(key)[0]+" and "+live,middle.get(key)[0]==live);
        }
        println("PASS latched MIDI, HOLD: a held note keeps its octave on MIDI"+(free?", a preset voltage stays off MIDI":""));
    }
    // In AFTER the pads move the set, from the octave each note already had.
    // Entered a period up in HOLD, carried into AFTER with pad 2 down, then
    // two periods of the pad: three periods over pad 2's note, as its CV is.
    void latchAfterMidi() throws Exception {
        int key=9;
        setup(0,false,1); command(0); latchFixture(); w(S+0x2fc,2,0);
        octavePad(2); key(key); sound();
        octavePad(1); sound();
        // Into AFTER through the toggle itself, with the pads up so the
        // shadow it takes the reference from is the pad standing now.
        controlScan(); call(0x8001e580L);
        check("the latch is in AFTER, its reference pad 2's",r(0x62e2,1)==1);
        octavePad(3); sound();
        Map<Integer,long[]> after=latchedRound("AFTER, two periods of the pad later");
        long want=jackNote(key,notePeriods()+1);
        check("in AFTER the pad moves the set on MIDI too: "+after.get(key)[0]+", want "+want,
            after.get(key)[0]==want);
        println("PASS latched MIDI, AFTER: the set follows the pad on MIDI from the octave each note had");
    }
    // A latched note's MIDI names the pitch its CV plays when the quantised
    // preset moves after the note went in (2026-09-28, the audit's
    // follow-ups).  In HOLD latch_preset_pin keeps the CV where it was
    // entered, and the note used to add the preset's live degrees all the
    // same; in AFTER the set follows the preset on both.  Each step's note is
    // read against its own CV: the degree of the slot's table, at the live
    // pad, nearest the CV, named as a live key on that degree is named.  The
    // quantised preset is forced on where the variant was built without it:
    // the latch runs there too, and the rotation is in every image.
    long cvNote(long cv) {
        long want=cv-livePad(), best=Long.MAX_VALUE;
        int at=0;
        for(int j=-64;j<192;j++) {
            long d=Math.abs(wrappedEntry(j)-want);
            if(d<best) { best=d; at=j; }
        }
        return Math.max(0,Math.min(127,at+36+(long)keysPerPeriod()*notePeriods()));
    }
    Map<Integer,long[]> presetRound(String when) throws Exception {
        Map<Integer,long[]> heard=new TreeMap<>();
        int sounded=0;
        for(int i=0;i<16&&sounded<4;i++) {
            midiOn.clear(); externalBeat(); sound();
            if(midiOn.isEmpty()) continue;
            sounded++;
            int slot=(int)r(S+0x34d,1);
            long note=lastNote(when), cv=r(S+0x352,2), want=cvNote(cv);
            check(when+": slot "+slot+" sends note "+note+" for a CV of "+cv+", which plays note "+want,note==want);
            heard.put(slot,new long[]{note,cv});
        }
        check(when+": four steps sound in sixteen beats, "+sounded+" did",sounded==4);
        return heard;
    }
    void latchPresetMidi() throws Exception {
        // Presets of about 200 and 500 cents: two and five degrees of a
        // twelve-key map, four and ten of the 24-key one.  {AFTER, the
        // preset the note goes in under, the one pad 2 moves it to}: a few
        // degrees up in both states and down in HOLD, and two periods up in
        // HOLD, where a note far under its key's rotated entry has to be
        // named in one sum rather than held at note 0 on the way.
        int key=9, low=61, high=153;
        int[][] moves={{0,low,high},{1,low,high},{0,high,low},{0,0,760}};
        long quantizer=-1;
        for(int[] mv:moves) {
            boolean after=mv[0]==1;
            String state=after?"AFTER":"HOLD";
            setup(0,false,1); command(0); latchFixture(); w(S+0x2fc,2,0);
            if(quantizer<0) quantizer=r(0x6d31,1);      // this image's own, put back at the end
            w(0x6d31,1,1);
            w(0x613a+2*2,2,mv[2]);
            presetSwitch(1,mv[1]);
            long entered=r(0x60f3,1);
            key(key); sound();
            if(after) {
                // Into AFTER through the toggle, with the pads up.
                controlScan(); call(0x8001e580L);
                check("the latch is in AFTER",r(0x62e2,1)==1);
            }
            Map<Integer,long[]> in=presetRound(state+", under the preset it went in with ("+entered+" degrees)");
            presetSwitch(2,mv[2]);
            long moved=r(0x60f3,1);
            check(state+": pad 2 moves the preset "+(moved-entered)+" degrees",Math.abs(moved-entered)>=2);
            Map<Integer,long[]> out=presetRound(state+", the preset moved "+(moved-entered)+" degrees");
            long n0=in.get(key)[0], n1=out.get(key)[0], c0=in.get(key)[1], c1=out.get(key)[1];
            if(!after)
                check("in HOLD the CV and the note stay where they went in over "+(moved-entered)+" degrees: CV "
                    +c0+" -> "+c1+", note "+n0+" -> "+n1,Math.abs(c1-c0)<=1&&n1==n0);
            else
                check("in AFTER the set follows the preset on both: CV "+c0+" -> "+c1+", note "+n0+" -> "+n1,
                    c1>c0&&n1-n0==moved-entered);
        }
        // With the quantiser off the preset adds a voltage, not degrees, and
        // a held note is named in whole periods as before: 100 units in,
        // then 200, under half a period either way.
        setup(0,false,1); command(0); latchFixture(); w(S+0x2fc,2,0);
        w(0x6d31,1,0);
        w(0x613a+2*2,2,152);
        presetSwitch(1,76);
        key(key); sound();
        Map<Integer,long[]> in=latchedRound("HOLD, the quantiser off, under a preset voltage");
        presetSwitch(2,152);
        Map<Integer,long[]> out=latchedRound("HOLD, the quantiser off, the voltage moved");
        long named=jackNote(key,notePeriods());
        check("with the quantiser off a held note keeps the note a live key names: "+in.get(key)[0]+" -> "
            +out.get(key)[0]+", want "+named,in.get(key)[0]==named&&out.get(key)[0]==named);
        w(0x6d31,1,quantizer);
        println("PASS latched MIDI under a moved preset: HOLD names the degree its CV kept, AFTER the one it moved to, "
            +keysPerPeriod()+" keys a period");
    }
    // The keyboard's velocity floors and the peak hold follow pressure_fix
    // (2026-09-28, the audit's follow-ups).  Three factory sites held them
    // in every image, as patches misnamed transpose_force_1..3: the floor of
    // the velocity a key's contact computes (0x80005466), which the poly
    // note and the mono note at the press both carry, the floor of the note
    // the mono path's hand-back sends (0x800062f8), both 1, and the peak
    // hold's reload (0x80005392), 1 where the factory has 10.  With the fix
    // on they stay so.  With it off they are the factory's: the floor is
    // state+0x2db, configuration knob 3's minimum, and a curve level
    // knob4_curve left in that byte (0xa0 | level) reads as 1.  Through the
    // real routines: the pressure state machine for the press and the lift,
    // and the conditioner from its own call site for the peak hold.
    static final long TOUCH=0x800053acL, KEY_VELOCITY=0x33e8L;
    void touch(int key,int reading) throws Exception {
        e.writeRegister("R11",reading); e.writeRegister("R12",key); call(TOUCH);
    }
    // The contact and lift thresholds at 210 and 180, the velocity's full
    // scale at 700 and its maximum at 127, as the factory's defaults have
    // the last two (0x8000714c).
    void unitVelocity() {
        w(S+0x394,2,100); w(S+0x396,2,100); w(S+0x2de,2,700); w(S+0x2dc,1,127);
    }
    void velocityFloor() throws Exception {
        // {pressure_fix, state+0x2db}: knob 3's minimum at 100, and a level.
        int[][] cases={{1,100},{0,100},{0,0xb5}};
        for(int[] c:cases) {
            boolean fix=c[0]==1, level=(c[1]>>5)==5;
            long floor=fix||level?1:c[1];
            String tag="pressure_fix "+(fix?"on":"off")+", state+0x2db 0x"+Integer.toHexString(c[1]);
            // The press.  The unit's settings are the factory's defaults for
            // the velocity (full scale 700, the maximum 127), and thresholds
            // that let a reading sit on the contact threshold and above the
            // lift's: a key pressed there takes the floor itself.
            for(int poly=0;poly<2;poly++) {
                setup(0,false,0); command(0);
                w(S+0x84,1,poly);
                unitVelocity();
                w(0x6d2f,1,c[0]); w(S+0x2db,1,c[1]);
                midiOn.clear(); midiVel.clear();
                touch(4,210); touch(4,210);
                long stored=r(KEY_VELOCITY+4,1), sent=midiVel.isEmpty()?-1:midiVel.get(midiVel.size()-1);
                check(tag+(poly==1?", poly":", mono")+": a press on the threshold takes velocity "+stored
                    +" and its note-on carries "+sent+", want "+floor,stored==floor&&sent==floor);
            }
            // The hand-back.  Both keys go down with the fix on, so the lift
            // hands key 4 its note back from a velocity of 1 in every case:
            // under a floor of 100 that is 100, and under a floor of 1 it
            // is 1 plus the factory's jitter of -10..9, held at 1.
            setup(0,false,0); command(0);
            w(S+0x84,1,0);
            unitVelocity();
            w(0x6d2f,1,1);
            touch(4,210); touch(4,210); touch(9,210); touch(9,210);
            long four=jackNote(4,notePeriods()), nine=jackNote(9,notePeriods());
            check(tag+": key 9 took the note from key 4 at velocity 1: note "+r(S+0x2e1,1)+", want "+nine,
                r(S+0x2e1,1)==nine&&r(S+0x2e2,1)==1);
            w(0x6d2f,1,c[0]); w(S+0x2db,1,c[1]);
            midiOn.clear(); midiVel.clear();
            touch(9,0);
            long back=midiVel.isEmpty()?-1:midiVel.get(midiVel.size()-1);
            check(tag+": letting key 9 go hands key 4 its note back ("+midiOn+") at velocity "+back
                +(floor==1?", 1..10":", want 100"),
                !midiOn.isEmpty()&&midiOn.get(midiOn.size()-1)==four
                &&(floor==1?back>=1&&back<=10:back==100));
            // The peak hold.  The countdown runs out on this pass and is
            // reloaded; on the next, a falling reading lands at once with a
            // reload of 1, and waits nine more passes with the factory's 10.
            // Sensor 30 is neither a key nor the strip, so the conditioner
            // stores it and does nothing else with it.
            setup(0,false,0); command(0);
            w(0x6d2f,1,c[0]); w(S+0x2db,1,c[1]);
            int k=30;
            long m=r(0x2c+k,1), raw=S+0x86+2*m, base=S+0xd6+2*m, held=S+0x126+2*k;
            w(base,2,0); w(raw,2,100); w(held,2,500); w(S+0x1c6,1,1);
            call(0x80004d52L,0x80004d56L);
            check(tag+": the countdown ran out, the falling reading landed, and the hold reloads with "
                +r(S+0x1c6,1)+", want "+(fix?1:10),r(held,2)==100&&r(S+0x1c6,1)==(fix?1:10));
            w(held,2,500);
            call(0x80004d52L,0x80004d56L);
            check(tag+": on the next pass a falling reading "+(fix?"lands":"waits")+": "+r(held,2),
                r(held,2)==(fix?100:500));
        }
        println("PASS velocity floors and peak hold: 1 and every scan with pressure_fix on; knob 3's minimum and every tenth scan off, a curve level reading 1");
    }
    // A key played over a take names its own note (audit 038711a, F08).
    // midi_step_degree gave every note midi_transpose named in PLAY the
    // sounding step's preset degrees less the take's, gated on 0x33c5 to
    // leave the keyboard out; the contact handler clears that flag just
    // before a press converts its note, so the keyboard took the step's
    // term.  Through the real contact handler and lift, the arp off: a take
    // whose steps were played two degrees over its reference, and a key
    // pressed while one of them sounds.
    void keyOverTakeMidi() throws Exception {
        setup(2,false,0); command(1);
        w(0x6600,1,2); w(0x6601,1,2); w(0x6091,1,0);
        w(S+0x2fc,2,0); externalBeat(); sound();
        check("a step of the take sounds, played two degrees over its reference",
            r(0x6158,1)==2&&r(0x6503,1)<2&&r(0x2eed,1)!=0);
        midiOn.clear(); touchOn(4); sound();
        long want=jackNote(4,notePeriods()), held=r(S+0x2e1,1), sent=lastNote("the key over the take");
        check("a key pressed over a take sends its own note: sent "+sent+", held "+held+", want "+want,
            sent==want&&held==want);
        touchOff(4);
        check("and its lift ends it",r(S+0x2e1,1)==0xff);
        println("PASS a key played over a take names its own note, not the sounding step's degrees");
    }
    // ---- A MIDI note named in one sum (the clamp-chain scan, 2026-09-28) --
    // F1 and F2.  Each helper that named a note held part of it to 0..127
    // and added the rest after: midi_period_note held key + 36 and the
    // periods before midi_period_live put the jack's N and a step's term on,
    // the two capped at 127 first, and midi_transpose held the routine's
    // note before N.  A note whose first part went under 0 or over 127 came
    // back from the wrong place.  So every entry is swept here over the key,
    // the periods, N and the term, on the image's own map, a 24-key record
    // and Bohlen-Pierce, against the one sum held once.  Where no hold was in
    // play that is the note the firmware always named.
    static final long MT_LIVE=0x8001e740L, MT_HELD=0x8001e744L, MT_PRESS=0x8001e748L, MP_LIVE=0x80020520L;
    // The rotation's state word at N degrees of the selected slot, as the
    // transposer leaves it, without a rebuild: the helpers read N off it.
    void stateN(int n) { int slot=(int)r(0x6090,1); if(slot>2) slot=0; w(0x60fa,2,0xa000|(slot<<8)|(n&0xff)); }
    // 32 entries of a map of `keys` equal steps to a period of 484, from 485.
    int[] equalTable(int keys) {
        int[] t=new int[32];
        for(int k=0;k<32;k++) t[k]=485+(484*k+keys/2)/keys;
        return t;
    }
    int oneSumCalls, oneSumReached, oneSumMisses;
    List<String> oneSumFailed=new ArrayList<>();
    void oneSumMiss(String what,long got,long want) {
        oneSumMisses++;
        if(oneSumFailed.size()<12) oneSumFailed.add(what+": sent "+got+", want "+want);
        else if(oneSumFailed.size()==12) oneSumFailed.add("...");
    }
    void oneSumSweep(String map) throws Exception {
        int k=keysPerPeriod(), before=oneSumMisses, calls=0, reached=0;
        int[] ns={0,1,10,30,127,130,200,255};
        // midi_transpose: every key and one past it, at every switch, pad and
        // trn zone the factory's count reads, through all three entries with
        // no note sounding.  N runs past a signed byte: it is the state
        // word's low byte, 0..255.
        for(int trn=0;trn<2;trn++)for(int zone:trn==0?new int[]{0}:new int[]{0,1,2,3,8})
        for(int oct=0;oct<2;oct++)for(int pad=0;pad<4;pad++)for(int n:ns) {
            w(S+0x6a,1,trn); w(S+0x6b,1,zone); w(S+0x342,1,oct); w(S+0x2ef,1,pad); stateN(n);
            int m=notePeriods();
            for(int key=0;key<30;key++) {
                long part=rawNote(key,m), want=key>28?0xff:clampNote(part+n);
                if(key<=28&&(part<0||part>127)&&want!=clampNote(clampNote(part)+Math.min(n,127))) reached++;
                for(long entry:new long[]{MT_LIVE,MT_HELD,MT_PRESS}) {
                    w(S+0x2e1,1,0xff);
                    e.writeRegister("R12",key); long got=call(entry); calls++;
                    if(got!=want) oneSumMiss(map+" midi_transpose "+Long.toHexString(entry)+" key "+key+" trn "+trn
                        +" zone "+zone+" octaves "+oct+" pad "+pad+" N "+n,got,want);
                }
            }
        }
        // The shift a press froze, read back for the note still sounding.
        w(S+0x6a,1,1); w(S+0x6b,1,0); w(S+0x342,1,0); w(S+0x2ef,1,1);
        int m0=notePeriods();
        for(int f:ns)for(int key=0;key<29;key++) {
            stateN(0); w(S+0x2e1,1,60); w(0x60fe,1,f);
            e.writeRegister("R12",key); long got=call(MT_HELD); calls++;
            long want=clampNote(rawNote(key,m0)+f);
            if(got!=want) oneSumMiss(map+" midi_transpose's lift, frozen "+f+", key "+key+" at "+m0+" periods",got,want);
        }
        // midi_period_live: a key at any count of periods, N and a step's
        // term on top, the term either side of zero.
        w(S+0x6a,1,0); w(S+0x6b,1,0); w(S+0x342,1,1); w(S+0x2ef,1,1);
        for(int n:new int[]{0,10,30,130})for(int d:new int[]{-40,-10,-1,0,1,10,40})for(int m=-6;m<=10;m++)
        for(int key=0;key<30;key++) {
            stateN(n);
            e.writeRegister("R12",key); e.writeRegister("R10",m&0xffffffffL); e.writeRegister("R11",d&0xffffffffL);
            long got=call(MP_LIVE); calls++;
            long part=rawNote(key,m), want=key>28?0xff:clampNote(part+n+d);
            if(key<=28&&want!=clampNote(clampNote(part)+Math.min(n+d,127))) reached++;
            if(got!=want) oneSumMiss(map+" midi_period_live key "+key+" at "+m+" periods, N "+n+", term "+d,got,want);
        }
        // Before the transposer has run, neither N nor the term goes on.
        w(0x60fa,2,0);
        int m1=notePeriods();
        for(int d:new int[]{-10,0,10})for(int m=-4;m<=8;m+=4)for(int key=0;key<29;key+=7) {
            e.writeRegister("R12",key); e.writeRegister("R10",m&0xffffffffL); e.writeRegister("R11",d&0xffffffffL);
            long got=call(MP_LIVE); calls++;
            if(got!=noteAt(key,m)) oneSumMiss(map+" midi_period_live before the transposer, key "+key+" at "+m,got,noteAt(key,m));
        }
        for(int key=0;key<29;key+=7) {
            w(S+0x2e1,1,0xff); e.writeRegister("R12",key); long got=call(MT_LIVE); calls++;
            if(got!=noteAt(key,m1)) oneSumMiss(map+" midi_transpose before the transposer, key "+key,got,noteAt(key,m1));
        }
        oneSumCalls+=calls; oneSumReached+=reached;
        println("ONE SUM "+map+": "+keysPerPeriod()+" keys a period, "+calls+" notes, "+reached
            +" where a held part came back, "+(oneSumMisses-before)+" off");
        check(map+": the one sum reaches a hold in "+reached+" notes",reached>0);
    }
    void midiOneSum() throws Exception {
        oneSumFailed.clear(); oneSumCalls=0; oneSumReached=0; oneSumMisses=0;
        setup(0,false,0); command(0);
        String own=keysPerPeriod()+"-key image map";
        oneSumSweep(own);
        if(keysPerPeriod()!=24) {
            setup(0,false,0); command(0); tunedRecord(24,484,equalTable(24));
            oneSumSweep("24-key record");
        }
        if(bpTable!=null) {
            setup(0,false,0); command(0); tunedRecord(bpKeys,bpPeriod,bpTable);
            oneSumSweep("Bohlen-Pierce record");
        } else println("SKIP Bohlen-Pierce in the one sum: no table was handed over");
        check("MIDI notes are named in one sum, held to 0..127 once: "
            +(oneSumMisses==0?"all":oneSumMisses+" off, "+oneSumFailed),oneSumMisses==0);
        println("PASS MIDI notes named in one sum: "+oneSumCalls+" notes through midi_transpose and midi_period_live, "
            +oneSumReached+" of them where a part held first came back from the wrong place");
    }
    // The same end to end, through the paths the scan named, each with a part
    // of its note under 0 or over 127 before the rest goes on: a latched note
    // in AFTER and in HOLD with the quantiser off, a take's playback on both
    // sides, a preview, and the keyboard in a trn zone.  Each note-on is held
    // to the one sum, and to the note its CV plays where that CV is inside
    // the pitch range, off both its holds: the degree of the slot's table nearest the CV less
    // the live transpose, named at the factory's count (cvNote, unheld).
    long cvNoteRaw(long cv) {
        long want=cv-livePad(), best=Long.MAX_VALUE;
        int at=0;
        for(int j=-128;j<320;j++) {
            long d=Math.abs(wrappedEntry(j)-want);
            if(d<best) { best=d; at=j; }
        }
        return at+36+(long)keysPerPeriod()*notePeriods();
    }
    void heardIs(String what,long sent,long sum,long cv) throws Exception {
        long want=clampNote(sum);
        check(what+": sends "+sent+", the one sum "+sum+" held once is "+want,sent==want);
        if(cv>-0x78&&cv<0xfff&&sum>=0&&sum<=127)
            check(what+": its CV "+cv+" plays note "+cvNoteRaw(cv)+", the note it sends",cvNoteRaw(cv)==sum);
    }
    // Knob 4 off the transpose whatever role the image was built with, so
    // the octave pads alone count; or on it at a raw knob position.
    void noTrn() { w(0x6d2c,1,0); w(S+0x6a,1,0); w(S+0x6b,1,0); }
    void trnAt(int raw) throws Exception { w(0x6d2c,1,1); w(S+0x310,2,raw); controlScan(); }
    // A 24-key map: the image's own where it has one, a record of 24 equal
    // steps where it does not.
    void map24() throws Exception { if(keysPerPeriod()!=24) tunedRecord(24,484,equalTable(24)); }
    // The notes a round of arp steps sends, by the slot each sounds.
    Map<Integer,long[]> latchHeard(int want) throws Exception {
        Map<Integer,long[]> heard=new TreeMap<>();
        for(int i=0;i<16&&heard.size()<want;i++) {
            midiOn.clear(); externalBeat(); sound();
            if(midiOn.isEmpty()) continue;
            heard.put((int)r(S+0x34d,1),new long[]{midiOn.get(midiOn.size()-1),(short)r(S+0x352,2)});
        }
        return heard;
    }
    // The notes a take's steps send, by the key each was recorded on.
    Map<Long,long[]> takeHeard(int want) throws Exception {
        Map<Long,long[]> heard=new TreeMap<>();
        for(int i=0;i<12&&heard.size()<want;i++) {
            midiOn.clear(); externalBeat(); sound();
            if(midiOn.isEmpty()) continue;
            int st=(int)r(0x6503,1);
            heard.put(st<0x40?r(0x61ee+st,1):-1L,new long[]{midiOn.get(midiOn.size()-1),(short)r(S+0x352,2)});
        }
        return heard;
    }
    // A take of `keys` steps laid directly: each recorded `periods` periods
    // off the take's reference `ref`, at its degrees `e`, the take born under
    // `born` preset degrees.
    void layTake(int[] keys,long ref,int periods,int e,int born) throws Exception {
        long p=r(0x6814,2);
        for(int i=0;i<keys.length;i++) {
            w(0x61ee+i,1,keys[i]); w(0x6600+i,1,e);
            w(0x6160+2*i,2,(periods*p+wrappedEntry(keys[i]+e))&0xffff);
        }
        w(0x62f4,2,ref&0xffff); w(0x6091,1,born);
    }
    // Each path on its own, so one that sends the wrong note leaves the
    // others to be heard.
    interface Scenario { void run() throws Exception; }
    List<String> pathsRan=new ArrayList<>(), pathsFailed=new ArrayList<>();
    void scenario(String name,boolean applies,String why,Scenario body) {
        if(!applies) { println("SKIP "+name+": "+why); return; }
        try { body.run(); pathsRan.add(name); println("PATH "+name); }
        catch(Exception ex) { pathsFailed.add(name+": "+ex.getMessage()); println("PATH FAIL "+name+": "+ex.getMessage()); }
    }
    void midiClampPaths() throws Exception {
        pathsRan.clear(); pathsFailed.clear();
        boolean latch=r(0x6d28,1)!=0, jackOn=r(0x6d32,1)!=0, seqOn=r(0x6d2d,1)!=0;
        String noJack="the jack is on portamento in this image";
        // AFTER on a twelve-key map: two keys entered on octave pad 0, the
        // set's reference taken at pad 3, played back at pad 0 - four periods
        // under key + 36 - with the jack 30 degrees up.  Key 5 is 7 under
        // note 0 before N, and was sent as note 30 for a CV playing note 23.
        scenario("AFTER four periods under its key, twelve keys, the jack 30 up",latch&&jackOn,
            latch?noJack:"no latching arp in this image",() -> {
            int[] keys={5,9};
            setup(0,false,1); command(0); tunedRecord(12,484,equalTable(12)); noTrn(); latchFixture(); w(S+0x2fc,2,0);
            cvFiltered=r(0x8001a348L,4)==0x8001eb20L;
            octavePad(0); for(int key:keys) { key(key); sound(); }
            octavePad(3); sound();
            controlScan(); call(0x8001e580L);
            check("the latch is in AFTER, its reference pad 3's",r(0x62e2,1)==1);
            octavePad(0); sound();
            tunedJack(30); sound();
            Map<Integer,long[]> heard=latchHeard(keys.length);
            for(int key:keys) {
                check("key "+key+" sounds",heard.containsKey(key));
                heardIs("key "+key,heard.get(key)[0],rawNote(key,notePeriods()-3)+jackN(),heard.get(key)[1]);
            }
        });
        // The 24-key known case: AFTER, entered on pad 0, the reference at
        // pad 2, played on the middle position under a quantised preset of
        // ten degrees.  Key 9 is 3 under note 0 before N: it was sent as note
        // 10 for a CV playing note 7.
        scenario("AFTER two periods under its key, 24 keys, a preset of ten degrees",latch,
            "no latching arp in this image",() -> {
            setup(0,false,1); command(0); map24(); noTrn(); latchFixture(); w(S+0x2fc,2,0);
            cvFiltered=r(0x8001a348L,4)==0x8001eb20L;
            w(0x6d31,1,1);
            octavePad(0); key(9); sound();
            octavePad(2); sound();
            controlScan(); call(0x8001e580L);
            check("the latch is in AFTER",r(0x62e2,1)==1);
            w(0x613a+2*1,2,153);
            presetSwitch(1,153);
            check("the preset stands ten degrees up: N="+jackN(),jackN()==10);
            Map<Integer,long[]> heard=latchHeard(1);
            check("key 9 sounds",heard.containsKey(9));
            heardIs("key 9",heard.get(9)[0],rawNote(9,notePeriods()-2)+jackN(),heard.get(9)[1]);
        });
        // HOLD with the quantiser off: the note is named in whole periods off
        // its stamp less what the pad adds now, and the preset's voltage
        // stays off MIDI.  Entered on pad 0 and played on the middle position
        // under a store of 200 with the jack 10 up: key 9 two periods under
        // its key was sent as note 10 for 7.
        scenario("HOLD with the quantiser off, 24 keys, the jack 10 up",latch&&jackOn,
            latch?noJack:"no latching arp in this image",() -> {
            setup(0,false,1); command(0); map24(); noTrn(); latchFixture(); w(S+0x2fc,2,0);
            cvFiltered=r(0x8001a348L,4)==0x8001eb20L;
            w(0x6d31,1,0);
            octavePad(0); key(9); sound();
            presetSwitch(1,200);
            tunedJack(10); sound();
            check("the latch holds, N="+jackN(),r(0x62e2,1)==0&&jackN()==10);
            Map<Integer,long[]> heard=latchHeard(1);
            check("key 9 sounds",heard.containsKey(9));
            long adds=200*0x84/0x64, off=(short)r(0x60a2+2*9,2)-adds;
            long sum=rawNote(9,notePeriods()+(int)periodsIn(off))+jackN();
            check("key 9, "+periodsIn(off)+" periods off its key, sends "+heard.get(9)[0]+", the one sum "+sum+" held once",
                heard.get(9)[0]==clampNote(sum));
        });
        // A take's playback under the floor: born at pad 3, the steps played
        // at pad 0, played back at pad 0 with the jack 30 up, four periods
        // under key + 36 on a twelve-key map; and born at pad 2, played at
        // pad 0, back at pad 1 with the jack 10 up on a 24-key one.
        int[][] floors={{12,30,3,0,0},{24,10,2,0,1}};
        for(int[] f:floors)
            scenario("playback under the floor, "+f[0]+" keys, born at pad "+f[2]+", played at pad "+f[3]+", back at pad "+f[4]
                +", the jack "+f[1]+" up",seqOn&&jackOn,seqOn?noJack:"no sequencer in this image",() -> {
            int[] keys={5,9};
            setup(keys.length,false,0);
            if(f[0]==12) tunedRecord(12,484,equalTable(12)); else map24();
            noTrn();
            long p=r(0x6814,2);
            layTake(keys,(f[2]-1)*p,f[3]-f[2],0,0);
            command(1);
            w(S+0x342,1,1); w(S+0x343,1,0); octavePad(f[4]);
            cvFiltered=r(0x8001a348L,4)==0x8001eb20L;
            tunedJack(f[1]);
            w(S+0x2fc,2,0);
            Map<Long,long[]> heard=takeHeard(keys.length);
            check("PLAY",r(0x6158,1)==2);
            for(int key:keys) {
                check("the step on key "+key+" sounds",heard.containsKey((long)key));
                heardIs("key "+key,heard.get((long)key)[0],rawNote(key,notePeriods()+f[3]-f[2])+jackN(),heard.get((long)key)[1]);
            }
        });
        // Over the cap: trn zone 8 on octave pad 3, the take born under a
        // preset of ten degrees and its steps played under none, so the
        // step's term is -10 on a note of 122 and more before it: it was
        // sent as 117.
        scenario("playback over the cap, trn zone 8 on pad 3, the step's term -10",seqOn,
            "no sequencer in this image",() -> {
            int[] keys={0,2};
            setup(keys.length,false,0); tunedRecord(12,484,equalTable(12));
            layTake(keys,0,0,0,10);
            command(1);
            trnAt(1023);
            w(S+0x342,1,1); w(S+0x343,1,0); octavePad(3); controlScan(); sound();
            check("trn zone 8 on pad 3, the preset at none, N="+jackN(),
                r(S+0x6a,1)==1&&r(S+0x6b,1)==8&&r(S+0x2ef,1)==3&&r(0x60f3,1)==0&&jackN()==0);
            w(S+0x2fc,2,0);
            Map<Long,long[]> heard=takeHeard(keys.length);
            for(int key:keys) {
                check("the step on key "+key+" sounds",heard.containsKey((long)key));
                heardIs("key "+key+", "+notePeriods()+" periods over it",heard.get((long)key)[0],
                    rawNote(key,notePeriods())+jackN()-10,heard.get((long)key)[1]);
            }
        });
        // A preview, through the arp's word with its state laid: a
        // Bohlen-Pierce record in trn zone 0 off the octaves, three periods
        // under key + 36, a step recorded there on key 0..2.  Sent as N.
        scenario("a preview under Bohlen-Pierce in trn zone 0",bpTable!=null&&r(0x80002428L,4)==ARP_NOTE,
            "no Bohlen-Pierce table was handed over",() -> {
            setup(1,false,0); tunedRecord(bpKeys,bpPeriod,bpTable);
            w(S+0x342,1,0); w(S+0x343,1,0); w(S+0x6a,1,1); w(S+0x6b,1,0);
            for(int n:new int[]{0,10,30}) for(int key:new int[]{0,1,2}) {
                stateN(n);
                long p=r(0x6814,2);
                w(0x61ee,1,key); w(0x6600,1,0); w(0x62f4,2,0); w(0x60f3,1,0); w(0x6091,1,0);
                w(0x6160,2,(-p+wrappedEntry(key))&0xffff);
                w(0x6158,1,2); w(0x33c5,1,0); w(0x6503,1,0); w(0x62fe,1,1);
                e.writeRegister("R12",key); long got=call(ARP_NOTE);
                long sum=rawNote(key,notePeriods())+n;
                check("N "+n+": key "+key+" sends "+got+", the one sum "+sum+" held once",got==clampNote(sum));
            }
        });
        // The keyboard in trn zone 1 on a 24-key map, the switch off the
        // octaves and the jack 10 up: key 9 is two periods under key + 36, 3
        // under note 0 before N, and was sent as note 10, mono and poly.  The
        // mono lift then ends the note it sent.
        for(int poly=0;poly<2;poly++) {
            int mode=poly;
            scenario("the keyboard in trn zone 1, 24 keys, the jack 10 up, "+(mode==1?"poly":"mono"),jackOn,noJack,() -> {
                setup(0,false,0); command(0); map24();
                cvFiltered=r(0x8001a348L,4)==0x8001eb20L;
                trnAt(150);
                w(S+0x342,1,0); w(S+0x343,1,0); controlScan(); sound();
                tunedJack(10); sound();
                check("trn zone 1: "+r(S+0x6a,1)+" "+r(S+0x6b,1),r(S+0x6a,1)==1&&r(S+0x6b,1)==1);
                w(S+0x84,1,mode);
                unitVelocity();
                midiOn.clear();
                touch(9,210); touch(9,210); sound();
                check("key 9 sends a note",!midiOn.isEmpty());
                heardIs("key 9",midiOn.get(midiOn.size()-1),rawNote(9,notePeriods())+jackN(),(short)r(S+0x352,2));
                if(mode==0) {
                    check("the mono keyboard holds the note it sent",r(S+0x2e1,1)==midiOn.get(midiOn.size()-1));
                    touch(9,0);
                    check("and its lift ends it",r(S+0x2e1,1)==0xff);
                }
            });
        }
        check("every path the scan named sends the note in one sum: "
            +(pathsFailed.isEmpty()?pathsRan.size()+" ran":pathsFailed.toString()),pathsFailed.isEmpty());
        println("PASS MIDI notes named in one sum through "+pathsRan.size()+" of the paths the scan named: "
            +"the latch in AFTER and HOLD, a take's playback under 0 and over 127, a preview, the keyboard in a trn zone");
    }
    // The jack transposer, driven from the raw CV cell the factory's second
    // ADC pass fills.  The variant carries the 5-limit JI scale, whose
    // steps differ enough that a shift by degrees is not a shift by a
    // constant - which is what separates the slot's interval from the key's.
    // The jack is one-poled now (cv_filter, RAM 0x60e8), so a step in the raw
    // cell reaches the quantiser over ~20 scans rather than at once.  Every
    // test below means "the CV is sitting at this value", not "one scan after
    // it moved", so settle it - rewriting the cell each scan because the
    // filter writes its own result back into it, exactly as the factory's ADC
    // pass refills it on the instrument.  cvStep is the unsettled form, for
    // the one test that wants a single scan.
    // Whether this build has the one pole in front of the transposer, asked of
    // the image rather than assumed: the per-scan chain's pool word points at
    // cv_filter when it does and straight at cv_transpose when it does not.
    // Default builds do not, since 2026-09-12 - the pole was audible - but the
    // option is still there and the tests below have to work either way.
    boolean cvFiltered;
    void cvStep(int raw) throws Exception { w(S+0x2f0,2,raw); controlScan(); }
    void cv(int raw) throws Exception {
        for (int i=0;i<48;i++) {
            w(S+0x2f0,2,raw); controlScan();
            if (!cvFiltered||r(0x60e8,2)==raw) break;
        }
    }
    void jackTransposer() throws Exception {
        // Every CV below is a multiple of the transposer's OWN period, read
        // out of its pool word in the image under test rather than written
        // here as a number.  That constant has moved twice - 123 to 491 when
        // the jack turned out to carry a 12-bit count, and 491 to 819 when
        // one period stopped costing volts_per_octave - and the first move
        // shipped a red suite, because cv(123) silently stopped meaning one
        // period.  A fixture derived from the image cannot go stale that way.
        // Since 2026-09-22 the period and the hysteresis are settings: the
        // cave's pool word names the mirror at 0x6800 and the values sit in
        // cells 5 and 7 of it, filled at boot - so they are read out of a
        // booted image, which is still the image under test and not a
        // number written here.  The pool word itself is checked to be the
        // mirror, because reading a stale offset as a period is exactly
        // how this suite went red the last time the pool moved.
        setup(0,false,0);
        check("the transposer reads its period out of the settings mirror",
            r(CV_POOL+8,4)==0x6800);
        int period=(int)r(0x680a,2), degree=(period+6)/12;
        check("the transposer's period is the one this build carries: "+period,
            period>100&&period<2048);
        cvFiltered=r(0x8001a348L,4)==0x8001eb20L;
        int hyst=(int)r(0x680e,2);
        println("JACK SHAPE period "+period+" counts, one degree "+degree
            +", hysteresis "+hyst+", "
            +(cvFiltered?"one pole in front":"no pole: hysteresis only"));
        // With nothing smoothing the reading, the hysteresis is the whole of
        // what stops a noisy count choosing the wrong degree.  Its contract is
        // that the band reaches cv_hysteresis counts PAST the half degree, so
        // park the jack on a degree boundary - the worst place - and jitter it
        // by three quarters of that, the way an unconditioned ADC does.  The
        // answer must not move once.  The band arithmetic is what this holds:
        // drop the hysteresis term from it and 64 scans chatter.
        setup(0,false,0); command(0); latchFixture(); octavePad(1); cv(0);
        int edge=period/24, swing=Math.max(1,hyst*3/4), chatter=0;
        long settled=r(0x60fa,2)&255;
        for(int i=0;i<64;i++) {
            w(S+0x2f0,2,edge+(i%2==0?swing:-swing)); controlScan();
            if((r(0x60fa,2)&255)!=settled)chatter++;
        }
        check("noise inside the band cannot walk the degree: "+chatter
            +" change(s) over 64 scans on the boundary, swing +-"+swing+" counts",
            chatter==0);
        // A held mono note follows the CV: the table moved, and so must the
        // base the factory copied out of it at note-on.
        setup(0,false,0); command(0); latchFixture(); octavePad(1); cv(0);
        touchOn(9); sound();
        long before=r(S+0x352,2), table=r(0x854+18,2);
        check("a mono note sounds its table pitch",before==table);
        // A step on the jack has to ARRIVE as a step.  The quantiser publishes
        // a new base on every scan its answer changes, so anything that walks
        // the reading towards its target is heard as a run up through the
        // degrees in between, not as a transposition - the reported symptom
        // was an audible slew.  Drive two periods in one scan, holding the
        // raw cell the way the factory's ADC pass refills it, and count how
        // many distinct pitches the output visits on the way.
        long fromBase=r(S+0x350,2), prevBase=fromBase; int visits=0;
        StringBuilder walk=new StringBuilder();
        for(int i=0;i<40;i++) {
            w(S+0x2f0,2,2*period); controlScan(); call(0x80003590L); pitch();
            if(r(S+0x350,2)!=prevBase) {
                prevBase=r(S+0x350,2); visits++;
                if(visits<=12)walk.append(" ").append(i).append(":").append(prevBase);
            }
        }
        println("JACK STEP two periods: "+visits+" pitch(es) visited -"+walk
            +(visits>12?" ...":""));
        check("a jack step arrives as one jump, not a run through the degrees between: "
            +visits+" visited",visits==1);
        check("and it lands on the two periods it asked for",
            prevBase==fromBase+2*484&&(r(0x60fa,2)&255)==24);
        cv(0); sound();
        if(cvFiltered) {
            // The pole: one scan takes a quarter of the step at shift 2, not
            // the whole of it.  A filter that settled at once would not be one.
            cvStep(period);
            check("the jack is filtered, not read raw: "+r(0x60e8,2),
                Math.abs(r(0x60e8,2)-period/4)<=period/24);
        } else {
            // Without one, the quantiser reads the cell the factory's ADC pass
            // left, and the pole's accumulator is never touched.
            w(0x60e8,2,0x5a5a); cvStep(period);
            check("no pole: the jack is read as the ADC pass left it",
                r(0x60e8,2)==0x5a5a&&(r(0x60fa,2)&255)==12);
        }
        cv(0); sound();
        // With the blend engaged, a base that moved without its history
        // would be folded into the applied offset and slewed out: a glide.
        w(S+0x306,2,900); sound();
        cv(period); for(int i=0;i<4;i++)sound();
        check("twelve degrees of a twelve-note scale are one period: "+r(0x854+18,2),
            r(0x854+18,2)==table+484&&(r(0x60fa,2)&255)==12);
        check("a held mono note follows the jack",r(S+0x352,2)==before+484);
        check("and the blend's base history moved with it, so nothing folds",
            (short)r(0x60f4,2)==r(0x854+18,2)&&r(0x60e2,2)==0);
        // A legato press takes the shift as it stands now and freezes it,
        // so its MIDI note and its pitch CV name the same note.
        touchOn(4); sound();
        e.writeRegister("R12",4); long plain=call(0x800057a8L);
        check("a legato note's MIDI note carries the live shift",
            r(S+0x2e1,1)==plain+(r(0x60fa,2)&255)&&r(S+0x352,2)==r(0x854+8,2));
        touchOff(4); touchOff(9);
        check("all keys released clears the MIDI note",r(S+0x2e1,1)==255);
        // Nothing held, and the pitch output still stands at the last note -
        // so the jack has to keep moving it.  Until 2026-09-12 the refresh
        // gave up as soon as state+0x2e1 read 0xff, and a CV turned between
        // phrases did nothing at all until the next press.
        long idleBase=r(S+0x350,2), idleTable=r(0x854+2*r(S+0x256,1),2);
        check("the last key is still there to follow",r(S+0x256,1)<29);
        cv(period+degree); sound();
        check("with no key held the base follows the jack",
            r(S+0x350,2)!=idleBase&&r(S+0x350,2)==r(0x854+2*r(S+0x256,1),2));
        check("and it moved by the degree the table moved",
            r(S+0x350,2)-idleBase==r(0x854+2*r(S+0x256,1),2)-idleTable);
        cv(period);
        // A latched note in a borrowed slot moves by its own key's degree,
        // not the slot's: the same key an octave up lands in slot 1, whose
        // step from degree 0 to 1 is a 16/15, while degree 1 to 2 is a 9/8.
        setup(0,false,1); command(0); latchFixture(); cv(0);
        octavePad(3); key(0); noteUp(0); octavePad(1); key(0); noteUp(0);
        int other=-1; for(int i=1;i<29;i++)if(r(S+0x21b+i,1)==1){other=i;break;}
        check("the octave repeat took a borrowed slot",other>0);
        long stamped=r(0x854+2*other,2)+(short)r(0x60a2+2*other,2), root=r(0x854,2), oldSlot=r(0x854+2*other,2);
        check("the borrowed slot stands for key 0's pitch",Math.abs(stamped-root)<=1);
        cv(degree);
        long shift=r(0x854,2)-root, slotShift=r(0x854+2*other,2)-oldSlot;
        check("one degree up moves key 0 by a 16/15: "+shift,shift>=44&&shift<=46);
        check("the scale is unequal here, or this proves nothing: shift "+shift+" slot "+slotShift,Math.abs(shift-slotShift)>2);
        check("the borrowed slot moved by its key's degree",
            Math.abs(r(0x854+2*other,2)+(short)r(0x60a2+2*other,2)-(stamped+shift))<=1);
        aim(other); sound();
        check("and sounds there",Math.abs(r(S+0x352,2)-(stamped+shift))<=1);
        // The arp's sounding note follows too, before its next step.
        w(0x2eed,1,1); aim(0); sound();
        long sounding=r(S+0x352,2); cv(2*degree); sound();
        check("the arp's sounding note follows the jack",
            Math.abs(r(S+0x352,2)-(sounding+r(0x854,2)-root-shift))<=1);
        // While the sequencer plays, the base is a step's pitch and the
        // sequencer shifts it by its own path: a jack move must not swap
        // the key table in under it.
        setup(2,false,1); command(1); cv(0);
        w(S+0x2fc,2,0); externalBeat(); sound();
        long stepBase=r(S+0x350,2); cv(period); sound();
        check("a jack move leaves the playing step's base alone",r(S+0x350,2)==stepBase);
        // The pole is read before anything writes it, and SRAM survives a
        // DFU: a value the previous image left in the cell is a shift the
        // jack is not asking for.  cold() zeroes the whole region, which is
        // what hid this - so dirty it on purpose, invalidate the marker and
        // run the real bootstrap.  4095 retained is 75 degrees at zero in.
        setup(0,false,0); command(0); latchFixture();
        w(0x60e8,2,4095); w(0x602a,2,0);
        call(0x8001ab60L);
        check("first use clears the jack filter's pole",r(0x60e8,2)==0);
        w(S+0x2f0,2,0); w(0x60fa,2,0); controlScan();
        check("so a jack at rest is read as rest, not as a retained shift",
            r(0x60e8,2)==0&&(r(0x60fa,2)&255)==0);
        // The arp's note carries knob 3's random octave on top of its table
        // entry, and the refresh has to carry it too: republishing the bare
        // entry dropped the note an octave whenever the CV moved under it.
        // The displacement comes from the firmware's own randomiser, not a
        // constant here, so this cannot pass against the wrong octave.
        setup(0,false,1); command(0); latchFixture(); octavePad(1); cv(0);
        w(0x60ea,2,1023); aim(9);
        long plainBase=r(0x854+18,2), displaced=plainBase;
        for(int i=0;i<64&&displaced==plainBase;i++) {
            e.writeRegister("R8",plainBase); call(0x80019da8L); displaced=(short)reg("R8");
        }
        check("knob 3 displaces an arp note by a whole period: "+(displaced-plainBase),
            Math.abs(displaced-plainBase)==484);
        w(0x2eed,1,1); w(S+0x350,2,displaced); sound();
        cv(period); sound();
        check("the arp's random octave survives the jack moving under it",
            r(S+0x350,2)==r(0x854+18,2)+(displaced-plainBase));
        // Changing the tuning slot must not re-apply the CV shift.  The
        // tuning applier runs BEFORE the transposer in the per-scan chain and
        // leaves the new slot's UNTRANSPOSED table in RAM 0x854, so a
        // displacement measured against that table reads as the whole shift,
        // and the rebuild then adds the shift a second time.  Round trip the
        // slot with the CV standing still: the sounding base must not move.
        setup(0,false,0); command(0); latchFixture(); octavePad(1); cv(0);
        touchOn(9); sound();
        cv(period); sound();
        long shifted=r(S+0x350,2);
        for(int slot:new int[]{1,0,2,0}) {
            w(0x6090,1,slot); sound(); sound();
            check("a change to tuning slot "+slot+" leaves the sounding base alone: "
                +r(S+0x350,2)+" against "+shifted,r(S+0x350,2)==shifted);
        }
        touchOff(9); sound();
        // A jack already patched when the instrument powers up.  The
        // ownership guard has to tell "nobody has published yet" from
        // "somebody replaced the table" - 0x60fc reads as unwritten in both -
        // or the boot-time shift is discarded and never comes back, and every
        // later move carries the missing periods.  Note there is no cv(0)
        // here: establishing zero first is exactly what hid this, because it
        // seeds 0x60fc through a rebuild that shifts nothing.
        setup(0,false,0); command(0); latchFixture(); octavePad(1);
        // Back to the state a cold boot actually leaves, AFTER those fixtures
        // have had their scans: an unseeded transposer.  latchFixture() runs a
        // control scan, and that scan rebuilds at CV zero and seeds 0x60fc -
        // which is precisely the condition under which this defect cannot
        // appear, and precisely why the first shape of this test passed.
        w(0x602a,2,0); call(0x8001ab60L);
        w(S+0x256,1,255); w(S+0x34d,1,255); w(0x2eed,1,0);
        w(S+0x350,2,0); w(0x60fa,2,0); w(0x60fc,2,0); w(0x60ec,2,0);
        check("the transposer is unseeded, or this proves nothing",
            r(0x60fc,2)==0&&(r(0x60fa,2)>>12)!=0xa);
        long rest=r(0x854,2), restDac2=r(S+0x358,2);
        cv(period); sound();
        check("a jack already patched at power-up is applied, not discarded: "
            +r(S+0x350,2),r(S+0x350,2)==r(0x854,2)-rest&&r(S+0x350,2)==484);
        check("and the DAC shows it",r(S+0x358,2)!=restDac2);
        cv(2*period); sound();
        check("the next move carries no missing period: "+r(S+0x350,2),
            r(S+0x350,2)==r(0x854,2)-rest&&r(S+0x350,2)==968);
        cv(period); sound();
        check("and it comes back to where it was",r(S+0x350,2)==484);
        // Both arp positions follow the jack with nothing sounding.  The
        // pitch output holds the last note there exactly as it does with the
        // arp off, so a CV turned between phrases has to move it in all
        // three; the earlier shape gave up as soon as 0x2eed read zero and
        // left both arp positions frozen.
        for(int position:new int[]{1,2}) {
            setup(0,false,position); command(0); latchFixture(); octavePad(1); cv(0);
            aim(9); w(0x2eed,1,0); sound();
            long idle=r(S+0x350,2), wasDac=r(S+0x358,2);
            long key9=r(0x854+18,2), key0=r(0x854,2);
            // One degree, not a whole period.  A period moves every key by
            // the same amount, so a refresh following the WRONG key lands on
            // the right answer anyway - which is how the first shape of this
            // test passed with the arp path neutered.  One degree of an
            // unequal tuning separates them, and the assertion below says so
            // rather than assuming it.
            cv(degree); sound();
            check("the scale is unequal at these two keys, or this proves nothing: "
                +(r(0x854+18,2)-key9)+" vs "+(r(0x854,2)-key0),
                Math.abs((r(0x854+18,2)-key9)-(r(0x854,2)-key0))>2);
            check("the arp stopped, the jack still moves the pitch by the last arp key's degree, position "+position,
                r(S+0x350,2)==r(0x854+18,2)&&r(S+0x350,2)!=idle);
            check("and the DAC moved with it, position "+position,r(S+0x358,2)!=wasDac);
        }
        // And before the first touch, when the factory's last-key byte has
        // never been written.  The bottom key is the reference; because the
        // base is still the bootstrap's zero, what the jack adds is the
        // shift alone, so the output walks up from its rest rather than
        // jumping to the bottom key's pitch.
        setup(0,false,0); command(0); latchFixture(); octavePad(1);
        w(0x60e8,2,0); w(0x602a,2,0); call(0x8001ab60L);
        w(S+0x256,1,255); w(S+0x34d,1,255); w(0x2eed,1,0);
        w(S+0x350,2,0); w(0x60fa,2,0); cv(0); sound();
        check("nothing played yet leaves the pitch at its rest",r(S+0x350,2)==0);
        long bottom=r(0x854,2), restDac=r(S+0x358,2);
        cv(period); sound();
        check("and the jack moves it by the shift alone: "+r(S+0x350,2),
            r(S+0x350,2)==r(0x854,2)-bottom&&r(S+0x350,2)==484);
        check("which the DAC shows",r(S+0x358,2)!=restDac);
        setup(0,false,1); command(0); latchFixture(); octavePad(1); cv(0);
        // How much a rebuild costs, against the ~5 ms scan: printed, not gated.
        cv(0); steps=0; cvStep(period/4); long rebuild=steps; steps=0; cvStep(period/4); long idle=steps;
        println("SCAN BUDGET jack rebuild "+rebuild+" instructions, an idle control scan "+idle);
        println("PASS jack transposer: a held mono note and the arp's note follow, legato MIDI takes the live shift, a borrowed slot moves by its key's degree, "
            +"the filter's pole starts from a known zero, the random octave survives a move, and nothing held follows in every switch position");
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
            // MIDI names each step at the octave its CV plays (audit
            // 038711a, F08): the key at the live pad, plus the periods the
            // step was played above or below the take's reference.  The
            // first press is the reference; the second was two periods
            // under it.
            int[] played={0,-2};
            midiOn.clear(); externalBeat(); sound();
            check("playback opens on the recorded octave",(short)r(S+0x352,2)==dac(step(0)+livePad()));
            long opens=lastNote("the opening step");
            check("and its MIDI note is the key at the live pad: "+opens,
                r(0x6503,1)==0&&opens==jackNote(0,notePeriods()+played[0]));
            midiOn.clear(); externalBeat(); sound();
            check("and a step under the rail plays clamped, not wrapped",
                (short)r(S+0x352,2)==dac(step(1)+livePad()));
            long under=lastNote("the step under the rail");
            check("its MIDI note is the two periods under the first that were played: "+opens+" then "+under,
                r(0x6503,1)==1&&under==jackNote(0,notePeriods()+played[1]));
            // The pad transposes PLAY, and only play: a preview is pinned.
            octavePad(3); midiOn.clear(); externalBeat(); sound();
            check("the pad transposes playback",
                r(S+0x352,2)==r(0x61e2,2)+(short)r(0x60a0,2));
            long moved=lastNote("the step after the pad");
            int sounding=(int)r(0x6503,1);
            check("and moves its MIDI note by the pad: step "+sounding+" sends "+moved,
                sounding<2&&moved==jackNote(0,notePeriods()+played[sounding]));
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
                // The reference the take was born under, read BEFORE the
                // power cycle: what the boot leaves in 0x62f4 is the thing
                // under test, so nothing below measures against it.
                long born=(short)r(0x62f4,2);
                check("the take was born off the neutral pad: reference "+born,Math.abs(born)>=PERIOD-1);
                cold();
                check("and survives a power cycle",
                    r(0x61e0,1)==2&&step(0)==opening&&step(1)==following);
                // With the octave it was born under (audit 038711a, F9).  The
                // boot used to leave the steps measured from neutral, so a
                // preview sounded the take a period out per pad step and an
                // append was stored against neutral.  The fixture is setup()'s
                // and startPreview()'s, since the boot cleared it all.
                check("and so does the octave it was recorded under: "+(short)r(0x62f4,2)+" for "+born,
                    (short)r(0x62f4,2)==born);
                arp(position); w(S+0x308,2,0); w(S+0x2f2,2,0); w(S+0x2da,1,0); w(0x2ee6,2,1023);
                now=0; clockExercise=true; latchFixture();
                command(0);
                check("the reloaded take opens for WRITE",r(0x6158,1)==1&&r(0x61e0,1)==2);
                // A pad neither note was recorded under, through the pitch
                // chain: the instrument republishes the transpose every scan,
                // and the control scans the gestures run here do not, so the
                // pin would otherwise read the pad command(0)'s own press
                // left behind.
                octavePad(2);
                bare(1);
                if(clock)call(0x8000737eL,0x80007386L);
                w(S+0x34a,2,20); w(0x2ee0,2,20); w(S+0x2fc,2,0);
                check("a bare pad 2 previews it",r(0x6158,1)==2&&r(0x62fe,1)==1);
                externalBeat(); sound();
                check("a preview after a power cycle sounds the take as recorded: "+r(S+0x352,2)+", recorded "+dac(opening+born),
                    (short)r(0x61e2,2)==opening&&r(S+0x352,2)==dac(opening+born));
                for(int i=0;i<4;i++)externalBeat();
                check("and returns to WRITE",r(0x6158,1)==1&&r(0x62fe,1)==0);
                octavePad(1); key(0); sound();
                check("an append after a power cycle is stored against the take's own reference: "+(step(2)+born)+" for "+(r(0x854,2)+livePad()),
                    r(0x61e0,1)==3&&Math.abs((step(2)+born)-(r(0x854,2)+livePad()))<=1);
                noteUp(0);
            }
        }
        println("PASS recorded octaves: stored per press in OFF and regular arp, keyboard-only OFF sounding, pinned preview, transposed play, the reference through a power cycle");
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
    // cv_transpose's pool base.  The offsets off it are what go stale - this
    // moved 8 bytes when the pool took a word for preset_degrees and the
    // suite read the housekeeping pointer as a period.
    static final long CV_POOL=0x8001e8b0L;
    // The shift the firmware actually settled on, slot and degrees packed as
    // 0xA000 | slot << 8 | N.  Reading it keeps the checks below honest on
    // the jack variant, where the jack is contributing degrees of its own.
    int liveN() { return (int)(r(0x60fa,2)&0xff); }
    int presetUnits(int store) { return (int)(float)((double)(store<<2)*0.33); }
    long presetTarget(int base,int store) throws Exception {
        w(S+0x350,2,base); w(0x613a,2,store); musicalScan(); return r(S+0x352,2);
    }
    // Slot 0 as the configuration booted it, 32 halfwords: the image's own
    // table or the record's.  This is the rotation's source and the only
    // table intervals may be read from: RAM 0x854 is the applier's copy, the
    // applier refreshes it only when the slot CHANGES (its guard at 0x60e4),
    // and cv_transpose overwrites it with its own rotated output - so a
    // model that reads 0x854 measures the firmware's last answer instead of
    // the tuning.
    int slotEntry(int j) { return bootedHalf(0x68e0+2L*j); }
    // A transcription of preset_degrees, in the cave's own order: entries from
    // the bottom up, first strictly nearer candidate wins, the whole period
    // seeded ahead of them all.  The winner's degree moves with its interval:
    // a period of keys for every period the reduction added or took away
    // (preset_degree_count, the clamp-chain scan 2026-09-28), which is the
    // index modulo the map's size for every table whose entries sit in their
    // own period, the factory temperament's among them.
    int presetDegrees(int store,int keys) {
        int units=store*132/100;
        int whole=units/PERIOD, rem=units%PERIOD;
        int bestK=-1, bestD=PERIOD-rem, bestG=keys, t0=slotEntry(0);
        for(int k=0;k<32;k++) {
            int c=slotEntry(k)-t0, g=k;
            while(c<0) { c+=PERIOD; g+=keys; }
            while(c>=PERIOD) { c-=PERIOD; g-=keys; }
            int d=Math.abs(c-rem);
            if(d<bestD) { bestD=d; bestK=k; bestG=g; }
        }
        return whole*keys+bestG;
    }
    // The add-to-pitch switch in its middle position, a pad made active and
    // its store set: the same fixture the 2026-09-13 audit drove.
    void presetSwitch(int pad,int store) throws Exception {
        w(S+0x342,1,0); w(S+0x343,1,1); w(S+0x2ef,1,pad);
        w(0x613a+2*pad,2,store); sound(); sound();
    }
    // What the rotation checks below could NOT see.  They assert that the key
    // table rotates and that the old direct adder contributes zero, and both
    // stayed true while a take lost every interval it was played at, a
    // preview jumped an octave, and a sounding note stopped following the
    // pad entirely.  A suite that only checks the mechanism it was written
    // for agrees with the defects around it.
    // preset_entry's arithmetic, done here from the tables: the selected
    // slot's entry i, wrapping by keys per period either way and moving a
    // period of pitch (cell 10) per wrap.
    long wrappedEntry(int i) {
        int slot=(int)r(0x6090,1); if(slot>2) slot=0;
        long kpp=r(0x69a0+2*slot,2), period=r(0x6814,2), wrap=0;
        while(i>31) { i-=kpp; wrap+=period; }
        while(i<0) { i+=kpp; wrap-=period; }
        return (short)r(0x68e0+64*slot+2*i,2)+wrap;
    }
    void presetSequencer() throws Exception {
        // 1. A take keeps the preset each step was played under.  The
        //    recorder normalises the JACK's shift out so playback can
        //    re-apply it live; the preset is a stored setting and belongs
        //    baked in, or both steps store one pitch.
        setup(0,false,1); latchFixture(); presetSwitch(0,0);
        key(9); sound(); noteUp(9);
        long firstHeard=r(S+0x352,2), firstStored=step(0);
        presetSwitch(0,367); key(9); sound(); noteUp(9);
        long secondHeard=r(S+0x352,2), secondStored=step(1);
        check("a take keeps the interval its presets played it at: heard "
              +firstHeard+"->"+secondHeard+", stored "+firstStored+"->"+secondStored,
              secondHeard!=firstHeard
              && secondHeard-firstHeard==secondStored-firstStored);
        // 2. A preview sounds what was recorded whatever pad starts it: the
        //    bare pad that starts one is itself an octave chooser, so it
        //    moves the very thing it is auditioning.
        setup(0,false,1); latchFixture(); presetSwitch(0,0);
        key(9); sound(); noteUp(9);
        long recorded=r(S+0x352,2);
        presetSwitch(1,367); bare(1); sound(); midiOn.clear(); externalBeat(); sound();
        // Within a unit: adjacent octaves of a generated table round to 484
        // or 485 apart, which is the same tolerance the latch match carries.
        check("a preview sounds the recorded pitch, not the live preset's: "
              +r(S+0x352,2)+" against "+recorded,
              Math.abs(r(S+0x352,2)-recorded)<=1);
        // And its MIDI note is the one recorded (audit 038711a, F08): the
        // step's degrees are measured against the live count, as
        // seq_cv_shift measures them in a preview, so the live preset's
        // degrees cancel and the jack's stay.  The key's own note, the
        // jack, and the degrees the step was played under.
        e.writeRegister("R12",9); long ownNote=call(0x800057a8L);
        long previewNote=lastNote("the preset preview"), jackDegrees=jackN()-r(0x60f3,1);
        check("a preview's MIDI note is the one recorded, not the live preset's: "+previewNote
              +", recorded as "+(ownNote+jackDegrees+r(0x6600,1))+" with "+r(0x60f3,1)+" live degrees",
              previewNote==ownNote+jackDegrees+r(0x6600,1));
        // 3. A note already sounding follows the pad, which the factory's
        //    per-scan re-add used to do for free before the preset stopped
        //    going through the adder at all.
        setup(0,false,0); latchFixture(); presetSwitch(0,0); touchOn(9); sound();
        long held=r(S+0x352,2);
        presetSwitch(0,367);
        check("a held key in WRITE follows the preset: "+held+" -> "+r(S+0x352,2),
              r(S+0x352,2)>held);
        // 4. A playing take transposes with the preset, and it arrives at the
        //    NEXT step rather than under the note already sounding - which is
        //    exactly what the octave pads do, and what the jack does.  The
        //    preset is normalised against the take's own reference, so this
        //    is the whole point of that reference existing: reading the live
        //    count on both sides cancels out and a take cannot be transposed
        //    at all.  No equality between the two deltas is asserted: the
        //    shift is per-key, and on an unequal scale two steps move by
        //    different intervals.
        setup(0,false,1); latchFixture(); presetSwitch(0,0);
        key(9); sound(); noteUp(9); key(11); sound(); noteUp(11);
        setup(2,false,1); command(1); presetSwitch(0,0);
        externalBeat(); sound(); long a0=r(S+0x352,2);
        presetSwitch(0,367);
        check("a preset move leaves the step already sounding alone: "
              +a0+" -> "+r(S+0x352,2), r(S+0x352,2)==a0);
        externalBeat(); sound(); long b1=r(S+0x352,2);
        setup(2,false,1); command(1); presetSwitch(0,0);
        externalBeat(); sound();
        externalBeat(); sound(); long a1=r(S+0x352,2);
        check("and the next step arrives transposed: "+a1+" -> "+b1, b1>a1);
        // 5. The latch's before/after switch governs the preset the way it
        //    governs the octave.  In hold a latched note keeps the preset it
        //    was entered at; in transpose the whole set follows.  The
        //    rotation moves the table under the set, so without the pin every
        //    latched note followed the pad whatever the switch said - the
        //    octave measured 0 and 968 across the two states while the preset
        //    measured 484 in both.
        for(int state=0;state<2;state++) {
            setup(0,false,1); command(0); latchFixture();
            w(0x62e2,1,state);
            presetSwitch(0,0); key(0); aim(0); sound();
            long entered=r(S+0x352,2);
            presetSwitch(0,367); sound();
            long moved=r(S+0x352,2)-entered;
            check("latch state "+state+": a preset move "
                  +(state==0?"leaves a latched note where it was entered"
                            :"carries the whole set")+", moved "+moved,
                  state==0 ? moved==0 : moved>0);
        }
        // 6. A step recorded under a lower preset than the take's reference
        //    reads below the bottom of the slot table once the pad is low:
        //    key + N - X + e under zero.  It has to wrap down a period the
        //    way an index past the top wraps up, so the step moves by the
        //    same interval as its neighbour (audit 2026-09-24: a step due at
        //    258 sounded at 2979, read from below the tables).  The steps sit
        //    a thousand units up, so seven degrees down stays off the floor
        //    in images without the pitch offset too.
        setup(2,false,1); w(0x6160,2,1500); w(0x6162,2,1540); w(0x6600,1,0); w(0x6601,1,0); w(0x6091,1,0);
        command(1); presetSwitch(0,0);
        externalBeat(); sound(); long up0=r(S+0x352,2);
        externalBeat(); sound(); long up1=r(S+0x352,2);
        check("the fixture has room seven degrees down: "+up0+", "+up1,up0>=600&&up1>=600);
        setup(2,false,1); w(0x6160,2,1500); w(0x6162,2,1540); w(0x6600,1,7); w(0x6601,1,0); w(0x6091,1,7);
        command(1); presetSwitch(0,0);
        check("the fixture reaches an index under zero: step 1's key is "+r(0x61ee+1,1),r(0x61ee+1,1)<7);
        externalBeat(); sound(); long down0=r(S+0x352,2);
        externalBeat(); sound(); long down1=r(S+0x352,2);
        // Each step moves by its own interval, read off the slot table: on an
        // unequal scale seven degrees down from two keys are two intervals.
        int n=(int)(r(0x60fa,2)&255), k0=(int)r(0x61ee,1), k1=(int)r(0x61ee+1,1);
        long move0=(wrappedEntry(k0+n)-wrappedEntry(k0+7))-(wrappedEntry(k0+n)-wrappedEntry(k0));
        long move1=(wrappedEntry(k1+n-7)-wrappedEntry(k1))-(wrappedEntry(k1+n)-wrappedEntry(k1));
        check("a step under the take's reference wraps down a period: step 0 "
              +up0+" -> "+down0+" (table says "+move0+"), step 1 "+up1+" -> "+down1+" (table says "+move1+")",
              down0<up0 && down1<up1 && Math.abs(down0-up0-move0)<=1 && Math.abs(down1-up1-move1)<=1);
        // 7. A step's MIDI note carries N + e - X at the arp's word, which is
        //    negative here: the note goes down by the degrees the CV did,
        //    and never under zero.  Audit 038711a moved that term to the
        //    arp's word from midi_transpose, where a key's own press took it
        //    as well: the press names the key's own note now, and the lift
        //    reads back what the press froze.  The step sits at its key's own
        //    entry, so no period of it enters.
        long arpWord=r(0x80002428L,4);
        if(arpWord==ARP_NOTE) {
            w(0x6158,1,2); w(0x33c5,1,0); w(0x6503,1,1); w(0x6601,1,0); w(0x60fa,2,0xa000); w(0x62fe,1,0);
            w(0x6162,2,wrappedEntry(9));
            e.writeRegister("R12",9); long plain=call(0x800057a8L);
            w(0x6091,1,7);
            e.writeRegister("R12",9); long step=call(ARP_NOTE);
            check("a step seven degrees under the reference sends its note seven lower: "+plain+" -> "+step,step==plain-7);
            w(0x6091,1,plain+5);
            e.writeRegister("R12",9); step=call(ARP_NOTE);
            check("and never a note under zero: "+(int)step,step==0);
            w(0x6091,1,7); w(S+0x2e1,1,0xff);
            e.writeRegister("R12",9); long pressed=call(0x8001e748L);
            w(S+0x2e1,1,pressed);
            e.writeRegister("R12",9); long lifted=call(0x8001e744L);
            check("a key pressed over that step names its own note, and its lift the same: "+pressed+" and "+lifted,
                  pressed==plain&&lifted==pressed);
            // And a shift past a byte: 200 degrees of jack name note 127 on
            // the press, and the lift has to read back the same - frozen as
            // a byte, 300 came back as 44.
            w(0x60fa,2,0xa0c8); w(S+0x2e1,1,0xff);
            e.writeRegister("R12",9); pressed=call(0x8001e748L);
            w(S+0x2e1,1,pressed);
            e.writeRegister("R12",9); lifted=call(0x8001e744L);
            check("a jack shift of 200 names note 127 on the press and on the lift: "+pressed+" and "+lifted,pressed==127&&lifted==127);
            // 8. The arp's word adds the periods a step was played away from
            //    the take's reference (audit 038711a, F08): a step stored a
            //    period over its key's entry names its note a period of the
            //    map's keys higher.
            w(0x60fa,2,0xa000); w(0x6091,1,7);
            w(0x6162,2,wrappedEntry(9)+periodUnits());
            e.writeRegister("R12",9); long over=call(ARP_NOTE);
            check("a step a period over the reference names the map's "+keysPerPeriod()+" keys higher: "+over,
                  over==Math.min(127,plain-7+keysPerPeriod()));
        }
        println("PASS preset voltage downstream: takes keep their intervals, "
                +"previews stay pinned, sounding notes follow the pad, and a step under the reference wraps");
    }
    void presetQuantize() throws Exception {
        setup(0,false,0);
        // The add-to-pitch switch in its middle position: state+0x342 zero
        // and state+0x343 not.  That is the only combination the factory adds
        // the preset voltage in - it branches on exactly these two at
        // 0x8000379c, taking the octave term whenever 0x342 is nonzero and
        // neither when both are zero.  Pad 1 active, so 0x613a is the live
        // store.
        w(S+0x342,1,0); w(S+0x343,1,1); w(S+0x344,4,1); w(S+0x2ef,1,0);
        // A build that forces transpose mode adds a constant period as well
        // (-484 with the toggle off the octave position); measure it, then
        // sit the fixture so the targets clear the factory's floor clamp at
        // 9 and its +-1 fix-ups at 0x1e0 and 0x78a.
        int k0=(int)presetTarget(1000,0)-1000;
        int base=485-k0;
        long rest=presetTarget(base,0);
        int[] stores={0,37,113,264,509,777,1023};
        if(quantized) {
            // 1.  Nothing reaches the pitch through the adder any more.  The
            //     whole transposition is a rotation of the key table, so the
            //     preset's own contribution to the sum is exactly zero - which
            //     is what preset_quantize returns.
            for(int store:stores)
                check("store "+store+" adds nothing to the pitch directly",
                      presetTarget(base,store)==rest);
            presetTarget(base,0);
            int n0=liveN(), d0=presetDegrees(0,12);
            // 2.  The live table is the slot's own, rotated by whole degrees.
            //     Twelve keys to the period: the factory temperament, which is
            //     what every slot carries in this image.
            int keys=12;
            for(int store:stores) {
                presetTarget(base,store);
                int n=liveN();
                // The preset's own contribution, measured against the same
                // baseline so whatever the jack adds cancels out.
                check("store "+store+" moves the shift by the degrees the cave computes",
                      n-n0==presetDegrees(store,keys)-d0);
                // And the table really is the slot rotated by that much -
                // absolute, against flash, not against a model of itself.
                for(int k=0;k<32;k++) {
                    int j=k+n, up=0;
                    while(j>31) { j-=keys; up+=PERIOD; }
                    check("store "+store+": key "+k+" plays degree "+j,
                          r(0x854+2*k,2)==slotEntry(j)+up);
                }
            }
            // 3.  The absolute one.  Everything above compares the table with
            //     a model of the same table, and a relative check passes on
            //     garbage - that cost a cycle on this very cave in 2026-09-06.
            //     Twelve degrees of a twelve-note scale must be exactly one
            //     period, measured off the image itself.
            check("twelve degrees is exactly one period",
                  slotEntry(12)-slotEntry(0)==PERIOD);
            // 4.  And the switch decides it.  In the octave position the
            //     factory never adds the preset, so nothing may rotate either,
            //     or the preset would transpose from a position that is not
            //     asking it to.
            w(S+0x342,1,1); w(S+0x343,1,0);
            presetTarget(base,1023);
            // n0 less the preset's own contribution is whatever the jack is
            // worth, and that is all that may survive the switch moving.
            check("the octave position drops the preset's degrees entirely",
                  liveN()==n0-d0);
            w(S+0x342,1,0); w(S+0x343,1,1);
        } else {
            // The factory's free add, untouched by any of this.  The soft
            // float truncates where Java rounds, so accept the model and the
            // model one under (store 25: 32 against 33).
            Set<Integer> offsets=new TreeSet<>();
            for(int store=0;store<=1023;store+=store<64?1:store<200?7:13) {
                int units=presetUnits(store);
                long got=presetTarget(base,store);
                check("free preset offset at store "+store+": got "+(got-rest)
                      +", expected "+units, got==rest+units||got==rest+units-1);
                offsets.add((int)(got-rest));
            }
            check("a free preset is not snapped to the table",offsets.size()>100);
            check("the top of the knob still reaches the same span",
                presetTarget(base,1023)-rest>=1300&&presetTarget(base,1023)-rest<=1352);
        }
        println("PASS preset voltage "+(quantized
            ?"rotates the key table by whole degrees, nothing added to the pitch"
            :"added unquantised"));
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
    // always a whole number of halves of the beat, the byte at 0x6152 says
    // which half the hit fell on, and the deadzone is the square reload.
    static final long GRID=0x8001ea00L;
    long gridReload(long beat) throws Exception {
        e.writeRegister("R12",beat); call(GRID); return r(S+0x38e,2);
    }
    // Hits by half of the beat over a run, at one knob setting: [0] is the
    // beat, [1] the half.  Also records the total halves stepped and whether
    // the grid held - a reload that is not a whole number of halves is how a
    // quarter or an eighth would show up.
    long gridTotal; boolean gridHeld, gridFollows;
    int[] gridHits(int knob,int hits,long beat) throws Exception {
        w(0x60e6,2,knob); w(0x6152,1,0);
        int[] at=new int[2]; int pos=0; gridTotal=0; gridHeld=true; gridFollows=true;
        for(int i=0;i<hits;i++) {
            long cd=gridReload(beat); int p=(int)r(0x6152,1);
            if(cd%(beat/2)!=0||cd<beat/2||cd>4*beat) { gridHeld=false; break; }
            int n=(int)(cd/(beat/2)); gridTotal+=n;
            if(p!=((pos+n)&1)) { gridFollows=false; break; }
            pos=p; at[p]++;
        }
        return at;
    }
    // Entering the lower half with a hit standing on the half.  Eight halves
    // of silence return to the phase they started from, so the miss limit has
    // to step past a position the law gives no hits at all, or it forces the
    // off-beat hit the law forbids.  Counts how often the limit was actually
    // reached, so a run that never exercised it fails instead of passing
    // quietly - the reason the first version of this suite could not see the
    // defect was a fixture property no assertion stated.
    int limitReached, longestHalves;
    int forcedHalves(int knob,int trials,long beat) throws Exception {
        w(0x60e6,2,knob); w(0x6153,1,0);
        int forced=0; limitReached=0; longestHalves=0;
        for(int i=0;i<trials;i++) {
            w(0x6152,1,1);
            long cd=gridReload(beat);
            // Whole halves even on an odd beat: the carry keeps the sum on
            // the grid, so the rounding recovers exactly what was stepped.
            int halves=(int)Math.round(cd*2.0/beat);
            if(halves>=8) limitReached++;
            if(halves>longestHalves) longestHalves=halves;
            if(r(0x6152,1)!=0||cd>0xfff) forced++;
        }
        return forced;
    }
    // Knob 2 as swing.  It replaces the same randomiser the quantised grid
    // does, on the same hook and the same output cell, and lengthens every
    // other step by as much as it shortens the one after - so the pair keeps
    // its total and the arpeggio stops being square without drifting in
    // tempo.  Shipped in every swing build and emulated by nothing until
    // now: get the pair byte or the clamp wrong and no suite noticed.
    static final long SWING=0x8001b100L;
    long swingReload(long step) throws Exception {
        e.writeRegister("R12",step); call(SWING); return r(S+0x38e,2);
    }
    void swingRhythm() throws Exception {
        fresh();
        check("the rhythm hook's pool word names the dispatcher, and knob 2's live byte says swing",
            r(0x80019d40L,4)==KBRHY&&r(0x6d2a,1)==2);
        long step=400;
        w(0x60e6,2,0x2f); w(0x6152,1,0);
        check("below the deadzone the step is the step itself",
            swingReload(step)==step&&r(0x6152,1)==0);
        w(0x60e6,2,0x2f); w(0x6152,1,1);
        check("and the deadzone leaves the pair byte where it found it",
            swingReload(step)==step&&r(0x6152,1)==1);
        long widest=0;
        for(int knob:new int[]{0x30,256,512,768,1023}) {
            w(0x60e6,2,knob); w(0x6152,1,0);
            long first=swingReload(step); long afterFirst=r(0x6152,1);
            long second=swingReload(step); long afterSecond=r(0x6152,1);
            check("knob "+knob+": the first of the pair is the long one: "+first,
                first>=step);
            check("knob "+knob+": the second is short by as much: "+second,
                first+second==2*step);
            check("knob "+knob+": the pair byte alternates",
                afterFirst==1&&afterSecond==0);
            widest=Math.max(widest,first-step);
        }
        // A third either way is the triplet feel the travel is cut for.
        check("full travel swings by about a third of a step: "+widest,
            widest*100>=step*32&&widest*100<=step*34);
        // Both of the randomiser's own limits, which swing inherits.
        w(0x60e6,2,1023); w(0x6152,1,0);
        long shortest=swingReload(10);  // long
        shortest=swingReload(10);       // then short, into the floor
        check("a short step cannot go under the randomiser's floor: "+shortest,
            shortest==8);
        w(0x60e6,2,1023); w(0x6152,1,0);
        check("and a long one cannot pass its ceiling",swingReload(0xfff)==0xfff);
        println("PASS knob 2 as swing: deadzone, alternating pair, kept total, both limits");
    }

    // Knob 2 as a bank of step patterns.  The mask says whether a step
    // sounds; 0x6150 says which step it is and wraps at that pattern's own
    // length; and because there is no shift-by-register here the mask walks
    // down to bit zero instead.  Both the walk and the wrap shipped in every
    // patterns build with nothing executing them.
    // The bank and its lengths as the configuration booted them, the
    // mirror's: a record's bank replaces the image's whole.
    static final long PATTERNS=0x8001b050L, BANK=0x69a8L, LENGTHS=0x6a28L;
    // The knob dispatchers (stage 2 phase B): the words name these, and the
    // live option bytes decide where they go.
    static final long KBSEL=0x8001fb80L, KBRHY=0x8001fbe0L;
    long patternMask(int i) { return bootedHalf(BANK+4L*i)|((long)bootedHalf(BANK+4L*i+2)<<16); }
    int patternLength(int i) { return bootedHalf(LENGTHS+2L*i); }
    // The bank entry as the gate should play it: 'x' for a step that sounds.
    String patternAt(int i) {
        StringBuilder out=new StringBuilder();
        for(int s=0;s<patternLength(i);s++)
            out.append(((patternMask(i)>>s)&1)!=0?'x':'.');
        return out.toString();
    }
    // One knob setting, walked from step zero until the counter comes back
    // to it.  Returns what sounded, in order.  walkSteps says the counter
    // moved one step at a time; walkLength is where it wrapped, and stays 0
    // if it had not wrapped after 40 steps - eight more than the widest mask
    // the bank can hold, so a gate wrapping at the mask's width instead of
    // the pattern's own does not simply run on unnoticed.
    //
    // Only the two of those are asserted, plus the played string against the
    // table: `played.length() == walkLength` would be a property of this
    // loop, which appends exactly once per step.
    int walkLength; boolean walkSteps;
    String patternWalk(int knob) throws Exception {
        w(0x60e6,2,knob); w(0x6150,2,0);
        StringBuilder out=new StringBuilder();
        walkLength=0; walkSteps=true;
        for(int i=0;i<40&&walkLength==0;i++) {
            int at=(int)r(0x6150,2);
            e.writeRegister("R12",4);
            long answer=(int)call(PATTERNS);
            int next=(int)r(0x6150,2);
            out.append(answer==-1?'.':'x');
            if(next==0) walkLength=at+1;
            else if(next!=at+1) { walkSteps=false; break; }
        }
        return out.toString();
    }
    void patternGate() throws Exception {
        // Keys held, or the real selector answers -1 to a hit and a hit
        // cannot be told from a rest.
        orderFixture(0,0,4,9,14);
        check("the factory selector pool names the dispatcher, and knob 2's live byte says patterns",
            r(0x80002420L,4)==KBSEL&&r(0x6d2a,1)==3);
        check("and the sequencer reaches the same one",r(0x8001b434L,4)==KBSEL);
        // The gate hands a hit to the real selector through its own pool
        // word; with nothing held that selector answers -1 too, and a hit
        // could not be told from a rest.
        e.writeRegister("R12",4);
        check("three keys are held, so a hit answers with one of them",
            r(S+0x21a,1)==3&&(int)call(r(0x8001b0fcL,4))!=-1);
        List<String> reached=new ArrayList<>();
        for(int knob=0;knob<=1023;knob+=8) {
            String played=patternWalk(knob);
            check("knob "+knob+": the step counter moves one step at a time,"
                +" played "+played,walkSteps);
            check("knob "+knob+": and comes back to zero inside 40 steps,"
                +" so it wraps at a pattern length and not at the mask's"
                +" width: played "+played,walkLength>0);
            if(!reached.contains(played)) reached.add(played);
        }
        check("the knob reaches more than one pattern: "+reached,reached.size()>1);
        // Both halves at once: the string is one character per step up to
        // the wrap, so equality pins the mask walk AND the length the wrap
        // used, against the two tables the image carries.
        for(int i=0;i<reached.size();i++)
            check("pattern "+i+" plays the mask and length the image carries: "
                +reached.get(i)+" against "+patternAt(i),
                reached.get(i).equals(patternAt(i)));
        // A rest must answer -1 and leave the note sequence alone: the
        // arpeggio plays slowly through a sparse fill rather than skipping.
        int rest=-1;
        for(int i=0;i<reached.size()&&rest<0;i++)
            if(reached.get(i).indexOf('.')>=0) rest=i;
        check("some pattern in the bank has a rest to check",rest>=0);
        println("PASS knob 2 as patterns: "+reached.size()+" patterns walked, each"
            +" against the mask and length the image carries");
    }
    void quantizedRhythm() throws Exception {
        fresh();
        check("the rhythm hook's pool word names the dispatcher, and knob 2's live byte says quantized",
            r(0x80019d40L,4)==KBRHY&&r(0x6d2a,1)==1);
        long beat=400;
        w(0x60e6,2,0); w(0x6152,1,0);
        check("below the deadzone the reload is the beat itself",gridReload(beat)==beat&&r(0x6152,1)==0);
        w(0x6152,1,1);
        check("a hit standing on the half steps the other half back onto the beat",
            gridReload(beat)==beat/2&&r(0x6152,1)==0);
        w(0x6152,1,0x0b);
        check("only the low bit of the position is read",
            gridReload(beat)==beat/2&&r(0x6152,1)==0);
        w(0x60e6,2,0x2f); w(0x6152,1,0);
        check("the deadzone reaches the randomiser's own 0x30",gridReload(beat)==beat);
        // The bottom half of the travel only thins: beats drop, nothing is
        // added, so every hit is on a beat and a hit costs more than a beat.
        // 2000 hits at one eighth of the travel, then at the midpoint.
        int[] at=gridHits(128,2000,beat);
        check("low: every reload is a whole number of halves, one to eight",gridHeld);
        check("low: the position follows the halves stepped",gridFollows);
        check("low: nothing sounds off the beat: "+at[1],at[1]==0);
        check("low: one beat in sixteen drops, so a hit costs a little over a beat: "+gridTotal,
            gridTotal>4100&&gridTotal<4500);
        at=gridHits(512,2000,beat);
        check("halfway: the grid holds",gridHeld&&gridFollows);
        check("halfway: the half still never sounds: "+at[1],at[1]==0);
        check("halfway: a quarter of the beats have dropped, the thinnest the knob goes: "+gridTotal,
            gridTotal>5000&&gridTotal<5600);
        // Past the midpoint the half arrives, and goes on arriving: sparse
        // just above it, a fifth of the hits at three quarters of the travel,
        // and half of them at the end, where the density is what RATE set.
        at=gridHits(600,2000,beat);
        check("just past halfway: the half sounds, and rarely: "+at[1],at[1]>90&&at[1]<220);
        at=gridHits(768,2000,beat);
        check("three quarters: the half takes about a fifth of the hits: "+at[1],at[1]>330&&at[1]<550);
        check("three quarters: still thinner than one hit a beat: "+gridTotal,
            gridTotal>4600&&gridTotal<5100);
        at=gridHits(1023,2000,beat);
        check("full travel: the grid holds",gridHeld&&gridFollows);
        check("full travel: the beat keeps half the hits: "+at[0],at[0]>900&&at[0]<1100);
        check("full travel: the half takes the other half: "+at[1],at[1]>900&&at[1]<1100);
        check("full travel: the mean spacing is one beat again: "+gridTotal,
            gridTotal>3700&&gridTotal<4200);
        // Lowering the knob out of the top half leaves the last hit standing
        // on a half the law no longer allows.  The next hit must come back to
        // the beat however long the silence runs.
        int forced=forcedHalves(512,2000,beat);
        check("at the midpoint a hit standing on the half never forces another: "+forced,forced==0);
        check("and the miss limit was reached, so that was tested: "+limitReached,limitReached>0);
        forced=forcedHalves(513,2000,beat);
        check("nor just above it, where the half's share still rounds to zero: "+forced,forced==0);
        check("and the miss limit was reached there too: "+limitReached,limitReached>0);
        // A tempo where the reload ceiling bites, which beat=400 cannot show:
        // nine halves of it is 1800, nowhere near 0xfff.  Nine halves of a
        // 995-scan beat is 4477 and does not fit, so the interval is
        // shortened - and shortening moves the phase by one half, which is
        // how the forbidden hit came back after the selection loop alone was
        // guarded.  995 scans is a real tempo, not a round number: RATE 35
        // through the factory's own period routine.
        forced=forcedHalves(512,4000,995);
        check("a beat whose longest intervals will not fit still forces none: "+forced,forced==0);
        check("and they were shortened to seven halves, never eight: "+longestHalves,longestHalves==7);
        // A beat that is not even: the remainder is carried, so a run of hits
        // tracks the grid to within a scan instead of running early by the
        // dropped fraction every reload.
        w(0x60e6,2,1023); w(0x6152,1,0); w(0x6153,1,0);
        long elapsed=0, stepped=0; int pos=0; boolean walks=true;
        for(int i=0;i<1000;i++) {
            long cd=gridReload(401); int p=(int)r(0x6152,1);
            long n=Math.round(cd*2.0/401);   // the halves that reload covers
            if(p!=((pos+n)&1)) walks=false;
            elapsed+=cd; stepped+=n; pos=p;
        }
        check("odd beat: the position follows the halves stepped",walks);
        check("odd beat: a thousand hits stay within a scan of the grid: "+elapsed+" for "+stepped+" halves",
            Math.abs(elapsed-Math.round(stepped*401.0/2))<=1);
        check("the carry is a remainder",r(0x6153,1)<2);
        // The randomiser's own limits, kept as a grid: too short asks for
        // another half, too long for one fewer, and the position byte moves
        // with the halves actually stepped.
        w(0x60e6,2,0); w(0x6152,1,1); w(0x6153,1,0);
        check("a reload under eight scans steps more halves, and says so",gridReload(4)==8&&r(0x6152,1)==1);
        w(0x6152,1,0); w(0x6153,1,0);
        long slow=gridReload(5000);
        check("a reload over 0xfff steps fewer halves, and says so",
            slow<=0xfff&&slow==5000/2&&r(0x6152,1)==1);
        w(0x6152,1,0); w(0x6153,1,0);
        check("a beat of zero cannot spin: the limit is the reload",gridReload(0)==8);
        steps=0; gridReload(400); println("SCAN BUDGET quantized reload "+steps+" instructions");
        println("PASS quantized rhythm: half grid, the bottom half thinning and the top half filling, position, deadzone, odd beats and the limits as grid");
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
    // Criterion 3 (2026-09-23): a setting change must not carry state over.
    // One session with every option on, dirtying what each of them owns -
    // latched notes, a take recorded and played, the divider fed edges,
    // pressure in the cache, the jack up, a preset in the middle position,
    // the knobs moved - then the restart that turns every option off, with
    // custom RAM kept as SRAM keeps it, against a cold boot with the same
    // record.  Whatever differs is state an option left behind for the
    // others to read; each run in the allowlist below is one that is read
    // only by the option that wrote it, and says why.
    // The period as a setting (2026-09-23): the panel octave pads and the
    // stored-octave (trn) arithmetic step what number cell 10 holds.  The
    // factory nudges a transpose by one at two thresholds, hence the slack.
    void periodCell() throws Exception {
        setup(0,false,0); latchFixture();
        for(int p:new int[]{484,767}) {
            w(0x6814,2,p);
            octavePad(1); sound(); long zero=livePad();
            octavePad(2); sound(); long up=livePad()-zero;
            octavePad(0); sound(); long down=livePad()-zero;
            octavePad(3); sound(); long up2=livePad()-zero;
            check("the octave pads step the period cell at "+p+": "+down+", "+up+", "+up2,
                Math.abs(down+p)<=1&&Math.abs(up-p)<=1&&Math.abs(up2-2*p)<=1);
            octavePad(1); w(S+0x6a,1,1); w(S+0x6b,1,3); sound(); long trn3=livePad();
            w(S+0x6b,1,4); sound(); long trn=livePad()-trn3;
            w(S+0x6a,1,0); w(S+0x6b,1,0); sound();
            check("the stored octave steps the period cell at "+p+": "+trn,Math.abs(trn-p)<=1);
        }
        w(0x6814,2,PERIOD);
        println("PASS the period cell: the panel octaves and the stored octave step what cell 10 holds");
    }
    static final long RES_LO=0x6000, RES_HI=0x7000;
    // The allowlist: each run is state read only by the option that wrote
    // it, or rewritten before anything reads it.  What is NOT here is what
    // option_boot and option_boot_state clear: the latch's stamps, term and
    // maps, the blend's offset and re-base history, the vibrato's cells and
    // the jack transposer's state word.
    static final long[][] RESIDUE_OK={
        {0x6000,0x6021}, // arp press-order list: the walk re-checks the held flags before it returns a key, the append moves a key it finds before adding it, and knob 1 factory never reads it
        {0x604e,0x6050}, // jack transposer: the slot's own pitch of the key being recorded, parked between the recorder's two halves and written before it is read
        {0x608e,0x608f}, // latch-position mirror: housekeeping rewrites it from the switch and the latch's byte every scan; the blend is its only reader
        {0x609c,0x609e}, // held pitch for a claimed beat: read under a claim, and clock_init zeroes the claim
        {0x60a0,0x60a2}, // the live transpose: republished from state+0x350 by transpose_capture on the first pass, before any press reads it
        {0x60dc,0x60e0}, // claimed beat's gate target: the same
        {0x60e6,0x60e8}, // arp knob 2 latch: rewritten by the first housekeeping pass; the role caves a factory knob bypasses are its readers
        {0x60ea,0x60ec}, // arp knob 3 latch: the same
        {0x60ef,0x60f0}, // previous switch position: the latch-exit watch fires once on the first scan, clearing held flags that are already clear
        {0x60f0,0x60f4}, // knob 4 and knob 1 latches, as knob 2's; 0x60f3 the preset's degree count, republished by the first scan's transposer chain before any press
        {0x60fc,0x6100}, // jack bookkeeping: table entry 0 as last written and the key the refresh belongs to, which the first scan's rebuild rewrites
        {0x6100,0x613a}, // corrected-pressure cache: rebuilt for every key each pass with the fix on, bypassed with it off
        {0x613a,0x6142}, // preset voltage store: musical, kept in SRAM by a volatile build and restored from the ring by a persistent one
        {0x6160,0x61e1}, // the take's steps and their count: musical, the same
        {0x61e6,0x61e8}, // the clock's millisecond counter: free-running
        {0x61ee,0x622e}, // the take's rest keys: musical, the same
        {0x6300,0x6420}, // persistence's staged record: written by every capture before the commit reads it
        {0x6503,0x6504}, // the sequencer step sounding now: every reader asks the mode first, and persist_boot zeroes the mode
        {0x6540,0x657a}, // slot-indexed pressure weights: zeroed and rebuilt per scan by the blend
        {0x657e,0x657f}, // last scan's step count: the strip's own, read in WRITE
        {0x6600,0x6640}, // per-step preset degrees: playback's, behind the mode, and restored from the ring
    };
    static boolean residueAllowed(long a) { for(long[] r:RESIDUE_OK) if(a>=r[0]&&a<r[1]) return true; return false; }
    byte[] settingsRecord(boolean allOff) {
        // The image's own record, its options as built or every one off:
        // the mirror after a boot without a record is the payload, the
        // marker is what the first-use initialiser left at 0x602a, and the
        // period is 484 (every controls variant repeats at the octave).
        // The knobs' "off" is the factory role, not zero.
        byte[] rec=new byte[0x2a8];
        byte[] payload=traced(0x6800,0x288-0x20);
        System.arraycopy(payload,0,rec,0x20,payload.length);
        int[] off={0,2,4,1,2,0,0,0,0,0,0,0};
        if(allOff) for(int c=16;c<28;c++) { rec[0x20+2*c]=0; rec[0x21+2*c]=(byte)off[c-16]; }
        rec[0]=0x32; rec[1]=0x31; rec[2]=0x38; rec[3]=0x53;     // "218S"
        rec[5]=2; rec[6]=0x02; rec[7]=(byte)0x98; rec[11]=1;   // layout 2, payload 0x298, generation 1
        int marker=(int)r(0x602a,2); rec[0x10]=(byte)(marker>>8); rec[0x11]=(byte)marker;
        rec[0x12]=(byte)(PERIOD>>8); rec[0x13]=(byte)PERIOD;
        java.util.zip.CRC32 c=new java.util.zip.CRC32(); c.update(rec,4,8); c.update(rec,0x10,0x2a8-0x10);
        long v=c.getValue(); for(int i=0;i<4;i++) rec[12+i]=(byte)(v>>>(24-8*i));
        return rec;
    }
    void plantSettings(byte[] rec) {
        byte[] ff=new byte[0x800]; Arrays.fill(ff,(byte)255);
        e.writeMemory(toAddr(0x8003d000L),ff); e.writeMemory(toAddr(0x8003d800L),ff);
        e.writeMemory(toAddr(0x8003d000L),rec);
    }
    // The watchdog restart as the chip comes back from it: the C runtime
    // zeroes the factory's RAM and copies its initialised data again, the
    // peripherals are as a reset leaves them, and custom SRAM above 0x6000
    // holds what the session left.  Then the startup hook, and the shared
    // first-use bootstrap as the first handler would call it - a no-op
    // when the marker survived, which is asserted first.
    void warmRestart() throws Exception {
        byte[] kept=e.readMemory(toAddr(RES_LO),(int)(RES_HI-RES_LO));
        long marker=r(0x602a,2);
        e.writeMemory(toAddr(0),new byte[0x8000]);
        e.writeMemory(toAddr(8),e.readMemory(toAddr(0x80015d28L),0x2ecc)); w(0x2ed4,4,0xffffffffL);
        for(int i=0;i<=12;i++) e.writeRegister("R"+i,0);
        e.writeRegister("SR",0); for(String f:new String[]{"N","Z","V","C"}) e.writeRegister(f,0);
        w(0x29cc,4,25000000); w(S+0x20c,4,1); time(0);
        w(0xffff1060L,4,0); w(0xffff10d0L,4,0);
        w(0xffff2404L,4,0); w(0xffff2410L,4,0x202);
        e.writeMemory(toAddr(RES_LO),kept);
        boot();
        check("the first-use marker survives the restart",r(0x602a,2)==marker);
        call(0x8001ab60L);
    }
    void residue() throws Exception { residueRun(true); residueRun(false); }
    // What the remap makes of a raw pitch, from the image's own table: add
    // 120 (and the vibrato), hold at entry 0, twelve entries to the 484-unit
    // octave, clamp at entry 77, interpolate with the firmware's rounding.
    long remapModel(long d) {
        d=Math.max(0,d+0x78);
        long idx=d*12/484, rem=d*12%484;
        if(idx>0x4d) { idx=0x4d; rem=0x1e3; }
        long lo=r(0x6840+2*idx,2), hi=r(0x6840+2*idx+2,2);
        return lo+(rem*(hi-lo)+0xf2)/484;
    }
    long bent(int target,int bend) throws Exception {
        w(S+0x352,2,target&0xffff); w(S+0x216,2,bend&0xffff);
        for(int i=0;i<8;i++) pitch();
        return r(S+0x358,2);
    }
    // A bend reaches under the bottom key, down to the pitch table's first
    // entry, and no further.  The remap adds 120 before it reads the table,
    // so the bottom key at the lowest octave position sits three entries
    // over entry 0 - the 0 V pitch, with the offset - and the scan's clamp
    // at 0 used to stop every bend at the bottom key: bent fully down, key 0
    // stayed on its own pitch.  Driven through the real scan (glide, bend
    // add, clamp, store hook, dispatcher, blend shim, remap) with the target
    // and the bend staged and the vibrato at rest.
    void bendUnderTheBottomKey() throws Exception {
        setup(0,false,0); command(2);
        w(S+0x310,2,0); w(S+0x306,2,0); controlScan();
        long entry0=r(0x6840,2), entry3=r(0x6840+6,2);
        check("the image's table has room under the bottom key: "+entry0+" < "+entry3,entry0<entry3);
        int[][] cases={{0,0},{0,-1},{0,-40},{0,-119},{0,-120},{0,-121},{0,-480},
                       {40,-80},{40,-200},{121,-241},{485,-120},{485,-480},{1000,-480}};
        for(int[] c:cases) {
            long dac=bent(c[0],c[1]);
            long raw=Math.max(-0x78,c[0]+c[1]);
            check("vibrato at rest under the bend fixture",r(0x6028,2)==0);
            // The scan carries glide plus bend unclamped, and the one clamp
            // holds it after the blend's offset (the clamp-chain scan,
            // 2026-09-28): the DAC below is the remap of the clamped sum.
            check("target "+c[0]+" bent "+c[1]+": the scan carries "+(c[0]+c[1])+" unclamped, read "+(short)r(0x3210,2),
                (short)r(0x3210,2)==c[0]+c[1]);
            check("target "+c[0]+" bent "+c[1]+": DAC "+dac+", the remap's "+remapModel(raw),
                dac==remapModel(raw));
        }
        check("bent fully down, the bottom key reaches entry 0: "+bent(0,-480),bent(0,-480)==entry0);
        check("and that is under the bottom key's own pitch",bent(0,-480)<bent(0,0));
        // The remap's own floor, entered past its per-scan chain (the
        // clock's bare entry) so the vibrato can be set: at the scan's floor
        // the vibrato takes d 13 under entry 0, and the divide is unsigned.
        for(int vib:new int[]{-13,-1,0,13}) {
            w(0x6028,2,vib&0xffff); e.writeRegister("R12",(-0x78)&0xffffffffL);
            call(0x8001c0e0L);
            check("vibrato "+vib+" at the floor: DAC "+r(S+0x358,2)+", the remap's "+remapModel(-0x78+vib),
                r(S+0x358,2)==remapModel(-0x78+vib));
        }
        w(0x6028,2,0);
        // A 208c table - pitch_offset off - lays the curve three entries
        // later and leaves 0 V under the bottom key: bent or not, it reads 0.
        long[] table=new long[79];
        for(int i=0;i<79;i++) table[i]=r(0x6840+2*i,2);
        for(int i=0;i<79;i++) w(0x6840+2*i,2,i<3?0:table[i-3]);
        check("a 208c table: a bend under the bottom key stays at 0 V",
            bent(0,-480)==0&&bent(0,-40)==0&&bent(0,0)==remapModel(0));
        for(int i=0;i<79;i++) w(0x6840+2*i,2,table[i]);
        w(S+0x216,2,0); pitch();
        println("PASS a bend reaches under the bottom key to the table's entry 0, and holds there");
    }
    // Both directions: a session with every option on restarting into
    // every option off, and a session with every option off restarting
    // into every option on.
    void residueRun(boolean onToOff) throws Exception {
        byte[] record, session;
        if(onToOff) {
            setup(4,true,1);                       // every option on, a take of four steps, the arp in latch
            record=settingsRecord(true);
            // The session.
            touchOn(9); touchOn(4); w(0x6100+18,2,600); w(0x6100+8,2,300); sound(); sound();
            touchOff(4); sound();                  // a latched key, a held key with pressure
            octavePad(2); sound(); octavePad(1); sound();
            w(S+0x342,1,0); w(S+0x343,1,1); w(S+0x2ef,1,0); w(0x613a,2,900); sound();   // the preset in the middle position
            w(S+0x2f0,2,2*(int)r(0x680a,2)); sound(); sound();                          // the jack two periods up
            w(S+0x30a,2,700); w(S+0x30c,2,650); w(S+0x30e,2,600); w(S+0x310,2,500); sound(); sound();  // the knobs moved
            append(5); append(7); command(0); command(1); sound(); sound();             // two more steps, the take closed and played
            for(int i=0;i<3;i++) { externalBeat(); sound(); }                          // edges into the divider
            check("the session left a take playing: mode="+r(0x6158,1)+" steps="+r(0x61e0,1),r(0x6158,1)==2&&r(0x61e0,1)>=6);
        } else {
            fresh(); record=settingsRecord(false); session=settingsRecord(true);
            plantSettings(session); cold();
            check("the session boots with every option off",r(0x6d28,1)==0&&r(0x6d2d,1)==0&&r(0x6d2a,1)==4);
            // The same gestures through the factory's own paths: no chord
            // arms, no latch holds, the divider never sees an edge of ours.
            touchOn(9); touchOn(4); w(0x6100+18,2,600); w(0x6100+8,2,300); sound(); sound();
            touchOff(4); sound();
            octavePad(2); sound(); octavePad(1); sound();
            w(S+0x342,1,0); w(S+0x343,1,1); w(S+0x2ef,1,0); w(0x613a,2,900); sound();
            w(S+0x2f0,2,2*(int)r(0x680a,2)); sound(); sound();
            w(S+0x30a,2,700); w(S+0x30c,2,650); w(S+0x30e,2,600); w(S+0x310,2,500); sound(); sound();
            w(0x46f3,1,2); for(int i=0;i<320;i++) scan();
            w(0x46f0,1,2); scan(); w(0x46f0,1,0); w(0x46f3,1,0); scan();
            for(int i=0;i<3;i++) {
                long t=1000+20*i;
                time(t-2); w(0xffff1060L,4,0); w(0xffff10d0L,4,32); call(0x800072e4L,0x80007328L); w(0xffff10d0L,4,0);
                time(t); w(0xffff1060L,4,32); w(0xffff10d0L,4,32); call(0x800072e4L,0x80007328L); w(0xffff10d0L,4,0);
                sound();
            }
            check("nothing armed with the sequencer off: mode="+r(0x6158,1),r(0x6158,1)==0);
        }
        // The restart into the other record, custom RAM kept.
        plantSettings(record);
        warmRestart();
        check("the restart booted the "+(onToOff?"all-off":"as-built")+" record",
            onToOff?(r(0x6d28,1)==0&&r(0x6d2d,1)==0&&r(0x6d2a,1)==4):(r(0x6d28,1)==1&&r(0x6d2d,1)==1));
        byte[] warmRam=e.readMemory(toAddr(RES_LO),(int)(RES_HI-RES_LO));
        // The cold boot with the same record: a new machine, the record in
        // its slot and the same musical persistence ring in flash before the
        // first boot, so a take restored from the ring is on both sides.
        byte[] ring=e.readMemory(toAddr(BASE),0x1000);
        fresh(); plantSettings(record); e.writeMemory(toAddr(BASE),ring); cold();
        byte[] coldRam=e.readMemory(toAddr(RES_LO),(int)(RES_HI-RES_LO));
        // Runs of differing bytes, each with what the two boots hold; the
        // ones outside the allowlist are the residue.
        StringBuilder diff=new StringBuilder(); int n=0, runs=0, allowed=0, residue=0;
        for(int i=0;i<warmRam.length;i++) {
            if(warmRam[i]==coldRam[i]) continue;
            int j=i; while(j<warmRam.length&&warmRam[j]!=coldRam[j]) j++;
            n+=j-i; runs++;
            boolean ok=true; for(int k=i;k<j;k++) if(!residueAllowed(RES_LO+k)) { ok=false; residue++; } else allowed++;
            if(runs<=60) {
                diff.append(String.format("\n  %s %04x..%04x (%d):",ok?"ok     ":"RESIDUE",RES_LO+i,RES_LO+j-1,j-i));
                for(int k=i;k<Math.min(j,i+8);k++) diff.append(String.format(" %02x/%02x",warmRam[k]&255,coldRam[k]&255));
                if(j-i>8) diff.append(" ...");
            }
            i=j-1;
        }
        String dir=onToOff?"on -> off":"off -> on";
        println("RESIDUE "+dir+": "+n+" byte(s) in "+runs+" run(s) differ after the warm restart (warm/cold), "+allowed+" allowlisted, "+residue+" residue:"+diff);
        check("a warm restart "+dir+" leaves the same custom RAM as a cold boot with the same record, bar the allowlist: "+residue+" byte(s) of residue",residue==0);
    }
    // ---- Calibration mode (NRPN 0x3f05) ---------------------------------
    // The page's pitch sweep turns it on and plays MIDI notes; the pitch
    // output is then the settings mirror's pitch-table entry for the last
    // note, exactly, with nothing the instrument would add.  Driven through
    // the parser (the NRPN), the dispatcher's own note-on case and its pool
    // word (the notes), the real scan (musicalScan) and the touch scan's
    // contact word (a key press).  remapIn is what the remap was handed on
    // the scan just run, so normal() is the pitch the scan itself computed:
    // what the instrument plays with the mode off.
    static final long CAL=0x6a6b, CAL_STAMP=0x6d40, CAL_ENTRY=0x6d44, CAL_MS=0x2efc;
    long remapIn=Long.MIN_VALUE, fastIn=Long.MIN_VALUE, blendOut=Long.MIN_VALUE;
    // Set, the remap returns as it is entered: a check that reads only what
    // it was handed leaves the pitch curve and the tuning unread.
    boolean skipRemap;
    // One USB-MIDI packet into the receive ring, then the factory parser
    // over it, as SettingsRegression feeds them.
    void usb(int status,int d1,int d2) throws Exception {
        long idx=r(0x34b0+0x84,4);
        w(0x34b0+4+idx,1,status); w(0x34b0+5+idx,1,d1); w(0x34b0+6+idx,1,d2); w(0x34b0+7+idx,1,0);
        w(0x34b0+0x84,4,idx+4>0x7f?0:idx+4);
        call(0x8000831cL);
    }
    void nrpn(int param,int value) throws Exception {
        usb(0xbf,99,param>>7); usb(0xbf,98,param&0x7f); usb(0xbf,6,value>>7); usb(0xbf,38,value&0x7f);
    }
    int channel() { return (int)r(S+0x2e7,1); }
    // Event 7's case in the dispatcher, the event where the case reads it
    // (R7-0x20: note, velocity, channel), through its MCALL on 0x80005238.
    void noteOn(int note) throws Exception { noteOn(note,channel()); }
    void noteOn(int note,int ch) throws Exception {
        w(0x7600-0x20,1,note); w(0x7600-0x1f,1,100); w(0x7600-0x1e,1,ch);
        call(0x80004e7eL,0x80004eb8L);
    }
    // A key's contact as the touch scan reports it (0x8000550e): the key in
    // R12, through the pool word.  touchOn() calls the handler directly.
    void contact(int key) throws Exception { e.writeRegister("R12",key); call(r(0x80005650L,4)); }
    long mirror(int entry) { return r(0x6840+2*entry,2); }
    long dac() throws Exception { remapIn=Long.MIN_VALUE; musicalScan(); return r(S+0x358,2); }
    long normal() throws Exception {
        check("the scan ran the remap",remapIn!=Long.MIN_VALUE);
        return remapModel(remapIn+(short)r(0x6028,2));
    }
    void calibrate() throws Exception { w(CAL_MS,4,1000); nrpn(0x3f05,0x2a2a); check("calibration mode on",r(CAL,1)==1); }
    // One scan in the mode: the DAC and the last-sent mirror are the entry.
    void plays(String what,int entry) throws Exception {
        long d=dac();
        check(what+": DAC "+d+", last sent "+r(0x3212,2)+", entry "+entry+" is "+mirror(entry),
            d==mirror(entry)&&r(0x3212,2)==mirror(entry));
    }
    void calibrationCommand() throws Exception {
        setup(0,false,0); command(2);
        check("calibration mode is off after a boot",r(CAL,1)==0);
        nrpn(0x3f05,0x1111); nrpn(0x3f05,0x2a2b); nrpn(0x3f05,0);
        check("0x3f05 without the commit's key leaves it off",r(CAL,1)==0);
        w(CAL_MS,4,20000); nrpn(0x3f05,0x2a2a);
        check("0x3f05 with the key turns it on, at entry 3, its five seconds counted from now",
            r(CAL,1)==1&&r(CAL_ENTRY,1)==3&&r(CAL_STAMP,4)==20000);
        plays("before any note, entry 3",3);
        noteOn(40); plays("note 40",19);
        w(CAL_MS,4,20100); nrpn(0x3f05,0x2a2a);
        check("the key again keeps the mode and the note's entry, and restarts the five seconds",
            r(CAL,1)==1&&r(CAL_ENTRY,1)==19&&r(CAL_STAMP,4)==20100);
        nrpn(0x3f06,0x2a2a);
        check("another command leaves the mode alone",r(CAL,1)==1);
        nrpn(0x3f05,0x2a2b);
        check("any other value turns it off",r(CAL,1)==0);
        long d=dac();
        check("and the next scan plays its own pitch again: DAC "+d+", the remap's "+normal(),d==normal());
        println("PASS calibration mode: 0x3f05 with the key turns it on at entry 3, the key again keeps the note, any other value turns it off");
    }
    void calibrationPitch() throws Exception {
        setup(0,false,0); command(2); calibrate();
        int[][] ends={{21,0},{99,78},{0,0},{20,0},{100,78},{127,78}};
        for(int[] n:ends) { noteOn(n[0]); plays("note "+n[0]+" is entry "+n[1],n[1]); }
        int wrong=0;
        for(int i=0;i<=78;i++) { noteOn(21+i); if(dac()!=mirror(i)||r(CAL_ENTRY,1)!=i) wrong++; }
        check("notes 21..99 play entries 0..78, every one exactly: "+wrong+" wrong",wrong==0);
        noteOn(40); noteOn(50,(channel()+1)&15);
        plays("a note on another channel leaves the entry",19);
        // What the instrument would add, one thing at a time, on a key the
        // keyboard has - note 40 is key 16 - so the scan's own pitch comes
        // off the key table.  Each moves the scan's own pitch (normal()) and
        // leaves the DAC on the entry.
        int n=40, entry=19;
        w(S+0x342,1,1); w(S+0x343,1,0); octavePad(0); noteOn(n); dac(); long own=normal();
        octavePad(1); noteOn(n); long d=dac(), with=normal();
        check("pad 2 with the add-to-pitch switch on the octaves moves the scan's own pitch: "+own+" -> "+with,with!=own);
        check("and the DAC plays the entry exactly: "+d+" = "+mirror(entry),d==mirror(entry)&&r(0x3212,2)==mirror(entry));
        octavePad(0);
        // The preset rotates the key table on the scan, so it has one before
        // the note reads the table.
        w(S+0x342,1,0); w(S+0x343,1,1); w(S+0x2ef,1,0); w(0x613a,2,0); dac(); noteOn(n); dac(); own=normal();
        w(0x613a,2,900); dac(); dac(); noteOn(n); d=dac(); with=normal();
        check("the preset up, the switch in the middle, moves the scan's own pitch: "+own+" -> "+with,with!=own);
        check("and the DAC plays the entry exactly: "+d,d==mirror(entry));
        w(0x613a,2,0); w(S+0x342,1,1); w(S+0x343,1,0);
        noteOn(n); dac(); own=normal();
        w(S+0x216,2,(-200)&0xffff); noteOn(n); d=dac(); with=normal();
        check("the strip bent down moves the scan's own pitch: "+own+" -> "+with,with!=own);
        check("and the DAC plays the entry exactly: "+d,d==mirror(entry));
        w(S+0x216,2,0);
        if(r(0x6d32,1)!=0) {
            // The jack one degree up: N=1.
            cv(0); noteOn(n); dac(); own=normal();
            int degree=((int)r(0x680a,2)+6)/12;
            cv(degree);
            check("the jack stands one degree up: "+(r(0x60fa,2)&255),(r(0x60fa,2)&255)==1);
            noteOn(n); d=dac(); with=normal();
            check("the jack transposing one degree moves the scan's own pitch: "+own+" -> "+with,with!=own);
            check("and the DAC plays the entry exactly: "+d,d==mirror(entry));
            cv(0);
        }
        if(r(0x6d33,1)!=0) {
            // Each tuning slot, applied by the per-scan applier.  A slot that
            // puts key 16 somewhere else must move the scan's own pitch.
            int moved=0;
            w(0x6090,1,0); w(0x60e4,2,0); controlScan(); noteOn(n); dac(); own=normal();
            long key0=r(0x854+2*16,2);
            for(int s=0;s<3;s++) {
                w(0x6090,1,s); w(0x60e4,2,0); controlScan();
                noteOn(n); d=dac(); with=normal();
                if(r(0x854+2*16,2)!=key0) {
                    moved++;
                    check("tuning slot "+s+" moves the scan's own pitch: "+own+" -> "+with,with!=own);
                }
                check("tuning slot "+s+": the DAC plays the entry exactly, "+d,d==mirror(entry));
            }
            w(0x6090,1,0); w(0x60e4,2,0); controlScan();
            println("CALIBRATION tuning slots: "+moved+" of 3 put key 16 elsewhere");
        }
        println("PASS calibration mode: entries 0..78, both ends clamped, exact over the octave pads, the preset, the strip"
            +(r(0x6d32,1)!=0?", the jack":"")+(r(0x6d33,1)!=0?", the tuning slots":""));
    }
    void calibrationSilence() throws Exception {
        // The arp on the external clock, three keys held.
        orderFixture(0,0,4,14,9);
        externalBeat(); externalBeat();
        check("the arp steps before the mode: "+outputs+" pulses",outputs>0);
        calibrate();
        long key=r(S+0x34d,1); int outs=outputs, wrong=0;
        for(int i=0;i<8;i++) { externalBeat(); if(r(S+0x358,2)!=mirror(3)) wrong++; }
        check("in the mode the arp makes no step on the clock: key "+r(S+0x34d,1)+" was "+key+", "+(outputs-outs)+" pulses",
            r(S+0x34d,1)==key&&outputs==outs);
        check("and every beat's scan plays the entry: "+wrong+" did not",wrong==0);
        nrpn(0x3f05,0);
        for(int i=0;i<3;i++) externalBeat();
        check("the arp steps again once the mode ends",outputs>outs);
        // And on its own tempo, through the DAC flush's step.
        setup(0,true,1); command(1); command(1); w(S+0x308,2,1000);
        w(S+0x21a,1,3);
        for(int k:new int[]{4,14,9}) { w(S+0x21b+k,1,1); e.writeRegister("R12",k); call(0x8001a020L); }
        w(S+0x34d,1,4);
        internalTicks(300);
        int before=outputs;
        check("the internal arp steps before the mode: "+before+" pulses",before>0);
        calibrate();
        internalTicks(300);
        check("and not in it: "+(outputs-before)+" pulses",outputs==before&&r(S+0x358,2)==mirror(3));
        if(seq) {
            // A take playing.
            setup(4,false,0); command(1);
            externalBeat(); externalBeat();
            int steps=selected.size();
            check("the take plays before the mode: mode "+r(0x6158,1)+", "+steps+" steps",r(0x6158,1)==2&&steps>0);
            calibrate();
            outs=outputs; wrong=0;
            for(int i=0;i<8;i++) { externalBeat(); if(r(S+0x358,2)!=mirror(3)) wrong++; }
            check("in the mode the take makes no step: "+(selected.size()-steps)+" steps, "+(outputs-outs)+" pulses",
                selected.size()==steps&&outputs==outs);
            check("it is still playing, and every scan plays the entry: "+wrong+" did not",r(0x6158,1)==2&&wrong==0);
            nrpn(0x3f05,0);
            for(int i=0;i<3;i++) externalBeat();
            check("the take steps again once the mode ends",selected.size()>steps);
            // Recording: a MIDI note records nothing; a key press ends the
            // mode first and then records as ever.
            setup(0,false,0);
            calibrate(); noteOn(40); sound(); sound();
            check("in WRITE a MIDI note in the mode records nothing",r(0x61e0,1)==0);
            contact(5); sound();
            check("a key press ends the mode and records its note",r(CAL,1)==0&&r(0x61e0,1)==1);
        }
        println("PASS calibration mode: the arp on either clock"+(seq?", a playing take and recording":"")+" silent while it is on, and back after");
    }
    void calibrationExits() throws Exception {
        // A key press.
        setup(0,false,0); command(2); calibrate();
        noteOn(40); plays("before the press",19);
        contact(9);
        check("a key press ends the mode",r(CAL,1)==0);
        check("and the handler has the press as ever: key 9 down",r(S+0x239+9,1)==1);
        long d=dac();
        check("the next scan plays its own pitch: "+d+" = "+normal(),d==normal());
        // Five seconds without a note-on, on the factory's millisecond count.
        setup(0,false,0); command(2);
        w(CAL_MS,4,100000); nrpn(0x3f05,0x2a2a);
        w(CAL_MS,4,104999); plays("4999 ms after the command",3);
        noteOn(40);
        w(CAL_MS,4,109998); plays("4999 ms after the last note-on",19);
        w(CAL_MS,4,109999); d=dac();
        check("5000 ms after it the scan ends the mode",r(CAL,1)==0);
        check("and plays its own pitch: "+d+" = "+normal(),d==normal());
        w(CAL_MS,4,200000); nrpn(0x3f05,0x2a2a);
        w(CAL_MS,4,204000); noteOn(50,(channel()+1)&15);
        w(CAL_MS,4,208999); plays("a note-on on another channel restarts the five seconds",3);
        w(CAL_MS,4,209000); dac();
        check("and they run out from it",r(CAL,1)==0);
        w(CAL_MS,4,0xfffff000L); nrpn(0x3f05,0x2a2a); noteOn(40);
        w(CAL_MS,4,0x387); plays("across the count's wrap, 4999 ms",19);
        w(CAL_MS,4,0x388); dac();
        check("and 5000 ms across it end the mode",r(CAL,1)==0);
        // A boot: the watchdog's restart, custom SRAM kept.
        setup(0,false,0); command(2); calibrate(); noteOn(40);
        warmRestart();
        check("the restart comes up with the mode off, the cells beside it kept",r(CAL,1)==0&&r(CAL_ENTRY,1)==19);
        d=dac();
        check("and plays its own pitch: "+d+" = "+normal(),d==normal());
        println("PASS calibration mode ends on a key press, five seconds without a note-on, and a restart; the next scan is the instrument's own");
    }
    void calibrationLiveEdit() throws Exception {
        setup(0,false,0); command(2); calibrate();
        int n=40, entry=19;
        // What the configuration booted: the image's own curve, or the
        // record's, which 0x3f01 reloads.
        long flash=bootedHalf(0x6840+2*entry);
        check("the mirror holds the booted entry "+entry+": "+mirror(entry)+" = "+flash,mirror(entry)==flash);
        noteOn(n); plays("note "+n,entry);
        long state=r(0x6a70,1), guard=r(0x60e4,2);
        int edit=(int)(flash>=0x800?flash-37:flash+37);
        nrpn(0x80+entry,edit);
        check("an NRPN write to parameter 0x"+Integer.toHexString(0x80+entry)+" lands in entry "+entry+" of the mirror itself: "+mirror(entry),
            mirror(entry)==edit);
        check("and moves nothing else: its neighbours, the commit state, the applier's guard",
            mirror(entry-1)==bootedHalf(0x6840+2*(entry-1))&&mirror(entry+1)==bootedHalf(0x6840+2*(entry+1))
            &&r(0x6a70,1)==state&&r(0x60e4,2)==guard);
        long d=dac();
        check("the next scan plays the new value: "+d+" = "+edit,d==edit);
        nrpn(0x3f01,0);
        check("0x3f01 puts the flash value back and leaves the mode on",mirror(entry)==flash&&r(CAL,1)==1);
        d=dac();
        check("and the next scan plays it: "+d+" = "+flash,d==flash);
        println("PASS calibration mode: a pitch-table write lands in the mirror and plays on the next scan; 0x3f01 puts the flash value back");
    }
    // ---- Audit 038711a, the pitch batch ----------------------------------
    // A slot table put in the settings mirror and made the live key table:
    // the applier copies it where the tunings are in use, and the
    // transposer's rebuild - unseeded, so it runs - copies it everywhere,
    // from the slot the applier selects.  Checked, since everything after
    // it rests on it.
    void liveTable(int[] t) throws Exception {
        for(int k=0;k<32;k++) w(0x68e0+2*k,2,t[k]);
        w(0x6090,1,0); w(0x60e4,2,0); w(0x60fa,2,0);
        controlScan();
        boolean live=true; for(int k=0;k<32;k++) live&=r(0x854+2*k,2)==t[k];
        check("the planted slot is the live key table, or this proves nothing: key 0 reads "+r(0x854,2),live);
    }
    // The factory temperament with key 0 moved, as a reference key's anchor
    // moves it: both bundled Sabat II scales put it at 483, 24TET-neutral at
    // 465, all under the period of 484 that octave pad 0 takes away.
    int[] anchored(int key0) {
        int[] t=new int[32];
        for(int k=0;k<32;k++) t[k]=k==0?key0:485+(484*k+6)/12;
        return t;
    }
    // The pressure blend's cave, entered as blend_slotmap enters it: R10 the
    // base the offset is measured from, R12 the target it stores back.
    // c[i] = {slot, table entry, stamp, pressure}; the latch mirror up, so
    // the stamps count, and the sequencer idle.
    static final long BLEND_CAVE=0x80019c64L;
    long blendRun(int base,int knob,int[][] c) throws Exception {
        w(0x6158,1,0); w(S+0x306,2,knob); w(0x608e,1,1);
        for(int k=0;k<29;k++) { w(S+0x21b+k,1,0); w(0x6540+2*k,2,0); w(0x6100+2*k,2,0); w(0x60a2+2*k,2,0); }
        for(int[] x:c) {
            w(S+0x21b+x[0],1,1); w(0x854+2*x[0],2,x[1]); w(0x60a2+2*x[0],2,x[2]&0xffff);
            w(0x6540+2*x[0],2,x[3]); w(0x6100+2*x[0],2,x[3]);
        }
        e.writeRegister("R10",base&0xffffffffL); e.writeRegister("R12",base&0xffffffffL);
        call(BLEND_CAVE);
        return (short)r(0x60e0,2);
    }
    // The blend worked out here, in the cave's own order - slots 28 down to
    // 0, the anchor the slot whose UNSTAMPED entry is the base, exempt from
    // the knob's threshold and the last one found the base R3 - two ways.
    // Right: each pitch held no lower than the frame's floor, and the
    // mean's floor.  The frame's floor is the pitch floor, -0x78, or the
    // lowest held pitch under it, one period (0x1e4) lower at most
    // (blend_frame, round 6): so a held note within a period of the floor
    // is weighed and measured where it stands, and only one further under
    // is held, for the unsigned divide.  As 038711a had it: the sum of
    // weight times pitch in 32 bits and an unsigned divide.
    long blendModel(int base,int knob,int[][] c,boolean right) {
        if(knob<0x30) return 0;
        long threshold=0x3ff-knob, sw=0, sp=0, r3=base, low=-0x78;
        for(int[] x:c) low=Math.min(low,x[1]+x[2]);
        long floor=Math.max(low,-0x78-0x1e4);
        for(int k=28;k>=0;k--) for(int[] x:c) {
            if(x[0]!=k) continue;
            boolean anchor=x[1]==base;
            long p=x[1]+x[2];
            if(right) p=Math.max(floor,p);
            if(anchor) r3=p;
            long v=x[3]-(anchor?0:threshold);
            if(v<=0) continue;
            long z=Math.min(v>>4,0x3f), weight=(z*z*z)>>3;
            sw+=weight; sp+=weight*p;
        }
        if(sw==0) return 0;
        long mean=right?Math.floorDiv(sp,sw):Long.divideUnsigned(sp&0xffffffffL,sw);
        return (short)(mean-r3);
    }
    // F2: the blend's divide is unsigned and a latched pitch can be under
    // zero.  A note latched under octave pad 0 on a slot whose key 0 is
    // under one period - 483 on both bundled Sabat II scales - is -1, and
    // 2^32 over its weight came out as the offset: the pitch swung between
    // the rails while the finger was down.
    void blendSign() throws Exception {
        setup(0,false,0); command(2);
        // Random hands first, so a run on an image without the fix still
        // says how many of each kind it got right.  With every pitch at or
        // above zero the model is 038711a's own arithmetic - checked here,
        // hand by hand - and the cave must match it, so the bias moves
        // nothing a pitch there reaches; with a pitch under zero the cave
        // must follow the model.
        Random rnd=new Random(0x38711aL);
        int plain=0, under=0, plainWrong=0, underWrong=0, identity=0;
        StringBuilder why=new StringBuilder();
        for(int t=0;t<240;t++) {
            boolean negative=t%2==1;
            int n=1+rnd.nextInt(4);
            List<Integer> slots=new ArrayList<>(); while(slots.size()<n){ int s=rnd.nextInt(29); if(!slots.contains(s)) slots.add(s); }
            int[][] c=new int[n][];
            for(int i=0;i<n;i++) {
                int table=negative?rnd.nextInt(1300):rnd.nextInt(3600);
                int stamp=negative?new int[]{-968,-484,-484,0}[rnd.nextInt(4)]:rnd.nextInt(table+1+484)-table;
                c[i]=new int[]{slots.get(i),table,stamp,rnd.nextInt(1024)};
            }
            int base=rnd.nextBoolean()?c[rnd.nextInt(n)][1]:rnd.nextInt(3000);
            int knob=0x30+rnd.nextInt(0x3d0);
            long o=blendRun(base,knob,c), m=blendModel(base,knob,c,true);
            boolean anyUnder=false; for(int[] x:c) anyUnder|=x[1]+x[2]<0;
            if(anyUnder) { under++; if(o!=m) { underWrong++; if(underWrong<=3) why.append(" under:").append(o).append("/").append(m); } }
            else {
                plain++;
                if(blendModel(base,knob,c,false)!=m) identity++;
                if(o!=m) { plainWrong++; if(plainWrong<=3) why.append(" plain:").append(o).append("/").append(m); }
            }
        }
        println("BLEND SIGN "+plain+" hands with every pitch at or above zero ("+plainWrong+" off the model, "+identity
            +" where the model and 038711a's arithmetic disagree), "+under+" with a pitch under zero ("+underWrong+" off the model)"+why);
        // The audit's case, alone under a finger at z = 5, and then at every
        // weight a finger can give it: a note at its own pitch is no offset.
        int[][] sabat={{0,483,-484,5*16}};
        long got=blendRun(483,0x80,sabat);
        StringBuilder bad=new StringBuilder(); int badZ=0;
        for(int z=2;z<64;z++) {
            int[][] one={{0,483,-484,z*16+8}};
            long o=blendRun(483,0x80,one);
            if(o!=0) { badZ++; if(badZ<=6) bad.append(" z").append(z).append(":").append(o); }
        }
        // Two fingers either side of zero, the knob at the top so neither is
        // thresholded: equal weights, so the mean is the midpoint.  A sum
        // under zero, one over it, the anchor under the floor, and a base
        // no contributor anchors, where the offset is measured from the base
        // itself.  The anchor under the floor is measured and weighed where
        // it stands, since round 6: it was held at the floor, 110, and a
        // latched set moved back into range by the transpose state then
        // took its offset from there.  And a contributor more than a period
        // under the floor, which the divide still holds a period under it.
        int zw=20*16;
        int[][][] pairs={
            {{0,424,-484,zw},{4,504,-484,zw}},     // -60 and +20: the sum under zero
            {{0,474,-484,zw},{4,534,-484,zw}},     // -10 and +50: over it
            {{0,284,-484,zw},{4,584,-484,zw}},     // -200 and +100
            {{0,434,-484,zw},{4,514,-484,zw}},     // -50 and +30, measured from a base of 1000
            {{0,584,-484,zw},{4,268,-968,zw}},     // +100 and -700, held at -604
        };
        int[] bases={424,474,284,1000,584};
        long[] want={40,30,150,-1010,-352};
        long[] pairGot=new long[pairs.length];
        for(int i=0;i<pairs.length;i++) pairGot[i]=blendRun(bases[i],0x3ff,pairs[i]);
        check("a lone note latched at -1 publishes no offset: "+got+" (038711a computed "+blendModel(483,0x80,sabat,false)+")",
            got==0&&blendModel(483,0x80,sabat,true)==0);
        check("at every weight: "+badZ+" of 62 published an offset"+bad,badZ==0);
        for(int i=0;i<pairs.length;i++) {
            long m=blendModel(bases[i],0x3ff,pairs[i],true);
            check("pair "+i+" either side of zero: offset "+pairGot[i]+", the model "+m+", written out "+want[i]
                +" (038711a computed "+blendModel(bases[i],0x3ff,pairs[i],false)+")",pairGot[i]==m&&m==want[i]);
        }
        check("with every pitch at or above zero the model is 038711a's arithmetic, so nothing a pitch there reaches moved",identity==0&&plain>50);
        check("and the cave publishes the model's offset for every hand: "+plainWrong+" and "+underWrong+" off"+why,
            plainWrong==0&&underWrong==0&&under>50);
        println("PASS the pressure blend's mean holds for pitches under zero, and is unchanged at and above it");
    }
    // F2 and F4 on the instrument's path: a Sabat II slot, the latching arp,
    // key 0 latched under octave pad 0 and the finger leaning in with the
    // portamento knob just past its deadzone.  The pitch must sit at -1 -
    // entry 2.95 of the pitch table, not the floor at zero's entry 3 - and
    // stay there while the pressure rises.
    void latchedUnderThePeriod() throws Exception {
        setup(0,false,1); command(0); latchFixture();
        check("the sequencer is idle, or the audition owns the pitch: mode "+r(0x6158,1),r(0x6158,1)==0);
        liveTable(anchored(483));
        octavePad(0); sound();
        key(0); aim(0); sound();
        check("the note latched under octave pad 0 carries minus one period, or this proves nothing: "+(short)r(0x60a2,2),
            (short)r(0x60a2,2)==-484&&r(0x608e,1)==1);
        long want=remapModel(-1);
        w(0x3490,1,2); w(S+0x306,2,0x80);
        StringBuilder seen=new StringBuilder(); int off=0, steps=0;
        for(int raw=200;raw<=1100;raw+=30) {
            for(int k=0;k<29;k++) w(0x3686+2*k,2,k==0?raw:110);
            call(0x8001aa10L);
            for(int i=0;i<3;i++) {
                sound(); steps++;
                long d=r(S+0x358,2), target=(short)r(0x60e0,2);
                if(d!=want||target!=0||(short)r(S+0x352,2)!=-1) { off++; if(off<=6) seen.append(" ").append(raw).append(":").append(d).append("/").append(target); }
            }
        }
        check("the finger's weight reaches the blend, or this proves nothing: "+r(0x6540,2),r(0x6540,2)>=0x100);
        check("key 0 at 483 latched under octave pad 0 sounds -1 and stays there as the finger leans in, DAC "+want
            +" (the floor at zero gave "+remapModel(0)+"): "+off+" of "+steps+" scans off"+seen,off==0);
        println("PASS a note latched under zero sounds its own pitch, and pressure on it bends nothing");
    }
    // F4: the target's floor is the pitch table's entry 0, as the scan's
    // clamp, the blend's apply and the clock's fast stage hold a pitch to.
    // Key 0 under octave pad 0 with a reference key's anchor below the
    // period: 483 plays -1, 465 plays -19, and 300 plays at the floor.
    void anchoredBottomKey() throws Exception {
        for(int key0:new int[]{483,465,300}) {
            setup(0,false,0); command(2);
            exactPosition(1);
            liveTable(anchored(key0));
            dac();
            touchOn(0); pressureFor(0,600);
            long d=settled("key 0 at "+key0+" under octave pad 0");
            long target=Math.max(-0x78,key0-484);
            check("key 0 at "+key0+" under octave pad 0 plays "+target+": DAC "+d+", the table's "+remapModel(target)
                +" (a floor at zero gave "+remapModel(0)+"), target "+(short)r(S+0x352,2),
                d==remapModel(target)&&(short)r(S+0x352,2)==target);
            touchOff(0); pressureFor(-1,0); dac();
        }
        println("PASS a bottom key anchored under the period plays its own pitch under octave pad 0, held at entry 0");
    }
    // F4's other half: the glide keeps its target in an unsigned halfword,
    // so a target under zero - which the floor now lets through - must not
    // read as one near 65535.  A real portamento time (the blend off, the
    // knob up), gliding into and out of targets under zero: every scan's
    // pitch lies between the two ends and none turns back.
    void glideUnderZero() throws Exception {
        setup(0,false,0); command(2);
        w(0x6d30,1,0); w(S+0x306,2,0x200); w(0x61e5,1,0);
        w(S+0x3a2,2,14); w(S+0x216,2,0); w(0x6028,2,0);
        w(S+0x352,2,485);
        for(int i=0;i<600&&(short)r(0x3210,2)!=485;i++) pitch();
        check("the glide lands on 485 first, or this proves nothing: "+(short)r(0x3210,2),(short)r(0x3210,2)==485);
        for(int[] leg:new int[][]{{485,-1},{-1,485},{485,-120},{-120,-40},{-40,300}}) {
            w(S+0x352,2,leg[1]&0xffff);
            int lo=Math.min(leg[0],leg[1]), hi=Math.max(leg[0],leg[1]), scans=0, outside=0, back=0;
            long prev=leg[0], worst=leg[0];
            for(int i=0;i<600;i++) {
                pitch(); scans++;
                long p=(short)r(0x3210,2);
                if(p<lo||p>hi) { outside++; if(Math.abs(p-leg[1])>Math.abs(worst-leg[1])) worst=p; }
                if(leg[1]<leg[0]?p>prev:p<prev) back++;
                prev=p;
                if(p==leg[1]) break;
            }
            check("a glide from "+leg[0]+" to "+leg[1]+" stays between them and never turns back: "+outside
                +" scan(s) outside (furthest "+worst+"), "+back+" backwards, lands on "+prev+" after "+scans,
                outside==0&&back==0&&prev==leg[1]);
            check("and it is a glide, or this proves nothing: "+scans+" scans",scans>3);
        }
        println("PASS the pitch glides into and out of targets under zero without leaving the span");
    }
    // F5: the tuning applier's copy must make the transposer rebuild, even
    // when the rotation's entry 0 is the slot's own.  A keyboard map that
    // doubles a degree, as diatonic7.kbm fills its unmapped keys from their
    // neighbours: keys 0 and 1 play one pitch, so one degree up leaves
    // entry 0 where it was.  Then the same slot is copied again - a tuning
    // entry written over MIDI with its own value, as a Send of an unchanged
    // table does - and the keyboard must still be rotated.
    int[] rotated(int[] t,int n) {
        int kpp=(int)r(0x69a0,2), period=(int)r(0x6814,2);
        int[] out=new int[32];
        for(int k=0;k<32;k++) { int i=k+n, add=0; while(i>31) { i-=kpp; add+=period; } out[k]=t[i]+add; }
        return out;
    }
    boolean isLive(int[] t) { for(int k=0;k<32;k++) if(r(0x854+2*k,2)!=t[k]) return false; return true; }
    void reloadedRotation() throws Exception {
        setup(0,false,0); command(2);
        cvFiltered=r(0x8001a348L,4)==0x8001eb20L;
        w(0x6d32,1,1); w(0x6d33,1,1);          // the jack transposes; the tunings are in use
        check("slot 0 has twelve keys to the period, or one degree is not one key: "+r(0x69a0,2),r(0x69a0,2)==12);
        int[] deg={0,0,2,2,4,5,5,7,7,9,9,11};
        int[] t=new int[32];
        for(int k=0;k<32;k++) t[k]=485+(484*(12*(k/12)+deg[k%12])+6)/12;
        w(S+0x342,1,1); w(S+0x343,1,0); octavePad(1);
        liveTable(t);
        int period=(int)r(0x680a,2), degree=(period+6)/12;
        cv(degree); sound(); sound();
        int[] rot=rotated(t,1);
        check("the jack stands one degree up: N="+liveN(),liveN()==1);
        check("the keyboard is rotated by it: entry 1 reads "+r(0x854+2,2)+", want "+rot[1],isLive(rot));
        check("and the doubled degree leaves entry 0 where the slot has it, or this proves nothing",rot[0]==t[0]&&rot[1]!=t[1]);
        touchOn(4); sound();
        long held=r(S+0x350,2);
        check("a held key sounds its rotated pitch: "+held+", want "+rot[4],held==rot[4]);
        nrpn(0x0100,t[0]);
        check("the write lands and clears the applier's guard, or this proves nothing: guard "+Long.toHexString(r(0x60e4,2)),
            r(0x68e0,2)==t[0]&&r(0x60e4,2)!=0xa5a0);
        sound();
        check("the copy is rotated again on the scan it lands: entry 1 reads "+r(0x854+2,2)+", want "+rot[1]+", N="+liveN(),
            isLive(rot)&&liveN()==1);
        sound();
        check("the held key did not move: "+r(S+0x350,2)+", was "+held,r(S+0x350,2)==held);
        touchOff(4); touchOn(6); sound();
        check("a new press sounds its rotated pitch: "+r(S+0x350,2)+", want "+rot[6],r(S+0x350,2)==rot[6]);
        touchOff(6);
        println("PASS a slot copied again under the jack's degree is rotated again, entry 0 unchanged or not");
    }
    // F6: with pressure_fix off at run time the factory multiplies the
    // pressure by the float in state+0x33c, and the fix's knob code keeps
    // its floor/ceiling pair in that same cell as an integer.  Knob 1 with
    // the fix on writes the pair (knob1_pressure_ceiling, run here, not
    // planted); then, the fix off, the factory's pressure routine runs to
    // the end of its multiply and float-to-int at 0x800033de.
    static final long KNOB1_DISPATCH=0x8001ee20L, PRESSURE_ROUTINE=0x80003380L, PRESSURE_PRODUCT=0x800033deL;
    long gainProduct(int key,int raw) throws Exception {
        w(S+0x256,1,key); w(0x3686+2*key,2,raw);
        e.writeRegister("SP",0x7800); e.writeRegister("R7",0x7600); e.writeRegister("LR",0x100); jump(PRESSURE_ROUTINE);
        for(int i=0;i<200000;i++) { if(pc()==PRESSURE_PRODUCT) return reg("R12"); step(); }
        throw new Exception("the pressure routine never reached its product");
    }
    void gainPairOff() throws Exception {
        setup(0,false,0); command(2);
        int key=9, raw=300; long want=(raw-110)*12;
        StringBuilder pairs=new StringBuilder();
        for(int knob:new int[]{0,1,200,409,512,800,1023}) {
            w(0x6d2f,1,1); w(S+0x34,4,0); w(S+0x30a,2,knob); w(S+0x33c,4,0x41400000L);
            e.writeRegister("R12",0); call(KNOB1_DISPATCH);
            long pair=r(S+0x33c,4), hi=pair>>>16, lo=pair&0xffff;
            pairs.append(" ").append(knob).append(":").append(Long.toHexString(pair));
            check("knob 1 at "+knob+" with the fix on leaves a floor/ceiling pair, 0x"+Long.toHexString(pair),
                hi>=1&&hi<=0x3ff&&lo>=0x20&&lo<=0x3ff);
            w(0x6d2f,1,0);
            long got=gainProduct(key,raw);
            check("fix off: the pair knob 1 left at "+knob+" multiplies as the factory's 12.0: "+got+", want "+want,got==want);
            check("and the pair stays in the cell for the fix to find again",r(S+0x33c,4)==pair);
        }
        // Knob 3 writes a pair of the same shape, knob3_pressure_floor, in
        // the independent trim no variant here builds, and with no test of
        // the fix's byte: these three are what it wrote at knob 0, 512 and
        // 1023 in the shipped config built that way, the fix off.
        for(long pair:new long[]{0x0131034fL,0x0231034fL,0x032f034fL}) {
            w(S+0x33c,4,pair); w(0x6d2f,1,0);
            long got=gainProduct(key,raw);
            check("fix off: knob 3's pair 0x"+Long.toHexString(pair)+" multiplies as 12.0 too: "+got,got==want&&r(S+0x33c,4)==pair);
        }
        // The factory's own gains are its to keep: 0.0 at the bottom of knob
        // 1's travel, the knob's floats (adc times 0x3c703c0f), and 12.0.
        float k=Float.intBitsToFloat(0x3c703c0f);
        for(int adc:new int[]{0,1,64,409,1023,-1}) {
            float g=adc<0?12.0f:adc*k;
            long bits=Float.floatToRawIntBits(g)&0xffffffffL;
            w(S+0x33c,4,bits); w(0x6d2f,1,0);
            long got=gainProduct(key,raw), model=(long)((float)(raw-110)*g);
            check("fix off: the factory's own gain "+g+" is used as it stands: "+got+", model "+model,Math.abs(got-model)<=1);
            check("and the cell is left as it was",r(S+0x33c,4)==bits);
        }
        println("PASS pressure_fix off: a floor/ceiling pair the fix left reads as the factory's 12.0, the factory's own gains as they are; pairs"+pairs);
    }
    // ---- Normal play on the table's entries (3.0.2) ----------------------
    // Calibration mode plays mirror[entry] for an entry.  Normal play has to
    // give the same DAC for every key and MIDI note that maps to that entry,
    // once the glide has settled, or the page tunes one pitch and the
    // keyboard plays another.  Three factory details kept it a count off:
    // the +-1 target fix-ups at 0x80003800 (keys 0..11 and notes 24..35 a
    // count flat under octave pad 0), the floor at 0x800038cc that zeroed
    // every target up to 9 (note 24's target of 1 with add-to-pitch off),
    // and the factory key table, which runs up to two counts over
    // 485 + 484k/12, so that the remap's rounding put every third key a
    // count sharp.
    //
    // The entry a key maps to, from the rule rather than off the image: key
    // 0 at no offset is table 485, which the remap's +120 puts on entry 15,
    // and each key is one entry and each period twelve.  Octave pad p adds
    // p-1 periods on the octave position.  Off and the middle position add
    // none, less one period where the factory's transpose mode is forced
    // (state+0x6a: the -period at 0x800035c0).  A MIDI note plays key
    // note-24, under zero too since the clamp-chain scan (2026-09-28, F3),
    // where the note-on floored the index at 0 and notes 0..23 all played
    // note 24; and the note-on drops it a further period (number cell 10,
    // 484 here) with the switch off the octaves (0x800064f8).  Past entry
    // 78 the remap holds the last entry.  Under
    // entry 3 the target is negative, and since audit 038711a (F4) it plays
    // entries 0..2 as a bend does; under entry 0 it holds at entry 0, the
    // pitch floor: those are counted and held to that entry.  Notes run from
    // 0 to 127: the note-on reads the key table as far as entry 103, and the
    // byte-for-byte check below holds every one from note 24 up to what it
    // read before it read the tuning.
    void noteOff(int note) throws Exception {
        w(0x7600-0x20,1,note); w(0x7600-0x1f,1,0); w(0x7600-0x1e,1,channel());
        call(0x80004ebcL,0x80004ef8L);
    }
    void pressureFor(int held,int pressure) throws Exception {
        for(int j=0;j<29;j++) { w(0x3490+j,1,j==held?2:0); w(0x3686+2*j,2,j==held?pressure:110); }
        call(0x8001aa10L);
    }
    // Five scans, the last three the same: the glide at rest (knob 0) has
    // landed by the second.
    long settled(String what) throws Exception {
        long[] h=new long[5];
        for(int i=0;i<h.length;i++) h[i]=dac();
        boolean still=true; for(int i=h.length-3;i<h.length;i++) still&=h[i]==h[h.length-1];
        check(what+" settles: "+Arrays.toString(h),still);
        return h[h.length-1];
    }
    void exactPosition(int p) throws Exception {
        // 0 off, 1..4 octave pads 0..3, 5 the middle with no preset.
        boolean octave=p>=1&&p<=4;
        for(int i=0;i<4;i++) w(0x613a+2*i,2,0);
        w(S+0x342,1,octave?1:0); w(S+0x343,1,p==5?1:0);
        octavePad(octave?p-1:1);
        w(S+0x306,2,0); w(S+0x310,2,0); w(S+0x216,2,0);
    }
    void exactPitch() throws Exception {
        String[] names={"off","octave pad 0","octave pad 1","octave pad 2","octave pad 3","the middle, no preset"};
        List<String> failed=new ArrayList<>();
        int floored=0, clamped=0, played=0;
        for(int p=0;p<6;p++) {
            setup(0,false,0); command(2);
            // The slot holding the image's own factory temperament: the
            // flash key table the .data copy fills RAM 0x854 from.  MIDI
            // notes past the slot read the slot's own wrap now
            // (midi_note_entry), which with this slot gives the same numbers.
            int slot=-1;
            for(int s=0;s<3&&slot<0;s++) {
                boolean same=true;
                for(int k=0;k<32;k++) same&=r(0x68e0+64*s+2*k,2)==r(0x80016574L+2*k,2);
                if(same) slot=s;
            }
            check("a tuning slot holds the image's factory temperament",slot>=0);
            w(0x6090,1,slot); w(0x60e4,2,0); controlScan();
            boolean live=true; for(int k=0;k<32;k++) live&=r(0x854+2*k,2)==r(0x80016574L+2*k,2);
            check("slot "+slot+" is the live key table",live);
            exactPosition(p); dac();
            boolean octave=p>=1&&p<=4;
            check("knob 4 leaves the transpose at rest: state+0x6a="+r(S+0x6a,1)+" 0x6b="+r(S+0x6b,1),
                r(S+0x6a,1)==0||r(S+0x6b,1)<=2);
            check("the vibrato at rest",(short)r(0x6028,2)==0);
            int periods=octave?p-2:(r(S+0x6a,1)!=0?-1:0);
            StringBuilder keys=new StringBuilder(), notes=new StringBuilder(), bases=new StringBuilder();
            int keyWrong=0, noteWrong=0, baseWrong=0;
            for(int k=0;k<=24;k++) {
                int entry=15+k+12*periods;
                touchOn(k); pressureFor(k,600);
                long d=settled(names[p]+" key "+k);
                long want=mirror(Math.max(0,Math.min(entry,78)));
                if(d!=want) { keyWrong++; keys.append(String.format(" %d:%+d",k,d-want)); }
                touchOff(k); pressureFor(-1,0); dac();
                played++;
            }
            for(int n=0;n<=127;n++) {
                int entry=15+(n-24)+12*(periods-(octave?0:1));
                rawBase=Long.MIN_VALUE; rawTarget=Long.MIN_VALUE;
                noteOn(n);
                // The note-on's own answer, before a scan: with the factory
                // temperament and nothing rotating it is the table the image
                // carries in flash, entry note-24, less 484 off the octaves -
                // what the note-on read before it read the tuning, unchanged
                // from note 24 up.  Under 24 the entry is note-24 itself,
                // twelve keys and 484 down a wrap, where the note-on used to
                // floor it at entry 0 and play note 24 (the clamp-chain scan,
                // 2026-09-28, F3).  The two reads by the note itself are the
                // flash table's entry note, as they always were.
                long base=(short)r(S+0x350,2);
                int at=n-24, wraps=0;
                while(at<0) { at+=12; wraps++; }
                long today=r(0x80016574L+2L*at,2)-484L*wraps-(octave?0:484);
                long raw=r(0x80016574L+2L*n,2);
                if(base!=today||(short)r(S+0x352,2)!=today||rawBase!=raw||rawTarget!=raw) {
                    baseWrong++; bases.append(String.format(" %d:%+d/%+d",n,base-today,rawBase-raw));
                }
                // The DAC for the notes it always had.  Past 99 every entry is
                // past 78, where the remap holds the last one, and the note-on
                // above, the same as before, is the whole of what could move.
                if(n<=99) {
                    long d=settled(names[p]+" note "+n);
                    long want=mirror(Math.max(0,Math.min(entry,78)));
                    if(entry<0) floored++; else if(entry>78) clamped++;
                    if(d!=want) { noteWrong++; notes.append(String.format(" %d:%+d",n,d-want)); }
                    noteOff(n); dac();
                } else noteOff(n);
                played++;
            }
            println("EXACT "+names[p]+", slot "+slot+", "+periods+" period(s): keys 0..24 "+keyWrong+" off"
                +(keyWrong>0?" (key:DAC-entry)"+keys:"")+"; notes 0..99 "+noteWrong+" off"
                +(noteWrong>0?" (note:DAC-entry)"+notes:"")+"; note-on for 0..127 "+baseWrong+" changed"
                +(baseWrong>0?" (note:pitch/by-the-note, against before)"+bases:""));
            if(keyWrong>0) failed.add(names[p]+": "+keyWrong+" key(s)");
            if(noteWrong>0) failed.add(names[p]+": "+noteWrong+" note(s)");
            if(baseWrong>0) failed.add(names[p]+": "+baseWrong+" note-on base(s) changed");
        }
        check("normal play gives each key and MIDI note its entry's DAC, as calibration mode does, and the note-on "
            +"reads what it read before for the factory temperament from note 24 up, and each note's own entry under it: "
            +(failed.isEmpty()?"all":failed.toString()),failed.isEmpty());
        println("PASS normal play lands on the pitch table entry: "+played+" keys and notes in six add-to-pitch positions, "
            +clamped+" past entry 78 held on it, "+floored+" under entry 0 held on it; "
            +"the note-on's three reads byte for byte as before for notes 24..127, and under 24 each note's own entry");
    }
    // ---- MIDI notes past the tuning slot (audit 038711a) ----------------
    // The note-on reads the key table at note-24, as far as entry 103, and
    // the applier and the rotation write entries 0..31 only: every note past
    // 55 used to play the factory's 12-TET entries whatever the tuning, the
    // jack or the quantised preset said.  So the pitch a note should get is
    // worked out here from the SLOT tables in the mirror, not from RAM 0x854:
    // degree note-24 plus the rotation's N, wrapped by the slot's keys per
    // period with one period of pitch per wrap - preset_entry's arithmetic,
    // which the rotation shares (wrappedEntry) - held at 0x3fff past the
    // table, and one period (number cell 10) less with the add-to-pitch
    // switch off OCTAVE.  The factory slot with nothing rotating is the one
    // state where the old and the new agree from note 24 up, and exactPitch
    // above is all in it.
    //
    // A note under 24 plays its own pitch (the clamp-chain scan, 2026-09-28,
    // F3): the note-on floored note-24 at zero before N went on, so with the
    // jack or a preset up notes 15..23 all played note 24's.  This model
    // floored it too, and agreed.  Under zero the index wraps in the live
    // table, rotated: entry i is entry i + K less a period, K the slot's keys
    // per period, the entry of the key K up rotated as that key is.  On a
    // scale whose period is not a whole number of units that can be a unit
    // off the slot's own entry at i + N less a period, where its rounding
    // carried.  Held at -0x7000, the room the note-on's halfword has.

    long midiBase(int note) {
        int i=note-24, slot=(int)r(0x6090,1); if(slot>2) slot=0;
        int kpp=(int)r(0x69a0+2*slot,2), wraps=0;
        while(i+kpp*wraps<0) wraps++;
        long v=wrappedEntry(i+kpp*wraps+liveN())-wraps*r(0x6814,2);
        if(i>31) v=Math.min(v,0x3fff);
        if(i<0) v=Math.max(v,-0x7000);
        return v-(r(S+0x342,1)!=1?r(0x6814,2):0);
    }
    // The DAC once the glide has landed: the octave pad or the forced
    // transpose, each a period of cell 10, the target held to 0..0xfff, then
    // the remap.
    long midiDac(int note,int p) {
        long period=r(0x6814,2);
        boolean octave=p>=1&&p<=4;
        long t=midiBase(note)+(octave?(p-2)*period:(r(S+0x6a,1)!=0?-period:0));
        // The adder floors a target at -0x78 since audit 038711a (F4), where
        // the remap's own hold at entry 0 takes over.
        return remapModel(Math.max(-0x78,Math.min(0xfff,t)));
    }
    // The two reads by the note itself: the live table extended the same way,
    // at the note rather than note-24.
    long midiRaw(int note) {
        long v=wrappedEntry(note+liveN());
        return note>31?Math.min(v,0x3fff):v;
    }
    // Notes 0..127 under what the fixture set: the note-on's three reads for
    // every one, and the settled DAC as well for every dacStride-th note from
    // 0, notes 55 and 56 either side of where the slot used to end, and 127;
    // for none with dacStride 0.
    int tunedPass, tunedNotesPlayed;
    List<String> tunedFailed=new ArrayList<>();
    void midiNotes(String what,int p,int dacStride) throws Exception {
        int baseWrong=0, dacWrong=0, above=0;
        StringBuilder b=new StringBuilder(), d=new StringBuilder();
        for(int n=0;n<=127;n++) {
            long want=midiBase(n), raw=midiRaw(n);
            rawBase=Long.MIN_VALUE; rawTarget=Long.MIN_VALUE;
            noteOn(n);
            long base=(short)r(S+0x350,2);
            if(base!=want||(short)r(S+0x352,2)!=want||rawBase!=raw||rawTarget!=raw) {
                baseWrong++;
                if(baseWrong<=6) b.append(String.format(" %d:%d/%d,%d/%d",n,base,want,rawBase,raw));
            }
            boolean dacToo=dacStride>0&&(n%dacStride==0||n==55||n==56||n==127);
            if(dacToo) {
                long got=settled(what+" note "+n), exp=midiDac(n,p);
                if(exp==remapModel(0xfff)) above++;
                if(got!=exp) {
                    dacWrong++;
                    if(dacWrong<=6) d.append(String.format(" %d:%d/%d",n,got,exp));
                }
                noteOff(n); dac();
            } else noteOff(n);
            tunedNotesPlayed++;
        }
        println("TUNED "+what+": N="+liveN()+", "+r(0x69a0+2*Math.min(2,(int)r(0x6090,1)),2)+" keys a period of "
            +r(0x6814,2)+"; notes 0..127: note-on "+baseWrong+" off"
            +(baseWrong>0?" (note:got/want, by-the-note got/want)"+b:"")
            +(dacStride>0?", DAC every "+dacStride+" and the seam "+dacWrong+" off"
                +(dacWrong>0?" (note:got/want)"+d:"")+", "+above+" at the top":""));
        if(baseWrong>0) tunedFailed.add(what+": "+baseWrong+" note-on pitch(es)");
        if(dacWrong>0) tunedFailed.add(what+": "+dacWrong+" DAC value(s)");
        tunedPass++;
    }
    // ---- MIDI note-offs under 24 (2026-09-28) ------------------------------
    // The factory note-off works out note - 24 in an unsigned byte where the
    // note-on floors it at zero, so a note-off under 24 cleared
    // state+0x341+note, past the held-note flags - the add-to-pitch switch
    // for note 1, the pitch base and target for notes 15 to 18, the gate for
    // 19 and 20 - and never let the gate go.  Driven here through the real
    // dispatcher in orders under and across 24, against a model that knows
    // notes and not indices: a note-on counts one more and is the last note,
    // a note-off counts one less, if any, and ends the gate when it lets go
    // of the last note.  That is the factory's rule for notes 24 and up.
    // After every note-off nothing in RAM under 0x7000 (the emulated stack is
    // above it) changes but the count at state+0x258, the 127 flags from
    // state+0x259 and the gate, the base stays the last note's and so does
    // the settled DAC, and with every note up the gate is down.
    boolean offMayWrite(long a) {
        return a==S+0x258||(a>=S+0x259&&a<S+0x259+127)||a==S+0x354||a==S+0x355;
    }
    int offEvents, offSequences;
    List<String> offFailed=new ArrayList<>();
    // Each order on its own, so one that goes wrong leaves the rest to be seen.
    void offOrder(String name,int[] events,boolean settle) {
        try { offSequence(name,events,settle); }
        catch(Exception ex) { offFailed.add(ex.getMessage()); println("OFF FAIL "+ex.getMessage()); }
    }
    // Events: n >= 0 a note-on of n, n < 0 a note-off of -n-1.
    void offSequence(String name,int[] events,boolean settle) throws Exception {
        setup(0,false,0); command(2);
        exactPosition(2);                 // octave pad 1: the switch on OCTAVE, where note 1 cleared it
        dac();
        int count=0, last=-1;
        boolean gate=false;
        long base=(short)r(S+0x350,2), d=settle?settled(name+" at rest"):0;
        for(int v:events) {
            if(v>=0) {
                noteOn(v); count++; last=v; gate=true;
                base=(short)r(S+0x350,2);
                check(name+": note "+v+" on plays its own pitch: base "+base+", want "+midiBase(v),base==midiBase(v));
                if(settle) d=settled(name+" note "+v); else dac();
                check(name+": note "+v+" on raises the gate",r(S+0x354,2)!=0);
            } else {
                int n=-v-1;
                byte[] before=e.readMemory(toAddr(0),0x7000);
                noteOff(n);
                byte[] after=e.readMemory(toAddr(0),0x7000);
                StringBuilder moved=new StringBuilder();
                for(int a=0;a<before.length;a++)
                    if(before[a]!=after[a]&&!offMayWrite(a))
                        moved.append(String.format(" S+0x%x:%d->%d",a-S,before[a]&255,after[a]&255));
                check(name+": note "+n+" off writes nothing past the flags, the count and the gate"+moved,moved.length()==0);
                if(count>0) count--;
                if(n==last) gate=false;
                check(name+": note "+n+" off: count "+r(S+0x258,1)+", want "+count,r(S+0x258,1)==count);
                check(name+": note "+n+" off: the gate "+(gate?"stays up":"goes down")+" ("+r(S+0x354,2)+")",
                    (r(S+0x354,2)!=0)==gate);
                check(name+": note "+n+" off leaves the pitch base at the last note's: "+(short)r(S+0x350,2)+", want "+base,
                    (short)r(S+0x350,2)==base);
                if(settle) {
                    long now=settled(name+" after note "+n+" off");
                    check(name+": and the pitch where the last note put it: DAC "+now+", want "+d,now==d);
                    check(name+": and the gate still "+(gate?"up":"down"),(r(S+0x354,2)!=0)==gate);
                }
            }
            offEvents++;
        }
        check(name+": every note up, the gate is down and nothing is counted",
            r(S+0x354,2)==0&&r(S+0x258,1)==0&&count==0);
        offSequences++;
    }
    void midiNoteOffs() throws Exception {
        offEvents=0; offSequences=0; offFailed.clear();
        // Two notes in each of the six orders: each on once and off once.
        int[][] pairs={{12,5},{5,24},{23,24},{1,17},{0,30},{20,127}};
        int[][] orders={{0,1,-1,-2},{0,1,-2,-1},{0,-1,1,-2},{1,0,-1,-2},{1,0,-2,-1},{1,-2,0,-1}};
        for(int[] pr:pairs)for(int[] o:orders) {
            int[] ev=new int[4];
            StringBuilder name=new StringBuilder();
            for(int i=0;i<4;i++) {
                int note=pr[Math.abs(o[i])-(o[i]<0?1:0)];
                ev[i]=o[i]>=0?note:-note-1;
                name.append(o[i]>=0?" +":" -").append(note);
            }
            offOrder("notes"+name,ev,true);
        }
        // A note-off with nothing pressed, and one of a note never pressed.
        offOrder("stray note-offs",new int[]{-6,-31,12,-4,-13},true);
        // Every note from 0 to 127 pressed, then let go in a shuffled order.
        int[] all=new int[256];
        List<Integer> ups=new ArrayList<>();
        for(int n=0;n<128;n++) { all[n]=n; ups.add(n); }
        Collections.shuffle(ups,new Random(24));
        for(int i=0;i<128;i++) all[128+i]=-ups.get(i)-1;
        offOrder("notes 0..127 down, then up shuffled",all,false);
        // Low notes and their neighbours toggled at random, then all let go.
        Random rnd=new Random(218);
        int[] pool={0,1,2,3,5,12,15,16,17,18,19,20,21,22,23,24,25,30,127};
        for(int round=0;round<3;round++) {
            List<Integer> ev=new ArrayList<>();
            Set<Integer> held=new HashSet<>();
            for(int i=0;i<40;i++) {
                int n=pool[rnd.nextInt(pool.length)];
                if(held.contains(n)) { ev.add(-n-1); held.remove(n); } else { ev.add(n); held.add(n); }
            }
            List<Integer> rest=new ArrayList<>(held);
            Collections.shuffle(rest,rnd);
            for(int n:rest) ev.add(-n-1);
            int[] arr=new int[ev.size()];
            for(int i=0;i<arr.length;i++) arr[i]=ev.get(i);
            offOrder("random round "+round,arr,false);
        }
        check("every order of note-ons and note-offs lets each note go: "
            +(offFailed.isEmpty()?"all":offFailed.size()+" wrong, "+offFailed),offFailed.isEmpty());
        println("PASS MIDI note-offs under and across 24: "+offEvents+" events in "+offSequences
            +" orders; each lets go of its own note, writes nothing past the flags, and the gate ends with the last note");
    }
    // A record's tables laid in the mirror as the page's NRPN writes lay
    // them, all three slots, then the boot's view of it: the applier's
    // guard and the rotation's state word unseeded (option_boot_state), so
    // the next scan copies and rotates it as the first scan after the
    // restart that applies a record does.
    void tunedRecord(int keys,int period,int[] table) throws Exception {
        for(int s=0;s<3;s++) {
            for(int k=0;k<32;k++) w(0x68e0+64*s+2*k,2,table[k]);
            w(0x69a0+2*s,2,keys);
        }
        w(0x6814,2,period); w(0x60e4,2,0); w(0x60fa,2,0);
        controlScan();
        int slot=Math.min(2,(int)r(0x6090,1));
        boolean live=true; for(int k=0;k<32;k++) live&=r(0x854+2*k,2)==table[k]||liveN()!=0;
        check("the record's slot is the live key table: slot "+slot+", N="+liveN(),live&&(r(0x60fa,2)>>12)==0xa);
    }
    // The jack at the degree count wanted, settled, and asked of the device.
    void tunedJack(int degrees) throws Exception {
        int keys=(int)r(0x69a0+2*Math.min(2,(int)r(0x6090,1)),2);
        int cvPeriod=(int)r(0x680a,2);
        cv((degrees*cvPeriod+keys/2)/keys);
        check("the jack stands "+degrees+" degree(s) up: N="+liveN(),liveN()==degrees);
    }
    // Bohlen-Pierce as tools/build.py makes it from tunings/BohlenPierce.scl
    // and BohlenPierce.kbm - 13 keys to the 3/1, 767 units - handed over by
    // tools/test_controls.py, so the table is the builder's and not one
    // written here.
    int bpKeys, bpPeriod; int[] bpTable;
    void tunedNotes() throws Exception {
        boolean jackOn=r(0x6d32,1)!=0, presetOn=r(0x6d31,1)!=0;
        cvFiltered=r(0x8001a348L,4)==0x8001eb20L;
        tunedFailed.clear(); tunedPass=0; tunedNotesPlayed=0;
        // The factory slot, rotated.  Seven degrees by the jack, so the seam
        // at entry 31 falls mid-period; twelve by the preset store the audit
        // drove, 367 on the active pad in the middle position.
        int factorySlot=-1, otherSlot=-1;
        for(int s=0;s<3;s++) {
            boolean same=true;
            for(int k=0;k<32;k++) same&=r(0x68e0+64*s+2*k,2)==r(0x80016574L+2*k,2);
            if(same&&factorySlot<0) factorySlot=s;
            if(!same&&otherSlot<0&&r(0x6d33,1)!=0) otherSlot=s;
        }
        if(jackOn) {
            for(int p:new int[]{0,2}) {
                setup(0,false,0); command(2);
                w(0x6090,1,factorySlot); w(0x60e4,2,0); controlScan();
                exactPosition(p); tunedJack(7); dac();
                midiNotes("the factory slot, the jack 7 up, "+(p==0?"off":"octave pad 1"),p,p==0?3:0);
            }
        }
        if(presetOn) {
            setup(0,false,0); command(2);
            w(0x6090,1,factorySlot); w(0x60e4,2,0); controlScan();
            exactPosition(5);
            int pad=(int)r(S+0x2ef,1);
            w(0x613a+2*pad,2,367); controlScan(); controlScan(); dac();
            check("the preset store 367 rotates the factory slot a period: N="+liveN(),liveN()==12);
            midiNotes("the factory slot, preset 367 in the middle",5,3);
        }
        if(otherSlot>=0) {
            // The image's own tuning where it has one that is not the
            // factory's: the jack variant's 5-limit JI.
            for(int n:jackOn?new int[]{0,7}:new int[]{0}) {
                setup(0,false,0); command(2);
                w(0x6090,1,otherSlot); w(0x60e4,2,0); controlScan();
                exactPosition(0); if(jackOn) tunedJack(n); dac();
                midiNotes("slot "+otherSlot+", the image's own tuning, N "+n+", off",0,n==0?3:0);
            }
        }
        if(bpTable!=null) {
            // A scale that repeats at the 3/1: the note-on's drop is one
            // period of it, as the octave pads' are.
            int[][] runs={{0,0,2},{1,0,0},{2,0,4}};
            for(int[] run:runs) {
                setup(0,false,0); command(2);
                tunedRecord(bpKeys,bpPeriod,bpTable);
                exactPosition(run[0]); if(jackOn) tunedJack(0); dac();
                midiNotes("Bohlen-Pierce, "+(run[0]==0?"off":"octave pad "+(run[0]-1)),run[0],run[2]);
            }
            if(jackOn) {
                setup(0,false,0); command(2);
                tunedRecord(bpKeys,bpPeriod,bpTable);
                exactPosition(0); tunedJack(5); dac();
                midiNotes("Bohlen-Pierce, the jack 5 up, off",0,0);
            }
        } else println("SKIP Bohlen-Pierce: no table was handed over (tools/test_controls.py passes one)");
        // One key a period, every key a period above the last: past entry 31
        // the pitch leaves the 16-bit table, and has to hold at the top
        // rather than wrap round to the bottom.
        int[] octaves=new int[32];
        for(int k=0;k<32;k++) octaves[k]=485+484*k;
        setup(0,false,0); command(2);
        tunedRecord(1,484,octaves);
        exactPosition(0); if(jackOn) tunedJack(0); dac();
        midiNotes("one key a period",0,8);
        check("MIDI notes 0..127 play the tuning in use, rotated, off the octaves by its period, held at the top, "
            +"and those under 24 their own pitches: "
            +(tunedFailed.isEmpty()?"all":tunedFailed.toString()),tunedFailed.isEmpty());
        println("PASS MIDI notes past the slot follow the tuning: "+tunedNotesPlayed+" notes in "+tunedPass+" runs"
            +(jackOn?", the jack":"")+(presetOn?", the preset":"")+(otherSlot>=0?", the image's own tuning":"")
            +(bpTable!=null?", Bohlen-Pierce":"")+", one key a period");
    }
    // ---- The clamp-chain scan (2026-09-28) --------------------------------
    // Every place the pitch chain held an intermediate value at a floor or
    // a cap, or read a signed one unsigned, before a later term could bring
    // it back into range - so what sounded was not the finished sum held
    // once.  Each check below works its expectation out from the unclamped
    // sum and holds it once, never through the order the firmware used.
    boolean forcePrng; long prngValue; int prngCalls;
    long clampPitch(long v) { return Math.max(-0x78,Math.min(0xfff,v)); }
    // Knob 3's random octave, drawn down, is heard a period down where the
    // finished target still sounds there, and a period up where the adder's
    // floor would take it (pitch finding 1).  The adder adds a term to the
    // arp's pitch and holds the sum at -0x78: the transpose it publishes at
    // 0x60a0 for a note the arp steps on, and for a latched slot
    // latch_hold's re-base - the slot's stamp in the hold state, and the
    // stamp plus the live transpose's distance from the set's reference in
    // the transpose state.  The draw was tested on the pitch alone against
    // 1: a note the pad takes under the floor collapsed onto it, and one
    // under 1 that would have sounded was sent up a period.
    long octaveDown(long table,long term) {
        long period=r(0x6814,2), down=table-period+term;
        return clampPitch(down<-0x78?table+period+term:down);
    }
    // One arp step with the factory PRNG forced.  Zero draws a shift, down;
    // bits 10..19 at their top draw none with the knob at its top.  The
    // count is every PRNG call the step made.
    int drawStep(long value) throws Exception {
        // Step 0 of every pattern in the patterns variant's bank is a hit,
        // so the gate lets the step sound; nothing else reads 0x6150.
        w(0x6150,2,0);
        forcePrng=true; prngValue=value; prngCalls=0;
        try { externalBeat(); } finally { forcePrng=false; }
        return prngCalls;
    }
    void knob3Octaves() throws Exception {
        w(S+0x30a,2,0); w(S+0x30c,2,0); w(S+0x30e,2,1023); w(0x60ea,2,1023);
        w(S+0x306,2,0); w(S+0x216,2,0);
    }
    void randomOctaveFloor() throws Exception {
        int[][] tables={null,anchored(483),anchored(465),anchored(300)};
        String[] names={"the image's table","key 0 at 483","key 0 at 465","key 0 at 300"};
        int cases=0, wrong=0, reflected=0, below=0, callsOff=0;
        StringBuilder why=new StringBuilder();
        for(int t=0;t<tables.length;t++) {
            setup(0,false,2); command(2); latchFixture();
            if(tables[t]!=null) liveTable(tables[t]);
            knob3Octaves();
            for(int pad=0;pad<4;pad++) {
                octavePad(pad);
                for(int k:new int[]{0,3,8,9,12,20,28}) {
                    touchOn(k); pressureFor(k,600); dac();
                    int plainCalls=drawStep(0x3ffL<<10);
                    for(int i=0;i<3;i++) dac();
                    long table=r(0x854+2*k,2), term=(short)r(0x60a0,2), period=r(0x6814,2);
                    int downCalls=drawStep(0);
                    long d=0; for(int i=0;i<4;i++) d=dac();
                    long target=(short)r(S+0x352,2), want=octaveDown(table,term);
                    cases++;
                    if(table-period+term<-0x78) reflected++;
                    else if(table-period<1) below++;
                    if(target!=want||d!=remapModel(want)) {
                        wrong++;
                        if(wrong<=6) why.append(String.format(" [%s, pad %d, key %d: table %d, term %d: target %d DAC %d, want %d DAC %d]",
                            names[t],pad,k,table,term,target,d,want,remapModel(want)));
                    }
                    if(plainCalls!=downCalls||plainCalls==0) callsOff++;
                    touchOff(k); pressureFor(-1,0);
                }
            }
        }
        check("knob 3's octave down, on the arp: "+wrong+" of "+cases+" steps off the finished target held once ("
            +reflected+" under the floor down, so up; "+below+" under 1 down that still sound)"+why,
            wrong==0&&reflected>0&&below>0);
        check("the draw costs the PRNG what an undrawn step does: "+callsOff+" of "+cases+" steps differ",callsOff==0);
        if(!lean) {
            // Latched slots: the term is latch_hold's.  {press pad, pad then,
            // state}: key 0 under octave pad 0 carries a period off; with the
            // pad at 2 the live term is a period on, and down on the stamp is
            // under the floor.  Under pad 2 with the pad at 0 it is the other
            // way round, and down sounds.  In the transpose state, entered
            // at pad 1, the pad then at 0 takes the set a period down with it.
            int[][] runs={{0,2,0},{2,0,0},{1,0,1}};
            int lwrong=0; StringBuilder lwhy=new StringBuilder();
            for(int[] run:runs) {
                setup(0,false,1); command(2); latchFixture(); knob3Octaves();
                octavePad(run[0]); sound();
                long pressed=livePad();
                key(0); aim(0); sound();
                long stamp=(short)r(0x60a2,2);
                check("key 0 latched under octave pad "+run[0]+" carries the transpose it was pressed at, or this proves nothing: "+stamp,
                    r(S+0x21b,1)==1&&Math.abs(stamp-pressed)<=1);
                long ref=pressed;
                if(run[2]==1) { w(0x62e2,1,1); w(0x6580,2,ref&0xffff); }
                octavePad(run[1]); sound();
                w(S+0x2fc,2,0);
                drawStep(0);
                long d=0; for(int i=0;i<4;i++) d=dac();
                long term=run[2]==1?stamp+livePad()-ref:stamp;
                long target=(short)r(S+0x352,2), want=octaveDown(r(0x854,2),term);
                check("the latched step is slot 0's, or this proves nothing: "+r(S+0x34d,1),r(S+0x34d,1)==0);
                if(target!=want||d!=remapModel(want)) {
                    lwrong++;
                    lwhy.append(String.format(" [%s, pressed at pad %d, pad now %d: stamp %d, live %d: target %d DAC %d, want %d DAC %d]",
                        run[2]==1?"transpose":"hold",run[0],run[1],stamp,livePad(),target,d,want,remapModel(want)));
                }
                if(run[2]==1) { w(0x62e2,1,0); w(0x6580,2,0); }
            }
            check("knob 3's octave down on a latched slot is tested on latch_hold's term: "+lwrong+" of "+runs.length+" off"+lwhy,lwrong==0);
        }
        println("PASS knob 3's octave down plays where the finished target sounds and goes up a period where the floor would take it, on the arp"
            +(lean?"":" and on latched slots in both states"));
    }
    // The pressure blend's offset and the scan's clamp (pitch findings 2
    // and 6).  The scan held glide plus bend at -0x78..0xfff and the apply
    // shim added the blend's offset to what was left: a bend under the floor
    // lost what it reached below and the offset then lifted the rest, and a
    // sum over the cap - far above the table's last entry - lost what an
    // offset down brought back into range.  The clock's fast stage did the
    // same.  Now the three are summed and held once, so the DAC must be the
    // remap of the target, the bend and the applied offset, clamped once;
    // with nothing clamped anywhere that is exactly what 3ca37de played.
    void leanOn(int low,int high,int lowRaw,int highRaw,int bend,int knob) throws Exception {
        // high first, then low: the mono path sounds the last key pressed.
        touchOn(high); touchOn(low);
        for(int j=0;j<29;j++) { w(0x3490+j,1,(j==low||j==high)?2:0); w(0x3686+2*j,2,j==low?lowRaw:j==high?highRaw:110); }
        call(0x8001aa10L);
        w(S+0x306,2,knob); w(S+0x216,2,bend&0xffff);
        for(int i=0;i<40;i++) { call(0x8001aa10L); dac(); }
    }
    // The clock's fast stage for the step now standing: a claim, the
    // attack guard clear and the glide snapping, then the flush's pass.
    long fastStage() throws Exception {
        w(0x625b,1,1); w(0x625a,1,0); w(0x2eee,2,0); w(0x60ee,1,0);
        call(0x8001c100L);
        return r(S+0x358,2);
    }
    void blendClampOnce() throws Exception {
        if(lean) { println("SKIP the blend's clamp: the blend is off in this image"); return; }
        // {octave position, sounding key, leaned key, sounding raw, leaned
        // raw, bend, knob, table}: the audit's cases under octave pad 0, the
        // same with nothing clamped, and one over the cap.
        // Key 12 at 3900: over the cap once bent, and still inside the glide's
        // own domain, which the scan offsets by 0x78 and holds to 0xfff.
        int[] high=new int[32];
        for(int k=0;k<32;k++) high[k]=k==0?1500:k==12?3900:485+(484*k+6)/12;
        Object[][] cases={
            {1,0,12,300,1300,-480,0xff,null},   // bend to -479 held at -120, then lifted 442 (the audit's)
            {1,0,12,300,1100,-480,0xff,null},   // the same, lifted less
            {1,0,12,300,1300,-200,0xff,null},
            {1,0,12,300,1300,0,0xff,null},      // nothing clamped: as 3ca37de played it
            {1,0,12,300,1300,-80,0xff,null},
            {2,0,12,300,1300,-480,0xff,null},
            {2,12,0,300,1300,480,0x3ff,high},   // 3900 bent to 4380, capped at 0xfff, then an offset down
        };
        int changed=0, same=0, engaged=0; StringBuilder why=new StringBuilder(); int wrong=0;
        for(Object[] c:cases) {
            setup(0,false,0); command(2);
            if(c[7]!=null) liveTable((int[])c[7]);
            exactPosition((Integer)c[0]);
            dac();
            int bend=(Integer)c[5];
            leanOn((Integer)c[1],(Integer)c[2],(Integer)c[3],(Integer)c[4],bend,(Integer)c[6]);
            long d=dac();
            long target=(short)r(S+0x352,2), off=(short)r(0x60e2,2);
            long want=clampPitch(target+bend+off);
            long old=Math.max(-0x78,clampPitch(target+bend)+off);
            long fast=fastStage();
            boolean clamped=want!=target+bend+off||target+bend!=clampPitch(target+bend);
            if(clamped) changed++; else same++;
            String what=String.format("key %d at %d under %s, key %d leaned on, bend %d: target %d, offset %d",
                (Integer)c[1],r(0x854+2*(Integer)c[1],2),(Integer)c[0]==1?"octave pad 0":"octave pad 1",(Integer)c[2],bend,target,off);
            if(d!=remapModel(want)||fast!=remapModel(want)) {
                wrong++;
                why.append(" ["+what+": scan DAC "+d+", fast stage "+fast+", once "+want+" DAC "+remapModel(want)
                    +"; the old order "+old+" DAC "+remapModel(old)+"]");
            }
            // A target the adder holds at the floor can take the whole
            // offset into its deficit (blend_deficit), so engagement is
            // asked of the cases whose target stands in range.
            if(target>-0x78&&target<0xfff) check(what+": the blend is engaged, or this proves nothing",off!=0);
            if(off!=0) engaged++;
            touchOff((Integer)c[1]); touchOff((Integer)c[2]); pressureFor(-1,0); w(S+0x216,2,0);
        }
        check("glide, bend and the blend's offset are held once, on the scan and in the clock's fast stage: "+wrong+" of "+cases.length
            +" off ("+changed+" with a clamp in play, "+same+" without, "+engaged+" engaged)"+why,
            wrong==0&&changed>=2&&same>=1&&engaged>=4);
        println("PASS glide, bend and the blend's offset are summed and held once, on the scan and in the clock's fast stage");
    }
    void blendCarriedOffset() throws Exception {
        if(lean) { println("SKIP the blend's offset at the adder: the blend is off in this image"); return; }
        setup(0,false,0); command(2);
        // The adder's route: the blend cave publishes the offset the apply
        // shim will add to the target's glide, and the adder clamps the
        // target as the cave returns.  The pitch the blend asks for is the
        // unclamped target plus its offset, held once, so the apply shim,
        // handed the held target and the offset settled, must sound that;
        // with the target in range that is the target plus the offset
        // itself, as 3ca37de sounded it.  An offset that does not reach
        // past what the clamp took off, or points further into it, leaves
        // the pitch where the clamp holds it.  Random hands, none latched,
        // against blendModel's offset.
        Random rnd=new Random(0x28092026L);
        int inRange=0, clampedHands=0, rangeWrong=0, clampWrong=0; StringBuilder hw=new StringBuilder();
        for(int t=0;t<300;t++) {
            int n=1+rnd.nextInt(4);
            List<Integer> slots=new ArrayList<>(); while(slots.size()<n){ int s=rnd.nextInt(29); if(!slots.contains(s)) slots.add(s); }
            int[][] cc=new int[n][];
            for(int i=0;i<n;i++) cc[i]=new int[]{slots.get(i),rnd.nextInt(3000),0,rnd.nextInt(1024)};
            int base=rnd.nextBoolean()?cc[rnd.nextInt(n)][1]:rnd.nextInt(3000);
            int[] terms={-1936,-1452,-968,-484,0,484,968,1452,2420};
            int target=base+terms[rnd.nextInt(terms.length)];
            int knob=0x30+rnd.nextInt(0x3d0);
            long o=blendModel(base,knob,cc,true);
            long want=clampPitch(target+o);
            long got=blendSounds(target,blendPublish(base,target,knob,cc));
            if(target<-0x78||target>0xfff) { clampedHands++; if(got!=want) { clampWrong++; if(clampWrong<=4) hw.append(" clamped:").append(target).append("+").append(o).append("->").append(got).append("/").append(want); } }
            else { inRange++; if(got!=want) { rangeWrong++; if(rangeWrong<=4) hw.append(" in range:").append(target).append("+").append(o).append("->").append(got).append("/").append(want); } }
        }
        check("the blend sounds its offset carried from the target the adder clamps: "+clampWrong+" of "+clampedHands
            +" hands with the target clamped off, "+rangeWrong+" of "+inRange+" in range"+hw,
            clampWrong==0&&rangeWrong==0&&clampedHands>40&&inRange>40);
        // And from a base under the floor.  A MIDI note under the table's
        // bottom plays its own degree a period down per period of keys, so
        // the base the blend measures from - no held key is it - can sit far
        // under the pitch floor, held at -0x7000.  The offset is measured
        // from that base as it stands and carried from the target the same
        // way; blend_goal used to take a base under zero for an anchor held
        // at the blend's own floor, and published the offset whole, so the
        // floor plus the whole offset sounded (clamp-chain scan, round 3:
        // note 12 over a held key 12, DAC 1435 where once is 1075).
        Random low=new Random(0x12092026L);
        int lowClamped=0, lowRange=0, lowWrong=0, lowRangeWrong=0; StringBuilder lw=new StringBuilder();
        for(int t=0;t<160;t++) {
            int n=1+low.nextInt(4);
            List<Integer> slots=new ArrayList<>(); while(slots.size()<n){ int s=low.nextInt(29); if(!slots.contains(s)) slots.add(s); }
            int[][] cc=new int[n][];
            for(int i=0;i<n;i++) cc[i]=new int[]{slots.get(i),low.nextInt(3000),0,low.nextInt(1024)};
            int base=t%2==0?-0x79-low.nextInt(2400):-0x79-low.nextInt(0x7000-0x79+1);
            int[] terms={-968,-484,0,484,968,1452,2420};
            int target=base+terms[low.nextInt(terms.length)];
            int knob=0x30+low.nextInt(0x3d0);
            long o=blendModel(base,knob,cc,true);
            long want=clampPitch(target+o);
            long got=blendSounds(target,blendPublish(base,target,knob,cc));
            if(target<-0x78||target>0xfff) lowClamped++; else { lowRange++; if(got!=want) lowRangeWrong++; }
            if(got!=want) { lowWrong++; if(lowWrong<=4) lw.append(" ").append(base).append(":").append(target).append("+").append(o).append("->").append(got).append("/").append(want); }
        }
        check("and from a base under the floor, as a MIDI note under the table's bottom leaves it: "+lowWrong+" of "+(lowClamped+lowRange)
            +" hands off ("+lowClamped+" with the target clamped, "+lowRange+" in range, "+lowRangeWrong+" of those off)"+lw,
            lowWrong==0&&lowClamped>40&&lowRange>10);
        // And from an anchor latched under the floor.  The blend held it at
        // the floor and measured its offset from there, which spent none of
        // the target's deficit, and a latched set that the transpose state
        // moved back into range then sounded short by the anchor's depth
        // (round 6).  It is measured and weighed where it stands now, within
        // a period of the floor, and its offset is carried like any other:
        // the pitch is the target plus the offset, clamped once.  Random
        // latched hands, the anchor's stamp taking it under the floor, the
        // target its own pitch.
        Random held=new Random(0x0c092026L);
        int heldWrong=0, heldPulled=0; StringBuilder hl=new StringBuilder();
        for(int t=0;t<60;t++) {
            int n=2+held.nextInt(3);
            List<Integer> slots=new ArrayList<>(); while(slots.size()<n){ int s=held.nextInt(29); if(!slots.contains(s)) slots.add(s); }
            int[][] cc=new int[n][];
            int anchor=held.nextInt(300);
            cc[0]=new int[]{slots.get(0),anchor,-484,held.nextInt(1024)};
            for(int i=1;i<n;i++) cc[i]=new int[]{slots.get(i),400+held.nextInt(2600),new int[]{-484,0,484}[held.nextInt(3)],held.nextInt(1024)};
            int knob=0x30+held.nextInt(0x3d0);
            long o=blendModel(anchor,knob,cc,true);
            if(o>0) heldPulled++;
            long want=clampPitch(anchor-484+o);
            long got=blendSounds(anchor-484,blendPublish(anchor,anchor-484,knob,cc,true));
            if(got!=want) { heldWrong++; if(heldWrong<=4) hl.append(" ").append(anchor-484).append("+").append(o).append("->").append(got).append("/").append(want); }
        }
        check("and from an anchor latched under the floor, measured where it stands: "+heldWrong+" of 60 hands off ("
            +heldPulled+" pulled up)"+hl,heldWrong==0&&heldPulled>10);
        println("PASS the blend sounds its offset carried from the target the adder clamps, from a base under the floor too, and 3ca37de's pitch wherever the target is in range");
    }
    // The apply shim with the published offset settled: handed the held
    // target as the glide leaves it, no bend, it sounds what the scan would
    // once the conditioner has landed.  What it hands the remap, which
    // returns unrun.
    long blendSounds(long target,long published) throws Exception {
        w(0x60e0,2,published&0xffff); w(0x60e2,2,published&0xffff);
        e.writeRegister("R12",clampPitch(target)&0xffffffffL);
        remapIn=Long.MIN_VALUE; skipRemap=true;
        try { call(0x8001a8f0L); } finally { skipRemap=false; }
        check("the apply shim reached the remap",remapIn!=Long.MIN_VALUE);
        return remapIn;
    }
    // The blend cave as blend_slotmap enters it, with the target apart from
    // the base: R10 the base the offset is measured from, R12 the target
    // the adder has summed.  No slot latched, so no stamp counts.
    long blendPublish(int base,int target,int knob,int[][] c) throws Exception { return blendPublish(base,target,knob,c,false); }
    long blendPublish(int base,int target,int knob,int[][] c,boolean latched) throws Exception {
        w(0x6158,1,0); w(S+0x306,2,knob); w(0x608e,1,latched?1:0);
        for(int k=0;k<29;k++) { w(S+0x21b+k,1,0); w(0x6540+2*k,2,0); w(0x6100+2*k,2,0); w(0x60a2+2*k,2,0); }
        for(int[] x:c) {
            w(S+0x21b+x[0],1,1); w(0x854+2*x[0],2,x[1]); w(0x60a2+2*x[0],2,x[2]&0xffff);
            w(0x6540+2*x[0],2,x[3]); w(0x6100+2*x[0],2,x[3]);
        }
        e.writeRegister("R10",base&0xffffffffL); e.writeRegister("R12",target&0xffffffffL);
        call(BLEND_CAVE);
        return (short)r(0x60e0,2);
    }
    // The blend's re-base history (pitch finding 4).  "Nothing has sounded
    // under the blend yet" was a history of -1, tested as any negative
    // base, and a base is negative whenever key 0 sits under the period
    // the note-on drops: MIDI note 24 off the octaves publishes -1 on
    // Sabat II's 483 and -19 on 24TET-neutral's 465.  The next note then
    // skipped the fold and jumped.  The fold keeps what is sounding across
    // the step, so the scan after the base moves must sound what the scan
    // before it did; key 0 at 485, whose base is +1, is the control.  And
    // the step it folds is the target's as the adder holds it: key 0 at 300
    // under octave pad 0 is a target the floor holds, and the handover to
    // note 31, key 7, lands in range with the blend still pulling toward
    // key 12, so folding the base's own step moved the pitch by what the
    // floor had taken (the clamp-chain scan, finding 2's re-base).  A
    // handover onto the leaned key itself would zero the offset, which the
    // conditioner snaps to rest by design.
    void rebaseSentinel() throws Exception {
        if(lean) { println("SKIP the re-base history: the blend is off in this image"); return; }
        StringBuilder seen=new StringBuilder(); int jumped=0;
        for(int key0:new int[]{485,483,465,300}) {
            setup(0,false,0); command(2);
            liveTable(anchored(key0));
            exactPosition(key0==300?1:0);
            w(S+0x306,2,0xff);
            touchOn(12);
            for(int j=0;j<29;j++) { w(0x3490+j,1,j==12?2:0); w(0x3686+2*j,2,j==12?1300:110); }
            call(0x8001aa10L);
            noteOn(24);
            long before=0; for(int i=0;i<30;i++) { call(0x8001aa10L); before=dac(); }
            long base=(short)r(S+0x350,2);
            if(key0==300) check("note 24 under octave pad 0 bases key 0 on its entry, its target held at the floor, or this proves nothing: "+base+", target "+(short)r(S+0x352,2),
                base==key0&&(short)r(S+0x352,2)==-0x78&&(short)r(0x60e2,2)!=0);
            else check("note 24 off the octaves bases key 0 a period down, or this proves nothing: "+base,
                base==key0-r(0x6814,2)&&(short)r(0x60f4,2)==base&&(short)r(0x60e2,2)!=0);
            noteOn(key0==300?31:36);
            call(0x8001aa10L); long after=dac();
            seen.append(String.format(" key 0 at %d: base %d, DAC %d then %d;",key0,base,before,after));
            if(Math.abs(after-before)>1) jumped++;
            touchOff(12); pressureFor(-1,0);
        }
        check("a base under zero is folded like any other, and the fold is the held target's step: the pitch holds across it,"+seen,jumped==0);
        println("PASS the blend folds a base step from under zero, and folds the step the held target takes; only the seed 0x7fff means nothing has sounded");
    }
    // A MIDI note under the table's bottom over a held key the blend pulls
    // toward (clamp-chain scan, round 3).  The note plays its own degree a
    // period down per period of keys, so its base sits under the pitch
    // floor and the adder holds its target there; the blend's offset is
    // measured from the base, so it has to be carried from the held target
    // or the floor plus the whole offset sounds.  Key 12 is the only key
    // with a finger on it, so whatever the note, the blend pulls the pitch
    // onto key 12: note 24, whose base is in range, is the reference, and
    // notes 12 and 0 must sound exactly what it does, near key 12's own
    // pitch, and hold it flat across the handover to note 36.
    void midiUnderTheFloor() throws Exception {
        if(lean) { println("SKIP a MIDI note under the floor under the blend: the blend is off in this image"); return; }
        long ref=-1, want=-1; StringBuilder seen=new StringBuilder(); int off=0;
        for(int note:new int[]{24,12,0}) {
            setup(0,false,0); command(2);
            liveTable(anchored(485));
            exactPosition(0);
            w(S+0x306,2,0xff);
            touchOn(12);
            for(int j=0;j<29;j++) { w(0x3490+j,1,j==12?2:0); w(0x3686+2*j,2,j==12?1300:110); }
            call(0x8001aa10L);
            noteOn(note);
            long before=0; for(int i=0;i<30;i++) { call(0x8001aa10L); before=dac(); }
            long base=(short)r(S+0x350,2), target=(short)r(S+0x352,2);
            if(note==24) {
                ref=before; want=remapModel(clampPitch(livePad()+r(0x854+24,2)));
                check("note 24 over held key 12 sounds near key 12's pitch, or the reference proves nothing: DAC "+ref+", key 12 alone "+want,
                    Math.abs(ref-want)<=12&&(short)r(0x60e2,2)!=0);
            } else check("note "+note+" bases under the floor and the adder holds its target there, or this proves nothing: base "+base+", target "+target,
                base<-0x78&&target==-0x78);
            noteOn(36);
            StringBuilder h=new StringBuilder(); int moved=0;
            for(int i=0;i<8;i++) { call(0x8001aa10L); long d=dac(); h.append(" ").append(d); if(Math.abs(d-ref)>1) moved++; }
            seen.append(String.format(" note %d: base %d, DAC %d, then note 36:%s;",note,base,before,h));
            if(before!=ref||moved>0) off++;
            touchOff(12); pressureFor(-1,0);
        }
        check("a MIDI note under the floor over a held key sounds and holds what note 24 does, DAC "+ref+":"+seen,off==0);
        println("PASS a MIDI note under the table's bottom, its target on the floor, sounds the blend's pull as note 24 does and holds it across the handover");
    }
    // The glide's ceiling (round 5).  The glide engine holds a voice's
    // accumulator to 0..4095.0, and the pitch glides remapOffset up, so a
    // target over 3975 glided to 3975.  The adder, the clock's fast stage
    // and the scan's one clamp hold the pitch to 0xfff, so under an offset
    // down the scan and the fast stage sounded different notes: target 4000
    // gave scan DAC 2067 against fast 2092.  The pitch the scan hands the
    // remap and the one the fast stage stages must both be the target, the
    // bend and the applied offset, clamped once.  Key 12 at 3900 and at
    // 3975 glided there before and are the control.  With the blend off
    // (lean) a bend down is the offset.
    void glideCeiling() throws Exception {
        // {key 12's pitch, key 0's raw pressure (110 is no finger), bend}
        int[][] cases={{3900,1300,0},{3975,1300,0},{3976,1300,0},{4000,1300,0},{4050,1300,-200},{4095,1300,0},
            {3900,110,-480},{4000,110,-480},{4095,110,-200}};
        int wrong=0, over=0, control=0, engaged=0; StringBuilder why=new StringBuilder();
        for(int[] c:cases) {
            boolean leans=c[1]!=110;
            if(lean&&leans) continue;
            setup(0,false,0); command(2);
            int[] t=new int[32];
            for(int k=0;k<32;k++) t[k]=k==12?c[0]:1500+(484*k+6)/12;
            liveTable(t);
            exactPosition(2);
            leanOn(12,0,300,c[1],c[2],leans?0x3ff:0);
            long d=dac(), scan=remapIn;
            long target=(short)r(S+0x352,2), off=(short)r(0x60e2,2);
            fastIn=Long.MIN_VALUE;
            long fast=fastStage(), staged=fastIn;
            long want=clampPitch(target+c[2]+off);
            check("key 12 at "+c[0]+" under octave pad 1 is the target, or this proves nothing: "+target,target==c[0]);
            if(leans) { check("the blend pulls key 12 toward key 0, or this proves nothing: offset "+off,off<-100); engaged++; }
            if(c[0]>3975) over++; else control++;
            if(scan!=want||staged!=want||d!=remapModel(want)||fast!=remapModel(want)) {
                wrong++;
                why.append(String.format(" [key 12 at %d, offset %d, bend %d: scan %d DAC %d, fast stage %d DAC %d, once %d DAC %d]",
                    c[0],off,c[2],scan,d,staged,fast,want,remapModel(want)));
            }
            touchOff(12); touchOff(0); pressureFor(-1,0); w(S+0x216,2,0);
        }
        check("the scan glides a target over 3975 to the target, and sounds what the fast stage stages: "+wrong+" of "+(over+control)
            +" off ("+over+" over 3975, "+control+" at or under it, "+engaged+" with the blend engaged)"+why,
            wrong==0&&over>=(lean?2:6)&&control>=(lean?1:3));
        println("PASS the glide carries the pitch up to 0xfff, and the scan and the fast stage hold it once, at the same place");
    }
    // A pad flip across the pitch floor or the cap with the blend engaged
    // (round 5).  The blend's offset is measured from the anchor's pitch,
    // and the octave pad moves the target and nothing the offset is
    // measured from, so a flip moves the pitch by the pad's step and the
    // offset stays.  A flip in range lands on the scan after it and holds.
    // Across the floor the adder's clamp takes part of the offset.
    // blend_goal published the offset less that part, and a flip changed
    // the part, so the published offset moved and the conditioner slewed
    // it: the pitch landed where 3ca37de put it and slid 20 to 45 ms to the
    // right one.  Every flip must sound the target plus the offset, clamped
    // once, from the scan after it and in the fast stage before it.  The
    // offset is read off the pitch at the start, where nothing clamps, and
    // the target is the base plus the live transpose, unclamped.  The
    // knob's two upper rates are the slow slews.  Latched notes in the
    // latch's transpose state follow the pad the same way.
    void padFlipLands() throws Exception {
        if(lean) { println("SKIP a pad flip under the blend: the blend is off in this image"); return; }
        int[] high=new int[32];
        for(int k=0;k<32;k++) high[k]=3316+(484*k+6)/12;   // key 12 at 3800
        // {table, sounding key, leaned key, start position, the positions it flips to}
        Object[][] runs={
            {anchored(300),0,12,2,new int[]{3,2,1,2}},    // in range up and back, then over the floor and back
            {anchored(100),0,12,2,new int[]{3,2,1,2}},
            {high,12,0,2,new int[]{1,2,3,2}},             // in range down and back, then over the cap and back
        };
        int across=0, inRange=0, wrong=0; StringBuilder why=new StringBuilder(), seen=new StringBuilder();
        for(int knob:new int[]{0x180,0x300}) for(Object[] run:runs) {
            setup(0,false,0); command(2);
            liveTable((int[])run[0]);
            exactPosition((Integer)run[3]);
            leanOn((Integer)run[1],(Integer)run[2],300,1300,0,knob);
            dac();
            long before=(short)r(S+0x350,2)+livePad();
            long o=remapIn-before;
            check("the target stands in range at the start, or the offset cannot be read off the pitch: "+before,before>-0x78&&before<0xfff);
            check("the blend is engaged, or this proves nothing: offset "+o,Math.abs(o)>100);
            for(int pos:(int[])run[4]) {
                octavePad(pos-1);
                long target=(short)r(S+0x350,2)+livePad();
                long want=clampPitch(target+o);
                boolean clamped=before<-0x78||before>0xfff||target<-0x78||target>0xfff;
                if(clamped) across++; else inRange++;
                fastIn=Long.MIN_VALUE; fastStage(); long staged=fastIn;
                long[] h=new long[12];
                for(int i=0;i<h.length;i++) { dac(); h[i]=remapIn; }
                boolean ok=staged==want; for(long x:h) ok&=x==want;
                String what=String.format("knob 0x%x, target %d to %d%s: want %d, fast stage %d, scans %s",
                    knob,before,target,clamped?" across the clamp":"",want,staged,Arrays.toString(h));
                seen.append(" [").append(what).append("]");
                if(!ok) { wrong++; if(wrong<=6) why.append(" [").append(what).append("]"); }
                before=target;
            }
            touchOff((Integer)run[1]); touchOff((Integer)run[2]); pressureFor(-1,0);
        }
        // And latched, in the latch's transpose state, where the pad moves
        // the latched set by latch_hold's term and the blend still measures
        // from the notes as they were latched.  Keys 0 and 12 latched under
        // octave pad 1, key 0 the arp's and resting, key 12 leaned on.
        for(int knob:new int[]{0x180,0x300}) {
            setup(0,false,1); command(0); latchFixture();
            liveTable(anchored(300));
            octavePad(1); sound();
            for(int k:new int[]{0,12}) { key(k); aim(k); sound(); }
            controlScan(); call(0x8001e580L);
            check("the latch is in its transpose state, or this proves nothing",r(0x62e2,1)==1);
            for(int k=0;k<29;k++) { w(0x3490+k,1,k==0||k==12?2:0); w(0x3686+2*k,2,k==12?1300:k==0?400:110); }
            w(S+0x306,2,knob);
            call(0x8001aa10L);
            aim(0);
            for(int i=0;i<40;i++) sound();
            long start=(short)r(S+0x352,2), pad0=livePad(), before=start;
            long o=remapIn-start;
            check("latched key 0 sounds in range at the start, or the offset cannot be read off the pitch: "+start,start>-0x78&&start<0xfff);
            check("the blend is engaged on the latched notes, or this proves nothing: offset "+o,Math.abs(o)>100);
            for(int pad:new int[]{2,1,0,1}) {    // in range up and back, then over the floor and back
                octavePad(pad);
                long target=start+livePad()-pad0;
                long want=clampPitch(target+o);
                boolean clamped=before<-0x78||before>0xfff||target<-0x78||target>0xfff;
                if(clamped) across++; else inRange++;
                fastIn=Long.MIN_VALUE; fastStage(); long staged=fastIn;
                long[] h=new long[12];
                for(int i=0;i<h.length;i++) { sound(); h[i]=remapIn; }
                boolean ok=staged==want; for(long x:h) ok&=x==want;
                String what=String.format("latched, knob 0x%x, target %d to %d%s: want %d, fast stage %d, scans %s",
                    knob,before,target,clamped?" across the clamp":"",want,staged,Arrays.toString(h));
                seen.append(" [").append(what).append("]");
                if(!ok) { wrong++; if(wrong<=6) why.append(" [").append(what).append("]"); }
                before=target;
            }
        }
        println("flips:"+seen);
        check("a pad flip under the blend lands on the target plus the offset, clamped once, from the scan after it: "+wrong+" of "+(across+inRange)
            +" off ("+across+" across the floor or the cap, "+inRange+" in range)"+why,
            wrong==0&&across>=12&&inRange>=12);
        println("PASS a pad flip across the floor or the cap lands at once under the blend, as a flip in range does, latched too");
    }
    // A note latched under the floor that the latch's transpose state moves
    // into range (round 6).  Keys 0 and 12 latched under octave pad 0 are
    // -234 and 485, with key 0 at 250.  In the transpose state the pads
    // move the set, so at pad 1 key 0 sounds 250 and key 12 969.  Leaned
    // fully onto key 12 from key 0, the pitch sounded 860, 109 short: the
    // blend measured key 0 where it was latched, under the floor, and held
    // it there, so it took the offset from the floor.  Each hand must sound
    // what it asks for, worked out here from where each held note sounds
    // (table, stamp and the set's shift), weighed as the blend weighs it,
    // and clamped once.  The offset the blend publishes must be that mean
    // less where the arp's note sounds, exactly.  The pitch must be the
    // mean, clamped once, to within what the conditioner's filter and
    // backlash leave a settled offset short of its target: four units at
    // blend_filter_shift 2 and blend_hysteresis 3.  The fingers: key 12
    // alone, pulling the pitch onto key 12; key 0 alone, its own pitch;
    // both, their weighted mean; and key 7 as the arp's key with key 0
    // leaned on.  The pads walk 1, 2, 0 and 1, so at pad 0 the notes sound
    // where they were latched.
    void transposedFloorAnchor() throws Exception {
        // {the arp's key, fingers as key, raw pressure, ...}
        int[][] hands={{0,12,1300},{0,0,900},{0,0,700,12,1300},{7,0,1300}};
        int off=0, runs=0, pulled=0; StringBuilder why=new StringBuilder(), seen=new StringBuilder();
        for(int[] hand:hands) {
            setup(0,false,1); command(0); latchFixture();
            check("the sequencer is idle, or the audition owns the pitch: mode "+r(0x6158,1),r(0x6158,1)==0);
            liveTable(anchored(250));
            octavePad(0); sound();
            for(int k:new int[]{0,7,12}) { key(k); aim(k); sound(); }
            check("keys 0, 7 and 12 latched under octave pad 0 carry minus one period, or this proves nothing",
                (short)r(0x60a2,2)==-484&&(short)r(0x60a2+14,2)==-484&&(short)r(0x60a2+24,2)==-484);
            controlScan(); call(0x8001e580L);
            check("the latch is in its transpose state, or this proves nothing",r(0x62e2,1)==1);
            for(int k=0;k<29;k++) { w(0x3490+k,1,0); w(0x3686+2*k,2,110); }
            for(int i=1;i<hand.length;i+=2) { w(0x3490+hand[i],1,2); w(0x3686+2*hand[i],2,hand[i+1]); }
            w(S+0x306,2,0x300);
            call(0x8001aa10L);
            aim(hand[0]);
            for(int i=0;i<60;i++) sound();
            for(int pad:new int[]{1,2,0,1}) {
                octavePad(pad);
                for(int i=0;i<60;i++) sound();
                long got=remapIn, published=(short)blendOut;
                fastIn=Long.MIN_VALUE; fastStage(); long staged=fastIn;
                long[] m=latchedBlend(0x300);
                long want=clampPitch(m[0]), offset=m[0]-m[2];
                if(m[1]>0) pulled++;
                runs++;
                String what=String.format("arp on key %d, fingers %s, pad %d: offset %d, want %d; sounds %d, fast stage %d, want %d (key 0 sounds %d, key 12 %d)",
                    hand[0],Arrays.toString(Arrays.copyOfRange(hand,1,hand.length)),pad,published,offset,got,staged,want,latchedSounds(0),latchedSounds(12));
                seen.append(" [").append(what).append("]");
                if(published!=offset||Math.abs(got-want)>4||Math.abs(staged-want)>4) { off++; if(off<=6) why.append(" [").append(what).append("]"); }
            }
            for(int k=0;k<29;k++) w(0x3490+k,1,0);
            call(0x8001aa10L);
        }
        println("transposed:"+seen);
        check("the blend pulls latched notes from where they sound, the transpose state's shift and all, and clamps once: "+off+" of "+runs+" off"+why,
            off==0&&pulled>=8);
        println("PASS a note latched under the floor and moved into range by the transpose state is measured and weighed where it sounds");
    }
    // Where held key k sounds in the latch: its table entry, its stamp and,
    // in the transpose state, the set's shift from its reference.
    long latchedSounds(int k) {
        long shift=r(0x62e2,1)==1?livePad()-(short)r(0x6580,2):0;
        return r(0x854+2*k,2)+(short)r(0x60a2+2*k,2)+shift;
    }
    // The blend's pull worked out from where the held notes sound: the
    // arp's note (the base's slot) exempt from the knob's threshold and
    // every other finger over it, weighed z^3/8 as the cave weighs them,
    // one mean, no floor.  {the mean, how many notes pull, where the arp's
    // note sounds}; with no weight at all, the arp's note alone.
    long[] latchedBlend(int knob) {
        long base=r(S+0x350,2), sw=0, sp=0, anchorSounds=0; int n=0;
        for(int k=28;k>=0;k--) {
            if(r(S+0x21b+k,1)!=1) continue;
            boolean anchor=r(0x854+2*k,2)==base;
            if(anchor) anchorSounds=latchedSounds(k);
            long v=r(0x6540+2*k,2)-(anchor?0:0x3ff-knob);
            if(v<=0) continue;
            long z=Math.min(v>>4,0x3f), weight=(z*z*z)>>3;
            sw+=weight; sp+=weight*latchedSounds(k);
            if(!anchor) n++;
        }
        return new long[]{sw==0?anchorSounds:Math.floorDiv(sp,sw),n,anchorSounds};
    }
    // The blend's fold across a handover when an anchor sits under the
    // floor (round 5).  Every anchor's offset is measured from where it
    // stands (round 6), and blend_deficit spends what the adder's clamp
    // takes from it, so the pitch is the target plus the offset, clamped
    // once, and a handover must fold the step between the two targets, or
    // the pitch jumps.  Round 5 measured an anchor latched under the floor
    // from the floor, and this check held its two kinds of step together
    // then.  Keys 0 and 1 at 250 and 300 latched under octave pad 0 sound
    // under the floor, key 7 sounds 283 and key 12 485.  One finger leans
    // on key 12 alone, so whichever key the arp stands on, the blend pulls
    // the pitch onto key 12 and nothing should move: under the floor to
    // under the floor, to range with an offset and back, and onto key 12
    // itself.
    void floorAnchorHandover() throws Exception {
        setup(0,false,1); command(0); latchFixture();
        check("the sequencer is idle, or the audition owns the pitch: mode "+r(0x6158,1),r(0x6158,1)==0);
        int[] t=anchored(250); t[1]=300;
        liveTable(t);
        octavePad(0); sound();
        for(int k:new int[]{0,1,7,12}) { key(k); aim(k); sound(); }
        check("keys 0, 1, 7 and 12 latched under octave pad 0 carry minus one period, or this proves nothing",
            (short)r(0x60a2,2)==-484&&(short)r(0x60a2+2,2)==-484&&(short)r(0x60a2+14,2)==-484
            &&(short)r(0x60a2+24,2)==-484&&r(0x608e,1)==1);
        for(int k=0;k<29;k++) { w(0x3490+k,1,k==12?2:0); w(0x3686+2*k,2,k==12?1300:110); }
        w(S+0x306,2,0x200);
        call(0x8001aa10L);
        for(int i=0;i<20;i++) sound();
        long want=r(0x854+24,2)-484;
        check("key 12 sounds its own pitch under the finger, or this proves nothing: "+remapIn+", "+want,remapIn==want);
        StringBuilder seen=new StringBuilder(); int moved=0, floored=0, offset=0;
        for(int k:new int[]{0,1,7,0,12,1,7,12,0}) {
            aim(k);
            StringBuilder h=new StringBuilder(); int off=0;
            for(int i=0;i<10;i++) { sound(); h.append(" ").append(remapIn); if(Math.abs(remapIn-want)>1) off++; }
            if((short)r(S+0x352,2)==-0x78) floored++;
            if((short)r(0x60e2,2)!=0&&(short)r(S+0x352,2)>-0x78) offset++;
            seen.append(String.format(" [to key %d:%s]",k,h));
            if(off>0) moved++;
        }
        check("the arp stands on anchors under the floor, and in range with an offset, or this proves nothing: "+floored+", "+offset,
            floored>=5&&offset>=2);
        check("a handover between anchors under the floor and in range holds the pitch the blend pulls to, "+want+":"+seen,moved==0);
        println("PASS the blend folds a handover to or from an anchor under the floor so the pitch holds");
    }
    // The recording audition's pinned pitch (pitch finding 5).  It went in
    // as the relative pitch plus one, zero meaning none, so a press one unit
    // under the take's reference stored zero and was never pinned: Sabat
    // II's key 0 at 483, recorded under octave pad 0 into a take born under
    // pad 1, auditioned the note latched in its slot instead.  The audition
    // must sound the press: its table entry plus the transpose it was
    // played under.
    void auditionPin() throws Exception {
        if(lean||!seq) { println("SKIP the audition pin: no latch recording in this image"); return; }
        StringBuilder seen=new StringBuilder(); int off=0;
        for(int key0:new int[]{485,483,465}) {
            setup(0,false,1); latchFixture();
            liveTable(anchored(key0));
            octavePad(1); key(0); sound(); noteUp(0);
            check("the first press opens the take, or this proves nothing",r(0x61e0,1)==1);
            octavePad(0); key(0); sound();
            long heard=r(0x854,2)+livePad();
            long target=(short)r(S+0x352,2), d=dac();
            check("the second press is recorded one period down, or this proves nothing: step "+step(1),
                r(0x61e0,1)==2&&sounds(1)==heard);
            seen.append(String.format(" key 0 at %d: heard %d, relative %d: target %d DAC %d;",key0,heard,step(1),target,d));
            if(target!=heard||d!=remapModel(clampPitch(heard))) off++;
            noteUp(0);
        }
        check("the audition sounds the press it recorded, whatever its relative pitch:"+seen,off==0);
        println("PASS the recording audition pins every relative pitch, one unit under the reference included");
    }
    // preset_degrees under a period that is not a whole number of units
    // (host finding 2).  The search reduces each entry's interval above
    // entry 0 into one period and read the winner's degree as its index
    // modulo the map's size: a period low wherever rounding left an entry a
    // unit under the whole periods its index stands for.  The tables are
    // tools/build.py's own, built by tools/test_controls.py: the host scan's
    // twelve-step scales with periods of 1201.5 and 1199 cents, which must
    // come right, and every bundled tuning, which must not move.  Every
    // store 0..1023 is asked of the cave; the degrees it publishes must
    // rotate key 0 by the table's nearest shift to the store's units -
    // preset_entry's wrap on the table's own entries, to within rounding.
    final List<String> pdNames=new ArrayList<>(); final List<int[]> pdTables=new ArrayList<>();
    final List<int[]> pdShape=new ArrayList<>();   // {keys, period, 1 for a probe scale}
    long wrapped(int[] t,int keys,int period,int i) {
        long wrap=0;
        while(i>31) { i-=keys; wrap+=period; }
        while(i<0) { i+=keys; wrap-=period; }
        return t[i]+wrap;
    }
    // 3ca37de's search, transcribed: the winner's index modulo the map.
    int degrees3ca37de(int[] t,int keys,int period,int store) {
        int units=store*132/100, whole=units/period, rem=units%period;
        int bestK=-1, bestD=period-rem;
        for(int k=0;k<32;k++) {
            int c=t[k]-t[0];
            while(c<0)c+=period;
            while(c>=period)c-=period;
            int d=Math.abs(c-rem);
            if(d<bestD) { bestD=d; bestK=k; }
        }
        return whole*keys+(bestK<0?keys:bestK%keys);
    }
    void presetDegreesRounded() throws Exception {
        if(pdTables.isEmpty()) { println("SKIP preset degrees under a rounded period: no tables were handed over (tools/test_controls.py passes them)"); return; }
        setup(0,false,0); command(2);
        // The three slot tables and their keys per period, the period and the
        // slot, put back afterwards: the kbm variant reads its map's size off
        // them for its own summary.
        byte[] keepTables=traced(0x68e0,0xc8);
        long keepPeriod=r(0x6814,2), keepSlot=r(0x6090,1);
        StringBuilder why=new StringBuilder(); int probeWrong=0, bundledMoved=0, bundledOff=0, probes=0;
        for(int s=0;s<pdTables.size();s++) {
            int[] t=pdTables.get(s); int keys=pdShape.get(s)[0], period=pdShape.get(s)[1];
            boolean probe=pdShape.get(s)[2]==1;
            for(int k=0;k<32;k++) w(0x68e0+2*k,2,t[k]);
            w(0x69a0,2,keys); w(0x6090,1,0); w(0x6814,2,period);
            w(S+0x342,1,0); w(S+0x343,1,1); w(S+0x2ef,1,0);
            int wrong=0, moved=0, maxN=(1352/period+2)*keys; StringBuilder first=new StringBuilder();
            for(int store=0;store<1024;store++) {
                w(0x613a,2,store);
                call(0x8001e1c0L);
                int n=(int)r(0x60f3,1), units=store*132/100;
                long err=Math.abs(wrapped(t,keys,period,n)-t[0]-units), best=Long.MAX_VALUE;
                for(int m=0;m<=maxN;m++) best=Math.min(best,Math.abs(wrapped(t,keys,period,m)-t[0]-units));
                if(err>best+4) { wrong++; if(first.length()<200) first.append(String.format(" store %d: %d degrees, %d off where %d is",store,n,err,best)); }
                if(!probe&&n!=degrees3ca37de(t,keys,period,store)) moved++;
            }
            if(probe) { probes++; if(wrong>0) { probeWrong+=wrong; why.append(" ["+pdNames.get(s)+": "+wrong+" stores"+first+"]"); } }
            else { bundledMoved+=moved; bundledOff+=wrong; if(moved>0||wrong>0) why.append(" ["+pdNames.get(s)+": "+moved+" moved, "+wrong+" off"+first+"]"); }
        }
        e.writeMemory(toAddr(0x68e0),keepTables); w(0x6814,2,keepPeriod); w(0x6090,1,keepSlot);
        w(0x60e4,2,0); controlScan();
        check("preset degrees under a period that is not whole: every store rotates to the nearest shift:"+why,probeWrong==0&&probes>=2);
        check("and every bundled tuning answers every store as 3ca37de did:"+why,bundledMoved==0&&bundledOff==0);
        println("PASS preset degrees keep the degree with the period each interval was reduced by: "+probes+" rounded periods right, "
            +(pdTables.size()-probes)+" bundled tunings unchanged, 1024 stores each");
    }
    // Every check, in the order a run takes them.  One image runs them all:
    // tools/test_controls.py says which configurations each one runs under,
    // lays each configuration as a settings record (SettingsRecord), and asks
    // for the checks that configuration owes.  What a check needs of the
    // configuration it is given is unusable() below, so a check asked for
    // under settings it cannot run under fails instead of skipping.
    // tunedNotes stands apart from exactPitch, so a note-on exactPitch
    // refuses leaves those to be heard.
    static final String[] CHECKS={"midiPeriod","presetOwnership","quickTapGate","bendUnderTheBottomKey",
        "presetQuantize","presetSequencer","transposeOutput","knob4Zones","noteOrders","releasedOrders",
        "latchedOrders","latchExitHold","latchTransposeState","latchStackMidi","latchHoldMidi","latchAfterMidi",
        "latchPresetMidi","velocityFloor","keyOverTakeMidi","midiOneSum","midiClampPaths","jackTransposer",
        "stripCarry","latchRecording","recordedOctaves","capacityAudition","pressureOwnership","staleAnchor",
        "previewBoundaries","recordedBounds","playbackPressure","heldPresetEdit","retainedStartup",
        "quantizedRhythm","swingRhythm","patternGate","periodCell","residue","calibrationCommand",
        "calibrationPitch","calibrationSilence","calibrationExits","calibrationLiveEdit","exactPitch",
        "tunedNotes","midiNoteOffs","blendSign","latchedUnderThePeriod","anchoredBottomKey","glideUnderZero",
        "reloadedRotation","gainPairOff","randomOctaveFloor","blendClampOnce","blendCarriedOffset",
        "rebaseSentinel","midiUnderTheFloor","auditionPin","presetDegreesRounded","glideCeiling","padFlipLands","floorAnchorHandover","transposedFloorAnchor",
        "padOctaveMode"};
    String knob2="spacing";
    // The mirror as this run's configuration booted it: the settings record
    // laid for it, or the image's own defaults.  Where a check used to read
    // a table out of the image (the tuning slots, the pattern bank, the
    // pitch curve), it reads the one the configuration boots.
    byte[] booted;
    int bootedHalf(long mirror) {
        if(reads!=null) { reads.add(mirror); reads.add(mirror+1); }
        int i=(int)(mirror-0x6800); return ((booted[i]&255)<<8)|(booted[i+1]&255);
    }
    String unusable(String name) {
        switch(name) {
            case "presetSequencer": return quantized&&seq?null:"needs the quantiser and the sequencer";
            case "transposeOutput": case "knob4Zones": case "recordedBounds":
                return !transpose?"needs knob 4 on trn":name.equals("recordedBounds")&&!seq?"needs the sequencer":null;
            case "noteOrders": case "releasedOrders": case "latchedOrders":
                // The pattern gate answers a rest with -1 at the note
                // selector, so an order walk cannot read one key per beat.
                return orders&&!knob2.equals("patterns")?null:"needs knob 1 on orders and knob 2 off patterns";
            case "latchExitHold": case "latchTransposeState": case "latchStackMidi": case "latchHoldMidi":
            case "latchAfterMidi": case "latchPresetMidi": case "staleAnchor": case "latchedUnderThePeriod":
            case "floorAnchorHandover": case "transposedFloorAnchor":
                return lean?"needs the latching arp":null;
            case "keyOverTakeMidi": case "stripCarry": case "playbackPressure": case "heldPresetEdit":
                return seq?null:"needs the sequencer";
            case "latchRecording": case "recordedOctaves": case "capacityAudition": case "pressureOwnership":
            case "previewBoundaries":
                return seq&&!transpose?null:"needs the sequencer and knob 4 off trn";
            // Its borrowed-slot half measures a 16/15 against a 9/8: the
            // jack configuration's 5-limit JI in slot 0.
            case "jackTransposer": {
                int step=bootedHalf(0x68e2)-bootedHalf(0x68e0);
                return r(0x6d32,1)!=0&&r(0x6d33,1)!=0&&step>=44&&step<=46?null:"needs the jack on transpose over the 5-limit JI";
            }
            case "retainedStartup": return lean?null:"needs the lean switches off";
            case "quantizedRhythm": return gridRhythm?null:"needs knob 2 on quantized";
            case "swingRhythm": return knob2.equals("swing")?null:"needs knob 2 on swing";
            case "patternGate": return knob2.equals("patterns")?null:"needs knob 2 on patterns";
            case "periodCell": case "residue":
                // One session with every option on: the shipped roles.
                return !transpose&&!orders&&!lean&&knob2.equals("spacing")?null:"needs the shipped knob roles and every option on";
            default: return null;
        }
    }
    @Override public void run() throws Exception {
        // key=value: checks= the names to run, comma-separated; profile= the
        // configuration's name, for the log; bp= and pd=, tables
        // tools/build.py made for tunedNotes/midiOneSum and
        // presetDegreesRounded.  The configuration itself is the record
        // SettingsRecord lays.
        Map<String,String> opt=new HashMap<>();
        for(String a:getScriptArgs()) {
            int i=a.indexOf('=');
            if(i<0) throw new Exception("ControlRegression takes key=value arguments, not "+a);
            opt.put(a.substring(0,i),a.substring(i+1));
        }
        String profile=opt.getOrDefault("profile","the image's own");
        // bp:<keys per period>:<period>:<32 table entries, commas>
        if(opt.containsKey("bp")) {
            String[] part=opt.get("bp").split(":");
            bpKeys=Integer.parseInt(part[0]); bpPeriod=Integer.parseInt(part[1]);
            String[] v=part[2].split(",");
            bpTable=new int[32];
            for(int k=0;k<32;k++) bpTable[k]=Integer.parseInt(v[k].trim());
        }
        // pd:<name>|<keys>|<period>|<1 probe, 0 bundled>|<32 entries>;...
        if(opt.containsKey("pd")) {
            for(String one:opt.get("pd").split(";")) {
                String[] part=one.split("\\|");
                int[] t=new int[32]; String[] v=part[4].split(",");
                for(int k=0;k<32;k++) t[k]=Integer.parseInt(v[k].trim());
                pdNames.add(part[0]); pdTables.add(t);
                pdShape.add(new int[]{Integer.parseInt(part[1]),Integer.parseInt(part[2]),Integer.parseInt(part[3])});
            }
        }
        List<String> wanted=new ArrayList<>(Arrays.asList(opt.getOrDefault("checks","").split(",")));
        wanted.removeIf(String::isEmpty);
        for(String name:wanted)
            if(!Arrays.asList(CHECKS).contains(name)) throw new Exception("no check named "+name);
        if(wanted.isEmpty()) throw new Exception("checks= names nothing to run");
        List<String> failures=new ArrayList<>();
        StringBuilder times=new StringBuilder();
        try {
            // The configuration, off the live option bytes its boot left:
            // the record laid for it, landed whole, or the image's own.
            fresh();
            byte[] laid=laidRecord();
            String why=laid==null?null:SettingsRecord.landed(e,laid);
            check("the settings laid for "+profile+" boot: "+why,why==null);
            booted=e.readMemory(toAddr(0x6800),0x268);
            int[] live=new int[12];
            for(int i=0;i<12;i++) live[i]=(int)r(0x6d28+i,1);
            boolean latch=live[0]!=0;
            orders=live[1]==1; transpose=live[4]==1;
            knob2=new String[]{"spacing","quantized","swing","patterns","factory"}[live[2]];
            gridRhythm=knob2.equals("quantized"); quantized=live[9]!=0;
            seq=live[5]!=0; clock=live[6]!=0; persistent=true; zones=9;
            // The lean switches go together here: the checks that read
            // `lean` were written for all five off, and no configuration
            // turns some of them off alone.
            boolean[] leanSwitches={latch,seq,clock,live[7]!=0,live[8]!=0};
            lean=!latch;
            for(boolean on:leanSwitches)
                if(on==lean) throw new Exception("the latch, sequencer, divider, pressure fix and portamento must be all on or all off: "+Arrays.toString(live));
            println("SETTINGS "+profile+": live "+Arrays.toString(live)+", knob2="+knob2+(laid==null?", the image's own":", laid as a record"));
            // every=1: every check it was given that this configuration can
            // run, the rest named and passed over (test_controls.py --every).
            boolean every=opt.getOrDefault("every","0").equals("1");
            int ran=0;
            for(String name:CHECKS) {
                if(!wanted.contains(name)) continue;
                String no=unusable(name);
                if(no!=null&&every) { println("NOT "+name+" under "+profile+": "+no); continue; }
                if(no!=null) { failures.add(name+" cannot run under "+profile+": "+no); println("FAIL "+name+" cannot run under "+profile+": "+no); continue; }
                long t0=System.nanoTime();
                try { traced(name,ControlRegression.class); }
                catch(java.lang.reflect.InvocationTargetException ex) {
                    Throwable c=ex.getCause(); failures.add(c.toString()); println(c.toString());
                }
                ran++;
                times.append(String.format(" %s=%.1f",name,(System.nanoTime()-t0)/1e9));
            }
            println("TIMES "+profile+":"+times);
            if(!failures.isEmpty())throw new Exception("CONTROL REGRESSION FAIL: "+failures);
            if(ran==0&&!every) throw new Exception("CONTROL REGRESSION FAIL: none of "+wanted+" can run under "+profile);
            println("CONTROL REGRESSION PASS: "+checks+" assertions; "+ran+" of "+wanted.size()+" checks under "+profile
                +": transpose="+transpose+", orders="+orders+", lean="+lean+", quantized="+quantized+", knob2="+knob2);
        } finally { if(e!=null)e.dispose(); }
    }
}
