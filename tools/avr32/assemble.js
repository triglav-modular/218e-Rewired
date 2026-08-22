// Driver: assemble the whole patch set without Ghidra.
//
//   jsc tools/avr32/encoder.js tools/avr32/runtime.js \
//       tools/avr32/program.js tools/avr32/assemble.js -- build/build.properties
//
// Prints the same EXTENT / BLOCK / SKIP / PATCH records the Ghidra script
// does, so the two can be compared line for line.

// jsc exposes script arguments as a GLOBAL `arguments` object, which a
// wrapping function would shadow — so capture it out here, before the IIFE.
// Node is checked FIRST: its CommonJS wrapper gives every module a local
// `arguments` too (holding exports/require/module, not the CLI), so testing
// for `arguments` alone made the Node fallback read garbage and crash.
var ARGV = (typeof process !== 'undefined' && process.argv)
    ? process.argv.slice(2)
    : (typeof arguments !== 'undefined'
        ? Array.prototype.slice.call(arguments) : []);

(function () {
    'use strict';

    var path = ARGV.length ? ARGV[ARGV.length - 1] : 'build/build.properties';

    var props = {};
    var text = readFile(path);
    var lines = text.split('\n');
    for (var i = 0; i < lines.length; i++) {
        var line = lines[i];
        if (!line || line.charAt(0) === '#') continue;
        var eq = line.indexOf('=');
        if (eq < 0) continue;
        props[line.substring(0, eq).trim()] = line.substring(eq + 1).trim();
    }

    RT.init(props);
    try {
        assembleProgram();
    } catch (e) {
        print('ASSEMBLY FAILED: ' + (e && e.message ? e.message : e));
        throw e;
    }
    var out = RT.output();
    for (var j = 0; j < out.length; j++) print(out[j]);
})();
