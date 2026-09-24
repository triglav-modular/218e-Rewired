// The settings transport against a fake instrument that behaves as the
// firmware does under src/SettingsRegression.java: every NRPN value lands
// in its mirror at once, a commit needs its key and takes effect on the
// next scan, a dump answers with every parameter and then the identity
// block, and an identity request answers with the block alone.  The wire
// order, the bursts, the checks, each way `install` can refuse, and what
// `read` hands the page are what this file holds.
//
//   node web/test_settingsmidi.js
'use strict';
var B = require('./buildlib.js');
var M = require('./settings.js');

// A silent early exit is a failure: an awaited promise that never settles
// lets node drain its loop and exit 0 without the last line.
process.exitCode = 1;
var failures = 0;
function check(name, ok, detail) {
    console.log((ok ? 'ok    ' : 'FAIL  ') + name + (ok || !detail ? '' : '  - ' + detail));
    if (!ok) failures++;
}

// A record to push, with something in every section.
var tables = { pitch_remap: [], tuning_slot0: [], tuning_slot1: [], tuning_slot2: [],
               tuning_period_keys: [12, 12, 7], arp_pattern_bank: [], arp_pattern_len: [] };
for (var i = 0; i < 79; i++) tables.pitch_remap.push(485 + 40 * i);
for (var k = 0; k < 32; k++) {
    tables.tuning_slot0.push(500 + 40 * k); tables.tuning_slot1.push(510 + 40 * k); tables.tuning_slot2.push(520 + 40 * k);
}
[0x12345678, 0x80000001, 0xDEADBEEF].forEach(function (m, n) {
    tables.arp_pattern_bank.push(m & 0xFFFF, Math.floor(m / 65536) & 0xFFFF);
    tables.arp_pattern_len.push(8 * (n + 1));
});
// Knob 2 on swing and the sequencer off: two option cells away from a
// keyboard that booted with everything at zero, so a send restarts it.
var record = B.settingsRecord({ chord_hold_scans: 200, knob2: 2, sequencer: 0 }, tables, true, 0xB007, 484, 1);
// And one whose option cells are all zero, which changes nothing live.
var zeroOptions = { latching_arp: 0, knob1: 0, knob2: 0, knob3: 0, knob4: 0, sequencer: 0, clock_divide: 0,
                    pressure_fix: 0, pressure_portamento: 0, quantize_presets: 0, portamento_in: 0 };
var quietRecord = B.settingsRecord(Object.assign({ chord_hold_scans: 200 }, zeroOptions), tables, true, 0xB007, 484, 1);

// Timers the test drives itself, so nothing here waits on a real clock.
function fakeTimers() {
    var queue = [], now = 0, id = 0;
    return {
        setTimeout: function (fn, ms) { queue.push({ at: now + ms, fn: fn, id: ++id }); return id; },
        clearTimeout: function (h) { queue = queue.filter(function (q) { return q.id !== h; }); },
        // Run timers as they come due, in order, until `until` settles - a
        // promise chain registers its next timer only after a microtask, so
        // an empty queue is not the end while something is still awaited.
        // With no promise given, run until the queue is empty.
        run: function (until) {
            var settled = !until;
            if (until) until.then(function () { settled = true; }, function () { settled = true; });
            return new Promise(function (done) {
                (function tick() {
                    if (settled && !queue.length) { done(); return; }
                    if (queue.length) {
                        queue.sort(function (a, b) { return a.at - b.at; });
                        var q = queue.shift(); now = q.at; q.fn();
                    }
                    setImmediate(tick);
                })();
            });
        }
    };
}

