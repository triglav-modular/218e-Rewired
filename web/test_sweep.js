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
        // Notes that make no sound at all - a dropout.  The oscillator is
        // silent while they are held, and sounds again on the next note.
        mute: opts.mute || {},
        // Notes the instrument ignores - the note-on never takes, so the
        // drone holds whatever it was already playing.  This is the shape
        // that used to be warned about and then folded in at about -100
        // cents, because -100 is inside the +/-120 the threshold allows.
        deaf: opts.deaf || {},
        // What the audio interface really has, against what an "ideal"
        // request negotiates out of it.  A desk that hands over two for
        // "ideal" and twelve for "exact: 12" is the Model 12 shape.
        channels: opts.channels || 1,
        idealGives: opts.idealGives || null,
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
                    if (!w.deaf[note]) {
                        w.hz = 32.703 * Math.pow(2, (note - 24 + w.error / 100) / 12);
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
                    for (var i = 0; i < buf.length; i++) {
                        buf[i] = w.noiseOnly
                            // A wandering hum plus hiss: periodic enough to be
                            // found, nowhere near steady enough to be a tone.
                            ? 0.02 * Math.sin(2*Math.PI*1554*i/RATE +
                                              3*Math.sin(2*Math.PI*7*i/RATE))
                              + 0.02 * (rnd() - 0.5)
                            : w.quiet
                            // A dropout is not silence on the wire, it is a
                            // level the sweep must refuse to read: this sits
                            // an order of magnitude under MIN_RMS.
                            ? 0.002 * (rnd() - 0.5)
                            : 0.25 * Math.sin(2*Math.PI*w.hz*i/RATE)
                              + 0.08 * Math.sin(4*Math.PI*w.hz*i/RATE);
                    }
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

    // --- a note that did not take is blank, not a -100 cent reading ------
    // The instrument ignores one note-on, so the drone holds the note before
    // it.  That reads about a semitone flat - just inside the +/-120 the
    // threshold allows - so it used to be warned about and then kept, and
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

    console.log(failures ? ('FAILED ' + failures) : 'ALL SWEEP DRIVER TESTS PASSED');
    if (failures) process.exit(1);
})().catch(function (e) { console.error('threw:', e && e.stack || e); process.exit(1); });
