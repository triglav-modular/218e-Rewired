// The assembler DSL that src/AssemblePressureFix.java runs inside Ghidra,
// reimplemented on top of encoder.js.
//
// Faithfulness matters more than elegance here: this has to emit the same
// EXTENT / BLOCK / SKIP / PATCH records, in the same order, with the same
// text, as the Java does — that is what makes the two comparable.

var RT = (function () {
    'use strict';

    var cfg = {};          // build.properties, flattened to key -> string
    var out = [];          // emitted record lines
    var pc = 0, base = 0, bytes = [], listing = [];

    function println(line) { out.push(line); }

    // --- minimal printf ------------------------------------------------
    // Only the conversions the Java actually uses: %08x %04x %02x %x %d %-36s %s
    function fmt(spec) {
        var args = Array.prototype.slice.call(arguments, 1), i = 0;
        return spec.replace(/%(-?)(0?)(\d*)([xds])/g, function (_, left, zero, width, kind) {
            var v = args[i++], s;
            if (kind === 'x') s = (v >>> 0).toString(16);
            else if (kind === 'd') s = String(v);
            else s = String(v);
            width = width ? parseInt(width, 10) : 0;
            while (s.length < width) s = left ? s + ' ' : (zero ? '0' : ' ') + s;
            return s;
        });
    }

    function hex(data) {
        var s = '';
        for (var i = 0; i < data.length; i++) {
            var b = (data[i] & 0xFF).toString(16);
            s += b.length < 2 ? '0' + b : b;
        }
        return s;
    }

    // --- build config ---------------------------------------------------
    // Missing keys default to "1", so an unconfigured run still assembles the
    // complete patch set — same as the Java.
    function on(key) { return cfg[key] !== '0'; }
    function block(name) { return on('block.' + name); }
    function feature(name) { return on('feature.' + name); }

    function number(key, fallback, low, high) {
        var raw = (cfg['number.' + key] || '').trim();
        // Strict decimal, matching Java's Integer.parseInt: parseInt takes
        // any numeric prefix and NaN slips every range check (both
        // comparisons are false), so a malformed setting assembled as
        // MOV Rd,0x0 here while the Ghidra build aborted.
        if (raw !== '' && !/^-?\d+$/.test(raw)) {
            throw new Error('Setting ' + key + ' is not a number: ' + raw);
        }
        var value = raw === '' ? fallback : parseInt(raw, 10);
        if (value < low || value > high) {
            throw new Error(fmt('Setting %s must be %d..%d to keep the encoding width: %d',
                                key, low, high, value));
        }
        return value;
    }

    // Mirrors twoPhaseBeat()/settleMs() in the Java.  The internal beat's
    // settle is the output RC's, not the scan grid's: the flush stages the
    // pitch, the millisecond timer spends the wait, and only then does the
    // gate follow.  Zero settle means there is nothing to hold and the old
    // fire-at-the-store path is what was asked for.
    function twoPhaseBeat() {
        return block('clock_fast_trigger') && block('clock_output');
    }

    function claimFor(settleScans) {
        return settleScans === 0 ? 1 : 2;
    }

    function settleMsFor(settleScans) {
        return settleScans * number('scan_period_ms', 5, 1, 20);
    }

    // Mirrors deadlineMs() in the Java: how long after the ACCEPTED EDGE the
    // external trigger goes out. Zero builds the cave but never calls it,
    // which is the fire-on-the-next-flush behaviour that shipped.
    function deadlineMs() {
        return twoPhaseBeat() ? number('clock_deadline_ms', 4, 0, 8) : 0;
    }

    // Mirrors holdPitchToGate() in the Java: whether the external beat's
    // pitch is held back to the gate's own transfer, which it is whenever a
    // deadline holds the gate and no settle asked for the CV to travel first.
    function holdPitchToGate() {
        return deadlineMs() > 0
            && settleMsFor(number('clock_settle_scans', 0, 0, 3)) === 0;
    }

    function table(name) {
        var raw = (cfg['table.' + name] || '').trim();
        if (raw === '') throw new Error('Missing table in build config: ' + name);
        return raw.split(',').map(function (p) { return parseInt(p.trim(), 10); });
    }

    function emitTable(name) {
        var v = table(name);
        for (var i = 0; i < v.length; i++) halfword(v[i]);
    }

    // --- emission -------------------------------------------------------
    function begin(address) { base = address; pc = address; bytes = []; listing = []; }

    function record(text, encoded) {
        listing.push(fmt('%08x  %-36s %s', pc, text, hex(encoded)));
        for (var i = 0; i < encoded.length; i++) bytes.push(encoded[i]);
        pc += encoded.length;
    }

    function emit(instruction) {
        var encoded = AVR32.encode(pc, instruction);
        if (encoded === null) {
            throw new Error(fmt('cannot encode at %08x: %s', pc, instruction));
        }
        record(instruction, encoded);
    }

    function word(value) {
        record(fmt('.word 0x%08x', value),
               [(value / 0x1000000) & 0xFF, (value / 0x10000) & 0xFF,
                (value / 0x100) & 0xFF, value & 0xFF]);
    }

    function halfword(value) {
        record(fmt('.hword 0x%04x', value), [(value >>> 8) & 0xFF, value & 0xFF]);
    }

    function padTo(address) {
        if (pc > address) {
            throw new Error(fmt('Code crossed target: pc=%08x target=%08x', pc, address));
        }
        while (pc < address) emit('NOP');
        // The Java throws 'Cannot align target' here; an odd gap would leave
        // pc one past the address and the patch a byte outside its extent.
        if (pc !== address) {
            throw new Error(fmt('Cannot align target: pc=%08x target=%08x', pc, address));
        }
    }

    // EXTENT is printed before the enable check, so the build can spot two
    // caves claiming the same flash even when only one of them is emitted.
    function finish(name, expectedEnd) {
        println(fmt('EXTENT %08x %08x %s', base, expectedEnd, name));
        padTo(expectedEnd);
        if (!block(name)) {
            println('SKIP ' + name + ' (disabled by build config)');
            return;
        }
        println('BLOCK ' + name);
        for (var i = 0; i < listing.length; i++) println(listing[i]);
        println(fmt('PATCH %08x %s ; %s', base, hex(bytes), name));
    }

    function singlePatch(name, address, instruction) {
        var encoded = AVR32.encode(address, instruction);
        if (encoded === null) {
            throw new Error(fmt('cannot encode at %08x: %s', address, instruction));
        }
        // EXTENT before the enable check, as the Java prints it: without it
        // the --no-ghidra build's collision check never saw these patches.
        println(fmt('EXTENT %08x %08x %s', address, address + encoded.length, name));
        if (!block(name)) {
            println('SKIP ' + name + ' (disabled by build config)');
            return;
        }
        println(fmt('PATCH %08x %s ; %s: %s', address, hex(encoded), name, instruction));
    }

    function wordPatch(name, address, value, comment) {
        println(fmt('EXTENT %08x %08x %s', address, address + 4, name));
        if (!block(name)) {
            println('SKIP ' + name + ' (disabled by build config)');
            return;
        }
        println(fmt('PATCH %08x %08x ; %s: %s', address, value, name, comment));
    }

    function fixedPatch(name, address, length, instruction) {
        println(fmt('EXTENT %08x %08x %s', address, address + length, name));
        begin(address);
        emit(instruction);
        if (pc > address + length) throw new Error('Instruction does not fit fixed patch');
        padTo(address + length);
        if (!block(name)) {
            println('SKIP ' + name + ' (disabled by build config)');
            return;
        }
        println(fmt('PATCH %08x %s ; %s: %s', address, hex(bytes), name, instruction));
    }

    function init(properties) { cfg = properties; out = []; }

    return {
        init: init, output: function () { return out; }, fmt: fmt,
        on: on, block: block, feature: feature, number: number,
        twoPhaseBeat: twoPhaseBeat, claimFor: claimFor,
        settleMsFor: settleMsFor, deadlineMs: deadlineMs,
        holdPitchToGate: holdPitchToGate,
        table: table, emitTable: emitTable, begin: begin, emit: emit,
        word: word, halfword: halfword, padTo: padTo, finish: finish,
        singlePatch: singlePatch, wordPatch: wordPatch, fixedPatch: fixedPatch,
        println: println
    };
})();

// The transpiled program calls these bare, exactly as the Java does.
var block = RT.block, feature = RT.feature, number = RT.number, table = RT.table,
    twoPhaseBeat = RT.twoPhaseBeat, claimFor = RT.claimFor,
    settleMsFor = RT.settleMsFor, deadlineMs = RT.deadlineMs,
    holdPitchToGate = RT.holdPitchToGate,
    emitTable = RT.emitTable, begin = RT.begin, emit = RT.emit, word = RT.word,
    halfword = RT.halfword, padTo = RT.padTo, finish = RT.finish,
    singlePatch = RT.singlePatch, wordPatch = RT.wordPatch,
    fixedPatch = RT.fixedPatch, println = RT.println;
function StringFormat() { return RT.fmt.apply(null, arguments); }
