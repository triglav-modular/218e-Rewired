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
    // carried: entries taken whole from another archive by unpack(), already
    // compressed and already carrying their own permissions.  They are passed
    // through rather than rebuilt, which is the point of them.
    function build(files, carried) {
        var entries = files.map(function (f) {
            var raw = typeof f.data === 'string' ? utf8(f.data) : f.data;
            return { name: utf8(f.name), raw: raw, crc: crc32(raw),
                     mode: f.exec ? 0o100755 : 0o100644 };
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
        })).then(function (built) {
            return assemble((carried || []).concat(built));
        });
    }

    // Read the entries out of a ZIP without decompressing them.  A signed .app
    // has to come out of our archive byte for byte or macOS stops trusting it,
    // and re-deflating it would also mean inflating it first - so the stored
    // bytes, the CRC and the mode are copied across exactly as they arrived.
    function unpack(bytes) {
        var d = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
        var end = -1;
        // The end record is last, after a comment of up to 64K.
        for (var i = bytes.length - 22; i >= 0 && i > bytes.length - 22 - 65536; i--) {
            if (d.getUint32(i, true) === 0x06054b50) { end = i; break; }
        }
        if (end < 0) throw new Error('not a ZIP');
        var count = d.getUint16(end + 10, true);
        var at = d.getUint32(end + 16, true);
        var out = [];
        for (var n = 0; n < count; n++) {
            if (d.getUint32(at, true) !== 0x02014b50) throw new Error('bad ZIP directory');
            var nameLen = d.getUint16(at + 28, true);
            var extraLen = d.getUint16(at + 30, true);
            var commentLen = d.getUint16(at + 32, true);
            var comp = d.getUint32(at + 20, true);
            var local = d.getUint32(at + 42, true);
            // The local header's extra field is free to differ from the one in
            // the directory, so the data offset is read from the local header.
            if (d.getUint32(local, true) !== 0x04034b50) throw new Error('bad ZIP entry');
            var from = local + 30 + d.getUint16(local + 26, true)
                                 + d.getUint16(local + 28, true);
            out.push({
                name: bytes.subarray(at + 46, at + 46 + nameLen),
                method: d.getUint16(at + 10, true),
                crc: d.getUint32(at + 16, true),
                body: bytes.subarray(from, from + comp),
                raw: { length: d.getUint32(at + 24, true) },
                mode: (d.getUint32(at + 38, true) >>> 16) || 0o100644
            });
            at += 46 + nameLen + extraLen + commentLen;
        }
        return out;
    }

    // Put every entry of an archive under a folder, so a bundle can be dropped
    // into a download beside the files that go with it.
    function under(prefix, entries) {
        var p = utf8(prefix);
        return entries.map(function (e) {
            var name = new Uint8Array(p.length + e.name.length);
            name.set(p); name.set(e.name, p.length);
            return { name: name, method: e.method, crc: e.crc, body: e.body,
                     raw: e.raw, mode: e.mode };
        });
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
            // otherwise, or whatever mode a carried entry arrived with.  Bit 0
            // of the low half is the DOS read-only flag, left clear.
            c.setUint32(38, (e.mode << 16) >>> 0, true);
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

    return { build: build, crc32: crc32, unpack: unpack, under: under };
})();
