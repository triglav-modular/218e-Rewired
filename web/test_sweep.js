// The sweep driver: what it refuses to start against, and what it measures.
//
// The estimator and the firmware arithmetic are checked in test_calibrate.js.
// What is checked here is the part that talks to the outside world - the
// channel probe, the anchor, note-off balance - by giving it an outside world
// to talk to: a MIDI output that answers on one channel, and an audio input
// that returns whatever that instrument is droning.  Neither needs hardware,
// and both can be made to misbehave in the exact way a real one did.
//
// The run that prompted this swept sixty-five notes into a keyboard that was
// unplugged, anchored on room noise from a laptop microphone at clarity 0.00,
// and reported every note as "not heard".  Three separate things had to let it
// through; each one is a test below.
//
// node only: it loads calibrate.js a second time against a fake window, which
// jsc's global-per-file loading cannot do.
var fs = require('fs'), path = require('path');
var SRC = fs.readFileSync(path.join(__dirname, 'calibrate.js'), 'utf8');
var RATE = 48000;

var failures = 0;
function ok(name, cond, detail) {
    if (!cond) { failures++; console.log('FAIL  ' + name + (detail ? '   ' + detail : '')); }
    else console.log('ok    ' + name + (detail ? '   ' + detail : ''));
}

// A deterministic noise source: a real Math.random() here would make the
// difference between a passing and a failing probe a matter of the day.
var seed = 2026;
function rnd() { seed = (seed * 1103515245 + 12345) & 0x7fffffff; return seed / 0x7fffffff; }

// --- the world on the other side of the cables -------------------------
function makeWorld(opts) {
    var w = {
        listening: opts.listening,          // the MIDI channel it answers on
        connected: opts.connected !== false,
        error: opts.error || 0,             // cents of tracking error, uniform
        errorAt: opts.errorAt || {},        // ...except at these notes
        noiseOnly: !!opts.noiseOnly,        // hears the room, not the 208
        // Notes that make no sound at all - a dropout.  The oscillator is
        // silent while they are held, and sounds again on the next note.
        mute: opts.mute || {},
        // Notes the instrument ignores - the note-on never takes, so the
        // drone holds whatever it was already playing.  This is the shape
        // that used to be warned about and then folded in at about -100
        // cents, because -100 was inside the 120 the guard then allowed.
        deaf: opts.deaf || {},
        // Nothing on the audio input at all - the 208 patched into a
        // different channel of the desk than the one being listened to.
        silent: !!opts.silent,
        // What the audio interface really has, against what an "ideal"
        // request negotiates out of it.  A desk that hands over two for
        // "ideal" and twelve for "exact: 12" is the Model 12 shape.
        channels: opts.channels || 1,
        idealGives: opts.idealGives || null,
        // Note-ons that arrive late, by their count on the listening channel
        // (1 and 2 are the probe's): 'half' keeps the note before sounding
        // through the first half of the next window read, 'whole' through
        // all of it, and a number holds that pitch through the first half
        // instead.  The Safari runs of 2026-09-26 had the anchor after the
        // probe late by half a window, every time.
        late: opts.late || {},
        heardOn: 0, pending: null,
        hz: 130.81, quiet: false, sent: [], notesOn: 0,
        opened: [], splitFrom: null
    };
    w.output = {
        id: 'sim', name: 'Sim MIDI OUT',
        get state() { return w.connected ? 'connected' : 'disconnected'; },
        send: function (m) {
            var status = m[0] & 0xf0, ch = m[0] & 0x0f, note = m[1];
            w.sent.push({ status: status, ch: ch, note: note });
            if (!w.connected) return;
            if (status === 0x90) {
                w.notesOn++;
                if (ch === w.listening) {
                    w.heardOn++;
                    var late = w.late[w.heardOn];
                    if (late) w.pending = { hz: typeof late === 'number' ? late : w.hz, whole: late === 'whole' };
                    if (!w.deaf[note]) {
                        var e = w.errorAt[note] !== undefined ? w.errorAt[note] : w.error;
                        w.hz = 32.703 * Math.pow(2, (note - 24 + e / 100) / 12);
                    }
                    w.quiet = !!w.mute[note];
                }
            } else if (status === 0x80) { w.notesOn--; }
        }
    };
    return w;
}

function load(w) {
    var root = {};
    // A world that keeps time (the calibration mode's, below) is told how
    // long each wait was; nothing here waits on a real clock.
    root.setTimeout = function (fn, ms) {
        if (w.clock !== undefined) w.clock += ms || 0;
        return setTimeout(fn, 0);
    };
    // And one with `listeners` is handed the page's, so a test can be the
    // tab going away.
    root.addEventListener = function (type, fn) {
        if (w.listeners) (w.listeners[type] = w.listeners[type] || []).push(fn);
    };
    root.removeEventListener = function (type, fn) {
        if (w.listeners && w.listeners[type]) {
            w.listeners[type] = w.listeners[type].filter(function (f) { return f !== fn; });
        }
    };
    root.navigator = {
        requestMIDIAccess: function () {
            return Promise.resolve({
                outputs: { forEach: function (f) { f(w.output); } }
            });
        },
        mediaDevices: {
            enumerateDevices: function () { return Promise.resolve([]); },
            // Constraints are honoured rather than ignored, because the fault
            // being pinned lives in the difference between them: getCapabilities
            // says nothing about channels - which is the common case and the one
            // that hid a twelve channel desk - "ideal" is answered with whatever
            // the device feels like, and only "exact" gets the full count.
            getUserMedia: function (c) {
                var asked = c && c.audio && c.audio.channelCount;
                var exact = asked && asked.exact;
                var got;
                if (exact) {
                    if (exact > w.channels) {
                        w.opened.push('exact ' + exact + ' refused');
                        var err = new Error('exact channelCount cannot be met');
                        err.name = 'OverconstrainedError';
                        return Promise.reject(err);
                    }
                    got = exact;
                    w.opened.push('exact ' + exact + ' -> ' + got);
                } else {
                    got = Math.min(w.idealGives || w.channels, w.channels);
                    w.opened.push('ideal -> ' + got);
                }
                return Promise.resolve({
                    getAudioTracks: function () {
                        return [{ getSettings: function () { return { channelCount: got }; },
                                  getCapabilities: function () { return {}; },
                                  stop: function () {} }];
                    },
                    getTracks: function () { return [{ stop: function () {} }]; }
                });
            }
        }
    };
    root.AudioContext = function () {
        this.sampleRate = RATE;
        this.createAnalyser = function () {
            return {
                fftSize: 2048, smoothingTimeConstant: 0,
                getFloatTimeDomainData: function (buf) {
                    // A world can act while a note is held and being read.
                    if (w.onRead) w.onRead();
                    var was = w.pending, now = w.hz;
                    w.pending = null;
                    for (var i = 0; i < buf.length; i++) {
                        w.hz = was && (was.whole || i < buf.length / 2) ? was.hz : now;
                        buf[i] = w.noiseOnly
                            // A wandering hum plus hiss: periodic enough to be
                            // found, nowhere near steady enough to be a tone.
                            ? 0.02 * Math.sin(2*Math.PI*1554*i/RATE +
                                              3*Math.sin(2*Math.PI*7*i/RATE))
                              + 0.02 * (rnd() - 0.5)
                            : (w.quiet || w.silent)
                            // A dropout is not silence on the wire, it is a
                            // level the sweep must refuse to read: this sits
                            // an order of magnitude under MIN_RMS.
                            ? 0.002 * (rnd() - 0.5)
                            : 0.25 * Math.sin(2*Math.PI*w.hz*i/RATE)
                              + 0.08 * Math.sin(4*Math.PI*w.hz*i/RATE);
                    }
                    w.hz = now;
                }
            };
        };
        this.createMediaStreamSource = function () { return { connect: function () {} }; };
        this.createChannelSplitter = function () {
            return { connect: function (dest, from) { w.splitFrom = from; } };
        };
        this.close = function () { return Promise.resolve(); };
    };
    // calibrate.js takes `window` when there is one; naming the parameter
    // window is enough to hand it ours.
    return new Function('window', SRC + '\nreturn window.CALIBRATE;')(root);
}

