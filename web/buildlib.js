// The build pipeline, ported from tools/build.py and tools/options.py.
//
// Everything from the seven options down to build.properties.  The assembler
// that consumes those properties is tools/avr32/{encoder,runtime,program}.js,
// already proven against Ghidra; this is the other half, and getting a
// byte-identical build.properties is what makes the resulting image identical
// too.
var BUILDLIB = (function () {
    'use strict';

    // --- Python repr, replicated ---------------------------------------
    // The init marker hashes repr(sorted(dict.items())) for the flags, the
    // numbers and the tables.  The digest only matches Python's if the text
    // being hashed matches exactly, down to True/False capitalisation and the
    // ", " between items.  Every key here is a plain identifier, so simple
    // single-quoting is enough.
    function pyRepr(v) {
        if (v === true) return 'True';
        if (v === false) return 'False';
        if (typeof v === 'number') return String(v);
        if (typeof v === 'string') return "'" + v + "'";
        if (Array.isArray(v)) return '[' + v.map(pyRepr).join(', ') + ']';
        throw new Error('pyRepr: unsupported ' + typeof v);
    }

    function reprSortedItems(obj) {
        var keys = Object.keys(obj).sort();
        return '[' + keys.map(function (k) {
            return '(' + pyRepr(k) + ', ' + pyRepr(obj[k]) + ')';
        }).join(', ') + ']';
    }

    function deepCopy(o) { return JSON.parse(JSON.stringify(o)); }

    // --- option expansion (tools/options.py) ----------------------------
    function flatCalibration() {
        var rows = [];
        for (var i = 0; i < 79; i++) rows.push({ semitone: i, cents: 0.0 });
        return rows;
    }

    function expand(options) {
        var cfg = deepCopy(GEN.internalDefaults);
        function want(name, fallback) {
            return Object.prototype.hasOwnProperty.call(options, name)
                ? options[name] : fallback;
        }
        cfg.arp.switch = want('latching_arp', true) ? 'latch' : 'factory';

        var remap = want('remap_knobs', true);
        var live = { knob1: 'arp_order', knob2: 'arp_rhythm',
                     knob3: 'arp_octaves', knob4: 'vibrato' };
        cfg.knobs = {};
        Object.keys(live).forEach(function (k) {
            cfg.knobs[k] = remap ? live[k] : 'factory';
        });

        // Pitch correction arrives as rows, not a file path: the browser has
        // no filesystem and the UI edits the offsets directly.
        cfg._calibration = want('pitch_correction', null) || flatCalibration();

        // Entries are either a {name, text} Scala file or the string
        // 'factory', so an empty slot between two filled ones keeps its place.
        var tunings = want('alternate_tunings', null);
        cfg._tunings = ['factory', 'factory', 'factory'];
        if (tunings && tunings.length) {
            if (tunings.length > 3) throw new Error('at most three tuning slots');
            tunings.forEach(function (t, i) { cfg._tunings[i] = t || 'factory'; });
        }

        // A hardware limit, not a shortlist: 6.5 octaves against a 10.22 V
        // DAC caps the scaling at 1.573 V/oct.  2 V/oct would need 13.00 V
        // and strand the top 17 keys at the ceiling.  See tools/options.py.
        var vpo = want('volts_per_octave', 1.2);
        if (vpo !== 1.0 && vpo !== 1.2) {
            throw new Error('volts_per_octave must be 1.0 or 1.2');
        }
        cfg.pitch.volts_per_octave = vpo;

        if (!want('pressure_fix', true)) {
            cfg.pressure.multi_key = 'factory';
            cfg.pressure.common_mode = false;
            cfg.pressure.error_diffusion = false;
            cfg.pressure.output_smoothing = 0;
            cfg._pressure_factory = true;
        }

        // Same rule as tools/options.py: the blend's pressure source is the
        // corrected-pressure cache, which only the reworked pressure pass
        // fills.  Without it the option builds and then silently does nothing.
        var blend = want('pressure_portamento', true);
        if (blend && !want('pressure_fix', true)) {
            throw new Error('Pressure-based portamento needs the pressure response ' +
                            'fix: the blend weights pitch by per-key pressure, and ' +
                            'only the reworked pressure path measures it.');
        }
        cfg.portamento.pressure_blend = blend;
        cfg.portamento.zero_snap = blend;
        return cfg;
    }

    // --- generators (tools/build.py) ------------------------------------
    // mapped: any degree count, and the whole list including the final degree,
    // because a .kbm then says which degree each key takes and which one is
    // the period.  Mirrors parse_scala(mapped=) in tools/build.py.
    function parseScala(text, name, mapped) {
        // Same shape as tools/build.py parse_scala: comments out first,
        // then the first remaining line is the description BY POSITION -
        // the format allows it to be blank, so it must never be filtered.
        var raw = text.split('\n').filter(function (l) {
            return l.replace(/^\s+/, '').charAt(0) !== '!';
        });
        var body = raw.slice(1).map(function (l) { return l.trim(); })
            .filter(function (l) { return l; });
        if (!raw.length || !body.length) {
            throw new Error(name + ': not a Scala file');
        }
        var head = body[0].split(/\s+/)[0];
        // Number(), not parseInt: '12x' must be refused, not read as 12,
        // or the page accepts files the CLI build rejects.
        var count = Number(head);
        if (!isFinite(count) || count !== Math.floor(count)) {
            throw new Error(name + ': degree count ' + JSON.stringify(head) +
                            ' is not a number');
        }
        var pitches = body.slice(1, 1 + count);
        if (pitches.length !== count) {
            throw new Error(name + ': declares ' + count + ' degrees, found ' + pitches.length);
        }
        var cents = [0.0];
        pitches.forEach(function (tok, index) {
            tok = tok.split(/\s+/)[0];
            var value;
            // Number(), not parseFloat: parseFloat takes any numeric prefix,
            // so '700.0!fifth' built an image here that tools/build.py
            // refuses.  Number() returns NaN for trailing text, and the
            // finiteness check below turns that into the same refusal the
            // CLI gives.
            if (tok.indexOf('.') >= 0) value = Number(tok);
            else {
                var p = tok.split('/');
                var ratio = p.length > 1 ? Number(p[0]) / Number(p[1]) : Number(p[0]);
                value = 1200.0 * Math.log(ratio) / Math.LN2;
            }
            // Every check below this is a comparison, and every comparison
            // with NaN is false - so an interior NaN is ascending, is an
            // octave, is anything asked of it.  It reaches the assembler and
            // becomes a zero halfword, which is a semitone silently retuned.
            if (!isFinite(value)) {
                throw new Error(name + ': degree ' + (index + 1) + ' is ' +
                                JSON.stringify(tok) + ', which is not a number');
            }
            cents.push(value);
        });
        if (count !== 12 && !mapped) {
            throw new Error(name + ': ' + count + ': the key table repeats every ' +
                            'octave, so a 12-note scale is required, or a .kbm to map it');
        }
        for (var i = 1; i < cents.length; i++) {
            if (cents[i] <= cents[i - 1]) {
                throw new Error(name + ': degrees are not strictly ascending');
            }
        }
        if (mapped) return cents;
        return cents.slice(0, 12);
    }

    // Python's round() is banker's rounding, but every call site here adds 0.5
    // and floors, so plain Math.floor(x + 0.5) is the same operation.
    function floorHalf(x) { return Math.floor(x + 0.5); }

    function factoryTuning(memory) {
        var out = [], base = GEN.factoryKeyTable;
        for (var k = 0; k < 32; k++) {
            var hi = memory[base + 2 * k], lo = memory[base + 2 * k + 1];
            if (hi === undefined || lo === undefined) {
                throw new Error('factory key table missing. Wrong base image?');
            }
            out.push((hi << 8) | lo);
        }
        return out;
    }

    // Scala keyboard mapping.  Same contract as parse_kbm in tools/build.py:
    // seven header values then one line per map position, 'x' or blank for a
    // position that sounds nothing.  The four MIDI-keyboard fields are read
    // to prove the file is well formed and then ignored - this instrument has
    // no note numbers and takes its absolute pitch from the 208's trimmer.
    // Unmapped positions take the nearest mapped position's degree, ties low.
    function parseKbm(text, name, cents) {
        var degreeCount = cents.length - 1;
        var raw = text.split('\n').filter(function (l) {
            return l.replace(/^\s+/, '').charAt(0) !== '!';
        });
        var header = [], index = 0;
        while (index < raw.length && header.length < 7) {
            var token = raw[index].trim();
            index += 1;
            if (token) header.push(token.split(/\s+/)[0]);
        }
        if (header.length < 7) {
            throw new Error(name + ': not a Scala keyboard mapping — needs seven ' +
                            'header values, found ' + header.length);
        }
        var names = ['map size', 'first MIDI note', 'last MIDI note', 'middle note',
                     'reference note', 'reference frequency', 'formal octave degree'];
        var values = [];
        for (var h = 0; h < 7; h++) {
            var value = Number(header[h]);
            var wantsInt = h !== 5;
            if (!isFinite(value) || (wantsInt && value !== Math.floor(value))) {
                throw new Error(name + ': ' + names[h] + ' is ' +
                                JSON.stringify(header[h]) + ', which is not a number');
            }
            values.push(value);
        }
        var size = values[0], refHz = values[5], formal = values[6];
        if (!(refHz > 0)) {
            throw new Error(name + ': reference frequency must be above zero');
        }
        if (size < 0) {
            throw new Error(name + ': map size is ' + size + ', which is negative');
        }
        if (!(formal >= 1 && formal <= degreeCount)) {
            throw new Error(name + ': formal octave degree is ' + formal +
                            ', but the scale has ' + degreeCount +
                            ' degrees — it must name one of them');
        }
        if (size === 0) {
            var linear = [];
            for (var d = 0; d < degreeCount; d++) linear.push(d);
            return { degrees: linear, formal: formal };
        }
        var entries = raw.slice(index);
        if (entries.length < size) {
            throw new Error(name + ': map size is ' + size + ', found ' +
                            entries.length + ' entries');
        }
        var degrees = [];
        for (var position = 0; position < size; position++) {
            var line = entries[position].trim();
            if (!line || line.charAt(0) === 'x' || line.charAt(0) === 'X') {
                degrees.push(null);
                continue;
            }
            var degree = Number(line.split(/\s+/)[0]);
            if (!isFinite(degree) || degree !== Math.floor(degree)) {
                throw new Error(name + ': position ' + position + ' is ' +
                                JSON.stringify(line) +
                                " — a scale degree or 'x' for unmapped");
            }
            if (!(degree >= 0 && degree <= degreeCount)) {
                throw new Error(name + ': position ' + position + ' names degree ' +
                                degree + ', but the scale has degrees 0..' + degreeCount);
            }
            degrees.push(degree);
        }
        var mappedAt = [];
        degrees.forEach(function (d, i) { if (d !== null) mappedAt.push(i); });
        if (!mappedAt.length) throw new Error(name + ': every position is unmapped');
        var filled = degrees.map(function (d, position) {
            if (d !== null) return d;
            var best = mappedAt[0];
            mappedAt.forEach(function (i) {
                if (Math.abs(i - position) < Math.abs(best - position)) best = i;
            });
            return degrees[best];
        });
        return { degrees: filled, formal: formal };
    }

    // Where a key sounds, in cents above the bottom key.
    function keyPitch(cents, degrees, period, key) {
        return period * Math.floor(key / degrees.length) +
               cents[degrees[key % degrees.length]];
    }

    // Cents to shift a scale so reference_key lands on the 12-TET grid, so the
    // note the 208 is tuned to sits in the same place in every slot.
    function anchorOffset(cents, referenceKey, degrees, period) {
        if (!(referenceKey >= 0 && referenceKey <= 11)) {
            throw new Error('reference_key must be 0..11 (0 = C, 9 = A)');
        }
        if (!degrees) return 100.0 * referenceKey - cents[referenceKey];
        return 100.0 * referenceKey - keyPitch(cents, degrees, period, referenceKey);
    }

    // offset is added inside the expression, in the same position as build.py:
    // floating-point addition is not associative, so shifting the cents array
    // beforehand could differ in the last bit and move a quantised entry.
    function tuningTable(cents, base, perOctave, offset, degrees, period) {
        offset = offset || 0.0;
        var out = [];
        for (var k = 0; k < 32; k++) {
            // cents[12] is the scale's own octave; every scale that was legal
            // before declares 1200 there, so unmapped tables do not move.
            var span = cents.length > 12 ? cents[12] : 1200.0;
            var pitch = degrees ? keyPitch(cents, degrees, period, k)
                                : span * Math.floor(k / 12) + cents[k % 12];
            out.push(base + Math.floor((pitch + offset) * perOctave / 1200 + 0.5));
        }
        return out;
    }

    function pressureCurve(span, onsetDb, fade) {
        var out = [], previous = 0, exponent = -onsetDb / 20.0;
        for (var x = 0; x <= span; x++) {
            var value;
            if (x === 0) value = 0;
            else value = floorHalf(span * Math.pow(10.0, (x / span - 1.0) * exponent));
            if (fade && x > 0 && x < fade) value = Math.min(value, Math.floor(value * x / fade));
            value = Math.max(previous, Math.min(span, value));
            out.push(value);
            previous = value;
        }
        return out;
    }

    function countsPerVolt(cfg) {
        return cfg.pitch.dac_counts / (cfg.pitch.dac_vref * cfg.pitch.dac_gain);
    }

    function pitchTable(cfg, rows) {
        var vpo = cfg.pitch.volts_per_octave;
        var scale = countsPerVolt(cfg) * (vpo / GEN.calibrationVoltsPerOctave);
        var offsets = {};
        rows.forEach(function (r) { offsets[r.semitone] = r.cents; });
        var top = Math.max.apply(null, Object.keys(offsets).map(Number));
        for (var i = 0; i <= top; i++) {
            if (!(i in offsets)) throw new Error('calibration has a gap at semitone ' + i);
        }
        if (top + 1 < GEN.pitchTableEntries) {
            throw new Error('calibration has ' + (top + 1) + ' rows, the firmware reads ' +
                            GEN.pitchTableEntries);
        }
        var table = [];
        for (i = 0; i <= top; i++) {
            table.push(floorHalf(scale * (i / 12.0 + offsets[i] / 1200.0)));
        }
        if (table.length !== GEN.pitchTableEntries) {
            throw new Error('Pitch curve has ' + table.length + ' entries, firmware needs ' +
                            GEN.pitchTableEntries + '.');
        }
        for (i = 1; i < table.length; i++) {
            // <=, not <: two adjacent entries at the same DAC count is a
            // semitone that plays its neighbour's pitch, and nothing further
            // down can tell.  The real tables step by 25 counts at the closest.
            if (table[i] < table[i - 1]) throw new Error('Pitch curve is not monotonic. Check the calibration table.');
            if (table[i] === table[i - 1]) throw new Error('Pitch curve repeats a DAC count at semitone ' + i + ' - that semitone would play its neighbour\'s pitch. Check the calibration table.');
        }
        if (table[0] < 0 || table[table.length - 1] > 4095) {
            throw new Error('Pitch curve leaves the 12-bit DAC range.');
        }
        return table;
    }


    // --- Intel HEX ------------------------------------------------------
    function parseHexText(text, name) {
        var memory = {}, upper = 0, startLinear = 0x80002000;
        var lines = text.split('\n');
        for (var n = 0; n < lines.length; n++) {
            var line = lines[n].replace(/[\r\s]+$/, '');
            if (!line) continue;
            if (line.charAt(0) !== ':') {
                throw new Error(name + ' line ' + (n + 1) + ': missing Intel HEX colon');
            }
            var raw = line.slice(1), rec = [];
            for (var i = 0; i + 1 < raw.length; i += 2) rec.push(parseInt(raw.substr(i, 2), 16));
            var sum = 0;
            for (i = 0; i < rec.length; i++) sum += rec[i];
            if (sum & 0xFF) throw new Error(name + ' line ' + (n + 1) + ': bad checksum');
            var length = rec[0], kind = rec[3];
            // Same rule as tools/build.py: the checksum cannot catch a wrong
            // partition, so the declared length is held to the bytes present.
            if (rec.length !== 5 + length) {
                throw new Error(name + ' line ' + (n + 1) + ': declares ' +
                                length + ' data bytes, carries ' + (rec.length - 5));
            }
            var address = (rec[1] << 8) | rec[2];
            var data = rec.slice(4, 4 + length);
            if (kind === 0) {
                for (i = 0; i < data.length; i++) {
                    var loc = upper + address + i;
                    if (loc in memory) {
                        throw new Error('duplicate byte at 0x' + loc.toString(16));
                    }
                    memory[loc] = data[i];
                }
            } else if (kind === 4) {
                upper = ((data[0] << 8) | data[1]) * 0x10000;
            } else if (kind === 5) {
                startLinear = ((data[0] << 24) | (data[1] << 16) |
                               (data[2] << 8) | data[3]) >>> 0;
            } else if (kind === 1) {
                break;
            } else {
                throw new Error(name + ' line ' + (n + 1) + ': record type ' + kind);
            }
        }
        return { memory: memory, startLinear: startLinear };
    }

    function renderHex(memory, startLinear) {
        function record(kind, address, data) {
            data = data || [];          // the end-of-file record carries none
            var body = [data.length, (address >> 8) & 0xFF, address & 0xFF, kind].concat(data);
            var sum = 0;
            for (var i = 0; i < body.length; i++) sum += body[i];
            body.push((-sum) & 0xFF);
            var out = ':';
            for (i = 0; i < body.length; i++) {
                var h = (body[i] & 0xFF).toString(16).toUpperCase();
                out += h.length < 2 ? '0' + h : h;
            }
            return out;
        }
        var addresses = Object.keys(memory).map(Number).sort(function (a, b) { return a - b; });
        var lines = [], index = 0, currentUpper = null;
        while (index < addresses.length) {
            var start = addresses[index], upper = Math.floor(start / 0x10000);
            if (upper !== currentUpper) {
                lines.push(record(4, 0, [(upper >> 8) & 0xFF, upper & 0xFF]));
                currentUpper = upper;
            }
            var chunk = [memory[start]];
            index++;
            while (index < addresses.length && chunk.length < 16) {
                var expected = start + chunk.length;
                if (addresses[index] !== expected || Math.floor(expected / 0x10000) !== upper) break;
                chunk.push(memory[expected]);
                index++;
            }
            lines.push(record(0, start & 0xFFFF, chunk));
        }
        lines.push(record(5, 0, [(startLinear >>> 24) & 0xFF, (startLinear >>> 16) & 0xFF,
                                 (startLinear >>> 8) & 0xFF, startLinear & 0xFF]));
        lines.push(record(1, 0));
        return lines.join('\n') + '\n';
    }

    // --- flags and numbers ----------------------------------------------
    function get(cfg, dotted) {
        return dotted.split('.').reduce(function (n, part) { return n[part]; }, cfg);
    }

    function resolveFlags(cfg) {
        var blocks = {}, features = {};
        Object.keys(GEN.featureMap).forEach(function (setting) {
            var entry = GEN.featureMap[setting];
            var value = get(cfg, setting), expected = GEN.enabledWhen[setting];
            var enabled = value === expected;
            if (setting === 'pressure.multi_key') {
                enabled = (value === 'mean' || value === 'max');
            }
            entry[0].forEach(function (n) { blocks[n] = enabled; });
            entry[1].forEach(function (n) { features[n] = enabled; });
        });
        return { blocks: blocks, features: features };
    }

    // How big one step of the octave controls is, in DAC units: the period
    // every slot repeats at.  Mirrors tools/build.py, including the refusal
    // when the slots disagree - there is one set of octave controls.
    function octaveUnits(cfg) {
        var per = cfg.tuning.units_per_octave, seen = {};
        (cfg._tunings || []).forEach(function (slot) {
            if (slot === 'factory') { seen[per] = true; return; }
            var mapped = !!slot.kbmText;
            var cents = parseScala(slot.text, slot.name, mapped);
            var period = cents.length > 12 ? cents[12] : 1200.0;
            if (mapped) {
                period = cents[parseKbm(slot.kbmText, slot.kbmName, cents).formal];
            }
            seen[floorHalf(period * per / 1200)] = true;
        });
        var keys = Object.keys(seen);
        if (keys.length > 1) {
            throw new Error('the tuning slots disagree about the period: ' +
                keys.join(' and ') + ' units — the octave controls step one ' +
                'period, and there is one set of them for the whole instrument');
        }
        return keys.length ? Number(keys[0]) : per;
    }

    function computeNumbers(cfg) {
        var calib = cfg.pressure.calibration;
        var numbers = {
            pressure_floor_default: calib.floor,
            pressure_ceiling_default: calib.ceiling,
            scan_period_ms: cfg.timing.scan_period_ms,
            proximity_reference: cfg.pressure.proximity_reference,
            factory_gain_shift: cfg.diagnostics.factory_gain_shift,
            black_key_scale_32: floorHalf(cfg.pressure.black_key_scale * 32),
            smoothing_taps: cfg.pressure.smoothing_taps,
            curve_default_level: cfg.pressure.curve.default_level,
            // One more than the top level: the knob maps adc*steps>>10, so the
            // configured default lands at twelve o'clock.
            curve_knob_steps: (cfg.pressure.curve.knob_max_level === undefined
                               ? 31 : cfg.pressure.curve.knob_max_level) + 1,
            resolution_bits: cfg.pressure.resolution_bits,
            multi_key_max: cfg.pressure.multi_key === 'max' ? 1 : 0,
            octave_units: octaveUnits(cfg)
        };
        var span = calib.trim_span;
        if (span !== 128 && span !== 256 && span !== 512) {
            throw new Error('trim_span must be 128, 256 or 512');
        }
        numbers.trim_shift = { 512: 1, 256: 2, 128: 3 }[span];
        numbers.floor_knob_base = Math.max(calib.floor - Math.floor(span / 2), 128);
        numbers.ceiling_knob_base = Math.max(calib.ceiling - Math.floor(span / 2), 128);
        if (calib.floor + 32 > calib.ceiling) {
            throw new Error('ceiling must exceed floor by at least 32');
        }
        numbers.latch_match_tolerance = cfg.arp.latch_match_tolerance;
        // The bottom of the trim range, as a multiplier in 1/256ths - a
        // setting, because it decides whether the knob can still reach the
        // coupling an instrument has when the player's feet leave the floor.
        var kMin = Math.round((calib.trim_min === undefined ? 0.70 : calib.trim_min) * 256);
        var kMax = Math.min(0x180, Math.floor((0x3FF * 256) / calib.ceiling));
        // Same rule as tools/build.py: a range that cannot fit is refused,
        // not floored past the cap that keeps the scaled ceiling under 1023.
        if (kMax - kMin < 0x10) {
            throw new Error('trim_min leaves fewer than the 16 steps the ' +
                            'knob encoding needs below the ceiling cap');
        }
        numbers.trim_scale_span = Math.max(kMax - kMin, 0x10);
        numbers.trim_scale_base = kMin;
        numbers.gate_settle_scans = cfg.timing.gate_settle_scans;
        numbers.blend_slew_shift = cfg.portamento.blend_slew_shift;
        numbers.blend_filter_shift = cfg.portamento.blend_filter_shift;
        numbers.blend_hysteresis = cfg.portamento.blend_hysteresis;
        numbers.blend_slew_taper = cfg.portamento.blend_slew_taper;
        numbers.vibrato_dither = cfg.vibrato.dither;
        var smoothing = cfg.pressure.output_smoothing;
        if (smoothing) numbers.output_interpolation_steps = smoothing;
        return numbers;
    }

    // --- properties -----------------------------------------------------
    function initMarker(blocks, features, numbers, tables) {
        // Same concatenation order as build.py: flags, numbers, tables, then
        // the raw bytes of the assembler source.
        var text = reprSortedItems(blocks) + reprSortedItems(features) +
                   reprSortedItems(numbers) + reprSortedItems(tables);
        var bytes = SHA256.utf8(text);
        var bin = atobShim(GEN.javaSourceBase64);
        for (var i = 0; i < bin.length; i++) bytes.push(bin.charCodeAt(i) & 0xFF);
        var digest = SHA256.hash(bytes);
        var first16 = parseInt(digest.substr(0, 4), 16);
        return 0x1000 + (first16 % 0xDFFE);
    }

    function atobShim(b64) {
        if (typeof atob === 'function') return atob(b64);
        var chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
        var out = '', buffer = 0, bits = 0;
        for (var i = 0; i < b64.length; i++) {
            var c = b64.charAt(i);
            if (c === '=') break;
            var v = chars.indexOf(c);
            if (v < 0) continue;
            buffer = (buffer << 6) | v; bits += 6;
            if (bits >= 8) { bits -= 8; out += String.fromCharCode((buffer >> bits) & 0xFF); }
        }
        return out;
    }

    function writeProperties(configName, blocks, features, numbers, tables) {
        var lines = ['# Generated by tools/build.py. Do not edit.',
                     '# Source: ' + configName];
        Object.keys(blocks).sort().forEach(function (n) {
            lines.push('block.' + n + '=' + (blocks[n] ? 1 : 0));
        });
        Object.keys(features).sort().forEach(function (n) {
            lines.push('feature.' + n + '=' + (features[n] ? 1 : 0));
        });
        Object.keys(numbers).sort().forEach(function (n) {
            lines.push('number.' + n + '=' + numbers[n]);
        });
        Object.keys(tables).sort().forEach(function (n) {
            lines.push('table.' + n + '=' + tables[n].join(','));
        });
        return lines.join('\n') + '\n';
    }

    return {
        pyRepr: pyRepr, reprSortedItems: reprSortedItems, expand: expand,
        parseScala: parseScala, parseKbm: parseKbm, keyPitch: keyPitch,
        factoryTuning: factoryTuning,
        tuningTable: tuningTable, anchorOffset: anchorOffset, pressureCurve: pressureCurve,
        countsPerVolt: countsPerVolt, pitchTable: pitchTable,
        floorHalf: floorHalf, parseHexText: parseHexText, renderHex: renderHex,
        resolveFlags: resolveFlags, computeNumbers: computeNumbers,
        initMarker: initMarker, writeProperties: writeProperties, get: get
    };
})();
if (typeof module !== 'undefined' && module.exports) module.exports = BUILDLIB;
