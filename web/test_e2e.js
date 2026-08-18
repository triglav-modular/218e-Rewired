var ARGV = (typeof arguments !== 'undefined') ? Array.prototype.slice.call(arguments)
    : (typeof process !== 'undefined' && process.argv ? process.argv.slice(2) : []);
(function () {
    'use strict';
    var options = JSON.parse(readFile(ARGV[0]));
    var result = WEBBUILD.build(options, readFile(ARGV[1]));
    print('SHA ' + result.sha256);
    print('patches ' + result.patches + '  changed ' + result.changed +
          '  added ' + result.added + '  skipped ' + result.skipped.length);
})();
