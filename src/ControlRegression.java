// Knob ownership and note-order regressions in emitted firmware.
// The entire applier/preset chain and real clock/selector paths execute;
// only the peripheral model inherited from SequenceEditRegression is used.
//@category Buchla218.Tests
import java.util.*;

public class ControlRegression extends SequenceEditRegression {
    static final long APPLIER=0x8001a2e8L;
    boolean transpose, orders, lean;
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
            check("release resumes actual transpose in this ADC event",r(S+0x352,2)!=target
                &&r(S+0x358,2)!=dac&&r(0x614d,1)==0);
            check("early ownership check preserves release-save edge",persistent?writes>before:writes==before);
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
            check("pad release returns knob ownership",r(0x614d,1)==0&&setting()==setting(direction==1?1023:0));
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
        w(S+0x2fc,2,0x420); // /1 even after acquisition
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
    void releasedOrders() throws Exception {
        for(int position:lean?new int[]{2}:new int[]{1,2})for(int zone:new int[]{3,4}) {
            setup(0,false,position); command(1); command(1);
            for(int k:new int[]{4,14,9})key(k);
            e.writeRegister("R12",14); call(position==1?0x80018d00L:0x8001a280L);
            check("real release/unlatch removes target",r(S+0x21b+14,1)==0&&r(S+0x21a,1)==2);
            w(S+0x34d,1,9); w(S+0x30a,2,zone*176+40); w(S+0x2fc,2,0x420);
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
            w(S+0x34d,1,0); w(S+0x30a,2,zone*176+40); w(S+0x2fc,2,0x420); w(0x614e,1,1);
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
    @Override public void run() throws Exception {
        String[] args=getScriptArgs();
        transpose=args.length>0&&args[0].equals("trn");
        orders=args.length>1&&args[1].equals("orders");
        zones=args.length>3?Integer.parseInt(args[3]):9;
        lean=args.length>4&&args[4].equals("lean");
        seq=!lean; clock=!lean; persistent=args.length>2&&args[2].equals("persist");
        List<String> failures=new ArrayList<>();
        try {
            try { presetOwnership(); } catch(Exception ex) { failures.add(ex.toString()); println(ex.toString()); }
            if(transpose)try { transposeOutput(); } catch(Exception ex) { failures.add(ex.toString()); println(ex.toString()); }
            if(orders)try { noteOrders(); } catch(Exception ex) { failures.add(ex.toString()); println(ex.toString()); }
            if(orders)try { releasedOrders(); } catch(Exception ex) { failures.add(ex.toString()); println(ex.toString()); }
            if(orders)try { latchedOrders(); } catch(Exception ex) { failures.add(ex.toString()); println(ex.toString()); }
            if(seq)try { stripCarry(); } catch(Exception ex) { failures.add(ex.toString()); println(ex.toString()); }
            if(!failures.isEmpty())throw new Exception("CONTROL REGRESSION FAIL: "+failures);
            println("CONTROL REGRESSION PASS: "+checks+" assertions; transpose="+transpose+", orders="+orders+", persist="+persistent+", lean="+lean);
        } finally { if(e!=null)e.dispose(); }
    }
}
