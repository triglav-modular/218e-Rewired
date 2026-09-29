// What the page remembers between visits.
//
// Two halves.  The arithmetic - which settings are kept and what an old save
// turns into against today's defaults - is in buildlib and is exercised
// directly.  The order a restore applies things in is a DEPENDENCY, not a
// preference: three pairs will silently undo each other if they are swapped,
// and the page walks BUILDLIB.SETTINGS_ORDER rather than a hand-written
// sequence, so asserting on that array is asserting on what the page does.
//
// The last check reads app.js itself.  An applier added without an entry in
// the order list is never called, and an entry with no applier is a control
// nobody restores: both are silent, and neither shows up in anything that
// only tests buildlib.
var B = (typeof BUILDLIB !== 'undefined') ? BUILDLIB : require('./buildlib.js');

var failures = 0;
function ok(name, cond, detail) {
    if (!cond) { failures++; print_('FAIL  ' + name + (detail ? '  ' + detail : '')); }
    else print_('ok    ' + name);
}
function print_(s) {
    if (typeof print === 'function') print(s); else console.log(s);
}

// --- only the deviations are kept ---------------------------------------
var base = { latching_arp: true, sequencer: true, volts_per_octave: '1.2' };

ok('an untouched page saves nothing',
   JSON.stringify(B.settingsDiff({ latching_arp: true, sequencer: true,
                                   volts_per_octave: '1.2' }, base)) === '{}');

ok('only the moved control is kept',
   JSON.stringify(B.settingsDiff({ latching_arp: true, sequencer: false,
                                   volts_per_octave: '1.2' }, base))
   === '{"sequencer":false}');

// --- an old save against today's defaults --------------------------------
// The whole reason deviations are stored rather than a snapshot.  Someone
// saved before `quantize_presets` existed; the page now ships it on.  A
// snapshot would hand back an object with no such key and turn it off.
var older = { sequencer: false };
var today = { latching_arp: true, sequencer: true, volts_per_octave: '1.2',
              quantize_presets: true };
var got = B.settingsPick(older, today);
ok('a control added since the save keeps its new default',
   got.quantize_presets === true);
ok('a control the visitor moved is still restored', got.sequencer === false);
ok('a control they never touched follows the page', got.latching_arp === true);

// --- a save is script-writable storage, so it is not trusted -------------
ok('a wrong type falls back to the default',
   B.settingsPick({ sequencer: 'yes' }, base).sequencer === true);
ok('a non-finite number falls back to the default',
   B.settingsPick({ volts_per_octave: 1 }, { volts_per_octave: 1.2 })
     .volts_per_octave === 1);
ok('NaN falls back to the default',
   B.settingsPick({ volts_per_octave: NaN }, { volts_per_octave: 1.2 })
     .volts_per_octave === 1.2);
ok('a key the page no longer has is dropped',
   !('remap_knobs' in B.settingsPick({ remap_knobs: true }, base)));
ok('a garbage blob restores the defaults entirely',
   JSON.stringify(B.settingsPick('not an object', base)) === JSON.stringify(base));
ok('a null blob restores the defaults entirely',
   JSON.stringify(B.settingsPick(null, base)) === JSON.stringify(base));

// --- the order is the dependency ----------------------------------------
var order = B.SETTINGS_ORDER;
function at(k) { return order.indexOf(k); }
function before(a, b, why) {
    ok(a + ' is restored before ' + b, at(a) >= 0 && at(b) >= 0 && at(a) < at(b), why);
}
// setPitchOffset drops a loaded table on purpose: changing the offset
// renumbers every semitone, so the table no longer describes this build.
// Restore the calibration first and it is thrown away on load.
before('pitch_offset', 'calibration', 'the offset renumbers the table');
// The volts per octave drops one too: a table belongs to the scaling it was
// taken at (below, the page's own buttons).
before('volts_per_octave', 'calibration', 'the scaling drops a loaded table as well');
// Setting knob 2 to patterns seeds the bank with CLIX when the bank is empty.
before('patterns', 'knob2', 'an empty bank is seeded by the knob');
// syncPortamento force-clears portamento while the pressure fix is off.
before('pressure_fix', 'pressure_portamento', 'portamento needs the fix');

var dupes = order.filter(function (k, i) { return order.indexOf(k) !== i; });
ok('no key is applied twice', !dupes.length, dupes.join(','));

