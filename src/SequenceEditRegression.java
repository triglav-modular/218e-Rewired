// Preview/backspace through emitted pad scanning and actual clock dispatch.
// Reuses only the peripheral/flash model; never replaces sequencer logic.
//@category Buchla218.Tests
import java.util.*;

public class SequenceEditRegression extends PersistenceRegression {
    boolean persistent=true;
    long now, toDoubleFn, mulDoubleFn, toIntFn;
    int previewCalls;
    final List<Integer> selected=new ArrayList<>(), heard=new ArrayList<>();

    @Override void step() throws Exception {
        long p=pc(), lr=reg("LR");
        // Same limited factory soft-float workaround as the transport suite.
        // Tempo conditioning, clock ownership and every selector execute.
        if(p==toDoubleFn&&lr==0x80002bceL) { putDouble((int)reg("R12")); ret(); return; }
        if(p==mulDoubleFn&&lr==0x80002be2L) { putDouble(getDouble(10)*getDouble(8)); ret(); return; }
        if(p==toIntFn&&lr==0x80002beeL) { e.writeRegister("R12",(int)getDouble(10)); ret(); return; }
        if(p==0x8001d800L)previewCalls++;
        if(p==0x8001b38aL)selected.add((int)reg("R9"));
        if(p==0x800077f8L&&clockExercise)heard.add((int)r(0x61e2,2));
        if(!persistent&&p==0x800108fcL)throw new Exception("flash entered with persistence disabled");
        super.step();
    }
    double getDouble(int low) { return Double.longBitsToDouble((reg("R"+(low+1))<<32)|reg("R"+low)); }
    void putDouble(double value) {
        long bits=Double.doubleToRawLongBits(value);
        e.writeRegister("R10",bits&0xffffffffL); e.writeRegister("R11",bits>>>32);
    }
    @Override void cold() throws Exception {
        if(persistent) { super.cold(); return; }
        e.writeMemory(toAddr(0),new byte[0x8000]);
        e.writeMemory(toAddr(8),e.readMemory(toAddr(0x80015d28L),0x2ecc)); w(0x2ed4,4,0xffffffffL);
        for(int i=0;i<=12;i++)e.writeRegister("R"+i,0);
        e.writeRegister("SR",0); for(String f:new String[]{"N","Z","V","C"})e.writeRegister(f,0);
        w(0x29cc,4,25000000); w(S+0x20c,4,1); time(0);
        w(0xffff1060L,4,0); w(0xffff10d0L,4,0);
        w(0xffff2404L,4,0); w(0xffff2410L,4,0x202);
        if(seq)w(0x62fe,2,0xa5a5); // prove the non-persistent initializer covers both bytes
        boot(); call(0x8001ab60L);
        if(seq)check("volatile startup clears preview and CLEAR event",r(0x62fe,2)==0&&r(0x6158,1)==0);
    }
    void arp(int position) { w(S+0x340,1,position==1?1:0); w(S+0x341,1,position==2?1:0); }
    void setup(int length,boolean internal,int position) throws Exception {
        fresh();
        toDoubleFn=r(0x80002cf4L,4); mulDoubleFn=r(0x80002cf8L,4); toIntFn=r(0x80002cfcL,4);
        for(int i=0;i<length;i++) { w(0x6160+2*i,2,500+40*i); w(0x61ee+i,1,i%29); }
        w(0x61e0,1,length); if(persistent)saveLive();
        arp(position); w(S+0x308,2,internal?1000:0); w(S+0x2f2,2,0); w(S+0x2da,1,0);
        w(0x2ee6,2,1023); now=0; clockExercise=true;
        command(0); resetTrace();
        check("fixture enters WRITE via real chord",r(0x6158,1)==1&&r(S+0x20c,4)==0);
    }
    void resetTrace() { outputs=0; previewCalls=0; selected.clear(); heard.clear(); }
    void scan() throws Exception { call(SCAN); }
    void press(int pad,int pad4) throws Exception { w(0x46f3,1,pad4); w(0x46f0+pad,1,2); scan(); }
    void release(int pad) throws Exception { w(0x46f0+pad,1,0); scan(); }
    void bare(int pad) throws Exception { press(pad,0); release(pad); }
    void command(int pad) throws Exception {
        // Fixture establishes an already-armed hold. The action traverses
        // the real press edge, command and persistence scan order.
        w(0x6154,2,200); w(0x6156,1,1); press(pad,2);
        release(pad); w(0x46f3,1,0); scan();
    }
    void append(int key) throws Exception { e.writeRegister("R12",key); call(0x8001b9d0L); scan(); }
    void pitch() throws Exception { w(0x3210,2,r(S+0x352,2)); call(0x80003236L,0x80003256L); }
    void externalBeat() throws Exception {
        now+=20;
        if(clock) { edge(now-2,false); edge(now,true); serviceAndOutput(now); }
        else { time(now); call(0x80004e58L,0x800051b0L); pitch(); }
        pitch(); scan();
    }
    void internalTicks(int count) throws Exception {
        for(int i=0;i<count;i++) {
            time(++now);
            if(clock)call(0x80007c66L,0x80007c6aL);
            call(0x80004f66L,0x80004faeL);
            if(now%5==0) { pitch(); scan(); }
        }
    }
    void startPreview() throws Exception {
        // Pad processing must clear a moved cursor and leftover audition/
        // strip/tie state before the same scan can sound a phantom note.
        w(0x61e1,1,3); w(0x61e4,1,1); w(0x61e5,1,4); w(0x6230,2,5);
        bare(1);
        check("bare pad 2 starts at top",r(0x6158,1)==2&&r(0x62fe,1)==1&&r(0x61e1,1)==0);
        check("preview clears pending audition and strip/tie history",r(0x6230,2)==0&&r(0x61e4,2)==0);
        if(clock)call(0x8000737eL,0x80007386L);
        w(S+0x34a,2,20); w(0x2ee0,2,20); w(S+0x2fc,2,0x420);
        resetTrace();
    }
    void previewOnce() throws Exception {
        for(boolean internal:new boolean[]{false,true})for(int position=0;position<3;position++)
            for(int length:new int[]{1,4,64}) {
                setup(length,internal,position); int before=writes;
                w(S+0x30a,2,1023); startPreview(); // BLEND must not randomize a preview
                if(internal)internalTicks(20*(length+3));
                else for(int i=0;i<length+3;i++)externalBeat();
                check("finite preview "+length+" internal="+internal+" switch="+position,
                    selected.size()==length&&outputs==length&&r(0x6158,1)==1&&r(0x62fe,1)==0);
                for(int i=0;i<length;i++)check("recorded preview order",selected.get(i)==i&&heard.get(i)==500+40*i);
                check("preview exercised full BLEND latch",r(0x60f2,1)==127);
                check("preview boundary reached by selector",previewCalls>=length+1);
                check("preview leaves WRITE open, no flash",writes==before&&r(0x61e0,1)==length);
                check("preview ends gate and returns strip to WRITE",r(S+0x354,2)==0&&r(S+0x20c,4)==0
                    &&r(0x622e,2)==2&&r(0x60ee,1)==0&&r(0x6230,2)==0&&r(0x61e5,1)==0);
                if(clock)check("preview drains pending FIFO ownership",r(0x6234,1)==r(0x6235,1)&&r(0x6237,1)==0);
            }
        setup(0,false,0); int before=writes; bare(1); externalBeat();
        check("empty preview is a no-op",r(0x6158,1)==1&&r(0x62fe,1)==0&&outputs==0&&writes==before);
        setup(4,false,0); startPreview(); for(int i=0;i<5;i++)externalBeat();
        startPreview(); for(int i=0;i<5;i++)externalBeat();
        check("second preview is also one shot",outputs==4&&r(0x6158,1)==1&&r(0x62fe,1)==0);
        command(1); resetTrace(); for(int i=0;i<10;i++)externalBeat();
        check("ordinary PLAY still loops",outputs==10&&r(0x6158,1)==2&&r(0x62fe,1)==0);
        for(int i=0;i<10;i++)check("ordinary sequential wrap",selected.get(i)==i%4);
        w(S+0x30a,2,1023); resetTrace();
        for(int i=0;i<40;i++)externalBeat();
        boolean shuffled=false;
        for(int i=1;i<selected.size();i++)shuffled|=selected.get(i)!=(selected.get(i-1)+1)%4;
        check("ordinary PLAY retains BLEND shuffle",outputs==40&&shuffled&&r(0x60f2,1)==127);
        int count=(int)r(0x61e0,1); bare(2);
        check("bare backspace is inert during PLAY",r(0x6158,1)==2&&r(0x61e0,1)==count);
        command(1); bare(1); bare(2);
        check("bare edit pads are inert while stopped",r(0x6158,1)==0&&r(0x61e0,1)==count&&r(0x62fe,1)==0);
        println("PASS one-shot preview 0/1/4/64 steps, internal/external clocks, three arp positions, shuffle, repeat, normal loop");
    }
    void restsAndTies() throws Exception {
        for(boolean internal:new boolean[]{false,true})for(int[] values:new int[][]{
            {500,0x7ffe,700,0x7fff}, {0x7fff,600,0x7fff,0x7ffe}, {0x7ffe}, {0x7fff}}) {
            setup(values.length,internal,0);
            for(int i=0;i<values.length;i++)w(0x6160+2*i,2,values[i]);
            int before=writes; startPreview();
            if(internal)internalTicks(20*(values.length+3));
            else for(int i=0;i<values.length+3;i++)externalBeat();
            check("rest/tie preview visits every step",selected.size()==values.length);
            for(int i=0;i<values.length;i++)check("rest/tie order",selected.get(i)==i);
            check("rest/tie end returns to clean WRITE",r(0x6158,1)==1&&r(0x62fe,1)==0
                &&r(S+0x354,2)==0&&r(0x60ee,1)==0&&r(0x61e5,1)==0&&writes==before);
        }
        println("PASS rests/ties including first/final tie and all-silent previews, gate release and no phantom audition");
    }
    void stripCarry() throws Exception {
        for(boolean internal:new boolean[]{false,true})for(boolean beforePreview:new boolean[]{false,true})
            for(int position:new int[]{1000,3000}) {
                setup(2,internal,0); int before=writes;
                w(S+0x1fe,2,position);
                if(beforePreview) { w(S+0x206,1,1); scan(); }
                // Unlike startPreview(), do not plant synthetic strip state:
                // this test carries a real sampled touch across transport.
                bare(1);
                if(clock)call(0x8000737eL,0x80007386L);
                w(S+0x34a,2,20); w(0x2ee0,2,20); w(S+0x2fc,2,0x420);
                if(!beforePreview) { w(S+0x206,1,1); scan(); }
                if(internal)internalTicks(100);
                else for(int i=0;i<3;i++)externalBeat();
                check("held strip survives preview without editing",r(0x6158,1)==1&&r(0x61e0,1)==2);
                scan(); scan(); w(S+0x206,1,0); scan();
                check("release of pre-WRITE touch appends nothing",r(0x61e0,1)==2&&writes==before);
                // Releasing must also re-arm the next touch, with exactly one
                // rest/tie and no repeats while held or already released.
                w(S+0x206,1,1); scan(); scan();
                check("fresh WRITE touch waits for release",r(0x61e0,1)==2);
                w(S+0x206,1,0); scan(); scan();
                check("fresh WRITE release appends once",r(0x61e0,1)==3
                    &&r(0x6164,2)==(position<2048?0x7ffe:0x7fff)&&writes==before);
            }
        for(boolean alreadyWriting:new boolean[]{false,true}) {
            setup(2,false,0);
            if(!alreadyWriting) { command(1); command(1); }
            int before=writes;
            w(S+0x206,1,1); w(S+0x1fe,2,1000); scan(); command(0); scan();
            w(S+0x206,1,0); scan();
            check("RECORD rejects a previously held strip",r(0x6158,1)==1&&r(0x61e0,1)==2&&writes==before);
            w(S+0x206,1,1); scan(); w(S+0x206,1,0); scan();
            check("RECORD accepts the next fresh strip touch",r(0x61e0,1)==3&&r(0x6164,2)==0x7ffe);
        }
        // The automatic end can run between control scans. If the strip was
        // up at that boundary, a new touch before the next scan is valid.
        setup(2,false,0); startPreview(); externalBeat(); externalBeat(); now+=20;
        if(clock) { edge(now-2,false); edge(now,true); serviceAndOutput(now); }
        else { time(now); call(0x80004e58L,0x800051b0L); pitch(); }
        check("preview returned before next control scan",r(0x6158,1)==1&&r(S+0x206,1)==0);
        w(S+0x1fe,2,3000); w(S+0x206,1,1); scan(); w(S+0x206,1,0); scan();
        check("first post-boundary touch is not swallowed",r(0x61e0,1)==3&&r(0x6164,2)==0x7fff);
        println("PASS strip ownership across preview/RECORD, both clocks, rest/tie halves, and fresh-touch rearming");
    }
    void cancellation() throws Exception {
        for(int pad:new int[]{0,1,2}) {
            setup(4,false,0); append(4); int before=writes; startPreview(); externalBeat(); command(pad);
            check("every explicit command cancels preview "+pad,r(0x62fe,1)==0);
            check("cancel command sets expected mode",r(0x6158,1)==(pad==0?1:0));
            if(persistent) {
                if(pad==0)check("resume WRITE does not complete take",writes==before&&r(call(NEWEST)+24,1)==4);
                else check("STOP/CLEAR completes preview take",writes==before+2&&r(call(NEWEST)+24,1)==(pad==1?5:0));
            }
            if(pad!=0)command(0);
            append(5); int length=(int)r(0x61e0,1); command(1);
            check("later normal PLAY is not preview",r(0x6158,1)==2&&r(0x62fe,1)==0);
            if(persistent) {
                check("later WRITE exit saves",r(call(NEWEST)+24,1)==length);
                cold(); check("later completed take survives reboot",r(0x61e0,1)==length&&r(0x62fe,2)==0);
            }
        }
        setup(4,false,0); int before=writes; startPreview(); command(1);
        check("stopping unchanged preview does not write",writes==before&&r(0x62fe,1)==0);
        println("PASS preview STOP/RECORD/CLEAR cancellation, correct save boundaries, later takes and restart");
    }
    void unarmedHold() throws Exception {
        for(int pad:new int[]{1,2}) {
            setup(4,false,0); int before=writes; press(pad,2);
            check("held unarmed pad 4 blocks bare action",r(0x6158,1)==1&&r(0x61e0,1)==4
                &&r(0x62fe,1)==0&&r(0x6156,1)==0&&r(0x6154,2)==1);
            for(int i=0;i<205;i++)scan();
            check("arming does not invent a fresh held-pad press",r(0x6156,1)==1&&r(0x6158,1)==1
                &&r(0x61e0,1)==4&&writes==before);
            release(pad); press(pad,2);
            check("fresh armed chord still works",r(0x6158,1)==(pad==1?2:0)&&r(0x62fe,1)==0
                &&r(0x61e0,1)==(pad==1?4:0));
            release(pad); w(0x46f3,1,0); scan();
            check("pad 4 release disarms",r(0x6156,1)==0);
        }
        println("PASS held-but-unarmed exclusion, no invented press when arming, fresh armed PLAY/CLEAR chords");
    }
    void backspace() throws Exception {
        for(int length:new int[]{0,1,4,64}) {
            setup(length,false,0); int before=writes;
            for(int left=length;left>0;left--) {
                int slot=left-1; press(2,0);
                check("one backspace removes one whole slot",r(0x61e0,1)==slot
                    &&r(0x6160+2*slot,2)==0&&r(0x61ee+slot,1)==0);
                scan(); scan(); check("held pad does not autorepeat",r(0x61e0,1)==slot); release(2);
            }
            bare(2);
            check("empty backspace stays unfinished",r(0x61e0,1)==0&&r(0x6158,1)==1&&writes==before);
            if(persistent) {
                // Another control's release-save must not smuggle this edit
                // into the durable sequence snapshot.
                w(0x46f0,1,2); w(S+0x30a,2,333); scan(); release(0);
                long page=call(NEWEST);
                check("preset release excludes unfinished empty take",r(page+16,2)==333&&r(page+24,1)==length);
                before=writes; command(2);
                check("explicit CLEAR of backspaced-empty take completes it",r(call(NEWEST)+24,1)==0
                    &&writes==before+(length==0?0:2)&&r(0x62ff,1)==0);
                before=writes; command(2); check("repeated CLEAR skips unchanged flash",writes==before);
                cold(); check("explicit empty CLEAR survives reboot",r(0x61e0,1)==0&&r(0x613a,2)==333);
            }
        }
        setup(1,false,0); int before=writes; bare(2); command(1);
        check("leaving WRITE after last backspace completes empty take",r(0x61e0,1)==0
            &&(!persistent||(writes==before+2&&r(call(NEWEST)+24,1)==0)));
        if(persistent) {
            setup(1,false,0); bare(2); cold();
            check("power cycle discards unfinished last backspace",r(0x61e0,1)==1&&r(0x6160,2)==500);
        }
        setup(4,false,0); w(0x6164,2,0x7ffe); w(0x6166,2,0x7fff);
        bare(2); bare(2); check("rest and tie can both be backspaced",r(0x61e0,1)==2&&r(0x6164,4)==0);
        append(6); check("append reuses freed slot",r(0x61e0,1)==3&&r(0x61f0,1)==6);
        println("PASS 0/1/4/64 backspaces, press edges, zeroed slots, rest/tie deletion, append reuse, empty-edit save isolation");
    }
    @Override public void run() throws Exception {
        seq=true; clock=getScriptArgs().length>0&&getScriptArgs()[0].contains("clock");
        persistent=getScriptArgs().length<2||!getScriptArgs()[1].equals("volatile");
        try {
            previewOnce(); restsAndTies(); stripCarry(); cancellation(); unarmedHold(); backspace();
            println("SEQUENCE EDIT PASS: "+checks+" assertions; clock="+clock+", persist="+persistent
                +"; emitted firmware with modeled peripherals, no hardware flash.");
        } finally { if(e!=null)e.dispose(); }
    }
}