// The plain sweep's signature (see the end of the file), as the sweep stood
// before calibration mode.
var PLAIN_SWEEP = 'e85c4a93:5811 c725248a:5584 7b8cfaa5:8675 091563a2:5999';

function sweep(w, opts) {
    var C = load(w);
    var o = { output: w.output, channel: 0, deviceId: null, audioChannel: 0,
              low: 3, high: 67, octaveTerm: false, velocity: 100 };
    for (var k in opts) if (k !== 'mute' && k !== 'deaf') o[k] = opts[k];
    return new C.Sweep(o).run();
}

// Which MIDI note excites a given table entry - asked of the module rather
// than worked out here, so a test that names entry 20 cannot drift off it.
function noteForEntry(entry) {
    var C = load(makeWorld({ listening: 0 }));
    var hit = C.plan(entry, entry, false)[0];
    if (!hit) throw new Error('no note reaches entry ' + entry);
    return hit.note;
}

// --- calibration mode: a keyboard that plays its table ---------------------
// Firmware with calibration mode plays mirror[note - 21] exactly and takes
// pitch-table writes into that mirror live, so the sweep can do in one run
// what the page otherwise does over several flashes: read a note, move its
// entry, read it again.  This is that firmware as the page sees it over
// MIDI - NRPN decoded into a mirror, a mode that lapses after five seconds
// without a note-on on its channel or on a key press, a reload from flash -
// in front of a 208 that is not quite exponential: its slope a little off,
// and its local gain wandering a few percent either side along the DAC.
// Outside the mode a note goes through the key table and the remap and the
// pads add 150 cents, so a note played outside it reads plausibly and wrong.
require('vm').runInThisContext(fs.readFileSync(path.join(__dirname, 'generated.js'), 'utf8'),
                               { filename: 'generated.js' });
var B = require('./buildlib.js'), M = require('./settings.js');
var PLAIN = load(makeWorld({ listening: 0 }));
var F0 = 27.5;                  // the 208 at 0 V: the A three semitones under the bottom C

function countsPerCentOf(cfg) {
    return cfg.pitch.dac_counts / (cfg.pitch.dac_vref * cfg.pitch.dac_gain) *
           cfg.pitch.volts_per_octave / GEN.calibrationVoltsPerOctave / 1200;
}

function modeWorld(opts) {
    var w = makeWorld({ listening: opts.listening === undefined ? 2 : opts.listening,
                        mute: opts.mute });
    var cfg = B.expand({ volts_per_octave: opts.vpo || 1.2, pitch_offset: opts.offset !== false });
    var cpo = countsPerCentOf(cfg) * 1200;          // DAC counts an octave of the ramp
    w.cfg = cfg;
    w.shift = GEN.bottomKeyIndex - cfg.pitch.bottom_key_semitone;
    w.flash = (opts.table || B.pitchTable(cfg, cfg._calibration)).slice();
    w.mirror = w.flash.slice();
    w.mode = false; w.clock = 0; w.lastOn = -1e9; w.modeSince = 0; w.listeners = {};
    w.events = []; w.pairs = []; w.writes = []; w.backwards = []; w.atModeOff = [];
    w.outside = 0; w.keyAt = opts.keyAt || {};
    var slope = opts.slope === undefined ? 0.015 : opts.slope;
    var wobble = opts.wobble === undefined ? 0.03 : opts.wobble;
    w.gMin = 1 + slope - wobble; w.gMax = 1 + slope + wobble;
    var P = 700, amp = wobble * P / (2 * Math.PI);  // local gain (1 + slope) +/- wobble
    var gainAt = opts.gainAt || {}, drift = opts.drift || 0;
    var outside = opts.outside === undefined ? 150 : opts.outside;
    function octaves(v) { return (v * (1 + slope) + amp * Math.sin(2 * Math.PI * v / P + 0.4)) / cpo; }
    // The pitch entry e sounds at when it holds v.  gainAt makes one entry
    // answer a move that many times over: not a 208, but the way to make a
    // try that overshoots on purpose.  drift is cents a second.
    w.pitchOf = function (e, v) {
        var o = octaves(v);
        if (gainAt[e]) o = octaves(w.flash[e]) + (o - octaves(w.flash[e])) * gainAt[e];
        return F0 * Math.pow(2, o + drift * w.clock / 1000 / 1200);
    };
    // Where entry e is exactly in tune against the anchor, in counts: what a
    // run is meant to get within half a count of.
    w.ideal = function (e) {
        var want = w.pitchOf(3, w.flash[3]) * Math.pow(2, (e - 3) / 12), lo = -4000, hi = 8000;
        for (var k = 0; k < 80; k++) {
            var mid = (lo + hi) / 2;
            if (w.pitchOf(e, mid) < want) lo = mid; else hi = mid;
        }
        return (lo + hi) / 2;
    };
    var dec = B.nrpnDecoder();
    w.output.send = function (m) {
        var status = m[0] & 0xf0, ch = m[0] & 0x0f, note = m[1];
        w.sent.push({ status: status, ch: ch, note: note });
        if (!w.connected) return;
        // Five seconds without a note-on, counted from the later of the
        // last one and the mode going on.  A second "on" while it is on
        // restarts nothing: that is the reading that makes a lapse likeliest.
        if (w.mode && w.clock - Math.max(w.lastOn, w.modeSince) > 5000) w.mode = false;
        if (status === 0xB0) {
            var got = dec.feed(m[0], m[1], m[2]);
            if (!got) return;
            w.pairs.push([got.param, got.value]);
            w.events.push(['nrpn', got.param, got.value]);
            if (got.param === 0x3f05) {
                if (got.value === 0x2a2a) {
                    if (!w.mode) w.modeSince = w.clock;
                    w.mode = true;
                } else if (w.mode) {
                    w.mode = false;
                    w.atModeOff.push(w.mirror.slice());
                }
            } else if (got.param === 0x3f01) {
                w.mirror = w.flash.slice();
            } else if (got.param >= 0x80 && got.param < 0x80 + 79) {
                var e = got.param - 0x80;
                w.mirror[e] = got.value;
                w.writes.push([e, got.value]);
                for (var k = w.shift + 1; k < 79; k++) {
                    if (w.mirror[k] <= w.mirror[k - 1]) { w.backwards.push([e, got.value, k]); break; }
                }
            }
            return;
        }
        if (status === 0x90) {
            w.notesOn++;
            w.events.push(['on', ch, note]);
            if (ch !== w.listening) return;
            w.heardOn++;
            w.lastOn = w.clock;
            if (w.keyAt[w.heardOn]) { w.mode = false; w.keyNote = note; }   // a key pressed with this note
            var idx, extra = 0;
            if (w.mode) {
                idx = Math.max(0, Math.min(78, note - 21));
            } else {
                w.outside++;
                var hit = PLAIN.entryFor(note, false);
                if (!hit) return;                       // no key reaches it: the drone holds
                idx = hit.index; extra = outside;
            }
            w.hz = w.pitchOf(idx, w.mirror[idx]) * Math.pow(2, extra / 1200);
            w.quiet = !!w.mute[note];
        } else if (status === 0x80) {
            w.notesOn--;
        }
    };
    return w;
}

