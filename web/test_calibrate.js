// The calibration sweep's arithmetic and its pitch estimator.
//
// Neither half can be checked against the instrument from here, so this
// checks them against the things that are knowable without one: the firmware's
// own integer arithmetic, which is written down in the factory image, and
// synthetic tones whose frequency is known exactly.
//
// Runs under node (require) and under jsc (the file is loaded beside
// calibrate.js, which leaves CALIBRATE on the global).
var C = (typeof CALIBRATE !== 'undefined') ? CALIBRATE
      : require('./calibrate.js').CALIBRATE;

var failures = 0;
function ok(name, cond, detail) {
    if (!cond) { failures++; print_('FAIL  ' + name + (detail ? '  ' + detail : '')); }
    else print_('ok    ' + name);
}
function print_(s) {
    if (typeof print === 'function') print(s); else console.log(s);
}

// --- the sweep reaches every entry the page asks for --------------------
// Notes 24 to 88 - C0 to E5 - against table entries 3 to 67, one apiece.
// If this ever stops holding, the page is offering boxes the sweep cannot
// fill, and that is worth failing over rather than discovering mid-run.
var steps = C.plan(3, 67, false);
ok('sweep is 65 notes', steps.length === 65, 'got ' + steps.length);
ok('sweep starts at note 24 on entry 3',
   steps[0].note === 24 && steps[0].index === 3,
   JSON.stringify(steps[0]));
ok('sweep ends at note 88 on entry 67',
   steps[64].note === 88 && steps[64].index === 67,
   JSON.stringify(steps[64]));

var seen = {}, gaps = [], dupes = [];
steps.forEach(function (s) {
    if (seen[s.index]) dupes.push(s.index);
    seen[s.index] = true;
});
for (var i = 3; i <= 67; i++) if (!seen[i]) gaps.push(i);
ok('every entry 3..67 is covered once', !gaps.length && !dupes.length,
   'gaps ' + gaps.join(',') + ' dupes ' + dupes.join(','));

// A note lands near the entry it is credited to, never halfway between two.
// The key table steps 40.33 units where an entry is 40.33 wide, so only every
// third divides exactly; what matters is that the remainder stays small enough
// for the neighbour's contribution to sit under the 2.5 cent quantisation the
// table already has.
var worstLean = 0;
steps.forEach(function (s) { worstLean = Math.max(worstLean, Math.abs(s.lean)); });
ok('no note leans more than 5% onto its neighbour', worstLean < 0.05,
   'worst ' + worstLean.toFixed(4));

// --- against the firmware's arithmetic, entry by entry -------------------
// index = (key_table[note-24] - 484 + 120) * 12 / 484, integer divide.
var mismatch = 0;
for (var note = 24; note <= 88; note++) {
    var raw = C.KEY_TABLE[note - C.FIRST_NOTE] - 484;
    var u = raw + 120;
    var q = Math.floor(u * 12 / 484);
    var rem = u * 12 - q * 484;
    if (rem / 484 > 0.5) q += 1;
    var got = C.entryFor(note, false);
    if (!got || got.index !== q) mismatch++;
}
ok('entryFor matches the remap for all 65 notes', mismatch === 0,
   mismatch + ' mismatched');

ok('note names match the page', C.noteLabel(3) === 'C0' && C.noteLabel(67) === 'E5',
   C.noteLabel(3) + '..' + C.noteLabel(67));

// --- the estimator ------------------------------------------------------
// A reading has to be good to a small fraction of a DAC step, or the rounding
// it decides is a coin toss.  One step is 2.5 cents at 1.2 V/oct.
var RATE = 48000, N = 32768;
function tone(f, kind, noise) {
    var x = new Float64Array(N), s = 12345, h, i;
    function rnd() { s = (s * 1103515245 + 12345) & 0x7fffffff; return s / 0x7fffffff - 0.5; }
    for (i = 0; i < N; i++) {
        var t = i / RATE, v = 0;
        if (kind === 'saw') {
            for (h = 1; h <= 32; h++) { if (f * h > RATE / 2) break; v += Math.sin(2 * Math.PI * f * h * t + h) / h; }
        } else if (kind === 'pulse') {
            for (h = 1; h <= 32; h++) { if (f * h > RATE / 2) break; v += Math.sin(2 * Math.PI * f * h * t) * (h % 2 ? 1 : 0.3) / h; }
        } else {
            v = Math.sin(2 * Math.PI * f * t);
        }
        x[i] = v * 0.3 + (noise || 0) * rnd();
    }
    return x;
}

var worst = 0, failed = [];
[32.703, 65.406, 130.81, 261.63, 523.25, 1046.5, 2093.0, 2637.0].forEach(function (f) {
    ['sine', 'saw', 'pulse'].forEach(function (kind) {
        // The window the sweep itself uses: it knows what the next note should
        // be to within a semitone, so the estimator is never asked to choose
        // between octaves.
        var r = C.measure(tone(f, kind, 0.01), RATE,
                          f * Math.pow(2, -3 / 12), f * Math.pow(2, 3 / 12));
        if (!r.ok) { failed.push(f + ' ' + kind + ': ' + r.why); return; }
        var err = Math.abs(C.cents(r.hz, f));
        if (err > worst) worst = err;
        if (err > 0.1) failed.push(f + ' ' + kind + ': ' + err.toFixed(3) + ' cents');
    });
});
ok('every synthetic tone within 0.1 cents', !failed.length,
   failed.join('; ') + ' worst ' + worst.toFixed(4));
print_('      worst estimator error ' + worst.toFixed(4) +
       ' cents, against a 2.5 cent DAC step');

// Noise must not produce a confident answer: a sweep that reads an unpatched
// input as a note writes a plausible number into a table nobody will question.
// The sweep gates on level as well, which this does not exercise - what is
// being asked here is only that clarity does not vouch for noise.
var noise = new Float64Array(N), seed = 99;
for (i = 0; i < N; i++) {
    seed = (seed * 1103515245 + 12345) & 0x7fffffff;
    noise[i] = seed / 0x7fffffff - 0.5;
}
var r = C.measure(noise, RATE, 20, 4000);
ok('noise is not reported as a clear pitch', !r.ok || r.clarity < 0.55,
   r.ok ? 'clarity ' + r.clarity.toFixed(3) : r.why);

print_(failures ? ('FAILED ' + failures) : 'ALL CALIBRATION TESTS PASSED');
if (failures && typeof process !== 'undefined') process.exit(1);