// The instrument.  `mirror` is a record-shaped array; `generation`, `slot`
// and `state` are what the identity block reports; `live` is the sixteen
// option bytes as booted, which only a restart refreshes from the mirror.
function fakeInstrument(options) {
    options = options || {};
    var mirror = [], live = [];
    for (var b = 0; b < 0x2a8; b++) mirror.push(0);
    for (var v = 0; v < 16; v++) live.push(0);
    var dec = B.nrpnDecoder();
    var inst = {
        mirror: mirror, live: live, marker: options.marker === undefined ? 0xB007 : options.marker,
        layout: options.layout === undefined ? 2 : options.layout, version: options.version,
        state: 0, slot: 0xff, generation: 0, received: [], sent: [], scans: 0, restarts: 0,
        drop: options.drop || null,     // a parameter number to lose on the wire
        lose: options.lose || [],       // parameter numbers lost on the way back
        refuseCommit: !!options.refuseCommit
    };
    var input = { onmidimessage: null };
    function reply(param, value) {
        if (inst.lose.indexOf(param) >= 0) return;
        B.nrpnMessages(param, value).forEach(function (m) {
            inst.sent.push(m);
            if (input.onmidimessage) input.onmidimessage({ data: m });
        });
    }
    function identityBlock() {
        reply(0x3f76, inst.version === undefined ? 0x300 : inst.version);
        reply(0x3f77, inst.marker >>> 14); reply(0x3f78, 484); reply(0x3f79, inst.slot); reply(0x3f7a, inst.state);
        reply(0x3f7b, Math.floor(inst.generation / 268435456) & 0xF);
        reply(0x3f7c, Math.floor(inst.generation / 16384) & 0x3FFF);
        reply(0x3f7d, inst.generation & 0x3FFF);
        reply(0x3f7e, inst.marker & 0x3FFF); reply(0x3f7f, inst.layout);
    }
    var output = {
        send: function (m) {
            var got = dec.feed(m[0], m[1], m[2]);
            if (!got) return;
            inst.received.push([got.param, got.value]);
            if (got.param === inst.drop) return;
            if (got.param === 0x3f00) { if (got.value === 0x2a2a) inst.state = 1; return; }
            if (got.param === 0x3f03) {
                var params = B.nrpnParamsOf(inst.mirror);
                params.slice(0, 32).forEach(function (p) { reply(p[0], p[1]); });
                inst.live.forEach(function (b, i) { reply(0x20 + i, b); });
                params.slice(32).forEach(function (p) { reply(p[0], p[1]); });
                identityBlock(); return;
            }
            if (got.param === 0x3f7f) { identityBlock(); return; }
            // A restart with its key: the boot copies the cells to the live
            // bytes, and the ports would go away and come back meanwhile.
            if (got.param === 0x3f04) {
                if (got.value !== 0x2a2a) return;
                inst.restarts++;
                for (var i = 0; i < 16; i++) inst.live[i] = B.nrpnValueOf(inst.mirror, 16 + i) & 0xFF;
                return;
            }
            if (got.param >= 0x20 && got.param < 0x30) return;   // read-only
            B.nrpnApply(inst.mirror, got.param, got.value);
        }
    };
    // What the scan does: a requested commit lands, or fails.
    inst.scan = function () {
        inst.scans++;
        if (inst.state === 1) {
            if (inst.refuseCommit) { inst.state = 3; return; }
            inst.state = 2; inst.slot = inst.slot === 0 ? 1 : 0; inst.generation++;
        }
    };
    inst.input = input; inst.output = output;
    return inst;
}

function same(a, b) { for (var o = 0x20; o < 0x288; o++) if (a[o] !== b[o]) return false; return true; }

