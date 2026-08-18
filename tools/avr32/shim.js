// Engine compatibility.
//
// jsc (which ships with macOS) provides print(), readFile() and a global
// `arguments`; Node provides none of them.  Assigning onto the global object
// explicitly — rather than `var print = ...` — avoids the hoisting trap where
// the declaration shadows the built-in before the typeof check can see it.
(function (g) {
    if (typeof g.print !== 'function') {
        g.print = function (line) { console.log(line); };
    }
    if (typeof g.readFile !== 'function') {
        g.readFile = function (path) {
            return require('fs').readFileSync(path, 'utf8');
        };
    }
})(typeof globalThis !== 'undefined' ? globalThis : this);