// --- the page's appliers and the order list agree ------------------------
// Skipped rather than failed where the file is not beside this one: the
// suite still has to run from a checkout that has only buildlib.
var app = null;
if (typeof require === 'function') {
    try { app = require('fs').readFileSync(__dirname + '/app.js', 'utf8'); }
    catch (e) { app = null; }
}
if (!app) {
    print_('skip  app.js is not readable from here');
} else {
    var block = /var APPLY = \{([\s\S]*?)\n    \};/.exec(app);
    var checks = /var CHECKS = \[([\s\S]*?)\];/.exec(app);
    ok('app.js still has an APPLY map', !!block);
    ok('app.js still has a CHECKS list', !!checks);
    if (block && checks) {
        var keys = [];
        block[1].replace(/^\s{8}(\w+): function/gm, function (_, k) {
            keys.push(k); return _;
        });
        checks[1].replace(/'([a-z_]+)'/g, function (_, k) { keys.push(k); return _; });

        // The defaults must come from what the DOCUMENT declares, not from
        // what is on screen when the page loads.  A browser restores checkbox
        // state across a reload before any script runs, so reading the live
        // checkbox captures the visitor's last choice AS the default: the
        // deviation then measures zero, is never saved, and the choice is
        // lost on the visit after next.  It went wrong exactly that way once.
        // Only visible on a second reload, and silent, so it is worth a guard
        // that a later simplification has to argue with.
        ok('the defaults are taken from the markup',
           /var DEFAULTS = scalars\(true\);/.test(app));
        ok('scalars reads defaultChecked for them',
           /markup \? el\.defaultChecked : el\.checked/.test(app));

        var missing = keys.filter(function (k) { return order.indexOf(k) < 0; });
        var unapplied = order.filter(function (k) { return keys.indexOf(k) < 0; });
        ok('every applier is in the order list', !missing.length,
           'never restored: ' + missing.join(','));
        ok('every key in the order list has an applier', !unapplied.length,
           'no applier: ' + unapplied.join(','));
    }
}

