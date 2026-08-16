// Disassemble address ranges of the loaded image, for investigation only.
// Usage: -postScript DisasmRange.java <start>:<count> [<start>:<count> ...]
// @category Analysis
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.mem.MemoryBlock;

public class DisasmRange extends GhidraScript {
    public void run() throws Exception {
        String[] args = getScriptArgs();
        for (String spec : args) {
            String[] parts = spec.split(":");
            long start = Long.parseLong(parts[0], 16);
            int count = Integer.parseInt(parts[1]);
            Address addr = toAddr(start);
            println("=== " + parts[0] + " ===");
            for (int i = 0; i < count; i++) {
                Instruction insn = getInstructionAt(addr);
                if (insn == null) {
                    disassemble(addr);
                    insn = getInstructionAt(addr);
                }
                if (insn == null) {
                    println(String.format("%08x  <undisassembled>", addr.getOffset()));
                    break;
                }
                println(String.format("%08x  %s", addr.getOffset(), insn.toString()));
                addr = addr.add(insn.getLength());
            }
        }
    }
}
