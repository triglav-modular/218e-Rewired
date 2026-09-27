// The unsent card, the page's: once Read settings has loaded what the
// keyboard holds, a change that makes the page's settings differ from it
// brings up a card fixed to the bottom of the window, with the port and Send
// settings.  Nothing goes to the keyboard until Send is pressed (the live
// send this replaced sent each change on its own).  Not before a read, not
// after a failed one, and not once another port is picked, until the next
// read; up while a send runs and after one fails, and gone a few seconds
// after one lands.
//
// The real code out of app.js (invalidate, refresh, the Send and the Read,
// and the card between them) with the DOM stubbed the way
// web/test_readback.js stubs it, the real transport in settings.js, and the
// real record builder (WEBBUILD.settings), against a fake instrument that
// behaves as web/test_settingsmidi.js's does - with a flash behind the mirror,
// so a reload puts back what the last commit saved.  The clock is the test's.
//
//   node web/test_unsent.js
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
    'var document = { body: { style: {} } };',
    'function classes() { var on = {}; return { contains: function (c) { return !!on[c]; }, toggle: function (c, v) { on[c] = v === undefined ? !on[c] : !!v; } }; }',
    'function $(id) { return nodes[id] || (nodes[id] = { id: id, value: "", disabled: false, hidden: false, textContent: "", classList: classes() }); }',
    'function msg(el, kind, text) { el.kind = kind; el.text = text; messages.push([el.id, kind, text]); }',
    'function bindDashes() {} function saveSoon() {} function syncReset() {}',
    'function reportSettings(action, outcome) { reports.push([action, outcome]); }',
    // The page's options, as the test sets them; a read loads what the
    // keyboard holds into them, as loadFromKeyboard does, and invalidates.
    'function options() { return JSON.parse(JSON.stringify(opts)); }',
    'function loadFromKeyboard() { if (nextLoad) opts = JSON.parse(JSON.stringify(nextLoad)); invalidate(); return ""; }',
    'function listKeyboard() { kbd.listed = true; refresh(); return Promise.resolve(); }',
    appSource('\n    var kbd = {', ';\n'), appSource('\n    var KBD_REASONS = {', '\n    };\n'),
    appFunction('kbdSelects'), appFunction('keyboardPort'), appFunction('keyboardPorts'),
    appFunction('recordBytes'), appFunction('outcomeOf'), appFunction('freshPorts'),
    appFunction('showFirmware'), appFunction('firmwareFrom'), appFunction('optionWords'),
    appSource('\n    function shown(', '}\n'), appFunction('otherImage'), appFunction('readVerdict'), appFunction('readRefusal'), appFunction('sendRefusal'),
    appFunction('sendRecord'),
    appSource('\n    var UNSENT_CHECK_MS', ';\n'), appSource('\n    var unsent = {', ';\n'),
    appFunction('pageRecord'), appFunction('unsentArm'), appFunction('unsentOff'), appFunction('unsentSoon'),
    appFunction('unsentCheck'), appFunction('unsentPicked'), appFunction('unsentLanded'), appFunction('unsentShow'),
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
function differs() { return M.changes(kb.flash, bytesOf(page.opts)).length > 0; }
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
function armed() { return run('unsent.armed'); }
function up() { return page.nodes.unsent.classList.contains('up'); }
function headShown() { return !page.nodes.unsentHead.hidden; }
function padded() { return !!page.document.body.style.paddingBottom; }
function send() { run('sendTo(keyboardPorts())'); }

var UNSENT_WAIT = run('UNSENT_CHECK_MS'), SENT_WAIT = run('SENT_LINGER_MS');

