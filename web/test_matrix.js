// Build every option combination the page can produce, through the same
// guarded path the page uses.  WEBBUILD.build throws on any guard failure -
// flash collision, a patch landing on a factory entry point, a byte changed
// outside a declared patch, or a hex that does not round-trip - so a clean
// return means the image is structurally sound.
//
// It cannot say the firmware behaves correctly when played.  What it does say
// is that no combination of options produces a malformed image, which is the
// class of fault that would matter before an instrument is even booted.
var ARGV = (typeof arguments !== 'undefined') ? Array.prototype.slice.call(arguments) : [];
(function () {
    var factory = readFile(ARGV[0]);
    var rows = [], csv = readFile(ARGV[1]).split('\n');
    for (var i = 0; i < csv.length; i++) {
        var l = csv[i];
        if (!l.trim() || l.charAt(0) === '#' || /^Semitone/i.test(l)) continue;
        var p = l.split(';'), n = parseInt(p[0], 10), c = parseFloat(p[3]);
        if (!isNaN(n) && !isNaN(c)) rows.push({ semitone: n, cents: c });
    }
    var T = GEN.bundledTunings, built = 0, failed = [], shas = {}, dupes = [];
    [true, false].forEach(function (arp) {
    [true, false].forEach(function (kn) {
    [true, false].forEach(function (fx) {
    [true, false].forEach(function (po) {
        if (po && !fx) return;   // options.py refuses this pairing
    [1.2, 1.0].forEach(function (v) {
    [null, rows].forEach(function (cal) {
    [0, 1, 2, 3].forEach(function (nt) {
        var o = { latching_arp: arp, remap_knobs: kn, pressure_fix: fx,
                  pressure_portamento: po, volts_per_octave: v };
        if (cal) o.pitch_correction = cal;
        if (nt) o.alternate_tunings = T.slice(0, nt);
        var label = 'arp=' + (arp ? 1 : 0) + ' knobs=' + (kn ? 1 : 0) +
                    ' fix=' + (fx ? 1 : 0) + ' porta=' + (po ? 1 : 0) +
                    ' v=' + v + ' cal=' + (cal ? 1 : 0) + ' tun=' + nt;
        try {
            var r = WEBBUILD.build(o, factory);
            built++;
            if (shas[r.sha256]) dupes.push(label + ' == ' + shas[r.sha256]);
            shas[r.sha256] = label;
        } catch (e) {
            failed.push(label + '  ->  ' + e.message);
        }
    }); }); }); }); }); }); });

    var distinct = Object.keys(shas).length;
    print(built + ' combinations built, ' + distinct + ' distinct images');
    if (failed.length) {
        print('FAILED to build:\n  ' + failed.join('\n  '));
    }
    if (dupes.length) {
        // Two option sets producing one image means an option changed nothing.
        print('IDENTICAL images from different options:\n  ' + dupes.join('\n  '));
    }
    if (failed.length || dupes.length) {
        throw new Error(failed.length + ' build failure(s), ' + dupes.length + ' collision(s)');
    }
    print('every combination builds and passes the structural guards');
})();
