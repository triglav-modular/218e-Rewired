// Live send, the page's: once Read settings has loaded what the keyboard
// holds, each change on the page goes to it without a press of Send - only
// the parameters that differ from what it is known to hold, a burst of edits
// as one send, then the verify, commit and restart a whole Send does.  Not
// before a read, not while a read, a send or a calibration run has the port,
// and not again after anything that leaves the page unsure what the keyboard
// holds - a failed read or send, or another port - until the next read.
//
// The real code out of app.js (invalidate, refresh, the Send and the Read,
// and the live send between them) with the DOM stubbed the way
// web/test_readback.js stubs it, the real transport in settings.js, and the
// real record builder (WEBBUILD.settings), against a fake instrument that
// behaves as web/test_settingsmidi.js's does - with a flash behind the mirror,
// so a reload puts back what the last commit saved.  The clock is the test's.
//
//   node web/test_livesend.js
'use strict';
var fs = require('fs'), path = require('path'), vm = require('vm');

// A silent early exit is a failure: an awaited promise that never settles
// lets node drain its loop and exit 0 without the last line.
process.exitCode = 1;
var failures = 0;
function check(name, ok, detail) {
    console.log((ok ? 'ok    ' : 'FAIL  ') + name + (ok || !detail ? '' : '  - ' + detail));
    if (!ok) failures++;
}

// Timers the test drives.  run(ms) runs what falls due in the next `ms`
// milliseconds, run() everything until nothing is left; a callback's promise
// chain runs out before the next is looked at, since a chain registers its
// next timer only after a microtask.  Every instrument scans before each
// callback, which is when a requested commit lands.
var instruments = [];
function fakeTimers() {
    var queue = [], now = 0, id = 0;
    return {
        setTimeout: function (fn, ms) { queue.push({ at: now + (ms || 0), fn: fn, id: ++id }); return id; },
        clearTimeout: function (h) { queue = queue.filter(function (q) { return q.id !== h; }); },
        // Looked at only once the microtasks already queued have run out: a
        // read answered at once is a chain of them with no timer in it.
        run: function (ms) {
            var until = ms === undefined ? Infinity : now + ms;
            return new Promise(function (done) {
                function tick() {
                    queue.sort(function (a, b) { return a.at - b.at || a.id - b.id; });
                    if (!queue.length || queue[0].at > until) {
                        if (until !== Infinity) now = until;
                        done(); return;
                    }
                    var q = queue.shift(); now = q.at;
                    instruments.forEach(function (i) { i.scan(); });
                    q.fn();
                    setImmediate(tick);
                }
                setImmediate(tick);
            });
        }
    };
}
var T = fakeTimers();

