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
        // Safari's input as it opens (the owner's runs, 2026-09-26/27):
        // `gapReads` reads that start with `gapSamples` exact zeros, and
        // reads `sharpFrom` to `sharpTo` (counted from 1) about 90 cents sharp.
        gapReads: opts.gapReads || 0, gapSamples: opts.gapSamples || 0,
        sharpFrom: opts.sharpFrom || 0, sharpTo: opts.sharpTo || 0, reads: 0,
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
                    w.reads++;
                    var gap = w.reads <= w.gapReads ? w.gapSamples : 0;
                    var sharp = w.reads >= w.sharpFrom && w.reads <= w.sharpTo ? Math.pow(2, 90 / 1200) : 1;
                    for (var i = 0; i < buf.length; i++) {
                        w.hz = (was && (was.whole || i < buf.length / 2) ? was.hz : now) * sharp;
                        if (i < gap) { buf[i] = 0; continue; }
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
    var P = 700, amp = wobble * P / (2 * Math.PI);  // local gain (1 + slope) +/- wobble
    var gainAt = opts.gainAt || {}, drift = opts.drift || 0;
    var outside = opts.outside === undefined ? 150 : opts.outside;
    // Near 0 V a 208 need not sit on the curve the rest of it follows:
    // `bend` cents at 0 counts, dying away over `bendSpan` counts.
    var bend = opts.bend || 0, bendSpan = opts.bendSpan || 100;
    function octaves(v) {
        return (v * (1 + slope) + amp * Math.sin(2 * Math.PI * v / P + 0.4)) / cpo +
               bend * Math.exp(-Math.max(0, v) / bendSpan) / 1200;
    }
    // The local gain against the ramp's, at its least and most over the DAC.
    w.gMin = Infinity; w.gMax = -Infinity;
    for (var gv = 0; gv < 4095; gv += 0.5) {
        var g = (octaves(gv + 0.5) - octaves(gv)) * cpo / 0.5;
        w.gMin = Math.min(w.gMin, g); w.gMax = Math.max(w.gMax, g);
    }
    // The 0 V pitch wandering by itself: refWander(k) cents on the k-th note
    // played at the 0 V entry in the mode, counted from 1.
    w.refPlays = 0;
    // The pitch entry e sounds at when it holds v.  gainAt makes one entry
    // answer a move that many times over: not a 208, but the way to make a
    // try that overshoots on purpose.  drift is cents a second.
    w.pitchOf = function (e, v) {
        var o = octaves(v);
        if (gainAt[e]) o = octaves(w.flash[e]) + (o - octaves(w.flash[e])) * gainAt[e];
        return F0 * Math.pow(2, o + drift * w.clock / 1000 / 1200);
    };
    // Where entry e is exactly in tune against the 208's 0 V pitch - the
    // 0 V entry at 0 counts - in counts: what a run is meant to get within
    // half a count of.
    w.ideal = function (e) {
        var want = w.pitchOf(w.shift, 0) * Math.pow(2, (e - w.shift) / 12), lo = 0, hi = 8000;
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
            if (w.mode && idx === w.shift && opts.refWander) extra += opts.refWander(++w.refPlays);
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
                        reference: w.shift,
                        write: function (entry, value) {
                            return M.writePitch(w.output, entry, value, { timers: timers });
                        } } };
    for (var k in opts) o[k] = opts[k];
    w.run = new C.Sweep(o);
    return w.run.run();
}

// A table already in tune with this 208, entry for entry, as a start.
function tunedTable(w) {
    return w.flash.map(function (v, e) { return e <= w.shift ? 0 : Math.round(w.ideal(e)); });
}
function startFrom(w, table) { w.flash = table.slice(); w.mirror = table.slice(); }

