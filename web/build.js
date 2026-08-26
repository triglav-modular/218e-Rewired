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
                var mapped = !!slot.kbmText;
                var cents = BUILDLIB.parseScala(slot.text, slot.name, mapped);
                var degrees = null, period = 1200.0;
                if (mapped) {
                    var map = BUILDLIB.parseKbm(slot.kbmText, slot.kbmName, cents);
                    degrees = map.degrees;
                    period = cents[map.formal];
                }
                var offset = BUILDLIB.anchorOffset(
                    cents, cfg.tuning.reference_key, degrees, period);
                // Same rule as tools/build.py: pinning a key to its 12-TET
                // pitch says nothing about a scale that has no octave, and
                // spends the headroom the octave switch needs.
                if (Math.abs((period || 1200.0) - 1200.0) > 0.001) offset = 0.0;
                tables['tuning_slot' + index] = BUILDLIB.tuningTable(
                    cents, BUILDLIB.baseUnits(cfg), cfg.tuning.units_per_octave, offset,
                    degrees, period);
            }
        });
        var bank = BUILDLIB.patternBank(cfg);
        tables.arp_pattern_bank = [];
        bank.masks.forEach(function (m) {
            tables.arp_pattern_bank.push(m & 0xFFFF, Math.floor(m / 65536) & 0xFFFF);
        });
        tables.arp_pattern_len = bank.lengths.slice();

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
        // Same rule as tools/build.py: the arp gate hook latches knobs 1-3
        // for the replacement behaviours, so with all three factory it goes.
        blocks.arp_gate_hook = ['knob1', 'knob2', 'knob3'].some(function (k) {
            return cfg.knobs[k] !== 'factory';
        });
        if (cfg._pressure_factory) {
            ['pressure_fn_pool', 'pressure_float_helper_pool', 'knob1_pool',
             // Same rule as tools/build.py: the edit-mode curve knob is
             // pressure work, so it reverts with the rest.
             'knob4_pool',
             'pressure_gain_nop',
             // The clamp skips jump the factory's own pressure filter; the
             // cells that made them load-bearing have moved out of its array.
             'pitch_clamp_skip_1', 'pitch_clamp_skip_2']
                .forEach(function (n) { blocks[n] = false; });
        }
        // Same rule as tools/build.py: with no Scala file the edit keys and
        // their LEDs stay factory, which means the applier goes too — it
        // asserts those LEDs and zeroes the old transpose-mode byte.
        var anyTuning = cfg._tunings.some(function (t) { return t !== 'factory'; });
        features.alternate_tunings = anyTuning;
        if (!anyTuning) {
            blocks.edit_key27_tuning_slot1 = false;
            blocks.edit_key28_tuning_slot0 = false;
            // Remote enable goes back with them: its guards were added when
            // the tuning selector shared state+0x2, which it no longer does.
            ['remote_guard_1', 'remote_guard_2', 'remote_guard_3']
                .forEach(function (n) { blocks[n] = false; });
        }
        // Same rule as tools/build.py: transpose mode survives only when
        // neither the tuning applier nor the knob remap has taken what it
        // needs, so with both off these three forcing patches stay out.
        var factoryKnobs = Object.keys(cfg.knobs).every(function (k) {
            return cfg.knobs[k] === 'factory';
        });
        if (!anyTuning && factoryKnobs) {
            ['transpose_force_1', 'transpose_force_2', 'transpose_force_3']
                .forEach(function (n) { blocks[n] = false; });
        }

        if (BUILDLIB.get(cfg, 'arp.switch') === 'latch') {
            blocks.pitch_target_blend_hook = true;
            blocks.blend_offset_apply = true;
        } else {
            // Same rule as tools/build.py: the factory long-hold on the arp
            // switch comes back when the factory switch does.
            blocks.poly_arp_independence = false;
        }
        features.pressure_trim_scale = cfg.pressure.calibration.trim_mode === 'scale';
        if (features.pressure_trim_scale) {
            blocks.knob3_pressure_floor = false;
            blocks.knob3_pool = false;
        }
        // Same rule as tools/build.py: the factory's octave arithmetic is only
        // rewritten when an octave has stopped being a 2/1.
        var octave = BUILDLIB.computeNumbers(cfg).octave_units;
        ['octave_step_down', 'octave_step_up', 'octave_step_up2',
         'octave_scale_mul', 'octave_scale_bias'].forEach(function (n) {
            blocks[n] = octave !== cfg.tuning.units_per_octave;
        });
        blocks.arp_order_zones = cfg.arp_order.knob1_orders === 1;
        blocks.arp_pattern_gate = cfg.knob2.mode === 'patterns';
        blocks.arp_pattern_tables = blocks.arp_pattern_gate;
        blocks.knob4_octave_switch =
            cfg.knob4.octaves === 1 && BUILDLIB.get(cfg, 'knobs.knob4') === 'vibrato';
        if (blocks.knob4_octave_switch) {
            features.knob4_vibrato = false;
            ['vibrato_engine', 'vibrato_sine', 'pressure_vibrato_scale',
             'pressure_vibrato_pool'].forEach(function (n) { blocks[n] = false; });
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
        // And nothing beyond them: tools/build.py compares both directions,
        // and one-way containment would let a renderHex defect flash stray
        // bytes with the page vouching for the image.
        var rekeys = Object.keys(reread.memory);
        if (rekeys.length !== keys.length) {
            throw new Error('round-trip check failed: the rendered hex holds ' +
                            rekeys.length + ' bytes, the build ' + keys.length);
        }
        var stray = 0;
        Object.keys(original).forEach(function (a) {
            if (original[a] !== factory.memory[a] && !(a in applied.claimed)) stray++;
        });
        if (stray) throw new Error(stray + ' byte(s) changed outside any patch');

        var sha = SHA256.hashString(hex);
        return {
            hex: hex,
            sha256: sha,
            // Same shape the command-line build reports and both flashers
            // carry: a declared version plus the image's own fingerprint.
            version: 'Rewired ' + GEN.version + ' (' + sha.slice(0, 8) + ')',
            // The scripts that go in a download, stamped for this image: the
            // same two substitutions tools/build.py makes when it writes them
            // into the repository.  A bundle therefore flashes the image it
            // ships with, without asking.
            scripts: (function () {
                function stamp(text) {
                    return text
                        .replace(/(EXPECTED_SHA256="?)[0-9a-f]{64}/, '$1' + sha)
                        .replace(/(FIRMWARE_VERSION="?)Rewired [^"\r\n]*/,
                                 '$1Rewired ' + GEN.version + ' (' + sha.slice(0, 8) + ')');
                }
                return {
                    flasherMac: stamp(GEN.flasherMac),
                    flasherWin: stamp(GEN.flasherWin)
                };
            })(),
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
