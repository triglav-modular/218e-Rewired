// Proves the archive the page hands out is one macOS still trusts.
//
// The signed .app cannot be rebuilt entry by entry: change a byte and the seal
// breaks.  ZIP.unpack carries the entries across untouched, and this runs that
// path under the same code the browser runs, writing the result out for ditto
// and spctl to judge.  Run by tools/test_zip.py, which does the judging.
var args = arguments;

// The page's deflate comes from CompressionStream, which jsc does not have;
// stored entries are valid ZIP and are what the fallback already produces.
var Blob = function (parts) { this.parts = parts; };
var CompressionStream = undefined;
// jsc has no TextEncoder; the names here are ASCII, which encodes the same.
var TextEncoder = function () {};
TextEncoder.prototype.encode = function (s) {
    var out = new Uint8Array(s.length), i;
    for (i = 0; i < s.length; i++) out[i] = s.charCodeAt(i) & 0xFF;
    return out;
};

function bytesOf(path) { return new Uint8Array(readFile(path, 'binary')); }

var src = bytesOf(args[0]);
var carried = ZIP.unpack(src);
print('entries ' + carried.length);
carried.forEach(function (e) {
    print('entry ' + e.mode.toString(8) + ' ' + e.raw.length + ' ' +
          String.fromCharCode.apply(null, e.name));
});

ZIP.build([{ name: 'README.txt', data: 'hello\n' },
           { name: 'firmware/218eV3_v369_Rewired_DFU.hex', data: ':00000001FF\n' }],
          ZIP.under('', carried)).then(function (blob) {
    var total = 0, i, j;
    for (i = 0; i < blob.parts.length; i++) total += blob.parts[i].length;
    var out = new Uint8Array(total), at = 0;
    for (i = 0; i < blob.parts.length; i++) {
        out.set(blob.parts[i], at); at += blob.parts[i].length;
    }
    var hex = '', H = '0123456789abcdef';
    for (j = 0; j < out.length; j++) hex += H[out[j] >> 4] + H[out[j] & 15];
    print('ZIPHEX ' + hex);
});