// The page: the scripts it loads, then the pieces of app.js under test.
var page = vm.createContext({
    console: console, setTimeout: T.setTimeout, clearTimeout: T.clearTimeout,
    window: { setTimeout: T.setTimeout, clearTimeout: T.clearTimeout }
});
['generated.js', 'sha256.js', 'buildlib.js', 'assembler.js', 'build.js', 'settings.js'].forEach(function (f) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, f), 'utf8'), page, { filename: 'web/' + f });
});
var B = page.BUILDLIB, M = page.SETTINGSMIDI;
var APP = fs.readFileSync(path.join(__dirname, 'app.js'), 'utf8');
function appSource(open, close) {
    var start = APP.indexOf(open);
    if (start < 0) throw new Error('app.js has no ' + open.trim());
    return APP.slice(start, APP.indexOf(close, start) + close.length);
}
function appFunction(name) { return appSource('\n    function ' + name + '(', '\n    }\n'); }
vm.runInContext([
    'var nodes = {}, messages = [], reports = [], opts = {}, nextLoad = null, sweep = null;',
    'var state = { factoryText: null, result: null, options: null };',
    'var document = { body: {} };',
    'function $(id) { return nodes[id] || (nodes[id] = { id: id, value: "", disabled: false, hidden: false, textContent: "" }); }',
    'function msg(el, kind, text) { el.kind = kind; el.text = text; messages.push([el.id, kind, text]); }',
    'function bindDashes() {} function saveSoon() {} function syncReset() {}',
    'function reportSettings(action, outcome) { reports.push([action, outcome]); }',
    // The page's options, as the test sets them; a read loads what the
    // keyboard holds into them, as loadFromKeyboard does, and invalidates.
    'function options() { return JSON.parse(JSON.stringify(opts)); }',
    'function loadFromKeyboard() { if (nextLoad) opts = JSON.parse(JSON.stringify(nextLoad)); invalidate(); return ""; }',
    'function readVerdict() { return null; }',
    'function listKeyboard() { kbd.listed = true; refresh(); return Promise.resolve(); }',
    appSource('\n    var kbd = {', ';\n'), appSource('\n    var KBD_REASONS = {', '\n    };\n'),
    appFunction('kbdSelects'), appFunction('keyboardPort'), appFunction('keyboardPorts'),
    appFunction('recordBytes'), appFunction('outcomeOf'), appFunction('freshPorts'),
    appFunction('showFirmware'), appFunction('firmwareFrom'), appFunction('optionWords'),
    appSource('\n    function shown(', '}\n'), appFunction('otherImage'), appFunction('readRefusal'), appFunction('sendRefusal'),
    appFunction('sendRecord'),
    appSource('\n    var LIVE_DELAY', ';\n'), appSource('\n    var live = {', ';\n'),
    appFunction('liveArm'), appFunction('liveOff'), appFunction('liveSoon'), appFunction('liveChanged'),
    appFunction('livePicked'), appFunction('liveSend'),
    appFunction('invalidate'), appFunction('refresh'),
    appSource('\n        var sendTo = function (ports) {', '\n        };\n'),
    appSource('\n        var readFrom = function (ports) {', '\n        };\n')
].join('\n'), page, { filename: 'web/app.js (extracted)' });
function run(code) { return vm.runInContext(code, page); }

function bytesOf(o) { return run('recordBytes')(page.WEBBUILD.settings(o).settings); }
function clone(o) { return JSON.parse(JSON.stringify(o)); }
// The payload: what a dump carries.  A read's record has a zero header.
function same(a, b) {
    if (!a || !b) return false;
    for (var o = 0x20; o < 0x288; o++) if (a[o] !== b[o]) return false;
    return true;
}
// A calibration with one entry moved.
function correction(moves) {
    var rows = [];
    for (var s = 0; s < 79; s++) rows.push({ semitone: s, cents: moves[s] || 0 });
    return rows;
}

