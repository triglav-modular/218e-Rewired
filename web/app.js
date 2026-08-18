// UI wiring.  All the real work is in build.js; this collects the seven
// options, the optional Scala files and the optional calibration, and shows
// what went wrong when something does.
(function () {
    'use strict';

    var $ = function (id) { return document.getElementById(id); };
    var state = { factoryText: null, slots: null, calibration: null, result: null };

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
        el.appendChild(d);
    }

    // --- semitone naming -------------------------------------------------
    // Semitone 0 is the 208's 0 V pitch, which is an A.  The bottom key is a
    // C, at semitone 3, and keys are numbered from 1 — the three different
    // ways the CSV let you name a row, and the reason this shows all of them.
    var NAMES = ['A','A#','B','C','C#','D','D#','E','F','F#','G','G#'];
    function noteName(semitone) {
        return NAMES[semitone % 12] + (Math.floor((semitone + 9) / 12));
    }
    // The 32 physical keys are semitones 3..34, key 1 being the bottom C.
    // Above that the same keys reach higher pitches through the octave switch.
    function keyLabel(semitone) {
        if (semitone >= 3 && semitone <= 34) return String(semitone - 2);
        return '+oct';
    }

    // Only pitches the instrument can actually produce are editable.  The
    // firmware indexes semitones 0..78, but 0..2 sit below the bottom key and
    // 68..78 above anything the octave switch can reach, so neither can be
    // played or measured.  They are filled in from the ends of the measured
    // range instead of being offered as boxes nobody can fill.
    var PLAYABLE_LOW = 3, PLAYABLE_HIGH = 67, TABLE_ENTRIES = 79;

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
                $('drop').className = 'drop err';
                msg($('fileMsg'), 'bad',
                    'That is not the stock v36.9 image.\n\nexpected  ' + GEN.factorySha256 +
                    '\nthis file ' + sha +
                    '\n\nThe build only accepts the exact factory image, so a wrong or ' +
                    'altered file is rejected rather than flashed.');
            } else {
                state.factoryText = text;
                $('drop').className = 'drop ok';
                msg($('fileMsg'), 'ok', 'Factory image verified — SHA-256 matches. ' +
                    'It stays on this machine.');
            }
            refresh();
        };
        reader.onerror = function () { fail('Could not read that file.'); };
        reader.readAsText(file);
        function fail(t) { $('drop').className = 'drop err'; msg($('fileMsg'), 'bad', t); }
    }

    var drop = $('drop');
    drop.addEventListener('click', function () { $('file').click(); });
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
            Array.prototype.forEach.call($('vpo').children, function (o) {
                o.setAttribute('aria-pressed', String(o === b));
            });
        });
    });

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

    function renderSlots() {
        var host = $('slots');
        host.innerHTML = '';
        SLOTS.forEach(function (meta, i) {
            var entry = state.slots[i];
            var row = document.createElement('div');
            row.className = 'slot';

            var who = document.createElement('div');
            who.className = 'who';
            who.innerHTML = '<b>' + meta.name + '</b><span>' + meta.note + '</span>';

            var what = document.createElement('div');
            what.className = 'what' + (entry ? '' : ' empty');
            if (entry) {
                // Each scale is shifted so the same key lands on the 12-TET
                // grid in every slot, which is why switching tuning never
                // moves the note the 208 was trimmed to.  Worth showing: it is
                // computed here, not baked into the file.
                var shift = '';
                try {
                    var cents = BUILDLIB.parseScala(entry.text, entry.name);
                    var off = BUILDLIB.anchorOffset(cents, 9);
                    shift = '  ' + (off >= 0 ? '+' : '') + off.toFixed(2) + 'c';
                } catch (e) { /* already reported on load */ }
                what.textContent = entry.name;
                what.title = entry.name + (shift ? ' — anchored on A by' + shift : '');
                if (shift) {
                    var tag = document.createElement('span');
                    tag.className = 'muted';
                    tag.style.cssText = 'font-family:inherit;font-size:11px;margin-left:8px';
                    tag.textContent = 'A anchored' + shift;
                    what.appendChild(tag);
                }
            } else {
                what.textContent = 'factory temperament';
            }

            var ctl = document.createElement('div');
            ctl.className = 'ctl';
            [['↑', i - 1], ['↓', i + 1]].forEach(function (pair) {
                var b = document.createElement('button');
                b.textContent = pair[0];
                b.title = 'move to slot ' + pair[1];
                b.disabled = !entry || pair[1] < 0 || pair[1] > 2;
                b.addEventListener('click', function () {
                    var to = pair[1], tmp = state.slots[to];
                    state.slots[to] = state.slots[i];
                    state.slots[i] = tmp;
                    renderSlots(); refresh();
                });
                ctl.appendChild(b);
            });
            var x = document.createElement('button');
            x.textContent = '✕'; x.title = 'clear this slot';
            x.disabled = !entry;
            x.addEventListener('click', function () {
                state.slots[i] = null; renderSlots(); refresh();
            });
            ctl.appendChild(x);

            row.appendChild(who); row.appendChild(what); row.appendChild(ctl);
            host.appendChild(row);
        });
        var filled = state.slots.filter(Boolean).length;
        $('sclCount').textContent = filled
            ? filled + ' of 3 slots set'
            : 'all three slots factory';
    }

    $('sclPick').addEventListener('click', function () { $('scl').click(); });
    $('scl').addEventListener('change', function (e) {
        var files = Array.prototype.slice.call(e.target.files);
        var problems = [];
        var pending = files.length;
        if (!pending) return;
        files.forEach(function (f) {
            var r = new FileReader();
            r.onload = function () {
                var entry = { name: f.name, text: r.result };
                try {
                    // Validate now, so a bad scale is caught while it is still
                    // obvious which file it was.
                    BUILDLIB.parseScala(entry.text, entry.name);
                    var free = state.slots.indexOf(null);
                    if (free < 0) problems.push(f.name + ' — all three slots are full');
                    else state.slots[free] = entry;
                } catch (err) {
                    problems.push(err.message);
                }
                if (--pending === 0) {
                    renderSlots(); refresh();
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

    function drawPlot() {
        var play = measured.slice(PLAYABLE_LOW, PLAYABLE_HIGH + 1);
        var svg = $('calPlot'), lo = Math.min.apply(null, play),
            hi = Math.max.apply(null, play);
        if (hi - lo < 1) { lo -= 1; hi += 1; }
        var span = PLAYABLE_HIGH - PLAYABLE_LOW;
        var pts = play.map(function (v, i) {
            return (i / span * 700).toFixed(1) + ',' +
                   (110 - (v - lo) / (hi - lo) * 100).toFixed(1);
        }).join(' ');
        svg.innerHTML =
            '<polyline points="' + pts + '" fill="none" stroke="var(--accent)" ' +
            'stroke-width="2" vector-effect="non-scaling-stroke"/>' +
            '<text x="2" y="12" fill="var(--muted)" font-size="11">' + hi.toFixed(1) +
            ' cents</text><text x="2" y="118" fill="var(--muted)" font-size="11">' +
            lo.toFixed(1) + '</text>';
    }

    function buildTable() {
        var body = $('calTable').tBodies[0];
        body.innerHTML = '';
        for (var n = PLAYABLE_LOW; n <= PLAYABLE_HIGH; n++) {
            (function (n) {
                var tr = document.createElement('tr');
                tr.innerHTML = '<td class="note">' + noteName(n) + '</td><td class="muted">' +
                    keyLabel(n) + '</td><td class="muted">' + n + '</td><td></td>';
                var input = document.createElement('input');
                input.type = 'number'; input.step = '0.01'; input.value = measured[n].toFixed(2);
                input.addEventListener('change', function () {
                    measured[n] = parseFloat(input.value) || 0;
                    drawPlot(); validateCal();
                });
                tr.lastChild.appendChild(input);
                body.appendChild(tr);
            })(n);
        }
    }

    function validateCal() {
        if (!$('useCal').checked) { msg($('calMsg'), '', ''); return true; }
        try {
            var cfg = BUILDLIB.expand({ volts_per_octave: vpo, pitch_correction: rows() });
            BUILDLIB.pitchTable(cfg, rows());
            msg($('calMsg'), 'ok', 'Correction is monotonic and inside the 12-bit DAC.');
            return true;
        } catch (e) {
            var hint = '';
            if (/DAC range/.test(e.message)) {
                hint = '\n\nThe corrected pitch runs past what the DAC can produce. That ' +
                       'usually means the lowest C was not tuned in before measuring, so ' +
                       'every reading carries the same offset — retune it and measure again.';
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
        return full.map(function (v, i) { return { semitone: i, cents: v }; });
    }

    $('useCal').addEventListener('change', function () { validateCal(); refresh(); });
    $('calZero').addEventListener('click', function () {
        measured = measured.map(function () { return 0; });
        buildTable(); drawPlot(); validateCal();
        msg($('calMsg'), '', '');
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
            '# Semitone is the index into the firmware table; the lowest C on the',
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
        if (!f) return;
        var r = new FileReader();
        r.onload = function () {
            var found = 0;
            // A file of Offset_Cents holds corrections, the opposite sign to a
            // measurement, so it is flipped on the way in.
            var isCorrection = /Offset_Cents/i.test(r.result);
            r.result.split('\n').forEach(function (line) {
                if (!line.trim() || line.charAt(0) === '#' || /^Semitone/i.test(line)) return;
                var p = line.split(line.indexOf(';') >= 0 ? ';' : ',');
                var n = parseInt(p[0], 10), c = parseFloat(p[3]);
                if (isCorrection) c = -c;
                // Only the playable rows are taken; anything outside is
                // regenerated from them, so a CSV with filled-in tails cannot
                // disagree with what the editor shows.
                if (!isNaN(n) && n >= PLAYABLE_LOW && n <= PLAYABLE_HIGH && !isNaN(c)) {
                    measured[n] = c; found++;
                }
            });
            $('useCal').checked = found > 0;
            buildTable(); drawPlot(); validateCal(); refresh();
            msg($('calMsg'), found ? 'ok' : 'bad',
                found ? 'Loaded ' + found + ' rows from ' + f.name
                      : 'No usable rows in ' + f.name +
                        ' — expected Semitone;Note;Key;Offset_Cents;Source');
        };
        r.readAsText(f);
    });

    // --- build ------------------------------------------------------------
    function options() {
        var o = {
            latching_arp: $('latching_arp').checked,
            remap_knobs: $('remap_knobs').checked,
            pressure_fix: $('pressure_fix').checked,
            pressure_portamento: $('pressure_portamento').checked,
            volts_per_octave: vpo
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
        if ($('useCal').checked) o.pitch_correction = rows();
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

    function refresh() {
        $('build').disabled = !state.factoryText;
        $('download').disabled = !state.result;
    }

    $('build').addEventListener('click', function () {
        msg($('buildMsg'), 'warn', 'Building…');
        $('build').disabled = true;
        // Yield first so the message paints before the synchronous build runs.
        setTimeout(function () {
            try {
                var t0 = Date.now();
                var r = WEBBUILD.build(options(), state.factoryText);
                state.result = r;
                msg($('buildMsg'), 'ok',
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

    $('download').addEventListener('click', function () {
        if (!state.result) return;
        download(state.result.hex, '218eV3_v369_Rewired_DFU.hex', 'text/plain');
    });

    $('useTunings').addEventListener('change', function () {
        $('tuningsBody').style.display = $('useTunings').checked ? '' : 'none';
        refresh();
    });

    renderSlots(); buildTable(); drawPlot(); syncPortamento(); refresh();
})();