(async function () {
    var at, restarts;

    // --- before a read ---------------------------------------------------------
    at = kb.received.length;
    change({ latching_arp: false });
    await T.run();
    check('before a read, a change sends nothing and brings up no card',
          differs() && kb.received.length === at && !armed() && !up());
    page.opts = clone(held);

    // --- a read arms it ------------------------------------------------------------
    at = kb.received.length;
    await read();
    check('a read arms the card for the port it read, and leaves it down',
          armed() && page.unsent.name === '218e Rewired' && !up() && !padded());
    check('saying only that the settings loaded', page.nodes.kbdLoadMsg.text === 'Keyboard settings loaded successfully.'
          && page.state.factoryText === null, page.nodes.kbdLoadMsg.text);
    check('and the read’s own load sends nothing', key(kb.received.slice(at)) === '[[16131,0]]',
          key(kb.received.slice(at)));

    // --- a change brings it up, and sends nothing ------------------------------------
    at = kb.received.length;
    change({ pressure_portamento: false });
    await T.run(UNSENT_WAIT - 1);
    check('a change is looked at once the edits pause', !up());
    await T.run();
    check('then the card comes up, saying there are unsent settings, with room under the page for it',
          up() && headShown() && padded());
    check('and nothing goes to the keyboard until Send', differs() && kb.received.length === at);
    change({ pressure_portamento: true });
    await T.run();
    check('putting it back takes the card down again', !up() && !padded() && kb.received.length === at);

    // --- Send from the card ------------------------------------------------------------
    change({ pressure_portamento: false });
    await T.run();
    restarts = kb.restarts; at = kb.received.length;
    var reportsBefore = page.reports.length;
    send();
    check('while the send runs the card stays up', up() && page.unsent.sending);
    await T.run(SENT_WAIT - 1);
    check('Send settings sends the whole record, saves it and restarts the keyboard',
          pushedSince(kb, at).length === 338 && countSince(kb, at, 0x3f00) === 1 && kb.restarts === restarts + 1
          && same(kb.flash, bytesOf(page.opts)), pushedSince(kb, at).length + ' pushed');
    check('then the card says so, and no longer that anything is unsent',
          up() && !headShown() && kbdMsg().text ===
          'Sent and saved. The keyboard has restarted and now runs the pressure portamento as set here.',
          kbdMsg().text);
    check('and it is counted as a send', page.reports.length === reportsBefore + 1
          && JSON.stringify(page.reports.slice(-1)) === '[["send","ok"]]', JSON.stringify(page.reports));
    await T.run();
    check('a few seconds later the card goes', !up() && !padded() && !page.unsent.sending);
    held = clone(page.opts);
    change({ clock_divide: false });
    await T.run();
    check('and the next change brings it up again, without the last send’s line',
          up() && headShown() && !kbdMsg().text, kbdMsg().text);
    page.opts = clone(held); run('invalidate()');
    await T.run();
    check('(put back)', !up());

    // --- a change while a send runs -----------------------------------------------------
    change({ pitch_correction: correction({ 20: 5 }) });
    await T.run();
    at = kb.received.length;
    send();
    await T.run(40);                               // past the build the send carries
    change({ pitch_correction: correction({ 20: 5, 30: -4 }) });
    await T.run();
    check('a change made while a send runs keeps the card up once it lands',
          up() && headShown() && differs() && kbdMsg().text === 'Sent and saved.', kbdMsg().text);
    send();
    await T.run();
    check('and the next Send carries it', !up() && same(kb.flash, bytesOf(page.opts)));
    held = clone(page.opts);

    // --- a send that fails ------------------------------------------------------------------
    change({ pitch_correction: correction({ 20: 5, 30: -4, 40: 7 }) });
    await T.run();
    at = kb.received.length;
    kb.drop = 0x80 + 40;                           // lost on the wire, every time
    send();
    await T.run();
    check('a send that fails leaves the card up, with the refusal and the unsent line',
          up() && headShown() && kbdMsg().kind === 'bad' && kbdMsg().text === page.KBD_REASONS.mismatch
          && countSince(kb, at, 0x3f00) === 0 && same(kb.flash, bytesOf(held)), kbdMsg().text);
    kb.drop = null;
    send();
    await T.run();
    check('and Send again, once it can land, takes it down', !up() && same(kb.flash, bytesOf(page.opts)));
    held = clone(page.opts);

    // --- a record the page cannot build ---------------------------------------------------
    // Only what a send can land brings the card up.
    at = kb.received.length;
    change({ pitch_correction: correction({ 20: 5, 30: -4, 40: 7, 70: 9 }) });
    await T.run();
    check('(up)', up());
    change({ pressure_fix: false, pressure_portamento: true });
    await T.run();
    check('settings the page cannot build take the card down, and nothing is sent',
          !up() && armed() && kb.received.length === at);
    page.opts = clone(held); run('invalidate()');
    await T.run();
    check('(put back)', !up());

    // --- a calibration run has the port -----------------------------------------------------
    page.sweep = { stop: function () {} };
    page.kbd.busy = true;
    at = kb.received.length;
    change({ pitch_correction: correction({ 20: 5, 30: -4, 40: 7, 50: 3 }) });   // as the run's tuned table loads
    await T.run();
    run('refresh()');
    check('a table a calibration run loads brings the card up, with Send held while the run has the port',
          up() && page.nodes.kbdSend.disabled === true && kb.received.length === at);
    run('sweep = null; kbd.busy = false; refresh();');
    check('and Send is free once it ends, still sending nothing by itself',
          up() && page.nodes.kbdSend.disabled === false && kb.received.length === at);
    send();
    await T.run();
    check('then goes like any other', !up() && same(kb.flash, bytesOf(page.opts)));
    held = clone(page.opts);

    // --- a read that loads other than the keyboard holds --------------------------------------
    // The load is not a change, even one that does not give the keyboard's
    // record back exactly: the card waits for something on the page to change.
    at = kb.received.length;
    page.nextLoad = Object.assign(clone(held), { pitch_correction: correction({ 20: 5, 30: -4, 40: 7, 50: 3, 60: 9 }) });
    run('readFrom(keyboardPorts())');
    await T.run();
    check('a read whose load differs from what the keyboard holds leaves the card down',
          differs() && !up() && key(kb.received.slice(at)) === '[[16131,0]]');
    change({ pitch_correction: correction({ 20: 5, 30: -4, 40: 7, 50: 3, 60: 9, 70: 9 }) });
    await T.run();
    check('until a change', up());
    page.opts = clone(held);
    await read();
    check('(read again)', !up() && !differs());

    // --- another port ------------------------------------------------------------------------
    change({ pitch_correction: correction({ 20: 5, 30: -4, 40: 7, 50: 3, 61: 9 }) });
    await T.run();
    check('(up)', up());
    run('$("kbdLoadPort").value = "other"; unsentPicked();');          // what a pick in the lists does
    check('picking another port takes the card down and disarms it', !up() && !armed());
    at = kb.received.length; var atOther = other.received.length;
    change({ pitch_correction: correction({ 20: 5, 30: -4, 40: 7, 50: 3, 63: 9 }) });
    await T.run();
    check('so a change brings up nothing and goes to neither port',
          differs() && !up() && kb.received.length === at && other.received.length === atOther);
    run('$("kbdLoadPort").value = "kbd"; unsentPicked();');
    change({ pitch_correction: correction({ 20: 5, 30: -4, 40: 7, 50: 3, 64: 9 }) });
    await T.run();
    check('nor when the keyboard is picked again, until it is read', !up() && !armed());
    page.opts = clone(held);

    // --- a keyboard on another image ----------------------------------------------------------
    // What the page sends is made for the image it builds: a keyboard on
    // another needs a flash first, so reading it arms nothing.
    var otherImage = bytesOf(held); otherImage[0x10] ^= 0x01;
    var elsewhere = fakeInstrument(otherImage, 'kbd2', '218e on another build');
    page.kbd.outputs.push(elsewhere.output); page.kbd.inputs.push(elsewhere.input);
    run('$("kbdLoadPort").value = "kbd2";');
    page.nextLoad = clone(held);
    run('readFrom(keyboardPorts())');
    await T.run();
    check('the read goes through, and says a flash comes first, with no factory image here',
          page.nodes.kbdLoadMsg.kind === 'warn' && page.state.factoryText === null &&
          page.nodes.kbdLoadMsg.text === 'Keyboard settings loaded successfully. This keyboard runs Rewired ' +
          run('shown(GEN.version)') + ' from another build. Flash the latest firmware to change settings.',
          page.nodes.kbdLoadMsg.text);
    at = elsewhere.received.length;
    change({ pitch_correction: correction({ 20: 5, 30: -4, 40: 7, 50: 3, 65: 9 }) });
    await T.run();
    check('a keyboard running another image is not armed, and a change brings up nothing',
          !armed() && !up() && elsewhere.received.length === at);
    run('$("kbdLoadPort").value = "kbd";');
    page.opts = clone(held);
    // By the version it reports.  A patch apart shows as the same major.minor,
    // so it is earlier or later firmware, not a number said twice.
    var v = page.GEN.version.split('.').map(Number);
    function said(ver) { return run('otherImage(' + JSON.stringify(ver) + ')'); }
    var flashLine = 'Flash the latest firmware to change settings.';
    check('a keyboard a patch behind runs earlier firmware',
          v[2] === 0 || said([v[0], v[1], v[2] - 1].join('.')) ===
          'This keyboard runs earlier firmware than this page builds. ' + flashLine,
          said([v[0], v[1], Math.max(0, v[2] - 1)].join('.')));
    check('a keyboard a patch ahead runs later firmware', said([v[0], v[1], v[2] + 1].join('.')) ===
          'This keyboard runs later firmware than this page. Reload the page.');
    check('a major.minor apart names both', said('2.4.0') ===
          'This keyboard runs Rewired 2.4; this page builds ' + v[0] + '.' + v[1] + '. ' + flashLine
          && said([v[0] + 1, 0, 0].join('.')) === 'This keyboard runs Rewired ' + (v[0] + 1) + '.0; this page is ' +
          v[0] + '.' + v[1] + '. Reload the page.', said('2.4.0'));

    // --- a read that fails ------------------------------------------------------------------
    await read();
    change({ pitch_correction: correction({ 20: 5, 30: -4, 40: 7, 50: 3, 62: 9 }) });
    await T.run();
    check('(up again)', up() && armed());
    kb.mute = true;
    await read();
    check('a read that fails takes the card down and disarms it', !up() && !armed()
          && page.nodes.kbdLoadMsg.text === page.KBD_REASONS['no reply'], page.nodes.kbdLoadMsg.text);
    kb.mute = false;

    if (failures) { console.log(failures + ' failure(s)'); process.exit(1); }
    process.exitCode = 0;
    console.log('ALL UNSENT CARD TESTS PASSED');
})().catch(function (err) { console.log('FAIL  ' + (err && err.stack || err)); process.exit(1); });
