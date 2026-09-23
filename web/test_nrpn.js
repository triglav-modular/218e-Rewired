// The NRPN codec in buildlib.js against the settings record: every
// parameter of a record round-trips through the four Control Changes and
// back into a record-shaped array, masks included, in the order the
// instrument dumps them; then the way back into the page, a dump's pairs
// into a record by name and a pitch table into the offsets it was built
// from.  The firmware's own receive and dump are checked
// by src/SettingsRegression.java against the same map, so a codec that
// agrees with this file agrees with the instrument.
//
//   node web/test_nrpn.js
'use strict';
// The pitch generators read GEN, the page's bundle, which is a plain
// script: under node it is run into this context first.
if (typeof GEN === 'undefined' && typeof require === 'function') {
    require('vm').runInThisContext(
        require('fs').readFileSync(require('path').join(__dirname, 'generated.js'), 'utf8'),
        { filename: 'generated.js' });
}
var B = (typeof BUILDLIB !== 'undefined') ? BUILDLIB : require('./buildlib.js');

var failures = 0;
function check(name, ok, detail) {
    console.log((ok ? 'ok    ' : 'FAIL  ') + name + (ok || !detail ? '' : '  - ' + detail));
    if (!ok) failures++;
}

var numbers = { chord_hold_scans: 200, transpose_cv_period: 819, transpose_cv_hysteresis: 12,
                knob2: 2, knob4: 1, sequencer: 0, portamento_in: 0 };
var tables = {
    pitch_remap: [], tuning_slot0: [], tuning_slot1: [], tuning_slot2: [],
    tuning_period_keys: [12, 7, 36],
    arp_pattern_bank: [], arp_pattern_len: []
};
for (var i = 0; i < 79; i++) tables.pitch_remap.push(485 + 40 * i);
for (var k = 0; k < 32; k++) {
    tables.tuning_slot0.push(500 + 40 * k);
    tables.tuning_slot1.push(510 + 40 * k);
    tables.tuning_slot2.push(520 + 40 * k);
}
// Masks with every third exercised, including bit 31.
var masks = [0x12345678, 0x80000001, 0xFFFFFFFF, 0x00003FFF, 0x0FFFC000, 0xF0000000, 1, 0xDEADBEEF];
masks.forEach(function (m, i) {
    tables.arp_pattern_bank.push(m & 0xFFFF, Math.floor(m / 65536) & 0xFFFF);
    tables.arp_pattern_len.push(1 + i * 4);
});
var record = B.settingsRecord(numbers, tables, true, 0xB007, 484, 5);

var params = B.nrpnParamsOf(record);
check('338 parameters in a record', params.length === 338, String(params.length));
check('the walk is the instrument\'s: cells, pitch, tuning, keys, masks, lengths',
      params[0][0] === 0 && params[31][0] === 31 && params[32][0] === 0x80 && params[110][0] === 0xce
      && params[111][0] === 0x100 && params[206][0] === 0x15f && params[207][0] === 0x160
      && params[210][0] === 0x180 && params[305][0] === 0x1df && params[306][0] === 0x1e0
      && params[337][0] === 0x1ff);
check('the live bytes are not pushed', params.every(function (p) { return p[0] < 0x20 || p[0] >= 0x80; }));
check('a number reads its cell', B.nrpnValueOf(record, 8) === 200 && B.nrpnValueOf(record, 5) === 819);
check('an option cell reads its index: knob2 swing, knob4 trn, sequencer off, jack portamento',
      B.nrpnValueOf(record, 18) === 2 && B.nrpnValueOf(record, 20) === 1 && B.nrpnValueOf(record, 21) === 0
      && B.nrpnValueOf(record, 26) === 0);
check('an option left out is the page\'s default: latch on, knob 1 order, the fix and its portamento on',
      B.nrpnValueOf(record, 16) === 1 && B.nrpnValueOf(record, 17) === 0 && B.nrpnValueOf(record, 23) === 1
      && B.nrpnValueOf(record, 24) === 1 && B.nrpnValueOf(record, 25) === 1);
check('the reserved cells are zero', B.nrpnValueOf(record, 10) === 0 && B.nrpnValueOf(record, 15) === 0
      && B.nrpnValueOf(record, 27) === 0 && B.nrpnValueOf(record, 31) === 0);
check('the cells are where the firmware has them', B.SETTINGS_OPTION_CELL === 16 && B.SETTINGS_CELLS.length === 32
      && B.SETTINGS_CELLS[16][0] === 'latching_arp' && B.SETTINGS_CELLS[26][0] === 'portamento_in'
      && B.SETTINGS_CELLS[18][3] === 4 && B.SETTINGS_CELLS[27][0] === null && B.NRPN_LIVE.base === 0x20 && B.NRPN_LIVE.count === 16);
