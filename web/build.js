// The whole build, in the browser: seven options + a factory image in, a
// flashable image out.  Nothing leaves the machine.
//
// Load order: generated.js, sha256.js, buildlib.js, then the assembler
// (encoder.js, runtime.js, program.js), then this.
var WEBBUILD = (function () {
    'use strict';

    // One record per slot that carries a scale, for the latch-spacing check:
    // { ideal, table, periodCents, periodUnits }.  Collected while the tables
    // are built, because the check needs the unquantised pitches too and this
    // is the only place they exist.  Reset per build, never accumulated.
    var spacingSlots = [];

    function tablesFor(cfg, factoryMemory) {
        var tables = {};
        spacingSlots = [];
        // How many keys each slot repeats over, for the jack transposer's
        // wrap: twelve, or the .kbm's map size.  Mirrors tools/build.py.
        tables.tuning_period_keys = [];
        tables.pressure_curve = BUILDLIB.pressureCurve(
            cfg.pressure.curve.span, cfg.pressure.curve.onset_db,
            cfg.pressure.curve.onset_fade);
        tables.pitch_remap = BUILDLIB.pitchTable(cfg, cfg._calibration);
        // Same rule as tools/build.py: without a .kbm there is one key table
        // entry per key and nothing to map them with, so the scale has to have
        // exactly the twelve the keyboard repeats.
        //
        // Every slot is checked before any of them is built, because baseUnits
        // below probes all three for their period: a 24-note scale in slot 1
        // or 2 was refused for disagreeing about the period, which is true of
        // the numbers and says nothing about the file.  tools/build.py puts
        // the rule inside slot_scale(), which its own probe goes through, so
        // the two refuse the same input the same way whichever slot it is in.
        cfg._tunings.forEach(function (slot) {
            if (slot === 'factory' || BUILDLIB.isTableSlot(slot)) return;
            var probe = BUILDLIB.slotScale(slot);
            if (!probe.degrees && probe.cents.length - 1 !== 12) {
                throw new Error(slot.name + ': ' + (probe.cents.length - 1) +
                    ' degrees — the key table gives one entry per key, so ' +
                    'without a .kbm to map them a 12-note scale is required');
            }
        });
        cfg._tunings.forEach(function (slot, index) {
            if (slot === 'factory') {
                tables['tuning_slot' + index] = BUILDLIB.factoryTuning(factoryMemory);
                tables.tuning_period_keys.push(12);
            } else if (BUILDLIB.isTableSlot(slot)) {
                // Read back from a keyboard: its table and its keys per period
                // as they came, and nothing for the latch-spacing check, which
                // needs the scale's ideal pitches and a table has none.  The
                // keyboard's loader bounded every entry when it took them.
                tables['tuning_slot' + index] = slot.table.slice();
                tables.tuning_period_keys.push(slot.periodKeys);
            } else {
                var scale = BUILDLIB.slotScale(slot);
                var period = scale.cents[scale.formal];
                var offset = BUILDLIB.anchorOffset(
                    scale.cents, cfg.tuning.reference_key, scale.degrees, period);
                // Same rule as tools/build.py: pinning a key to its 12-TET
                // pitch says nothing about a scale that has no octave, and
                // spends the headroom the octave switch needs.
                if (Math.abs(period - 1200.0) > 0.001) offset = 0.0;
                var perOctave = cfg.tuning.units_per_octave;
                var table = BUILDLIB.tuningTable(
                    scale.cents, BUILDLIB.baseUnits(cfg), perOctave,
                    offset, scale.degrees, period);
                var periodUnits = BUILDLIB.floorHalf(period * perOctave / 1200);
                BUILDLIB.checkTableRange(slot.name, table, periodUnits);
                tables['tuning_slot' + index] = table;
                tables.tuning_period_keys.push(scale.degrees ? scale.degrees.length : 12);
                spacingSlots.push({
                    ideal: BUILDLIB.idealKeyPitches(
                        scale.cents, scale.degrees, period, offset),
                    table: table,
                    periodCents: period,
                    periodUnits: periodUnits
                });
            }
        });
        // The wider-than-32 map is refused in build() below, after the latch
        // spacing check, as tools/build.py orders them.
        var widest = Math.max.apply(null, tables.tuning_period_keys);
        // Same rule as tools/build.py, word for word: one degree has to be
        // able to cross the rotation's hysteresis band or the shift never
        // changes.  Every image, for the same reason; a map wider than 32
        // is refused in build() whatever its hysteresis, so not measured here.
        if (widest <= 32) {
            var cvPeriod = Math.floor(cfg.portamento_in.cv_counts_per_volt
                                      * cfg.portamento_in.cv_volts_per_period + 0.5);
            var hyst = cfg.portamento_in.cv_hysteresis;
            var headroom = cvPeriod - Math.floor(cvPeriod / 2);
            if (hyst * widest >= headroom) {
                throw new Error('portamento_in.cv_hysteresis: ' + hyst +
                    ' is too wide beside a keyboard map of ' + widest +
                    ' positions - one degree of the rotation moves the ' +
                    'reading ' + cvPeriod + ' counts and the hysteresis band ' +
                    'is ' + (Math.floor(cvPeriod / 2) + hyst * widest) + ', so a ' +
                    'single-degree shift would be ignored.  Use a hysteresis of ' +
                    'at most ' + Math.floor((headroom - 1) / widest) + ', or a ' +
                    'map with fewer positions.');
            }
        }
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
        // Same rule as tools/build.py: the knob roles are option cells
        // decided at boot (stage 2 phase B), so every knob cave is in every
        // image and the hooks that reach them always stand.
        blocks.arp_gate_hook = true;
        ['arp_selector_pool', 'arp_rhythm_hook', 'arp_octave_hook',
         'vibrato_engine', 'vibrato_sine', 'pressure_vibrato_scale',
         'pressure_vibrato_pool', 'knob4_early_pool',
         // Phase C: the latch, likewise.
         'noteoff_pool_1', 'noteoff_pool_2', 'latch_pitch_toggle',
         'release_count_guard', 'latch_owner', 'latch_hold',
         'latch_state_toggle', 'factory_pad_latch_off']
            .forEach(function (n) { blocks[n] = true; });
        features.knob4_vibrato = true;
        features.arp_latch = true;
        // Phase E: the jack transposer and the quantised preset voltage.
        // The key-table rotation and everything that follows it are in
        // every image and idle at zero degrees; the live bytes decide.
        ['preset_quantize', 'preset_quantize_pool', 'glide_cv_addend',
         'cv_transpose', 'midi_transpose', 'preset_entry', 'latch_preset_pin',
         'midi_transpose_arp_pool', 'midi_transpose_poly_pool',
         'midi_transpose_lift_pool', 'midi_transpose_compare_pool',
         'seq_record_pitch_cv', 'seq_cv_shift', 'cv_stamps']
            .forEach(function (n) { blocks[n] = true; });
        features.cv_transpose = true;
        features.cv_jack = true;
        features.preset_rotate = true;
        // Phase F, same rule as tools/build.py: the pressure path is decided
        // at boot from its two option cells, so every pressure cave is in
        // every image, the blend hook and the zero-snap hook included.
        ['glide_rate_hook', 'pitch_target_blend_hook', 'blend_offset_apply',
         'blend_target_conditioner'].forEach(function (n) { blocks[n] = true; });
        features.pressure_blend = true;
        // Same rule as tools/build.py: with no Scala file the edit keys and
        // their LEDs stay factory, which means the applier goes too — it
        // asserts those LEDs and zeroes the old transpose-mode byte.
        // Since 2026-09-23 that is option cell 27, decided at boot: the keys,
        // the applier and the remote-enable guards are in every image.
        features.alternate_tunings = true;
        // Same rule as tools/build.py: with the knob roles decided at
        // runtime the build cannot know whether the knobs are all factory,
        // so the three transpose forcing patches stay in every image.

        // Same rule as tools/build.py: the latch may be live in any image,
        // so the blend caves are in every image, and poly_arp_independence
        // stays whatever the latch does.
        blocks.pitch_target_blend_hook = true;
        blocks.blend_offset_apply = true;
        blocks.blend_target_conditioner = true;
        features.pressure_trim_scale = cfg.pressure.calibration.trim_mode === 'scale';
        if (features.pressure_trim_scale) {
            blocks.knob3_pressure_floor = false;
            blocks.knob3_pool = false;
        }
        // Same rule as tools/build.py: the factory's octave arithmetic reads
        // the period out of number cell 10 in every image (2026-09-23), so
        // its five sites are hooks, not blocks.
        // Every knob-2 and knob-1 cave, in every image (stage 2 phase B).
        ['arp_order_zones', 'arp_pattern_gate', 'arp_pattern_tables', 'arp_swing', 'arp_quantized']
            .forEach(function (n) { blocks[n] = true; });
        // Stage 2 phase D, same rule as tools/build.py: the sequencer is
        // decided at boot from its option cell, so every sequencer cave is
        // in every image; the pad-4 chord's arm reads the live byte.
        ['seq_chord', 'seq_arm_gate', 'seq_enter', 'seq_record', 'seq_select', 'seq_pitch',
         'seq_clock_enabled', 'seq_transport', 'seq_clock_rate_hook',
         'seq_clock_change_hook', 'seq_clock_setup_hook', 'seq_clock_tick_hook',
         'seq_clock_input_hook', 'seq_clock_midi_hook',
         'seq_strip', 'seq_gate', 'seq_glide', 'strip_pool',
         'seq_gate_clear', 'seq_gate_clear_hook',
         'seq_pulse_drop', 'pulse_drop_pool', 'seq_next_step',
         'seq_noteoff', 'seq_noteoff_hook',
         'seq_trigger_led', 'seq_trigger_led_hook',
         'seq_strip_led', 'strip_dac_redirect',
         'seq_edit', 'seq_preview_step', 'seq_command',
         'seq_preview_next', 'seq_preview_start', 'seq_preview_transport',
         'seq_record_pitch', 'seq_preview_pin', 'seq_hold', 'seq_flash',
         'seq_restart_init', 'seq_boot']
            .forEach(function (n) { blocks[n] = true; });
        var keep = !!(cfg.persist && cfg.persist.on);
        ['persist_crc', 'persist_record_crc', 'persist_pack',
         'persist_valid', 'persist_newest', 'persist_load',
         'persist_same', 'persist_verify', 'persist_save', 'persist_tick',
         'persist_capture', 'persist_boot', 'persist_scan_shim', 'persist']
            .forEach(function (n) { blocks[n] = keep; });
        // Since phase G the factory ISR posts the clock event whenever the
        // divider's byte is off, so the sequencer's gate on it always stands.
        blocks.seq_clock_input_hook = true;
        // Same rule as tools/build.py: transpose_capture lives inside the
        // blend hook and is what keeps 0x60a0 current, which the sequencer
        // reads as the take's reference.  The hook exists whenever the
        // sequencer does, which since phase D is every image; the pressure
        // following inside it stays independent.
        blocks.pitch_target_blend_hook = true;
        blocks.blend_offset_apply = true;
        blocks.blend_target_conditioner = true;
        // Stage 2 phase G, same rule as tools/build.py: the divider is
        // decided at boot from its option cell, so every clock cave is in
        // every image.
        ['clock_scan', 'clock_pulse', 'clock_hook',
         'clock_tempo', 'clock_tempo_hook',
         'clock_ms_tick', 'clock_ms_pool',
         'clock_gate', 'clock_gate_hook', 'clock_settle',
         'clock_capture', 'clock_irq_hook',
         'clock_edge_mode', 'clock_init', 'clock_init_pool', 'clock_thresholds',
         'clock_service', 'clock_output', 'clock_low_age', 'clock_attack_guard',
         'clock_spike_units', 'clock_fast_trigger', 'clock_remap_bare',
         'clock_deadline', 'clock_pitch_target']
            .forEach(function (n) { blocks[n] = true; });
        // The settings mirror is in every image, as tools/build.py has it:
        // the boot chain starts at settings_boot and its validator shares
        // persist_crc, so both stay on with persistence off.
        ['settings_copy', 'settings_valid', 'settings_newest',
         'settings_boot', 'settings_defaults', 'settings_reload',
         'settings_target', 'settings_apply', 'settings_nrpn',
         'settings_send', 'settings_value', 'settings_scan',
         'settings_commit', 'settings_verify', 'settings_cc_hook',
         'settings_cc_pool', 'clock_init_pool', 'persist_crc']
            .forEach(function (n) { blocks[n] = true; });
        blocks.profiler_pool = true;
        blocks.knob4_octave_switch = true;
        var smoothing = cfg.pressure.output_smoothing;
        // The event-17 wrapper is shared between pressure smoothing and the
        // clock's trigger rise, so it exists for either; dac_interpolate is
        // the pressure half alone.  Mirrors tools/build.py.
        ['dac_interpolator', 'dac_flush_pool']
            .forEach(function (n) { blocks[n] = !!smoothing || true; });   // the divider is in every image since phase G
        ['dac_interpolate', 'pressure_target_redirect']
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
    //
    // GEN.controlFlow is flat triples: source, target, and the pool word a
    // call goes through, or 0 for a direct branch.  A pool call whose word a
    // patch rewrites has been redirected, so its factory target is no longer
    // live from that source - the same filter tools/build.py applies.  The
    // page used to be handed only the direct branches, 2,665 of 3,613, and
    // checked the rest against nothing.
    function checkEntryPoints(patches) {
        var patched = {}, problems = [], p, i, b;
        for (p = 0; p < patches.length; p++) {
            for (i = 0; i < patches[p].data.length; i++) {
                patched[patches[p].address + i] = true;
            }
        }
        var live = [];
        for (i = 0; i < GEN.controlFlow.length; i += 3) {
            var pool = GEN.controlFlow[i + 2], redirected = false;
            if (pool) {
                for (b = 0; b < 4; b++) if (patched[pool + b]) redirected = true;
            }
            if (!redirected) live.push(GEN.controlFlow[i], GEN.controlFlow[i + 1]);
        }
        for (p = 0; p < patches.length; p++) {
            var start = patches[p].address, end = start + patches[p].data.length;
            for (i = 0; i < live.length; i += 2) {
                var src = live[i], dst = live[i + 1];
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
        return live.length / 2;
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
        // Same refusal tools/build.py makes, and it has to happen here rather
        // than in the editor: a fine keyboard mapping can put two notes closer
        // together than the latch can tell apart, and the image that comes out
        // is valid in every other way - nothing downstream would catch it.
        BUILDLIB.checkLatchSpacing(cfg, spacingSlots);
        // Same rule as tools/build.py, word for word, and in the same order:
        // the rotation shifts a 32-entry table and wraps by the map's size,
        // so a wider map cannot be shifted.  Since stage 2 phase E the
        // rotation is in every image - either input can be turned on over
        // MIDI - so the map is refused outright.
        var widest = Math.max.apply(null, tables.tuning_period_keys);
        if (widest > 32) {
            throw new Error('alternate_tunings: a keyboard map of ' + widest +
                ' positions cannot be shifted by the key-table rotation, ' +
                'whose table holds 32 entries - use a map of up to 32');
        }
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
            // The settings record this image's tables make, as hex, for the
            // parity matrix against tools/build.py's build/settings.bin.
            settings: BUILDLIB.settingsRecord(numbers, tables, flags.blocks.arp_pattern_tables,
                                              numbers.init_marker, numbers.octave_units, 1)
                .map(function (b) { return (b < 16 ? '0' : '') + b.toString(16); }).join(''),
            patches: records.patches.length,
            skipped: records.skipped,
            changed: applied.changed,
            added: applied.added
        };
    }

    return { build: build };
})();
if (typeof module !== 'undefined' && module.exports) module.exports = WEBBUILD;
