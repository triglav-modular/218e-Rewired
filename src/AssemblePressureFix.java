// Assemble and print the AVR32 byte patches for the Buchla 218e pressure-curve modification.
//@category Buchla218

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import ghidra.app.plugin.assembler.Assembler;
import ghidra.app.plugin.assembler.Assemblers;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;

public class AssemblePressureFix extends GhidraScript {
    // Build settings resolved by tools/build.py from config/218e.toml and
    // handed over as a properties file (the single script argument).  Keys:
    //   block.<name>   = 1|0   emit this patch, or leave the factory bytes
    //   feature.<name> = 1|0   emit an optional section inside a block
    //   table.<name>   = comma-separated halfword values
    // Missing keys default to 1 / empty, so the script still assembles the
    // full patch set when run by hand without a properties file.
    private Properties cfg = new Properties();
    private Assembler assembler;
    private long pc;
    private long base;
    private ByteArrayOutputStream bytes;
    private List<String> listing;

    private boolean on(String key) {
        return !"0".equals(cfg.getProperty(key, "1"));
    }

    private boolean block(String name) {
        return on("block." + name);
    }

    private boolean feature(String name) {
        return on("feature." + name);
    }

    private int[] table(String name) {
        String raw = cfg.getProperty("table." + name, "").trim();
        if (raw.isEmpty()) {
            throw new IllegalStateException("Missing table in build config: " + name);
        }
        String[] parts = raw.split(",");
        int[] values = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            values[i] = Integer.parseInt(parts[i].trim());
        }
        return values;
    }

    private void emitTable(String name) {
        for (int v : table(name)) {
            halfword(v);
        }
    }

    private void begin(long address) {
        base = address;
        pc = address;
        bytes = new ByteArrayOutputStream();
        listing = new ArrayList<>();
    }

    private void emit(String instruction) throws Exception {
        byte[] encoded = assembler.assembleLine(addr(pc), instruction);
        listing.add(String.format("%08x  %-36s %s", pc, instruction, hex(encoded)));
        bytes.write(encoded);
        pc += encoded.length;
    }

    private void padTo(long address) throws Exception {
        if (pc > address) {
            throw new IllegalStateException(String.format(
                "Code crossed target: pc=%08x target=%08x", pc, address));
        }
        while (pc < address) {
            emit("NOP");
        }
        if (pc != address) {
            throw new IllegalStateException(String.format(
                "Cannot align target: pc=%08x target=%08x", pc, address));
        }
    }

    private void word(long value) {
        byte[] encoded = new byte[] {
            (byte) (value >>> 24), (byte) (value >>> 16),
            (byte) (value >>> 8), (byte) value
        };
        listing.add(String.format("%08x  %-36s %s", pc,
            String.format(".word 0x%08x", value), hex(encoded)));
        bytes.writeBytes(encoded);
        pc += 4;
    }

    private void halfword(int value) {
        byte[] encoded = new byte[] { (byte) (value >>> 8), (byte) value };
        listing.add(String.format("%08x  %-36s %s", pc,
            String.format(".hword 0x%04x", value), hex(encoded)));
        bytes.writeBytes(encoded);
        pc += 2;
    }

    private void finish(String name, long expectedEnd) throws Exception {
        padTo(expectedEnd);
        if (!block(name)) {
            println("SKIP " + name + " (disabled by build config)");
            return;
        }
        println("BLOCK " + name);
        for (String line : listing) {
            println(line);
        }
        println(String.format("PATCH %08x %s ; %s", base, hex(bytes.toByteArray()), name));
    }

    private static String hex(byte[] data) {
        StringBuilder out = new StringBuilder(data.length * 2);
        for (byte b : data) {
            out.append(String.format("%02x", b & 0xff));
        }
        return out.toString();
    }

    private Address addr(long value) {
        return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(value);
    }

    private void singlePatch(String name, long address, String instruction) throws Exception {
        byte[] encoded = assembler.assembleLine(addr(address), instruction);
        if (!block(name)) {
            println("SKIP " + name + " (disabled by build config)");
            return;
        }
        println(String.format("PATCH %08x %s ; %s: %s", address, hex(encoded), name, instruction));
    }

    // A pool word repoint: the whole mechanism by which a code cave is
    // activated.  Skipping one leaves the factory pointer, and therefore the
    // factory behaviour, completely intact.
    private void wordPatch(String name, long address, long value, String comment) {
        if (!block(name)) {
            println("SKIP " + name + " (disabled by build config)");
            return;
        }
        println(String.format("PATCH %08x %08x ; %s: %s", address, value, name, comment));
    }

    private void fixedPatch(String name, long address, int length, String instruction) throws Exception {
        begin(address);
        emit(instruction);
        if (pc > address + length) {
            throw new IllegalStateException("Instruction does not fit fixed patch");
        }
        padTo(address + length);
        if (!block(name)) {
            println("SKIP " + name + " (disabled by build config)");
            return;
        }
        println(String.format("PATCH %08x %s ; %s: %s", address, hex(bytes.toByteArray()), name, instruction));
    }

    @Override
    protected void run() throws Exception {
        String[] scriptArgs = getScriptArgs();
        if (scriptArgs.length > 0 && !scriptArgs[0].isEmpty()) {
            try (FileInputStream in = new FileInputStream(scriptArgs[0])) {
                cfg.load(in);
            }
            println("CONFIG " + scriptArgs[0]);
        } else {
            println("CONFIG (none — assembling the complete patch set)");
        }
        assembler = Assemblers.getAssembler(currentProgram);

        // Ordinary knob 3 trims the pressure floor around the hardcoded
        // default: floor = (knob >> 2) + 452, i.e. 452..707 with exactly 580
        // at the center of travel.  The
        // low half of state+0x33c holds the full-pressure endpoint; the high
        // half holds the floor. Pad-3 + knob 3 retains its original behavior.
        begin(0x80014300L);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("MOV R11,R12");
        emit("LDDPC R10,0x80014378");
        emit("LD.W R8,R10[0x34]");
        emit("CP.W R8,0x3");
        emit("BR{ne} 0x80014320");
        emit("MOV R12,R11");
        emit("MCALL PC[0x8001437c]");
        emit("RJMP 0x80014374");
        padTo(0x80014320L);
        emit("CP.W R8,0x0");
        emit("BR{ne} 0x80014374");
        emit("LD.UH R8,R10[0x30e]");
        emit("LSR R8,0x2");
        emit("SUB R8,-0x1c4");
        emit("LD.W R9,R10[0x33c]");
        emit("BFEXTU R11,R9,0x0,0x10");
        emit("CP.W R11,0x20");
        emit("BR{lt} 0x80014346");
        emit("CP.W R11,0x3ff");
        emit("BR{ls} 0x8001434a");
        padTo(0x80014346L);
        emit("MOV R11,0x348");
        padTo(0x8001434aL);
        emit("MOV R12,R11");
        emit("SUB R12,0x20");
        emit("CP.W R8,R12");
        emit("BR{ls} 0x80014356");
        emit("MOV R8,R12");
        padTo(0x80014356L);
        emit("LSL R8,0x10");
        emit("OR R8,R11");
        emit("ST.W R10[0x33c],R8");
        emit("LD.UB R9,R10[0x2db]");
        emit("MOV R8,R9");
        emit("BFEXTU R8,R8,0x5,0x3");
        emit("CP.W R8,0x5");
        emit("BR{eq} 0x80014374");
        emit("MOV R9,0xa0");
        emit("ST.B R10[0x2db],R9");
        padTo(0x80014374L);
        emit("LDM SP++,R7,PC");
        padTo(0x80014378L);
        word(0x00003560L); // global state base
        word(0x800040c8L); // original knob-3 handler
        finish("knob3_pressure_floor", 0x80014380L);

        // Knob 4 handler. Preserve its old behavior for internal mode 4;
        // otherwise encode curve=(ADC>>5) and marker 101 in velocity-min byte.
        begin(0x80014380L);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("LDDPC R10,0x800143f8");
        emit("LD.W R8,R10[0x34]");
        emit("CP.W R8,0x4");
        emit("BR{ne} 0x800143a4");
        emit("MOV R12,0x5");
        emit("MCALL PC[0x800143fc]");
        emit("RJMP 0x800143ee");
        padTo(0x800143a4L);
        emit("CP.W R8,0x0");
        emit("BR{ne} 0x800143ee");
        emit("LD.UH R9,R10[0x310]");
        emit("LSR R9,0x5");
        emit("MOV R11,0xa0");
        emit("OR R9,R11");
        emit("ST.B R10[0x2db],R9");
        padTo(0x800143eeL);
        emit("LDM SP++,R7,PC");
        padTo(0x800143f8L);
        word(0x00003560L); // global state base
        word(0x80004070L); // original special-mode knob-4 handler
        finish("knob4_curve", 0x80014400L);

        // Note-on wrapper: perform the original key initialization, then
        // clear the raw-filter sample count so the growing average restarts.
        begin(0x80018d00L);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        if (feature("arp_latch")) {
            // A press of an already-latched key returns -1 and the note-on is
            // skipped, which is what makes the keys behave as toggles.
            emit("MCALL PC[0x80018d3c]");
            emit("CP.W R12,-0x1");
            emit("BR{eq} 0x80018d2a");
        }
        emit("ST.W --SP,R12");
        emit("MCALL PC[0x80018d30]");
        emit("LDDPC R9,0x80018d34");
        emit("MOV R8,0x0");
        emit("ST.H R9[0x0],R8");
        emit("LD.W R12,SP++");
        emit("MCALL PC[0x80018d38]");
        padTo(0x80018d2aL);
        emit("LDM SP++,R7,PC");
        padTo(0x80018d30L);
        word(0x80005a04L); // original note-on initialization
        word(0x00003226L); // raw-filter sample count
        word(0x8001a020L); // press-order list append
        word(0x8001a2a8L); // latch toggle check
        finish("note_on_reset_raw_filter", 0x80018d40L);

        // Release/source-selection wrapper. Preserve the selected-key return
        // value while clearing the sample count for the growing average.
        begin(0x80018d40L);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("MCALL PC[0x80018d70]");
        emit("MOV R8,R12");
        emit("LDDPC R9,0x80018d74");
        emit("MOV R10,0x0");
        emit("ST.H R9[0x0],R10");
        emit("MOV R12,R8");
        emit("LDM SP++,R7,PC");
        padTo(0x80018d70L);
        word(0x8000596cL); // original active-key selector
        word(0x00003226L); // raw-filter sample count
        finish("source_change_reset_raw_filter", 0x80018d80L);

        // One entry per possible normalized raw pressure count. The shape is
        // the 218r's half-decade exponential: zero exactly at the floor, then
        // a ~32% onset step and a smooth 10 dB rise to full pressure. Values
        // exceed the linear ramp, so the blend must use an arithmetic shift.
        begin(0x80018d80L);
        emitTable("pressure_curve");
        finish("half_decade_exponential_curve_table", 0x800194a4L);

        // Ordinary knob 1 trims the full-pressure endpoint around the
        // hardcoded default: ceiling = (knob >> 2) + 712, i.e. 712..967 with
        // exactly 840 at the center of travel. Internal mode 6 retains the
        // factory key-contact sensitivity adjustment.
        begin(0x800194c0L);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("MOV R11,R12");
        emit("LDDPC R10,0x80019570");
        emit("LD.W R8,R10[0x34]");
        emit("CP.W R8,0x6");
        emit("BR{ne} 0x800194dc");
        emit("MOV R12,R11");
        emit("MCALL PC[0x80019574]");
        emit("RJMP 0x80019568");
        padTo(0x800194dcL);
        emit("CP.W R8,0x0");
        emit("BR{ne} 0x80019568");
        emit("LD.UH R8,R10[0x30a]");
        emit("LSR R8,0x2");
        emit("SUB R8,-0x2c8");
        emit("LD.W R9,R10[0x33c]");
        emit("LSR R11,R9,0x10");
        emit("CP.W R11,0x3ff");
        emit("BR{ls} 0x80019518");
        emit("MOV R11,0x244");
        padTo(0x80019518L);
        emit("MOV R12,R11");
        emit("SUB R12,-0x20");
        emit("CP.W R8,R12");
        emit("BR{ge} 0x80019526");
        emit("MOV R8,R12");
        padTo(0x80019526L);
        emit("LSL R11,0x10");
        emit("OR R8,R11");
        emit("ST.W R10[0x33c],R8");
        emit("LD.UB R9,R10[0x2db]");
        emit("MOV R8,R9");
        emit("BFEXTU R8,R8,0x5,0x3");
        emit("CP.W R8,0x5");
        emit("BR{eq} 0x80019568");
        emit("MOV R9,0xa0");
        emit("ST.B R10[0x2db],R9");
        padTo(0x80019568L);
        emit("LDM SP++,R7,PC");
        padTo(0x80019570L);
        word(0x00003560L); // global state base
        word(0x80004188L); // original knob-1/key-sensitivity handler
        finish("knob1_pressure_ceiling", 0x80019580L);

        // Average the raw signal before all nonlinear processing. Then map the
        // saved [floor, ceiling] interval onto 0..913, apply the knob-4 curve,
        // and finally expand to the full 12-bit pressure output. There is no
        // gain multiplication after this function.
        begin(0x80019580L);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("MOV R8,R12");
        if (feature("pressure_common_mode")) {
            // Subtract the per-scan common-mode estimate published by
            // scan_housekeeping at RAM 0x602c, clamped at zero.
            emit("MOV R9,0x602c");
            emit("LD.SH R9,R9[0x0]");
            emit("SUB R8,R8,R9 << 0x0");
            emit("CP.W R8,0x0");
            emit("BR{ge} 0x8001959c");
            emit("MOV R8,0x0");
        }
        padTo(0x8001959cL);
        emit("LDDPC R10,0x80019720");
        emit("LD.UH R9,R10[0x10]");
        emit("SUB R9,-0x1");
        emit("CP.W R9,0x8");
        emit("BR{ls} 0x800195b0");
        emit("MOV R9,0x8");
        padTo(0x800195b0L);
        emit("ST.H R10[0x10],R9");
        emit("MOV R12,R10");
        emit("SUB R12,-0xe");
        emit("MOV R11,0x7");
        padTo(0x800195bcL);
        emit("LD.UH R7,R12[-0x2]");
        emit("ST.H R12[0x0],R7");
        emit("SUB R12,0x2");
        emit("SUB R11,0x1");
        emit("BR{ne} 0x800195bc");
        emit("ST.H R10[0x0],R8");
        emit("MOV R12,R10");
        emit("MOV R11,R9");
        emit("MOV R8,0x0");
        padTo(0x800195d0L);
        emit("LD.UH R7,R12[0x0]");
        emit("ADD R8,R7");
        emit("SUB R12,-0x2");
        emit("SUB R11,0x1");
        emit("BR{ne} 0x800195d0");
        emit("DIVU R8,R8,R9");
        padTo(0x80019600L);

        emit("LDDPC R12,0x80019728");
        emit("LD.UB R11,R12[0x2db]");
        emit("MOV R7,R11");
        emit("BFEXTU R7,R7,0x5,0x3");
        emit("CP.W R7,0x5");
        emit("BR{eq} 0x80019628");
        emit("MOV R7,0x0");
        emit("MOV R10,0x244");
        emit("MOV R9,0x348");
        emit("RJMP 0x80019670");
        padTo(0x80019628L);
        emit("BFEXTU R7,R11,0x0,0x5");
        emit("LD.W R9,R12[0x33c]");
        emit("LSR R10,R9,0x10");
        emit("BFEXTU R9,R9,0x0,0x10");
        emit("CP.W R10,0x3ff");
        emit("BR{hi} 0x80019666");
        emit("CP.W R9,0x20");
        emit("BR{lt} 0x80019666");
        emit("CP.W R9,0x3ff");
        emit("BR{hi} 0x80019666");
        emit("MOV R11,R9");
        emit("SUB R11,R10");
        emit("CP.W R11,0x1f");
        emit("BR{hi} 0x80019670");
        padTo(0x80019666L);
        emit("MOV R10,0x244");
        emit("MOV R9,0x348");
        padTo(0x80019670L);

        emit("CP.W R8,R10");
        emit("BR{hi} 0x80019682");
        emit("MOV R8,0x0");
        emit("RJMP 0x800196a0");
        padTo(0x80019682L);
        emit("CP.W R8,R9");
        emit("BR{lt} 0x80019690");
        emit("MOV R8,0x391");
        emit("RJMP 0x800196a0");
        padTo(0x80019690L);
        emit("SUB R8,R10");
        emit("SUB R9,R10");
        emit("MOV R10,0x391");
        emit("MUL R8,R10,R8");
        emit("DIVU R8,R8,R9");
        padTo(0x800196a0L);

        emit("CP.W R7,0x0");
        emit("BR{eq} 0x800196cc");
        emit("LDDPC R12,0x8001972c");
        emit("LD.UH R9,R12[R8 << 0x1]");
        emit("SUB R9,R8,R9 << 0x0");
        emit("MOV R10,R7");
        emit("LSR R11,R10,0x2");
        emit("LSL R10,0x3");
        emit("ADD R10,R11");
        emit("MUL R9,R10,R9");
        emit("SUB R9,-0x80");
        emit("ASR R9,0x8");
        emit("SUB R8,R9");
        padTo(0x800196ccL);
        emit("MOV R9,0xfff");
        emit("MUL R8,R9,R8");
        emit("SUB R8,-0x1c8");
        emit("MOV R9,0x391");
        emit("DIVU R8,R8,R9");
        emit("MOV R12,R8");
        emit("MCALL PC[0x80019730]");
        emit("MCALL PC[0x80019724]");
        emit("LDM SP++,R7,PC");
        padTo(0x80019720L);
        word(0x00003216L); // pressure-history RAM: eight taps, count at +0x10
        word(0x80013350L); // original signed-int-to-float helper
        word(0x00003560L); // global state base
        word(0x80018d80L); // full-resolution curve table
        word(0x80019740L); // edit-mode USB pressure telemetry
        finish("calibrated_pressure_curve", 0x80019734L);

        // Rate-limited calibration telemetry.  This function is called after
        // the pressure result has been calculated, so it cannot alter the
        // pressure path.  It recomputes the observable intermediate values
        // from the sixteen raw history taps and returns the original result.
        // Telemetry is emitted only while edit mode and USB MIDI are active.
        begin(0x80019740L);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("SUB SP,0x18");
        emit("ST.H R7[-0x2],R12");       // final 12-bit output
        emit("LDDPC R10,0x80019930");    // global state
        emit("LD.UB R8,R10[0x39]");      // edit-mode flag
        emit("CP.W R8,0x1");
        emit("BR{ne} 0x80019910");
        emit("LD.UB R8,R10[0x349]");     // USB MIDI enabled
        emit("CP.W R8,0x0");
        emit("BR{eq} 0x80019910");
        emit("LDDPC R9,0x80019934");     // private telemetry divider
        emit("LD.UB R8,R9[0x0]");
        emit("SUB R8,-0x1");
        emit("ST.B R9[0x0],R8");
        emit("ANDL R8,0x1f");            // one frame per 32 calculations
        emit("BR{ne} 0x80019910");

        // Capture the newest baseline-subtracted sample and exact 16-tap
        // average used by the pressure function.
        emit("LDDPC R12,0x8001992c");
        emit("LD.UH R8,R12[0x0]");
        emit("ST.H R7[-0x4],R8");        // instantaneous raw
        emit("LD.UH R11,R12[0x10]");     // growing-average sample count
        emit("MOV R9,0x0");
        emit("CP.W R11,0x0");
        emit("BR{ne} 0x80019790");
        emit("MOV R8,0x0");              // no samples yet: report average 0
        emit("RJMP 0x800197bc");
        padTo(0x80019790L);
        emit("CP.W R11,0x8");
        emit("BR{ls} 0x800197a0");
        emit("MOV R11,0x8");
        padTo(0x800197a0L);
        emit("LD.UH R8,R12[0x0]");
        emit("ADD R9,R8");
        emit("SUB R12,-0x2");
        emit("SUB R11,0x1");
        emit("BR{ne} 0x800197a0");
        emit("LDDPC R12,0x8001992c");
        emit("LD.UH R11,R12[0x10]");
        emit("CP.W R11,0x8");
        emit("BR{ls} 0x800197b8");
        emit("MOV R11,0x8");
        padTo(0x800197b8L);
        emit("DIVU R8,R9,R11");          // quotient to R8 (even destination)
        padTo(0x800197bcL);
        emit("ST.H R7[-0x6],R8");        // averaged raw (exact mean of n taps)

        // Resolve the same persisted curve/floor/ceiling validation and
        // normalization as the pressure calculation itself.
        emit("LD.UB R8,R10[0x2db]");
        emit("MOV R11,R8");
        emit("BFEXTU R11,R11,0x5,0x3");
        emit("CP.W R11,0x5");
        emit("BR{ne} 0x80019820");
        emit("ANDL R8,0x1f");
        emit("ST.B R7[-0xd],R8");        // curve level
        emit("LD.W R8,R10[0x33c]");
        emit("LSR R11,R8,0x10");        // floor
        emit("BFEXTU R9,R8,0x0,0x10");  // ceiling
        emit("CP.W R11,0x3ff");
        emit("BR{hi} 0x80019820");
        emit("CP.W R9,0x20");
        emit("BR{lt} 0x80019820");
        emit("CP.W R9,0x3ff");
        emit("BR{hi} 0x80019820");
        emit("MOV R8,R9");
        emit("SUB R8,R11");
        emit("CP.W R8,0x1f");
        emit("BR{hi} 0x80019834");
        padTo(0x80019820L);
        emit("MOV R11,0x0");
        emit("ST.B R7[-0xd],R11");
        emit("MOV R11,0x244");
        emit("MOV R9,0x348");
        padTo(0x80019834L);
        emit("ST.H R7[-0xa],R11");       // applied floor
        emit("ST.H R7[-0xc],R9");        // applied ceiling
        emit("LD.UH R8,R7[-0x6]");
        emit("CP.W R8,R11");
        emit("BR{hi} 0x80019854");
        emit("MOV R8,0x0");
        emit("RJMP 0x80019878");
        padTo(0x80019854L);
        emit("CP.W R8,R9");
        emit("BR{lt} 0x80019864");
        emit("MOV R8,0x391");
        emit("RJMP 0x80019878");
        padTo(0x80019864L);
        emit("SUB R8,R11");
        emit("SUB R9,R11");
        emit("MOV R11,0x391");
        emit("MUL R8,R11,R8");
        emit("DIVU R8,R8,R9");
        padTo(0x80019878L);
        emit("ST.H R7[-0x8],R8");        // normalized 0..913

        // Capture the two absolute scan components that the factory scanner
        // subtracts to produce the active key's live raw pressure.  A weighted
        // subtraction may reject proximity better than the factory 1:1
        // subtraction; telemetry measures that possibility without changing
        // the pressure path.
        emit("LDDPC R12,0x80019930");
        emit("LD.UB R10,R12[0x256]");     // active key index, 0..28
        emit("LDDPC R11,0x80019938");     // key-to-scan-channel map
        emit("LD.UB R10,R11[R10 << 0x0]");
        emit("LSL R10,0x1");
        emit("ADD R12,R12,R10 << 0x0");
        emit("LD.UH R8,R12[0x86]");
        emit("ST.H R7[-0x10],R8");       // scan component A
        emit("LD.UH R8,R12[0xd6]");
        emit("ST.H R7[-0x12],R8");       // scan component B

        // USB-MIDI channel 16, undefined CC range 102..118.  CC 118 is the
        // frame terminator, letting the receiver discard partial frames.
        emit("LD.UH R12,R7[-0x4]");
        emit("MOV R11,0x66");
        emit("MCALL PC[0x80019924]");
        emit("LD.UH R12,R7[-0x6]");
        emit("MOV R11,0x68");
        emit("MCALL PC[0x80019924]");
        emit("LD.UH R12,R7[-0x8]");
        emit("MOV R11,0x6a");
        emit("MCALL PC[0x80019924]");
        emit("LD.UH R12,R7[-0x2]");
        emit("MOV R11,0x6c");
        emit("MCALL PC[0x80019924]");
        emit("LD.UH R12,R7[-0xa]");
        emit("MOV R11,0x6e");
        emit("MCALL PC[0x80019924]");
        emit("LD.UH R12,R7[-0xc]");
        emit("MOV R11,0x70");
        emit("MCALL PC[0x80019924]");
        emit("LD.UH R12,R7[-0x10]");
        emit("MOV R11,0x72");
        emit("MCALL PC[0x80019924]");
        emit("LD.UH R12,R7[-0x12]");
        emit("MOV R11,0x74");
        emit("MCALL PC[0x80019924]");
        emit("LD.UB R11,R7[-0xd]");
        emit("MOV R12,0x76");
        emit("MOV R10,0xf");
        emit("MCALL PC[0x80019928]");
        padTo(0x80019910L);
        emit("LD.UH R12,R7[-0x2]");
        emit("SUB SP,-0x18");
        emit("LDM SP++,R7,PC");
        padTo(0x80019924L);
        word(0x80019940L); // send one 14-bit diagnostic value
        word(0x80008034L); // direct USB-MIDI CC sender
        word(0x00003216L); // sixteen raw pressure-history taps
        word(0x00003560L); // global state base
        word(0x00003239L); // otherwise-unused byte in the BSS gap
        word(0x0000002cL); // factory key-to-scan-channel map
        finish("edit_mode_pressure_telemetry", 0x80019940L);

        // Send a 14-bit unsigned value as adjacent CC MSB/LSB messages.
        begin(0x80019940L);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("SUB SP,0x8");
        emit("ST.H R7[-0x2],R12");
        emit("ST.B R7[-0x3],R11");
        emit("LD.UH R11,R7[-0x2]");
        emit("LSR R11,0x7");
        emit("LD.UB R12,R7[-0x3]");
        emit("MOV R10,0xf");
        emit("MCALL PC[0x8001997c]");
        emit("LD.UH R11,R7[-0x2]");
        emit("ANDL R11,0x7f");
        emit("LD.UB R12,R7[-0x3]");
        emit("SUB R12,-0x1");
        emit("MOV R10,0xf");
        emit("MCALL PC[0x8001997c]");
        emit("SUB SP,-0x8");
        emit("LDM SP++,R7,PC");
        padTo(0x8001997cL);
        word(0x80008034L); // direct USB-MIDI CC sender
        finish("send_usb_midi_14bit", 0x80019980L);

        // Pitch-CV calibration remap (uTune replacement, stage 1).  The final
        // pitch value (key table + transpose + glide + bend, clamped 0..4095,
        // 484 units/octave, lowest key C0 = 485) is remapped through a
        // piecewise-linear curve with one anchor per octave, encoding the
        // user's 208p calibration (1 V/oct nominal; C5=5.0231 V, C6=6.232 V).
        // Called with R12 = raw pitch; stores the DAC value and the last-sent
        // mirror itself, replacing the tail of the factory update function.
        begin(0x80019980L);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("ST.W --SP,R12");
        emit("MCALL PC[0x80019a00]");
        emit("LD.W R12,SP++");
        emit("MOV R8,R12");
        emit("SUB R8,-0x78");
        // Global vibrato (knob 4): signed offset in factory units (max +-13
        // = +-32 cents) computed each scan by the vibrato engine into RAM
        // 0x6028; added pre-remap so depth is constant in cents and rides
        // the tracking-corrected curve. Zero when the knob is in its
        // deadzone. d stays >= 106, so no clamp is needed.
        emit("MOV R10,0x6028");
        emit("LD.SH R10,R10[0x0]");
        emit("ADD R8,R10");
        emit("MOV R9,0xc");
        emit("MUL R8,R8,R9");
        emit("MOV R9,0x1e4");
        emit("DIVU R8,R8,R9");
        emit("CP.W R8,0x4d");
        emit("BR{ls} 0x800199b8");
        emit("MOV R8,0x4d");
        emit("MOV R9,0x1e3");
        padTo(0x800199b8L);
        emit("MOV R11,R9");
        emit("LSL R8,0x1");
        emit("LDDPC R10,0x800199f8");
        emit("ADD R10,R8");
        emit("LD.UH R12,R10[0x0]");
        emit("LD.UH R9,R10[0x2]");
        emit("SUB R9,R9,R12 << 0x0");
        emit("MUL R8,R11,R9");
        emit("SUB R8,-0xf2");
        emit("MOV R11,0x1e4");
        emit("DIVU R8,R8,R11");
        emit("ADD R9,R12,R8 << 0x0");
        padTo(0x800199e0L);
        emit("LDDPC R8,0x800199fc");
        emit("ST.H R8[0x358],R9");
        emit("MOV R8,0x3212");
        emit("ST.H R8[0x0],R9");
        emit("LDM SP++,R7,PC");
        padTo(0x800199f8L);
        word(0x80019bc0L); // per-semitone tracking-corrected curve table
        word(0x00003560L); // global state base
        word(0x8001a2e8L); // tuning applier + latch watch + vibrato chain
        finish("pitch_remap_utune", 0x80019a04L);

        // Per-semitone pitch curve: index 0 = the 208p's 0 V pitch (A);
        // index 3 = bottom key at the leftmost octave position (C).  Values
        // are DAC units: uTune per-octave calibration interpolated per
        // semitone, minus the measured tracking error at each semitone
        // (218e-key-calibration_done.csv), held constant beyond semi 64.
        begin(0x80019bc0L);
        emitTable("pitch_remap");
        finish("tracking_correction_table", 0x80019c5eL);

        // Pressure-weighted portamento (Haken Continuum, US 7,902,450 B2, as
        // in the Micro_Easel MonoKeyboard): each scan the pitch target becomes
        // X_port = sum(z^3 * X_k) / sum(z^3) over held keys within PInterv
        // (484 units = 12 semitones) of the sounding base, z = per-key sensor
        // delta minus 110, scaled to 0..63, up to four contributors.  The
        // blend is injected as (X_port - base) before the glide, so single
        // keys, handovers, arpeggiation, transpose, and every tuning table
        // behave exactly as before when only one key is pressed.
        // Entry word first (read by the MCALL hook), code follows.
        begin(0x80019c60L);
        word(0x80019c64L);
        emit("STM --SP,R0,R1,R2,R3,R4,R5,R7,LR");
        emit("MOV R7,SP");
        emit("MOV R4,R12");
        emit("LDDPC R9,0x80019d34");
        emit("LD.SH R11,R9[0x350]");
        // Portamento knob = pressure-needed-to-bend: T = 1023 - rate index.
        // At knob zero T exceeds any possible touch, so only the sounding
        // key contributes and the blend is exactly zero (factory behavior).
        // The anchor key is never thresholded, so engagement is smooth.
        emit("LD.SH R5,R9[0x3a2]");
        // Hard gate: below the knob's deadzone the blend loop never runs at
        // all — multi-finger common-mode sensor inflation can push deltas
        // past any threshold, so "off" must not depend on pressure at all.
        emit("CP.W R5,0x30");
        emit("BR{lt} 0x80019d28");
        emit("MOV R8,0x3ff");
        emit("SUB R5,R8,R5 << 0x0");
        emit("MOV R0,0x0");
        emit("MOV R1,0x0");
        emit("MOV R3,0x0");
        emit("MOV R2,0x1f");
        padTo(0x80019c90L);
        emit("ADD R8,R9,R2 << 0x0");
        emit("LD.UB R10,R8[0x21b]");
        emit("CP.W R10,0x1");
        emit("BR{ne} 0x80019d08");
        emit("MOV R8,0x854");
        emit("ADD R8,R8,R2 << 0x1");
        emit("LD.UH R10,R8[0x0]");
        emit("SUB R8,R10,R11 << 0x0");
        emit("ABS R8");
        emit("CP.W R8,0x1e4");
        emit("BR{ge} 0x80019d08");
        emit("MOV R8,0x3686");
        emit("ADD R8,R8,R2 << 0x1");
        emit("LD.UH R8,R8[0x0]");
        emit("SUB R8,0x6e");
        emit("CP.W R10,R11");
        emit("BR{eq} 0x80019cd0");
        emit("SUB R8,R8,R5 << 0x0");
        padTo(0x80019cd0L);
        emit("CP.W R8,0x0");
        emit("BR{le} 0x80019d08");
        emit("LSR R8,0x4");
        emit("CP.W R8,0x3f");
        emit("BR{ls} 0x80019ce8");
        emit("MOV R8,0x3f");
        padTo(0x80019ce8L);
        emit("MOV R12,R8");
        emit("MUL R12,R12,R8");
        emit("MUL R12,R12,R8");
        emit("ADD R0,R12");
        emit("MUL R12,R12,R10");
        emit("ADD R1,R12");
        emit("SUB R3,-0x1");
        emit("CP.W R3,0x4");
        emit("BR{ge} 0x80019d10");
        padTo(0x80019d08L);
        emit("SUB R2,0x1");
        emit("BR{ge} 0x80019c90");
        padTo(0x80019d10L);
        emit("CP.W R0,0x0");
        emit("BR{eq} 0x80019d28");
        emit("DIVU R0,R1,R0");
        emit("SUB R0,R0,R11 << 0x0");
        emit("ADD R4,R0");
        emit("CP.W R4,0x0");
        emit("BR{ge} 0x80019d28");
        emit("MOV R4,0x0");
        padTo(0x80019d28L);
        emit("LDDPC R8,0x80019d34");
        emit("ST.H R8[0x352],R4");
        emit("LDM SP++,R0,R1,R2,R3,R4,R5,R7,PC");
        padTo(0x80019d34L);
        word(0x00003560L); // global state base
        finish("pressure_blend_continuum", 0x80019d38L);

        // Arpeggiator randomness on the preset-voltage knobs (outside edit):
        //   knob 1 (0x30a) -> state+0x38c, the factory weighted-random key
        //     selector's bias/randomness parameter (works with edit key 25 on);
        //   knob 2 (0x30c) -> random gate shortening: the countdown's gate-off
        //     compare (was == 3) becomes == R, R redrawn per step in
        //     [3, 3 + (interval-4)*knob/1024] via the factory PRNG (0x80013e04);
        //   knob 3 (0x30e) -> random +-octave on each arp note with
        //     probability knob/1024 (bottom deadzone = off, factory-exact).
        // Knob values latch only outside edit mode so edit-mode knob use
        // never disturbs the arp.  RAM: 0x322a knob2 latch, 0x322c last
        // countdown, 0x322e knob3 latch, 0x3230 gate threshold.
        // Arp controls on the preset knobs (outside edit; latches edit-gated):
        //   knob 1 (0x30a>>3 -> state+0x38c latch): press-order vs random key
        //     selection, applied by the replacement selector below;
        //   knob 2 (0x30c -> 0x322a latch): rhythm randomness — the per-step
        //     countdown reload becomes T*((1024-r) + r*E)/1024 with E an
        //     exponential draw (mean ~1, CLZ-geometric approximation, clamp
        //     4x), the Micro_Easel RANDOM PULSER law; knob low = even pulses;
        //   knob 3 (0x30e -> 0x322e latch): random +-octave per arp note.
        // Gate-off timing itself is factory (compare == 3 restored).
        begin(0x80019d38L);
        word(0x80019d44L); // gate/housekeeping entry (hook at 0x21a0)
        word(0x80019da8L); // octave entry (hook at 0x22f6)
        word(0x80019df8L); // rhythm entry (hook at 0x21fa)
        // R8 is dead at the hook site (factory overwrote it); do not push it,
        // so the final CP.H can run AFTER the LDM restore and survive the
        // return (LDM with PC would execute return-and-test-R12, destroying
        // the flags the caller's BR{ne} consumes — that bug killed the
        // factory gate-off masking of arp pitch transitions).
        emit("STM --SP,R7,R9,R10,R11,R12,LR");
        emit("MOV R7,SP");
        emit("LDDPC R10,0x80019e90");
        emit("LD.UB R8,R10[0x39]");
        emit("CP.W R8,0x1");
        emit("BR{eq} 0x80019d98");
        emit("LD.SH R8,R10[0x30a]");
        emit("LSR R8,0x3");
        emit("ST.B R10[0x38c],R8");
        emit("LD.SH R8,R10[0x30c]");
        emit("MOV R11,0x322a");
        emit("ST.H R11[0x0],R8");
        emit("LD.SH R8,R10[0x30e]");
        emit("MOV R11,0x322e");
        emit("ST.H R11[0x0],R8");
        padTo(0x80019d98L);
        emit("LDM SP++,R7,R9,R10,R11,R12,LR");
        emit("MOV R8,0x3");
        emit("CP.H R9,R8");
        emit("MOV PC,LR");
        padTo(0x80019da8L);
        emit("STM --SP,R0,R7,R9,R10,R11,R12,LR");
        emit("MOV R7,SP");
        emit("MOV R11,0x322e");
        emit("LD.SH R11,R11[0x0]");
        emit("CP.W R11,0x30");
        emit("BR{lt} 0x80019df0");
        emit("MOV R0,R11");
        emit("ST.W --SP,R8");
        emit("MCALL PC[0x80019e94]");
        emit("LD.W R8,SP++");
        emit("BFEXTU R9,R12,0xa,0xa");
        emit("CP.W R9,R0");
        emit("BR{ge} 0x80019df0");
        emit("BFEXTU R9,R12,0x14,0x1");
        emit("CP.W R9,0x0");
        emit("BR{eq} 0x80019de0");
        emit("SUB R8,-0x1e4");
        emit("RJMP 0x80019df0");
        padTo(0x80019de0L);
        emit("SUB R8,0x1e4");
        emit("CP.W R8,0x1");
        emit("BR{ge} 0x80019df0");
        emit("SUB R8,-0x3c8");
        padTo(0x80019df0L);
        emit("LDM SP++,R0,R7,R9,R10,R11,R12,PC");
        padTo(0x80019df8L);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("MOV R9,R12");
        emit("MOV R10,0x322a");
        emit("LD.SH R10,R10[0x0]");
        emit("CP.W R10,0x30");
        emit("BR{ge} 0x80019e18");
        emit("LDDPC R8,0x80019e90");
        emit("ST.H R8[0x38e],R9");
        emit("LDM SP++,R7,PC");
        padTo(0x80019e18L);
        emit("ST.W --SP,R9");
        emit("ST.W --SP,R10");
        emit("MCALL PC[0x80019e94]");
        emit("LD.W R10,SP++");
        emit("LD.W R9,SP++");
        emit("ANDH R12,0x7fff");
        emit("CLZ R8,R12");
        emit("SUB R8,0x1");
        emit("BFEXTU R11,R12,0x0,0x8");
        emit("MOV R12,0xb1");
        emit("MUL R8,R8,R12");
        emit("MUL R11,R11,R12");
        emit("LSR R11,0x8");
        emit("ADD R8,R11");
        emit("CP.W R8,0x400");
        emit("BR{le} 0x80019e50");
        emit("MOV R8,0x400");
        padTo(0x80019e50L);
        emit("MUL R11,R10,R8");
        emit("LSR R11,0x8");
        emit("MOV R12,0x400");
        emit("SUB R12,R12,R10 << 0x0");
        emit("ADD R12,R11");
        emit("MUL R12,R12,R9");
        emit("LSR R12,0xa");
        emit("CP.W R12,0x8");
        emit("BR{ge} 0x80019e70");
        emit("MOV R12,0x8");
        padTo(0x80019e70L);
        emit("CP.W R12,0xfff");
        emit("BR{le} 0x80019e80");
        emit("MOV R12,0xfff");
        padTo(0x80019e80L);
        emit("LDDPC R8,0x80019e90");
        emit("ST.H R8[0x38e],R12");
        emit("LDM SP++,R7,PC");
        padTo(0x80019e90L);
        word(0x00003560L); // global state base
        word(0x80013e04L); // factory PRNG
        finish("arp_random_knobs", 0x80019e98L);

        // Press-order list (RAM 0x6000: length byte + up to 32 keys, mid-gap
        // between BSS end 0x4748 and the stack) and the replacement arp key
        // selector: knob 1 blends press-order stepping into fully random.
        begin(0x8001a020L);
        emit("STM --SP,R7,R8,R9,R10,R11,R12,LR");
        emit("MOV R7,SP");
        emit("MOV R10,0x6000");
        emit("LD.UB R8,R10[0x0]");
        emit("CP.W R8,0x20");
        emit("BR{ls} 0x8001a038");
        emit("MOV R8,0x0");
        padTo(0x8001a038L);
        emit("MOV R9,0x0");
        padTo(0x8001a03cL);
        emit("CP.W R9,R8");
        emit("BR{ge} 0x8001a078");
        emit("ADD R11,R10,R9 << 0x0");
        emit("LD.UB R11,R11[0x1]");
        emit("CP.W R11,R12");
        emit("BR{eq} 0x8001a054");
        emit("SUB R9,-0x1");
        emit("RJMP 0x8001a03c");
        padTo(0x8001a054L);
        emit("MOV R11,R8");
        emit("SUB R11,0x1");
        emit("CP.W R9,R11");
        emit("BR{ge} 0x8001a074");
        emit("ADD R11,R10,R9 << 0x0");
        emit("LD.UB LR,R11[0x2]");
        emit("ST.B R11[0x1],LR");
        emit("SUB R9,-0x1");
        emit("RJMP 0x8001a054");
        padTo(0x8001a074L);
        emit("SUB R8,0x1");
        padTo(0x8001a078L);
        emit("CP.W R8,0x20");
        emit("BR{ge} 0x8001a08c");
        emit("ADD R11,R10,R8 << 0x0");
        emit("ST.B R11[0x1],R12");
        emit("SUB R8,-0x1");
        padTo(0x8001a08cL);
        emit("ST.B R10[0x0],R8");
        emit("LDM SP++,R7,R8,R9,R10,R11,R12,PC");
        padTo(0x8001a0a0L);
        // Selector entry (pool 0x80002420 repointed here). R12 = held-flags
        // pointer; returns the next key in R12 or -1.
        emit("STM --SP,R0,R1,R2,R3,R7,LR");
        emit("MOV R7,SP");
        emit("MOV R0,R12");
        emit("LDDPC R1,0x8001a220");
        emit("LD.UB R8,R1[0x340]");
        emit("CP.W R8,0x0");
        emit("BR{ne} 0x8001a0c8");
        emit("LD.UB R8,R1[0x341]");
        emit("CP.W R8,0x0");
        emit("BR{eq} 0x8001a1f0");
        padTo(0x8001a0c8L);
        emit("LD.UB R2,R1[0x38c]");
        emit("CP.W R2,0x6");
        emit("BR{lt} 0x8001a150");
        emit("MCALL PC[0x8001a224]");
        emit("BFEXTU R3,R12,0xa,0x7");
        emit("CP.W R3,R2");
        emit("BR{ge} 0x8001a150");
        emit("SUB SP,0x20");
        emit("MOV R2,0x0");
        emit("MOV R3,0x0");
        padTo(0x8001a0f0L);
        emit("CP.W R3,0x20");
        emit("BR{ge} 0x8001a110");
        emit("ADD R8,R0,R3 << 0x0");
        emit("LD.UB R8,R8[0x0]");
        emit("CP.W R8,0x1");
        emit("BR{ne} 0x8001a108");
        emit("ADD R8,SP,R2 << 0x0");
        emit("ST.B R8[0x0],R3");
        emit("SUB R2,-0x1");
        padTo(0x8001a108L);
        emit("SUB R3,-0x1");
        emit("RJMP 0x8001a0f0");
        padTo(0x8001a110L);
        emit("CP.W R2,0x0");
        emit("BR{eq} 0x8001a148");
        emit("MOV R3,0x4");
        padTo(0x8001a118L);
        emit("MCALL PC[0x8001a224]");
        emit("LSR R12,0x11");
        emit("DIVU R10,R12,R2");
        emit("ADD R8,SP,R11 << 0x0");
        emit("LD.UB R8,R8[0x0]");
        emit("CP.W R2,0x1");
        emit("BR{eq} 0x8001a140");
        emit("LD.UB R9,R1[0x34d]");
        emit("CP.W R8,R9");
        emit("BR{ne} 0x8001a140");
        emit("SUB R3,0x1");
        emit("BR{gt} 0x8001a118");
        padTo(0x8001a140L);
        emit("SUB SP,-0x20");
        emit("MOV R12,R8");
        emit("RJMP 0x8001a200");
        padTo(0x8001a148L);
        emit("SUB SP,-0x20");
        emit("RJMP 0x8001a1f0");
        padTo(0x8001a150L);
        emit("MOV R10,0x6000");
        emit("LD.UB R8,R10[0x0]");
        emit("CP.W R8,0x20");
        emit("BR{ls} 0x8001a164");
        emit("MOV R8,0x0");
        padTo(0x8001a164L);
        emit("CP.W R8,0x0");
        emit("BR{eq} 0x8001a1f0");
        emit("LD.UB R9,R1[0x34d]");
        emit("MOV R3,0x0");
        padTo(0x8001a180L);
        emit("CP.W R3,R8");
        emit("BR{ge} 0x8001a1a0");
        emit("ADD R11,R10,R3 << 0x0");
        emit("LD.UB R11,R11[0x1]");
        emit("CP.W R11,R9");
        emit("BR{eq} 0x8001a1a0");
        emit("SUB R3,-0x1");
        emit("RJMP 0x8001a180");
        padTo(0x8001a1a0L);
        emit("CP.W R3,R8");
        emit("BR{lt} 0x8001a1b0");
        emit("MOV R3,R8");
        emit("SUB R3,0x1");
        padTo(0x8001a1b0L);
        emit("MOV R2,R8");
        padTo(0x8001a1b8L);
        emit("SUB R3,-0x1");
        emit("CP.W R3,R8");
        emit("BR{lt} 0x8001a1d0");
        emit("MOV R3,0x0");
        padTo(0x8001a1d0L);
        emit("ADD R11,R10,R3 << 0x0");
        emit("LD.UB R11,R11[0x1]");
        emit("ADD R9,R0,R11 << 0x0");
        emit("LD.UB R9,R9[0x0]");
        emit("CP.W R9,0x1");
        emit("BR{eq} 0x8001a1f8");
        emit("SUB R2,0x1");
        emit("CP.W R2,0x0");
        emit("BR{gt} 0x8001a1b8");
        padTo(0x8001a1f0L);
        emit("MOV R12,-0x1");
        emit("RJMP 0x8001a218");
        padTo(0x8001a1f8L);
        emit("MOV R12,R11");
        padTo(0x8001a200L);
        emit("LD.UB R8,R1[0x39]");
        emit("CP.W R8,0x0");
        emit("BR{eq} 0x8001a218");
        emit("CP.W R12,0xc");
        emit("BR{lt} 0x8001a1f0");
        emit("CP.W R12,0x18");
        emit("BR{gt} 0x8001a1f0");
        padTo(0x8001a218L);
        emit("LDM SP++,R0,R1,R2,R3,R7,PC");
        padTo(0x8001a220L);
        word(0x00003560L); // global state base
        word(0x80013e04L); // factory PRNG
        finish("arp_order_selector", 0x8001a228L);

        // Zero-portamento fix, safe variant: the glide RATE VALUE is forced
        // to the fastest table entry (0, step ~= 99.95% per scan) whenever
        // the rate index sits in the knob deadzone — a pot offset otherwise
        // lands on an audibly slow entry. Entered from a hook over the
        // factory's rate-table lookup (R9 = rate index); stores the value to
        // the rate variable (RAM 0x2eee) exactly as the factory code did.
        begin(0x8001a230L);
        word(0x8001a234L);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("CP.W R9,0x30");
        emit("BR{ge} 0x8001a248");
        emit("MOV R8,0x0");
        emit("RJMP 0x8001a250");
        padTo(0x8001a248L);
        emit("LDDPC R8,0x8001a260");
        emit("LD.SH R8,R8[R9 << 0x1]");
        emit("CASTS.H R8");
        padTo(0x8001a250L);
        emit("MOV R9,0x2eee");
        emit("ST.H R9[0x0],R8");
        emit("LDM SP++,R7,PC");
        padTo(0x8001a260L);
        word(0x80015150L); // factory glide rate-curve table
        finish("glide_rate_clamp", 0x8001a264L);

        // Pulse defer: the four factory pool words that pointed at the
        // pulse-high routine (0x800077f8) are repointed to the setter below,
        // which just marks the pulse pending; the pitch-store hook fires the
        // real routine after the pitch lands. Word first: the real routine's
        // address, read by the hook's MCALL PC[0x8001a268].
        begin(0x8001a268L);
        word(0x800077f8L); // real pulse-high routine
        emit("MOV R8,0x3232");
        emit("MOV R9,0x1");
        emit("ST.B R8[0x0],R9");
        emit("MOV PC,LR");
        finish("pulse_defer_set", 0x8001a278L);

        // Latch mode (arp switch position 1). Three pieces:
        //   latch_noteoff  — physical releases are ignored while latched;
        //   latch_check    — a press of an already-held key unlatches it
        //                    (called from the note-on wrapper, returns -1);
        //   applier_plus   — runs the tuning applier then watches state+0x340
        //                    for the latch->off/regular edge (prev byte at
        //                    RAM 0x3233) and clears all held flags + count.
        // Latch mode v2 (restored — the earlier symptom was factory
        // polyphonic-MIDI release semantics, not the latch).
        begin(0x8001a280L);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("LDDPC R8,0x8001a338");
        emit("LD.UB R8,R8[0x340]");
        emit("CP.W R8,0x1");
        emit("BR{eq} 0x8001a29c");
        emit("MCALL PC[0x8001a33c]");
        padTo(0x8001a29cL);
        emit("LDM SP++,R7,PC");
        padTo(0x8001a2a8L);
        emit("STM --SP,R7,R8,R9,R10,LR");
        emit("MOV R7,SP");
        emit("LDDPC R10,0x8001a338");
        emit("LD.UB R8,R10[0x340]");
        emit("CP.W R8,0x1");
        emit("BR{ne} 0x8001a2e0");
        emit("ADD R9,R10,R12 << 0x0");
        emit("LD.UB R8,R9[0x21b]");
        emit("CP.W R8,0x1");
        emit("BR{ne} 0x8001a2e0");
        emit("MOV R8,0x0");
        emit("ST.B R9[0x21b],R8");
        emit("LD.UB R8,R10[0x21a]");
        emit("CP.W R8,0x0");
        emit("BR{eq} 0x8001a2dc");
        emit("SUB R8,0x1");
        emit("ST.B R10[0x21a],R8");
        padTo(0x8001a2dcL);
        emit("MOV R12,-0x1");
        padTo(0x8001a2e0L);
        emit("LDM SP++,R7,R8,R9,R10,PC");
        padTo(0x8001a2e8L);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("MCALL PC[0x8001a340]");          // tuning applier
        if (feature("knob4_vibrato")) {
            emit("MCALL PC[0x8001a344]");      // vibrato engine
        }
        emit("MCALL PC[0x8001a348]");          // per-scan housekeeping
        emit("LDM SP++,R7,PC");
        padTo(0x8001a338L);
        word(0x00003560L); // global state base
        word(0x80005a50L); // original note-off
        word(0x80019a40L); // tuning applier
        word(0x8001a350L); // vibrato engine
        word(0x8001a480L); // latch watch + poly-MIDI boot force + common-mode
        finish("latch_v2", 0x8001a34cL);

        // Note-off pointer pools -> latch-gated wrapper.
        // Global vibrato on knob 4 (Micro_Easel one-knob law: depth and rate
        // rise together; +-33 cents and 1..6 Hz at full; deadzone = off).
        // Runs at 200 Hz from applier_plus. RAM: 0x3234 knob latch
        // (edit-gated — knob 4 in edit still sets the pressure curve),
        // 0x6024 LFO phase, 0x6026 smoothed depth (steps +-1/scan, ~65 ms
        // swell), 0x6028 signed output offset in pitch units.
        begin(0x8001a350L);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("LDDPC R10,0x8001a3f8");
        emit("LD.UB R8,R10[0x39]");
        emit("CP.W R8,0x1");
        emit("BR{eq} 0x8001a370");
        emit("LD.SH R8,R10[0x310]");
        emit("MOV R9,0x3234");
        emit("ST.H R9[0x0],R8");
        padTo(0x8001a370L);
        emit("MOV R9,0x3234");
        emit("LD.SH R11,R9[0x0]");
        emit("CP.W R11,0x30");
        emit("BR{ge} 0x8001a384");
        emit("MOV R11,0x30");
        padTo(0x8001a384L);
        emit("SUB R11,0x30");
        emit("MOV R8,0xe");
        emit("MUL R8,R8,R11");
        emit("LSR R8,0xa");
        emit("MOV R9,0x6026");
        emit("LD.SH R12,R9[0x0]");
        emit("CP.W R12,0xd");
        emit("BR{ls} 0x8001a3a0");
        emit("MOV R12,0x0");
        padTo(0x8001a3a0L);
        emit("CP.W R12,R8");
        emit("BR{eq} 0x8001a3b0");
        emit("BR{lt} 0x8001a3ac");
        emit("SUB R12,0x1");
        emit("RJMP 0x8001a3b0");
        padTo(0x8001a3acL);
        emit("SUB R12,-0x1");
        padTo(0x8001a3b0L);
        emit("ST.H R9[0x0],R12");
        emit("MOV R8,0x6b8");
        emit("MUL R11,R11,R8");
        emit("LSR R11,0xa");
        emit("SUB R11,-0x148");
        emit("MOV R9,0x6024");
        emit("LD.UH R8,R9[0x0]");
        emit("ADD R8,R11");
        emit("CASTU.H R8");
        emit("ST.H R9[0x0],R8");
        emit("LSR R8,0xa");
        emit("LDDPC R9,0x8001a3fc");
        emit("ADD R9,R9,R8 << 0x1");
        emit("LD.SH R11,R9[0x0]");
        emit("MUL R11,R11,R12");
        emit("ASR R11,0x7");
        emit("MOV R9,0x6028");
        emit("ST.H R9[0x0],R11");
        emit("LDM SP++,R7,PC");
        padTo(0x8001a3f8L);
        word(0x00003560L); // global state base
        word(0x8001a400L); // sine table
        int[] sine = {0, 12, 25, 37, 49, 60, 71, 81, 90, 98, 106, 112, 117, 122, 125, 126, 127, 126, 125, 122, 117, 112, 106, 98, 90, 81, 71, 60, 49, 37, 25, 12, 0, 65524, 65511, 65499, 65487, 65476, 65465, 65455, 65446, 65438, 65430, 65424, 65419, 65414, 65411, 65410, 65409, 65410, 65411, 65414, 65419, 65424, 65430, 65438, 65446, 65455, 65465, 65476, 65487, 65499, 65511, 65524};
        for (int v : sine) {
            halfword(v);
        }
        finish("vibrato_engine", 0x8001a480L);

        // Per-scan housekeeping (chained from applier_plus):
        //   (a) once per power-up (marker 0xB007 at RAM 0x602a) force
        //       polyphonic MIDI OFF (state+0x84=0) regardless of the saved
        //       setting — re-enable via edit key 29 for DFU flashing;
        //   (b) latch-exit watch: on state+0x340 leaving 1 (prev at RAM
        //       0x3233) clear the held count and all 32 held flags;
        //   (c) common-mode estimate: minimum sensor delta over keys whose
        //       touch state (RAM 0x3490+k) != 2, minus the 110 baseline,
        //       clamped 0..0x320 (0 if no untouched key) -> RAM 0x602c;
        //       subtracted from the raw pressure in the filter cave.
        begin(0x8001a480L);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("LDDPC R10,0x8001a534");
        if (feature("poly_midi_default_off")) {
            emit("MOV R9,0x602a");
            emit("LD.UH R8,R9[0x0]");
            emit("MOV R11,0xb007");
            emit("CP.W R8,R11");
            emit("BR{eq} 0x8001a4a4");
            emit("ST.H R9[0x0],R11");
            emit("MOV R8,0x0");
            emit("ST.B R10[0x84],R8");
        }
        padTo(0x8001a4a4L);
        if (feature("arp_latch")) {
            // Leaving the latch switch position releases every latched key.
            emit("LD.UB R8,R10[0x340]");
            emit("MOV R11,0x3233");
            emit("LD.UB R9,R11[0x0]");
            emit("ST.B R11[0x0],R8");
            emit("CP.W R9,0x1");
            emit("BR{ne} 0x8001a4d8");
            emit("CP.W R8,0x1");
            emit("BR{eq} 0x8001a4d8");
            emit("MOV R9,0x0");
            emit("ST.B R10[0x21a],R9");
            emit("MOV R9,0x1f");
            padTo(0x8001a4c8L);
            emit("ADD R12,R10,R9 << 0x0");
            emit("MOV R8,0x0");
            emit("ST.B R12[0x21b],R8");
            emit("SUB R9,0x1");
            emit("BR{ge} 0x8001a4c8");
        }
        padTo(0x8001a4d8L);
        if (feature("pressure_common_mode")) {
            // Common-mode (two-hand proximity) estimate: the smallest sensor
            // delta among keys that are not being touched, baseline removed
            // and clamped, published to RAM 0x602c for the pressure filter.
            emit("MOV R9,0x1c");
            emit("MOV R11,0x7fff");
            padTo(0x8001a4e0L);
            emit("MOV R8,0x3490");
            emit("ADD R8,R8,R9 << 0x0");
            emit("LD.UB R8,R8[0x0]");
            emit("CP.W R8,0x2");
            emit("BR{eq} 0x8001a500");
            emit("MOV R8,0x3686");
            emit("ADD R8,R8,R9 << 0x1");
            emit("LD.UH R8,R8[0x0]");
            emit("CP.W R8,R11");
            emit("BR{ge} 0x8001a500");
            emit("MOV R11,R8");
            padTo(0x8001a500L);
            emit("SUB R9,0x1");
            emit("BR{ge} 0x8001a4e0");
            emit("MOV R8,0x7fff");
            emit("CP.W R11,R8");
            emit("BR{ne} 0x8001a510");
            emit("MOV R11,0x6e");
            padTo(0x8001a510L);
            emit("SUB R11,0x6e");
            emit("CP.W R11,0x0");
            emit("BR{ge} 0x8001a51c");
            emit("MOV R11,0x0");
            padTo(0x8001a51cL);
            emit("CP.W R11,0x320");
            emit("BR{le} 0x8001a528");
            emit("MOV R11,0x320");
            padTo(0x8001a528L);
            emit("MOV R9,0x602c");
            emit("ST.H R9[0x0],R11");
        }
        emit("LDM SP++,R7,PC");
        padTo(0x8001a534L);
        word(0x00003560L); // global state base
        finish("scan_housekeeping", 0x8001a538L);

        // Note-off pointer pools -> latch-gated wrapper.
        begin(0x80005b18L);
        word(0x8001a280L);
        finish("noteoff_pool_1", 0x80005b1cL);
        begin(0x80006278L);
        word(0x8001a280L);
        finish("noteoff_pool_2", 0x8000627cL);



        // Repointed pulse-caller pools (arp advance + three key-scan sites).
        begin(0x8000243cL);
        word(0x8001a26cL);
        finish("pulse_pool_arp", 0x80002440L);
        begin(0x80005ed8L);
        word(0x8001a26cL);
        finish("pulse_pool_key1", 0x80005edcL);
        begin(0x800063fcL);
        word(0x8001a26cL);
        finish("pulse_pool_key2", 0x80006400L);
        begin(0x800065a4L);
        word(0x8001a26cL);
        finish("pulse_pool_key3", 0x800065a8L);

        // Hook: the factory rate-table lookup and store routed through the
        // clamp (original: LDDPC/LD.SH/CASTS.H/LDDPC/ST.H, 14 bytes).
        begin(0x800031c2L);
        emit("MCALL PC[0x8001a230]");
        padTo(0x800031ceL);
        finish("glide_rate_hook", 0x800031ceL);

        // Hook 1: gate-off compare routed through knob housekeeping
        // (comparison itself is factory == 3).
        begin(0x800021a0L);
        emit("MCALL PC[0x80019d38]");
        padTo(0x800021a6L);
        finish("arp_gate_hook", 0x800021a6L);

        // Hook 2: arp note value routed through the octave randomizer.
        begin(0x800022f6L);
        emit("MCALL PC[0x80019d3c]");
        emit("ST.H R7[-0x8],R8");
        padTo(0x800022feL);
        finish("arp_octave_hook", 0x800022feL);

        // Hook 3: the per-step countdown reload routed through the rhythm
        // randomizer (R12 = tempo).
        begin(0x800021faL);
        emit("LD.SH R12,R7[-0x10]");
        emit("MCALL PC[0x80019d40]");
        padTo(0x80002204L);
        finish("arp_rhythm_hook", 0x80002204L);

        // Factory selector pointer -> replacement press-order/random selector.
        begin(0x80002420L);
        word(0x8001a0a0L);
        finish("arp_selector_pool", 0x80002424L);

        // Hook: the transpose adder's target store now routes through the
        // blend cave (R12 = unblended base+transpose target).
        begin(0x800038bcL);
        emit("LD.SH R12,R7[-0x6]");
        emit("MCALL PC[0x80019c60]");
        padTo(0x800038c6L);
        finish("pitch_target_blend_hook", 0x800038c6L);

        // Tuning applier and tables.  Selector lives in the old remote-enable
        // byte (state+2, persisted with settings): 0 = Sabat II (default),
        // 1 = ADDAC JI, 2 = 12TET.  On change: copy the 32-entry table to RAM
        // 0x854 and set the LEDs (rem-en = ch 5 = Sabat, trn = ch 8 = ADDAC).
        // Outside edit mode the LEDs are re-asserted every scan.  The old
        // transpose-mode byte (state+0x6a) is forced to zero permanently.
        begin(0x80019a40L);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("LDDPC R10,0x80019ae8");
        emit("LD.UB R8,R10[0x2]");
        emit("CP.W R8,0x2");
        emit("BR{ls} 0x80019a58");
        emit("MOV R8,0x0");
        emit("ST.B R10[0x2],R8");
        padTo(0x80019a58L);
        emit("MOV R9,0x0");
        emit("ST.B R10[0x6a],R9");
        emit("MOV R11,0xa5a0");
        emit("ADD R11,R8");
        emit("MOV R9,0x3228");
        emit("LD.UH R12,R9[0x0]");
        emit("CP.W R12,R11");
        emit("BR{ne} 0x80019a80");
        emit("LD.UB R9,R10[0x39]");
        emit("CP.W R9,0x1");
        emit("BR{ne} 0x80019aa0");
        emit("LDM SP++,R7,PC");
        padTo(0x80019a80L);
        emit("ST.H R9[0x0],R11");
        emit("LSL R12,R8,0x6");
        emit("LDDPC R11,0x80019aec");
        emit("ADD R11,R12");
        emit("MOV R12,0x854");
        emit("MOV R9,0x20");
        padTo(0x80019a90L);
        emit("LD.UH LR,R11[0x0]");
        emit("ST.H R12[0x0],LR");
        emit("SUB R11,-0x2");
        emit("SUB R12,-0x2");
        emit("SUB R9,0x1");
        emit("BR{ne} 0x80019a90");
        padTo(0x80019aa0L);
        emit("CP.W R8,0x0");
        emit("BR{ne} 0x80019ab8");
        emit("MOV R12,0x5");
        emit("MCALL PC[0x80019af0]");
        emit("MOV R12,0x8");
        emit("MCALL PC[0x80019af4]");
        emit("LDM SP++,R7,PC");
        padTo(0x80019ab8L);
        emit("CP.W R8,0x1");
        emit("BR{ne} 0x80019ad0");
        emit("MOV R12,0x8");
        emit("MCALL PC[0x80019af0]");
        emit("MOV R12,0x5");
        emit("MCALL PC[0x80019af4]");
        emit("LDM SP++,R7,PC");
        padTo(0x80019ad0L);
        emit("MOV R12,0x5");
        emit("MCALL PC[0x80019af4]");
        emit("MOV R12,0x8");
        emit("MCALL PC[0x80019af4]");
        emit("LDM SP++,R7,PC");
        padTo(0x80019ae8L);
        word(0x00003560L); // global state base
        word(0x80019af8L); // tuning tables (Sabat II, ADDAC, 12TET)
        word(0x80006808L); // LED bit set
        word(0x800068ccL); // LED bit clear
        emitTable("tuning_slot0");
        emitTable("tuning_slot1");
        emitTable("tuning_slot2");
        finish("tuning_applier_tables", 0x80019bb8L);

        // Edit key 27 (was transpose-mode toggle): ADDAC JI <-> 12TET.
        begin(0x80003d82L);
        emit("LDDPC R8,0x80003e24");
        emit("LD.UB R9,R8[0x2]");
        emit("MOV R10,0x1");
        emit("CP.W R9,0x1");
        emit("BR{ne} 0x80003d92");
        emit("MOV R10,0x2");
        padTo(0x80003d92L);
        emit("ST.B R8[0x2],R10");
        emit("MOV R9,0x0");
        emit("ST.B R8[0x6a],R9");
        emit("MOV R9,0x1");
        emit("ST.B R8[0x3a],R9");
        emit("RJMP 0x80003e10");
        padTo(0x80003db8L);
        finish("edit_key27_tuning_addac", 0x80003db8L);

        // Edit key 28 (was remote-enable toggle): Sabat II <-> 12TET.
        begin(0x80003db8L);
        emit("LDDPC R8,0x80003e24");
        emit("LD.UB R9,R8[0x2]");
        emit("MOV R10,0x0");
        emit("CP.W R9,0x0");
        emit("BR{ne} 0x80003dc8");
        emit("MOV R10,0x2");
        padTo(0x80003dc8L);
        emit("ST.B R8[0x2],R10");
        emit("MOV R9,0x1");
        emit("ST.B R8[0x3a],R9");
        emit("RJMP 0x80003e10");
        padTo(0x80003de8L);
        finish("edit_key28_tuning_sabat", 0x80003de8L);

        // Hook: replace the factory pitch-DAC store and last-sent mirror with
        // a call into the remap.  The 0..0xfff clamp still runs just before.
        // After the remap stores the fresh pitch to DAC slot 2, fire any
        // pulse deferred by the flag at RAM 0x3232 — the trigger then always
        // rises with the correct pitch already in the DAC buffer (the arp
        // advance runs at 1 kHz but pitch only updates here at 200 Hz; the
        // factory called the pulse routine immediately, shipping the new
        // gate with the previous note's pitch for up to 5 ms).
        begin(0x80003236L);
        emit("LDDPC R8,0x80003368");
        emit("LD.SH R8,R8[0x0]");
        emit("MOV R12,R8");
        emit("MCALL PC[0x8000336c]");
        emit("MOV R8,0x3232");
        emit("LD.UB R9,R8[0x0]");
        emit("CP.W R9,0x1");
        emit("BR{ne} 0x80003252");
        emit("MOV R9,0x0");
        emit("ST.B R8[0x0],R9");
        emit("MCALL PC[0x8001a268]");
        padTo(0x80003252L);
        padTo(0x80003256L);
        finish("pitch_store_hook", 0x80003256L);

        // Repurposed pool word: was the last-sent mirror address (0x3212),
        // now the remap entry point read by the MCALL above.
        begin(0x8000336cL);
        word(0x80019980L);
        finish("pitch_hook_pool", 0x80003370L);

        singlePatch("pressure_gain_nop", 0x800043a4L, "NOP");
        fixedPatch("transpose_force_1", 0x80005466L, 4, "MOV R8,0x1");
        fixedPatch("transpose_force_2", 0x800062f8L, 4, "MOV R8,0x1");
        fixedPatch("pitch_clamp_skip_1", 0x800033f8L, 2, "RJMP 0x80003506");
        fixedPatch("pitch_clamp_skip_2", 0x800033c0L, 2, "RJMP 0x800033d6");
        wordPatch("pressure_fn_pool", 0x80003574L, 0x80019580L,
            "int-to-float pointer -> calibrated pressure curve");
        fixedPatch("transpose_force_3", 0x80005392L, 2, "MOV R8,0x1");
        wordPatch("pressure_float_helper_pool", 0x8000357cL, 0x80013434L,
            "restore original post-gain float-to-int helper");
        wordPatch("knob1_pool", 0x800043c4L, 0x800194c0L,
            "knob-1 pointer -> pressure-ceiling wrapper");
        wordPatch("knob3_pool", 0x800043ccL, 0x80014300L,
            "knob-3 pointer -> pressure-floor wrapper");
        wordPatch("knob4_pool", 0x800043d0L, 0x80014380L,
            "knob-4 pointer -> knob4_curve");
        // Remote-enable guards always see 0: state+2 now stores the tuning
        // selector, and the remote feature is permanently retired.
        begin(0x80006528L);
        emit("MOV R8,0x0");
        emit("CP.W R8,0x0");
        finish("remote_guard_1", 0x8000652cL);
        begin(0x800066aeL);
        emit("MOV R8,0x0");
        emit("CP.W R8,0x0");
        finish("remote_guard_2", 0x800066b2L);
        begin(0x800085daL);
        emit("MOV R8,0x0");
        emit("CP.W R8,0x0");
        finish("remote_guard_3", 0x800085deL);
        wordPatch("note_on_pool", 0x80005e8cL, 0x80018d00L,
            "note-on pointer -> filter-reset wrapper");
        wordPatch("active_key_pool", 0x80006280L, 0x80018d40L,
            "active-key pointer -> filter-reset wrapper");
    }
}
