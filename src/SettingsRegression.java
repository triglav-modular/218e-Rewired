// The settings loader: the RAM mirror the readers point at, filled at boot
// from the image's own tables and overridden by the newer valid record in
// one of two flash slots.  Runs on PersistenceRegression's harness - its
// cold boots, flash model and factory startup - and reads this image's own
// build.properties and settings.bin, so what it compares against is what
// tools/build.py wrote for the very image under emulation.  Never flashes.
// Run via tools/test_persistence.py; see docs/PLAN-SETTINGS.md.
//@category Buchla218.Tests
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32;

public class SettingsRegression extends PersistenceRegression {
    static final long SLOT0=0x8003d000L, SLOT1=0x8003d800L, MIRROR=0x6800, STATE=0x6a70;
    static final long SNEWEST=0x8001f150L, APPLIER=0x80019a40L, REMAP=0x80019980L, CHAIN=0x8001a2e8L;
    static final long PARSER=0x8000831cL, SENDER=0x80008034L, WRITER=0x800108fcL, SETSCAN=0x8001f820L;
    static final long USB=0x34b0, NRPN=0x6a68, LIVE=0x6d28, WDT=0xffff0d30L;
    // settings_restart: its second store is at +6 and its spin at +0xa.
    static final long RESTART=0x8001fb60L, RESTART_2ND=RESTART+6, RESTART_SPIN=RESTART+0xa;
    static final int LEN=0x2a8, PAY=0x20, END=0x288, LAYOUT=2;
    static final String[] NUMBERS={"tie_glide_rate","strip_halfway_units","clock_min_ms",
        "clock_rearm_us","clock_lock_pulses","transpose_cv_period","transpose_cv_zero",
        "transpose_cv_hysteresis","chord_hold_scans","latch_state_hold_scans","octave_units"};
    static final int[] DEFAULTS={60,2048,4,250,5,123,0,2,300,200,484};
    // The option cells, 16..26, and the top of each one's range.
    static final String[] OPTIONS={"latching_arp","knob1","knob2","knob3","knob4","sequencer",
        "clock_divide","pressure_fix","pressure_portamento","quantize_presets","portamento_in","alternate_tunings"};
    static final int[] OPTION_MAX={1,2,4,1,2,1,1,1,1,1,1,1};
    static final int PARAMS=354;   // 32 cells, 16 live bytes, 79, 96, 3, 96, 32
    // Phase B: the knob dispatchers and latch helpers, and the caves they choose.
    static final long KB=0x8001fb80L, KBSEL=KB, KBK1=KB+0x20, KBRHY=KB+0x60, KBVIB=KB+0xa0;
    static final long KBEARLY=KB+0xc0, KBLATE=KB+0xe0, KBL1=KB+0x100, KBL3=KB+0x130, BLEND=0x6d38;
    static final long BLENDSEL=0x8001a0a0L, ZONES=0x8001aec0L, FACTORYSEL=0x800029a8L, GATE=0x8001b050L;
    static final long RAND=0x80019df8L, GRID=0x8001ea00L, SWING=0x8001b100L, VIB=0x8001a350L;
    static final long EARLY=0x8001d920L, ADC=0x80004a00L, LATE=0x8001b010L;
    // Phase C: the latch dispatchers, the pad test, the chord shim, and what they choose.
    static final long LT=0x8001fd20L, LTON=LT, LTOFF=LT+0x30, LTHOLD=LT+0x50, LTPAD=LT+0x70, LTSHIM=LT+0x90, LTPOOL=LT+0xb0;
    static final long LOWNER=0x8001dde0L, LNOTEOFF=0x8001a280L, FNOTEOFF=0x80005a50L, LHOLD=0x8001e520L, FTIMER=0x800045e8L;
    // Phase D: the sequencer's arm gate, the chord that calls it, its word in the chord's slack and the MCALL that reads it.
    static final long SQARM=0x8001fe50L, CHORD=0x8001b180L, CHORDWORD=0x8001b29cL, CHORDCALL=0x8001b1baL;
    // Phase E: the jack's reading, the degree gate, the glide shim and its word, the chain dispatch, the adder's dispatch and what they choose.
    static final long JK=0x8001fea0L, PG=0x8001fed0L, GA=0x8001fef0L, GAPOOL=GA+0x18, CD=0x8001ff10L;
    static final long PQ=0x8001e140L, PD=0x8001e1c0L, CVENTRY=0x8001e7c0L, CVFILTER=0x8001eb20L, CVPOOL=0x8001e8b0L;
    static final long F2I=0x80013434L, GLIDESITE=0x8000313eL, CHAINWORD=0x8001a348L;
    // Phase F: the pressure dispatchers, the three hook caves, the interpolator's gate, the route word, the glide value, the boot clear.
    static final long PF=0x8001ee00L, K1=0x8001ee20L, K4=0x8001ee40L, C2=0x8001ee60L, C1=0x8001ee80L, GN=0x8001eea0L;
    static final long IP=0x8001eec0L, PB=0x8001eef0L, GR=0x8001ef10L, OBC=0x8001ef40L;
    static final long REBASE=0x8001ad28L, PIN=0x8001eca8L, BLENDAT=0x80019c64L, STALLED=0x4718;
    // option_boot, and option_boot_state between it and the blend tail: the state an option owns, cleared with it off.
    static final long OB=0x8001fdf0L, OBS=0x8001ff30L;
    // octave_period: the five factory octave sites call its pool words and read number cell 10.
    static final long OP=0x8001ff74L, OPPOOL=OP+0x30;
    // seq_restart_clear: the sequencer's runtime, zeroed from seq_restart_init at every boot.
    static final long SQC=0x8001ed60L;
    static final long CURVE=0x80019580L, I2F=0x80013350L, KNOB1=0x800194c0L, FKNOB1=0x80004188L, KNOB4=0x80014380L, FKNOB4=0x80004070L;
    static final long COND=0x8001ad78L, REMAPCAVE=0x80019980L, INTERP=0x8001a600L, INTERPOUT=0x8001a688L, GLIDETABLE=0x80015150L;
    // Phase G: the ISR dispatch, the event-10 dispatch, the pulse dispatch, and what they choose.
    static final long CI=0x8001ef60L, CE=0x8001ef90L, PU=0x8001efc0L, CAPTURE=0x8001c200L, FBODY=0x800072f4L;
    static final long SKIP10=0x800051b0L, ARPSTEP=0x8000210cL, CLOCKPULSE=0x8001c700L, DEFER=0x8001a26cL, ISR=0x800072e4L, ISREND=0x80007328L;
    // Cell 27: the two tuning keys' caves, the remote-enable guard, the applier's dispatch, and what they choose.
    static final long TK27=0x8001e8e0L, TK28=0x8001e930L, RG=0x8001e980L, TA=0x8001e9a0L, APPLIERCAVE=0x80019a40L;
    static final long K27FACTORY=0x80003d88L, K28FACTORY=0x80003dc0L;
    long wdtFirst=-1, wdtSecond=-1; int restarts;
    Properties props=new Properties();
    byte[] record;
    boolean keepSlots, stubChain, failWrite, stubSelector;
    // The factory's endpoint wait sets the byte at 0x4718 when it times out
    // and clears it when a wait succeeds (0x8000d9c4: 0x8000daf6 and
    // 0x8000da34); the sender reaches it through the flush.  From this many
    // packets on, the host has stopped reading.  -1: it reads everything.
    int stallFrom=-1;
    int slotWrites;
    final List<int[]> sent=new ArrayList<>();