check('optionCells and optionsOf are inverses', JSON.stringify(B.optionsOf(B.optionCells({ knob2: 'patterns', latching_arp: false, portamento_in: 'portamento' })))
      === JSON.stringify({ latching_arp: false, knob1: 'order', knob2: 'patterns', knob3: 'octaves', knob4: 'vibrato',
                           sequencer: true, clock_divide: true, pressure_fix: true, pressure_portamento: true,
                           quantize_presets: true, portamento_in: 'portamento' }));
check('an option outside its choices is refused', (function () {
    try { B.optionCells({ knob2: 'random' }); return false; } catch (e) { return /knob2 must be one of/.test(e.message); }
})());
check('a cell past its range is refused by the record', (function () {
    try { B.settingsRecord({ knob2: 5 }, tables, true, 0xB007, 484, 5); return false; } catch (e) { return /knob2 must be 0\.\.4/.test(e.message); }
})());
check('pressure_portamento without pressure_fix is refused by the record', (function () {
    try { B.settingsRecord({ pressure_fix: 0, pressure_portamento: 1 }, tables, true, 0xB007, 484, 5); return false; }
    catch (e) { return /needs pressure_fix/.test(e.message); }
})());
check('and both off is fine', B.nrpnValueOf(B.settingsRecord({ pressure_fix: 0, pressure_portamento: 0 }, tables, true, 0xB007, 484, 5), 24) === 0);
check('a pitch entry reads its cell', B.nrpnValueOf(record, 0x83) === 485 + 120);
check('a tuning entry reads its cell', B.nrpnValueOf(record, 0x100 + 64 + 5) === 520 + 200);
check('keys per period read theirs', B.nrpnValueOf(record, 0x162) === 36);
check('a length reads its cell', B.nrpnValueOf(record, 0x1e1) === 5);
check('the thirds of 0x12345678', B.nrpnValueOf(record, 0x180) === (0x12345678 & 0x3FFF)
      && B.nrpnValueOf(record, 0x181) === ((0x12345678 >>> 14) & 0x3FFF)
      && B.nrpnValueOf(record, 0x182) === (0x12345678 >>> 28));
check('bit 31 rides in the top third', B.nrpnValueOf(record, 0x180 + 3) === 1
      && B.nrpnValueOf(record, 0x181 + 3) === 0 && B.nrpnValueOf(record, 0x182 + 3) === 8);
check('a gap names nothing', B.nrpnValueOf(record, 0x20) === null && B.nrpnValueOf(record, 0x30) === null
      && B.nrpnValueOf(record, 0xcf) === null && B.nrpnValueOf(record, 0x163) === null && B.nrpnValueOf(record, 0x200) === null);

// Every parameter into a blank record: the payload comes back exactly.
var blank = [];
for (var b = 0; b < record.length; b++) blank.push(0);
var applied = params.every(function (p) { return B.nrpnApply(blank, p[0], p[1]); });
check('every parameter applies', applied);
var same = true;
for (var o = 0x20; o < 0x288; o++) if (blank[o] !== record[o]) { same = false; break; }
check('applying every parameter rebuilds the payload byte for byte', same);
check('a gap applies nowhere, the live bytes included', !B.nrpnApply(blank, 0x3f00, 1) && !B.nrpnApply(blank, 0x30, 1)
      && !B.nrpnApply(blank, 0x21, 1));

// A third replaces only its own bits, whatever order they arrive in.
var scratch = record.slice();
B.nrpnApply(scratch, 0x181, 0x2ABC);
check('the middle third leaves the other two alone',
      B.nrpnValueOf(scratch, 0x180) === (0x12345678 & 0x3FFF) && B.nrpnValueOf(scratch, 0x181) === 0x2ABC
      && B.nrpnValueOf(scratch, 0x182) === (0x12345678 >>> 28));
B.nrpnApply(scratch, 0x182, 0x9);
B.nrpnApply(scratch, 0x180, 0x1234);
var m = ((scratch[0x1c8 + 2] << 8) | scratch[0x1c8 + 3]) * 65536 + ((scratch[0x1c8] << 8) | scratch[0x1c8 + 1]);
check('three thirds make the mask, low halfword first', m === (0x1234 + 0x2ABC * 16384 + 9 * 268435456));

