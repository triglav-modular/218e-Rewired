// Does the JavaScript pipeline produce the same build.properties as Python?
//
// If it does, the image must match too: the assembler consuming those
// properties is already proven byte-identical to Ghidra.
// Capture the engine's argument list before the IIFE: inside a function
// `arguments` is that function's own, which is empty.
var ARGV = (typeof arguments !== 'undefined') ? Array.prototype.slice.call(arguments)
    : (typeof process !== 'undefined' && process.argv ? process.argv.slice(2) : []);

(function () {
    'use strict';
    var optionsJson = ARGV[0], factoryPath = ARGV[1], expectedPath = ARGV[2];
    var configName = ARGV[3] || 'config/218e.toml';

    var options = JSON.parse(readFile(optionsJson));
    var factory = BUILDLIB.parseHexText(readFile(factoryPath), 'factory');
    var cfg = BUILDLIB.expand(options);

    // tables, in the same order build.py builds them
    var tables = {};
    tables.pressure_curve = BUILDLIB.pressureCurve(
        cfg.pressure.curve.span, cfg.pressure.curve.onset_db, cfg.pressure.curve.onset_fade);
    tables.pitch_remap = BUILDLIB.pitchTable(cfg, cfg._calibration);
    cfg._tunings.forEach(function (slot, index) {
        if (slot === 'factory') {
            tables['tuning_slot' + index] = BUILDLIB.factoryTuning(factory.memory);
        } else {
            var cents = BUILDLIB.parseScala(slot.text, slot.name);
            var offset = BUILDLIB.anchorOffset(cents, cfg.tuning.reference_key);
            tables['tuning_slot' + index] = BUILDLIB.tuningTable(
                cents, cfg.tuning.base_units, cfg.tuning.units_per_octave, offset);
        }
    });
    var blackMask = 0x0A54A54A;
    var excess = BUILDLIB.floorHalf(cfg.pressure.black_key_scale * 256) - 256;
    var bk = [];
    for (var k = 0; k < 32; k++) bk.push(((blackMask >>> k) & 1) ? excess : 0);
    tables.black_key_excess = bk;

    var flags = BUILDLIB.resolveFlags(cfg);
    var blocks = flags.blocks, features = flags.features;
    var numbers = BUILDLIB.computeNumbers(cfg);

    if (cfg._pressure_factory) {
        ['pressure_fn_pool', 'pressure_float_helper_pool', 'knob1_pool',
         'pressure_gain_nop'].forEach(function (n) { blocks[n] = false; });
    }
    var anyTuning = cfg._tunings.some(function (t) { return t !== 'factory'; });
    features.alternate_tunings = anyTuning;
    if (!anyTuning) {
        blocks.edit_key27_tuning_slot1 = false;
        blocks.edit_key28_tuning_slot0 = false;
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
    ['dac_interpolator', 'dac_flush_pool', 'pressure_target_redirect'].forEach(function (n) {
        blocks[n] = !!smoothing;
    });

    numbers.init_marker = BUILDLIB.initMarker(blocks, features, numbers, tables);

    var got = BUILDLIB.writeProperties(configName, blocks, features, numbers, tables);
    var want = readFile(expectedPath);
    if (got === want) {
        print('IDENTICAL');
        return;
    }
    print('DIFFERS');
    var a = got.split('\n'), b = want.split('\n');
    var shown = 0;
    for (var i = 0; i < Math.max(a.length, b.length) && shown < 8; i++) {
        if (a[i] !== b[i]) {
            print('  line ' + (i + 1));
            print('    js:     ' + String(a[i]).substring(0, 100));
            print('    python: ' + String(b[i]).substring(0, 100));
            shown++;
        }
    }
})();
