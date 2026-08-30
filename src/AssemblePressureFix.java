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

    // The internal clock's beat can ride the same 1 kHz flush the external
    // one does, but its settle is not optional: the output pole is tau
    // 0.9 ms, so a gate raised with the pitch store sits 132 cents short on
    // an octave jump.  So the flush stages the pitch, waits the settle out in
    // MILLISECONDS, and only then raises the gate - the same wait the scan
    // gave it, minus the scan grid the wait used to be quantised to.  With
    // the settle configured to zero there is nothing to hold and the old
    // fire-at-the-store path is already what was asked for.
    private boolean twoPhaseBeat() {
        return block("clock_fast_trigger") && block("clock_output");
    }

    // Which claim a settle asks for: none to wait out means fire on the next
    // flush (1); anything else means stage the pitch now and hold the gate
    // for the wait (2).  The wait itself is read back off the scan countdown
    // clock_settle has already written, so there is one source for it.
    private int claimFor(int settleScans) {
        return settleScans == 0 ? 1 : 2;
    }

    // The same wait as the scan grid gave it, in the milliseconds the timer
    // actually counts.  Both terms are build constants, so this is settled
    // here and nothing has to multiply at 1 kHz.
    private int settleMsFor(int settleScans) {
        return settleScans * number("scan_period_ms", 5, 1, 20);
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
        println(String.format("EXTENT %08x %08x %s", address, address + encoded.length, name));
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
        println(String.format("EXTENT %08x %08x %s", address, address + 4, name));
        if (!block(name)) {
            println("SKIP " + name + " (disabled by build config)");
            return;
        }
        println(String.format("PATCH %08x %08x ; %s: %s", address, value, name, comment));
    }

    private void fixedPatch(String name, long address, int length, String instruction) throws Exception {
        println(String.format("EXTENT %08x %08x %s", address, address + length, name));
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

        // Whichever key selector this build installed.  The sequencer calls
        // the same one when it is not playing, so the two can never disagree
        // about which selector is actually in the image.
        long arpSelector = number("knob2_patterns", 0, 0, 1) == 1 ? 0x8001b050L
                         : number("knob1_orders", 0, 0, 1) == 1 ? 0x8001aec0L
                         : 0x8001a0a0L;

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
        //
        // The level is taken from where the knob is, not from a value anyone
        // set: mode 0 is "no pads held", so this writes on an ordinary sweep.
        // Removing it once made the response linear for everyone, which is
        // only what an instrument sitting at level 0 already had.
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
        // level = adc * (max + 1) >> 10, so the knob spans 0..max with the
        // configured default at twelve o'clock.  ADC >> 5 gave 0..31, which
        // put every useful setting in the first eighth of the travel.
        emit(String.format("MOV R11,0x%x",
             number("curve_knob_steps", 0x20, 0x2, 0x20)));
        emit("MUL R9,R9,R11");
        emit("LSR R9,0xa");
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
        if (block("seq_record")) {
            // BEFORE the latch toggle, not after.  In the latch position a
            // press of an already-sounding pitch means "release it" and
            // returns -1, and the wrapper stops there - so a repeated note
            // never reached the recorder and simply went missing from the
            // sequence.  What is recorded is the physical press, which is
            // what was played.  R12 is the key here (the first-use cave
            // saves and restores it) and this cave leaves it alone.  The pool
            // below starts one word earlier to make room for its entry;
            // extending the block instead would run into its neighbour.
            emit("MCALL PC[0x80018d3c]");
        }
        if (feature("arp_latch")) {
            // A press of an already-latched key returns -1 and the note-on is
            // skipped, which is what makes the keys behave as toggles.
            emit("MCALL PC[0x80018d38]");
            emit("CP.W R12,-0x1");
            emit("BR{eq} 0x80018d28");
        }
        emit("ST.W --SP,R12");
        emit("MCALL PC[0x80018d2c]");
        emit("LDDPC R9,0x80018d30");
        emit("MOV R8,0x0");
        emit("ST.H R9[0x0],R8");
        emit("LD.W R12,SP++");
        emit("MCALL PC[0x80018d34]");
        padTo(0x80018d28L);
        emit("LDM SP++,R7,PC");
        word(0x80005a04L); // original note-on initialization
        word(0x00006080L); // raw-filter sample count
        word(0x8001a020L); // press-order list append
        word(0x8001dde0L); // latch_owner -> the pitch-aware latch toggle
        word(0x8001b9d0L); // the sequencer's recorder
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
            // k = top - adc * span / 1024, with 256 as unity and the build
            // sizing `span` so the scaled ceiling can never reach the 1023
            // validity limit.  Subtracted, not added: the owner plays this
            // knob the other way round, so clockwise lowers the multiplier
            // and the 1.00x default sits mirrored at 4 of 10.  A fixed
            // 0.5x..1.5x range would pin the ceiling partway up the knob
            // while the floor kept rising, narrowing the window instead of
            // moving it.
            int trimSpan = number("trim_scale_span", 0x100, 0x10, 0x100);
            int trimBase = number("trim_scale_base", 0x80, 0x40, 0x100);
            // The top of the range: what the bottom-of-travel position now
            // maps to.  Derived, so base and span stay the two settings.
            int trimTop = trimBase + ((0x3ff * trimSpan) >> 10);
            emit("LD.UH R8,R10[0x30a]");
            emit(String.format("MOV R9,0x%x", trimSpan));
            emit("MUL R8,R8,R9");
            emit("LSR R8,0xa");
            emit(String.format("MOV R9,0x%x", trimTop));
            emit("SUB R9,R8");
            emit("MOV R8,R9");
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
        // Signed, not BR{hi}.  These two are read back from settings and are
        // only ever written in order, but nothing in the flash says so: stored
        // reversed - a floor of 900 against a ceiling of 500 - the subtraction
        // wraps to 0xfffffe70, which is comfortably "higher" than 31 unsigned,
        // and the pair is taken as a valid span.  What the instrument does
        // then is switch between nothing and everything within a few counts.
        // Read as signed the difference is -400 and the defaults load.
        emit("BR{gt} 0x80019670");
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

        if (!feature("error_diffusion")) {
            emit("CP.W R7,0x0");
            emit("BR{eq} 0x800196f4");
        }
        // With diffusion the level-0 shortcut is dropped: at level 0 the blend
        // weight is zero, so the same path produces exactly n, and both paths
        // must reach the quantiser with the extra fractional bits in place.
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
        if (feature("error_diffusion")) {
            // Carry the blend four bits further before it is rounded off.
            // ((n-cv)*k*16 + 128) >> 8 is ((n-cv)*k + 8) >> 4, so the wider
            // result costs one shift.  This is where the resolution was going:
            // the >>8 alone collapsed 2409 distinct levels to 1933.
            emit("SUB R9,-0x8");
            emit("ASR R9,0x4");
            emit("LSL R8,0x4");
        } else {
            emit("SUB R9,-0x80");
            emit("ASR R9,0x8");
        }
        emit("SUB R8,R9");
        padTo(0x800196f4L);
        emit("MOV R9,0xfff");
        emit("MUL R8,R9,R8");
        if (feature("error_diffusion")) {
            // First-order error diffusion.  What the division throws away is
            // carried into the next scan instead of being discarded, so the
            // output's time average tracks the true value to far better than
            // one code.  DIVU leaves the remainder in the register above the
            // quotient, so the error costs nothing to obtain.
            emit("MOV R10,0x6094");
            emit("LD.W R11,R10[0x0]");
            emit("ADD R8,R11");
            emit(String.format("MOV R9,0x%x", SPAN << 4));
            emit("DIVU R8,R8,R9");
            emit("ST.W R10[0x0],R9");
        } else {
            emit(String.format("SUB R8,-0x%x", SPAN / 2));
            emit(String.format("MOV R9,0x%x", SPAN));
            emit("DIVU R8,R8,R9");
        }
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
        if (!feature("clock_latency")) {
            // The clock-latency build has to report while a clock is running
            // and a key is held, which is not edit mode.  USB MIDI is still
            // required; only the edit-mode gate is lifted, and only for that
            // diagnostic.
            emit("LD.UB R8,R10[0x39]");      // edit-mode flag
            emit("CP.W R8,0x1");
            emit("BR{ne} 0x80019910");
        }
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
        emit("BR{gt} 0x80019834");   // signed, for the reason above
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
        } else if (feature("clock_latency")) {

            // Diagnostic: the accepted external edge to gate-raise delay, as
            // the firmware itself sees it.  CC 114/115 is the running MAX and
            // CC 116/117 the running MEAN, both in cycles/32 -- the same unit
            // the scan profiler uses, so ms = value * 32 / 60e6.  The mean is
            // the one to trust; compare it against the scope's 1.55 ms.
            emit("MOV R10,0x6032");
            emit("LD.UH R8,R10[0x0]");      // max
            emit("ST.H R7[-0x10],R8");
            emit("LD.UH R8,R10[0x2]");      // mean
            emit("ST.H R7[-0x12],R8");
        } else if (feature("latch_probe")) {

            // Diagnostic: what the latch toggle saw on its last press.
            // CC 114/115 is the transpose AS OF that press (the snapshot at
            // RAM 0x609A - power-up noise until the first press), CC 116/117
            // the live transpose now (RAM 0x60A0).  The old text promised a
            // pressed-pitch cell at 0x609C that nothing ever wrote.
            // Press the same key repeatedly with the arp running:
            // if the transpose moves while the key does not, the shared term
            // is drifting; if it holds and the press still fails to match, a
            // stamp is wrong instead.
            emit("MOV R10,0x609a");
            emit("LD.UH R8,R10[0x0]");      // as of the last latch press
            emit("ST.H R7[-0x10],R8");
            emit("MOV R10,0x60a0");
            emit("LD.UH R8,R10[0x0]");      // live now
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

        // Pitch-CV calibration remap, stage 1.  The final
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
        finish("pitch_remap_calibration", 0x80019a04L);

        // Per-semitone pitch curve: index 0 = the 208p's 0 V pitch (A);
        // index 3 = bottom key at the leftmost octave position (C).  Values
        // are DAC units: the per-octave calibration interpolated per
        // semitone, minus the measured tracking error at each semitone
        // (218e-key-calibration_done.csv), held constant beyond semi 64.
        begin(0x80019bc0L);
        emitTable("pitch_remap");
        finish("tracking_correction_table", 0x80019c5eL);

        // Knob 2's pattern bank: one 32-bit mask per pattern as two halfwords,
        // low first, then one length each.  In the gap the relocated sine
        // left behind.
        begin(0x80019f20L);
        emitTable("arp_pattern_bank");
        padTo(0x80019f78L);
        emitTable("arp_pattern_len");
        finish("arp_pattern_tables", 0x80019fa4L);

        // Pressure-based portamento:
        // each scan the pitch target becomes
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
        // The base pitch the offset is measured from arrives in R10 from
        // the chaining cave, which reads the published base and - in latch
        // builds - translates the last arp key through the ownership map,
        // so the anchor is the note that key currently PLAYS.  Identifying
        // it here off state+0x350 anchored a recording audition on the old
        // note still latched in the key's own slot number.
        emit("MOV R11,R10");
        // Portamento knob = pressure-needed-to-bend: T = 1023 - knob.
        // At knob zero T exceeds any possible touch, so only the sounding
        // key contributes and the blend is exactly zero (factory behavior).
        // The anchor key is never thresholded, so engagement is smooth.
        // Read the KNOB MIRROR (state+0x306), not the combined rate index at
        // +0x3a2 — the index carries a pressure-derived addend, so with the
        // full 218r curve the threshold would move with pressure and the
        // blend would engage and disengage erratically under the fingers.
        emit("LD.SH R5,R9[0x306]");
        // While the sequencer PLAYS (or previews), the keys no longer choose
        // the notes and the knob means time, as documented - so the blend
        // has nothing to steer and its target parks at zero.  The apply shim
        // slews the applied offset out, so a blend held down when playback
        // starts glides away instead of stepping.
        emit("LD.UB R8,R9[0x2bf8]");    // 0x6158, the sequencer mode
        emit("CP.W R8,0x2");
        emit("BR{eq} 0x80019d18");
        // Hard gate: below the knob's deadzone the blend loop never runs at
        // all — multi-finger common-mode sensor inflation can push deltas
        // past any threshold, so "off" must not depend on pressure at all.
        emit("CP.W R5,0x30");
        emit("BR{lt} 0x80019d18");
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
        padTo(0x80019c98L);
        emit("ADD R8,R9,R2 << 0x0");
        emit("LD.UB R10,R8[0x21b]");
        emit("CP.W R10,0x1");
        emit("BR{ne} 0x80019d06");
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
            emit("BR{ne} 0x80019cc8");
            emit("MOV R8,0x60a2");
            emit("LD.SH R8,R8[R2 << 0x1]");
            emit("ADD R10,R8");
        }
        padTo(0x80019cc8L);
        // The base must sit in the same domain as the contributors: measuring
        // a stamped contributor against an unstamped base published the stamp
        // itself as an offset, which the glide target had already applied.
        emit("CP.W R12,0x0");
        emit("BR{eq} 0x80019cd0");
        emit("MOV R3,R10");
        padTo(0x80019cd0L);
        // The weight must be the pressure of the FINGER whose note this slot
        // holds.  Slot and key part company the moment a repeat press
        // allocates a free slot, so in latch builds the weight comes from
        // the per-scan slot map the re-base shim builds from the ownership
        // records — indexing the raw by-key cache by slot handed the old
        // note the new finger's pressure.
        emit(feature("arp_latch") ? "MOV R8,0x6540" : "MOV R8,0x6100");
        emit("LD.UH R8,R8[R2 << 0x1]");
        emit("CP.W R12,0x0");
        emit("BR{ne} 0x80019ce0");
        emit("SUB R8,R8,R5 << 0x0");
        padTo(0x80019ce0L);
        emit("CP.W R8,0x0");
        emit("BR{le} 0x80019d06");
        emit("LSR R8,0x4");
        emit("CP.W R8,0x3f");
        emit("BR{ls} 0x80019cf2");
        emit("MOV R8,0x3f");
        padTo(0x80019cf2L);
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
        padTo(0x80019d06L);
        emit("SUB R2,0x1");
        emit("BR{ge} 0x80019c98");
        emit("CP.W R0,0x0");
        emit("BR{eq} 0x80019d18");
        emit("DIVU R0,R1,R0");
        // Publish X - base as an OFFSET (RAM 0x60e0) instead of folding it
        // into the glide target: the pitch shim adds it after the glide
        // engine, so pressure steers the pitch immediately while the same
        // knob keeps its classic note-to-note portamento.  Both paths store,
        // so a released chord zeroes the offset within one scan.
        emit("SUB R0,R0,R3 << 0x0");
        emit("RJMP 0x80019d1a");
        padTo(0x80019d18L);
        emit("MOV R0,0x0");
        padTo(0x80019d1aL);
        emit("MOV R8,0x60e0");
        emit("ST.H R8[0x0],R0");
        emit("LDDPC R8,0x80019d34");
        emit("ST.H R8[0x352],R4");
        emit("LDM SP++,R0,R1,R2,R3,R4,R5,R7,PC");
        padTo(0x80019d34L);
        word(0x00003560L); // global state base
        finish("pressure_blend", 0x80019d38L);

        // Arpeggiator randomness on the preset-voltage knobs (outside edit):
        //   knob 1 (0x30a) -> 0x60f2 latch, read by the replacement key
        //     selector below.  Not state+0x38c: that is the factory
        //     weighted-random selector's own bias parameter, and the factory
        //     selector still runs when knob 1 is left factory - an earlier
        //     design borrowed the cell and quietly overwrote it;
        //   knob 2 (0x30c) -> random gate shortening: the countdown's gate-off
        //     compare (was == 3) becomes == R, R redrawn per step in
        //     [3, 3 + (interval-4)*knob/1024] via the factory PRNG (0x80013e04);
        //   knob 3 (0x30e) -> random +-octave on each arp note with
        //     probability knob/1024 (bottom deadzone = off, factory-exact).
        // Knob values latch only outside edit mode so edit-mode knob use
        // never disturbs the arp.  RAM: 0x60e6 knob2 latch, 0x60e8 last
        // countdown, 0x60ea knob3 latch, 0x60ec gate threshold, 0x60f2
        // knob1 latch.
        // Arp controls on the preset knobs (outside edit; latches edit-gated):
        //   knob 1 (0x30a>>3 -> 0x60f2 latch): press-order vs random key
        //     selection, applied by the replacement selector below;
        //   knob 2 (0x30c -> 0x60e6 latch): rhythm randomness — the per-step
        //     countdown reload becomes T*((1024-r) + r*E)/1024 with E an
        //     exponential draw (mean ~1, CLZ-geometric approximation, clamp
        //     4x), a random-pulser spacing law; knob low = even pulses;
        //   knob 3 (0x30e -> 0x60ea latch): random +-octave per arp note.
        // Gate-off timing itself is factory (compare == 3 restored).
        // Knob 2's latch has two other readers, one at a time: swing, which
        // takes this same pool word, and the pattern gate, which sits at the
        // note selector instead and turns the randomiser off.
        begin(0x80019d38L);
        word(0x80019d44L); // gate/housekeeping entry (hook at 0x21a0)
        word(block("seq_pitch") ? 0x8001ba30L : 0x80019da8L);
        word(number("knob2_swing", 0, 0, 1) == 1 ? 0x8001b100L : 0x80019df8L);
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
        emit("BR{eq} 0x80019d94");
        // A knob does one thing at a time.  Holding a preset pad and turning
        // its knob sets that pad's voltage, and while it is doing that the
        // knob's OTHER job has to stand still - setting preset voltage 2 was
        // also winding the arp's rhythm randomness up with it.  Per knob, not
        // all of them, so holding pad 1 does not freeze knobs 2 and 3.  The
        // knob_pickup helper answers "hold or follow": it holds while the
        // editor owns the knob AND afterwards, until the knob has moved
        // again - releasing the pad used to snap the frozen value straight
        // to wherever the edit had left the knob standing.
        emit("MOV R11,0x0");
        emit("MCALL PC[0x8001ddd4]");
        emit("CP.W R9,0x0");
        emit("BR{ne} 0x80019d6a");
        emit("LD.SH R8,R10[0x30a]");
        emit("LSR R8,0x3");
        emit("MOV R11,0x60f2");
        emit("ST.B R11[0x0],R8");
        padTo(0x80019d6aL);
        emit("MOV R11,0x1");
        emit("MCALL PC[0x8001ddd4]");
        emit("CP.W R9,0x0");
        emit("BR{ne} 0x80019d7e");
        emit("LD.SH R8,R10[0x30c]");
        emit("MOV R11,0x60e6");
        emit("ST.H R11[0x0],R8");
        padTo(0x80019d7eL);
        emit("MOV R11,0x2");
        emit("MCALL PC[0x8001ddd4]");
        emit("CP.W R9,0x0");
        emit("BR{ne} 0x80019d94");
        emit("LD.SH R8,R10[0x30e]");
        emit("MOV R11,0x60ea");
        emit("ST.H R11[0x0],R8");
        padTo(0x80019d94L);
        if (block("clock_attack_guard")) {
            emit("MCALL PC[0x8001cb1c]");
        } else if (block("seq_gate")) {
            // LR is still on the stack here, so a call is safe; two lines
            // later it would not be.  R8 comes back as the threshold.
            emit("MCALL PC[0x8001b54c]");
        }
        padTo(0x80019d98L);
        emit("LDM SP++,R7,R9,R10,R11,R12,LR");
        if (!block("seq_gate") && !block("clock_attack_guard")) {
            emit("MOV R8,0x3");
        }
        emit("CP.H R9,R8");
        emit("MOV PC,LR");
        padTo(0x80019da8L);
        emit("STM --SP,R0,R7,R9,R10,R11,R12,LR");
        emit("MOV R7,SP");
        emit("MOV R11,0x60ea");
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
        emit(String.format("SUB R8,-0x%x", number("octave_units", 484, 1, 2000)));
        emit("RJMP 0x80019df0");
        padTo(0x80019de0L);
        emit(String.format("SUB R8,0x%x", number("octave_units", 484, 1, 2000)));
        emit("CP.W R8,0x1");
        emit("BR{ge} 0x80019df0");
        emit(String.format("SUB R8,-0x%x", 2 * number("octave_units", 484, 1, 2000)));
        padTo(0x80019df0L);
        emit("LDM SP++,R0,R7,R9,R10,R11,R12,PC");
        padTo(0x80019df8L);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("MOV R9,R12");
        emit("MOV R10,0x60e6");
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
        emit("MOV R2,0x60f2");
        emit("LD.UB R2,R2[0x0]");
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
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        if (feature("pressure_blend")) {
            // No time-based glide: the pressure-based blend is the only
            // portamento.  Notes snap; the knob means pressure-needed-to-bend.
            emit("MOV R8,0x0");
        } else {
            // Blend-off builds keep classic portamento with the zero-snap.
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
        }
        padTo(0x8001a254L);
        if (block("seq_gate")) {
            // The store goes out of line so a tie can override the rate on
            // its way past.  There is no room for the test here - the block
            // ends where pulse_defer_set begins.
            emit("MCALL PC[0x8001a25c]");
        } else {
            emit("MOV R9,0x2eee");
            emit("ST.H R9[0x0],R8");
        }
        emit("LDM SP++,R7,PC");
        if (block("seq_gate")) {
            padTo(0x8001a25cL);
            word(0x8001b610L); // store_glide_rate, with the tie's override
        }
        padTo(0x8001a260L);
        word(0x80015150L); // the factory glide-rate table
        finish("glide_rate_clamp", 0x8001a264L);

        // Pulse defer: the four factory pool words that pointed at the
        // pulse-high routine (0x800077f8) are repointed to the setter below,
        // which just marks the pulse pending; the pitch-store hook fires the
        // real routine after the pitch lands. Word first: the real routine's
        // address, read by the hook's MCALL PC[0x8001a268].
        //
        // The mark is a countdown of scans, not a flag, so the trigger can be
        // held past the scan that writes the pitch — see gate_settle_scans at
        // the hook.  A pulse arriving while one is already pending does NOT
        // restart the countdown: the gate then always rises within a bounded
        // number of scans of the FIRST request, instead of being pushed back
        // indefinitely by a fast arp whose steps land inside the window.
        // A LEAF, and it must stay one: it returns with MOV PC,LR, so an
        // MCALL inside it - which writes LR - turns that return into a jump
        // to itself, and the first trigger request hangs the instrument with
        // its USB still enumerating.  That shipped once.  The clock-aware
        // helper now saves LR before its gate-off call; the
        // four pulse pools point THERE in a divider build.
        begin(0x8001a268L);
        word(0x800077f8L); // real pulse-high routine
        emit("MOV R8,0x60ee");
        emit("LD.UB R9,R8[0x0]");
        emit("CP.W R9,0x0");
        emit("BR{ne} 0x8001a27a");
        emit(String.format("MOV R9,0x%x",
            number("gate_settle_scans", 1, 0, 3) + 1));
        emit("ST.B R8[0x0],R9");
        padTo(0x8001a27aL);
        emit("MOV PC,LR");
        finish("pulse_defer_set", 0x8001a280L);

        // Latch mode (arp switch position 1). Three pieces:
        //   latch_noteoff  — physical releases are ignored while latched;
        //   latch_check    — a press of an already-held key unlatches it
        //                    (called from the note-on wrapper, returns -1);
        //   applier_plus   — runs the tuning applier then watches state+0x340
        //                    for the latch->off/regular edge (prev byte at
        //                    RAM 0x60ef) and clears all held flags + count.
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
        // Only reached when a tuning has actually been supplied.  With every
        // slot on the factory temperament the applier would copy that table
        // over itself each scan, but it also asserts the rem-en and trn LEDs
        // and permanently zeroes the old transpose-mode byte — so leaving it
        // out is what hands those back to the factory.
        if (feature("alternate_tunings")) {
            emit("MCALL PC[0x8001a340]");      // tuning applier
        }
        // Preset pickup must own this scan's knob before vibrato reads it.
        // Otherwise the first movement changes vibrato, then the editor
        // freezes that already-changed value for the rest of the hold.
        emit("MCALL PC[0x8001a348]");          // per-scan housekeeping
        if (feature("knob4_vibrato")) {
            emit("MCALL PC[0x8001a344]");      // vibrato engine
        }
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
        word(block("clock_scan") ? 0x8001b980L : 0x8001a540L);
        finish("profiler_pool", 0x80007dc4L);

        // Pressure output interpolation.  The scan writes a target at RAM
        // 0x6036 and this 1 kHz handler divides each new gap over N remaining
        // ticks.  Recomputing gap/N distributes integer remainders, and the
        // last tick snaps exactly to target: five smaller DAC treads, with no
        // exponential tail and no change to the already-full scan schedule.
        // Shared first-use initialisation makes a separate byte marker here
        // unnecessary and guarantees the target, snapshot, counter and DAC
        // slot become valid atomically before any of them is read.
        // The cave is the event-17 wrapper, and TWO things want it: the
        // pressure interpolation below, and the clock's trigger rise.  It is
        // built whenever either is on, and each half is conditional, because
        // a build can have one without the other - `pressure_fix = false`
        // sets smoothing to zero while clock division stays on, and hooking
        // this only for smoothing left the trigger back on the 5 ms scan
        // with the fast-trigger cave emitted but unreachable.
        begin(0x8001a600L);
        emit("MCALL PC[0x8001ac80]");
        if (block("dac_interpolate")) {
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
        } else {
            // No smoothing: the scan writes the pressure DAC slot itself and
            // the target, snapshot and counter above are never maintained.
            // Nothing here may read or write them.
            emit("RJMP 0x8001a684");
        }
        padTo(0x8001a684L);
        // The trigger's rise rides out on this same flush, one slot along.
        // Staged here, before the hand-back, so pitch and gate reach the DAC
        // in the same millisecond - which is the whole point of moving it.
        if (block("clock_fast_trigger")) {
            emit("MCALL PC[0x8001a698]");
        }
        padTo(0x8001a688L);
        emit("LDDPC R12,0x8001a694");
        emit("MOV PC,R12");             // on into the factory flush handler
        padTo(0x8001a690L);
        word(0x00003560L); // global state base
        word(0x80004f66L); // factory event-17 case
        if (block("clock_fast_trigger")) {
            word(0x8001c100L); // the clock's fast trigger
        }
        finish("dac_interpolator", 0x8001a69cL);

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
            // The single key's value comes out of the cache the MCALL above
            // just filled - floor-subtracted and colour-corrected, the same
            // number the multi-key path averages.  The old code colour-scaled
            // R12 instead, as if it still held the caller's raw pressure;
            // with common_mode on, the combiner had already left the LAST
            // key's proximity result there.  Unreachable through the seven
            // options (multi_key is always on), but the corpus builds carry
            // this branch, and one correction lives in one place.
            emit("LDDPC R10,0x8001a7f0");
            emit("LD.UB R8,R10[0x256]");
            emit("CP.W R8,0x1d");
            emit("BR{ge} 0x8001a7c0");
            emit("MOV R12,0x6100");
            emit("LD.UH R12,R12[R8 << 0x1]");
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
        // Empty slot.  It used to hold 0x8001aa90 labelled "cache fill",
        // but nothing reads this word and that address is the middle of the
        // cache loop, not an entry - an MCALL through it would have run the
        // loop tail frameless and popped the caller's stack into PC.  Zero,
        // so any future use faults on the first fetch instead.
        word(0x00000000L);
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
        emit("MOV R8,0x60a0");
        emit("LD.SH R9,R8[-0x27f0]");   // state+0x350, read off the publish base
        emit("RSUB R9,R12");
        emit("ST.H R8[0x0],R9");
        if (feature("arp_latch")) {
            emit("MOV R10,0x38a0");
            emit("LD.UB R11,R10[0x0]");
            emit("CP.W R11,0x1");
            emit("BR{ne} 0x8001a8e4");
            // While the sequencer PLAYS, the sounding note is a recorded
            // step whose pitch is already absolute, and the last arp key is
            // whatever key that step happened to name.  Re-basing off that
            // key's live latch slot would transpose the step a second time,
            // so playback keeps the pitch it was handed.
            emit("LD.UB R11,R8[0xb8]");     // 0x60a0 + 0xb8: sequencer mode
            emit("CP.W R11,0x2");
            emit("BR{eq} 0x8001a8e4");
            emit("LD.UB R10,R10[0xd]");
            // 0x1d with BR{ge}, not 0x1c with BR{hi}: the key arrives
            // zero-extended from LD.UB, so the signed test is the same test,
            // and {ge} has a two-byte encoding where {hi} does not.  The two
            // bytes pay for the held check below.
            emit("CP.W R10,0x1d");
            emit("BR{ge} 0x8001a8e4");
            // A stamp only means anything for a slot that is sounding, so
            // gate on the held flag (state+0x21b) before reading it.  Without
            // this the shim re-based the transpose off the last arp key's
            // stamp cell even when that slot had never been latched, which
            // replaced the live transpose with a stale one.
            emit("MOV R11,0x377b");
            emit("LD.UB R11,R11[R10 << 0x0]");
            emit("CP.W R11,0x1");
            emit("BR{ne} 0x8001a8e4");
            emit("ADD R8,R8,R10 << 0x1");
            emit("LD.SH R8,R8[0x2]");
            // RSUB leaves transpose-now MINUS the stamp, so subtracting it
            // rebases R12 onto the stamp - two bytes shorter than the sum
            // the other way round, and those bytes pay for the mode check.
            emit("RSUB R8,R9");
            emit("SUB R12,R8");
        }
        padTo(0x8001a8e4L);
        emit("MCALL PC[0x8001a8ec]");
        emit("LDM SP++,R7,PC");
        padTo(0x8001a8ecL);
        word(0x8001ad28L); // the blend re-base shim, which chains to the cave
        finish("transpose_capture", 0x8001a8f0L);

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
        // With the knob-scaled slew the conditioner does the smoothing and
        // this shim must copy its output exactly: shift 0 makes the step the
        // whole gap, and the nudge can never trigger.
        emit(String.format("ASR R11,0x%x",
            number("blend_slew_taper", 1, 0, 1) == 1
                ? 0 : number("blend_slew_shift", 2, 0, 4)));
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
        if (feature("latch_probe")) {
            // Snapshot both halves of the comparison before anything acts on
            // them, so a failed match can be read back afterwards.
            emit("MOV R9,0x609a");
            emit("ST.H R9[0x0],R8");        // the transpose term, as seen here
        }
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
        // Match with a tolerance, not for bit-equality.  Both sides are built
        // from the same transpose at 0x60A0, but that term is not stable:
        // the latch probe measured it reading -485 on some presses and -484
        // on others, because the generated tuning tables round and adjacent
        // octaves land 484 or 485 units apart.  One unit is 2.48 cents, so an
        // exact compare missed, the allocator ran, and the press added a note
        // instead of releasing one.  Semitones are ~40 units apart (484/12),
        // so a tolerance this small cannot reach the neighbouring note.
        //
        // BR{lt} against tolerance+1 rather than BR{le} against the tolerance:
        // {lt} has a two-byte encoding here and {le} does not, and |x| is
        // never negative, so the two tests are the same test.
        emit("SUB R9,R11");
        emit("ABS R9");
        emit(String.format("CP.W R9,0x%x",
            number("latch_match_tolerance", 8, 0, 30) + 1));
        emit("BR{lt} 0x8001a9e8");
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
        emit("ST.B R9[0x4],R8");        // 0x6090 tuning slot 0, the declared default
        emit("ST.W R9[0x8],R8");        // 0x6094 output error accumulator
        emit("ST.H R9[0xc],R8");        // 0x6098 vibrato error accumulator
        // Finite DAC interpolation state and its live slot start together.
        emit("MOV R9,0x602c");
        emit("ST.W R9[0x0],R8");
        emit("MOV R9,0x6036");
        emit("ST.H R9[0x0],R8");
        emit("ST.H R10[0x356],R8");
        // The curve level byte is not touched here.  It sits in factory state
        // and survives a flash, and knob 4 owns it again - forcing it to 0
        // would drop the curve until the knob was next swept.
        // Pitch offsets are read before the first blend scan on some paths.
        emit("MOV R9,0x60a0");
        emit("ST.H R9[0x0],R8");
        emit("MOV R9,0x60e0");
        emit("ST.W R9[0x0],R8");
        // The 29 latch stamps that follow the live transpose.  A slot sounds
        // at table[k] plus its stamp, so an uninitialised stamp gives a slot
        // an arbitrary pitch — read by the latch toggle's match loop and by
        // the transpose shim before anything has written one.  Zero is the
        // rest state: the slot sounds at its own key's nominal pitch.
        emit("MOV R9,0x60a2");
        emit("MOV R12,0x1c");
        padTo(0x8001ac00L);
        emit("ST.H R9[0x0],R8");
        emit("SUB R9,-0x2");
        emit("SUB R12,0x1");
        emit("BR{ge} 0x8001ac00");
        // The factory's own held-key bookkeeping, cleared once per flash.
        //
        // Flashing does not power-cycle anything the way a cold start does,
        // and SRAM survives a DFU update, so a key that was registered as
        // held before the update is still held after it: the gate stays
        // asserted and the arpeggiator keeps playing a note nobody is
        // touching.  A reporter hit exactly that - "the gate output was also
        // always latched" - and the instrument's own reset button cleared it.
        //
        // Both pairs go: the note pair at state+0x21a/0x21b (count and 29
        // flags) and the touch-scan pair at state+0x238/0x239.  The second
        // matters most, because release_count_guard makes a stuck count
        // permanent by design - it refuses to decrement a count whose flag is
        // already clear, which is what stops the factory's underflow to 255,
        // and also what stops a stale count from ever walking back down.
        //
        // Zeroing both together keeps them consistent: a release arriving
        // afterwards finds its flag clear, takes the guard's early exit, and
        // leaves the count at zero.
        emit("MOV R9,0x1c");
        padTo(0x8001ac20L);
        emit("ADD R12,R10,R9 << 0x0");
        emit("ST.B R12[0x21b],R8");
        emit("ST.B R12[0x239],R8");
        emit("SUB R9,0x1");
        emit("BR{ge} 0x8001ac20");
        emit("ST.B R10[0x21a],R8");
        emit("ST.B R10[0x238],R8");

        // The blend can also precede the pressure pass: publish known-zero
        // samples for all 29 physical keys until that pass fills the cache.
        emit("MOV R9,0x6100");
        // 0xff, not 0x1c: the run reaches from the pressure cache all the way
        // over the preset block, the arp's own cells, the sequencer, the
        // clock divider, its FIFO and the borrowed strip mode - everything in
        // this gap.  SRAM survives a DFU, so anything left out here starts as
        // whatever the last image happened to leave: a sequencer that resumes
        // an old mode mid-flash, a divider that thinks it is still locked, a
        // strip mode waiting to be given back to a take that never happened,
        // or - the one that was already wrong at 0x25 - two of the four
        // preset following flags, which would have stopped the pad-4 chord
        // arming at all.
        // The last sixteen halfwords used to be conditional on persistence
        // or sequencing, but the knob pickup stamps at 0x62e8 belong to the
        // remapped knobs, which every build carries - and a stale stamp
        // from the previous image froze those knobs at power-up until each
        // was moved.  The full run is unconditional now.
        emit("MOV R12,0xff");
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
        emit("MOV R9,0x60e4");
        emit("ST.H R9[0x0],R8");        // tuning-apply guard
        emit("ST.H R9[0x2],R8");        // 0x60e6 arp knob 2 latch
        emit("ST.H R9[0x6],R8");        // 0x60ea arp knob 3 latch
        emit("ST.B R9[0xa],R8");        // 0x60ee deferred-pulse countdown
        emit("ST.H R9[0xc],R8");        // 0x60f0 vibrato knob latch
        emit("ST.B R9[0xe],R8");        // 0x60f2 arp knob 1 latch
        if (feature("arp_latch")) {
            emit("LD.UB R8,R10[0x340]");
            emit("MOV R9,0x608e");
            emit("ST.B R9[0x0],R8");
            // 0x60ef, as a displacement off the base already in R9: the two
            // bytes the second MOV cost went to the knob-1 latch init above.
            emit("ST.B R9[0x61],R8");
        }
        // The seed for the blend re-base history and the marker commit live in
        // a continuation cave: this cave is packed to the byte.  A plain
        // branch keeps the STM frame, and the continuation ends with the same
        // LDM this cave's early exit uses.
        emit("RJMP 0x8001ad00");
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

        // Continuation of first_use_initializer, reached by RJMP with the STM
        // frame intact; R10 still holds the state base and nothing here needs
        // R8.  The re-base history starts at -1, "nothing has sounded under
        // the blend yet": the first blend scan must record a base without
        // re-basing against it, because the applied offset is still zero.
        begin(0x8001ad00L);
        emit("MOV R9,0x60f4");
        emit("MOV R11,0x0");
        emit("ST.H R9[0x2],R11");       // 0x60f6 blend target filter
        emit("ST.H R9[0x4],R11");       // 0x60f8 blend hysteresis hold
        // The delete-pad flash countdown sits outside the big zero fill, and
        // it is read every scan - a stale count would blink pad 3 at power-up.
        emit("MOV R9,0x6502");
        emit("ST.B R9[0x0],R11");
        emit("MOV R9,0x60f4");
        emit("SUB R11,0x1");
        emit("ST.H R9[0x0],R11");
        // Commit the marker only after all dependent state is coherent.
        emit("MOV R9,0x602a");
        emit(String.format("MOV R11,0x%x", number("init_marker", 0xb007, 0x1000, 0xeffe)));
        emit("ST.H R9[0x0],R11");
        emit("LDM SP++,R7,R8,R9,R10,R11,R12,PC");
        finish("first_use_initializer_tail", 0x8001ad28L);

        // Blend re-base, between transpose_capture and the blend cave.  The
        // blend publishes X_port - base and the apply shim slews the applied
        // offset toward it, but the base itself snaps on note-on: at a
        // handover the output visits the new note for the tens of
        // milliseconds the slew needs to rebuild the offset, then walks back
        // to the old one - an audible stutter ahead of the glide.  So when
        // the sounding base moves while the blend is engaged, the step is
        // folded into the applied offset in the same scan:
        // new_base + (applied + old - new) is exactly the pitch that was
        // already sounding, and the slew proceeds from there, driven only by
        // the pressure handover.
        //
        // transpose_capture has already MCALLed the initializer this scan, so
        // 0x60f4 is seeded before the first read here.  R12 carries the pitch
        // into the blend cave and is not touched.
        begin(0x8001ad28L);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("LDDPC R9,0x8001ad70");
        emit("LD.SH R11,R9[0x350]");
        emit("MOV R8,0x60f4");
        emit("LD.SH R10,R8[0x0]");
        emit("ST.H R8[0x0],R11");
        emit("CP.W R10,0x0");
        emit("BR{lt} 0x8001ad58");
        emit("CP.W R10,R11");
        emit("BR{eq} 0x8001ad58");
        // The cave's own engagement gate, mirrored: with the knob in the
        // deadzone the blend is off and notes snap by design.
        emit("LD.SH R9,R9[0x306]");
        emit("CP.W R9,0x30");
        emit("BR{lt} 0x8001ad58");
        // The step itself is applied one cave along, where the sequencer
        // mode can veto it: during PLAY the base moves because the FACTORY
        // GLIDE is walking between steps, not because a note handed over,
        // and folding those steps into the offset bent every transition
        // backwards.  The base history above updates every scan regardless,
        // so returning to live playing measures from the current base.
        emit("SUB R10,R10,R11 << 0x0");
        emit("RJMP 0x8001ad5a");
        padTo(0x8001ad58L);
        emit("MOV R10,0x0");
        padTo(0x8001ad5aL);
        emit("MCALL PC[0x8001ad6c]");
        emit("LDM SP++,R7,PC");
        padTo(0x8001ad6cL);
        word(0x8001de20L); // the mode-aware re-base step, then the blend
        word(0x00003560L); // global state base
        finish("blend_rebase", 0x8001ad78L);

        // Blend target conditioner: an EMA filter and a backlash band between
        // the published offset target and the slew that chases it.
        //
        // The published target is recomputed each scan from raw sensor
        // deltas, and holding a bend steady leaves it jittering by a unit or
        // two - largely mains hum through the player's body, which capacitive
        // sensing receives by design.  The apply shim's anti-stall nudge
        // faithfully chases every flip, so a held bend chatters by ~a unit at
        // up to scan rate: audible crackle on the pitch line.
        //
        // The filter shaves the noise; the backlash refuses what remains.
        // Backlash, not a deadband: the target drags the held value through
        // an H-wide window, so a monotonic bend tracks continuously with no
        // stepping - only direction reversals and jitter pay H.  An exactly
        // zero target snaps everything to rest, so a released note still
        // lands dead on pitch; the blend publishes exact zero whenever it is
        // disengaged, which is precisely when cleanliness is owed.
        //
        // transpose_capture has already MCALLed the initializer this scan,
        // so both cells are seeded before the first read.  R12 carries the
        // pitch into the apply shim and is not touched.
        begin(0x8001ad78L);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("MOV R8,0x60e0");
        emit("LD.SH R9,R8[0x0]");
        emit("CP.W R9,0x0");
        emit("BR{ne} 0x8001ad94");
        emit("MOV R9,0x0");
        emit("ST.H R8[0x16],R9");
        emit("ST.H R8[0x18],R9");
        emit("RJMP 0x8001adcc");
        padTo(0x8001ad94L);
        emit("LD.SH R10,R8[0x16]");
        emit("SUB R11,R9,R10 << 0x0");
        if (number("blend_filter_shift", 2, 0, 4) > 0) {
            emit(String.format("SUB R11,-0x%x",
                1 << (number("blend_filter_shift", 2, 0, 4) - 1)));
        }
        emit(String.format("ASR R11,0x%x", number("blend_filter_shift", 2, 0, 4)));
        emit("ADD R10,R11");
        emit("ST.H R8[0x16],R10");
        emit("LD.SH R9,R8[0x18]");
        emit("SUB R11,R10,R9 << 0x0");
        emit(String.format("CP.W R11,0x%x", number("blend_hysteresis", 3, 0, 8)));
        emit("BR{le} 0x8001adbc");
        emit("MOV R9,R10");
        if (number("blend_hysteresis", 3, 0, 8) > 1) {
            emit(String.format("SUB R9,0x%x", number("blend_hysteresis", 3, 0, 8) - 1));
        }
        emit("RJMP 0x8001adc8");
        padTo(0x8001adbcL);
        if (number("blend_hysteresis", 3, 0, 8) > 0) {
            emit(String.format("CP.W R11,-0x%x", number("blend_hysteresis", 3, 0, 8)));
        } else {
            emit("CP.W R11,0x0");
        }
        emit("BR{ge} 0x8001adc8");
        emit("MOV R9,R10");
        if (number("blend_hysteresis", 3, 0, 8) > 1) {
            emit(String.format("SUB R9,-0x%x", number("blend_hysteresis", 3, 0, 8) - 1));
        }
        padTo(0x8001adc8L);
        emit("ST.H R8[0x18],R9");
        padTo(0x8001adccL);
        if (number("blend_slew_taper", 1, 0, 1) == 1) {
            // The slew, with the portamento knob choosing its rate: the low
            // quarter of the dial closes half the remaining gap per scan and
            // the top quarter a sixteenth, so the same handover takes ~60 ms
            // at the bottom of the travel and ~220 ms at the top.  Until now
            // the rate was one build-time constant, and the knob changed how
            // far a bend went but never how fast it moved - the floor a
            // player feels on a quick handover was fixed at every position.
            emit("LD.SH R10,R8[0x2]");
            emit("SUB R11,R9,R10 << 0x0");
            emit("MOV R7,R11");
            emit("LDDPC R9,0x8001ae14");
            emit("LD.SH R9,R9[0x306]");
            emit("CP.W R9,0x100");
            emit("BR{lt} 0x8001adf8");
            emit("CP.W R9,0x200");
            emit("BR{lt} 0x8001adf4");
            emit("CP.W R9,0x300");
            emit("BR{lt} 0x8001adf0");
            // Rates 0/1/2/2, topping out at ~45 ms: the bottom quarter is a
            // single-scan snap - the hysteresis, not the slew, is what keeps
            // a held bend frozen, so pass-through is safe - the next quarter
            // ~20 ms, and the whole upper half ~45 ms.
            emit("ASR R11,0x2");
            emit("RJMP 0x8001adfa");
            padTo(0x8001adf0L);
            emit("ASR R11,0x2");
            emit("RJMP 0x8001adfa");
            padTo(0x8001adf4L);
            emit("ASR R11,0x1");
            emit("RJMP 0x8001adfa");
            padTo(0x8001adf8L);
            emit("ASR R11,0x0");
            padTo(0x8001adfaL);
            // The anti-stall nudge, as the old shim had it: a positive gap
            // whose shift rounds to zero still moves one unit; negative gaps
            // round toward minus infinity and move on their own.
            emit("CP.W R11,0x0");
            emit("BR{ne} 0x8001ae06");
            emit("CP.W R7,0x0");
            emit("BR{le} 0x8001ae06");
            emit("MOV R11,0x1");
            padTo(0x8001ae06L);
            emit("ADD R10,R11");
            emit("ST.H R8[0x0],R10");
        } else {
            emit("ST.H R8[0x0],R9");
        }
        padTo(0x8001ae0cL);
        emit("MCALL PC[0x8001ae18]");
        emit("LDM SP++,R7,PC");
        padTo(0x8001ae14L);
        word(0x00003560L); // global state base
        word(0x8001a8f0L); // the blend-offset apply shim
        finish("blend_target_conditioner", 0x8001ae1cL);

        // Preset voltage editing.  The four getters read our store now, so
        // something has to put values into it: hold the pad under a knob and
        // turn that knob, and the store follows until the pad is released.
        //
        // Following does not begin until the knob has actually MOVED, or a pad
        // touched with the knob standing anywhere would snatch the stored
        // voltage to that position - the pickup problem every stored-value
        // control has.  While a pad is up its snapshot tracks the knob, so the
        // movement is always measured from where the knob stood when the pad
        // went down.
        //
        // Pads read like keys: RAM 0x46f0, a byte each, 2 meaning held.  Ours
        // are 0x613a store, 0x6142 snapshots, 0x614a flags, one base reaching
        // all three.
        begin(0x8001ae1cL);
        emit("STM --SP,R0,R1,R2,R7,LR");
        emit("MOV R7,SP");
        emit("LDDPC R9,0x8001aebc");
        emit("MOV R11,0x46f0");
        emit("MOV R1,0x613a");
        emit("MOV R0,0x0");
        emit("MOV R2,0x0");
        padTo(0x8001ae30L);
        emit("ADD R8,R11,R0 << 0x0");
        emit("LD.UB R8,R8[0x0]");
        emit("ADD R12,R9,R2 << 0x0");
        emit("LD.SH R12,R12[0x30a]");
        emit("ADD R10,R1,R0 << 0x0");
        emit("CP.W R8,0x2");
        emit("BR{ne} 0x8001ae78");
        emit("LD.UB R8,R10[0x10]");
        emit("CP.W R8,0x0");
        emit("BR{ne} 0x8001ae70");
        emit("ADD R8,R1,R2 << 0x0");
        emit("LD.SH R8,R8[0x8]");
        emit("SUB R8,R12,R8 << 0x0");
        emit("CP.W R8,0x8");
        emit("BR{gt} 0x8001ae68");
        emit("CP.W R8,-0x8");
        emit("BR{lt} 0x8001ae68");
        emit("RJMP 0x8001ae90");
        padTo(0x8001ae68L);
        emit("MOV R8,0x1");
        emit("ST.B R10[0x10],R8");
        padTo(0x8001ae70L);
        emit("ADD R8,R1,R2 << 0x0");
        emit("ST.H R8[0x0],R12");
        emit("RJMP 0x8001ae90");
        padTo(0x8001ae78L);
        // A partial touch is not a release: ownership, the snapshot and the
        // stored voltage all hold until the finger truly leaves (touch 0),
        // the same boundary the persistence gesture uses.  A flicker down
        // to the light-touch level used to clear following here, and the
        // bare-pad hold could rearm in the middle of a preset edit.
        emit("CP.W R8,0x0");
        emit("BR{ne} 0x8001ae90");
        padTo(0x8001ae80L);
        emit("ADD R8,R1,R2 << 0x0");
        emit("ST.H R8[0x8],R12");
        emit("MOV R8,0x0");
        emit("ST.B R10[0x10],R8");
        padTo(0x8001ae90L);
        emit("SUB R0,-0x1");
        emit("SUB R2,-0x2");
        emit("CP.W R0,0x4");
        emit("BR{lt} 0x8001ae30");
        if (number("knob4_octaves", 0, 0, 1) == 1) {
            emit("MCALL PC[0x8001aeb8]");
        }
        emit("LDM SP++,R0,R1,R2,R7,PC");
        padTo(0x8001aeb8L);
        word(0x8001b010L); // knob 4 as an octave switch
        padTo(0x8001aebcL);
        word(0x00003560L); // global state base
        finish("preset_editor", 0x8001aec0L);

        // Knob 1 as six note orders instead of one blend.  The knob's travel
        // is cut into zones - ascending, descending, mirror, press order,
        // reverse press order, random - and the zone picks how the next key is
        // chosen.  The 1.x behaviour, a continuous blend from press order into
        // randomness, is the other setting; neither is a subset of the other,
        // so the build chooses.
        //
        // The frame here is deliberately the same as the selector this
        // replaces, because two of the six ARE that selector: random and press
        // order jump straight into its existing code, and its epilogue pops
        // this frame correctly because the two match.  Reached by the same
        // pool word, so only one of the two is ever installed.
        begin(0x8001aec0L);
        emit("STM --SP,R0,R1,R2,R3,R7,LR");
        emit("MOV R7,SP");
        emit("MOV R0,R12");
        emit("LDDPC R1,0x8001b000");
        emit("LD.UB R8,R1[0x340]");
        emit("CP.W R8,0x0");
        emit("BR{ne} 0x8001aee0");
        emit("LD.UB R8,R1[0x341]");
        emit("CP.W R8,0x0");
        emit("BR{eq} 0x8001aff0");
        padTo(0x8001aee0L);
        emit("MOV R2,0x60f2");
        emit("LD.UB R2,R2[0x0]");
        emit("MOV R8,0x6");
        emit("MUL R2,R2,R8");
        emit("LSR R2,0x7");             // zone, 0..5
        // Zone 5 is random and zone 2 is mirror, so the knob runs up, down,
        // up-down, as played, backwards, random: the deterministic orders in
        // a row and the unpredictable one at the end of the travel. Zone 2
        // falls through to mirror; its direction-update guard below must
        // use that same zone, not the random zone that used to live here.
        emit("CP.W R2,0x5");
        emit("BR{eq} 0x8001afd0");      // random: the old code
        emit("CP.W R2,0x3");
        emit("BR{eq} 0x8001afd8");      // press order: the old code
        emit("CP.W R2,0x4");
        emit("BR{eq} 0x8001af90");      // reverse press order
        emit("MOV R3,0x1");             // ascending
        emit("CP.W R2,0x0");
        emit("BR{eq} 0x8001af20");
        emit("MOV R3,-0x1");            // descending
        emit("CP.W R2,0x1");
        emit("BR{eq} 0x8001af20");
        // Mirror keeps its direction between notes and turns at the ends.  It
        // is held as 0 or 1 rather than a signed byte, so an unsigned load
        // reads it.
        emit("MOV R8,0x614e");
        emit("LD.UB R8,R8[0x0]");
        emit("MOV R3,0x1");
        emit("CP.W R8,0x0");
        emit("BR{ne} 0x8001af20");
        emit("MOV R3,-0x1");
        padTo(0x8001af20L);
        // A latched slot is not a pitch index: octave stacking can put any
        // pitch in any slot. Choose by (table + signed stamp, slot) instead.
        emit("MCALL PC[0x8001b00c]");
        emit("RJMP 0x8001afe8");
        padTo(0x8001af90L);
        // History survives release. The bounded reverse walk checks held
        // flags, just like the forward walk, before returning a note.
        emit("MCALL PC[0x8001afcc]");
        emit("RJMP 0x8001afe8");
        padTo(0x8001afccL);
        word(0x8001dc60L);
        padTo(0x8001afd0L);
        emit("LDDPC R12,0x8001b004");   // the old random path
        emit("MOV PC,R12");
        padTo(0x8001afd8L);
        emit("LDDPC R12,0x8001b008");   // the old press-order path
        emit("MOV PC,R12");
        padTo(0x8001afe0L);
        emit("MOV R12,R9");
        padTo(0x8001afe8L);
        emit("LDM SP++,R0,R1,R2,R3,R7,PC");
        padTo(0x8001aff0L);
        emit("MOV R12,0x0");
        emit("SUB R12,0x1");            // nothing held: -1
        emit("LDM SP++,R0,R1,R2,R3,R7,PC");
        padTo(0x8001b000L);
        word(0x00003560L); // global state base
        word(0x8001a0deL); // random, past the blend test
        word(0x8001a150L); // press order
        word(0x8001da00L); // pitch-aware ascending / descending / mirror
        finish("arp_order_zones", 0x8001b010L);

        // Knob 4 as an octave switch instead of vibrato.
        //
        // Not by inventing a transpose: the instrument already has one.  The
        // factory's trn mode transposes by ([state+0x6b] - 2) octaves, nine
        // steps from -2 to +6, and knob 4 is the knob that sets it - which is
        // exactly why remap_knobs retires trn, and what this hands back.  So
        // this writes the factory's own two bytes and lets the factory's own
        // code apply them, range checks included.  Those checks are the reason
        // it is done this way: the remap divides unsigned and has no room for
        // a low clamp, so -2 octaves through any shortcut of ours would go
        // negative at the bottom key and wrap enormous.
        //
        // Our octave_scale_mul/bias patches sit on that arithmetic already, so
        // trn steps the scale's period rather than a hardcoded 2/1.
        //
        // Run both before the ADC event's pitch calculation and after the
        // tuning applier (which clears the enable byte). The early pass must
        // predict pickup without consuming the preset editor's release edge.
        begin(0x8001b010L);
        emit("LDDPC R8,0x8001b04c");
        emit("MOV PC,R8");
        padTo(0x8001b04cL);
        word(0x8001d960L);
        finish("knob4_octave_switch", 0x8001b050L);

        if (block("knob4_octave_switch")) {
            begin(0x800051f0L);
            word(0x8001d920L);
            finish("knob4_early_pool", 0x800051f4L);
        }

        // Knob 2 as a bank of step patterns.  A pattern says whether a step
        // sounds, which is not a question about how long the step is, so this
        // sits at the note selector rather than in the rhythm randomiser: it
        // takes the selector's pool word and calls the real selector through.
        //
        // A rest returns -1, the same answer the selector gives when nothing
        // is held, and the caller already knows to stay quiet for it.  The
        // note sequence does not advance on a rest - only hits move it on -
        // so a sparse fill plays the arpeggio slowly rather than skipping
        // through it.
        //
        // Knob 2 picks the pattern; RAM 0x6150 is the step, wrapped at that
        // pattern's own length.
        begin(0x8001b050L);
        emit("STM --SP,R0,R1,R7,LR");
        emit("MOV R7,SP");
        emit("MOV R0,R12");             // hold the caller's argument
        emit("LDDPC R1,0x8001b0f0");    // state base
        // Which pattern: knob 2's latch across the bank.
        emit("MOV R8,0x60e6");
        emit("LD.SH R8,R8[0x0]");
        emit("CP.W R8,0x0");
        emit("BR{ge} 0x8001b06c");
        emit("MOV R8,0x0");
        padTo(0x8001b06cL);
        emit(String.format("MOV R9,0x%x", number("pattern_count", 1, 1, 32)));
        emit("MUL R8,R8,R9");
        emit("LSR R8,0xa");
        emit("CP.W R8,R9");
        emit("BR{lt} 0x8001b07c");
        emit("MOV R8,R9");
        emit("SUB R8,0x1");
        padTo(0x8001b07cL);
        // That pattern's mask (two halfwords, low first) and its length.
        emit("LDDPC R10,0x8001b0f4");   // bank
        emit("ADD R10,R10,R8 << 0x2");
        emit("LD.UH R11,R10[0x0]");
        emit("LD.UH R12,R10[0x2]");
        emit("LSL R12,0x10");
        emit("OR R11,R12");             // the 32 steps
        emit("LDDPC R10,0x8001b0f8");   // lengths
        emit("ADD R10,R10,R8 << 0x1");
        emit("LD.UH R9,R10[0x0]");
        // Where we are in it, and where we go next.
        emit("MOV R10,0x6150");
        emit("LD.UH R8,R10[0x0]");
        emit("CP.W R8,R9");
        emit("BR{lt} 0x8001b0a0");
        emit("MOV R8,0x0");
        padTo(0x8001b0a0L);
        emit("MOV R12,R8");
        emit("SUB R12,-0x1");
        emit("CP.W R12,R9");
        emit("BR{lt} 0x8001b0ac");
        emit("MOV R12,0x0");
        padTo(0x8001b0acL);
        emit("ST.H R10[0x0],R12");
        // Does this step sound?  There is no shift-by-register here, so the
        // mask walks down to bit zero instead - at most 31 passes, once per
        // arpeggiator step, which is nothing.
        emit("CP.W R8,0x0");
        emit("BR{eq} 0x8001b0bc");
        padTo(0x8001b0b4L);
        emit("LSR R11,0x1");
        emit("SUB R8,0x1");
        emit("BR{gt} 0x8001b0b4");
        padTo(0x8001b0bcL);
        emit("BFEXTU R11,R11,0x0,0x1");
        emit("CP.W R11,0x0");
        emit("BR{eq} 0x8001b0d8");
        emit("MOV R12,R0");             // a hit: the real selector answers
        emit("MCALL PC[0x8001b0fc]");
        emit("LDM SP++,R0,R1,R7,PC");
        padTo(0x8001b0d8L);
        emit("MOV R12,0x0");
        emit("SUB R12,0x1");            // a rest
        emit("LDM SP++,R0,R1,R7,PC");
        padTo(0x8001b0f0L);
        word(0x00003560L); // global state base
        word(0x80019f20L); // pattern bank
        word(0x80019f78L); // pattern lengths
        word(number("knob1_orders", 0, 0, 1) == 1 ? 0x8001aec0L : 0x8001a0a0L);
        finish("arp_pattern_gate", 0x8001b100L);

        // Knob 2 as swing.  The randomiser it replaces answers the same
        // question - how long is this step - so this takes the same hook and
        // the same output cell, and simply lengthens every other step by as
        // much as it shortens the one after.  The pair keeps its total, so
        // the arpeggio does not drift in tempo, it only stops being square.
        //
        // Up to a third either way, which is a triplet feel at full travel.
        begin(0x8001b100L);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("MOV R9,R12");             // the step this would have been
        emit("MOV R8,0x60e6");
        emit("LD.SH R8,R8[0x0]");
        emit("CP.W R8,0x30");
        emit("BR{lt} 0x8001b154");      // deadzone: square, exactly as shipped
        emit("MOV R10,0x55");
        emit("MUL R8,R8,R10");
        emit("LSR R8,0xa");             // 0..85 out of 256: a third of a step
        emit("MUL R10,R9,R8");
        emit("LSR R10,0x8");            // how far this step moves
        emit("MOV R11,0x6152");
        emit("LD.UB R12,R11[0x0]");
        emit("CP.W R12,0x0");
        emit("BR{ne} 0x8001b134");
        emit("MOV R12,0x1");            // long now, short next
        emit("ST.B R11[0x0],R12");
        emit("ADD R9,R10");
        emit("RJMP 0x8001b13c");
        padTo(0x8001b134L);
        emit("MOV R12,0x0");
        emit("ST.B R11[0x0],R12");
        emit("SUB R9,R9,R10 << 0x0");
        padTo(0x8001b13cL);
        emit("CP.W R9,0x8");            // the randomiser's own limits
        emit("BR{ge} 0x8001b146");
        emit("MOV R9,0x8");
        padTo(0x8001b146L);
        emit("MOV R8,0xfff");
        emit("CP.W R9,R8");
        emit("BR{le} 0x8001b154");
        emit("MOV R9,R8");
        padTo(0x8001b154L);
        emit("LDDPC R8,0x8001b160");
        emit("ST.H R8[0x38e],R9");
        emit("LDM SP++,R7,PC");
        padTo(0x8001b160L);
        word(0x00003560L); // global state base
        finish("arp_swing", 0x8001b164L);

        // The sequencer's controls, on a pad chord.  Hold pad 4 for about one
        // second to arm - its light blinks - then, still holding it, press
        // pad 1 to record, pad 2 to play, pad 3 to stop.  The add-to-pitch
        // toggle is not involved: it keeps selecting octaves, preset voltage
        // or none exactly as the factory does.
        //
        // A deliberate hold because pad 4 with another pad is an ordinary thing
        // to do, and a bare chord would fire by accident.  The arm dies with
        // the hold, so it can never outlive the gesture that made it.
        //
        // RAM off one base at 0x6154: +0 hold counter (halfword; scans are
        // ~5 ms, so one second is 200 of them), +2 armed, +3 selected,
        // +4 mode, +5 the pad to hold the selection at, +6..8 last scan's
        // touch levels for pads 1-3, +0xa a free-running blink counter.
        // One counter drives every blink this firmware adds, so they share a
        // rate and a phase; bit 6 of it toggles every 64 scans, ~1.6 Hz.
        begin(0x8001b180L);
        emit("STM --SP,R0,R1,R2,R3,R7,LR");
        emit("MOV R7,SP");
        emit("LDDPC R0,0x8001b300");    // global state base
        emit("MOV R1,0x6154");
        emit("MOV R2,0x46f0");          // the pad touch array, 2 = held
        emit("LD.UH R8,R1[0xa]");
        emit("SUB R8,-0x1");
        emit("ST.H R1[0xa],R8");
        emit("BFEXTU R3,R8,0x6,0x1");   // R3 = the blink phase, for all of it

        emit("LD.UB R8,R2[0x3]");
        emit("CP.W R8,0x2");
        emit("BR{ne} 0x8001b1e0");      // pad 4 up: the release path

        // Held.  Count towards the arm, saturating rather than wrapping -
        // wrapping would disarm a long hold when the count passed zero.
        emit("LD.UH R8,R1[0x0]");
        emit(String.format("MOV R9,0x%x",
             number("chord_hold_scans", 300, 20, 2000)));
        emit("CP.W R8,R9");
        emit("BR{ge} 0x8001b1ae");
        emit("SUB R8,-0x1");
        emit("ST.H R1[0x0],R8");
        padTo(0x8001b1aeL);
        emit("LD.UB R10,R1[0x2]");      // armed?
        emit("CP.W R10,0x0");
        emit("BR{ne} 0x8001b1cc");
        emit("CP.W R8,R9");
        emit("BR{lt} 0x8001b200");      // not long enough yet
        // A hold whose knob has moved is a preset edit, not a chord.  The
        // editor flags that pad as following at 0x614a + pad.
        emit("MOV R10,0x614d");
        emit("LD.UB R10,R10[0x0]");
        emit("CP.W R10,0x0");
        emit("BR{ne} 0x8001b200");
        emit("MOV R10,0x1");
        emit("ST.B R1[0x2],R10");       // armed
        emit("LD.UB R10,R0[0x2ef]");
        emit("ST.B R1[0x5],R10");       // hold the selection where it stands

        padTo(0x8001b1ccL);
        // The selecting press must not also pick a preset, so the active pad
        // is frozen for as long as the arm lasts.  Freezing beats undoing
        // each press: it cannot race the factory's own pad handler.
        emit("LD.UB R10,R1[0x5]");
        emit("LD.UB R11,R0[0x2ef]");
        emit("CP.W R11,R10");
        emit("BR{eq} 0x8001b200");
        emit("MOV R12,R10");
        emit("MCALL PC[0x8001b304]");   // select_pad
        emit("RJMP 0x8001b200");

        padTo(0x8001b1e0L);
        // Pad 4 up.  Everything the hold set goes, and the lights are
        // repainted from the truth underneath rather than from anything
        // remembered, so an eaten press cannot leave them wrong.  Runs on
        // every exit, the hold that refused to arm included.
        emit("LD.UH R8,R1[0x0]");
        emit("CP.W R8,0x0");
        emit("BR{ne} 0x8001b1f0");
        emit("LD.UB R8,R1[0x2]");
        emit("CP.W R8,0x0");
        emit("BR{eq} 0x8001b200");
        padTo(0x8001b1f0L);
        emit("MOV R9,0x0");
        emit("ST.H R1[0x0],R9");
        emit("ST.B R1[0x2],R9");
        emit("ST.B R1[0x3],R9");
        emit("LD.UB R12,R0[0x2ef]");
        emit("MCALL PC[0x8001b304]");   // select_pad repaints all four

        padTo(0x8001b200L);
        // Pads 1, 2 and 3 on their press edge.  This loop runs EVERY scan,
        // armed or not: if it only ran while armed, a pad already held when
        // the arm completed would look like a fresh press and fire at once.
        emit("MOV R11,0x0");
        padTo(0x8001b204L);
        emit("ADD R12,R2,R11 << 0x0");
        emit("LD.UB R12,R12[0x0]");     // this scan's level
        emit("ADD R8,R1,R11 << 0x0");
        emit("LD.UB R9,R8[0x6]");       // last scan's
        emit("ST.B R8[0x6],R12");
        // The press edge first, because BOTH readings of a press need it: a
        // chord while pad 4 is held, and - while recording - a bare press
        // meaning preview or backspace.
        // The bare reading is watched EVERY scan, held or not, because it is
        // a hold that means something now and the count has to clear when
        // the finger comes off.  The chord still wants the press edge.
        emit("LD.UB R10,R1[0x2]");      // armed?
        emit("CP.W R10,0x0");
        emit("BR{ne} 0x8001b21e");
        emit("MCALL PC[0x8001b244]");   // bare: seq_hold counts, then acts
        emit("RJMP 0x8001b248");
        padTo(0x8001b21eL);
        emit("CP.W R12,0x2");
        emit("BR{ne} 0x8001b248");
        emit("CP.W R9,0x2");
        emit("BR{eq} 0x8001b248");
        padTo(0x8001b226L);
        // A press, and as many as you like: using the hold does not spend
        // it.  Pad 4 stays held and stays armed until it is let go, so play
        // then stop then clear is three presses inside one hold rather than
        // three separate holds.  R11 is the pad; seq_enter decides what it
        // means and writes the mode itself.
        emit("MCALL PC[0x8001b318]");
        // Repaint from the frozen pad: that is both the freeze and the clean
        // slate the flash below writes its own channel onto.
        emit("LD.UB R12,R1[0x5]");
        emit("MCALL PC[0x8001b304]");   // select_pad
        emit("RJMP 0x8001b248");        // over the pool word, never through it
        padTo(0x8001b244L);
        word(0x8001dd20L);              // seq_hold
        padTo(0x8001b248L);
        emit("SUB R11,-0x1");
        emit("CP.W R11,0x3");
        emit("BR{lt} 0x8001b204");

        // Pad 4's own light blinks for as long as the hold lasts.  It used to
        // go steady on the first press, which said "taken" when only one
        // press was allowed; now that the hold keeps taking them, blinking
        // for the whole hold is what is true.  Only while armed - the release
        // path already repainted.
        emit("LD.UB R10,R1[0x2]");
        emit("CP.W R10,0x0");
        emit("BR{eq} 0x8001b268");
        emit("MOV R9,R3");
        padTo(0x8001b25eL);
        emit("MOV R11,0x3");
        emit("MCALL PC[0x8001b314]");   // write one channel
        padTo(0x8001b268L);
        // The running mode flashes its own pad for as long as it runs, and
        // has to be written EVERY scan: select_pad clears channels 0-3 and
        // lights one on every pad press, so a flash asserted once would be
        // wiped by the next press.  Record is pad 1, play is pad 2.
        emit("LD.UB R11,R1[0x4]");
        emit("CP.W R11,0x0");
        emit("BR{eq} 0x8001b27a");
        emit("SUB R11,0x1");
        emit("MOV R9,R3");
        emit("MCALL PC[0x8001b314]");   // write one channel
        padTo(0x8001b27aL);
        // The pitch strip, watched here because this is the cave that runs
        // every scan.  It reads where the strip is while it is held and
        // enters a rest or a tie when it is let go - a release is a release,
        // whatever the bend value happened to be doing.
        emit("MCALL PC[0x8001b31c]");   // the strip, per scan
        emit("MCALL PC[0x8001b2bc]");   // and sound whatever record took in
        padTo(0x8001b28aL);
        emit("MCALL PC[0x8001b310]");   // led_flush: free when nothing changed
        emit("LDM SP++,R0,R1,R2,R3,R7,PC");

        padTo(0x8001b2a0L);
        // write_channel(R11 = channel, R9 = lit or not).
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("MOV R12,R11");
        emit("CP.W R9,0x0");
        emit("BR{eq} 0x8001b2b4");
        emit("MCALL PC[0x8001b308]");   // led_set
        emit("LDM SP++,R7,PC");
        padTo(0x8001b2b4L);
        emit("MCALL PC[0x8001b30c]");   // led_clear
        emit("LDM SP++,R7,PC");

        padTo(0x8001b2bcL);
        word(0x8001df30L); // the delete-pad flash, then seq_record_sound
        padTo(0x8001b2c0L);
        // Hearing what you just played into the sequence.  Recording silences
        // the arp - an arpeggiator chewing on what you hold is not what you
        // are listening for - but silence is not what you want either: you
        // want the note you just entered, once, with its pitch and its
        // trigger.
        //
        // So the note-on leaves the key here and this steps the arp once, now
        // (R12 = -1: step, do not reload).  The selector answers with that key
        // and spends it, so the arp's own steps after it sound nothing.  The
        // pitch, the gate, the trigger and the MIDI note all come from the
        // factory's own note machinery that way, already paired.
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("LD.UB R8,R1[0x4]");
        emit("CP.W R8,0x1");
        emit("BR{ne} 0x8001b2de");      // only record
        emit("MOV R8,0x6230");
        emit("LD.UH R8,R8[0x0]");
        emit("CP.W R8,0x0");
        emit("BR{eq} 0x8001b2de");      // nothing waiting to be heard
        emit("MOV R12,0xffff");
        emit("MCALL PC[0x8001b2e4]");   // the arp step
        padTo(0x8001b2deL);
        emit("LDM SP++,R7,PC");
        padTo(0x8001b2e4L);
        word(0x8000210cL); // the arp step
        padTo(0x8001b2e8L);
        // What the selector answers while recording: the key waiting to be
        // heard, once, or nothing.
        emit("MOV R8,0x6230");
        emit("LD.UH R12,R8[0x0]");
        emit("CP.W R12,0x0");
        emit("BR{eq} 0x8001b2fa");
        emit("MOV R9,0x0");
        emit("ST.H R8[0x0],R9");        // spent
        emit("SUB R12,0x1");
        emit("MOV PC,LR");
        padTo(0x8001b2faL);
        emit("MOV R12,0x0");
        emit("SUB R12,0x1");            // -1: nothing sounds
        emit("MOV PC,LR");

        padTo(0x8001b300L);
        word(0x00003560L); // global state base
        word(0x8000698cL); // select_pad(0..3)
        word(0x80006808L); // led_set(ch)
        word(0x800068ccL); // led_clear(ch)
        word(0x8000673cL); // led_flush()
        word(0x8001b2a0L); // write_channel(R11, R9)
        word(0x8001b660L); // seq_enter(R11 = the pad pressed)
        word(0x8001b590L); // the strip, per scan
        finish("seq_chord", 0x8001b320L);

        // Explicit pad transport: record appends, play starts at the top,
        // and CLEAR alone erases the take. Cancel preview ownership here,
        // not in the shared transport used to start/end a preview internally.
        begin(0x8001b660L);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("MCALL PC[0x8001b6b8]");   // seq_command: R8=steps, R10=zero
        // Whatever this press means, nothing transient carries into it.
        // Stopping mid-tie used to leave the slide armed, so the first note
        // after restarting held its gate and slid in from nowhere; and a
        // strip still held when record starts is blocked until release by
        // the shared transport below, so it cannot open an unintended rest.
        emit("ST.B R8[0x5],R10");       // 0x61e5, the tie's slide count
        // and the key a note-on left waiting to be heard.  The pad loop runs
        // before the record-sound call in the same scan, so a press that ends
        // a take can leave the last note pending - and the NEXT take would
        // open by sounding a note nobody played into it.
        emit("ST.H R8[0x50],R10");      // 0x6230
        emit("ST.H R8[0x320],R10");     // 0x6500, the audition's pinned pitch
        emit("MOV R12,0x6154");
        emit("CP.W R11,0x0");
        emit("BR{ne} 0x8001b688");
        // Record APPENDS.  It used to wipe, which made going back for one
        // more note mean playing the whole thing again; clearing is pad 3's
        // job and saying so once is enough.
        //
        // And pad 1 TOGGLES, the way pad 2 does: pressing it again inside the
        // same hold ends the take rather than leaving record mode reachable
        // only by starting playback or clearing.  The stop it wants is pad
        // 2's, three instructions further down, so it borrows that.
        emit("LD.UB R9,R12[0x4]");
        emit("CP.W R9,0x1");
        emit("BR{eq} 0x8001b69c");      // already recording: stop
        emit("MOV R9,0x1");
        emit("RJMP 0x8001b6a8");
        padTo(0x8001b688L);
        emit("CP.W R11,0x1");
        emit("BR{ne} 0x8001b6a0");
        // Pad 2 both starts and stops: the same pad either way, so there is
        // no hunting for which one ends it.
        emit("LD.UB R9,R12[0x4]");
        emit("CP.W R9,0x2");
        emit("BR{eq} 0x8001b69c");
        emit("ST.B R8[0x1],R10");       // play, from the top
        emit("MOV R9,0x2");
        emit("RJMP 0x8001b6a8");
        padTo(0x8001b69cL);
        emit("MOV R9,0x0");             // already playing: stop
        emit("RJMP 0x8001b6a8");
        padTo(0x8001b6a0L);
        emit("ST.B R8[0x0],R10");       // pad 3: clear it out, and stop
        emit("ST.B R8[0x1],R10");
        emit("MOV R9,0x0");
        padTo(0x8001b6a8L);
        emit("MCALL PC[0x8001b6b4]");   // the strip's mode, aside or back
        emit("ST.B R12[0x4],R9");       // the mode this press leaves behind
        emit("LDM SP++,R7,PC");
        padTo(0x8001b6b4L);
        word(0x8001d640L); // transport + strip_mode_swap(R9 = new mode)
        word(0x8001d840L); // seq_command(R11 = explicit pad command)

        padTo(0x8001b6c0L);
        // Everything that has to happen because the sequencer's mode is
        // CHANGING, in the one place that can see both what it was and what
        // it is becoming.  R9 is the mode being entered, R12 the sequencer's
        // own block.
        //
        // The strip has two modes of its own, and state+0x20c says which:
        // 0 stays where it is left, 1 springs back and bends the pitch.
        // Recording wants the first, because a rest and a tie are read from
        // an absolute position - so record borrows it and gives back
        // whatever the player had, rather than switching them silently.
        // The saved value is kept plus one, so that zero means nothing is
        // being held and a restore cannot fire twice.
        emit("STM --SP,R0,R7,LR");
        emit("MOV R7,SP");
        emit("LD.UB R8,R12[0x4]");      // the mode this press replaces
        emit("CP.W R8,R9");
        emit("BR{eq} 0x8001b728");      // nothing is changing
        // Leaving PLAY ends the note the sequencer was sounding - and leaving
        // WRITE ends a recording audition still ringing, MIDI note-off
        // included.  Nothing else will: the arp step is what tidies up after
        // a step, and a stop or a CLEAR lands exactly when the arp is not
        // stepping.  Entering from idle (R8 zero) has nothing sounding.
        emit("CP.W R8,0x0");
        emit("BR{eq} 0x8001b6d6");
        emit("MCALL PC[0x8001b734]");   // seq_release, which keeps R9 and R12
        emit("LD.UB R8,R12[0x4]");      // the call had R8
        padTo(0x8001b6d6L);
        emit("LDDPC R10,0x8001b730");   // global state base
        emit("MOV R11,0x622e");
        emit("CP.W R9,0x1");
        emit("BR{ne} 0x8001b714");
        emit("LD.W R8,R10[0x20c]");
        emit("SUB R8,-0x1");
        emit("ST.H R11[0x0],R8");
        emit("MOV R11,0x0");
        emit("ST.W R10[0x20c],R11");    // absolute, for as long as record lasts
        // A bend already standing has to be put away with the mode that made
        // it.  state+0x216 is the offset the pitch adds, and bend() only ever
        // writes it on the relative side of its own test at 0x80002edc - so
        // once record has forced absolute, no value we pass bend() can reach
        // it, and a bend left over from before the take would be added to
        // every note of it.  This is the factory's own 1 -> 0 cleanup at
        // 0x8000afee, done for the same reason it does it.
        emit("CP.W R8,0x2");            // relative, plus the one it is kept as
        emit("BR{ne} 0x8001b728");
        emit("ST.H R10[0x216],R11");    // R11 is still zero
        // R9 is the mode being entered and the caller still needs it, and a
        // call is free to destroy R8..R12 - so it goes on the stack, and the
        // port is loaded again for the second send rather than being expected
        // to survive the first.
        emit("ST.W --SP,R9");
        emit("LD.UB R0,R10[0x2e7]");    // the port, where a call cannot reach
        emit("MOV R10,R0");
        emit("MOV R11,0x0");
        emit("MOV R12,0x40");           // pitch bend centre: 0x2000
        emit("MCALL PC[0x8001b738]");
        emit("MOV R10,R0");
        emit("MOV R11,0x0");
        emit("MOV R12,0x40");
        emit("MCALL PC[0x8001b73c]");
        emit("LD.W R9,SP++");
        emit("RJMP 0x8001b728");
        padTo(0x8001b714L);
        emit("CP.W R8,0x1");
        emit("BR{ne} 0x8001b728");      // record is not what is being left
        emit("LD.UH R8,R11[0x0]");
        emit("CP.W R8,0x0");
        emit("BR{eq} 0x8001b728");      // nothing was ever borrowed
        emit("SUB R8,0x1");
        emit("ST.W R10[0x20c],R8");
        emit("MOV R8,0x0");
        emit("ST.H R11[0x0],R8");
        padTo(0x8001b728L);
        emit("MOV R12,0x6154");         // the block again, for the caller
        emit("LDM SP++,R0,R7,PC");
        padTo(0x8001b730L);
        word(0x00003560L); // global state base
        word(0x8001b448L); // seq_release
        word(0x80008104L); // pitch bend out, one port
        word(0x80007efcL); // and the other
        finish("seq_enter", 0x8001b740L);

        // The bend strip, while recording.  Two pieces share this block: the
        // bend hook, whose only job is silence, and the per-scan watch that
        // reads where the strip is and enters what it says.
        //
        // The hook is called with R12 = the strip's value, and only when that
        // value CHANGES - the factory's own bend function already early-exits
        // on an unchanged one.  Recording passes zero on, so a strip touched
        // to enter a rest does not also bend the pitch.
        begin(0x8001b570L);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("MOV R8,0x6154");
        emit("LD.UB R8,R8[0x4]");
        emit("CP.W R8,0x1");
        emit("BR{ne} 0x8001b582");      // not recording: the strip is itself
        emit("MOV R12,0x0");
        padTo(0x8001b582L);
        emit("MCALL PC[0x8001b600]");   // the factory's own bend
        emit("LDM SP++,R7,PC");

        padTo(0x8001b590L);
        // The strip, once per scan, called from the pad chord's own cave.
        //
        // A rest or a tie is read from WHERE the strip is when it is let go,
        // not from which way it was pushed: below halfway a rest, above
        // halfway a tie.  state+0x1fe is that position - the centroid of the
        // seven capacitive segments (0x8000aa98), mapped from 1250..6750 onto
        // 0..4095 by the factory's own clamping mapper at 0x8000ad00.  It is
        // written only while the touch flag is up, so after a release it
        // still holds where the finger left, and it is the raw position in
        // both strip modes: what state+0x20c changes is state+0x1f8, the
        // OUTPUT, which is absolute in one mode and centred on 0x7ff in the
        // other.
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("MOV R10,0x61e0");         // the step store; +4 is the strip's latch
        emit("LDDPC R11,0x8001b604");   // global state base
        emit("LD.UB R8,R11[0x206]");    // is the strip touched at all
        emit("CP.W R8,0x0");
        emit("BR{eq} 0x8001b5b4");
        // 0 = released, 1 = this touch may produce a step, 2 = a touch
        // carried across transport. Never re-arm 2 while the finger stays
        // down; the release clears it without appending anything.
        emit("LD.UB R8,R10[0x4]");
        emit("CP.W R8,0x0");
        emit("BR{ne} 0x8001b5f6");
        emit("MOV R8,0x1");
        emit("ST.B R10[0x4],R8");       // down, and down is what a release needs
        emit("LDM SP++,R7,PC");
        padTo(0x8001b5b4L);
        // Up.  One step per release, and every release: three taps at the
        // bottom enter three rests, which is what a bar of them takes.
        emit("LD.UB R8,R10[0x4]");
        emit("MOV R9,0x0");
        emit("ST.B R10[0x4],R9");
        emit("CP.W R8,0x1");
        emit("BR{ne} 0x8001b5f6");      // already up, or transport rejected it
        emit("MOV R8,0x6154");
        emit("LD.UB R8,R8[0x4]");
        emit("CP.W R8,0x1");
        emit("BR{ne} 0x8001b5f6");      // only record listens to the strip
        emit("LD.UB R9,R10[0x0]");
        emit("CP.W R9,0x40");
        emit("BR{ge} 0x8001b5f6");      // 64 steps and no more
        emit("LD.SH R12,R11[0x1fe]");   // where the finger left
        emit(String.format("MOV R8,0x%x",
             number("strip_halfway_units", 2048, 128, 3968)));
        emit("MOV R11,0x2");            // above halfway: a tie
        emit("CP.W R12,R8");
        emit("BR{ge} 0x8001b5e0");
        emit("MOV R11,0x1");            // below halfway: a rest
        padTo(0x8001b5e0L);
        // 0x7ffe is a rest and 0x7fff a tie, as a pitch can never be either.
        emit("MOV R8,0x7ffd");
        emit("ADD R8,R8,R11 << 0x0");
        emit("MOV R12,0x6160");
        emit("ADD R12,R12,R9 << 0x1");
        emit("ST.H R12[0x0],R8");
        emit("SUB R9,-0x1");
        emit("ST.B R10[0x0],R9");
        padTo(0x8001b5f6L);
        emit("LDM SP++,R7,PC");
        padTo(0x8001b600L);
        word(0x80002e30L); // bend(position)
        word(0x00003560L); // global state base
        finish("seq_strip", 0x8001b608L);

        // The glide rate, stored.  Normally whatever the clamp worked out -
        // for a pressure-blend build that is zero, meaning notes snap.  But
        // for the one step where a tie moves the pitch, a rate of our own, so
        // the note slides into the next rather than stepping to it.  303
        // fashion: the tie is the slide.
        begin(0x8001b610L);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("MOV R10,0x6154");
        emit("LD.UB R10,R10[0x4]");
        emit("CP.W R10,0x2");
        emit("BR{ne} 0x8001b648");      // not playing: whatever the clamp said
        // The portamento knob is asked FIRST, and its answer covers the tie's
        // slide as well as the ordinary glide.  A tie makes the note before it
        // longer; the note after a tie is a new note, and a new note that
        // slides in when nothing was asked to slide is just wrong.  The
        // deadzone is the one every other glide in this firmware answers to.
        emit("MOV R8,0x3866");
        emit("LD.SH R8,R8[0x0]");
        emit("CP.W R8,0x30");
        emit("BR{lt} 0x8001b646");      // knob off: nothing slides at all
        emit("MOV R10,0x61e5");
        emit("LD.UB R10,R10[0x0]");
        emit("CP.W R10,0x0");
        emit("BR{eq} 0x8001b63c");
        // A tie in hand, and the knob up: the slide is the tie's, 303
        // fashion, rather than the knob's own time.
        emit(String.format("MOV R8,0x%x",
             number("tie_glide_rate", 60, 1, 1024)));
        emit("RJMP 0x8001b648");
        padTo(0x8001b63cL);
        // Playing, no tie in hand: the portamento knob means TIME here, the
        // way it does on a build without the pressure blend.  A blend build
        // otherwise forces the rate to zero, because pressure is the
        // portamento - but the sequencer's keyboard is silent, so there is no
        // pressure to blend and the knob would mean nothing at all.  R9 still
        // holds the table index the caller worked out.
        emit("LDDPC R8,0x8001b654");
        emit("LD.SH R8,R8[R9 << 0x1]");
        emit("CASTS.H R8");
        emit("RJMP 0x8001b648");
        padTo(0x8001b646L);
        emit("MOV R8,0x0");
        padTo(0x8001b648L);
        emit("MOV R9,0x2eee");
        emit("ST.H R9[0x0],R8");
        emit("LDM SP++,R7,PC");
        padTo(0x8001b654L);
        word(0x80015150L); // the factory glide-rate table
        finish("seq_glide", 0x8001b658L);

        // Scheduled diagnostic counter and long-low banking. Short low phases
        // and input intervals are measured with COUNT in the GPIO ISR.
        begin(0x8001bb70L);
        emit("STM --SP,R0,R7,LR");
        emit("MOV R7,SP");
        emit("MOV R0,R12");
        emit("MOV R8,0x61e6");
        emit("LD.UH R9,R8[0x0]");
        emit("SUB R9,-0x1");
        emit("ST.H R8[0x0],R9");
        if (twoPhaseBeat()) {
            // The beat's settle is milliseconds, so it is spent here, on the
            // 1 ms timer, and not on the 5 ms scan that used to quantise it.
            // Only while the flush owns the step (claim 3); at every other
            // moment 0x60ee is the scan's own countdown and is not ours.
            emit("MOV R8,0x625b");
            emit("LD.UB R9,R8[0x0]");
            emit("CP.W R9,0x3");
            emit("BR{ne} 0x8001bb9c");
            emit("MOV R8,0x60ee");
            emit("LD.UB R9,R8[0x0]");
            emit("CP.W R9,0x0");
            emit("BR{eq} 0x8001bb9c");
            emit("SUB R9,0x1");
            emit("ST.B R8[0x0],R9");
            padTo(0x8001bb9cL);
        }
        emit("MCALL PC[0x8001bbb8]");   // bank a long low before COUNT wraps
        emit("MOV R12,R0");
        emit("MCALL PC[0x8001bbbc]");
        emit("LDM SP++,R0,R7,PC");
        padTo(0x8001bbb8L);
        word(0x8001ca00L);
        word(0x800076b0L);
        finish("clock_ms_tick", 0x8001bbc0L);

        // Clock-latency diagnostic.  Both gate-raise paths reach the factory
        // pulse-high routine through a pool word; this build repoints both at
        // the shim below, so nothing is added at either call site.  It stamps
        // COUNT against the stamp of the edge the dequeue is actually acting
        // on (0x6240), keeps a running max and mean in cycles/32, and
        // tail-calls the real routine.  NOT the ISR's newest accepted stamp
        // at 0x623c: that is whatever the input has done since, so with any
        // queue depth at all it charges a beat's gate raise to an edge that
        // did not cause it.  Fully transparent: R8-R12 are saved around the
        // measurement because the callee's argument convention is the factory
        // routine's, not ours.
        //
        // This is the one split no other measurement reaches. The instrument
        // shows 3.3-3.4 ms of edge-to-gate spread that is invariant to the
        // scan period, to the settle, to where the dequeue runs, and whose
        // output stage is fine-grained on both clock sources.  Everything
        // between the ISR stamp and the gate is timed here.  If this reports
        // the full 3.4 ms, the delay is inside the firmware after the stamp.
        // If it reports far less, the delay is BEFORE the stamp -- interrupt
        // latency or input conditioning -- which no firmware change reaches.
        //
        // Guarded on 0x6236 so only external-clock beats are timed; an
        // internal beat leaves 0x6240 stale and would report nonsense.
        // Max/mean are running, not windowed: power-cycle to reset.
        if (block("clock_latency")) {
            begin(0x8001bbc0L);
            emit("STM --SP,R7,LR");
            emit("MOV R7,SP");
            emit("STM --SP,R8,R9,R10,R11,R12");
            emit("MOV R10,0x6234");
            emit("LD.UB R8,R10[0x2]");      // 0x6236 input present
            emit("CP.W R8,0x0");
            emit("BR{eq} 0x8001bc50");
            // Time each CONSUMED EDGE exactly once. Without this, any gate
            // raise that was not caused by the edge still under measurement
            // -- a latched key, a rest completing, anything the arp does
            // between beats -- is timed against a stale stamp and reports a
            // delay that never happened. The first version of this shim did
            // exactly that and reported a 5.64 ms spread where the scope saw
            // 3.36 ms, which is how the fault was found: a sub-interval
            // cannot be wider than the path containing it.
            emit("LD.W R11,R10[0xc]");      // 0x6240 stamp of the edge in flight
            emit("MOV R9,0x6040");
            emit("LD.W R8,R9[0x0]");        // stamp of the edge last timed
            emit("CP.W R8,R11");
            emit("BR{eq} 0x8001bc50");      // this edge is already counted
            emit("ST.W R9[0x0],R11");
            emit("MFSR R9,COUNT");
            emit("SUB R11,R9,R11 << 0x0");  // cycles since that edge
            emit("LSR R11,0x5");            // cycles/32, to fit a CC pair
            // Out of range is DISCARDED, not clamped. Clamping wrote 0x3fff
            // into the max, and a max is the one statistic a single bad
            // sample destroys for the rest of the session -- the instrument
            // published exactly 16383 for that reason. A sample only gets
            // here above 8.74 ms if the beat waited behind a drained
            // backlog, which is a different population from the delay this
            // is measuring. 0x6040 is already updated, so a discarded edge
            // is not retried against a later gate raise.
            emit("MOV R8,0x3fff");
            emit("CP.W R11,R8");
            emit("BR{hi} 0x8001bc50");
            padTo(0x8001bc10L);
            emit("MOV R10,0x6032");
            emit("LD.UH R8,R10[0x0]");      // running max
            emit("CP.W R11,R8");
            emit("BR{ls} 0x8001bc20");
            emit("ST.H R10[0x0],R11");
            padTo(0x8001bc20L);
            // Running MEAN, because min and max are the least robust pair of
            // statistics there are and this instrument has already been
            // fooled once by an outlier. The mean is what gets compared
            // against the scope's own 1.55 ms.
            emit("MOV R10,0x6038");
            emit("LD.W R8,R10[0x0]");       // sum of delays
            emit("ADD R8,R11");
            emit("ST.W R10[0x0],R8");
            emit("LD.UH R9,R10[0x4]");      // 0x603c sample count
            emit("SUB R9,-0x1");
            emit("ST.H R10[0x4],R9");
            emit("DIVU R8,R8,R9");          // quotient R8, even destination
            emit("MOV R10,0x6034");
            emit("ST.H R10[0x0],R8");       // published mean
            padTo(0x8001bc50L);
            emit("LDM SP++,R8,R9,R10,R11,R12");
            emit("MCALL PC[0x8001bc60]");   // the real pulse-high routine
            emit("LDM SP++,R7,PC");
            padTo(0x8001bc60L);
            word(0x800077f8L);
            finish("clock_latency", 0x8001bc64L);
        }

        // Main-loop wrapper, NOT a pitch-remap callback. Take at most one
        // queued edge before the factory dispatcher. A sounding step holds
        // its slot until its pitch has been stored and its trigger emitted.
        // Consequently a delayed dispatcher cannot merge two notes into one
        // pending-trigger flag, or compute a new note inside an old remap.
        begin(0x8001b980L);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("MCALL PC[0x8001b9bc]");
        emit("MCALL PC[0x8001b9c0]");
        emit("LDM SP++,R7,PC");
        padTo(0x8001b9bcL);
        word(0x8001c400L); // clock_service
        word(feature("scan_profiler") ? 0x8001a540L : 0x80004c64L);
        finish("clock_scan", 0x8001b9c4L);

        // One accepted, timestamped input from the FIFO. R12 = COUNT at the
        // edge. Neither dispatcher latency nor the 1 ms task changes its
        // interval. Input qualification has already happened in the ISR.
        begin(0x8001c800L);
        emit("STM --SP,R0,R1,R7,LR");
        emit("MOV R7,SP");
        emit("MOV R0,R12");
        emit("MOV R10,0x61e6");
        emit("MOV R11,0x6240");
        emit("LD.W R8,R11[0x0]");
        emit("ST.W R11[0x0],R0");       // last consumed edge
        emit("LD.UB R9,R10[0x6]");
        emit("CP.W R9,0x0");
        emit("BR{eq} 0x8001c884");     // first edge: no period to infer
        emit("SUB R8,R0,R8 << 0x0");   // unsigned wrap-safe cycle interval
        emit("LD.W R11,R11[0x4]");      // cycles/ms, from factory CPU frequency
        emit("MOV R9,R11");
        emit("LSR R9,0x1");
        emit("ADD R8,R9");              // round to nearest ms
        emit("DIVU R8,R8,R11");         // quotient R8, remainder R9
        emit(String.format("MOV R11,0x%x",
             number("clock_max_ms", 2400, 50, 30000)));
        emit("CP.W R8,R11");
        emit("BR{gt} 0x8001c884");
        emit("MOV R1,R8");
        emit("LD.UH R9,R10[0x4]");
        emit("CP.W R9,0x0");
        emit("BR{eq} 0x8001c878");
        emit("MOV R11,R8");
        emit("SUB R11,R11,R9 << 0x0");
        emit("ABS R11");
        emit("LSR R9,0x3");
        emit("SUB R9,-0x2");
        emit("CP.W R11,R9");
        emit("BR{gt} 0x8001c878");
        emit("LD.UB R11,R10[0x6]");
        emit(String.format("CP.W R11,0x%x",
             number("clock_lock_pulses", 5, 2, 32)));
        emit("BR{ge} 0x8001c87c");
        emit("SUB R11,-0x1");
        emit("RJMP 0x8001c87c");
        padTo(0x8001c878L);
        emit("MOV R11,0x1");            // reacquire confidence, NOT divide phase
        emit("RJMP 0x8001c87c");
        padTo(0x8001c87cL);
        emit("ST.B R10[0x6],R11");
        emit("ST.H R10[0x4],R1");
        emit("RJMP 0x8001c894");
        padTo(0x8001c884L);
        emit("MOV R11,0x1");
        emit("ST.B R10[0x6],R11");
        emit("MOV R1,0x0");
        emit("ST.H R10[0x4],R1");
        padTo(0x8001c894L);
        emit("LD.UH R8,R10[0x0]");
        emit("ST.H R10[0x2],R8");       // diagnostic dispatch time only
        emit("MOV R9,0x6233");
        emit("LD.UB R11,R10[0x6]");
        emit(String.format("CP.W R11,0x%x",
             number("clock_lock_pulses", 5, 2, 32)));
        emit("BR{lt} 0x8001c8ac");
        emit("MOV R11,0x1");
        emit("ST.B R9[0x0],R11");       // once acquired, latched until timeout
        padTo(0x8001c8acL);
        emit("LD.UB R11,R9[0x0]");
        emit("MOV R12,0x1");
        emit("CP.W R11,0x0");
        emit("BR{eq} 0x8001c8d0");
        emit("LDDPC R11,0x8001c9e0");
        emit("LD.SH R11,R11[0x2fc]");   // RATE knob itself, not knob-plus-CV
        // The knob reads straight through: zero is /1, the top is /8, so the
        // divider counts up the way the printed scale does.
        // CLAMPED, the way the factory clamps this channel at every single
        // read (0x800079e0 ends by storing the 0x3ff bound over anything
        // larger).  The raw cell exceeds 0x3ff at the top of the knob, which
        // is where /8 now lives, and unclamped the shift would carry it past
        // /8 into a divisor the lock never asked for - the same over-range
        // that used to silence /1 when the knob's ends were the other way
        // round, and that jittered triggers at random on the sampler build.
        // The subtraction survives only as that test: it goes negative
        // exactly when the raw cell is over the bound, and the compact branch
        // conditions live on the far side of it.
        emit("MOV R12,0x3ff");
        emit("SUB R12,R12,R11 << 0x0");
        emit("CP.W R12,0x0");
        emit("BR{ge} 0x8001c8ca");
        emit("MOV R11,0x3ff");
        padTo(0x8001c8caL);
        emit("MOV R12,R11");
        emit("LSR R12,0x7");
        emit("SUB R12,-0x1");           // /1 .. /8
        padTo(0x8001c8d0L);
        // Keep the factory gate-off countdown alive without allowing the
        // internal timer to generate extra steps while an input is present.
        emit("MOV R9,R1");
        emit("CP.W R9,0x0");
        emit("BR{ne} 0x8001c8dc");
        emit(String.format("MOV R9,0x%x",
             number("clock_release_ms", 2600, 100, 32000)));
        padTo(0x8001c8dcL);
        emit("MUL R9,R9,R12");
        emit("MOV R11,R9");
        emit("LSR R11,0x2");
        emit("ADD R9,R11");
        emit("SUB R9,-0x2");
        emit("MOV R11,0x7fff");
        emit("CP.W R9,R11");
        emit("BR{le} 0x8001c8f8");
        emit("MOV R9,R11");
        padTo(0x8001c8f8L);
        emit("LDDPC R11,0x8001c9e0");
        emit("ST.H R11[0x38e],R9");
        emit("LD.UB R11,R10[0x7]");
        emit("SUB R11,-0x1");
        emit("CP.W R11,R12");
        emit("BR{ge} 0x8001c914");
        emit("ST.B R10[0x7],R11");
        emit("LDM SP++,R0,R1,R7,PC");
        padTo(0x8001c914L);
        emit("MOV R11,0x0");
        emit("ST.B R10[0x7],R11");
        emit("MOV R11,0x6237");
        emit("MOV R12,0x1");
        emit("ST.B R11[0x0],R12");       // even a rest/tie gets its own pitch scan
        emit("MOV R12,0xffff");
        emit("MCALL PC[0x8001c9e4]");
        emit("LDM SP++,R0,R1,R7,PC");
        padTo(0x8001c9e0L);
        word(0x00003560L);
        word(0x8000210cL);
        finish("clock_pulse", 0x8001ca00L);

        // The rate knob cannot reload the countdown while an external input
        // owns timing, including acquisition (not just a fully locked rate).
        begin(0x8001b870L);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("MOV R12,0x6236");
        emit("LD.UB R12,R12[0x0]");
        emit("CP.W R12,0x0");
        emit("BR{ne} 0x8001b884");
        emit("ST.H R9[0x38e],R8");
        padTo(0x8001b884L);
        emit("LDM SP++,R7,PC");
        padTo(0x8001b890L);
        word(0x8001b870L);
        finish("clock_tempo", 0x8001b894L);

        // GPIO capture, called inside the factory ISR's existing frame.
        // The factory selected RISING-only interrupts (mode 1); the clock
        // build explicitly selects PIN CHANGE (mode 0) below. IFR is cleared
        // BEFORE sampling PVR, so a later transition remains pending.
        //
        // A high consumes the low interval even when rejected. No count of
        // rejected events, elapsed period or missing sample can bypass this
        // check. Only the interrupt writes the producer index and timestamps.
        // There are no DAC calls, event-queue calls or waits in this path.
        begin(0x8001c200L);
        emit("MOV R8,-0xf000");
        emit("LD.W R9,R8[0xd0]");
        emit("BFEXTU R9,R9,0x5,0x1");
        emit("CP.W R9,0x0");
        emit("BR{eq} 0x8001c2f0");
        emit("MOV R9,0x20");
        emit("ST.W R8[0xd8],R9");
        emit("LD.W R9,R8[0x60]");
        emit("MFSR R12,COUNT");
        emit("BFEXTU R9,R9,0x5,0x1");
        emit("MOV R10,0x6234");
        emit("CP.W R9,0x0");
        emit("BR{ne} 0x8001c244");
        emit("MOV R9,0x1");
        emit("ST.B R10[-0x2],R9");
        emit("ST.W R10[0x4],R12");      // a new low run, even after a hidden bounce
        emit("RJMP 0x8001c2f0");
        padTo(0x8001c244L);
        emit("LD.UB R9,R10[-0x2]");
        emit("MOV R8,0x0");
        emit("ST.B R10[-0x2],R8");      // spend/reset BEFORE either rejection
        emit("CP.W R9,0x0");
        emit("BR{eq} 0x8001c2f0");
        emit("CP.W R9,0x2");
        emit("BR{eq} 0x8001c264");      // a continuous low already timed and banked
        emit("LD.W R8,R10[0x4]");
        emit("SUB R8,R12,R8 << 0x0");
        emit("LD.W R9,R10[0x14]");
        emit("CP.W R8,R9");
        emit("BR{ls} 0x8001c2f0");      // <=250 us at default: chatter
        padTo(0x8001c264L);
        emit("LD.UB R9,R10[0x2]");
        emit("CP.W R9,0x0");
        emit("BR{eq} 0x8001c290");
        emit("LD.W R8,R10[0x8]");
        emit("SUB R8,R12,R8 << 0x0");
        emit("LD.W R9,R10[0x18]");
        emit("SUB R9,0x1");
        emit("CP.W R8,R9");
        emit("BR{ls} 0x8001c2f0");      // unsigned refractory, including COUNT wrap
        padTo(0x8001c290L);
        emit("ST.W R10[0x8],R12");      // accepted physical edge, not dispatch time
        // And the millisecond count beside it.  The release below is timed
        // from the 1 ms task rather than from COUNT: COUNT is scaled by the
        // CPU-frequency word at RAM 0x29cc, which says 25 MHz, and on the
        // instrument a nominal 2600 ms release was expiring in well under a
        // second - the ratio a wrong frequency gives.  The 1 ms task is
        // demonstrably right, because every tempo and hold time on the panel
        // is, so the release counts its ticks and cannot inherit that error.
        // The halfword wraps every 65 s; the subtraction is masked.
        emit("MOV R9,0x61e6");
        emit("LD.UH R9,R9[0x0]");
        emit("MOV R11,0x62f6");
        emit("ST.H R11[0x0],R9");
        emit("MOV R9,0x1");
        emit("ST.B R10[0x2],R9");
        emit("LD.UB R8,R10[0x0]");
        emit("LD.UB R9,R10[0x1]");
        emit("MOV R11,R8");
        emit("SUB R11,-0x1");
        emit("ANDL R11,0x1f");
        emit("CP.W R11,R9");
        emit("BR{eq} 0x8001c2d4");
        emit("MOV R9,0x6260");
        emit("ADD R8,R9,R8 << 0x2");
        emit("ST.W R8[0x0],R12");
        emit("ST.B R10[0x0],R11");      // publish only after the entry is complete
        emit("RJMP 0x8001c2f0");
        padTo(0x8001c2d4L);
        // Full: drop newest, preserve unread entries, expose an overrun
        // counter. Never spin in an interrupt or overwrite the consumer.
        emit("LD.UH R8,R10[0x24]");
        emit("CP.W R8,0xffff");
        emit("BR{eq} 0x8001c2f0");
        emit("SUB R8,-0x1");
        emit("ST.H R10[0x24],R8");
        padTo(0x8001c2f0L);
        emit("MOV PC,LR");
        finish("clock_capture", 0x8001c300L);

        // A low can last minutes while disconnected. Bank its qualification
        // before the 32-bit COUNT wraps, without using samples to count short
        // low phases. Pending GPIO transitions invalidate this observation;
        // only the ISR starts/restarts a low run and every high spends it.
        begin(0x8001ca00L);
        emit("STM --SP,R0,R7,LR");
        emit("MOV R7,SP");
        emit("MFSR R0,SR");
        emit("SSRF 0x10");
        emit("MOV R10,0x6234");
        emit("LD.UB R8,R10[-0x2]");
        emit("CP.W R8,0x1");
        emit("BR{ne} 0x8001ca70");
        emit("MOV R11,-0xf000");
        emit("LD.W R8,R11[0x60]");
        emit("BFEXTU R8,R8,0x5,0x1");
        emit("CP.W R8,0x0");
        emit("BR{ne} 0x8001ca70");
        emit("LD.W R8,R10[0x4]");
        emit("MFSR R9,COUNT");
        emit("SUB R8,R9,R8 << 0x0");
        emit("LD.W R9,R10[0x14]");
        emit("CP.W R8,R9");
        emit("BR{ls} 0x8001ca70");
        emit("LD.W R8,R11[0xd0]");
        emit("BFEXTU R8,R8,0x5,0x1");
        emit("CP.W R8,0x0");
        emit("BR{ne} 0x8001ca70");
        emit("MOV R8,0x2");
        emit("ST.B R10[-0x2],R8");
        padTo(0x8001ca70L);
        emit("MTSR SR,R0");
        emit("LDM SP++,R0,R7,PC");
        finish("clock_low_age", 0x8001ca80L);

        // A delayed pitch scan can emit a spike just as the factory countdown
        // reaches its gate-off threshold. Protect the actual output's age,
        // not the input/dispatch time. Retry the threshold on the next tick
        // instead of losing the eventual gate-off. The sequencer's tie rule
        // still has priority, and the attack-drop itself is untouched - four
        // milliseconds at the default trigger_spike_units of 5, which this
        // guard's four-millisecond window exactly covers.
        begin(0x8001ca80L);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        if (block("seq_gate")) {
            emit("MCALL PC[0x8001b54c]");
        } else {
            emit("MOV R8,0x3");
        }
        emit("CP.W R8,0x3");
        emit("BR{ne} 0x8001cb10");
        emit("MOV R10,0x6234");
        emit("LD.UB R9,R10[0x2]");
        emit("CP.W R9,0x0");
        emit("BR{eq} 0x8001cb10");
        emit("LD.UB R9,R10[0x26]");
        emit("CP.W R9,0x0");
        emit("BR{eq} 0x8001cb10");
        emit("LDDPC R11,0x8001cb18");
        emit("LD.SH R9,R11[0x38e]");
        emit("CP.W R9,0x3");
        emit("BR{ne} 0x8001cb10");
        emit("LD.W R9,R10[0x20]");
        emit("MFSR R12,COUNT");
        emit("SUB R9,R12,R9 << 0x0");
        emit("LD.W R12,R10[0x10]");
        emit("LSL R12,0x2");            // the guard window: see clock_service
        emit("SUB R12,0x1");
        emit("CP.W R9,R12");
        emit("BR{hi} 0x8001cb10");
        emit("MOV R9,0x4");
        emit("ST.H R11[0x38e],R9");
        emit("MOV R8,-0x8000");
        padTo(0x8001cb10L);
        emit("LDM SP++,R7,PC");
        padTo(0x8001cb18L);
        word(0x00003560L);
        word(0x8001ca80L);
        finish("clock_attack_guard", 0x8001cb20L);

        // ---------------------------------------------------------------
        // Persistence v2. Only musical data is serialized, never mode,
        // touch history, clock state or knob pickup state. A verified body
        // is committed by programming its still-erased marker word LAST.
        // Keep the newest valid page out of every retry lap. Completed edit
        // gestures commit immediately, without an idle/clock/arp gate. A
        // separate musical snapshot excludes other edits still in progress.
        //
        // Header: marker[4], version[2]=2, length[2]=204, generation[4],
        // CRC32[4]. Payload: presets[8], count[1], reserved[3], pitches[128],
        // keys[64]. Unused steps, reserved bytes and alignment padding are
        // zero. CRC-32/ISO-HDLC covers header bytes 4..11 then the payload.
        // The 224-byte staging buffer and both writes are 8-byte aligned.
        // ---------------------------------------------------------------

        // Incremental reflected CRC32. R12 = unfinalized CRC, R11 = bytes,
        // R10 = length; returns CRC in R12. Polynomial 0xedb88320.
        begin(0x8001cc00L);
        emit("STM --SP,R0,R7,LR");
        emit("MOV R7,SP");
        emit("LDDPC R8,0x8001cc7c");
        padTo(0x8001cc10L);
        emit("CP.W R10,0x0");
        emit("BR{eq} 0x8001cc60");
        emit("LD.UB R9,R11[0x0]");
        emit("EOR R12,R9");
        emit("SUB R11,-0x1");
        emit("MOV R0,0x8");
        padTo(0x8001cc20L);
        emit("BFEXTU R9,R12,0x0,0x1");
        emit("LSR R12,0x1");
        emit("CP.W R9,0x0");
        emit("BR{eq} 0x8001cc30");
        emit("EOR R12,R8");
        padTo(0x8001cc30L);
        emit("SUB R0,0x1");
        emit("BR{ne} 0x8001cc20");
        emit("SUB R10,0x1");
        emit("RJMP 0x8001cc10");
        padTo(0x8001cc60L);
        emit("LDM SP++,R0,R7,PC");
        padTo(0x8001cc7cL);
        word(0xedb88320L);
        finish("persist_crc", 0x8001cc80L);

        // R12 = record address; returns the finalized CRC of metadata/data.
        begin(0x8001cc80L);
        emit("STM --SP,R0,R7,LR");
        emit("MOV R7,SP");
        emit("MOV R0,R12");
        emit("MOV R11,R0");
        emit("SUB R11,-0x4");
        emit("MOV R10,0x8");
        emit("MOV R12,-0x1");
        emit("MCALL PC[0x8001ccdc]");
        emit("MOV R11,R0");
        emit("SUB R11,-0x10");
        emit("MOV R10,0xcc");
        emit("MCALL PC[0x8001ccdc]");
        emit("MOV R8,-0x1");
        emit("EOR R12,R8");
        emit("LDM SP++,R0,R7,PC");
        padTo(0x8001ccdcL);
        word(0x8001cc00L);
        finish("persist_record_crc", 0x8001cce0L);

        // Validate commit, version, CRC and the bounds needed by every
        // consumer. R12 = record; returns generation, or zero if invalid.
        // V1 raw-RAM records are deliberately not accepted as V2 data.
        begin(0x8001cce0L);
        emit("STM --SP,R0,R1,R7,LR");
        emit("MOV R7,SP");
        emit("MOV R0,R12");
        emit("LD.W R8,R0[0x0]");
        emit("LDDPC R9,0x8001cdf8");
        emit("CP.W R8,R9");
        emit("BR{ne} 0x8001cde0");
        emit("LD.UH R8,R0[0x4]");
        emit("CP.W R8,0x2");
        emit("BR{ne} 0x8001cde0");
        emit("LD.UH R8,R0[0x6]");
        emit("CP.W R8,0xcc");
        emit("BR{ne} 0x8001cde0");
        emit("LD.W R8,R0[0x8]");
        emit("CP.W R8,0x0");
        emit("BR{eq} 0x8001cde0");
        emit("MCALL PC[0x8001cdfc]");
        emit("LD.W R8,R0[0xc]");
        emit("CP.W R12,R8");
        emit("BR{ne} 0x8001cde0");
        emit("MOV R1,0x0");
        padTo(0x8001cd30L);
        emit("ADD R8,R0,R1 << 0x1");
        emit("LD.UH R8,R8[0x10]");
        emit("CP.W R8,0x3ff");
        emit("BR{hi} 0x8001cde0");
        emit("SUB R1,-0x1");
        emit("CP.W R1,0x4");
        emit("BR{lt} 0x8001cd30");
        emit("LD.UB R10,R0[0x18]");
        emit("CP.W R10,0x40");
        emit("BR{hi} 0x8001cde0");
        emit("MOV R1,0x0");
        padTo(0x8001cd60L);
        emit("CP.W R1,R10");
        emit("BR{ge} 0x8001cdd8");
        emit("ADD R8,R0,R1 << 0x1");
        emit("LD.UH R9,R8[0x1c]");
        emit("CP.W R9,0xfff");
        emit("BR{ls} 0x8001cd90");
        emit("CP.W R9,0x7ffe");
        emit("BR{eq} 0x8001cdb0");
        emit("CP.W R9,0x7fff");
        emit("BR{ne} 0x8001cde0");
        emit("RJMP 0x8001cdb0");
        padTo(0x8001cd90L);
        emit("ADD R8,R0,R1 << 0x0");
        emit("LD.UB R9,R8[0x9c]");
        emit("CP.W R9,0x1d");
        emit("BR{ge} 0x8001cde0");
        padTo(0x8001cdb0L);
        emit("SUB R1,-0x1");
        emit("RJMP 0x8001cd60");
        padTo(0x8001cdd8L);
        emit("LD.W R12,R0[0x8]");
        emit("RJMP 0x8001cde4");
        padTo(0x8001cde0L);
        emit("MOV R12,0x0");
        padTo(0x8001cde4L);
        emit("LDM SP++,R0,R1,R7,PC");
        padTo(0x8001cdf8L);
        word(0x32313850L);
        word(0x8001cc80L);
        finish("persist_valid", 0x8001ce00L);

        // Return newest address R12 (zero if empty), index R11 (-1 if
        // empty), generation R10. Serial-number arithmetic handles wrap;
        // successful generations in this eight-page ring stay close.
        begin(0x8001ce00L);
        emit("STM --SP,R0,R1,R2,R3,R7,LR");
        emit("MOV R7,SP");
        emit("MOV R0,0x0");
        emit("MOV R1,0x0");
        emit("MOV R2,0x0");
        emit("MOV R3,-0x1");
        padTo(0x8001ce10L);
        emit(String.format("CP.W R0,0x%x", number("persist_page_count", 8, 2, 8)));
        emit("BR{ge} 0x8001ce60");
        emit("MOV R8,R0");
        emit("LSL R8,0x9");
        emit("LDDPC R9,0x8001ce78");
        emit("ADD R8,R9");
        emit("MOV R12,R8");
        emit("ST.W --SP,R8");
        emit("MCALL PC[0x8001ce7c]");
        emit("LD.W R8,SP++");
        emit("CP.W R12,0x0");
        emit("BR{eq} 0x8001ce54");
        emit("CP.W R1,0x0");
        emit("BR{eq} 0x8001ce48");
        emit("SUB R9,R12,R2 << 0x0");
        emit("CP.W R9,0x0");
        emit("BR{le} 0x8001ce54");
        padTo(0x8001ce48L);
        emit("MOV R2,R12");
        emit("MOV R1,R8");
        emit("MOV R3,R0");
        padTo(0x8001ce54L);
        emit("SUB R0,-0x1");
        emit("RJMP 0x8001ce10");
        padTo(0x8001ce60L);
        emit("MOV R12,R1");
        emit("MOV R11,R3");
        emit("MOV R10,R2");
        emit("LDM SP++,R0,R1,R2,R3,R7,PC");
        padTo(0x8001ce78L);
        word(0x8003e000L);
        word(0x8001cce0L);
        finish("persist_newest", 0x8001ce80L);

        // Canonical record from COMPLETED edits at 0x6400, generation in R12.
        // A preset release cannot accidentally commit an unfinished take,
        // and leaving record cannot commit a different pad still held down.
        begin(0x8001ce80L);
        emit("STM --SP,R0,R1,R2,R7,LR");
        emit("MOV R7,SP");
        emit("MOV R0,R12");
        emit("MOV R8,0x6300");
        emit("MOV R9,0x0");
        emit("MOV R10,0x38");
        padTo(0x8001cea0L);
        emit("ST.W R8[0x0],R9");
        emit("SUB R8,-0x4");
        emit("SUB R10,0x1");
        emit("BR{ne} 0x8001cea0");
        emit("MOV R8,0x6300");
        emit("MOV R9,-0x1");
        emit("ST.W R8[0x0],R9");       // uncommitted
        emit("MOV R9,0x2");
        emit("ST.H R8[0x4],R9");
        emit("MOV R9,0xcc");
        emit("ST.H R8[0x6],R9");
        emit("ST.W R8[0x8],R0");
        emit("MOV R1,0x0");
        padTo(0x8001ced0L);
        emit("MOV R8,0x6400");
        emit("ADD R8,R8,R1 << 0x0");
        emit("LD.UB R9,R8[0x0]");
        emit("MOV R8,0x6310");
        emit("ADD R8,R8,R1 << 0x0");
        emit("ST.B R8[0x0],R9");
        emit("SUB R1,-0x1");
        emit("CP.W R1,0x8");
        emit("BR{lt} 0x8001ced0");
        emit("MOV R8,0x6408");
        emit("LD.UB R2,R8[0x0]");
        emit("MOV R9,0x40");
        emit("CP.W R2,R9");
        emit("BR{ls} 0x8001cf00");
        emit("MOV R2,R9");
        padTo(0x8001cf00L);
        emit("MOV R8,0x6318");
        emit("ST.B R8[0x0],R2");
        emit("MOV R1,0x0");
        padTo(0x8001cf10L);
        emit("CP.W R1,R2");
        emit("BR{ge} 0x8001cf90");
        emit("MOV R8,0x640c");
        emit("ADD R8,R8,R1 << 0x1");
        emit("LD.UH R9,R8[0x0]");
        emit("MOV R8,0x631c");
        emit("ADD R8,R8,R1 << 0x1");
        emit("ST.H R8[0x0],R9");
        emit("CP.W R9,0x7ffe");
        emit("BR{ge} 0x8001cf70");      // rest/tie: the zero key stays zero
        emit("MOV R8,0x648c");
        emit("ADD R8,R8,R1 << 0x0");
        emit("LD.UB R9,R8[0x0]");
        emit("MOV R8,0x639c");
        emit("ADD R8,R8,R1 << 0x0");
        emit("ST.B R8[0x0],R9");
        padTo(0x8001cf70L);
        emit("SUB R1,-0x1");
        emit("RJMP 0x8001cf10");
        padTo(0x8001cf90L);
        emit("MOV R12,0x6300");
        emit("MCALL PC[0x8001cfbc]");
        emit("MOV R8,0x6300");
        emit("ST.W R8[0xc],R12");
        emit("LDM SP++,R0,R1,R2,R7,PC");
        padTo(0x8001cfbcL);
        word(0x8001cc80L);
        finish("persist_pack", 0x8001cfc0L);

        // Startup restores ONLY musical data. The boot wrapper clears
        // transient state first, including after a warm reset.
        begin(0x8001cfc0L);
        emit("STM --SP,R0,R1,R7,LR");
        emit("MOV R7,SP");
        emit("MCALL PC[0x8001d07c]");
        emit("CP.W R12,0x0");
        emit("BR{eq} 0x8001d070");
        emit("MOV R0,R12");
        emit("MOV R8,0x62e0");
        emit("ST.B R8[0x1],R11");
        emit("ST.W R8[0x4],R10");
        emit("MOV R1,0x0");
        padTo(0x8001cfe0L);
        emit("ADD R8,R0,R1 << 0x0");
        emit("LD.UB R9,R8[0x10]");
        emit("MOV R8,0x613a");
        emit("ADD R8,R8,R1 << 0x0");
        emit("ST.B R8[0x0],R9");
        emit("SUB R1,-0x1");
        emit("CP.W R1,0x8");
        emit("BR{lt} 0x8001cfe0");
        emit("LD.UB R9,R0[0x18]");
        emit("MOV R8,0x61e0");
        emit("ST.B R8[0x0],R9");
        emit("MOV R1,0x0");
        padTo(0x8001d010L);
        emit("ADD R8,R0,R1 << 0x1");
        emit("LD.UH R9,R8[0x1c]");
        emit("MOV R8,0x6160");
        emit("ADD R8,R8,R1 << 0x1");
        emit("ST.H R8[0x0],R9");
        emit("ADD R8,R0,R1 << 0x0");
        emit("LD.UB R9,R8[0x9c]");
        emit("MOV R8,0x61ee");
        emit("ADD R8,R8,R1 << 0x0");
        emit("ST.B R8[0x0],R9");
        emit("SUB R1,-0x1");
        emit("CP.W R1,0x40");
        emit("BR{lt} 0x8001d010");
        padTo(0x8001d070L);
        emit("LDM SP++,R0,R1,R7,PC");
        padTo(0x8001d07cL);
        word(0x8001ce00L);
        finish("persist_load", 0x8001d080L);

        // R12 = record. Compare only its canonical musical payload with
        // staging; return zero when equal. Pack must precede this call.
        begin(0x8001d080L);
        emit("MOV R9,0x0");
        padTo(0x8001d084L);
        emit("CP.W R9,0xcc");
        emit("BR{ge} 0x8001d0b0");
        emit("ADD R8,R12,R9 << 0x0");
        emit("LD.UB R10,R8[0x10]");
        emit("MOV R8,0x6310");
        emit("ADD R8,R8,R9 << 0x0");
        emit("LD.UB R11,R8[0x0]");
        emit("CP.W R10,R11");
        emit("BR{ne} 0x8001d0b8");
        emit("SUB R9,-0x1");
        emit("RJMP 0x8001d084");
        padTo(0x8001d0b0L);
        emit("MOV R12,0x0");
        emit("MOV PC,LR");
        padTo(0x8001d0b8L);
        emit("MOV R12,0x1");
        emit("MOV PC,LR");
        finish("persist_same", 0x8001d0c0L);

        // Byte-for-byte readback of the WHOLE expected record, including
        // the generation, marker and padding, before and after commit.
        begin(0x8001d0c0L);
        emit("MOV R9,0x0");
        padTo(0x8001d0c4L);
        emit("CP.W R9,0xe0");
        emit("BR{ge} 0x8001d0f0");
        emit("ADD R8,R12,R9 << 0x0");
        emit("LD.UB R10,R8[0x0]");
        emit("MOV R8,0x6300");
        emit("ADD R8,R8,R9 << 0x0");
        emit("LD.UB R11,R8[0x0]");
        emit("CP.W R10,R11");
        emit("BR{ne} 0x8001d0f8");
        emit("SUB R9,-0x1");
        emit("RJMP 0x8001d0c4");
        padTo(0x8001d0f0L);
        emit("MOV R12,0x0");
        emit("MOV PC,LR");
        padTo(0x8001d0f8L);
        emit("MOV R12,0x1");
        emit("MOV PC,LR");
        finish("persist_verify", 0x8001d100L);

        // Called after a changed, completed edit. Return zero on success/no change,
        // one on failure. Request states: 0 clean, 1 pending, 2 failed.
        // Failure is latched until another edit, not retried every scan.
        begin(0x8001d100L);
        emit("STM --SP,R0,R1,R2,R3,R4,R7,LR");
        emit("MOV R7,SP");
        emit("MCALL PC[0x8001d260]");
        emit("MOV R4,R12");             // preserve the last good address
        emit("MOV R2,R11");
        emit("MOV R3,R10");
        emit("SUB R3,-0x1");
        emit("CP.W R3,0x0");
        emit("BR{ne} 0x8001d120");
        emit("MOV R3,0x1");             // zero is the invalid-generation sentinel
        padTo(0x8001d120L);
        emit("MOV R12,R3");
        emit("MCALL PC[0x8001d264]");
        emit("CP.W R4,0x0");
        emit("BR{eq} 0x8001d150");
        emit("MOV R12,R4");
        emit("MCALL PC[0x8001d268]");
        emit("CP.W R12,0x0");
        emit("BR{eq} 0x8001d248");
        padTo(0x8001d150L);
        emit(String.format("MOV R1,0x%x", number("persist_page_count", 8, 2, 8)));
        emit("CP.W R4,0x0");
        emit("BR{eq} 0x8001d160");
        emit("SUB R1,0x1");             // NEVER erase the newest valid record
        padTo(0x8001d160L);
        emit("CP.W R1,0x0");
        emit("BR{eq} 0x8001d23c");
        emit("SUB R1,0x1");
        emit("SUB R2,-0x1");
        emit(String.format("CP.W R2,0x%x", number("persist_page_count", 8, 2, 8)));
        emit("BR{lt} 0x8001d180");
        emit("MOV R2,0x0");
        padTo(0x8001d180L);
        emit("MOV R0,R2");
        emit("LSL R0,0x9");
        emit("LDDPC R9,0x8001d270");
        emit("ADD R0,R9");
        emit("MOV R8,0x6300");
        emit("MOV R9,-0x1");
        emit("ST.W R8[0x0],R9");        // reset marker after a failed commit too
        emit("MOV R12,R0");
        emit("MOV R11,0x6300");
        emit("MOV R10,0xe0");
        emit("MOV R9,0x1");             // erase, then write UNCOMMITTED body
        emit("MCALL PC[0x8001d278]");
        emit("MOV R12,R0");
        emit("MCALL PC[0x8001d274]");
        emit("CP.W R12,0x0");
        emit("BR{ne} 0x8001d160");
        emit("MOV R8,0x6300");
        emit("LDDPC R9,0x8001d26c");
        emit("ST.W R8[0x0],R9");
        // Same page-aligned simple driver path, length eight, no erase.
        // Only the erased marker word CHANGES; version/length and all words
        // the driver preserves from the page remain bit-for-bit identical.
        // This is the EEPROM-emulation procedure in UC3B section 14.4.7.
        emit("MOV R12,R0");
        emit("MOV R11,0x6300");
        emit("MOV R10,0x8");
        emit("MOV R9,0x0");
        emit("MCALL PC[0x8001d278]");
        emit("MOV R12,R0");
        emit("MCALL PC[0x8001d274]");
        emit("CP.W R12,0x0");
        emit("BR{ne} 0x8001d160");
        emit("MOV R12,R0");
        emit("MCALL PC[0x8001d27c]");
        emit("CP.W R12,0x0");
        emit("BR{eq} 0x8001d160");
        emit("MOV R8,0x62e0");
        emit("ST.B R8[0x1],R2");
        emit("ST.W R8[0x4],R3");
        emit("RJMP 0x8001d248");
        padTo(0x8001d23cL);
        emit("MOV R12,0x1");
        emit("MOV R8,0x62e0");
        emit("MOV R9,0x2");
        emit("ST.B R8[0x0],R9");
        emit("RJMP 0x8001d258");
        padTo(0x8001d248L);
        emit("MOV R12,0x0");
        emit("MOV R8,0x62e0");
        emit("ST.B R8[0x0],R12");
        padTo(0x8001d258L);
        emit("LDM SP++,R0,R1,R2,R3,R4,R7,PC");
        padTo(0x8001d260L);
        word(0x8001ce00L);
        word(0x8001ce80L);
        word(0x8001d080L);
        word(0x32313850L);
        word(0x8003e000L);
        word(0x8001d0c0L);
        word(0x800108fcL);
        word(0x8001cce0L);
        finish("persist_save", 0x8001d280L);

        // Capture only completed musical edits into 0x6400..0x64cb.
        // R12 mask: bits 0..3 = released preset pads, bit 4 = sequence.
        // Return R12 = changed. Unchanged gestures (including empty clear)
        // never write even on a blank ring. Mask 0x1f initializes every
        // snapshot byte at boot; no snapshot survives a warm reset.
        begin(0x8001d280L);
        emit("STM --SP,R0,R1,R2,R3,R4,R7,LR");
        emit("MOV R7,SP");
        emit("MOV R2,R12");
        emit("MOV R0,0x0");
        emit("MOV R1,0x0");
        emit("MOV R3,0x6400");
        padTo(0x8001d294L);
        emit("CP.W R1,0x4");
        emit("BR{eq} 0x8001d2e0");
        emit("MOV R8,R2");
        emit("ANDL R8,0x1");
        emit("CP.W R8,0x0");
        emit("BR{eq} 0x8001d2cc");
        emit("MOV R8,0x613a");
        emit("ADD R8,R8,R1 << 0x1");
        emit("LD.UH R9,R8[0x0]");
        emit("ADD R10,R3,R1 << 0x1");
        emit("LD.UH R11,R10[0x0]");
        emit("CP.W R9,R11");
        emit("BR{eq} 0x8001d2c0");
        emit("MOV R0,0x1");
        padTo(0x8001d2c0L);
        emit("ST.H R10[0x0],R9");
        padTo(0x8001d2ccL);
        emit("LSR R2,0x1");
        emit("SUB R1,-0x1");
        emit("RJMP 0x8001d294");
        padTo(0x8001d2e0L);
        emit("CP.W R2,0x0");
        emit("BR{eq} 0x8001d3d8");
        emit("MOV R8,0x61e0");
        emit("LD.UB R4,R8[0x0]");
        emit("CP.W R4,0x40");
        emit("BR{ls} 0x8001d2f8");
        emit("MOV R4,0x40");
        padTo(0x8001d2f8L);
        emit("LD.UB R9,R3[0x8]");
        emit("CP.W R4,R9");
        emit("BR{eq} 0x8001d308");
        emit("MOV R0,0x1");
        padTo(0x8001d308L);
        emit("ST.B R3[0x8],R4");
        emit("MOV R8,0x0");
        emit("ST.B R3[0x9],R8");
        emit("ST.H R3[0xa],R8");
        emit("MOV R1,0x0");
        padTo(0x8001d318L);
        emit("CP.W R1,0x40");
        emit("BR{ge} 0x8001d3d8");
        emit("MOV R9,0x0");
        emit("MOV R11,0x0");
        emit("CP.W R1,R4");
        emit("BR{ge} 0x8001d350");
        emit("MOV R8,0x6160");
        emit("ADD R8,R8,R1 << 0x1");
        emit("LD.UH R9,R8[0x0]");
        emit("CP.W R9,0x7ffe");
        emit("BR{ge} 0x8001d350");
        emit("MOV R8,0x61ee");
        emit("ADD R8,R8,R1 << 0x0");
        emit("LD.UB R11,R8[0x0]");
        padTo(0x8001d350L);
        emit("ADD R10,R3,R1 << 0x1");
        emit("LD.UH R8,R10[0xc]");
        emit("CP.W R8,R9");
        emit("BR{eq} 0x8001d366");
        emit("MOV R0,0x1");
        padTo(0x8001d366L);
        emit("ST.H R10[0xc],R9");
        emit("ADD R10,R3,R1 << 0x0");
        emit("LD.UB R8,R10[0x8c]");
        emit("CP.W R8,R11");
        emit("BR{eq} 0x8001d382");
        emit("MOV R0,0x1");
        padTo(0x8001d382L);
        emit("ST.B R10[0x8c],R11");
        emit("SUB R1,-0x1");
        emit("RJMP 0x8001d318");
        padTo(0x8001d3d8L);
        emit("MOV R12,R0");
        emit("LDM SP++,R0,R1,R2,R3,R4,R7,PC");
        finish("persist_capture", 0x8001d400L);

        // Watch completion of recording/preset edits and explicit CLEAR.
        // Preview is logically still WRITE, including across multiple scans:
        // ending it naturally resumes WRITE; an explicit STOP finishes it.
        // A preset's edit flag survives the intermediate
        // touched-but-not-held level: commit only once that pad is released.
        // Changed completed gestures commit in this scan, regardless of mode,
        // held controls, clock input, gate state or CPU timebase.
        begin(0x8001d400L);
        emit("STM --SP,R0,R1,R2,R3,R7,LR");
        emit("MOV R7,SP");
        emit("MOV R0,0x62e0");
        emit("MOV R2,0x0");
        emit("MOV R8,0x6154");
        emit("LD.UB R9,R8[0x4]");
        emit("MOV R8,0x62fe");
        emit("LD.UB R8,R8[0x0]");
        emit("CP.W R8,0x0");
        emit("BR{eq} 0x8001d424");
        emit("MOV R9,0x1");             // preview keeps the WRITE session open
        padTo(0x8001d424L);
        emit("LD.UB R10,R0[0x18]");
        emit("ST.B R0[0x18],R9");
        emit("CP.W R10,0x1");
        emit("BR{ne} 0x8001d440");
        emit("CP.W R9,0x1");
        emit("BR{eq} 0x8001d440");
        emit("MOV R8,0x10");
        emit("OR R2,R8");
        padTo(0x8001d440L);
        // Length reaching zero is not a CLEAR gesture: backspace can do
        // that while the take is still being edited. Consume the event
        // latched by seq_command instead, including CLEAR of an empty edit.
        emit("MOV R8,0x62ff");
        emit("LD.UB R9,R8[0x0]");
        emit("MOV R10,0x0");
        emit("ST.B R8[0x0],R10");
        emit("CP.W R9,0x0");
        emit("BR{eq} 0x8001d470");
        emit("MOV R8,0x10");
        emit("OR R2,R8");
        padTo(0x8001d470L);
        emit("MOV R1,0x0");
        emit("MOV R3,0x1");
        padTo(0x8001d474L);
        emit("CP.W R1,0x4");
        emit("BR{ge} 0x8001d4c0");
        emit("MOV R8,0x614a");
        emit("ADD R8,R8,R1 << 0x0");
        emit("LD.UB R9,R8[0x0]");
        emit("ADD R8,R0,R1 << 0x0");
        emit("LD.UB R10,R8[0x19]");
        emit("OR R9,R10");
        emit("MOV R10,0x46f0");
        emit("ADD R10,R10,R1 << 0x0");
        emit("LD.UB R10,R10[0x0]");
        emit("CP.W R10,0x0");
        emit("BR{ne} 0x8001d4ac");
        emit("CP.W R9,0x0");
        emit("BR{eq} 0x8001d4ac");
        emit("OR R2,R3");
        emit("MOV R9,0x0");
        padTo(0x8001d4acL);
        emit("ST.B R8[0x19],R9");
        emit("SUB R1,-0x1");
        emit("LSL R3,0x1");
        emit("RJMP 0x8001d474");
        padTo(0x8001d4c0L);
        emit("CP.W R2,0x0");
        emit("BR{eq} 0x8001d4e0");
        emit("MOV R12,R2");
        emit("MCALL PC[0x8001d518]");
        emit("CP.W R12,0x0");
        emit("BR{eq} 0x8001d4e0");
        emit("MOV R8,0x1");
        emit("ST.B R0[0x0],R8");
        emit("MCALL PC[0x8001d51c]");
        padTo(0x8001d4e0L);
        emit("LDM SP++,R0,R1,R2,R3,R7,PC");
        padTo(0x8001d518L);
        word(0x8001d280L);
        word(0x8001d100L);
        finish("persist_tick", 0x8001d520L);

        begin(0x8001d520L);
        emit("STM --SP,R7,LR");
        emit("MCALL PC[0x8001d534]");
        if (block("seq_chord")) emit("MCALL PC[0x8001d53c]");
        emit("MCALL PC[0x8001d538]");
        emit("LDM SP++,R7,PC");
        padTo(0x8001d534L);
        word(0x8001ae1cL);
        word(0x8001d400L);
        word(0x8001b180L);
        finish("persist_scan_shim", 0x8001d540L);

        // Startup before GPIO interrupts are installed, not a late restore
        // in the middle of the pitch scan. SRAM can survive reset: always
        // reset musical/runtime state and reload, even if the init marker
        // matches. Do not touch the factory's stored strip-mode setting.
        begin(0x8001d540L);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("MCALL PC[0x8001d5b0]");
        emit("MOV R8,0x0");
        emit("MOV R10,0x613a");
        emit("MOV R9,0x7b");
        padTo(0x8001d558L);
        emit("ST.H R10[0x0],R8");
        emit("SUB R10,-0x2");
        emit("SUB R9,0x1");
        emit("BR{ge} 0x8001d558");
        emit("MOV R10,0x62e0");
        emit("MOV R9,0xf");
        padTo(0x8001d56cL);
        emit("ST.H R10[0x0],R8");
        emit("SUB R10,-0x2");
        emit("SUB R9,0x1");
        emit("BR{ge} 0x8001d56c");
        emit("MOV R10,0x62e0");
        emit("MOV R9,-0x1");
        emit("ST.B R10[0x1],R9");
        emit("MCALL PC[0x8001d5b4]");
        emit("MOV R12,0x1f");
        emit("MCALL PC[0x8001d5bc]");   // initialize completed-edit snapshot
        emit("MOV R10,0x62e0");
        emit("MOV R9,0x1");
        emit("ST.B R10[0x1d],R9");
        emit("MCALL PC[0x8001d5b8]");
        emit("LDM SP++,R7,PC");
        padTo(0x8001d5b0L);
        word(block("seq_restart_init") ? 0x8001df80L : 0x8001ab60L);
        word(0x8001cfc0L);
        word(block("clock_capture") ? 0x8001c300L : 0x80007340L);
        word(0x8001d280L);
        finish("persist_boot", 0x8001d5c0L);

        // The sequencer owns its run state, not the physical arp switch.
        // Use the same effective enable for tempo conditioning, factory
        // start/stop setup, periodic ticks and external-clock dispatch.
        // Returns R8 = boolean, clobbers only R8/R9. In particular the
        // clock service keeps R10 (FIFO) and R11 (factory state) live.
        begin(0x8001d600L);
        emit("MOV R9,0x6154");
        emit("LD.UB R8,R9[0x4]");
        emit("CP.W R8,0x2");
        emit("BR{eq} 0x8001d62c");
        emit("LDDPC R9,0x8001d638");
        emit("LD.UB R8,R9[0x340]");
        emit("LD.UB R9,R9[0x341]");
        emit("OR R8,R9");
        emit("MOV PC,LR");
        padTo(0x8001d62cL);
        emit("MOV R8,0x1");
        emit("MOV PC,LR");
        padTo(0x8001d638L);
        word(0x00003560L);
        word(0x8001d600L);
        finish("seq_clock_enabled", 0x8001d640L);

        // Every play/stop/clear/record transition goes through seq_enter's
        // mode-change pool. Preserve its R9/R12 arguments. Finish the strip
        // swap first, while the old mode is still visible; then publish the
        // new mode before asking the FACTORY tempo/setup routine to run.
        // This does not overwrite the physical switch or invent a second
        // oscillator. Stopping the sequence returns clock ownership to arp.
        begin(0x8001d640L);
        emit("STM --SP,R0,R1,R2,R7,R9,R12,LR");
        emit("MOV R7,SP");
        emit("LD.UB R0,R12[0x4]");
        emit("MOV R1,R9");
        // A touch present at any explicit/preview boundary belongs to the
        // old gesture. Block it until release, including RECORD -> RECORD.
        // Sample the physical level here: if already up, the first fresh
        // touch after this transition must remain usable without a dead tap.
        emit("LDDPC R10,0x8001d770");
        emit("LD.UB R8,R10[0x206]");
        emit("CP.W R8,0x0");
        emit("BR{eq} 0x8001d656");
        emit("MOV R8,0x2");
        padTo(0x8001d656L);
        emit("MOV R10,0x61e4");
        emit("ST.B R10[0x0],R8");
        emit("MCALL PC[0x8001d768]");    // original strip-mode transition
        emit("CP.W R0,R1");
        emit("BR{eq} 0x8001d750");
        emit("CP.W R1,0x2");
        emit("BR{ne} 0x8001d670");
        emit("MCALL PC[0x8001d76c]");    // end any preceding arp note on PLAY
        padTo(0x8001d670L);
        emit("ST.B R12[0x4],R1");
        emit("CP.W R0,0x2");
        emit("BR{eq} 0x8001d688");
        emit("CP.W R1,0x2");
        emit("BR{ne} 0x8001d750");
        padTo(0x8001d688L);
        emit("MFSR R2,SR");
        emit("SSRF 0x10");
        emit("MOV R8,0x0");
        emit("MOV R10,0x60ee");
        emit("ST.B R10[0x0],R8");       // no old deferred trigger crosses transport
        if (block("clock_capture")) {
            emit("MOV R10,0x6234");
            emit("LD.UB R9,R10[0x0]");
            emit("ST.B R10[0x1],R9");  // discard pre-start/stop FIFO entries
            emit("ST.B R10[0x2],R8");
            emit("ST.B R10[0x3],R8");
            emit("ST.B R10[-0x1],R8"); // release acquired divider
            emit("MOV R10,0x61ea");
            emit("ST.H R10[0x0],R8");
            emit("ST.H R10[0x2],R8");
        }
        emit("MTSR SR,R2");
        emit("LDDPC R10,0x8001d770");
        emit("ST.H R10[0x38e],R8");     // start at the first beat, not an old countdown
        emit("MCALL PC[0x8001d774]");    // rate + factory enable transition/setup
        padTo(0x8001d750L);
        emit("LDM SP++,R0,R1,R2,R7,R9,R12,PC");
        padTo(0x8001d768L);
        word(0x8001b6c0L);
        word(0x8001b448L);
        word(0x00003560L);
        word(0x80002b28L);
        finish("seq_transport", 0x8001d780L);

        // A bare pad press while RECORDING - pad 4 not held, so it is not a
        // chord. R11 is the pad-loop index and must survive the transport.
        begin(0x8001d780L);
        emit("STM --SP,R0,R7,R11,LR");
        emit("MOV R7,SP");
        emit("MOV R0,0x6154");
        emit("LD.UB R8,R0[0x4]");
        emit("CP.W R8,0x1");
        emit("BR{ne} 0x8001d7fc");      // only while recording
        emit("MOV R8,0x46f3");
        emit("LD.UB R8,R8[0x0]");
        emit("CP.W R8,0x2");
        emit("BR{eq} 0x8001d7fc");      // held but not armed is NOT a bare press
        // A pad the preset editor is FOLLOWING is setting a voltage, not
        // running the sequencer: the hold that reached here belongs to the
        // edit, and acting on it previewed or deleted mid-edit.  Following
        // is sticky until the release, so the whole hold is declined.
        emit("MOV R8,0x614a");
        emit("LD.UB R8,R8[R11 << 0x0]");
        emit("CP.W R8,0x0");
        emit("BR{ne} 0x8001d7fc");
        emit("CP.W R11,0x1");
        emit("BR{eq} 0x8001d7b0");      // pad 2: hear it back
        emit("CP.W R11,0x2");
        emit("BR{eq} 0x8001d7c8");      // pad 3: take the last one back
        padTo(0x8001d7b0L);
        emit("MCALL PC[0x8001d7f8]");   // seq_preview_start
        emit("RJMP 0x8001d7fc");
        padTo(0x8001d7c8L);
        // Backspace.  Shortening the sequence is what makes it play right,
        // and erase the freed slot. Persistence canonicalizes unused slots
        // separately; this edit does not complete or save the WRITE session.
        emit("MOV R10,0x61e0");
        emit("LD.UB R8,R10[0x0]");
        emit("CP.W R8,0x0");
        emit("BR{eq} 0x8001d7fc");      // already empty
        emit("SUB R8,0x1");
        emit("ST.B R10[0x0],R8");
        emit("MOV R9,0x0");
        emit("MOV R12,0x6160");
        emit("ADD R12,R12,R8 << 0x1");
        emit("ST.H R12[0x0],R9");       // the pitch it held
        emit("MOV R12,0x61ee");
        emit("ADD R12,R12,R8 << 0x0");
        emit("ST.B R12[0x0],R9");       // and the key it was played on
        // Say so on the pad itself: the per-scan flash service blinks pad 3
        // for this many scans, so a deletion that changes nothing audible -
        // trimming a rest, editing while stopped - is still visibly taken.
        emit("MOV R10,0x6502");
        emit("MOV R12,0x30");
        emit("ST.B R10[0x0],R12");
        emit("RJMP 0x8001d7fc");
        padTo(0x8001d7f8L);
        word(0x8001d880L);              // seq_preview_start
        padTo(0x8001d7fcL);
        emit("LDM SP++,R0,R7,R11,PC");
        finish("seq_edit", 0x8001d800L);

        // Which step plays next, asked where the sequence would otherwise
        // wrap.  Returns R9 = the step, or -1 to sound nothing: a preview
        // stops at the end instead of going round, and hands the sequencer
        // back to recording.  R8, R10, R11 and R12 are the caller's.
        begin(0x8001d800L);
        emit("STM --SP,R7,R8,R10,R11,R12,LR");
        emit("MOV R7,SP");
        emit("MOV R10,0x61e0");
        emit("LD.UB R11,R10[0x0]");
        emit("LD.UB R9,R10[0x1]");
        emit("CP.W R9,R11");
        emit("BR{lt} 0x8001d834");      // still inside: play it
        emit("MOV R8,0x62fe");
        emit("LD.UB R12,R8[0x0]");
        emit("CP.W R12,0x0");
        emit("BR{eq} 0x8001d830");      // no preview: wrap, as it always did
        emit("MOV R12,0x0");
        emit("ST.B R8[0x0],R12");       // the preview is over
        emit("MOV R12,0x6154");
        emit("MOV R9,0x1");
        emit("MCALL PC[0x8001d838]");
        emit("ST.B R12[0x4],R9");       // and recording has it back
        emit("MOV R9,-0x1");            // sounding nothing on the way out
        emit("RJMP 0x8001d834");
        padTo(0x8001d830L);
        emit("MOV R9,0x0");
        padTo(0x8001d834L);
        emit("LDM SP++,R7,R8,R10,R11,R12,PC");
        padTo(0x8001d838L);
        word(0x8001d8e0L);              // clear audition state + transport
        finish("seq_preview_step", 0x8001d840L);

        // Explicit chord commands cancel preview. Only pad 3 emits CLEAR;
        // the persistence scan consumes that event, not a length transition.
        // Leave R8/R10 exactly as seq_enter's transient reset expects them.
        begin(0x8001d840L);
        emit("MOV R8,0x62fe");
        emit("MOV R10,0x0");
        emit("ST.B R8[0x0],R10");
        emit("CP.W R11,0x2");
        emit("BR{ne} 0x8001d854");
        emit("MOV R9,0x1");
        emit("ST.B R8[0x1],R9");
        padTo(0x8001d854L);
        emit("MOV R8,0x61e0");
        emit("MOV PC,LR");
        finish("seq_command", 0x8001d860L);

        // A preview traverses the recorded order once, irrespective of the
        // shuffle knob. Keep count as an end sentinel until seq_select asks
        // for its next note; wrapping here would make the end unreachable.
        // Ordinary playback tail-calls the existing shuffle/wrap routine.
        begin(0x8001d860L);
        emit("MOV R8,0x62fe");
        emit("LD.UB R8,R8[0x0]");
        emit("CP.W R8,0x0");
        emit("BR{ne} 0x8001d878");
        emit("LDDPC R8,0x8001d874");
        emit("MOV PC,R8");              // preserve LR across the long tail call
        padTo(0x8001d874L);
        word(0x8001baa0L);
        padTo(0x8001d878L);
        emit("SUB R9,-0x1");
        emit("MOV PC,LR");
        finish("seq_preview_next", 0x8001d880L);

        begin(0x8001d880L);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("MOV R10,0x61e0");
        emit("LD.UB R8,R10[0x0]");
        emit("CP.W R8,0x0");
        emit("BR{eq} 0x8001d8d0");      // an empty take cannot preview
        emit("MOV R8,0x0");
        emit("ST.B R10[0x1],R8");
        emit("MOV R8,0x62fe");
        emit("MOV R9,0x1");
        emit("ST.B R8[0x0],R9");
        emit("MOV R12,0x6154");
        emit("MOV R9,0x2");
        emit("MCALL PC[0x8001d8dc]");
        padTo(0x8001d8d0L);
        emit("LDM SP++,R7,PC");
        padTo(0x8001d8dcL);
        word(0x8001d8e0L);
        finish("seq_preview_start", 0x8001d8e0L);

        // Preview transitions bypass seq_enter, so reset the same transient
        // tie/audition state before the shared mode/clock transition, which
        // also rejects any strip touch still held at the boundary.
        // R9/R12 are the transport's arguments and survive unchanged.
        begin(0x8001d8e0L);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("MOV R8,0x61e0");
        emit("MOV R10,0x0");
        emit("ST.B R8[0x5],R10");
        emit("ST.H R8[0x50],R10");
        emit("ST.H R8[0x320],R10");     // 0x6500, the audition's pinned pitch
        emit("MCALL PC[0x8001d91c]");
        emit("LDM SP++,R7,PC");
        padTo(0x8001d91cL);
        word(0x8001d640L);
        finish("seq_preview_transport", 0x8001d920L);

        // ADC event: preserve the factory decoder's other work, then publish
        // knob-4 ownership BEFORE 0x80003590 consumes the transpose bytes.
        // The late applier remains necessary after tuning clears the enable.
        begin(0x8001d920L);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("MCALL PC[0x8001d94c]");
        emit("MCALL PC[0x8001d950]");
        emit("MCALL PC[0x8001d954]");
        emit("LDM SP++,R7,PC");
        padTo(0x8001d94cL);
        word(0x8001ab60L);
        word(0x80004a00L);
        word(0x8001d960L);
        finish("knob4_early", 0x8001d960L);

        // Read-only pickup prediction. In particular DO NOT clear following
        // on release: the later persistence shim must still observe that
        // edge. The knob_pickup helper decides hold-or-follow: it stamps
        // where the knob is while the editor owns it, then keeps holding the
        // zone until the knob has moved again - a released pad used to hand
        // the zone straight to wherever the edit left the knob standing.
        // While held with no edit begun, exactly the editor's >8-unit
        // movement threshold owns the first changed scan.
        // Global edit owns knob 4's pressure curve, never the live transpose.
        begin(0x8001d960L);
        emit("MOV R12,LR");             // this leaf now calls, so LR parks here
        emit("MOV R9,0x3560");
        emit("MOV R10,0x60f0");
        emit("LD.UB R8,R9[0x39]");
        emit("CP.W R8,0x1");
        emit("BR{eq} 0x8001d9d0");
        emit("MOV R11,0x3");
        emit("MCALL PC[0x8001d9e0]");
        emit("CP.W R9,0x0");
        emit("MOV R9,0x3560");          // restore; MOV leaves the flags alone
        emit("BR{ne} 0x8001d9d0");      // the edit, or its parked aftermath, holds
        emit("LD.SH R8,R9[0x310]");
        emit("MOV R11,0x46f3");
        emit("LD.UB R11,R11[0x0]");
        emit("CP.W R11,0x2");
        emit("BR{ne} 0x8001d9b0");
        emit("LD.SH R11,R10[0x58]");   // last unheld snapshot: 0x6148
        emit("SUB R11,R8,R11 << 0x0");
        emit("CP.W R11,0x8");
        emit("BR{gt} 0x8001d9d0");
        emit("CP.W R11,-0x8");
        emit("BR{lt} 0x8001d9d0");
        padTo(0x8001d9b0L);
        emit(String.format("MOV R11,0x%x", number("knob4_zones", 9, 3, 16)));
        emit("MUL R8,R8,R11");
        emit("LSR R8,0xa");
        emit("ST.H R10[0x0],R8");
        padTo(0x8001d9d0L);
        emit("LD.UH R8,R10[0x0]");
        emit("ST.B R9[0x6b],R8");
        emit("MOV R8,0x1");
        emit("ST.B R9[0x6a],R8");
        emit("MOV PC,R12");
        padTo(0x8001d9e0L);
        word(0x8001dd80L);
        finish("knob4_owned_transpose", 0x8001da00L);

        // Pitch-ordered selection. R0=held flags, R1=state, R2=zone 0/1/2,
        // R3=direction +/-1. Scan the 29 slots, tracking min, max and the
        // nearest rank strictly beyond the previous note in that direction.
        // A rank packs (signed effective pitch, slot) into one 32-bit value:
        // equal pitches remain distinct, with a deterministic slot tie-break.
        // No sorting, allocation, random draws or unbounded search is needed.
        begin(0x8001da00L);
        emit("STM --SP,R0,R1,R2,R3,R4,R5,R6,R7,LR");
        emit("LDDPC R4,0x8001dbf0");    // minimum starts at INT_MAX
        emit("LDDPC R5,0x8001dbf4");    // maximum starts at INT_MIN
        emit("LD.UB R12,R1[0x34d]");
        emit("CP.W R12,0x1d");
        emit("BR{ge} 0x8001da30");
        emit("MCALL PC[0x8001dbf8]");
        emit("MOV R1,R12");
        emit("RJMP 0x8001da40");
        padTo(0x8001da30L);
        emit("MOV R1,R5");             // no previous note: start at an end
        emit("CP.W R3,0x0");
        emit("BR{ge} 0x8001da40");
        emit("MOV R1,R4");
        padTo(0x8001da40L);
        // On a mirror retry min/max already contain real ranks; use the
        // sentinel, not those endpoints, to reset the nearest candidate.
        emit("LDDPC R6,0x8001dbf0");
        emit("CP.W R3,0x0");
        emit("BR{ge} 0x8001da4c");
        emit("LDDPC R6,0x8001dbf4");
        padTo(0x8001da4cL);
        emit("MOV R7,0x0");
        padTo(0x8001da50L);
        emit("LD.UB R8,R0[R7 << 0x0]");
        emit("CP.W R8,0x1");
        emit("BR{ne} 0x8001daac");
        emit("MOV R12,R7");
        emit("MCALL PC[0x8001dbf8]");
        emit("CP.W R12,R4");
        emit("BR{ge} 0x8001da68");
        emit("MOV R4,R12");
        padTo(0x8001da68L);
        emit("CP.W R12,R5");
        emit("BR{le} 0x8001da74");
        emit("MOV R5,R12");
        padTo(0x8001da74L);
        emit("CP.W R3,0x0");
        emit("BR{lt} 0x8001da90");
        emit("CP.W R12,R1");
        emit("BR{le} 0x8001daac");
        emit("CP.W R12,R6");
        emit("BR{ge} 0x8001daac");
        emit("MOV R6,R12");
        emit("RJMP 0x8001daac");
        padTo(0x8001da90L);
        emit("CP.W R12,R1");
        emit("BR{ge} 0x8001daac");
        emit("CP.W R12,R6");
        emit("BR{le} 0x8001daac");
        emit("MOV R6,R12");
        padTo(0x8001daacL);
        emit("SUB R7,-0x1");
        emit("CP.W R7,0x1d");
        emit("BR{lt} 0x8001da50");
        emit("LDDPC R8,0x8001dbf0");
        emit("CP.W R4,R8");
        emit("BR{eq} 0x8001db90");      // no held slots
        emit("CP.W R3,0x0");
        emit("BR{ge} 0x8001dac8");
        emit("LDDPC R8,0x8001dbf4");
        padTo(0x8001dac8L);
        emit("CP.W R6,R8");
        emit("BR{ne} 0x8001db20");
        // Mirror bounces rather than wraps when the current endpoint was
        // removed or direction changed. At most ONE retry, so one held note
        // (or all equal pitches) cannot spin. Zones 0/1 wrap straight away.
        emit("CP.W R2,0x2");
        emit("BR{ne} 0x8001db00");
        emit("MOV R2,0x3");
        emit("MOV R8,0x0");
        emit("SUB R3,R8,R3 << 0x0");
        emit("RJMP 0x8001da40");
        padTo(0x8001db00L);
        emit("MOV R6,R4");
        emit("CP.W R3,0x0");
        emit("BR{ge} 0x8001db20");
        emit("MOV R6,R5");
        padTo(0x8001db20L);
        emit("CP.W R2,0x2");
        emit("BR{lt} 0x8001db70");
        emit("MOV R8,0x1");
        emit("CP.W R3,0x0");
        emit("BR{ge} 0x8001db34");
        emit("MOV R8,0x0");
        padTo(0x8001db34L);
        emit("CP.W R6,R5");
        emit("BR{ne} 0x8001db44");
        emit("MOV R8,0x0");
        emit("RJMP 0x8001db58");
        padTo(0x8001db44L);
        emit("CP.W R6,R4");
        emit("BR{ne} 0x8001db58");
        emit("MOV R8,0x1");
        padTo(0x8001db58L);
        emit("MOV R9,0x614e");
        emit("ST.B R9[0x0],R8");
        padTo(0x8001db70L);
        emit("BFEXTU R12,R6,0x0,0x5");
        emit("LDM SP++,R0,R1,R2,R3,R4,R5,R6,R7,PC");
        padTo(0x8001db90L);
        emit("MOV R12,-0x1");
        emit("LDM SP++,R0,R1,R2,R3,R4,R5,R6,R7,PC");
        padTo(0x8001dbf0L);
        word(0x7fffffffL);
        word(0x80000000L);
        word(0x8001dc00L);
        finish("arp_pitch_order", 0x8001dc00L);

        // R12=valid slot 0..28 -> signed rank. Only latch mode interprets
        // stamps; regular/factory arp must ignore old stamps in scratch RAM.
        // Even the full unsigned-table + signed-stamp range fits after <<5.
        begin(0x8001dc00L);
        emit("MOV R8,0x854");
        emit("LD.UH R9,R8[R12 << 0x1]");
        if (feature("arp_latch")) {
            emit("MOV R8,0x3560");
            emit("LD.UB R8,R8[0x340]");
            emit("CP.W R8,0x1");
            emit("BR{ne} 0x8001dc30");
            emit("MOV R8,0x60a2");
            emit("LD.SH R8,R8[R12 << 0x1]");
            emit("ADD R9,R8");
        }
        padTo(0x8001dc30L);
        emit("LSL R9,0x5");
        emit("ADD R12,R9,R12 << 0x0");
        emit("MOV PC,LR");
        finish("arp_pitch_rank", 0x8001dc60L);

        // Reverse press history, bounded by the validated history count.
        // R0=held flags, R1=state; never index flags using an unchecked entry.
        begin(0x8001dc60L);
        emit("STM --SP,R2,R7,LR");
        emit("MOV R10,0x6000");
        emit("LD.UB R8,R10[0x0]");
        emit("CP.W R8,0x20");
        emit("BR{hi} 0x8001dcd4");
        emit("CP.W R8,0x0");
        emit("BR{eq} 0x8001dcd4");
        emit("LD.UB R9,R1[0x34d]");
        emit("MOV R2,0x0");
        padTo(0x8001dc80L);
        emit("CP.W R2,R8");
        emit("BR{ge} 0x8001dc98");
        emit("ADD R11,R10,R2 << 0x0");
        emit("LD.UB R11,R11[0x1]");
        emit("CP.W R11,R9");
        emit("BR{eq} 0x8001dc98");
        emit("SUB R2,-0x1");
        emit("RJMP 0x8001dc80");
        padTo(0x8001dc98L);
        emit("MOV R12,R8");
        padTo(0x8001dc9aL);
        emit("SUB R2,0x1");
        emit("BR{ge} 0x8001dca6");
        emit("MOV R2,R8");
        emit("SUB R2,0x1");
        padTo(0x8001dca6L);
        emit("ADD R11,R10,R2 << 0x0");
        emit("LD.UB R11,R11[0x1]");
        emit("CP.W R11,0x1d");
        emit("BR{ge} 0x8001dcc0");
        emit("LD.UB R9,R0[R11 << 0x0]");
        emit("CP.W R9,0x1");
        emit("BR{eq} 0x8001dccc");
        padTo(0x8001dcc0L);
        emit("SUB R12,0x1");
        emit("BR{ne} 0x8001dc9a");
        emit("RJMP 0x8001dcd4");
        padTo(0x8001dcccL);
        emit("MOV R12,R11");
        emit("RJMP 0x8001dcda");
        padTo(0x8001dcd4L);
        emit("MOV R12,-0x1");
        padTo(0x8001dcdaL);
        emit("LDM SP++,R2,R7,PC");
        finish("arp_reverse_held", 0x8001dce0L);

        // The pitch a key actually SOUNDS.  R12 = key, returns R11, and R12
        // is left as the caller had it.  A leaf, and it must stay one.
        //
        // With the latching arp the key table is only half the answer: a
        // press is worth the table pitch plus TODAY'S transpose - the same
        // sum the latch toggle stamps when it lets the press through.  An
        // earlier version read the SLOT stamp at 0x60a2 instead, but the
        // recorder runs before the toggle, so a fresh press read a stamp
        // that had not been written yet and a reused slot read the note
        // that used to live there.  0x60a0 is published every scan and
        // belongs to no slot.
        //
        // The sum is then clamped to 0..4095 exactly as the sounding path
        // clamps it.  Octaves stacked on the top keys can push past the
        // DAC's range, and persistence rightly refuses a step it could not
        // play back - one such note left the whole take unsaveable.
        begin(0x8001dce0L);
        emit("MOV R11,0x854");
        emit("ADD R11,R11,R12 << 0x1");
        emit("LD.SH R11,R11[0x0]");
        // Plus the transpose published this scan, in EVERY arp position.
        // The octave choice reaches the sounding pitch whichever way the
        // switch points - the latch through its stamps, the other two
        // through the live base - so leaving it out of the store in OFF and
        // regular modes recorded a different take than the one played.
        emit("MOV R8,0x60a0");
        emit("LD.SH R8,R8[0x0]");
        emit("ADD R11,R8");
        padTo(0x8001dd00L);
        emit("CP.W R11,0x0");
        emit("BR{ge} 0x8001dd08");
        emit("MOV R11,0x0");
        padTo(0x8001dd08L);
        emit("MOV R8,0xfff");
        emit("CP.W R11,R8");
        emit("BR{lt} 0x8001dd12");      // {lt}, which has the short encoding;
        emit("MOV R11,R8");             // equal rewrites 4095 with itself
        padTo(0x8001dd12L);
        emit("MOV PC,LR");
        finish("seq_record_pitch", 0x8001dd20L);

        // A bare pad 2 or 3 must be HELD before it means preview or
        // backspace.  Those pads have another job - with ADD TO PITCH set to
        // octaves they choose one - and a press edge stole it, so octave 3
        // could not be chosen without deleting a note.  A tap now belongs to
        // whatever else the pad does; only a hold reaches seq_edit.
        //
        // R11 = pad 0..2, R12 = this scan's touch level.  Called EVERY scan,
        // held or not, so the count clears on release.  It fires once: the
        // count saturates one past the threshold and waits for the release.
        begin(0x8001dd20L);
        emit("STM --SP,R7,R11,LR");
        emit("MOV R7,SP");
        emit("CP.W R11,0x0");
        emit("BR{eq} 0x8001dd74");      // pad 1 is neither preview nor backspace:
                                        // count nothing, so a preset hold on it
                                        // cannot churn state other code watches
        emit("MOV R10,0x625c");
        emit("MOV R9,R11");
        emit("SUB R9,-0x1");            // this pad, plus one
        emit("CP.W R12,0x2");
        emit("BR{ne} 0x8001dd44");      // not held
        emit("LD.UB R8,R10[0x0]");
        emit("CP.W R8,R9");
        emit("BR{eq} 0x8001dd54");      // still the same hold: count on
        emit("ST.B R10[0x0],R9");       // a new hold starts here
        emit("MOV R8,0x1");             // and this scan is the first of it
        emit("ST.B R10[0x1],R8");
        emit("RJMP 0x8001dd74");
        padTo(0x8001dd44L);
        // Released.  Only this pad's own hold is cleared, so a finger coming
        // off one pad cannot cancel another's count.
        emit("LD.UB R8,R10[0x0]");
        emit("CP.W R8,R9");
        emit("BR{ne} 0x8001dd74");
        emit("MOV R8,0x0");
        emit("ST.B R10[0x0],R8");
        emit("ST.B R10[0x1],R8");
        emit("RJMP 0x8001dd74");
        padTo(0x8001dd54L);
        emit("LD.UB R8,R10[0x1]");
        emit(String.format("CP.W R8,0x%x",
             number("seq_edit_hold_scans", 60, 2, 250)));
        emit("BR{ge} 0x8001dd74");      // already fired; wait for the release
        emit("SUB R8,-0x1");
        emit("ST.B R10[0x1],R8");
        emit(String.format("CP.W R8,0x%x",
             number("seq_edit_hold_scans", 60, 2, 250)));
        emit("BR{lt} 0x8001dd74");      // not long enough yet
        emit("MCALL PC[0x8001dd78]");   // long enough: seq_edit acts
        padTo(0x8001dd74L);
        emit("LDM SP++,R7,R11,PC");
        padTo(0x8001dd78L);
        word(0x8001d780L);              // seq_edit
        finish("seq_hold", 0x8001dd80L);

        // Soft pickup for the knobs the preset editor borrows.  R11 = which
        // knob; the answer comes back in R9, nonzero while the knob's other
        // job must hold its value.  While the
        // editor owns the knob (0x614a + knob set) this stamps where the
        // knob is, stored plus one so the initialiser's zero fill reads as
        // "nothing parked"; afterwards the parked stamp keeps holding until
        // the knob moves past the editor's own 8-unit threshold, so leaving
        // preset-set mode no longer snaps the musical value to wherever the
        // edit left the knob.  Clobbers R8; preserves R10, R11 and R12.
        // The knob mirrors are read off their absolute address, not a passed
        // base, so the callers stay register-for-register drop-ins.
        begin(0x8001dd80L);
        emit("MOV R9,0x614a");
        emit("LD.UB R9,R9[R11 << 0x0]");
        emit("MOV R8,0x386a");          // state+0x30a: the first knob mirror
        emit("LD.SH R8,R8[R11 << 0x1]");    // where the knob is right now
        emit("CP.W R9,0x0");
        emit("BR{eq} 0x8001dda4");
        emit("SUB R8,-0x1");            // the edit owns the knob: remember where
        emit("MOV R9,0x62e8");
        emit("ST.H R9[R11 << 0x1],R8");
        emit("MOV R9,0x1");
        emit("MOV PC,LR");
        padTo(0x8001dda4L);
        emit("MOV R9,0x62e8");
        emit("LD.SH R9,R9[R11 << 0x1]");
        emit("CP.W R9,0x0");
        emit("BR{eq} 0x8001ddd0");      // nothing parked: follow
        emit("SUB R9,0x1");             // the raw the edit ended at
        emit("SUB R9,R8,R9 << 0x0");    // how far the knob has come since
        emit("CP.W R9,0x8");
        emit("BR{gt} 0x8001ddc4");
        emit("CP.W R9,-0x8");
        emit("BR{lt} 0x8001ddc4");
        emit("MOV R9,0x1");             // still parked: keep holding
        emit("MOV PC,LR");
        padTo(0x8001ddc4L);
        emit("MOV R9,0x62e8");          // the hand is back: let go
        emit("MOV R8,0x0");
        emit("ST.H R9[R11 << 0x1],R8");
        padTo(0x8001ddd0L);
        emit("MOV R9,0x0");
        emit("MOV PC,LR");
        padTo(0x8001ddd4L);
        word(0x8001dd80L);
        finish("knob_pickup", 0x8001dde0L);

        // The pitch-aware latch toggle, wrapped so the press's OWNERSHIP is
        // written down while both halves of it are still in hand: R0 keeps
        // the physical key across the call, and the toggle answers with the
        // slot the note actually went to - its own key's, or any free one.
        // The two maps say, per key, which slot its current note lives in,
        // and per slot, which key's press made its note; the pressure pass
        // reads them back so a finger's weight lands on the note under it,
        // not on whatever older note still occupies the finger's own slot
        // number.  A toggle-OFF answers -1. Clear current[key] in that case:
        // one physical key may own several octave-latched slots, and removing
        // an older one must not leave this still-held press weighting a
        // different surviving octave. A full-latch rejection follows the
        // same rule: this press made no note, so it owns no pressure target.
        begin(0x8001dde0L);
        emit("STM --SP,R0,R7,LR");
        emit("MOV R7,SP");
        emit("MOV R0,R12");
        emit("MCALL PC[0x8001de1c]");   // the toggle itself
        emit("CP.W R12,0x0");
        emit("BR{lt} 0x8001de08");
        emit("MOV R9,0x6521");
        emit("SUB R12,-0x1");
        emit("ST.B R9[R0 << 0x0],R12"); // current[key] = slot, plus one
        emit("SUB R12,0x1");            // restore the toggle's return
        emit("SUB R9,0x1d");            // 0x6504: owner[]
        emit("SUB R0,-0x1");
        emit("ST.B R9[R12 << 0x0],R0"); // owner[slot] = key, plus one
        emit("RJMP 0x8001de18");
        padTo(0x8001de08L);
        emit("CP.W R0,0x1c");
        emit("BR{hi} 0x8001de18");      // invalid key: no indexed write
        emit("MOV R9,0x6521");
        emit("MOV R8,0x0");
        emit("ST.B R9[R0 << 0x0],R8");  // rejected/toggled press owns nothing
        padTo(0x8001de18L);
        emit("LDM SP++,R0,R7,PC");
        padTo(0x8001de1cL);
        word(0x8001a930L);              // pitch-aware latch toggle
        finish("latch_owner", 0x8001de20L);

        // Between the re-base shim and the blend cave, three per-scan jobs
        // that all need the sequencer's mode in hand.
        //
        // First the re-base step the shim measured (R10, zero when none):
        // while the sequencer PLAYS or previews, the base moves because the
        // factory glide is walking between steps, not because a note handed
        // over, and folding those steps into the applied offset and the
        // conditioner cells sent every transition off in the wrong direction
        // before the glide could turn it around.
        //
        // Then the pitch this chain publishes (R12).  A one-shot preview
        // auditions the take AS RECORDED, so the live pad transpose - play
        // mode's to follow - is subtracted back out for as long as the
        // preview runs.  And a recording audition sounds the pitch the
        // recorder just stored, exactly: the chain otherwise re-derives the
        // note from the last arp key's slot and stamp every scan, which is
        // the old note for a reallocated slot and the old octave for a
        // toggled-off press.  The pin holds between presses, which only
        // holds the note that was already sounding; with the arp OFF the
        // keyboard is live and no pin applies.
        //
        // Last, the slot-indexed pressure weights: zero them, then land each
        // physically held key's pressure on the slot its CURRENT note lives
        // in - and only if the ownership maps agree, so a note that was
        // toggled away under a still-held finger pulls nothing.
        begin(0x8001de20L);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("CP.W R10,0x0");
        emit("BR{eq} 0x8001de54");
        emit("MOV R8,0x6158");
        emit("LD.UB R8,R8[0x0]");
        emit("CP.W R8,0x2");
        emit("BR{eq} 0x8001de54");
        emit("MOV R8,0x60e2");
        emit("LD.SH R9,R8[0x0]");
        emit("ADD R9,R10");
        emit("ST.H R8[0x0],R9");
        emit("LD.SH R9,R8[0x14]");
        emit("ADD R9,R10");
        emit("ST.H R8[0x14],R9");
        emit("LD.SH R9,R8[0x16]");
        emit("ADD R9,R10");
        emit("ST.H R8[0x16],R9");
        padTo(0x8001de54L);
        emit("MOV R8,0x6158");
        emit("LD.UB R8,R8[0x0]");
        emit("CP.W R8,0x1");
        emit("BR{eq} 0x8001de80");
        emit("CP.W R8,0x2");
        emit("BR{ne} 0x8001de9c");
        emit("MOV R8,0x62fe");
        emit("LD.UB R8,R8[0x0]");
        emit("CP.W R8,0x0");
        emit("BR{eq} 0x8001de9c");
        emit("MOV R8,0x60a0");
        emit("LD.SH R8,R8[0x0]");
        emit("SUB R12,R8");             // preview: as recorded
        emit("RJMP 0x8001de9c");
        padTo(0x8001de80L);
        emit("MOV R8,0x38a0");          // state+0x340/341 in one halfword
        emit("LD.UH R8,R8[0x0]");
        emit("CP.W R8,0x0");
        emit("BR{eq} 0x8001de9c");
        emit("MOV R8,0x6500");
        emit("LD.UH R9,R8[0x0]");
        emit("CP.W R9,0x0");
        emit("BR{eq} 0x8001de9c");
        emit("SUB R9,0x1");
        emit("MOV R12,R9");             // the audition: the stored pitch
        padTo(0x8001de9cL);
        if (feature("arp_latch")) {
            emit("MOV R9,0x6540");
            emit("MOV R8,0x0");
            emit("MOV R10,0x1c");
            padTo(0x8001dea4L);
            emit("ST.H R9[R10 << 0x1],R8");
            emit("SUB R10,0x1");
            emit("BR{ge} 0x8001dea4");
            emit("MOV R10,0x1c");
            padTo(0x8001deaeL);
            emit("MOV R8,0x6100");
            emit("LD.UH R8,R8[R10 << 0x1]");
            emit("CP.W R8,0x0");
            emit("BR{eq} 0x8001dee4");
            emit("MOV R9,0x6521");
            emit("LD.UB R9,R9[R10 << 0x0]");
            emit("CP.W R9,0x0");
            emit("BR{eq} 0x8001dee4");
            emit("SUB R9,0x1");
            emit("CP.W R9,0x1d");
            emit("BR{ge} 0x8001dee4");  // only 0..28 is a slot
            emit("MOV R11,0x6504");
            emit("LD.UB R11,R11[R9 << 0x0]");
            emit("SUB R11,0x1");
            emit("CP.W R11,R10");
            emit("BR{ne} 0x8001dee4");  // another press took that slot
            emit("MOV R11,0x6540");
            emit("ADD R11,R11,R9 << 0x1");
            emit("ST.H R11[0x0],R8");
            padTo(0x8001dee4L);
            emit("SUB R10,0x1");
            emit("BR{ge} 0x8001deae");
        }
        padTo(0x8001dee8L);
        // The anchor pitch the blend measures its offset from, in R10: the
        // published base, or - when the ownership map knows the last arp
        // key's current note - that note's own table pitch, so a recording
        // audition of an allocated slot anchors on the note it sounds, not
        // on the older note still latched in the key's slot number.  In
        // live latch mode the arp names slots, whose map entries are unset
        // unless that key was itself pressed, so the base falls through.
        emit("MOV R8,0x3560");
        emit("LD.SH R10,R8[0x350]");
        if (feature("arp_latch")) {
            emit("LD.UB R9,R8[0x34d]");
            emit("CP.W R9,0x1d");
            emit("BR{ge} 0x8001df18");
            emit("MOV R11,0x6521");
            emit("LD.UB R9,R11[R9 << 0x0]");
            emit("CP.W R9,0x0");
            emit("BR{eq} 0x8001df18");
            emit("SUB R9,0x1");
            emit("CP.W R9,0x1d");
            emit("BR{ge} 0x8001df18");
            emit("MOV R11,0x854");
            emit("ADD R11,R11,R9 << 0x1");
            emit("LD.UH R10,R11[0x0]");
        }
        padTo(0x8001df18L);
        emit("MCALL PC[0x8001df20]");
        emit("LDM SP++,R7,PC");
        padTo(0x8001df20L);
        word(0x80019c64L);              // the real blend cave entry
        finish("blend_slotmap", 0x8001df28L);

        // The delete pad's flash, serviced every scan on the way to the
        // record-sound call.  A backspace loads the countdown; while it
        // runs, pad 3 blinks on a faster phase than the mode pads so it
        // reads as an event, not a state, and the last tick repaints all
        // four channels from the active preset - the same repaint the pad-4
        // release path uses - so the pad ends exactly as the truth has it.
        // R0 and R1 are the chord cave's: the state base and 0x6154.
        begin(0x8001df30L);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("MOV R10,0x6502");
        emit("LD.UB R8,R10[0x0]");
        emit("CP.W R8,0x0");
        emit("BR{eq} 0x8001df60");
        emit("SUB R8,0x1");
        emit("ST.B R10[0x0],R8");
        emit("CP.W R8,0x0");
        emit("BR{ne} 0x8001df54");
        emit("LD.UB R12,R0[0x2ef]");
        emit("MCALL PC[0x8001df68]");   // select_pad repaints all four
        emit("RJMP 0x8001df60");
        padTo(0x8001df54L);
        emit("LD.UH R9,R1[0xa]");       // the shared scan counter
        emit("BFEXTU R9,R9,0x4,0x1");   // a faster blink than the mode pads
        emit("MOV R11,0x2");            // the delete pad's channel
        emit("MCALL PC[0x8001df6c]");   // write one channel
        padTo(0x8001df60L);
        emit("MCALL PC[0x8001df70]");   // and sound whatever record took in
        emit("LDM SP++,R7,PC");
        padTo(0x8001df68L);
        word(0x8000698cL);              // select_pad(0..3)
        word(0x8001b2a0L);              // write_channel(R11, R9)
        word(0x8001b2c0L);              // seq_record_sound
        finish("seq_flash", 0x8001df80L);

        // Same-image warm restart/DFU keeps custom SRAM and its first-use
        // marker. Clear the delete-flash transient on every real startup,
        // then let the shared first-use bootstrap do any image migration.
        // Persistence and clock startup both call this wrapper; a sequencer
        // build without either reaches it through seq_boot below.
        begin(0x8001df80L);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("MCALL PC[0x8001dfa0]");
        emit("MOV R8,0x0");
        emit("MOV R9,0x6502");
        emit("ST.B R9[0x0],R8");
        emit("LDM SP++,R7,PC");
        padTo(0x8001dfa0L);
        word(0x8001ab60L);
        finish("seq_restart_init", 0x8001dfa8L);

        begin(0x8001dfa8L);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("MCALL PC[0x8001dfc4]");
        emit("MCALL PC[0x8001dfc8]");
        emit("LDM SP++,R7,PC");
        padTo(0x8001dfc4L);
        word(0x8001df80L);
        word(0x80007340L);
        finish("seq_boot", 0x8001dfd0L);

        // Called at 0x80007bf4 while the factory has interrupts masked, BEFORE
        // enabling the input. SRAM survives warm restart and DFU: reset the
        // FIFO explicitly even if the common first-use marker already matches.
        // COUNT's scale is the factory delay routine's CPU-frequency word
        // (0x800129e0 -> pool 0x80012a24 -> RAM 0x29cc), not a guessed 60 MHz.
        begin(0x8001c300L);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit(block("seq_restart_init")
            ? "MCALL PC[0x8001dfc4]" : "MCALL PC[0x8001ac80]");
        emit("MOV R8,0x0");
        emit("MOV R10,0x6232");
        emit("MOV R9,0x56");
        padTo(0x8001c318L);
        emit("ST.H R10[0x0],R8");
        emit("SUB R10,-0x2");
        emit("SUB R9,0x1");
        emit("BR{ge} 0x8001c318");
        emit("MOV R10,0x61e6");
        emit("ST.W R10[0x2],R8");       // 0x61e8: last dispatch / period
        emit("ST.H R10[0x6],R8");       // presence confidence / divide phase
        emit("MOV R10,0x60ee");
        emit("ST.B R10[0x0],R8");       // no pre-restart deferred trigger survives
        if (block("clock_latency")) {
            // The diagnostic's accumulators are OUTSIDE the 0x6232..0x62df
            // sweep above, and nothing else clears them. On the instrument
            // they came up holding whatever was in RAM, so the running sum
            // and count were seeded with garbage and the published mean came
            // out ABOVE the published max -- which is how the fault was
            // found. The emulator could not have caught it: the harness
            // zeroes RAM 0..0x8000 before every test, so these cells only
            // ever started clean there.
            emit("MOV R10,0x6032");
            emit("ST.H R10[0x0],R8");   // running max
            emit("ST.H R10[0x2],R8");   // published mean
            emit("ST.W R10[0x6],R8");   // 0x6038 sum of delays
            emit("ST.H R10[0xa],R8");   // 0x603c sample count
            emit("ST.W R10[0xe],R8");   // 0x6040 stamp of the edge last timed
        }
        emit("MOV R10,0x6244");
        emit("MOV R8,0x29cc");
        emit("LD.W R8,R8[0x0]");
        emit("MOV R9,0x3e8");
        emit("DIVU R8,R8,R9");
        emit("ST.W R10[0x0],R8");       // cycles/ms
        emit(String.format("MOV R9,0x%x", number("clock_min_ms", 4, 1, 4)));
        emit("MUL R9,R8,R9");
        emit("ST.W R10[0x8],R9");
        emit(String.format("MOV R9,0x%x",
             number("clock_release_ms", 2600, 100, 32000)));
        emit("MUL R9,R8,R9");
        emit("ST.W R10[0xc],R9");
        emit(String.format("MOV R9,0x%x",
             number("clock_rearm_us", 250, 1, 1000)));
        emit("MUL R8,R8,R9");
        emit("MOV R9,0x3e8");
        emit("DIVU R8,R8,R9");
        emit("ST.W R10[0x4],R8");
        emit("MCALL PC[0x8001c3b8]");   // install/enable factory interrupts
        emit("MOV R8,-0xf000");
        emit("MOV R9,0x20");
        emit("ST.W R8[0xd8],R9");
        emit("LD.W R8,R8[0x60]");
        emit("BFEXTU R8,R8,0x5,0x1");
        emit("CP.W R8,0x0");
        emit("BR{ne} 0x8001c3b0");
        emit("MOV R10,0x6234");
        emit("MFSR R8,COUNT");
        emit("ST.W R10[0x4],R8");
        emit("MOV R8,0x1");
        emit("ST.B R10[-0x2],R8");
        padTo(0x8001c3b0L);
        emit("LDM SP++,R7,PC");
        padTo(0x8001c3b8L);
        word(0x80007340L);
        finish("clock_init", 0x8001c3c0L);

        // One bounded main-loop dequeue. The short critical section covers
        // timeout and tail publication; SR is restored EXACTLY, including
        // a pre-existing interrupt mask. All musical/DAC work is outside it.
        begin(0x8001c400L);
        emit("STM --SP,R0,R7,LR");
        emit("MOV R7,SP");
        emit("MFSR R0,SR");
        emit("SSRF 0x10");
        emit("MOV R10,0x6234");
        emit("LDDPC R11,0x8001c570");
        if (block("seq_clock_enabled")) {
            emit("MCALL PC[0x8001d63c]");
        } else {
            emit("LD.UB R8,R11[0x340]");
            emit("LD.UB R9,R11[0x341]");
            emit("OR R8,R9");
        }
        emit("CP.W R8,0x0");
        emit("BR{eq} 0x8001c4e0");
        emit("LD.UB R8,R10[0x2]");
        emit("CP.W R8,0x0");
        emit("BR{eq} 0x8001c540");
        // Milliseconds, not COUNT cycles - see the stamp in the capture ISR.
        emit("MOV R8,0x62f6");
        emit("LD.UH R8,R8[0x0]");
        emit("MOV R9,0x61e6");
        emit("LD.UH R9,R9[0x0]");
        emit("SUB R8,R9,R8 << 0x0");
        emit("CASTU.H R8");             // wrap-safe across the 65 s halfword
        emit(String.format("MOV R9,0x%x",
             number("clock_release_ms", 2600, 100, 32000)));
        emit("CP.W R8,R9");
        emit("BR{hi} 0x8001c4e0");
        emit("LD.UB R8,R10[0x3]");
        emit("CP.W R8,0x0");
        emit("BR{ne} 0x8001c540");
        emit("MOV R8,0x60ee");
        emit("LD.UB R8,R8[0x0]");
        emit("CP.W R8,0x0");
        emit("BR{ne} 0x8001c540");
        // Do not cut the previous 3 ms attack merely because a later edge
        // is queued. Its pitch scan may have completed only a moment ago.
        emit("LD.UB R8,R10[0x26]");
        emit("CP.W R8,0x0");
        emit("BR{eq} 0x8001c47c");
        emit("LD.W R8,R10[0x20]");
        emit("MFSR R9,COUNT");
        emit("SUB R8,R9,R8 << 0x0");
        emit("LD.W R9,R10[0x10]");
        // Four milliseconds of COUNT: the whole spike at the default five
        // units (measured: units are (n - 1) ms), and spike plus margin at
        // anything shorter.  trigger_spike_units is bounded at 5 so the
        // spike can never outgrow this window.
        emit("LSL R9,0x2");
        emit("SUB R9,0x1");
        emit("CP.W R8,R9");
        emit("BR{ls} 0x8001c540");      // unsigned: long idle is not a negative age
        padTo(0x8001c47cL);
        emit("LD.UB R8,R10[0x0]");
        emit("LD.UB R9,R10[0x1]");
        emit("CP.W R8,R9");
        emit("BR{eq} 0x8001c540");
        emit("MOV R8,0x6260");
        emit("ADD R8,R8,R9 << 0x2");
        emit("LD.W R12,R8[0x0]");
        emit("SUB R9,-0x1");
        emit("ANDL R9,0x1f");
        emit("ST.B R10[0x1],R9");
        emit("MTSR SR,R0");
        emit("MCALL PC[0x8001c574]");
        emit("LDM SP++,R0,R7,PC");
        padTo(0x8001c4e0L);
        emit("MOV R8,0x0");
        emit("ST.B R10[0x2],R8");
        emit("ST.B R10[-0x1],R8");       // acquired divider releases only here
        emit("LD.UB R9,R10[0x0]");
        emit("ST.B R10[0x1],R9");       // discard stale/off-mode queued edges
        emit("MOV R9,0x61ea");
        emit("ST.H R9[0x0],R8");
        emit("ST.H R9[0x2],R8");
        emit("LD.SH R8,R11[0x34a]");
        emit("ST.H R11[0x38e],R8");
        padTo(0x8001c540L);
        emit("MTSR SR,R0");
        emit("LDM SP++,R0,R7,PC");
        padTo(0x8001c570L);
        word(0x00003560L);
        word(0x8001c800L);
        finish("clock_service", 0x8001c580L);

        // After the real pitch remap/store. Keep a clock step in flight until
        // its own trigger has actually fired, including a configured settle
        // wait. Back-to-back catch-up scans must not merge physical spikes:
        // leave the request pending if the last output is less than 4 ms old.
        begin(0x8001c600L);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        // The guard above moves the fire label along by the eight bytes it
        // costs; everything after 0x8001c66c is where it always was.
        long outFire = twoPhaseBeat() ? 0x8001c650L : 0x8001c648L;
        if (twoPhaseBeat()) {
            // While the flush owns the step (claim 2 or 3) 0x60ee counts
            // MILLISECONDS, and the scan reading them as scans would spend
            // the settle five times over and raise the gate itself.  Leave
            // the whole step alone; the flush is the one holding it.
            emit("MOV R8,0x625b");
            emit("LD.UB R9,R8[0x0]");
            emit("CP.W R9,0x1");
            emit("BR{hi} 0x8001c688");
        }
        emit("MOV R10,0x6234");
        emit("MOV R8,0x60ee");
        emit("LD.UB R9,R8[0x0]");
        emit("CP.W R9,0x0");
        emit("BR{eq} 0x8001c678");
        emit("CP.W R9,0x1");
        emit("BR{gt} 0x8001c66c");
        emit("LD.UB R9,R10[0x3]");
        emit("CP.W R9,0x0");
        emit(String.format("BR{eq} 0x%x", outFire));
        emit("LD.UB R9,R10[0x26]");
        emit("CP.W R9,0x0");
        emit(String.format("BR{eq} 0x%x", outFire));
        emit("LD.W R9,R10[0x20]");
        emit("MFSR R11,COUNT");
        emit("SUB R9,R11,R9 << 0x0");
        emit("LD.W R11,R10[0x10]");
        emit("LSL R11,0x2");
        emit("SUB R11,0x1");
        emit("CP.W R9,R11");
        emit("BR{ls} 0x8001c688");
        padTo(outFire);
        emit("MOV R9,0x0");
        emit("ST.B R8[0x0],R9");
        emit("MCALL PC[0x8001c6b0]");
        emit("MOV R10,0x6234");
        emit("MFSR R9,COUNT");
        emit("ST.W R10[0x20],R9");
        emit("MOV R9,0x1");
        emit("ST.B R10[0x26],R9");
        emit("RJMP 0x8001c678");
        padTo(0x8001c66cL);
        emit("SUB R9,0x1");
        emit("ST.B R8[0x0],R9");
        emit("RJMP 0x8001c688");
        padTo(0x8001c678L);
        emit("MOV R9,0x0");
        emit("ST.B R10[0x3],R9");       // a rest/tie completes here too
        // The scan and the 1 kHz flush are separate dispatcher events and
        // nothing orders them within a millisecond.  Whichever of them
        // completes the step drops the other's claim on it, or a scan that
        // got there first would fire, and the flush would fire again behind
        // it.  Reached only once the step is done: the attack-age retry
        // branches past this and leaves both claims standing.
        if (block("clock_fast_trigger")) {
            emit("MOV R8,0x625b");
            emit("ST.B R8[0x0],R9");
        }
        padTo(0x8001c688L);
        emit("LDM SP++,R7,PC");
        padTo(0x8001c6b0L);
        // Diagnostic builds route the gate through the latency shim, which
        // stamps the delay and tail-calls the real routine.
        word(block("clock_latency") ? 0x8001bbc0L : 0x800077f8L);
        word(0x8001c600L);
        finish("clock_output", 0x8001c6c0L);

        // The pitch remap, entered PAST the per-scan chain at its head.
        // 0x80019980 opens with a call to 0x8001a2e8 - the tuning applier,
        // the per-scan housekeeping and the VIBRATO ENGINE - every one of
        // which advances state once per scan.  The fast trigger below runs at
        // 1 kHz, so it enters at 0x8001998e and takes only the arithmetic:
        // the vibrato OFFSET the engine last computed, the calibration table,
        // and the stores to DAC slot 2 and the last-sent mirror.  Entering
        // there means building the frame the remap's own tail returns from.
        begin(0x8001c0e0L);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("LDDPC R8,0x8001c0ec");
        emit("MOV PC,R8");
        padTo(0x8001c0ecL);
        word(0x8001998eL); // pitch_remap_calibration, minus its chain
        finish("clock_remap_bare", 0x8001c0f0L);

        // The trigger's rise, moved off the 5 ms pitch scan and onto the 1 kHz
        // DAC flush.  Measured on the emitted firmware, the delay from an
        // accepted edge to the physical spike was uniform over a whole scan
        // period - 0 to 4.8 ms, with no fixed component to trim.  The gate's
        // FALL was already on the edge itself, which is why the scope showed
        // a rock-steady drop followed by a wandering rise.
        //
        // clock_settle arms the byte at 0x625b beside the countdown it
        // already sets; whichever of the two contexts reaches the step first
        // fires it and clears the other's claim, so the trigger can never go
        // out twice.  This one is reached from the DAC-flush cave every
        // millisecond and takes the step whenever it may.
        //
        // Called from a tail-jump, not a call: R8..R12 and LR are the
        // interpolator's scratch already, R7 is saved here.
        begin(0x8001c100L);
        // Two-phase moves every label after the glide guard along by 0x20 and
        // takes the block out to 0x8001c200, which is free.  Without it every
        // address below is the one that shipped, so that build is unchanged.
        long fastFire  = twoPhaseBeat() ? 0x8001c150L : 0x8001c14cL;
        long fastClear = twoPhaseBeat() ? 0x8001c15cL : 0x8001c14cL;
        long fastJoin  = twoPhaseBeat() ? 0x8001c168L : 0x8001c14cL;
        long fastClampLow  = twoPhaseBeat() ? 0x8001c17cL : 0x8001c166L;
        long fastClampHigh = twoPhaseBeat() ? 0x8001c188L : 0x8001c172L;
        long fastStage = twoPhaseBeat() ? 0x8001c198L : 0x8001c180L;
        long fastDecline = 0x8001c1c0L;
        long fastExit  = twoPhaseBeat() ? 0x8001c1d4L : 0x8001c1c8L;
        long fastPool  = twoPhaseBeat() ? 0x8001c1d8L : 0x8001c1d0L;
        // R0 carries the CLAIM through the staging below, which needs every
        // one of R8..R12: 1 fires here and now, 2 stages the pitch and starts
        // the settle, 3 is that settle having run out.  R0 is callee-saved,
        // so it joins the frame rather than being borrowed.
        if (twoPhaseBeat()) emit("STM --SP,R0,R7,LR"); else emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("MOV R8,0x625b");
        if (twoPhaseBeat()) emit("LD.UB R0,R8[0x0]"); else emit("LD.UB R9,R8[0x0]");
        if (twoPhaseBeat()) emit("CP.W R0,0x0"); else emit("CP.W R9,0x0");
        emit(String.format("BR{eq} 0x%x", fastExit));   // nothing armed
        if (twoPhaseBeat()) {
            // Claim 3 is a settle already running: the pitch went out on an
            // earlier flush and the gate is waiting on the millisecond timer,
            // which is not us.  When it does run out the gate still owes the
            // attack-age guard below, so this rejoins rather than jumping.
            emit("CP.W R0,0x3");
            emit("BR{ne} 0x8001c11e");
            emit("MOV R9,0x60ee");
            emit("LD.UB R9,R9[0x0]");
            emit("CP.W R9,0x0");
            emit(String.format("BR{ne} 0x%x", fastExit));
            padTo(0x8001c11eL);
        }
        // The same 4 ms attack-age guard the scan path applies: a spike must
        // not be stacked under one the factory countdown is still holding.
        // Staying armed retries on the next tick rather than dropping a beat.
        emit("MOV R10,0x6234");
        emit("LD.UB R9,R10[0x26]");
        emit("CP.W R9,0x0");
        emit("BR{eq} 0x8001c140");
        emit("LD.W R9,R10[0x20]");
        emit("MFSR R11,COUNT");
        emit("SUB R9,R11,R9 << 0x0");
        emit("LD.W R11,R10[0x10]");
        emit("LSL R11,0x2");
        emit("SUB R11,0x1");
        emit("CP.W R9,R11");
        emit(String.format("BR{ls} 0x%x", fastExit));
        padTo(0x8001c140L);
        if (twoPhaseBeat()) {
            // A settle that has run out has already been through the guard
            // below, five milliseconds ago, and its pitch is already in the
            // DAC.  Only the gate is left, so nothing here may decline it -
            // a decline now would strand the note with no trigger at all.
            emit("CP.W R0,0x3");
            emit(String.format("BR{eq} 0x%x", fastFire));
        }
        // The glide has to be snapping for this to reach the same answer the
        // scan would.  The fast path stages the step's TARGET; the scan
        // stages wherever the glide engine has got to.  At the fastest rate
        // those agree to well under one DAC count, and a blend build forces
        // that rate.  With a real portamento time they do not agree, so the
        // beat goes back to the scan rather than have its pitch jump.
        emit("MOV R9,0x2eee");
        emit("LD.SH R9,R9[0x0]");
        emit("CP.W R9,0x0");
        emit(String.format("BR{ne} 0x%x", fastDecline));
        // Only two-phase needs a label here; without it the fire path simply
        // follows the guard, exactly as the shipped block does.
        if (twoPhaseBeat()) padTo(fastFire);
        if (twoPhaseBeat()) {
            // Claim 2 is the beat's FIRST flush: the pitch goes out below and
            // the countdown becomes MILLISECONDS, for the 1 ms timer to spend
            // while the output RC travels.  Claim 1 and claim 3 both fire
            // outright, so both clear the claim and the countdown together.
            // 0x60ee already holds this path's wait in MILLISECONDS -
            // clock_settle put it there when it set the claim, external or
            // internal - so there is nothing to convert here.  Marking the
            // claim as settling hands that countdown to the 1 ms timer.
            emit("CP.W R0,0x2");
            emit(String.format("BR{ne} 0x%x", fastClear));
            emit("MOV R9,0x3");
            emit("ST.B R8[0x0],R9");
            emit(String.format("RJMP 0x%x", fastJoin));
            padTo(fastClear);
        }
        emit("MOV R9,0x0");
        emit("ST.B R8[0x0],R9");        // consume the arm
        emit("MOV R8,0x60ee");
        emit("ST.B R8[0x0],R9");        // and the scan's countdown with it
        if (twoPhaseBeat()) padTo(fastJoin);
        emit(String.format("LDDPC R10,0x%x", fastPool));
        emit("LD.SH R12,R10[0x352]");   // the step's own pitch TARGET
        // The bend strip's offset, which the 200 Hz scan adds to the target
        // at 0x800031f4 before clamping the result into 0x3210.  Leaving it
        // out was the pitch bleed: the fast path drove DAC slot 2 to a
        // bend-less note under every trigger and the scan only corrected it
        // up to 5 ms later.  Reading the cell advances nothing - bend()
        // maintains it from the strip's own pass, not from the pitch scan.
        emit("LD.SH R11,R10[0x216]");
        emit("ADD R12,R11");
        // and the scan's own clamp, both ends, so what is staged here cannot
        // leave the range 0x3210 is held to.
        emit("CP.W R12,0x0");
        emit(String.format("BR{ge} 0x%x", fastClampLow));
        emit("MOV R12,0x0");
        padTo(fastClampLow);
        emit("MOV R11,0xfff");
        emit("CP.W R12,R11");
        emit(String.format("BR{le} 0x%x", fastClampHigh));
        emit("MOV R12,R11");
        padTo(fastClampHigh);
        if (feature("pressure_blend")) {
            // The offset the conditioner last applied, so the value staged
            // here is the one the scan would stage.  Reading it advances
            // nothing: the filter and its slew both live in the scan.
            emit("MOV R11,0x60e2");
            emit("LD.SH R11,R11[0x0]");
            emit("ADD R12,R11");
            emit("CP.W R12,0x0");
            emit(String.format("BR{ge} 0x%x", fastStage));
            emit("MOV R12,0x0");
        }
        padTo(fastStage);
        emit(String.format("MCALL PC[0x%x]", fastPool + 4));  // pitch, slot 2
        if (twoPhaseBeat()) {
            // Phase A ends here.  The CV now has the whole settle to travel
            // before the gate follows it out on a later flush.
            emit("CP.W R0,0x2");
            emit(String.format("BR{eq} 0x%x", fastExit));
        }
        emit(String.format("MCALL PC[0x%x]", fastPool + 8));  // gate, slot 0
        emit("MOV R10,0x6234");
        emit("MFSR R9,COUNT");
        emit("ST.W R10[0x20],R9");
        emit("MOV R9,0x1");
        emit("ST.B R10[0x26],R9");
        emit("MOV R9,0x0");
        emit("ST.B R10[0x3],R9");       // the step is complete
        emit(String.format("RJMP 0x%x", fastExit));
        padTo(fastDecline);
        emit("MOV R9,0x0");
        emit("ST.B R8[0x0],R9");        // disarm only; 0x60ee still fires it
        if (twoPhaseBeat()) {
            // ...except under claim 2, where 0x60ee is holding milliseconds
            // the scan would read as scans and spend five times over.  Hand
            // it a scan count instead: the next scan takes the step, which is
            // exactly what declining the glide asked for.  Claim 3 never
            // arrives here - a settle that has run out skips the guard.
            emit("CP.W R0,0x1");
            emit(String.format("BR{eq} 0x%x", fastExit));
            emit("MOV R8,0x60ee");
            emit("MOV R9,0x1");
            emit("ST.B R8[0x0],R9");
        }
        padTo(fastExit);
        if (twoPhaseBeat()) emit("LDM SP++,R0,R7,PC"); else emit("LDM SP++,R7,PC");
        padTo(fastPool);
        word(0x00003560L); // global state base
        word(0x8001c0e0L); // remap, minus the per-scan chain
        word(block("clock_latency") ? 0x8001bbc0L : 0x800077f8L);
        finish("clock_fast_trigger", twoPhaseBeat() ? 0x8001c1f0L : 0x8001c1e0L);

        // Ending a take has to end the NOTE, not just the mode.  The
        // sequencer's note is started and stopped by the arp's step function,
        // and everything that ends one - the MIDI note-off, the gate, the
        // trigger light - lives inside it at 0x80002218..0x800022c2.  Stop
        // and clear change the mode and then wait for the next step to tidy
        // up.  With RATE at zero, or with an external clock locked and then
        // taken away, there IS no next step: the gate sits at its 5 V sustain
        // and the MIDI note stays on, for as long as the instrument is
        // powered.
        //
        // So this does what that step would have done, with the factory's own
        // routines and in the factory's own order.  It preserves R9 and R12
        // because seq_enter is still holding the mode being entered and the
        // sequencer's block in them.
        begin(0x8001b448L);
        emit("STM --SP,R0,R1,R7,R9,R12,LR");
        emit("MOV R7,SP");
        emit("LDDPC R1,0x8001b4d0");    // global state base
        emit("MOV R8,0x2eed");
        emit("LD.UB R8,R8[0x0]");       // the factory's own active-note flag
        emit("CP.W R8,0x0");
        emit("BR{eq} 0x8001b4b8");      // nothing is sounding
        emit("MOV R8,0x2ee4");
        // The low byte of the halfword, which is what the factory's own
        // CASTU.B takes from it - this processor is big-endian, so that byte
        // is the second one.  The note, kept where a call cannot reach it.
        emit("LD.UB R0,R8[0x1]");
        // The 208's own bus first, when it is the one carrying the note.
        emit("MOV R8,0x2efa");
        emit("LD.UB R8,R8[0x0]");
        emit("CP.W R8,0x0");
        emit("BR{eq} 0x8001b48c");
        emit("LD.W R8,R1[0x4]");
        emit("CP.W R8,0x0");
        emit("BR{eq} 0x8001b48c");
        emit("LD.UB R12,R1[0x0]");
        emit("MCALL PC[0x8001b4d4]");   // take the bus
        emit("MOV R10,R0");
        emit("LD.W R11,R1[0x4]");
        emit("LD.UB R12,R1[0x34e]");
        emit("MCALL PC[0x8001b4d8]");   // note off, on the bus
        emit("LD.UB R12,R1[0x0]");
        emit("MCALL PC[0x8001b4dc]");   // and give it back
        padTo(0x8001b48cL);
        emit("LD.UB R10,R1[0x2e7]");
        emit("MOV R11,R0");
        emit("LD.UB R12,R1[0x34e]");
        emit("MCALL PC[0x8001b4e0]");   // note off, one port
        emit("LD.UB R10,R1[0x2e7]");
        emit("MOV R11,R0");
        emit("LD.UB R12,R1[0x34e]");
        emit("MCALL PC[0x8001b4e4]");   // and the other
        emit("MOV R8,0x2eed");
        emit("MOV R9,0x0");
        emit("ST.B R8[0x0],R9");        // nothing is sounding now
        padTo(0x8001b4b8L);
        emit("MCALL PC[0x8001b4e8]");   // gate to zero, and flushed
        emit("MOV R12,0x4");
        emit("MCALL PC[0x8001b4ec]");   // the trigger light with it
        emit("MOV R8,0x60ee");
        emit("MOV R9,0x0");
        emit("ST.B R8[0x0],R9");        // and no deferred pulse outlives the stop
        emit("LDM SP++,R0,R1,R7,R9,R12,PC");
        padTo(0x8001b4d0L);
        word(0x00003560L); // global state base
        word(0x8000f1f0L); // take the 208 bus
        word(0x8000f3a8L); // note off on the bus
        word(0x8000f160L); // give the bus back
        word(0x80007e44L); // MIDI note off, port one
        word(0x800081f0L); // MIDI note off, port two
        word(0x80002440L); // gate to zero and flush it
        word(0x800068ccL); // led_clear(ch)
        finish("seq_release", 0x8001b4f0L);

        // A Buchla trigger is a 10 V spike that drops to a 5 V sustain only
        // while the note is HELD, and to 0 when it is let go.  The factory
        // schedules that drop three counts after the spike (0x8000788a) and
        // performs it at 0x80007540, which is the pool word this replaces.
        //
        // A sequencer step that is not tied into the next one is not held by
        // anything: it should go to 0 there, not sit at the sustain for the
        // rest of the step.  Which is which is seq_gate's decision, asked
        // rather than repeated, so the gate and the pulse can never disagree
        // about the same step.
        //
        // R12 is the scheduler's own message pointer.  The factory routine
        // stores and increments it and then never reads it, but it is handed
        // back untouched all the same.
        begin(0x8001b320L);
        emit("STM --SP,R0,R7,LR");
        emit("MOV R7,SP");
        emit("MOV R0,R12");
        emit("MOV R8,0x6154");
        emit("LD.UB R8,R8[0x4]");
        emit("CP.W R8,0x2");
        emit("BR{ne} 0x8001b342");      // not playing: the factory's own drop
        emit("MCALL PC[0x8001b350]");   // seq_gate -> R8, negative if held
        emit("CP.W R8,0x0");
        emit("BR{lt} 0x8001b342");      // a tie is carrying it: keep the 5 V
        emit("MCALL PC[0x8001b354]");   // to zero, and flushed
        emit("LDM SP++,R0,R7,PC");
        padTo(0x8001b342L);
        emit("MOV R12,R0");
        emit("MCALL PC[0x8001b358]");   // the factory's 10 V -> 5 V
        emit("LDM SP++,R0,R7,PC");
        padTo(0x8001b350L);
        word(0x8001b4f0L); // seq_gate
        word(0x80002440L); // gate to zero and flush it
        word(0x80007540L); // the factory's own drop to the sustain
        finish("seq_pulse_drop", 0x8001b35cL);

        // How long the arp holds its gate.  Three counts from the end of the
        // step, as the factory does - unless the step about to play is a tie,
        // and then a threshold the countdown can never reach, so the gate
        // never falls and the note carries across.  The tie's own step
        // answers the selector with -1, so nothing retriggers and the pitch
        // it is carrying stays put.  R8 = the threshold.
        //
        // Only the tie holds it.  A real note after a tie used to be held too
        // - the 303 slide - but a Buchla trigger is a 10 V spike that drops
        // to 5 V only while a note is HELD, and the note after a tie is a new
        // note.  It gets its own spike, the way the SH-101 gives one to every
        // note that is not tied.
        begin(0x8001b4f0L);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("MOV R8,0x3");
        emit("MOV R9,0x6154");
        emit("LD.UB R9,R9[0x4]");
        emit("CP.W R9,0x2");
        emit("BR{ne} 0x8001b544");      // not playing: the factory's own
        emit("MOV R10,0x61e0");
        emit("LD.UB R11,R10[0x0]");
        emit("CP.W R11,0x0");
        emit("BR{eq} 0x8001b544");
        emit("LD.UB R9,R10[0x1]");      // the step about to play
        emit("CP.W R9,R11");
        emit("BR{lt} 0x8001b520");
        // Off the end.  A LOOP wraps here and step zero is genuinely next -
        // but a one-shot preview leaves cursor==count as its end sentinel,
        // and there is nothing after the end for a tie to carry into: the
        // last note gets the factory countdown, however the take begins.
        emit("MOV R9,0x62fe");
        emit("LD.UB R9,R9[0x0]");
        emit("CP.W R9,0x0");
        emit("BR{ne} 0x8001b544");
        emit("MOV R9,0x0");
        padTo(0x8001b520L);
        emit("MOV R10,0x6160");
        emit("ADD R10,R10,R9 << 0x1");
        emit("LD.SH R10,R10[0x0]");
        // A REST is silent whatever else is going on, and it is tested first
        // for exactly that reason: a tie that runs into a rest used to keep
        // the gate up through it, because the tie's own hold was checked
        // before anyone asked what the next step was.
        emit("MOV R11,0x7ffe");
        emit("CP.W R10,R11");
        emit("BR{eq} 0x8001b544");
        emit("MOV R11,0x7fff");
        emit("CP.W R10,R11");
        emit("BR{ne} 0x8001b544");      // a real note next: it gets its own
        emit("MOV R8,-0x8000");         // a tie next: carry the gate into it
        padTo(0x8001b544L);
        emit("LDM SP++,R7,PC");
        padTo(0x8001b54cL);
        word(0x8001b4f0L); // this cave, for the caller too far away to pool it
        finish("seq_gate", 0x8001b550L);

        // The arp's OTHER gate clear.  When no key is held it drops the gate
        // and its LED at every fired step, before choosing a note - and in
        // play mode no key is ever held, so this fired on every step and no
        // tie could survive it however the countdown compare was answered.
        // Suppressing one and not the other was the whole of the bug.
        //
        // The decision is seq_gate's own, called rather than repeated, so the
        // two can never come to different conclusions about the same step: it
        // answers a negative threshold exactly when the gate is to be held.
        begin(0x8001b8a0L);
        emit("STM --SP,R0,R7,LR");
        emit("MOV R7,SP");
        emit("MCALL PC[0x8001b8d0]");   // seq_gate -> R8 = the threshold
        emit("CP.W R8,0x0");
        emit("BR{lt} 0x8001b8c4");      // held: leave the gate alone
        emit("LDDPC R9,0x8001b8d4");
        emit("MOV R8,0x0");
        emit("ST.H R9[0x354],R8");
        emit("MOV R12,0x4");
        emit("MCALL PC[0x8001b8d8]");   // and its LED
        padTo(0x8001b8c4L);
        emit("LDM SP++,R0,R7,PC");
        padTo(0x8001b8d0L);
        word(0x8001b4f0L); // seq_gate
        word(0x00003560L); // global state base
        word(0x800068ccL); // led_clear(ch)
        finish("seq_gate_clear", 0x8001b8dcL);

        // Whether the arp should send its MIDI note-off for this step.
        //
        // NOT the gate's decision, which is where this went wrong first.  The
        // CV gate is held across a tie AND across the tie into the note it
        // slides to, because that is one continuous voltage.  MIDI cannot do
        // that: the note it slides to sends its own Note On, so the old note
        // must be ended or the Ons and Offs stop balancing - note, tie, note,
        // rest sent two Ons and one Off and left a voice hanging on any
        // receiver that stacks them.
        //
        // So the rule here is only: is the step about to play a TIE?  Then
        // nothing new sounds and the note carries.  Everything else - a real
        // note, a rest, the end of a tie - ends the note that was sounding.
        begin(0x8001b8f0L);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("MOV R9,0x6154");
        emit("LD.UB R9,R9[0x4]");
        emit("CP.W R9,0x2");
        emit("BR{ne} 0x8001b934");      // not playing: the factory's answer
        emit("MOV R10,0x61e0");
        emit("LD.UB R11,R10[0x0]");
        emit("CP.W R11,0x0");
        emit("BR{eq} 0x8001b934");
        emit("LD.UB R9,R10[0x1]");      // the step about to play
        emit("CP.W R9,R11");
        emit("BR{lt} 0x8001b91c");
        // Off the end: a loop wraps, but a preview's end sentinel means no
        // step follows - no tie can carry, so the note that was sounding
        // must be ended the factory's way, Ons and Offs in balance.
        emit("MOV R9,0x62fe");
        emit("LD.UB R9,R9[0x0]");
        emit("CP.W R9,0x0");
        emit("BR{ne} 0x8001b934");
        emit("MOV R9,0x0");
        padTo(0x8001b91cL);
        emit("MOV R10,0x6160");
        emit("ADD R10,R10,R9 << 0x1");
        emit("LD.SH R10,R10[0x0]");
        emit("MOV R11,0x7fff");
        emit("CP.W R10,R11");
        emit("BR{ne} 0x8001b934");
        emit("MOV R12,0x0");            // a tie next: hold the note
        emit("LDM SP++,R7,PC");
        padTo(0x8001b934L);
        emit("MOV R9,0x2eed");
        emit("LD.UB R12,R9[0x0]");      // the factory's own active-note flag
        emit("LDM SP++,R7,PC");
        padTo(0x8001b940L);
        word(0x8001b8f0L); // this cave, for the caller too far away to pool it
        finish("seq_noteoff", 0x8001b944L);

        // Whether the trigger LED should be lit.  Event 13 lights it only
        // when something is held - a key, a touch, a note - and while the
        // sequencer plays nothing is, so the light stayed dark through a
        // sequence that was sending triggers the whole time.  Same shape as
        // the gate clear: a no-key-held test that play mode always fails.
        begin(0x8001ba60L);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("LDDPC R9,0x8001ba90");
        emit("LD.UB R12,R9[0x21a]");    // the factory's own reason to light
        emit("CP.W R12,0x0");
        emit("BR{ne} 0x8001ba84");
        emit("MOV R9,0x6154");
        emit("LD.UB R12,R9[0x4]");
        emit("CP.W R12,0x2");
        emit("BR{ne} 0x8001ba80");
        emit("MOV R12,0x1");            // playing: light it
        emit("RJMP 0x8001ba84");
        padTo(0x8001ba80L);
        emit("MOV R12,0x0");
        padTo(0x8001ba84L);
        emit("LDM SP++,R7,PC");
        padTo(0x8001ba90L);
        word(0x00003560L); // global state base
        word(0x8001ba60L); // this cave, for the caller too far away to pool it
        finish("seq_trigger_led", 0x8001ba98L);

        // Record.  Called from the note-on wrapper with R12 = the key, which
        // it must leave alone - the wrapper still needs it.  What goes in the
        // store is the PITCH, the same halfword the arp would have played for
        // that key, so a later change of tuning slot moves the keyboard
        // without moving anything already recorded.
        begin(0x8001b9d0L);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("MOV R8,0x6154");
        emit("LD.UB R9,R8[0x4]");
        emit("CP.W R9,0x1");
        emit("BR{ne} 0x8001ba28");
        emit("MOV R10,0x61e0");
        emit("LD.UB R9,R10[0x0]");
        emit("CP.W R9,0x40");           // 64 steps and no more
        emit("BR{ge} 0x8001ba28");
        // The pitch this key SOUNDS, transpose included.
        emit("MCALL PC[0x8001ba2c]");   // seq_record_pitch -> R11
        emit("MOV R8,0x6160");
        emit("ADD R8,R8,R9 << 0x1");
        emit("ST.H R8[0x0],R11");
        // The KEY as well as the pitch.  The pitch is what the CV plays, and
        // keeping it is what makes a recording survive a change of tuning -
        // but MIDI names notes by key, and answering the arp's selector with
        // a placeholder made every step of every sequence go out as note 36.
        emit("MOV R8,0x61ee");
        emit("ADD R8,R8,R9 << 0x0");
        emit("ST.B R8[0x0],R12");
        emit("SUB R9,-0x1");
        emit("ST.B R10[0x0],R9");
        // And it is left here to be HEARD - unless the arp switch is OFF,
        // because then the keyboard is live and this press already sounds
        // exactly the pitch just stored; an audition on top of it sent the
        // same MIDI note twice with only one note-off to share.  Both
        // switch bytes come in one halfword read - state+0x340 latch, then
        // +0x341 regular - reached off the step-store base already in R10.
        emit("LD.UH R8,R10[-0x2940]");
        emit("CP.W R8,0x0");
        emit("BR{eq} 0x8001ba28");
        // With the arp engaged, recording silences it and the keyboard has
        // no pulse or pitch of its own, so a bar of notes went in silent.
        // The per-scan cave steps the arp once for this key, which sounds it
        // the factory's own way - pulse, gate, trigger and MIDI together -
        // and the PITCH stored above is kept beside the key so the audition
        // sounds precisely what the take will play back, whatever slot the
        // latch toggle is about to shuffle this press into.
        emit("MOV R8,0x6230");
        emit("LD.UH R9,R8[0x0]");
        emit("CP.W R9,0x0");
        emit("BR{ne} 0x8001ba28");      // one still waiting to be heard
        emit("SUB R12,-0x1");
        emit("ST.H R8[0x0],R12");       // the key, plus one
        emit("SUB R12,0x1");            // and left as the caller had it
        emit("SUB R11,-0x1");
        emit("ST.H R10[0x320],R11");    // 0x6500: the pitch, plus one
        padTo(0x8001ba28L);
        emit("LDM SP++,R7,PC");
        padTo(0x8001ba2cL);
        word(0x8001dce0L);              // seq_record_pitch
        finish("seq_record", 0x8001ba30L);

        // Play, at the arp's own note selection.  The arp asks which key to
        // sound; while playing we answer with a valid one so the step is not
        // skipped, and put the step's pitch where the value hook below will
        // swap it in.  An empty sequence answers -1, which the arp already
        // reads as nothing this step.
        //
        // Not playing, this is the factory's own question, asked the factory's
        // way: no key held means no note.
        begin(0x8001b360L);
        emit("STM --SP,R0,R7,LR");
        emit("MOV R7,SP");
        emit("MOV R8,0x6154");
        emit("LD.UB R9,R8[0x4]");
        // Recording silences the arp.  You are playing the keyboard to put
        // notes in, and an arpeggiator chewing on what you hold is not what
        // you are listening for.
        emit("CP.W R9,0x1");
        // Recording: the arp sounds nothing of its own, but it does sound the
        // one key the note-on left waiting - see seq_record_sound.
        emit("BR{eq} 0x8001b428");
        emit("CP.W R9,0x2");
        emit("BR{ne} 0x8001b410");      // not playing: the factory's question
        emit("MOV R10,0x61e0");
        emit("LD.UB R11,R10[0x0]");     // how many steps there are
        emit("CP.W R11,0x0");
        emit("BR{eq} 0x8001b400");      // none: silence
        emit("MCALL PC[0x8001b408]");   // preview end, or normal wrap
        emit("CP.W R9,0x0");
        emit("BR{lt} 0x8001b400");      // end returns silence, not step zero
        padTo(0x8001b38aL);
        emit("MOV R8,0x6160");
        emit("ADD R8,R8,R9 << 0x1");
        emit("LD.SH R8,R8[0x0]");
        // A rest and a tie are kept where a pitch cannot reach.  Both answer
        // -1, so nothing is retriggered; what separates them is the gate,
        // which seq_gate holds up across a tie and lets fall on a rest.
        emit("MOV R12,0x7ffe");
        emit("CP.W R8,R12");
        emit("BR{ge} 0x8001b3d4");
        emit("MOV R12,0x61e2");
        emit("ST.H R12[0x0],R8");       // the pitch this step sounds
        // and the key it was played on, which is what MIDI names it by.  R0
        // carries it past the advance and the slide bookkeeping below, both
        // of which want the other registers.  Answering with a placeholder
        // instead sent every step of every sequence out as the same note.
        emit("MOV R12,0x61ee");
        emit("ADD R12,R12,R9 << 0x0");
        emit("LD.UB R0,R12[0x0]");
        emit("MCALL PC[0x8001b43c]");   // which step plays next
        padTo(0x8001b3b8L);
        emit("ST.B R10[0x1],R9");
        // This step moves the pitch, so it is the one that spends the slide a
        // tie armed.  The glide clamp reads the same cell every scan.
        emit("MOV R12,0x61e5");
        emit("LD.UB R8,R12[0x0]");
        emit("CP.W R8,0x0");
        emit("BR{eq} 0x8001b3ca");
        emit("SUB R8,0x1");
        emit("ST.B R12[0x0],R8");
        padTo(0x8001b3caL);
        emit("MOV R12,R0");             // the key this step was recorded on
        emit("LDM SP++,R0,R7,PC");
        padTo(0x8001b3d4L);
        // A rest or a tie: step past it, sound nothing new.  A tie arms the
        // slide into whatever follows; a rest ENDS one, so the note after a
        // rest attacks cleanly rather than sliding in from a note two steps
        // back that the rest already silenced.
        emit("MOV R12,0x7fff");
        emit("CP.W R8,R12");
        emit("MOV R8,0x0");             // a rest: the slide ends here
        emit("BR{ne} 0x8001b3e0");
        emit("MOV R8,0x2");             // a tie: it arms one
        padTo(0x8001b3e0L);
        emit("MOV R12,0x61e5");
        emit("ST.B R12[0x0],R8");
        emit("MCALL PC[0x8001b43c]");   // which step plays next
        padTo(0x8001b3f0L);
        emit("ST.B R10[0x1],R9");
        emit("MOV R12,0x0");
        emit("SUB R12,0x1");
        emit("LDM SP++,R0,R7,PC");
        padTo(0x8001b400L);
        emit("MOV R12,0x0");
        emit("SUB R12,0x1");            // nothing recorded: silence
        emit("LDM SP++,R0,R7,PC");
        padTo(0x8001b408L);
        word(0x8001d800L);              // seq_preview_step
        padTo(0x8001b410L);
        emit("LDDPC R9,0x8001b430");
        emit("LD.UB R8,R9[0x21a]");
        emit("CP.W R8,0x0");
        emit("BR{eq} 0x8001b400");
        emit("MOV R12,0x21b");
        emit("ADD R12,R9");             // &state[0x21b], the held-key flags
        emit("MCALL PC[0x8001b434]");   // the selector this build installed
        emit("LDM SP++,R0,R7,PC");
        padTo(0x8001b428L);
        emit("MCALL PC[0x8001b438]");   // the key waiting to be heard, or -1
        emit("LDM SP++,R0,R7,PC");
        padTo(0x8001b430L);
        word(0x00003560L); // global state base
        word(arpSelector);
        word(0x8001b2e8L); // what the selector answers while recording
        word(0x8001d860L); // preview-aware next step; normal play still shuffles
        finish("seq_select", 0x8001b440L);

        // Which step plays next.
        //
        // Knob 1's BLEND setting reaches a sequence: the knob is the chance,
        // out of 128, that the next step is any step rather than the one after
        // this.  At zero it is the recorded order exactly, which is what it
        // has always been.  Knob 1's other setting - the six note-order zones
        // - is the keyboard's alone and leaves a recorded order as it was
        // played.
        //
        // Rests and ties come along unchanged, and still mean what they meant:
        // a tie holds whatever is sounding and a rest silences it, whichever
        // note the shuffle has put them beside.
        //
        // R9 = the step that just played, R11 = how many there are.  R9 comes
        // back as the next one.  R0, R10 and R11 are the caller's.
        begin(0x8001baa0L);
        emit("STM --SP,R0,R1,R2,R7,R10,R11,LR");
        emit("MOV R7,SP");
        if (number("knob1_orders", 0, 0, 1) == 0) {
            emit("MOV R8,0x60f2");
            emit("LD.UB R8,R8[0x0]");   // knob 1, 0..127
            emit("CP.W R8,0x0");
            emit("BR{eq} 0x8001bad4");  // at zero the draw is not even taken
            // Everything still needed after the draw goes into R0..R2, which
            // the ABI makes a callee preserve - the PRNG destroys R8..R12,
            // and holding the blend and the count in two of them made every
            // shuffled advance a product of rubbish.
            emit("MOV R0,R8");          // the blend
            emit("MOV R1,R9");          // the step that just played
            emit("MOV R2,R11");         // how many there are
            emit("MCALL PC[0x8001bae0]");   // the factory PRNG
            emit("MOV R9,R1");
            emit("MOV R11,R2");
            emit("BFEXTU R10,R12,0x0,0x7");
            emit("CP.W R10,R0");
            emit("BR{ge} 0x8001bad4");
            emit("BFEXTU R9,R12,0x8,0x8");
            emit("MUL R9,R9,R11");
            emit("LSR R9,0x8");         // 0 .. count-1, without a divide
            emit("RJMP 0x8001badc");
        }
        padTo(0x8001bad4L);
        emit("SUB R9,-0x1");
        emit("CP.W R9,R11");
        emit("BR{lt} 0x8001badc");
        emit("MOV R9,0x0");
        padTo(0x8001badcL);
        emit("LDM SP++,R0,R1,R2,R7,R10,R11,PC");
        padTo(0x8001bae0L);
        word(0x80013e04L); // the factory PRNG
        finish("seq_next_step", 0x8001bae4L);



        // Whether this 1 kHz call is the arp's beat.  The factory said yes
        // whenever the countdown had run out - which is right when the
        // internal timer is the only timer, and wrong the moment a clock is
        // patched in: chatter could leave the countdown short, it ran out
        // between pulses, and the arp free-ran at the RATE knob's own tempo
        // over the top of the clock.  At the knob's fast end that was a
        // continuous spray of notes; at the slow end a phantom note midway
        // between pulses.  Measured off the instrument, both.
        //
        // So: a pulse-driven step (interval -1) always proceeds; a countdown
        // still running always holds; and a countdown that has run out
        // proceeds only when NO clock is about.  While one is, the beat
        // belongs to the pulses, and the countdown is pushed one clock
        // interval ahead instead - gate-off still rides it on the way down.
        // "About" is the divider's own presence byte, held by any plausible
        // pulse and cleared by the two-second release.
        //
        // Called from inside the factory arp step with its frame in R7:
        // R7[-0x10] is the interval this call was made with.  R8 comes back
        // 0 to proceed, 1 to hold; a leaf, so LR is the way back.
        begin(0x8001baf0L);
        emit("LDDPC R9,0x8001bb30");    // global state base
        emit("LD.SH R10,R9[0x38e]");    // the countdown, signed
        emit("LD.SH R11,R7[-0x10]");    // the interval argument
        emit("MOV R8,-0x1");
        emit("CP.W R11,R8");
        emit("BR{eq} 0x8001bb26");      // a pulse: step now, whatever the count
        emit("CP.W R10,0x0");
        emit("BR{gt} 0x8001bb20");      // not time yet
        emit("MOV R8,0x6236");          // ISR-owned presence, even before dequeue
        emit("LD.UB R8,R8[0x0]");
        emit("CP.W R8,0x0");
        emit("BR{eq} 0x8001bb26");      // no clock about: the timer is the timer
        emit("MOV R8,0x61ea");
        emit("LD.UH R8,R8[0x0]");
        emit("CP.W R8,0x0");
        emit("BR{ne} 0x8001bb1c");
        emit("MOV R8,0x2");             // no interval measured yet: a moment
        padTo(0x8001bb1cL);
        emit("ST.H R9[0x38e],R8");      // held off for one more interval
        padTo(0x8001bb20L);
        emit("MOV R8,0x1");
        emit("MOV PC,LR");
        padTo(0x8001bb26L);
        emit("MOV R8,0x0");
        emit("MOV PC,LR");
        padTo(0x8001bb30L);
        word(0x00003560L); // global state base
        finish("clock_gate", 0x8001bb34L);

        // A selected OUTPUT note owns gate-low, not a raw GPIO interrupt.
        // The divider and the sequencer's rest/tie decision have already run.
        // This is a non-leaf: preserve LR across the physical gate-off call.
        begin(0x8001c700L);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("MOV R8,0x60ee");
        emit("LD.UB R9,R8[0x0]");
        emit("CP.W R9,0x0");
        emit("BR{ne} 0x8001c760");
        emit("MOV R10,0x6237");
        emit("LD.UB R10,R10[0x0]");
        emit("CP.W R10,0x0");
        emit("BR{eq} 0x8001c730");
        emit("MCALL PC[0x8001c778]");
        padTo(0x8001c730L);
        emit("MOV R8,0x60ee");
        emit(String.format("MOV R9,0x%x",
            number("gate_settle_scans", 1, 0, 3) + 1));
        emit("MOV R10,0x6236");
        emit("LD.UB R10,R10[0x0]");
        emit("CP.W R10,0x0");
        // No external clock: the internal beat and a bare keyboard note both
        // arrive here.  The tail at 0x8001c780 sorts them out; without the
        // two-phase claim built, both go straight to the scan as before.
        if (twoPhaseBeat()) emit("BR{eq} 0x8001c780"); else emit("BR{eq} 0x8001c756");
        emit(String.format("MOV R9,0x%x",
            number("clock_settle_scans", 0, 0, 3) + 1));
        // A clock is present, so the fast trigger takes this step off the
        // scan.  Both claims are set; the first context to reach the step
        // clears the other.  A settle asked for in SCANS no longer hands the
        // step back to the scan - the flush stages the pitch and holds the
        // gate for the same wait, counted in milliseconds, so the wait costs
        // what it is for and not the 5 ms grid it used to be rounded to.
        if (twoPhaseBeat()) {
            emit("MOV R10,0x625b");
            emit(String.format("MOV R11,0x%x",
                 claimFor(number("clock_settle_scans", 0, 0, 3))));
            emit("ST.B R10[0x0],R11");
            if (number("clock_settle_scans", 0, 0, 3) > 0) {
                emit(String.format("MOV R9,0x%x",
                     settleMsFor(number("clock_settle_scans", 0, 0, 3))));
            }
        }
        padTo(0x8001c756L);
        emit("ST.B R8[0x0],R9");
        padTo(0x8001c760L);
        emit("LDM SP++,R7,PC");
        padTo(0x8001c778L);
        word(0x80002440L);
        if (twoPhaseBeat()) {
            // The internal clock's beat, not the player's finger.  With the
            // arp or the sequencer running the note has already been chosen,
            // so the wait below belongs to the output RC and nothing else -
            // claim the step for the flush and let it hold the gate for the
            // settle in MILLISECONDS.  A key pressed with both switched off
            // is a different thing: its latency is the player's own, and it
            // is left on the scan exactly as it was.
            padTo(0x8001c780L);
            emit("LDDPC R11,0x8001c7b8");
            emit("LD.UB R12,R11[0x340]");
            emit("LD.UB R11,R11[0x341]");
            emit("OR R12,R11");
            emit("CP.W R12,0x0");
            emit("BR{eq} 0x8001c756");
            emit("MOV R10,0x625b");
            emit(String.format("MOV R11,0x%x",
                 claimFor(number("gate_settle_scans", 1, 0, 3))));
            emit("ST.B R10[0x0],R11");
            if (number("gate_settle_scans", 1, 0, 3) > 0) {
                emit(String.format("MOV R9,0x%x",
                     settleMsFor(number("gate_settle_scans", 1, 0, 3))));
            }
            emit("RJMP 0x8001c756");
            padTo(0x8001c7b8L);
            word(0x00003560L); // global state base
        }
        finish("clock_settle", twoPhaseBeat() ? 0x8001c7c0L : 0x8001c780L);

        // The pitch the arp is about to sound.  While the sequencer plays that
        // is the step's own pitch; otherwise it is whatever the keyboard
        // handed up.  Either way the octave randomiser runs on it AFTER it is
        // chosen, so knob 3 displaces sequenced notes the same way it
        // displaces played ones - it used to run first and have its answer
        // thrown away by the step.  The pad octave transpose is applied
        // further downstream and so still applies.
        begin(0x8001ba30L);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("MOV R9,0x6154");
        emit("LD.UB R9,R9[0x4]");
        emit("CP.W R9,0x2");
        emit("BR{ne} 0x8001ba46");
        emit("MOV R9,0x61e2");
        emit("LD.SH R8,R9[0x0]");       // the step's own pitch
        padTo(0x8001ba46L);
        emit("MCALL PC[0x8001ba50]");   // and then the octave randomiser
        emit("LDM SP++,R7,PC");
        padTo(0x8001ba50L);
        word(0x80019da8L); // the octave entry this replaces
        finish("seq_pitch", 0x8001ba54L);

        // Note-off pointer pools -> latch-gated wrapper.
        // Global vibrato on knob 4 (one-knob law: depth and rate
        // rise together; +-33 cents and 1..6 Hz at full; deadzone = off).
        // Pressure scales the effective knob from one-half to full value.
        // Runs at 200 Hz from applier_plus. RAM: 0x60f0 knob latch
        // (edit-gated — knob 4 in edit still sets the pressure curve),
        // 0x6024 LFO phase, 0x6026 smoothed depth (steps +-1/scan, ~65 ms
        // swell), 0x6028 signed output offset in pitch units.
        begin(0x8001a350L);
        emit("STM --SP,R0,R1,R7,LR");
        emit("MOV R7,SP");
        emit("LDDPC R10,0x8001a470");
        emit("LD.UB R8,R10[0x39]");
        emit("CP.W R8,0x1");
        emit("BR{eq} 0x8001a374");
        // and not while pad 4 is using knob 4 to set its own voltage - the
        // same rule the other three knobs answer to, through the same
        // knob_pickup helper, so the latch also stays parked after the edit
        // until the knob moves again instead of snapping to it on release.
        emit("MOV R11,0x3");
        emit("MCALL PC[0x8001ddd4]");
        emit("CP.W R9,0x0");
        emit("BR{ne} 0x8001a374");
        emit("LD.SH R8,R10[0x310]");
        emit("MOV R9,0x60f0");
        emit("ST.H R9[0x0],R8");
        padTo(0x8001a374L);
        emit("MOV R9,0x60f0");
        emit("LD.SH R11,R9[0x0]");
        emit("CP.W R11,0x30");
        emit("BR{ge} 0x8001a384");
        emit("MOV R11,0x30");
        padTo(0x8001a384L);
        emit("SUB R11,0x30");
        emit("MCALL PC[0x8001aca0]");
        emit("MOV R8,0xd0");            // depth target in Q4 pitch units
        emit("MUL R8,R8,R11");
        emit("LSR R8,0xa");
        emit("MOV R9,0x6026");
        emit("LD.SH R12,R9[0x0]");
        emit("CP.W R12,0xd0");
        emit("BR{ls} 0x8001a3a8");
        emit("MOV R12,0x0");
        // Depth slews toward the target at 16 Q4-units (one whole pitch unit)
        // per scan, the same ~65 ms swell as the old integer step, and snaps
        // once it is within one step so it lands on the fractional target
        // instead of oscillating around it.
        padTo(0x8001a3a8L);
        emit("SUB R1,R8,R12 << 0x0");   // gap = target - depth
        emit("CP.W R1,0x10");
        emit("BR{gt} 0x8001a3bc");
        emit("CP.W R1,-0x10");
        emit("BR{lt} 0x8001a3c2");
        emit("MOV R12,R8");             // within a step: land exactly
        emit("RJMP 0x8001a3c8");
        padTo(0x8001a3bcL);
        emit("SUB R12,-0x10");
        emit("RJMP 0x8001a3c8");
        padTo(0x8001a3c2L);
        emit("SUB R12,0x10");
        padTo(0x8001a3c8L);
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
        // Interpolate the sine between table entries with the phase fraction.
        // 64 entries over a 16-bit phase is a 5.6-degree step; without this
        // the LFO is a staircase no matter how fine the depth is.  The table
        // carries a 65th entry repeating the first, so the neighbour read
        // needs no wrap.
        emit("MOV R11,R8");             // keep the phase
        emit("LSR R8,0xa");             // table index, 0..63
        emit("LDDPC R0,0x8001a474");
        emit("ADD R0,R0,R8 << 0x1");
        emit("LD.SH R10,R0[0x0]");
        emit("LD.SH R8,R0[0x2]");
        emit("SUB R8,R10");             // delta to the next entry
        emit("BFEXTU R11,R11,0x0,0xa"); // phase fraction
        emit("MUL R8,R8,R11");
        emit("ASR R8,0xa");
        emit("ADD R8,R10");             // Q7 sine, interpolated
        // Amplitude, carrying the remainder between scans.  The offset leaves
        // here in whole pitch units — 2.48 cents each — so at shallow depth
        // plain truncation quantises the modulation into audible steps.
        // Diffusing it lets the average land between them.
        emit("MUL R8,R8,R12");          // Q7 sine * Q4 depth = Q11
        if (number("vibrato_dither", 1, 0, 1) == 1) {
            emit("MOV R9,0x6098");
            emit("LD.SH R10,R9[0x0]");
            emit("ADD R8,R10");
            emit("MOV R10,R8");
            emit("ASR R10,0xb");        // floor: the remainder stays positive
            emit("LSL R1,R10,0xb");
            emit("SUB R8,R1");
            emit("ST.H R9[0x0],R8");    // carry the remainder
            emit("MOV R9,0x6028");
            emit("ST.H R9[0x0],R10");
        } else {
            // Truncate instead.  Diffusing the remainder gets the AVERAGE
            // right, but it pays for that by moving the output between two
            // adjacent pitch units at whatever rate the remainder happens to
            // overflow - about 100 times a second at shallow depth, where the
            // LFO itself only asks for ten.  Those are 2.48-cent steps in the
            // audio band, heard as a buzz riding on the note rather than as
            // the vibrato they encode.  Truncation gives a coarser LFO and a
            // quiet one.
            emit("ASR R8,0xb");
            emit("MOV R9,0x6028");
            emit("ST.H R9[0x0],R8");
        }
        emit("LDM SP++,R0,R1,R7,PC");
        padTo(0x8001a470L);
        word(0x00003560L); // global state base
        word(0x80019e98L); // sine table, relocated to free the code space
        finish("vibrato_engine", 0x8001a480L);

        // The sine, moved out of the engine's cave so the interpolation above
        // fits.  65 entries: the last repeats the first as the interpolation
        // sentinel.
        begin(0x80019e98L);
        int[] sine = {0, 12, 25, 37, 49, 60, 71, 81, 90, 98, 106, 112, 117, 122, 125, 126, 127, 126, 125, 122, 117, 112, 106, 98, 90, 81, 71, 60, 49, 37, 25, 12, 0, 65524, 65511, 65499, 65487, 65476, 65465, 65455, 65446, 65438, 65430, 65424, 65419, 65414, 65411, 65410, 65409, 65410, 65411, 65414, 65419, 65424, 65430, 65438, 65446, 65455, 65465, 65476, 65487, 65499, 65511, 65524};
        for (int v : sine) {
            halfword(v);
        }
        halfword(sine[0]);
        finish("vibrato_sine", 0x80019f1cL);

        // Per-scan housekeeping (chained from applier_plus):
        //   (a) run the shared first-use bootstrap before reading custom RAM;
        //   (b) latch-exit watch: on state+0x340 leaving 1 (prev at RAM
        //       0x60ef) clear the held count and all 29 held flags;
        begin(0x8001a480L);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("MCALL PC[0x8001ac80]");
        emit("LDDPC R10,0x8001a534");
        padTo(0x8001a4dcL);
        if (feature("arp_latch")) {
            // Leaving the latch switch position releases the keys the latch
            // was holding - and ONLY those.  A key still under a finger is
            // still being played, so the arpeggiator has to keep running on
            // it until it is physically let go.
            //
            // The factory already knows which those are.  It keeps two
            // parallel structures: the note pair at state+0x21a/0x21b, which
            // the latch deliberately holds open past the release, and the
            // touch-scan pair at state+0x238/0x239, which tracks fingers and
            // nothing else - 0x80005b6a sets a key's touch flag on contact
            // and 0x80005edc clears it on lift, neither of them latch-aware.
            // So while latched the two disagree exactly on the latched-but-
            // released keys, and the note flags this transition should end up
            // with ARE the touch flags.
            //
            // Copying the flag across is cheaper than testing it, because the
            // touch flag is 0 or 1 and so doubles as the survivor's increment.
            // The count is re-derived from the flags rather than copied from
            // 0x238: release_count_guard refuses to decrement a count whose
            // flag is already clear, so a count that disagrees with its own
            // flags can never walk back down.
            emit("LD.UB R8,R10[0x340]");
            // Mirror the latch position where the blend can read it cheaply.
            emit("MOV R9,0x608e");
            emit("ST.B R9[0x0],R8");
            emit("MOV R11,0x60ef");
            emit("LD.UB R9,R11[0x0]");
            emit("ST.B R11[0x0],R8");
            emit("CP.W R9,0x1");
            emit("BR{ne} 0x8001a510");
            emit("CP.W R8,0x1");
            emit("BR{eq} 0x8001a510");
            emit("MOV R8,0x0");            // keys still physically down
            // 0..28, the real extent of the array.  Clearing 32 zeroed the
            // same three bytes of adjacent state the selector was misreading.
            emit("MOV R9,0x1c");
            padTo(0x8001a4faL);
            emit("ADD R12,R10,R9 << 0x0");
            emit("LD.UB R11,R12[0x239]");  // finger on this key?
            emit("ST.B R12[0x21b],R11");   // held := touched
            emit("ADD R8,R11");
            emit("SUB R9,0x1");
            emit("BR{ge} 0x8001a4fa");
            emit("ST.B R10[0x21a],R8");
        }
        padTo(0x8001a510L);
        emit("MCALL PC[0x8001a520]");   // preset voltage editing
        if (block("seq_chord") && !block("persist")) {
            emit("MCALL PC[0x8001a524]"); // the sequencer's pad chord
        }
        // Clock dequeue runs only from the main loop, never inside a pitch
        // remap whose input pitch has already been calculated.
        emit("LDM SP++,R7,PC");
        padTo(0x8001a520L);
        // With persistence on, the preset editor is reached through a shim
        // that runs editor, sequencer controls and persistence in that order,
        // so a completed gesture is committed in the same control scan.
        word(block("persist") ? 0x8001d520L : 0x8001ae1cL);
        word(0x8001b180L);              // the sequencer chord
        word(0x8001b980L);              // the external clock, per scan
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

        // Guard the touch-scan release bookkeeping, in place.
        //
        // The factory keeps two parallel held-key structures.  The note pair
        // at state+0x21a/0x21b is guarded at both ends — 0x80005A04 only
        // counts up when the flag was clear, 0x80005A50 only counts down when
        // it was set — so it cannot drift.  The touch-scan pair at
        // state+0x238/0x239 is guarded on the way up (0x80005B86) and not on
        // the way down: this release path clears the flag and decrements the
        // count unconditionally, with no zero check.  One unpaired release
        // therefore takes the count from 0 to 255 through the byte store, and
        // six sites read it as "some key is down".
        //
        // Nothing branches into these 36 bytes, so they can be rewritten as a
        // unit.  The base pointer moves to R10 so the count store can reuse it
        // instead of loading the pool word a second time; those four bytes,
        // plus the redundant CASTU.B before a byte store, pay for the guard.
        // R10 is free here — the function takes its arguments in R12/R11 and
        // nothing writes R10 before this point.
        begin(0x80005ef0L);
        emit("LD.UB R8,R7[-0xc]");          // the released key
        emit("LD.W R10,PC[0x380]");         // state base, from the factory pool
        emit("ADD R9,R10,R8 << 0x0");
        emit("LD.UB R8,R9[0x239]");
        emit("CP.W R8,0x0");
        emit("BR{eq} 0x80005f14");          // never registered: nothing to undo
        emit("MOV R8,0x0");
        emit("ST.B R9[0x239],R8");
        emit("LD.UB R8,R10[0x238]");
        emit("SUB R8,0x1");
        emit("ST.B R10[0x238],R8");
        finish("release_count_guard", 0x80005f14L);

        // Repointed pulse-caller pools (arp advance + three key-scan sites).
        begin(0x8000243cL);
        word(block("clock_scan") ? 0x8001c700L : 0x8001a26cL);
        finish("pulse_pool_arp", 0x80002440L);
        begin(0x80005ed8L);
        word(block("clock_scan") ? 0x8001c700L : 0x8001a26cL);
        finish("pulse_pool_key1", 0x80005edcL);
        begin(0x800063fcL);
        word(block("clock_scan") ? 0x8001c700L : 0x8001a26cL);
        finish("pulse_pool_key2", 0x80006400L);
        begin(0x800065a4L);
        word(block("clock_scan") ? 0x8001c700L : 0x8001a26cL);
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

        // The factory's tempo-change reload of the countdown, routed through
        // the divider so it cannot take it over mid-lock.
        if (block("clock_tempo")) {
            begin(0x80002194L);
            emit("MCALL PC[0x8001b890]");
            finish("clock_tempo_hook", 0x80002198L);
        }

        // The arp step's own is-it-time test, routed through the gate above
        // so the internal timer stands down while a clock is about.  The
        // replaced factory instructions are exactly that test: countdown
        // still running plus interval == -1, both reproduced in the cave.
        if (block("clock_gate")) {
            begin(0x800021ceL);
            emit("MCALL PC[0x800021e8]");
            emit("CP.W R8,0x0");
            emit("BR{ne} 0x800023d6");  // hold: the not-time exit
            emit("RJMP 0x800021ee");  // never execute the address literal below
            padTo(0x800021e8L);
            word(0x8001baf0L);
            padTo(0x800021eeL);
            finish("clock_gate_hook", 0x800021eeL);
        }

        // Clock builds never post event 10. A stale/synthetic event must not
        // bypass the capture FIFO and advance a note without a physical edge.
        if (block("clock_pulse")) {
            begin(0x80004e72L);
            emit("RJMP 0x800051b0");
            finish("clock_hook", 0x80004e7aL);
        }

        if (block("clock_capture")) {
            // Keep the factory ISR prologue/epilogue and RETE. Replace ALL
            // raw gate-low/post-event work, including the late IFR clear.
            begin(0x800072eeL);
            emit("MCALL PC[0x80007334]");
            emit("RJMP 0x80007322");
            finish("clock_irq_hook", 0x80007322L);
            begin(0x80007334L);
            word(0x8001c200L);
            finish("clock_irq_pool", 0x80007338L);
            singlePatch("clock_edge_mode", 0x8000737eL, "MOV R11,0x0");
            // The trigger spike's own length.  The factory schedules the
            // drop with 3 at 0x80007888 and the owner measured that spike at
            // 2 ms on the jack - the countdown fires at one, so the units
            // are (n - 1) milliseconds.  The Buchla shape the owner asked
            // for is a ~4 ms spike, which is 5 here.  Bounded at 5 because
            // the attack-age guards cover four milliseconds of spike and no
            // more; raising this past them would let a gate-off truncate
            // what they protect.
            singlePatch("clock_spike_units", 0x80007888L, String.format(
                 "MOV R10,0x%x", number("trigger_spike_units", 5, 1, 5)));
        }

        if (block("clock_capture") || block("persist") || block("seq_boot")) {
            begin(0x80007d8cL);
            word(block("persist") ? 0x8001d540L
                 : block("clock_capture") ? 0x8001c300L : 0x8001dfa8L);
            finish("clock_init_pool", 0x80007d90L);
        }

        if (block("seq_clock_enabled")) {
            // Tempo conditioning must run with arp OFF too; otherwise RATE
            // and its low-end clock-disable flag would stay stale on PLAY.
            begin(0x80002b30L);
            emit("MCALL PC[0x8001d63c]");
            emit("CP.W R8,0x0");
            emit("BR{eq} 0x80002c22");
            emit("RJMP 0x80002b44");
            finish("seq_clock_rate_hook", 0x80002b44L);
            // The factory's enable edge detector and setup/teardown agree
            // with the effective run state, not just the physical switch.
            begin(0x80002ac4L);
            emit("MCALL PC[0x8001d63c]");
            emit("CP.W R8,0x0");
            emit("BR{eq} 0x80002ae0");
            emit("RJMP 0x80002ad8");
            finish("seq_clock_change_hook", 0x80002ad8L);
            begin(0x80002c2cL);
            emit("MCALL PC[0x8001d63c]");
            emit("CP.W R8,0x0");
            emit("BR{ne} 0x80002ca6");
            emit("RJMP 0x80002c48");
            finish("seq_clock_setup_hook", 0x80002c48L);
            begin(0x80004f86L);
            emit("MCALL PC[0x8001d63c]");
            emit("CP.W R8,0x0");
            emit("BR{eq} 0x80004fae");
            emit("RJMP 0x80004f9e");
            finish("seq_clock_tick_hook", 0x80004f9eL);
            // Clock-divider builds consume GPIO only through their FIFO.
            // Without it, retain the factory's physical-clock event path.
            if (!block("clock_capture")) {
                begin(0x80004e58L);
                emit("MCALL PC[0x8001d63c]");
                emit("CP.W R8,0x0");
                emit("BR{eq} 0x8000518a");
                emit("RJMP 0x80004e72");
                finish("seq_clock_input_hook", 0x80004e72L);
            }
            begin(0x80004efcL);
            emit("MCALL PC[0x8001d63c]");
            emit("CP.W R8,0x0");
            emit("BR{eq} 0x8000518e");
            emit("RJMP 0x80004f16");
            finish("seq_clock_midi_hook", 0x80004f16L);
        }

        // Hook: event 13, the trigger LED.  Its own two other reasons to
        // light branch straight past this test and are untouched.
        if (block("seq_trigger_led")) {
            // Stop short of 0x80004F48: the other two reasons to light branch
            // straight to it, and swallowing it would bury them.
            begin(0x80004f3aL);
            emit("MCALL PC[0x8001ba94]");
            emit("CP.W R12,0x0");
            emit("BR{eq} 0x80005192");
            finish("seq_trigger_led_hook", 0x80004f48L);
        }

        // Hook: the arp's MIDI note-off test.  The factory asked "is a note
        // sounding"; with the sequencer playing a tie the answer has to be no,
        // so the note is not ended underneath the gate we are holding up.
        if (block("seq_noteoff")) {
            begin(0x80002218L);
            emit("MCALL PC[0x8001b940]");
            emit("CP.W R12,0x0");
            emit("BR{eq} 0x800022a0");
            finish("seq_noteoff_hook", 0x80002220L);
        }

        // Hook: the no-key-held gate clear, routed through the sequencer so a
        // tie can keep its gate across the step boundary.
        if (block("seq_gate_clear")) {
            begin(0x800022b4L);
            emit("MCALL PC[0x800022bc]");
            emit("RJMP 0x800022c2");
            padTo(0x800022bcL);
            word(0x8001b8a0L);
            finish("seq_gate_clear_hook", 0x800022c2L);
        }

        // Hook: the arp's note selection.  The factory asked "is anything held,
        // and if so which key next"; the sequencer answers the same question
        // with a step of its own while it plays.  The pool word rides in the
        // space the replaced code frees.
        if (block("seq_select")) {
            begin(0x800022c2L);
            emit("MCALL PC[0x800022d8]");
            emit("MOV R8,R12");
            emit("ST.B R7[-0x5],R8");
            emit("RJMP 0x800022de");
            padTo(0x800022d8L);
            word(0x8001b360L);
            finish("arp_select_hook", 0x800022deL);
        }

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

        // Factory selector pointer -> whichever replacement this build wants:
        // the 1.x blend from press order into randomness, or the six zones.
        begin(0x80002420L);
        word(arpSelector);
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
        // 1 = slot 1, 2 = slot 2.  On change: copy the 32-entry table to RAM
        // 0x854 and set the LEDs (rem-en = ch 5 = slot 0, trn = ch 8 = slot 1).
        // Outside edit mode the LEDs are re-asserted every scan.  The old
        // transpose-mode byte (state+0x6a) is forced to zero permanently.
        begin(0x80019a40L);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("LDDPC R10,0x80019ae8");
        emit("MOV R9,0x6090");          // tuning slot, off the factory's flags
        emit("LD.UB R8,R9[0x0]");
        emit("CP.W R8,0x2");
        emit("BR{ls} 0x80019a58");
        emit("MOV R8,0x0");
        emit("ST.B R9[0x0],R8");
        padTo(0x80019a58L);
        emit("MOV R9,0x0");
        emit("ST.B R10[0x6a],R9");
        emit("MOV R11,0xa5a0");
        emit("ADD R11,R8");
        emit("MOV R9,0x60e4");
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
        word(0x80019af8L); // the three tuning tables
        word(0x80006808L); // LED bit set
        word(0x800068ccL); // LED bit clear
        emitTable("tuning_slot0");
        emitTable("tuning_slot1");
        emitTable("tuning_slot2");
        finish("tuning_applier_tables", 0x80019bb8L);

        // Edit key 27 (was transpose-mode toggle): slot 1 <-> slot 2.
        // The slot lives at RAM 0x6090, not state+0x2 where the first version
        // put it.  state+0x2 is the factory's remote-enable flag: it gates the
        // MIDI command handler at 0x80004FD2, and two of those commands write
        // it directly.  Sharing the byte meant selecting a tuning other than
        // slot 0 silently switched remote control on, and a remote-enable
        // message silently retuned the instrument.
        begin(0x80003d82L);
        emit("MOV R9,0x6090");
        emit("LD.UB R8,R9[0x0]");
        emit("MOV R10,0x1");
        emit("CP.W R8,0x1");
        emit("BR{ne} 0x80003d92");
        emit("MOV R10,0x2");
        padTo(0x80003d92L);
        emit("ST.B R9[0x0],R10");
        emit("LDDPC R8,0x80003e24");
        emit("MOV R9,0x0");
        emit("ST.B R8[0x6a],R9");
        emit("MOV R9,0x1");
        emit("ST.B R8[0x3a],R9");
        emit("RJMP 0x80003e10");
        padTo(0x80003db8L);
        finish("edit_key27_tuning_slot1", 0x80003db8L);

        // Edit key 28 (was remote-enable toggle): slot 0 <-> slot 2.
        begin(0x80003db8L);
        emit("MOV R9,0x6090");
        emit("LD.UB R8,R9[0x0]");
        emit("MOV R10,0x0");
        emit("CP.W R8,0x0");
        emit("BR{ne} 0x80003dc8");
        emit("MOV R10,0x2");
        padTo(0x80003dc8L);
        emit("ST.B R9[0x0],R10");
        emit("LDDPC R8,0x80003e24");
        emit("MOV R9,0x1");
        emit("ST.B R8[0x3a],R9");
        emit("RJMP 0x80003e10");
        padTo(0x80003de8L);
        finish("edit_key28_tuning_slot0", 0x80003de8L);

        // Hook: replace the factory pitch-DAC store and last-sent mirror with
        // a call into the remap.  The 0..0xfff clamp still runs just before.
        // After the remap stores the fresh pitch to DAC slot 2, fire any
        // pulse deferred by the flag at RAM 0x60ee — the trigger then always
        // rises with the correct pitch already in the DAC buffer (the arp
        // advance runs at 1 kHz but pitch only updates here at 200 Hz; the
        // factory called the pulse routine immediately, shipping the new
        // gate with the previous note's pitch for up to 5 ms).
        begin(0x80003236L);
        emit("LDDPC R8,0x80003368");
        emit("LD.SH R8,R8[0x0]");
        emit("MOV R12,R8");
        emit("MCALL PC[0x8000336c]");
        //
        // The pending mark is a countdown of scans.  Firing in this same pass
        // puts the gate on the correct DAC VALUE, but the pitch CV itself is
        // still moving: the output stage is a single pole of tau ~= 0.9 ms
        // (measured, 1.97 ms 10-90% on the jack), so at the instant the
        // trigger rises the CV has covered 89% of the step — 132 cents short
        // on an octave jump.  gate_settle_scans holds the trigger that many
        // further scans; one scan is 5.6 tau, which lands within 0.4% of the
        // target.  Zero restores the fire-immediately behaviour.
        //
        // The cost is trigger latency: up to one more scan period on top of
        // the up-to-one this hook already imposes.  It also cannot help an arp
        // whose steps are closer together than the countdown, which drops
        // triggers rather than delaying them — see the config note.
        if (block("clock_output")) {
            emit("MCALL PC[0x8001c6b4]");
            emit("RJMP 0x80003256");
        } else {
        emit("MOV R8,0x60ee");
        emit("LD.UB R9,R8[0x0]");
        emit("CP.W R9,0x0");
        emit("BR{eq} 0x80003256");      // nothing pending
        emit("SUB R9,0x1");
        emit("ST.B R8[0x0],R9");
        emit("CP.W R9,0x0");            // SUB set the flags, but ST.B sits
        emit("BR{ne} 0x80003256");      // between it and the branch
        emit("MCALL PC[0x8001a268]");
        }
        finish("pitch_store_hook", 0x80003256L);

        // The 1 ms task maintains diagnostics and banks long low intervals.
        // Input timestamps come directly from COUNT in the GPIO ISR.
        if (block("clock_scan")) {
            begin(0x80007da0L);
            word(0x8001bb70L);
            finish("clock_ms_pool", 0x80007da4L);
        }

        // The pulse's drop from 10 V to the 5 V sustain, through our cave, so
        // that a sequencer step nothing is holding drops to 0 instead.
        if (block("seq_gate")) {
            begin(0x800078bcL);
            word(0x8001b320L);
            finish("pulse_drop_pool", 0x800078c0L);
        }

        // The bend strip's own pool word.  With the sequencer on it goes
        // through our cave, which reads the ends for rests and ties and
        // silences the bend while recording.
        if (block("seq_strip")) {
            begin(0x8000335cL);
            word(0x8001b570L);
            finish("strip_pool", 0x80003360L);
        }

        // Repurposed pool word: was the last-sent mirror address (0x3212),
        // now the remap entry point read by the MCALL above.
        begin(0x8000336cL);
        if (feature("pressure_blend")) {
            word(0x8001ad78L); // target conditioner -> blend-offset shim -> remap
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
        // Decoupled preset voltages.  The factory reads the knob mirror at the
        // moment it wants a preset voltage, so the voltage IS wherever the knob
        // is standing - which is why the same four knobs cannot also be the
        // arpeggiator's controls.  These four reads move to our own store, and
        // the knobs are freed.  Both consumers go with them: the preset output
        // and the pitch adder's middle position read the same four bytes.
        //
        // The displacement is sixteen bits and the base is the state block, so
        // our RAM is reachable from it - same instruction, same four bytes,
        // just a longer reach.  0x613a - 0x3560 = 0x2bda.
        fixedPatch("preset_read_1", 0x80003628L, 4, "LD.SH R8,R8[0x2bda]");
        fixedPatch("preset_read_2", 0x80003672L, 4, "LD.SH R8,R8[0x2bdc]");
        fixedPatch("preset_read_3", 0x800036bcL, 4, "LD.SH R8,R8[0x2bde]");
        fixedPatch("preset_read_4", 0x80003706L, 4, "LD.SH R8,R8[0x2be0]");
        // There are TWO of these getters, identical in shape and both
        // switching on the active pad: the one above feeds the pitch adder,
        // and this one drives the preset voltage's own output.  Patching only
        // the first left the jack still following the knob while the pitch
        // adder had already been decoupled - the two would have disagreed
        // about what the preset voltage was.
        fixedPatch("preset_out_1", 0x8000a97eL, 4, "LD.SH R8,R8[0x2bda]");
        fixedPatch("preset_out_2", 0x8000a98eL, 4, "LD.SH R8,R8[0x2bdc]");
        fixedPatch("preset_out_3", 0x8000a99eL, 4, "LD.SH R8,R8[0x2bde]");
        fixedPatch("preset_out_4", 0x8000a9aeL, 4, "LD.SH R8,R8[0x2be0]");

        // An octave is a 2/1 everywhere in the factory: the panel switch adds
        // -484, 0, +484 or +968 DAC units by position, and the stored octave
        // setting multiplies by 484 with a two-octave bias.  With a scale that
        // repeats somewhere else those move the keyboard off its own scale, so
        // each constant becomes one period.  All are plain immediates of the
        // same width, so nothing after them moves; at 484 the patches are not
        // emitted at all.
        fixedPatch("octave_step_down", 0x80003776L, 4,
            String.format("MOV R8,-0x%x", number("octave_units", 484, 1, 2000)));
        fixedPatch("octave_step_up", 0x80003788L, 4,
            String.format("MOV R8,0x%x", number("octave_units", 484, 1, 2000)));
        fixedPatch("octave_step_up2", 0x80003792L, 4,
            String.format("MOV R8,0x%x", 2 * number("octave_units", 484, 1, 2000)));
        fixedPatch("octave_scale_mul", 0x800035e4L, 4,
            String.format("MOV R8,0x%x", number("octave_units", 484, 1, 2000)));
        // The factory writes this one as the three-operand SUB R8,R8,0x3c8;
        // the two-operand form is the same operation and the same width.
        fixedPatch("octave_scale_bias", 0x800035faL, 4,
            String.format("SUB R8,0x%x", 2 * number("octave_units", 484, 1, 2000)));

        wordPatch("knob1_pool", 0x800043c4L, 0x800194c0L,
            "knob-1 pointer -> pressure-ceiling wrapper");
        wordPatch("knob3_pool", 0x800043ccL, 0x80014300L,
            "knob-3 pointer -> pressure-floor wrapper");
        wordPatch("knob4_pool", 0x800043d0L, 0x80014380L,
            "knob-4 pointer -> knob4_curve");
        // Remote-enable guards read constant zero.  Only emitted with a
        // tuning installed: the selector moved out of state+0x2 to RAM
        // 0x6090, so nothing shares the flag and a build without tunings
        // leaves the factory feature alone.
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
