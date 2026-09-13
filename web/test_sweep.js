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
        noiseOnly: !!opts.noiseOnly,        // hears the room, not the 208
        hz: 130.81, sent: [], notesOn: 0
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
                    w.hz = 32.703 * Math.pow(2, (note - 24 + w.error / 100) / 12);
                }
            } else if (status === 0x80) { w.notesOn--; }
        }
    };
    return w;
}

function load(w) {
    var root = {};
    root.setTimeout = function (fn) { return setTimeout(fn, 0); };
    root.addEventListener = function () {};
    root.removeEventListener = function () {};
    root.navigator = {
        requestMIDIAccess: function () {
            return Promise.resolve({
                outputs: { forEach: function (f) { f(w.output); } }
            });
        },
        mediaDevices: {
            enumerateDevices: function () { return Promise.resolve([]); },
            getUserMedia: function () {
                return Promise.resolve({
                    getAudioTracks: function () {
                        return [{ getSettings: function () { return { channelCount: 1 }; },
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
                    for (var i = 0; i < buf.length; i++) {
                        buf[i] = w.noiseOnly
                            // A wandering hum plus hiss: periodic enough to be
                            // found, nowhere near steady enough to be a tone.
                            ? 0.02 * Math.sin(2*Math.PI*1554*i/RATE +
                                              3*Math.sin(2*Math.PI*7*i/RATE))
                              + 0.02 * (rnd() - 0.5)
                            : 0.25 * Math.sin(2*Math.PI*w.hz*i/RATE)
                              + 0.08 * Math.sin(4*Math.PI*w.hz*i/RATE);
                    }
                }
            };
        };
        this.createMediaStreamSource = function () { return { connect: function () {} }; };
        this.createChannelSplitter = function () { return { connect: function () {} }; };
        this.close = function () { return Promise.resolve(); };
    };
    // calibrate.js takes `window` when there is one; naming the parameter
    // window is enough to hand it ours.
    return new Function('window', SRC + '\nreturn window.CALIBRATE;')(root);
}

function sweep(w, opts) {
    var C = load(w);
    var o = { output: w.output, channel: 0, deviceId: null, audioChannel: 0,
              low: 3, high: 67, octaveTerm: false, velocity: 100 };
    for (var k in opts) o[k] = opts[k];
    return new C.Sweep(o).run();
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

    console.log(failures ? ('FAILED ' + failures) : 'ALL SWEEP DRIVER TESTS PASSED');
    if (failures) process.exit(1);
})().catch(function (e) { console.error('threw:', e && e.stack || e); process.exit(1); });
