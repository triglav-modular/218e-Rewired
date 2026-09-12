// Build the page's option combinations plus opt-in persistence, through the same
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
    // The 2.0 features, as a joint dimension rather than two independent
    // ones: what matters is each alone and both together, not their cross
    // with every other flag twice over.
    [[false, false], [true, false], [false, true], [true, true]].forEach(function (sq) {
    [true, false].forEach(function (persist) {
    [true, false].forEach(function (fx) {
    [true, false].forEach(function (po) {
        if (po && !fx) return;   // options.py refuses this pairing
    // The pitch offset rides on the scaling dimension: the two only meet in
    // the pitch table, so the 208c layout at one scaling is the case to cover.
    [[1.2, true], [1.0, true], [1.2, false]].forEach(function (vo) {
        var v = vo[0];
    [null, rows].forEach(function (cal) {
    // The preset quantiser rides on the tunings dimension: it reads the
    // live key table, so with and without a scale installed is the pair to
    // cover, not its cross with every other flag.
    [[0, false], [1, true], [2, false], [3, true]].forEach(function (nq) {
        var nt = nq[0];
        // kn stands in for the old remap switch: every knob at its default
        // role, or every knob on None.
        var o = { latching_arp: arp, pressure_fix: fx,
                  knob1: kn ? 'order' : 'factory', knob2: kn ? 'spacing' : 'factory',
                  knob3: kn ? 'octaves' : 'factory', knob4: kn ? 'vibrato' : 'factory',
                  pressure_portamento: po, volts_per_octave: v, pitch_offset: vo[1],
                  quantize_presets: nq[1],
                  sequencer: sq[0], clock_divide: sq[1], persist: persist };
        // buildlib refuses persist = false unless the caller says it means
        // it.  This matrix is where the unsupported build is characterised.
        if (!persist) o.unsupported_volatile = true;
        if (cal) o.pitch_correction = cal;
        if (nt) o.alternate_tunings = T.slice(0, nt);
        var label = 'arp=' + (arp ? 1 : 0) + ' knobs=' + (kn ? 1 : 0) +
                    ' seq=' + (sq[0] ? 1 : 0) + ' clk=' + (sq[1] ? 1 : 0) +
                    ' persist=' + (persist ? 1 : 0) +
                    ' fix=' + (fx ? 1 : 0) + ' porta=' + (po ? 1 : 0) +
                    ' v=' + v + ' off=' + (vo[1] ? 1 : 0) +
                    ' cal=' + (cal ? 1 : 0) + ' tun=' + nt + ' q=' + (nq[1] ? 1 : 0);
        try {
            var r = WEBBUILD.build(o, factory);
            built++;
            if (shas[r.sha256]) dupes.push(label + ' == ' + shas[r.sha256]);
            shas[r.sha256] = label;
        } catch (e) {
            failed.push(label + '  ->  ' + e.message);
        }
    }); }); }); }); }); }); }); }); });

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