// The page's wiring, in the test's hands: the mode and the writes go
// through SETTINGSMIDI exactly as app.js sends them.  Every entry that holds
// an offset is swept - all 79 with the pitch offset, from the bottom key's
// up without it - which is what the page asks for.
function modeSweep(w, opts) {
    var C = load(w);
    var timers = { setTimeout: function (fn, ms) { w.clock += ms || 0; return setTimeout(fn, 0); } };
    var o = { output: w.output, channel: w.listening, deviceId: null, audioChannel: 0,
              low: w.shift, high: 78, velocity: 100,
              mode: { on: function () { M.calibrationMode(w.output, true); },
                      off: function () { M.endCalibration(w.output); } },
              adjust: { table: w.flash.slice(), countsPerCent: countsPerCentOf(w.cfg),
                        write: function (entry, value) {
                            return M.writePitch(w.output, entry, value, { timers: timers });
                        } } };
    for (var k in opts) o[k] = opts[k];
    w.run = new C.Sweep(o);
    return w.run.run();
}

// How close a run came: the worst distance from in tune, in counts and in
// the residuals the run itself read, over the entries it heard.
function closeness(w, out) {
    var r = { counts: Infinity, cents: Infinity, at: null, centsAt: null, heard: 0 };
    if (!out || !out.table || !out.readings) return r;
    r.counts = 0; r.cents = 0;
    out.readings.forEach(function (x) {
        if (x.residual === null || x.residual === undefined) return;
        r.heard++;
        var d = Math.abs(out.table[x.index] - w.ideal(x.index));
        if (d > r.counts) { r.counts = d; r.at = x.index; }
        if (Math.abs(x.residual) > r.cents) { r.cents = Math.abs(x.residual); r.centsAt = x.index; }
    });
    return r;
}
// What "within half a count" can mean, given how it is read.  Half a count
// is judged at the ramp's rate, and a 208's own rate is a few percent either
// side of it.  Where it is shallower a note stops up to 0.5/gMin of its own
// counts out; where it is steeper and in tune midway between two counts,
// neither comes within half a count at the ramp's rate, and the closer is
// kept - reading up to half a count times gMax.  Plus 0.01 count and 0.02
// cent for the estimator.
function tolerance(w) {
    return { counts: 0.5 / w.gMin + 0.01,
             cents: 0.5 / countsPerCentOf(w.cfg) * w.gMax + 0.02 };
}
function lastPairs(w, n) { return JSON.stringify(w.pairs.slice(-n)); }
var ENDED = JSON.stringify([[0x3f05, 0], [0x3f01, 0]]);
function increasing(t, from) {
    if (!t) return false;
    for (var k = from + 1; k < t.length; k++) if (t[k] <= t[k - 1]) return false;
    return true;
}
function same(a, b) { return JSON.stringify(a) === JSON.stringify(b); }

// The page's own code for what a converging run hands it, out of app.js
// with the DOM stubbed the way web/test_readback.js stubs it: the version
// gate, and loadPitchTable with rows(), whose table is what a build makes.
var APP = fs.readFileSync(path.join(__dirname, 'app.js'), 'utf8');
function appSource(open, close) {
    var start = APP.indexOf(open);
    if (start < 0) return '';
    return APP.slice(start, APP.indexOf(close, start) + close.length);
}
function appFunction(name) { return appSource('\n    function ' + name + '(', '\n    }\n'); }
function pageLoad(table) {
    var vm = require('vm'), page = vm.createContext({ BUILDLIB: B, GEN: GEN });
    vm.runInContext([
        'var vpo = 1.2, pitchOffset = true, nodes = {};',
        'var PLAYABLE_LOW = 3, PLAYABLE_HIGH = 67, TABLE_ENTRIES = 79;',
        'var measured = [], baseline = {}, baselineSources = {}, baselineName = "", baselineHistory = null, interpolated = {};',
        // Readings on the page before the run: the load clears them.
        'for (var i = 0; i < 79; i++) measured.push(i % 7 ? 0 : 3.5);',
        'function $(id) { return nodes[id] || (nodes[id] = { checked: false }); }',
        'function press(id, v) { if (id === "vpo") vpo = Number(v); else if (id === "offset") { var on = v === "1"; if (on !== pitchOffset) { pitchOffset = on; PLAYABLE_LOW = on ? 3 : 0; PLAYABLE_HIGH = PLAYABLE_LOW + 64; if (haveBaseline()) clearBaseline(); } } }',
        'function syncCalBody() {} function syncBaseline() {} function buildTable() {} function drawPlot() {} function validateCal() {} function invalidate() {}',
        appFunction('clearBaseline'), appFunction('haveBaseline'), appFunction('rows'),
        appFunction('loadPitchTable')
    ].join('\n'), page, { filename: 'web/app.js (extracted)' });
    page.table = table;
    page.was = B.pitchTableSettings(table);
    return vm.runInContext('clearBaseline(); loadPitchTable(table, was, "the tuned table");' +
        '({ built: BUILDLIB.pitchTable(BUILDLIB.expand({ volts_per_octave: vpo, pitch_offset: pitchOffset }), rows()),' +
        '   cleared: measured.every(function (v) { return v === 0; }), ticked: $("useCal").checked,' +
        '   name: baselineName })', page);
}

// A sweep's whole outward behaviour, minus the clock: every MIDI message,
// every reading, warning and log row, and which keys the result has.
function signature(w, out) {
    var text = JSON.stringify({
        sent: w.sent.map(function (m) { return [m.status, m.ch, m.note]; }),
        keys: Object.keys(out).sort(), readings: out.readings, warnings: out.warnings,
        log: out.log.map(function (r) {
            var c = {};
            Object.keys(r).forEach(function (k) { if (k !== 't') c[k] = r[k]; });
            return c;
        }),
        anchorHz: out.anchorHz, channel: out.channel, drift: out.drift
    }, function (k, v) { return typeof v === 'number' ? Math.round(v * 1000) / 1000 : v; });
    var h = 0x811c9dc5;
    for (var i = 0; i < text.length; i++) {
        h ^= text.charCodeAt(i);
        h = Math.imul(h, 0x01000193) >>> 0;
    }
    return ('0000000' + h.toString(16)).slice(-8) + ':' + text.length;
}

