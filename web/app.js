// UI wiring.  All the real work is in build.js; this collects the seven
// options, the optional Scala files and the optional calibration, and shows
// what went wrong when something does.
(function () {
    'use strict';

    var $ = function (id) { return document.getElementById(id); };
    var state = { factoryText: null, factoryMtime: null, slots: null,
                  calibration: null, result: null, patterns: [] };
    // What each preset knob is set to; the buttons below drive it.
    var knobRole = { knob1: 'order', knob2: 'spacing', knob3: 'octaves', knob4: 'vibrato' };

    function download(text, name, type) {
        var a = document.createElement('a');
        a.href = URL.createObjectURL(new Blob([text], { type: type }));
        a.download = name;
        document.body.appendChild(a); a.click(); document.body.removeChild(a);
        setTimeout(function () { URL.revokeObjectURL(a.href); }, 1000);
    }

    function msg(el, kind, text) {
        el.innerHTML = '';
        if (!text) return;
        var d = document.createElement('div');
        d.className = 'msg ' + kind;
        d.textContent = text;
        bindDashes(d);
        el.appendChild(d);
    }

    /* An em dash is Unicode line-break class B2 - "break opportunity before
       and after" - so a browser may begin a line with one however the spacing
       reads, and text-wrap:pretty can only choose among the breaks it is
       handed.  Bind each dash to the word ahead of it: the no-break space
       removes the break at the space, the word joiner removes B2, and a line
       can then only break after the dash.

       This runs over rendered text and never over the strings themselves -
       the same sentences go into README.txt and the flasher scripts inside
       the download, and those want plain ASCII spacing.  Idempotent: a bound
       run no longer holds a plain space, so it stops matching.  */
    function bindDashes(root) {
        var w = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
            acceptNode: function (n) {
                var p = n.parentNode && n.parentNode.nodeName;
                if (p === 'SCRIPT' || p === 'STYLE' || p === 'PRE' || p === 'CODE')
                    return NodeFilter.FILTER_REJECT;
                return / +\u2014 /.test(n.nodeValue)
                    ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_REJECT;
            }
        });
        var n, hit = [];
        while ((n = w.nextNode())) hit.push(n);
        hit.forEach(function (t) {
            t.nodeValue = t.nodeValue.replace(/ +\u2014 /g, '\u00a0\u2060\u2014 ');
        });
    }

    // --- semitone naming -------------------------------------------------
    // Semitone 0 is the 208's 0 V pitch.  A 208, 208r or 208p starts from
    // A, which puts the bottom key - a C - at semitone 3; the 208c starts
    // from C, so there the bottom key IS semitone 0.  Keys are numbered from
    // 1 — the three different ways the CSV let you name a row, and the
    // reason this shows all of them.
    var pitchOffset = true;
    var NAMES_A = ['A','A#','B','C','C#','D','D#','E','F','F#','G','G#'];
    var NAMES_C = ['C','C#','D','D#','E','F','F#','G','G#','A','A#','B'];
    function noteNames() { return pitchOffset ? NAMES_A : NAMES_C; }
    function noteName(semitone) {
        // The bottom key is C1 either way; only the row it sits on moves.
        return noteNames()[semitone % 12] +
            Math.floor((semitone + 12 - PLAYABLE_LOW) / 12);
    }
    // The 32 physical keys start at the bottom C, key 1.  Above that the
    // same keys reach higher pitches through the octave switch.
    function keyLabel(semitone) {
        if (semitone >= PLAYABLE_LOW && semitone <= PLAYABLE_LOW + 31) {
            return String(semitone - PLAYABLE_LOW + 1);
        }
        return '+oct';
    }

    // Only pitches the instrument can actually produce are editable.  The
    // firmware indexes semitones 0..78, but with the offset 0..2 sit below
    // the bottom key and 68..78 above anything the octave switch can reach,
    // so neither can be played or measured.  They are filled in from the
    // ends of the measured range instead of being offered as boxes nobody
    // can fill.  Without the offset the same 65 notes start at semitone 0.
    var PLAYABLE_LOW = 3, PLAYABLE_HIGH = 67, TABLE_ENTRIES = 79;
    function setPitchOffset(on) {
        if (on === pitchOffset) return;
        var from = PLAYABLE_LOW;
        pitchOffset = on;
        PLAYABLE_LOW = on ? 3 : 0;
        PLAYABLE_HIGH = PLAYABLE_LOW + 64;
        // Readings belong to keys, not rows: a table entered before the
        // switch keeps each key's cents when the rows move under it.
        var moved = measured.map(function () { return 0; });
        for (var n = 0; n < TABLE_ENTRIES; n++) {
            var to = n - from + PLAYABLE_LOW;
            if (to >= 0 && to < TABLE_ENTRIES) moved[to] = measured[n];
        }
        measured = moved;
        loadedTail = null;
        buildTable(); drawPlot(); validateCal(); invalidate();
    }

    // --- factory image ---------------------------------------------------
    function loadFactory(file) {
        var reader = new FileReader();
        reader.onload = function () {
            var text = reader.result;
            var sha;
            try { sha = SHA256.hashString(text); }
            catch (e) { return fail('Could not read that file.'); }
            if (sha !== GEN.factorySha256) {
                state.factoryText = null;
                state.result = null;
                $('drop').className = 'drop err';
                msg($('fileMsg'), 'bad',
                    'That is not the stock v36.9 image.\n\nexpected  ' + GEN.factorySha256 +
                    '\nthis file ' + sha +
                    '\n\nThe build only accepts the exact factory image, so a wrong or ' +
                    'altered file is rejected rather than flashed.');
            } else {
                state.factoryText = text;
                // The date the file already had.  It goes back into the
                // download with it, so the stock image keeps saying when it
                // was made rather than when it was handed back.
                state.factoryMtime = (file && file.lastModified)
                    ? new Date(file.lastModified) : null;
                $('drop').className = 'drop ok';
                msg($('fileMsg'), 'ok', 'Factory image verified: SHA-256 matches. ' +
                    'It stays on this machine.');
            }
            refresh();
        };
        reader.onerror = function () { fail('Could not read that file.'); };
        reader.readAsText(file);
        function fail(t) {
            state.factoryText = null;
            state.result = null;
            $('drop').className = 'drop err';
            msg($('fileMsg'), 'bad', t);
            refresh();
        }
    }

    var drop = $('drop');
    drop.addEventListener('click', function () { $('file').click(); });
    // The real input is hidden and unfocusable, so the drop zone is the
    // keyboard's way in: without this, no keyboard or switch user could
    // supply the factory image, and everything downstream is gated on it.
    drop.addEventListener('keydown', function (e) {
        if (e.key === 'Enter' || e.key === ' ') {
            e.preventDefault();
            $('file').click();
        }
    });
    drop.addEventListener('dragover', function (e) {
        e.preventDefault(); drop.classList.add('over');
    });
    drop.addEventListener('dragleave', function () { drop.classList.remove('over'); });
    drop.addEventListener('drop', function (e) {
        e.preventDefault(); drop.classList.remove('over');
        if (e.dataTransfer.files[0]) loadFactory(e.dataTransfer.files[0]);
    });
    $('file').addEventListener('change', function (e) {
        if (e.target.files[0]) loadFactory(e.target.files[0]);
    });

    // --- volts per octave -------------------------------------------------
    var vpo = 1.2;
    Array.prototype.forEach.call($('vpo').children, function (b) {
        b.addEventListener('click', function () {
            vpo = parseFloat(b.dataset.v);
            updateOffsetNote();
            // The DAC-range check depends on the scaling, so a green verdict
            // given at 1 V/oct must not survive a switch to 1.2 unexamined.
            validateCal();
            invalidate();
            Array.prototype.forEach.call($('vpo').children, function (o) {
                o.setAttribute('aria-pressed', String(o === b));
            });
        });
    });

    // --- pitch offset -----------------------------------------------------
    // What the bottom key puts out at the lowest octave position: nothing
    // with the offset off, three semitones at the chosen scaling with it on.
    // Follows both pickers, so it is refreshed from each.
    function updateOffsetNote() {
        var volts = pitchOffset ? vpo * 3 / 12 : 0;
        $('offsetNote').textContent = 'The lowest key will output ' +
            parseFloat(volts.toFixed(3)) + 'V.';
    }
    Array.prototype.forEach.call($('offset').children, function (b) {
        b.addEventListener('click', function () {
            setPitchOffset(b.dataset.v === '1');
            updateOffsetNote();
            Array.prototype.forEach.call($('offset').children, function (o) {
                o.setAttribute('aria-pressed', String(o === b));
            });
        });
    });
    updateOffsetNote();

    // --- Scala files ------------------------------------------------------
    // The three slots are not interchangeable, so which file goes where is a
    // real choice rather than upload order: slot 0 is what the instrument
    // powers up in, and the two edit keys each toggle against slot 2.
    var SLOTS = [
        { name: 'Slot 0', note: 'power-on default · rem-en LED lit · edit key 28 toggles it against slot 2' },
        { name: 'Slot 1', note: 'trn LED lit · edit key 27 toggles it against slot 2' },
        { name: 'Slot 2', note: 'both LEDs dark · the slot the other two toggle against' }
    ];
    // Start with the three bundled scales already in their slots, but behind
    // a checkbox that defaults to off: ticking it is what opts in, and the
    // slots are then already sensible rather than empty.
    state.slots = GEN.bundledTunings.map(function (t) {
        return { name: t.name, text: t.text };
    });

    // All three are drawn, not typed.  U+2191 and U+2193 are outside Latin-1
    // and were substituted per browser, which is what made the row look wrong
    // in Safari.  The cross could be U+00D7, which Euclid does have, but a
    // drawn one matches the arrows beside it and its weight is a number here
    // rather than the font's idea of bold.
    var ICONS = {
        up:    'M12 19V5M5 12l7-7 7 7',
        down:  'M12 5v14M19 12l-7 7-7-7',
        clear: 'M6 6l12 12M18 6L6 18'
    };
    function icon(name) {
        var ns = 'http://www.w3.org/2000/svg';
        var svg = document.createElementNS(ns, 'svg');
        svg.setAttribute('viewBox', '0 0 24 24');
        svg.setAttribute('aria-hidden', 'true');
        svg.setAttribute('focusable', 'false');
        var path = document.createElementNS(ns, 'path');
        path.setAttribute('d', ICONS[name]);
        path.setAttribute('fill', 'none');
        path.setAttribute('stroke', 'currentColor');
        path.setAttribute('stroke-width', '2.4');
        path.setAttribute('stroke-linecap', 'round');
        path.setAttribute('stroke-linejoin', 'round');
        svg.appendChild(path);
        return svg;
    }

    // The .kbm picker is shared; this says whose button opened it.
    var mapTarget = -1;

    // Knob 2's pattern bank.  A row per pattern, a cell per step: the grid is
    // the honest shape for something whose meaning is which steps sound, and
    // the text form beside it is what people paste to each other.
    function patternText(p) {
        return p.text.slice(0, p.length);
    }
    // How many steps to a group.  The largest divisor of the length that is
    // still a group worth reading - up to eight, and never so small that the
    // pattern becomes a row of pairs - or the whole length when nothing
    // divides it evenly.
    function barSize(length) {
        for (var n = Math.min(8, length); n >= 3; n--) {
            if (length % n === 0) return n;
        }
        return length;
    }

    function renderPatterns() {
        var list = $('patList');
        list.textContent = '';
        state.patterns.forEach(function (p, i) {
            var row = document.createElement('div');
            row.className = 'pat';

            var n = document.createElement('span');
            n.className = 'patnum';
            n.textContent = (i + 1);
            row.appendChild(n);

            // Grouped so the groups come out even: 16 as 8 and 8, 10 as 5 and
            // 5, 9 as three 3s.  A length with no such division - 11, 13, 22 -
            // is left as one run rather than broken up unevenly.
            var grid = document.createElement('span');
            grid.className = 'patgrid';
            var bars = barSize(p.length);
            // A length nothing divides - 26, say - has no groups to draw, and
            // a single group that long cannot wrap: it would run out under the
            // length field.  Those steps go straight into the row and wrap
            // wherever they run out of line.
            var grouped = bars <= 8;
            if (!grouped) grid.className += ' ungrouped';
            // The marked step counts the group, not a fixed four: at groups of
            // five, every fourth step cuts across them.  It marks each group's
            // first step, which is what a wrapped line loses - the gap that
            // separates groups is not there at the start of a line.  Ungrouped
            // lengths have no group to count, so the mark is a plain ruler.
            var beat = grouped ? bars : 4;
            var bar = grid;
            for (var k = 0; k < p.length; k++) {
                (function (step) {
                    if (grouped && step % bars === 0) {
                        bar = document.createElement('span');
                        bar.className = 'patbar';
                        grid.appendChild(bar);
                    }
                    var cell = document.createElement('button');
                    cell.type = 'button';
                    cell.className = 'step' + (p.text[step] !== '.' ? ' on' : '')
                        + (step % beat === 0 ? ' beat' : '');
                    cell.title = 'step ' + (step + 1);
                    cell.addEventListener('click', function () {
                        var t = p.text.split('');
                        t[step] = t[step] === '.' ? 'x' : '.';
                        p.text = t.join('');
                        renderPatterns(); invalidate();
                    });
                    bar.appendChild(cell);
                })(k);
            }
            row.appendChild(grid);

            // The number keeps its field, but not the browser's own up and
            // down arrows: those are drawn in the platform's colours and are
            // all but invisible on this background.  Ours are the same
            // chevrons the rest of the page uses.
            var lenbox = document.createElement('span');
            lenbox.className = 'patlenbox';
            var len = document.createElement('input');
            len.type = 'number'; len.min = 1; len.max = 32; len.value = p.length;
            len.className = 'patlen'; len.title = 'steps before it repeats';
            function setLength(v) {
                v = Math.max(1, Math.min(32, v || 1));
                while (p.text.length < v) p.text += '.';
                p.length = v;
                renderPatterns(); invalidate();
            }
            len.addEventListener('change', function () {
                setLength(parseInt(len.value, 10));
            });
            lenbox.appendChild(len);

            var steppers = document.createElement('span');
            steppers.className = 'patsteps';
            [['up', 1], ['down', -1]].forEach(function (pair) {
                var b = document.createElement('button');
                b.type = 'button';
                b.className = 'patstep';
                b.title = pair[1] > 0 ? 'one step longer' : 'one step shorter';
                b.appendChild(icon(pair[0]));
                b.addEventListener('click', function () {
                    setLength(p.length + pair[1]);
                });
                steppers.appendChild(b);
            });
            lenbox.appendChild(steppers);
            row.appendChild(lenbox);

            var x = document.createElement('button');
            x.type = 'button'; x.className = 'clear'; x.title = 'remove this pattern';
            x.appendChild(icon('clear'));
            x.addEventListener('click', function () {
                state.patterns.splice(i, 1);
                renderPatterns(); invalidate();
            });
            row.appendChild(x);
            list.appendChild(row);
        });
        $('patternBody').classList.toggle('hidden', knobRole.knob2 !== 'patterns');
        $('patAdd').disabled = state.patterns.length >= 32;
    }

    function renderSlots() {
        var host = $('slots');
        host.innerHTML = '';
        SLOTS.forEach(function (meta, i) {
            var entry = state.slots[i];
            var row = document.createElement('div');
            row.className = 'slot';

            var who = document.createElement('div');
            who.className = 'who';
            who.innerHTML = '<b>' + meta.name + '</b>';

            var note = document.createElement('span');
            note.className = 'note';
            note.textContent = meta.note;

            var what = document.createElement('div');
            what.className = 'what' + (entry ? '' : ' empty');
            if (entry) {
                // Each scale is shifted so the same key lands on the 12-TET
                // grid in every slot, which is why switching tuning never
                // moves the note the 208 was trimmed to.  Worth showing: it is
                // computed here, not baked into the file.
                var anchorChip = '', anchorTip = '', mapShape = '';
                try {
                    // Resolved through the same function web/build.js uses, so
                    // what the page says about a slot cannot drift from what
                    // the build does with it.
                    var scale = BUILDLIB.slotScale(entry);
                    var usable = scale.degrees || scale.cents.length - 1 === 12;
                    if (scale.degrees) mapShape = scale.degrees.length + ' keys/oct';
                    var period = usable ? scale.cents[scale.formal] : 1200.0;
                    // The build drops the anchor when the scale does not repeat
                    // at the octave: pinning one key to its 12-TET pitch says
                    // nothing about a scale that has no place on that grid, so
                    // degree 0 keeps the bottom key instead.  Reporting the
                    // offset anyway named a shift no image ever carried.
                    if (!usable) {
                        // Not buildable yet - the slot's own warning says why.
                    } else if (Math.abs(period - 1200.0) > 0.001) {
                        anchorChip = 'bottom key anchored';
                        anchorTip = 'anchored on the bottom key';
                    } else {
                        var offset = BUILDLIB.anchorOffset(
                            scale.cents, 9, scale.degrees, period);
                        var shift = '  ' + (offset >= 0 ? '+' : '')
                            + offset.toFixed(2) + 'c';
                        anchorChip = 'A anchored' + shift;
                        anchorTip = 'anchored on A by' + shift;
                    }
                } catch (e) { /* already reported on load */ }
                var fname = document.createElement('span');
                fname.className = 'fname';
                fname.textContent = entry.name;
                what.appendChild(fname);
                what.title = entry.name + (entry.kbmName ? ' mapped by ' + entry.kbmName : '') +
                    (anchorTip ? ': ' + anchorTip : '');
                if (entry.kbmName) {
                    var chip = document.createElement('span');
                    chip.className = 'kbmchip';
                    chip.textContent = entry.kbmName;
                    var off = document.createElement('button');
                    off.textContent = '\u00d7';
                    off.title = 'remove this keyboard mapping';
                    off.addEventListener('click', function () {
                        delete entry.kbmName; delete entry.kbmText;
                        renderSlots(); invalidate();
                    });
                    chip.appendChild(off);
                    what.appendChild(chip);
                }
                if (anchorChip) {
                    var tag = document.createElement('span');
                    tag.className = 'muted';
                    tag.style.cssText = 'font-family:inherit;font-size:11px;margin-left:8px';
                    tag.textContent = anchorChip +
                        (mapShape ? ' · ' + mapShape : '');
                    what.appendChild(tag);
                }
                if (entry.needsMap && !entry.kbmText) {
                    var warn = document.createElement('span');
                    warn.className = 'kbmneed';
                    warn.textContent = 'needs a keyboard mapping';
                    what.appendChild(warn);
                }
            } else {
                what.textContent = 'factory temperament';
            }

            var ctl = document.createElement('div');
            ctl.className = 'ctl';
            [['up', i - 1], ['down', i + 1]].forEach(function (pair) {
                var b = document.createElement('button');
                b.appendChild(icon(pair[0]));
                b.title = 'move to slot ' + pair[1];
                b.disabled = !entry || pair[1] < 0 || pair[1] > 2;
                b.addEventListener('click', function () {
                    var to = pair[1], tmp = state.slots[to];
                    state.slots[to] = state.slots[i];
                    state.slots[i] = tmp;
                    renderSlots(); invalidate();
                });
                ctl.appendChild(b);
            });
            // Each scale carries its own mapping button, because a .kbm
            // belongs to one scale and nothing about the file says which.
            var m = document.createElement('button');
            m.textContent = '.kbm';
            m.disabled = !entry;
            m.className = 'kbmbtn' + (entry && entry.kbmText ? ' mapped' : '');
            m.title = !entry ? 'no scale in this slot'
                : entry.kbmText ? 'replace ' + entry.kbmName + ' — the keyboard mapping'
                : 'add a keyboard mapping (.kbm) for ' + entry.name;
            m.addEventListener('click', function () {
                mapTarget = i;
                $('kbm').value = '';   // re-picking the same file must still fire
                $('kbm').click();
            });
            ctl.appendChild(m);

            var x = document.createElement('button');
            x.className = 'clear';
            x.appendChild(icon('clear')); x.title = 'clear this slot';
            x.disabled = !entry;
            x.addEventListener('click', function () {
                state.slots[i] = null; renderSlots(); invalidate();
            });
            ctl.appendChild(x);

            // order sets the grid flow: name and controls share row 1, the
            // file takes row 2, the note runs full width beneath both.
            row.appendChild(who); row.appendChild(ctl);
            row.appendChild(what); row.appendChild(note);
            host.appendChild(row);
        });
        var filled = state.slots.filter(Boolean).length;
        $('sclCount').textContent = filled
            ? filled + ' of 3 slots set'
            : 'all three slots factory';
    }

    // A mapping is validated against the scale it is being attached to: the
    // degree it names has to exist in THAT scale, so the file alone cannot
    // say whether it is good.
    $('kbm').addEventListener('change', function (e) {
        var f = e.target.files && e.target.files[0];
        var slot = state.slots[mapTarget];
        if (!f || !slot) return;
        var r = new FileReader();
        r.onload = function () {
            try {
                var cents = BUILDLIB.parseScala(slot.text, slot.name, true);
                BUILDLIB.parseKbm(r.result, f.name, cents);
            } catch (err) {
                msg($('sclMsg'), 'bad', err.message);
                return;
            }
            slot.kbmName = f.name;
            slot.kbmText = r.result;
            msg($('sclMsg'), '', '');
            renderSlots(); invalidate();
        };
        r.onerror = function () { msg($('sclMsg'), 'bad', f.name + ': could not be read'); };
        r.readAsText(f);
    });

    // The pattern bank's own controls.
    $('patAdd').addEventListener('click', function () {
        if (state.patterns.length >= 32) return;
        // A copy of the last row: a bank is usually variations on something,
        // and a variation starts from what it varies.  The first one has
        // nothing to copy, so it gets a plain four-to-the-floor.
        var last = state.patterns[state.patterns.length - 1];
        state.patterns.push(last
            ? { text: last.text, length: last.length }
            : { text: 'x...x...x...x...', length: 16 });
        renderPatterns(); invalidate();
    });
    $('patClix').addEventListener('click', function () {
        state.patterns = GEN.clix.map(function (mask) {
            var t = '';
            for (var i = 0; i < 32; i++) t += (mask >>> i) & 1 ? 'x' : '.';
            return { text: t, length: 32 };
        });
        renderPatterns(); invalidate();
    });
    $('patCopy').addEventListener('click', function () {
        download(state.patterns.map(patternText).join('\n') + '\n',
                 'patterns.txt', 'text/plain');
    });
    $('patPaste').addEventListener('click', function () { $('patFile').click(); });
    $('patFile').addEventListener('change', function (e) {
        var file = e.target.files[0];
        e.target.value = '';
        if (!file) return;
        var r = new FileReader();
        r.onload = function () { readPatterns(String(r.result)); };
        r.readAsText(file);
    });
    // One pattern per line; a dot is a rest and anything else a hit, which is
    // how these get written down and passed around.
    function readPatterns(text) {
        var rows = text.split(/[\r\n]+/).map(function (l) {
            return l.replace(/\s+/g, '');
        }).filter(function (l) { return l.length; });
        var bad = rows.filter(function (l) {
            return l.length > 32 || !/[^.]/.test(l);
        });
        if (!rows.length || bad.length) {
            msg($('buildMsg'), 'bad', !rows.length
                ? 'Nothing to read there.'
                : 'Each line needs 1 to 32 steps and at least one hit: '
                  + JSON.stringify(bad[0]));
            return;
        }
        if (rows.length > 32) {
            msg($('buildMsg'), 'bad', rows.length + ' patterns; the bank holds 32.');
            return;
        }
        state.patterns = rows.map(function (l) {
            var t = l.replace(/[^.]/g, 'x');
            return { text: t + '.'.repeat(32 - t.length), length: t.length };
        });
        msg($('buildMsg'), '', state.patterns.length + ' patterns read.');
        renderPatterns(); invalidate();
    }

    $('sclPick').addEventListener('click', function () { $('scl').click(); });
    $('scl').addEventListener('change', function (e) {
        var files = Array.prototype.slice.call(e.target.files);
        var problems = [];
        var pending = files.length;
        if (!pending) return;
        // Every file is read before any is placed: a .kbm has to find its
        // scale, and a multi-select hands them over in whatever order the
        // browser likes.  Scales take slots first, then each map is paired
        // with the scale of the same name, or with the first mapless slot.
        files.forEach(function (f) {
            var r = new FileReader();
            r.onload = function () {
                var entry = { name: f.name, text: r.result };
                try {
                    // Validate now, so a bad scale is caught while it is still
                    // obvious which file it was.
                    BUILDLIB.parseScala(entry.text, entry.name);
                } catch (err) {
                    // A count other than twelve is not wrong, only unfinished:
                    // a keyboard mapping decides what the keys do with it.  Any
                    // other complaint is a real one.
                    try {
                        BUILDLIB.parseScala(entry.text, entry.name, true);
                        entry.needsMap = true;
                    } catch (fatal) {
                        problems.push(fatal.message);
                        return;
                    }
                }
                var free = state.slots.indexOf(null);
                if (free < 0) { problems.push(f.name + ': all three slots are full'); return; }
                state.slots[free] = entry;
            };
            r.onerror = function () {
                problems.push(f.name + ': could not be read');
            };
            // loadend fires after load AND after error, so the countdown
            // reaches zero either way - an unreadable file used to freeze
            // the whole listing while the readable ones were already in.
            r.onloadend = function () {
                if (--pending === 0) {
                    renderSlots(); invalidate();
                    msg($('sclMsg'), problems.length ? 'bad' : '', problems.join('\n'));
                }
            };
            r.readAsText(f);
        });
        e.target.value = '';   // so the same file can be picked again
    });

    // --- calibration ------------------------------------------------------
    // What the user types is a MEASUREMENT: how many cents sharp the note
    // played, positive for sharp.  The firmware wants the opposite — a
    // correction that pushes the pitch back — so rows() negates.
    //
    // That negation is exact rather than approximate.  Folding a reading into
    // an existing table has to scale it by the octave width at that pitch,
    // because a cent costs more voltage where the 208's scaling is stretched;
    // but this table starts flat, where the width is exactly 1.000, so the
    // correction is simply minus the reading.  Which is also why nothing
    // accumulates here: each entry stands alone.
    var measured = [];
    // Rows a loaded CSV supplied OUTSIDE the playable keys, kept verbatim so
    // the same file builds the same image here and in tools/build.py - the
    // loader used to regenerate the tails, so a full 79-row calibration
    // built one image on the page and another on the CLI, silently.  Cleared
    // the moment any key is edited: the tail belonged to the measurements it
    // arrived with.
    var loadedTail = null;
    for (var i = 0; i < TABLE_ENTRIES; i++) measured.push(0);

    function drawPlot() {
        var play = measured.slice(PLAYABLE_LOW, PLAYABLE_HIGH + 1);
        var svg = $('calPlot'), lo = Math.min.apply(null, play),
            hi = Math.max.apply(null, play);
        if (hi - lo < 1) { lo -= 1; hi += 1; }
        var span = PLAYABLE_HIGH - PLAYABLE_LOW;
        // The labels own the top and bottom 16 units: an 11px label on a
        // 12/118 baseline spans roughly y 4-12 and 110-118, and the curve's
        // extreme lands at the left edge whenever the table is flat there,
        // which is exactly where the labels are.  So the curve keeps out of
        // those bands rather than crossing them.
        function y(v) { return 104 - (v - lo) / (hi - lo) * 88; }
        var pts = play.map(function (v, i) {
            return (i / span * 700).toFixed(1) + ',' + y(v).toFixed(1);
        }).join(' ');
        // In tune is a place on this plot, so it is drawn: a hairline at
        // zero, under the curve, and only when zero is inside the range -
        // pinned to an edge it would read as a frame, not a datum.  Muted
        // ink at low opacity rather than var(--line): the plot sits on
        // panel2, which is lighter than the panel that hairline was mixed
        // against, and there the line all but vanished.
        var zero = lo < 0 && hi > 0 ?
            '<line x1="0" x2="700" y1="' + y(0).toFixed(1) + '" y2="' +
            y(0).toFixed(1) + '" stroke="var(--muted)" stroke-opacity=".4" ' +
            'stroke-width="1" vector-effect="non-scaling-stroke"/>' : '';
        svg.innerHTML = zero +
            '<polyline points="' + pts + '" fill="none" stroke="var(--accent)" ' +
            'stroke-width="2" vector-effect="non-scaling-stroke"/>';
        // The labels are HTML beside the svg, not <text> inside it: the plot
        // stretches to its column (preserveAspectRatio none), and type drawn
        // inside would stretch with it.  Lines may distort; letters may not.
        $('calHi').textContent = hi.toFixed(1) + ' cents';
        $('calLo').textContent = lo.toFixed(1);
    }

    // The offsets are laid out as the keyboard they describe: naturals along
    // the bottom, accidentals raised between them, each key carrying its own
    // cents box.  Sixty-five numbered rows made you count to find a note; a
    // keyboard is found by shape.
    var WHITE_W = 48, BLACK_W = 32;
    function buildTable() {
        var kbd = $('calKeys');
        kbd.innerHTML = '';
        var whites = 0;
        for (var n = PLAYABLE_LOW; n <= PLAYABLE_HIGH; n++) {
            (function (n) {
                var black = noteNames()[n % 12].indexOf('#') >= 0;
                var key = document.createElement('div');
                key.className = 'key ' + (black ? 'black' : 'white');
                // A black key straddles the join between the two naturals it
                // sits between, so it hangs half its width back from the next.
                key.style.left = (black ? whites * WHITE_W - BLACK_W / 2
                                        : whites * WHITE_W) + 'px';
                if (!black) whites++;

                var nm = document.createElement('span');
                nm.className = 'nm';
                nm.textContent = noteName(n);

                var input = document.createElement('input');
                input.type = 'number';
                input.step = '0.01';
                input.value = measured[n].toFixed(2);
                input.title = noteName(n) + ', key ' + keyLabel(n);
                if (measured[n] !== 0) input.className = 'set';
                input.addEventListener('change', function () {
                    measured[n] = parseFloat(input.value) || 0;
                    input.className = measured[n] !== 0 ? 'set' : '';
                    loadedTail = null;
                    drawPlot(); validateCal();
                    if ($('useCal').checked) invalidate();
                });
                key.appendChild(nm);
                key.appendChild(input);
                kbd.appendChild(key);
            })(n);
        }
        kbd.style.width = (whites * WHITE_W) + 'px';
    }

    // A table with every entry at zero corrects nothing: the image it builds
    // is the one calibration-off builds, so the page reports it that way.
    // The checkbox alone used to count as "applied" - image.txt and the
    // beacon both said the correction was in while the image carried the
    // flat ramp.  The readings live only in this page, so a fresh visit
    // with the box ticked and no CSV loaded is exactly that table.
    function calibrationBlank() {
        return rows().every(function (r) { return r.cents === 0; });
    }

    function validateCal() {
        if (!$('useCal').checked || calibrationBlank()) {
            msg($('calMsg'), '', '');
            return true;
        }
        try {
            var cfg = BUILDLIB.expand({ volts_per_octave: vpo, pitch_offset: pitchOffset,
                                        pitch_correction: rows() });
            BUILDLIB.pitchTable(cfg, rows());
            msg($('calMsg'), 'ok', 'Correction is monotonic and inside the 12-bit DAC.');
            return true;
        } catch (e) {
            var hint = '';
            if (/DAC range/.test(e.message)) {
                hint = '\n\nThe corrected pitch runs past what the DAC can produce. That ' +
                       'usually means the lowest C was not tuned in before measuring, so ' +
                       'every reading carries the same offset. Retune it and measure again.';
            } else if (/monotonic/.test(e.message)) {
                hint = '\n\nThe corrected pitch goes backwards somewhere: a note ends up ' +
                       'lower than the one below it. Check for a reading with the wrong ' +
                       'sign, or one entered against the wrong note.';
            }
            msg($('calMsg'), 'bad', e.message + hint);
            return false;
        }
    }

    // Extend the playable range over the rest of the table: below the bottom
    // key everything holds the lowest measured value, and above the top the
    // correction keeps climbing at the slope it ended on, which is what the
    // shipped calibration does.
    function rows() {
        var full = measured.map(function (v) { return -v; });
        // Below the lowest playable note the correction is zero, matching the
        // shipped calibration.  Carrying the lowest correction down instead
        // would push semitone 0 below zero volts for any instrument reading
        // uniformly sharp, and those entries are unreachable anyway.
        for (var n = PLAYABLE_LOW - 1; n >= 0; n--) full[n] = 0;
        var slope = full[PLAYABLE_HIGH] - full[PLAYABLE_HIGH - 1];
        for (n = PLAYABLE_HIGH + 1; n < TABLE_ENTRIES; n++) {
            full[n] = full[n - 1] + slope;
        }
        // A loaded file's own out-of-range rows win over the derivation, so
        // the CSV builds byte-identically to the CLI reading the same file.
        if (loadedTail) {
            for (n = 0; n < TABLE_ENTRIES; n++) {
                if (n in loadedTail) full[n] = -loadedTail[n];
            }
        }
        return full.map(function (v, i) { return { semitone: i, cents: v }; });
    }

    function syncCalBody() {
        $('calBody').classList.toggle('hidden', !$('useCal').checked);
    }
    $('useCal').addEventListener('change', function () {
        syncCalBody(); validateCal(); invalidate();
    });
    $('calZero').addEventListener('click', function () {
        loadedTail = null;
        measured = measured.map(function () { return 0; });
        buildTable(); drawPlot(); validateCal();
        msg($('calMsg'), '', '');
        // The table is part of the image: without this the image built
        // from the old readings stayed downloadable after they were cleared.
        invalidate();
    });
    // Same columns the loader reads and the repository's own calibration file
    // uses, so a table can go out, be edited or shared, and come back.
    $('calSave').addEventListener('click', function () {
        var out = [
            '# 218e pitch measurements, saved from the Rewired firmware builder.',
            '#',
            '# Measured_Cents is how far each note played from correct pitch, as read',
            '# on a tuner.  Positive means it played SHARP.  The builder works out the',
            '# correction from these; do not negate them yourself.',
            '#',
            '# Semitone counts up from the 208\'s 0 V pitch; the lowest C on the',
            '# keyboard is semitone ' + PLAYABLE_LOW + '.  Only notes the keyboard can play are',
            '# listed - the rest of the table is derived from these.',
            'Semitone;Note;Key;Measured_Cents'
        ];
        for (var n = PLAYABLE_LOW; n <= PLAYABLE_HIGH; n++) {
            out.push([n, noteName(n), keyLabel(n), measured[n].toFixed(6)].join(';'));
        }
        download(out.join('\n') + '\n', '218e-pitch-measurements.csv', 'text/csv');
    });

    $('calPick').addEventListener('click', function () { $('calFile').click(); });
    $('calFile').addEventListener('change', function (e) {
        var f = e.target.files[0];
        e.target.value = '';   // so the same file can be picked again
        if (!f) return;
        var r = new FileReader();
        r.onload = function () {
            var found = 0;
            var tail = {}, tailCount = 0;
            // A file of Offset_Cents holds corrections, the opposite sign to a
            // measurement, so it is flipped on the way in.
            var isCorrection = /Offset_Cents/i.test(r.result);
            // Split on any line ending: CRLF from Windows, and CR alone,
            // which Excel can still write.  CRLF already worked - a stray
            // \r rides on the last field, which parseFloat ignores - but a
            // CR-only file arrives as one long line and yields nothing.
            r.result.split(/\r\n|\r|\n/).forEach(function (line) {
                if (!line.trim() || line.charAt(0) === '#' || /^Semitone/i.test(line)) return;
                var semi = line.indexOf(';') >= 0;
                var p = line.split(semi ? ';' : ',');
                var cRaw = p[3] || '';
                // Excel in a comma-decimal locale re-saves a semicolon file
                // with '12,5' where this wrote '12.5'; parseFloat stops at
                // the comma and every fraction was silently dropped.
                if (semi && /^\s*-?\d+,\d+\s*$/.test(cRaw)) {
                    cRaw = cRaw.replace(',', '.');
                }
                var n = parseInt(p[0], 10), c = parseFloat(cRaw);
                if (isCorrection) c = -c;
                if (!isNaN(n) && !isNaN(c)) {
                    if (n >= PLAYABLE_LOW && n <= PLAYABLE_HIGH) {
                        measured[n] = c; found++;
                    } else if (n >= 0 && n < TABLE_ENTRIES) {
                        // The keys cannot edit these, but the file said what
                        // they are, and the build honours the file.
                        tail[n] = c; tailCount++;
                    }
                }
            });
            loadedTail = (found && tailCount) ? tail : null;
            $('useCal').checked = found > 0;
            syncCalBody();
            buildTable(); drawPlot(); validateCal(); invalidate();
            msg($('calMsg'), found ? 'ok' : 'bad',
                found ? 'Loaded ' + found + ' playable rows' +
                        (tailCount ? ' and ' + tailCount + ' beyond the keys'
                                   : '') + ' from ' + f.name
                      : 'No usable rows in ' + f.name +
                        ': expected Semitone;Note;Key;Offset_Cents;Source');
        };
        r.readAsText(f);
    });

    // --- build ------------------------------------------------------------
    function options() {
        var o = {
            latching_arp: $('latching_arp').checked,
            sequencer: $('sequencer').checked,
            clock_divide: $('clock_divide').checked,
            // Each knob's own pick; 'factory' is the None row.
            knob1: knobRole.knob1,
            knob2: knobRole.knob2,
            knob3: knobRole.knob3,
            knob4: knobRole.knob4,
            arp_patterns: knobRole.knob2 === 'patterns'
                ? state.patterns.map(function (p) { return [p.text, p.length]; })
                : null,
            pressure_fix: $('pressure_fix').checked,
            pressure_portamento: $('pressure_portamento').checked,
            volts_per_octave: vpo,
            pitch_offset: pitchOffset,
            quantize_presets: $('quantize_presets').checked,
            portamento_in: $('portamento_transpose').checked ? 'transpose' : 'portamento'
        };
        // The checkbox is the opt-in: off means factory everything, however
        // the slots are filled.  Trailing empty slots simply shorten the
        // list; a gap in the middle stays a gap, sent as an explicit factory
        // slot.
        var slots = $('useTunings').checked ? state.slots.slice() : [];
        while (slots.length && !slots[slots.length - 1]) slots.pop();
        if (slots.length) {
            o.alternate_tunings = slots.map(function (e) { return e || 'factory'; });
        }
        if ($('useCal').checked && !calibrationBlank()) o.pitch_correction = rows();
        return o;
    }

    // Portamento depends on the pressure fix for its data, so the page keeps
    // the pair consistent rather than letting the build refuse it later.
    function syncPortamento() {
        var fix = $('pressure_fix'), porta = $('pressure_portamento');
        porta.disabled = !fix.checked;
        var label = porta.closest('label');
        label.style.opacity = fix.checked ? '' : '0.45';
        label.title = fix.checked ? ''
            : 'Needs the pressure response fix: the blend weights pitch by per-key pressure.';
        if (!fix.checked) porta.checked = false;
    }
    $('pressure_fix').addEventListener('change', syncPortamento);
    ['latching_arp', 'sequencer', 'clock_divide',
     'pressure_fix', 'pressure_portamento', 'quantize_presets',
     'portamento_transpose']
        .forEach(function (id) {
            $(id).addEventListener('change', invalidate);
        });

    // Any change to what would be built makes the built image a lie, so the
    // one thing every option handler does is drop it.  The download buttons
    // go dark and Build takes the accent back through refresh().
    function invalidate() {
        state.result = null;
        state.options = null;
        refresh();
    }

    function refresh() {
        $('build').disabled = !state.factoryText;
        $('dlMac').disabled = !state.result;
        $('dlWin').disabled = !state.result;
        // The accent marks whatever is next: Build until an image exists,
        // then Download.  Changing an option clears state.result, so it
        // hands the emphasis back on its own.
        $('build').className = state.result ? '' : 'primary';
        $('dlMac').className = state.result ? 'primary' : '';
        $('dlWin').className = state.result ? 'primary' : '';
    bindDashes(document.body);
    }

    $('build').addEventListener('click', function () {
        msg($('buildMsg'), 'warn', 'Building…');
        $('build').disabled = true;
        // Yield first so the message paints before the synchronous build runs.
        setTimeout(function () {
            try {
                var t0 = Date.now();
                var chosen = options();
                var r = WEBBUILD.build(chosen, state.factoryText);
                // The options ride with the result: image.txt and the beacon
                // must describe the build they accompany, not whatever the
                // controls say by the time an async download assembles.
                r.options = chosen;
                state.result = r;
                state.options = chosen;
                msg($('buildMsg'), 'ok',
                    r.version + '\n' +
                    'Built in ' + (Date.now() - t0) + ' ms.\n\n' +
                    'SHA-256  ' + r.sha256 + '\n' +
                    r.patches + ' patches · ' + r.changed + ' bytes changed · ' +
                    r.added + ' newly programmed · ' + r.skipped.length + ' left factory\n\n' +
                    'Every difference from your factory image lies inside a declared patch, ' +
                    'and the image was read back and verified before this was shown.');
            } catch (e) {
                state.result = null;
                msg($('buildMsg'), 'bad', 'Build failed.\n\n' + e.message);
            }
            $('build').disabled = false;
            refresh();
        }, 30);
    });

    // A download is everything needed to flash: the image, the flasher stamped
    // with that image's checksum, the rescue script, and the vendor tools the
    // flasher runs.  The tools are fetched at download time rather than
    // carried in the page, which would put ten megabytes in front of every
    // visitor for a file most of them take once.
    var KIT = {
        dlMac: {
            zip: 'Rewired-macOS.zip',
            // Everything the flasher needs is sealed inside the app, so there
            // is nothing to collect alongside it.
            tools: [],
            bundle: 'kit/mac/Flasher.zip',
            firmware: 'firmware/218eV3_v369_Rewired_DFU.hex',
            // The app is signed, so it is the same app for everyone and cannot
            // be stamped with this build's checksum the way a loose script is.
            // The image names itself beside the firmware instead.
            scripts: function (r) { return [
                { name: 'firmware/image.txt', data: manifest(r) }
            ]; },
            note: function (r, partial) { return readme(r, [
                'Unzip it anywhere, keeping the app and the firmware folder',
                'together, and double-click',
                '',
                '    218e Rewired Flasher.app'
            ], partial, {
                firmware: 'firmware/218eV3_v369_Rewired_DFU.hex',
                knows: []
            }); }
        },
        dlWin: {
            zip: 'Rewired-Windows.zip',
            // In a folder, not loose beside the flasher: the images are the
            // one thing here anybody adds to or replaces, and a folder that
            // holds only images is a clearer place to put one than a folder
            // that also holds the flasher, the tools and the README.
            firmware: 'firmware/218eV3_v369_Rewired_DFU.hex',
            // One folder for everything the flasher runs, rather than a
            // windows/support for the executables and a tools for the scripts
            // - the split said something about where they came from, nothing
            // about what they are.
            tools: [
                ['kit/windows/support/dfu-programmer.exe', 'tools/dfu-programmer.exe', false],
                ['kit/windows/support/sendmidi.exe', 'tools/sendmidi.exe', false],
                ['kit/windows/support/zadig-2.8.exe', 'tools/zadig-2.8.exe', false],
                ['kit/tools/Scan-Images.ps1', 'tools/Scan-Images.ps1', false],
                ['kit/tools/Find-DfuDevice.ps1', 'tools/Find-DfuDevice.ps1', false],
                ['kit/tools/Show-Menu.ps1', 'tools/Show-Menu.ps1', false],
                ['kit/tools/validate_hex.py', 'tools/validate_hex.py', false]
            ],
            scripts: function (r) { return [
                { name: '218e_Rewired_Flasher.bat', data: r.scripts.flasherWin },
                // The manifest was macOS-only, so the Windows flasher had
                // nothing to read and listed the images with no idea what
                // either of them was.
                { name: 'firmware/image.txt', data: manifest(r) }
            ]; },
            note: function (r, partial) { return readme(r, [
                'Unzip it anywhere, keeping the folders together, and double-click',
                '',
                '    218e_Rewired_Flasher.bat',
                '',
                'Windows Defender may warn about it: More info, then Run anyway.',
                '',
                'The first flash on a machine pauses to bind the WinUSB driver.',
                'The flasher opens Zadig at the one moment that can be done, and',
                'tells you what to pick. If Zadig says Replace Driver rather than',
                'Install Driver, press it anyway.'
            ], partial, {
                firmware: 'firmware/218eV3_v369_Rewired_DFU.hex'
            }); }
        }
    };

    // What a download says about the images it carries: which build it is for,
    // what went into it, and that the other file is the stock image.  Read by
    // both flashers, so it is written the same way for both.
    function manifest(r) {
        return [
            '# Written by the builder page. The flasher reads this to know',
            '# which image this download was made for, and what went into it.',
            'EXPECTED_SHA256=' + r.sha256,
            'FIRMWARE_VERSION=Rewired ' + GEN.version +
                ' (' + r.sha256.slice(0, 8) + ')'
        ].concat(describe(r.options).map(function (line) {
            return 'OPTION=' + line;
        }), [
            'FACTORY_SHA256=' + GEN.factorySha256,
            'FACTORY_OPTION=Buchla stock firmware, exactly as you uploaded it.',
            'FACTORY_OPTION=Flashing it removes every Rewired change.'
        ]).join('\n') + '\n';
    }

    function describe(o) {
        var lines = [
            'Arpeggiator: ' + (o.latching_arp ? 'latching' : 'factory'),
            'Knobs 1-4: ' + [o.knob1, o.knob2, o.knob3, o.knob4].join(', '),
            'Sequencer: ' + (o.sequencer ? 'on' : 'off'),
            'Pressure: ' + (o.pressure_fix ? 'rewired' : 'factory') +
                (o.pressure_portamento ? ', portamento' : ''),
            'Scaling: ' + o.volts_per_octave + ' V/octave',
            // No brackets: echoed by both flashers, see the tunings line.
            'Pitch offset: ' + (o.pitch_offset === false
                ? 'none - 208c' : '3 semitones - 208, 208r, 208p'),
            'Oscillator correction: ' + (o.pitch_correction ? 'applied' : 'off'),
            'Preset voltages: ' + (o.quantize_presets
                ? 'quantized to the tuning when added to pitch' : 'not quantized'),
            'Portamento jack: ' + (o.portamento_in === 'transpose'
                ? 'transposes by degrees of the tuning' : 'adds portamento')
        ];
        if (o.alternate_tunings && o.alternate_tunings.length) {
            // A slot is { name, text }, so joining the array gave a row of
            // [object Object].  No brackets either: these lines are echoed
            // inside a batch FOR block, where an unescaped ) ends the block
            // instead of printing.
            lines.push('Alternate tunings: ' + o.alternate_tunings.length +
                       ' - ' + o.alternate_tunings.map(function (t) {
                           var named = (t && t.name) ? t.name : String(t);
                           // The name is echoed by both flashers - cmd FOR
                           // blocks re-parse & | < > ! ^ % and quotes, the
                           // shell has its own set - so anything outside a
                           // plain allowlist becomes a space.  Display only;
                           // the file itself is untouched.
                           return named.replace(/\.scl$/i, '')
                               .replace(/[^\w .,+'\/:()\-]/g, ' ')
                               .replace(/\s+/g, ' ').trim();
                       }).join(', '));
        } else {
            lines.push('Alternate tunings: off');
        }
        return lines;
    }

    function readme(r, howto, partial, opts) {
        var missing = partial ? [
            'THE FLASHING TOOLS ARE NOT IN THIS ZIP', '',
            'It was built from a page opened as a file rather than served, and a',
            'browser will not read neighbouring files in that case. Copy these',
            'files into a checkout of',
            '  https://github.com/triglav-modular/218e-Rewired',
            'and run the flasher from there, or take the download again from',
            '  https://triglav-modular.github.io/218e-Rewired/',
            'which packs everything.', ''
        ] : [];
        var where = (opts && opts.firmware) || '218eV3_v369_Rewired_DFU.hex';
        var knows = (opts && opts.knows) || [
            'The flasher carries that checksum, so it installs this build',
            'without asking which file to use.'];
        var rescue = (opts && opts.rescue) || [
            'The keyboard stays in DFU mode and a power cycle will not',
            'release it. Open the flasher again and choose "Get the keyboard',
            'out of DFU mode". It flashes nothing.'];
        var stock = where.replace(/[^/]+$/, '218eV3_v369_DFU.hex');
        return ['218e V3 Rewired ' + GEN.version, '']
            .concat(['This zip has everything needed to flash:', '',
                     '  ' + where + '   the firmware you built',
                     '  SHA-256  ' + r.sha256, '',
                     '  ' + stock + '   the stock image you uploaded',
                     '  SHA-256  ' + GEN.factorySha256, ''])
            .concat(knows, knows.length ? [''] : [], ['HOW TO USE IT', ''])
            .concat(missing, howto)
            .concat(['', 'IF A FLASH IS INTERRUPTED', ''])
            .concat(rescue, ['',
                     'READ THE WARNING THE FLASHER PRINTS BEFORE YOU AGREE TO IT.',
                     'This is experimental, unofficial firmware for the Buchla 218e',
                     'V3 only, and it can brick the instrument.', ''])
            .join('\n');
    }

    // How many people build this, and with what options.  Sent once per
    // download, and deliberately narrow: which options were chosen, which
    // platform, which version.  No identifier of any kind, nothing that
    // could carry one, and never the factory image or the calibration - the
    // build still happens entirely in this browser and nothing about it
    // leaves except these values.
    //
    // The URL is relative on purpose.  Only the deployment behind the worker
    // has anywhere to put this; a clone served from somewhere else, or the
    // page opened from a file, reports nowhere rather than reporting to us.
    function report(id, r) {
        try {
            if (!navigator.sendBeacon) return;
            // The build's own options, so the count describes the download
            // even if the controls have moved since.
            var o = (r && r.options) || state.options || {};
            var body = JSON.stringify({
                platform: id === 'dlMac' ? 'mac' : 'win',
                version: GEN.version,
                volts_per_octave: o.volts_per_octave,
                pitch_offset: o.pitch_offset !== false,
                latching_arp: !!o.latching_arp,
                // The remap checkbox this column counted is gone; it now
                // means "any knob doing something other than its preset
                // voltage", which is what the checkbox meant when it was on.
                remap_knobs: ['knob1', 'knob2', 'knob3', 'knob4'].some(function (k) {
                    return o[k] !== 'factory';
                }),
                pressure_fix: !!o.pressure_fix,
                pressure_portamento: !!o.pressure_portamento,
                sequencer: !!o.sequencer,
                clock_divide: !!o.clock_divide,
                // Which role each knob took - a name from the page's own
                // picker, 'factory' for the None row.
                knob1: o.knob1, knob2: o.knob2, knob3: o.knob3, knob4: o.knob4,
                // How many patterns the bank holds, not what they are: the
                // page's own CLIX bank and a bank someone typed both count
                // the same way, as a size.
                arp_patterns: (o.arp_patterns || []).length,
                // How many slots were filled, not which.  A slot can hold a
                // Scala file someone wrote themselves, and its name is
                // theirs, not ours to collect.
                alternate_tunings: (o.alternate_tunings || [])
                    .filter(function (t) { return t !== 'factory'; }).length,
                // Whether a calibration was supplied - never the numbers,
                // which are measurements of one person's instrument.
                pitch_correction: !!o.pitch_correction
            });
            // text/plain keeps this a simple request, so it needs no
            // preflight and no CORS reply to be delivered.
            navigator.sendBeacon('beacon', new Blob([body], { type: 'text/plain' }));
        } catch (e) {
            // Counting downloads must never be able to stop one.
        }
    }

    Object.keys(KIT).forEach(function (id) {
        $(id).addEventListener('click', function () {
            var r = state.result;
            if (!r) return;
            var p = KIT[id];
            var btn = $(id), label = btn.querySelector('span').textContent;
            btn.disabled = true;
            // A page opened from disk cannot fetch its neighbours: browsers
            // refuse cross-origin reads on file:, and every file: URL is its
            // own origin.  The tools simply cannot be collected, so the zip
            // carries the firmware and the scripts and says where the rest is.
            var offline = location.protocol === 'file:';
            // Only the real deployments carry kit/.  A clone served with
            // python -m http.server - the setup web/README.md itself
            // documents - has no kit and used to fail both downloads with a
            // packaging error; it degrades to the partial zip instead.  On
            // the canonical hosts a missing kit file is a deploy defect and
            // stays a loud failure.
            var canonical = ['triglavmodular.hu', 'www.triglavmodular.hu',
                             'triglav-modular.github.io']
                .indexOf(location.hostname) >= 0;
            btn.querySelector('span').textContent = offline ? 'Packing…' : 'Fetching tools…';
            var bundle = (offline || !p.bundle) ? Promise.resolve([]) :
                fetch(p.bundle).then(function (res) {
                    if (!res.ok) throw new Error(p.bundle + ' returned ' + res.status);
                    return res.arrayBuffer();
                }).then(function (b) {
                    if (!b || !b.byteLength) throw new Error(p.bundle + ' came back empty');
                    return ZIP.under('', ZIP.unpack(new Uint8Array(b)));
                }, function (e) {
                    throw new Error(p.bundle + ': ' + (e && e.message ? e.message : e));
                });
            Promise.all(offline ? [] : p.tools.map(function (t) {
                return fetch(t[0]).then(function (res) {
                    if (!res.ok) throw new Error(t[0] + ' returned ' + res.status);
                    return res.arrayBuffer();
                }).then(function (b) {
                    if (!b || !b.byteLength) throw new Error(t[0] + ' came back empty');
                    return { name: t[1], data: new Uint8Array(b), exec: t[2] };
                }, function (e) {
                    // Name the file: "Load failed" on its own says nothing about
                    // which of eleven requests gave up.
                    throw new Error(t[0] + ': ' + (e && e.message ? e.message : e));
                });
            })).then(function (tools) {
                btn.querySelector('span').textContent = 'Packing…';
                var built = p.firmware || '218eV3_v369_Rewired_DFU.hex';
                // The stock image travels with the build made from it, so
                // going back does not mean going and finding it again.  It is
                // the file that was just uploaded, handed back to the person
                // who uploaded it - it never left this browser.
                //
                // It keeps its own date - but not if that would put it level
                // with the build, or ahead of it.  ZIP stores seconds in two-
                // second steps, so a stock image downloaded moments ago lands
                // on the same tick as the build; a clock that has since been
                // set back puts it in front.  Either way the list of images,
                // which is ordered by date, would offer stock firmware first
                // and preselected.  Two ticks back is the nearest date that
                // cannot tie.
                var stock = built.replace(/[^/]+$/, '218eV3_v369_DFU.hex');
                var floor = new Date(Date.now() - 4000);
                var stockDate = state.factoryMtime || floor;
                if (stockDate > floor) stockDate = floor;
                var files = [{ name: built, data: r.hex },
                             { name: stock, data: state.factoryText,
                               mtime: stockDate }]
                    .concat(p.scripts(r), tools,
                            [{ name: 'README.txt', data: p.note(r, offline) },
                             { name: 'changelog.txt', data: GEN.changelog }]);
                if (offline) {
                    msg($('buildMsg'), 'warn',
                        'This page is open from a file rather than a web server, so the ' +
                        'browser will not let it read the flashing tools. Every file:// ' +
                        'address counts as a separate origin.\n\nThe download has the ' +
                        'firmware and the scripts. Take the tools from the repository, ' +
                        'or use the hosted page for a complete one:\n' +
                        'https://triglav-modular.github.io/218e-Rewired/');
                }
                return bundle.then(function (carried) {
                    return ZIP.build(files, carried);
                });
            }).then(function (blob) {
                var a = document.createElement('a');
                a.href = URL.createObjectURL(blob);
                a.download = p.zip;
                document.body.appendChild(a); a.click(); a.remove();
                setTimeout(function () { URL.revokeObjectURL(a.href); }, 4000);
                btn.querySelector('span').textContent = label; btn.disabled = false;
                report(id, r);
            }).catch(function (e) {
                if (!offline && !canonical) {
                    // The kit is not beside this copy of the page.  Pack what
                    // exists locally, exactly as the file: path does.
                    btn.querySelector('span').textContent = 'Packing…';
                    var built2 = p.firmware || '218eV3_v369_Rewired_DFU.hex';
                    var stock2 = built2.replace(/[^/]+$/, '218eV3_v369_DFU.hex');
                    var floor2 = new Date(Date.now() - 4000);
                    var stockDate2 = state.factoryMtime || floor2;
                    if (stockDate2 > floor2) stockDate2 = floor2;
                    var files2 = [{ name: built2, data: r.hex },
                                  { name: stock2, data: state.factoryText,
                                    mtime: stockDate2 }]
                        .concat(p.scripts(r),
                                [{ name: 'README.txt', data: p.note(r, true) },
                                 { name: 'changelog.txt', data: GEN.changelog }]);
                    msg($('buildMsg'), 'warn',
                        'This copy of the page has no flashing tools beside it (' +
                        e.message + ').\n\nThe download has the firmware and the ' +
                        'scripts. Take the tools from the repository, or use the ' +
                        'hosted page for a complete one:\n' +
                        'https://triglav-modular.github.io/218e-Rewired/');
                    return ZIP.build(files2, []).then(function (blob) {
                        var a = document.createElement('a');
                        a.href = URL.createObjectURL(blob);
                        a.download = p.zip;
                        document.body.appendChild(a); a.click(); a.remove();
                        setTimeout(function () { URL.revokeObjectURL(a.href); }, 4000);
                        btn.querySelector('span').textContent = label;
                        btn.disabled = false;
                    }).catch(function (e2) {
                        btn.querySelector('span').textContent = label;
                        btn.disabled = false;
                        msg($('buildMsg'), 'bad',
                            'Could not assemble the download: ' + e2.message);
                    });
                }
                btn.querySelector('span').textContent = label; btn.disabled = false;
                msg($('buildMsg'), 'bad',
                    'Could not assemble the download: ' + e.message +
                    '\n\nThe firmware itself built fine. This is the packaging step.');
            });
        });
    });


    $('useTunings').addEventListener('change', function () {
        $('tuningsBody').classList.toggle('hidden', !$('useTunings').checked);
        invalidate();
    });

    // Major.minor only, from the same GEN.version the flashers are stamped
    // with.  The patch number and the build's own fingerprint belong on the
    // build result, not in the masthead.
    $('ver').textContent = GEN.version.split('.').slice(0, 2).join('.');

    // Each preset knob picks its own role, the same control the volts-per-
    // octave choice uses; None hands that knob back to its preset voltage.
    // The pattern editor belongs to knob 2 and only appears when that knob
    // is set to patterns.
    ['knob1', 'knob2', 'knob3', 'knob4'].forEach(function (id) {
        Array.prototype.forEach.call($(id).children, function (b) {
            b.addEventListener('click', function () {
                knobRole[id] = b.dataset.v;
                Array.prototype.forEach.call($(id).children, function (o) {
                    o.setAttribute('aria-pressed', String(o === b));
                });
                if (id === 'knob2' && b.dataset.v === 'patterns'
                        && !state.patterns.length) {
                    state.patterns = [{ text: 'x...x...x...x...', length: 16 }];
                }
                renderPatterns();
                invalidate();
            });
        });
    });

    // The changelog, from the same generated data the package's changelog.txt
    // ships.  That file is plain text - "2.0 (2026-08-29)" opening a release,
    // "- " opening an entry - and this turns it into headings and real list
    // items, so the panel gets hanging bullets rather than a run of dashes
    // held together by white-space: pre-line.
    (function () {
        var body = $('chlogBody'), btn = $('chlogBtn'), list = null;
        GEN.changelog.split('\n').forEach(function (line) {
            if (!line.trim()) return;
            var head = /^(\d+\.\d+(?:\.\d+)?)\s*(?:\((.+)\))?\s*$/.exec(line);
            if (head) {
                var rel = document.createElement('div');
                rel.className = 'chlog-rel';
                var h = document.createElement('h4');
                h.className = 'chlog-ver';
                h.appendChild(document.createTextNode(head[1]));
                if (head[2]) {
                    var when = document.createElement('span');
                    when.className = 'chlog-date';
                    when.textContent = head[2];
                    h.appendChild(when);
                }
                list = document.createElement('ul');
                list.className = 'chlog-list';
                rel.appendChild(h);
                rel.appendChild(list);
                body.appendChild(rel);
                return;
            }
            // A line before any version heading would have nowhere to go, so
            // it opens an unlabelled list rather than being dropped.
            if (!list) {
                list = document.createElement('ul');
                list.className = 'chlog-list';
                body.appendChild(list);
            }
            var li = document.createElement('li');
            li.textContent = line.replace(/^[-\u2013\u2014]\s*/, '');
            list.appendChild(li);
        });
        // Centre the panel on the pill, then pull it back inside the paragraph
        // if that hung it over an edge.  The paragraph is the content column,
        // so a panel within it is on-screen at every width.  Measured rather
        // than assumed: the pill sits wherever the sentence ends, which moves
        // with the wrap.  Setting right as well as left would stretch it.
        function centreChangelog() {
            if (body.classList.contains('hidden')) return;
            var sub = body.offsetParent;
            if (!sub) return;
            var room = sub.clientWidth - body.offsetWidth;
            var want = btn.offsetLeft + btn.offsetWidth / 2 - body.offsetWidth / 2;
            body.style.setProperty('--chlog-left',
                Math.round(Math.max(0, Math.min(want, room))) + 'px');
            body.style.right = 'auto';
        }
        btn.addEventListener('click', function (e) {
            e.stopPropagation();
            var open = body.classList.toggle('hidden');
            btn.setAttribute('aria-expanded', String(!open));
            centreChangelog();
        });
        window.addEventListener('resize', centreChangelog);
        document.addEventListener('click', function () {
            body.classList.add('hidden');
            btn.setAttribute('aria-expanded', 'false');
        });
        body.addEventListener('click', function (e) { e.stopPropagation(); });
    })();

    renderPatterns();
    renderSlots(); buildTable(); drawPlot(); syncPortamento(); syncCalBody(); refresh();
    bindDashes(document.body);
})();
