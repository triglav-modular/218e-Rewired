// Reading a keyboard and building again keeps its tunings.
//
// A read hands the page the record the keyboard holds; a tuning table does
// not turn back into a scale, so each slot comes back as its table, its keys
// per period and the period (BUILDLIB.isTableSlot), and the next build
// carries them as they came.  Before that, a read left the page's own slots
// in place and a rebuild switched the keyboard's tunings off and replaced
// its tables.  This builds an image from scales, reads its record back into
// table slots the way the page's read does, builds again, and requires the
// same record and the same image.
//
// Needs the factory image, which is not in the repository, so it steps
// aside without one (CI has none).
//
//   node web/test_readback.js
'use strict';
var fs = require('fs'), path = require('path'), vm = require('vm');
var ROOT = path.resolve(__dirname, '..');
var FACTORY = path.join(ROOT, 'firmware', '218eV3_v369_DFU.hex');

process.exitCode = 1;
if (!fs.existsSync(FACTORY)) {
    console.log('skip  no factory image at firmware/218eV3_v369_DFU.hex');
    process.exitCode = 0;
    return;
}
['web/generated.js', 'web/sha256.js', 'web/buildlib.js', 'web/assembler.js', 'web/build.js'].forEach(function (f) {
    vm.runInThisContext(fs.readFileSync(path.join(ROOT, f), 'utf8'), { filename: f });
});
var B = BUILDLIB, factory = fs.readFileSync(FACTORY, 'utf8');
var failures = 0;
function check(name, ok, detail) {
    console.log((ok ? 'ok    ' : 'FAIL  ') + name + (ok || !detail ? '' : '  - ' + detail));
    if (!ok) failures++;
}
function bytesOf(hex) {
    var out = [];
    for (var i = 0; i < hex.length; i += 2) out.push(parseInt(hex.substr(i, 2), 16));
    return out;
}
function same(a, b, from, to) {
    for (var i = from; i < to; i++) if (a[i] !== b[i]) return false;
    return true;
}
// The slots a read of this record gives the page: every slot as a table.
function tableSlots(record) {
    var f = B.settingsFields(record);
    return [0, 1, 2].map(function (s) {
        return { name: 'the keyboard’s tuning', table: f['tuning_slot' + s].slice(),
                 periodKeys: f.tuning_period_keys[s], octaveUnits: f.numbers.octave_units };
    });
}
var L = B.SETTINGS_LAYOUT;
function roundTrip(label, tunings) {
    var first = WEBBUILD.build({ alternate_tunings: tunings }, factory);
    var rec = bytesOf(first.settings);
    var again = WEBBUILD.build({ alternate_tunings: tableSlots(rec) }, factory);
    var back = bytesOf(again.settings);
    var f = B.settingsFields(rec), g = B.settingsFields(back);
    check(label + ': the tuning tables and keys per period come back as they were',
          same(rec, back, L.tuning, L.tuning + 192 + 8));
    check(label + ': the tunings stay switched on, and the period stays ' + f.numbers.octave_units,
          g.options.alternate_tunings === true && g.numbers.octave_units === f.numbers.octave_units);
    check(label + ': the whole record is the same', same(rec, back, 0x10, rec.length));
    check(label + ': and so is the image', first.sha256 === again.sha256, first.sha256.slice(0, 12) + ' ' + again.sha256.slice(0, 12));
    return rec;
}

var T = GEN.bundledTunings;
roundTrip('two scales and a factory slot', [T[0], 'factory', T[1]]);
// A scale that repeats at the tritave: twelve equal steps of 3/1, so the
// period the octave controls step is 767 units rather than 484.
var tritave = '! tritave.scl\ntwelve equal steps of 3/1\n 12\n!\n';
for (var k = 1; k < 12; k++) tritave += ' ' + (1901.955 * k / 12).toFixed(6) + '\n';
tritave += ' 3/1\n';
var tri = { name: 'tritave.scl', text: tritave };
var rec = roundTrip('three tritave slots', [tri, tri, tri]);
check('the tritave record carries its own period', B.settingsFields(rec).numbers.octave_units !== 484,
      String(B.settingsFields(rec).numbers.octave_units));
// A keyboard's table beside a scale that repeats elsewhere is the same
// refusal two disagreeing scales get: one set of octave controls.
var refused = null;
try { WEBBUILD.build({ alternate_tunings: [tableSlots(rec)[0], T[2]] }, factory); }
catch (e) { refused = e.message; }
check('a keyboard’s tritave table beside an octave scale is refused for the period',
      refused !== null && /disagree about the period/.test(refused), String(refused));

if (failures) { console.log(failures + ' failure(s)'); process.exit(1); }
process.exitCode = 0;
console.log('ALL READ-BACK TESTS PASSED');
