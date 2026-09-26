// The chip's alignment rule, which the emulator does not enforce.  On the
// AT32UC3B a word or doubleword access that is not word-aligned, or a
// halfword access at an odd address, raises an address exception (AVR32UC
// TRM 3.5, 3.9.1.19); Ghidra's p-code just moves the bytes.  One ST.W to
// 0x622e passed every suite that way and hung the first 3.0 image at boot on
// the instrument (2026-09-26).  Installed on every EmulatorHelper the
// harnesses create, so every scenario they run checks alignment as well.
import ghidra.app.emulator.EmulatorHelper;
import ghidra.app.emulator.MemoryAccessFilter;
import ghidra.pcode.emulate.EmulateExecutionState;
import ghidra.program.model.address.AddressSpace;

public class AlignGuard {
    static EmulatorHelper install(EmulatorHelper e) {
        AddressSpace memory = e.getProgram().getAddressFactory().getDefaultAddressSpace();
        e.getEmulator().addMemoryAccessFilter(new MemoryAccessFilter() {
            @Override protected void processRead(AddressSpace space, long offset, int size, byte[] values) {
                check(space, offset, size, "load");
            }
            @Override protected void processWrite(AddressSpace space, long offset, int size, byte[] values) {
                check(space, offset, size, "store");
            }
            void check(AddressSpace space, long offset, int size, String kind) {
                // Instruction fetch reads code at halfword steps; only data counts.
                if (space != memory || e.getEmulateExecutionState() == EmulateExecutionState.INSTRUCTION_DECODE) return;
                int mask = size >= 4 ? 3 : size == 2 ? 1 : 0;
                if ((offset & mask) != 0) {
                    throw new IllegalStateException(String.format(
                        "misaligned %d-byte %s at %08x, PC=%08x: the chip takes an address exception here",
                        size, kind, offset, e.getExecutionAddress().getOffset()));
                }
            }
        });
        return e;
    }
}
