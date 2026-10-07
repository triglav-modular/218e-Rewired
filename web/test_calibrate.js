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
//
// The key table comes out of the factory image, not out of calibrate.js.  This
// check used to restate entryFor's formula over entryFor's own KEY_TABLE, so
// the only thing it could catch was entryFor disagreeing with itself: a wrong
// number in that table - the one thing here nobody can check by reading - sailed
// through, and every reading the sweep took would have been credited to the
// wrong entry with the harness green.  0x80016574 is where the 218e v3 v369
// image keeps it, 65 big-endian halfwords for keys 0..64.
//
// node only, and the image half only where the image is.  firmware/*.hex is
// gitignored - it is the owner's own factory image and never leaves their
// machine - so CI has the build pipeline but no image to read.  What runs
// there is the address, against the tracked constant the build resolves it
// from; what runs on a machine that has the image is the table itself.  The
// skip is named rather than silent, so a check that stopped running says so.
var KEY_TABLE_AT = 0x80016574;
var lib = null, image = null;
if (typeof require === 'function' && typeof __dirname === 'string') {
    var GEN = require('./generated.js');
    if (typeof global !== 'undefined' && global.GEN === undefined) global.GEN = GEN;
    lib = { gen: GEN, build: require('./buildlib.js') };
    var fs = require('fs'), path = require('path');
    var hexPath = path.join(__dirname, '..', 'firmware', '218eV3_v369_DFU.hex');
    if (fs.existsSync(hexPath)) {
        var mem = lib.build.parseHexText(fs.readFileSync(hexPath, 'utf8'),
                                         '218eV3_v369_DFU.hex').memory;
        image = [];
        for (var k = 0; k < 65; k++) {
            var at = KEY_TABLE_AT + k * 2;
            if (!(at in mem) || !((at + 1) in mem)) { image.push(null); continue; }
            image.push((mem[at] << 8) | mem[at + 1]);
        }
    }
}

if (!lib) {
    print_('SKIP  the factory key table - no module loader here, run this under node');
} else {
    // The address calibrate.js reads from is the one the build resolves, and
    // that one is tracked - so this much is checkable without the image.
    ok('the key table is where the build says it is',
       lib.gen.factoryKeyTable === KEY_TABLE_AT,
       'build says 0x' + lib.gen.factoryKeyTable.toString(16) +
       ', this reads 0x' + KEY_TABLE_AT.toString(16));

    if (!image) {
        print_('SKIP  the key table itself - firmware/218eV3_v369_DFU.hex is not here ' +
               '(gitignored: it is the owner\'s image). Run this where the image is.');
    } else {
        var short = image.filter(function (v) { return v === null; }).length;
        ok('the factory image still holds 65 key pitches at 0x80016574', short === 0,
           short + ' halfwords missing');

        var wrong = [];
        for (var i2 = 0; i2 < 65; i2++) {
            if (image[i2] !== C.KEY_TABLE[i2]) {
                wrong.push('key ' + i2 + ': image ' + image[i2] +
                           ', calibrate.js ' + C.KEY_TABLE[i2]);
            }
        }
        ok('calibrate.js carries the key table the firmware ships', !wrong.length,
           wrong.slice(0, 3).join('; ') +
           (wrong.length > 3 ? ' (+' + (wrong.length - 3) + ')' : ''));

        // And the remap's arithmetic over the image's numbers, not over ours.
        var mismatch = 0, sample = '';
        for (var note = 24; note <= 88; note++) {
            var raw = image[note - 24] - 484;
            var u = raw + 120;
            var q = Math.floor(u * 12 / 484);
            var rem = u * 12 - q * 484;
            if (rem / 484 > 0.5) q += 1;
            var got = C.entryFor(note, false);
            if (!got || got.index !== q) {
                mismatch++;
                if (!sample) sample = 'note ' + note + ': want ' + q +
                                      ', got ' + (got ? got.index : 'null');
            }
        }
        ok('entryFor matches the remap over the image\'s own table', mismatch === 0,
           mismatch + ' mismatched' + (sample ? ' - ' + sample : ''));
    }
}

