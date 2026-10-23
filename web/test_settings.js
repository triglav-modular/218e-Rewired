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
        'var pitchOffset = true, PLAYABLE_LOW = 3, PLAYABLE_HIGH = 67, TABLE_ENTRIES = 80;',
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
                                                             '218e-pitch-calibration.csv'), 'utf8'), 80);
    var ownerRows = [];
    for (var s = 0; s < 80; s++) ownerRows.push({ semitone: s, cents: owner.rows[s] });
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
    var switched = vm.runInContext('({ vpo: vpo, have: haveBaseline(), built: built() })', page);
    var flat = PB.pitchTable(at12, at12._calibration);
    ok('switching to 1.2 V/oct drops it, as switching the offset does, rather than build it ' +
       'rounded twice (' + off + ' entries a count off)',
       switched.vpo === 1.2 && !switched.have && JSON.stringify(switched.built) === JSON.stringify(flat),
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

    // --- the boxes and the plot after a tuning run ------------------------
    // A run leaves no readings: it moved the entries until they played in
    // tune.  The boxes and the plot used to show those readings, all zero,
    // so a run that tuned every note looked like one that found nothing.
    // With its table loaded they show the offsets it holds, and a box edits
    // its own entry (the owner, 2026-10-01).  The page's own buildTable and
    // drawPlot, against a DOM that keeps what they write.
    var view = vm.createContext({ console: console });
    ['generated.js', 'buildlib.js'].forEach(function (f) {
        vm.runInContext(fs.readFileSync(path.join(__dirname, f), 'utf8'), view, { filename: 'web/' + f });
    });
    vm.runInContext([
        'var nodes = {};',
        'function element(id) { return { id: id, checked: true, textContent: "", className: "", value: "",',
        '    style: {}, on: {}, kids: [], set innerHTML(v) { this.kids = []; this.html = v; },',
        '    get innerHTML() { return this.html; },',
        '    appendChild: function (c) { this.kids.push(c); },',
        '    addEventListener: function (t, f) { this.on[t] = f; }, classList: { toggle: function () {} } }; }',
        'var document = { createElement: function () { return element(null); } };',
        'function $(id) { return nodes[id] || (nodes[id] = element(id)); }',
        'function validateCal() {} function saveSoon() {} function invalidate() {} function syncBaseline() {}',
        'function press() {} function syncCalBody() {} function msg() {}',
        'var pitchOffset = true, PLAYABLE_LOW = 3, PLAYABLE_HIGH = 67, TABLE_ENTRIES = 80;',
        'var measured = [], interpolated = {};',
        'for (var i = 0; i < TABLE_ENTRIES; i++) measured.push(0);',
        appSource('\n    var NAMES_C', ';\n'), appSource('\n    var NAMES_A', ';\n'),
        appFunction('noteNames'), appFunction('noteName'), appFunction('keyLabel'),
        appSource('\n    var baseline = {}', ';\n'),
        appFunction('clearBaseline'), appFunction('haveBaseline'), appFunction('rows'),
        appFunction('showsTable'), appFunction('shownCents'), appFunction('loadPitchTable'),
        appFunction('drawPlot'), appSource('\n    var WHITE_W', ';\n'), appFunction('buildTable'),
        appFunction('goodCalibration'),
        appSource("\n    $('calBaseClear').addEventListener('click', function () {", '\n    });\n'),
        'clearBaseline();',
        'function boxes() { return $("calKeys").kids.map(function (k) { return k.kids[1]; }); }',
        'function built() { return BUILDLIB.pitchTable(BUILDLIB.expand({ volts_per_octave: 1.2,',
        '    pitch_offset: true }), rows()); }'
    ].join('\n'), view, { filename: 'web/app.js (extracted)' });
    view.tuned = once;
    vm.runInContext('loadPitchTable(tuned, { volts_per_octave: 1.2, pitch_offset: true }, ' +
                    '"the tuned table", {});', view);
    var cents = PB.pitchCents(at12, once).map(function (r) { return r.cents; });
    var after = vm.runInContext('({ values: boxes().map(function (b) { return b.value; }), ' +
                                'hi: $("calHi").textContent, lo: $("calLo").textContent, ' +
                                'hint: $("calKeysHint").innerHTML })', view);
    var want = cents.slice(3, 68).map(function (c) { return c.toFixed(2); });
    var play = cents.slice(3, 68);
    ok('after a run the 65 boxes show the offsets the tuned table holds, and the plot spans them',
       JSON.stringify(after.values) === JSON.stringify(want) &&
       after.values.some(function (v) { return v !== '0.00'; }) &&
       after.hi === Math.max.apply(null, play).toFixed(1) + ' cents' &&
       after.lo === Math.min.apply(null, play).toFixed(1) &&
       /^<strong>The offset each note gets, in cents\.<\/strong>/.test(after.hint),
       JSON.stringify({ first: after.values.slice(0, 4), want: want.slice(0, 4), hi: after.hi, lo: after.lo,
                        hint: after.hint }));
    vm.runInContext('var b = boxes()[27]; b.value = String(baseline[30] + 2.5); b.on.change();', view);
    var edited = vm.runInContext('({ base: baseline[30], measured: measured[30], built: built() })', view);
    var wantRows = cents.map(function (c, s) { return { semitone: s, cents: s === 30 ? c + 2.5 : c }; });
    ok('a box edits its own entry of the tuned table, not a reading on top of it',
       Math.abs(edited.base - (cents[30] + 2.5)) < 1e-9 && edited.measured === 0 &&
       JSON.stringify(edited.built) === JSON.stringify(PB.pitchTable(at12, wantRows)),
       JSON.stringify({ base: edited.base, measured: edited.measured }));
    // Any loaded table shows its offsets in the keys, not only a run's
    // (the owner, 2026-10-07): the keyboard's, read with Read settings, and
    // a file's.
    vm.runInContext('loadPitchTable(tuned, { volts_per_octave: 1.2, pitch_offset: true }, ' +
                    '"the keyboard\\u2019s table", {});', view);
    var read = vm.runInContext('({ values: boxes().map(function (b) { return b.value; }), ' +
                               'hint: $("calKeysHint").innerHTML })', view);
    ok('a table read off the keyboard shows its offsets in the keys too',
       JSON.stringify(read.values) === JSON.stringify(want) &&
       /^<strong>The offset each note gets, in cents\.<\/strong>/.test(read.hint),
       JSON.stringify({ first: read.values.slice(0, 4), want: want.slice(0, 4) }));
    vm.runInContext('loadPitchTable(tuned, { volts_per_octave: 1.2, pitch_offset: true }, "cal.csv", {}); ' +
                    'baselineHistory = { read: { 30: 3, 31: 2 }, against: { 30: 1, 31: 1 } }; buildTable(); ' +
                    'var b = boxes()[27]; b.value = String(baseline[30] - 1.5); b.on.change();', view);
    var file = vm.runInContext('({ base: baseline[30], measured: measured[30], ' +
                               'history: JSON.stringify(baselineHistory) })', view);
    ok('a file\u2019s table edits the same way, and the edited key loses the reading the file recorded for it',
       Math.abs(file.base - (cents[30] - 1.5)) < 1e-9 && file.measured === 0 &&
       file.history === JSON.stringify({ read: { 31: 2 }, against: { 31: 1 } }), JSON.stringify(file));
    vm.runInContext('loadPitchTable(tuned, { volts_per_octave: 1.2, pitch_offset: true }, ' +
                    '"the tuned table", {}); measured[30] = 4.5; buildTable();', view);
    var onTop = vm.runInContext('boxes().map(function (b) { return b.value; })', view);
    var readHint = vm.runInContext('$("calKeysHint").innerHTML', view);
    ok('readings loaded on top of a table still show readings',
       onTop[27] === '4.50' && onTop.filter(function (v) { return v !== '0.00'; }).length === 1 &&
       /^<strong>What each note played, in cents\.<\/strong>/.test(readHint),
       JSON.stringify({ onTop: onTop.slice(26, 29) }));

    // --- Forget loaded table --------------------------------------------------
    // Forgetting a table leaves the page as though none had been loaded: the
    // keys show readings again, all zero, and a box takes a reading.  They
    // used to keep showing the forgotten table's offsets, and an edit typed
    // into one went into the cleared table: an offset no box showed, saved,
    // kept across a reload and applied by every build (audit 2026-10-23).
    // The page's own Forget, against the page that never loaded a table,
    // before and after the same edit.
    function sameState(a, b) { return JSON.stringify(a) === JSON.stringify(b); }
    function calView() {
        return vm.runInContext('({ values: boxes().map(function (b) { return b.value; }), ' +
            'hint: $("calKeysHint").innerHTML, hi: $("calHi").textContent, lo: $("calLo").textContent, ' +
            'have: haveBaseline(), rows: rows(), ' +
            'built: (function () { try { return built(); } catch (e) { return e.message; } })() })', view);
    }
    function typeInto(box, v) {
        vm.runInContext('var b = boxes()[' + box + ']; b.value = "' + v + '"; b.on.change();', view);
    }
    vm.runInContext('clearBaseline(); for (var e = 0; e < TABLE_ENTRIES; e++) measured[e] = 0; ' +
                    'interpolated = {}; buildTable(); drawPlot();', view);
    var neverLoaded = calView();
    typeInto(27, '4.5');
    var neverLoadedEdit = calView();
    vm.runInContext('loadPitchTable(tuned, { volts_per_octave: 1.2, pitch_offset: true }, ' +
                    '"the keyboard\\u2019s table", {}); $("calBaseClear").on.click();', view);
    var forgot = calView();
    ok('Forget loaded table leaves the keys, the plot and the build as a page that never loaded one',
       sameState(forgot, neverLoaded),
       JSON.stringify({ boxes: forgot.values.slice(26, 29), hint: forgot.hint.slice(0, 40), hi: forgot.hi,
                        have: forgot.have, built: sameState(forgot.built, neverLoaded.built) }));
    typeInto(27, '4.5');
    var forgotEdit = calView();
    var hidden = vm.runInContext('JSON.parse(JSON.stringify({ measured: measured, baseline: baseline }))', view);
    ok('and a box edited after it takes a reading, not an offset in the forgotten table',
       sameState(forgotEdit, neverLoadedEdit) && hidden.measured[30] === 4.5 &&
       Object.keys(hidden.baseline).every(function (k) { return hidden.baseline[k] === 0; }),
       JSON.stringify({ measured30: hidden.measured[30], baseline30: hidden.baseline[30],
                        built: sameState(forgotEdit.built, neverLoadedEdit.built) }));
    // What such a page saved: the cleared table, named nothing, with the edit
    // in it as an offset at semitone 30 and no reading.  It comes back as the
    // page that never loaded a table, the edit dropped as Forget drops a table.
    var cleared = {};
    for (var hc = 0; hc < 80; hc++) cleared[hc] = hc === 30 ? 4.5 : 0;
    var zeros = [];
    for (var hz = 0; hz < 80; hz++) zeros.push(0);
    view.stored = JSON.parse(JSON.stringify({ measured: zeros, interpolated: {}, baseline: cleared,
                                              baselineSources: {}, baselineName: '', baselineHistory: null }));
    vm.runInContext('var c = goodCalibration(stored); measured = c.measured; interpolated = c.interpolated; ' +
                    'baseline = c.baseline; baselineSources = c.baselineSources; baselineName = c.baselineName; ' +
                    'baselineHistory = c.baselineHistory; buildTable(); drawPlot();', view);
    var restored = calView();
    ok('a save holding such an offset, under no table’s name, restores as the page that never loaded one',
       sameState(restored, neverLoaded),
       JSON.stringify({ row30: restored.rows[30], built: sameState(restored.built, neverLoaded.built) }));

    // --- a calibration the page saved before 3.2 --------------------------------
    // Up to 3.1 the page's table stopped at D#6, so its saved calibration holds
    // 79 readings and a table of 79 rows.  It was dropped whole for not holding
    // 80, with the box still ticked and the build carrying the plain table
    // (audit 2026-10-23).  It comes back as the page saves it with E6
    // unmeasured: no reading there, and the table's E6 the step under it, as
    // the read of a 79-entry keyboard gives it.  Each state is saved the way
    // saveNow() saves it, then cut to the 79 entries a 3.1 page held, and the
    // two saves have to restore to the same page building the same table: the
    // keyboard's table read off a 3.1 keyboard, a file's with readings and a
    // record on top, and readings with no table.
    function savedCalibration() {
        return JSON.parse(vm.runInContext('JSON.stringify({ measured: measured, interpolated: interpolated, ' +
            'baseline: baseline, baselineSources: baselineSources, baselineName: baselineName, ' +
            'baselineHistory: baselineHistory })', view));
    }
    function as31(c) {
        c = JSON.parse(JSON.stringify(c));
        c.measured = c.measured.slice(0, 79);
        delete c.baseline[79]; delete c.baselineSources[79]; delete c.interpolated[79];
        if (c.baselineHistory) { delete c.baselineHistory.read[79]; delete c.baselineHistory.against[79]; }
        return c;
    }
    function restoredFrom(c) {
        view.stored = c;
        var got = vm.runInContext('(function () { var c = goodCalibration(stored); if (!c) return null; ' +
            'measured = c.measured; interpolated = c.interpolated; baseline = c.baseline; ' +
            'baselineSources = c.baselineSources; baselineName = c.baselineName; ' +
            'baselineHistory = c.baselineHistory; buildTable(); drawPlot(); ' +
            'return JSON.parse(JSON.stringify(c)); })()', view);
        return got && { saved: got, page: calView() };
    }
    // Exact but for the table's own rows, which may differ in the last bits
    // of a double: E6 is the step under it in cents rather than in counts.
    function sameRestore(a, b) {
        function bare(r) {
            var c = JSON.parse(JSON.stringify(r));
            delete c.saved.baseline; c.page.rows = c.page.rows.length; return c;
        }
        if (!a || !b || !sameState(bare(a), bare(b))) return false;
        var keys = Object.keys(b.saved.baseline);
        return keys.length === 80 && sameState(Object.keys(a.saved.baseline), keys) &&
            keys.every(function (k) { return Math.abs(a.saved.baseline[k] - b.saved.baseline[k]) < 1e-9; }) &&
            a.page.rows.every(function (r, s) {
                return r.semitone === s && Math.abs(r.cents - b.page.rows[s].cents) < 1e-9;
            });
    }
    // A 3.1 keyboard's table, as the page reads it: 79 entries and E6
    // filled in from the step under it.
    var read31 = PB.extendPitchTable(once.slice(0, 79).concat([0]));
    view.read31 = read31;
    var SAVED31 = [
        ['the keyboard’s table read off a 3.1 keyboard',
         'clearBaseline(); for (var e = 0; e < TABLE_ENTRIES; e++) measured[e] = 0; interpolated = {}; ' +
         'loadPitchTable(read31, { volts_per_octave: 1.2, pitch_offset: true }, "the keyboard\\u2019s table", {});'],
        ['a file’s table with readings and its record on top',
         'loadPitchTable(read31, { volts_per_octave: 1.2, pitch_offset: true }, "cal.csv", ' +
         '{ 3: "measured", 40: "measured", 70: "extrapolated" }); ' +
         'baselineHistory = { read: { 30: 3, 31: 2 }, against: { 30: 1, 31: 1 } }; ' +
         'measured[40] = 2.5; measured[41] = 1.5; interpolated[41] = true;'],
        ['readings with no table',
         'clearBaseline(); for (var e = 0; e < TABLE_ENTRIES; e++) measured[e] = 0; interpolated = {}; ' +
         'measured[3] = 1.25; measured[35] = -3; measured[67] = 6.5; interpolated[35] = true;']
    ];
    SAVED31.forEach(function (sc) {
        vm.runInContext(sc[1], view);
        var now = savedCalibration(), then = as31(now);
        var from32 = restoredFrom(now), from31 = restoredFrom(then);
        ok('a calibration the 3.1 page saved, ' + sc[0] + ', restores as the same one saved with E6 unmeasured',
           then.measured.length === 79 && Object.keys(then.baseline).length === 79 &&
           !!from32 && sameRestore(from31, from32),
           JSON.stringify(from31 ? { measured79: from31.saved.measured[79], base79: from31.saved.baseline[79],
                                     want79: from32 && from32.saved.baseline[79],
                                     built: !!from32 && sameState(from31.page.built, from32.page.built) }
                                 : 'dropped'));
    });
    vm.runInContext('loadPitchTable(read31, { volts_per_octave: 1.2, pitch_offset: true }, "cal.csv", {});', view);
    var shortSave = as31(savedCalibration());
    shortSave.measured = shortSave.measured.slice(0, 78); delete shortSave.baseline[78];
    var overSave = savedCalibration();
    overSave.measured = overSave.measured.concat([0]); overSave.baseline[80] = 0;
    view.stored = shortSave;
    var shortGot = vm.runInContext('goodCalibration(stored)', view);
    view.stored = overSave;
    var overGot = vm.runInContext('goodCalibration(stored)', view);
    ok('a save of 78 or 81 entries is still dropped', shortGot === null && overGot === null,
       JSON.stringify({ short: !!shortGot, over: !!overGot }));

    // --- a table file that stops at D#6 -----------------------------------------
    // Every table before 3.2 stopped at semitone 78, D#6: the shipped one and
    // every one the 3.1 page saved, which its README says to load back.  The
    // page refused them as a row short (audit 2026-10-23).  A table that stops
    // there loads with E6 as the firmware fills in a 79-entry table, the row
    // under it plus the step to it, marked extrapolated, and tools/build.py
    // reads it the same way (tools/test.py, web/test_fold.py,
    // web/test_configs.py): the shipped table without its 80th row loads as the
    // shipped table.  A row fewer, or a gap at D#6, is still refused, in the
    // page's own words.  The page's own loader, with the file handed to it.
    vm.runInContext('var said = []; msg = function (el, kind, text) { said.push({ kind: kind, text: text }); }; ' +
        'function FileReader() {} ' +
        'FileReader.prototype.readAsText = function (f) { this.result = f.text; this.onload(); };', view);
    vm.runInContext(appSource("\n    $('calFile').addEventListener('change', function (e) {", '\n    });\n'), view);
    function loadFile(name, text) {
        view.file = { name: name, text: text };
        vm.runInContext('said = []; $("calFile").on.change({ target: { files: [file], value: "" } });', view);
        return vm.runInContext('({ have: haveBaseline(), name: baselineName, ' +
            'base: JSON.parse(JSON.stringify(baseline)), sources: JSON.parse(JSON.stringify(baselineSources)), ' +
            'said: said, built: (function () { try { return built(); } catch (e) { return e.message; } })() })', view);
    }
    var shippedText = fs.readFileSync(path.join(__dirname, '..', 'calibration', '218e-pitch-calibration.csv'), 'utf8');
    function without(text, rows) {
        return text.split('\n').filter(function (l) {
            return !rows.some(function (r) { return l.indexOf(r + ';') === 0; });
        }).join('\n');
    }
    vm.runInContext('clearBaseline(); for (var e = 0; e < TABLE_ENTRIES; e++) measured[e] = 0; interpolated = {};', view);
    var wholeFile = loadFile('218e-pitch-calibration.csv', shippedText);
    var stoppedFile = loadFile('stopped.csv', without(shippedText, [79]));
    ok('a table file that stops at D#6 loads, its E6 the step under it, and builds the shipped table',
       stoppedFile.have && stoppedFile.name === 'stopped.csv' && Object.keys(stoppedFile.base).length === 80 &&
       stoppedFile.base[79] === 2 * stoppedFile.base[78] - stoppedFile.base[77] &&
       Math.abs(stoppedFile.base[79] - wholeFile.base[79]) < 1e-9 && stoppedFile.sources[79] === 'extrapolated' &&
       sameState(stoppedFile.built, wholeFile.built) && stoppedFile.said.length === 1 &&
       stoppedFile.said[0].kind === 'ok' &&
       stoppedFile.said[0].text === 'Loaded stopped.csv as the table already on the instrument.',
       JSON.stringify({ have: stoppedFile.have, rows: Object.keys(stoppedFile.base).length,
                        e6: stoppedFile.base[79], shipped: wholeFile.base[79], said: stoppedFile.said }));
    var shortFile = loadFile('short.csv', without(shippedText, [78, 79]));
    var gapFile = loadFile('gap.csv', without(shippedText, [78]));
    ok('a table a row shorter, or with a gap at D#6, is still refused, and the table loaded stays',
       shortFile.name === 'stopped.csv' && gapFile.name === 'stopped.csv' &&
       sameState(shortFile.said, [{ kind: 'bad', text: 'short.csv has 78 of the 80 rows a table needs - ' +
           'missing semitone 78 and 1 others. A calibration is loaded whole or not at all.' }]) &&
       sameState(gapFile.said, [{ kind: 'bad', text: 'gap.csv has 79 of the 80 rows a table needs - ' +
           'missing semitone 78. A calibration is loaded whole or not at all.' }]),
       JSON.stringify({ short: shortFile.said, gap: gapFile.said }));

    // --- a calibration saved before 3.2 at that page's default offset -------------
    // A save names the pitch offset only where it was moved off its page's
    // default.  The 3.1 page's was 3 semitones and this page's is None, so a
    // returning 3.1 visitor comes back on None (the owner, 2026-10-23), and a
    // calibration that save holds was made at 3 semitones.  It comes back the
    // way switching from 3 semitones to None leaves it on this page: the
    // readings move with their keys and a loaded table is dropped
    // (setPitchOffset).  Restored as it stood, the bottom C's reading landed on
    // D#1, the top C's fell off the keys, and the table was built three
    // semitones off its layout (audit 2026-10-23).  The page's own restore, its
    // offset buttons and its keys, against a 3.2 page holding the same
    // calibration at 3 semitones that is switched to None by hand.  A 3.1 save
    // that names its offset, and a save this page made, come back as they are.
    function storedPage(store) {
        var p = vm.createContext({ console: console });
        ['generated.js', 'buildlib.js'].forEach(function (f) {
            vm.runInContext(fs.readFileSync(path.join(__dirname, f), 'utf8'), p, { filename: 'web/' + f });
        });
        vm.runInContext([
            'var nodes = {}, store = ' + JSON.stringify(store === null ? null : JSON.stringify(store)) + ';',
            'function button(v) { return { dataset: { v: v }, attrs: {}, on: {},',
            '    setAttribute: function (k, x) { this.attrs[k] = x; }, getAttribute: function (k) { return this.attrs[k]; },',
            '    addEventListener: function (t, f) { this.on[t] = f; }, click: function () { this.on.click(); } }; }',
            'function element(id) { return { id: id, checked: false, textContent: "", className: "", value: "",',
            '    disabled: false, style: {}, on: {}, kids: [], children: [],',
            '    set innerHTML(v) { this.kids = []; this.html = v; }, get innerHTML() { return this.html; },',
            '    appendChild: function (c) { this.kids.push(c); },',
            '    addEventListener: function (t, f) { this.on[t] = f; }, classList: { toggle: function () {} } }; }',
            'var document = { createElement: function () { return element(null); } };',
            'function $(id) { return nodes[id] || (nodes[id] = element(id)); }',
            '$("vpo").children = [button("1.0"), button("1.2")]; $("vpo").children[1].setAttribute("aria-pressed", "true");',
            // This page's markup: None pressed, and a calibration in use.
            '$("offset").children = [button("0"), button("1")]; $("offset").children[0].setAttribute("aria-pressed", "true");',
            '$("useCal").checked = true;',
            'function bindDashes() {} function invalidate() {} function updateOffsetNote() {} function syncCalBody() {}',
            'var K_SETTINGS = "settings", K_FACTORY = "factory", restoring = false;',
            'function readStore(k) { return k === K_SETTINGS ? store : null; }',
            'var DEFAULTS = { pitch_offset: "0", volts_per_octave: "1.2" };',
            'var pitchOffset = false, PLAYABLE_LOW = 0, PLAYABLE_HIGH = 64, TABLE_ENTRIES = 80;',
            'var measured = [], interpolated = {};',
            'for (var i = 0; i < TABLE_ENTRIES; i++) measured.push(0);',
            appSource('\n    var NAMES_C', ';\n'), appSource('\n    var NAMES_A', ';\n'),
            appFunction('noteNames'), appFunction('noteName'), appFunction('keyLabel'),
            appSource('\n    var baseline = {}', ';\n'),
            appFunction('clearBaseline'), appFunction('haveBaseline'), appFunction('rows'),
            appFunction('syncBaseline'), appFunction('showsTable'), appFunction('shownCents'),
            appFunction('drawPlot'), appSource('\n    var WHITE_W', ';\n'), appFunction('buildTable'),
            appFunction('press'), appFunction('pressed'), appFunction('msg'),
            appFunction('calibrationBlank'), appFunction('validateCal'), appFunction('goodCalibration'),
            appFunction('loadPitchTable'),
            appSource('\n    var vpo = 1.2;', '\n    });\n'), appFunction('setPitchOffset'),
            appSource("\n    Array.prototype.forEach.call($('offset').children", '\n    });\n'),
            appSource('\n    var APPLY = {', '\n    };\n'), appFunction('applyAll'), appFunction('restore'),
            'clearBaseline(); restore(); buildTable(); drawPlot();',
            // What the page shows and builds, and what saveNow() would save of it.
            'function seen() { return JSON.parse(JSON.stringify({ offset: pressed("offset"), on: pitchOffset,',
            '    measured: measured, interpolated: interpolated, baseline: baseline, name: baselineName,',
            '    sources: baselineSources, history: baselineHistory,',
            '    keys: $("calKeys").kids.map(function (k) { return k.kids[0].textContent + " " + k.kids[1].value; }),',
            '    line: $("calMsg").kids.map(function (k) { return k.className + ": " + k.textContent; }),',
            '    built: (function () { try { return BUILDLIB.pitchTable(BUILDLIB.expand({ volts_per_octave: vpo,',
            '        pitch_offset: pitchOffset }), rows()); } catch (e) { return e.message; } })() })); }',
            'function calibrationNow() { return JSON.parse(JSON.stringify({ measured: measured,',
            '    interpolated: interpolated, baseline: baseline, baselineSources: baselineSources,',
            '    baselineName: baselineName, baselineHistory: baselineHistory })); }'
        ].join('\n'), p, { filename: 'web/app.js (extracted)' });
        return p;
    }
    // The same page, the same calibration and the same build, but for the
    // last bits of a double in the table's rows (E6, made in cents).
    function sameSeen(a, b) {
        var ka = Object.keys(a.baseline), kb = Object.keys(b.baseline);
        function bare(s) { var c = JSON.parse(JSON.stringify(s)); delete c.baseline; return c; }
        return sameState(bare(a), bare(b)) && sameState(ka, kb) &&
            kb.every(function (k) { return Math.abs(a.baseline[k] - b.baseline[k]) < 1e-9; });
    }
    function readingKeys(s) {
        return s.keys.filter(function (k) { return !/ 0\.00$/.test(k); }).join(', ');
    }
    // A table read off a 3.1 keyboard, at each offset.
    var noOffset = PB.expand({ volts_per_octave: 1.2, pitch_offset: false });
    var readNone31 = PB.extendPitchTable(PB.pitchTable(noOffset, ownerRows).slice(0, 79).concat([0]));
    // The calibration a 3.2 page holds at `offset`: optionally the keyboard's
    // table, and readings on the bottom C, the next key up and the top two.
    function holding(offset, table, name) {
        var p = storedPage(null);
        p.held = table;
        vm.runInContext('$("offset").children[' + (offset === '1' ? 1 : 0) + '].click();' +
            (table ? 'loadPitchTable(held, BUILDLIB.pitchTableSettings(held), ' + JSON.stringify(name) + ', {});' : '') +
            'measured[PLAYABLE_LOW] = -4; measured[PLAYABLE_LOW + 1] = -1.25; interpolated[PLAYABLE_LOW + 1] = true; ' +
            'measured[PLAYABLE_HIGH - 1] = 1.5; measured[PLAYABLE_HIGH] = -2; buildTable(); drawPlot(); validateCal();', p);
        return vm.runInContext('({ seen: seen(), calibration: calibrationNow() })', p);
    }
    function saved(version, options, calibration) {
        return { v: 1, version: version, options: options, calibration: calibration };
    }
    [['the keyboard’s table and readings', read31, 'the keyboard’s table'], ['readings alone', null, '']]
        .forEach(function (sc) {
            var at3 = holding('1', sc[1], sc[2]);
            // 3.2's save of it names the offset, which is not this page's default.
            var page32 = storedPage(saved('3.2.0', { pitch_offset: '1' }, at3.calibration));
            var restored32 = vm.runInContext('seen()', page32);
            vm.runInContext('$("offset").children[0].click();', page32);
            var switched = vm.runInContext('seen()', page32);
            // 3.1's names none: 3 semitones was its default.
            var page31 = storedPage(saved('3.1.0', {}, as31(at3.calibration)));
            var from31 = vm.runInContext('seen()', page31);
            ok('a 3.1 save at its default offset, ' + sc[0] + ', comes back as 3 semitones switched to None',
               sameSeen(restored32, at3.seen) && from31.offset === '0' && !from31.on && sameSeen(from31, switched),
               JSON.stringify({ readingsOn: readingKeys(from31), want: readingKeys(switched),
                                table: from31.name, wantTable: switched.name, line: from31.line,
                                wantLine: switched.line, built: sameState(from31.built, switched.built) }));
        });
    // Named, the offset is the one the calibration was made at, and nothing
    // moves: a 3.1 visitor on None.  And a save this page made on None, its
    // own default, names none and is this page's.
    var atNone = holding('0', readNone31, 'the keyboard’s table');
    var named31 = vm.runInContext('seen()', storedPage(saved('3.1.0', { pitch_offset: '0' }, as31(atNone.calibration))));
    var own32 = vm.runInContext('seen()', storedPage(saved('3.2.0', {}, atNone.calibration)));
    ok('a 3.1 save that names its offset, and a save of this page’s on its default, come back as they were',
       sameSeen(named31, atNone.seen) && sameSeen(own32, atNone.seen) && own32.name === 'the keyboard’s table',
       JSON.stringify({ named: readingKeys(named31), own: readingKeys(own32), want: readingKeys(atNone.seen),
                        tables: [named31.name, own32.name] }));
}

print_(failures ? ('FAILED ' + failures) : 'ALL SETTINGS TESTS PASSED');
if (failures && typeof process !== 'undefined') process.exit(1);
