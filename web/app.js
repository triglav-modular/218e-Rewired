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
        var movedMarks = {};
        for (var n = 0; n < TABLE_ENTRIES; n++) {
            var to = n - from + PLAYABLE_LOW;
            if (to >= 0 && to < TABLE_ENTRIES) {
                moved[to] = measured[n];
                if (interpolated[n]) movedMarks[to] = true;
            }
        }
        measured = moved;
        interpolated = movedMarks;
        // Changing the offset renumbers every semitone, so a loaded table no
        // longer describes this instrument's layout - its row 3 is not this
        // build's row 3.  Dropped rather than shifted: a table quietly moved
        // under the user is the kind of baseline that folds into a plausible
        // wrong answer.
        if (haveBaseline()) {
            clearBaseline();
            msg($('calMsg'), 'bad', 'The loaded table was dropped: changing the pitch ' +
                'offset renumbers the semitones, so it no longer describes this ' +
                'build. Load it again if it was measured at this setting.');
        }
        syncBaseline();
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
            saveFactory();
            refresh();
        };
        reader.onerror = function () { fail('Could not read that file.'); };
        reader.readAsText(file);
        function fail(t) {
            state.factoryText = null;
            state.result = null;
            $('drop').className = 'drop err';
            msg($('fileMsg'), 'bad', t);
            saveFactory();
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
    // real choice rather than upload order: the two edit keys each toggle
    // against slot 2, and the selection is remembered between power cycles.
    var SLOTS = [
        { name: 'Slot 0', note: 'rem-en LED lit · edit key 28 toggles it against slot 2' },
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
        // A bank of one builds and plays; it is the knob that has nothing to
        // do, so this is advice and not a refusal.
        msg($('patMsg'), 'warn', state.patterns.length === 1
            ? 'With only one pattern, knob 2 has nothing to switch between. '
              + 'You should probably add more patterns.'
            : '');
    }

    // A CLIX mask is 32 bits, least significant step first.
    function clixPattern(mask) {
        var t = '';
        for (var i = 0; i < 32; i++) t += (mask >>> i) & 1 ? 'x' : '.';
        return { text: t, length: 32 };
    }

    // What switching knob 2 to patterns starts with.  Four unlike fills rather
    // than one, because a bank of one gives the knob nothing to sweep through
    // and the first thing anyone does is turn it.  CLIX numbering is 1-based.
    var DEFAULT_CLIX = [2, 8, 12, 22];

    function defaultPatterns() {
        var picked = DEFAULT_CLIX.filter(function (n) {
            return GEN.clix[n - 1] !== undefined;
        });
        return picked.length
            ? picked.map(function (n) { return clixPattern(GEN.clix[n - 1]); })
            : [{ text: 'x...x...x...x...', length: 16 }];
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
        state.patterns = GEN.clix.map(clixPattern);
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
    for (var i = 0; i < TABLE_ENTRIES; i++) measured.push(0);
    // Which of those the sweep carried across from a neighbour rather than
    // heard.  They are real numbers in `measured` and build a real table, but
    // nothing measured them, and the saved file is the only place that can
    // still say so once the session is gone.
    var interpolated = {};

    // What is already on the instrument, as Offset_Cents per semitone, and
    // which of those rows were extrapolated rather than measured.
    //
    // Readings are taken against whatever the keyboard is applying, so a
    // second round of measuring corrects the REMAINDER and has to accumulate
    // onto the table that produced it.  With nothing loaded this is flat,
    // where the octave width is exactly 1.000 and the fold reduces to minus
    // the reading - so an ordinary first calibration builds exactly what it
    // always did.
    // baselineHistory is what the loaded table recorded about the round
    // that wrote it - per key, the reading it was pushed from and the offset
    // that reading was taken against - which gives the next fold the key's
    // own slope.  Null for a table written before it recorded anything.
    var baseline = {}, baselineSources = {}, baselineName = '', baselineHistory = null;
    function clearBaseline() {
        baseline = {}; baselineSources = {}; baselineName = ''; baselineHistory = null;
        for (var n = 0; n < TABLE_ENTRIES; n++) baseline[n] = 0;
    }
    clearBaseline();
    function haveBaseline() { return !!baselineName; }

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
                    delete interpolated[n];
                    input.className = measured[n] !== 0 ? 'set' : '';
                    drawPlot(); validateCal();
                    saveSoon();
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

    // Whether the image being built actually carries a table.  Three places
    // need the same answer - the build options, the file list in the download
    // and the README that describes it - and they must not drift apart: a zip
    // that ships a table the image does not apply is worse than one that ships
    // no table at all, because the next round would measure on top of it.
    function calibrationInBuild() {
        return $('useCal').checked && !calibrationBlank();
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

    // The offsets the build reads.  The arithmetic is in buildlib, where it can
    // be tested against the CLI's; this only supplies the page's state.
    function rows() {
        return BUILDLIB.calibrationRows(baseline, baselineSources, measured,
                                        PLAYABLE_LOW, PLAYABLE_HIGH, TABLE_ENTRIES,
                                        haveBaseline(), baselineHistory)
            .map(function (v, i) { return { semitone: i, cents: v }; });
    }

    function syncBaseline() {
        var el = $('calBase');
        if (!el) return;
        el.textContent = haveBaseline()
            ? 'Measuring on top of ' + baselineName +
              ' \u2014 new readings accumulate onto it.'
            : 'No table loaded: readings are taken as a first calibration of an ' +
              'uncorrected instrument.';
        el.classList.toggle('set', haveBaseline());
        $('calBaseClear').disabled = !haveBaseline();
    }

    function syncCalBody() {
        $('calBody').classList.toggle('hidden', !$('useCal').checked);
    }
    $('useCal').addEventListener('change', function () {
        syncCalBody(); validateCal(); invalidate();
    });
    $('calZero').addEventListener('click', function () {
        measured = measured.map(function () { return 0; });
        interpolated = {};
        buildTable(); drawPlot(); validateCal(); syncBaseline();
        msg($('calMsg'), '', haveBaseline()
            ? 'Readings cleared. ' + baselineName + ' is still loaded as the table on '
              + 'the instrument.' : '');
        // The table is part of the image: without this the image built
        // from the old readings stayed downloadable after they were cleared.
        invalidate();
    });
    // The table, not the session.
    //
    // This used to save the readings - a record of an afternoon, which says
    // nothing about an instrument once it has been flashed.  What is worth
    // keeping beside the image is the TABLE it was built with, because that is
    // what the keyboard is applying and therefore what the next round of
    // measuring has to accumulate onto.  Same columns as the repository's own
    // calibration file, so it loads back here as a baseline and tools/build.py
    // reads it directly.
    function calibrationCsv() {
        var full = rows();
        var folded = {};
        full.forEach(function (r) { folded[r.semitone] = r.cents; });
        var record = BUILDLIB.historyToSave(baseline, folded, measured, interpolated,
                                            baselineHistory);
        function cell(map, n) {
            return map.hasOwnProperty(n) ? map[n].toFixed(6) : '';
        }
        var out = [
            '# 218e pitch calibration, saved from the Rewired firmware builder.',
            '#',
            '# Offset_Cents is the correction the firmware applies: how far each',
            '# semitone is pushed from an ideal ramp.  Positive raises the pitch.',
            '# This is a TABLE, not a set of readings - keep it beside the image you',
            '# flash, and load it back here before measuring again so the next round',
            '# accumulates onto it instead of replacing it.',
            '#',
            '# Source "interpolated" means the sweep never heard that note and',
            '# carried it across from its neighbours: it is a guess, worth checking',
            '# by hand before it is measured on top of.',
            '#',
            '# Semitone counts up from the 208\'s 0 V pitch; the lowest C on the',
            '# keyboard is semitone ' + PLAYABLE_LOW + '.',
            '#',
            '# Read_Cents and Read_Against are what each key read in the round that',
            '# wrote this table, and the offset it was read against.  The next round',
            '# reads them back.  Leave them as they are.',
            'Semitone;Note;Key;Offset_Cents;Source;Read_Cents;Read_Against'
        ];
        for (var n = 0; n < TABLE_ENTRIES; n++) {
            var src = interpolated[n] ? 'interpolated'
                    : measured[n] ? 'measured'
                    : (baselineSources[n] || (n < PLAYABLE_LOW ? 'octave'
                       : n > PLAYABLE_HIGH ? 'extrapolated' : 'measured'));
            out.push([n, noteNames()[n % 12], keyLabel(n),
                      full[n].cents.toFixed(6), src,
                      cell(record.read, n), cell(record.against, n)].join(';'));
        }
        return out.join('\n') + '\n';
    }

    var CAL_CSV_NAME = '218e-pitch-calibration.csv';

    $('calSave').addEventListener('click', function () {
        download(calibrationCsv(), CAL_CSV_NAME, 'text/csv');
    });

    $('calBaseClear').addEventListener('click', function () {
        clearBaseline();
        syncBaseline(); drawPlot(); validateCal(); invalidate();
        msg($('calMsg'), '', 'Forgotten. Readings now build a first calibration of an ' +
            'uncorrected instrument.');
    });

    $('calPick').addEventListener('click', function () { $('calFile').click(); });
    $('calFile').addEventListener('change', function (e) {
        var f = e.target.files[0];
        e.target.value = '';   // so the same file can be picked again
        if (!f) return;
        var r = new FileReader();
        r.onload = function () {
            // Two kinds of file, told apart by their column.  A calibration
            // carries Offset_Cents and describes a TABLE - what an instrument
            // is applying - so it becomes the baseline to measure on top of.
            // A measurement carries Measured_Cents and describes a SESSION -
            // how far each note played - so it fills the boxes.
            //
            // The loader used to turn a calibration into pseudo-readings by
            // negating it.  That built the same image back, but threw the
            // table away the moment anything new was entered: the next round
            // of measuring corrected the remainder as though the first round
            // had never happened.
            var isCorrection = /Offset_Cents/i.test(r.result);
            var clearedReadings = false;
            // The parsing lives in buildlib so a test can reach it; the
            // record columns it reads are what gives the fold a key's own
            // slope on a second round.
            var parsed = BUILDLIB.parseCalibration(r.result, TABLE_ENTRIES);
            var rowsIn = parsed.rows, sources = parsed.sources, found = parsed.found;

            if (!found) {
                msg($('calMsg'), 'bad', 'No usable rows in ' + f.name + ': expected ' +
                    'Semitone;Note;Key;Offset_Cents;Source, or the same with a ' +
                    'Measured_Cents column.');
                return;
            }

            if (isCorrection) {
                // A table is a baseline only if it is a whole one.  A partial
                // file would leave the rest of the instrument's correction at
                // zero, which is not "unknown" - it is "no correction", and
                // the fold would quietly undo what is flashed there.
                var missing = [];
                for (var n = 0; n < TABLE_ENTRIES; n++) if (!(n in rowsIn)) missing.push(n);
                if (missing.length) {
                    msg($('calMsg'), 'bad', f.name + ' has ' + found + ' of the ' +
                        TABLE_ENTRIES + ' rows a table needs - missing semitone ' +
                        missing[0] + (missing.length > 1
                            ? ' and ' + (missing.length - 1) + ' others' : '') +
                        '. A calibration is loaded whole or not at all.');
                    return;
                }
                baseline = rowsIn;
                baselineSources = sources;
                baselineName = f.name;
                baselineHistory = parsed.history;
                var had = measured.some(function (v) { return v !== 0; });
                measured = measured.map(function () { return 0; });
                interpolated = {};
                clearedReadings = had;
            } else {
                for (var k in rowsIn) {
                    if (k >= PLAYABLE_LOW && k <= PLAYABLE_HIGH) {
                        measured[k] = rowsIn[k];
                        delete interpolated[k];
                    }
                }
            }
            $('useCal').checked = true;
            syncCalBody(); syncBaseline();
            buildTable(); drawPlot(); validateCal(); invalidate();
            msg($('calMsg'), 'ok', isCorrection
                ? 'Loaded ' + f.name + ' as the table already on the instrument. ' +
                  'Anything measured now accumulates onto it.' +
                  (clearedReadings ? ' The readings that were entered have been ' +
                   'cleared: they were taken against whatever was flashed at the ' +
                   'time, which this file now says. Measure again.' : '')
                : 'Loaded ' + found + ' readings from ' + f.name + '.');
        };
        r.readAsText(f);
    });

    // --- measuring it automatically ---------------------------------------
    // The same numbers the boxes above hold, arrived at by playing the
    // instrument instead of by hand.  calibrate.js drives the keyboard over
    // MIDI and measures what it hears; everything downstream - the plot, the
    // monotonic check, the CSV, the image - is unchanged.
    var sweep = null, listed = { midi: false, audio: false };
    // Whether the message on screen was put there by the audio path, so it can
    // be taken down when that path succeeds without silencing a MIDI complaint
    // that is still true.
    var audioComplaint = false;
    function audioMsg(kind, text) {
        audioComplaint = !!text;
        msg($('autoMsg'), kind, text);
    }

    function autoNote(text, bar) {
        var el = $('calProgress');
        el.textContent = text || '';
        if (bar !== undefined && bar !== null) {
            var b = document.createElement('span');
            b.className = 'bar';
            var i = document.createElement('i');
            i.style.width = Math.round(bar * 100) + '%';
            b.appendChild(i);
            el.appendChild(b);
        }
    }

    // The measurement log: every note sent, and the pitch that came back.
    var logRows = [];

    function logLine(r) {
        function f(v, n) { return v === null || v === undefined ? '--' : v.toFixed(n); }
        var head = (r.t / 1000).toFixed(1).padStart(6) + 's  ' +
                   r.what.padEnd(6) + ' n' + String(r.note).padStart(3) + ' ' +
                   r.name.padEnd(4) + ' e' + String(r.entry === null ? '--' : r.entry).padStart(2) +
                   ' ch' + String(r.channel + 1).padStart(3) + '  ';
        if (r.hz === null) {
            return head + '<i>no pitch (' + r.why + ')  rms ' + f(r.rms, 4) + '</i>';
        }
        var want = r.expectHz ? '  want ' + f(r.expectHz, 2) : '';
        var off = r.expectHz ? '  ' + (CALIBRATE.cents(r.hz, r.expectHz) >= 0 ? '+' : '') +
                  CALIBRATE.cents(r.hz, r.expectHz).toFixed(1) + 'c' : '';
        return head + '<b>' + f(r.hz, 3) + ' Hz</b>' + want + off +
               '  cl ' + f(r.clarity, 2) +
               '  half ' + (r.halfDrift === null ? '--' :
                            (r.halfDrift >= 0 ? '+' : '') + f(r.halfDrift, 1) + 'c');
    }

    // A plain line in the log, for things that are not a note reading.
    function pushNote(text) {
        var el = $('calLog');
        el.classList.add('on');
        el.insertAdjacentText('beforeend', text + '\n');
        el.scrollTop = el.scrollHeight;
        $('calLogRow').classList.add('on');
        $('calLogClear').disabled = false;
    }

    function pushLog(r) {
        logRows.push(r);
        var el = $('calLog');
        el.classList.add('on');
        el.insertAdjacentHTML('beforeend', logLine(r) + '\n');
        el.scrollTop = el.scrollHeight;
        $('calLogRow').classList.add('on');
        $('calLogSave').disabled = false;
        $('calLogClear').disabled = false;
    }

    $('calLogClear').addEventListener('click', function () {
        logRows = [];
        $('calLog').textContent = '';
        $('calLog').classList.remove('on');
        $('calLogRow').classList.remove('on');
        $('calLogSave').disabled = true;
        $('calLogClear').disabled = true;
    });

    $('calLogSave').addEventListener('click', function () {
        var cols = ['ms', 'what', 'midi_note', 'name', 'table_entry', 'midi_channel',
                    'expected_hz', 'detected_hz', 'first_half_hz', 'second_half_hz',
                    'half_drift_cents', 'clarity', 'rms', 'why'];
        var out = [
            '# 218e calibration sweep log.',
            '# One row per note sent: what came back for it, as measured.',
            '# what: probe = finding the MIDI channel, anchor = the bottom note',
            '#       re-measured to cancel drift, sweep = a note of the table.',
            '# A sweep row whose detected_hz is below the sweep row before it is',
            '# a note that did not take - the firmware cannot play a higher note lower.',
            cols.join(',')
        ];
        logRows.forEach(function (r) {
            out.push([r.t, r.what, r.note, r.name, r.entry === null ? '' : r.entry,
                      r.channel + 1,
                      r.expectHz === null ? '' : r.expectHz.toFixed(4),
                      r.hz === null ? '' : r.hz.toFixed(4),
                      r.firstHalfHz === null ? '' : r.firstHalfHz.toFixed(4),
                      r.secondHalfHz === null ? '' : r.secondHalfHz.toFixed(4),
                      r.halfDrift === null ? '' : r.halfDrift.toFixed(3),
                      r.clarity === null ? '' : r.clarity.toFixed(4),
                      r.rms === null ? '' : r.rms.toFixed(6),
                      r.why].join(','));
        });
        download(out.join('\n') + '\n', '218e-sweep-log.csv', 'text/csv');
    });

    function fillSelect(sel, items, empty) {
        // The lists are rebuilt whenever a device appears or a permission
        // changes, and a rebuild that forgets the choice would quietly move
        // the measurement to a different input between picking and starting.
        var had = sel.value;
        sel.innerHTML = '';
        if (!items.length) {
            sel.appendChild(new Option(empty, ''));
            return;
        }
        items.forEach(function (it) { sel.appendChild(new Option(it.label, it.value)); });
        if (had && items.some(function (it) { return it.value === had; })) sel.value = had;
    }

    function listMidi() {
        if (listed.midi) return Promise.resolve();
        return CALIBRATE.midiOutputs().then(function (ports) {
            listed.midi = true;
            // The instrument names its own port, so a kit with several things
            // plugged in still opens on the right one.
            var items = ports.map(function (p) {
                return { value: p.id, label: p.name || p.id, port: p };
            });
            fillSelect($('calMidi'), items, 'No MIDI outputs found');
            var mine = items.filter(function (i) { return /218e/i.test(i.label); })[0];
            if (mine) $('calMidi').value = mine.value;
            window.__calPorts = ports;
        }, function (err) {
            // Not latched: a browser that has no Web MIDI will say so again,
            // and one that was merely not ready gets another chance without
            // the page having to be reloaded.
            fillSelect($('calMidi'), [], 'Web MIDI unavailable');
            msg($('autoMsg'), 'bad', err.message);
        });
    }

    // Enumerate first, and only ask for a stream if that comes back without
    // labels.
    //
    // The obvious order - take a stream, then enumerate - is wrong in a way
    // that looks exactly like a refused permission.  getUserMedia({audio:true})
    // opens the DEFAULT input, and on macOS that fails with NotReadableError
    // whenever another application is holding the interface.  Which is the
    // normal state of affairs here: the sequencer driving the keyboard is
    // using the same box.  Permission was granted, the device was busy, and
    // the select said "No audio input permission".
    //
    // Once permission has been given to this origin, enumerateDevices() fills
    // the labels in on its own and no stream is needed at all.  The stream is
    // only the way to make the browser ask the first time.
    function listAudio() {
        if (listed.audio) return Promise.resolve();
        if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
            fillSelect($('calAudio'), [], 'No audio input in this browser');
            audioMsg('bad', 'This browser will not give a page an audio ' +
                'input. Over plain http only localhost is allowed to ask; the ' +
                'published page is https and can.');
            return Promise.resolve();
        }
        function show(devs) {
            var named = devs.filter(function (d) { return d.label; });
            fillSelect($('calAudio'), devs.map(function (d, i) {
                return { value: d.deviceId, label: d.label || ('Input ' + (i + 1)) };
            }), 'No audio inputs found');
            // Only settled once the labels are real: an unlabelled list means
            // the browser has not granted this origin yet, and asking again
            // later is exactly what should happen.
            // An unnamed list is the ordinary state before the origin has been
            // granted audio, and it lasts until the request below resolves -
            // which is moments.  It used to explain itself in a red box, which
            // meant the common path opened with an error nobody needed to act
            // on.  The Rescan button is there for the case where it persists.
            if (named.length) {
                listed.audio = true;
                if (audioComplaint) audioMsg('', '');
            }
            return named.length;
        }
        return CALIBRATE.audioInputs().then(function (devs) {
            if (show(devs)) return null;
            return navigator.mediaDevices.getUserMedia({ audio: true })
                .then(function (st) {
                    // Enumerate while the stream is still open, and only then
                    // let go of it.  Labels are visible while a stream is live
                    // or while a persistent grant stands - stopping first threw
                    // away the very thing that reveals them, so a granted
                    // permission still came back as one nameless default.  With
                    // "Allow this time" the grant is gone the moment the track
                    // stops, which is the case that made it look like the
                    // permission had not been given at all.
                    return CALIBRATE.audioInputs().then(function (devs) {
                        var n = show(devs);
                        st.getTracks().forEach(function (t) { t.stop(); });
                        return n;
                    }, function (err) {
                        st.getTracks().forEach(function (t) { t.stop(); });
                        throw err;
                    });
                }, function (err) {
                    // Whatever went wrong, the devices themselves enumerated,
                    // so the list stays usable - unlabelled, but pickable.
                    audioMsg('bad', CALIBRATE.audioTrouble(err));
                });
        }, function (err) {
            fillSelect($('calAudio'), [], 'Could not list audio inputs');
            audioMsg('bad', CALIBRATE.audioTrouble(err));
        });
    }

    // The channels a device has can only be learned by opening it, so this
    // runs when one is picked rather than up front, and remembers the answer.
    var chanFor = {};   // reset by Rescan
    function listChannels() {
        var id = $('calAudio').value || '';
        if (chanFor[id]) return Promise.resolve();
        return CALIBRATE.channelCount(id || null).then(function (res) {
            var n = res.count;
            chanFor[id] = n;
            // Into the log, where the diagnostics live, rather than into the
            // panel: this matters on the day a desk offers fewer channels than
            // it has, and never again.
            pushNote('channels on ' + ($('calAudio').selectedOptions[0]
                     ? $('calAudio').selectedOptions[0].text : 'default') +
                     ': ' + n + '  [' + res.report.join('; ') + ']');
            var items = [];
            for (var i = 0; i < n; i++) items.push({ value: String(i), label: String(i + 1) });
            var keep = $('calChan').value;
            fillSelect($('calChan'), items, '1');
            if (keep && Number(keep) < n) $('calChan').value = keep;
            $('calChan').disabled = n < 2;
        }, function (err) {
            // The count is unknown, not zero.  Leaving the list alone keeps
            // whatever was already pickable rather than collapsing a twelve
            // channel desk to one because the device was busy for a moment.
            msg($('autoMsg'), 'bad', CALIBRATE.audioTrouble(err));
        });
    }

    $('calMidi').addEventListener('focus', listMidi);
    $('calAudio').addEventListener('focus', listAudio);
    $('calAudio').addEventListener('change', listChannels);
    $('calRescan').addEventListener('click', function () {
        listed.audio = false;
        listed.midi = false;
        chanFor = {};
        msg($('autoMsg'), '', '');
        $('calRescan').disabled = true;
        Promise.resolve().then(listMidi).then(listAudio).then(listChannels)
            .then(function () { $('calRescan').disabled = false; },
                  function () { $('calRescan').disabled = false; });
    });
    // --- step 5: the settings, to the keyboard over MIDI -----------------
    // The record a build serialized (WEBBUILD.build's `settings`, the same
    // bytes tools/build.py writes to build/settings.bin) goes to the
    // instrument through SETTINGSMIDI.install, which refuses with a named
    // reason; each reason has its own line here.  The instrument is one
    // device with an input and an output of the same name, so the select
    // lists outputs and the input is found by that name.
    var kbd = { outputs: [], inputs: [], listed: false };
    function listKeyboard() {
        if (!$('kbdPort')) return Promise.resolve();
        return Promise.all([CALIBRATE.midiOutputs(), CALIBRATE.midiInputs()]).then(function (r) {
            kbd.outputs = r[0]; kbd.inputs = r[1]; kbd.listed = true;
            var items = kbd.outputs.map(function (p) {
                return { value: p.id, label: p.name || p.id };
            });
            fillSelect($('kbdPort'), items, 'No MIDI outputs found');
            if (!$('kbdPort').value) {
                var mine = items.filter(function (i) { return /218e/i.test(i.label); })[0];
                if (mine) $('kbdPort').value = mine.value;
            }
            refresh();
        }, function (err) {
            fillSelect($('kbdPort'), [], 'Web MIDI unavailable');
            msg($('kbdMsg'), 'bad', err.message);
            refresh();
        });
    }
    function keyboardPorts() {
        var out = kbd.outputs.filter(function (p) { return p.id === $('kbdPort').value; })[0];
        if (!out) return null;
        var inp = kbd.inputs.filter(function (p) { return p.name === out.name; })[0] || kbd.inputs[0];
        return inp ? { output: out, input: inp } : null;
    }
    function recordBytes(hex) {
        var out = [];
        for (var i = 0; i < hex.length; i += 2) out.push(parseInt(hex.substr(i, 2), 16));
        return out;
    }
    var KBD_REASONS = {
        'no reply': 'No reply from the keyboard: check it is on and plugged in, and that this is the right port.',
        'wrong layout': 'This keyboard runs a different build: flash the firmware from step 3 first.',
        'wrong image': 'This keyboard runs a different build: flash the firmware from step 3 first.',
        'mismatch': 'The keyboard did not read everything back the same: try again.',
        'not written': 'The keyboard could not save: try again, and if it keeps failing, flash the firmware again.'
    };
    if ($('kbdSend')) {
        // The MIDI permission is asked for when the step comes into view,
        // not on load: someone building an image to download has no use for
        // the prompt.
        var kbdSection = $('kbdPort').closest('section');
        if (window.IntersectionObserver && kbdSection) {
            new IntersectionObserver(function (entries, observer) {
                if (entries.some(function (e) { return e.isIntersecting; })) {
                    observer.disconnect();
                    listKeyboard();
                }
            }).observe(kbdSection);
        }
        $('kbdPort').addEventListener('focus', function () { if (!kbd.listed) listKeyboard(); });
        $('kbdPort').addEventListener('change', refresh);
        $('kbdSend').addEventListener('click', function () {
            var ports = keyboardPorts();
            if (!ports || !state.result) return;
            $('kbdSend').disabled = true;
            msg($('kbdMsg'), 'warn', 'Sending…');
            SETTINGSMIDI.install(ports.output, ports.input, recordBytes(state.result.settings), {})
                .then(function () {
                    msg($('kbdMsg'), 'ok', 'Sent and saved.');
                }, function (err) {
                    msg($('kbdMsg'), 'bad', KBD_REASONS[err && err.reason] || String(err && err.message || err));
                })
                .then(refresh);
        });
    }

    // A port appearing or going away invalidates a list that is only built
    // once.  The selection is put back if it survived, so unplugging something
    // else does not quietly move the choice out from under the next run.
    if (CALIBRATE.onMidiChange) {
        CALIBRATE.onMidiChange(function () {
            var was = $('calMidi').value;
            listed.midi = false;
            listMidi().then(function () {
                var opts = $('calMidi').options, i;
                for (i = 0; i < opts.length; i++) {
                    if (opts[i].value === was) { $('calMidi').value = was; return; }
                }
            });
            if (kbd.listed) listKeyboard();
        });
    }

    $('calChan').addEventListener('focus', listChannels);
    if (navigator.mediaDevices && navigator.mediaDevices.addEventListener) {
        navigator.mediaDevices.addEventListener('devicechange', function () {
            listed.audio = false;
            if (!$('calRun').disabled) listAudio();
        });
    }

    // A note that could not be heard is not a note that played in tune.  Rather
    // than leave a zero - which reads as "correct" and builds a table that says
    // so - carry the reading across the gap from its measured neighbours.
    function bridgeGaps(got) {
        var known = [];
        for (var n = PLAYABLE_LOW; n <= PLAYABLE_HIGH; n++) {
            if (got[n] !== null && got[n] !== undefined) known.push(n);
        }
        if (!known.length) return 0;
        var filled = 0;
        for (n = PLAYABLE_LOW; n <= PLAYABLE_HIGH; n++) {
            if (got[n] !== null && got[n] !== undefined) {
                measured[n] = got[n]; delete interpolated[n]; continue;
            }
            var below = null, above = null;
            known.forEach(function (k) {
                if (k < n) below = k;
                if (above === null && k > n) above = k;
            });
            if (below === null) measured[n] = got[above];
            else if (above === null) measured[n] = got[below];
            else measured[n] = got[below] + (got[above] - got[below]) *
                               (n - below) / (above - below);
            interpolated[n] = true;
            filled++;
        }
        return filled;
    }

    function setRunning(on) {
        $('calRun').disabled = on;
        $('calStop').disabled = !on;
        $('calMidi').disabled = on;
        $('calMidiChan').disabled = on;
        $('calAudio').disabled = on;
        $('calChan').disabled = on || (chanFor[$('calAudio').value || ''] || 1) < 2;
    }

    $('calStop').addEventListener('click', function () {
        if (sweep) sweep.stop();
        autoNote('Stopping after this note\u2026');
    });

    $('calRun').addEventListener('click', function () {
        msg($('calMsg'), '', '');
        Promise.resolve().then(listMidi).then(listAudio).then(listChannels).then(function () {
            var ports = window.__calPorts || [];
            var chosen = ports.filter(function (p) { return p.id === $('calMidi').value; })[0];
            if (!chosen) {
                msg($('calMsg'), 'bad', 'Choose the MIDI output the 218e is on.');
                return;
            }
            // Being in the list is not being plugged in.
            if (CALIBRATE.portGone && CALIBRATE.portGone(chosen)) {
                msg($('calMsg'), 'bad', (chosen.name || 'That MIDI output') +
                    ' is not connected any more. Plug it back in, or press ' +
                    'Rescan inputs to pick another.');
                return;
            }
            var got = {};
            setRunning(true);
            autoNote('Listening for the bottom C\u2026', 0);
            sweep = new CALIBRATE.Sweep({
                output: chosen,
                // Empty means Auto: the sweep finds the channel by playing on
                // each in turn and watching for the pitch to move.
                channel: $('calMidiChan').value === '' ? null
                                                       : Number($('calMidiChan').value),
                deviceId: $('calAudio').value || null,
                audioChannel: parseInt($('calChan').value, 10) || 0,
                // What listChannels() actually got the device to open.  The
                // sweep needs it because "ideal" can negotiate two channels on
                // a desk that hands over twelve when asked for twelve outright,
                // and that is the number this dropdown was filled from.
                audioChannels: chanFor[$('calAudio').value || ''] || null,
                // Entries, not semitones.  The sweep counts in firmware table
                // entries - the bottom key is entry 3 whatever the pitch
                // offset is - while these boxes count in calibration
                // semitones, where the bottom key is PLAYABLE_LOW.  The two
                // coincide with the offset on and are three apart without it,
                // so handing the sweep PLAYABLE_LOW/PLAYABLE_HIGH raw played
                // 62 of the 65 keys on a 208c and filed every reading three
                // rows high.
                low: CALIBRATE.entryForSemitone(PLAYABLE_LOW, PLAYABLE_LOW),
                high: CALIBRATE.entryForSemitone(PLAYABLE_HIGH, PLAYABLE_LOW),
                octaveTerm: false, velocity: 100,
                onReading: pushLog,
                onProbe: function (ch, confirming) {
                    autoNote((confirming ? 'Checking for the keyboard on MIDI channel '
                                         : 'Looking for the keyboard on MIDI channel ') +
                             (ch + 1) + '\u2026', 0);
                },
                onChannel: function (ch) {
                    $('calMidiChan').value = String(ch);
                },
                onNote: function (step, reading, i, total) {
                    got[CALIBRATE.semitoneFor(step.index, PLAYABLE_LOW)] =
                        reading ? reading.cents : null;
                    var name = CALIBRATE.noteLabel(step.index);
                    autoNote(name + '  ' + (i + 1) + ' of ' + total + '   ' +
                             (reading && reading.cents !== null ?
                                 (reading.cents >= 0 ? '+' : '') +
                                 reading.cents.toFixed(1) + ' cents' : 'not heard'),
                             (i + 1) / total);
                }
            });
            return sweep.run().then(function (out) {
                var bridged = bridgeGaps(got);
                $('useCal').checked = true;
                syncCalBody();
                buildTable(); drawPlot(); validateCal(); invalidate();
                var heard = out.readings.filter(function (r) { return r.cents !== null; });
                autoNote('');
                var note = 'Measured ' + heard.length + ' of ' + out.readings.length +
                    ' notes on MIDI channel ' + (out.channel + 1) + '. Bottom C was ' +
                    out.anchorHz.toFixed(2) + ' Hz; the ' +
                    'oscillator drifted ' + out.drift.toFixed(1) + ' cents over the run, ' +
                    'which has been taken out of every reading.';
                if (bridged) {
                    note += ' ' + bridged + ' note' + (bridged === 1 ? ' was' : 's were') +
                        ' not heard and have been carried across from their neighbours - ' +
                        'check those by hand.';
                }
                if (out.warnings.length) {
                    // Capped: a run that goes wrong everywhere would otherwise
                    // bury its own summary under sixty-five lines.
                    var show = out.warnings.slice(0, 12);
                    note += '\n\n' + show.join('\n');
                    if (out.warnings.length > show.length) {
                        note += '\n...and ' + (out.warnings.length - show.length) +
                                ' more.';
                    }
                }
                msg($('calMsg'), heard.length && !out.warnings.length ? 'ok' : 'bad', note);
            });
        }).catch(function (err) {
            autoNote('');
            msg($('calMsg'), 'bad', err.message || String(err));
        }).then(function () { sweep = null; setRunning(false); });
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
        if (calibrationInBuild()) o.pitch_correction = rows();
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
        saveSoon();
        syncReset();
        refresh();
    }

    function refresh() {
        $('build').disabled = !state.factoryText;
        $('dlMac').disabled = !state.result;
        $('dlWin').disabled = !state.result;
        // Step 5 wants the image's own record, which only a build has.
        if ($('kbdSend')) $('kbdSend').disabled = !(state.result && $('kbdPort').value);
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
            // How many patterns the bank holds, not what they are - the same
            // line the flashers echo, so no brackets.
            'Pattern bank: ' + (o.knob2 === 'patterns'
                ? (o.arp_patterns || []).length + ' patterns'
                : 'not in use - knob 2 is not on patterns'),
            'Sequencer: ' + (o.sequencer ? 'on' : 'off'),
            'Clock divider: ' + (o.clock_divide ? 'on' : 'off'),
            'Pressure: ' + (o.pressure_fix ? 'rewired' : 'factory') +
                (o.pressure_portamento ? ', portamento' : ''),
            'Scaling: ' + o.volts_per_octave + ' V/octave',
            // No brackets: echoed by both flashers, see the tunings line.
            'Pitch offset: ' + (o.pitch_offset === false
                ? 'none - 208c' : '3 semitones - 208, 208r, 208p'),
            'Oscillator correction: ' + (o.pitch_correction ? 'applied' : 'off'),
            'Preset voltages: ' + (o.quantize_presets
                ? 'quantized to the tuning when added to pitch' : 'not quantized'),
            'Portamento banana jack: ' + (o.portamento_in === 'transpose'
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
            .concat(calibrationInBuild() ? [
                     '  ' + CAL_CSV_NAME +
                     '   the pitch table this image applies',
                     '  Keep it with the image. Load it back into the builder',
                     '  before measuring again, so the next set of readings adds',
                     '  to this table instead of replacing it.', ''] : [])
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
                pitch_correction: !!o.pitch_correction,
                // Whether a preset voltage is snapped to the tuning when it
                // is added to pitch, and what the portamento banana jack was
                // set to do.  Both were on the page for a while before they
                // were counted, so a build using either was invisible here.
                quantize_presets: !!o.quantize_presets,
                portamento_in: o.portamento_in,
                // Which download of the day this is from this browser, so a
                // count of people is not a count of afternoons: one person
                // trying twelve option sets otherwise reads as twelve.  An
                // ordinal 1..10, never an identifier - every value is shared
                // by millions of downloads and there is no key to join two
                // rows on.  -1 when the browser cannot count.
                nth_today: countToday()
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
                // The table travels in the kit because it describes what this
                // image applies, and the next round of measuring has to load
                // it back or it starts from an instrument it is not looking at.
                // Saving it was a separate button nobody had a reason to press
                // until a second calibration, by which time it was too late.
                // It sits at the root, beside the README and the flash log the
                // flasher writes there, not in the firmware folder: the
                // owner's call (2026-09-17), so the record of what was flashed
                // and the table it was flashed with are found together.
                var cal = calibrationInBuild()
                    ? [{ name: CAL_CSV_NAME, data: calibrationCsv() }]
                    : [];
                var files = [{ name: built, data: r.hex },
                             { name: stock, data: state.factoryText,
                               mtime: stockDate }]
                    .concat(cal, p.scripts(r), tools,
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
                    state.patterns = defaultPatterns();
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

    // --- remembering the last build ---------------------------------------
    //
    // Everything here stays in this browser: localStorage is per-origin and
    // nothing in this block goes near the network.  The beacon is unchanged
    // and still carries no identifier.
    //
    // Two keys, not one.  The factory image is 261 KB of text and changes
    // once, when a file is dropped; the settings are small and change on
    // every click, so writing them together would rewrite half a megabyte
    // each time a checkbox moved.
    //
    // The key is namespaced by the page's own directory because the staging
    // build at /dev/ is the SAME ORIGIN as the released page, one path down.
    // A single key would have a test on the staging page quietly overwrite
    // what the released page remembered.
    var STORE = '218e-rewired' + location.pathname.replace(/[^/]*$/, '');
    var K_SETTINGS = STORE + 'settings', K_FACTORY = STORE + 'factory';
    var K_TODAY = STORE + 'today';

    // Where the ordinal the beacon sends stops going up.  The worker holds the
    // same ceiling; tools/test_worker.mjs keeps the two in step.
    var MAX_PER_DAY = 10;

    // How many downloads this browser has made today, and nothing else: no
    // identifier, no history, and only the ordinal ever leaves.  The date is
    // the LOCAL calendar date rather than UTC, so an evening's work does not
    // split in half at midnight in a timezone nobody here is in.
    //
    // Nothing kept here has to survive the night, which is what makes it
    // sound: the seven-day eviction that would quietly corrupt a long-lived
    // counter has nothing to take away that this depends on.
    //
    // -1 rather than 1 when storage cannot be written.  A browser that cannot
    // count does not know this is a first download, and reporting every one of
    // its downloads as somebody's first would inflate the very number this
    // exists to make honest.
    function countToday() {
        var d = new Date();
        var day = d.getFullYear() + '-' + (d.getMonth() + 1) + '-' + d.getDate();
        var n = 0;
        try {
            var was = JSON.parse(readStore(K_TODAY) || 'null');
            if (was && was.day === day && typeof was.n === 'number' && was.n > 0) {
                n = was.n;
            }
        } catch (e) {
            n = 0;
        }
        n += 1;
        if (!writeStore(K_TODAY, JSON.stringify({ day: day, n: n }))) return -1;
        return n > MAX_PER_DAY ? MAX_PER_DAY : n;
    }

    // Storage is absent on file: URLs in some browsers, throws in private
    // windows, and refuses when the origin is full.  Every one of those is
    // "this page does not remember", never a broken page - the same direction
    // the beacon fails in.
    function readStore(key) {
        try { return window.localStorage.getItem(key); } catch (e) { return null; }
    }
    function writeStore(key, text) {
        try {
            if (text === null) window.localStorage.removeItem(key);
            else window.localStorage.setItem(key, text);
            return true;
        } catch (e) { return false; }
    }

    // The pickers keep the button's own data-v rather than the parsed value:
    // String(1.0) is "1" and the button is "1.0", so a round trip through a
    // number would stop matching the markup it has to find again.
    function pressed(groupId) {
        var hit = '';
        Array.prototype.forEach.call($(groupId).children, function (b) {
            if (b.getAttribute('aria-pressed') === 'true') hit = b.dataset.v;
        });
        return hit;
    }
    function press(groupId, value) {
        var host = $(groupId), hit = null;
        Array.prototype.forEach.call(host.children, function (b) {
            if (b.dataset.v === String(value)) hit = b;
        });
        // Only when it is not already the one pressed: these handlers do real
        // work, and setPitchOffset in particular renumbers the table.
        if (hit && hit.getAttribute('aria-pressed') !== 'true') hit.click();
    }
    function tick(id, on) {
        var el = $(id);
        if (!el || el.checked === !!on) return;
        el.checked = !!on;
        el.dispatchEvent(new Event('change'));
    }

    var CHECKS = ['latching_arp', 'sequencer', 'clock_divide', 'pressure_fix',
                  'pressure_portamento', 'quantize_presets', 'portamento_transpose'];

    // The scalar half - the part kept as deviations from the page's defaults.
    //
    // `markup` asks for the value the DOCUMENT declares rather than the one on
    // screen, and the difference is not academic: a browser restores checkbox
    // state across a reload by itself, before any script runs.  Read the live
    // checkbox to capture the defaults and a box the visitor turned off last
    // time is already off when they are captured, so the deviation measures
    // zero, is never saved, and the choice is lost on the visit after next.
    // defaultChecked is the `checked` attribute, which that restoration does
    // not touch.  The pickers need no equivalent: aria-pressed is not form
    // state, and nothing has clicked yet when the defaults are taken.
    function scalars(markup) {
        function on(id) {
            var el = $(id);
            return markup ? el.defaultChecked : el.checked;
        }
        var o = {
            pitch_offset: pressed('offset'),
            volts_per_octave: pressed('vpo'),
            knob1: knobRole.knob1, knob2: knobRole.knob2,
            knob3: knobRole.knob3, knob4: knobRole.knob4,
            use_tunings: on('useTunings'),
            use_cal: on('useCal')
        };
        CHECKS.forEach(function (id) { o[id] = on(id); });
        return o;
    }

    // What the document declares, captured before anything is restored.
    var DEFAULTS = scalars(true);

    var saveTimer = null, restoring = false;

    function saveNow() {
        saveTimer = null;
        if (restoring) return;
        var body;
        try {
            body = JSON.stringify({
                v: 1,
                version: GEN.version,
                options: BUILDLIB.settingsDiff(scalars(), DEFAULTS),
                patterns: state.patterns,
                slots: state.slots,
                // The calibration's own working state, not the CSV.  Loading
                // a CSV means "this table is already on the instrument": it
                // becomes the baseline and the readings are cleared.  Saving
                // one and reading it back would therefore promote this
                // session's unflashed readings to already-flashed, and the
                // next round would fold onto a table that was never there.
                calibration: {
                    measured: measured,
                    interpolated: interpolated,
                    baseline: baseline,
                    baselineSources: baselineSources,
                    baselineName: baselineName,
                    baselineHistory: baselineHistory
                }
            });
        } catch (e) { return; }
        writeStore(K_SETTINGS, body);
    }
    // Coalesced: a sweep writes a reading at a time, and every one of them
    // reaches invalidate().
    function saveSoon() {
        if (restoring) return;
        if (saveTimer) clearTimeout(saveTimer);
        saveTimer = setTimeout(saveNow, 400);
    }
    window.addEventListener('pagehide', function () {
        if (saveTimer) { clearTimeout(saveTimer); saveNow(); }
    });

    function saveFactory() {
        if (!state.factoryText) { writeStore(K_FACTORY, null); return; }
        try {
            writeStore(K_FACTORY, JSON.stringify({
                text: state.factoryText,
                // The image's own date, which goes back into the download so
                // the stock file keeps saying when it was made.  Without it a
                // restored build would stamp today.
                mtime: state.factoryMtime ? state.factoryMtime.getTime() : null
            }));
        } catch (e) { /* out of room; the page just asks for the file again */ }
    }

    // --- putting it back --------------------------------------------------
    function goodPatterns(v) {
        if (!Array.isArray(v)) return null;
        var out = [];
        v.slice(0, 32).forEach(function (p) {
            if (p && typeof p.text === 'string' && typeof p.length === 'number'
                    && isFinite(p.length)) {
                out.push({ text: p.text, length: p.length });
            }
        });
        return out;
    }
    function goodSlots(v) {
        if (!Array.isArray(v)) return null;
        var out = [];
        v.slice(0, SLOTS.length).forEach(function (e) {
            if (!e || typeof e.name !== 'string' || typeof e.text !== 'string') {
                out.push(null);
                return;
            }
            var slot = { name: e.name, text: e.text };
            if (typeof e.kbmName === 'string' && typeof e.kbmText === 'string') {
                slot.kbmName = e.kbmName;
                slot.kbmText = e.kbmText;
            }
            out.push(slot);
        });
        return out;
    }
    // A stored calibration is taken whole or not at all, for the same reason
    // a loaded table is: a half-applied one leaves the rest at zero, which is
    // "no correction" rather than "unknown", and the fold would quietly undo
    // what is flashed there.
    function goodCalibration(v) {
        if (!v || typeof v !== 'object') return null;
        if (!Array.isArray(v.measured) || v.measured.length !== TABLE_ENTRIES) return null;
        var m = [], i;
        for (i = 0; i < TABLE_ENTRIES; i++) {
            if (typeof v.measured[i] !== 'number' || !isFinite(v.measured[i])) return null;
            m.push(v.measured[i]);
        }
        var base = {}, src = {}, marks = {};
        if (v.baseline && typeof v.baseline === 'object') {
            for (i = 0; i < TABLE_ENTRIES; i++) {
                var b = v.baseline[i];
                if (typeof b !== 'number' || !isFinite(b)) return null;
                base[i] = b;
            }
        } else {
            for (i = 0; i < TABLE_ENTRIES; i++) base[i] = 0;
        }
        if (v.baselineSources && typeof v.baselineSources === 'object') {
            Object.keys(v.baselineSources).forEach(function (k) {
                if (typeof v.baselineSources[k] === 'string') src[k] = v.baselineSources[k];
            });
        }
        if (v.interpolated && typeof v.interpolated === 'object') {
            Object.keys(v.interpolated).forEach(function (k) {
                if (v.interpolated[k]) marks[k] = true;
            });
        }
        return {
            measured: m, interpolated: marks, baseline: base, baselineSources: src,
            baselineName: typeof v.baselineName === 'string' ? v.baselineName : '',
            baselineHistory: v.baselineHistory && typeof v.baselineHistory === 'object'
                ? v.baselineHistory : null
        };
    }

    // Keyed by the same names BUILDLIB.SETTINGS_ORDER lists, and driven by
    // that array rather than by the order written here - the dependencies
    // between these are documented there, next to the order that enforces
    // them.
    var APPLY = {
        pitch_offset: function (v) { press('offset', v); },
        volts_per_octave: function (v) { press('vpo', v); },
        patterns: function (v) {
            var p = goodPatterns(v);
            if (p) { state.patterns = p; renderPatterns(); }
        },
        knob1: function (v) { press('knob1', v); },
        knob2: function (v) { press('knob2', v); },
        knob3: function (v) { press('knob3', v); },
        knob4: function (v) { press('knob4', v); },
        use_tunings: function (v) { tick('useTunings', v); },
        use_cal: function (v) { tick('useCal', v); },
        slots: function (v) {
            var s = goodSlots(v);
            if (s) { state.slots = s; renderSlots(); }
        },
        calibration: function (v) {
            var c = goodCalibration(v);
            if (!c) return;
            measured = c.measured;
            interpolated = c.interpolated;
            baseline = c.baseline;
            baselineSources = c.baselineSources;
            baselineName = c.baselineName;
            baselineHistory = c.baselineHistory;
            syncBaseline(); buildTable(); drawPlot(); validateCal();
        },
        factory: function (v) {
            if (!v || typeof v.text !== 'string') return;
            var sha;
            try { sha = SHA256.hashString(v.text); } catch (e) { sha = null; }
            // The pin is the whole safety argument for keeping the image at
            // all: what comes back out of storage goes through the same check
            // the dropped file did, so a corrupted or substituted copy is
            // refused exactly as a wrong file is.
            if (sha !== GEN.factorySha256) { writeStore(K_FACTORY, null); return; }
            state.factoryText = v.text;
            state.factoryMtime = typeof v.mtime === 'number' ? new Date(v.mtime) : null;
            $('drop').className = 'drop ok';
            msg($('fileMsg'), 'ok', 'Factory image remembered from last time: ' +
                'SHA-256 matches. It stays on this machine.');
        }
    };
    CHECKS.forEach(function (id) {
        APPLY[id] = function (v) { tick(id, v); };
    });

    // The walk a restore and a Reset share, so both go through the one
    // ordered list.  A key with no value here is simply not applied, which is
    // what leaves the visitor's own data alone on a Reset.
    function applyAll(values) {
        restoring = true;
        try {
            BUILDLIB.SETTINGS_ORDER.forEach(function (k) {
                if (APPLY[k] && values[k] !== undefined) APPLY[k](values[k]);
            });
        } finally {
            restoring = false;
        }
    }

    function restore() {
        var saved = null, hex = null;
        try { saved = JSON.parse(readStore(K_SETTINGS) || 'null'); } catch (e) { saved = null; }
        try { hex = JSON.parse(readStore(K_FACTORY) || 'null'); } catch (e) { hex = null; }
        // A blob from a format this page does not know is left alone rather
        // than guessed at.  The image is not versioned: it is one field and a
        // hash that has to match anyway.
        if (saved && saved.v !== 1) saved = null;
        var all = BUILDLIB.settingsPick(saved && saved.options, DEFAULTS);
        if (saved) {
            all.patterns = saved.patterns;
            all.slots = saved.slots;
            all.calibration = saved.calibration;
        }
        all.factory = hex;
        applyAll(all);
    }

    // Remembering is meant to be invisible: nothing on the page announces it,
    // and the one control that exists appears only once something has moved
    // off its default, because until then it has nothing to undo.
    function syncReset() {
        var el = $('reset');
        if (!el || !DEFAULTS) return;
        el.classList.toggle('hidden',
            !Object.keys(BUILDLIB.settingsDiff(scalars(), DEFAULTS)).length);
    }

    // Guarded: the entry document is served no-cache while the assets are
    // immutable, so a browser can hold a page from before this control for as
    // long as its revalidation takes.  Throwing here would take the restore
    // below down with it.
    if ($('reset')) $('reset').addEventListener('click', function () {
        // Back to the page's own defaults, through the same appliers a
        // restore uses.  The compound keys are not in DEFAULTS and so are
        // skipped: the Scala files, the pattern bank, the measured
        // calibration and the factory image stay exactly where they are.
        // This puts the CHOICES back, not the work - re-ticking a box brings
        // what was loaded back with it, and a sweep is not thrown away by a
        // button labelled Reset.  The deviations then being empty is what
        // empties the save.
        applyAll(DEFAULTS);
        saveNow();
        syncReset();
    });

    restore();
    syncReset();
    renderPatterns();
    renderSlots(); buildTable(); drawPlot(); syncPortamento();
    syncCalBody(); syncBaseline(); refresh();
    bindDashes(document.body);
})();