ok('note names match the page', C.noteLabel(3) === 'C0' && C.noteLabel(67) === 'E5',
   C.noteLabel(3) + '..' + C.noteLabel(67));

// --- the two numberings, against the thing that lays the table out -------
// A reading is taken in firmware table entries and filed in calibration
// semitones, and with the pitch offset off - a 208c - the two are three apart.
// Nothing converted between them: the sweep was handed the page's semitones as
// if they were entries, so it played 62 of the 65 keys, filed every reading
// three rows above the key it came from, and the curve still validated.
//
// Which entry a semitone actually reaches is asked of BUILDLIB.pitchTable, by
// bending one semitone and seeing which entry moves.  Restating its
// `shift = GEN.bottomKeyIndex - bottom` here would be one more check that
// cannot fail.
//
// The two expressions below are the ones web/app.js hands the sweep - the
// `low`/`high` it plans with and the row it files a reading into.  app.js
// needs a document to load, so this exercises the conversion with the same
// arguments rather than the page itself; a call site changed there is not
// caught here.
if (!lib) {
    print_('SKIP  the entry/semitone conversion - no module loader here, run this under node');
} else {
    var entryReachedBy = function (semitone, pitchOffset) {
        var cfg = lib.build.expand({ volts_per_octave: 1.2, pitch_offset: pitchOffset });
        var flat = [], bent = [], n;
        for (n = 0; n < lib.gen.pitchTableEntries; n++) {
            flat.push({ semitone: n, cents: 0 });
            bent.push({ semitone: n, cents: n === semitone ? 50 : 0 });
        }
        var a = lib.build.pitchTable(cfg, flat);
        var b = lib.build.pitchTable(cfg, bent);
        for (n = 0; n < a.length; n++) if (a[n] !== b[n]) return n;
        return null;
    };

    [true, false].forEach(function (pitchOffset) {
        // What the page calls PLAYABLE_LOW, and what the build calls
        // bottom_key_semitone: the same number, from the same option.
        var bottom = lib.build.expand({ volts_per_octave: 1.2,
                                        pitch_offset: pitchOffset })
                        .pitch.bottom_key_semitone;
        var low = bottom, high = bottom + 64;
        var label = pitchOffset ? '208/208r/208p' : '208c';

        // Every playable semitone converts to the entry the build lays it at.
        var off = [];
        for (var sm = low; sm <= high; sm++) {
            var reached = entryReachedBy(sm, pitchOffset);
            if (C.entryForSemitone(sm, bottom) !== reached) {
                off.push('semitone ' + sm + ': conversion says entry ' +
                         C.entryForSemitone(sm, bottom) + ', the build uses ' + reached);
            }
            if (reached !== null && C.semitoneFor(reached, bottom) !== sm) {
                off.push('entry ' + reached + ' files back as semitone ' +
                         C.semitoneFor(reached, bottom) + ', not ' + sm);
            }
        }
        ok('entry and semitone convert both ways on a ' + label, !off.length,
           off.slice(0, 2).join('; ') + (off.length > 2 ? ' (+' + (off.length - 2) + ')' : ''));

        // And the whole run: bound the plan in entries the way the page now
        // does, file each step by semitone, and every box must be covered by
        // the key that actually plays it.
        var planned = C.plan(C.entryForSemitone(low, bottom),
                             C.entryForSemitone(high, bottom), false);
        ok('a ' + label + ' sweep plays all 65 keys', planned.length === 65,
           'planned ' + planned.length);

        var filed = {}, wrongRow = [];
        planned.forEach(function (st) {
            filed[C.semitoneFor(st.index, bottom)] = st;
        });
        var blank = [];
        for (sm = low; sm <= high; sm++) {
            if (!filed[sm]) { blank.push(sm); continue; }
            if (filed[sm].index !== entryReachedBy(sm, pitchOffset)) {
                wrongRow.push('semitone ' + sm + ' filed from entry ' + filed[sm].index);
            }
        }
        ok('a ' + label + ' sweep fills every box ' + low + '..' + high,
           !blank.length, 'blank: ' + blank.join(','));
        ok('and each box holds the key that plays it on a ' + label,
           !wrongRow.length, wrongRow.slice(0, 3).join('; '));
    });
}