// --- switching the volts per octave drops a loaded table ------------------
// As switching the offset does.  A keyboard's table comes into the page as
// offsets read off counts already rounded at its own scaling, so built again
// at the other one it was rounded twice: the owner's calibration, read at
// 1 V/oct and built at 1.2, came out a count off on 21 entries of the table
// it makes at 1.2 itself.  The page's own buttons and its own load, out of
// app.js, with the DOM stubbed.  Node only: it needs vm.
//
// And it says so on the calibration line, in the owner's words (2026-09-28).
// That line is validateCal()'s, which the switch runs last, so a message put
// there before it was cleared or replaced at once, as the offset's own was
// until 2026-09-29.  So what is read here is the line as the page leaves it,
// written by the page's own msg() and validateCal(), for both switches.
if (app && typeof require === 'function') {
    var vm = require('vm'), fs = require('fs'), path = require('path');
    var appSource = function (open, close) {
        var start = app.indexOf(open);
        if (start < 0) throw new Error('app.js has no ' + open.trim());
        return app.slice(start, app.indexOf(close, start) + close.length);
    };
    var appFunction = function (name) { return appSource('\n    function ' + name + '(', '\n    }\n'); };
    var page = vm.createContext({ console: console });
    ['generated.js', 'buildlib.js'].forEach(function (f) {
        vm.runInContext(fs.readFileSync(path.join(__dirname, f), 'utf8'), page, { filename: 'web/' + f });
    });
    vm.runInContext([
        'var nodes = {};',
        'function button(v) { return { dataset: { v: v }, attrs: {}, on: {},',
        '    setAttribute: function (k, x) { this.attrs[k] = x; }, getAttribute: function (k) { return this.attrs[k]; },',
        '    addEventListener: function (t, f) { this.on[t] = f; }, click: function () { this.on.click(); } }; }',
        'function element(id) { return { id: id, checked: false, textContent: "", disabled: false, kids: [],',
        '    set innerHTML(v) { this.kids = []; }, appendChild: function (c) { this.kids.push(c); },',
        '    children: [], classList: { toggle: function () {} } }; }',
        'var document = { createElement: function () { return element(null); } };',
        'function $(id) { return nodes[id] || (nodes[id] = element(id)); }',
        'function shown(id) { return $(id).kids.map(function (k) { return { kind: k.className, text: k.textContent }; }); }',
        '$("vpo").children = [button("1.0"), button("1.2")]; $("vpo").children[1].setAttribute("aria-pressed", "true");',
        '$("offset").children = [button("0"), button("1")]; $("offset").children[1].setAttribute("aria-pressed", "true");',
        'function bindDashes() {} function buildTable() {} function drawPlot() {}',
        'function invalidate() {} function updateOffsetNote() {} function syncCalBody() {}',
        'var pitchOffset = true, PLAYABLE_LOW = 3, PLAYABLE_HIGH = 67, TABLE_ENTRIES = 79;',
        'var measured = [], interpolated = {};',
        'for (var i = 0; i < TABLE_ENTRIES; i++) measured.push(0);',
        appSource('\n    var baseline = {}', ';\n'),
        appFunction('clearBaseline'), appFunction('haveBaseline'), appFunction('rows'),
        appFunction('syncBaseline'), appFunction('press'), appFunction('loadPitchTable'),
        appFunction('msg'), appFunction('calibrationBlank'), appFunction('validateCal'),
        appSource('\n    var vpo = 1.2;', '\n    });\n'),
        appFunction('setPitchOffset'),
        appSource("\n    Array.prototype.forEach.call($('offset').children", '\n    });\n'),
        'clearBaseline();',
        'function built() { return BUILDLIB.pitchTable(BUILDLIB.expand({ volts_per_octave: vpo,',
        '    pitch_offset: pitchOffset }), rows()); }'
    ].join('\n'), page, { filename: 'web/app.js (extracted)' });
    var PB = page.BUILDLIB;
    var owner = PB.parseCalibration(fs.readFileSync(path.join(__dirname, '..', 'calibration',
                                                             '218e-pitch-calibration.csv'), 'utf8'), 79);
    var ownerRows = [];
    for (var s = 0; s < 79; s++) ownerRows.push({ semitone: s, cents: owner.rows[s] });
    var at10 = PB.expand({ volts_per_octave: 1.0 }), at12 = PB.expand({ volts_per_octave: 1.2 });
    var held = PB.pitchTable(at10, ownerRows);
    var twice = PB.pitchTable(at12, PB.pitchCents(at10, held)), once = PB.pitchTable(at12, ownerRows);
    var off = twice.filter(function (v, e) { return v !== once[e]; }).length;
    page.held = held;
    vm.runInContext('loadPitchTable(held, { volts_per_octave: 1.0, pitch_offset: true }, ' +
                    '"the keyboard\\u2019s table", {});', page);
    var loaded = vm.runInContext('({ vpo: vpo, have: haveBaseline(), built: built() })', page);
    ok('a keyboard’s table at 1 V/oct loads at its own scaling and builds back exactly',
       loaded.vpo === 1.0 && loaded.have && JSON.stringify(loaded.built) === JSON.stringify(held),
       JSON.stringify({ vpo: loaded.vpo, have: loaded.have }));
    vm.runInContext('$("vpo").children[1].click();', page);
    var switched = vm.runInContext('({ vpo: vpo, have: haveBaseline(), built: built(), ' +
                                   'line: $("calBase").textContent })', page);
    var flat = PB.pitchTable(at12, at12._calibration);
    ok('switching to 1.2 V/oct drops it, as switching the offset does, rather than build it ' +
       'rounded twice (' + off + ' entries a count off)',
       switched.vpo === 1.2 && !switched.have && JSON.stringify(switched.built) === JSON.stringify(flat) &&
       /^No table loaded/.test(switched.line),
       JSON.stringify({ vpo: switched.vpo, have: switched.have,
                        rescaled: JSON.stringify(switched.built) === JSON.stringify(twice) }));
    page.held12 = once;
    vm.runInContext('loadPitchTable(held12, { volts_per_octave: 1.2, pitch_offset: true }, ' +
                    '"the tuned table", {}); $("vpo").children[1].click();', page);
    var again = vm.runInContext('({ vpo: vpo, have: haveBaseline(), built: built() })', page);
    ok('and pressing the scaling it is already at keeps it',
       again.vpo === 1.2 && again.have && JSON.stringify(again.built) === JSON.stringify(once),
       JSON.stringify({ vpo: again.vpo, have: again.have }));

    var DROPPED = 'The loaded table was dropped: changing the volts per octave rescales every ' +
        'entry, so it no longer describes this build. Load it again if it was measured at this setting.';
    function line() { return vm.runInContext('shown("calMsg")', page); }
    function says(got, kind, text) {
        return got.length === 1 && got[0].kind === 'msg ' + kind && got[0].text === text;
    }
    // Loaded, the line says the table builds; switched, it says the table
    // went, and that is what it still says once the switch is done.
    vm.runInContext('loadPitchTable(held, { volts_per_octave: 1.0, pitch_offset: true }, ' +
                    '"the keyboard\\u2019s table", {});', page);
    var before = line();
    vm.runInContext('$("vpo").children[1].click();', page);
    var after = line();
    ok('the drop is said on the calibration line, in the owner’s words, and stays there',
       says(before, 'ok', 'Correction is monotonic and inside the 12-bit DAC.') && says(after, 'bad', DROPPED),
       JSON.stringify({ before: before, after: after }));
    // With readings on the page the table still builds, and the drop stands
    // in for the verdict.  With readings that run past the DAC it goes ahead
    // of the reason, which still has to be read.
    vm.runInContext('$("vpo").children[0].click(); loadPitchTable(held, { volts_per_octave: 1.0, ' +
                    'pitch_offset: true }, "the keyboard\\u2019s table", {}); measured[30] = 4.5;', page);
    vm.runInContext('$("vpo").children[1].click();', page);
    var readings = line();
    vm.runInContext('$("vpo").children[0].click(); loadPitchTable(held, { volts_per_octave: 1.0, ' +
                    'pitch_offset: true }, "the keyboard\\u2019s table", {});' +
                    'for (var e = 40; e <= 67; e++) measured[e] = -6000;', page);
    var threw = vm.runInContext('(function () { try { $("vpo").children[1].click(); return null; } ' +
                                'catch (e) { return e.message; } })()', page);
    var failing = line();
    ok('beside readings too, and ahead of a reading that runs past the DAC',
       says(readings, 'bad', DROPPED) && threw === null && failing.length === 1 &&
       failing[0].kind === 'msg bad' && failing[0].text.indexOf(DROPPED + '\n\n') === 0 &&
       /DAC range/.test(failing[0].text),
       JSON.stringify({ readings: readings, threw: threw, failing: failing.map(function (m) {
           return { kind: m.kind, text: m.text.slice(0, 200) }; }) }));
    // Nothing loaded, or the scaling pressed that it is at: nothing dropped,
    // nothing said about it.
    vm.runInContext('for (var e = 0; e < TABLE_ENTRIES; e++) measured[e] = 0; clearBaseline();' +
                    '$("vpo").children[0].click();', page);
    var plain = line();
    vm.runInContext('loadPitchTable(held, { volts_per_octave: 1.0, pitch_offset: true }, ' +
                    '"the keyboard\\u2019s table", {}); $("vpo").children[0].click();', page);
    var same = line();
    ok('and nothing is said when nothing was dropped',
       plain.length === 0 && says(same, 'ok', 'Correction is monotonic and inside the 12-bit DAC.'),
       JSON.stringify({ plain: plain, same: same }));

    // The pitch offset drops a loaded table too, and says so in its own
    // words, which the owner approved with the drop: the page's own offset
    // buttons, read the same way.
    var OFFSET_DROPPED = 'The loaded table was dropped: changing the pitch offset renumbers the ' +
        'semitones, so it no longer describes this build. Load it again if it was measured at this setting.';
    vm.runInContext('loadPitchTable(held, { volts_per_octave: 1.0, pitch_offset: true }, ' +
                    '"the keyboard\\u2019s table", {});', page);
    var loadedOn = line();
    vm.runInContext('$("offset").children[0].click();', page);
    var offLine = line(), offState = vm.runInContext('({ on: pitchOffset, have: haveBaseline() })', page);
    ok('switching the offset off drops the table and says so, in its own words, and stays there',
       says(loadedOn, 'ok', 'Correction is monotonic and inside the 12-bit DAC.') &&
       says(offLine, 'bad', OFFSET_DROPPED) && offState.on === false && !offState.have,
       JSON.stringify({ before: loadedOn, after: offLine, state: offState }));
    page.heldOff = PB.pitchTable(PB.expand({ volts_per_octave: 1.0, pitch_offset: false }), ownerRows);
    vm.runInContext('loadPitchTable(heldOff, { volts_per_octave: 1.0, pitch_offset: false }, ' +
                    '"the keyboard\\u2019s table", {}); measured[30] = 4.5;' +
                    '$("offset").children[1].click();', page);
    var onLine = line();
    vm.runInContext('for (var e = 0; e < TABLE_ENTRIES; e++) measured[e] = 0; clearBaseline();' +
                    '$("offset").children[0].click();', page);
    var bare = line();
    vm.runInContext('$("offset").children[1].click();', page);
    ok('and on again, beside readings; nothing is said when nothing was loaded',
       says(onLine, 'bad', OFFSET_DROPPED) && bare.length === 0 &&
       vm.runInContext('pitchOffset', page) === true,
       JSON.stringify({ on: onLine, bare: bare }));
}

print_(failures ? ('FAILED ' + failures) : 'ALL SETTINGS TESTS PASSED');
if (failures && typeof process !== 'undefined') process.exit(1);
