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

    function measure(samples, rate, fMin, fMax) {
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
    function heard(r) {
        return !!(r && r.ok && r.clarity >= MIN_CLARITY && r.rms >= MIN_RMS);
    }

    // --- talking to the instrument ---------------------------------------
    // Web MIDI without sysex: this asks for nothing the browser has to warn
    // about, and nothing the instrument treats as a command.
    var midiAccess = null, midiWatchers = [];
    function midiOutputs() {
        if (!root.navigator || !root.navigator.requestMIDIAccess) {
            return Promise.reject(new Error(
                'This browser has no Web MIDI. Chrome and Edge have it, and ' +
                'Firefox after installing the permission add-on it offers; ' +
                'Safari has none at all.'));
        }
        return root.navigator.requestMIDIAccess({ sysex: false }).then(function (access) {
            // Ports come and go while the page is open and the list is built
            // once, so without this a keyboard unplugged after the list was
            // made stays in the dropdown looking perfectly selectable.
            if (access !== midiAccess) {
                midiAccess = access;
                access.onstatechange = function (e) {
                    midiWatchers.forEach(function (cb) {
                        try { cb(e && e.port); } catch (err) {}
                    });
                };
            }
            var out = [];
            access.outputs.forEach(function (p) { out.push(p); });
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
    var MAX_CHANNELS = 32;          // asked for; the device gives what it has

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

    function Sweep(opts) {
        this.opts = opts;
        this.stopped = false;
    }

    Sweep.prototype.stop = function () { this.stopped = true; };

    Sweep.prototype.run = async function () {
        var o = this.opts, self = this;
        var steps = plan(o.low, o.high, o.octaveTerm);
        if (!steps.length) throw new Error('Nothing to sweep.');

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
        var ctx = new (root.AudioContext || root.webkitAudioContext)();
        var analyser = ctx.createAnalyser();
        analyser.fftSize = WINDOW;
        analyser.smoothingTimeConstant = 0;
        var source = ctx.createMediaStreamSource(stream);
        // One channel of the interface, not a mix of it.  A splitter keeps
        // them apart - its channelInterpretation is 'discrete', so channel 7
        // arrives as channel 7 rather than being folded into a stereo pair.
        var count = Math.max(seen.got, 1);
        var want = Math.min(Math.max(0, o.audioChannel || 0), count - 1);
        if (o.onChannels) o.onChannels(count, want);
        if (count > 1) {
            var splitter = ctx.createChannelSplitter(count);
            source.connect(splitter);
            splitter.connect(analyser, want, 0);
        } else {
            source.connect(analyser);
        }
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

        async function hear(note, expectHz, channel, what) {
            var ch = channel === undefined ? o.channel : channel;
            release();
            await sleep(GAP_MS);
            send(o.output, 0x90, note, o.velocity || 100, ch);
            held = note;
            heldOn = ch;
            await sleep(SETTLE_MS + fill);
            analyser.getFloatTimeDomainData(buf);
            release();
            var lo = expectHz ? expectHz * Math.pow(2, -3 / 12) : 18;
            var hi = expectHz ? expectHz * Math.pow(2, 3 / 12) : 6000;
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
            var e = entryFor(note, o.octaveTerm);
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
                halfDrift: r ? r.drift : null,
                clarity: r && r.ok ? r.clarity : null,
                rms: r ? r.rms : null,
                why: r && r.ok ? '' : (r ? r.why : 'not measured')
            };
            log.push(row);
            if (o.onReading) o.onReading(row);
        }

        var anchor = steps[0], results = [], marks = [], warnings = [];
        var log = [], t0 = Date.now();
        var previous = null;
        try {
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
            async function probe(ch) {
                var hiNote = anchor.note + 24;
                var hiEntry = entryFor(hiNote, o.octaveTerm);
                if (!hiEntry) return false;
                var apart = 100 * (hiEntry.index - anchor.index);
                // Both measurements search the whole range.  Handing the second
                // one the answer as its expected pitch narrows YIN to a band
                // +/-300 cents around exactly the interval being tested - the
                // same width as the test - so anything periodic in that band
                // passes by construction.  Broadband noise sailed through it
                // while a real tone on the wrong channel was caught, which is
                // the wrong way round.  And both have to be heard clearly, not
                // merely found: the point of the probe is to establish that
                // something is listening.
                var lo = await hear(anchor.note, null, ch, 'probe');
                var hi = await hear(hiNote, null, ch, 'probe');
                return heard(lo) && heard(hi) &&
                       Math.abs(cents(hi.hz, lo.hz) - apart) < 300;
            }

            if (o.channel === null || o.channel === undefined) {
                var found = null;
                for (var ch = 0; ch < 16 && found === null; ch++) {
                    if (self.stopped) throw new Error('Stopped.');
                    if (o.onProbe) o.onProbe(ch, false);
                    if (await probe(ch)) found = ch;
                }
                if (found === null) {
                    throw new Error('No MIDI channel moved the pitch. Check the ' +
                        'keyboard is on the chosen MIDI port and still plugged in, ' +
                        'and that the 208 is droning into the chosen audio input ' +
                        'and channel.');
                }
                o.channel = found;
                if (o.onChannel) o.onChannel(found);
            } else {
                if (o.onProbe) o.onProbe(o.channel, true);
                if (self.stopped) throw new Error('Stopped.');
                if (!(await probe(o.channel))) {
                    throw new Error('MIDI channel ' + (o.channel + 1) + ' did not ' +
                        'move the pitch, so nothing is listening there. Check the ' +
                        'keyboard is on the chosen MIDI port and still plugged in, ' +
                        'and that the 208 is droning into the chosen audio input ' +
                        'and channel. Set the channel to Auto to search for it.');
                }
            }

            var first = await hear(anchor.note, null, undefined, 'anchor');
            if (!heard(first)) {
                throw new Error('The bottom C did not come back as a steady tone' +
                    (first.ok ? ' (clarity ' + first.clarity.toFixed(2) + ', level ' +
                                first.rms.toFixed(4) + ')' : ' (' + first.why + ')') +
                    '. Every reading is measured against it, so the sweep stops ' +
                    'here rather than anchoring on noise. Check the 208 is droning ' +
                    'into the chosen audio input and channel.');
            }
            marks.push({ t: Date.now(), hz: first.hz });

            for (var i = 0; i < steps.length; i++) {
                if (self.stopped) throw new Error('Stopped.');
                var step = steps[i];
                if (i > 0 && i % ANCHOR_EVERY === 0) {
                    var re = await hear(anchor.note, marks[marks.length - 1].hz,
                                       undefined, 'anchor');
                    if (heard(re)) marks.push({ t: Date.now(), hz: re.hz });
                    else warnings.push('the drift check before ' +
                        noteLabel(step.index) + ' was not heard clearly and was ' +
                        'skipped - readings after it lean on the check before it');
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
                var t = Date.now();
                if (!heard(got)) {
                    warnings.push(noteLabel(step.index) + ': ' +
                        (got.ok && got.rms < MIN_RMS ? 'too quiet to read'
                                                     : 'not heard clearly') + ', left blank');
                    results.push({ index: step.index, note: step.note, cents: null });
                    if (o.onNote) o.onNote(step, null, i, steps.length);
                    continue;
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
                    warnings.push(noteLabel(step.index) + ' came out ' +
                        Math.abs(cents(got.hz, previous.hz)).toFixed(0) +
                        ' cents BELOW ' + noteLabel(previous.index) + ' (' +
                        previous.hz.toFixed(2) + ' Hz then ' + got.hz.toFixed(2) +
                        ' Hz) - the note did not take');
                }
                // A reading more than a semitone out is not a tracking error,
                // it is the wrong note: an alternate tuning is selected, or a
                // note was missed.  Recorded as blank and reported, rather
                // than folded in as if it were a measurement.
                //
                // A note that did not take is blanked on the same grounds and
                // not on its size.  It lands about 100 cents out - just inside
                // the 120 the threshold allows - so warning about it and then
                // keeping it wrote a +100 cent correction into the table, one
                // or two DAC counts clear of its neighbour, past the collision
                // guard and past validateCal, and shipped a semitone playing
                // very nearly its neighbour's pitch.
                if (Math.abs(off) > 120 || backwards) {
                    if (!backwards) {
                        warnings.push(noteLabel(step.index) + ': ' + off.toFixed(0) +
                                      ' cents out, ignored');
                    }
                    results.push({ index: step.index, note: step.note, cents: null });
                } else {
                    results.push({ index: step.index, note: step.note, cents: off,
                                   hz: got.hz });
                }
                if (got.drift !== null && Math.abs(got.drift) > 8) {
                    warnings.push(noteLabel(step.index) + ' moved ' +
                        got.drift.toFixed(1) + ' cents while being measured');
                }
                previous = { hz: got.hz, index: step.index };
                if (o.onNote) o.onNote(step, results[results.length - 1], i, steps.length);
            }
        } finally {
            root.removeEventListener('pagehide', letGo);
            root.removeEventListener('beforeunload', letGo);
            release();
            try { stream.getTracks().forEach(function (t) { t.stop(); }); } catch (e) {}
            try { await ctx.close(); } catch (e) {}
        }
        return { readings: results, warnings: warnings, anchorHz: first.hz,
                 channel: o.channel, log: log,
                 drift: marks.length > 1 ?
                     cents(marks[marks.length - 1].hz, marks[0].hz) : 0 };
    };

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
        measure: measure, cents: cents, yin: yin, refine: refine,
        audioTrouble: audioTrouble, channelCount: channelCount,
        onMidiChange: onMidiChange, portGone: portGone,
        trackChannels: trackChannels,
        midiOutputs: midiOutputs, audioInputs: audioInputs,
        Sweep: Sweep
    };
})(typeof window !== 'undefined' ? window : this);