// --- a table file, as the page reads one --------------------------------
// tools/build.py reads a calibration table's columns by name and refuses a
// semitone named twice.  The page read the cents and Source by position,
// so a table with its columns in another order loaded on the CLI and was
// "No usable rows" here, and it counted every row it read, so a table with
// one semitone twice was refused as 79 of the 79 rows, missing one (80 since 3.2).  The
// parser out of buildlib, then the page's own file loader out of app.js,
// with the file picker and the page around it stubbed.
if (!lib) {
    print_('SKIP  the calibration file reader - no module loader here, run this under node');
} else {
    var csvText = fs.readFileSync(path.join(__dirname, '..', 'calibration',
                                            '218e-pitch-calibration.csv'), 'utf8');
    var shipped = lib.build.parseCalibration(csvText, 80);
    ok('the shipped table reads as 80 semitones, none twice',
       shipped.found === 80 && Object.keys(shipped.rows).length === 80 && shipped.twice === null,
       shipped.found + ' found, twice ' + shipped.twice);
    var twiceText = csvText.replace(/\n41;/, '\n40;');
    var twiceRead = lib.build.parseCalibration(twiceText, 80);
    ok('a table naming semitone 40 twice counts 79 semitones and says which came twice',
       twiceRead.found === 79 && twiceRead.twice === 40, twiceRead.found + ' found, twice ' + twiceRead.twice);
    // The same table with its columns in another order, and a measurement
    // with its column where the CLI's fold writes it: read by name.
    var lines = csvText.split('\n').filter(function (l) { return l && l.charAt(0) !== '#'; });
    var head = lines[0].split(';'), cents = head.indexOf('Offset_Cents'), src = head.indexOf('Source');
    var moved = lines.map(function (l) {
        var q = l.split(';');
        return [q[0], q[cents], q[1], q[2], q[src]].join(';');
    }).join('\n');
    var movedRead = lib.build.parseCalibration(moved, 80);
    ok('a table with its columns in another order reads the same offsets and sources',
       movedRead.found === 80 && JSON.stringify(movedRead.rows) === JSON.stringify(shipped.rows) &&
       JSON.stringify(movedRead.sources) === JSON.stringify(shipped.sources), moved.split('\n')[0]);
    var headless = lines.slice(1).join('\n');
    ok('a table with no header still reads by position',
       JSON.stringify(lib.build.parseCalibration(headless, 80).rows) === JSON.stringify(shipped.rows));
    var measuredRead = lib.build.parseCalibration('Semitone;Measured_Cents\n40;1.5\n41;-2.25\n', 80);
    ok('a measurement reads its cents by name',
       measuredRead.found === 2 && measuredRead.rows[40] === 1.5 && measuredRead.rows[41] === -2.25,
       JSON.stringify(measuredRead.rows));

    // The page's loader: a file picked, read, and loaded or refused.
    var vm = require('vm');
    var APP = fs.readFileSync(path.join(__dirname, 'app.js'), 'utf8');
    var open = "\n    $('calFile').addEventListener('change', function (e) {";
    var at = APP.indexOf(open);
    var loader = at < 0 ? '' : APP.slice(at, APP.indexOf('\n    });\n', at) + 8);
    var pageCtx = vm.createContext({ BUILDLIB: lib.build });
    vm.runInContext([
        'var nodes = {}, TABLE_ENTRIES = 80, PLAYABLE_LOW = 3, PLAYABLE_HIGH = 67;',
        'var measured = [], baseline = {}, baselineSources = {}, baselineName = "", baselineHistory = null, interpolated = {};',
        'for (var i = 0; i < 80; i++) measured.push(0);',
        'function $(id) { return nodes[id] || (nodes[id] = { id: id, value: "", checked: false, on: {},',
        '    addEventListener: function (t, f) { this.on[t] = f; } }); }',
        'function msg(el, kind, text) { el.kind = kind; el.text = text; }',
        'function syncCalBody() {} function syncBaseline() {} function buildTable() {} function drawPlot() {}',
        'function validateCal() {} function invalidate() {}',
        'function FileReader() {} FileReader.prototype.readAsText = function (f) { this.result = f.text; this.onload(); };',
        'function pick(name, text) {',
        '    baseline = {}; nodes.calMsg = null;',
        '    nodes.calFile.on.change({ target: { files: [{ name: name, text: text }], value: "" } });',
        '    return { kind: $("calMsg").kind, text: $("calMsg").text, rows: Object.keys(baseline).length };',
        '}',
        loader
    ].join('\n'), pageCtx, { filename: 'web/app.js (extracted)' });
    var pick = loader ? pageCtx.pick : function () { return { text: 'no file loader in app.js' }; };
    var got = pick('218e-pitch-calibration.csv', csvText);
    ok('the page loads the shipped table whole', got.kind === 'ok' && got.rows === 80, got.text);
    got = pick('twice.csv', twiceText);
    ok('and refuses one naming a semitone twice in tools/build.py’s words',
       got.kind === 'bad' && got.rows === 0 &&
       got.text === 'twice.csv: semitone 40 appears twice - a stale row from a hand edit would silently win',
       got.text);
    got = pick('short.csv', csvText.replace(/\n41;[^\n]*/, ''));
    ok('a table one row short says 79 of the 80, and which one',
       got.kind === 'bad' && got.rows === 0 &&
       /^short\.csv has 79 of the 80 rows a table needs - missing semitone 41\./.test(got.text), got.text);
    got = pick('moved.csv', moved);
    ok('and one with its columns in another order loads', got.kind === 'ok' && got.rows === 80, got.text);
}

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

