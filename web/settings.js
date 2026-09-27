// Settings over MIDI, the transport: push a record to the instrument,
// read it back, read what it holds, commit it, and ask who is listening.  Everything here is
// the wire; the words the page shows for it are the page's.
//
// The instrument takes NRPN on channel 16 (BUILDLIB.nrpn*), applies each
// value to its mirror as it arrives, commits only when asked with the key,
// and answers a dump with every parameter followed by the identity block,
// whose last parameter - the layout version - is how a dump is known to be
// over.  Its receive ring holds 32 packets and overflows silently, so a
// push goes out in bursts of four parameters (sixteen packets) with a
// pause between them, and a push is never trusted until a dump has read it
// back equal: that is the check that catches a dropped packet.
//
// A reply is only believed whole: every parameter a dump or an identity
// block is due to carry has to arrive (BUILDLIB.nrpnMissing), or the reply
// is refused rather than decoded with zeros where the lost values were.
//
// A record's option cells (docs/PLAN-SETTINGS-2.md) take effect at the
// next power-up, from the live option bytes the boot copies them to.  So
// `install` ends by asking for a restart when a committed cell differs
// from a live byte, and `awaitLive` waits through the USB re-enumeration
// for the keyboard to come back running them.
//
// docs/PLAN-SETTINGS.md is the protocol; src/SettingsRegression.java is
// the instrument's side of it under emulation.
var SETTINGSMIDI = (function () {
    'use strict';
    var B = (typeof BUILDLIB !== 'undefined') ? BUILDLIB : require('./buildlib.js');

    function sleep(ms, root) {
        var timers = root || (typeof window !== 'undefined' ? window : globalThis);
        return new Promise(function (done) { timers.setTimeout(done, ms); });
    }

    // Send one parameter's four Control Changes.
    function sendParam(output, param, value) {
        B.nrpnMessages(param, value).forEach(function (m) { output.send(m); });
    }

    // Every parameter of a record, in bursts.  `opts.burst` parameters per
    // burst (default 4, sixteen packets) and `opts.gap` milliseconds between
    // bursts (default 3).  `opts.onProgress(sent, total)` if given.
    function push(output, record, opts) {
        return pushParams(output, B.nrpnParamsOf(record), opts);
    }
    // The same for a list of [param, value] pairs: what `changes` gives.
    function pushParams(output, params, opts) {
        opts = opts || {};
        var burst = opts.burst || 4, gap = opts.gap === undefined ? 3 : opts.gap;
        var i = 0;
        function next() {
            if (i >= params.length) return Promise.resolve(params.length);
            for (var n = 0; n < burst && i < params.length; n++, i++) {
                sendParam(output, params[i][0], params[i][1]);
            }
            if (opts.onProgress) opts.onProgress(i, params.length);
            return sleep(gap, opts.timers).then(next);
        }
        return next();
    }

    // Listen on an input for decoded NRPN pairs until `until(pairs)` says
    // stop or `timeoutMs` passes without the block completing.  Resolves
    // with the pairs; rejects with Error('no reply') on the timeout, and
    // the caller decides what that means.
    // `start` sends whatever asks for the reply, once the listener is in
    // place.  A port that has gone away throws from send(), and inside the
    // promise that is a 'no reply' like any other silence rather than a
    // throw out of the transport, which left the page's buttons disabled
    // and the listener installed until its timeout.
    function collect(input, until, timeoutMs, timers, start) {
        timers = timers || (typeof window !== 'undefined' ? window : globalThis);
        return new Promise(function (resolve, reject) {
            var dec = B.nrpnDecoder(), pairs = [], done = false;
            var previous = input.onmidimessage;
            var timer = timers.setTimeout(function () { finish(false); }, timeoutMs);
            function finish(ok) {
                if (done) return;
                done = true;
                timers.clearTimeout(timer);
                input.onmidimessage = previous || null;
                if (ok) resolve(pairs); else reject(new Error('no reply'));
            }
            input.onmidimessage = function (e) {
                var d = e.data;
                for (var i = 0; i + 2 < d.length + 1 && i + 2 <= d.length; i += 3) {
                    var got = dec.feed(d[i], d[i + 1], d[i + 2]);
                    if (got) {
                        pairs.push([got.param, got.value]);
                        if (until(pairs)) { finish(true); return; }
                    }
                }
            };
            if (start) {
                try { start(); } catch (e) { finish(false); }
            }
        });
    }

    function hasVersion(pairs) {
        return pairs.length > 0 && pairs[pairs.length - 1][0] === B.NRPN_IDENTITY.layoutVersion;
    }

    // Who is listening: the identity block, or Error('no reply').  The
    // block carries `missing`, the parameters of it that did not arrive.
    function identity(output, input, timeoutMs, timers) {
        return collect(input, hasVersion, timeoutMs || 2000, timers, function () {
            sendParam(output, B.NRPN_COMMANDS.identity, 0);
        }).then(function (pairs) {
            var id = B.nrpnIdentity(pairs);
            id.missing = B.nrpnMissing(pairs, true);
            return id;
        });
    }

    // Everything the instrument holds: the pairs of a full dump, with the
    // identity block decoded beside them and `missing`, the parameters of
    // the dump that did not arrive.
    function dump(output, input, timeoutMs, timers) {
        return collect(input, hasVersion, timeoutMs || 5000, timers, function () {
            sendParam(output, B.NRPN_COMMANDS.dump, 0);
        }).then(function (pairs) {
            return { pairs: pairs, identity: B.nrpnIdentity(pairs), missing: B.nrpnMissing(pairs) };
        });
    }

    function commit(output) { sendParam(output, B.NRPN_COMMANDS.commit, B.NRPN_COMMANDS.commitKey); }
    function restart(output) { sendParam(output, B.NRPN_COMMANDS.restart, B.NRPN_COMMANDS.restartKey); }
    function reload(output) { sendParam(output, B.NRPN_COMMANDS.reload, 0); }
    function defaults(output) { sendParam(output, B.NRPN_COMMANDS.defaults, 0); }

    // Calibration mode (web/calibrate.js): on with its key, off with
    // anything else.  A run ends with endCalibration - the mode off and the
    // mirror reloaded from flash - so nothing a run wrote outlives it: what
    // it found goes into the page's build, and reaches the keyboard only
    // through a send or a flash.  The reload is tried even when the mode's
    // own message throws.
    function calibrationMode(output, on) {
        sendParam(output, B.NRPN_COMMANDS.calibrate, on ? B.NRPN_COMMANDS.calibrateKey : 0);
    }
    function endCalibration(output) {
        try { calibrationMode(output, false); } finally { reload(output); }
    }
    // One pitch-table entry into the live mirror, never committed.  One
    // parameter is four packets, well inside the ring, and it resolves
    // after the pause a push leaves between bursts (`opts.gap`, default 3).
    var PITCH = B.NRPN_SECTIONS.filter(function (s) { return s.offset === B.SETTINGS_LAYOUT.pitch; })[0];
    function writePitch(output, entry, value, opts) {
        opts = opts || {};
        if (!(entry >= 0 && entry < PITCH.count && entry === Math.floor(entry)) ||
            !(value >= 0 && value <= 0xFFF && value === Math.floor(value))) {
            return Promise.reject(new Error('pitch entry ' + entry + ' = ' + value + ' is out of range'));
        }
        sendParam(output, PITCH.base + entry, value);
        return sleep(opts.gap === undefined ? 3 : opts.gap, opts.timers);
    }

    // The parameters of a record that a dump did not read back the same:
    // [param, sent, got] each.  A parameter the dump left out counts too.
    function differences(record, pairs) {
        var got = {};
        pairs.forEach(function (p) { got[p[0]] = p[1]; });
        var out = [];
        B.nrpnParamsOf(record).forEach(function (p) {
            if (got[p[0]] !== p[1]) out.push([p[0], p[1], got[p[0]] === undefined ? null : got[p[0]]]);
        });
        return out;
    }

    // The parameters of `record` that differ from `from`, [param, value]
    // each, in the dump order: what a keyboard known to hold `from` has to
    // be sent to hold `record`.  The header is not among them - no
    // parameter reaches it - so a read's record, whose header is zero,
    // compares with a built one on the payload alone.
    function changes(from, record) {
        return B.nrpnParamsOf(record).filter(function (p) { return B.nrpnValueOf(from, p[0]) !== p[1]; });
    }

    // The image marker a record was made for, out of its bytes.
    function markerOf(record) { return ((record[0x10] & 0xFF) << 8) | (record[0x11] & 0xFF); }

    // A refusal with a name on it, so the page can say which.
    function fail(reason, extra) {
        var err = new Error(reason);
        err.reason = reason;
        if (extra) Object.keys(extra).forEach(function (k) { err[k] = extra[k]; });
        return Promise.reject(err);
    }

    var LAYOUT = 2;

    // What the instrument holds, for the page: one dump, decoded into a
    // record-shaped array and its fields by name, with the identity block
    // beside them, the live option bytes, and `pending` - the options whose
    // cell the keyboard holds but does not run yet, which a restart would
    // apply.  Rejects with `reason` 'no reply' or 'wrong layout' - a map
    // this page does not know is not read into it - or 'incomplete' (with
    // `missing`) when the dump lost parameters on the way.
    function read(output, input, opts) {
        opts = opts || {};
        return dump(output, input, opts.timeout, opts.timers).catch(function () {
            return fail('no reply');
        }).then(function (d) {
            if (d.identity.layoutVersion !== LAYOUT) return fail('wrong layout', { identity: d.identity });
            if (d.missing.length) return fail('incomplete', { identity: d.identity, missing: d.missing });
            var record = B.nrpnRecordOf(d.pairs), live = B.nrpnLiveOf(d.pairs);
            return { identity: d.identity, pairs: d.pairs, record: record, fields: B.settingsFields(record),
                     live: live, pending: B.pendingOptions(record, live) };
        });
    }

    // After a restart the keyboard's ports go away with the USB
    // re-enumeration and come back.  `getPorts()` answers with fresh
    // {output, input} or null, and is asked every `opts.every` ms (default
    // 500) for up to `opts.limit` ms (default 20000) until a read answers
    // with live bytes equal to the record's option cells.  Resolves with
    // that read.  Rejects with `reason` 'no reply' when nothing answered in
    // time, or 'not applied' (with `read`) when the keyboard came back and
    // still runs other options than the record's - a record it refused.
    function awaitLive(getPorts, record, opts) {
        opts = opts || {};
        var every = opts.every || 500, limit = opts.limit || 20000, spent = 0, last = null;
        function attempt() {
            return Promise.resolve().then(getPorts).then(function (ports) {
                if (!ports) return null;
                return read(ports.output, ports.input, { timeout: opts.timeout || 1500, timers: opts.timers })
                    .then(function (r) { return r; }, function () { return null; });
            }).then(function (r) {
                if (r && r.live && !B.pendingOptions(record, r.live).length) return r;
                if (r) last = r;
                spent += every;
                if (spent >= limit) return fail(last ? 'not applied' : 'no reply', last ? { read: last } : {});
                return sleep(every, opts.timers).then(attempt);
            });
        }
        return attempt();
    }

    // The whole procedure: identity, push, dump, compare, commit, identity,
    // and then a restart when a committed option cell differs from the
    // keyboard's live byte.  Resolves with the final identity, carrying
    // `restarted` and `pending` (the options the restart applies; the
    // caller waits with awaitLive).  Rejects with an Error whose `reason`
    // is one of 'no reply', 'wrong layout', 'wrong image', 'mismatch'
    // (with `differences` and `missing`: a value read back otherwise, or a
    // reply - the verifying dump or an identity block - that lost
    // parameters, which a retry answers either way), or 'not written' (with
    // `state`), so the page can say which - they have different fixes.
    //
    // `opts.params`, [param, value] pairs, pushes those alone rather than
    // every parameter: a live send's, the parameters that differ from what
    // the keyboard is known to hold (`changes`).  Everything after the push
    // is the same.  The verifying dump is still compared with the whole
    // record, so a keyboard that did not hold what the page thought is a
    // mismatch rather than a record saved half one and half the other.
    function install(output, input, record, opts) {
        opts = opts || {};
        // `before`, the identity the send started from: a commit is only
        // believed when the generation moved past it, since the commit state
        // a lost request leaves is whatever the last commit left.  `pushed`:
        // from here the mirror holds values that went live on arrival, and a
        // send that fails from here on reloads, so the keyboard does not play
        // half a record nobody saved.
        var live = null, before = null, pushed = false;
        return identity(output, input, opts.timeout, opts.timers).catch(function () {
            return fail('no reply');
        }).then(function (id) {
            if (id.layoutVersion !== LAYOUT) return fail('wrong layout', { identity: id });
            if (id.missing.length) return fail('mismatch', { identity: id, differences: [], missing: id.missing });
            if (id.imageMarker !== markerOf(record)) return fail('wrong image', { identity: id });
            if (opts.onStage) opts.onStage('push');
            before = id; pushed = true;
            return opts.params ? pushParams(output, opts.params, opts) : push(output, record, opts);
        }).then(function () {
            if (opts.onStage) opts.onStage('verify');
            return dump(output, input, opts.timeout, opts.timers).catch(function () {
                return fail('no reply');
            });
        }).then(function (d) {
            // Every value read back equal, and the dump whole: without all
            // sixteen live bytes there is no telling whether a restart is
            // owed, so a dump short of one is not good enough to commit on.
            var diff = differences(record, d.pairs);
            if (diff.length || d.missing.length) return fail('mismatch', { differences: diff, missing: d.missing });
            live = B.nrpnLiveOf(d.pairs);
            if (opts.onStage) opts.onStage('commit');
            commit(output);
            // The commit lands on the instrument's next scan; the identity
            // that follows carries its state.
            return sleep(opts.settle === undefined ? 50 : opts.settle, opts.timers);
        }).then(function () {
            return identity(output, input, opts.timeout, opts.timers).catch(function () {
                return fail('no reply');
            });
        }).then(function (id) {
            if (id.missing.length) return fail('mismatch', { identity: id, differences: [], missing: id.missing });
            if (id.commitState !== 2 || id.generation === before.generation) {
                return fail('not written', { identity: id, state: id.commitState });
            }
            id.pending = B.pendingOptions(record, live);
            id.restarted = id.pending.length > 0;
            if (id.restarted) {
                if (opts.onStage) opts.onStage('restart');
                restart(output);
            }
            return id;
        }).catch(function (err) {
            if (pushed) {
                try { reload(output); } catch (e) { /* the port is gone: nothing to reload through */ }
            }
            throw err;
        });
    }

    return {
        push: push, pushParams: pushParams, dump: dump, read: read, identity: identity, commit: commit,
        reload: reload, defaults: defaults, differences: differences, changes: changes,
        install: install, restart: restart,
        awaitLive: awaitLive, markerOf: markerOf, sendParam: sendParam, LAYOUT: LAYOUT,
        calibrationMode: calibrationMode, endCalibration: endCalibration, writePitch: writePitch
    };
})();
if (typeof module !== 'undefined' && module.exports) module.exports = SETTINGSMIDI;
