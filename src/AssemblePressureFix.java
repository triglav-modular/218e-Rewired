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

    private int number(String key, int fallback, int low, int high) {
        String raw = cfg.getProperty("number." + key, "").trim();
        int value = raw.isEmpty() ? fallback : Integer.parseInt(raw);
        // The bounds keep each MOV Rd,imm at the width its patch site allows.
        if (value < low || value > high) {
            throw new IllegalStateException(String.format(
                "Setting %s must be %d..%d to keep the encoding width: %d",
                key, low, high, value));
        }
        return value;
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
        // Report every block's extent before the enable check, so the build
        // can detect two caves claiming the same flash even when only one of
        // them is emitted in this configuration.
        println(String.format("EXTENT %08x %08x %s", base, expectedEnd, name));
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
        emit(String.format("LSR R8,0x%x", number("trim_shift", 2, 1, 4)));
        emit(String.format("SUB R8,-0x%x", number("floor_knob_base", 0x1c4, 0x80, 0x7d0)));
        emit("LD.W R9,R10[0x33c]");
        emit("BFEXTU R11,R9,0x0,0x10");
        emit("CP.W R11,0x20");
        emit("BR{lt} 0x80014346");
        emit("CP.W R11,0x3ff");
        emit("BR{ls} 0x8001434a");
        padTo(0x80014346L);
        emit(String.format("MOV R11,0x%x", number("pressure_ceiling_default", 0x348, 0x80, 0x7d0)));
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
        emit(String.format("MOV R9,0x%x", 0xa0 | number("curve_default_level", 0x1f, 0x0, 0x1f)));
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
        emit("MCALL PC[0x8001ac80]");
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
        word(0x00006080L); // raw-filter sample count
        word(0x8001a020L); // press-order list append
        word(0x8001a930L); // pitch-aware latch toggle (stamps on proceed)
        finish("note_on_reset_raw_filter", 0x80018d40L);

        // Release/source-selection wrapper. Preserve the selected-key return
        // value while clearing the sample count for the growing average.
        begin(0x80018d40L);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("MCALL PC[0x8001ac80]");
        emit("MCALL PC[0x80018d70]");
        emit("MOV R8,R12");
        emit("LDDPC R9,0x80018d74");
        emit("MOV R10,0x0");
        emit("ST.H R9[0x0],R10");
        emit("MOV R12,R8");
        emit("LDM SP++,R7,PC");
        padTo(0x80018d70L);
        word(0x8000596cL); // original active-key selector
        word(0x00006080L); // raw-filter sample count
        finish("source_change_reset_raw_filter", 0x80018d80L);

        // One entry per possible normalized raw pressure count. The shape is
        // the 218r's half-decade exponential: zero exactly at the floor, then
        // a ~32% onset step and a smooth 10 dB rise to full pressure. Values
        // exceed the linear ramp, so the blend must use an arithmetic shift.
        begin(0x80018d80L);
        emitTable("pressure_curve");
        // One sentinel repeat of the last entry: the interpolating lookup
        // reads table[i+1], and at full scale i is the final index.  A
        // sentinel is cheaper and safer than a bounds test in the hot path.
        {
            int[] curveTable = table("pressure_curve");
            halfword(curveTable[curveTable.length - 1]);
        }
        finish("half_decade_exponential_curve_table", 0x800194a8L);

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
        if (feature("pressure_trim_scale")) {
            // One knob for the whole calibration.  Capacitive coupling scales
            // the entire signal — lifting your feet off the floor costs about
            // 30% of it — so the useful control multiplies floor and ceiling
            // together rather than moving either endpoint on its own.
            // k runs 128..383 with 256 as unity, i.e. 0.5x to ~1.5x.
            // k = 128 + adc * span / 1024, where the build sizes `span` so the
            // scaled ceiling can never reach the 1023 validity limit.  A fixed
            // 0.5x..1.5x range would pin the ceiling partway up the knob while
            // the floor kept rising, narrowing the window instead of moving it.
            emit("LD.UH R8,R10[0x30a]");
            emit(String.format("MOV R9,0x%x", number("trim_scale_span", 0x100, 0x10, 0x100)));
            emit("MUL R8,R8,R9");
            emit("LSR R8,0xa");
            emit("SUB R8,-0x80");
            emit(String.format("MOV R9,0x%x", number("pressure_ceiling_default", 0x348, 0x80, 0x7d0)));
            emit("MUL R9,R9,R8");
            emit("LSR R9,0x8");
            emit(String.format("MOV R11,0x%x", number("pressure_floor_default", 0x244, 0x80, 0x7d0)));
            emit("MUL R11,R11,R8");
            emit("LSR R11,0x8");
            emit("MOV R8,R9");
            emit("MOV R12,0x3ff");
            emit("CP.W R8,R12");
            emit("BR{ls} 0x80019518");
            emit("MOV R8,R12");
        } else {
            emit("LD.UH R8,R10[0x30a]");
            emit(String.format("LSR R8,0x%x", number("trim_shift", 2, 1, 4)));
            emit(String.format("SUB R8,-0x%x", number("ceiling_knob_base", 0x2c8, 0x80, 0x7d0)));
            emit("LD.W R9,R10[0x33c]");
            emit("LSR R11,R9,0x10");
            emit("CP.W R11,0x3ff");
            emit("BR{ls} 0x80019518");
            emit(String.format("MOV R11,0x%x", number("pressure_floor_default", 0x244, 0x80, 0x7d0)));
        }
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
        emit(String.format("MOV R9,0x%x", 0xa0 | number("curve_default_level", 0x1f, 0x0, 0x1f)));
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
        // Prep cave: black-key scaling, and the debug A/B factory law.
        // Returns R9=0 to continue with our law (R12 = scaled raw), or R9=1
        // when the factory law already produced the final value — as a
        // FLOAT, because the caller feeds our return through the factory's
        // float-to-int helper; returning a raw integer here reads as a
        // denormal ~1e-42 and truncates to zero output.
        emit("MCALL PC[0x80019734]");
        emit("CP.W R9,0x0");
        emit("BR{eq} 0x800195a8");
        emit("LDM SP++,R7,PC");
        padTo(0x800195a8L);
        emit("MOV R8,R12");
        // The proximity estimate is subtracted per key inside the shared pass,
        // in the raw domain and before the colour correction — subtracting it
        // here, after aggregation and scaling, left a residual on black keys.
        padTo(0x800195bcL);
        // Growing average, now variable-depth (8..24 taps, edit knob 2) and
        // relocated to RAM 0x6050 — see the variable_filter cave.
        emit("MOV R12,R8");
        emit("MCALL PC[0x80019738]");
        emit("MOV R8,R12");
        padTo(0x80019600L);

        emit("LDDPC R12,0x80019728");
        emit("LD.UB R11,R12[0x2db]");
        emit("MOV R7,R11");
        emit("BFEXTU R7,R7,0x5,0x3");
        emit("CP.W R7,0x5");
        emit("BR{eq} 0x80019628");
        emit(String.format("MOV R7,0x%x", number("curve_default_level", 0x1f, 0x0, 0x1f)));
        emit(String.format("MOV R10,0x%x", number("pressure_floor_default", 0x244, 0x80, 0x7d0)));
        emit(String.format("MOV R9,0x%x", number("pressure_ceiling_default", 0x348, 0x80, 0x7d0)));
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
        emit(String.format("MOV R10,0x%x", number("pressure_floor_default", 0x244, 0x80, 0x7d0)));
        emit(String.format("MOV R9,0x%x", number("pressure_ceiling_default", 0x348, 0x80, 0x7d0)));
        padTo(0x80019670L);
        // The chain below runs in fixed point with `resolution_bits`
        // fractional bits, so the transfer function is unchanged — it is the
        // same mapping sampled finely instead of once per raw count.  With
        // bits = 0 every shift disappears and this is the original integer
        // arithmetic exactly.
        final int BITS = number("resolution_bits", 4, 0, 4);
        final int SPAN = 0x391 << BITS;
        if (BITS > 0) {
            emit(String.format("LSL R10,0x%x", BITS));
            emit(String.format("LSL R9,0x%x", BITS));
        }
        emit("CP.W R8,R10");
        emit("BR{hi} 0x80019686");
        emit("MOV R8,0x0");
        emit("RJMP 0x800196ac");
        padTo(0x80019686L);
        emit("CP.W R8,R9");
        emit("BR{lt} 0x80019698");
        emit(String.format("MOV R8,0x%x", SPAN));
        emit("RJMP 0x800196ac");
        padTo(0x80019698L);
        emit("SUB R8,R10");
        emit("SUB R9,R10");
        emit("MOV R10,0x391");
        emit("MUL R8,R10,R8");
        if (BITS > 0) {
            emit(String.format("LSR R9,0x%x", BITS));
        }
        emit("DIVU R8,R8,R9");
        padTo(0x800196acL);

        emit("CP.W R7,0x0");
        emit("BR{eq} 0x800196f4");
        emit("LDDPC R12,0x8001972c");
        if (BITS > 0) {
            // Interpolate the curve between adjacent table entries, so the
            // fractional part survives the lookup.  The index is clamped
            // below the last entry before its neighbour is read.
            emit(String.format("LSR R11,R8,0x%x", BITS));
            emit(String.format("BFEXTU R10,R8,0x0,0x%x", BITS));
            emit("LSL R11,0x1");
            emit("ADD R12,R11");
            emit("LD.UH R9,R12[0x0]");
            emit("LD.UH R11,R12[0x2]");
            emit("SUB R11,R9");
            emit("MUL R11,R11,R10");
            emit(String.format("LSL R9,0x%x", BITS));
            emit("ADD R9,R11");
        } else {
            emit("LD.UH R9,R12[R8 << 0x1]");
        }
        emit("SUB R9,R8,R9 << 0x0");
        emit("MOV R10,R7");
        emit("LSR R11,R10,0x2");
        emit("LSL R10,0x3");
        emit("ADD R10,R11");
        emit("MUL R9,R10,R9");
        emit("SUB R9,-0x80");
        emit("ASR R9,0x8");
        emit("SUB R8,R9");
        padTo(0x800196f4L);
        emit("MOV R9,0xfff");
        emit("MUL R8,R9,R8");
        emit(String.format("SUB R8,-0x%x", SPAN / 2));
        emit(String.format("MOV R9,0x%x", SPAN));
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
        word(0x8001a790L); // prep: black-key scale + debug A/B
        word(0x8001a800L); // variable-depth growing average
        finish("calibrated_pressure_curve", 0x8001973cL);

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

        // Capture the newest baseline-subtracted sample and the growing
        // average, both rounded to whole counts.  The pressure path itself
        // runs in fixed point, so these are a diagnostic approximation of it,
        // not the values it computes.
        emit("LDDPC R12,0x8001992c");
        // With the ring buffer the newest sample is not tap[0]; the filter
        // publishes it separately.  The mean below is order-independent, so
        // summing the first `count` taps stays correct either way.
        emit("MOV R8,0x608c");
        emit("LD.UH R8,R8[0x0]");
        emit("ST.H R7[-0x4],R8");        // instantaneous raw
        emit("LD.UH R11,R12[0x30]");     // growing-average sample count
        emit("MOV R9,0x0");
        emit("CP.W R11,0x0");
        emit("BR{ne} 0x80019790");
        emit("MOV R8,0x0");              // no samples yet: report average 0
        emit("RJMP 0x800197bc");
        padTo(0x80019790L);
        emit("CP.W R11,0x18");
        emit("BR{ls} 0x800197a0");
        emit("MOV R11,0x18");
        padTo(0x800197a0L);
        emit("LD.UH R8,R12[0x0]");
        emit("ADD R9,R8");
        emit("SUB R12,-0x2");
        emit("SUB R11,0x1");
        emit("BR{ne} 0x800197a0");
        emit("LDDPC R12,0x8001992c");
        emit("LD.UH R11,R12[0x30]");
        emit("CP.W R11,0x18");
        emit("BR{ls} 0x800197b8");
        emit("MOV R11,0x18");
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
        emit(String.format("MOV R11,0x%x", number("curve_default_level", 0x1f, 0x0, 0x1f)));
        emit("ST.B R7[-0xd],R11");
        emit(String.format("MOV R11,0x%x", number("pressure_floor_default", 0x244, 0x80, 0x7d0)));
        emit(String.format("MOV R9,0x%x", number("pressure_ceiling_default", 0x348, 0x80, 0x7d0)));
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
        if (feature("scan_profiler")) {

            // Diagnostic build: the two scan-component fields carry the
            // profiler's numbers instead.  CC 114/115 is the worst single
            // dispatch in cycles/32, CC 116/117 the CPU load in tenths of a
            // percent.
            emit("MOV R10,0x6032");
            emit("LD.UH R8,R10[0x0]");
            emit("ST.H R7[-0x10],R8");
            emit("LD.UH R8,R10[0x2]");
            emit("ST.H R7[-0x12],R8");
        } else if (feature("telemetry_smoothing")) {

            // Diagnostic: the two scan-component fields carry the live
            // smoothing state instead — CC 114/115 the filter depth in taps,
            // CC 116/117 the interpolator shift.  Turning edit knob 2 must
            // move both, or the knob path is broken.
            // scan A = filter depth in taps, scan B = interpolator shift —
            // confirms the configured smoothing is what actually runs.
            emit("MOV R10,0x6082");
            emit("LD.UH R8,R10[0x0]");
            emit("ST.H R7[-0x10],R8");
            emit("LD.UH R8,R10[0x2]");
            emit("ST.H R7[-0x12],R8");
        } else {
            emit("LD.UB R10,R12[0x256]");     // active key index, 0..28
            emit("LDDPC R11,0x80019938");     // key-to-scan-channel map
            emit("LD.UB R10,R11[R10 << 0x0]");
            emit("LSL R10,0x1");
            emit("ADD R12,R12,R10 << 0x0");
            emit("LD.UH R8,R12[0x86]");
            emit("ST.H R7[-0x10],R8");       // scan component A
            emit("LD.UH R8,R12[0xd6]");
            emit("ST.H R7[-0x12],R8");       // scan component B
        }

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
        word(0x00006050L); // pressure-history taps (up to 24), count at +0x30
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
        word(0x8001a8a0L); // transpose-capture shim, chains to the cave below
        emit("STM --SP,R0,R1,R2,R3,R4,R5,R7,LR");
        emit("MOV R7,SP");
        emit("MOV R4,R12");
        emit("MCALL PC[0x8001ac80]");
        emit("LDDPC R9,0x80019d34");
        emit("LD.SH R11,R9[0x350]");
        // Portamento knob = pressure-needed-to-bend: T = 1023 - knob.
        // At knob zero T exceeds any possible touch, so only the sounding
        // key contributes and the blend is exactly zero (factory behavior).
        // The anchor key is never thresholded, so engagement is smooth.
        // Read the KNOB MIRROR (state+0x306), not the combined rate index at
        // +0x3a2 — the index carries a pressure-derived addend, so with the
        // full 218r curve the threshold would move with pressure and the
        // blend would engage and disengage erratically under the fingers.
        emit("LD.SH R5,R9[0x306]");
        // Hard gate: below the knob's deadzone the blend loop never runs at
        // all — multi-finger common-mode sensor inflation can push deltas
        // past any threshold, so "off" must not depend on pressure at all.
        emit("CP.W R5,0x30");
        emit("BR{lt} 0x80019d20");
        emit("MOV R8,0x3ff");
        emit("SUB R5,R8,R5 << 0x0");
        emit("MOV R0,0x0");
        emit("MOV R1,0x0");
        // R3 carries the base the offset is measured from, in the same pitch
        // domain as the contributors.  It defaults to the unstamped base and
        // becomes the anchor's stamped pitch when the anchor is found.
        emit("MOV R3,R11");
        // Keys 28..0: the cache and the latch stamps cover 29 slots, and slot
        // 31's stamp address is the blend's own target cell.
        emit("MOV R2,0x1c");
        padTo(0x80019c90L);
        emit("ADD R8,R9,R2 << 0x0");
        emit("LD.UB R10,R8[0x21b]");
        emit("CP.W R10,0x1");
        emit("BR{ne} 0x80019d08");
        emit("MOV R8,0x854");
        emit("ADD R8,R8,R2 << 0x1");
        emit("LD.UH R10,R8[0x0]");
        // Identify the anchor on the UNSTAMPED pitch, before any stamp moves
        // it, and keep the answer: it decides both the threshold exemption and
        // which contributor supplies the base.
        emit("CP.W R10,R11");
        emit("SR{EQ} R12");
        if (feature("arp_latch")) {
            // In latch mode a slot sounds at table[k] plus its stamp, so it is
            // weighted at that pitch — otherwise a note latched an octave away
            // pulls toward where its key sits now rather than where it sounds.
            emit("MOV R8,0x608e");
            emit("LD.UB R8,R8[0x0]");
            emit("CP.W R8,0x1");
            emit("BR{ne} 0x80019cc0");
            emit("MOV R8,0x60a2");
            emit("LD.SH R8,R8[R2 << 0x1]");
            emit("ADD R10,R8");
        }
        padTo(0x80019cc0L);
        // The base must sit in the same domain as the contributors: measuring
        // a stamped contributor against an unstamped base published the stamp
        // itself as an offset, which the glide target had already applied.
        emit("CP.W R12,0x0");
        emit("BR{eq} 0x80019cc8");
        emit("MOV R3,R10");
        padTo(0x80019cc8L);
        emit("MOV R8,0x6100");
        emit("LD.UH R8,R8[R2 << 0x1]");
        emit("CP.W R12,0x0");
        emit("BR{ne} 0x80019cdc");
        emit("SUB R8,R8,R5 << 0x0");
        padTo(0x80019cdcL);
        emit("CP.W R8,0x0");
        emit("BR{le} 0x80019d08");
        emit("LSR R8,0x4");
        emit("CP.W R8,0x3f");
        emit("BR{ls} 0x80019cf4");
        emit("MOV R8,0x3f");
        padTo(0x80019cf4L);
        emit("MOV R12,R8");
        emit("MUL R12,R12,R8");
        emit("MUL R12,R12,R8");
        // Scale the cubic weight only as far as 32-bit overflow safety needs.
        // >>3 keeps all 29 worst-case products below 2^32 while retaining
        // distinct weights at light pressure (z=4/5/6 -> 8/15/27).  The old
        // >>6 collapsed those touches to 1/1/3 and audibly flattened the blend.
        emit("LSR R12,0x3");
        emit("ADD R0,R12");
        emit("MUL R12,R12,R10");
        emit("ADD R1,R12");
        padTo(0x80019d08L);
        emit("SUB R2,0x1");
        emit("BR{ge} 0x80019c90");
        padTo(0x80019d10L);
        emit("CP.W R0,0x0");
        emit("BR{eq} 0x80019d20");
        emit("DIVU R0,R1,R0");
        // Publish X - base as an OFFSET (RAM 0x60e0) instead of folding it
        // into the glide target: the pitch shim adds it after the glide
        // engine, so pressure steers the pitch immediately while the same
        // knob keeps its classic note-to-note portamento.  Both paths store,
        // so a released chord zeroes the offset within one scan.
        emit("SUB R0,R0,R3 << 0x0");
        emit("RJMP 0x80019d24");
        padTo(0x80019d20L);
        emit("MOV R0,0x0");
        padTo(0x80019d24L);
        emit("MOV R8,0x60e0");
        emit("ST.H R8[0x0],R0");
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
        // Keys 0..28 only.  The held-flag array is 29 entries — the factory's
        // own selectors start their walk at 0x1c — so scanning 32 read three
        // bytes of unrelated state beyond it and treated any that happened to
        // hold 1 as a held key.  The random branch trusts these flags without
        // the press-order path's held re-check, so a phantom 29/30/31 played
        // straight out as a pitch up to an octave above the real key.
        emit("CP.W R3,0x1d");
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
        if (feature("pressure_blend")) {
            // No time-based glide: the pressure-weighted blend is the only
            // portamento.  Notes snap; the knob means pressure-needed-to-bend.
            emit("STM --SP,R7,LR");
            emit("MOV R7,SP");
            emit("MOV R8,0x0");
            emit("MOV R9,0x2eee");
            emit("ST.H R9[0x0],R8");
            emit("LDM SP++,R7,PC");
        } else {
            // Blend-off builds keep classic portamento with the zero-snap.
            emit("STM --SP,R7,LR");
            emit("MOV R7,SP");
            emit("MOV R8,0x3866");
            emit("LD.SH R8,R8[0x0]");
            emit("CP.W R8,0x30");
            emit("BR{ge} 0x8001a24c");
            emit("MOV R8,0x0");
            emit("RJMP 0x8001a254");
            padTo(0x8001a24cL);
            emit("LDDPC R8,0x8001a260");
            emit("LD.SH R8,R8[R9 << 0x1]");
            emit("CASTS.H R8");
            padTo(0x8001a254L);
            emit("MOV R9,0x2eee");
            emit("ST.H R9[0x0],R8");
            emit("LDM SP++,R7,PC");
        }
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
        emit("MCALL PC[0x8001ac80]");
        emit("MCALL PC[0x8001a340]");          // tuning applier
        if (feature("knob4_vibrato")) {
            emit("MCALL PC[0x8001a344]");      // vibrato engine
        }
        emit("MCALL PC[0x8001a348]");          // per-scan housekeeping
        if (feature("pressure_ab_switch")) {
            emit("MCALL PC[0x8001a34c]");      // octave-switch shadow sync
        }
        emit("LDM SP++,R7,PC");
        padTo(0x8001a338L);
        word(0x00003560L); // global state base
        word(0x80005a50L); // original note-off
        word(0x80019a40L); // tuning applier
        word(0x8001a350L); // vibrato engine
        word(0x8001a480L); // latch watch + poly-MIDI boot force + common-mode
        word(0x8001a750L); // octave-switch shadow sync
        finish("latch_v2", 0x8001a350L);

        // Scan profiler (diagnostic).  Wraps the main loop's event dispatcher
        // so every event handler is timed with the CPU cycle counter, which
        // free-runs at the CPU clock and which nothing else in the firmware
        // writes.  Answers one question: how much of each scan period is
        // already spoken for, and therefore whether a shorter period fits.
        //
        //   RAM 0x6032  worst single dispatch in the last window, cycles/32
        //   RAM 0x6034  CPU load over the last window, tenths of a percent
        //   RAM 0x6038  window start / 0x603c busy accumulator / 0x6040 max
        //
        // The accumulators need no initialisation: whatever the SRAM powers
        // up holding produces one bogus window, after which the rollover
        // resets everything.
        begin(0x8001a540L);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("MFSR R8,COUNT");
        emit("ST.W --SP,R8");
        emit("MCALL PC[0x8001a5e0]");
        emit("MFSR R9,COUNT");
        emit("LD.W R8,SP++");
        emit("SUB R9,R9,R8 << 0x0");   // cycles this dispatch took
        emit("MOV R10,0x6038");
        emit("LD.W R11,R10[0x4]");
        emit("ADD R11,R9");
        emit("ST.W R10[0x4],R11");     // busy += delta
        emit("LD.W R11,R10[0x8]");
        emit("CP.W R9,R11");
        emit("BR{ls} 0x8001a570");
        emit("ST.W R10[0x8],R9");      // max = delta
        padTo(0x8001a570L);
        emit("MFSR R8,COUNT");
        emit("LD.W R11,R10[0x0]");
        emit("SUB R8,R8,R11 << 0x0");  // elapsed since the window opened
        emit("LDDPC R11,0x8001a5e4");
        emit("CP.W R8,R11");
        emit("BR{ls} 0x8001a5dc");     // window still open
        emit("MOV R9,0x3e8");
        emit("DIVU R8,R8,R9");         // R8 = elapsed/1000
        emit("CP.W R8,0x0");
        emit("BR{eq} 0x8001a5c8");
        emit("LD.W R12,R10[0x4]");
        emit("DIVU R8,R12,R8");        // R8 = busy per mille of the window
        emit("MOV R11,0x3e8");
        emit("CP.W R8,R11");
        emit("BR{ls} 0x8001a5a4");
        emit("MOV R8,R11");
        padTo(0x8001a5a4L);
        emit("MOV R11,0x6034");
        emit("ST.H R11[0x0],R8");      // load, tenths of a percent
        emit("LD.W R12,R10[0x8]");
        emit("LSR R12,0x5");           // cycles/32, to fit a 14-bit CC pair
        emit("MOV R11,0x3fff");
        emit("CP.W R12,R11");
        emit("BR{ls} 0x8001a5bc");
        emit("MOV R12,R11");
        padTo(0x8001a5bcL);
        emit("MOV R11,0x6032");
        emit("ST.H R11[0x0],R12");     // worst dispatch
        padTo(0x8001a5c8L);
        emit("MOV R8,0x0");
        emit("ST.W R10[0x4],R8");
        emit("ST.W R10[0x8],R8");
        emit("MFSR R8,COUNT");
        emit("ST.W R10[0x0],R8");      // open the next window
        padTo(0x8001a5dcL);
        emit("LDM SP++,R7,PC");
        padTo(0x8001a5e0L);
        word(0x80004c64L); // real event dispatcher
        word(0x01000000L); // window length in cycles (~280 ms at 60 MHz)
        finish("scan_profiler", 0x8001a5e8L);

        // Main-loop dispatcher pointer -> profiler wrapper.
        begin(0x80007dc0L);
        word(0x8001a540L);
        finish("profiler_pool", 0x80007dc4L);

        // Pressure output interpolation.  The scan writes a target at RAM
        // 0x6036 and this 1 kHz handler divides each new gap over N remaining
        // ticks.  Recomputing gap/N distributes integer remainders, and the
        // last tick snaps exactly to target: five smaller DAC treads, with no
        // exponential tail and no change to the already-full scan schedule.
        // Shared first-use initialisation makes a separate byte marker here
        // unnecessary and guarantees the target, snapshot, counter and DAC
        // slot become valid atomically before any of them is read.
        begin(0x8001a600L);
        emit("MCALL PC[0x8001ac80]");
        emit("MOV R10,0x6036");
        emit("LD.SH R11,R10[0x0]");     // target
        emit("CP.W R11,0x0");
        emit("BR{ge} 0x8001a612");
        emit("MOV R11,0x0");
        padTo(0x8001a612L);
        emit("MOV R9,0xfff");
        emit("CP.W R11,R9");
        emit("BR{ls} 0x8001a61e");
        emit("MOV R11,R9");             // clamped to the 12-bit DAC range
        padTo(0x8001a61eL);
        emit("LDDPC R12,0x8001a690");
        emit("LD.SH R8,R12[0x356]");    // where the output is now
        emit("MOV R9,0x602c");
        emit("LD.UH R10,R9[0x0]");      // target snapshot
        emit("CP.W R10,R11");
        emit("BR{eq} 0x8001a64c");
        emit("ST.H R9[0x0],R11");
        emit("MOV R10,0x6084");
        emit("LD.UH R10,R10[0x0]");
        emit("CP.W R10,0x8");
        emit("BR{ls} 0x8001a644");
        emit("MOV R10,0x8");
        padTo(0x8001a644L);
        emit("ST.H R9[0x2],R10");       // restart only for a new target
        emit("RJMP 0x8001a650");
        padTo(0x8001a64cL);
        emit("LD.UH R10,R9[0x2]");
        padTo(0x8001a650L);
        emit("SUB R9,R11,R8 << 0x0");   // signed gap remaining
        emit("CP.W R10,0x1");
        emit("BR{ls} 0x8001a674");
        // DIVS writes a quotient/remainder register pair.  Save the decremented
        // counter first, then use scratch pair R10:R11 without touching the
        // dispatcher's callee-saved registers.
        emit("SUB R10,0x1");
        emit("MOV R11,0x602e");
        emit("ST.H R11[0x0],R10");
        emit("SUB R10,-0x1");
        emit("DIVS R10,R9,R10");
        emit("ADD R8,R10");
        emit("RJMP 0x8001a680");
        padTo(0x8001a674L);
        emit("MOV R8,R11");             // last (or disabled) tick is exact
        emit("MOV R10,0x0");
        emit("MOV R9,0x602e");
        emit("ST.H R9[0x0],R10");
        padTo(0x8001a680L);
        emit("ST.H R12[0x356],R8");
        padTo(0x8001a688L);
        emit("LDDPC R12,0x8001a694");
        emit("MOV PC,R12");             // on into the factory flush handler
        padTo(0x8001a690L);
        word(0x00003560L); // global state base
        word(0x80004f66L); // factory event-17 case
        finish("dac_interpolator", 0x8001a698L);

        // Dispatcher jump-table entry 17 (DAC flush) -> interpolator.
        begin(0x8001485cL);
        word(0x8001a600L);
        finish("dac_flush_pool", 0x80014860L);

        // The scan's pressure store now lands on the interpolator's target
        // (state+0x2ad6 = RAM 0x6036) instead of the DAC slot directly.
        fixedPatch("pressure_target_redirect", 0x80002db2L, 4, "ST.H R9[0x2ad6],R8");

        // Local proximity estimator.  R12 is the held key being corrected.
        // Walk outward on each
        // side past touched keys (and past the immediate neighbours, which
        // carry spill from the pressing finger itself) to the first untouched
        // key; take the larger of the two sides.  Whatever that key reads
        // above `proximity_reference` is field from a hovering hand, and is
        // returned in R12.  Calling this per physically held key keeps distant
        // hands from sharing the last-active key's field estimate.
        begin(0x8001a6a0L);
        emit("STM --SP,R7,R9,LR");
        emit("MOV R7,SP");
        emit("MOV R11,0x0");
        emit("CP.W R12,0x1c");
        emit("BR{hi} 0x8001a720");
        emit("MOV R9,R12");
        emit("SUB R9,-0x2");
        emit("MOV R10,0x3");
        padTo(0x8001a6b8L);
        emit("CP.W R9,0x1c");
        emit("BR{gt} 0x8001a6e8");
        emit("MOV R8,0x3490");
        emit("ADD R8,R8,R9 << 0x0");
        emit("LD.UB R8,R8[0x0]");
        emit("CP.W R8,0x2");
        emit("BR{ne} 0x8001a6d4");
        emit("SUB R10,0x1");
        emit("BR{eq} 0x8001a6e8");
        emit("SUB R9,-0x1");
        emit("RJMP 0x8001a6b8");
        padTo(0x8001a6d4L);
        emit("MOV R8,0x3686");
        emit("ADD R8,R8,R9 << 0x1");
        emit("LD.UH R8,R8[0x0]");
        emit("CP.W R8,R11");
        emit("BR{ls} 0x8001a6e8");
        emit("MOV R11,R8");
        padTo(0x8001a6e8L);
        emit("MOV R10,0x3");
        emit("MOV R9,R12");
        emit("SUB R9,0x2");
        padTo(0x8001a6f0L);
        emit("CP.W R9,0x0");
        emit("BR{lt} 0x8001a720");
        emit("MOV R8,0x3490");
        emit("ADD R8,R8,R9 << 0x0");
        emit("LD.UB R8,R8[0x0]");
        emit("CP.W R8,0x2");
        emit("BR{ne} 0x8001a70c");
        emit("SUB R10,0x1");
        emit("BR{eq} 0x8001a720");
        emit("SUB R9,0x1");
        emit("RJMP 0x8001a6f0");
        padTo(0x8001a70cL);
        emit("MOV R8,0x3686");
        emit("ADD R8,R8,R9 << 0x1");
        emit("LD.UH R8,R8[0x0]");
        emit("CP.W R8,R11");
        emit("BR{ls} 0x8001a720");
        emit("MOV R11,R8");
        padTo(0x8001a720L);
        emit(String.format("MOV R8,0x%x", number("proximity_reference", 0x12c, 0x6e, 0x7d0)));
        emit("SUB R11,R11,R8 << 0x0");
        emit("CP.W R11,0x0");
        emit("BR{ge} 0x8001a734");
        emit("MOV R11,0x0");
        padTo(0x8001a734L);
        emit("MOV R12,R11");
        emit("LDM SP++,R7,R9,PC");
        padTo(0x8001a748L);
        word(0x00003560L); // global state base
        finish("proximity_estimator", 0x8001a74cL);

        // Octave-switch shadow sync (debug A/B builds).  The switch reader's
        // stores are redirected to shadow RAM (flags 0x6046/0x6047, position
        // word 0x6048), so the live position drives only the pressure A/B.
        // For the first ~200 scans after the power-up init the shadow is
        // copied into the real state bytes, which applies the position the
        // switch sits in at power-on; after that the octave function is
        // frozen and the switch is free to flip.
        begin(0x8001a750L);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("MOV R9,0x604c");
        emit("LD.UH R8,R9[0x0]");
        emit("CP.W R8,0xc8");
        emit("BR{ge} 0x8001a780");
        emit("SUB R8,-0x1");
        emit("ST.H R9[0x0],R8");
        emit("LDDPC R10,0x8001a788");
        emit("MOV R9,0x6046");
        emit("LD.UB R8,R9[0x0]");
        emit("ST.B R10[0x342],R8");
        emit("LD.UB R8,R9[0x1]");
        emit("ST.B R10[0x343],R8");
        emit("LD.W R8,R9[0x2]");
        emit("ST.W R10[0x344],R8");
        padTo(0x8001a780L);
        emit("LDM SP++,R7,PC");
        padTo(0x8001a788L);
        word(0x00003560L); // global state base
        finish("octswitch_sync", 0x8001a78cL);

        // Pressure prep.  (1) Black keys have physically smaller pads, so the
        // same finger pressure couples less charge — measured ~0.72-0.81x of
        // a white key.  Scale the raw value up for black keys (mask bit per
        // key, C at the bottom) so both key colours land in one calibration
        // window.  (2) The debug A/B factory law, when enabled: linear gain
        // into saturation, converted through the same int-to-float helper the
        // normal epilogue uses, since the caller expects a float back.
        begin(0x8001a790L);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        // Correct every key once per scan into the shared cache, then let the
        // consumers read it.  This runs on the pressure path, which happens
        // once per scan, so the portamento weighting may read a cache one scan
        // (5 ms) old — immaterial against its own 20 ms slew, and worth it to
        // stop two loops applying the same correction differently.
        emit("MCALL PC[0x8001a7f4]");
        if (false) {
        } else if (!feature("multi_key_pressure")) {
            emit("LDDPC R10,0x8001a7f0");
            emit("LD.UB R8,R10[0x256]");
            emit("CP.W R8,0x1c");
            emit("BR{hi} 0x8001a7c0");
            emit("MOV R9,0xa54a");
            emit("ORH R9,0xa54");
            emit("LSR R9,R9,R8");
            emit("BFEXTU R9,R9,0x0,0x1");
            emit("CP.W R9,0x0");
            emit("BR{eq} 0x8001a7c0");
            emit(String.format("MOV R9,0x%x", number("black_key_scale_32", 43, 32, 96)));
            emit("MUL R12,R12,R9");
            emit("LSR R12,0x5");
        }
        padTo(0x8001a7c0L);
        if (feature("pressure_ab_switch")) {
            emit("MOV R9,0x6046");
            emit("LD.UB R9,R9[0x0]");
            emit("CP.W R9,0x1");
            emit("BR{ne} 0x8001a7e8");
            emit(String.format("LSL R12,0x%x", number("factory_gain_shift", 3, 1, 5)));
            emit("MOV R9,0xfff");
            emit("CP.W R12,R9");
            emit("BR{ls} 0x8001a7d8");
            emit("MOV R12,R9");
            padTo(0x8001a7d8L);
            emit("MCALL PC[0x8001a7f8]");
            emit("MOV R9,0x1");
            emit("LDM SP++,R7,PC");
        }
        padTo(0x8001a7e8L);
        emit("MOV R9,0x0");
        emit("LDM SP++,R7,PC");
        padTo(0x8001a7f0L);
        word(0x00003560L); // global state base
        word(0x8001aa10L); // multi-key pressure combiner
        word(0x80013350L); // signed-int-to-float helper (same as the epilogue)
        word(0x8001aa90L); // per-key corrected-pressure cache fill
        finish("pressure_prep", 0x8001a800L);

        // Variable-depth growing average.  Depth N (8..24 taps = 40..120 ms
        // at the 5 ms scan) lives at RAM 0x6082, set by edit knob 2; taps at
        // RAM 0x6050, sample count still at 0x6080 (zeroed by the note-on and
        // source-change wrappers).  Averages only the samples gathered since
        // the touch, so attacks stay instant at any depth.
        begin(0x8001a800L);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("MOV R8,R12");
        // Depth (RAM 0x6082), clamped against power-up garbage.
        emit("MOV R9,0x6082");
        emit("LD.UH R11,R9[0x0]");
        emit("CP.W R11,0x8");
        emit("BR{ge} 0x8001a816");
        emit("MOV R11,0x8");
        padTo(0x8001a816L);
        emit("CP.W R11,0x18");
        emit("BR{le} 0x8001a81e");
        emit("MOV R11,0x18");
        padTo(0x8001a81eL);
        // Ring buffer with a running sum: one subtract, one add and one store
        // per scan instead of shifting the whole history and re-summing it.
        // A zero count — set by the note-on and source-change wrappers — also
        // resets the ring, so a new touch starts clean.
        //
        // Count and index are checked against the depth before they are
        // trusted, not just the depth against its own bounds.  The write index
        // scales a store off 0x6050, so an out-of-range one is a wild halfword
        // write into whatever follows — the corrected-pressure cache, or the
        // stack.  Power-up normally clears these, but that clearing is gated
        // on a 16-bit marker surviving in SRAM; a collision, or a brownout
        // that retains the marker and little else, would otherwise walk
        // straight into that store.  Everything reachable is one comparison
        // away, so check rather than rely on the marker.
        emit("MOV R9,0x6080");
        emit("LD.UH R10,R9[0x0]");      // count
        emit("LD.UH R12,R9[0x6]");      // write index
        emit("CP.W R10,0x0");
        emit("BR{eq} 0x8001a836");      // empty: also clears a stale index/sum
        emit("CP.W R10,R11");
        emit("BR{hi} 0x8001a836");      // more samples than the ring holds
        // Reversed operands: the assembler takes {hi} but not {lo}.
        emit("CP.W R11,R12");
        emit("BR{hi} 0x8001a842");      // depth > index, so it is inside: trust it
        padTo(0x8001a836L);
        emit("MOV R10,0x0");
        emit("MOV R12,0x0");
        emit("ST.H R9[0x0],R10");
        emit("ST.H R9[0x6],R12");
        emit("ST.W R9[0x8],R10");
        padTo(0x8001a842L);
        emit("LD.W R9,R9[0x8]");        // running sum; base is done with
        emit("CP.W R10,R11");
        emit("BR{lt} 0x8001a858");
        // Full: drop the oldest sample, which is the one at the write index.
        emit("MOV LR,0x6050");
        emit("LD.UH LR,LR[R12 << 0x1]");
        emit("SUB R9,R9,LR << 0x0");
        emit("RJMP 0x8001a85c");
        padTo(0x8001a858L);
        emit("SUB R10,-0x1");
        padTo(0x8001a85cL);
        emit("MOV LR,0x6050");
        emit("ST.H LR[R12 << 0x1],R8");
        emit("ADD R9,R8");
        emit("SUB R12,-0x1");
        emit("CP.W R12,R11");
        emit("BR{lt} 0x8001a870");
        emit("MOV R12,0x0");
        padTo(0x8001a870L);
        // Write the ring state back off one base: the four cells live within
        // 0x6080..0x608d, and folding the addresses into displacements buys
        // the bytes the validation above costs.
        emit("MOV LR,0x6080");
        emit("ST.H LR[0x0],R10");       // 0x6080 count
        emit("ST.H LR[0x6],R12");       // 0x6086 write index
        emit("ST.W LR[0x8],R9");        // 0x6088 running sum
        emit("ST.H LR[0xc],R8");        // 0x608c newest sample
        // Keep `resolution_bits` fractional bits of the mean.
        if (number("resolution_bits", 4, 0, 4) > 0) {
            emit(String.format("LSL R9,0x%x", number("resolution_bits", 4, 0, 4)));
        }
        emit("DIVU R8,R9,R10");
        emit("MOV R12,R8");
        emit("LDM SP++,R7,PC");
        finish("variable_filter", 0x8001a890L);

        // (Edit knob 2 smoothing control removed for now: the wrapper ran and
        // stored, but its ADC mirror read never followed the physical knob in
        // edit mode.  The filter depth and interpolation length are fixed from the build
        // config until the edit-mode knob mirror question is settled.)

        // Per-scan transpose capture and, in latch mode, the per-note hold:
        // R12 arrives as base+offset; G = R12 - state[0x350] is published for
        // the note-on stamp, and the sounding note (last arp key, 0x34d) is
        // re-based to the offset it was stamped with.  Doing this here makes
        // the hold exact within a single scan — no transient when the octave
        // switch flips between arp steps.
        begin(0x8001a8a0L);
        emit("STM --SP,R7,LR");
        emit("MCALL PC[0x8001ac80]");
        emit("MOV R8,0x38b0");
        emit("LD.SH R9,R8[0x0]");
        emit("SUB R9,R12,R9 << 0x0");
        emit("MOV R8,0x60a0");
        emit("ST.H R8[0x0],R9");
        if (feature("arp_latch")) {
            emit("MOV R10,0x38a0");
            emit("LD.UB R11,R10[0x0]");
            emit("CP.W R11,0x1");
            emit("BR{ne} 0x8001a8d8");
            emit("LD.UB R10,R10[0xd]");
            emit("CP.W R10,0x1c");
            emit("BR{hi} 0x8001a8d8");
            emit("ADD R8,R8,R10 << 0x1");
            emit("LD.SH R8,R8[0x2]");
            emit("SUB R8,R8,R9 << 0x0");
            emit("ADD R12,R8");
        }
        padTo(0x8001a8d8L);
        emit("MCALL PC[0x8001a8e0]");
        emit("LDM SP++,R7,PC");
        padTo(0x8001a8e0L);
        word(0x80019c64L); // the real blend cave entry
        finish("transpose_capture", 0x8001a8e4L);

        // Post-glide blend apply, with smoothing.  The blend cave publishes a
        // raw offset target each scan; this shim slews the APPLIED offset
        // (RAM 0x60e2) toward it by 1/2^blend_slew_shift of the remaining gap
        // per scan, so sensor jitter walking the z quantisation near the
        // threshold cannot frequency-modulate the pitch.  The +-1 nudge
        // prevents the shift from stalling short of the target.
        //
        // This is exponential, not a fixed settling time: at the default shift
        // of 2 it closes a quarter of the gap every 5 ms, which is ~17 ms to
        // 63%, ~55 ms to 95% and ~85 ms to 99%, plus up to one scan of
        // pressure-cache latency ahead of it.  An earlier comment called it a
        // "20 ms settle", which is the time constant, not the settle.
        begin(0x8001a8f0L);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("MCALL PC[0x8001ac80]");
        emit("MOV R9,0x60e0");
        emit("LD.SH R10,R9[0x0]");
        emit("LD.SH R8,R9[0x2]");
        emit("SUB R11,R10,R8 << 0x0");
        emit(String.format("ASR R11,0x%x", number("blend_slew_shift", 2, 0, 4)));
        emit("CP.W R11,0x0");
        emit("BR{ne} 0x8001a914");
        emit("CP.W R10,R8");
        emit("BR{le} 0x8001a914");
        emit("MOV R11,0x1");
        padTo(0x8001a914L);
        emit("ADD R8,R11");
        emit("ST.H R9[0x2],R8");
        emit("ADD R12,R8");
        emit("CP.W R12,0x0");
        emit("BR{ge} 0x8001a924");
        emit("MOV R12,0x0");
        padTo(0x8001a924L);
        emit("MCALL PC[0x8001a92c]");
        emit("LDM SP++,R7,PC");
        padTo(0x8001a92cL);
        word(0x80019980L); // the real pitch remap
        finish("blend_offset_apply", 0x8001a930L);

// Pitch-aware latch: latched notes are pitches held in slots.  A slot k
        // sounds at table[k] + stamp[k], and the stamp can be any value — so a
        // pitch is not tied to its own key's slot.  A press computes its
        // would-be pitch P: if a latched slot sounds P, it unlatches (toggle,
        // from any octave).  Otherwise the pitch latches into the pressed
        // key's slot, or any free slot if that one is occupied — the same key
        // pressed in three octaves yields three latched notes.  With no free
        // slot the press is suppressed.
        begin(0x8001a930L);
        emit("STM --SP,R0,R7,LR");
        emit("MOV R7,SP");
        emit("LDDPC R10,0x8001aa08");
        emit("CP.W R12,0x1c");
        emit("BR{hi} 0x8001a9e0");
        emit("MOV R8,0x854");
        emit("ADD R8,R8,R12 << 0x1");
        emit("LD.UH R11,R8[0x0]");
        emit("MOV R8,0x60a0");
        emit("LD.SH R8,R8[0x0]");
        emit("ADD R11,R8");
        emit("LD.UB R8,R10[0x340]");
        emit("CP.W R8,0x1");
        emit("BR{ne} 0x8001a9c0");
        emit("MOV R0,0x0");
        padTo(0x8001a960L);
        emit("ADD R9,R10,R0 << 0x0");
        emit("LD.UB R8,R9[0x21b]");
        emit("CP.W R8,0x1");
        emit("BR{ne} 0x8001a98c");
        emit("MOV R8,0x854");
        emit("ADD R8,R8,R0 << 0x1");
        emit("LD.UH R9,R8[0x0]");
        emit("MOV R8,0x60a0");
        emit("ADD R8,R8,R0 << 0x1");
        emit("LD.SH R8,R8[0x2]");
        emit("ADD R9,R8");
        emit("CP.W R9,R11");
        emit("BR{eq} 0x8001a9e8");
        padTo(0x8001a98cL);
        emit("SUB R0,-0x1");
        emit("CP.W R0,0x1c");
        emit("BR{le} 0x8001a960");
        emit("ADD R9,R10,R12 << 0x0");
        emit("LD.UB R8,R9[0x21b]");
        emit("CP.W R8,0x0");
        emit("BR{eq} 0x8001a9c0");
        emit("MOV R0,0x0");
        padTo(0x8001a9a4L);
        emit("ADD R9,R10,R0 << 0x0");
        emit("LD.UB R8,R9[0x21b]");
        emit("CP.W R8,0x0");
        emit("BR{eq} 0x8001a9bc");
        emit("SUB R0,-0x1");
        emit("CP.W R0,0x1c");
        emit("BR{le} 0x8001a9a4");
        emit("MOV R12,-0x1");
        emit("RJMP 0x8001a9e0");
        padTo(0x8001a9bcL);
        emit("MOV R12,R0");
        padTo(0x8001a9c0L);
        emit("MOV R8,0x854");
        emit("ADD R8,R8,R12 << 0x1");
        emit("LD.UH R9,R8[0x0]");
        emit("SUB R9,R11,R9 << 0x0");
        emit("MOV R8,0x60a0");
        emit("ADD R8,R8,R12 << 0x1");
        emit("ST.H R8[0x2],R9");
        padTo(0x8001a9e0L);
        emit("LDM SP++,R0,R7,PC");
        padTo(0x8001a9e8L);
        emit("ADD R9,R10,R0 << 0x0");
        emit("MOV R8,0x0");
        emit("ST.B R9[0x21b],R8");
        emit("LD.UB R8,R10[0x21a]");
        emit("CP.W R8,0x0");
        emit("BR{eq} 0x8001aa00");
        emit("SUB R8,0x1");
        emit("ST.B R10[0x21a],R8");
        padTo(0x8001aa00L);
        emit("MOV R12,-0x1");
        emit("LDM SP++,R0,R7,PC");
        padTo(0x8001aa08L);
        word(0x00003560L); // global state base
        finish("latch_pitch_toggle", 0x8001aa0cL);

        // One pass over the keys does all of it: subtract the baseline and a
        // spatially local proximity estimate, correct for key colour, publish the result
        // for the portamento weighting, and aggregate the physically held
        // keys for the pressure CV.
        //
        // Order matters here.  The proximity estimate is a raw-domain figure,
        // so it is subtracted BEFORE the colour correction — scaling first
        // and subtracting an unscaled estimate afterwards left roughly
        // (scale-1) x estimate behind on black keys.
        //
        // The factory sources pressure from the last key touched, so adding a
        // second key hands the CV to it and a barely-touched one reads below
        // the floor, cutting the output to zero with the first finger still
        // down.  Only PHYSICALLY held keys aggregate (touch state 2), so
        // latched keys, which have no finger on them, cannot drag it down.
        begin(0x8001aa10L);
        emit("STM --SP,R0,R1,R2,R3,R7,LR");
        emit("MOV R7,SP");
        emit("MCALL PC[0x8001ac80]");
        emit("LDDPC R0,0x8001ab1c");
        emit("MOV R1,0x0");
        emit("MOV R2,0x0");
        emit("MOV R3,0x0");
        emit("MOV R9,0x1c");
        padTo(0x8001aa30L);
        // Ignore released and latched slots entirely.  Publishing zero for
        // them also prevents the portamento loop from consuming old pressure.
        emit("MOV R11,0x3490");
        emit("ADD R11,R11,R9 << 0x0");
        emit("LD.UB R11,R11[0x0]");
        emit("CP.W R11,0x2");
        emit("BR{ne} 0x8001aab0");
        if (feature("pressure_common_mode")) {
            emit("MOV R12,R9");
            emit("MCALL PC[0x8001ab18]");
            emit("MOV R10,R12");
        } else {
            emit("MOV R10,0x0");
        }
        emit("MOV R8,0x3686");
        emit("ADD R8,R8,R9 << 0x1");
        emit("LD.UH R8,R8[0x0]");
        emit("SUB R8,0x6e");
        emit("SUB R8,R8,R10 << 0x0");
        emit("CP.W R8,0x0");
        emit("BR{gt} 0x8001aa68");
        emit("MOV R8,0x0");
        padTo(0x8001aa68L);
        emit("LD.UH R11,R0[R9 << 0x1]");
        emit("MUL R11,R8,R11");
        emit("SUB R11,-0x80");
        emit("LSR R11,0x8");
        emit("ADD R8,R11");
        emit("MOV R11,0x6100");
        emit("ST.H R11[R9 << 0x1],R8");
        emit("ADD R1,R8");
        emit("SUB R2,-0x1");
        emit("CP.W R8,R3");
        emit("BR{ls} 0x8001aaa8");
        emit("MOV R3,R8");
        padTo(0x8001aaa8L);
        emit("RJMP 0x8001aac0");
        padTo(0x8001aab0L);
        emit("MOV R8,0x0");
        emit("MOV R11,0x6100");
        emit("ST.H R11[R9 << 0x1],R8");
        padTo(0x8001aac0L);
        emit("SUB R9,0x1");
        emit("BR{ge} 0x8001aa30");
        if (feature("multi_key_pressure")) {
            // No key under a finger means no pressure.  Say so, rather than
            // leaving the caller's R12 to travel on: it only read as silence
            // because the value it happened to carry sat below the floor.
            emit("MOV R12,0x0");
            emit("CP.W R2,0x0");
            emit("BR{eq} 0x8001aae8");
            if (number("multi_key_max", 0, 0, 1) == 1) {
                emit("MOV R12,R3");
            } else {
                // Round the mean rather than truncating: with two keys a lost
                // half-count is a persistent bias of several DAC counts.
                emit("LSR R11,R2,0x1");
                emit("ADD R1,R11");
                emit("DIVU R8,R1,R2");
                emit("MOV R12,R8");
            }
        }
        padTo(0x8001aae8L);
        emit("LDM SP++,R0,R1,R2,R3,R7,PC");
        padTo(0x8001ab18L);
        word(0x8001a6a0L); // per-held-key proximity estimator
        padTo(0x8001ab1cL);
        word(0x8001ab20L); // key-colour coefficients, in flash
        finish("pressure_cache", 0x8001ab20L);

        // Per-key black-key correction, as a Q8 excess over unity: 0 for a
        // white key, round(scale*256)-256 for a black one.  Copied into RAM at
        // power-up so every consumer can reach it with a short immediate, and
        // so the pressure aggregate and the portamento weighting apply exactly
        // the same numbers.
        begin(0x8001ab20L);
        emitTable("black_key_excess");
        finish("black_key_excess_table", 0x8001ab60L);

        // Shared first-use bootstrap.  Every handler that consumes custom RAM
        // calls this before its first load, instead of relying on the later
        // pitch-applier housekeeping to happen first.  The build-derived
        // marker is written last, so an interrupted initialisation is retried.
        begin(0x8001ab60L);
        emit("STM --SP,R7,R8,R9,R10,R11,R12,LR");
        emit("MOV R7,SP");
        emit("MOV R9,0x602a");
        emit("LD.UH R8,R9[0x0]");
        emit(String.format("MOV R11,0x%x", number("init_marker", 0xb007, 0x1000, 0xeffe)));
        emit("CP.W R8,R11");
        emit("BR{eq} 0x8001ac74");
        emit("MOV R8,0x0");
        emit("LDDPC R10,0x8001ac7c");
        // Empty press-order state and every first-read signal-processing cell.
        emit("MOV R9,0x6000");
        emit("ST.B R9[0x0],R8");
        emit("MOV R9,0x604c");
        emit("ST.H R9[0x0],R8");
        emit("MOV R9,0x6024");
        emit("ST.W R9[0x0],R8");
        emit("MOV R9,0x6028");
        emit("ST.H R9[0x0],R8");
        emit("MOV R9,0x6080");
        emit("ST.H R9[0x0],R8");
        emit(String.format("MOV R11,0x%x", number("smoothing_taps", 8, 8, 24)));
        emit("MOV R9,0x6082");
        emit("ST.H R9[0x0],R11");
        emit(String.format("MOV R11,0x%x", number("output_interpolation_steps", 5, 1, 8)));
        emit("MOV R9,0x6084");
        emit("ST.H R9[0x0],R11");
        emit("MOV R9,0x6086");
        emit("ST.H R9[0x0],R8");
        emit("MOV R9,0x6088");
        emit("ST.W R9[0x0],R8");
        emit("MOV R9,0x608c");
        emit("ST.H R9[0x0],R8");
        // Finite DAC interpolation state and its live slot start together.
        emit("MOV R9,0x602c");
        emit("ST.W R9[0x0],R8");
        emit("MOV R9,0x6036");
        emit("ST.H R9[0x0],R8");
        emit("ST.H R10[0x356],R8");
        // Pitch offsets are read before the first blend scan on some paths.
        emit("MOV R9,0x60a0");
        emit("ST.H R9[0x0],R8");
        emit("MOV R9,0x60e0");
        emit("ST.W R9[0x0],R8");
        // The blend can also precede the pressure pass: publish known-zero
        // samples for all 29 physical keys until that pass fills the cache.
        emit("MOV R9,0x6100");
        emit("MOV R12,0x1c");
        padTo(0x8001ac40L);
        emit("ST.H R9[0x0],R8");
        emit("SUB R9,-0x2");
        emit("SUB R12,0x1");
        emit("BR{ge} 0x8001ac40");
        // The scratch inside the factory's dead filter array, cleared off one
        // base to stay inside this cave's remaining bytes.  Zero is the safe
        // rest state for each: no arp rhythm or octave randomness, no pending
        // pulse, and a vibrato depth that clamps to its minimum.  This must
        // stay ahead of the latch section below, which loads the switch
        // position into R8 and so ends the run of zero stores.
        emit("MOV R9,0x3228");
        emit("ST.H R9[0x0],R8");        // tuning-apply guard
        emit("ST.H R9[0x2],R8");        // 0x322a arp knob 2 latch
        emit("ST.H R9[0x6],R8");        // 0x322e arp knob 3 latch
        emit("ST.B R9[0xa],R8");        // 0x3232 deferred-pulse flag
        emit("ST.H R9[0xc],R8");        // 0x3234 vibrato knob latch
        if (feature("arp_latch")) {
            emit("LD.UB R8,R10[0x340]");
            emit("MOV R9,0x608e");
            emit("ST.B R9[0x0],R8");
            emit("MOV R9,0x3233");
            emit("ST.B R9[0x0],R8");
        }
        // Commit the marker only after all dependent state is coherent.
        emit("MOV R9,0x602a");
        emit(String.format("MOV R11,0x%x", number("init_marker", 0xb007, 0x1000, 0xeffe)));
        emit("ST.H R9[0x0],R11");
        padTo(0x8001ac74L);
        emit("LDM SP++,R7,R8,R9,R10,R11,R12,PC");
        padTo(0x8001ac7cL);
        word(0x00003560L); // global state base
        finish("first_use_initializer", 0x8001ac80L);

        // One shared pool word keeps every consumer on the same bootstrap.
        begin(0x8001ac80L);
        word(0x8001ab60L);
        finish("initializer_pool", 0x8001ac84L);

        // Scale the effective one-knob vibrato control from 50% at zero
        // pressure to its original value at full pressure. The 0x1000
        // rounding bias also makes pressure 4095 reproduce K exactly.
        begin(0x8001ac84L);
        emit("LD.UH R8,R10[0x356]");
        emit("SUB R8,-0x1000");
        emit("MUL R11,R11,R8");
        emit("SUB R11,-0x1000");
        emit("LSR R11,0xd");
        emit("MOV PC,LR");
        finish("pressure_vibrato_scale", 0x8001aca0L);

        begin(0x8001aca0L);
        word(0x8001ac84L);
        finish("pressure_vibrato_pool", 0x8001aca4L);

        // The factory's long-hold switch combination also toggles polyphonic
        // MIDI, independently of the edit-mode setting. Preserve its debounce
        // completion flag but skip the toggle, MIDI flush and status flash so
        // the saved edit-mode value has a single owner.
        begin(0x8000456cL);
        emit("LDDPC R9,0x800045cc");
        emit("MOV R8,0x1");
        emit("ST.B R9[0x38],R8");
        emit("RJMP 0x800045c6");
        finish("poly_arp_independence", 0x8000458cL);

        // One-time migration for settings records written by older firmware.
        // Byte zero of the persisted payload is written but never restored by
        // the factory loader, so it can safely identify the new ownership
        // model without consuming RAM or resetting any other saved setting.
        // Load first, then migrate only poly MIDI and save the whole record.
        begin(0x8001aca4L);
        emit("STM --SP,R7,R8,R9,R10,R11,LR");
        emit("MOV R7,SP");
        emit("MCALL PC[0x8001acf0]");
        // Hold the loader's return across the migration: on the migrating
        // boot the saver runs last, and returning *its* R12 would hand the
        // caller a different value than an ordinary boot does.
        emit("ST.W --SP,R12");
        emit("LDDPC R10,0x8001acf4");
        emit("LDDPC R8,0x8001acf8");
        emit("LD.W R8,R8[0x0]");
        emit("LD.UB R9,R8[0x2]");
        emit("MOV R11,0xa5");
        emit("CP.W R9,R11");
        emit("BR{eq} 0x8001ace0");
        emit("MOV R9,0x0");
        emit("ST.B R10[0x84],R9");
        emit("MCALL PC[0x8001acfc]");
        padTo(0x8001ace0L);
        emit("LD.W R12,SP++");
        emit("LDM SP++,R7,R8,R9,R10,R11,PC");
        padTo(0x8001acf0L);
        word(0x8000a264L); // factory persistent-settings loader
        word(0x00003560L); // global state base
        word(0x00000968L); // pointer to the persisted settings record
        word(0x80009fb8L); // factory persistent-settings saver
        finish("poly_settings_migration", 0x8001ad00L);

        // Note-off pointer pools -> latch-gated wrapper.
        // Global vibrato on knob 4 (Micro_Easel one-knob law: depth and rate
        // rise together; +-33 cents and 1..6 Hz at full; deadzone = off).
        // Pressure scales the effective knob from one-half to full value.
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
        emit("MCALL PC[0x8001aca0]");
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
        //   (a) run the shared first-use bootstrap before reading custom RAM;
        //   (b) latch-exit watch: on state+0x340 leaving 1 (prev at RAM
        //       0x3233) clear the held count and all 29 held flags;
        begin(0x8001a480L);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("MCALL PC[0x8001ac80]");
        emit("LDDPC R10,0x8001a534");
        padTo(0x8001a4dcL);
        if (feature("arp_latch")) {
            // Leaving the latch switch position releases every latched key.
            emit("LD.UB R8,R10[0x340]");
            // Mirror the latch position where the blend can read it cheaply.
            emit("MOV R9,0x608e");
            emit("ST.B R9[0x0],R8");
            emit("MOV R11,0x3233");
            emit("LD.UB R9,R11[0x0]");
            emit("ST.B R11[0x0],R8");
            emit("CP.W R9,0x1");
            emit("BR{ne} 0x8001a510");
            emit("CP.W R8,0x1");
            emit("BR{eq} 0x8001a510");
            emit("MOV R9,0x0");
            emit("ST.B R10[0x21a],R9");
            // 0..28, the real extent of the array.  Clearing 32 zeroed the
            // same three bytes of adjacent state the selector was misreading.
            emit("MOV R9,0x1c");
            padTo(0x8001a500L);
            emit("ADD R12,R10,R9 << 0x0");
            emit("MOV R8,0x0");
            emit("ST.B R12[0x21b],R8");
            emit("SUB R9,0x1");
            emit("BR{ge} 0x8001a500");
        }
        padTo(0x8001a510L);
        emit("LDM SP++,R7,PC");
        padTo(0x8001a534L);
        word(0x00003560L); // global state base
        finish("scan_housekeeping", 0x8001a53cL);

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
        if (feature("pressure_blend")) {
            word(0x8001a8f0L); // blend-offset shim, chains to the remap
        } else {
            word(0x80019980L);
        }
        finish("pitch_hook_pool", 0x80003370L);

        // Scan period, in milliseconds.  The main loop registers a periodic
        // task here whose callback posts event 2 — the key/pressure/pitch
        // scan.  This single immediate is the instrument's whole update rate:
        // pressure and pitch reach the DAC once per scan, and the glide
        // engine, the vibrato phase and the pressure attack ramp all advance
        // once per scan too, so their timings scale with it.
        fixedPatch("scan_period", 0x80007c0cL, 2,
            String.format("MOV R10,0x%x", number("scan_period_ms", 5, 1, 20)));

        // Both cold-start defaults must agree. The persistent-settings loader
        // still runs afterward and restores any value explicitly saved from
        // edit mode; these sites only govern a new/invalid record and reset.
        fixedPatch("poly_powerup_default_off", 0x800071d6L, 2, "MOV R8,0x0");
        fixedPatch("poly_factory_reset_default_off", 0x8000a444L, 2, "MOV R8,0x0");
        fixedPatch("poly_persistence_marker", 0x80009fc2L, 4, "MOV R8,0xa5");
        wordPatch("poly_settings_loader_pool", 0x80007da8L, 0x8001aca4L,
            "settings loader -> one-time poly-MIDI migration wrapper");

        // Octave-switch reader: redirect the second switch's stores to shadow
        // RAM so flipping it changes only the pressure A/B (debug builds).
        fixedPatch("octsw_redirect_1", 0x800039ccL, 4, "ST.B R9[0x2ae7],R8");
        fixedPatch("octsw_redirect_2", 0x800039d4L, 4, "ST.B R9[0x2ae6],R8");
        fixedPatch("octsw_redirect_3", 0x800039dcL, 4, "ST.W R8[0x2ae8],R9");
        fixedPatch("octsw_redirect_4", 0x800039f2L, 4, "ST.B R9[0x2ae6],R8");
        fixedPatch("octsw_redirect_5", 0x800039faL, 4, "ST.B R9[0x2ae7],R8");
        fixedPatch("octsw_redirect_6", 0x80003a02L, 4, "ST.W R8[0x2ae8],R9");
        fixedPatch("octsw_redirect_7", 0x80003a0cL, 4, "ST.B R9[0x2ae6],R8");
        fixedPatch("octsw_redirect_8", 0x80003a14L, 4, "ST.B R9[0x2ae7],R8");
        fixedPatch("octsw_redirect_9", 0x80003a1cL, 4, "ST.W R8[0x2ae8],R9");

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
