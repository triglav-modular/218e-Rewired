// A settings record laid in the chip's first settings slot before a boot,
// the way a send leaves it: the page's NRPN writes fill the mirror, 0x3f00
// commits the mirror to a slot, and 0x3f04 restarts into it.  One image
// then runs under every configuration a check needs, because every option
// that can ship changes only the record's data (2026-09-28).  The runners
// name the record, which tools/build.py serialized for that configuration,
// in REWIRED_SETTINGS_RECORD; SettingsEquivalence proves that a record
// built in, sent and laid here all boot the same machine.  Unset, nothing
// is laid and the image boots its own defaults.
import ghidra.app.emulator.EmulatorHelper;
import ghidra.program.model.address.AddressSpace;
import java.nio.file.*;
import java.util.Arrays;

public class SettingsRecord {
    static final long SLOT0=0x8003d000L, SLOT1=0x8003d800L;
    static final int LENGTH=0x2a8, PAYLOAD=0x20, END=0x288;
    static final String ENV="REWIRED_SETTINGS_RECORD";
    static String path; static byte[] cached;
    // The record the runner named, or null.
    static byte[] named() throws Exception {
        String p=System.getenv(ENV);
        if(p==null||p.isEmpty()) return null;
        if(!p.equals(path)) {
            byte[] r=Files.readAllBytes(Paths.get(p));
            if(r.length<LENGTH) throw new Exception(ENV+" names "+r.length+" bytes, not a record: "+p);
            cached=r; path=p;
        }
        return cached;
    }
    // Both slots erased, the record in the first: what a fresh chip holds
    // after one committed send.
    static void lay(EmulatorHelper e,byte[] record) {
        AddressSpace flash=e.getProgram().getAddressFactory().getDefaultAddressSpace();
        byte[] ff=new byte[0x800]; Arrays.fill(ff,(byte)255);
        e.writeMemory(flash.getAddress(SLOT0),ff); e.writeMemory(flash.getAddress(SLOT1),ff);
        e.writeMemory(flash.getAddress(SLOT0),Arrays.copyOf(record,LENGTH));
    }
    // What a boot of that record must leave: the mirror is the record's
    // payload, and each live option byte the low byte of its cell (16..31).
    static String landed(EmulatorHelper e,byte[] record) {
        AddressSpace ram=e.getProgram().getAddressFactory().getDefaultAddressSpace();
        byte[] mirror=e.readMemory(ram.getAddress(0x6800),END-PAYLOAD);
        if(!Arrays.equals(mirror,Arrays.copyOfRange(record,PAYLOAD,END)))
            return "the mirror is not the record's payload: the loader refused it (another image's marker?)";
        byte[] live=e.readMemory(ram.getAddress(0x6d28),16);
        for(int i=0;i<16;i++)
            if(live[i]!=record[PAYLOAD+2*(16+i)+1])
                return "live option byte "+i+" is "+live[i]+", its cell "+record[PAYLOAD+2*(16+i)+1];
        return null;
    }
}