// How close a run came: the worst distance from in tune, in counts and in
// the residuals the run itself read, over the entries it tuned.  An entry
// filled in is not one it tuned.  (The run before the 0 V reference had
// no `source`, and every entry it heard counts.)
function closeness(w, out) {
    var r = { counts: Infinity, cents: Infinity, at: null, centsAt: null, heard: 0 };
    if (!out || !out.table || !out.readings) return r;
    r.counts = 0; r.cents = 0;
    out.readings.forEach(function (x) {
        if (x.residual === null || x.residual === undefined) return;
        if (x.source !== undefined && x.source !== 'measured' && x.source !== 'reference') return;
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
// cent for the estimator.  And in counts, whatever the run's reading of
// its reference is out by: every entry is tuned to that reading, so an
// error in it moves them all.  The reference is the 0 V entry now, 27 Hz
// here, where the estimator reads a few hundredths of a cent out - a few
// thousandths of a count past the bound, on the entry nearest it.
function tolerance(w, out) {
    var ref = out && typeof out.anchorHz === 'number' && out.anchorEntry === w.shift
        ? Math.abs(PLAIN.cents(out.anchorHz, w.pitchOf(w.shift, 0))) : 0;
    return { counts: 0.5 / w.gMin + 0.01 + ref * countsPerCentOf(w.cfg) / w.gMin,
             cents: 0.5 / countsPerCentOf(w.cfg) * w.gMax + 0.02, ref: ref };
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
function pageLoad(table, sources) {
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
    page.sources = sources;
    return vm.runInContext('clearBaseline(); loadPitchTable(table, was, "the tuned table", sources);' +
        '({ built: BUILDLIB.pitchTable(BUILDLIB.expand({ volts_per_octave: vpo, pitch_offset: pitchOffset }), rows()),' +
        '   cleared: measured.every(function (v) { return v === 0; }), ticked: $("useCal").checked,' +
        '   name: baselineName, sources: JSON.parse(JSON.stringify(baselineSources)) })', page);
}
// What the page hands loadPitchTable for a run: app.js's own runSources.
function sourcesOf(out, w) {
    var vm = require('vm'), page = vm.createContext({ CALIBRATE: PLAIN });
    vm.runInContext(appFunction('runSources'), page, { filename: 'web/app.js (extracted)' });
    if (typeof page.runSources !== 'function') return null;
    return page.runSources(out.readings, w.cfg.pitch.bottom_key_semitone);
}

// The page's own run, out of app.js: sweepInMode, handed the keyboard's read
// the way Start measuring hands it one, against this world's keyboard and
// 208, with the mode and the writes going out through SETTINGSMIDI.  The DOM,
// the page's load of a read and its card are stubbed, and what the run hands
// loadPitchTable is caught.  `pageVpo` is the page's volts per octave;
// `read`, whether Read settings has armed the card for this keyboard; and
// `channel`, the MIDI channel picked, null for Auto.  A load puts the
// keyboard's scaling on the page, as loadFromKeyboard's press does.
function pageRun(w, opts) {
    var vm = require('vm'), C = load(w), got = { out: null, err: null, playing: null };
    var timers = { setTimeout: function (fn, ms) { w.clock += ms || 0; return setTimeout(fn, 0); } };
    var page = vm.createContext({
        BUILDLIB: B, GEN: GEN, console: console,
        CALIBRATE: Object.assign({}, C, { Sweep: function (o) {
            got.playing = o.adjust && o.adjust.playing ? o.adjust.playing.slice() : null;
            var run = new C.Sweep(o), go = run.run.bind(run);
            run.run = function () { return go().then(function (out) { got.out = out; return out; }); };
            return run;
        } }),
        SETTINGSMIDI: { calibrationMode: M.calibrationMode, endCalibration: M.endCalibration,
                        writePitch: function (output, e, v) {
                            return M.writePitch(output, e, v, { timers: timers });
                        } }
    });
    vm.runInContext([
        'var vpo = ' + opts.pageVpo + ', sweep = null, TABLE_ENTRIES = 79, loaded = null, loads = 0;',
        'var unsent = { armed: ' + !!opts.read + ', name: ' +
            JSON.stringify(opts.read ? w.output.name : null) + ' };',
        'function $() { return {}; } function msg() {} function autoNote() {} function noteProgress() {}',
        'function withWarnings(note) { return note; }',
        'function press(id, v) { if (id === "vpo") vpo = Number(v); }',
        'function loadFromKeyboard(r) { loads++; press("vpo", BUILDLIB.pitchTableSettings(' +
            'r.fields.pitch_remap).volts_per_octave === 1.2 ? "1.2" : "1.0"); return ""; }',
        'function unsentArm(name) { unsent.armed = true; unsent.name = name; }',
        'function sweepOptions(chosen) { return { output: chosen, channel: ' +
            JSON.stringify(opts.channel === undefined ? w.listening : opts.channel) +
            ', deviceId: null, audioChannel: 0, velocity: 100 }; }',
        // As the page's: the scaling the table was built at onto the page.
        'function loadPitchTable(table, was, name, sources) {',
        '    press("vpo", was.volts_per_octave === 1.2 ? "1.2" : "1.0");',
        '    loaded = { table: table.slice(), was: was, name: name, sources: sources }; }',
        appFunction('runSources'), appFunction('runStart'), appFunction('sweepInMode')
    ].join('\n'), page, { filename: 'web/app.js (extracted)' });
    page.chosen = w.output;
    page.r = { fields: { pitch_remap: w.flash.slice() },
               identity: { firmwareVersion: GEN.version, imageMarker: 0 } };
    var ran;
    try { ran = Promise.resolve(vm.runInContext('sweepInMode(chosen, r)', page)); }
    catch (e) { ran = Promise.reject(e); }
    return ran.then(function () {}, function (e) { got.err = e; }).then(function () {
        got.loaded = page.loaded; got.loads = page.loads; got.vpo = page.vpo;
        return got;
    });
}
// Every pitch write that went out before the first note-on.
function writesBeforeFirstNote(w) {
    var n = 0;
    for (var k = 0; k < w.events.length && w.events[k][0] !== 'on'; k++) {
        if (w.events[k][0] === 'nrpn' && w.events[k][1] >= 0x80 && w.events[k][1] < 0x80 + 79) n++;
    }
    return n;
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

    // --- Safari's input as it opens ---------------------------------------
    // Every Safari run began with silence (zeros the estimator read as about
    // 5.3 kHz at clarity 1.00) and then read about 90 cents sharp from roughly
    // 3 to 10 s in.  The probe met both and failed, or anchored on the sharp
    // stretch.  With the warm-up the drone is watched until it has settled.
    var C = load(makeWorld({ listening: 0 }));
    var zeros = new Float32Array(4096), mixed = new Float32Array(32768);
    for (var z = 0; z < mixed.length; z++) mixed[z] = z < 12000 ? 0 : 0.25 * Math.sin(2 * Math.PI * 65.3 * z / RATE);
    ok('a window that starts with silence is no audio, not a 5 kHz tone',
       C.measure(mixed, RATE, 18, 6000).why === 'no audio' && C.measure(zeros, RATE, 18, 6000).why === 'no audio',
       JSON.stringify(C.measure(mixed, RATE, 18, 6000)));
    // Reads 1-2 start silent; reads 10 to 33 are sharp: with the 300 ms warm-up
    // steps that is 3 to 10 s in, as Safari did.
    function safari() { return makeWorld({ listening: 2, gapReads: 2, gapSamples: 12000, sharpFrom: 10, sharpTo: 33 }); }
    w = safari(); err = null;
    try { out = await sweep(w, { channel: 2, high: 20, warmupMs: 12000 }); } catch (e) { err = e; }
    heard = err ? [] : out.readings.filter(function (r) { return r.cents !== null; });
    ok('with the warm-up, a Safari-like input sweeps clean',
       !err && heard.length === out.readings.length && heard.every(function (r) { return Math.abs(r.cents) < 2; }) &&
       Math.abs(1200 * Math.log2(out.anchorHz / 32.703)) < 2,
       err ? err.message : heard.length + '/' + out.readings.length + ' anchored ' + out.anchorHz.toFixed(2));
    ok('and no note went out before the input had settled',
       w.sent.length > 0 && w.reads > 33, 'reads ' + w.reads);
    w = safari(); err = null;
    try { out = await sweep(w, { channel: 2, high: 20 }); } catch (e) { err = e; }
    heard = err ? [] : out.readings.filter(function (r) { return r.cents !== null && Math.abs(r.cents) < 2; });
    ok('without it, the same input does not (the fixture can fail)',
       !!err || heard.length < out.readings.length,
       err ? err.message.slice(0, 60) : heard.length + '/' + out.readings.length);

    // --- an input opened ahead of the run -----------------------------------
    // The page opens Safari's input as soon as it is picked (CALIBRATE.listen)
    // and hands it to the run, which waits out only what is left of the
    // warm-up and still wants two steady seconds.  The world here has
    // already settled: the sharp stretch went by while the page was open.
    function firstNote(w) {
        var send = w.output.send, at = { ms: null };
        w.clock = 0;
        w.output.send = function (m) {
            if (at.ms === null && (m[0] & 0xf0) === 0x90) at.ms = w.clock;
            return send(m);
        };
        return at;
    }
    async function warmRun(w, ageMs, extra) {
        var C = load(w);
        var input = await C.listen({ deviceId: null, audioChannel: 0 });
        input.since -= ageMs;
        if (extra) extra(input);
        var at = firstNote(w), opened = w.opened.length;
        var o = { output: w.output, channel: 2, deviceId: null, audioChannel: 0,
                  low: 3, high: 20, octaveTerm: false, velocity: 100,
                  warmupMs: 12000, input: input, phases: [] };
        o.onPhase = function (p) { o.phases.push(p); };
        var r = null, e = null;
        try { r = await new C.Sweep(o).run(); } catch (x) { e = x; }
        return { out: r, err: e, at: at.ms, input: input, reopened: w.opened.length > opened,
                 phases: o.phases };
    }
    var early = await warmRun(makeWorld({ listening: 2 }), 20000);
    heard = early.err ? [] : early.out.readings.filter(function (r) { return r.cents !== null && Math.abs(r.cents) < 2; });
    ok('an input opened 20 s before the run is listened through, not opened again',
       !early.err && !early.reopened && heard.length === early.out.readings.length,
       early.err ? early.err.message : 'reopened ' + early.reopened + ', ' + heard.length + '/' + early.out.readings.length);
    ok('and the first note goes out after two steady seconds, not twelve',
       early.at !== null && early.at >= 2000 && early.at < 4000, 'first note at ' + early.at + ' ms');
    ok('and the run leaves it open for the next one', !early.input.closed && early.input.fits({ deviceId: null, audioChannel: 0 }));
    ok('and it says what it is doing before the first note',
       early.phases.join(',') === 'settle,reference', early.phases.join(','));
    var fresh = await warmRun(makeWorld({ listening: 2 }), 3000);
    ok('one opened 3 s before waits out the other 9',
       !fresh.err && fresh.at >= 9000 && fresh.at < 11000, fresh.err ? fresh.err.message : 'first note at ' + fresh.at + ' ms');
    var ended = await warmRun(makeWorld({ listening: 2 }), 20000, function (input) {
        input.stream = { getAudioTracks: function () { return [{ readyState: 'ended' }]; },
                         getTracks: function () { return []; } };
    });
    ok('one whose track ended is not used: the run opens its own and waits the whole warm-up',
       !ended.err && ended.reopened && ended.at >= 12000, ended.err ? ended.err.message : 'reopened ' + ended.reopened + ', first note at ' + ended.at + ' ms');
    var elsewhere = await warmRun(makeWorld({ listening: 2, channels: 2 }), 20000, function (input) { input.want = 1; });
    ok('nor one on another channel', !elsewhere.err && elsewhere.reopened && elsewhere.at >= 12000,
       elsewhere.err ? elsewhere.err.message : 'reopened ' + elsewhere.reopened + ', first note at ' + elsewhere.at + ' ms');

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
    // on a 208 whose slope is 1.5% steep, whose gain wanders 3%, and whose
    // 0 V pitch sits 20 cents flat of the curve the rest of it follows.
    // The owner tunes the 208 at 0 V with the keyboard off, so that pitch is
    // the reference: entry 0 stays at 0 counts and every other entry is
    // tuned to it times 2^(e/12), the bottom C included.  Against the bottom
    // C, as the run used to tune, every key came out a few counts off the
    // pitch the trimmer set.
    w = modeWorld({ listening: 2, bend: -20 });
    var half = 0.5 / countsPerCentOf(w.cfg);
    err = null;
    try { out = await modeSweep(w, {}); } catch (e) { err = e; out = { readings: [], warnings: [], log: [] }; }
    ok('a run in calibration mode completes', !err, err ? err.message : '');
    var rd = out.readings;
    ok('calibration mode sweeps all 79 entries, notes 21 to 99, one apiece',
       rd.length === 79 && rd.every(function (r, i) { return r.index === i && r.note === i + 21; }),
       rd.length + ' readings, ' + (rd.length ? rd[0].note + '..' + rd[rd.length - 1].note : '-'));
    ok('and returns the table it converged', !!out.table && out.table.length === 79);
    var near = closeness(w, out), tol = tolerance(w, out);
    ok('and every entry is heard and tuned, none filled in',
       near.heard === 79 && rd.every(function (r) { return r.source === (r.index ? 'measured' : 'reference'); }) &&
       !!out.filled && Object.keys(out.filled).length === 0,
       near.heard + '/79, sources ' + rd.map(function (r) { return r.source; }).filter(function (s, i, a) {
           return a.indexOf(s) === i; }).join(','));
    ok('the 0 V entry is 0 counts in the table', !!out.table && out.table[0] === 0,
       out.table ? 'entry 0 = ' + out.table[0] : '-');
    ok('one run brings every tuned entry within half a count of the 0 V pitch times 2^(e/12) (' +
       tol.counts.toFixed(3) + ' of this 208’s counts)',
       near.counts <= tol.counts, 'worst ' + near.counts.toFixed(3) + ' counts, at entry ' + near.at +
       '; the 0 V pitch read ' + tol.ref.toFixed(3) + ' cents out');
    var at3 = rd.filter(function (r) { return r.index === 3; })[0] || {};
    ok('the bottom C, entry 3, is tuned with them and not held as the anchor',
       at3.source === 'measured' && !!out.table &&
       Math.abs(out.table[3] - w.ideal(3)) <= tol.counts && w.writes.some(function (p) { return p[0] === 3; }),
       out.table ? 'entry 3 = ' + out.table[3] + ', in tune at ' + w.ideal(3).toFixed(2) : '-');
    ok('with every residual ' + tol.cents.toFixed(2) + ' cents or less at 1.2 V/oct, half a count being ' +
       half.toFixed(2), near.cents <= tol.cents,
       'worst ' + near.cents.toFixed(3) + ' cents, at entry ' + near.centsAt);
    var start = Math.max.apply(null, rd.map(function (r) { return Math.abs(r.first || 0); }));
    ok('from a table that needed it', start > 50, 'the flat table read up to ' + start.toFixed(1) + ' cents out');
    ok('in at most three tries a note', rd.every(function (r) { return r.tries >= 1 && r.tries <= 3; }),
       rd.map(function (r) { return r.tries; }).join(''));
    // The 0 V entry is written once, to 0, before it is first heard.
    var zeroWrite = -1, zeroHeard = -1;
    w.events.forEach(function (ev, k) {
        if (zeroWrite < 0 && ev[0] === 'nrpn' && ev[1] === 0x80 && ev[2] === 0) zeroWrite = k;
        if (zeroHeard < 0 && ev[0] === 'on' && ev[1] === w.listening && ev[2] === 21) zeroHeard = k;
    });
    ok('the 0 V entry is written to 0 before it is first played, and never again',
       same(w.writes.filter(function (p) { return p[0] === 0; }), [[0, 0]]) &&
       zeroWrite >= 0 && zeroWrite < zeroHeard,
       JSON.stringify(w.writes.filter(function (p) { return p[0] === 0; })) + ', written at event ' +
       zeroWrite + ', first played at ' + zeroHeard);
    var anchors = out.log.filter(function (r) { return r.what === 'anchor'; });
    var firstSweep = out.log.map(function (r) { return r.what; }).indexOf('sweep');
    ok('the anchor is the 0 V entry, note 21, at every check, and says so',
       anchors.length > 2 && anchors.every(function (r) { return r.entry === 0 && r.note === 21; }) &&
       out.anchorEntry === 0,
       anchors.length + ' anchor readings at entries ' + anchors.map(function (r) { return r.entry; })
           .filter(function (s, i, a) { return a.indexOf(s) === i; }).join(',') + ', anchorEntry ' + out.anchorEntry);
    ok('and is read until it repeats before anything is tuned against it',
       out.log.slice(0, firstSweep).filter(function (r) { return r.what === 'anchor'; }).length >= 2);
    ok('its pitch is the 208’s own at 0 V',
       typeof out.anchorHz === 'number' && Math.abs(PLAIN.cents(out.anchorHz, w.pitchOf(0, 0))) < 0.5,
       (out.anchorHz || 0).toFixed(3) + ' Hz against ' + w.pitchOf(0, 0).toFixed(3));
    ok('a steady 0 V pitch raises no warning about it',
       !out.warnings.some(function (x) { return /0 V note/.test(x); }), out.warnings.join(' | '));
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
    ok('and through the page’s own rows, loaded as a baseline',
       !!conv12 && same(B.pitchTable(cfg12, pageRows), conv12));
    var loaded = null, src12 = sourcesOf(out, w);
    try { loaded = conv12 && pageLoad(conv12, src12); } catch (e) { loaded = { error: e.message }; }
    ok('and through the page’s own load of it: the build’s table is the converged table',
       !!loaded && !loaded.error && same(loaded.built, conv12) && loaded.cleared && loaded.ticked &&
       loaded.name === 'the tuned table', loaded && loaded.error ? loaded.error : '');
    ok('the page’s counts per cent are the ramp’s',
       typeof B.pitchCountsPerCent === 'function' &&
       Math.abs(B.pitchCountsPerCent(cfg12) - countsPerCentOf(cfg12)) < 1e-12 &&
       Math.abs(B.pitchCountsPerCent(cfg12) - 0.4006) < 0.001,
       typeof B.pitchCountsPerCent === 'function' ? String(B.pitchCountsPerCent(cfg12)) : 'missing');

    // 1 V/oct without the pitch offset: the three entries under the bottom
    // key sit at 0 V and are not offsets, so the 0 V entry is entry 3, the
    // bottom C, and the sweep starts there; a slope 1% shallow, where half
    // a count in cents is a little more than half a count of the 208; and a
    // 0 V pitch 15 cents sharp of its curve.
    w = modeWorld({ listening: 2, vpo: 1.0, offset: false, slope: -0.01, wobble: 0.02, bend: 15 });
    half = 0.5 / countsPerCentOf(w.cfg);
    out = await modeSweep(w, {});
    near = closeness(w, out); tol = tolerance(w, out);
    ok('at 1 V/oct without the offset, entries 3 to 78 are swept',
       out.readings.length === 76 && out.readings[0].index === 3 && near.heard === 76,
       out.readings.length + ' readings, ' + near.heard + ' heard');
    ok('against entry 3 at 0 counts, the 208’s 0 V pitch',
       !!out.table && out.table[3] === 0 && out.anchorEntry === 3 &&
       out.readings[0].source === 'reference' &&
       same(w.writes.filter(function (p) { return p[0] === 3; }), [[3, 0]]) &&
       Math.abs(PLAIN.cents(out.anchorHz, w.pitchOf(3, 0))) < 0.5,
       out.table ? 'entry 3 = ' + out.table[3] + ', anchorEntry ' + out.anchorEntry : '-');
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
    try { loaded = out.table && pageLoad(out.table, sourcesOf(out, w)); } catch (e) { loaded = { error: e.message }; }
    ok('and the page’s load of it builds it again, the offset switched off to match',
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

    // --- the keyboard's table at the other volts per octave -----------------
    // The guide has the page's volts per octave set to the 208's trim, and a
    // run tuned from the keyboard's table whatever its scaling: one at the
    // other scaling played the probe's two octaves 2000 or 2880 cents apart,
    // and the run said no MIDI channel was listening.  The page's own run,
    // against a keyboard at one scaling and a 208 at the other: both ways
    // round, without the offset, on Auto, and with nothing read, where the
    // run's read is loaded into the page first.
    var MISMATCH = [
        { trim: 1.0, table: 1.2, offset: true, channel: 2, read: true },
        { trim: 1.2, table: 1.0, offset: true, channel: 2, read: true },
        { trim: 1.0, table: 1.2, offset: false, channel: 2, read: true },
        { trim: 1.0, table: 1.2, offset: true, channel: null, read: true },
        { trim: 1.2, table: 1.0, offset: true, channel: 2, read: false }
    ];
    for (var mi = 0; mi < MISMATCH.length; mi++) {
        var mc = MISMATCH[mi];
        var what = 'a 208 at ' + mc.trim.toFixed(1) + ' V/oct, the keyboard’s table at ' +
            mc.table.toFixed(1) + (mc.offset ? '' : ', no offset') +
            (mc.channel === null ? ', on Auto' : '') + (mc.read ? '' : ', nothing read');
        w = modeWorld({ listening: 2, vpo: mc.trim, offset: mc.offset });
        var kcfg = B.expand({ volts_per_octave: mc.table, pitch_offset: mc.offset });
        var heldTable = B.pitchTable(kcfg, kcfg._calibration);
        startFrom(w, heldTable);
        var atFirstNote = null, sendOn = w.output.send;
        w.output.send = function (m) {
            if ((m[0] & 0xf0) === 0x90 && !atFirstNote) atFirstNote = w.mirror.slice();
            return sendOn(m);
        };
        var pr = await pageRun(w, { pageVpo: mc.trim, channel: mc.channel, read: mc.read });
        ok(what + ': the run tunes', !pr.err && !!pr.out, pr.err ? pr.err.message.slice(0, 100) : '');
        ok('  the flat table at the 208’s scaling is in the mirror before the probe’s first note',
           !!atFirstNote && same(atFirstNote, B.pitchTable(w.cfg, w.cfg._calibration)),
           atFirstNote ? 'entry 27 plays ' + atFirstNote[27] + ', the keyboard’s holds ' + heldTable[27] : 'no note');
        ok('  written without the table the keyboard plays ever running backwards',
           w.backwards.length === 0 && writesBeforeFirstNote(w) > 70, w.backwards.length +
           ' backwards, ' + writesBeforeFirstNote(w) + ' written before the first note');
        near = closeness(w, pr.out); tol = tolerance(w, pr.out);
        ok('  every tuned entry within half a count (' + tol.counts.toFixed(3) + ')',
           !!pr.out && near.heard >= 70 && near.counts <= tol.counts,
           'worst ' + near.counts.toFixed(3) + ' counts at entry ' + near.at + ', ' + near.heard + ' heard');
        ok('  the mode off and the keyboard’s own table back in the mirror at the end',
           lastPairs(w, 2) === ENDED && !w.mode && same(w.mirror, heldTable), lastPairs(w, 2));
        ok('  and the page takes the tuned table at the 208’s scaling',
           !!pr.loaded && pr.loaded.was.volts_per_octave === mc.trim &&
           pr.loaded.was.pitch_offset === mc.offset && !!pr.out && same(pr.loaded.table, pr.out.table) &&
           B.pitchTableSettings(pr.loaded.table).volts_per_octave === mc.trim,
           pr.loaded ? JSON.stringify(pr.loaded.was) : 'nothing loaded');
        if (!mc.read) {
            ok('  the run’s read is loaded as a read, and the page keeps its volts per octave',
               pr.loads === 1 && pr.vpo === mc.trim, pr.loads + ' load(s), the page at ' + pr.vpo);
        }
    }
    // At the same scaling the keyboard's table is the start, and nothing is
    // written before the probe.
    w = modeWorld({ listening: 2 });
    var pm = await pageRun(w, { pageVpo: 1.2, read: true });
    ok('a keyboard at the page’s scaling is tuned from its own table, nothing written before the probe',
       !pm.err && pm.playing === null && writesBeforeFirstNote(w) === 0 &&
       !!pm.loaded && pm.loaded.was.volts_per_octave === 1.2, pm.err ? pm.err.message.slice(0, 100) : '');
    // A run that stops before its first note, with nothing read: the page
    // holds what the keyboard does, at the volts per octave it was set to,
    // and nothing went to the keyboard.
    w = modeWorld({ listening: 2, vpo: 1.0 });
    startFrom(w, B.pitchTable(B.expand({ volts_per_octave: 1.2 }), B.expand({})._calibration));
    w.silent = true;
    var pf = await pageRun(w, { pageVpo: 1.0, read: false });
    ok('a run that stops before its first note leaves the read loaded and the page’s volts per octave',
       !!pf.err && /Nothing usable/.test(pf.err.message) && pf.loads === 1 && pf.vpo === 1.0 &&
       w.writes.length === 0 && !w.mode, (pf.err ? pf.err.message.slice(0, 60) : 'ran') + ', ' +
       pf.loads + ' load(s), the page at ' + pf.vpo + ', ' + w.writes.length + ' written');

    // --- what is filled in, and what is left alone ---------------------------
    // A note that is not heard is not written at all, and is not kept at the
    // value it came with either: after the run it is filled in from the
    // tuned entries on either side, linearly in counts.
    var hush = {}; hush[41] = true; hush[42] = true;       // entries 20 and 21
    w = modeWorld({ listening: 2, mute: hush });
    out = await modeSweep(w, { high: 26 });
    var lost = out.readings.filter(function (r) { return r.index === 20 || r.index === 21; });
    var t = out.table || [];
    ok('two notes not heard mid-range are filled in between their tuned neighbours',
       lost.length === 2 && lost.every(function (r) {
           var k = r.index - 19, want = Math.round(t[19] + (t[22] - t[19]) * k / 3);
           return r.cents === null && r.source === 'interpolated' && r.value === want &&
                  t[r.index] === want && want !== w.flash[r.index];
       }) && !!out.filled && out.filled[20] === 'interpolated' && out.filled[21] === 'interpolated',
       JSON.stringify(lost.map(function (r) { return [r.index, r.source, r.value, w.flash[r.index]]; })));
    ok('and are never written', !w.writes.some(function (p) { return p[0] === 20 || p[0] === 21; }));
    ok('and say so, in the warnings and in the log',
       out.warnings.some(function (x) { return x === PLAIN.noteLabel(20) + ': too quiet to read, interpolated'; }) &&
       same(out.log.filter(function (r) { return r.what === 'fill'; })
                   .map(function (r) { return [r.entry, r.why, r.value]; }),
            [[20, 'interpolated', t[20]], [21, 'interpolated', t[21]]]),
       out.warnings.join(' | '));
    ok('while the notes around them still converge', closeness(w, out).counts <= tolerance(w, out).counts,
       closeness(w, out).counts.toFixed(3));

    // The closest try is the one kept.  Entry 40 here answers a move two and
    // a half times over, so each correction overshoots further than the
    // last: the first value, never moved, was the closest, and it is
    // written back.  It started 2 counts sharp of a table in tune, about 5
    // cents: out by more than half a count, and within the 10 that keeps it.
    // (In tune as the same 208 without the overshoot: the overshoot pivots
    // on the value the entry starts at, so it has to be set first.)
    w = modeWorld({ listening: 2, gainAt: { 40: 2.5 } });
    var nearly = tunedTable(modeWorld({ listening: 2 })); nearly[40] += 2; startFrom(w, nearly);
    out = await modeSweep(w, { low: 36, high: 44 });
    var wild = out.readings.filter(function (r) { return r.index === 40; })[0] || {};
    var to40 = w.writes.filter(function (p) { return p[0] === 40; });
    ok('an entry whose tries only get worse is played three times', wild.tries === 3, 'tries ' + wild.tries);
    ok('and keeps its best, the value it started at, within ' + PLAIN.FILL_CENTS + ' cents',
       wild.value === w.flash[40] && !!out.table && out.table[40] === w.flash[40] &&
       wild.source === 'measured' && Math.abs(wild.residual) > half && Math.abs(wild.residual) <= 10,
       'kept ' + wild.value + ', started ' + w.flash[40] + ', ' + (wild.residual || 0).toFixed(1) + ' cents');
    ok('which is written back, so the mirror plays it until the run ends',
       to40.length === 3 && to40[2][1] === w.flash[40] && w.atModeOff.length === 1 &&
       w.atModeOff[0][40] === w.flash[40], JSON.stringify(to40));
    ok('and the entry is named as not converged, and kept',
       out.warnings.some(function (x) { return /^\S+: \d+\.\d cents out after 3 tries$/.test(x); }),
       out.warnings.join(' | '));

    // Never past a neighbour.  Entry 30 here is a semitone flat and entry
    // 31 sits six counts above 29, so 30 has five counts of room: it moves
    // as far as the room allows and no further, and 31 - with room above
    // it - converges.  Still tens of cents out, 30 is then filled in
    // between 29 and the 31 it could not pass.
    var squeezed = B.pitchTable(B.expand({}), B.expand({})._calibration);
    squeezed[30] = squeezed[29] + 3; squeezed[31] = squeezed[29] + 6;
    w = modeWorld({ listening: 2, table: squeezed });
    out = await modeSweep(w, { low: 26, high: 34 });
    var at30 = out.readings.filter(function (r) { return r.index === 30; })[0] || {};
    var to30 = w.writes.filter(function (p) { return p[0] === 30; }).map(function (p) { return p[1]; });
    ok('an entry never passes the neighbour above it, not even for a note',
       w.backwards.length === 0 && !!out.table && increasing(out.table, 0), JSON.stringify(w.backwards.slice(0, 2)));
    ok('it goes as far as the room allows', Math.max.apply(null, to30) === squeezed[31] - 1,
       'played at ' + to30.join(','));
    ok('and is named as not converged, and filled in', out.warnings.some(function (x) {
           return x.indexOf(PLAIN.noteLabel(30) + ': ') === 0 && /cents out after \d tries, interpolated$/.test(x);
       }),
       out.warnings.join(' | '));
    ok('between 29 and the 31 it could not pass',
       at30.source === 'interpolated' && !!out.table &&
       out.table[30] === Math.round((out.table[29] + out.table[31]) / 2) && increasing(out.table, 0),
       out.table ? out.table.slice(29, 32).join(',') : '-');
    ok('while the neighbour with room converges',
       !!out.table && Math.abs(out.table[31] - w.ideal(31)) <= tolerance(w, out).counts,
       out.table ? (out.table[31] - w.ideal(31)).toFixed(2) + ' counts' : '-');

    // The 0 V entry is the reference, so it is never moved - even when
    // drift makes its own sweep reading come back several cents off.
    w = modeWorld({ listening: 2, slope: 0, wobble: 0, drift: 3 });
    out = await modeSweep(w, { high: 10 });
    var a0 = out.readings.filter(function (r) { return r.index === 0; })[0] || {};
    ok('the 0 V entry stays at 0 when its own reading has drifted past half a count',
       Math.abs(a0.cents) > half && a0.value === 0 && a0.source === 'reference' && out.table[0] === 0 &&
       same(w.writes.filter(function (p) { return p[0] === 0; }), [[0, 0]]),
       'read ' + (a0.cents === undefined ? '-' : a0.cents.toFixed(2)) + ' cents, ' + w.writes.length + ' writes');
    var checks = out.log.filter(function (r) { return r.what === 'anchor'; });
    ok('and the drift checks read it, and follow the drift',
       checks.length >= 3 && checks.every(function (r) { return r.entry === 0; }) && out.drift > 10 &&
       out.anchorEntry === 0, checks.length + ' checks, drift ' + out.drift.toFixed(1) + ' cents');

    // --- both ends of the range ---------------------------------------------
    // From a table in tune, with four entries that will not tune: entry 1
    // silent, entry 2 answering a move two and a half times over, 30 cents
    // sharp, and the same at the top, 77 and 78.  None is kept at a value
    // it was played at or started from: the bottom pair is interpolated
    // between the 0 V entry's 0 counts and entry 3, the top pair carries on
    // at the slope of the octave under 76.
    var ends = {}; ends[22] = true; ends[99] = true;
    w = modeWorld({ listening: 2, bend: -20, mute: ends, gainAt: { 2: 2.5, 77: 2.5 } });
    var edged = tunedTable(modeWorld({ listening: 2, bend: -20 }));
    edged[1] += 6; edged[2] += 12; edged[77] += 12; edged[78] += 20;
    startFrom(w, edged);
    out = await modeSweep(w, {});
    t = out.table || [];
    var byEntry = {};
    out.readings.forEach(function (r) { byEntry[r.index] = r; });
    var played = function (e) {
        return [w.flash[e]].concat(w.writes.filter(function (p) { return p[0] === e; })
                                           .map(function (p) { return p[1]; }));
    };
    var endWant = {
        1: Math.round(t[3] / 3), 2: Math.round(2 * t[3] / 3),
        77: Math.round(t[76] + (t[76] - t[64]) / 12), 78: Math.round(t[76] + 2 * (t[76] - t[64]) / 12)
    };
    ok('entries that will not tune at both ends are filled in, not kept',
       [1, 2, 77, 78].every(function (e) {
           var r = byEntry[e] || {};
           return t[e] === endWant[e] && r.value === t[e] && r.cents === null && played(e).indexOf(t[e]) < 0;
       }),
       [1, 2, 77, 78].map(function (e) {
           return e + ': ' + t[e] + ' (want ' + endWant[e] + ', played ' + played(e).join('/') + ')';
       }).join('; '));
    ok('the bottom pair interpolated from 0 V, the top pair extrapolated, and flagged so',
       !!out.filled && same(out.filled, { 1: 'interpolated', 2: 'interpolated', 77: 'extrapolated', 78: 'extrapolated' }) &&
       [1, 2].every(function (e) { return byEntry[e] && byEntry[e].source === 'interpolated'; }) &&
       [77, 78].every(function (e) { return byEntry[e] && byEntry[e].source === 'extrapolated'; }),
       JSON.stringify(out.filled));
    ok('with a warning each that ends in what became of it',
       [[1, /^\S+: too quiet to read, interpolated$/], [2, /^\S+: -?\d+\.\d cents out after \d tries, interpolated$/],
        [77, /^\S+: -?\d+\.\d cents out after \d tries, extrapolated$/], [78, /^\S+: too quiet to read, extrapolated$/]]
           .every(function (p) {
               return out.warnings.some(function (x) {
                   return x.indexOf(PLAIN.noteLabel(p[0]) + ': ') === 0 && p[1].test(x);
               });
           }), out.warnings.join(' | '));
    ok('and a fill row each in the log',
       same(out.log.filter(function (r) { return r.what === 'fill'; })
                   .map(function (r) { return [r.entry, r.why]; }),
            [[1, 'interpolated'], [2, 'interpolated'], [77, 'extrapolated'], [78, 'extrapolated']]));
    ok('the silent ones are never written, and the table stays increasing inside the DAC',
       !w.writes.some(function (p) { return p[0] === 1 || p[0] === 78; }) &&
       increasing(t, 0) && t[0] === 0 && t[78] <= 4095);
    ok('while every other entry is tuned',
       closeness(w, out).heard === 75 && closeness(w, out).counts <= tolerance(w, out).counts,
       closeness(w, out).heard + ' tuned, worst ' + closeness(w, out).counts.toFixed(3));
    var srcEnds = sourcesOf(out, w);
    loaded = null;
    try { loaded = out.table && pageLoad(out.table, srcEnds); } catch (e) { loaded = { error: e.message }; }
    ok('the page loads it exactly, and its saved table says which rows were filled in',
       !!loaded && !loaded.error && same(loaded.built, out.table) &&
       loaded.sources[1] === 'interpolated' && loaded.sources[2] === 'interpolated' &&
       loaded.sources[77] === 'extrapolated' && loaded.sources[78] === 'extrapolated' &&
       loaded.sources[3] === 'measured' && loaded.sources[0] === undefined,
       loaded && loaded.error ? loaded.error : JSON.stringify(loaded && loaded.sources));

    // --- the top of the 208 -------------------------------------------------
    // Nothing sounds from entry 72 up.  Three notes in a row not heard is
    // the top: the sweep stops there, plays nothing above, and the rest are
    // extrapolated from the octave under the last entry that tuned.
    var topless = {};
    for (var tn = 72 + 21; tn <= 99; tn++) topless[tn] = true;
    w = modeWorld({ listening: 2, mute: topless });
    startFrom(w, tunedTable(w));
    out = await modeSweep(w, {});
    t = out.table || [];
    var sweptNotes = {};
    w.events.forEach(function (ev) { if (ev[0] === 'on' && ev[1] === w.listening) sweptNotes[ev[2]] = true; });
    ok('three silent entries at the top stop the sweep: nothing above them is played',
       !!sweptNotes[95] && !sweptNotes[96] && Object.keys(sweptNotes).length === 75,
       Object.keys(sweptNotes).length + ' notes played, of 79, the highest ' +
       Math.max.apply(null, Object.keys(sweptNotes).map(Number)));
    var slope71 = (t[71] - t[59]) / 12, topOk = true;
    for (var te = 72; te <= 78; te++) {
        var tr = out.readings.filter(function (r) { return r.index === te; })[0] || {};
        if (tr.source !== 'extrapolated' || t[te] !== Math.round(t[71] + slope71 * (te - 71)) ||
            tr.tries !== (te <= 74 ? 1 : 0)) topOk = false;
    }
    ok('and every entry from the first silent one up is extrapolated from the octave under 71',
       topOk && out.readings.length === 79 && increasing(t, 0) && t[78] <= 4095,
       t.slice(70).join(','));
    ok('which the warnings say',
       out.warnings.indexOf('three notes in a row were not heard, so the sweep stopped: ' +
                            PLAIN.noteLabel(75) + ' to ' + PLAIN.noteLabel(78) + ' extrapolated') >= 0 &&
       out.warnings.some(function (x) { return x === PLAIN.noteLabel(72) + ': too quiet to read, extrapolated'; }),
       out.warnings.join(' | '));
    ok('and the run still ends with the mode off and the mirror reloaded',
       lastPairs(w, 2) === ENDED && !w.mode && same(w.mirror, w.flash) && w.notesOn === 0);

    // Not at the bottom: three silent entries just above 0 V are not the top
    // of anything, and the sweep carries on past them.  Without the pitch
    // offset, so the probe's bottom C is the 0 V entry and still sounds.
    var lowless = {}; lowless[25] = true; lowless[26] = true; lowless[27] = true;
    w = modeWorld({ listening: 2, vpo: 1.0, offset: false, mute: lowless });
    startFrom(w, tunedTable(w));
    out = await modeSweep(w, { high: 20 });
    ok('three silent entries just above 0 V do not end the sweep',
       out.readings.length === 18 && out.readings.every(function (r) { return r.tries >= 1; }) &&
       !!out.filled && same(out.filled, { 4: 'interpolated', 5: 'interpolated', 6: 'interpolated' }),
       JSON.stringify(out.filled) + ', ' + out.readings.length + ' readings');

    // --- the 0 V reference has to repeat ------------------------------------
    // The 0 V pitch reading 6 cents sharp from the first drift check on:
    // the run goes on, and says so first.
    w = modeWorld({ listening: 2, refWander: function (k) { return k > 3 ? 6 : 0; } });
    startFrom(w, tunedTable(w));
    out = await modeSweep(w, { high: 30 });
    ok('a 0 V pitch that moves more than a few cents through the run is warned about first',
       /^the 0 V note moved \d+\.\d cents over the run$/.test(out.warnings[0] || ''),
       out.warnings.join(' | '));
    // One that never reads the same twice is no reference at all.
    w = modeWorld({ listening: 2, refWander: function (k) { return k % 2 ? 0 : 60; } });
    err = null;
    try { await modeSweep(w, {}); } catch (e) { err = e; }
    ok('a 0 V pitch that does not repeat stops the run before anything is tuned against it',
       !!err && /^The 0 V note did not come back as a steady tone \(it did not repeat: [\d., ]+ Hz\)/.test(err.message) &&
       same(w.writes, [[0, 0]]) && lastPairs(w, 2) === ENDED && same(w.mirror, w.flash),
       err ? err.message : 'it ran');

    // --- filling in, on its own ---------------------------------------------
    var FG = PLAIN.fillGaps, ramp = [];
    for (var fe = 0; fe < 79; fe++) ramp.push(40 * fe);
    var bent = ramp.slice(); bent[4] = 163;
    var fg = FG ? FG(bent, [2, 3], 0) : null;
    ok('fillGaps: between two entries that stand, linearly in counts',
       !!fg && fg.table[2] === 81 && fg.table[3] === 122 && same(fg.sources, { 2: 'interpolated', 3: 'interpolated' }) &&
       same(fg.table.slice(4), bent.slice(4)), fg ? fg.table.slice(0, 5).join(',') : 'missing');
    var steep = ramp.slice(); steep[75] = 3060;          // the last octave: (3060 - 2520) / 12 = 45 a semitone
    fg = FG ? FG(steep, [76, 77, 78], 0) : null;
    ok('fillGaps: above the last that stands, at the slope of its last octave',
       !!fg && fg.table.slice(76).join() === '3105,3150,3195' && fg.sources[78] === 'extrapolated',
       fg ? fg.table.slice(75).join(',') : 'missing');
    fg = FG ? FG([0, 30, 70].concat(ramp.slice(3)), ramp.map(function (v, e) { return e; }).slice(3), 0) : null;
    ok('fillGaps: with less than an octave standing above the floor, over what there is',
       !!fg && fg.table.slice(3, 6).join() === '105,140,175' && fg.sources[78] === 'extrapolated',
       fg ? fg.table.slice(0, 6).join(',') : 'missing');
    var nearTop = ramp.slice(); nearTop[62] = 3000; nearTop[74] = 4000;
    fg = FG ? FG(nearTop, [75, 76, 77, 78], 0) : null;
    ok('fillGaps: kept strictly increasing and inside the DAC',
       !!fg && fg.table.slice(74).join() === '4000,4083,4093,4094,4095', fg ? fg.table.slice(74).join(',') : 'missing');
    fg = FG ? FG(ramp, ramp.map(function (v, e) { return e; }).slice(1), 0) : null;
    ok('fillGaps: with nothing standing above the floor, nothing is filled',
       !!fg && same(fg.table, ramp) && same(fg.sources, {}));
    var c208 = [0, 0, 0, 0].concat(ramp.slice(1, 76));
    fg = FG ? FG(c208, [1, 2, 3, 5], 3) : null;
    ok('fillGaps: the floor and everything under it stand',
       !!fg && same(fg.table.slice(0, 5), [0, 0, 0, 0, 40]) && fg.table[5] === 80 && same(fg.sources, { 5: 'interpolated' }),
       fg ? fg.table.slice(0, 6).join(',') + ' ' + JSON.stringify(fg.sources) : 'missing');

    // --- the mode stays on while it is needed ------------------------------
    // An Auto search spends two notes on every channel that does not answer,
    // and the mode lapses five seconds after the last note on the one that
    // does: nine channels in, a single "on" would have left the run playing
    // through the pads.
    w = modeWorld({ listening: 9 });
    out = await modeSweep(w, { channel: null, high: 12 });
    ok('an Auto search finds the channel with the mode on throughout',
       out.channel === 9 && w.outside === 0 && closeness(w, out).counts <= tolerance(w, out).counts,
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
       w.outside === 1 && !!out.table && rest <= tolerance(w, out).counts,
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
            table: w.flash.slice(), countsPerCent: countsPerCentOf(w.cfg), reference: w.shift,
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
