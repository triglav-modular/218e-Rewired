// Minimal ZIP writer.  The page has no dependencies and is opened from a file:
// URL as often as from a server, so a library is not an option; a stored or
// deflated ZIP is a few hundred lines of header layout.
//
// Deflate comes from CompressionStream where the browser has it, and the
// archive falls back to stored entries where it does not.  Both are valid ZIP;
// the difference is only size.
var ZIP = (function () {
    'use strict';

    var CRC = (function () {
        var t = new Uint32Array(256);
        for (var n = 0; n < 256; n++) {
            var c = n;
            for (var k = 0; k < 8; k++) c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
            t[n] = c >>> 0;
        }
        return t;
    })();

    function crc32(bytes) {
        var c = 0xFFFFFFFF;
        for (var i = 0; i < bytes.length; i++) c = CRC[(c ^ bytes[i]) & 0xFF] ^ (c >>> 8);
        return (c ^ 0xFFFFFFFF) >>> 0;
    }

    function utf8(str) { return new TextEncoder().encode(str); }

    // A fixed timestamp, so the same options always produce the same archive
    // byte for byte — the same property the firmware build has.
    var DOS_TIME = 0x6000;              // 12:00:00
    var DOS_DATE = ((2026 - 1980) << 9) | (1 << 5) | 1;   // 2026-01-01

    // Deflate is an optimisation, never a requirement: every failure path here
    // returns null and the entry is stored instead.  Reading a stream back
    // through Response is the fragile step across browsers - Safari reports a
    // failed body read as "Load failed" - so it is probed once with three
    // bytes before any real data goes near it.
    var deflateProbe = null;
    function canDeflate() {
        if (deflateProbe) return deflateProbe;
        deflateProbe = raw(new Uint8Array([1, 2, 3]))
            .then(function (out) { return !!out; })
            .catch(function () { return false; });
        return deflateProbe;
    }

    function raw(bytes) {
        if (typeof CompressionStream === 'undefined' ||
            typeof Response === 'undefined') return Promise.resolve(null);
        return new Promise(function (resolve) {
            var cs;
            try { cs = new CompressionStream('deflate-raw'); }
            catch (e) { resolve(null); return; }
            var w;
            try { w = cs.writable.getWriter(); } catch (e) { resolve(null); return; }
            // The writer's promises must be handled or a rejection escapes as
            // an unhandled one and the page reports a failure that is not one.
            Promise.resolve(w.write(bytes)).catch(function () {});
            Promise.resolve(w.close()).catch(function () {});
            var done = false;
            var settle = function (v) { if (!done) { done = true; resolve(v); } };
            try {
                new Response(cs.readable).arrayBuffer()
                    .then(function (b) { settle(new Uint8Array(b)); })
                    .catch(function () { settle(null); });
            } catch (e) { settle(null); }
            // A stream that never settles would hang the download for good.
            setTimeout(function () { settle(null); }, 10000);
        });
    }

    function deflate(bytes) {
        return canDeflate().then(function (ok) {
            return ok ? raw(bytes).catch(function () { return null; }) : null;
        }).catch(function () { return null; });
    }

    // files: [{name, data, exec}] where data is a string or Uint8Array.  exec
    // marks a file that must come out of the archive runnable: without the
    // mode in the external attributes, an extracted dfu-programmer has no
    // execute bit and the flasher cannot run it.
    function build(files) {
        var entries = files.map(function (f) {
            var raw = typeof f.data === 'string' ? utf8(f.data) : f.data;
            return { name: utf8(f.name), raw: raw, crc: crc32(raw), exec: !!f.exec };
        });
        return Promise.all(entries.map(function (e) {
            return deflate(e.raw).then(function (packed) {
                // only take the deflated form if it actually helps
                if (packed && packed.length < e.raw.length) {
                    e.body = packed; e.method = 8;
                } else {
                    e.body = e.raw; e.method = 0;
                }
                return e;
            });
        })).then(assemble);
    }

    function assemble(entries) {
        var parts = [], central = [], offset = 0;
        entries.forEach(function (e) {
            var h = new DataView(new ArrayBuffer(30));
            h.setUint32(0, 0x04034b50, true);
            h.setUint16(4, 20, true);
            h.setUint16(6, 0x0800, true);          // names are UTF-8
            h.setUint16(8, e.method, true);
            h.setUint16(10, DOS_TIME, true);
            h.setUint16(12, DOS_DATE, true);
            h.setUint32(14, e.crc, true);
            h.setUint32(18, e.body.length, true);
            h.setUint32(22, e.raw.length, true);
            h.setUint16(26, e.name.length, true);
            h.setUint16(28, 0, true);
            parts.push(new Uint8Array(h.buffer), e.name, e.body);

            var c = new DataView(new ArrayBuffer(46));
            c.setUint32(0, 0x02014b50, true);
            c.setUint16(4, (3 << 8) | 20, true);   // made by: Unix
            c.setUint16(6, 20, true);
            c.setUint16(8, 0x0800, true);
            c.setUint16(10, e.method, true);
            c.setUint16(12, DOS_TIME, true);
            c.setUint16(14, DOS_DATE, true);
            c.setUint32(16, e.crc, true);
            c.setUint32(20, e.body.length, true);
            c.setUint32(24, e.raw.length, true);
            c.setUint16(28, e.name.length, true);
            // Unix mode in the high half: 0100755 for an executable, 0100644
            // otherwise.  Bit 0 of the low half is the DOS read-only flag,
            // left clear.
            c.setUint32(38, ((e.exec ? 0o100755 : 0o100644) << 16) >>> 0, true);
            c.setUint32(42, offset, true);
            central.push(new Uint8Array(c.buffer), e.name);
            offset += 30 + e.name.length + e.body.length;
        });
        var centralSize = central.reduce(function (n, p) { return n + p.length; }, 0);
        var end = new DataView(new ArrayBuffer(22));
        end.setUint32(0, 0x06054b50, true);
        end.setUint16(8, entries.length, true);
        end.setUint16(10, entries.length, true);
        end.setUint32(12, centralSize, true);
        end.setUint32(16, offset, true);
        return new Blob(parts.concat(central, [new Uint8Array(end.buffer)]),
                        { type: 'application/zip' });
    }

    return { build: build, crc32: crc32 };
})();
