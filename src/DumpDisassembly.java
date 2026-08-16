// Disassemble a whole address range to a file, for offline analysis.
// Usage: -postScript DumpDisassembly.java <start> <end> <outfile>
// @category Analysis
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Instruction;
import java.io.PrintWriter;

public class DumpDisassembly extends GhidraScript {
    public void run() throws Exception {
        String[] args = getScriptArgs();
        long start = Long.parseLong(args[0], 16);
        long end = Long.parseLong(args[1], 16);
        PrintWriter out = new PrintWriter(args[2]);
        Address addr = toAddr(start);
        int emitted = 0;
        while (addr.getOffset() < end) {
            Instruction insn = getInstructionAt(addr);
            if (insn == null) {
                try {
                    disassemble(addr);
                } catch (Exception e) {
                    // fall through; undisassemblable bytes are skipped below
                }
                insn = getInstructionAt(addr);
            }
            if (insn == null) {
                addr = addr.add(2);
                continue;
            }
            out.println(String.format("%08x\t%s", addr.getOffset(), insn.toString()));
            emitted++;
            addr = addr.add(insn.getLength());
        }
        out.close();
        println("instructions written: " + emitted);
    }
}
