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
    // The lean is never more than 2.5% of the gap to the neighbour and is
    // usually under 1% - the key table steps 40.33 units where a table entry
    // is 40.33 wide, and only every third one divides exactly.  Round to the
    // entry it sits on: below a cent of the neighbour comes with it, which is
    // well under the 2.5 cent step the table is quantised to anyway.
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

    // The sweep, as a list.  Built from the arithmetic rather than written
    // out, so a changed assumption cannot leave a stale table behind.
    function plan(lowSemitone, highSemitone, octaveTerm) {
        var out = [], seen = {};
        for (var note = FIRST_NOTE; note < FIRST_NOTE + KEY_TABLE.length; note++) {
            var e = entryFor(note, octaveTerm);
            if (!e || e.index < lowSemitone || e.index > highSemitone) continue;
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

    // --- talking to the instrument ---------------------------------------
    // Web MIDI without sysex: this asks for nothing the browser has to warn
    // about, and nothing the instrument treats as a command.
    function midiOutputs() {
        if (!root.navigator || !root.navigator.requestMIDIAccess) {
            return Promise.reject(new Error(
                'This browser has no Web MIDI. Chrome and Edge have it; Safari and ' +
                'Firefox do not, so the sweep needs one of those two.'));
        }
        return root.navigator.requestMIDIAccess({ sysex: false }).then(function (access) {
            var out = [];
            access.outputs.forEach(function (p) { out.push(p); });
            return out;
        });
    }

    function audioInputs() {
        return root.navigator.mediaDevices.enumerateDevices().then(function (all) {
            return all.filter(function (d) { return d.kind === 'audioinput'; });
        });
    }

    function sleep(ms) {
        return new Promise(function (done) { root.setTimeout(done, ms); });
    }

    // A note, on every channel unless one is named.  The handler compares the
    // message channel against the instrument's own and ignores the rest, so
    // sixteen note-ons sound one note - which saves asking for a setting that
    // is only discoverable by trying it.
    function send(out, status, note, vel, channel) {
        if (channel === null || channel === undefined) {
            for (var c = 0; c < 16; c++) out.send([status | c, note, vel]);
        } else {
            out.send([status | (channel & 15), note, vel]);
        }
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
    var SETTLE_MS = 90;

    function Sweep(opts) {
        this.opts = opts;
        this.stopped = false;
    }

    Sweep.prototype.stop = function () { this.stopped = true; };

    Sweep.prototype.run = async function () {
        var o = this.opts, self = this;
        var steps = plan(o.low, o.high, o.octaveTerm);
        if (!steps.length) throw new Error('Nothing to sweep.');

        var stream = await root.navigator.mediaDevices.getUserMedia({
            audio: {
                deviceId: o.deviceId ? { exact: o.deviceId } : undefined,
                echoCancellation: false, autoGainControl: false,
                noiseSuppression: false, channelCount: 1
            }
        });
        var ctx = new (root.AudioContext || root.webkitAudioContext)();
        var analyser = ctx.createAnalyser();
        analyser.fftSize = WINDOW;
        analyser.smoothingTimeConstant = 0;
        ctx.createMediaStreamSource(stream).connect(analyser);
        var buf = new Float32Array(analyser.fftSize);
        var rate = ctx.sampleRate;
        // The buffer is a rolling window, so it has to fill with the new note
        // before it is read - otherwise the reading is part of the note
        // before it, which on a sweep is a consistent, plausible, wrong answer.
        var fill = 1000 * analyser.fftSize / rate;
        var held = null;

        function release() {
            if (held !== null) { send(o.output, 0x80, held, 0, o.channel); held = null; }
        }

        async function hear(note, expectHz) {
            release();
            send(o.output, 0x90, note, o.velocity || 100, o.channel);
            held = note;
            await sleep(SETTLE_MS + fill);
            analyser.getFloatTimeDomainData(buf);
            release();
            var lo = expectHz ? expectHz * Math.pow(2, -3 / 12) : 18;
            var hi = expectHz ? expectHz * Math.pow(2, 3 / 12) : 6000;
            return measure(buf, rate, lo, hi);
        }

        var anchor = steps[0], results = [], marks = [], warnings = [];
        try {
            var first = await hear(anchor.note, null);
            if (!first.ok) {
                throw new Error('Nothing heard on the audio input (' + first.why +
                                '). Check the 208 is droning and patched to the input.');
            }
            marks.push({ t: Date.now(), hz: first.hz });
            var expect = first.hz;

            for (var i = 0; i < steps.length; i++) {
                if (self.stopped) throw new Error('Stopped.');
                var step = steps[i];
                if (i > 0 && i % ANCHOR_EVERY === 0) {
                    var re = await hear(anchor.note, marks[marks.length - 1].hz);
                    if (re.ok) marks.push({ t: Date.now(), hz: re.hz });
                }
                var want = first.hz * Math.pow(2, (step.index - anchor.index) / 12);
                var got = await hear(step.note, i === 0 ? first.hz : expect);
                var t = Date.now();
                // Clarity alone passes noise that happens to be periodic, and
                // an input left unpatched is mostly hum - which is periodic.
                // The level has to be there too.
                if (!got.ok || got.clarity < 0.55 || got.rms < 0.002) {
                    warnings.push(noteLabel(step.index) + ': ' +
                        (got.ok && got.rms < 0.002 ? 'too quiet to read'
                                                   : 'not heard clearly') + ', left blank');
                    results.push({ index: step.index, note: step.note, cents: null });
                    if (o.onNote) o.onNote(step, null, i, steps.length);
                    continue;
                }
                // Drift-corrected reference: where the anchor was at this moment.
                var ref = anchorAt(marks, t);
                var off = cents(got.hz, ref) - 100 * (step.index - anchor.index);
                // A reading more than a semitone out is not a tracking error,
                // it is the wrong note: an alternate tuning is selected, or a
                // note was missed.  Recorded as blank and reported, rather
                // than folded in as if it were a measurement.
                if (Math.abs(off) > 120) {
                    warnings.push(noteLabel(step.index) + ': ' + off.toFixed(0) +
                                  ' cents out, ignored');
                    results.push({ index: step.index, note: step.note, cents: null });
                } else {
                    results.push({ index: step.index, note: step.note, cents: off,
                                   hz: got.hz });
                }
                expect = got.hz * Math.pow(2, 1 / 12);
                if (o.onNote) o.onNote(step, results[results.length - 1], i, steps.length);
            }
        } finally {
            release();
            try { stream.getTracks().forEach(function (t) { t.stop(); }); } catch (e) {}
            try { await ctx.close(); } catch (e) {}
        }
        return { readings: results, warnings: warnings, anchorHz: first.hz,
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
        entryFor: entryFor, plan: plan, noteLabel: noteLabel,
        measure: measure, cents: cents, yin: yin, refine: refine,
        midiOutputs: midiOutputs, audioInputs: audioInputs,
        Sweep: Sweep
    };
})(typeof window !== 'undefined' ? window : this);
