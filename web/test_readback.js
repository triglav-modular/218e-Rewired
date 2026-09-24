// Reading a keyboard and building again keeps what it holds: its tunings,
// its timing numbers and its pattern bank, and a record with another period
// names the same image as the default build.
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

// The period is a setting, so a record with another one fits an image built
// at the octave: the page sends a record only to the marker it names, and
// the tritave build's must be the default build's.  (Audit 2026-09-24,
// finding 1: knob 4's zone count, worked out from the period at build time,
// kept the two images apart and the record was refused as the wrong image.)
vm.runInThisContext(fs.readFileSync(path.join(ROOT, 'web/settings.js'), 'utf8'), { filename: 'web/settings.js' });
var plain = WEBBUILD.build({}, factory), plainRec = bytesOf(plain.settings);
check('a tritave record names the same image marker as the default build, so it can be sent to one',
      SETTINGSMIDI.markerOf(rec) === SETTINGSMIDI.markerOf(plainRec),
      SETTINGSMIDI.markerOf(rec).toString(16) + ' against ' + SETTINGSMIDI.markerOf(plainRec).toString(16));

// --- the page's own read, then a build ------------------------------------
// The real loadFromKeyboard and options out of app.js, the helpers they
// call, and APPLY, with the DOM stubbed: a read of a keyboard, no edit, and
// a build must give the keyboard's record back.  (Audit 2026-09-24,
// findings 2 and 3.)
var APP = fs.readFileSync(path.join(ROOT, 'web', 'app.js'), 'utf8');
function appSource(open, close) {
    var start = APP.indexOf(open);
    if (start < 0) throw new Error('app.js has no ' + open.trim());
    var end = APP.indexOf(close, start);
    return APP.slice(start, end + close.length);
}
function appFunction(name) { return appSource('\n    function ' + name + '(', '\n    }\n'); }
var page = vm.createContext({ BUILDLIB: B, WEBBUILD: WEBBUILD, GEN: GEN, console: console });
vm.runInContext([
    'var state = { patterns: [], slots: [null, null, null], numbers: null, factoryText: null };',
    'var knobRole = { knob1: "order", knob2: "spacing", knob3: "octaves", knob4: "vibrato" };',
    'var vpo = 1.2, pitchOffset = true, nodes = {};',
    'var measured = [], baseline = {}, baselineSources = {}, baselineName = "", baselineHistory = null, interpolated = {};',
    'for (var i = 0; i < 79; i++) measured.push(0);',
    'function $(id) { return nodes[id] || (nodes[id] = { checked: false }); }',
    'function tick(id, v) { $(id).checked = !!v; }',
    'function press(id, v) { if (id === "vpo") vpo = Number(v); else if (id === "offset") pitchOffset = v === "1"; else knobRole[id] = v; }',
    'function renderSlots() {} function renderPatterns() {} function syncCalBody() {} function syncBaseline() {}',
    'function buildTable() {} function drawPlot() {} function validateCal() {} function invalidate() {}',
    'function calibrationInBuild() { return false; }'
].join('\n'), page);
vm.runInContext([
    appSource('\n    var CHECKS = [', '];\n'), appSource('\n    var DEFAULT_CLIX', ';\n'),
    appSource('\n    var APPLY = {', '\n    };\n'),
    appSource('\n    CHECKS.forEach(function (id) {\n        APPLY[id]', '\n    });\n'),
    appFunction('clixPattern'), appFunction('defaultPatterns'), appFunction('keyboardSlots'),
    appFunction('keyboardNumbers'), appFunction('loadFromKeyboard'), appFunction('options')
].join('\n'), page, { filename: 'web/app.js (extracted)' });
page.factory = factory;
page.state.factoryText = factory;
function readThenBuild(record) {
    page.fields = B.settingsFields(record);
    var message = vm.runInContext('loadFromKeyboard({ fields: fields })', page);
    return { message: message, back: bytesOf(vm.runInContext('WEBBUILD.build(options(), factory)', page).settings) };
}

// Every timing number moved off the page's own, inside its bounds, as an
// NRPN sender could leave them; the tunings too, so the record is not one
// this page's defaults would make anyway.
var custom = bytesOf(WEBBUILD.build({ alternate_tunings: [T[0], 'factory', T[1]] }, factory).settings);
var mine = B.timingDefaults(), moved = {};
B.SETTINGS_NUMBERS.forEach(function (n, k) {
    if (n[0] === 'octave_units') return;
    var v = mine[n[0]] >= n[3] ? mine[n[0]] - 1 : mine[n[0]] + 1;
    moved[n[0]] = v;
    custom[L.numbers + 2 * k] = v >> 8; custom[L.numbers + 2 * k + 1] = v & 0xff;
});
var got = readThenBuild(custom), gotFields = B.settingsFields(got.back);
var lost = Object.keys(moved).filter(function (k) { return gotFields.numbers[k] !== moved[k]; });
check('a read and a build keep every timing number the keyboard held', lost.length === 0,
      lost.map(function (k) { return k + ' ' + moved[k] + ' -> ' + gotFields.numbers[k]; }).join(', '));
check('the whole record comes back as it was read', same(custom, got.back, 0x20, custom.length));
check('and the read says the timing numbers were loaded', /the timing numbers/.test(got.message), got.message);
check('the page carries the ten it read, each differing from its own',
      JSON.stringify(page.state.numbers) === JSON.stringify(moved), JSON.stringify(page.state.numbers));
check('the build refuses a timing number outside the loader’s bounds',
      (function () { try { WEBBUILD.build({ settings_numbers: { clock_min_ms: 5 } }, factory); return false; }
                     catch (e) { return /clock_min_ms must be a whole number from 1 to 4/.test(e.message); } })());

// A keyboard built without patterns holds an unused bank: one pattern, no
// steps.  A read must not load that as a pattern, or choosing Patterns does
// not seed the page's own bank and the build refuses an all-rest pattern.
page.state.patterns = [];
got = readThenBuild(plainRec);
check('a default keyboard’s unused bank is not loaded as a pattern', page.state.patterns.length === 0,
      JSON.stringify(page.state.patterns));
check('and its timing numbers are the page’s own, so nothing is carried', page.state.numbers === null,
      JSON.stringify(page.state.numbers));
check('a default keyboard read and built gives its record back', same(plainRec, got.back, 0x20, plainRec.length));
// What choosing Patterns then does: app.js's knob 2 handler seeds an empty bank.
vm.runInContext('if (!state.patterns.length) state.patterns = defaultPatterns(); knobRole.knob2 = "patterns";', page);
var seeded = null;
try { seeded = bytesOf(vm.runInContext('WEBBUILD.build(options(), factory)', page).settings); }
catch (e) { seeded = e.message; }
check('then Patterns builds with the page’s own bank', Array.isArray(seeded)
      && B.settingsFields(seeded).lengths.filter(function (n) { return n > 0; }).length === page.state.patterns.length,
      String(seeded));
// A bank somebody chose comes back as it was.
var banked = bytesOf(WEBBUILD.build({ knob2: 'patterns', arp_patterns: [['x.x.', 4], ['.xx..x', 6]] }, factory).settings);
page.state.patterns = [];
got = readThenBuild(banked);
check('a keyboard’s own bank is loaded pattern for pattern', page.state.patterns.length === 2,
      JSON.stringify(page.state.patterns));
check('and read and built it gives its record back', same(banked, got.back, 0x20, banked.length));

if (failures) { console.log(failures + ' failure(s)'); process.exit(1); }
process.exitCode = 0;
console.log('ALL READ-BACK TESTS PASSED');
