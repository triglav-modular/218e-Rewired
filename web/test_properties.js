// Does the JavaScript pipeline produce the same build.properties as Python?
//
// If it does, the image must match too: the assembler consuming those
// properties is already proven byte-identical to Ghidra.
//
// This asks WEBBUILD.build() for the properties rather than working them out
// again.  It used to re-derive the flags itself, which meant every new gate
// had to be added in three places - build.py, web/build.js and here - and the
// third was missed, so the harness failed while the builds were fine.  There
// is one implementation of the gating now and this reads its output.
var ARGV = (typeof arguments !== 'undefined') ? Array.prototype.slice.call(arguments)
    : (typeof process !== 'undefined' && process.argv ? process.argv.slice(2) : []);

(function () {
    'use strict';
    var optionsJson = ARGV[0], factoryPath = ARGV[1], expectedPath = ARGV[2];

    var options = JSON.parse(readFile(optionsJson));
    var got = WEBBUILD.build(options, readFile(factoryPath)).properties;
    var want = readFile(expectedPath);

    // build() names the stock config; Python is run against a temporary copy.
    // The filename on that comment line is not part of what the build means.
    function normalise(t) { return t.replace(/^# Source:.*$/m, '# Source: <config>'); }
    got = normalise(got); want = normalise(want);

    if (got === want) { print('IDENTICAL'); return; }
    print('DIFFERS');
    var a = got.split('\n'), b = want.split('\n'), shown = 0;
    for (var i = 0; i < Math.max(a.length, b.length) && shown < 8; i++) {
        if (a[i] !== b[i]) {
            print('  line ' + (i + 1));
            print('    js:     ' + String(a[i]).substring(0, 100));
            print('    python: ' + String(b[i]).substring(0, 100));
            shown++;
        }
    }
})();