(async function () {
    // push alone: bursts of four parameters with a gap between them.
    var inst = fakeInstrument(), timers = fakeTimers();
    var progress = [];
    var pushed = M.push(inst.output, record, { timers: timers, onProgress: function (n, t) { progress.push(n); } });
    await timers.run(pushed); await pushed;
    check('a push lands every parameter in the mirror', same(inst.mirror, record));
    check('338 parameters, in the dump order, the live bytes not among them', inst.received.length === 338
          && inst.received[0][0] === 0 && inst.received[31][0] === 31 && inst.received[32][0] === 0x80 && inst.received[337][0] === 0x1ff);
    check('progress is reported per burst', progress.length === 85 && progress[0] === 4 && progress[84] === 338,
          progress.length + ' bursts');
    check('a push changes no live byte', inst.live.every(function (b) { return b === 0; }));

    // identity and dump
    inst = fakeInstrument({ marker: 0x1234 }); timers = fakeTimers();
    var idp = M.identity(inst.output, inst.input, 2000, timers); await timers.run(idp); var id = await idp;
    check('identity answers with the block', id.layoutVersion === 2 && id.imageMarker === 0x1234 && id.slotLoaded === 0xff);
    check('an identity request costs one parameter', inst.received.length === 1 && inst.received[0][0] === 0x3f7f);
    var dp = M.dump(inst.output, inst.input, 5000, timers); await timers.run(dp); var d = await dp;
    check('a dump answers with every parameter, the live bytes and the block', d.pairs.length === 364 && d.identity.imageMarker === 0x1234
          && d.identity.firmwareVersion === '3.0.0' && d.pairs[32][0] === 0x20 && d.pairs[47][0] === 0x2f && d.pairs[48][0] === 0x80);
    var diff = M.differences(record, d.pairs);
    var nonzero = B.nrpnParamsOf(record).filter(function (p) { return p[1] !== 0; }).length;
    check('differences against an empty instrument name every non-zero parameter',
          diff.length === nonzero && diff.every(function (x) { return x[2] === 0; }), diff.length + ' of ' + nonzero);

    // read: one dump, decoded for the page
    inst = fakeInstrument({ marker: 0x1234 }); timers = fakeTimers();
    for (var o = 0; o < 0x2a8; o++) inst.mirror[o] = record[o];
    var rp = M.read(inst.output, inst.input, { timers: timers }); await timers.run(rp); var rd = await rp;
    check('read gives the identity, the record and its fields', rd.identity.imageMarker === 0x1234
          && same(rd.record, record) && rd.fields.numbers.chord_hold_scans === 200
          && rd.fields.tuning_period_keys.join(',') === '12,12,7' && rd.fields.lengths[2] === 24
          && rd.fields.masks[2] === 0xDEADBEEF && rd.fields.lengths[3] === 0, JSON.stringify(rd.fields.numbers));
    check('and the options, the live bytes and what a restart would apply', rd.fields.options.knob2 === 'swing'
          && rd.fields.options.sequencer === false && rd.live.length === 16 && rd.live.every(function (b) { return b === 0; })
          && rd.pending.join(',') === 'latching_arp,knob2,clock_divide,pressure_fix,pressure_portamento,quantize_presets,portamento_in',
          rd.pending.join(','));
    check('a read costs one parameter', inst.received.length === 1 && inst.received[0][0] === 0x3f03);
    var other = fakeInstrument({ layout: 3, version: 0x320 }); timers = fakeTimers();
    var rl = M.read(other.output, other.input, { timers: timers }).then(function () { return null; }, function (x) { return x; });
    await timers.run(rl); var re = await rl;
    check('a layout this page does not know is refused, with the block', re && re.reason === 'wrong layout' && re.identity.layoutVersion === 3
          && re.identity.firmwareVersion === '3.2.0');
    var older = fakeInstrument({ layout: 1 }); timers = fakeTimers();
    rl = M.read(older.output, older.input, { timers: timers }).then(function () { return null; }, function (x) { return x; });
    await timers.run(rl); re = await rl;
    check('layout 1 is refused too', re && re.reason === 'wrong layout' && re.identity.layoutVersion === 1);
    // A reply that lost parameters on the way back still ends with the
    // layout version, so it looks finished; read refuses it rather than
    // decoding zeros where the values were.
    async function readOf(inst) {
        var timers = fakeTimers();
        var p = M.read(inst.output, inst.input, { timers: timers }).then(function () { return null; }, function (x) { return x; });
        await timers.run(p); return p;
    }
    var lossy = fakeInstrument({ lose: [0x25] });
    for (var lo = 0; lo < 0x2a8; lo++) lossy.mirror[lo] = record[lo];
    re = await readOf(lossy);
    check('a dump that lost a live byte is "incomplete", naming it', re && re.reason === 'incomplete'
          && re.missing.join(',') === '37' && re.identity.imageMarker === 0xB007);
    var every = B.nrpnParamsOf(record).map(function (p) { return p[0]; });
    re = await readOf(fakeInstrument({ lose: every }));
    check('and one that is the identity block alone is too, not a record of zeros', re && re.reason === 'incomplete'
          && re.missing.length === 338);
    re = await readOf(fakeInstrument({ lose: [0x3f7a] }));
    check('as is one short of an identity parameter', re && re.reason === 'incomplete' && re.missing.join(',') === String(0x3f7a));
    re = await readOf(fakeInstrument({ lose: [0x25], layout: 3 }));
    check('a layout this page does not know is still told apart first', re && re.reason === 'wrong layout');
    var mute = fakeInstrument(); mute.output.send = function () {}; timers = fakeTimers();
    var rq = M.read(mute.output, mute.input, { timers: timers, timeout: 50 }).then(function () { return null; }, function (x) { return x; });
    await timers.run(rq); re = await rq;
    check('a read of nothing is "no reply"', re && re.reason === 'no reply');

    // no reply
    var silent = { send: function () {} }, quietInput = { onmidimessage: null };
    timers = fakeTimers();
    var failed = M.identity(silent, quietInput, 100, timers).then(function () { return 'answered'; }, function (e) { return e.message; });
    await timers.run(failed);
    check('silence is "no reply" after the timeout', (await failed) === 'no reply');
    check('the input handler is put back after a timeout', quietInput.onmidimessage === null);

    // install, the whole procedure
    inst = fakeInstrument(); timers = fakeTimers();
    var stages = [];
    // The commit lands on a scan: run scans while the timers run.
    var installP = M.install(inst.output, inst.input, record, { timers: timers, onStage: function (s) { stages.push(s); } });
    var settle = timers.setTimeout; timers.setTimeout = function (fn, ms) { return settle(function () { inst.scan(); fn(); }, ms); };
    await timers.run(installP); var result = await installP;
    check('install pushes, verifies, commits and reads state 2 back', result.commitState === 2 && result.slotLoaded === 0
          && result.generation === 1, JSON.stringify(result));
    check('then restarts, naming the options the restart applies', result.restarted === true
          && result.pending.join(',') === 'latching_arp,knob2,clock_divide,pressure_fix,pressure_portamento,quantize_presets,portamento_in'
          && inst.restarts === 1, JSON.stringify(result.pending));
    check('in that order', stages.join(',') === 'push,verify,commit,restart');
    check('the commit went out with its key', inst.received.some(function (p) { return p[0] === 0x3f00 && p[1] === 0x2a2a; }));
    check('and only after the dump read everything back equal', inst.state === 2 && same(inst.mirror, record));
    check('the restart went out with its key, after the commit', (function () {
        var c = -1, r = -1;
        inst.received.forEach(function (p, i) { if (p[0] === 0x3f00) c = i; if (p[0] === 0x3f04) r = i; });
        return r > c && inst.received[r][1] === 0x2a2a;
    })());
    check('the keyboard came back running the record\'s options', inst.live.slice(0, 11).join(',') === '1,0,2,0,0,0,1,1,1,1,1');

    // awaitLive: the ports are gone for a while, then a read shows the
    // live bytes equal to the cells.
    timers = fakeTimers();
    var asks = 0;
    var awaited = M.awaitLive(function () { asks++; return asks < 4 ? null : { output: inst.output, input: inst.input }; },
                              record, { timers: timers, every: 500, limit: 20000 });
    await timers.run(awaited); var back = await awaited;
    check('awaitLive asks for the ports until they are back, then reads the options as applied',
          asks === 4 && back.pending.length === 0 && back.fields.options.knob2 === 'swing', asks + ' asks');
    var stale = fakeInstrument(); timers = fakeTimers();
    for (var so = 0; so < 0x2a8; so++) stale.mirror[so] = record[so];   // holds the cells, never restarted
    var never = M.awaitLive(function () { return { output: stale.output, input: stale.input }; }, record,
                            { timers: timers, every: 500, limit: 2000 }).then(function () { return null; }, function (e) { return e; });
    await timers.run(never); var ne = await never;
    check('a keyboard that comes back still running other options is "not applied", with the read',
          ne && ne.reason === 'not applied' && ne.read && ne.read.pending.length === 7);
    timers = fakeTimers();
    var gone = M.awaitLive(function () { return null; }, record, { timers: timers, every: 500, limit: 2000 })
        .then(function () { return null; }, function (e) { return e; });
    await timers.run(gone); ne = await gone;
    check('and one that never comes back is "no reply"', ne && ne.reason === 'no reply');

    // A record whose option cells equal the live bytes commits without a restart.
    inst = fakeInstrument(); timers = fakeTimers(); stages = [];
    installP = M.install(inst.output, inst.input, quietRecord, { timers: timers, onStage: function (s) { stages.push(s); } });
    settle = timers.setTimeout; timers.setTimeout = function (fn, ms) { return settle(function () { inst.scan(); fn(); }, ms); };
    await timers.run(installP); result = await installP;
    check('a send that changes no option does not restart', result.commitState === 2 && result.restarted === false
          && result.pending.length === 0 && inst.restarts === 0 && stages.join(',') === 'push,verify,commit');

    // install refusals, each its own reason
    async function refusal(inst, opts) {
        var timers = fakeTimers();
        var p = M.install(inst.output, inst.input, record, Object.assign({ timers: timers }, opts || {}))
            .then(function () { return null; }, function (e) { return e; });
        var settle = timers.setTimeout; timers.setTimeout = function (fn, ms) { return settle(function () { inst.scan(); fn(); }, ms); };
        await timers.run(p); return p;
    }
    var e = await refusal(fakeInstrument({ marker: 0x1111 }));
    check('a record for another image is refused before anything is sent', e && e.reason === 'wrong image' && e.identity.imageMarker === 0x1111);
    e = await refusal(fakeInstrument({ layout: 1 }));
    check('another layout is refused', e && e.reason === 'wrong layout');
    e = await refusal(fakeInstrument({ drop: 0x83 }));
    check('a parameter lost on the wire is a mismatch, and nothing is committed',
          e && e.reason === 'mismatch' && e.differences.length === 1 && e.differences[0][0] === 0x83 && e.differences[0][1] === 485 + 120);
    // The dump that verifies a push lost one of the live bytes: without it
    // there is no telling whether the options changed, so nothing is
    // committed and no restart is skipped.
    var shortLive = fakeInstrument({ lose: [0x21] });
    e = await refusal(shortLive);
    check('a verifying dump without all sixteen live bytes is a mismatch: no commit, no restart',
          e && e.reason === 'mismatch' && e.differences.length === 0 && e.missing.join(',') === '33'
          && shortLive.state === 0 && shortLive.restarts === 0
          && !shortLive.received.some(function (p) { return p[0] === 0x3f00 || p[0] === 0x3f04; }));
    var shortRecord = fakeInstrument({ lose: [0x1ff] });
    e = await refusal(shortRecord);
    check('as is one without a record parameter, which is also a difference',
          e && e.reason === 'mismatch' && e.missing.join(',') === String(0x1ff) && shortRecord.state === 0);
    var shortId = fakeInstrument({ lose: [0x3f7c] });
    e = await refusal(shortId);
    check('and an identity block short of a parameter refuses before anything is pushed',
          e && e.reason === 'mismatch' && e.missing.join(',') === String(0x3f7c) && shortId.received.length === 1);
    e = await refusal(fakeInstrument({ refuseCommit: true }));
    check('a commit the instrument could not write is reported with its state', e && e.reason === 'not written' && e.state === 3);
    var quiet = fakeInstrument(); quiet.output.send = function () {};
    e = await refusal(quiet, { timeout: 50 });
    check('no reply is its own reason', e && e.reason === 'no reply');

    // A send that fails after its push drops what it pushed: the values went
    // live on arrival and nothing saved them.  (Audit 2026-09-24.)
    function lastSent(inst) { return inst.received.length ? inst.received[inst.received.length - 1][0] : null; }
    var lossy = fakeInstrument({ drop: 0x83 });
    e = await refusal(lossy);
    check('a send that fails after its push reloads, so the keyboard drops the half it got',
          e && e.reason === 'mismatch' && lastSent(lossy) === 0x3f01);
    var early = fakeInstrument({ marker: 0x1111 });
    e = await refusal(early);
    check('and one refused before the push sends nothing more', e && e.reason === 'wrong image'
          && !early.received.some(function (p) { return p[0] === 0x3f01; }));
    // A commit request lost on the way: the state still says what the last
    // commit left, 2, and only the generation tells.  (Audit 2026-09-24.)
    var lost = fakeInstrument({ drop: 0x3f00 }); lost.state = 2; lost.generation = 5;
    e = await refusal(lost);
    check('a commit that never arrived is not written, whatever state the last one left',
          e && e.reason === 'not written' && lost.generation === 5 && lastSent(lost) === 0x3f01);
    // A port that went away: send() throws.  That is a refusal the caller
    // handles, with the listener put back, not a throw out of the transport.
    var gone = fakeInstrument(), listener = function () {};
    gone.output.send = function () { throw new Error('Port is disconnected.'); };
    gone.input.onmidimessage = listener;
    var threw = false, rp = null;
    try { rp = M.read(gone.output, gone.input, { timers: fakeTimers() }).then(function () { return null; }, function (x) { return x; }); }
    catch (x) { threw = true; }
    e = threw ? null : await rp;
    check('a port whose send throws reads as no reply, and the listener is put back',
          !threw && e && e.reason === 'no reply' && gone.input.onmidimessage === listener);
    threw = false;
    try { e = await refusal(gone); } catch (x) { threw = true; }
    check('and a send through it is no reply too', !threw && e && e.reason === 'no reply');

    check('markerOf reads the record\'s image marker', M.markerOf(record) === 0xB007);

    if (failures) { console.log(failures + ' failure(s)'); process.exit(1); }
    process.exitCode = 0;
    console.log('ALL SETTINGS TRANSPORT TESTS PASSED');
})().catch(function (err) { console.log('FAIL  ' + (err && err.stack || err)); process.exit(1); });
