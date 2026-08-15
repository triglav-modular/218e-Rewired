// Export functions, decompilation, call graph, symbols, references, and instructions.
// @category Export

import java.io.*;
import java.util.*;
import ghidra.app.decompiler.*;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;

public class ExportAnalysis extends GhidraScript {
    public void run() throws Exception {
        String outDir = getScriptArgs().length > 0 ? getScriptArgs()[0] : ".";
        File dir = new File(outDir);
        dir.mkdirs();

        DecompInterface decomp = new DecompInterface();
        decomp.toggleCCode(true);
        decomp.toggleSyntaxTree(true);
        decomp.openProgram(currentProgram);

        FunctionManager fm = currentProgram.getFunctionManager();
        Listing listing = currentProgram.getListing();
        ReferenceManager rm = currentProgram.getReferenceManager();

        try (PrintWriter pw = new PrintWriter(new File(dir, "functions.txt"));
             PrintWriter cg = new PrintWriter(new File(dir, "calls.txt"));
             PrintWriter dec = new PrintWriter(new File(dir, "decompiled.c"));
             PrintWriter ins = new PrintWriter(new File(dir, "instructions.txt"));
             PrintWriter refs = new PrintWriter(new File(dir, "references.txt"))) {

            FunctionIterator fit = fm.getFunctions(true);
            int count = 0;
            while (fit.hasNext() && !monitor.isCancelled()) {
                Function f = fit.next();
                count++;
                pw.printf("%s %s size=%d params=%d%n", f.getEntryPoint(), f.getName(),
                          f.getBody().getNumAddresses(), f.getParameterCount());

                Set<Function> callees = f.getCalledFunctions(monitor);
                for (Function c : callees) {
                    cg.printf("%s %s -> %s %s%n", f.getEntryPoint(), f.getName(),
                              c.getEntryPoint(), c.getName());
                }

                dec.printf("\n/* ===== %s %s size=%d ===== */\n", f.getEntryPoint(),
                           f.getName(), f.getBody().getNumAddresses());
                DecompileResults res = decomp.decompileFunction(f, 60, monitor);
                if (res.decompileCompleted() && res.getDecompiledFunction() != null) {
                    dec.println(res.getDecompiledFunction().getC());
                } else {
                    dec.println("/* DECOMPILE FAILED: " + res.getErrorMessage() + " */");
                }
            }
            pw.println("TOTAL=" + count);

            InstructionIterator iit = listing.getInstructions(true);
            while (iit.hasNext()) {
                Instruction i = iit.next();
                ins.printf("%s  %-28s", i.getAddress(), i.toString());
                Reference[] rr = i.getReferencesFrom();
                if (rr.length > 0) {
                    ins.print(" ; refs:");
                    for (Reference r : rr) ins.print(" " + r.getToAddress() + "[" + r.getReferenceType() + "]");
                }
                ins.println();
            }

            AddressIterator ait = rm.getReferenceSourceIterator(currentProgram.getMemory(), true);
            while (ait.hasNext()) {
                Address a = ait.next();
                for (Reference r : rm.getReferencesFrom(a)) {
                    refs.printf("%s -> %s %s op=%d%n", a, r.getToAddress(), r.getReferenceType(), r.getOperandIndex());
                }
            }
        } finally {
            decomp.dispose();
        }
    }
}
