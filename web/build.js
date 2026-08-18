// The whole build, in the browser: seven options + a factory image in, a
// flashable image out.  Nothing leaves the machine.
//
// Load order: generated.js, sha256.js, buildlib.js, then the assembler
// (encoder.js, runtime.js, program.js), then this.
var WEBBUILD = (function () {
    'use strict';

    function tablesFor(cfg, factoryMemory) {
        var tables = {};
        tables.pressure_curve = BUILDLIB.pressureCurve(
            cfg.pressure.curve.span, cfg.pressure.curve.onset_db,
            cfg.pressure.curve.onset_fade);
        tables.pitch_remap = BUILDLIB.pitchTable(cfg, cfg._calibration);
        cfg._tunings.forEach(function (slot, index) {
            if (slot === 'factory') {
                tables['tuning_slot' + index] = BUILDLIB.factoryTuning(factoryMemory);
            } else {
                var cents = BUILDLIB.parseScala(slot.text, slot.name);
                var offset = BUILDLIB.anchorOffset(cents, cfg.tuning.reference_key);
                tables['tuning_slot' + index] = BUILDLIB.tuningTable(
                    cents, cfg.tuning.base_units, cfg.tuning.units_per_octave, offset);
            }
        });
        var mask = 0x0A54A54A;
        var excess = BUILDLIB.floorHalf(cfg.pressure.black_key_scale * 256) - 256;
        var bk = [];
        for (var k = 0; k < 32; k++) bk.push(((mask >>> k) & 1) ? excess : 0);
        tables.black_key_excess = bk;
        return tables;
    }

    function flagsFor(cfg) {
        var flags = BUILDLIB.resolveFlags(cfg);
        var blocks = flags.blocks, features = flags.features;
        if (cfg._pressure_factory) {
            ['pressure_fn_pool', 'pressure_float_helper_pool', 'knob1_pool',
             'knob4_pool', 'pressure_gain_nop'].forEach(function (n) { blocks[n] = false; });
        }
        if (BUILDLIB.get(cfg, 'arp.switch') === 'latch') {
            blocks.pitch_target_blend_hook = true;
            blocks.blend_offset_apply = true;
        }
        features.pressure_trim_scale = cfg.pressure.calibration.trim_mode === 'scale';
        if (features.pressure_trim_scale) {
            blocks.knob3_pressure_floor = false;
            blocks.knob3_pool = false;
        }
        var smoothing = cfg.pressure.output_smoothing;
        ['dac_interpolator', 'dac_flush_pool', 'pressure_target_redirect']
            .forEach(function (n) { blocks[n] = !!smoothing; });
        return { blocks: blocks, features: features };
    }

    // --- the same checks tools/build.py runs -----------------------------
    function parseRecords(lines) {
        var patches = [], extents = [], skipped = [];
        lines.forEach(function (line) {
            var m = /^PATCH ([0-9a-f]{8}) ([0-9a-f]+)(?: ; (.*))?$/.exec(line);
            if (m) {
                var data = [];
                for (var i = 0; i < m[2].length; i += 2) {
                    data.push(parseInt(m[2].substr(i, 2), 16));
                }
                patches.push({ address: parseInt(m[1], 16), data: data, note: m[3] || '' });
                return;
            }
            m = /^EXTENT ([0-9a-f]{8}) ([0-9a-f]{8}) (\S+)$/.exec(line);
            if (m) {
                extents.push({ start: parseInt(m[1], 16), end: parseInt(m[2], 16), name: m[3] });
                return;
            }
            if (line.indexOf('SKIP ') === 0) skipped.push(line.slice(5).split(' ')[0]);
        });
        if (!patches.length) throw new Error('assembler produced no PATCH records');
        return { patches: patches, extents: extents, skipped: skipped };
    }

    function checkExtents(extents) {
        var sorted = extents.slice().sort(function (a, b) { return a.start - b.start; });
        for (var i = 1; i < sorted.length; i++) {
            if (sorted[i].start < sorted[i - 1].end) {
                throw new Error('blocks overlap in flash: ' + sorted[i - 1].name +
                                ' and ' + sorted[i].name);
            }
        }
        return sorted.length;
    }

    // No patch may bury an address some other factory code still branches to,
    // because the jump would then land inside our instructions.
    function checkEntryPoints(patches) {
        var problems = [];
        for (var p = 0; p < patches.length; p++) {
            var start = patches[p].address, end = start + patches[p].data.length;
            for (var i = 0; i < GEN.controlFlow.length; i += 2) {
                var src = GEN.controlFlow[i], dst = GEN.controlFlow[i + 1];
                if (dst > start && dst < end && !(src >= start && src < end)) {
                    problems.push((patches[p].note || 'patch') + ' buries 0x' +
                                  dst.toString(16) + ', branched to from 0x' + src.toString(16));
                }
            }
        }
        if (problems.length) {
            throw new Error('patch overwrites a live factory branch target:\n  ' +
                            problems.slice(0, 5).join('\n  '));
        }
        return GEN.controlFlow.length / 2;
    }

    function applyPatches(memory, patches) {
        var addresses = Object.keys(memory).map(Number);
        var low = Math.min.apply(null, addresses), high = Math.max.apply(null, addresses);
        var claimed = {}, changed = 0, added = 0;
        patches.forEach(function (patch, index) {
            for (var i = 0; i < patch.data.length; i++) {
                var loc = patch.address + i;
                if (loc < low || loc > high) {
                    throw new Error('patch at 0x' + loc.toString(16) +
                                    ' lies outside the application image');
                }
                if (loc in claimed && claimed[loc] !== index) {
                    throw new Error('patches overlap at 0x' + loc.toString(16));
                }
                claimed[loc] = index;
                if (!(loc in memory)) added++;
                else if (memory[loc] !== patch.data[i]) changed++;
                memory[loc] = patch.data[i];
            }
        });
        return { changed: changed, added: added, claimed: claimed };
    }

    /**
     * options: the seven switches.  factoryHexText: the user's own image.
     * Returns { hex, sha256, patches, changed, added, skipped, properties }.
     */
    function build(options, factoryHexText) {
        var factory = BUILDLIB.parseHexText(factoryHexText, 'factory image');
        var factorySha = SHA256.hashString(factoryHexText);
        if (factorySha !== GEN.factorySha256) {
            throw new Error('That is not the expected factory image.\n' +
                            '  expected SHA-256 ' + GEN.factorySha256 + '\n' +
                            '  this file      ' + factorySha);
        }

        var cfg = BUILDLIB.expand(options);
        var tables = tablesFor(cfg, factory.memory);
        var flags = flagsFor(cfg);
        var numbers = BUILDLIB.computeNumbers(cfg);
        numbers.init_marker = BUILDLIB.initMarker(flags.blocks, flags.features, numbers, tables);

        // The assembler takes the same flat key -> string map the properties
        // file holds, so build that shape directly.
        var props = {};
        Object.keys(flags.blocks).forEach(function (n) {
            props['block.' + n] = flags.blocks[n] ? '1' : '0';
        });
        Object.keys(flags.features).forEach(function (n) {
            props['feature.' + n] = flags.features[n] ? '1' : '0';
        });
        Object.keys(numbers).forEach(function (n) {
            props['number.' + n] = String(numbers[n]);
        });
        Object.keys(tables).forEach(function (n) {
            props['table.' + n] = tables[n].join(',');
        });

        RT.init(props);
        assembleProgram();
        var records = parseRecords(RT.output());
        checkExtents(records.extents);
        checkEntryPoints(records.patches);

        var original = {};
        Object.keys(factory.memory).forEach(function (a) { original[a] = factory.memory[a]; });
        var applied = applyPatches(factory.memory, records.patches);

        var hex = BUILDLIB.renderHex(factory.memory, factory.startLinear);

        // Read the rendered image back and confirm it round-trips, then confirm
        // every difference from the factory image lies inside a declared patch.
        var reread = BUILDLIB.parseHexText(hex, 'output');
        if (reread.startLinear !== factory.startLinear) {
            throw new Error('round-trip check failed: start address differs');
        }
        var keys = Object.keys(factory.memory);
        for (var i = 0; i < keys.length; i++) {
            if (reread.memory[keys[i]] !== factory.memory[keys[i]]) {
                throw new Error('round-trip check failed at 0x' + Number(keys[i]).toString(16));
            }
        }
        var stray = 0;
        Object.keys(original).forEach(function (a) {
            if (original[a] !== factory.memory[a] && !(a in applied.claimed)) stray++;
        });
        if (stray) throw new Error(stray + ' byte(s) changed outside any patch');

        return {
            hex: hex,
            sha256: SHA256.hashString(hex),
            properties: BUILDLIB.writeProperties('config/218e.toml', flags.blocks,
                                                 flags.features, numbers, tables),
            patches: records.patches.length,
            skipped: records.skipped,
            changed: applied.changed,
            added: applied.added
        };
    }

    return { build: build };
})();
if (typeof module !== 'undefined' && module.exports) module.exports = WEBBUILD;
