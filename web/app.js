// UI wiring.  All the real work is in build.js; this collects the seven
// options, the optional Scala files and the optional calibration, and shows
// what went wrong when something does.
(function () {
    'use strict';

    var $ = function (id) { return document.getElementById(id); };
    var state = { factoryText: null, scales: [], calibration: null, result: null };

    function msg(el, kind, text) {
        el.innerHTML = '';
        if (!text) return;
        var d = document.createElement('div');
        d.className = 'msg ' + kind;
        d.textContent = text;
        el.appendChild(d);
    }

    // --- semitone naming -------------------------------------------------
    // Semitone 0 is the 208p's 0 V pitch, which is an A.  The bottom key is a
    // C, at semitone 3, and keys are numbered from 1 — the three different
    // ways the CSV let you name a row, and the reason this shows all of them.
    var NAMES = ['A','A#','B','C','C#','D','D#','E','F','F#','G','G#'];
    function noteName(semitone) {
        return NAMES[semitone % 12] + (Math.floor((semitone + 9) / 12));
    }
    function keyNumber(semitone) {
        return semitone >= 3 && semitone <= 34 ? String(semitone - 2) : '';
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
    var vpo = 1.0;
    Array.prototype.forEach.call($('vpo').children, function (b) {
        b.addEventListener('click', function () {
            vpo = parseFloat(b.dataset.v);
            Array.prototype.forEach.call($('vpo').children, function (o) {
                o.setAttribute('aria-pressed', String(o === b));
            });
        });
    });

    // --- Scala files ------------------------------------------------------
    $('sclPick').addEventListener('click', function () { $('scl').click(); });
    $('scl').addEventListener('change', function (e) {
        var files = Array.prototype.slice.call(e.target.files).slice(0, 3);
        state.scales = [];
        $('sclList').innerHTML = '';
        files.forEach(function (f) {
            var r = new FileReader();
            r.onload = function () {
                var entry = { name: f.name, text: r.result };
                var line = document.createElement('div');
                try {
                    // Validate now rather than at build time, so a bad scale is
                    // caught while you can still see which file it was.
                    BUILDLIB.parseScala(entry.text, entry.name);
                    state.scales.push(entry);
                    line.className = 'ok';
                    line.textContent = '✓ ' + f.name;
                } catch (err) {
                    line.className = 'bad';
                    line.textContent = '✗ ' + f.name + ' — ' + err.message;
                }
                $('sclList').appendChild(line);
                $('sclCount').textContent = state.scales.length
                    ? state.scales.length + ' loaded' : 'none';
                refresh();
            };
            r.readAsText(f);
        });
    });

    // --- calibration ------------------------------------------------------
    var offsets = [];
    for (var i = 0; i < 79; i++) offsets.push(0);

    function drawPlot() {
        var svg = $('calPlot'), lo = Math.min.apply(null, offsets),
            hi = Math.max.apply(null, offsets);
        if (hi - lo < 1) { lo -= 1; hi += 1; }
        var pts = offsets.map(function (v, n) {
            return (n / 78 * 700).toFixed(1) + ',' +
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
        offsets.forEach(function (v, n) {
            var tr = document.createElement('tr');
            tr.innerHTML = '<td class="note">' + noteName(n) + '</td><td class="muted">' +
                keyNumber(n) + '</td><td class="muted">' + n + '</td><td></td>';
            var input = document.createElement('input');
            input.type = 'number'; input.step = '0.01'; input.value = v.toFixed(2);
            input.addEventListener('change', function () {
                offsets[n] = parseFloat(input.value) || 0;
                drawPlot(); validateCal();
            });
            tr.lastChild.appendChild(input);
            body.appendChild(tr);
        });
    }

    function validateCal() {
        if (!$('useCal').checked) { msg($('calMsg'), '', ''); return true; }
        try {
            var cfg = BUILDLIB.expand({ volts_per_octave: vpo, pitch_correction: rows() });
            BUILDLIB.pitchTable(cfg, rows());
            msg($('calMsg'), 'ok', 'Correction is monotonic and inside the 12-bit DAC.');
            return true;
        } catch (e) {
            msg($('calMsg'), 'bad', e.message);
            return false;
        }
    }

    function rows() {
        return offsets.map(function (v, n) { return { semitone: n, cents: v }; });
    }

    $('useCal').addEventListener('change', function () { validateCal(); refresh(); });
    $('calZero').addEventListener('click', function () {
        offsets = offsets.map(function () { return 0; });
        buildTable(); drawPlot(); validateCal();
    });
    $('calPick').addEventListener('click', function () { $('calFile').click(); });
    $('calFile').addEventListener('change', function (e) {
        var f = e.target.files[0];
        if (!f) return;
        var r = new FileReader();
        r.onload = function () {
            var found = 0;
            r.result.split('\n').forEach(function (line) {
                if (!line.trim() || line.charAt(0) === '#' || /^Semitone/i.test(line)) return;
                var p = line.split(line.indexOf(';') >= 0 ? ';' : ',');
                var n = parseInt(p[0], 10), c = parseFloat(p[3]);
                if (!isNaN(n) && n < 79 && !isNaN(c)) { offsets[n] = c; found++; }
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
        if (state.scales.length) o.alternate_tunings = state.scales;
        if ($('useCal').checked) o.pitch_correction = rows();
        return o;
    }

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
        var blob = new Blob([state.result.hex], { type: 'text/plain' });
        var a = document.createElement('a');
        a.href = URL.createObjectURL(blob);
        a.download = '218eV3_v369_PressureFix_DFU.hex';
        document.body.appendChild(a); a.click(); document.body.removeChild(a);
        setTimeout(function () { URL.revokeObjectURL(a.href); }, 1000);
    });

    buildTable(); drawPlot(); refresh();
})();
