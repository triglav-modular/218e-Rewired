// Measuring the 208 automatically: drive the keyboard over MIDI, listen on an
// audio input, and fill in the cents boxes the calibration section already has.
//
// Nothing here talks to the instrument in any private way.  The 218e's own
// MIDI input plays notes - the factory handler at 0x80006400 maps a received
// note to a key, reads the live key table for its raw pitch and hands that to
// the same pitch remap a finger would - so a swept note exercises exactly the
// table entry this page is about to correct.  No SysEx, no DFU, no firmware
// change, and any MIDI source can do it; Ableton playing C0 to E5 is the same
// thing by hand.
//
// What the firmware does with a received note:
//
//   key    = note - 24, clamped at zero
//   raw    = key_table[key]             (the live table at RAM 0x854)
//   raw   -= 484                        unless add-to-pitch is on OCTAVE
//
// The sweep assumes that subtraction, so add-to-pitch must be OFF - not its
// middle position.  Both leave state+0x342 zero and so both subtract, but the
// middle position is the one that adds the active pad's preset voltage to the
// pitch, which would transpose the whole run by however that pad is set.  Off
// adds neither term.
//   index  = (raw + 120) * 12 / 484     integer divide, clamped at 0x4d
//   output = table[index], interpolated towards table[index+1] by the remainder
//
// Which is why notes 24 to 88 are the sweep: they land on table entries 3 to
// 67 exactly, one apiece, and those are the 65 notes this page calls C0 to E5
// and asks for offsets on.
(function (root) {
    'use strict';

    // --- the firmware's own numbers -------------------------------------
    // The factory key table, read out of 218eV3_v369_DFU.hex at 0x80016574 -
    // keys 0 to 64, the span notes 24 to 88 reach.
    var KEY_TABLE = [
        485, 525, 566, 606, 647, 687, 727, 768, 808, 848, 889, 929, 969,
        1010, 1050, 1090, 1131, 1171, 1211, 1252, 1292, 1332, 1373, 1413,
        1453, 1494, 1534, 1574, 1615, 1655, 1695, 1736, 1776, 1817, 1857,
        1897, 1938, 1978, 2018, 2059, 2099, 2139, 2180, 2220, 2260, 2301,
        2341, 2381, 2422, 2462, 2502, 2543, 2583, 2623, 2664, 2704, 2744,
        2785, 2825, 2865, 2906, 2946, 2987, 3027, 3067];
    var FIRST_NOTE = 24;            // note 24 is key 0, the bottom C
    var UNITS_OCTAVE = 484;         // raw pitch units in an octave
    var REMAP_ADD = 120;            // three semitones, added before the lookup
    var TOP_INDEX = 0x4d;           // where the remap clamps

    // Which table entry a note excites, and how far it leans on the next one.
    // This is the remap's integer arithmetic rather than an approximation of
    // it: one raw unit is 2.5 cents of output, so rounding it here would be a
    // reading attributed to the wrong entry.
    //
    // The lean is never more than 3.4% of the gap to the neighbour, and under
    // 2% for 53 of the 65 notes - the key table steps 40.33 units where a table
    // entry is 40.33 wide, and only every third one divides exactly.  Round to
    // the entry it sits on: 0.083 cents of the neighbour comes with it at
    // worst, well under the 2.5 cent step the table is quantised to anyway.
    function entryFor(note, octaveTerm) {
        var key = note - FIRST_NOTE;
        if (key < 0 || key >= KEY_TABLE.length) return null;
        var raw = KEY_TABLE[key] - (octaveTerm ? 0 : UNITS_OCTAVE);
        var u = raw + REMAP_ADD;
        if (u < 0) return null;
        var q = Math.floor(u * 12 / UNITS_OCTAVE);
        var lean = (u * 12 - q * UNITS_OCTAVE) / UNITS_OCTAVE;
        if (lean > 0.5) { q += 1; lean -= 1; }     // it is that entry, just short
        if (q > TOP_INDEX) return null;
        return { note: note, index: q, lean: lean };
    }

    // The two numberings, and the conversion between them.
    //
    // Everything in this file counts in firmware table entries: the bottom key
    // excites entry 3, because REMAP_ADD is three semitones and no option moves
    // it.  The page counts in calibration semitones, where the bottom key sits
    // at `bottomKeySemitone` - 3 on a 208, 208r or 208p, 0 on a 208c - and
    // BUILDLIB.pitchTable lays the curve out to match, putting semitone s at
    // entry s + (3 - bottomKeySemitone).
    //
    // With the pitch offset on the two coincide, which is why a year of use
    // never showed this.  With it off, every reading the sweep took was filed
    // three rows above the key it came from: the bottom C's correction landed
    // on the D# a minor third up, the top three rows were never measured, and
    // the curve still validated, so the page reported a clean build.
    var BOTTOM_KEY_ENTRY = 3;
    function semitoneFor(entry, bottomKeySemitone) {
        return entry - BOTTOM_KEY_ENTRY + bottomKeySemitone;
    }
    function entryForSemitone(semitone, bottomKeySemitone) {
        return semitone - bottomKeySemitone + BOTTOM_KEY_ENTRY;
    }

    // The sweep, as a list.  Built from the arithmetic rather than written
    // out, so a changed assumption cannot leave a stale table behind.
    //
    // The bounds are table entries, not the page's semitones - they were named
    // `lowSemitone`/`highSemitone` while being compared against `e.index`, and
    // that is the name the caller believed.
    function plan(lowEntry, highEntry, octaveTerm) {
        var out = [], seen = {};
        for (var note = FIRST_NOTE; note < FIRST_NOTE + KEY_TABLE.length; note++) {
            var e = entryFor(note, octaveTerm);
            if (!e || e.index < lowEntry || e.index > highEntry) continue;
            if (seen[e.index]) continue;
            seen[e.index] = true;
            out.push(e);
        }
        return out;
    }

    // --- calibration mode --------------------------------------------------
    // Firmware with calibration mode plays the table itself.  NRPN 0x3f05
    // with 0x2a2a turns it on (SETTINGSMIDI.calibrationMode), and while it
    // is on the pitch output is exactly mirror[note - 21] for the last
    // note-on on the instrument's channel, clamped to entries 0..78.  None
    // of the arithmetic above applies: no key table, no remap and no lean,
    // and nothing added - no transposition, pads, tuning slot, glide or
    // vibrato - with the arpeggiator and the sequencer silent.  So every one
    // of the 79 entries is reachable, notes 21 to 99, one apiece, and the
    // bottom C is still note 24 on entry 3.  A pitch-table write (NRPN
    // 0x0080 + entry) lands in the live mirror at once, so the next note
    // plays it.  The mode ends after five seconds without a note-on, on a
    // key press, and on boot.
    var MODE_FIRST_NOTE = 21, MODE_ENTRIES = 79;
    function modeEntryFor(note) {
        var index = note - MODE_FIRST_NOTE;
        if (index < 0 || index >= MODE_ENTRIES) return null;
        return { note: note, index: index, lean: 0 };
    }
    // The sweep in the mode: every entry from lowEntry to highEntry.
    function modePlan(lowEntry, highEntry) {
        var out = [];
        for (var e = Math.max(0, lowEntry); e <= Math.min(MODE_ENTRIES - 1, highEntry); e++) {
            out.push(modeEntryFor(e + MODE_FIRST_NOTE));
        }
        return out;
    }

    // --- pitch estimation ------------------------------------------------
    // Two stages, because neither alone is enough.  A period estimator picks
    // the right partial and cannot be fooled by a 208's harmonics, but its
    // resolution is one sample of period - 6 cents at 2 kHz, nowhere near the
    // 2.5 cent step this table is quantised to.  Phase over a long window has
    // all the resolution anyone could want and no idea which partial it is
    // on.  So: YIN to choose, phase to measure.
    //
    // Only intervals matter here - the 208's own trimmer sets absolute pitch -
    // so the sound card's clock error divides out and never reaches the table.
    var YIN_THRESHOLD = 0.15;

    function detrend(x) {
        var mean = 0, i;
        for (i = 0; i < x.length; i++) mean += x[i];
        mean /= x.length;
        var out = new Float64Array(x.length);
        for (i = 0; i < x.length; i++) out[i] = x[i] - mean;
        return out;
    }

    function yin(x, rate, fMin, fMax) {
        var tauMin = Math.max(2, Math.floor(rate / fMax));
        var tauMax = Math.min(Math.floor(rate / fMin), (x.length >> 1) - 1);
        if (tauMax <= tauMin) return null;
        var W = Math.min(x.length - tauMax, 4096);
        var d = new Float64Array(tauMax + 2);
        var cm = new Float64Array(tauMax + 2);
        var tau, i, run = 0, best = -1, bestVal = Infinity, bestTau = -1;
        for (tau = tauMin; tau <= tauMax; tau++) {
            var s = 0;
            for (i = 0; i < W; i++) {
                var diff = x[i] - x[i + tau];
                s += diff * diff;
            }
            d[tau] = s;
            run += s;
            cm[tau] = s * (tau - tauMin + 1) / (run || 1);
            if (cm[tau] < bestVal) { bestVal = cm[tau]; bestTau = tau; }
        }
        // The first dip under the threshold, not the deepest: the deepest is
        // as often an octave down, and an octave error is a reading recorded
        // 1200 cents out rather than a slightly noisy one.
        for (tau = tauMin + 1; tau < tauMax; tau++) {
            if (cm[tau] < YIN_THRESHOLD && cm[tau] <= cm[tau + 1]) { best = tau; break; }
        }
        if (best < 0) best = bestTau;
        if (best < 1 || best >= tauMax) return null;
        var a = d[best - 1], b = d[best], c = d[best + 1];
        var denom = a + c - 2 * b;
        var shift = denom ? 0.5 * (a - c) / denom : 0;
        return { period: best + shift, clarity: 1 - cm[best] };
    }

    // One complex Fourier coefficient at f over L samples from `off`,
    // Hann-windowed so a neighbouring partial does not lean on the phase.
    function coeff(x, off, L, f, rate) {
        var re = 0, im = 0, w = 2 * Math.PI * f / rate;
        for (var i = 0; i < L; i++) {
            var win = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (L - 1));
            var v = x[off + i] * win, p = w * (off + i);
            re += v * Math.cos(p);
            im -= v * Math.sin(p);
        }
        return { re: re, im: im };
    }

    // Phase advance between two blocks gives frequency to whatever precision
    // their separation allows - as long as the starting estimate is good
    // enough to say which cycle the far block is on.  coeff() references both
    // phases to sample zero, so their difference is 2*pi*(f_true - f_assumed)
    // *M/rate: the correction is read straight off it, and the unwrap only has
    // to pick the turn nearest zero.
    //
    // That holds while |f_true - f_assumed| < rate/(2M), so M starts short
    // enough for the period estimate's own error - which grows as f^2, and at
    // 3.5 kHz is tens of Hz - and doubles from there.  Starting at the full
    // separation put the top octave a whole cycle out, and a confidently
    // wrong reading is worse than a noisy one.
    function refine(x, rate, f0) {
        var n = x.length, L = Math.min(8192, n >> 2), f = f0;
        if (L < 64) return f0;
        for (var M = 256; M <= n - L; M *= 2) {
            var a = coeff(x, 0, L, f, rate);
            var b = coeff(x, M, L, f, rate);
            var d = Math.atan2(b.im, b.re) - Math.atan2(a.im, a.re);
            d -= 2 * Math.PI * Math.round(d / (2 * Math.PI));
            var next = f + d * rate / (2 * Math.PI * M);
            if (!isFinite(next) || next <= 0) return f;
            f = next;
        }
        return f;
    }

    // A stretch of exact zeros is no audio, not a quiet tone: Safari's
    // input delivers silence for a few hundred milliseconds after it opens,
    // and the estimator reads a window that starts that way as about 5.3 kHz
    // at clarity 1.00 (the owner's Safari run, 2026-09-27).  A real input,
    // even a quiet one, does not hold exactly zero for 256 samples.
    var SILENT_RUN = 256;
    function hasGap(samples) {
        var run = 0;
        for (var i = 0; i < samples.length; i++) {
            if (samples[i] === 0) { if (++run >= SILENT_RUN) return true; }
            else run = 0;
        }
        return false;
    }
    function measure(samples, rate, fMin, fMax) {
        if (hasGap(samples)) return { ok: false, why: 'no audio', rms: 0 };
        var x = detrend(samples), i, rms = 0;
        for (i = 0; i < x.length; i++) rms += x[i] * x[i];
        rms = Math.sqrt(rms / x.length);
        var coarse = yin(x, rate, fMin, fMax);
        if (!coarse) return { ok: false, why: 'no pitch found', rms: rms };
        var hz = refine(x, rate, rate / coarse.period);
        if (!isFinite(hz) || hz <= 0) return { ok: false, why: 'unstable', rms: rms };
        return { ok: true, hz: hz, clarity: coarse.clarity, rms: rms };
    }

    function cents(hz, ref) { return 1200 * Math.log(hz / ref) / Math.LN2; }

    // What counts as having heard a note.  Clarity alone passes noise that
    // happens to be periodic, and an input left unpatched is mostly hum -
    // which is periodic - so the level has to be there too.
    //
    // The anchor is held to this as well, and that is the point of having it
    // named once.  Every reading is a ratio against the anchor, so an anchor
    // taken from noise does not fail loudly the way a bad reading does: it
    // moves the whole curve, by an amount that can sit under the rejection
    // threshold and be folded in as real.  A sweep has already been started
    // against a disconnected keyboard and anchored on room noise at clarity
    // 0.00; every note after it was correctly rejected, and the run was still
    // sixty-five notes long.
    var MIN_CLARITY = 0.55, MIN_RMS = 0.002;
    // How far either side of the expected pitch a note is looked for, and so
    // how far out a reading can be and still be a reading.  Wide enough that
    // a tracking error is found and narrow enough that the fifth above (702
    // cents) and the octave are not: those are the partials a 208's waveform
    // offers, and the estimator picks the one in the band.
    var SEARCH_CENTS = 300;
    function heard(r) {
        return !!(r && r.ok && r.clarity >= MIN_CLARITY && r.rms >= MIN_RMS);
    }

    // --- talking to the instrument ---------------------------------------
    // Web MIDI without sysex: this asks for nothing the browser has to warn
    // about, and nothing the instrument treats as a command.
    //
    // Access is asked for once and kept.  Every requestMIDIAccess() returns
    // a new MIDIAccess, and each one sends its own statechange for every
    // port.  This used to ask on every list, so each new object got a handler
    // that listed again, which asked again: in Chrome, six seconds after the
    // grant, 195,786 requests and 29,831 MIDIAccess objects, and the tab and
    // then the browser out of memory (2026-09-26).  One object and one
    // handler; a list reads that object's live port maps.
    var midiRequest = null, midiWatchers = [];
    function requestAccess() {
        if (!root.navigator || !root.navigator.requestMIDIAccess) {
            return Promise.reject(new Error(
                'Safari needs the Web MIDI extension, turned on and allowed on this site. Or use Chrome, Firefox or Edge.'));
        }
        if (!midiRequest) {
            midiRequest = root.navigator.requestMIDIAccess({ sysex: false }).then(function (access) {
                // Ports come and go while the page is open and the list is
                // built once, so without this a keyboard unplugged after the
                // list was made stays in the dropdown looking perfectly
                // selectable.
                access.onstatechange = function (e) {
                    midiWatchers.forEach(function (cb) {
                        try { cb(e && e.port); } catch (err) {}
                    });
                };
                return access;
            }, function (err) {
                // A refusal is not kept: a browser that was not ready, or a
                // permission given later, gets another chance on the next ask.
                midiRequest = null;
                throw err;
            });
        }
        return midiRequest;
    }

    function midiOutputs() {
        return requestAccess().then(function (access) {
            var out = [];
            access.outputs.forEach(function (p) { out.push(p); });
            return out;
        });
    }

    // The inputs too, for the settings step: the instrument answers a dump
    // on the input that shares its output's name.
    function midiInputs() {
        return requestAccess().then(function (access) {
            var out = [];
            access.inputs.forEach(function (p) { out.push(p); });
            return out;
        });
    }

    // A port object stays live after it is handed out, so its `state` is the
    // current answer even when the list it came from is stale.  That is what
    // makes this worth checking at the moment a sweep starts.
    function portGone(port) { return !!port && port.state === 'disconnected'; }

    function onMidiChange(cb) { midiWatchers.push(cb); }

    function audioInputs() {
        return root.navigator.mediaDevices.enumerateDevices().then(function (all) {
            return all.filter(function (d) { return d.kind === 'audioinput'; });
        });
    }

    // err.name is the part that says what to do about it; err.message in
    // Chrome is often just "Permission denied", which is not always true - a
    // busy interface reports NotReadableError with permission fully granted.
    function audioTrouble(err) {
        var name = err && err.name ? err.name : '';
        if (name === 'NotAllowedError') {
            return 'The browser refused the audio input. Allow it for this page - ' +
                'the padlock in the address bar - and check the browser itself has ' +
                'the microphone: macOS System Settings, Privacy and Security, ' +
                'Microphone. A blocked page is never asked again, so the dialog ' +
                'not appearing is the usual sign of one of those two.';
        }
        if (name === 'NotReadableError' || name === 'AbortError') {
            return 'The audio input is allowed but could not be opened, which ' +
                'usually means another application has the interface. Quit or ' +
                'release it there and try again. (' + name + ')';
        }
        if (name === 'NotFoundError' || name === 'OverconstrainedError') {
            return 'That audio input is not there any more. Pick another one. (' +
                name + ')';
        }
        return 'The audio input could not be opened: ' +
            (name ? name + ' - ' : '') + (err && err.message ? err.message : String(err));
    }

    function sleep(ms) {
        return new Promise(function (done) { root.setTimeout(done, ms); });
    }

    // One channel, never sixteen.
    //
    // Sending on every channel looked free - the instrument ignores the ones
    // that are not its own - but the ignoring happens at the far end of its
    // event queue, not at the parser: each received note is queued whatever
    // its channel, and the queue is 32 entries that already carry the 1 kHz
    // DAC flush.  Sixteen note-offs and sixteen note-ons per step is 32 events
    // in a burst, so events were dropped, and a dropped note-on leaves the
    // pitch sitting on the note before it.  Which reads, on a rising sweep,
    // as the pitch falling back now and then.
    function send(out, status, note, vel, channel) {
        out.send([status | (channel & 15), note, vel]);
    }

    // --- the sweep --------------------------------------------------------
    // Every reading is against the bottom note, not against an absolute
    // frequency: the 208's trimmer owns absolute pitch, and what this table
    // corrects is the shape.  The anchor is re-measured through the run and
    // interpolated by timestamp, so the oscillator's thermal drift - which
    // over a minute is easily larger than the error being measured - comes
    // out of every reading instead of tilting the whole curve.
    var ANCHOR_EVERY = 8;
    // 683 ms at 48 kHz.  Half that is enough everywhere but the bottom
    // octave, where eleven cycles of a 33 Hz note left the estimate a
    // quarter of a cent out - small, but the bottom of the range is where
    // the anchor lives and an error there tilts every reading after it.
    var WINDOW = 32768;             // analyser samples: 683 ms at 48 kHz
    // After the note-off, before the next note-on.  Back-to-back they are two
    // events in the same queue drain, and the gate drop and the new note land
    // in one scan; apart, each is seen on its own.
    var GAP_MS = 60;
    var SETTLE_MS = 200;
    // A note whose two half-windows disagree by more than this was still
    // moving while it was measured.
    var MOVED_CENTS = 8;
    // The bottom C is the reference for every reading, so it is believed only
    // once it has settled: its halves agree, and it is within this of the
    // pitch the probe heard for the same note a second earlier (or of the
    // last check, for the checks through the run).  Straight after the
    // probe's high note it can still be that note.  On 2026-09-26, three
    // runs in Safari with the Web MIDI extension, the first half of the
    // anchor's window was still the probe's C2 and the second half C0,
    // 2400 cents apart; the whole window read as C2 at clarity 1.00, so
    // every note after it was looked for two octaves too high, found only a
    // harmonic at clarity 0.00, and came back "not heard".  Held and
    // measured again, it settles, so it gets this many tries.
    var ANCHOR_NEAR_CENTS = 50;
    var ANCHOR_TRIES = 3;
    var MAX_CHANNELS = 32;          // asked for; the device gives what it has
    // Converging (opts.adjust): how many times one note is played, the first
    // reading included, and the top of the 12-bit DAC.
    var ADJUST_TRIES = 3;
    var DAC_TOP = 0xFFF;
    // Tuning (mode and adjust together).  An entry still further out than
    // this after its tries is not kept: it is filled in from the entries
    // around it that did tune (fillGaps).  Three notes in a row not heard at
    // all is the top of what the 208 plays, and the sweep stops there.  The
    // 0 V reference is read at the start and at every drift check, and
    // readings of it further apart than REFERENCE_SPREAD_CENTS are named:
    // near 0 V a 208 was erratic in one run, and every note is tuned
    // against it.  3 cents is the warm-up's own bar for a steady drone.
    var FILL_CENTS = 10;
    var TOP_SILENT = 3;
    var REFERENCE_SPREAD_CENTS = 3;

    function constraints(deviceId, want) {
        return {
            audio: {
                deviceId: deviceId ? { exact: deviceId } : undefined,
                echoCancellation: false, autoGainControl: false,
                noiseSuppression: false,
                channelCount: want ? { exact: want } : { ideal: MAX_CHANNELS }
            }
        };
    }

    // What a track actually carries.  Not the AudioNode: a
    // MediaStreamAudioSourceNode reports channelCount 2 in Chrome whatever is
    // on the wire, which turned a twelve input desk into a choice of two.
    function trackChannels(stream) {
        var t = stream.getAudioTracks()[0];
        if (!t) return { got: 1, max: 1 };
        var got = 1, reported = null;
        try { got = (t.getSettings && t.getSettings().channelCount) || 1; } catch (e) {}
        try {
            var caps = t.getCapabilities && t.getCapabilities();
            if (caps && caps.channelCount && caps.channelCount.max) {
                reported = caps.channelCount.max;
            }
        } catch (e) { reported = null; }
        // `reported` stays null when the device says nothing, which is not the
        // same as saying one: the difference decides whether to go looking.
        return { got: got, reported: reported,
                 max: Math.max(got, reported === null ? got : reported) };
    }

    function stop(stream) {
        try { stream.getTracks().forEach(function (t) { t.stop(); }); } catch (e) {}
    }

    // How many channels a device offers.
    //
    // Asked by trying, not by reading a promise.  "ideal" is a wish Chrome can
    // answer with two from a twelve input desk, and getCapabilities() for an
    // audio input is often incomplete or has no channelCount at all - so
    // trusting it meant never asking for twelve, and a Model 12 offered a
    // choice of 1 and 2 with nothing to say why.
    //
    // So: take what "ideal" gives, then ask for each larger count outright and
    // keep the largest that opens.  The ladder is short and every rung is a
    // real device shape; an exact request Chrome cannot meet is refused
    // immediately with OverconstrainedError, which costs nothing.
    var LADDER = [32, 24, 22, 20, 18, 16, 14, 12, 10, 8, 6, 4, 2];

    function channelCount(deviceId) {
        var md = root.navigator.mediaDevices, report = [];
        return md.getUserMedia(constraints(deviceId, null)).then(function (stream) {
            var seen = trackChannels(stream);
            stop(stream);
            var caps = seen.reported;
            report.push('ideal -> ' + seen.got +
                        (caps === null ? ' (device reports no channel capability)'
                                       : ' (capabilities max ' + seen.max + ')'));
            // When the device states a maximum, believe it and ask once.  The
            // ladder exists for devices that state nothing - which is the case
            // that hid a twelve channel desk behind a count of two - and
            // running it regardless costs a dozen open-and-close cycles on the
            // interface for an answer already given.
            var rungs = (caps === null ? LADDER : [seen.max])
                .filter(function (n) { return n > seen.got; });
            var best = seen.got;

            function tryRung(i) {
                if (i >= rungs.length) return Promise.resolve(best);
                return md.getUserMedia(constraints(deviceId, rungs[i]))
                    .then(function (s2) {
                        var got = trackChannels(s2).got;
                        stop(s2);
                        report.push('exact ' + rungs[i] + ' -> ' + got);
                        // Chrome can accept the constraint and still hand back
                        // fewer, so what the track says wins over what was asked.
                        if (got > best) { best = got; return best; }
                        return tryRung(i + 1);
                    }, function (err) {
                        report.push('exact ' + rungs[i] + ' -> refused (' +
                                    (err && err.name ? err.name : 'error') + ')');
                        return tryRung(i + 1);
                    });
            }
            return tryRung(0).then(function (n) {
                return { count: n, report: report };
            });
        });
    }

    // Two options change what a sweep is, and without them it is the sweep
    // it always was.
    //
    // `opts.mode`, { on, off }: the instrument is in calibration mode for
    // the run (above).  Notes map straight to entries, `low` and `high` can
    // reach all 79, and the anchor is entry 3 whatever the range.  on() goes
    // out before every note-on, not once: the mode lapses after five seconds
    // without a note-on on the instrument's channel, and an Auto search
    // spends longer than that playing on the others - so a single on()
    // left everything after it played through the pads and the
    // transposition, which is a plausible, wrong table.  off() goes out once
    // the run ends, however it ends - done, failed, stopped, or the page
    // going away - and only if on() ever did.
    //
    // `opts.adjust`, { table, write(entry, value) -> Promise, countsPerCent,
    // reference, playing }: converge each entry instead of only reading it.
    // `table` is the 79 entries the run starts from, `write` puts one into
    // the instrument's live mirror, and countsPerCent is the DAC counts that
    // move the ramp a cent (BUILDLIB.pitchCountsPerCent).  A note more than
    // half a count out is moved by its reading and played again, up to
    // ADJUST_TRIES times, and the closest value is the one kept.  The result
    // carries `table`, and each reading its `value`, `original`, `tries` and
    // `residual`.  `table` is what the instrument is playing, unless
    // `playing` is given: then that is, and tuning, the run writes `table`
    // over it before the channel probe plays anything.  The page gives it
    // where the keyboard's table is at the other volts per octave from the
    // page's, and `table` is the flat table at the page's (app.js runStart).
    //
    // With both, the run tunes the table to the 208's 0 V pitch, which is
    // where its owner tunes it (with the keyboard off).  `reference` is the
    // table's 0 V entry: 0 with the pitch offset, 3 without (the entries
    // under it are held at 0 and never touched).  It is written to 0 counts
    // before anything is measured and kept there; it is the anchor, read
    // until it repeats, and it is what the drift checks read.  Every entry
    // above it is tuned to it times 2^((e - reference)/12), the bottom C
    // included.  An entry not heard, or still more than FILL_CENTS out after
    // its tries, is filled in after the run from the tuned entries around it
    // (fillGaps), and TOP_SILENT entries in a row not heard end the sweep,
    // the rest filled the same way.  Each reading carries its `source`:
    // 'reference', 'measured', or what fillGaps made of it - 'interpolated',
    // 'extrapolated', or null where there was nothing to fill it from and it
    // keeps the value it came with.  The result adds `anchorEntry` and
    // `filled`, entry -> source for every entry filled in.
    //
    // `opts.input`, an input from listen(): listened through when it still
    // fits the options, and left open.  `opts.onPhase(name)` says what the
    // run is doing before its first note: 'settle' for the warm-up, then
    // 'reference' for the anchor (the channel search has onProbe).
    // Opens the chosen input and channel: the stream, and the context and
    // analyser listening to that one channel of it.
    async function openInput(o) {
        var stream;
        try {
            // Every processing option is off because each would rewrite the
            // signal being measured: gain control moves a steady tone's level
            // around, and noise suppression is a filter bank that will happily
            // reshape a plain oscillator.  Chrome also forces mono when echo
            // cancellation is on, which would quietly undo the channel count.
            //
            // The channel asked for has to be reachable, and "ideal" is a
            // wish: Chrome answers it with two from a twelve input desk, and a
            // device that reports no channelCount capability leaves nothing to
            // raise the request to.  So this opened two channels, clamped the
            // chosen one to index 1, listened to the wrong input, and then
            // failed with a message blaming MIDI - while the dropdown beside
            // it still offered twelve.  The dropdown is filled by
            // channelCount(), which found that number by asking for it
            // outright; ask outright here too, largest candidate first, and
            // keep the first request that actually reaches the channel.
            stream = await root.navigator.mediaDevices.getUserMedia(
                constraints(o.deviceId, null));
            var seen = trackChannels(stream);
            var need = (o.audioChannel || 0) + 1;
            if (need > seen.got) {
                // The count the page already opened, then what the device
                // claims, then the same ladder channelCount climbs for the
                // devices that claim nothing - and `need` itself last, so a
                // shape the ladder does not name is still tried.
                var rungs = [];
                [o.audioChannels, seen.max].concat(LADDER).concat([need])
                    .forEach(function (n) {
                        if (n >= need && rungs.indexOf(n) < 0) rungs.push(n);
                    });
                for (var ri = 0; ri < rungs.length && need > seen.got; ri++) {
                    var wider = null;
                    try {
                        wider = await root.navigator.mediaDevices.getUserMedia(
                            constraints(o.deviceId, rungs[ri]));
                    } catch (e) { continue; }   // refused: try the next rung
                    var opened = trackChannels(wider);
                    // Chrome can accept the constraint and still hand back
                    // fewer, so what the track says wins over what was asked.
                    if (opened.got > seen.got) {
                        stop(stream); stream = wider; seen = opened;
                    } else { stop(wider); }
                }
            }
        } catch (err) {
            throw new Error(audioTrouble(err));
        }
        // The channel offered has to be the channel listened on.  Clamping it
        // into range instead meant reading a different input and then failing
        // with a message about MIDI, which sends the owner to the wrong cable.
        // Refused here, before any note goes out, and named as an audio fault.
        var count = Math.max(seen.got, 1);
        var want = Math.max(0, o.audioChannel || 0);
        if (want >= count) {
            stop(stream);
            throw new Error('Audio channel ' + (want + 1) + ' could not be opened; ' +
                'this input gave ' + count + ' channel' + (count === 1 ? '' : 's') +
                '. Pick a channel in range, or choose a different input.');
        }
        var ctx = new (root.AudioContext || root.webkitAudioContext)();
        var analyser = ctx.createAnalyser();
        analyser.fftSize = WINDOW;
        analyser.smoothingTimeConstant = 0;
        var source = ctx.createMediaStreamSource(stream);
        // One channel of the interface, not a mix of it.  A splitter keeps
        // them apart - its channelInterpretation is 'discrete', so channel 7
        // arrives as channel 7 rather than being folded into a stereo pair.
        if (count > 1) {
            var splitter = ctx.createChannelSplitter(count);
            source.connect(splitter);
            splitter.connect(analyser, want, 0);
        } else {
            source.connect(analyser);
        }
        return new Input(o, stream, count, want, ctx, analyser);
    }

    // An open input.  `since` is when it started delivering: the later of
    // the stream opening and its context running, since which of the two
    // starts Safari's settling (the warm-up in run) is not known and the
    // later is the safe one.  A track that ends or is muted spoils it:
    // whatever it comes back as has not been waited out.
    function Input(o, stream, count, want, ctx, analyser) {
        var self = this;
        this.deviceId = o.deviceId || null;
        this.stream = stream; this.count = count; this.want = want;
        this.ctx = ctx; this.analyser = analyser;
        this.since = null; this.spoiled = false; this.closed = false;
        this.running();
        try { ctx.addEventListener('statechange', function () { self.running(); }); } catch (e) {}
        stream.getAudioTracks().forEach(function (t) {
            try {
                t.addEventListener('ended', function () { self.spoiled = true; });
                t.addEventListener('mute', function () { self.spoiled = true; });
            } catch (e) {}
        });
    }
    Input.prototype.running = function () {
        var state = this.ctx.state;
        if (this.since === null && (state === undefined || state === 'running')) this.since = Date.now();
    };
    Input.prototype.age = function () {
        return this.since === null ? 0 : Date.now() - this.since;
    };
    // Whether a run with these options can listen through it.
    Input.prototype.fits = function (o) {
        if (this.closed || this.spoiled || this.ctx.state === 'closed') return false;
        if ((o.deviceId || null) !== this.deviceId) return false;
        if (Math.max(0, o.audioChannel || 0) !== this.want) return false;
        return this.stream.getAudioTracks().every(function (t) {
            return t.readyState !== 'ended' && !t.muted;
        });
    };
    Input.prototype.close = function () {
        if (this.closed) return;
        this.closed = true;
        stop(this.stream);
        try {
            var done = this.ctx.close();
            if (done && done.catch) done.catch(function () {});
        } catch (e) {}
    };

    // An input opened ahead of a run, which the page hands to it as
    // `opts.input`.  Safari reads sharp for seconds after an input opens,
    // so the page opens it as soon as it is picked, and the run waits out
    // only what is left of the warm-up.  The page closes it.
    function listen(o) { return openInput(o); }

    function Sweep(opts) {
        this.opts = opts;
        this.stopped = false;
    }

    Sweep.prototype.stop = function () { this.stopped = true; };

    Sweep.prototype.run = async function () {
        var o = this.opts, self = this;
        var mode = o.mode || null, adjust = o.adjust || null;
        var tuning = !!(mode && adjust);
        // The 0 V entry, when tuning; nothing under it is swept.
        var zero = tuning ? adjust.reference : null;
        if (tuning && !(zero >= 0 && zero < MODE_ENTRIES && zero === Math.floor(zero))) {
            throw new Error('The table’s 0 V entry is not known.');
        }
        var entryOf = mode ? modeEntryFor : function (note) { return entryFor(note, o.octaveTerm); };
        var steps = mode ? modePlan(tuning ? Math.max(o.low, zero) : o.low, o.high)
                         : plan(o.low, o.high, o.octaveTerm);
        if (!steps.length) throw new Error('Nothing to sweep.');

        // The input it listens on: the one the page opened ahead of the run
        // (listen, below) while it is still the chosen input and channel and
        // still live, or one of its own, closed when the run ends.
        var input = o.input && o.input.fits(o) ? o.input : null;
        var own = !input;
        if (own) input = await openInput(o);
        var stream = input.stream, ctx = input.ctx, analyser = input.analyser;
        var count = input.count, want = input.want;
        if (ctx.state === 'suspended' && ctx.resume) {
            try { await ctx.resume(); } catch (e) {}
            input.running();
        }
        if (o.onChannels) o.onChannels(count, want);
        var buf = new Float32Array(analyser.fftSize);
        var rate = ctx.sampleRate;
        // The buffer is a rolling window, so it has to fill with the new note
        // before it is read - otherwise the reading is part of the note
        // before it, which on a sweep is a consistent, plausible, wrong answer.
        var fill = 1000 * analyser.fftSize / rate;
        var held = null;

        var heldOn = 0;
        function release() {
            if (held !== null) { send(o.output, 0x80, held, 0, heldOn); held = null; }
        }

        // A note-off has to go out even if the page is closed mid-sweep.
        //
        // The instrument's note-off is what clears the key's held flag, and the
        // sweep reaches key indices the 29-key keyboard cannot address - so a
        // note left on cannot be released by playing, and survives until the
        // next matching note-off or a power cycle.  That is not theoretical:
        // it is the fault that looked like the keyboard being stuck an octave
        // up.  pagehide is the one that fires reliably when a tab goes away.
        function letGo() { release(); }
        root.addEventListener('pagehide', letGo);
        root.addEventListener('beforeunload', letGo);

        // The mode, the same way: whatever a run wrote into the mirror is
        // meant for this run alone, and off() is what gives the keyboard its
        // own table back.  A listener of its own, after the note-off's, so a
        // port that has gone away and throws from the first still gets the
        // second tried.
        var modeOn = false;
        function modeUp() { modeOn = true; mode.on(); }
        function modeDown() {
            if (!modeOn) return;
            modeOn = false;
            try { mode.off(); } catch (e) { /* the port is gone: nothing to end it through */ }
        }
        if (mode) {
            root.addEventListener('pagehide', modeDown);
            root.addEventListener('beforeunload', modeDown);
        }
        // The DAC value a note is being played at, for the log (converging).
        var trying = null;

        async function hear(note, expectHz, channel, what) {
            var ch = channel === undefined ? o.channel : channel;
            release();
            if (mode) modeUp();
            await sleep(GAP_MS);
            send(o.output, 0x90, note, o.velocity || 100, ch);
            held = note;
            heldOn = ch;
            await sleep(SETTLE_MS + fill);
            analyser.getFloatTimeDomainData(buf);
            release();
            var lo = expectHz ? expectHz * Math.pow(2, -SEARCH_CENTS / 1200) : 18;
            var hi = expectHz ? expectHz * Math.pow(2, SEARCH_CENTS / 1200) : 6000;
            var whole = measure(buf, rate, lo, hi);
            if (!whole.ok) { note_log(note, ch, what, expectHz, whole, null, null); return whole; }
            // Did it hold still while it was being measured?  The two halves
            // of the same window are two readings of the same note, so a note
            // still moving - a glide, a note that never arrived and left the
            // previous one decaying, a pitch being pulled by something else -
            // shows up as the halves disagreeing.  A settled note's halves
            // agree to well under a cent.
            var half = buf.length >> 1, i;
            var a = new Float32Array(half), b = new Float32Array(half);
            for (i = 0; i < half; i++) { a[i] = buf[i]; b[i] = buf[half + i]; }
            var ra = measure(a, rate, lo, hi), rb = measure(b, rate, lo, hi);
            whole.drift = (ra.ok && rb.ok) ? cents(rb.hz, ra.hz) : null;
            note_log(note, ch, what, expectHz, whole, ra, rb);
            return whole;
        }

        // Every note that goes out, and what came back for it - the probe and
        // the drift anchor included, since a reading that looks wrong in the
        // sweep is often explained by the one before it that was not part of
        // the sweep at all.
        function note_log(note, ch, what, expectHz, r, firstHalf, secondHalf) {
            var e = entryOf(note);
            var row = {
                t: Date.now() - t0,
                what: what || 'sweep',
                note: note,
                name: e ? noteLabel(e.index) : '?',
                entry: e ? e.index : null,
                channel: ch,
                expectHz: expectHz || null,
                hz: r && r.ok ? r.hz : null,
                firstHalfHz: firstHalf && firstHalf.ok ? firstHalf.hz : null,
                secondHalfHz: secondHalf && secondHalf.ok ? secondHalf.hz : null,
                // measure() sets no drift on a note it could not read, and
                // the CSV writer tests for null: undefined reached .toFixed
                // and the download button did nothing, for any run with a
                // note that was not heard.
                halfDrift: (r && r.drift !== undefined) ? r.drift : null,
                clarity: r && r.ok ? r.clarity : null,
                rms: r ? r.rms : null,
                why: r && r.ok ? '' : (r ? r.why : 'not measured')
            };
            // Converging, the entry's DAC value as the note played; null
            // for a note that is not a sweep note.
            if (adjust) row.value = trying;
            log.push(row);
            if (o.onReading) o.onReading(row);
        }

        // The probe's low note is the bottom C in the mode, entry 3, and the
        // first note swept otherwise; its high note is two octaves above.
        // The anchor is that same note, except when tuning: then it is the
        // 0 V entry, because that is the pitch the owner tunes the 208 to
        // and expects every key to be in tune with.  The bottom C used to be
        // the reference here, which left a 208 whose 0 V pitch sits off its
        // curve out of tune with its own trimmer by however far that was.
        var probeAt = mode ? modeEntryFor(MODE_FIRST_NOTE + BOTTOM_KEY_ENTRY) : steps[0];
        var anchor = tuning ? modeEntryFor(MODE_FIRST_NOTE + zero) : probeAt;
        var results = [], marks = [], warnings = [];
        var log = [], t0 = Date.now();
        var previous = null;

        // What one sweep reading says, and what is wrong with it: null for a
        // note not heard, else its pitch and its cents, null where the
        // reading cannot be kept.  `blank` finishes the "not heard" warning
        // with what becomes of the entry; `quiet` keeps every warning back,
        // which is how a note played again is judged.
        function judge(step, got, t, blank, quiet) {
            function say(text) { if (!quiet) warnings.push(text); }
            if (!heard(got)) {
                say(noteLabel(step.index) + ': ' +
                    (got.ok && got.rms < MIN_RMS ? 'too quiet to read'
                                                 : 'not heard clearly') + blank);
                return null;
            }
            // Drift-corrected reference: where the anchor was at this moment.
            var ref = anchorAt(marks, t);
            var off = cents(got.hz, ref) - 100 * (step.index - anchor.index);
            // The firmware cannot play a higher note lower: the output is
            // table[index] interpolated towards table[index+1], the table
            // is strictly increasing, and index rises with the note.  So a
            // reading that goes backwards is not a tracking error being
            // measured - it is the note not having taken, and the pitch
            // that came back belongs to the note before it.
            var backwards = !!(previous && got.hz <= previous.hz);
            if (backwards) {
                say(noteLabel(step.index) + ' came out ' +
                    Math.abs(cents(got.hz, previous.hz)).toFixed(0) +
                    ' cents BELOW ' + noteLabel(previous.index) + ' (' +
                    previous.hz.toFixed(2) + ' Hz then ' + got.hz.toFixed(2) +
                    ' Hz) - the note did not take');
            }
            // A reading anywhere in the band that was searched is a
            // reading.  This used to stop at 120 cents on the argument
            // that more than a semitone out is the wrong note rather than
            // a tracking error - but a wrong note is caught by the
            // backwards check below, and a real 208 does read that far
            // out: a replaced expo converter came back 129 and 138 cents
            // flat at the top two keys (2026-09-17), clarity 1.00, on top
            // of a table already carrying +180 there, and the guard threw
            // away exactly the two readings the recalibration was for.
            // Beyond the band nothing is found at all, so this is a
            // safety net for a reference that moved between the search
            // and the reading, not a judgement about the instrument.
            //
            // A note that did not take is blanked on its shape and not on
            // its size.  It lands about 100 cents out, so warning about it
            // and then keeping it wrote a +100 cent correction into the
            // table, one or two DAC counts clear of its neighbour, past
            // the collision guard and past validateCal, and shipped a
            // semitone playing very nearly its neighbour's pitch.
            var keep = !(Math.abs(off) > SEARCH_CENTS || backwards);
            if (!keep && !backwards) {
                say(noteLabel(step.index) + ': ' + off.toFixed(0) + ' cents out, ignored');
            }
            if (got.drift !== null && Math.abs(got.drift) > MOVED_CENTS) {
                say(noteLabel(step.index) + ' moved ' +
                    got.drift.toFixed(1) + ' cents while being measured');
            }
            return { hz: got.hz, cents: keep ? off : null };
        }

        // --- converging (opts.adjust) --------------------------------------
        // The instrument's table as it is being played: the caller's, with
        // every write made since; and the caller's, as it came.
        var table = adjust ? adjust.table.slice() : null;
        var start = adjust ? adjust.table.slice() : null;
        // Tuning: the warnings that end with what became of their entry,
        // which is known only once the run is over ({ at, entry }), and how
        // many entries in a row have not been heard at all.
        // `topAt` is the first step not played once the top is found.
        var fates = [], silentRun = 0, heardAbove = false, topAt = null;
        // Half a DAC count, in cents: nearer than this, the next count over
        // is no nearer, so the note is as right as the table can make it.
        var HALF_COUNT = adjust ? 0.5 / adjust.countsPerCent : 0;
        // Notes played since the anchor was last measured.  Converging, a
        // step can be three notes, so the drift check counts notes rather
        // than steps and keeps the spacing the plain sweep has.
        var sinceAnchor = 0;

        // Where an entry may move to: inside the DAC, and strictly between
        // its neighbours as they stand now, so the table the instrument is
        // playing is never one that plays a higher note lower - not even
        // for the length of a note.  Null when there is no room.
        function room(e, v) {
            var lo = e > 0 ? table[e - 1] + 1 : 0;
            var hi = e < table.length - 1 ? table[e + 1] - 1 : DAC_TOP;
            lo = Math.max(0, lo); hi = Math.min(DAC_TOP, hi);
            if (lo > hi) return null;
            return Math.max(lo, Math.min(hi, v));
        }

        // One entry, brought as close to its pitch as the table allows.  It
        // is read as the plain sweep reads it; more than half a count out,
        // it is moved by that reading - the ramp's counts per cent, which a
        // 208 answers with a few percent more or less, so the next reading
        // says how far that fell short - and played again.  The closest of
        // the tries is the one kept, and written back if it was not the last
        // one played.  A note not heard, or not believed, keeps the value it
        // came with; so does the anchor, which is the reference every other
        // reading is taken against: moving it would move them all.
        //
        // Tuning, a note not heard or not believed, or one still more than
        // FILL_CENTS out after its tries, is filled in after the run
        // instead (fillGaps): its warning waits for what that made of it.
        // The value played until then is the one it would have kept.
        async function converge(step, i) {
            var e = step.index, original = start[e], value = table[e], was = value;
            var tried = [], best = null, played = 0, reading = null, silent = false;
            var fixed = e === anchor.index, at = warnings.length;
            while (played < ADJUST_TRIES) {
                if (played > 0 && self.stopped) throw new Error('Stopped.');
                var wantHz = anchorAt(marks, Date.now()) *
                             Math.pow(2, (e - anchor.index) / 12);
                trying = value;
                var got = await hear(step.note, wantHz, undefined, played ? 'retry' : 'sweep');
                trying = null;
                played++; sinceAnchor++;
                if (played === 1) silent = !heard(got);
                // Only the first reading warns, the way a sweep always has.
                // After it, the one thing worth saying is a note that did not
                // come within half a count, and that is said below.
                var j = judge(step, got, Date.now(), tuning ? '' : ', left unchanged', played > 1);
                if (played === 1) reading = j;
                if (!j || j.cents === null) break;
                tried.push({ value: value, cents: j.cents, hz: j.hz });
                if (!best || Math.abs(j.cents) < Math.abs(best.cents)) best = tried[tried.length - 1];
                if (fixed || Math.abs(j.cents) <= HALF_COUNT || played >= ADJUST_TRIES) break;
                // The first move is at the ramp's rate.  After it the note
                // has been heard at two values, which is its own slope, and
                // the next move goes by that: at the ramp's rate a 208 four
                // percent steep there took 2123 to 2094 to 2096 around an
                // in-tune 2095.45, and three tries ended 1.4 cents out.  A
                // slope under half or over twice the ramp's is not a 208's
                // and is not believed.
                var rate = adjust.countsPerCent;
                if (tried.length >= 2) {
                    var a = tried[tried.length - 1], b = tried[tried.length - 2];
                    var slope = (a.cents - b.cents) / (a.value - b.value);     // cents a count
                    var ramp = 1 / adjust.countsPerCent;
                    if (slope >= ramp / 2 && slope <= ramp * 2) rate = 1 / slope;
                }
                var next = room(e, value - Math.round(j.cents * rate));
                // Nowhere to go, or somewhere already heard: another try
                // would only say again what one has said.
                if (next === null || next === value ||
                    tried.some(function (t) { return t.value === next; })) break;
                await adjust.write(e, next);
                table[e] = value = next;
            }
            var keep = best ? best.value : was;
            if (table[e] !== keep) {
                await adjust.write(e, keep);
                table[e] = keep;
            }
            var r = { index: e, note: step.note, cents: best ? best.cents : null,
                      residual: best ? best.cents : null,
                      first: tried.length ? tried[0].cents : null,
                      original: original, value: keep, tries: played };
            if (best) r.hz = best.hz;
            // Null until fillGaps says what it is.
            var fill = tuning && !fixed && (!best || Math.abs(best.cents) > FILL_CENTS);
            if (tuning) r.source = fixed ? 'reference' : fill ? null : 'measured';
            results.push(r);
            if (best && !fixed && Math.abs(best.cents) > HALF_COUNT) {
                warnings.push(noteLabel(e) + ': ' + best.cents.toFixed(1) + ' cents out after ' +
                              played + (played === 1 ? ' try' : ' tries'));
                if (fill) fates.push({ at: warnings.length - 1, entry: e });
            } else if (fill && warnings.length > at) {
                fates.push({ at: at, entry: e });
            }
            // What the next note is held to: the pitch this one was left at.
            if (reading) previous = { hz: best ? best.hz : reading.hz, index: e };
            if (o.onNote) o.onNote(step, best ? r : null, i, steps.length);
            return { silent: silent };
        }

        // An entry filled in after the run, in the log beside the notes: no
        // pitch was taken for it, and `why` says how it was made.
        function fill_log(e, how) {
            var row = {
                t: Date.now() - t0, what: 'fill', note: e + MODE_FIRST_NOTE,
                name: noteLabel(e), entry: e, channel: o.channel, expectHz: null,
                hz: null, firstHalfHz: null, secondHalfHz: null, halfDrift: null,
                clarity: null, rms: null, why: how, value: table[e]
            };
            log.push(row);
            if (o.onReading) o.onReading(row);
        }

        try {
            // Is anything coming in at all?  The 208 is droning before the
            // first note goes out - that is what the guide asks for - so an
            // input with nothing on it is known before MIDI is touched.
            // Without this the probe played thirty-two notes into a silent
            // channel and then blamed the keyboard: a sweep listening on
            // channel 1 of a desk with the 208 on channel 2 heard bleed at
            // level 0.0013, under the 0.002 a note needs, and reported that
            // no MIDI channel moved the pitch.
            // Safari's input also settles for seconds after it opens: past
            // the silence at the start, every Safari run read a steady tone
            // about 90 cents sharp from roughly 3 to 10 s in (2026-09-26/27,
            // logs 4 to 8, before calibration mode as well as with it; never
            // in Chrome).  So with `o.warmupMs` the drone is watched before
            // any note goes out: at least that long, and until its last two
            // seconds agree to 3 cents, or at most ten seconds more.  Counted
            // in the sleeps it waits, so a fake clock runs it at once.
            // An input the page opened ahead of the run has been settling
            // since, so only what is left is waited; its last two seconds
            // still have to agree before a note goes out.
            var watch = (o.warmupMs || 0) > 0;
            var warm = watch ? Math.max(0, o.warmupMs - input.age()) : 0;
            var waited = 0, heardHz = [], STEP = 300;
            if (watch && o.onPhase) o.onPhase('settle');
            while (watch) {
                if (self.stopped) throw new Error('Stopped.');
                await sleep(STEP);
                waited += STEP;
                analyser.getFloatTimeDomainData(buf);
                var w0 = measure(buf, rate, 18, 6000);
                heardHz.push(heard(w0) ? w0.hz : null);
                var last = heardHz.slice(-Math.ceil(2000 / STEP));
                var still = last.length * STEP >= 2000 &&
                    last.every(function (h) { return h !== null; }) &&
                    cents(Math.max.apply(null, last), Math.min.apply(null, last)) <= 3;
                if ((waited >= warm && still) || waited >= warm + 10000) break;
            }
            // An input that has not started yet is silence, not a level:
            // wait for it a few windows before judging what comes in.
            var idle = null;
            for (var tries = 0; tries < 5 && (!idle || idle.why === 'no audio'); tries++) {
                await sleep(fill);
                analyser.getFloatTimeDomainData(buf);
                idle = measure(buf, rate, 18, 6000);
            }
            if (idle.rms < MIN_RMS) {
                throw new Error('Nothing usable is coming in on audio channel ' +
                    (want + 1) + ' of the chosen input: level ' + idle.rms.toFixed(4) +
                    ', where a note needs ' + MIN_RMS.toFixed(4) + '. The 208 should ' +
                    'be droning into that channel before the sweep starts. Check the ' +
                    'cable and the channel\u2019s level.');
            }

            // Tuning from a table the instrument does not hold yet (it holds
            // `playing`), the table goes into the mirror before the probe
            // plays anything: two octaves at the other scaling came out 2000
            // or 2880 cents apart, and the probe blamed the MIDI channel.  In
            // the mode, so the run's end reloads the mirror however it ends.
            // Entries that move down are written from the bottom up and
            // entries that move up from the top down, so the table being
            // played never runs backwards, not even between two writes.
            if (tuning && adjust.playing) {
                modeUp();
                var lower = [], higher = [];
                for (var k = 0; k < table.length; k++) {
                    if (table[k] < adjust.playing[k]) lower.push(k);
                    else if (table[k] > adjust.playing[k]) higher.push(k);
                }
                for (k = 0; k < lower.length; k++) await adjust.write(lower[k], table[lower[k]]);
                for (k = higher.length - 1; k >= 0; k--) await adjust.write(higher[k], table[higher[k]]);
            }

            // Which channel the instrument is listening on.  There is no way
            // to ask it, so this plays two notes two octaves apart and watches
            // for the pitch to move: on the wrong channel nothing is heard and
            // the oscillator holds whatever it was already droning, so no
            // movement is the answer "not this one".
            //
            // This runs whether or not a channel was chosen.  Choosing one used
            // to skip it, which meant the only check that anything is listening
            // at all was the one a chosen channel opted out of - and a keyboard
            // unplugged after the port list was built sails straight past.
            // Searching costs up to sixteen of these; confirming costs one.
            //
            // It answers `yes` when the pitch moved the two octaves, and
            // `wrong` with the interval, in cents, when both notes were heard
            // clearly and the pitch moved at least 300 cents but 300 or more
            // away from the two octaves: something is listening, and the
            // 208's trim is not the scaling the table plays at.  That is the
            // volts per octave, not the MIDI channel, so it gets its own
            // message.  Anything less than 300 cents is no movement.
            async function probe(ch) {
                var hiNote = probeAt.note + 24;
                var hiEntry = entryOf(hiNote);
                if (!hiEntry) return { yes: false, wrong: null };
                var apart = 100 * (hiEntry.index - probeAt.index);
                // Both measurements search the whole range.  Handing the second
                // one the answer as its expected pitch narrows YIN to a band
                // +/-300 cents around exactly the interval being tested - the
                // same width as the test - so anything periodic in that band
                // passes by construction.  Broadband noise sailed through it
                // while a real tone on the wrong channel was caught, which is
                // the wrong way round.  And both have to be heard clearly, not
                // merely found: the point of the probe is to establish that
                // something is listening.
                var lo = await hear(probeAt.note, null, ch, 'probe');
                var hi = await hear(hiNote, null, ch, 'probe');
                var both = heard(lo) && heard(hi);
                var moved = both ? cents(hi.hz, lo.hz) : null;
                var yes = both && Math.abs(moved - apart) < 300;
                if (yes) probeLo = lo;
                return { yes: yes, wrong: both && !yes && moved >= 300 ? moved : null };
            }
            var probeLo = null;
            function wrongInterval(ch, moved) {
                return new Error('MIDI channel ' + (ch + 1) + ' moved the pitch ' +
                    (moved / 100).toFixed(1) + ' semitones where two octaves are 24. ' +
                    'Set the volts per octave above to match how your 208 is trimmed, ' +
                    'then measure again.');
            }

            // The anchor, held until it has settled (ANCHOR_TRIES above).
            function steady(r, near) {
                return heard(r) && r.drift !== null && Math.abs(r.drift) <= MOVED_CENTS &&
                       (!near || Math.abs(cents(r.hz, near)) <= ANCHOR_NEAR_CENTS);
            }
            // With `repeat`, settled is not enough: two readings have to
            // agree, and it gets one more try to show that.  The last
            // reading comes back as `r`, and the steady ones as `heardHz`.
            async function steadyAnchor(expectHz, near, repeat) {
                var r = null, before = null, heardHz = [];
                for (var tries = 0; tries < ANCHOR_TRIES + (repeat ? 1 : 0); tries++) {
                    if (self.stopped) throw new Error('Stopped.');
                    r = await hear(anchor.note, expectHz, undefined, 'anchor');
                    if (!steady(r, near)) continue;
                    heardHz.push(r.hz);
                    if (!repeat || (before && steady(r, before.hz))) return { r: r, ok: true, heardHz: heardHz };
                    before = r;
                }
                return { r: r, ok: false, heardHz: heardHz };
            }

            if (o.channel === null || o.channel === undefined) {
                var found = null;
                for (var ch = 0; ch < 16 && found === null; ch++) {
                    if (self.stopped) throw new Error('Stopped.');
                    if (o.onProbe) o.onProbe(ch, false);
                    var answer = await probe(ch);
                    if (answer.yes) found = ch;
                    // The first channel that moves the pitch is the one
                    // listening, whatever the interval: the search ends there.
                    else if (answer.wrong !== null) throw wrongInterval(ch, answer.wrong);
                }
                if (found === null) {
                    throw new Error('No MIDI channel moved the pitch. Check the ' +
                        'keyboard is on the chosen MIDI port and still plugged in, ' +
                        'and that the 208 is droning into the chosen audio input ' +
                        'and channel. The sweep is listening on channel ' +
                        (want + 1) + ' of that input.');
                }
                o.channel = found;
                if (o.onChannel) o.onChannel(found);
            } else {
                if (o.onProbe) o.onProbe(o.channel, true);
                if (self.stopped) throw new Error('Stopped.');
                var chosen = await probe(o.channel);
                if (!chosen.yes && chosen.wrong !== null) throw wrongInterval(o.channel, chosen.wrong);
                if (!chosen.yes) {
                    throw new Error('MIDI channel ' + (o.channel + 1) + ' did not ' +
                        'move the pitch, so nothing is listening there. Check the ' +
                        'keyboard is on the chosen MIDI port and still plugged in, ' +
                        'and that the 208 is droning into the chosen audio input ' +
                        'and channel. The sweep is listening on channel ' +
                        (want + 1) + ' of that input. Set the channel to Auto ' +
                        'to search for it.');
                }
            }

            // Tuning, the 0 V entry goes to 0 counts before it is heard, and
            // stays there: it is the pitch the 208's own trimmer sets.
            if (tuning) {
                await adjust.write(zero, 0);
                table[zero] = 0;
            }

            // Searched over the whole range, like the probe: a band around
            // the probe's reading would find a C2 still sounding there too,
            // as its fourth subharmonic.  Held to the probe's reading where
            // the probe played this same note at this same value; tuning, it
            // has to repeat as well, and that is what catches a stale window
            // at a note the probe never played.
            var near = !tuning ? probeLo && probeLo.hz
                : zero === probeAt.index && start[zero] === 0 && probeLo ? probeLo.hz : null;
            if (o.onPhase) o.onPhase('reference');
            var settled = await steadyAnchor(null, near, tuning);
            var first = settled.r;
            if (!settled.ok) {
                var why = !first.ok ? first.why
                    : !heard(first) ? 'clarity ' + first.clarity.toFixed(2) + ', level ' +
                                      first.rms.toFixed(4)
                    : first.drift === null || Math.abs(first.drift) > MOVED_CENTS
                        ? 'it moved ' + (first.drift === null ? '?' : Math.abs(first.drift).toFixed(0)) +
                          ' cents while being measured'
                        : near && !steady(first, near)
                            ? first.hz.toFixed(2) + ' Hz, where the probe heard ' +
                              near.toFixed(2) + ' Hz'
                            : 'it did not repeat: ' + settled.heardHz.map(function (h) {
                                  return h.toFixed(2);
                              }).join(', ') + ' Hz';
                throw new Error((tuning ? 'The 0 V note' : 'The bottom C') +
                    ' did not come back as a steady tone' +
                    ' (' + why + ')' +
                    '. Every reading is measured against it, so the sweep stops ' +
                    'here rather than anchoring on noise. Check the 208 is droning ' +
                    'into the chosen audio input and channel.');
            }
            marks.push({ t: Date.now(), hz: first.hz });

            for (var i = 0; i < steps.length; i++) {
                if (self.stopped) throw new Error('Stopped.');
                var step = steps[i];
                if (adjust ? sinceAnchor >= ANCHOR_EVERY : (i > 0 && i % ANCHOR_EVERY === 0)) {
                    var last = marks[marks.length - 1].hz;
                    var re = await steadyAnchor(last, last);
                    if (re.ok) marks.push({ t: Date.now(), hz: re.r.hz });
                    else warnings.push('the drift check before ' +
                        noteLabel(step.index) + ' was not heard clearly and was ' +
                        'skipped - readings after it lean on the check before it');
                    sinceAnchor = 0;
                }
                if (adjust) {
                    var c = await converge(step, i);
                    if (!tuning) continue;
                    // The top of what the 208 plays: TOP_SILENT entries in a
                    // row not heard at all, once something above the 0 V
                    // entry has been.  Not before - a 208 erratic near 0 V
                    // must not end the run at its bottom.  What was not
                    // played is filled in with the rest.
                    if (c.silent) silentRun++;
                    else { silentRun = 0; if (step.index !== zero) heardAbove = true; }
                    if (silentRun >= TOP_SILENT && heardAbove && i < steps.length - 1) {
                        topAt = i + 1;
                        break;
                    }
                    continue;
                }
                // What this note should come back as: the drift-corrected
                // anchor, times the ideal interval from the anchor's entry to
                // this one.  Derived rather than carried forward from the last
                // reading, because carrying it forward froze it on every note
                // that was not heard - the advance sat below the miss path's
                // `continue` - and three silent notes left the band three
                // semitones low, so the notes after them were measured at
                // their true pitches and thrown away.  A note that did not
                // take froze it the same way, one semitone at a time.  Since a
                // reading is only accepted within 120 cents of this same
                // ladder, the +/-300 cent band around it cannot discard a
                // reading the run would have kept.
                var wantHz = anchorAt(marks, Date.now()) *
                             Math.pow(2, (step.index - anchor.index) / 12);
                var got = await hear(step.note, wantHz, undefined, 'sweep');
                var j = judge(step, got, Date.now(), ', left blank');
                if (!j) {
                    results.push({ index: step.index, note: step.note, cents: null });
                    if (o.onNote) o.onNote(step, null, i, steps.length);
                    continue;
                }
                results.push(j.cents === null
                    ? { index: step.index, note: step.note, cents: null }
                    : { index: step.index, note: step.note, cents: j.cents, hz: j.hz });
                previous = { hz: j.hz, index: step.index };
                if (o.onNote) o.onNote(step, results[results.length - 1], i, steps.length);
            }
        } finally {
            root.removeEventListener('pagehide', letGo);
            root.removeEventListener('beforeunload', letGo);
            if (mode) {
                root.removeEventListener('pagehide', modeDown);
                root.removeEventListener('beforeunload', modeDown);
            }
            // The note-off first, then the mode - and the mode even when the
            // note-off throws, which is what a port that has gone away does.
            try { release(); } finally { modeDown(); }
            // One of its own is closed; one the page opened is the page's.
            if (own) {
                try { stream.getTracks().forEach(function (t) { t.stop(); }); } catch (e) {}
                try { await ctx.close(); } catch (e) {}
            }
        }
        var filled = null;
        if (tuning) {
            // What was not played once the top was found, then every entry
            // that did not tune, filled in from the ones that did.
            var skipped = topAt === null ? [] : steps.slice(topAt);
            skipped.forEach(function (s) {
                results.push({ index: s.index, note: s.note, cents: null, residual: null,
                               first: null, original: start[s.index], value: table[s.index],
                               tries: 0, source: null });
            });
            var gaps = results.filter(function (r) { return r.source === null; })
                              .map(function (r) { return r.index; });
            var made = fillGaps(table, gaps, zero);
            filled = {};
            results.forEach(function (r) {
                if (r.source !== null) return;
                r.source = made.sources[r.index] || null;
                // Nothing tuned to fill it from: the value it came with.
                table[r.index] = r.source ? made.table[r.index] : start[r.index];
                r.value = table[r.index];
                r.cents = null;
                if (r.source) filled[r.index] = r.source;
            });
            var fate = function (e) { return filled[e] || 'left unchanged'; };
            fates.forEach(function (f) { warnings[f.at] += ', ' + fate(f.entry); });
            if (skipped.length) {
                var a = skipped[0].index, b = skipped[skipped.length - 1].index;
                warnings.push('three notes in a row were not heard, so the sweep stopped: ' +
                              noteLabel(a) + (a === b ? '' : ' to ' + noteLabel(b)) + ' ' + fate(a));
            }
            gaps.forEach(function (e) { if (filled[e]) fill_log(e, filled[e]); });
            // First, because every note is tuned against it, so it
            // qualifies every reading under it.
            var refHz = marks.map(function (m) { return m.hz; });
            var spread = cents(Math.max.apply(null, refHz), Math.min.apply(null, refHz));
            if (spread > REFERENCE_SPREAD_CENTS) {
                warnings.unshift('the 0 V note moved ' + spread.toFixed(1) + ' cents over the run');
            }
        }
        var out = { readings: results, warnings: warnings, anchorHz: first.hz,
                    channel: o.channel, log: log,
                    drift: marks.length > 1 ?
                        cents(marks[marks.length - 1].hz, marks[0].hz) : 0 };
        if (adjust) out.table = table;
        // The plain sweep's result is pinned (test_sweep.js), so only the
        // mode says which entry it anchored on.
        if (mode) out.anchorEntry = anchor.index;
        if (tuning) out.filled = filled;
        return out;
    };

    // The entries of `table` listed in `gaps`, filled in from the entries
    // around them that stand, never below `floor` (the 0 V entry, which
    // stands).  Between two that stand, linearly in counts; above the last
    // that stands, at the slope of its last octave - counts a semitone over
    // the twelve entries under it, or over as many as there are above the
    // floor.  Only a filled entry moves, and it is kept strictly between its
    // neighbours and inside the DAC.  Returns the table and `sources`, entry
    // -> 'interpolated' or 'extrapolated'.  With nothing standing above the
    // floor there is nothing to fill from: those entries are not filled, and
    // have no source.
    function fillGaps(table, gaps, floor) {
        var t = table.slice(), gap = {}, sources = {}, top = t.length - 1, e, k;
        gaps.forEach(function (g) { if (g > floor && g <= top) gap[g] = true; });
        for (e = floor + 1; e <= top; e++) {
            if (!gap[e]) continue;
            var a = e - 1, b = e;                   // a stands: runs are filled whole
            while (b <= top && gap[b]) b++;
            if (b <= top) {
                for (k = e; k < b; k++) {
                    t[k] = Math.round(t[a] + (t[b] - t[a]) * (k - a) / (b - a));
                    sources[k] = 'interpolated';
                }
            } else if (a > floor) {
                var base = Math.max(floor, a - 12);
                var slope = (t[a] - t[base]) / (a - base);
                for (k = e; k < b; k++) {
                    t[k] = Math.round(t[a] + slope * (k - a));
                    sources[k] = 'extrapolated';
                }
            }
            e = b;
        }
        for (e = floor + 1; e <= top; e++) {
            if (sources[e]) t[e] = Math.max(t[e], t[e - 1] + 1, 0);
        }
        for (e = top; e > floor; e--) {
            if (sources[e]) t[e] = Math.min(t[e], e === top ? DAC_TOP : t[e + 1] - 1);
        }
        return { table: t, sources: sources };
    }

    // Where the anchor sat at time t, interpolated between the times it was
    // actually measured.
    function anchorAt(marks, t) {
        if (marks.length === 1) return marks[0].hz;
        for (var i = 1; i < marks.length; i++) {
            if (t <= marks[i].t) {
                var a = marks[i - 1], b = marks[i];
                var f = (t - a.t) / Math.max(1, b.t - a.t);
                return a.hz + (b.hz - a.hz) * f;
            }
        }
        return marks[marks.length - 1].hz;
    }

    var NAMES = ['C', 'C#', 'D', 'D#', 'E', 'F', 'F#', 'G', 'G#', 'A', 'A#', 'B'];
    function noteLabel(semitone) {
        var n = semitone - 3;                       // semitone 3 is the bottom C
        return NAMES[((n % 12) + 12) % 12] + Math.floor(n / 12);
    }

    root.CALIBRATE = {
        KEY_TABLE: KEY_TABLE, FIRST_NOTE: FIRST_NOTE,
        BOTTOM_KEY_ENTRY: BOTTOM_KEY_ENTRY,
        semitoneFor: semitoneFor, entryForSemitone: entryForSemitone,
        entryFor: entryFor, plan: plan, noteLabel: noteLabel,
        MODE_FIRST_NOTE: MODE_FIRST_NOTE, modeEntryFor: modeEntryFor, modePlan: modePlan,
        fillGaps: fillGaps, FILL_CENTS: FILL_CENTS,
        measure: measure, cents: cents, yin: yin, refine: refine,
        audioTrouble: audioTrouble, channelCount: channelCount, listen: listen,
        onMidiChange: onMidiChange, portGone: portGone,
        trackChannels: trackChannels,
        midiOutputs: midiOutputs, midiInputs: midiInputs, audioInputs: audioInputs,
        Sweep: Sweep
    };
})(typeof window !== 'undefined' ? window : this);