// --- MIDI access is asked for once ----------------------------------------
// Every requestMIDIAccess() returns a new MIDIAccess, and each one sends its
// own statechange for every port.  The page lists ports on focus and again on
// every port change, and while each list asked anew, each new object's
// handler listed again and asked again: in Chrome, six seconds after the
// grant, 195,786 requests and 29,831 MIDIAccess objects, and the browser out
// of memory (2026-09-26).  A fake browser here counts the asks, refuses the
// first one as a browser can, and fires port changes on every object it made.
var R = (typeof CALIBRATE !== 'undefined') ? this : require('./calibrate.js');
function midiAccessOnce() {
    var asks = 0, made = [], refuseNext = true;
    function port(id, kind) { return { id: id, name: '218e', type: kind, state: 'connected' }; }
    R.navigator = {
        requestMIDIAccess: function () {
            asks++;
            if (refuseNext) { refuseNext = false; return Promise.reject(new Error('not yet')); }
            var a = { inputs: new Map([['i', port('i', 'input')]]),
                      outputs: new Map([['o', port('o', 'output')]]), onstatechange: null };
            made.push(a);
            return Promise.resolve(a);
        }
    };
    function changeEverywhere() {
        made.forEach(function (a) { if (a.onstatechange) a.onstatechange({ port: a.outputs.get('o') }); });
    }
    var relists = 0;
    // What the page's watcher does on a port change: list both again.
    C.onMidiChange(function () {
        relists++;
        C.midiOutputs(); C.midiInputs();
    });
    return C.midiOutputs().then(function () { return 'resolved'; }, function (e) { return e.message; })
    .then(function (first) {
        ok('a refused request is reported', first === 'not yet', first);
        return Promise.all([C.midiOutputs(), C.midiInputs(), C.midiOutputs(), C.midiInputs()]);
    }).then(function (lists) {
        ok('after a refusal the next list asks again, and gets the ports',
           asks === 2 && lists[0].length === 1 && lists[1].length === 1, 'asks ' + asks);
        changeEverywhere(); changeEverywhere(); changeEverywhere();
        return new Promise(function (done) { setTimeout(done, 0); });
    }).then(function () {
        ok('lists and port changes reuse the one MIDIAccess', asks === 2 && made.length === 1,
           'asks ' + asks + ', MIDIAccess objects ' + made.length);
        ok('each port change runs the page watcher once', relists === 3, 'watcher ran ' + relists + ' times');
    });
}

function finish() {
    print_(failures ? ('FAILED ' + failures) : 'ALL CALIBRATION TESTS PASSED');
    if (failures && typeof process !== 'undefined') process.exit(1);
}
midiAccessOnce().then(finish, function (e) { ok('the MIDI access test ran', false, String(e)); finish(); });