// The instrument, as test_settingsmidi.js's: NRPN values land in the mirror,
// a commit needs its key and lands on the next scan, saving the mirror to
// flash, a reload puts the flash back, a dump answers with every parameter,
// the live option bytes and the identity block, and a restart copies the
// cells to the live bytes.  It starts holding `record`, committed and booted.
function fakeInstrument(record, id, name) {
    var inst = {
        mirror: record.slice(), flash: record.slice(), live: [], marker: M.markerOf(record),
        state: 2, slot: 0, generation: 1, received: [], restarts: 0, drop: null, mute: false
    };
    for (var v = 0; v < 16; v++) inst.live.push(B.nrpnValueOf(record, 16 + v) & 0xFF);
    var dec = B.nrpnDecoder(), input = { name: name, onmidimessage: null };
    function reply(param, value) {
        B.nrpnMessages(param, value).forEach(function (m) {
            if (input.onmidimessage) input.onmidimessage({ data: m });
        });
    }
    function identityBlock() {
        reply(0x3f76, B.versionCode(page.GEN.version));   // the image this page builds
        reply(0x3f77, inst.marker >>> 14); reply(0x3f78, 484); reply(0x3f79, inst.slot); reply(0x3f7a, inst.state);
        reply(0x3f7b, Math.floor(inst.generation / 268435456) & 0xF);
        reply(0x3f7c, Math.floor(inst.generation / 16384) & 0x3FFF);
        reply(0x3f7d, inst.generation & 0x3FFF);
        reply(0x3f7e, inst.marker & 0x3FFF); reply(0x3f7f, 2);
    }
    var output = {
        id: id, name: name,
        send: function (m) {
            if (inst.mute) return;
            var got = dec.feed(m[0], m[1], m[2]);
            if (!got) return;
            inst.received.push([got.param, got.value]);
            if (got.param === inst.drop) return;
            if (got.param === 0x3f00) { if (got.value === 0x2a2a) inst.state = 1; return; }
            if (got.param === 0x3f01) { inst.mirror = inst.flash.slice(); return; }
            if (got.param === 0x3f03) {
                var params = B.nrpnParamsOf(inst.mirror);
                params.slice(0, 32).forEach(function (p) { reply(p[0], p[1]); });
                inst.live.forEach(function (b, i) { reply(0x20 + i, b); });
                params.slice(32).forEach(function (p) { reply(p[0], p[1]); });
                identityBlock(); return;
            }
            if (got.param === 0x3f7f) { identityBlock(); return; }
            if (got.param === 0x3f04) {
                if (got.value !== 0x2a2a) return;
                inst.restarts++;
                for (var i = 0; i < 16; i++) inst.live[i] = B.nrpnValueOf(inst.mirror, 16 + i) & 0xFF;
                return;
            }
            if (got.param >= 0x3f00) return;
            if (got.param >= 0x20 && got.param < 0x30) return;   // read-only
            B.nrpnApply(inst.mirror, got.param, got.value);
        }
    };
    inst.scan = function () {
        if (inst.state === 1) {
            inst.state = 2; inst.slot = inst.slot ? 0 : 1; inst.generation++;
            inst.flash = inst.mirror.slice();
        }
    };
    inst.input = input; inst.output = output;
    instruments.push(inst);
    return inst;
}

// What went to an instrument since `at`: the record's parameters, and how
// many of one command.
function pushedSince(inst, at) { return inst.received.slice(at).filter(function (p) { return p[0] < 0x3f00; }); }
function countSince(inst, at, param) {
    return inst.received.slice(at).filter(function (p) { return p[0] === param; }).length;
}
// Something on the page the keyboard does not hold: a "nothing went out"
// that is not also this would pass with nothing to send.
function unsent() { return M.changes(kb.flash, bytesOf(page.opts)).length > 0; }
function key(pairs) { return JSON.stringify(pairs.map(function (p) { return [p[0], p[1]]; })); }

var held = {};                          // the options the keyboard holds
var kb = fakeInstrument(bytesOf(held), 'kbd', '218e Rewired');
var other = fakeInstrument(bytesOf({ knob1: 'factory' }), 'other', 'Another synth');
page.kbd.outputs = [kb.output, other.output];
page.kbd.inputs = [kb.input, other.input];
page.kbd.listed = true;
page.CALIBRATE = {
    midiOutputs: function () { return Promise.resolve(page.kbd.outputs.slice()); },
    midiInputs: function () { return Promise.resolve(page.kbd.inputs.slice()); }
};
run('$("kbdLoadPort").value = "kbd"; $("kbdPort").value = "kbd";');

function kbdMsg() { return page.nodes.kbdMsg || {}; }
function change(o) {
    Object.keys(o).forEach(function (k) { page.opts[k] = o[k]; });
    run('invalidate()');
}
function read() { page.nextLoad = clone(held); run('readFrom(keyboardPorts())'); return T.run(); }
function known() { return page.live.known; }

