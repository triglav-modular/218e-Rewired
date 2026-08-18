// Corpus test: every instruction Ghidra assembled must encode identically.
//
//   jsc tools/avr32/encoder.js tools/avr32/test_corpus.js
//
// Three outcomes per entry:
//   pass — encoder produced Ghidra's exact bytes
//   FAIL — encoder produced different bytes (a real defect; wrong-width
//          encodings shift every following address)
//   skip — encoder returned null, i.e. the shape is not implemented yet
//
// A skip is a to-do.  A FAIL is a bug, and the run reports failure.

(function () {
    'use strict';

    var corpus = JSON.parse(readFile('tools/avr32/corpus.json'));

    function shape(text) {
        return text.replace(/\b(R\d+|LR|PC|SP)\b/g, 'R')
                   .replace(/-?0x[0-9a-fA-F]+/g, 'IMM');
    }

    function hex(bytes) {
        var s = '';
        for (var i = 0; i < bytes.length; i++) {
            var b = (bytes[i] & 0xFF).toString(16);
            s += b.length < 2 ? '0' + b : b;
        }
        return s;
    }

    var stats = {};          // shape -> {pass, fail, skip}
    var failures = [];
    var totals = { pass: 0, fail: 0, skip: 0 };

    for (var i = 0; i < corpus.entries.length; i++) {
        var e = corpus.entries[i];
        var key = shape(e.text);
        if (!stats[key]) stats[key] = { pass: 0, fail: 0, skip: 0 };

        var got = AVR32.encode(parseInt(e.addr, 16), e.text);
        var outcome;
        if (got === null) {
            outcome = 'skip';
        } else if (hex(got) === e.bytes) {
            outcome = 'pass';
        } else {
            outcome = 'fail';
            if (failures.length < 12) {
                failures.push('  ' + e.addr + '  ' + e.text +
                              '\n      ghidra ' + e.bytes + '   encoder ' + hex(got));
            }
        }
        stats[key][outcome]++;
        totals[outcome]++;
    }

    var names = Object.keys(stats).sort(function (a, b) {
        return (stats[b].pass + stats[b].fail + stats[b].skip) -
               (stats[a].pass + stats[a].fail + stats[a].skip);
    });

    print('implemented shapes');
    var covered = 0;
    for (var j = 0; j < names.length; j++) {
        var s = stats[names[j]];
        if (s.pass === 0 && s.fail === 0) continue;
        covered++;
        var mark = s.fail ? 'FAIL' : 'ok  ';
        print('  ' + mark + '  ' + pad(names[j], 26) +
              ' pass ' + s.pass + (s.fail ? '  fail ' + s.fail : ''));
    }

    print('');
    print('not yet implemented (top 10 by volume)');
    var shown = 0;
    for (var k = 0; k < names.length && shown < 10; k++) {
        var t = stats[names[k]];
        if (t.skip === 0) continue;
        print('  ' + pad(names[k], 26) + ' ' + t.skip);
        shown++;
    }

    if (failures.length) {
        print('');
        print('mismatches:');
        for (var f = 0; f < failures.length; f++) print(failures[f]);
    }

    var total = corpus.entries.length;
    print('');
    print('corpus ' + total + ' instructions, ' + names.length + ' shapes');
    print('  encoded ' + (totals.pass + totals.fail) + '  (' +
          pct(totals.pass + totals.fail, total) + '% of corpus, ' +
          covered + ' shapes)');
    print('  passing ' + totals.pass + '  (' + pct(totals.pass, total) + '% of corpus)');
    print('  mismatched ' + totals.fail);
    print('  unimplemented ' + totals.skip);
    print('');
    print(totals.fail === 0 ? 'RESULT: PASS' : 'RESULT: FAIL');

    function pad(s, n) { while (s.length < n) s += ' '; return s; }
    function pct(a, b) { return (100 * a / b).toFixed(1); }
})();
