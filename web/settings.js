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
        opts = opts || {};
        var burst = opts.burst || 4, gap = opts.gap === undefined ? 3 : opts.gap;
        var params = B.nrpnParamsOf(record);
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
    function collect(input, until, timeoutMs, timers) {
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
        });
    }

    function hasVersion(pairs) {
        return pairs.length > 0 && pairs[pairs.length - 1][0] === B.NRPN_IDENTITY.layoutVersion;
    }

    // Who is listening: the identity block, or Error('no reply').
    function identity(output, input, timeoutMs, timers) {
        var waiting = collect(input, hasVersion, timeoutMs || 2000, timers);
        sendParam(output, B.NRPN_COMMANDS.identity, 0);
        return waiting.then(B.nrpnIdentity);
    }

    // Everything the instrument holds: the pairs of a full dump, with the
    // identity block decoded beside them.
    function dump(output, input, timeoutMs, timers) {
        var waiting = collect(input, hasVersion, timeoutMs || 5000, timers);
        sendParam(output, B.NRPN_COMMANDS.dump, 0);
        return waiting.then(function (pairs) {
            return { pairs: pairs, identity: B.nrpnIdentity(pairs) };
        });
    }

    function commit(output) { sendParam(output, B.NRPN_COMMANDS.commit, B.NRPN_COMMANDS.commitKey); }
    function reload(output) { sendParam(output, B.NRPN_COMMANDS.reload, 0); }
    function defaults(output) { sendParam(output, B.NRPN_COMMANDS.defaults, 0); }

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

    // The image marker a record was made for, out of its bytes.
    function markerOf(record) { return ((record[0x10] & 0xFF) << 8) | (record[0x11] & 0xFF); }

    // A refusal with a name on it, so the page can say which.
    function fail(reason, extra) {
        var err = new Error(reason);
        err.reason = reason;
        if (extra) Object.keys(extra).forEach(function (k) { err[k] = extra[k]; });
        return Promise.reject(err);
    }

    // What the instrument holds, for the page: one dump, decoded into a
    // record-shaped array and its fields by name, with the identity block
    // beside them.  Rejects with `reason` 'no reply' or 'wrong layout' - a
    // map this page does not know is not read into it.
    function read(output, input, opts) {
        opts = opts || {};
        return dump(output, input, opts.timeout, opts.timers).catch(function () {
            return fail('no reply');
        }).then(function (d) {
            if (d.identity.layoutVersion !== 1) return fail('wrong layout', { identity: d.identity });
            var record = B.nrpnRecordOf(d.pairs);
            return { identity: d.identity, pairs: d.pairs, record: record, fields: B.settingsFields(record) };
        });
    }

    // The whole procedure: identity, push, dump, compare, commit, identity.
    // Resolves with the final identity.  Rejects with an Error whose
    // `reason` is one of 'no reply', 'wrong layout', 'wrong image',
    // 'mismatch' (with `differences`), or 'not written' (with `state`), so
    // the page can say which - they have different fixes.
    function install(output, input, record, opts) {
        opts = opts || {};
        return identity(output, input, opts.timeout, opts.timers).catch(function () {
            return fail('no reply');
        }).then(function (id) {
            if (id.layoutVersion !== 1) return fail('wrong layout', { identity: id });
            if (id.imageMarker !== markerOf(record)) return fail('wrong image', { identity: id });
            if (opts.onStage) opts.onStage('push');
            return push(output, record, opts);
        }).then(function () {
            if (opts.onStage) opts.onStage('verify');
            return dump(output, input, opts.timeout, opts.timers).catch(function () {
                return fail('no reply');
            });
        }).then(function (d) {
            var diff = differences(record, d.pairs);
            if (diff.length) return fail('mismatch', { differences: diff });
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
            if (id.commitState !== 2) return fail('not written', { identity: id, state: id.commitState });
            return id;
        });
    }

    return {
        push: push, dump: dump, read: read, identity: identity, commit: commit, reload: reload,
        defaults: defaults, differences: differences, install: install,
        markerOf: markerOf, sendParam: sendParam
    };
})();
if (typeof module !== 'undefined' && module.exports) module.exports = SETTINGSMIDI;