// Wire form and back.
var msgs = B.nrpnMessages(0x181, 0x2ABC);
check('four Control Changes on channel 16',
      msgs.length === 4 && msgs.every(function (x) { return x[0] === 0xBF; })
      && msgs[0][1] === 99 && msgs[0][2] === 3 && msgs[1][1] === 98 && msgs[1][2] === 1
      && msgs[2][1] === 6 && msgs[2][2] === 0x55 && msgs[3][1] === 38 && msgs[3][2] === 0x3C);
var dec = B.nrpnDecoder();
var got = null;
msgs.forEach(function (x) { var r = dec.feed(x[0], x[1], x[2]); if (r) got = r; });
check('the decoder completes on the data LSB', got && got.param === 0x181 && got.value === 0x2ABC);
check('another channel is ignored', dec.feed(0xB3, 38, 1) === null);
check('a foreign controller is ignored', dec.feed(0xBF, 5, 1) === null);
var all = [];
params.forEach(function (p) {
    B.nrpnMessages(p[0], p[1]).forEach(function (x) { var r = dec.feed(x[0], x[1], x[2]); if (r) all.push([r.param, r.value]); });
});
check('every parameter survives the wire', all.length === params.length
      && all.every(function (p, i) { return p[0] === params[i][0] && p[1] === params[i][1]; }));

// The way back: a dump's pairs into a record, and the record by name -
// what a read hands the page.
var back = B.nrpnRecordOf(all);
var samePayload = true;
for (var o = 0x20; o < 0x288; o++) if (back[o] !== record[o]) samePayload = false;
check('a dump\'s pairs rebuild the record\'s payload byte for byte', samePayload);
var f = B.settingsFields(back);
check('the options come back by name', f.cells.knob2 === 2 && f.options.knob2 === 'swing' && f.options.knob4 === 'trn'
      && f.options.sequencer === false && f.options.portamento_in === 'portamento' && f.options.latching_arp === true
      && Object.keys(f.options).length === 11, JSON.stringify(f.options));
// The live bytes ride in a dump between the cells and the pitch curve.
var liveDump = all.slice();
for (var lv = 0; lv < 16; lv++) liveDump.splice(32 + lv, 0, [0x20 + lv, lv === 2 ? 3 : (lv === 4 ? 1 : B.nrpnValueOf(record, 16 + lv))]);
check('the live bytes come out of a dump', JSON.stringify(B.nrpnLiveOf(liveDump)) === JSON.stringify([1, 0, 3, 0, 1, 0, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0]));
check('and are not in the record built from it', JSON.stringify(B.nrpnRecordOf(liveDump).slice(0x20, 0x288)) === JSON.stringify(record.slice(0x20, 0x288)));
check('a dump without them gives null', B.nrpnLiveOf(all) === null);
check('pending names the options whose cell and live byte differ', B.pendingOptions(record, B.nrpnLiveOf(liveDump)).join(',') === 'knob2'
      && B.pendingOptions(record, B.nrpnLiveOf(all)).length === 0
      && B.pendingOptions(record, [1, 0, 2, 0, 1, 0, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0]).length === 0
      && B.pendingOptions(record, [1, 0, 2, 0, 1, 0, 1, 1, 1, 1, 0, 1, 0, 0, 0, 0]).join(',') === 'cell 27');
check('the fields come back by name', f.numbers.chord_hold_scans === 200
      && f.numbers.transpose_cv_period === 819 && f.numbers.tie_glide_rate === 60
      && f.pitch_remap.length === 79 && f.pitch_remap[78] === 485 + 40 * 78
      && f.tuning_slot0[0] === 500 && f.tuning_slot2[31] === 520 + 40 * 31
      && f.tuning_period_keys.join(',') === '12,7,36'
      && f.masks.length === 32 && f.masks[2] === 0xFFFFFFFF && f.masks[7] === 0xDEADBEEF
      && f.lengths[7] === 29 && f.lengths[8] === 0 && f.masks[8] === 0,
      JSON.stringify(f.numbers));