    // The chip's slots are erased flash until something writes them; the
    // imported image has no bytes there, so say so before every boot that
    // is not meant to find a record.
    @Override void cold() throws Exception {
        if(!keepSlots) {
            byte[] ff=new byte[0x800]; Arrays.fill(ff,(byte)255);
            e.writeMemory(toAddr(SLOT0),ff); e.writeMemory(toAddr(SLOT1),ff);
        }
        super.cold();
    }
    // The pitch remap begins by calling the per-scan chain (applier, latch
    // watch, vibrato); the remap's own table read is what is under test.
    // The USB-MIDI sender is recorded rather than run - what the firmware
    // asked to send is the claim - and the factory flash writer, when it is
    // aimed at a settings slot, is modelled at the level persistence has
    // already proved it at: an erase-and-write fills the page with 0xff
    // and copies; a write without erase can only clear bits.
    @Override void step() throws Exception {
        long p=pc();
        if(stubChain&&p==CHAIN) { ret(); return; }
        // The pattern gate's hit calls the real selector through knob 1's
        // dispatcher; answering 77 there tells a hit from a rest (-1).
        if(stubSelector&&p==KBK1) { e.writeRegister("R12",77); ret(); return; }
        // The restart's two watchdog writes are read back where they land;
        // the spin that follows would never return, so it is returned from
        // by hand.  The reset itself is the bench's to see.
        if(p==RESTART_2ND) wdtFirst=r(WDT,4);
        if(p==RESTART_SPIN) { wdtSecond=r(WDT,4); restarts++; ret(); return; }
        if(p==SENDER) {
            sent.add(new int[]{(int)reg("R12"),(int)reg("R11"),(int)reg("R10")});
            w(STALLED,1,stallFrom>=0&&sent.size()>stallFrom?1:0);
            ret(); return;
        }
        if(p==WRITER&&reg("R12")>=SLOT0&&reg("R12")<SLOT1+0x800) {
            long dest=reg("R12"), src=reg("R11"), len=reg("R10"), erase=reg("R9");
            check("a settings write stays inside one page",(dest&~511L)==((dest+len-1)&~511L));
            slotWrites++;
            if(!failWrite) {
                if(erase!=0) { byte[] ff=new byte[512]; Arrays.fill(ff,(byte)255); e.writeMemory(toAddr(dest&~511L),ff); }
                byte[] from=e.readMemory(toAddr(src),(int)len), to=e.readMemory(toAddr(dest),(int)len);
                for(int i=0;i<len;i++) to[i]&=from[i];
                e.writeMemory(toAddr(dest),to);
            }
            ret(); return;
        }
        super.step();
    }
    // One USB-MIDI packet into the receive ring, then the factory parser
    // over it - the dispatcher's own drain, one packet at a time.
    void feed(int status,int d1,int d2) throws Exception {
        long idx=r(USB+0x84,4);
        w(USB+4+idx,1,status); w(USB+5+idx,1,d1); w(USB+6+idx,1,d2); w(USB+7+idx,1,0);
        w(USB+0x84,4,idx+4>0x7f?0:idx+4);
        call(PARSER);
    }
    void nrpn(int param,int value) throws Exception {
        feed(0xbf,99,param>>7); feed(0xbf,98,param&0x7f); feed(0xbf,6,value>>7); feed(0xbf,38,value&0x7f);
    }
    // Decode what the firmware sent into (parameter, value) pairs, the
    // page's decoder mirrored: independent bytes, complete on the data LSB.
    List<int[]> decoded() {
        List<int[]> out=new ArrayList<>(); int pm=0,pl=0,dm=0;
        for(int[] m:sent) {
            check2("every packet is a Control Change on channel 16",m[2]==15);
            if(m[0]==99)pm=m[1]; else if(m[0]==98)pl=m[1]; else if(m[0]==6)dm=m[1];
            else if(m[0]==38) out.add(new int[]{(pm<<7)|pl,(dm<<7)|m[1]});
        }
        return out;
    }
    void check2(String name,boolean ok) { if(!ok) throw new RuntimeException("FAIL "+name); }
    // The value the mirror holds for a parameter, worked out here from
    // the map in docs/PLAN-SETTINGS.md rather than by asking the firmware.
    long expect(int param) throws Exception {
        if(param<0x20) return r(MIRROR+2*param,2);
        if(param<0x30) return r(LIVE+param-0x20,1);
        if(param>=0x80&&param<0xcf) return r(0x6840+2*(param-0x80),2);
        if(param>=0x100&&param<0x160) return r(0x68e0+2*(param-0x100),2);
        if(param>=0x160&&param<0x163) return r(0x69a0+2*(param-0x160),2);
        if(param>=0x180&&param<0x1e0) {
            int i=param-0x180; long m=r(0x69a8+4*(i/3),2)|(r(0x69aa+4*(i/3),2)<<16);
            return (m>>>(14*(i%3)))&0x3fff;
        }
        if(param>=0x1e0&&param<0x200) return r(0x6a28+2*(param-0x1e0),2);
        return -1;
    }
    void receive() throws Exception {
        fresh();
        long marker=num("init_marker",0)&0xffff;
        nrpn(0,61);
        check("an NRPN on channel 16 writes its number cell",r(0x6800,2)==61);
        nrpn(0,1025);
        check("a number past its bound is ignored, not clamped",r(0x6800,2)==61);
        nrpn(0x83,1234);
        check("a pitch entry lands",r(0x6840+6,2)==1234);
        nrpn(0x83,0x1000);
        check("a pitch entry past the DAC is ignored",r(0x6840+6,2)==1234);
        w(0x60e4,2,0xa5a0); nrpn(0x105,999);
        check("a tuning entry lands and clears the applier's guard",r(0x68e0+10,2)==999&&r(0x60e4,2)==0);
        nrpn(0x105,0x3fff);
        check("a tuning entry takes all fourteen bits",r(0x68e0+10,2)==0x3fff);
        nrpn(0x105,999);
        // Keys per period and the period shape the rotated table too, so a
        // write to either re-copies the slot as a slot write does.  (Audit
        // 2026-09-24: only the slots cleared the guard, and the rotation
        // kept the old wrap until the jack or the slot moved.)
        for(int i=0;i<32;i++) w(0x854+2*i,2,0xabc);
        w(0x60e4,2,0xa5a0); nrpn(0x160,(int)r(0x69a0,2));
        check("a keys-per-period write clears the applier's guard",r(0x60e4,2)==0);
        w(0x6090,1,0); call(APPLIER);
        check("and the applier copies the slot again over a stale table",
            Arrays.equals(e.readMemory(toAddr(0x854),64),e.readMemory(toAddr(0x68e0),64)));
        w(0x60e4,2,0xa5a0); nrpn(10,(int)r(MIRROR+20,2));
        check("a period write clears it too",r(0x60e4,2)==0);
        nrpn(0x161,7);
        check("keys per period land",r(0x69a2,2)==7);
        nrpn(0x161,0); nrpn(0x161,props.getProperty("block.preset_entry","0").trim().equals("1")?33:128);
        check("keys per period outside their bound are ignored",r(0x69a2,2)==7);
        nrpn(0x180,0x1234); nrpn(0x181,0x2abc); nrpn(0x182,0x9);
        long mask=0x1234L|(0x2abcL<<14)|(0x9L<<28);
        check("three thirds make a mask, low halfword first",
            r(0x69a8,2)==(mask&0xffff)&&r(0x69aa,2)==(mask>>>16));
        nrpn(0x181,0x0001);
        check("a third replaces only its own bits",r(0x69a8,2)==0x5234&&r(0x69aa,2)==0x9000);
        nrpn(0x1e0,16);
        check("a length lands",r(0x6a28,2)==16);
        nrpn(0x1e0,33);
        check("a length past 32 is ignored",r(0x6a28,2)==16);
        byte[] before=mirror(); nrpn(0x200,5); nrpn(0x3000,5); nrpn(0x30,1);
        check("a parameter that names nothing changes nothing",Arrays.equals(before,mirror()));
        // The option cells: a write lands in the mirror and not in the live
        // bytes, which stay as booted until the next power-up.
        long live17=r(LIVE+1,1), was17=r(MIRROR+2*17,2);
        nrpn(17,was17==2?1:2);
        check("an option cell takes a value inside its range",r(MIRROR+2*17,2)==(was17==2?1:2));
        check("and the live byte stays as booted",r(LIVE+1,1)==live17);
        nrpn(17,3);
        check("an option past its range is ignored",r(MIRROR+2*17,2)==(was17==2?1:2));
        nrpn(18,4); check("knob 2 reaches factory, its top value",r(MIRROR+2*18,2)==4);
        nrpn(18,5); check("and not past it",r(MIRROR+2*18,2)==4);
        nrpn(11,1); nrpn(28,1);
        check("a reserved cell refuses everything but zero",r(MIRROR+2*11,2)==0&&r(MIRROR+2*28,2)==0);
        long period=r(MIRROR+20,2); nrpn(10,767);
        check("the period cell, 10, takes a value",r(MIRROR+20,2)==767);
        nrpn(10,2001); nrpn(10,99);
        check("and not one past its bounds, 100..2000",r(MIRROR+20,2)==767);
        nrpn(10,100);
        check("its floor lands",r(MIRROR+20,2)==100);
        nrpn(10,(int)period);
        int was27=(int)r(MIRROR+2*27,2); nrpn(27,was27==1?0:1);
        check("the tunings' switch, cell 27, takes the other value",r(MIRROR+2*27,2)==(was27==1?0:1));
        nrpn(27,2); check("and not past it",r(MIRROR+2*27,2)==(was27==1?0:1));
        nrpn(27,was27);
        byte[] beforeLive=e.readMemory(toAddr(LIVE),16); nrpn(0x21,1); nrpn(0x2f,7);
        check("the live bytes are read-only over MIDI",Arrays.equals(beforeLive,e.readMemory(toAddr(LIVE),16)));
        // The pair: portamento needs the fix, on receive as on load.
        nrpn(23,1); nrpn(24,1);
        check("portamento on with the fix on lands",r(MIRROR+2*24,2)==1);
        nrpn(23,0);
        check("the fix going off takes portamento with it",r(MIRROR+2*23,2)==0&&r(MIRROR+2*24,2)==0);
        nrpn(24,1);
        check("portamento on with the fix off is ignored",r(MIRROR+2*24,2)==0);
        nrpn(24,0); nrpn(23,1);
        check("the fix back on leaves portamento where it was",r(MIRROR+2*23,2)==1&&r(MIRROR+2*24,2)==0);
        nrpn(24,1);
        check("and portamento then lands",r(MIRROR+2*24,2)==1);
        // The factory's own Control Change path, in front of which this
        // sits: controller 5 is the portamento time, stored shifted with a
        // flag, on the instrument's channel and on no other.
        w(S+0x2e7,1,2); w(S+0x3a2,2,0); w(S+0x1fc,1,0);
        feed(0xb2,5,40);
        check("controller 5 on the instrument's channel still reaches the factory",
            r(S+0x3a2,2)==(40<<3)&&r(S+0x1fc,1)==1);
        w(S+0x3a2,2,0); w(S+0x1fc,1,0); feed(0xb3,5,40);
        check("and on another channel it does not",r(S+0x3a2,2)==0&&r(S+0x1fc,1)==0);
        w(S+0x2e7,1,15); w(S+0x3a2,2,0);
        feed(0xbf,5,41);
        check("a channel-16 controller that is not NRPN still reaches the factory",
            r(S+0x3a2,2)==(41<<3));
        long state=r(NRPN,4); feed(0xb3,99,7); feed(0xb3,38,7);
        check("NRPN on another channel is not ours",r(NRPN,4)==state);
        // An RPN select parks the parameter in hand where it names nothing,
        // so a host's RPN data entry on channel 16 - the pitch-bend range an
        // MPE upper zone sends - is not applied to the last NRPN.  (Audit
        // 2026-09-24: it set cell 0 to 256.)
        nrpn(0,61); byte[] held=mirror();
        feed(0xbf,101,0); feed(0xbf,100,0); feed(0xbf,6,2); feed(0xbf,38,0);
        check("RPN 0 data entry on channel 16 changes nothing",Arrays.equals(held,mirror()));
        feed(0xbf,38,5);
        check("nor does a lone data LSB after it",Arrays.equals(held,mirror()));
        nrpn(0,62);
        check("and an NRPN after it lands",r(0x6800,2)==62);
        check("nothing was sent",sent.isEmpty());
        println("PASS NRPN on channel 16 lands in the mirror, bounded; the factory's path is untouched");
    }
    // After a power-up the parameter in hand names nothing: a stray data
    // entry on channel 16 is not a write to cell 0.  (Audit 2026-09-24.)
    void strayDataEntry() throws Exception {
        fresh();
        check("the boot parks the parameter at 0x3fff",r(NRPN,2)==0x7f7f);
        feed(0xbf,6,2); feed(0xbf,38,0);
        check("a data entry with no parameter after boot changes nothing",Arrays.equals(mirror(),payloadOf(record)));
        println("PASS a stray data entry lands nowhere, after boot and after an RPN select");
    }
    void commits() throws Exception {
        fresh(); keepSlots=true; slotWrites=0;
        nrpn(0,61); nrpn(0x3f00,0x1111);
        check("a commit needs its key",r(STATE,1)==0);
        nrpn(0x3f00,0x2a2a);
        check("a commit is requested, not done, on receive",r(STATE,1)==1&&slotWrites==0);
        call(SETSCAN);
        check("the scan commits into slot 0: two page writes and the marker",
            r(STATE,1)==2&&r(STATE+1,1)==0&&r(STATE+4,4)==1&&slotWrites==3);
        long p=call(SNEWEST);
        check("the record is the newest and carries the edit",p==SLOT0&&r(SLOT0+0x20,2)==61&&r(SLOT0+8,4)==1);
        check("the committed record says layout 2",r(SLOT0+4,2)==LAYOUT);
        call(SETSCAN);
        check("a scan without a request writes nothing",slotWrites==3);
        nrpn(0,62); nrpn(0x3f00,0x2a2a); call(SETSCAN);
        check("the next commit takes the other slot and the next generation",
            r(STATE,1)==2&&r(STATE+1,1)==1&&r(STATE+4,4)==2&&call(SNEWEST)==SLOT1&&slotWrites==6);
        check("slot 0 still holds the previous record",r(SLOT0+0x20,2)==61&&r(SLOT0,4)==0x32313853L);
        w(0x6800,2,0); cold();
        check("a power cycle boots the committed record",r(0x6800,2)==62&&r(STATE+1,1)==1&&r(STATE+4,4)==2);
        failWrite=true; nrpn(0,63); nrpn(0x3f00,0x2a2a); call(SETSCAN);
        check("a write that does not take leaves state 3 and the old record newest",
            r(STATE,1)==3&&call(SNEWEST)==SLOT1&&r(SLOT1+0x20,2)==62&&r(0x6800,2)==63);
        failWrite=false; nrpn(0x3f00,0x2a2a); call(SETSCAN);
        check("the next request tries again",r(STATE,1)==2&&r(STATE+1,1)==0&&r(STATE+4,4)==3&&r(SLOT0+0x20,2)==63);
        nrpn(0x3f01,0); w(0x6800,2,0); w(0x60e4,2,0xa5a0); nrpn(0x3f01,0);
        check("0x3f01 reloads the mirror from flash",r(0x6800,2)==63&&r(STATE,1)==0);
        check("and clears the applier's guard, so the next scan re-copies the slot",r(0x60e4,2)==0);
        w(0x60e4,2,0xa5a0); nrpn(0x3f02,0);
        check("0x3f02 puts the image's own settings back",Arrays.equals(mirror(),payloadOf(record)));
        check("and clears the applier's guard too",r(0x60e4,2)==0);
        // What the guard is for: the table in play follows the mirror.
        for(int i=0;i<32;i++) w(0x854+2*i,2,0xabc);
        w(0x6090,1,0); call(APPLIER);
        check("the applier then copies the slot the defaults put back, not the stale table",
            Arrays.equals(e.readMemory(toAddr(0x854),64),e.readMemory(toAddr(0x68e0),64)));
        // The restart: the watchdog's two-key write, and only with the key.
        restarts=0; wdtFirst=-1; wdtSecond=-1;
        nrpn(0x3f04,0x1111); nrpn(0x3f04,0);
        check("a restart needs its key",restarts==0);
        nrpn(0x3f04,0x2a2a);
        check("0x3f04 with the key reaches the watchdog: 0x55 then 0xaa, PSEL 7, enabled",
            restarts==1&&wdtFirst==0x55000701L&&wdtSecond==0xaa000701L);
        keepSlots=false;
        println("PASS commit: alternating slots, generations, a failed write, reload, defaults and the restart");
    }
    void dumps() throws Exception {
        fresh(); keepSlots=true; sent.clear();
        nrpn(0x180,0x1234); nrpn(0x181,0x2abc); nrpn(0x182,0x9); nrpn(0x1e5,9); nrpn(0x3,77);
        nrpn(0x3f03,0);
        int scans=0, most=0;
        while(r(NRPN+4,2)!=0x4000&&scans<400) { int before=sent.size(); call(SETSCAN); scans++; most=Math.max(most,sent.size()-before); }
        check("a dump ends by itself",r(NRPN+4,2)==0x4000);
        check("at most eight packets a scan",most<=8);
        List<int[]> got=decoded();
        check(PARAMS+" parameters and the ten of the identity block: "+got.size(),got.size()==PARAMS+10);
        boolean order=true, values=true;
        int[] walk=new int[PARAMS]; int n=0;
        for(int q=0;q<0x30;q++)walk[n++]=q; for(int q=0x80;q<0xcf;q++)walk[n++]=q; for(int q=0x100;q<0x163;q++)walk[n++]=q;
        for(int q=0x180;q<0x200;q++)walk[n++]=q;
        for(int i=0;i<PARAMS;i++) { if(got.get(i)[0]!=walk[i])order=false; if(got.get(i)[1]!=expect(walk[i]))values=false; }
        check("in the instrument's order: cells, live bytes, pitch, tuning, keys, masks, lengths",order);
        check("every value is the mirror's, the live bytes theirs",values);
        long marker=num("init_marker",0)&0xffff;
        int b=PARAMS;
        check("the identity block follows: firmware version, marker top bits, period, slot, state, generation, marker, layout version 2",
            got.get(b)[0]==0x3f76&&got.get(b)[1]==num("firmware_version_code",0x300)
            &&got.get(b+1)[0]==0x3f77&&got.get(b+1)[1]==(marker>>14)
            &&got.get(b+2)[0]==0x3f78&&got.get(b+2)[1]==num("octave_units",484)
            &&got.get(b+3)[0]==0x3f79&&got.get(b+3)[1]==0xff
            &&got.get(b+4)[0]==0x3f7a&&got.get(b+4)[1]==0
            &&got.get(b+5)[0]==0x3f7b&&got.get(b+6)[0]==0x3f7c&&got.get(b+7)[0]==0x3f7d
            &&got.get(b+8)[0]==0x3f7e&&got.get(b+8)[1]==(marker&0x3fff)
            &&got.get(b+9)[0]==0x3f7f&&got.get(b+9)[1]==LAYOUT);
        // The generation in three parts, after a commit gave it one.
        sent.clear(); nrpn(0x3f00,0x2a2a); call(SETSCAN);
        w(STATE+4,4,0x12345678L);
        nrpn(0x3f7f,0); while(r(NRPN+4,2)!=0x4000) call(SETSCAN);
        got=decoded();
        check("an identity request sends the ten alone",got.size()==10);
        long gen=got.get(7)[1]|((long)got.get(6)[1]<<14)|((long)got.get(5)[1]<<28);
        check("the generation rides in three parts",gen==0x12345678L&&got.get(4)[1]==2&&got.get(3)[1]==0);
        // A marker past 0x3fff needs its top bits: check the split, not the luck of this build's marker.
        check("the marker rides in two parts",(got.get(1)[1]<<14|got.get(8)[1])==marker);
        check("the firmware version leads the block",got.get(0)[0]==0x3f76&&got.get(0)[1]==num("firmware_version_code",0x300));
        // A host that stops reading: the flush waits for it, 100 frames the
        // first time and a frame at a time after that, eight packets a scan
        // for as long as the cursor runs.  The dump ends at the first
        // parameter that leaves a wait timed out (audit 2026-09-24).
        sent.clear(); nrpn(0x3f03,0); stallFrom=10;
        call(SETSCAN); call(SETSCAN);
        int stalled=sent.size();
        check("a stalled host ends the dump at the parameter it stalled on: cursor "
            +Long.toHexString(r(NRPN+4,2))+", "+stalled+" packets",r(NRPN+4,2)==0x4000&&stalled==12);
        call(SETSCAN);
        check("and nothing more is sent",sent.size()==stalled);
        stallFrom=-1;
        keepSlots=false;
        println("PASS dump: every parameter, paced, in order, then the identity block");
    }
    // The pattern gate reaches the bank the mirror holds, up to its first
    // zero length, whatever count the image was built with: a bank sent over
    // MIDI is reached whole, and a shorter one leaves no silent positions.
    long gate(int knob) throws Exception {
        w(0x60e6,2,knob); w(0x6150,2,0); e.writeRegister("R12",5);
        return call(GATE)&0xffffffffL;
    }
    void mask(int i,long m) { w(0x69a8+4*i,2,m&0xffff); w(0x69aa+4*i,2,(m>>>16)&0xffff); }
    void patterns() throws Exception {
        fresh(); stubSelector=true;
        for(int i=0;i<32;i++) { mask(i,0); w(0x6a28+2*i,2,0); }
        // Pattern 0 never sounds and pattern 1 always does, four steps each.
        // Never sounding is a hit past the length: a mask of no steps at all
        // plays every step, below.
        mask(0,0x80000000L); mask(1,0xffffffffL); w(0x6a28,2,4); w(0x6a2a,2,4);
        check("knob 2 at the bottom picks pattern 0, a rest",gate(0)==0xffffffffL);
        check("and at the top pattern 1 of a two-pattern bank, a hit",gate(0x3ff)==77);
        w(0x6a2a,2,0); mask(2,0xffffffffL); w(0x6a2c,2,4);
        check("a zero length ends the bank: the top is pattern 0 again",gate(0x3ff)==0xffffffffL);
        w(0x6a28,2,0);
        check("an empty bank is one pattern that rests",gate(0x3ff)==0xffffffffL&&gate(0)==0xffffffffL);
        for(int i=0;i<32;i++) { mask(i,i==31?0xffffffffL:0x80000000L); w(0x6a28+2*i,2,1); }
        check("a full bank reaches pattern 31 at the top",gate(0x3ff)==77);
        check("and pattern 30 just below it",gate(0x3ff-32)==0xffffffffL);
        // A pattern with no steps is no pattern: every step sounds.  The bank
        // of an image built without patterns is one such, and an NRPN sender
        // can turn knob 2 to patterns without sending a bank.  (Audit
        // 2026-09-24: the arpeggiator went silent.)
        for(int i=0;i<32;i++) { mask(i,0); w(0x6a28+2*i,2,0); }
        w(0x6a28,2,32);
        boolean every=true; for(int k=0;k<40;k++) every&=gate(0)==77;
        check("an empty pattern plays every step",every);
        stubSelector=false;
        println("PASS the pattern gate reaches the mirror's bank, however many patterns the image was built with");
    }
    // Where a dispatcher lands: run it from a caller at 0x100 until the PC
    // leaves it, and answer the PC - a cave's address, or 0x100 for a return.
    long resolve(long entry) throws Exception {
        e.writeRegister("SP",0x7800); e.writeRegister("LR",0x100); jump(entry);
        for(int i=0;i<40;i++) { long p=pc(); if(p<entry||p>=entry+0x40) return p; step(); }
        throw new Exception("dispatcher did not leave "+Long.toHexString(entry));
    }
    void knobs() throws Exception {
        fresh();
        long[] k1={BLENDSEL,ZONES,FACTORYSEL};
        for(int c=0;c<3;c++) {
            w(LIVE+1,1,c); w(LIVE+2,1,0);
            check("knob 1 cell "+c+" selects "+Long.toHexString(k1[c])+", from the factory's word and the gate's",
                resolve(KBSEL)==k1[c]&&resolve(KBK1)==k1[c]);
        }
        w(LIVE+2,1,3);
        check("knob 2 on patterns puts the gate in front of the selector",resolve(KBSEL)==GATE);
        w(LIVE+1,1,1);
        check("and the gate's own word still reaches knob 1's choice",resolve(KBK1)==ZONES);
        long[] k2={RAND,GRID,SWING};
        for(int c=0;c<3;c++) {
            w(LIVE+2,1,c); e.writeRegister("R12",321);
            check("knob 2 cell "+c+" reloads through "+Long.toHexString(k2[c])+" with the tempo still in R12",
                resolve(KBRHY)==k2[c]&&reg("R12")==321);
        }
        w(LIVE+1,1,1); w(LIVE+2,1,3); e.writeRegister("R12",0x377b); resolve(KBSEL);
        check("the selector dispatcher leaves its argument in R12",reg("R12")==0x377b);
        for(int c=3;c<5;c++) {
            w(LIVE+2,1,c); w(S+0x38e,2,0); e.writeRegister("R12",321); e.writeRegister("R8",0); e.writeRegister("R9",0);
            long p=resolve(KBRHY);
            check("knob 2 cell "+c+" is the factory's own reload: the tempo stored, R8 the state base, R9 the tempo",
                p==0x100&&r(S+0x38e,2)==321&&reg("R8")==S&&reg("R9")==321);
        }
        w(LIVE+4,1,0);
        check("knob 4 on vibrato: the engine runs, the octave switch does not, early or late",
            resolve(KBVIB)==VIB&&resolve(KBEARLY)==ADC&&resolve(KBLATE)==0x100);
        w(LIVE+4,1,1);
        check("knob 4 on trn: the octave switch runs early and late, the engine does not",
            resolve(KBVIB)==0x100&&resolve(KBEARLY)==EARLY&&resolve(KBLATE)==LATE);
        w(LIVE+4,1,2);
        check("knob 4 factory: neither",resolve(KBVIB)==0x100&&resolve(KBEARLY)==ADC&&resolve(KBLATE)==0x100);
        w(LIVE+1,1,0); e.writeRegister("R8",77); resolve(KBL1);
        check("knob 1 blending: the latch and the blend latch both take the knob",r(0x60f2,1)==77&&r(BLEND,1)==77);
        w(LIVE+1,1,1); e.writeRegister("R8",66); resolve(KBL1);
        check("knob 1 on zones: the latch takes the knob, the blend latch zero",r(0x60f2,1)==66&&r(BLEND,1)==0);
        w(LIVE+1,1,2); e.writeRegister("R8",55); resolve(KBL1);
        check("knob 1 factory: the same",r(0x60f2,1)==55&&r(BLEND,1)==0);
        w(LIVE+3,1,0); e.writeRegister("R8",555); resolve(KBL3);
        check("knob 3 octaves: the latch takes the knob",r(0x60ea,2)==555);
        w(LIVE+3,1,1); e.writeRegister("R8",555); resolve(KBL3);
        check("knob 3 factory: the latch reads zero, which is the randomiser's deadzone",r(0x60ea,2)==0);
        check("the pool words name the dispatchers: the selector's, the rhythm's, the vibrato call, knob 4 early and late, the gate's inner",
            r(0x80002420L,4)==KBSEL&&r(0x80019d40L,4)==KBRHY&&r(0x8001a344L,4)==KBVIB
            &&r(0x800051f0L,4)==KBEARLY&&r(0x8001aeb8L,4)==KBLATE&&r(0x8001b0fcL,4)==KBK1
            &&(!seq||r(0x8001b434L,4)==KBSEL));
        println("PASS knob roles: every cell value reaches its cave, and the latches follow the roles");
    }
    void latch() throws Exception {
        fresh();
        w(LIVE,1,1);
        check("latch on: the note-on reaches latch_owner, the note-off the latch's wrapper, the hold latch_hold",
            resolve(LTON)==LOWNER&&resolve(LTOFF)==LNOTEOFF&&resolve(LTHOLD)==LHOLD);
        // A dispatcher must leave what its target takes: the key in R12 for
        // the note-on and note-off, and for latch_hold R8 (0x60a0), R9 (the
        // transpose just published) and R12 (the pitch in hand).
        e.writeRegister("R12",7); resolve(LTON);
        check("the note-on dispatcher leaves the key in R12",reg("R12")==7);
        e.writeRegister("R12",7); resolve(LTOFF);
        check("the note-off dispatcher leaves the key in R12",reg("R12")==7);
        e.writeRegister("R8",0x60a0); e.writeRegister("R9",-484&0xffffffffL); e.writeRegister("R12",1234); resolve(LTHOLD);
        check("the hold dispatcher leaves R8, R9 and R12 for latch_hold",
            reg("R8")==0x60a0&&reg("R9")==(-484&0xffffffffL)&&reg("R12")==1234);
        w(LIVE,1,0); w(0x6521+5,1,0); w(0x6504+5,1,0);
        e.writeRegister("R12",5); long p=resolve(LTON);
        check("latch off: a press writes the identity ownership and hands the key back",
            p==0x100&&reg("R12")==5&&r(0x6521+5,1)==6&&r(0x6504+5,1)==6);
        e.writeRegister("R12",5); p=resolve(LTOFF);
        check("and a release clears it on the way to the factory note-off",
            p==FNOTEOFF&&reg("R12")==5&&r(0x6521+5,1)==0);
        e.writeRegister("R12",29);
        check("a key past 28 owns nothing and still reaches the factory note-off",resolve(LTOFF)==FNOTEOFF);
        e.writeRegister("R12",1234); p=resolve(LTHOLD);
        check("latch off: the hold hands the pitch back",p==0x100&&reg("R12")==1234);
        w(0x46f1,1,2); e.writeRegister("R8",0x46f0);
        w(LIVE,1,1); resolve(LTPAD);
        check("latch on: the pad test reads pad 2 and says held",reg("R9")==2&&reg("Z")==1);
        w(LIVE,1,0); resolve(LTPAD);
        check("latch off: the pad test says not held whatever pad 2 does",reg("R9")==0&&reg("Z")==0);
        w(LIVE,1,1);
        check("latch on: the factory latch chord's timer is swallowed",resolve(LTSHIM)==0x100);
        w(LIVE,1,0);
        check("latch off: the factory latch chord's timer runs",resolve(LTSHIM)==FTIMER);
        long disp=((LTPOOL+24)-(0x8000491eL&~3L))>>2;
        check("the words name the dispatchers: the note-on, both note-off pools, the hold, and the chord's call is an MCALL to the shim's word",
            r(0x80018d38L,4)==LTON&&r(0x80005b18L,4)==LTOFF&&r(0x80006278L,4)==LTOFF&&r(0x8001a8e0L,4)==LTHOLD
            &&r(0x8000491eL,4)==(0xf01f0000L|(disp&0xffffL))&&r(LTPOOL+24,4)==LTSHIM);
        // With the latch off the factory's own latch (pads 2 & 3) holds a
        // released key in the note flags, and leaving the switch position
        // must not hand the note flags back to the touch flags: that copy is
        // the latch's exit, and there is no latch to exit (audit 2026-09-24).
        for(int on=1;on>=0;on--) {
            w(LIVE,1,on); w(S+0x340,1,1); w(S+0x341,1,0); call(SCAN);
            for(int k=0;k<29;k++){ w(S+0x21b+k,1,0); w(S+0x239+k,1,0); }
            w(S+0x2ea,1,1); w(S+0x21b+5,1,1); w(S+0x21a,1,1);
            w(S+0x340,1,0); w(S+0x341,1,1); call(SCAN);
            check("latch "+(on==1?"on: leaving its position releases a key no finger holds"
                                 :"off: leaving the position keeps what the factory latch holds")
                +", flag="+r(S+0x21b+5,1)+" count="+r(S+0x21a,1),
                on==1 ? r(S+0x21b+5,1)==0&&r(S+0x21a,1)==0 : r(S+0x21b+5,1)==1&&r(S+0x21a,1)==1);
        }
        // Nor does its hold state reach the notes.  The pin writes a stamp
        // for each held key when the preset's count moves and the restored
        // state says hold, and the blend weights a held key at its stamped
        // pitch while the switch sits in the latch position: with the latch
        // off a restored state decided 4.4 semitones of blend target.
        for(int on=1;on>=0;on--) {
            w(LIVE,1,on);
            for(int k=0;k<29;k++){ w(S+0x21b+k,1,0); w(0x60a2+2*k,2,0); }
            w(S+0x21b+5,1,1); w(0x6504+5,1,6); w(0x6521+5,1,6); w(S+0x21a,1,1);
            w(0x6582,2,0); w(0x62e2,1,0); w(0x6092,1,0); w(0x60f3,1,3);
            call(PIN);
            long stamp=(short)r(0x60a2+10,2);
            check("latch "+(on==1?"on: a preset move in hold pins the held key":"off: a preset move pins nothing")
                +", stamp="+stamp+", count seen "+r(0x6092,1),
                (on==1 ? stamp!=0 : stamp==0) && r(0x6092,1)==3);
            w(S+0x340,1,1); w(S+0x341,1,0); call(SCAN);
            long plain=blendAt(0), stamped=blendAt(-121);
            check("latch "+(on==1?"on: a stamp moves the blend's target":"off: a stamp left in RAM moves nothing")
                +", "+plain+" and "+stamped, on==1 ? stamped!=plain : stamped==plain);
        }
        // What the latch owns does not outlive it across the restart that
        // turns it off: SRAM survives, the first-use initialiser does not
        // run again, so option_boot clears the stamps, the term and the
        // ownership maps when the live byte is zero - and leaves them when
        // it is one.
        keepSlots=true;
        byte[] off=edited(); setHalf(off,0x20+2*16,0); stamp(off); plant(SLOT0,off);
        // The 29 stamps are 0x60a2..0x60da; 0x60dc is the claimed beat's
        // gate target, which the clear must leave alone.
        cold(); w(0x60a2,2,0x1234); w(0x60da,2,0x5678); w(0x60dc,2,0x4321); w(0x609e,2,0x2222); w(0x6504,1,7); w(0x653d,1,9); boot();
        check("latch off at boot: the stamps, the term and the ownership maps are cleared, and the cell after the stamps is not"
            +" live="+r(LIVE,1)+" stamps="+Long.toHexString(r(0x60a2,2))+","+Long.toHexString(r(0x60da,2))
            +" next="+Long.toHexString(r(0x60dc,2))+" term="+Long.toHexString(r(0x609e,2))+" own="+r(0x6504,1)+","+r(0x653d,1),
            r(LIVE,1)==0&&r(0x60a2,2)==0&&r(0x60da,2)==0&&r(0x60dc,2)==0x4321&&r(0x609e,2)==0&&r(0x6504,1)==0&&r(0x653d,1)==0);
        byte[] on=edited(); setHalf(on,0x20+2*16,1); setGen(on,4); stamp(on); plant(SLOT0,on);
        cold(); w(0x60a2,2,0x1234); w(0x609e,2,0x2222); w(0x6504,1,7); boot();
        check("latch on at boot: cleared just the same, nothing is held when the chip comes up",
            r(LIVE,1)==1&&r(0x60a2,2)==0&&r(0x609e,2)==0&&r(0x6504,1)==0);
        keepSlots=false;
        println("PASS latch: the four gates follow the live byte, the shim gives the factory chord back, and the latch's RAM does not outlive a boot");
    }
    // The blend's target, keys 5 and 9 held under equal pressure and key
    // 5 carrying a stamp; the switch position is whatever housekeeping
    // mirrored last.
    long blendAt(int stamp5) throws Exception {
        w(0x6158,1,0); w(S+0x306,2,0x200);
        for(int k=0;k<29;k++){ w(S+0x21b+k,1,0); w(0x6540+2*k,2,0); w(0x60a2+2*k,2,0); }
        w(S+0x21b+5,1,1); w(S+0x21b+9,1,1);
        w(0x6540+10,2,0x800); w(0x6540+18,2,0x800);
        w(0x60a2+10,2,stamp5&0xffff);
        long anchor=r(0x854+18,2);
        e.writeRegister("R12",anchor); e.writeRegister("R10",anchor);
        call(BLENDAT);
        return (short)r(0x60e0,2);
    }
    void sequencer() throws Exception {
        fresh();
        // The gate answers R10: zero to arm, nonzero to refuse - the preset
        // editor's hold flag for pad 4 first, then the live byte - and
        // spends R10 alone.
        w(LIVE+5,1,1); w(0x614d,1,0);
        e.writeRegister("R8",11); e.writeRegister("R9",22); e.writeRegister("R11",33); e.writeRegister("R12",44);
        long p=resolve(SQARM);
        check("sequencer on: the arm gate answers zero and leaves R8, R9, R11 and R12",
            p==0x100&&reg("R10")==0&&reg("R8")==11&&reg("R9")==22&&reg("R11")==33&&reg("R12")==44);
        w(LIVE+5,1,0); p=resolve(SQARM);
        check("sequencer off: the arm gate refuses",p==0x100&&reg("R10")!=0);
        w(LIVE+5,1,1); w(0x614d,1,1); p=resolve(SQARM);
        check("a pad the editor follows refuses whatever the byte says",p==0x100&&reg("R10")!=0);
        w(0x614d,1,0);
        long disp=(CHORDWORD-(CHORDCALL&~3L))>>2;
        check("the chord reaches the gate through the word in its own slack",
            r(CHORDWORD,4)==SQARM&&r(CHORDCALL,4)==(0xf01f0000L|(disp&0xffffL)));
        // The chord itself, scan by scan: pad 4 held past the hold, then a
        // pad pressed inside it.  With the byte off the hold counts and
        // never arms, and neither pad 1 nor pad 2 over a take in RAM moves
        // the mode - which is how a take restored from the record is kept
        // and cannot be played.  With it on the hold arms and pad 1 opens
        // record.
        int hold=(int)r(0x6810,2);
        w(0x61e0,1,2); w(0x6160,2,500); w(0x6162,2,540); w(0x61ee,1,3); w(0x61ef,1,7);
        w(LIVE+5,1,0); w(0x46f3,1,2);
        for(int i=0;i<hold+10;i++) call(CHORD);
        check("sequencer off: pad 4 held past the hold never arms, armed="+r(0x6156,1)+" count="+r(0x6154,2)+" of "+hold,
            r(0x6156,1)==0&&r(0x6154,2)==hold);
        w(0x46f1,1,2); call(CHORD); w(0x46f1,1,0); call(CHORD);
        check("sequencer off: pad 2 inside the hold plays nothing of the take in RAM, mode="+r(0x6158,1),r(0x6158,1)==0&&r(0x61e0,1)==2);
        w(0x46f0,1,2); call(CHORD); w(0x46f0,1,0); call(CHORD);
        check("sequencer off: pad 1 inside the hold records nothing, mode="+r(0x6158,1),r(0x6158,1)==0);
        w(0x46f3,1,0); call(CHORD);
        check("and the release clears the hold",r(0x6154,2)==0&&r(0x6156,1)==0);
        w(LIVE+5,1,1); w(0x46f3,1,2);
        for(int i=0;i<hold+10;i++) call(CHORD);
        check("sequencer on: the same hold arms, armed="+r(0x6156,1),r(0x6156,1)==1&&r(0x6154,2)==hold);
        w(0x46f0,1,2); call(CHORD); w(0x46f0,1,0); call(CHORD);
        check("sequencer on: pad 1 inside the hold opens record, mode="+r(0x6158,1),r(0x6158,1)==1);
        w(0x46f3,1,0); call(CHORD);
        println("PASS sequencer: the arm follows the live byte, and with it off the chord never leaves mode 0");
    }
    void jack() throws Exception {
        fresh();
        int zero=(int)r(MIRROR+0xc,2), period=(int)r(MIRROR+0xa,2);
        // jack_read: the reading less its zero, clamped, or zero by the byte.  It spends R8-R10.
        w(LIVE+10,1,1); w(S+0x2f0,2,zero+600); e.writeRegister("R11",33); e.writeRegister("R12",44);
        long p=resolve(JK);
        check("jack on: jack_read answers the reading less its zero and leaves R11 and R12",
            p==0x100&&reg("R8")==600&&reg("R11")==33&&reg("R12")==44);
        w(S+0x2f0,2,zero>0?zero-1:0); p=resolve(JK);
        check("jack on: a reading below the zero clamps to zero",p==0x100&&reg("R8")==0);
        w(LIVE+10,1,0); w(S+0x2f0,2,zero+600); p=resolve(JK);
        check("jack off: jack_read answers zero whatever the jack reads",p==0x100&&reg("R8")==0);
        // preset_degrees_gate: preset_degrees itself, or zero degrees published.  It spends R9 and R12.
        w(LIVE+9,1,1);
        check("presets on: the gate hands over to preset_degrees",resolve(PG)==PD);
        w(LIVE+9,1,0); w(0x60f3,1,5);
        e.writeRegister("R0",1); e.writeRegister("R2",2); e.writeRegister("R8",3); e.writeRegister("R10",4); e.writeRegister("R11",6);
        p=resolve(PG);
        check("presets off: the gate answers zero degrees, publishes them, and leaves R0, R2, R8, R10 and R11",
            p==0x100&&reg("R12")==0&&r(0x60f3,1)==0&&reg("R0")==1&&reg("R2")==2&&reg("R8")==3&&reg("R10")==4&&reg("R11")==6);
        // preset_quantize: zero with the option on; the factory's float-to-int with it off, the float still in R12.
        w(LIVE+9,1,1); e.writeRegister("R12",0x3f800000L); p=resolve(PQ);
        check("presets on: the adder's float-to-int answers zero",p==0x100&&reg("R12")==0);
        w(LIVE+9,1,0); e.writeRegister("R12",0x3f800000L); p=resolve(PQ);
        check("presets off: it hands the float on to the factory's own",p==F2I&&reg("R12")==0x3f800000L);
        // glide_cv_shim: 0x28 with the jack transposing, the factory's load with it on portamento.  It spends R9.
        w(LIVE+10,1,1); e.writeRegister("R8",S); e.writeRegister("R10",10); e.writeRegister("R11",11); e.writeRegister("R12",12); p=resolve(GA);
        check("jack on: the glide-rate addend is the constant the factory turns into zero, R10-R12 kept",
            p==0x100&&reg("R8")==0x28&&reg("R10")==10&&reg("R11")==11&&reg("R12")==12);
        w(LIVE+10,1,0); w(S+0x2f0,2,0x1234); e.writeRegister("R8",S); p=resolve(GA);
        check("jack off: the addend is the factory's own load of the jack",p==0x100&&reg("R8")==0x1234);
        boolean filtered=num("transpose_cv_filter_shift",0)>0;
        long disp=(GAPOOL-(GLIDESITE&~3L))>>2;
        check("the words: the transposer's jack and degrees words, the adder's, the glide site's MCALL and its word, and the chain's entry"
            +(filtered?" through the filter dispatch":""),
            r(CVPOOL+12,4)==JK&&r(CVPOOL+40,4)==PG&&r(0x80003914L,4)==PQ&&r(GAPOOL,4)==GA
            &&r(GLIDESITE,4)==(0xf01f0000L|(disp&0xffffL))&&r(CHAINWORD,4)==(filtered?CD:CVENTRY));
        if(filtered) {
            w(LIVE+10,1,1); check("filter built, jack on: the chain enters through the filter",resolve(CD)==CVFILTER);
            w(LIVE+10,1,0); check("filter built, jack off: the chain enters the transposer",resolve(CD)==CVENTRY);
        }
        // The transposer itself with both inputs asking for degrees: the
        // jack three periods above its zero, the add-to-pitch switch in its
        // middle position and the active pad's store at full.  With both
        // bytes off nothing rotates; with both on it does.
        w(S+0x342,1,0); w(S+0x343,1,1); w(S+0x2ef,1,0); w(0x613a,2,1023); w(S+0x2f0,2,zero+3*period); w(0x6090,1,0);
        w(LIVE+9,1,0); w(LIVE+10,1,0); call(CVENTRY);
        check("both off: the transposer rebuilds at zero degrees, state="+Long.toHexString(r(0x60fa,2))+" degrees="+r(0x60f3,1),
            r(0x60fa,2)==0xa000&&r(0x60f3,1)==0);
        w(LIVE+9,1,1); w(LIVE+10,1,1); call(CVENTRY);
        check("both on: the jack and the preset shift the table, state="+Long.toHexString(r(0x60fa,2))+" degrees="+r(0x60f3,1),
            (r(0x60fa,2)&0xff)>=3&&r(0x60f3,1)>0);
        println("PASS jack and presets: the four gates follow the live bytes, and the transposer idles at zero degrees with both off");
    }
    void pressure() throws Exception {
        fresh();
        // The three dispatchers on the factory's pool words, R12 the argument throughout.
        long[][] d={{PF,CURVE,I2F},{K1,KNOB1,FKNOB1},{K4,KNOB4,FKNOB4}};
        String[] n={"the curve","knob 1","knob 4"};
        for(int i=0;i<3;i++) {
            w(LIVE+7,1,1); e.writeRegister("R12",0x5a5a); long p=resolve(d[i][0]);
            check("fix on: "+n[i]+"'s word reaches ours with R12 kept",p==d[i][1]&&reg("R12")==0x5a5a);
            w(LIVE+7,1,0); e.writeRegister("R12",0x5a5a); p=resolve(d[i][0]);
            check("fix off: "+n[i]+"'s word reaches the factory's with R12 kept",p==d[i][2]&&reg("R12")==0x5a5a);
        }
        // The two clamp caves: the pair replayed and a return with the fix off, the skip's target with it on.
        w(LIVE+7,1,0); w(S+0x33c,4,0x1234); w(0x3216+0x1c,2,0x2345);
        e.writeRegister("R9",9); e.writeRegister("R11",11); e.writeRegister("R12",12);
        long p=resolve(C2);
        check("fix off: the gain's pair replays - the gain in R8 - and returns, R9, R11, R12 kept",
            p==0x100&&reg("R8")==0x1234&&reg("R9")==9&&reg("R11")==11&&reg("R12")==12);
        p=resolve(C1);
        check("fix off: the filter's pair replays - the history in R8 - and returns",p==0x100&&reg("R8")==0x2345);
        w(LIVE+7,1,1);
        check("fix on: the gain is skipped",resolve(C2)==0x800033d6L);
        check("fix on: the filter is skipped",resolve(C1)==0x80003506L);
        // The gain branch: mode 4 (Z set) calls whatever the byte; otherwise the byte decides.
        w(LIVE+7,1,0); e.writeRegister("Z",1); p=resolve(GN);
        check("gain, mode 4: the knob-4 call is made through the dispatch with R12 = 5",p==K4&&reg("R12")==5);
        e.writeRegister("Z",0); e.writeRegister("R12",77); p=resolve(GN);
        check("gain, fix off, not mode 4: the factory's skip",p==0x100&&reg("R12")==77);
        w(LIVE+7,1,1); e.writeRegister("Z",0); p=resolve(GN);
        check("gain, fix on, not mode 4: the call is made anyway",p==K4&&reg("R12")==5);
        // The interpolator's gate: the clamp, then the byte; off copies the target to the slot.
        w(LIVE+7,1,1); w(S+0x356,2,0); e.writeRegister("R11",5000); p=resolve(IP);
        check("fix on: the gate clamps and leaves the slot to the interpolator",p==0x100&&reg("R11")==0xfff&&reg("R9")!=0&&r(S+0x356,2)==0);
        w(LIVE+7,1,0); e.writeRegister("R11",1234); p=resolve(IP);
        check("fix off: the gate copies the target to the slot and says so",p==0x100&&reg("R9")==0&&r(S+0x356,2)==1234);
        // And the interpolator as a whole, one flush tick: straight through with the fix off, a fifth of the way with it on.
        w(LIVE+7,1,0); w(0x6036,2,1000); w(S+0x356,2,0); call(INTERP,INTERPOUT);
        check("fix off: one tick puts the whole target on the DAC slot, slot="+r(S+0x356,2),r(S+0x356,2)==1000);
        w(LIVE+7,1,1); w(0x6036,2,1000); w(S+0x356,2,0); w(0x602c,2,0); w(0x6084,2,5); call(INTERP,INTERPOUT);
        check("fix on: one tick moves the slot a fifth of the way, slot="+r(S+0x356,2),r(S+0x356,2)==200);
        // The blend's route word and the glide value.
        w(LIVE+8,1,1); e.writeRegister("R12",0x3e8); p=resolve(PB);
        check("blend on: the pitch hook routes through the conditioner with the pitch kept",p==COND&&reg("R12")==0x3e8);
        w(LIVE+8,1,0); e.writeRegister("R12",0x3e8); p=resolve(PB);
        check("blend off: the pitch hook goes straight to the remap",p==REMAPCAVE&&reg("R12")==0x3e8);
        w(LIVE+8,1,1); e.writeRegister("R9",3); p=resolve(GR);
        check("blend on: the glide rate value is zero, the index kept",p==0x100&&reg("R8")==0&&reg("R9")==3);
        w(LIVE+8,1,0); w(S+0x306,2,0x10); p=resolve(GR);
        check("blend off, knob in its deadzone: the fastest entry",p==0x100&&reg("R8")==0&&reg("R9")==3);
        w(S+0x306,2,0x200); p=resolve(GR);
        check("blend off, knob up: the factory table at the index",p==0x100&&reg("R8")==(short)r(GLIDETABLE+6,2)&&reg("R9")==3);
        long c1=(C1+0x1c-(0x800033f8L&~3L))>>2, c2=(C2+0x1c-(0x800033c0L&~3L))>>2, gn=(GN+0x1c-(0x800043a4L&~3L))>>2;
        check("the words: the three pools, the route word, the glide word, the interpolator's, and the three hooks' MCALLs onto their own words",
            r(0x80003574L,4)==PF&&r(0x800043c4L,4)==K1&&r(0x800043d0L,4)==K4&&r(0x8000336cL,4)==PB&&r(0x8001a260L,4)==GR&&r(0x8001a68cL,4)==IP
            &&r(0x800033f8L,4)==(0xf01f0000L|(c1&0xffffL))&&r(C1+0x1c,4)==C1
            &&r(0x800033c0L,4)==(0xf01f0000L|(c2&0xffffL))&&r(C2+0x1c,4)==C2
            &&r(0x800043a4L,4)==(0xf01f0000L|(gn&0xffffL))&&r(GN+0x1c,4)==GN);
        // The re-base folds a base step into the offset only while the blend
        // is the portamento.  With it off nothing slews the offset back out,
        // and the clock's fast stage adds it under every staged pitch: a knob
        // raised while notes changed, then parked, left -200 there until the
        // next boot (audit 2026-09-24).
        for(int on=1;on>=0;on--) {
            w(LIVE+8,1,on); w(0x6158,1,0); w(0x60e2,2,0); w(0x60f4,2,0xffff);
            for(long b:new long[]{1000,1500,1200}) {
                w(S+0x350,2,b); w(S+0x306,2,0x200); e.writeRegister("R12",b); call(REBASE);
            }
            long folded=(short)r(0x60e2,2);
            check("blend "+(on==1?"on: a base step with the knob up folds into the offset"
                                 :"off: nothing folds, so the fast stage adds nothing")+", offset="+folded,
                on==1 ? folded==-200 : folded==0);
        }
        // The blend's offset does not outlive it across the restart that turns it off.
        keepSlots=true;
        byte[] off=edited(); setHalf(off,0x20+2*24,0); stamp(off); plant(SLOT0,off);
        cold(); w(0x60e2,2,0x1234); boot();
        check("blend off at boot: the conditioner's offset is cleared, live="+r(LIVE+8,1),r(LIVE+8,1)==0&&r(0x60e2,2)==0);
        byte[] on=edited(); setHalf(on,0x20+2*24,1); setGen(on,4); stamp(on); plant(SLOT0,on);
        cold(); w(0x60e2,2,0x1234); boot();
        check("blend on at boot: cleared just the same, nothing sounds when the chip comes up",r(LIVE+8,1)==1&&r(0x60e2,2)==0);
        keepSlots=false;
        println("PASS pressure: the fix's three words, three hooks and the interpolator follow its byte; the blend's route, glide value and boot clear follow its own");
    }
    // option_boot_state (criterion 3, 2026-09-23): the vibrato engine's
    // state, the jack transposer's state word and the blend's re-base
    // history, cleared at every boot whichever way their bytes point - the
    // three cells the residue test found read with their option off, and
    // then, run the other way, inherited by a boot with it on.
    void state() throws Exception {
        fresh(); keepSlots=true;
        check("option_boot chains through option_boot_state to the blend tail",
            r(OB+0x58,4)==OBS&&r(OBS+0x40,4)==OBC);
        // The period as a setting: the five factory octave sites are calls
        // onto octave_period's pool words, which name its four entries.
        long[][] sites={{0x80003776L,0},{0x80003788L,4},{0x80003792L,8},{0x800035e4L,4},{0x800035faL,12},{0x800035c0L,0}};
        boolean hooked=r(OPPOOL,4)==OP&&r(OPPOOL+4,4)==OP+0x10&&r(OPPOOL+8,4)==OP+0x18&&r(OPPOOL+12,4)==OP+0x24;
        for(long[] s:sites) {
            long disp=((OPPOOL+s[1])-(s[0]&~3L))>>2;
            hooked&=r(s[0],4)==(0xf01f0000L|(disp&0xffffL));
        }
        check("the six factory octave sites call octave_period's words, and the words name its entries",hooked);
        // seq_restart_clear: called from seq_restart_init, zeroes the
        // sequencer's runtime and leaves its musical cells.
        check("seq_restart_init's second word names seq_restart_clear",r(0x8001dfa4L,4)==SQC);
        w(0x613a,2,0x111); w(0x6142,2,0x222); w(0x614d,1,3); w(0x6154,2,0x333); w(0x6158,1,2); w(0x615f,1,7);
        w(0x6160,2,0x444); w(0x61e0,1,5); w(0x61e1,1,3); w(0x61e5,1,1); w(0x61e6,2,0x555); w(0x61ee,1,9);
        w(0x622e,2,2); w(0x6230,2,5); w(0x62e2,1,1); w(0x62e3,1,4); w(0x62e8,2,0x666); w(0x62f4,2,0x777); w(0x62fe,1,1); w(0x62ff,1,1);
        w(0x6500,2,0x888); w(0x6503,1,2); w(0x657a,2,0x999); w(0x657c,1,3); w(0x657e,1,8); w(0x6580,4,0xaaaaL); w(0x6584,2,0xbbb);
        w(0x6090,1,1); w(0x6091,1,4); w(0x6092,1,5); w(0x6600,2,0xccc);
        call(SQC);
        check("seq_restart_clear zeroes the runtime: snapshot, flags, chord and mode, cursor, borrowed strip, countdown, stamps, reference, preview, audition, lamps, latch shadow, the count the rebuild saw",
            r(0x6142,2)==0&&r(0x614d,1)==0&&r(0x6154,2)==0&&r(0x6158,1)==0&&r(0x615f,1)==0&&r(0x61e1,1)==0&&r(0x61e5,1)==0&&r(0x622e,2)==0&&r(0x6230,2)==0
            &&r(0x62e3,1)==0&&r(0x62e8,2)==0&&r(0x62f4,2)==0&&r(0x62fe,1)==0&&r(0x62ff,1)==0&&r(0x6500,2)==0&&r(0x6503,1)==0&&r(0x657c,1)==0&&r(0x657e,1)==0
            &&r(0x6580,4)==0&&r(0x6584,2)==0&&r(0x6092,1)==0);
        check("and keeps the musical cells: presets, steps and count, keys, the latch's state, the tuning slot, the take's preset reference, the per-step degrees, the strip's slot, the clock's counter",
            r(0x613a,2)==0x111&&r(0x6160,2)==0x444&&r(0x61e0,1)==5&&r(0x61ee,1)==9&&r(0x62e2,1)==1&&r(0x6090,1)==1&&r(0x6091,1)==4&&r(0x6600,2)==0xccc&&r(0x657a,2)==0x999&&r(0x61e6,2)==0x555);
        for(int p:new int[]{767,484}) {
            w(0x6814,2,p);
            e.writeRegister("R8",1000); long q=resolve(OP);
            check("octave down answers -"+p,q==0x100&&(int)reg("R8")==-p);
            q=resolve(OP+0x10);
            check("octave up answers "+p,q==0x100&&reg("R8")==p);
            q=resolve(OP+0x18);
            check("two octaves answer "+2*p,q==0x100&&reg("R8")==2*p);
            e.writeRegister("R8",3000); e.writeRegister("R9",7); q=resolve(OP+0x24);
            check("the bias takes two periods off R8",q==0x100&&reg("R8")==3000-2*p);
            e.writeRegister("R9",7); q=resolve(OP+0x10);
            check("the multiplier's entry leaves R9, the octave index",q==0x100&&reg("R9")==7);
        }
        byte[] off=edited(); setHalf(off,0x20+2*20,2); setHalf(off,0x20+2*26,0); setHalf(off,0x20+2*24,0); stamp(off); plant(SLOT0,off);
        cold(); w(0x6024,2,0x1111); w(0x6026,2,0x2222); w(0x6028,2,0x3333); w(0x60fa,2,0xa035); w(0x60f4,2,0x0c82); boot();
        check("knob 4 factory, jack on portamento, blend off at boot: the vibrato's phase, depth and offset, the transposer's word and the re-base history are cleared"
            +" live="+r(LIVE+4,1)+","+r(LIVE+10,1)+","+r(LIVE+8,1)+" vib="+Long.toHexString(r(0x6024,2))+","+Long.toHexString(r(0x6026,2))+","+Long.toHexString(r(0x6028,2))
            +" jack="+Long.toHexString(r(0x60fa,2))+" base="+Long.toHexString(r(0x60f4,2)),
            r(LIVE+4,1)==2&&r(LIVE+10,1)==0&&r(LIVE+8,1)==0
            &&r(0x6024,2)==0&&r(0x6026,2)==0&&r(0x6028,2)==0&&r(0x60fa,2)==0&&r(0x60f4,2)==0xffff);
        byte[] trn=edited(); setHalf(trn,0x20+2*20,1); setHalf(trn,0x20+2*26,0); setHalf(trn,0x20+2*24,0); setGen(trn,4); stamp(trn); plant(SLOT0,trn);
        cold(); w(0x6028,2,0x3333); boot();
        check("knob 4 as trn clears the vibrato too",r(LIVE+4,1)==1&&r(0x6028,2)==0);
        byte[] on=edited(); setHalf(on,0x20+2*20,0); setHalf(on,0x20+2*26,1); setHalf(on,0x20+2*23,1); setHalf(on,0x20+2*24,1); setGen(on,6); stamp(on); plant(SLOT0,on);
        cold(); w(0x6024,2,0x1111); w(0x6026,2,0x2222); w(0x6028,2,0x3333); w(0x60fa,2,0xa035); w(0x60f4,2,0x0c82); boot();
        check("knob 4 vibrato, jack transposing, blend on at boot: cleared just the same, none of them is happening when the chip comes up"
            +" live="+r(LIVE+4,1)+","+r(LIVE+10,1)+","+r(LIVE+8,1),
            r(LIVE+4,1)==0&&r(LIVE+10,1)==1&&r(LIVE+8,1)==1
            &&r(0x6024,2)==0&&r(0x6026,2)==0&&r(0x6028,2)==0&&r(0x60fa,2)==0&&r(0x60f4,2)==0xffff);
        keepSlots=false;
        println("PASS option state: the vibrato's cells, the transposer's word and the re-base history are cleared at every boot; the six octave sites and the sequencer's clear");
    }
    void clock() throws Exception {
        fresh();
        w(LIVE+6,1,1);
        check("divider on: the ISR hook reaches the capture cave, event 10 is skipped, the pulse pools reach the clock's pulse",
            resolve(CI)==CAPTURE&&resolve(CE)==SKIP10&&resolve(PU)==CLOCKPULSE);
        w(LIVE+6,1,0);
        long p=resolve(CE);
        check("divider off: event 10 makes the factory's arp step with R12 = 0xffff",p==ARPSTEP&&reg("R12")==0xffff);
        check("divider off: the pulse pools reach pulse_defer_set",resolve(PU)==DEFER);
        // The ISR's off path: the flag getter replayed - IFR bit 5 set reads as 1 in R8 - then the factory body.
        w(0xffff10d0L,4,0x20); e.writeRegister("R12",5); p=call(CI,FBODY);
        check("divider off: the hook replays the flag getter and continues in the factory body, flag="+reg("R8"),reg("R8")==1);
        // The whole ISR from its entry, a low then a high a millisecond apart: the divider captures the edge with the byte on and
        // not with it off, where the factory body runs instead.
        for(int on=1;on>=0;on--) {
            w(LIVE+6,1,on); w(0x6232,1,0); w(0x6234,1,0); w(0x6235,1,0); w(0x6236,1,0);
            time(10); w(0xffff10d0L,4,0x20); w(0xffff1060L,4,0); call(ISR,ISREND);
            time(11); w(0xffff10d0L,4,0x20); w(0xffff1060L,4,0x20); call(ISR,ISREND);
            check((on==1?"divider on":"divider off")+": a rising edge after a low "+(on==1?"is captured":"is not captured")
                +", produced="+r(0x6234,1)+" present="+r(0x6236,1),
                r(0x6234,1)==on&&r(0x6236,1)==on);
        }
        long ci=(CI+0x24-(0x800072eeL&~3L))>>2, ce=(CE+0x1c-(0x80004e72L&~3L))>>2, sg=(0x8001d63cL-(0x80004e58L&~3L))>>2;
        check("the words: the factory's own at 0x80007334, the two hooks' MCALLs onto their dispatch words, the sequencer's gate in front of the event, and the four pulse pools",
            r(0x80007334L,4)==0x80002440L&&r(0x800072eeL,4)==(0xf01f0000L|(ci&0xffffL))&&r(CI+0x24,4)==CI
            &&r(0x80004e58L,4)==(0xf01f0000L|(sg&0xffffL))
            &&r(0x80004e72L,4)==(0xf01f0000L|(ce&0xffffL))&&r(CE+0x1c,4)==CE
            &&r(0x8000243cL,4)==PU&&r(0x80005ed8L,4)==PU&&r(0x800063fcL,4)==PU&&r(0x800065a4L,4)==PU);
        println("PASS clock: the ISR, event 10 and the pulse pools follow the divider's byte; with it off the factory body runs and nothing is captured");
    }
    void tunings() throws Exception {
        fresh();
        w(LIVE+11,1,1);
        check("tunings on: the chain's applier word reaches the applier",resolve(TA)==APPLIERCAVE);
        w(0x6090,1,1); w(S+0x6a,1,1); w(S+0x3a,1,0); long p=resolve(TK27);
        check("tunings on: key 27 takes slot 1 to slot 2, zeroes transpose mode, sets the dirty flag and returns",
            p==0x100&&r(0x6090,1)==2&&r(S+0x6a,1)==0&&r(S+0x3a,1)==1);
        p=resolve(TK27);
        check("and back to slot 1",p==0x100&&r(0x6090,1)==1);
        w(0x6090,1,0); w(S+0x3a,1,0); p=resolve(TK28);
        check("tunings on: key 28 takes slot 0 to slot 2 and sets the dirty flag",p==0x100&&r(0x6090,1)==2&&r(S+0x3a,1)==1);
        p=resolve(TK28);
        check("and back to slot 0",p==0x100&&r(0x6090,1)==0);
        w(S+0x2,1,1); e.writeRegister("R8",S); p=resolve(RG);
        check("tunings on: the remote-enable guard reads zero, Z set",p==0x100&&reg("R8")==0&&reg("Z")==1);
        w(LIVE+11,1,0);
        check("tunings off: the applier word returns",resolve(TA)==0x100);
        w(0x6090,1,1); w(S+0x6a,1,1); p=resolve(TK27);
        check("tunings off: key 27 replays the factory's load and goes on into the factory key, slot untouched",
            p==K27FACTORY&&reg("R8")==1&&r(0x6090,1)==1);
        w(S+0x2,1,0); p=resolve(TK28);
        check("tunings off: key 28 replays the factory's load and toggle and goes on into the factory key",
            p==K28FACTORY&&reg("R8")==1&&r(0x6090,1)==1);
        w(S+0x2,1,1); e.writeRegister("R8",S); p=resolve(RG);
        check("tunings off: the guard reads the flag itself, Z clear",p==0x100&&reg("R8")==1&&reg("Z")==0);
        // A slot a record or the last session left is not the table in play
        // with cell 27 off: the transposer's rebuild, the preset rotation
        // and the recorder read the slot tables by it, and nothing else puts
        // it back.  Slot 1 is made to differ so the table says which it was
        // built from (audit 2026-09-24).
        long[] slot1=new long[32];
        for(int j=0;j<32;j++) { slot1[j]=r(0x6920+2*j,2); w(0x6920+2*j,2,r(0x68e0+2*j,2)+20); }
        for(int on=1;on>=0;on--) {
            w(LIVE+11,1,on); w(0x6090,1,1); w(0x60fa,2,0); w(0x60e4,2,0);
            call(CHAIN); call(CHAIN);
            check("tunings "+(on==1?"on: slot 1 is the table in play":"off: slot 0 is, whatever the cell held")
                +", table[0]="+r(0x854,2)+", slot "+r(0x6090,1),
                on==1 ? r(0x854,2)==r(0x6920,2)&&r(0x6090,1)==1 : r(0x854,2)==r(0x68e0,2)&&r(0x6090,1)==0);
        }
        for(int j=0;j<32;j++) w(0x6920+2*j,2,slot1[j]);
        w(S+0x2,1,0); e.writeRegister("R8",S); p=resolve(RG);
        check("and Z set when the flag is zero",p==0x100&&reg("R8")==0&&reg("Z")==1);
        long k27=(TK27+0x44-(0x80003d82L&~3L))>>2, k28=(TK28+0x44-(0x80003db8L&~3L))>>2;
        long g1=(RG+0x18-(0x80006528L&~3L))>>2, g2=(RG+0x18-(0x800066aeL&~3L))>>2, g3=(RG+0x18-(0x800085daL&~3L))>>2;
        check("the words: the applier's, both keys' MCALLs onto their own words, and the three guards' MCALLs onto the guard's",
            r(0x8001a340L,4)==TA&&r(TK27+0x44,4)==TK27&&r(TK28+0x44,4)==TK28&&r(RG+0x18,4)==RG
            &&r(0x80003d82L,4)==(0xf01f0000L|(k27&0xffffL))&&r(0x80003db8L,4)==(0xf01f0000L|(k28&0xffffL))
            &&r(0x80006528L,4)==(0xf01f0000L|(g1&0xffffL))&&r(0x800066aeL,4)==(0xf01f0000L|(g2&0xffffL))&&r(0x800085daL,4)==(0xf01f0000L|(g3&0xffffL)));
        println("PASS tunings: the two edit keys, the remote-enable guard and the applier follow cell 27's byte");
    }
    byte[] mirror() { return e.readMemory(toAddr(MIRROR),END-PAY); }
    static byte[] payloadOf(byte[] rec) { return Arrays.copyOfRange(rec,PAY,END); }
    static void stamp(byte[] rec) {
        CRC32 c=new CRC32(); c.update(rec,4,8); c.update(rec,0x10,LEN-0x10);
        long v=c.getValue(); for(int i=0;i<4;i++) rec[12+i]=(byte)(v>>>(24-8*i));
    }
    static void setGen(byte[] rec,long g) { for(int i=0;i<4;i++) rec[8+i]=(byte)(g>>>(24-8*i)); }
    static void setHalf(byte[] rec,int off,int v) { rec[off]=(byte)(v>>>8); rec[off+1]=(byte)v; }
    static int half(byte[] b,int off) { return ((b[off]&255)<<8)|(b[off+1]&255); }
    void plant(long slot,byte[] rec) { e.writeMemory(toAddr(slot),rec); }
    int num(String name,int fallback) {
        String v=props.getProperty("number."+name,"").trim();
        return v.isEmpty()?fallback:Integer.parseInt(v);
    }
    // The build's record with a value changed in every section, so a load
    // that copied only part of it, or from the wrong offset, shows.
    byte[] edited() {
        byte[] rec=record.clone();
        setHalf(rec,0x20,61);                          // tie_glide_rate
        setHalf(rec,0x24,2);                           // clock_min_ms
        setHalf(rec,0x2a,700);                         // transpose_cv_period
        setHalf(rec,0x60+2*3,half(rec,0x60+2*3)+5);    // pitch entry 3
        setHalf(rec,0x100,777);                        // tuning slot 0, key 0
        setHalf(rec,0x180+2*5,1234);                   // tuning slot 2, key 5
        setHalf(rec,0x1c4,7);                          // keys per period, slot 2
        setHalf(rec,0x1c8,0x1234); setHalf(rec,0x248,16); // a mask and a length
        setHalf(rec,0x20+2*17,half(rec,0x20+2*17)==2?1:2); // knob1, another valid role
        setGen(rec,3); stamp(rec); return rec;
    }
    void defaults() throws Exception {
        fresh();
        check("a fresh boot mirrors the record the build serialized",
            Arrays.equals(mirror(),payloadOf(record)));
        for(int i=0;i<NUMBERS.length;i++)
            check("cell "+i+" holds "+NUMBERS[i],r(MIRROR+2*i,2)==num(NUMBERS[i],DEFAULTS[i]));
        for(int i=0;i<OPTIONS.length;i++) {
            long v=r(MIRROR+2*(16+i),2);
            check("cell "+(16+i)+" holds the build's "+OPTIONS[i],v==num(OPTIONS[i],0)&&v<=OPTION_MAX[i]);
        }
        boolean live=true;
        for(int i=0;i<16;i++) if(r(LIVE+i,1)!=(r(MIRROR+2*(16+i),2)&0xff)) live=false;
        check("the live option bytes are cells 16..31 as booted",live);
        check("the blend latch starts at zero",r(BLEND,1)==0);
        check("the reserved cells are zero, and cell 10 is the period",r(MIRROR+2*11,4)==0&&r(MIRROR+2*10,2)==num("octave_units",484)&&r(MIRROR+2*28,4)==0&&r(MIRROR+2*30,4)==0);
        check("nothing loaded: state clean, no slot, generation zero",
            r(STATE,1)==0&&r(STATE+1,1)==0xff&&r(STATE+4,4)==0);
        check("the pitch curve is the emitted one",
            Arrays.equals(e.readMemory(toAddr(0x6840),158),e.readMemory(toAddr(0x80019bc0L),158)));
        check("the tuning tables are the emitted ones",
            Arrays.equals(e.readMemory(toAddr(0x68e0),192),e.readMemory(toAddr(0x80019af8L),192)));
        check("keys per period are the emitted ones",
            Arrays.equals(e.readMemory(toAddr(0x69a0),6),e.readMemory(toAddr(0x8001e2d0L),6)));
        check("settings_newest finds nothing in erased slots",call(SNEWEST)==0);
        println("PASS a fresh boot mirrors the image's own tables and numbers");
    }
    void loads() throws Exception {
        fresh(); byte[] rec=edited(); plant(SLOT0,rec); keepSlots=true; cold();
        check("a valid record in slot 0 overrides the mirror",Arrays.equals(mirror(),payloadOf(rec)));
        check("state names slot 0 and generation 3",
            r(STATE,1)==0&&r(STATE+1,1)==0&&r(STATE+4,4)==3);
        check("the live bytes follow the loaded record's option cells",
            r(LIVE+1,1)==half(rec,0x20+2*17)&&r(LIVE,1)==half(rec,0x20+2*16));
        // The readers: the applier copies a slot's table out of the mirror
        // into RAM 0x854 when its guard is clear.
        w(0x6090,1,2); w(0x60e4,2,0); call(APPLIER);
        check("the applier copies slot 2 out of the mirror",r(0x854+2*5,2)==1234);
        w(0x6090,1,0); w(0x60e4,2,0); call(APPLIER);
        check("and slot 0",r(0x854,2)==777);
        // The pitch remap interpolates between neighbours, so a flat mirror
        // curve remaps every raw pitch to that one value.
        for(int i=0;i<79;i++) w(0x6840+2*i,2,1000);
        w(0x6028,2,0); e.writeRegister("R12",0x300); stubChain=true; call(REMAP); stubChain=false;
        check("the pitch remap reads the mirror",r(0x3212,2)==1000);
        // A falling curve: every segment interpolates between its own two
        // entries, inside the DAC.  (Audit 2026-09-24: an unsigned divide
        // put a falling segment's output near 0x6e16.)
        for(int i=0;i<79;i++) w(0x6840+2*i,2,3900-40*i);
        stubChain=true;
        boolean inside=true; String worst="";
        for(int p=0;p<0x1000;p+=37) {
            w(0x6028,2,0); e.writeRegister("R12",p); call(REMAP);
            long out=r(0x3212,2);
            if(out<3900-40*78||out>3900) { inside=false; worst=p+"->"+Long.toHexString(out); }
        }
        stubChain=false;
        check("a falling pitch curve interpolates inside its own entries "+worst,inside);
        for(int i=0;i<79;i++) w(0x6840+2*i,2,0);
        boot();
        check("a warm reset reloads the record",Arrays.equals(mirror(),payloadOf(rec)));
        if(clock) {
            long perMs=r(0x6244,4);
            check("clock_init scales clock_min_ms from the mirror",perMs!=0&&r(0x624c,4)==perMs*2);
        }
        if(seq) {
            // seq_glide, playing with a tie in hand and the knob up: the
            // rate it stores is the tie's, off cell 0.
            w(0x6158,1,2); w(0x3866,2,0x100); w(0x61e5,1,1); call(0x8001b610L);
            check("seq_glide takes the tie rate from the mirror",r(0x2eee,2)==61);
            w(0x6158,1,0); w(0x61e5,1,0);
        }
        keepSlots=false;
        println("PASS a record loads, the readers read the mirror, a warm reset reloads");
    }
    void rejections() throws Exception {
        fresh(); byte[] good=edited(); keepSlots=true;
        // offset, width, value: each one thing the validator refuses, with
        // the CRC restamped so only that check can be the reason.
        // Keys per period: 33 is past the rotation's table, 128 past a .kbm.
        long keys=props.getProperty("block.preset_entry","0").trim().equals("1")?33:128;
        long[][] bad={{0,1,0xff},{4,2,1},{4,2,3},{6,2,0x299},{8,4,0},{0x10,2,0x1234},
            {0x20,2,0},{0x20,2,1025},{0x24,2,5},{0x2e,2,65},{0x60,2,0x1000},{0xfe,2,0x1000},
            {0x100,2,0x4000},{0x1c0,2,0},{0x1c4,2,keys},{0x248,2,33},
            {0x34,2,0},{0x34,2,99},{0x34,2,2001},{0x36,2,1},{0x56,2,2},{0x58,2,1},{0x42,2,3},{0x44,2,5},{0x48,2,3}};   // cell 10 is the period, bounded 1..2000; 11 is reserved; cell 27 is the tunings' switch: 2 is out of range, 28 is reserved
        for(long[] b:bad) {
            byte[] rec=good.clone();
            for(int i=0;i<b[1];i++) rec[(int)b[0]+i]=(byte)(b[2]>>>(8*(b[1]-1-i)));
            stamp(rec); plant(SLOT0,rec); cold();
            check("rejected: "+b[1]+" byte(s) at 0x"+Long.toHexString(b[0])+" = "+b[2],
                Arrays.equals(mirror(),payloadOf(record))&&r(STATE+1,1)==0xff);
        }
        byte[] rec=good.clone(); rec[0x61]^=1; plant(SLOT0,rec); cold();
        check("rejected: a flipped payload bit",r(STATE+1,1)==0xff);
        rec=good.clone(); setHalf(rec,0x4e,0); setHalf(rec,0x50,1); stamp(rec); plant(SLOT0,rec); cold();
        check("rejected: pressure_portamento without pressure_fix",r(STATE+1,1)==0xff);
        rec=good.clone(); setHalf(rec,0x4e,0); setHalf(rec,0x50,0); stamp(rec); plant(SLOT0,rec); cold();
        check("and both off loads",r(STATE+1,1)==0&&r(LIVE+7,1)==0&&r(LIVE+8,1)==0);
        plant(SLOT0,good); cold();
        check("and the good record loads again",r(STATE+1,1)==0);
        rec=good.clone(); setHalf(rec,0x100+2*31,0x3fff); setHalf(rec,0x34,100); stamp(rec); plant(SLOT0,rec); cold();
        check("a tuning entry of 0x3fff and the period's floor, 100, load",
            r(STATE+1,1)==0&&r(0x68e0+2*31,2)==0x3fff&&r(MIRROR+20,2)==100);
        keepSlots=false;
        println("PASS header, CRC, image, period and every bound refuse a record");
    }
    void slots() throws Exception {
        fresh(); keepSlots=true;
        byte[] a=edited(); setGen(a,4); stamp(a);
        byte[] b=edited(); setHalf(b,0x20,62); setGen(b,5); stamp(b);
        plant(SLOT0,a); plant(SLOT1,b); cold();
        check("the newer generation wins, in slot 1",
            r(0x6800,2)==62&&r(STATE+1,1)==1&&r(STATE+4,4)==5);
        setGen(a,6); stamp(a); plant(SLOT0,a); cold();
        check("and in slot 0",r(0x6800,2)==61&&r(STATE+1,1)==0&&r(STATE+4,4)==6);
        setGen(a,0xffffffffL); stamp(a); setGen(b,1); stamp(b); plant(SLOT0,a); plant(SLOT1,b); cold();
        check("generation wraps: 1 is newer than 0xffffffff",r(STATE+1,1)==1&&r(STATE+4,4)==1);
        b[0x61]^=1; plant(SLOT1,b); cold();
        check("a corrupt newer slot falls back to the older valid one",
            r(STATE+1,1)==0&&r(STATE+4,4)==0xffffffffL);
        keepSlots=false;
        println("PASS two slots: the newest wins, across the wrap, and the older stands in");
    }
    // 0x3f01 on a keyboard with no valid record drops the edits to the
    // image's own settings, which is what the next boot would give: with
    // both slots erased, and with both corrupt.  A valid record still wins.
    // (Audit 2026-09-24, finding 4: the reload copied nothing when neither
    // slot was valid, and the edits stayed.)
    void reloads() throws Exception {
        fresh(); keepSlots=true;
        nrpn(0,61); nrpn(2,1); nrpn(0x100,777); nrpn(0x1e5,9); w(0x60e4,2,0xa5a0);
        check("the edits are in the mirror",r(0x6800,2)==61&&r(0x6804,2)==1&&r(0x68e0,2)==777&&r(0x6a28+2*5,2)==9);
        nrpn(0x3f01,0);
        check("0x3f01 with both slots erased gives the image's own settings back",Arrays.equals(mirror(),payloadOf(record)));
        check("no slot loaded, the state clean and the applier's guard clear",
            r(STATE+1,1)==0xff&&r(STATE,1)==0&&r(0x60e4,2)==0);
        byte[] a=edited(); a[0x61]^=1;
        byte[] b=edited(); setGen(b,4); stamp(b); b[0x101]^=1;
        plant(SLOT0,a); plant(SLOT1,b); cold();
        check("two corrupt slots boot the image's own settings",Arrays.equals(mirror(),payloadOf(record))&&r(STATE+1,1)==0xff);
        nrpn(0,61); nrpn(0x3f01,0);
        check("and 0x3f01 drops an edit back to them",Arrays.equals(mirror(),payloadOf(record))&&r(STATE+1,1)==0xff);
        byte[] good=edited(); plant(SLOT1,good); nrpn(0,62); nrpn(0x3f01,0);
        check("with a valid record beside a corrupt one, 0x3f01 gives the record",
            Arrays.equals(mirror(),payloadOf(good))&&r(STATE+1,1)==1&&r(STATE+4,4)==3);
        keepSlots=false;
        println("PASS reload without a valid record: erased and corrupt slots give the image's own settings");
    }
    // The strip's lamp and its recorder split tie from rest at the same
    // place, settings cell 1.  With the cell moved to 3000, a finger let go
    // at 2500 - past the build's 2048, short of the cell - shows the rest
    // lamp while it is down and records a rest; at 3500 the tie lamp and a
    // tie.  (2026-09-24, beside the audit's finding 5: the lamp compared
    // with the build's number and could show a tie the release then
    // recorded as a rest.)
    void stripHalfway() throws Exception {
        fresh();
        nrpn(1,3000);
        check("cell 1 takes 3000",r(0x6802,2)==3000);
        w(0x6158,1,1); w(0x61e0,1,0); w(0x61e4,1,0);
        int[][] cases={{2500,num("strip_led_rest_units",0),0x7ffe},{3500,num("strip_led_tie_units",4095),0x7fff}};
        for(int i=0;i<cases.length;i++) {
            int[] c=cases[i];
            w(S+0x206,1,1); w(S+0x1fe,2,c[0]); call(0x8001e000L);
            check("the finger down at "+c[0]+" lights "+(c[2]==0x7fff?"the tie":"the rest")+" lamp: "+r(0x38be,2),r(0x38be,2)==c[1]);
            w(S+0x206,1,0); call(0x8001e000L);
            check("and let go there it records "+(c[2]==0x7fff?"a tie":"a rest")+": "+Long.toHexString(r(0x6160+2*i,2)),
                r(0x61e0,1)==i+1&&r(0x6160+2*i,2)==c[2]);
        }
        w(0x6158,1,0);
        println("PASS the strip's lamp and its recorder both split tie from rest at cell 1");
    }
    @Override public void run() throws Exception {
        String[] args=getScriptArgs();
        String mode=args[0]; seq=mode.contains("seq"); clock=mode.contains("clock");
        props.load(Files.newBufferedReader(Paths.get(args[1])));
        record=Files.readAllBytes(Paths.get(args[2]));
        try {
            defaults(); loads(); rejections(); slots(); reloads(); stripHalfway(); strayDataEntry(); receive(); commits(); dumps(); knobs(); patterns(); latch(); sequencer(); jack(); pressure(); state(); clock(); tunings();
            println("SETTINGS REGRESSION PASS: "+mode+", "+checks+" assertions; no physical flash testing, no real reset.");
        } finally { if(e!=null)e.dispose(); }
    }
}
