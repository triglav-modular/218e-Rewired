// The three ways a configuration reaches the instrument, booted side by
// side: built into an image as its defaults, sent over MIDI the way the
// page sends it and committed, and laid in a settings slot the way the
// suites lay it (SettingsRecord).  The suites run one image under every
// configuration a check needs by laying records, so this is what makes a
// check under a laid record a check of the image built with those options.
//
// For each configuration the runner names - its image, built by
// tools/build.py from the same config, and the record the same build
// serialized - three cold boots, each of the whole RAM:
//   built in:  that image's flash over the one imported, both slots erased;
//   sent:      the imported image, every parameter of the record through
//              the factory MIDI parser in the page's order, 0x3f00 with its
//              key and the settings scan that commits it, then a power cycle;
//   laid:      the imported image with the record in slot 0.
// All three must leave the same RAM below the stack, the settings loader's
// own slot and generation apart, and the same again after one pass of the
// per-scan chain has made the selected slot the live key table.  The sent
// record, as committed, must be the record byte for byte.
//
// Arguments: name=<image.hex>|<settings.bin> per configuration, and
// neuter=<cell> to lay every record with that option cell changed - the
// control that shows the comparison can fail.  Run by tools/test_controls.py,
// test_persistence.py and test_clock.py.  Never flashes.
//@category Buchla218.Tests
import java.nio.file.*;
import java.util.*;