(async function () {
    var w, err, out;

    // --- a keyboard that is not there ----------------------------------
    // The reported fault, exactly: unplugged MIDI, a laptop microphone, and a
    // channel chosen by hand rather than left on Auto.
    w = makeWorld({ listening: null, connected: false, noiseOnly: true });
    err = null;
    try { await sweep(w, { channel: 2, high: 10 }); } catch (e) { err = e; }
    ok('a disconnected keyboard stops the sweep', !!err,
       err ? '' : 'it ran to the end');
    ok('and blames the silent MIDI, not the audio',
       !!err && /did not move the pitch/.test(err.message));
    ok('and stops within a few notes, not sixty-five', w.sent.length < 12,
       w.sent.length + ' MIDI messages');

    // The port says so itself, which is what makes it checkable before a run.
    var C = load(w);
    ok('portGone spots a disconnected port', C.portGone(w.output) === true);
    w.connected = true;
    ok('and clears once it is back', C.portGone(w.output) === false);

    // --- listening, but not where it was told to look -------------------
    w = makeWorld({ listening: 5 });
    err = null;
    try { await sweep(w, { channel: 2, high: 10 }); } catch (e) { err = e; }
    ok('a chosen channel nothing answers on is caught and named',
       !!err && /channel 3 did not move the pitch/.test(err.message),
       err ? '' : 'it ran to the end');

    // The probe used to hand its second measurement the answer as the pitch to
    // expect, which narrowed the search to a +/-300 cent band around exactly
    // the interval being tested - the width of the test itself.  Noise passed;
    // a real tone on the wrong channel did not.  Both directions are pinned.
    w = makeWorld({ listening: null, noiseOnly: true });
    err = null;
    try { await sweep(w, { channel: null, high: 12 }); } catch (e) { err = e; }
    ok('Auto against noise gives up instead of anchoring on it',
       !!err && /No MIDI channel moved the pitch/.test(err.message),
       err ? '' : 'it ran to the end');

    // --- an instrument that is actually there ---------------------------
    var found = null;
    w = makeWorld({ listening: 9 });
    out = await sweep(w, { channel: null, high: 12,
                           onChannel: function (ch) { found = ch; } });
    ok('Auto finds the channel the keyboard answers on', found === 9, 'found ' + found);
    ok('and reports it back with the readings', out.channel === 9);

    w = makeWorld({ listening: 2 });
    out = await sweep(w, { channel: 2, high: 20 });
    var heard = out.readings.filter(function (r) { return r.cents !== null; });
    ok('every note of a working instrument is heard',
       heard.length === out.readings.length,
       heard.length + '/' + out.readings.length);
    var worst = Math.max.apply(null, heard.map(function (r) { return Math.abs(r.cents); }));
    ok('and one that tracks perfectly reads near zero', worst < 2,
       'worst ' + worst.toFixed(2) + ' cents');
    ok('every note sent was released', w.notesOn === 0, 'still on: ' + w.notesOn);

    // A tracking error shared by every note is the 208's tuning, not its
    // tracking: it divides out against the anchor and must not reach the table.
    w = makeWorld({ listening: 2, error: 7 });
    out = await sweep(w, { channel: 2, high: 20 });
    heard = out.readings.filter(function (r) { return r.cents !== null; });
    var mean = heard.reduce(function (a, r) { return a + r.cents; }, 0) / heard.length;
    ok('a uniform error cancels against the anchor', Math.abs(mean) < 1.5,
       'mean ' + mean.toFixed(2) + ' cents');

    // --- a dropout costs its own notes and no more -----------------------
    // The band hear() searches used to be carried forward from the last
    // reading, and the carry sat below the miss path's `continue` - so a note
    // that was not heard froze it.  Three silent notes left it three semitones
    // low, and the notes after them were measured at their true pitches and
    // thrown away.  Reproduced at entries 20-22 in the audit: three misses
    // cost eleven readings.
    var gone = {};
    [20, 21, 22].forEach(function (e) { gone[noteForEntry(e)] = true; });
    w = makeWorld({ listening: 2, mute: gone });
    out = await sweep(w, { channel: 2, high: 34 });
    var missed = out.readings.filter(function (r) { return r.cents === null; });
    ok('three silent notes cost three readings, not the rest of the sweep',
       missed.length === 3,
       missed.length + ' blank: ' + missed.map(function (r) { return r.index; }).join(','));
    ok('and the three blanks are the notes that were actually silent',
       missed.map(function (r) { return r.index; }).join(',') === '20,21,22',
       missed.map(function (r) { return r.index; }).join(','));
    var after = out.readings.filter(function (r) { return r.index > 22; });
    ok('the notes after a dropout are still measured',
       after.length > 0 && after.every(function (r) {
           return r.cents !== null && Math.abs(r.cents) < 2;
       }),
       after.filter(function (r) { return r.cents === null; }).length + ' of ' +
       after.length + ' lost');

    // --- the anchor is believed only once it has settled -----------------
    // Three runs in Safari (2026-09-26): the bottom C after the probe's C2
    // arrived half a window late, the whole window read as C2 at clarity
    // 1.00, and it was taken as the anchor.  Every note after it was looked
    // for two octaves too high, found only as a harmonic at clarity 0.00,
    // and reported "not heard": sixty-five blanks.
    var C0 = 32.703;
    w = makeWorld({ listening: 2, late: { 3: 'half' } });
    out = await sweep(w, { channel: 2, high: 20 });
    heard = out.readings.filter(function (r) { return r.cents !== null; });
    ok('an anchor still moving when measured is measured again',
       out.log.filter(function (r) { return r.what === 'anchor'; }).length >= 2 &&
       Math.abs(1200 * Math.log2(out.anchorHz / C0)) < 2,
       'anchored at ' + out.anchorHz.toFixed(2) + ' Hz');
    ok('and the sweep after it is heard', heard.length === out.readings.length,
       heard.length + '/' + out.readings.length);
    ok('and reads near zero', heard.every(function (r) { return Math.abs(r.cents) < 2; }));

    // Late by a whole window, the anchor is the probe's C2 throughout: its
    // halves agree, so only the probe's reading of the same note gives it
    // away.
    w = makeWorld({ listening: 2, late: { 3: 'whole' } });
    out = await sweep(w, { channel: 2, high: 20 });
    heard = out.readings.filter(function (r) { return r.cents !== null; });
    ok('an anchor that is still the probe\u2019s high note is caught by the probe\u2019s reading',
       Math.abs(1200 * Math.log2(out.anchorHz / C0)) < 2 && heard.length === out.readings.length,
       'anchored at ' + out.anchorHz.toFixed(2) + ' Hz, ' + heard.length + '/' + out.readings.length);

    // One that never settles stops the sweep and says why.
    w = makeWorld({ listening: 2, late: { 3: 'half', 4: 130.81, 5: 130.81 } });
    err = null;
    try { await sweep(w, { channel: 2, high: 20 }); } catch (e) { err = e; }
    ok('an anchor that never settles stops the sweep',
       !!err && /did not come back as a steady tone \(it moved \d+ cents while being measured\)/.test(err.message),
       err ? err.message : 'it ran to the end');
    ok('and every note sent was released', w.notesOn === 0, 'still on: ' + w.notesOn);

    // The checks through the run are held to the same: a late one is
    // measured again rather than skipped or believed.
    w = makeWorld({ listening: 2, late: { 12: 'half' } });
    out = await sweep(w, { channel: 2, high: 34 });
    heard = out.readings.filter(function (r) { return r.cents !== null; });
    ok('a drift check that is still moving is measured again, not believed',
       heard.length === out.readings.length &&
       heard.every(function (r) { return Math.abs(r.cents) < 2; }) &&
       !out.warnings.some(function (x) { return /drift check/.test(x); }),
       heard.length + '/' + out.readings.length + ' ' + out.warnings.join(' | '));

    // --- a note that did not take is blank, not a -100 cent reading ------
    // The instrument ignores one note-on, so the drone holds the note before
    // it.  That reads about a semitone flat - inside the 120 the guard then
    // allowed - so it used to be warned about and then kept, and
    // rows() negated it into a +100 cent correction one or two DAC counts
    // clear of its neighbour: past the collision guard, past validateCal, and
    // into an image with a semitone playing very nearly its neighbour's pitch.
    var stuck = {}; stuck[noteForEntry(23)] = true;
    w = makeWorld({ listening: 2, deaf: stuck });
    out = await sweep(w, { channel: 2, high: 30 });
    var didNotTake = out.readings.filter(function (r) { return r.index === 23; })[0];
    ok('a note that did not take is recorded blank', didNotTake &&
       didNotTake.cents === null,
       didNotTake ? 'cents ' + didNotTake.cents : 'no reading at entry 23');
    ok('and it is still reported in the warnings',
       out.warnings.some(function (x) { return /did not take/.test(x); }),
       out.warnings.join(' | '));
    ok('and nothing else in the run is blanked with it',
       out.readings.filter(function (r) { return r.cents === null; }).length === 1,
       out.readings.filter(function (r) { return r.cents === null; })
                   .map(function (r) { return r.index; }).join(','));

    // --- the channel it listens on is the channel it was given -----------
    // A desk that reports no channelCount capability, negotiates two for
    // "ideal" and honours "exact: 12".  The dropdown beside the sweep is
    // filled by channelCount(), which finds twelve by asking for it outright;
    // the sweep opened with "ideal", got two, clamped channel 12 to index 1,
    // listened to the wrong input, and then blamed MIDI for the silence.
    var opened = null;
    w = makeWorld({ listening: 2, channels: 12, idealGives: 2 });
    out = await sweep(w, { channel: 2, high: 10, audioChannel: 11,
                           audioChannels: 12,   // what the page's dropdown was filled from
                           onChannels: function (count, want) {
                               opened = { count: count, want: want };
                           } });
    ok('a twelve channel desk behind an "ideal" of two is opened in full',
       opened && opened.count === 12,
       opened ? 'opened ' + opened.count + ' [' + w.opened.join('; ') + ']'
              : 'onChannels never fired');
    ok('and the sweep listens on the channel it was asked for',
       opened && opened.want === 11 && w.splitFrom === 11,
       opened ? 'want ' + opened.want + ', splitter took ' + w.splitFrom : '-');

    // The count the page discovered is tried before the ladder, so the common
    // case costs one extra open rather than a walk down from 32.
    ok('the count the page already opened is asked for first',
       w.opened.length === 2 && w.opened[1] === 'exact 12 -> 12',
       w.opened.join('; '));

    // And without it - a caller that never ran channelCount - the same ladder
    // channelCount climbs still finds the shape, at the cost of the refusals.
    w = makeWorld({ listening: 2, channels: 12, idealGives: 2 });
    opened = null;
    out = await sweep(w, { channel: 2, high: 10, audioChannel: 11,
                           onChannels: function (count, want) {
                               opened = { count: count, want: want };
                           } });
    ok('and the ladder reaches it even when the count was never passed in',
       opened && opened.count === 12 && opened.want === 11,
       opened ? 'opened ' + opened.count + ' on ' + opened.want : '-');

    // A device that really does have two channels must not be walked down the
    // ladder for a channel it cannot reach - and must still sweep.
    w = makeWorld({ listening: 2, channels: 2 });
    out = await sweep(w, { channel: 2, high: 10, audioChannel: 1 });
    ok('a device that already reaches the channel is opened once',
       w.opened.length === 1 && w.opened[0] === 'ideal -> 2', w.opened.join('; '));

    // --- a channel that cannot be reached is an audio fault, and says so ---
    // Two channels on the wire and channel 12 chosen: the sweep used to clamp
    // to index 1, listen to the wrong input, and then report that MIDI was
    // silent.  It refuses before any note goes out now, and the refusal is
    // about the audio.
    w = makeWorld({ listening: 2, channels: 2 });
    err = null;
    try { await sweep(w, { channel: 2, high: 10, audioChannel: 11 }); } catch (e) { err = e; }
    ok('a channel the input does not have stops the sweep', !!err,
       err ? '' : 'it ran to the end');
    ok('and the refusal names the audio channel, not MIDI',
       !!err && /Audio channel 12 could not be opened/.test(err.message) &&
       !/MIDI/.test(err.message),
       err ? err.message : '-');
    ok('and it says how many the input actually gave',
       !!err && /this input gave 2 channels/.test(err.message),
       err ? err.message : '-');
    ok('and nothing was played into the instrument first', w.sent.length === 0,
       w.sent.length + ' MIDI messages');

    // And when the channel is reachable but nothing answers on MIDI, the
    // message says where the sweep was listening, so the silence can be
    // traced to the right cable.
    w = makeWorld({ listening: 5, channels: 12, idealGives: 2 });
    err = null;
    try {
        await sweep(w, { channel: 2, high: 10, audioChannel: 11, audioChannels: 12 });
    } catch (e) { err = e; }
    ok('a MIDI probe failure names the audio channel it listened on',
       !!err && /listening on channel 12 of that input/.test(err.message),
       err ? err.message : 'it ran to the end');

    w = makeWorld({ listening: null, noiseOnly: true, channels: 12, idealGives: 2 });
    err = null;
    try {
        await sweep(w, { channel: null, high: 12, audioChannel: 11, audioChannels: 12 });
    } catch (e) { err = e; }
    ok('and so does the Auto search when no channel answers',
       !!err && /No MIDI channel moved the pitch/.test(err.message) &&
       /listening on channel 12 of that input/.test(err.message),
       err ? err.message : 'it ran to the end');

    // --- a real instrument reads past a semitone out -------------------
    // A replaced expo converter came back 129 and 138 cents flat at the top
    // two keys, clarity 1.00, and the 120 cent guard threw both away - the
    // two readings the recalibration was for.  Anything found inside the
    // band the estimator searched is a reading.
    // The tail of that run, as logged: the sag grows through the top octave,
    // so every note still rises and none of them is a note that did not take.
    var sag = {};
    [[59, -54], [60, -64], [61, -74], [62, -85], [63, -98], [64, -106],
     [65, -117], [66, -129], [67, -138]].forEach(function (p) {
        sag[noteForEntry(p[0])] = p[1];
    });
    w = makeWorld({ listening: 0, errorAt: sag });
    out = await sweep(w, { channel: 0 });
    var top = out.readings.filter(function (r) { return r.index >= 66; });
    ok('the top two keys, 129 and 138 cents flat, are kept',
       top.length === 2 && top.every(function (r) { return r.cents !== null; }),
       out.warnings.join(' | ') || 'no warnings');
    ok('and read at their size', top.length === 2 &&
       Math.abs(top[0].cents + 129) < 3 && Math.abs(top[1].cents + 138) < 3,
       top.map(function (r) { return r.cents === null ? 'blank' : r.cents.toFixed(1); }).join(','));

    // --- an input with nothing on it is an audio fault -------------------
    // The sweep listened on channel 1 of a desk with the 208 on channel 2,
    // heard bleed at level 0.0013, played thirty-two notes into it and then
    // reported that no MIDI channel moved the pitch.
    w = makeWorld({ listening: 0, silent: true });
    err = null;
    try { await sweep(w, { channel: null, audioChannel: 0, high: 10 }); } catch (e) { err = e; }
    ok('a silent audio input stops the sweep', !!err, err ? '' : 'it ran');
    ok('and is named as the audio, not MIDI',
       !!err && /Nothing usable is coming in on audio channel 1/.test(err.message),
       err ? err.message : '');
    ok('and says the level heard and the level needed',
       !!err && /level 0\.000\d, where a note needs 0\.0020/.test(err.message));
    ok('and no note was played first', w.sent.length === 0, w.sent.length + ' sent');

    // --- the log carries a note the estimator could not read -------------
    // A note that sounds outside the band searched - here 900 cents flat,
    // which a run anchored three octaves up logged as "no pitch found" from
    // 2.9 kHz upward - is measured as nothing.  The CSV writer tests each
    // field for null; the drift of such a row was undefined, and the
    // Download button died on .toFixed for any run with one in it.
    var gap = {}; gap[noteForEntry(12)] = -900;
    w = makeWorld({ listening: 0, errorAt: gap });
    out = await sweep(w, { channel: 0, high: 14 });
    var unread = out.log.filter(function (r) { return r.hz === null; });
    ok('an unread note is in the log', unread.length > 0);
    ok('with every field null rather than undefined',
       unread.every(function (r) {
           return ['hz', 'firstHalfHz', 'secondHalfHz', 'halfDrift', 'clarity']
               .every(function (k) { return r[k] === null; });
       }),
       unread.length ? JSON.stringify(unread[0]) : '');

    // --- the plain sweep is the sweep it was ------------------------------
    // Converging is an option, and a sweep without it has to be exactly the
    // one before it: the same MIDI, note for note, and the same readings,
    // warnings, log and result.  Pinned by a hash taken from the sweep as it
    // stood before calibration mode (2026-09-27), over four runs that reach
    // its branches: a uniform error, an Auto search, three dropouts and a
    // note that did not take, and an anchor that arrived late.
    seed = 2026;
    var sigs = [];
    w = makeWorld({ listening: 2, error: 7 });
    out = await sweep(w, { channel: 2, high: 20 });
    sigs.push(signature(w, out));
    w = makeWorld({ listening: 5 });
    out = await sweep(w, { channel: null, high: 12 });
    sigs.push(signature(w, out));
    var dropped = {};
    [20, 21, 22].forEach(function (e) { dropped[noteForEntry(e)] = true; });
    var ignored = {}; ignored[noteForEntry(23)] = true;
    w = makeWorld({ listening: 2, mute: dropped, deaf: ignored });
    out = await sweep(w, { channel: 2, high: 30 });
    sigs.push(signature(w, out));
    w = makeWorld({ listening: 2, late: { 3: 'half' } });
    out = await sweep(w, { channel: 2, high: 20 });
    sigs.push(signature(w, out));
    ok('without adjust or mode the sweep sends, reads and reports what it did before',
       sigs.join(' ') === PLAIN_SWEEP, sigs.join(' '));

    // --- calibration mode: one run converges -----------------------------
    // 1.2 V/oct with the pitch offset: all 79 entries, from the flat table,
    // on a 208 whose slope is 1.5% steep and whose gain wanders 3%.
    w = modeWorld({ listening: 2 });
    var half = 0.5 / countsPerCentOf(w.cfg);
    err = null;
    try { out = await modeSweep(w, {}); } catch (e) { err = e; out = { readings: [], warnings: [], log: [] }; }
    ok('a run in calibration mode completes', !err, err ? err.message : '');
    var rd = out.readings;
    ok('calibration mode sweeps all 79 entries, notes 21 to 99, one apiece',
       rd.length === 79 && rd.every(function (r, i) { return r.index === i && r.note === i + 21; }),
       rd.length + ' readings, ' + (rd.length ? rd[0].note + '..' + rd[rd.length - 1].note : '-'));
    ok('and returns the table it converged', !!out.table && out.table.length === 79);
    var near = closeness(w, out), tol = tolerance(w);
    ok('and every entry is heard', near.heard === 79, near.heard + '/79');
    ok('one run brings every heard entry within half a count of in tune (' +
       tol.counts.toFixed(3) + ' of this 208\u2019s counts)',
       near.counts <= tol.counts, 'worst ' + near.counts.toFixed(3) + ' counts, at entry ' + near.at);
    ok('with every residual ' + tol.cents.toFixed(2) + ' cents or less at 1.2 V/oct, half a count being ' +
       half.toFixed(2), near.cents <= tol.cents,
       'worst ' + near.cents.toFixed(3) + ' cents, at entry ' + near.centsAt);
    var start = Math.max.apply(null, rd.map(function (r) { return Math.abs(r.first || 0); }));
    ok('from a table that needed it', start > 50, 'the flat table read up to ' + start.toFixed(1) + ' cents out');
    ok('in at most three tries a note', rd.every(function (r) { return r.tries >= 1 && r.tries <= 3; }),
       rd.map(function (r) { return r.tries; }).join(''));
    ok('the anchor is never written', !w.writes.some(function (p) { return p[0] === 3; }) &&
       !!out.table && out.table[3] === w.flash[3], JSON.stringify(w.writes.filter(function (p) { return p[0] === 3; })));
    ok('the table the keyboard plays never goes backwards, not even for a note',
       w.backwards.length === 0 && increasing(out.table, w.shift), JSON.stringify(w.backwards.slice(0, 3)));
    ok('and the mirror holds every kept value when the mode ends',
       w.atModeOff.length === 1 && same(w.atModeOff[0], out.table),
       w.atModeOff.length + ' mode-offs');
    ok('the run ends with the mode off and the mirror reloaded from flash',
       lastPairs(w, 2) === ENDED && !w.mode && same(w.mirror, w.flash), lastPairs(w, 2));
    var firstOn = -1, firstMode = -1;
    w.events.forEach(function (ev, k) {
        if (firstOn < 0 && ev[0] === 'on') firstOn = k;
        if (firstMode < 0 && ev[0] === 'nrpn' && ev[1] === 0x3f05 && ev[2] === 0x2a2a) firstMode = k;
    });
    ok('the mode goes on before the first note', firstMode >= 0 && firstMode < firstOn,
       'mode at event ' + firstMode + ', first note at ' + firstOn);
    ok('and no note is played outside it', w.outside === 0, w.outside + ' outside');
    ok('every note sent was released', w.notesOn === 0, 'still on: ' + w.notesOn);
    ok('the log carries the value each note played at',
       out.log.some(function (r) { return r.what === 'retry'; }) &&
       out.log.filter(function (r) { return r.what === 'sweep' || r.what === 'retry'; })
              .every(function (r) { return typeof r.value === 'number'; }));

    // What the page does with it: the table becomes offsets, and the offsets
    // have to build that table again exactly - once straight, and once the
    // way the page loads a keyboard's table, as the baseline with the
    // readings cleared.
    var cfg12 = w.cfg, conv12 = out.table;
    var rows = conv12 ? B.pitchCents(cfg12, conv12) : [];
    ok('the converged table builds again from its offsets, entry for entry',
       !!conv12 && same(B.pitchTable(cfg12, rows), conv12));
    var base = {}, zeros = [];
    rows.forEach(function (r) { base[r.semitone] = r.cents; });
    for (var z = 0; z < 79; z++) zeros.push(0);
    var pageRows = B.calibrationRows(base, {}, zeros, 3, 67, 79, true, null)
        .map(function (v, s) { return { semitone: s, cents: v }; });
    ok('and through the page\u2019s own rows, loaded as a baseline',
       !!conv12 && same(B.pitchTable(cfg12, pageRows), conv12));
    var loaded = null;
    try { loaded = conv12 && pageLoad(conv12); } catch (e) { loaded = { error: e.message }; }
    ok('and through the page\u2019s own load of it: the build\u2019s table is the converged table',
       !!loaded && !loaded.error && same(loaded.built, conv12) && loaded.cleared && loaded.ticked &&
       loaded.name === 'the tuned table', loaded && loaded.error ? loaded.error : '');
    ok('the page\u2019s counts per cent are the ramp\u2019s',
       typeof B.pitchCountsPerCent === 'function' &&
       Math.abs(B.pitchCountsPerCent(cfg12) - countsPerCentOf(cfg12)) < 1e-12 &&
       Math.abs(B.pitchCountsPerCent(cfg12) - 0.4006) < 0.001,
       typeof B.pitchCountsPerCent === 'function' ? String(B.pitchCountsPerCent(cfg12)) : 'missing');

    // 1 V/oct without the pitch offset: the three entries under the bottom
    // key sit at 0 V and are not offsets, so the sweep starts at entry 3;
    // and a slope 1% shallow, where half a count in cents is a little more
    // than half a count of the 208.
    w = modeWorld({ listening: 2, vpo: 1.0, offset: false, slope: -0.01, wobble: 0.02 });
    half = 0.5 / countsPerCentOf(w.cfg);
    out = await modeSweep(w, {});
    near = closeness(w, out); tol = tolerance(w);
    ok('at 1 V/oct without the offset, entries 3 to 78 are swept',
       out.readings.length === 76 && out.readings[0].index === 3 && near.heard === 76,
       out.readings.length + ' readings, ' + near.heard + ' heard');
    ok('and each comes within half a count of in tune (' + tol.counts.toFixed(3) + ' of its counts)',
       near.counts <= tol.counts, 'worst ' + near.counts.toFixed(3) + ' counts at entry ' + near.at);
    ok('with every residual ' + tol.cents.toFixed(2) + ' cents or less, half a count being ' + half.toFixed(2),
       near.cents <= tol.cents, 'worst ' + near.cents.toFixed(3) + ' at entry ' + near.centsAt);
    ok('the entries under the bottom key are neither played nor written',
       !w.writes.some(function (p) { return p[0] < 3; }) && !!out.table &&
       out.table.slice(0, 3).join() === '0,0,0' &&
       !w.events.some(function (ev) { return ev[0] === 'on' && ev[2] < 24; }));
    ok('and that table builds again from its offsets too', !!out.table &&
       same(B.pitchTable(w.cfg, B.pitchCents(w.cfg, out.table)), out.table) &&
       increasing(out.table, w.shift));
    loaded = null;
    try { loaded = out.table && pageLoad(out.table); } catch (e) { loaded = { error: e.message }; }
    ok('and the page\u2019s load of it builds it again, the offset switched off to match',
       !!loaded && !loaded.error && same(loaded.built, out.table), loaded && loaded.error ? loaded.error : '');

    // --- which keyboards get it ---------------------------------------------
    // The gate out of app.js: 3.0.1, the first firmware with the mode (the
    // owner, 2026-09-27), and later; 3.0 and older get the sweep they always
    // did.  Then the rule itself, against another threshold.
    var gate = require('vm').createContext({ BUILDLIB: B }), allowed = null;
    try {
        require('vm').runInContext(appSource('\n    var CALIBRATION_MODE_FIRMWARE', ';\n') +
                                   appFunction('calibrationModeSupported'), gate);
        if (typeof gate.calibrationModeSupported === 'function') {
            allowed = function (v) { return gate.calibrationModeSupported({ firmwareVersion: v }); };
        }
    } catch (e) { allowed = null; }
    ok('3.0.1 is the first firmware put into calibration mode, and 3.0 is not',
       !!allowed && gate.CALIBRATION_MODE_FIRMWARE === '3.0.1' && allowed('3.0.1') &&
       allowed('3.1.0') && !allowed('3.0.0') && !allowed('2.4.0'));
    if (allowed) require('vm').runInContext('CALIBRATION_MODE_FIRMWARE = "3.1.0";', gate);
    ok('once one is, that version and later are, and earlier or silent ones are not',
       !!allowed && allowed('3.1.0') && allowed('3.2.0') && allowed('4.0.0') && !allowed('3.0.9') &&
       !allowed(null) && !gate.calibrationModeSupported(null));

    // --- what is left alone -----------------------------------------------
    // A note that is not heard keeps the value it came with, and is not
    // written at all.
    var hush = {}; hush[41] = true; hush[42] = true;       // entries 20 and 21
    w = modeWorld({ listening: 2, mute: hush });
    out = await modeSweep(w, { high: 26 });
    var lost = out.readings.filter(function (r) { return r.index === 20 || r.index === 21; });
    ok('a note not heard keeps its original value',
       lost.length === 2 && lost.every(function (r) {
           return r.cents === null && r.value === w.flash[r.index] && !!out.table &&
                  out.table[r.index] === w.flash[r.index];
       }), JSON.stringify(lost));
    ok('and is never written', !w.writes.some(function (p) { return p[0] === 20 || p[0] === 21; }));
    ok('and says so', out.warnings.some(function (x) {
           return x.indexOf(PLAIN.noteLabel(20) + ': too quiet to read, left unchanged') === 0;
       }), out.warnings.join(' | '));
    ok('while the notes around it still converge', closeness(w, out).counts <= tolerance(w).counts,
       closeness(w, out).counts.toFixed(3));

    // The closest try is the one kept.  Entry 40 here answers a move two and
    // a half times over, so each correction overshoots further than the
    // last: the first value, never moved, was the closest, and it is
    // written back.
    w = modeWorld({ listening: 2, gainAt: { 40: 2.5 } });
    out = await modeSweep(w, { low: 36, high: 44 });
    var wild = out.readings.filter(function (r) { return r.index === 40; })[0] || {};
    var to40 = w.writes.filter(function (p) { return p[0] === 40; });
    ok('an entry whose tries only get worse is played three times', wild.tries === 3, 'tries ' + wild.tries);
    ok('and keeps its best, the value it started at',
       wild.value === w.flash[40] && !!out.table && out.table[40] === w.flash[40],
       'kept ' + wild.value + ', started ' + w.flash[40]);
    ok('which is written back, so the mirror plays it until the run ends',
       to40.length === 3 && to40[2][1] === w.flash[40] && w.atModeOff.length === 1 &&
       w.atModeOff[0][40] === w.flash[40], JSON.stringify(to40));
    ok('and the entry is named as not converged',
       out.warnings.some(function (x) { return /cents out after 3 tries/.test(x); }), out.warnings.join(' | '));

    // Never past a neighbour.  Entry 30 here is a semitone flat and entry
    // 31 sits six counts above 29, so 30 has five counts of room: it moves
    // as far as the room allows and no further, and says so, and 31 - with
    // room above it - converges.
    var squeezed = B.pitchTable(B.expand({}), B.expand({})._calibration);
    squeezed[30] = squeezed[29] + 3; squeezed[31] = squeezed[29] + 6;
    w = modeWorld({ listening: 2, table: squeezed });
    out = await modeSweep(w, { low: 26, high: 34 });
    var at30 = out.readings.filter(function (r) { return r.index === 30; })[0] || {};
    ok('an entry never passes the neighbour above it, not even for a note',
       w.backwards.length === 0 && !!out.table && increasing(out.table, 0), JSON.stringify(w.backwards.slice(0, 2)));
    ok('it goes as far as the room allows', at30.value === squeezed[31] - 1, 'value ' + at30.value);
    ok('and is named as not converged', out.warnings.some(function (x) {
           return x.indexOf(PLAIN.noteLabel(30) + ': ') === 0 && /cents out after/.test(x);
       }),
       out.warnings.join(' | '));
    ok('while the neighbour with room converges',
       !!out.table && Math.abs(out.table[31] - w.ideal(31)) <= tolerance(w).counts,
       out.table ? (out.table[31] - w.ideal(31)).toFixed(2) + ' counts' : '-');

    // The anchor is the reference, so it is never moved - even when drift
    // makes its own sweep reading come back several cents off.
    w = modeWorld({ listening: 2, slope: 0, wobble: 0, drift: 1.5 });
    out = await modeSweep(w, { high: 10 });
    var a3 = out.readings.filter(function (r) { return r.index === 3; })[0] || {};
    ok('the anchor stays put when its own reading has drifted past half a count',
       Math.abs(a3.cents) > half && a3.value === w.flash[3] &&
       !w.writes.some(function (p) { return p[0] === 3; }),
       'read ' + (a3.cents === undefined ? '-' : a3.cents.toFixed(2)) + ' cents, ' + w.writes.length + ' writes');

    // --- the mode stays on while it is needed ------------------------------
    // An Auto search spends two notes on every channel that does not answer,
    // and the mode lapses five seconds after the last note on the one that
    // does: nine channels in, a single "on" would have left the run playing
    // through the pads.
    w = modeWorld({ listening: 9 });
    out = await modeSweep(w, { channel: null, high: 12 });
    ok('an Auto search finds the channel with the mode on throughout',
       out.channel === 9 && w.outside === 0 && closeness(w, out).counts <= tolerance(w).counts,
       'channel ' + out.channel + ', ' + w.outside + ' outside, worst ' + closeness(w, out).counts.toFixed(2));

    // A key pressed mid-run ends the mode.  The note it lands on is played
    // outside, 150 cents off, and costs that entry its tries; the next note
    // has the mode back, and every other entry converges as it would have.
    w = modeWorld({ listening: 2, keyAt: { 20: true } });
    out = await modeSweep(w, { high: 30 });
    var hit = w.keyNote - 21, rest = 0;
    (out.readings || []).forEach(function (r) {
        if (r.index !== hit && r.residual !== null && r.residual !== undefined) {
            rest = Math.max(rest, Math.abs(out.table[r.index] - w.ideal(r.index)));
        }
    });
    ok('a key press mid-run costs one note, not the rest of the run',
       w.outside === 1 && !!out.table && rest <= tolerance(w).counts,
       w.outside + ' outside, at entry ' + hit + '; the others worst ' + rest.toFixed(3) + ' counts');

    // --- and goes off however the run ends ---------------------------------
    w = modeWorld({ listening: 2 });
    err = null;
    try {
        await modeSweep(w, { high: 30, onNote: function (step, r, i) { if (i === 8) w.run.stop(); } });
    } catch (e) { err = e; }
    ok('Stop ends the mode and reloads the mirror',
       !!err && /Stopped/.test(err.message) && lastPairs(w, 2) === ENDED && !w.mode &&
       same(w.mirror, w.flash) && w.notesOn === 0, (err ? err.message : 'ran') + ' ' + lastPairs(w, 2));

    w = modeWorld({ listening: 2 });
    err = null;
    var writes = 0;
    try {
        await modeSweep(w, { high: 30, adjust: {
            table: w.flash.slice(), countsPerCent: countsPerCentOf(w.cfg),
            write: function (entry, value) {
                if (++writes === 5) return Promise.reject(new Error('port gone'));
                return M.writePitch(w.output, entry, value, { gap: 0 });
            } } });
    } catch (e) { err = e; }
    ok('an error mid-run ends the mode and reloads the mirror',
       !!err && err.message === 'port gone' && lastPairs(w, 2) === ENDED && !w.mode &&
       same(w.mirror, w.flash) && w.notesOn === 0, (err ? err.message : 'ran') + ' ' + lastPairs(w, 2));

    // The tab going away: pagehide, with a sweep note held and being read,
    // lets go of the note and ends the mode there and then.
    w = modeWorld({ listening: 2 });
    var atHide = null, reads = 0;
    w.onRead = function () {
        if (++reads !== 12) return;
        var held = w.notesOn;
        (w.listeners.pagehide || []).slice().forEach(function (f) { f(); });
        atHide = { held: held, pairs: lastPairs(w, 2), mode: w.mode, on: w.notesOn,
                   reloaded: same(w.mirror, w.flash), n: (w.listeners.pagehide || []).length };
        w.run.stop();
    };
    try { await modeSweep(w, { high: 30 }); } catch (e) { /* stopped */ }
    ok('pagehide lets go of the held note, ends the mode and reloads the mirror at once',
       !!atHide && atHide.held === 1 && atHide.on === 0 && atHide.pairs === ENDED && !atHide.mode &&
       atHide.reloaded, JSON.stringify(atHide));
    ok('and the listeners go when the run does', (w.listeners.pagehide || []).length === 0 &&
       (w.listeners.beforeunload || []).length === 0);

    // An audio input that cannot be opened stops the run before anything
    // goes out, the mode included: there is nothing to end.
    w = modeWorld({ listening: 2 });
    w.channels = 2;
    err = null;
    try { await modeSweep(w, { audioChannel: 11 }); } catch (e) { err = e; }
    ok('a run refused before its first note sends nothing, the mode included',
       !!err && w.sent.length === 0, (err ? err.message : 'ran') + ', ' + w.sent.length + ' sent');

    console.log(failures ? ('FAILED ' + failures) : 'ALL SWEEP DRIVER TESTS PASSED');
    if (failures) process.exit(1);
})().catch(function (e) { console.error('threw:', e && e.stack || e); process.exit(1); });