// A pitch table back into the offsets and the two settings it was built
// with.  Both scalings and both offsets, on the flat table and on one
// pushed about by a calibration's worth of counts: the same table has to
// build again from what came out, entry for entry, because that is what
// the page rebuilds after a read.
[[1.2, true], [1.2, false], [1.0, true], [1.0, false]].forEach(function (c) {
    var cfg = B.expand({ volts_per_octave: c[0], pitch_offset: c[1] });
    var flat = B.pitchTable(cfg, cfg._calibration);
    var bent = flat.map(function (v, i) { return v && i % 2 ? v + (i % 5) - 2 : v; });
    [flat, bent].forEach(function (table, which) {
        var name = (which ? 'a bent' : 'the flat') + ' table at ' + c[0] + ' V/oct, offset ' + (c[1] ? 'on' : 'off');
        var was = B.pitchTableSettings(table);
        check(name + ': the scaling and the offset are read off it',
              was.volts_per_octave === c[0] && was.pitch_offset === c[1], JSON.stringify(was));
        var rows = B.pitchCents(cfg, table);
        var again = B.pitchTable(cfg, rows);
        check(name + ': builds again from its offsets', rows.length === 79
              && again.length === table.length && again.every(function (v, i) { return v === table[i]; }));
    });
});
check('a table of the wrong length is refused', (function () {
    try { B.pitchCents(B.expand({}), [1, 2, 3]); return false; } catch (e) { return /79 entries/.test(e.message); }
})());

// The identity block, sent last, with the generation in three parts.
var identity = [[0x3f76, 0x312], [0x3f77, 2], [0x3f78, 484], [0x3f79, 0xff], [0x3f7a, 2], [0x3f7b, 3], [0x3f7c, 5], [0x3f7d, 7], [0x3f7e, 0x3007]];
check('no identity until the layout version arrives', B.nrpnIdentity(identity) === null);
identity.push([0x3f7f, 2]);
var id = B.nrpnIdentity(identity);
check('the identity block decodes', id && id.layoutVersion === 2 && id.imageMarker === 0xB007
      && id.generation === 7 + 5 * 16384 + 3 * 268435456 && id.commitState === 2
      && id.slotLoaded === 0xff && id.octaveUnits === 484 && id.firmwareVersion === '3.1.2', JSON.stringify(id));
check('a keyboard that sends no version decodes with null for it',
      B.nrpnIdentity(identity.slice(1)).firmwareVersion === null);
check('the version code packs 6.4.4 and unpacks', B.versionCode('3.0.0') === 0x300 && B.versionText(0x300) === '3.0.0'
      && B.versionCode('63.15.15') === 0x3fff && B.versionText(0x3fff) === '63.15.15' && B.versionCode(GEN.version) === B.computeNumbers(B.expand({})).firmware_version_code);
check('a version that does not fit is refused', (function () { try { B.versionCode('64.0.0'); return false; } catch (e) { return true; } })()
      && (function () { try { B.versionCode('3.0'); return false; } catch (e) { return true; } })());
check('versions compare by part, not by string', B.compareVersions('3.0.0', '3.0.0') === 0 && B.compareVersions('3.0.1', '3.0.0') > 0
      && B.compareVersions('3.0.0', '3.10.0') < 0 && B.compareVersions('10.0.0', '9.9.9') > 0);
check('the commands are where the firmware has them',
      B.NRPN_COMMANDS.commit === 0x3f00 && B.NRPN_COMMANDS.commitKey === 0x2a2a
      && B.NRPN_COMMANDS.reload === 0x3f01 && B.NRPN_COMMANDS.defaults === 0x3f02
      && B.NRPN_COMMANDS.dump === 0x3f03 && B.NRPN_COMMANDS.restart === 0x3f04
      && B.NRPN_COMMANDS.restartKey === 0x2a2a && B.NRPN_COMMANDS.identity === 0x3f7f);
check('the layout is 2 and the build numbers carry the option cells',
      B.SETTINGS_LAYOUT.version === 2 && (function () {
          var n = B.computeNumbers(B.expand({ knob2: 'swing', knob4: 'trn', sequencer: false, portamento_in: 'portamento', latching_arp: false }));
          return n.knob2 === 2 && n.knob4 === 1 && n.sequencer === 0 && n.portamento_in === 0 && n.latching_arp === 0
              && n.knob1 === 0 && n.knob3 === 0 && n.clock_divide === 1 && n.pressure_fix === 1 && n.pressure_portamento === 1
              && n.quantize_presets === 1;
      })() && (function () {
          var n = B.computeNumbers(B.expand({ knob1: 'orders', knob2: 'factory', knob3: 'factory', knob4: 'factory',
                                              pressure_fix: false, pressure_portamento: false, quantize_presets: false }));
          return n.knob1 === 1 && n.knob2 === 4 && n.knob3 === 1 && n.knob4 === 2 && n.pressure_fix === 0
              && n.pressure_portamento === 0 && n.quantize_presets === 0;
      })());

if (failures) { console.log(failures + ' failure(s)'); process.exit(1); }
console.log('ALL NRPN CODEC TESTS PASSED');