public class SettingsEquivalence extends SettingsRegression {
    // The settings loader's own cells: which slot it loaded (0xff for none)
    // and that slot's generation.  A boot of an image's own defaults and a
    // boot of a record differ there by design.
    static final long LOADED=STATE+1, GENERATION=STATE+4;
    // The stack, from 0x7000 up to where the harness starts it at 0x7800:
    // what a boot's calls leave below the stack pointer is dead, and a boot
    // that loads a record calls deeper than one that finds none.
    static final int STACK=0x7000;
    TreeMap<Long,Byte> image;          // flash to write over the imported image before a boot
    @Override void cold() throws Exception {
        if(image!=null) {
            // Contiguous runs, one write each.
            long start=-1, last=-1; ByteArrayOutputStreamLite run=new ByteArrayOutputStreamLite();
            for(Map.Entry<Long,Byte> b:image.entrySet()) {
                if(b.getKey()!=last+1) { if(start>=0) e.writeMemory(toAddr(start),run.bytes()); start=b.getKey(); run.reset(); }
                run.add(b.getValue()); last=b.getKey();
            }
            if(start>=0) e.writeMemory(toAddr(start),run.bytes());
        }
        super.cold();
    }
    static class ByteArrayOutputStreamLite {
        byte[] b=new byte[4096]; int n;
        void add(byte v) { if(n==b.length) b=Arrays.copyOf(b,2*n); b[n++]=v; }
        void reset() { n=0; }
        byte[] bytes() { return Arrays.copyOf(b,n); }
    }
    static TreeMap<Long,Byte> readHex(Path path) throws Exception {
        TreeMap<Long,Byte> out=new TreeMap<>(); long upper=0;
        for(String line:Files.readAllLines(path)) {
            line=line.trim();
            if(line.isEmpty()) continue;
            if(line.charAt(0)!=':') throw new Exception("not Intel HEX: "+path);
            int n=Integer.parseInt(line.substring(1,3),16), at=Integer.parseInt(line.substring(3,7),16);
            int type=Integer.parseInt(line.substring(7,9),16);
            if(type==0) for(int i=0;i<n;i++) out.put(upper+at+i,(byte)Integer.parseInt(line.substring(9+2*i,11+2*i),16));
            else if(type==1) break;
            else if(type==2) upper=(long)Integer.parseInt(line.substring(9,13),16)<<4;
            else if(type==4) upper=(long)Integer.parseInt(line.substring(9,13),16)<<16;
        }
        return out;
    }
    // Every parameter of a record, as web/buildlib.js nrpnParamsOf lists
    // them: the 32 cells, the pitch curve, the three tuning slots, keys per
    // period, each pattern mask in thirds of 14, 14 and 4 bits, the lengths.
    static List<int[]> params(byte[] rec) {
        List<int[]> out=new ArrayList<>();
        for(int i=0;i<32;i++) out.add(new int[]{i,half(rec,0x20+2*i)});
        for(int i=0;i<79;i++) out.add(new int[]{0x80+i,half(rec,0x60+2*i)});
        for(int i=0;i<96;i++) out.add(new int[]{0x100+i,half(rec,0x100+2*i)});
        for(int i=0;i<3;i++) out.add(new int[]{0x160+i,half(rec,0x1c0+2*i)});
        for(int i=0;i<96;i++) {
            long m=half(rec,0x1c8+4*(i/3))|((long)half(rec,0x1ca+4*(i/3))<<16);
            out.add(new int[]{0x180+i,(int)((m>>>(14*(i%3)))&0x3fff)});
        }
        for(int i=0;i<32;i++) out.add(new int[]{0x1e0+i,half(rec,0x248+2*i)});
        return out;
    }
    byte[] ram() { return e.readMemory(toAddr(0),STACK); }
    // A boot's whole RAM, and the same after one pass of the per-scan chain.
    byte[][] snapshot() throws Exception {
        byte[] boot=ram(); call(CHAIN); return new byte[][]{boot,ram()};
    }
    static String differ(byte[] a,byte[] b) {
        StringBuilder out=new StringBuilder(); int n=0;
        for(int i=0;i<a.length;i++) {
            if(i==LOADED||(i>=GENERATION&&i<GENERATION+4)) continue;
            if(a[i]!=b[i]) { if(n++<12) out.append(String.format(" %04x:%02x/%02x",i,a[i]&255,b[i]&255)); }
        }
        return n==0?null:n+" byte(s):"+out;
    }
    static String spans(byte[] a,byte[] b) {
        StringBuilder out=new StringBuilder(); int start=-1;
        for(int i=0;i<=a.length;i++) {
            boolean d=i<a.length&&a[i]!=b[i];
            if(d&&start<0) start=i;
            if(!d&&start>=0) { out.append(String.format(" %04x-%04x",start,i)); start=-1; }
        }
        return out.length()==0?"nothing":out.toString().trim();
    }
    @Override public void run() throws Exception {
        if(SettingsRecord.named()!=null)
            throw new Exception("SettingsEquivalence lays its own records: unset "+SettingsRecord.ENV);
        int neuter=-1;
        LinkedHashMap<String,String[]> named=new LinkedHashMap<>();
        for(String a:getScriptArgs()) {
            int i=a.indexOf('=');
            if(i<0) throw new Exception("SettingsEquivalence takes name=<image.hex>|<settings.bin>, not "+a);
            if(a.startsWith("neuter=")) { neuter=Integer.parseInt(a.substring(7)); continue; }
            named.put(a.substring(0,i),a.substring(i+1).split("\\|"));
        }
        if(named.isEmpty()) throw new Exception("SettingsEquivalence was given no configuration");
        List<String> failures=new ArrayList<>();
        byte[][] first=null; String firstName=null;
        try {
            for(Map.Entry<String,String[]> c:named.entrySet()) {
                String name=c.getKey();
                byte[] rec=Files.readAllBytes(Paths.get(c.getValue()[1]));
                // Built in.
                image=readHex(Paths.get(c.getValue()[0])); keepSlots=false;
                fresh(); byte[][] built=snapshot();
                boolean own=r(LOADED,1)==0xff&&r(GENERATION,4)==0;
                image=null;
                // Sent: the page's parameters through the factory parser,
                // committed by the settings scan, then a power cycle.
                fresh(); keepSlots=true; slotWrites=0;
                List<int[]> ps=params(rec);
                for(int[] p:ps) nrpn(p[0],p[1]);
                nrpn(0x3f00,0x2a2a); call(SETSCAN);
                boolean committed=r(STATE,1)==2&&r(STATE+1,1)==0&&slotWrites==3;
                byte[] slot=e.readMemory(toAddr(SLOT0),LEN);
                cold(); byte[][] sent=snapshot();
                boolean sentLoaded=r(LOADED,1)==0;
                // Laid, as the suites lay it; neutered, with one option cell moved.
                byte[] laid=rec.clone();
                if(neuter>=0) {
                    int v=half(laid,0x20+2*neuter), top=OPTION_MAX[neuter-16];
                    setHalf(laid,0x20+2*neuter,v==top?0:v+1); stamp(laid);
                    println("NEUTER "+name+": cell "+neuter+" laid as "+half(laid,0x20+2*neuter)+", built and sent as "+v);
                }
                fresh(); SettingsRecord.lay(e,laid); keepSlots=true; cold();
                byte[][] put=snapshot();
                boolean laidLoaded=r(LOADED,1)==0;
                keepSlots=false;
                try {
                    check(name+": a boot of its image loads no record",own);
                    check(name+": "+ps.size()+" parameters sent and committed to slot 0",committed&&ps.size()==338);
                    check(name+": the committed record is the build's, byte for byte"
                        +(Arrays.equals(slot,Arrays.copyOf(rec,LEN))?"":": "+differ(slot,Arrays.copyOf(rec,LEN))),
                        Arrays.equals(slot,Arrays.copyOf(rec,LEN)));
                    check(name+": the sent and the laid record both load",sentLoaded&&laidLoaded);
                    for(int s=0;s<2;s++) {
                        String when=s==0?"booted":"after a scan";
                        String d1=differ(built[s],sent[s]), d2=differ(built[s],put[s]);
                        check(name+", "+when+": sent and built in leave the same RAM"+(d1==null?"":": "+d1),d1==null);
                        check(name+", "+when+": laid and built in leave the same RAM"+(d2==null?"":": "+d2),d2==null);
                    }
                    // Where this configuration's machine differs from the
                    // first one's: what a check has to read to tell them apart.
                    if(first==null) { first=built; firstName=name; }
                    else println("DIFFERS "+name+" from "+firstName+": booted "+spans(first[0],built[0])
                        +"; after a scan "+spans(first[1],built[1]));
                    println("PASS "+name+": built in, sent and laid boot the same RAM, live bytes "
                        +HexFormat.of().formatHex(Arrays.copyOfRange(built[0],(int)LIVE,(int)LIVE+12)));
                } catch(Exception ex) { failures.add(ex.getMessage()); println(ex.getMessage()); }
            }
            if(!failures.isEmpty()) throw new Exception("SETTINGS EQUIVALENCE FAIL: "+failures);
            println("SETTINGS EQUIVALENCE PASS: "+named.size()+" configuration(s), "+checks
                +" assertions; built in, sent over MIDI and laid in a slot boot the same RAM");
        } finally { if(e!=null)e.dispose(); }
    }
}
