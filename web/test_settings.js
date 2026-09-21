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

print_(failures ? ('FAILED ' + failures) : 'ALL SETTINGS TESTS PASSED');
if (failures && typeof process !== 'undefined') process.exit(1);
