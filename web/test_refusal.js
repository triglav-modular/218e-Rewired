// The other half of the parity harness: what both builders must REFUSE.
//
// Comparing successful images can only prove the two agree about configs they
// both accept.  A guard missing from one side produces a perfectly ordinary
// image, identical to nothing because the other side never built one - so the
// image comparison stays silent and the page ships a build the CLI would not
// make.  This prints the refusal instead, for web/test_configs.py to match.
var ARGV = (typeof arguments !== 'undefined') ? Array.prototype.slice.call(arguments)
    : (typeof process !== 'undefined' && process.argv ? process.argv.slice(2) : []);
(function () {
    'use strict';
    var options = JSON.parse(readFile(ARGV[0]));
    try {
        var result = WEBBUILD.build(options, readFile(ARGV[1]));
        print('BUILT ' + result.sha256);
    } catch (e) {
        print('REFUSED ' + (e && e.message ? e.message : String(e)).replace(/\n/g, ' '));
    }
})();
