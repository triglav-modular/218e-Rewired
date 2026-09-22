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
var record = B.settingsRecord({ chord_hold_scans: 200 }, tables, true, 0xB007, 484, 1);

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
// and `state` are what the identity block reports.
function fakeInstrument(options) {
    options = options || {};
    var mirror = [];
    for (var b = 0; b < 0x2a8; b++) mirror.push(0);
    var dec = B.nrpnDecoder();
    var inst = {
        mirror: mirror, marker: options.marker === undefined ? 0xB007 : options.marker,
        layout: options.layout === undefined ? 1 : options.layout,
        state: 0, slot: 0xff, generation: 0, received: [], sent: [], scans: 0,
        drop: options.drop || null,     // a parameter number to lose on the wire
        refuseCommit: !!options.refuseCommit
    };
    var input = { onmidimessage: null };
    function reply(param, value) {
        B.nrpnMessages(param, value).forEach(function (m) {
            inst.sent.push(m);
            if (input.onmidimessage) input.onmidimessage({ data: m });
        });
    }
    function identityBlock() {
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
            if (got.param === 0x3f00) { if (got.value === 0x2a2a) inst.state = 1; return; }
            if (got.param === 0x3f03) { B.nrpnParamsOf(inst.mirror).forEach(function (p) { reply(p[0], p[1]); }); identityBlock(); return; }
            if (got.param === 0x3f7f) { identityBlock(); return; }
            if (got.param === inst.drop) return;
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
    check('316 parameters, in the dump order', inst.received.length === 316
          && inst.received[0][0] === 0 && inst.received[315][0] === 0x1ff);
    check('progress is reported per burst', progress.length === 79 && progress[0] === 4 && progress[78] === 316,
          progress.length + ' bursts');

    // identity and dump
    inst = fakeInstrument({ marker: 0x1234 }); timers = fakeTimers();
    var idp = M.identity(inst.output, inst.input, 2000, timers); await timers.run(idp); var id = await idp;
    check('identity answers with the block', id.layoutVersion === 1 && id.imageMarker === 0x1234 && id.slotLoaded === 0xff);
    check('an identity request costs one parameter', inst.received.length === 1 && inst.received[0][0] === 0x3f7f);
    var dp = M.dump(inst.output, inst.input, 5000, timers); await timers.run(dp); var d = await dp;
    check('a dump answers with every parameter and the block', d.pairs.length === 325 && d.identity.imageMarker === 0x1234);
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
    check('a read costs one parameter', inst.received.length === 1 && inst.received[0][0] === 0x3f03);
    var other = fakeInstrument({ layout: 2 }); timers = fakeTimers();
    var rl = M.read(other.output, other.input, { timers: timers }).then(function () { return null; }, function (x) { return x; });
    await timers.run(rl); var re = await rl;
    check('a layout this page does not know is refused, with the block', re && re.reason === 'wrong layout' && re.identity.layoutVersion === 2);
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
    check('in that order', stages.join(',') === 'push,verify,commit');
    check('the commit went out with its key', inst.received.some(function (p) { return p[0] === 0x3f00 && p[1] === 0x2a2a; }));
    check('and only after the dump read everything back equal', inst.state === 2 && same(inst.mirror, record));

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
    e = await refusal(fakeInstrument({ layout: 2 }));
    check('another layout is refused', e && e.reason === 'wrong layout');
    e = await refusal(fakeInstrument({ drop: 0x83 }));
    check('a parameter lost on the wire is a mismatch, and nothing is committed',
          e && e.reason === 'mismatch' && e.differences.length === 1 && e.differences[0][0] === 0x83 && e.differences[0][1] === 485 + 120);
    e = await refusal(fakeInstrument({ refuseCommit: true }));
    check('a commit the instrument could not write is reported with its state', e && e.reason === 'not written' && e.state === 3);
    var quiet = fakeInstrument(); quiet.output.send = function () {};
    e = await refusal(quiet, { timeout: 50 });
    check('no reply is its own reason', e && e.reason === 'no reply');

    check('markerOf reads the record\'s image marker', M.markerOf(record) === 0xB007);

    if (failures) { console.log(failures + ' failure(s)'); process.exit(1); }
    process.exitCode = 0;
    console.log('ALL SETTINGS TRANSPORT TESTS PASSED');
})().catch(function (err) { console.log('FAIL  ' + (err && err.stack || err)); process.exit(1); });