(async function () {
    var at, pushed, want, before;

    // --- before a read: Send only ------------------------------------------
    at = kb.received.length;
    change({ latching_arp: false });
    await T.run();
    check('before a read, a change sends nothing', unsent() && kb.received.length === at && known() === null);

    at = kb.received.length;
    run('sendTo(keyboardPorts())');
    await T.run();
    check('Send settings before a read sends the whole record, saves and restarts',
          pushedSince(kb, at).length === 338 && countSince(kb, at, 0x3f00) === 1 && kb.restarts === 1
          && same(kb.mirror, bytesOf(page.opts)) && /^Sent and saved\. The keyboard has restarted/.test(kbdMsg().text),
          pushedSince(kb, at).length + ' pushed; ' + kbdMsg().text);
    held = clone(page.opts);
    check('and is counted as a send', JSON.stringify(page.reports.slice(-1)) === '[["send","ok"]]',
          JSON.stringify(page.reports));
    at = kb.received.length;
    change({ knob1: 'factory' });
    await T.run();
    check('but does not start live send: that takes a read', unsent() && kb.received.length === at && known() === null);
    page.opts = clone(held);

    // --- a read arms it ------------------------------------------------------
    at = kb.received.length;
    await read();
    check('a read arms live send, holding what it read from that port',
          known() !== null && same(known(), kb.mirror) && page.live.name === '218e Rewired');
    check('and the read’s own load sends nothing', key(kb.received.slice(at)) === '[[16131,0]]',
          key(kb.received.slice(at)));

    // --- one option ------------------------------------------------------------
    var reportsBefore = page.reports.length, restarts = kb.restarts;
    before = known(); at = kb.received.length;
    change({ pressure_portamento: false });
    want = M.changes(before, bytesOf(page.opts));
    await T.run();
    pushed = pushedSince(kb, at);
    check('after a read, changing one option sends only its parameter',
          want.length === 1 && key(pushed) === key(want), key(pushed) + ' against ' + key(want));
    check('then commits it and restarts the keyboard to run it',
          countSince(kb, at, 0x3f00) === 1 && kb.restarts === restarts + 1 && kb.state === 2
          && kb.live[8] === 0 && same(kb.flash, bytesOf(page.opts)));
    check('saying so with the Send card’s own lines',
          page.messages.some(function (m) { return m[0] === 'kbdMsg' && m[2] === 'Sending…'; })
          && kbdMsg().text === 'Sent and saved. The keyboard has restarted and now runs the pressure portamento as set here.',
          kbdMsg().text);
    check('and the keyboard is known to hold the new record', same(known(), bytesOf(page.opts)));
    check('a live send is not counted as a press of Send', page.reports.length === reportsBefore);
    held = clone(page.opts);

    // --- one table entry ---------------------------------------------------------
    restarts = kb.restarts; before = known(); at = kb.received.length;
    change({ pitch_correction: correction({ 20: 5 }) });
    want = M.changes(before, bytesOf(page.opts));
    await T.run();
    pushed = pushedSince(kb, at);
    check('changing a table entry sends only that entry',
          want.length === 1 && want[0][0] >= 0x80 && want[0][0] < 0x80 + 79 && key(pushed) === key(want),
          key(pushed) + ' against ' + key(want));
    check('and saves it without a restart', countSince(kb, at, 0x3f00) === 1 && kb.restarts === restarts
          && kbdMsg().text === 'Sent and saved.' && same(kb.flash, bytesOf(page.opts)), kbdMsg().text);
    held = clone(page.opts);

    // --- a burst ----------------------------------------------------------------
    before = known(); at = kb.received.length;
    change({ knob3: 'factory' });
    await T.run(100);
    change({ pitch_correction: correction({ 20: 5, 30: -4 }) });
    await T.run(100);
    change({ clock_divide: false });
    await T.run(399);
    check('a burst of changes waits for the last one', unsent() && kb.received.length === at,
          (kb.received.length - at) + ' parameters went early');
    want = M.changes(before, bytesOf(page.opts));
    await T.run();
    pushed = pushedSince(kb, at);
    check('then goes as one send carrying all of them',
          want.length === 3 && key(pushed) === key(want) && countSince(kb, at, 0x3f00) === 1
          && countSince(kb, at, 0x3f03) <= 2, key(pushed) + ' against ' + key(want));
    held = clone(page.opts);

    // --- a calibration run has the port ------------------------------------------
    before = known(); at = kb.received.length;
    page.sweep = { stop: function () {} };
    change({ pitch_correction: correction({ 20: 5, 30: -4, 40: 7 }) });   // as the run's tuned table loads
    await T.run();
    check('nothing goes out while a calibration run is going', unsent() && kb.received.length === at && page.live.due === true);
    run('sweep = null; refresh();');                                      // the run's end
    want = M.changes(before, bytesOf(page.opts));
    await T.run();
    pushed = pushedSince(kb, at);
    check('once it ends, the table it loaded goes like any other change',
          want.length === 1 && key(pushed) === key(want) && same(kb.flash, bytesOf(page.opts)), key(pushed));
    held = clone(page.opts);
    // A read or a send holds the port the same way (kbd.busy).
    at = kb.received.length;
    page.kbd.busy = true;
    change({ quantize_presets: false });
    await T.run();
    check('nor while a read or a send has the port', unsent() && kb.received.length === at);
    run('kbd.busy = false; refresh();');
    await T.run();
    check('and it goes when that one ends', pushedSince(kb, at).length === 1 && same(kb.flash, bytesOf(page.opts)));
    held = clone(page.opts);

    // --- no port picked ------------------------------------------------------------
    at = kb.received.length;
    run('$("kbdLoadPort").value = "";');
    change({ knob4: 'factory' });
    await T.run();
    check('with no port picked nothing goes', unsent() && kb.received.length === at && other.received.length === 0);
    run('$("kbdLoadPort").value = "kbd"; refresh();');
    await T.run();
    check('and it goes when the keyboard’s port is back', pushedSince(kb, at).length === 1
          && same(kb.flash, bytesOf(page.opts)));
    held = clone(page.opts);

    // --- the Send button, after a read ------------------------------------------------
    restarts = kb.restarts; at = kb.received.length;
    page.opts.latching_arp = true;                       // not through invalidate(): only Send carries it
    run('sendTo(keyboardPorts())');
    await T.run();
    check('Send settings after a read still sends the whole record',
          pushedSince(kb, at).length === 338 && same(kb.flash, bytesOf(page.opts)) && kb.restarts === restarts + 1,
          pushedSince(kb, at).length + ' pushed');
    check('and the keyboard is then known to hold it', same(known(), bytesOf(page.opts)));
    held = clone(page.opts);
    at = kb.received.length;
    change({ pitch_correction: correction({ 20: 5, 30: -4, 40: 7, 50: 3 }) });
    await T.run();
    pushed = pushedSince(kb, at);
    check('so the next change sends itself alone, not what the Send carried',
          pushed.length === 1 && pushed[0][0] >= 0x80 && pushed[0][0] < 0x80 + 79, key(pushed));
    held = clone(page.opts);

    // --- a read while it is on ---------------------------------------------------------
    // The load is not a change, even one that does not give the keyboard's
    // record back exactly: what it read is what it holds, and nothing goes
    // out until something on the page is changed.
    at = kb.received.length;
    page.nextLoad = Object.assign(clone(held), { knob4: 'vibrato' });
    run('readFrom(keyboardPorts())');
    await T.run();
    check('a read while live send is on sends nothing back, whatever its load left',
          unsent() && key(kb.received.slice(at)) === '[[16131,0]]' && same(known(), kb.flash),
          key(kb.received.slice(at)));
    page.opts = clone(held);

    // --- a record the page cannot build ---------------------------------------------
    at = kb.received.length;
    change({ pressure_fix: false, pressure_portamento: true });
    await T.run();
    check('a change the page cannot build sends nothing and says why',
          kb.received.length === at && /^Build failed\./.test(kbdMsg().text), kbdMsg().text);
    check('and leaves live send on: the keyboard still holds what it did', known() !== null && same(known(), kb.flash));
    change({ pressure_fix: true, pressure_portamento: false, sequencer: false });
    await T.run();
    check('the next change that builds goes', pushedSince(kb, at).length === 1 && same(kb.flash, bytesOf(page.opts)));
    held = clone(page.opts);

    // --- a send that fails ------------------------------------------------------------
    before = known(); at = kb.received.length;
    page.opts.pitch_correction = correction({ 20: 5, 30: -4, 40: 7, 50: 3, 60: 6 });
    want = M.changes(before, bytesOf(page.opts));
    kb.drop = want[0][0];                                // lost on the wire
    run('invalidate()');
    await T.run();
    check('a live send that fails shows the Send card’s refusal',
          kbdMsg().kind === 'bad' && kbdMsg().text === page.KBD_REASONS.mismatch, kbdMsg().text);
    check('drops what it pushed, and stops live send',
          known() === null && countSince(kb, at, 0x3f01) === 1 && countSince(kb, at, 0x3f00) === 0
          && same(kb.mirror, bytesOf(held)));
    kb.drop = null; at = kb.received.length;
    change({ pitch_correction: correction({ 20: 5, 30: -4, 40: 7, 50: 3, 61: 9 }) });
    await T.run();
    check('so the next change sends nothing', unsent() && kb.received.length === at);
    await read();
    at = kb.received.length;
    change({ pitch_correction: correction({ 20: 5, 30: -4, 40: 7, 50: 3, 61: 9 }) });
    await T.run();
    check('until the next read, after which changes go again', pushedSince(kb, at).length === 1
          && same(kb.flash, bytesOf(page.opts)), key(pushedSince(kb, at)) + ' ' + kbdMsg().text);
    held = clone(page.opts);

    // --- another port --------------------------------------------------------------------
    run('$("kbdLoadPort").value = "other"; livePicked();');          // what a pick in the lists does
    check('picking another port stops live send', known() === null);
    at = kb.received.length; var atOther = other.received.length;
    change({ knob4: 'vibrato' });
    await T.run();
    check('so a change goes to neither port', unsent() && kb.received.length === at && other.received.length === atOther);
    run('$("kbdLoadPort").value = "kbd"; livePicked();');
    change({ knob3: 'octaves' });
    await T.run();
    check('nor to the keyboard when it is picked again, until it is read', unsent() && kb.received.length === at);
    page.opts = clone(held);
    await read();
    // A refill of the lists that lands on another port, with no pick: the
    // send itself sees the port is not the one read.
    run('$("kbdLoadPort").value = "other";');
    at = kb.received.length; atOther = other.received.length;
    change({ knob4: 'vibrato' });
    await T.run();
    check('a port that changed under the page stops it too, sending nothing',
          known() === null && unsent() && kb.received.length === at && other.received.length === atOther);
    run('$("kbdLoadPort").value = "kbd";');
    page.opts = clone(held);

    // --- a read that fails --------------------------------------------------------------
    await read();
    check('(read again)', known() !== null);
    kb.mute = true;
    await read();
    check('a read that fails stops live send', known() === null
          && page.nodes.kbdLoadMsg.text === page.KBD_REASONS['no reply'], page.nodes.kbdLoadMsg.text);
    kb.mute = false; at = kb.received.length;
    change({ knob4: 'vibrato' });
    await T.run();
    check('so the next change sends nothing', unsent() && kb.received.length === at);

    if (failures) { console.log(failures + ' failure(s)'); process.exit(1); }
    process.exitCode = 0;
    console.log('ALL LIVE SEND TESTS PASSED');
})().catch(function (err) { console.log('FAIL  ' + (err && err.stack || err)); process.exit(1); });
