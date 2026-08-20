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

// AVR32 instruction encoder — the piece that replaces Ghidra's assembler.
//
// Ghidra is used by src/AssemblePressureFix.java for exactly one call:
//
//     assembler.assembleLine(addr(pc), instruction)
//
// This reproduces that call for the instruction subset this firmware actually
// uses.  It does not aim to be a complete AVR32 assembler: it aims to agree
// with Ghidra, byte for byte, on the ~55 operand shapes in the corpus.  Every
// layout below was derived from build/assemble.log rather than from the
// architecture manual, and test_corpus.js proves the agreement.
//
// Unsupported shapes return null rather than guessing.  A wrong encoding of
// the right width would shift nothing and corrupt silently; null fails loudly.

var AVR32 = (function () {
    'use strict';

    // LR/PC/SP are the architectural names for R14/R15/R13.
    var NAMED = { LR: 14, PC: 15, SP: 13 };

    function reg(token) {
        if (Object.prototype.hasOwnProperty.call(NAMED, token)) return NAMED[token];
        var m = /^R(\d{1,2})$/.exec(token);
        if (!m) return null;
        var n = parseInt(m[1], 10);
        return n >= 0 && n <= 15 ? n : null;
    }

    function imm(token) {
        var m = /^(-?)0x([0-9a-fA-F]+)$/.exec(token);
        if (!m) return null;
        var v = parseInt(m[2], 16);
        return m[1] === '-' ? -v : v;
    }

    function half(v) { return [(v >> 8) & 0xFF, v & 0xFF]; }
    function word(v) { return [(v >>> 24) & 0xFF, (v >>> 16) & 0xFF, (v >>> 8) & 0xFF, v & 0xFF]; }

    // Extended forms all share the layout <opcode12> <Rd4> <imm16>.
    function extended(opcode12, rd, value) {
        return word(((opcode12 << 20) | (rd << 16) | (value & 0xFFFF)) >>> 0);
    }

    function fits(value, bits) {
        var limit = 1 << (bits - 1);
        return value >= -limit && value <= limit - 1;
    }

    // Compact register-register form: the source sits in bits 12..9, a
    // per-mnemonic sub-opcode in bits 7..4, the destination in bits 3..0.
    function regreg(sub, rs, rd) {
        return half((rs << 9) | (sub << 4) | rd);
    }

    var RULES = [
        // --- data directives -------------------------------------------
        // Emitted by the word()/halfword() helpers for tables and pool
        // addresses.  Big-endian, like the rest of the image.
        { re: /^\.hword (-?0x[0-9a-fA-F]+)$/, fn: function (m) { return half(imm(m[1]) & 0xFFFF); } },
        { re: /^\.word (-?0x[0-9a-fA-F]+)$/, fn: function (m) { return word(imm(m[1]) >>> 0); } },

        { re: /^NOP$/, fn: function () { return half(0xD703); } },

        // --- MOV -------------------------------------------------------
        // Compact imm8 is SIGNED: 0xa0 (160) does not fit and goes extended,
        // while -0x1 does fit and stays compact.  Getting this backwards
        // changes instruction width and shifts every following address, which
        // padTo()/finish() would catch — but only after the fact.
        {
            re: /^MOV (\S+),(-?0x[0-9a-fA-F]+)$/, fn: function (m) {
                var rd = reg(m[1]), v = imm(m[2]);
                if (rd === null || v === null) return null;
                if (fits(v, 8)) return half(0x3000 | ((v & 0xFF) << 4) | rd);
                // imm21: the low 16 bits sit in the usual place, and the top
                // five SCATTER — k[20:17] into bits 28..25 and k[16] into bit
                // 20 — over a fixed base of 0xE0600000.  The 0xE06 prefix that
                // every 16-bit-immediate MOV shows is just this form with the
                // upper five bits zero.
                //
                // Only non-negative values are accepted: every extended MOV in
                // the corpus is positive, negatives all fitting the compact
                // signed imm8, so a negative imm21 would be an unproven guess.
                if (v >= 0 && v <= 0x1FFFFF) {
                    var up = (v >> 16) & 0x1F;
                    var hi = 0xE0600000 + ((up >> 1) * 0x02000000) +
                             ((up & 1) * 0x00100000) + (rd * 0x10000);
                    return word((hi >>> 0) + (v & 0xFFFF));
                }
                return null;
            }
        },
        {
            re: /^MOV (\S+),(\S+)$/, fn: function (m) {
                var rd = reg(m[1]), rs = reg(m[2]);
                return rd === null || rs === null ? null : regreg(0x9, rs, rd);
            }
        },

        // --- CP.W ------------------------------------------------------
        // Compact immediate here is a signed 6-bit field in bits 9..4.
        {
            re: /^CP\.W (\S+),(-?0x[0-9a-fA-F]+)$/, fn: function (m) {
                var rd = reg(m[1]), v = imm(m[2]);
                if (rd === null || v === null) return null;
                if (fits(v, 6)) return half(0x5800 | ((v & 0x3F) << 4) | rd);
                if (v >= -0x8000 && v <= 0xFFFF) return extended(0xE04, rd, v);
                return null;
            }
        },
        {
            re: /^CP\.W (\S+),(\S+)$/, fn: function (m) {
                var rd = reg(m[1]), rs = reg(m[2]);
                return rd === null || rs === null ? null : regreg(0x3, rs, rd);
            }
        },

        // --- SUB -------------------------------------------------------
        // Three distinct encodings, all reached from the same two-operand
        // text, so the register and the sign both steer the choice:
        //
        //   SUB SP,imm   dedicated stack form; the immediate is word-scaled,
        //                so the field holds imm/4 (SUB SP,0x20 -> 0x208d,
        //                which is also what an unscaled SUB Rd,0x8 would be).
        //   SUB Rd,imm   compact signed imm8, else extended.
        //   extended     opcode 0xE02 for a positive immediate, 0xFE3 for a
        //                negative one.  Both carry the low 16 bits.
        {
            re: /^SUB (\S+),(-?0x[0-9a-fA-F]+)$/, fn: function (m) {
                var rd = reg(m[1]), v = imm(m[2]);
                if (rd === null || v === null) return null;
                if (m[1] === 'SP') {
                    // Only word-aligned adjustments are representable; a
                    // stray byte offset would silently encode as v/4 rounded.
                    if (v % 4 !== 0) return null;
                    var scaled = v / 4;
                    if (!fits(scaled, 8)) return null;
                    return half(0x2000 | ((scaled & 0xFF) << 4) | rd);
                }
                if (fits(v, 8)) return half(0x2000 | ((v & 0xFF) << 4) | rd);
                // Only the observed sub-ranges are proven by the corpus
                // (0..0x1e4 positive, -0x1000..-0xf2 negative); the corpus
                // test is what would catch a value outside them disagreeing.
                if (v >= 0 && v <= 0xFFFF) return extended(0xE02, rd, v);
                if (v >= -0x8000 && v < 0) return extended(0xFE3, rd, v);
                return null;
            }
        },

        // --- loads and stores ------------------------------------------
        // Stores carry no signedness, so the size token is H/B where the
        // loads say UH/SH/UB — the same split tools/build.py notes in its
        // RAM coverage scan.  Longer alternatives come first so UB is not
        // matched as B.
        {
            re: /^(LD|ST)\.(UB|SB|UH|SH|W|H|B) (\S+),(\S+)\[(-?0x[0-9a-fA-F]+)\]$/,
            fn: function (m) {
                if (m[1] !== 'LD') return null;          // loads read Rd,Rb[disp]
                var rd = reg(m[3]), rb = reg(m[4]), disp = imm(m[5]);
                if (rd === null || rb === null || disp === null) return null;
                return mem('LD.' + m[2], rb, disp, rd);
            }
        },
        {
            re: /^(LD|ST)\.(UB|SB|UH|SH|W|H|B) (\S+)\[(-?0x[0-9a-fA-F]+)\],(\S+)$/,
            fn: function (m) {
                if (m[1] !== 'ST') return null;          // stores write Rb[disp],Rs
                var rb = reg(m[3]), disp = imm(m[4]), rs = reg(m[5]);
                if (rb === null || rs === null || disp === null) return null;
                return mem('ST.' + m[2], rb, disp, rs);
            }
        },
        {
            re: /^LD\.(UB|SB|UH|SH|W|H|B) (\S+),(\S+)\[(\S+) << (0x[0-9a-fA-F]+)\]$/,
            fn: function (m) {
                var rd = reg(m[2]), rb = reg(m[3]), ri = reg(m[4]), sh = imm(m[5]);
                if (rd === null || rb === null || ri === null || sh === null) return null;
                return memIndexed('LD.' + m[1], rb, ri, sh, rd);
            }
        },
        {
            re: /^ST\.(UB|SB|UH|SH|W|H|B) (\S+)\[(\S+) << (0x[0-9a-fA-F]+)\],(\S+)$/,
            fn: function (m) {
                var rb = reg(m[2]), ri = reg(m[3]), sh = imm(m[4]), rs = reg(m[5]);
                if (rb === null || ri === null || rs === null || sh === null) return null;
                return memIndexed('ST.' + m[1], rb, ri, sh, rs);
            }
        },

        // Stack pushes and pops of a single register: fixed fields in op3 = 0.
        {
            re: /^ST\.W --(\S+),(\S+)$/, fn: function (m) {
                var rb = reg(m[1]), rs = reg(m[2]);
                return rb === null || rs === null ? null : half((rb << 9) | 0xD0 | rs);
            }
        },
        {
            re: /^LD\.W (\S+),(\S+)\+\+$/, fn: function (m) {
                var rd = reg(m[1]), rb = reg(m[2]);
                return rd === null || rb === null ? null : half((rb << 9) | 0x100 | rd);
            }
        },

        // --- PC-relative -----------------------------------------------
        // Compact BR shares its 0xC000 space with RJMP, split by bit 3: the
        // branch condition occupies bits 2..0 only, so a condition of 8 or
        // above (ls, gt, le, hi) has NO compact form.  That is exactly why
        // AssemblePressureFix.java reaches for BR{ge} over BR{hi} and BR{lt}
        // over BR{le} when it needs two bytes.
        {
            re: /^BR\{(\w+)\} (0x[0-9a-fA-F]+)$/, fn: function (m, pc) {
                var cond = COND[m[1]], target = imm(m[2]);
                if (cond === undefined || target === null) return null;
                var delta = target - pc;
                if (delta % 2 !== 0) return null;
                var slots = delta / 2;
                if (cond < 8 && fits(slots, 8)) {
                    return half(0xC000 | ((slots & 0xFF) << 4) | cond);
                }
                if (!fits(slots, 16)) return null;
                // Positive and negative take different opcodes, as SUB does.
                var top = (slots >= 0 ? 0xE080 : 0xFE90) | cond;
                return word(((top * 0x10000) >>> 0) + (slots & 0xFFFF));
            }
        },
        {
            re: /^RJMP (0x[0-9a-fA-F]+)$/, fn: function (m, pc) {
                var target = imm(m[1]);
                if (target === null) return null;
                var delta = target - pc;
                if (delta % 2 !== 0) return null;
                // 10-bit signed displacement: the low 8 bits sit in the usual
                // field, and the top 2 spill into bits 1..0 — which is why a
                // backwards RJMP reads as 0x..b where a forwards one reads
                // 0x..8.
                var slots = delta / 2;
                if (!fits(slots, 10)) return null;
                var d = slots & 0x3FF;
                return half(0xC000 | ((d & 0xFF) << 4) | 0x8 | ((d >> 8) & 0x3));
            }
        },
        {
            re: /^MCALL PC\[(0x[0-9a-fA-F]+)\]$/, fn: function (m, pc) {
                var target = imm(m[1]);
                if (target === null) return null;
                var delta = target - alignedPC(pc);
                if (delta % 4 !== 0) return null;
                var slots = delta / 4;
                if (!fits(slots, 16)) return null;
                return word((0xF01F0000 >>> 0) + (slots & 0xFFFF));
            }
        },
        // --- ALU -------------------------------------------------------
        {
            re: /^(ADD|SUB|OR) (\S+),(\S+)$/, fn: function (m) {
                var rd = reg(m[2]), rs = reg(m[3]);
                return rd === null || rs === null ? null
                     : regreg(REGREG[m[1]], rs, rd);
            }
        },
        {
            re: /^(ABS|CASTU\.H|CASTS\.H|SR\{EQ\}) (\S+)$/, fn: function (m) {
                var op = UNARY[m[1]], rd = reg(m[2]);
                return op === undefined || rd === null ? null : half((op << 4) | rd);
            }
        },
        {
            re: /^(ASR|LSL|LSR) (\S+),(0x[0-9a-fA-F]+)$/, fn: function (m) {
                var base = SHIFT_IMM[m[1]], rd = reg(m[2]), sh = imm(m[3]);
                if (base === undefined || rd === null || sh === null) return null;
                if (sh < 0 || sh > 31) return null;
                return half(base | ((sh >> 1) << 9) | ((sh & 1) << 4) | rd);
            }
        },
        {
            re: /^(ADD|SUB) (\S+),(\S+),(\S+) << (0x[0-9a-fA-F]+)$/, fn: function (m) {
                var t = TRIADIC[m[1]];
                var rd = reg(m[2]), rs = reg(m[3]), ri = reg(m[4]), sh = imm(m[5]);
                if (rd === null || rs === null || ri === null || sh === null) return null;
                if (sh < 0 || sh > 15) return null;
                return triadic(rs, ri, t[0], sh, rd);
            }
        },
        {
            re: /^(MUL|DIVU|DIVS) (\S+),(\S+),(\S+)$/, fn: function (m) {
                var t = TRIADIC[m[1]];
                var rd = reg(m[2]), rs = reg(m[3]), ri = reg(m[4]);
                if (!t || rd === null || rs === null || ri === null) return null;
                return triadic(rs, ri, t[0], t[1], rd);
            }
        },
        {
            re: /^(LSL|LSR) (\S+),(\S+),(0x[0-9a-fA-F]+)$/, fn: function (m) {
                var rd = reg(m[2]), rs = reg(m[3]), sh = imm(m[4]);
                if (rd === null || rs === null || sh === null) return null;
                if (sh < 0 || sh > 31) return null;
                return dyadic(rs, rd, (m[1] === 'LSL' ? 0x1500 : 0x1600) | sh);
            }
        },
        {
            re: /^(CLZ|CP\.H) (\S+),(\S+)$/, fn: function (m) {
                var ra = reg(m[2]), rb = reg(m[3]);
                if (ra === null || rb === null) return null;
                return dyadic(rb, ra, m[1] === 'CLZ' ? 0x1200 : 0x1900);
            }
        },
        {
            // System-register read.  Only COUNT is proven; any other register
            // name returns null rather than an invented number.
            re: /^MFSR (\S+),(\w+)$/, fn: function (m) {
                var rd = reg(m[1]), sysreg = SYSREG[m[2]];
                if (rd === null || sysreg === undefined) return null;
                return extended(0xE1B, rd, sysreg);
            }
        },
        {
            re: /^(ANDL|ANDH|ORH) (\S+),(0x[0-9a-fA-F]+)$/, fn: function (m) {
                var rd = reg(m[2]), v = imm(m[3]);
                if (rd === null || v === null || v < 0 || v > 0xFFFF) return null;
                var op = { 'ANDL': 0xE01, 'ANDH': 0xE41, 'ORH': 0xEA1 }[m[1]];
                return extended(op, rd, v);
            }
        },
        {
            // Shift by a REGISTER amount, which is the triadic layout rather
            // than the dyadic one the immediate form uses.  Only LSR is
            // proven; LSL by register never appears, so it returns null
            // instead of an encoding guessed from symmetry.
            re: /^LSR (\S+),(\S+),(\w+)$/, fn: function (m) {
                var rd = reg(m[1]), rs = reg(m[2]), rsh = reg(m[3]);
                if (rd === null || rs === null || rsh === null) return null;
                return triadic(rs, rsh, 0x0A, 4, rd);
            }
        },
        {
            // BFEXTU Rd,Rs,offset,width — note Rd takes the HIGH field here,
            // the opposite way round from CLZ/CP.H above.
            re: /^BFEXTU (\S+),(\S+),(0x[0-9a-fA-F]+),(0x[0-9a-fA-F]+)$/,
            fn: function (m) {
                var rd = reg(m[1]), rs = reg(m[2]), off = imm(m[3]), wid = imm(m[4]);
                if (rd === null || rs === null || off === null || wid === null) return null;
                if (off < 0 || off > 31 || wid < 0 || wid > 31) return null;
                return word((((7 << 29) | (rd << 25) | (0x1D << 20) | (rs << 16)) >>> 0) +
                            (0xC000 | (off << 5) | wid));
            }
        },
        // --- register lists --------------------------------------------
        // LDM/STM carry their register list as a 16-bit mask in the low half,
        // so all fourteen "shapes" the corpus reports are one encoding.
        //
        // Only SP has ever been the base here, so bits 19..16 = Rb is an
        // inference rather than a proof — but a well-supported one: that
        // nibble reads exactly 0xd (13, SP) in all 22 forms, and every other
        // extended encoding in this ISA puts a register nibble there.
        {
            re: /^LDM (\w+)\+\+,(.+)$/, fn: function (m) {
                var rb = reg(m[1]), mask = regMask(m[2]);
                if (rb === null || mask === null) return null;
                return word((((0xE3C0 | rb) * 0x10000) >>> 0) + mask);
            }
        },
        {
            re: /^STM --(\w+),(.+)$/, fn: function (m) {
                var rb = reg(m[1]), mask = regMask(m[2]);
                if (rb === null || mask === null) return null;
                return word((((0xEBC0 | rb) * 0x10000) >>> 0) + mask);
            }
        },
        {
            re: /^LDDPC (\S+),(0x[0-9a-fA-F]+)$/, fn: function (m, pc) {
                var rd = reg(m[1]), target = imm(m[2]);
                if (rd === null || target === null) return null;
                var delta = target - alignedPC(pc);
                if (delta % 4 !== 0 || delta < 0) return null;
                var slots = delta / 4;
                if (slots > 0x7F) return null;      // 7-bit field, bits 10..4
                return half(0x4800 | (slots << 4) | rd);
            }
        }
    ];

    // --- ALU -------------------------------------------------------------
    // Compact two-operand sub-opcodes, in the (Rs << 9) | (sub << 4) | Rd form.
    var REGREG = { 'ADD': 0x0, 'SUB': 0x1, 'CP.W': 0x3, 'OR': 0x4, 'MOV': 0x9 };

    // Compact one-operand: (op12 << 4) | Rd.
    var UNARY = { 'ABS': 0x5C4, 'CASTU.H': 0x5C7, 'CASTS.H': 0x5C8, 'SR{EQ}': 0x5F0 };

    // Compact shift-by-immediate.  The count is SPLIT: its high bits sit in
    // 12..9 and its least significant bit in bit 4.
    var SHIFT_IMM = { 'ASR': 0xA140, 'LSL': 0xA160, 'LSR': 0xA180 };

    // Three-operand, sharing the indexed load/store layout exactly:
    // 111 Rs(28..25) Ri(19..16) sub8(15..8) extra4(7..4) Rd(3..0).
    // For ADD/SUB the extra nibble is the shift; for the others it is fixed.
    var TRIADIC = {
        'ADD': [0x00, null], 'SUB': [0x01, null],
        'MUL': [0x02, 4], 'DIVS': [0x0C, 0], 'DIVU': [0x0D, 0]
    };

    // Two-operand extended: 111 Rb(28..25) Ra(19..16) tail16.  Note the
    // operand order is reversed relative to the text -- in `OP Ra,Rb` it is
    // Rb that lands in the high field.
    function dyadic(rb, ra, tail) {
        return word((((7 << 29) | (rb << 25) | (ra << 16)) >>> 0) + (tail & 0xFFFF));
    }

    function triadic(rs, ri, sub8, extra4, rd) {
        return word(((7 << 29) | (rs << 25) | (ri << 16) |
                     (sub8 << 8) | (extra4 << 4) | rd) >>> 0);
    }

    // A register list becomes a 16-bit mask, one bit per register number.
    function regMask(list) {
        var parts = list.split(','), mask = 0;
        for (var i = 0; i < parts.length; i++) {
            var r = reg(parts[i].trim());
            if (r === null) return null;
            mask |= 1 << r;
        }
        return mask;
    }

    // --- PC-relative ---------------------------------------------------
    // Condition codes, proven by the corpus.  Deliberately partial: a
    // condition that has never been assembled here returns null rather than
    // an unverified encoding.
    var COND = { eq: 0, ne: 1, ge: 4, lt: 5, ls: 8, gt: 9, le: 10, hi: 11 };

    // MCALL and LDDPC address their pool word from the CURRENT INSTRUCTION'S
    // WORD-ALIGNED PC, not from its own address.  Half the corpus sites sit at
    // odd halfword addresses, where the two differ — using the raw PC there
    // yields a displacement that is not even a multiple of 4.
    // NB: arithmetic, not `pc & ~3`.  Flash addresses here are above 0x80000000,
    // and JavaScript's bitwise operators coerce to SIGNED int32, which turns
    // 0x80014386 into a negative number and the displacement into nonsense.
    function alignedPC(pc) { return pc - (pc % 4); }

    // Encoding tables for the load/store families, derived from the corpus.
    var DISP_COMPACT = {
        'LD.UB': [0, 24, 1, 7], 'LD.W': [3, 0, 4, 31], 'LD.SH': [4, 0, 2, 7],
        'LD.UH': [4, 8, 2, 7], 'ST.W': [4, 16, 4, 7], 'ST.H': [5, 0, 2, 7],
        'ST.B': [5, 8, 1, 7]
    };
    var DISP_EXTENDED = {
        'LD.W': 15, 'LD.SH': 16, 'LD.UH': 17, 'LD.UB': 19,
        'ST.W': 20, 'ST.H': 21, 'ST.B': 22
    };
    var SYSREG = { 'COUNT': 0x42 };

    var INDEXED = { 'LD.SH': 0x04, 'LD.UH': 0x05, 'LD.UB': 0x07, 'ST.H': 0x0A };

    // Compact base+displacement.  The displacement is scaled by the access
    // width, so it must be non-negative, aligned, and inside the field.
    function memCompact(kind, rb, disp, rr) {
        var t = DISP_COMPACT[kind];
        if (!t) return null;
        var op3 = t[0], base = t[1], scale = t[2], max = t[3];
        if (disp < 0 || disp % scale !== 0) return null;
        var slot = disp / scale;
        if (slot > max) return null;
        return half((op3 << 13) | (rb << 9) | (((base + slot) & 0x1F) << 4) | rr);
    }

    // Extended base+displacement: signed 16-bit, any alignment.
    function memExtended(kind, rb, disp, rr) {
        var sub = DISP_EXTENDED[kind];
        if (sub === undefined) return null;
        if (disp < -0x8000 || disp > 0x7FFF) return null;
        return word((((7 << 29) | (rb << 25) | (sub << 20) | (rr << 16)) >>> 0) +
                    (disp & 0xFFFF));
    }

    function mem(kind, rb, disp, rr) {
        return memCompact(kind, rb, disp, rr) || memExtended(kind, rb, disp, rr);
    }

    // Register-indexed with a shift: Rb[Ri << sh].
    function memIndexed(kind, rb, ri, sh, rr) {
        var sub = INDEXED[kind];
        if (sub === undefined || sh < 0 || sh > 3) return null;
        return word((((7 << 29) | (rb << 25) | (ri << 16) | (sub << 8) |
                      (sh << 4) | rr)) >>> 0);
    }

    // pc is accepted for the PC-relative shapes (branches, LDDPC, MCALL) that
    // are not implemented yet; it is part of the contract from the start so
    // callers never have to change.
    function encode(pc, text) {
        var line = text.replace(/\s+/g, ' ').trim();
        for (var i = 0; i < RULES.length; i++) {
            var m = RULES[i].re.exec(line);
            if (m) {
                var out = RULES[i].fn(m, pc);
                if (out) return out;
            }
        }
        return null;
    }

    return { encode: encode, reg: reg, imm: imm };
})();

if (typeof module !== 'undefined' && module.exports) module.exports = AVR32;

// The assembler DSL that src/AssemblePressureFix.java runs inside Ghidra,
// reimplemented on top of encoder.js.
//
// Faithfulness matters more than elegance here: this has to emit the same
// EXTENT / BLOCK / SKIP / PATCH records, in the same order, with the same
// text, as the Java does — that is what makes the two comparable.

var RT = (function () {
    'use strict';

    var cfg = {};          // build.properties, flattened to key -> string
    var out = [];          // emitted record lines
    var pc = 0, base = 0, bytes = [], listing = [];

    function println(line) { out.push(line); }

    // --- minimal printf ------------------------------------------------
    // Only the conversions the Java actually uses: %08x %04x %02x %x %d %-36s %s
    function fmt(spec) {
        var args = Array.prototype.slice.call(arguments, 1), i = 0;
        return spec.replace(/%(-?)(0?)(\d*)([xds])/g, function (_, left, zero, width, kind) {
            var v = args[i++], s;
            if (kind === 'x') s = (v >>> 0).toString(16);
            else if (kind === 'd') s = String(v);
            else s = String(v);
            width = width ? parseInt(width, 10) : 0;
            while (s.length < width) s = left ? s + ' ' : (zero ? '0' : ' ') + s;
            return s;
        });
    }

    function hex(data) {
        var s = '';
        for (var i = 0; i < data.length; i++) {
            var b = (data[i] & 0xFF).toString(16);
            s += b.length < 2 ? '0' + b : b;
        }
        return s;
    }

    // --- build config ---------------------------------------------------
    // Missing keys default to "1", so an unconfigured run still assembles the
    // complete patch set — same as the Java.
    function on(key) { return cfg[key] !== '0'; }
    function block(name) { return on('block.' + name); }
    function feature(name) { return on('feature.' + name); }

    function number(key, fallback, low, high) {
        var raw = (cfg['number.' + key] || '').trim();
        var value = raw === '' ? fallback : parseInt(raw, 10);
        if (value < low || value > high) {
            throw new Error(fmt('Setting %s must be %d..%d to keep the encoding width: %d',
                                key, low, high, value));
        }
        return value;
    }

    function table(name) {
        var raw = (cfg['table.' + name] || '').trim();
        if (raw === '') throw new Error('Missing table in build config: ' + name);
        return raw.split(',').map(function (p) { return parseInt(p.trim(), 10); });
    }

    function emitTable(name) {
        var v = table(name);
        for (var i = 0; i < v.length; i++) halfword(v[i]);
    }

    // --- emission -------------------------------------------------------
    function begin(address) { base = address; pc = address; bytes = []; listing = []; }

    function record(text, encoded) {
        listing.push(fmt('%08x  %-36s %s', pc, text, hex(encoded)));
        for (var i = 0; i < encoded.length; i++) bytes.push(encoded[i]);
        pc += encoded.length;
    }

    function emit(instruction) {
        var encoded = AVR32.encode(pc, instruction);
        if (encoded === null) {
            throw new Error(fmt('cannot encode at %08x: %s', pc, instruction));
        }
        record(instruction, encoded);
    }

    function word(value) {
        record(fmt('.word 0x%08x', value),
               [(value / 0x1000000) & 0xFF, (value / 0x10000) & 0xFF,
                (value / 0x100) & 0xFF, value & 0xFF]);
    }

    function halfword(value) {
        record(fmt('.hword 0x%04x', value), [(value >>> 8) & 0xFF, value & 0xFF]);
    }

    function padTo(address) {
        if (pc > address) {
            throw new Error(fmt('Code crossed target: pc=%08x target=%08x', pc, address));
        }
        while (pc < address) emit('NOP');
    }

    // EXTENT is printed before the enable check, so the build can spot two
    // caves claiming the same flash even when only one of them is emitted.
    function finish(name, expectedEnd) {
        println(fmt('EXTENT %08x %08x %s', base, expectedEnd, name));
        padTo(expectedEnd);
        if (!block(name)) {
            println('SKIP ' + name + ' (disabled by build config)');
            return;
        }
        println('BLOCK ' + name);
        for (var i = 0; i < listing.length; i++) println(listing[i]);
        println(fmt('PATCH %08x %s ; %s', base, hex(bytes), name));
    }

    function singlePatch(name, address, instruction) {
        var encoded = AVR32.encode(address, instruction);
        if (encoded === null) {
            throw new Error(fmt('cannot encode at %08x: %s', address, instruction));
        }
        if (!block(name)) {
            println('SKIP ' + name + ' (disabled by build config)');
            return;
        }
        println(fmt('PATCH %08x %s ; %s: %s', address, hex(encoded), name, instruction));
    }

    function wordPatch(name, address, value, comment) {
        if (!block(name)) {
            println('SKIP ' + name + ' (disabled by build config)');
            return;
        }
        println(fmt('PATCH %08x %08x ; %s: %s', address, value, name, comment));
    }

    function fixedPatch(name, address, length, instruction) {
        begin(address);
        emit(instruction);
        if (pc > address + length) throw new Error('Instruction does not fit fixed patch');
        padTo(address + length);
        if (!block(name)) {
            println('SKIP ' + name + ' (disabled by build config)');
            return;
        }
        println(fmt('PATCH %08x %s ; %s: %s', address, hex(bytes), name, instruction));
    }

    function init(properties) { cfg = properties; out = []; }

    return {
        init: init, output: function () { return out; }, fmt: fmt,
        on: on, block: block, feature: feature, number: number,
        table: table, emitTable: emitTable, begin: begin, emit: emit,
        word: word, halfword: halfword, padTo: padTo, finish: finish,
        singlePatch: singlePatch, wordPatch: wordPatch, fixedPatch: fixedPatch,
        println: println
    };
})();

// The transpiled program calls these bare, exactly as the Java does.
var block = RT.block, feature = RT.feature, number = RT.number, table = RT.table,
    emitTable = RT.emitTable, begin = RT.begin, emit = RT.emit, word = RT.word,
    halfword = RT.halfword, padTo = RT.padTo, finish = RT.finish,
    singlePatch = RT.singlePatch, wordPatch = RT.wordPatch,
    fixedPatch = RT.fixedPatch, println = RT.println;
function StringFormat() { return RT.fmt.apply(null, arguments); }

// GENERATED by tools/avr32/transpile.py from src/AssemblePressureFix.java
// Do not edit: change the Java and re-run the transpiler.
function assembleProgram() {

        // Ordinary knob 3 trims the pressure floor around the hardcoded
        // default: floor = (knob >> 2) + 452, i.e. 452..707 with exactly 580
        // at the center of travel.  The
        // low half of state+0x33c holds the full-pressure endpoint; the high
        // half holds the floor. Pad-3 + knob 3 retains its original behavior.
        begin(0x80014300);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("MOV R11,R12");
        emit("LDDPC R10,0x80014378");
        emit("LD.W R8,R10[0x34]");
        emit("CP.W R8,0x3");
        emit("BR{ne} 0x80014320");
        emit("MOV R12,R11");
        emit("MCALL PC[0x8001437c]");
        emit("RJMP 0x80014374");
        padTo(0x80014320);
        emit("CP.W R8,0x0");
        emit("BR{ne} 0x80014374");
        emit("LD.UH R8,R10[0x30e]");
        emit(StringFormat("LSR R8,0x%x", number("trim_shift", 2, 1, 4)));
        emit(StringFormat("SUB R8,-0x%x", number("floor_knob_base", 0x1c4, 0x80, 0x7d0)));
        emit("LD.W R9,R10[0x33c]");
        emit("BFEXTU R11,R9,0x0,0x10");
        emit("CP.W R11,0x20");
        emit("BR{lt} 0x80014346");
        emit("CP.W R11,0x3ff");
        emit("BR{ls} 0x8001434a");
        padTo(0x80014346);
        emit(StringFormat("MOV R11,0x%x", number("pressure_ceiling_default", 0x348, 0x80, 0x7d0)));
        padTo(0x8001434a);
        emit("MOV R12,R11");
        emit("SUB R12,0x20");
        emit("CP.W R8,R12");
        emit("BR{ls} 0x80014356");
        emit("MOV R8,R12");
        padTo(0x80014356);
        emit("LSL R8,0x10");
        emit("OR R8,R11");
        emit("ST.W R10[0x33c],R8");
        emit("LD.UB R9,R10[0x2db]");
        emit("MOV R8,R9");
        emit("BFEXTU R8,R8,0x5,0x3");
        emit("CP.W R8,0x5");
        emit("BR{eq} 0x80014374");
        emit(StringFormat("MOV R9,0x%x", 0xa0 | number("curve_default_level", 0x1f, 0x0, 0x1f)));
        emit("ST.B R10[0x2db],R9");
        padTo(0x80014374);
        emit("LDM SP++,R7,PC");
        padTo(0x80014378);
        word(0x00003560); // global state base
        word(0x800040c8); // original knob-3 handler
        finish("knob3_pressure_floor", 0x80014380);

        // Knob 4 handler. Preserve its old behavior for internal mode 4;
        // otherwise encode curve=(ADC>>5) and marker 101 in velocity-min byte.
        //
        // The level is taken from where the knob is, not from a value anyone
        // set: mode 0 is "no pads held", so this writes on an ordinary sweep.
        // Removing it once made the response linear for everyone, which is
        // only what an instrument sitting at level 0 already had.
        begin(0x80014380);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("LDDPC R10,0x800143f8");
        emit("LD.W R8,R10[0x34]");
        emit("CP.W R8,0x4");
        emit("BR{ne} 0x800143a4");
        emit("MOV R12,0x5");
        emit("MCALL PC[0x800143fc]");
        emit("RJMP 0x800143ee");
        padTo(0x800143a4);
        emit("CP.W R8,0x0");
        emit("BR{ne} 0x800143ee");
        emit("LD.UH R9,R10[0x310]");
        emit("LSR R9,0x5");
        emit("MOV R11,0xa0");
        emit("OR R9,R11");
        emit("ST.B R10[0x2db],R9");
        padTo(0x800143ee);
        emit("LDM SP++,R7,PC");
        padTo(0x800143f8);
        word(0x00003560); // global state base
        word(0x80004070); // original special-mode knob-4 handler
        finish("knob4_curve", 0x80014400);

        // Note-on wrapper: perform the original key initialization, then
        // clear the raw-filter sample count so the growing average restarts.
        begin(0x80018d00);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("MCALL PC[0x8001ac80]");
        if (feature("arp_latch")) {
            // A press of an already-latched key returns -1 and the note-on is
            // skipped, which is what makes the keys behave as toggles.
            emit("MCALL PC[0x80018d3c]");
            emit("CP.W R12,-0x1");
            emit("BR{eq} 0x80018d2a");
        }
        emit("ST.W --SP,R12");
        emit("MCALL PC[0x80018d30]");
        emit("LDDPC R9,0x80018d34");
        emit("MOV R8,0x0");
        emit("ST.H R9[0x0],R8");
        emit("LD.W R12,SP++");
        emit("MCALL PC[0x80018d38]");
        padTo(0x80018d2a);
        emit("LDM SP++,R7,PC");
        padTo(0x80018d30);
        word(0x80005a04); // original note-on initialization
        word(0x00006080); // raw-filter sample count
        word(0x8001a020); // press-order list append
        word(0x8001a930); // pitch-aware latch toggle (stamps on proceed)
        finish("note_on_reset_raw_filter", 0x80018d40);

        // Release/source-selection wrapper. Preserve the selected-key return
        // value while clearing the sample count for the growing average.
        begin(0x80018d40);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("MCALL PC[0x8001ac80]");
        emit("MCALL PC[0x80018d70]");
        emit("MOV R8,R12");
        emit("LDDPC R9,0x80018d74");
        emit("MOV R10,0x0");
        emit("ST.H R9[0x0],R10");
        emit("MOV R12,R8");
        emit("LDM SP++,R7,PC");
        padTo(0x80018d70);
        word(0x8000596c); // original active-key selector
        word(0x00006080); // raw-filter sample count
        finish("source_change_reset_raw_filter", 0x80018d80);

        // One entry per possible normalized raw pressure count. The shape is
        // the 218r's half-decade exponential: zero exactly at the floor, then
        // a ~32% onset step and a smooth 10 dB rise to full pressure. Values
        // exceed the linear ramp, so the blend must use an arithmetic shift.
        begin(0x80018d80);
        emitTable("pressure_curve");
        // One sentinel repeat of the last entry: the interpolating lookup
        // reads table[i+1], and at full scale i is the final index.  A
        // sentinel is cheaper and safer than a bounds test in the hot path.
        {
            var curveTable = table("pressure_curve");
            halfword(curveTable[curveTable.length - 1]);
        }
        finish("half_decade_exponential_curve_table", 0x800194a8);

        // Ordinary knob 1 trims the full-pressure endpoint around the
        // hardcoded default: ceiling = (knob >> 2) + 712, i.e. 712..967 with
        // exactly 840 at the center of travel. Internal mode 6 retains the
        // factory key-contact sensitivity adjustment.
        begin(0x800194c0);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("MOV R11,R12");
        emit("LDDPC R10,0x80019570");
        emit("LD.W R8,R10[0x34]");
        emit("CP.W R8,0x6");
        emit("BR{ne} 0x800194dc");
        emit("MOV R12,R11");
        emit("MCALL PC[0x80019574]");
        emit("RJMP 0x80019568");
        padTo(0x800194dc);
        emit("CP.W R8,0x0");
        emit("BR{ne} 0x80019568");
        if (feature("pressure_trim_scale")) {
            // One knob for the whole calibration.  Capacitive coupling scales
            // the entire signal — lifting your feet off the floor costs about
            // 30% of it — so the useful control multiplies floor and ceiling
            // together rather than moving either endpoint on its own.
            // k runs 128..383 with 256 as unity, i.e. 0.5x to ~1.5x.
            // k = 128 + adc * span / 1024, where the build sizes `span` so the
            // scaled ceiling can never reach the 1023 validity limit.  A fixed
            // 0.5x..1.5x range would pin the ceiling partway up the knob while
            // the floor kept rising, narrowing the window instead of moving it.
            emit("LD.UH R8,R10[0x30a]");
            emit(StringFormat("MOV R9,0x%x", number("trim_scale_span", 0x100, 0x10, 0x100)));
            emit("MUL R8,R8,R9");
            emit("LSR R8,0xa");
            emit("SUB R8,-0x80");
            emit(StringFormat("MOV R9,0x%x", number("pressure_ceiling_default", 0x348, 0x80, 0x7d0)));
            emit("MUL R9,R9,R8");
            emit("LSR R9,0x8");
            emit(StringFormat("MOV R11,0x%x", number("pressure_floor_default", 0x244, 0x80, 0x7d0)));
            emit("MUL R11,R11,R8");
            emit("LSR R11,0x8");
            emit("MOV R8,R9");
            emit("MOV R12,0x3ff");
            emit("CP.W R8,R12");
            emit("BR{ls} 0x80019518");
            emit("MOV R8,R12");
        } else {
            emit("LD.UH R8,R10[0x30a]");
            emit(StringFormat("LSR R8,0x%x", number("trim_shift", 2, 1, 4)));
            emit(StringFormat("SUB R8,-0x%x", number("ceiling_knob_base", 0x2c8, 0x80, 0x7d0)));
            emit("LD.W R9,R10[0x33c]");
            emit("LSR R11,R9,0x10");
            emit("CP.W R11,0x3ff");
            emit("BR{ls} 0x80019518");
            emit(StringFormat("MOV R11,0x%x", number("pressure_floor_default", 0x244, 0x80, 0x7d0)));
        }
        padTo(0x80019518);
        emit("MOV R12,R11");
        emit("SUB R12,-0x20");
        emit("CP.W R8,R12");
        emit("BR{ge} 0x80019526");
        emit("MOV R8,R12");
        padTo(0x80019526);
        emit("LSL R11,0x10");
        emit("OR R8,R11");
        emit("ST.W R10[0x33c],R8");
        emit("LD.UB R9,R10[0x2db]");
        emit("MOV R8,R9");
        emit("BFEXTU R8,R8,0x5,0x3");
        emit("CP.W R8,0x5");
        emit("BR{eq} 0x80019568");
        emit(StringFormat("MOV R9,0x%x", 0xa0 | number("curve_default_level", 0x1f, 0x0, 0x1f)));
        emit("ST.B R10[0x2db],R9");
        padTo(0x80019568);
        emit("LDM SP++,R7,PC");
        padTo(0x80019570);
        word(0x00003560); // global state base
        word(0x80004188); // original knob-1/key-sensitivity handler
        finish("knob1_pressure_ceiling", 0x80019580);

        // Average the raw signal before all nonlinear processing. Then map the
        // saved [floor, ceiling] interval onto 0..913, apply the knob-4 curve,
        // and finally expand to the full 12-bit pressure output. There is no
        // gain multiplication after this function.
        begin(0x80019580);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        // Prep cave: black-key scaling, and the debug A/B factory law.
        // Returns R9=0 to continue with our law (R12 = scaled raw), or R9=1
        // when the factory law already produced the final value — as a
        // FLOAT, because the caller feeds our return through the factory's
        // float-to-int helper; returning a raw integer here reads as a
        // denormal ~1e-42 and truncates to zero output.
        emit("MCALL PC[0x80019734]");
        emit("CP.W R9,0x0");
        emit("BR{eq} 0x800195a8");
        emit("LDM SP++,R7,PC");
        padTo(0x800195a8);
        emit("MOV R8,R12");
        // The proximity estimate is subtracted per key inside the shared pass,
        // in the raw domain and before the colour correction — subtracting it
        // here, after aggregation and scaling, left a residual on black keys.
        padTo(0x800195bc);
        // Growing average, now variable-depth (8..24 taps, edit knob 2) and
        // relocated to RAM 0x6050 — see the variable_filter cave.
        emit("MOV R12,R8");
        emit("MCALL PC[0x80019738]");
        emit("MOV R8,R12");
        padTo(0x80019600);

        emit("LDDPC R12,0x80019728");
        emit("LD.UB R11,R12[0x2db]");
        emit("MOV R7,R11");
        emit("BFEXTU R7,R7,0x5,0x3");
        emit("CP.W R7,0x5");
        emit("BR{eq} 0x80019628");
        emit(StringFormat("MOV R7,0x%x", number("curve_default_level", 0x1f, 0x0, 0x1f)));
        emit(StringFormat("MOV R10,0x%x", number("pressure_floor_default", 0x244, 0x80, 0x7d0)));
        emit(StringFormat("MOV R9,0x%x", number("pressure_ceiling_default", 0x348, 0x80, 0x7d0)));
        emit("RJMP 0x80019670");
        padTo(0x80019628);
        emit("BFEXTU R7,R11,0x0,0x5");
        emit("LD.W R9,R12[0x33c]");
        emit("LSR R10,R9,0x10");
        emit("BFEXTU R9,R9,0x0,0x10");
        emit("CP.W R10,0x3ff");
        emit("BR{hi} 0x80019666");
        emit("CP.W R9,0x20");
        emit("BR{lt} 0x80019666");
        emit("CP.W R9,0x3ff");
        emit("BR{hi} 0x80019666");
        emit("MOV R11,R9");
        emit("SUB R11,R10");
        emit("CP.W R11,0x1f");
        // Signed, not BR{hi}.  These two are read back from settings and are
        // only ever written in order, but nothing in the flash says so: stored
        // reversed - a floor of 900 against a ceiling of 500 - the subtraction
        // wraps to 0xfffffe70, which is comfortably "higher" than 31 unsigned,
        // and the pair is taken as a valid span.  What the instrument does
        // then is switch between nothing and everything within a few counts.
        // Read as signed the difference is -400 and the defaults load.
        emit("BR{gt} 0x80019670");
        padTo(0x80019666);
        emit(StringFormat("MOV R10,0x%x", number("pressure_floor_default", 0x244, 0x80, 0x7d0)));
        emit(StringFormat("MOV R9,0x%x", number("pressure_ceiling_default", 0x348, 0x80, 0x7d0)));
        padTo(0x80019670);
        // The chain below runs in fixed point with `resolution_bits`
        // fractional bits, so the transfer function is unchanged — it is the
        // same mapping sampled finely instead of once per raw count.  With
        // bits = 0 every shift disappears and this is the original integer
        // arithmetic exactly.
        var BITS = number("resolution_bits", 4, 0, 4);
        var SPAN = 0x391 << BITS;
        if (BITS > 0) {
            emit(StringFormat("LSL R10,0x%x", BITS));
            emit(StringFormat("LSL R9,0x%x", BITS));
        }
        emit("CP.W R8,R10");
        emit("BR{hi} 0x80019686");
        emit("MOV R8,0x0");
        emit("RJMP 0x800196ac");
        padTo(0x80019686);
        emit("CP.W R8,R9");
        emit("BR{lt} 0x80019698");
        emit(StringFormat("MOV R8,0x%x", SPAN));
        emit("RJMP 0x800196ac");
        padTo(0x80019698);
        emit("SUB R8,R10");
        emit("SUB R9,R10");
        emit("MOV R10,0x391");
        emit("MUL R8,R10,R8");
        if (BITS > 0) {
            emit(StringFormat("LSR R9,0x%x", BITS));
        }
        emit("DIVU R8,R8,R9");
        padTo(0x800196ac);

        if (!feature("error_diffusion")) {
            emit("CP.W R7,0x0");
            emit("BR{eq} 0x800196f4");
        }
        // With diffusion the level-0 shortcut is dropped: at level 0 the blend
        // weight is zero, so the same path produces exactly n, and both paths
        // must reach the quantiser with the extra fractional bits in place.
        emit("LDDPC R12,0x8001972c");
        if (BITS > 0) {
            // Interpolate the curve between adjacent table entries, so the
            // fractional part survives the lookup.  The index is clamped
            // below the last entry before its neighbour is read.
            emit(StringFormat("LSR R11,R8,0x%x", BITS));
            emit(StringFormat("BFEXTU R10,R8,0x0,0x%x", BITS));
            emit("LSL R11,0x1");
            emit("ADD R12,R11");
            emit("LD.UH R9,R12[0x0]");
            emit("LD.UH R11,R12[0x2]");
            emit("SUB R11,R9");
            emit("MUL R11,R11,R10");
            emit(StringFormat("LSL R9,0x%x", BITS));
            emit("ADD R9,R11");
        } else {
            emit("LD.UH R9,R12[R8 << 0x1]");
        }
        emit("SUB R9,R8,R9 << 0x0");
        emit("MOV R10,R7");
        emit("LSR R11,R10,0x2");
        emit("LSL R10,0x3");
        emit("ADD R10,R11");
        emit("MUL R9,R10,R9");
        if (feature("error_diffusion")) {
            // Carry the blend four bits further before it is rounded off.
            // ((n-cv)*k*16 + 128) >> 8 is ((n-cv)*k + 8) >> 4, so the wider
            // result costs one shift.  This is where the resolution was going:
            // the >>8 alone collapsed 2409 distinct levels to 1933.
            emit("SUB R9,-0x8");
            emit("ASR R9,0x4");
            emit("LSL R8,0x4");
        } else {
            emit("SUB R9,-0x80");
            emit("ASR R9,0x8");
        }
        emit("SUB R8,R9");
        padTo(0x800196f4);
        emit("MOV R9,0xfff");
        emit("MUL R8,R9,R8");
        if (feature("error_diffusion")) {
            // First-order error diffusion.  What the division throws away is
            // carried into the next scan instead of being discarded, so the
            // output's time average tracks the true value to far better than
            // one code.  DIVU leaves the remainder in the register above the
            // quotient, so the error costs nothing to obtain.
            emit("MOV R10,0x6094");
            emit("LD.W R11,R10[0x0]");
            emit("ADD R8,R11");
            emit(StringFormat("MOV R9,0x%x", SPAN << 4));
            emit("DIVU R8,R8,R9");
            emit("ST.W R10[0x0],R9");
        } else {
            emit(StringFormat("SUB R8,-0x%x", SPAN / 2));
            emit(StringFormat("MOV R9,0x%x", SPAN));
            emit("DIVU R8,R8,R9");
        }
        emit("MOV R12,R8");
        emit("MCALL PC[0x80019730]");
        emit("MCALL PC[0x80019724]");
        emit("LDM SP++,R7,PC");
        padTo(0x80019720);
        word(0x00003216); // pressure-history RAM: eight taps, count at +0x10
        word(0x80013350); // original signed-int-to-float helper
        word(0x00003560); // global state base
        word(0x80018d80); // full-resolution curve table
        word(0x80019740); // edit-mode USB pressure telemetry
        word(0x8001a790); // prep: black-key scale + debug A/B
        word(0x8001a800); // variable-depth growing average
        finish("calibrated_pressure_curve", 0x8001973c);

        // Rate-limited calibration telemetry.  This function is called after
        // the pressure result has been calculated, so it cannot alter the
        // pressure path.  It recomputes the observable intermediate values
        // from the sixteen raw history taps and returns the original result.
        // Telemetry is emitted only while edit mode and USB MIDI are active.
        begin(0x80019740);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("SUB SP,0x18");
        emit("ST.H R7[-0x2],R12");       // final 12-bit output
        emit("LDDPC R10,0x80019930");    // global state
        emit("LD.UB R8,R10[0x39]");      // edit-mode flag
        emit("CP.W R8,0x1");
        emit("BR{ne} 0x80019910");
        emit("LD.UB R8,R10[0x349]");     // USB MIDI enabled
        emit("CP.W R8,0x0");
        emit("BR{eq} 0x80019910");
        emit("LDDPC R9,0x80019934");     // private telemetry divider
        emit("LD.UB R8,R9[0x0]");
        emit("SUB R8,-0x1");
        emit("ST.B R9[0x0],R8");
        emit("ANDL R8,0x1f");            // one frame per 32 calculations
        emit("BR{ne} 0x80019910");

        // Capture the newest baseline-subtracted sample and the growing
        // average, both rounded to whole counts.  The pressure path itself
        // runs in fixed point, so these are a diagnostic approximation of it,
        // not the values it computes.
        emit("LDDPC R12,0x8001992c");
        // With the ring buffer the newest sample is not tap[0]; the filter
        // publishes it separately.  The mean below is order-independent, so
        // summing the first `count` taps stays correct either way.
        emit("MOV R8,0x608c");
        emit("LD.UH R8,R8[0x0]");
        emit("ST.H R7[-0x4],R8");        // instantaneous raw
        emit("LD.UH R11,R12[0x30]");     // growing-average sample count
        emit("MOV R9,0x0");
        emit("CP.W R11,0x0");
        emit("BR{ne} 0x80019790");
        emit("MOV R8,0x0");              // no samples yet: report average 0
        emit("RJMP 0x800197bc");
        padTo(0x80019790);
        emit("CP.W R11,0x18");
        emit("BR{ls} 0x800197a0");
        emit("MOV R11,0x18");
        padTo(0x800197a0);
        emit("LD.UH R8,R12[0x0]");
        emit("ADD R9,R8");
        emit("SUB R12,-0x2");
        emit("SUB R11,0x1");
        emit("BR{ne} 0x800197a0");
        emit("LDDPC R12,0x8001992c");
        emit("LD.UH R11,R12[0x30]");
        emit("CP.W R11,0x18");
        emit("BR{ls} 0x800197b8");
        emit("MOV R11,0x18");
        padTo(0x800197b8);
        emit("DIVU R8,R9,R11");          // quotient to R8 (even destination)
        padTo(0x800197bc);
        emit("ST.H R7[-0x6],R8");        // averaged raw (exact mean of n taps)

        // Resolve the same persisted curve/floor/ceiling validation and
        // normalization as the pressure calculation itself.
        emit("LD.UB R8,R10[0x2db]");
        emit("MOV R11,R8");
        emit("BFEXTU R11,R11,0x5,0x3");
        emit("CP.W R11,0x5");
        emit("BR{ne} 0x80019820");
        emit("ANDL R8,0x1f");
        emit("ST.B R7[-0xd],R8");        // curve level
        emit("LD.W R8,R10[0x33c]");
        emit("LSR R11,R8,0x10");        // floor
        emit("BFEXTU R9,R8,0x0,0x10");  // ceiling
        emit("CP.W R11,0x3ff");
        emit("BR{hi} 0x80019820");
        emit("CP.W R9,0x20");
        emit("BR{lt} 0x80019820");
        emit("CP.W R9,0x3ff");
        emit("BR{hi} 0x80019820");
        emit("MOV R8,R9");
        emit("SUB R8,R11");
        emit("CP.W R8,0x1f");
        emit("BR{gt} 0x80019834");   // signed, for the reason above
        padTo(0x80019820);
        emit(StringFormat("MOV R11,0x%x", number("curve_default_level", 0x1f, 0x0, 0x1f)));
        emit("ST.B R7[-0xd],R11");
        emit(StringFormat("MOV R11,0x%x", number("pressure_floor_default", 0x244, 0x80, 0x7d0)));
        emit(StringFormat("MOV R9,0x%x", number("pressure_ceiling_default", 0x348, 0x80, 0x7d0)));
        padTo(0x80019834);
        emit("ST.H R7[-0xa],R11");       // applied floor
        emit("ST.H R7[-0xc],R9");        // applied ceiling
        emit("LD.UH R8,R7[-0x6]");
        emit("CP.W R8,R11");
        emit("BR{hi} 0x80019854");
        emit("MOV R8,0x0");
        emit("RJMP 0x80019878");
        padTo(0x80019854);
        emit("CP.W R8,R9");
        emit("BR{lt} 0x80019864");
        emit("MOV R8,0x391");
        emit("RJMP 0x80019878");
        padTo(0x80019864);
        emit("SUB R8,R11");
        emit("SUB R9,R11");
        emit("MOV R11,0x391");
        emit("MUL R8,R11,R8");
        emit("DIVU R8,R8,R9");
        padTo(0x80019878);
        emit("ST.H R7[-0x8],R8");        // normalized 0..913

        // Capture the two absolute scan components that the factory scanner
        // subtracts to produce the active key's live raw pressure.  A weighted
        // subtraction may reject proximity better than the factory 1:1
        // subtraction; telemetry measures that possibility without changing
        // the pressure path.
        emit("LDDPC R12,0x80019930");
        if (feature("scan_profiler")) {

            // Diagnostic build: the two scan-component fields carry the
            // profiler's numbers instead.  CC 114/115 is the worst single
            // dispatch in cycles/32, CC 116/117 the CPU load in tenths of a
            // percent.
            emit("MOV R10,0x6032");
            emit("LD.UH R8,R10[0x0]");
            emit("ST.H R7[-0x10],R8");
            emit("LD.UH R8,R10[0x2]");
            emit("ST.H R7[-0x12],R8");
        } else if (feature("latch_probe")) {

            // Diagnostic: what the latch toggle saw on its last press.
            // CC 114/115 is the live transpose it built the pressed pitch
            // from (RAM 0x609A), CC 116/117 the pressed pitch itself
            // (0x609C).  Press the same key repeatedly with the arp running:
            // if the transpose moves while the key does not, the shared term
            // is drifting; if it holds and the press still fails to match, a
            // stamp is wrong instead.
            emit("MOV R10,0x609a");
            emit("LD.UH R8,R10[0x0]");      // as of the last latch press
            emit("ST.H R7[-0x10],R8");
            emit("MOV R10,0x60a0");
            emit("LD.UH R8,R10[0x0]");      // live now
            emit("ST.H R7[-0x12],R8");
        } else if (feature("telemetry_smoothing")) {

            // Diagnostic: the two scan-component fields carry the live
            // smoothing state instead — CC 114/115 the filter depth in taps,
            // CC 116/117 the interpolator shift.  Turning edit knob 2 must
            // move both, or the knob path is broken.
            // scan A = filter depth in taps, scan B = interpolator shift —
            // confirms the configured smoothing is what actually runs.
            emit("MOV R10,0x6082");
            emit("LD.UH R8,R10[0x0]");
            emit("ST.H R7[-0x10],R8");
            emit("LD.UH R8,R10[0x2]");
            emit("ST.H R7[-0x12],R8");
        } else {
            emit("LD.UB R10,R12[0x256]");     // active key index, 0..28
            emit("LDDPC R11,0x80019938");     // key-to-scan-channel map
            emit("LD.UB R10,R11[R10 << 0x0]");
            emit("LSL R10,0x1");
            emit("ADD R12,R12,R10 << 0x0");
            emit("LD.UH R8,R12[0x86]");
            emit("ST.H R7[-0x10],R8");       // scan component A
            emit("LD.UH R8,R12[0xd6]");
            emit("ST.H R7[-0x12],R8");       // scan component B
        }

        // USB-MIDI channel 16, undefined CC range 102..118.  CC 118 is the
        // frame terminator, letting the receiver discard partial frames.
        emit("LD.UH R12,R7[-0x4]");
        emit("MOV R11,0x66");
        emit("MCALL PC[0x80019924]");
        emit("LD.UH R12,R7[-0x6]");
        emit("MOV R11,0x68");
        emit("MCALL PC[0x80019924]");
        emit("LD.UH R12,R7[-0x8]");
        emit("MOV R11,0x6a");
        emit("MCALL PC[0x80019924]");
        emit("LD.UH R12,R7[-0x2]");
        emit("MOV R11,0x6c");
        emit("MCALL PC[0x80019924]");
        emit("LD.UH R12,R7[-0xa]");
        emit("MOV R11,0x6e");
        emit("MCALL PC[0x80019924]");
        emit("LD.UH R12,R7[-0xc]");
        emit("MOV R11,0x70");
        emit("MCALL PC[0x80019924]");
        emit("LD.UH R12,R7[-0x10]");
        emit("MOV R11,0x72");
        emit("MCALL PC[0x80019924]");
        emit("LD.UH R12,R7[-0x12]");
        emit("MOV R11,0x74");
        emit("MCALL PC[0x80019924]");
        emit("LD.UB R11,R7[-0xd]");
        emit("MOV R12,0x76");
        emit("MOV R10,0xf");
        emit("MCALL PC[0x80019928]");
        padTo(0x80019910);
        emit("LD.UH R12,R7[-0x2]");
        emit("SUB SP,-0x18");
        emit("LDM SP++,R7,PC");
        padTo(0x80019924);
        word(0x80019940); // send one 14-bit diagnostic value
        word(0x80008034); // direct USB-MIDI CC sender
        word(0x00006050); // pressure-history taps (up to 24), count at +0x30
        word(0x00003560); // global state base
        word(0x00003239); // otherwise-unused byte in the BSS gap
        word(0x0000002c); // factory key-to-scan-channel map
        finish("edit_mode_pressure_telemetry", 0x80019940);

        // Send a 14-bit unsigned value as adjacent CC MSB/LSB messages.
        begin(0x80019940);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("SUB SP,0x8");
        emit("ST.H R7[-0x2],R12");
        emit("ST.B R7[-0x3],R11");
        emit("LD.UH R11,R7[-0x2]");
        emit("LSR R11,0x7");
        emit("LD.UB R12,R7[-0x3]");
        emit("MOV R10,0xf");
        emit("MCALL PC[0x8001997c]");
        emit("LD.UH R11,R7[-0x2]");
        emit("ANDL R11,0x7f");
        emit("LD.UB R12,R7[-0x3]");
        emit("SUB R12,-0x1");
        emit("MOV R10,0xf");
        emit("MCALL PC[0x8001997c]");
        emit("SUB SP,-0x8");
        emit("LDM SP++,R7,PC");
        padTo(0x8001997c);
        word(0x80008034); // direct USB-MIDI CC sender
        finish("send_usb_midi_14bit", 0x80019980);

        // Pitch-CV calibration remap, stage 1.  The final
        // pitch value (key table + transpose + glide + bend, clamped 0..4095,
        // 484 units/octave, lowest key C0 = 485) is remapped through a
        // piecewise-linear curve with one anchor per octave, encoding the
        // user's 208p calibration (1 V/oct nominal; C5=5.0231 V, C6=6.232 V).
        // Called with R12 = raw pitch; stores the DAC value and the last-sent
        // mirror itself, replacing the tail of the factory update function.
        begin(0x80019980);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("ST.W --SP,R12");
        emit("MCALL PC[0x80019a00]");
        emit("LD.W R12,SP++");
        emit("MOV R8,R12");
        emit("SUB R8,-0x78");
        // Global vibrato (knob 4): signed offset in factory units (max +-13
        // = +-32 cents) computed each scan by the vibrato engine into RAM
        // 0x6028; added pre-remap so depth is constant in cents and rides
        // the tracking-corrected curve. Zero when the knob is in its
        // deadzone. d stays >= 106, so no clamp is needed.
        emit("MOV R10,0x6028");
        emit("LD.SH R10,R10[0x0]");
        emit("ADD R8,R10");
        emit("MOV R9,0xc");
        emit("MUL R8,R8,R9");
        emit("MOV R9,0x1e4");
        emit("DIVU R8,R8,R9");
        emit("CP.W R8,0x4d");
        emit("BR{ls} 0x800199b8");
        emit("MOV R8,0x4d");
        emit("MOV R9,0x1e3");
        padTo(0x800199b8);
        emit("MOV R11,R9");
        emit("LSL R8,0x1");
        emit("LDDPC R10,0x800199f8");
        emit("ADD R10,R8");
        emit("LD.UH R12,R10[0x0]");
        emit("LD.UH R9,R10[0x2]");
        emit("SUB R9,R9,R12 << 0x0");
        emit("MUL R8,R11,R9");
        emit("SUB R8,-0xf2");
        emit("MOV R11,0x1e4");
        emit("DIVU R8,R8,R11");
        emit("ADD R9,R12,R8 << 0x0");
        padTo(0x800199e0);
        emit("LDDPC R8,0x800199fc");
        emit("ST.H R8[0x358],R9");
        emit("MOV R8,0x3212");
        emit("ST.H R8[0x0],R9");
        emit("LDM SP++,R7,PC");
        padTo(0x800199f8);
        word(0x80019bc0); // per-semitone tracking-corrected curve table
        word(0x00003560); // global state base
        word(0x8001a2e8); // tuning applier + latch watch + vibrato chain
        finish("pitch_remap_calibration", 0x80019a04);

        // Per-semitone pitch curve: index 0 = the 208p's 0 V pitch (A);
        // index 3 = bottom key at the leftmost octave position (C).  Values
        // are DAC units: the per-octave calibration interpolated per
        // semitone, minus the measured tracking error at each semitone
        // (218e-key-calibration_done.csv), held constant beyond semi 64.
        begin(0x80019bc0);
        emitTable("pitch_remap");
        finish("tracking_correction_table", 0x80019c5e);

        // Pressure-based portamento (as in the Micro_Easel MonoKeyboard):
        // each scan the pitch target becomes
        // X_port = sum(z^3 * X_k) / sum(z^3) over held keys within PInterv
        // (484 units = 12 semitones) of the sounding base, z = per-key sensor
        // delta minus 110, scaled to 0..63, up to four contributors.  The
        // blend is injected as (X_port - base) before the glide, so single
        // keys, handovers, arpeggiation, transpose, and every tuning table
        // behave exactly as before when only one key is pressed.
        // Entry word first (read by the MCALL hook), code follows.
        begin(0x80019c60);
        word(0x8001a8a0); // transpose-capture shim, chains to the cave below
        emit("STM --SP,R0,R1,R2,R3,R4,R5,R7,LR");
        emit("MOV R7,SP");
        emit("MOV R4,R12");
        emit("MCALL PC[0x8001ac80]");
        emit("LDDPC R9,0x80019d34");
        emit("LD.SH R11,R9[0x350]");
        // Portamento knob = pressure-needed-to-bend: T = 1023 - knob.
        // At knob zero T exceeds any possible touch, so only the sounding
        // key contributes and the blend is exactly zero (factory behavior).
        // The anchor key is never thresholded, so engagement is smooth.
        // Read the KNOB MIRROR (state+0x306), not the combined rate index at
        // +0x3a2 — the index carries a pressure-derived addend, so with the
        // full 218r curve the threshold would move with pressure and the
        // blend would engage and disengage erratically under the fingers.
        emit("LD.SH R5,R9[0x306]");
        // Hard gate: below the knob's deadzone the blend loop never runs at
        // all — multi-finger common-mode sensor inflation can push deltas
        // past any threshold, so "off" must not depend on pressure at all.
        emit("CP.W R5,0x30");
        emit("BR{lt} 0x80019d20");
        emit("MOV R8,0x3ff");
        emit("SUB R5,R8,R5 << 0x0");
        emit("MOV R0,0x0");
        emit("MOV R1,0x0");
        // R3 carries the base the offset is measured from, in the same pitch
        // domain as the contributors.  It defaults to the unstamped base and
        // becomes the anchor's stamped pitch when the anchor is found.
        emit("MOV R3,R11");
        // Keys 28..0: the cache and the latch stamps cover 29 slots, and slot
        // 31's stamp address is the blend's own target cell.
        emit("MOV R2,0x1c");
        padTo(0x80019c90);
        emit("ADD R8,R9,R2 << 0x0");
        emit("LD.UB R10,R8[0x21b]");
        emit("CP.W R10,0x1");
        emit("BR{ne} 0x80019d08");
        emit("MOV R8,0x854");
        emit("ADD R8,R8,R2 << 0x1");
        emit("LD.UH R10,R8[0x0]");
        // Identify the anchor on the UNSTAMPED pitch, before any stamp moves
        // it, and keep the answer: it decides both the threshold exemption and
        // which contributor supplies the base.
        emit("CP.W R10,R11");
        emit("SR{EQ} R12");
        if (feature("arp_latch")) {
            // In latch mode a slot sounds at table[k] plus its stamp, so it is
            // weighted at that pitch — otherwise a note latched an octave away
            // pulls toward where its key sits now rather than where it sounds.
            emit("MOV R8,0x608e");
            emit("LD.UB R8,R8[0x0]");
            emit("CP.W R8,0x1");
            emit("BR{ne} 0x80019cc0");
            emit("MOV R8,0x60a2");
            emit("LD.SH R8,R8[R2 << 0x1]");
            emit("ADD R10,R8");
        }
        padTo(0x80019cc0);
        // The base must sit in the same domain as the contributors: measuring
        // a stamped contributor against an unstamped base published the stamp
        // itself as an offset, which the glide target had already applied.
        emit("CP.W R12,0x0");
        emit("BR{eq} 0x80019cc8");
        emit("MOV R3,R10");
        padTo(0x80019cc8);
        emit("MOV R8,0x6100");
        emit("LD.UH R8,R8[R2 << 0x1]");
        emit("CP.W R12,0x0");
        emit("BR{ne} 0x80019cdc");
        emit("SUB R8,R8,R5 << 0x0");
        padTo(0x80019cdc);
        emit("CP.W R8,0x0");
        emit("BR{le} 0x80019d08");
        emit("LSR R8,0x4");
        emit("CP.W R8,0x3f");
        emit("BR{ls} 0x80019cf4");
        emit("MOV R8,0x3f");
        padTo(0x80019cf4);
        emit("MOV R12,R8");
        emit("MUL R12,R12,R8");
        emit("MUL R12,R12,R8");
        // Scale the cubic weight only as far as 32-bit overflow safety needs.
        // >>3 keeps all 29 worst-case products below 2^32 while retaining
        // distinct weights at light pressure (z=4/5/6 -> 8/15/27).  The old
        // >>6 collapsed those touches to 1/1/3 and audibly flattened the blend.
        emit("LSR R12,0x3");
        emit("ADD R0,R12");
        emit("MUL R12,R12,R10");
        emit("ADD R1,R12");
        padTo(0x80019d08);
        emit("SUB R2,0x1");
        emit("BR{ge} 0x80019c90");
        padTo(0x80019d10);
        emit("CP.W R0,0x0");
        emit("BR{eq} 0x80019d20");
        emit("DIVU R0,R1,R0");
        // Publish X - base as an OFFSET (RAM 0x60e0) instead of folding it
        // into the glide target: the pitch shim adds it after the glide
        // engine, so pressure steers the pitch immediately while the same
        // knob keeps its classic note-to-note portamento.  Both paths store,
        // so a released chord zeroes the offset within one scan.
        emit("SUB R0,R0,R3 << 0x0");
        emit("RJMP 0x80019d24");
        padTo(0x80019d20);
        emit("MOV R0,0x0");
        padTo(0x80019d24);
        emit("MOV R8,0x60e0");
        emit("ST.H R8[0x0],R0");
        emit("LDDPC R8,0x80019d34");
        emit("ST.H R8[0x352],R4");
        emit("LDM SP++,R0,R1,R2,R3,R4,R5,R7,PC");
        padTo(0x80019d34);
        word(0x00003560); // global state base
        finish("pressure_blend", 0x80019d38);

        // Arpeggiator randomness on the preset-voltage knobs (outside edit):
        //   knob 1 (0x30a) -> state+0x38c, the factory weighted-random key
        //     selector's bias/randomness parameter (works with edit key 25 on);
        //   knob 2 (0x30c) -> random gate shortening: the countdown's gate-off
        //     compare (was == 3) becomes == R, R redrawn per step in
        //     [3, 3 + (interval-4)*knob/1024] via the factory PRNG (0x80013e04);
        //   knob 3 (0x30e) -> random +-octave on each arp note with
        //     probability knob/1024 (bottom deadzone = off, factory-exact).
        // Knob values latch only outside edit mode so edit-mode knob use
        // never disturbs the arp.  RAM: 0x60e6 knob2 latch, 0x60e8 last
        // countdown, 0x60ea knob3 latch, 0x60ec gate threshold.
        // Arp controls on the preset knobs (outside edit; latches edit-gated):
        //   knob 1 (0x30a>>3 -> state+0x38c latch): press-order vs random key
        //     selection, applied by the replacement selector below;
        //   knob 2 (0x30c -> 0x60e6 latch): rhythm randomness — the per-step
        //     countdown reload becomes T*((1024-r) + r*E)/1024 with E an
        //     exponential draw (mean ~1, CLZ-geometric approximation, clamp
        //     4x), the Micro_Easel RANDOM PULSER law; knob low = even pulses;
        //   knob 3 (0x30e -> 0x60ea latch): random +-octave per arp note.
        // Gate-off timing itself is factory (compare == 3 restored).
        begin(0x80019d38);
        word(0x80019d44); // gate/housekeeping entry (hook at 0x21a0)
        word(0x80019da8); // octave entry (hook at 0x22f6)
        word(0x80019df8); // rhythm entry (hook at 0x21fa)
        // R8 is dead at the hook site (factory overwrote it); do not push it,
        // so the final CP.H can run AFTER the LDM restore and survive the
        // return (LDM with PC would execute return-and-test-R12, destroying
        // the flags the caller's BR{ne} consumes — that bug killed the
        // factory gate-off masking of arp pitch transitions).
        emit("STM --SP,R7,R9,R10,R11,R12,LR");
        emit("MOV R7,SP");
        emit("LDDPC R10,0x80019e90");
        emit("LD.UB R8,R10[0x39]");
        emit("CP.W R8,0x1");
        emit("BR{eq} 0x80019d98");
        emit("LD.SH R8,R10[0x30a]");
        emit("LSR R8,0x3");
        emit("ST.B R10[0x38c],R8");
        emit("LD.SH R8,R10[0x30c]");
        emit("MOV R11,0x60e6");
        emit("ST.H R11[0x0],R8");
        emit("LD.SH R8,R10[0x30e]");
        emit("MOV R11,0x60ea");
        emit("ST.H R11[0x0],R8");
        padTo(0x80019d98);
        emit("LDM SP++,R7,R9,R10,R11,R12,LR");
        emit("MOV R8,0x3");
        emit("CP.H R9,R8");
        emit("MOV PC,LR");
        padTo(0x80019da8);
        emit("STM --SP,R0,R7,R9,R10,R11,R12,LR");
        emit("MOV R7,SP");
        emit("MOV R11,0x60ea");
        emit("LD.SH R11,R11[0x0]");
        emit("CP.W R11,0x30");
        emit("BR{lt} 0x80019df0");
        emit("MOV R0,R11");
        emit("ST.W --SP,R8");
        emit("MCALL PC[0x80019e94]");
        emit("LD.W R8,SP++");
        emit("BFEXTU R9,R12,0xa,0xa");
        emit("CP.W R9,R0");
        emit("BR{ge} 0x80019df0");
        emit("BFEXTU R9,R12,0x14,0x1");
        emit("CP.W R9,0x0");
        emit("BR{eq} 0x80019de0");
        emit("SUB R8,-0x1e4");
        emit("RJMP 0x80019df0");
        padTo(0x80019de0);
        emit("SUB R8,0x1e4");
        emit("CP.W R8,0x1");
        emit("BR{ge} 0x80019df0");
        emit("SUB R8,-0x3c8");
        padTo(0x80019df0);
        emit("LDM SP++,R0,R7,R9,R10,R11,R12,PC");
        padTo(0x80019df8);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("MOV R9,R12");
        emit("MOV R10,0x60e6");
        emit("LD.SH R10,R10[0x0]");
        emit("CP.W R10,0x30");
        emit("BR{ge} 0x80019e18");
        emit("LDDPC R8,0x80019e90");
        emit("ST.H R8[0x38e],R9");
        emit("LDM SP++,R7,PC");
        padTo(0x80019e18);
        emit("ST.W --SP,R9");
        emit("ST.W --SP,R10");
        emit("MCALL PC[0x80019e94]");
        emit("LD.W R10,SP++");
        emit("LD.W R9,SP++");
        emit("ANDH R12,0x7fff");
        emit("CLZ R8,R12");
        emit("SUB R8,0x1");
        emit("BFEXTU R11,R12,0x0,0x8");
        emit("MOV R12,0xb1");
        emit("MUL R8,R8,R12");
        emit("MUL R11,R11,R12");
        emit("LSR R11,0x8");
        emit("ADD R8,R11");
        emit("CP.W R8,0x400");
        emit("BR{le} 0x80019e50");
        emit("MOV R8,0x400");
        padTo(0x80019e50);
        emit("MUL R11,R10,R8");
        emit("LSR R11,0x8");
        emit("MOV R12,0x400");
        emit("SUB R12,R12,R10 << 0x0");
        emit("ADD R12,R11");
        emit("MUL R12,R12,R9");
        emit("LSR R12,0xa");
        emit("CP.W R12,0x8");
        emit("BR{ge} 0x80019e70");
        emit("MOV R12,0x8");
        padTo(0x80019e70);
        emit("CP.W R12,0xfff");
        emit("BR{le} 0x80019e80");
        emit("MOV R12,0xfff");
        padTo(0x80019e80);
        emit("LDDPC R8,0x80019e90");
        emit("ST.H R8[0x38e],R12");
        emit("LDM SP++,R7,PC");
        padTo(0x80019e90);
        word(0x00003560); // global state base
        word(0x80013e04); // factory PRNG
        finish("arp_random_knobs", 0x80019e98);

        // Press-order list (RAM 0x6000: length byte + up to 32 keys, mid-gap
        // between BSS end 0x4748 and the stack) and the replacement arp key
        // selector: knob 1 blends press-order stepping into fully random.
        begin(0x8001a020);
        emit("STM --SP,R7,R8,R9,R10,R11,R12,LR");
        emit("MOV R7,SP");
        emit("MOV R10,0x6000");
        emit("LD.UB R8,R10[0x0]");
        emit("CP.W R8,0x20");
        emit("BR{ls} 0x8001a038");
        emit("MOV R8,0x0");
        padTo(0x8001a038);
        emit("MOV R9,0x0");
        padTo(0x8001a03c);
        emit("CP.W R9,R8");
        emit("BR{ge} 0x8001a078");
        emit("ADD R11,R10,R9 << 0x0");
        emit("LD.UB R11,R11[0x1]");
        emit("CP.W R11,R12");
        emit("BR{eq} 0x8001a054");
        emit("SUB R9,-0x1");
        emit("RJMP 0x8001a03c");
        padTo(0x8001a054);
        emit("MOV R11,R8");
        emit("SUB R11,0x1");
        emit("CP.W R9,R11");
        emit("BR{ge} 0x8001a074");
        emit("ADD R11,R10,R9 << 0x0");
        emit("LD.UB LR,R11[0x2]");
        emit("ST.B R11[0x1],LR");
        emit("SUB R9,-0x1");
        emit("RJMP 0x8001a054");
        padTo(0x8001a074);
        emit("SUB R8,0x1");
        padTo(0x8001a078);
        emit("CP.W R8,0x20");
        emit("BR{ge} 0x8001a08c");
        emit("ADD R11,R10,R8 << 0x0");
        emit("ST.B R11[0x1],R12");
        emit("SUB R8,-0x1");
        padTo(0x8001a08c);
        emit("ST.B R10[0x0],R8");
        emit("LDM SP++,R7,R8,R9,R10,R11,R12,PC");
        padTo(0x8001a0a0);
        // Selector entry (pool 0x80002420 repointed here). R12 = held-flags
        // pointer; returns the next key in R12 or -1.
        emit("STM --SP,R0,R1,R2,R3,R7,LR");
        emit("MOV R7,SP");
        emit("MOV R0,R12");
        emit("LDDPC R1,0x8001a220");
        emit("LD.UB R8,R1[0x340]");
        emit("CP.W R8,0x0");
        emit("BR{ne} 0x8001a0c8");
        emit("LD.UB R8,R1[0x341]");
        emit("CP.W R8,0x0");
        emit("BR{eq} 0x8001a1f0");
        padTo(0x8001a0c8);
        emit("LD.UB R2,R1[0x38c]");
        emit("CP.W R2,0x6");
        emit("BR{lt} 0x8001a150");
        emit("MCALL PC[0x8001a224]");
        emit("BFEXTU R3,R12,0xa,0x7");
        emit("CP.W R3,R2");
        emit("BR{ge} 0x8001a150");
        emit("SUB SP,0x20");
        emit("MOV R2,0x0");
        emit("MOV R3,0x0");
        padTo(0x8001a0f0);
        // Keys 0..28 only.  The held-flag array is 29 entries — the factory's
        // own selectors start their walk at 0x1c — so scanning 32 read three
        // bytes of unrelated state beyond it and treated any that happened to
        // hold 1 as a held key.  The random branch trusts these flags without
        // the press-order path's held re-check, so a phantom 29/30/31 played
        // straight out as a pitch up to an octave above the real key.
        emit("CP.W R3,0x1d");
        emit("BR{ge} 0x8001a110");
        emit("ADD R8,R0,R3 << 0x0");
        emit("LD.UB R8,R8[0x0]");
        emit("CP.W R8,0x1");
        emit("BR{ne} 0x8001a108");
        emit("ADD R8,SP,R2 << 0x0");
        emit("ST.B R8[0x0],R3");
        emit("SUB R2,-0x1");
        padTo(0x8001a108);
        emit("SUB R3,-0x1");
        emit("RJMP 0x8001a0f0");
        padTo(0x8001a110);
        emit("CP.W R2,0x0");
        emit("BR{eq} 0x8001a148");
        emit("MOV R3,0x4");
        padTo(0x8001a118);
        emit("MCALL PC[0x8001a224]");
        emit("LSR R12,0x11");
        emit("DIVU R10,R12,R2");
        emit("ADD R8,SP,R11 << 0x0");
        emit("LD.UB R8,R8[0x0]");
        emit("CP.W R2,0x1");
        emit("BR{eq} 0x8001a140");
        emit("LD.UB R9,R1[0x34d]");
        emit("CP.W R8,R9");
        emit("BR{ne} 0x8001a140");
        emit("SUB R3,0x1");
        emit("BR{gt} 0x8001a118");
        padTo(0x8001a140);
        emit("SUB SP,-0x20");
        emit("MOV R12,R8");
        emit("RJMP 0x8001a200");
        padTo(0x8001a148);
        emit("SUB SP,-0x20");
        emit("RJMP 0x8001a1f0");
        padTo(0x8001a150);
        emit("MOV R10,0x6000");
        emit("LD.UB R8,R10[0x0]");
        emit("CP.W R8,0x20");
        emit("BR{ls} 0x8001a164");
        emit("MOV R8,0x0");
        padTo(0x8001a164);
        emit("CP.W R8,0x0");
        emit("BR{eq} 0x8001a1f0");
        emit("LD.UB R9,R1[0x34d]");
        emit("MOV R3,0x0");
        padTo(0x8001a180);
        emit("CP.W R3,R8");
        emit("BR{ge} 0x8001a1a0");
        emit("ADD R11,R10,R3 << 0x0");
        emit("LD.UB R11,R11[0x1]");
        emit("CP.W R11,R9");
        emit("BR{eq} 0x8001a1a0");
        emit("SUB R3,-0x1");
        emit("RJMP 0x8001a180");
        padTo(0x8001a1a0);
        emit("CP.W R3,R8");
        emit("BR{lt} 0x8001a1b0");
        emit("MOV R3,R8");
        emit("SUB R3,0x1");
        padTo(0x8001a1b0);
        emit("MOV R2,R8");
        padTo(0x8001a1b8);
        emit("SUB R3,-0x1");
        emit("CP.W R3,R8");
        emit("BR{lt} 0x8001a1d0");
        emit("MOV R3,0x0");
        padTo(0x8001a1d0);
        emit("ADD R11,R10,R3 << 0x0");
        emit("LD.UB R11,R11[0x1]");
        emit("ADD R9,R0,R11 << 0x0");
        emit("LD.UB R9,R9[0x0]");
        emit("CP.W R9,0x1");
        emit("BR{eq} 0x8001a1f8");
        emit("SUB R2,0x1");
        emit("CP.W R2,0x0");
        emit("BR{gt} 0x8001a1b8");
        padTo(0x8001a1f0);
        emit("MOV R12,-0x1");
        emit("RJMP 0x8001a218");
        padTo(0x8001a1f8);
        emit("MOV R12,R11");
        padTo(0x8001a200);
        emit("LD.UB R8,R1[0x39]");
        emit("CP.W R8,0x0");
        emit("BR{eq} 0x8001a218");
        emit("CP.W R12,0xc");
        emit("BR{lt} 0x8001a1f0");
        emit("CP.W R12,0x18");
        emit("BR{gt} 0x8001a1f0");
        padTo(0x8001a218);
        emit("LDM SP++,R0,R1,R2,R3,R7,PC");
        padTo(0x8001a220);
        word(0x00003560); // global state base
        word(0x80013e04); // factory PRNG
        finish("arp_order_selector", 0x8001a228);

        // Zero-portamento fix, safe variant: the glide RATE VALUE is forced
        // to the fastest table entry (0, step ~= 99.95% per scan) whenever
        // the rate index sits in the knob deadzone — a pot offset otherwise
        // lands on an audibly slow entry. Entered from a hook over the
        // factory's rate-table lookup (R9 = rate index); stores the value to
        // the rate variable (RAM 0x2eee) exactly as the factory code did.
        begin(0x8001a230);
        word(0x8001a234);
        if (feature("pressure_blend")) {
            // No time-based glide: the pressure-based blend is the only
            // portamento.  Notes snap; the knob means pressure-needed-to-bend.
            emit("STM --SP,R7,LR");
            emit("MOV R7,SP");
            emit("MOV R8,0x0");
            emit("MOV R9,0x2eee");
            emit("ST.H R9[0x0],R8");
            emit("LDM SP++,R7,PC");
        } else {
            // Blend-off builds keep classic portamento with the zero-snap.
            emit("STM --SP,R7,LR");
            emit("MOV R7,SP");
            emit("MOV R8,0x3866");
            emit("LD.SH R8,R8[0x0]");
            emit("CP.W R8,0x30");
            emit("BR{ge} 0x8001a24c");
            emit("MOV R8,0x0");
            emit("RJMP 0x8001a254");
            padTo(0x8001a24c);
            emit("LDDPC R8,0x8001a260");
            emit("LD.SH R8,R8[R9 << 0x1]");
            emit("CASTS.H R8");
            padTo(0x8001a254);
            emit("MOV R9,0x2eee");
            emit("ST.H R9[0x0],R8");
            emit("LDM SP++,R7,PC");
        }
        padTo(0x8001a260);
        word(0x80015150); // factory glide rate-curve table
        finish("glide_rate_clamp", 0x8001a264);

        // Pulse defer: the four factory pool words that pointed at the
        // pulse-high routine (0x800077f8) are repointed to the setter below,
        // which just marks the pulse pending; the pitch-store hook fires the
        // real routine after the pitch lands. Word first: the real routine's
        // address, read by the hook's MCALL PC[0x8001a268].
        //
        // The mark is a countdown of scans, not a flag, so the trigger can be
        // held past the scan that writes the pitch — see gate_settle_scans at
        // the hook.  A pulse arriving while one is already pending does NOT
        // restart the countdown: the gate then always rises within a bounded
        // number of scans of the FIRST request, instead of being pushed back
        // indefinitely by a fast arp whose steps land inside the window.
        begin(0x8001a268);
        word(0x800077f8); // real pulse-high routine
        emit("MOV R8,0x60ee");
        emit("LD.UB R9,R8[0x0]");
        emit("CP.W R9,0x0");
        emit("BR{ne} 0x8001a27a");
        emit(StringFormat("MOV R9,0x%x",
            number("gate_settle_scans", 1, 0, 3) + 1));
        emit("ST.B R8[0x0],R9");
        padTo(0x8001a27a);
        emit("MOV PC,LR");
        finish("pulse_defer_set", 0x8001a280);

        // Latch mode (arp switch position 1). Three pieces:
        //   latch_noteoff  — physical releases are ignored while latched;
        //   latch_check    — a press of an already-held key unlatches it
        //                    (called from the note-on wrapper, returns -1);
        //   applier_plus   — runs the tuning applier then watches state+0x340
        //                    for the latch->off/regular edge (prev byte at
        //                    RAM 0x60ef) and clears all held flags + count.
        // Latch mode v2 (restored — the earlier symptom was factory
        // polyphonic-MIDI release semantics, not the latch).
        begin(0x8001a280);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("LDDPC R8,0x8001a338");
        emit("LD.UB R8,R8[0x340]");
        emit("CP.W R8,0x1");
        emit("BR{eq} 0x8001a29c");
        emit("MCALL PC[0x8001a33c]");
        padTo(0x8001a29c);
        emit("LDM SP++,R7,PC");
        padTo(0x8001a2a8);
        emit("STM --SP,R7,R8,R9,R10,LR");
        emit("MOV R7,SP");
        emit("LDDPC R10,0x8001a338");
        emit("LD.UB R8,R10[0x340]");
        emit("CP.W R8,0x1");
        emit("BR{ne} 0x8001a2e0");
        emit("ADD R9,R10,R12 << 0x0");
        emit("LD.UB R8,R9[0x21b]");
        emit("CP.W R8,0x1");
        emit("BR{ne} 0x8001a2e0");
        emit("MOV R8,0x0");
        emit("ST.B R9[0x21b],R8");
        emit("LD.UB R8,R10[0x21a]");
        emit("CP.W R8,0x0");
        emit("BR{eq} 0x8001a2dc");
        emit("SUB R8,0x1");
        emit("ST.B R10[0x21a],R8");
        padTo(0x8001a2dc);
        emit("MOV R12,-0x1");
        padTo(0x8001a2e0);
        emit("LDM SP++,R7,R8,R9,R10,PC");
        padTo(0x8001a2e8);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("MCALL PC[0x8001ac80]");
        // Only reached when a tuning has actually been supplied.  With every
        // slot on the factory temperament the applier would copy that table
        // over itself each scan, but it also asserts the rem-en and trn LEDs
        // and permanently zeroes the old transpose-mode byte — so leaving it
        // out is what hands those back to the factory.
        if (feature("alternate_tunings")) {
            emit("MCALL PC[0x8001a340]");      // tuning applier
        }
        if (feature("knob4_vibrato")) {
            emit("MCALL PC[0x8001a344]");      // vibrato engine
        }
        emit("MCALL PC[0x8001a348]");          // per-scan housekeeping
        if (feature("pressure_ab_switch")) {
            emit("MCALL PC[0x8001a34c]");      // octave-switch shadow sync
        }
        emit("LDM SP++,R7,PC");
        padTo(0x8001a338);
        word(0x00003560); // global state base
        word(0x80005a50); // original note-off
        word(0x80019a40); // tuning applier
        word(0x8001a350); // vibrato engine
        word(0x8001a480); // latch watch + poly-MIDI boot force + common-mode
        word(0x8001a750); // octave-switch shadow sync
        finish("latch_v2", 0x8001a350);

        // Scan profiler (diagnostic).  Wraps the main loop's event dispatcher
        // so every event handler is timed with the CPU cycle counter, which
        // free-runs at the CPU clock and which nothing else in the firmware
        // writes.  Answers one question: how much of each scan period is
        // already spoken for, and therefore whether a shorter period fits.
        //
        //   RAM 0x6032  worst single dispatch in the last window, cycles/32
        //   RAM 0x6034  CPU load over the last window, tenths of a percent
        //   RAM 0x6038  window start / 0x603c busy accumulator / 0x6040 max
        //
        // The accumulators need no initialisation: whatever the SRAM powers
        // up holding produces one bogus window, after which the rollover
        // resets everything.
        begin(0x8001a540);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("MFSR R8,COUNT");
        emit("ST.W --SP,R8");
        emit("MCALL PC[0x8001a5e0]");
        emit("MFSR R9,COUNT");
        emit("LD.W R8,SP++");
        emit("SUB R9,R9,R8 << 0x0");   // cycles this dispatch took
        emit("MOV R10,0x6038");
        emit("LD.W R11,R10[0x4]");
        emit("ADD R11,R9");
        emit("ST.W R10[0x4],R11");     // busy += delta
        emit("LD.W R11,R10[0x8]");
        emit("CP.W R9,R11");
        emit("BR{ls} 0x8001a570");
        emit("ST.W R10[0x8],R9");      // max = delta
        padTo(0x8001a570);
        emit("MFSR R8,COUNT");
        emit("LD.W R11,R10[0x0]");
        emit("SUB R8,R8,R11 << 0x0");  // elapsed since the window opened
        emit("LDDPC R11,0x8001a5e4");
        emit("CP.W R8,R11");
        emit("BR{ls} 0x8001a5dc");     // window still open
        emit("MOV R9,0x3e8");
        emit("DIVU R8,R8,R9");         // R8 = elapsed/1000
        emit("CP.W R8,0x0");
        emit("BR{eq} 0x8001a5c8");
        emit("LD.W R12,R10[0x4]");
        emit("DIVU R8,R12,R8");        // R8 = busy per mille of the window
        emit("MOV R11,0x3e8");
        emit("CP.W R8,R11");
        emit("BR{ls} 0x8001a5a4");
        emit("MOV R8,R11");
        padTo(0x8001a5a4);
        emit("MOV R11,0x6034");
        emit("ST.H R11[0x0],R8");      // load, tenths of a percent
        emit("LD.W R12,R10[0x8]");
        emit("LSR R12,0x5");           // cycles/32, to fit a 14-bit CC pair
        emit("MOV R11,0x3fff");
        emit("CP.W R12,R11");
        emit("BR{ls} 0x8001a5bc");
        emit("MOV R12,R11");
        padTo(0x8001a5bc);
        emit("MOV R11,0x6032");
        emit("ST.H R11[0x0],R12");     // worst dispatch
        padTo(0x8001a5c8);
        emit("MOV R8,0x0");
        emit("ST.W R10[0x4],R8");
        emit("ST.W R10[0x8],R8");
        emit("MFSR R8,COUNT");
        emit("ST.W R10[0x0],R8");      // open the next window
        padTo(0x8001a5dc);
        emit("LDM SP++,R7,PC");
        padTo(0x8001a5e0);
        word(0x80004c64); // real event dispatcher
        word(0x01000000); // window length in cycles (~280 ms at 60 MHz)
        finish("scan_profiler", 0x8001a5e8);

        // Main-loop dispatcher pointer -> profiler wrapper.
        begin(0x80007dc0);
        word(0x8001a540);
        finish("profiler_pool", 0x80007dc4);

        // Pressure output interpolation.  The scan writes a target at RAM
        // 0x6036 and this 1 kHz handler divides each new gap over N remaining
        // ticks.  Recomputing gap/N distributes integer remainders, and the
        // last tick snaps exactly to target: five smaller DAC treads, with no
        // exponential tail and no change to the already-full scan schedule.
        // Shared first-use initialisation makes a separate byte marker here
        // unnecessary and guarantees the target, snapshot, counter and DAC
        // slot become valid atomically before any of them is read.
        begin(0x8001a600);
        emit("MCALL PC[0x8001ac80]");
        emit("MOV R10,0x6036");
        emit("LD.SH R11,R10[0x0]");     // target
        emit("CP.W R11,0x0");
        emit("BR{ge} 0x8001a612");
        emit("MOV R11,0x0");
        padTo(0x8001a612);
        emit("MOV R9,0xfff");
        emit("CP.W R11,R9");
        emit("BR{ls} 0x8001a61e");
        emit("MOV R11,R9");             // clamped to the 12-bit DAC range
        padTo(0x8001a61e);
        emit("LDDPC R12,0x8001a690");
        emit("LD.SH R8,R12[0x356]");    // where the output is now
        emit("MOV R9,0x602c");
        emit("LD.UH R10,R9[0x0]");      // target snapshot
        emit("CP.W R10,R11");
        emit("BR{eq} 0x8001a64c");
        emit("ST.H R9[0x0],R11");
        emit("MOV R10,0x6084");
        emit("LD.UH R10,R10[0x0]");
        emit("CP.W R10,0x8");
        emit("BR{ls} 0x8001a644");
        emit("MOV R10,0x8");
        padTo(0x8001a644);
        emit("ST.H R9[0x2],R10");       // restart only for a new target
        emit("RJMP 0x8001a650");
        padTo(0x8001a64c);
        emit("LD.UH R10,R9[0x2]");
        padTo(0x8001a650);
        emit("SUB R9,R11,R8 << 0x0");   // signed gap remaining
        emit("CP.W R10,0x1");
        emit("BR{ls} 0x8001a674");
        // DIVS writes a quotient/remainder register pair.  Save the decremented
        // counter first, then use scratch pair R10:R11 without touching the
        // dispatcher's callee-saved registers.
        emit("SUB R10,0x1");
        emit("MOV R11,0x602e");
        emit("ST.H R11[0x0],R10");
        emit("SUB R10,-0x1");
        emit("DIVS R10,R9,R10");
        emit("ADD R8,R10");
        emit("RJMP 0x8001a680");
        padTo(0x8001a674);
        emit("MOV R8,R11");             // last (or disabled) tick is exact
        emit("MOV R10,0x0");
        emit("MOV R9,0x602e");
        emit("ST.H R9[0x0],R10");
        padTo(0x8001a680);
        emit("ST.H R12[0x356],R8");
        padTo(0x8001a688);
        emit("LDDPC R12,0x8001a694");
        emit("MOV PC,R12");             // on into the factory flush handler
        padTo(0x8001a690);
        word(0x00003560); // global state base
        word(0x80004f66); // factory event-17 case
        finish("dac_interpolator", 0x8001a698);

        // Dispatcher jump-table entry 17 (DAC flush) -> interpolator.
        begin(0x8001485c);
        word(0x8001a600);
        finish("dac_flush_pool", 0x80014860);

        // The scan's pressure store now lands on the interpolator's target
        // (state+0x2ad6 = RAM 0x6036) instead of the DAC slot directly.
        fixedPatch("pressure_target_redirect", 0x80002db2, 4, "ST.H R9[0x2ad6],R8");

        // Local proximity estimator.  R12 is the held key being corrected.
        // Walk outward on each
        // side past touched keys (and past the immediate neighbours, which
        // carry spill from the pressing finger itself) to the first untouched
        // key; take the larger of the two sides.  Whatever that key reads
        // above `proximity_reference` is field from a hovering hand, and is
        // returned in R12.  Calling this per physically held key keeps distant
        // hands from sharing the last-active key's field estimate.
        begin(0x8001a6a0);
        emit("STM --SP,R7,R9,LR");
        emit("MOV R7,SP");
        emit("MOV R11,0x0");
        emit("CP.W R12,0x1c");
        emit("BR{hi} 0x8001a720");
        emit("MOV R9,R12");
        emit("SUB R9,-0x2");
        emit("MOV R10,0x3");
        padTo(0x8001a6b8);
        emit("CP.W R9,0x1c");
        emit("BR{gt} 0x8001a6e8");
        emit("MOV R8,0x3490");
        emit("ADD R8,R8,R9 << 0x0");
        emit("LD.UB R8,R8[0x0]");
        emit("CP.W R8,0x2");
        emit("BR{ne} 0x8001a6d4");
        emit("SUB R10,0x1");
        emit("BR{eq} 0x8001a6e8");
        emit("SUB R9,-0x1");
        emit("RJMP 0x8001a6b8");
        padTo(0x8001a6d4);
        emit("MOV R8,0x3686");
        emit("ADD R8,R8,R9 << 0x1");
        emit("LD.UH R8,R8[0x0]");
        emit("CP.W R8,R11");
        emit("BR{ls} 0x8001a6e8");
        emit("MOV R11,R8");
        padTo(0x8001a6e8);
        emit("MOV R10,0x3");
        emit("MOV R9,R12");
        emit("SUB R9,0x2");
        padTo(0x8001a6f0);
        emit("CP.W R9,0x0");
        emit("BR{lt} 0x8001a720");
        emit("MOV R8,0x3490");
        emit("ADD R8,R8,R9 << 0x0");
        emit("LD.UB R8,R8[0x0]");
        emit("CP.W R8,0x2");
        emit("BR{ne} 0x8001a70c");
        emit("SUB R10,0x1");
        emit("BR{eq} 0x8001a720");
        emit("SUB R9,0x1");
        emit("RJMP 0x8001a6f0");
        padTo(0x8001a70c);
        emit("MOV R8,0x3686");
        emit("ADD R8,R8,R9 << 0x1");
        emit("LD.UH R8,R8[0x0]");
        emit("CP.W R8,R11");
        emit("BR{ls} 0x8001a720");
        emit("MOV R11,R8");
        padTo(0x8001a720);
        emit(StringFormat("MOV R8,0x%x", number("proximity_reference", 0x12c, 0x6e, 0x7d0)));
        emit("SUB R11,R11,R8 << 0x0");
        emit("CP.W R11,0x0");
        emit("BR{ge} 0x8001a734");
        emit("MOV R11,0x0");
        padTo(0x8001a734);
        emit("MOV R12,R11");
        emit("LDM SP++,R7,R9,PC");
        padTo(0x8001a748);
        word(0x00003560); // global state base
        finish("proximity_estimator", 0x8001a74c);

        // Octave-switch shadow sync (debug A/B builds).  The switch reader's
        // stores are redirected to shadow RAM (flags 0x6046/0x6047, position
        // word 0x6048), so the live position drives only the pressure A/B.
        // For the first ~200 scans after the power-up init the shadow is
        // copied into the real state bytes, which applies the position the
        // switch sits in at power-on; after that the octave function is
        // frozen and the switch is free to flip.
        begin(0x8001a750);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("MOV R9,0x604c");
        emit("LD.UH R8,R9[0x0]");
        emit("CP.W R8,0xc8");
        emit("BR{ge} 0x8001a780");
        emit("SUB R8,-0x1");
        emit("ST.H R9[0x0],R8");
        emit("LDDPC R10,0x8001a788");
        emit("MOV R9,0x6046");
        emit("LD.UB R8,R9[0x0]");
        emit("ST.B R10[0x342],R8");
        emit("LD.UB R8,R9[0x1]");
        emit("ST.B R10[0x343],R8");
        emit("LD.W R8,R9[0x2]");
        emit("ST.W R10[0x344],R8");
        padTo(0x8001a780);
        emit("LDM SP++,R7,PC");
        padTo(0x8001a788);
        word(0x00003560); // global state base
        finish("octswitch_sync", 0x8001a78c);

        // Pressure prep.  (1) Black keys have physically smaller pads, so the
        // same finger pressure couples less charge — measured ~0.72-0.81x of
        // a white key.  Scale the raw value up for black keys (mask bit per
        // key, C at the bottom) so both key colours land in one calibration
        // window.  (2) The debug A/B factory law, when enabled: linear gain
        // into saturation, converted through the same int-to-float helper the
        // normal epilogue uses, since the caller expects a float back.
        begin(0x8001a790);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        // Correct every key once per scan into the shared cache, then let the
        // consumers read it.  This runs on the pressure path, which happens
        // once per scan, so the portamento weighting may read a cache one scan
        // (5 ms) old — immaterial against its own 20 ms slew, and worth it to
        // stop two loops applying the same correction differently.
        emit("MCALL PC[0x8001a7f4]");
        if (false) {
        } else if (!feature("multi_key_pressure")) {
            emit("LDDPC R10,0x8001a7f0");
            emit("LD.UB R8,R10[0x256]");
            // 0x1d with BR{ge}, not 0x1c with BR{hi}: the key arrives
            // zero-extended from LD.UB, so the signed test is the same test,
            // and {ge} has a two-byte encoding where {hi} does not.  Those two
            // bytes are what let this branch fit its cave — with BR{hi} it ran
            // to 0x8001a7c2 and the build failed on the padTo below.
            emit("CP.W R8,0x1d");
            emit("BR{ge} 0x8001a7c0");
            emit("MOV R9,0xa54a");
            emit("ORH R9,0xa54");
            emit("LSR R9,R9,R8");
            emit("BFEXTU R9,R9,0x0,0x1");
            emit("CP.W R9,0x0");
            emit("BR{eq} 0x8001a7c0");
            emit(StringFormat("MOV R9,0x%x", number("black_key_scale_32", 43, 32, 96)));
            emit("MUL R12,R12,R9");
            emit("LSR R12,0x5");
        }
        padTo(0x8001a7c0);
        if (feature("pressure_ab_switch")) {
            emit("MOV R9,0x6046");
            emit("LD.UB R9,R9[0x0]");
            emit("CP.W R9,0x1");
            emit("BR{ne} 0x8001a7e8");
            emit(StringFormat("LSL R12,0x%x", number("factory_gain_shift", 3, 1, 5)));
            emit("MOV R9,0xfff");
            emit("CP.W R12,R9");
            emit("BR{ls} 0x8001a7d8");
            emit("MOV R12,R9");
            padTo(0x8001a7d8);
            emit("MCALL PC[0x8001a7f8]");
            emit("MOV R9,0x1");
            emit("LDM SP++,R7,PC");
        }
        padTo(0x8001a7e8);
        emit("MOV R9,0x0");
        emit("LDM SP++,R7,PC");
        padTo(0x8001a7f0);
        word(0x00003560); // global state base
        word(0x8001aa10); // multi-key pressure combiner
        word(0x80013350); // signed-int-to-float helper (same as the epilogue)
        word(0x8001aa90); // per-key corrected-pressure cache fill
        finish("pressure_prep", 0x8001a800);

        // Variable-depth growing average.  Depth N (8..24 taps = 40..120 ms
        // at the 5 ms scan) lives at RAM 0x6082, set by edit knob 2; taps at
        // RAM 0x6050, sample count still at 0x6080 (zeroed by the note-on and
        // source-change wrappers).  Averages only the samples gathered since
        // the touch, so attacks stay instant at any depth.
        begin(0x8001a800);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("MOV R8,R12");
        // Depth (RAM 0x6082), clamped against power-up garbage.
        emit("MOV R9,0x6082");
        emit("LD.UH R11,R9[0x0]");
        emit("CP.W R11,0x8");
        emit("BR{ge} 0x8001a816");
        emit("MOV R11,0x8");
        padTo(0x8001a816);
        emit("CP.W R11,0x18");
        emit("BR{le} 0x8001a81e");
        emit("MOV R11,0x18");
        padTo(0x8001a81e);
        // Ring buffer with a running sum: one subtract, one add and one store
        // per scan instead of shifting the whole history and re-summing it.
        // A zero count — set by the note-on and source-change wrappers — also
        // resets the ring, so a new touch starts clean.
        //
        // Count and index are checked against the depth before they are
        // trusted, not just the depth against its own bounds.  The write index
        // scales a store off 0x6050, so an out-of-range one is a wild halfword
        // write into whatever follows — the corrected-pressure cache, or the
        // stack.  Power-up normally clears these, but that clearing is gated
        // on a 16-bit marker surviving in SRAM; a collision, or a brownout
        // that retains the marker and little else, would otherwise walk
        // straight into that store.  Everything reachable is one comparison
        // away, so check rather than rely on the marker.
        emit("MOV R9,0x6080");
        emit("LD.UH R10,R9[0x0]");      // count
        emit("LD.UH R12,R9[0x6]");      // write index
        emit("CP.W R10,0x0");
        emit("BR{eq} 0x8001a836");      // empty: also clears a stale index/sum
        emit("CP.W R10,R11");
        emit("BR{hi} 0x8001a836");      // more samples than the ring holds
        // Reversed operands: the assembler takes {hi} but not {lo}.
        emit("CP.W R11,R12");
        emit("BR{hi} 0x8001a842");      // depth > index, so it is inside: trust it
        padTo(0x8001a836);
        emit("MOV R10,0x0");
        emit("MOV R12,0x0");
        emit("ST.H R9[0x0],R10");
        emit("ST.H R9[0x6],R12");
        emit("ST.W R9[0x8],R10");
        padTo(0x8001a842);
        emit("LD.W R9,R9[0x8]");        // running sum; base is done with
        emit("CP.W R10,R11");
        emit("BR{lt} 0x8001a858");
        // Full: drop the oldest sample, which is the one at the write index.
        emit("MOV LR,0x6050");
        emit("LD.UH LR,LR[R12 << 0x1]");
        emit("SUB R9,R9,LR << 0x0");
        emit("RJMP 0x8001a85c");
        padTo(0x8001a858);
        emit("SUB R10,-0x1");
        padTo(0x8001a85c);
        emit("MOV LR,0x6050");
        emit("ST.H LR[R12 << 0x1],R8");
        emit("ADD R9,R8");
        emit("SUB R12,-0x1");
        emit("CP.W R12,R11");
        emit("BR{lt} 0x8001a870");
        emit("MOV R12,0x0");
        padTo(0x8001a870);
        // Write the ring state back off one base: the four cells live within
        // 0x6080..0x608d, and folding the addresses into displacements buys
        // the bytes the validation above costs.
        emit("MOV LR,0x6080");
        emit("ST.H LR[0x0],R10");       // 0x6080 count
        emit("ST.H LR[0x6],R12");       // 0x6086 write index
        emit("ST.W LR[0x8],R9");        // 0x6088 running sum
        emit("ST.H LR[0xc],R8");        // 0x608c newest sample
        // Keep `resolution_bits` fractional bits of the mean.
        if (number("resolution_bits", 4, 0, 4) > 0) {
            emit(StringFormat("LSL R9,0x%x", number("resolution_bits", 4, 0, 4)));
        }
        emit("DIVU R8,R9,R10");
        emit("MOV R12,R8");
        emit("LDM SP++,R7,PC");
        finish("variable_filter", 0x8001a890);

        // (Edit knob 2 smoothing control removed for now: the wrapper ran and
        // stored, but its ADC mirror read never followed the physical knob in
        // edit mode.  The filter depth and interpolation length are fixed from the build
        // config until the edit-mode knob mirror question is settled.)

        // Per-scan transpose capture and, in latch mode, the per-note hold:
        // R12 arrives as base+offset; G = R12 - state[0x350] is published for
        // the note-on stamp, and the sounding note (last arp key, 0x34d) is
        // re-based to the offset it was stamped with.  Doing this here makes
        // the hold exact within a single scan — no transient when the octave
        // switch flips between arp steps.
        begin(0x8001a8a0);
        emit("STM --SP,R7,LR");
        emit("MCALL PC[0x8001ac80]");
        emit("MOV R8,0x38b0");
        emit("LD.SH R9,R8[0x0]");
        emit("SUB R9,R12,R9 << 0x0");
        emit("MOV R8,0x60a0");
        emit("ST.H R8[0x0],R9");
        if (feature("arp_latch")) {
            emit("MOV R10,0x38a0");
            emit("LD.UB R11,R10[0x0]");
            emit("CP.W R11,0x1");
            emit("BR{ne} 0x8001a8e4");
            emit("LD.UB R10,R10[0xd]");
            // 0x1d with BR{ge}, not 0x1c with BR{hi}: the key arrives
            // zero-extended from LD.UB, so the signed test is the same test,
            // and {ge} has a two-byte encoding where {hi} does not.  The two
            // bytes pay for the held check below.
            emit("CP.W R10,0x1d");
            emit("BR{ge} 0x8001a8e4");
            // A stamp only means anything for a slot that is sounding, so
            // gate on the held flag (state+0x21b) before reading it.  Without
            // this the shim re-based the transpose off the last arp key's
            // stamp cell even when that slot had never been latched, which
            // replaced the live transpose with a stale one.
            emit("MOV R11,0x377b");
            emit("LD.UB R11,R11[R10 << 0x0]");
            emit("CP.W R11,0x1");
            emit("BR{ne} 0x8001a8e4");
            emit("ADD R8,R8,R10 << 0x1");
            emit("LD.SH R8,R8[0x2]");
            emit("SUB R8,R8,R9 << 0x0");
            emit("ADD R12,R8");
        }
        padTo(0x8001a8e4);
        emit("MCALL PC[0x8001a8ec]");
        emit("LDM SP++,R7,PC");
        padTo(0x8001a8ec);
        word(0x80019c64); // the real blend cave entry
        finish("transpose_capture", 0x8001a8f0);

        // Post-glide blend apply, with smoothing.  The blend cave publishes a
        // raw offset target each scan; this shim slews the APPLIED offset
        // (RAM 0x60e2) toward it by 1/2^blend_slew_shift of the remaining gap
        // per scan, so sensor jitter walking the z quantisation near the
        // threshold cannot frequency-modulate the pitch.  The +-1 nudge
        // prevents the shift from stalling short of the target.
        //
        // This is exponential, not a fixed settling time: at the default shift
        // of 2 it closes a quarter of the gap every 5 ms, which is ~17 ms to
        // 63%, ~55 ms to 95% and ~85 ms to 99%, plus up to one scan of
        // pressure-cache latency ahead of it.  An earlier comment called it a
        // "20 ms settle", which is the time constant, not the settle.
        begin(0x8001a8f0);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("MCALL PC[0x8001ac80]");
        emit("MOV R9,0x60e0");
        emit("LD.SH R10,R9[0x0]");
        emit("LD.SH R8,R9[0x2]");
        emit("SUB R11,R10,R8 << 0x0");
        emit(StringFormat("ASR R11,0x%x", number("blend_slew_shift", 2, 0, 4)));
        emit("CP.W R11,0x0");
        emit("BR{ne} 0x8001a914");
        emit("CP.W R10,R8");
        emit("BR{le} 0x8001a914");
        emit("MOV R11,0x1");
        padTo(0x8001a914);
        emit("ADD R8,R11");
        emit("ST.H R9[0x2],R8");
        emit("ADD R12,R8");
        emit("CP.W R12,0x0");
        emit("BR{ge} 0x8001a924");
        emit("MOV R12,0x0");
        padTo(0x8001a924);
        emit("MCALL PC[0x8001a92c]");
        emit("LDM SP++,R7,PC");
        padTo(0x8001a92c);
        word(0x80019980); // the real pitch remap
        finish("blend_offset_apply", 0x8001a930);

// Pitch-aware latch: latched notes are pitches held in slots.  A slot k
        // sounds at table[k] + stamp[k], and the stamp can be any value — so a
        // pitch is not tied to its own key's slot.  A press computes its
        // would-be pitch P: if a latched slot sounds P, it unlatches (toggle,
        // from any octave).  Otherwise the pitch latches into the pressed
        // key's slot, or any free slot if that one is occupied — the same key
        // pressed in three octaves yields three latched notes.  With no free
        // slot the press is suppressed.
        begin(0x8001a930);
        emit("STM --SP,R0,R7,LR");
        emit("MOV R7,SP");
        emit("LDDPC R10,0x8001aa08");
        emit("CP.W R12,0x1c");
        emit("BR{hi} 0x8001a9e0");
        emit("MOV R8,0x854");
        emit("ADD R8,R8,R12 << 0x1");
        emit("LD.UH R11,R8[0x0]");
        emit("MOV R8,0x60a0");
        emit("LD.SH R8,R8[0x0]");
        emit("ADD R11,R8");
        if (feature("latch_probe")) {
            // Snapshot both halves of the comparison before anything acts on
            // them, so a failed match can be read back afterwards.
            emit("MOV R9,0x609a");
            emit("ST.H R9[0x0],R8");        // the transpose term, as seen here
        }
        emit("LD.UB R8,R10[0x340]");
        emit("CP.W R8,0x1");
        emit("BR{ne} 0x8001a9c0");
        emit("MOV R0,0x0");
        padTo(0x8001a960);
        emit("ADD R9,R10,R0 << 0x0");
        emit("LD.UB R8,R9[0x21b]");
        emit("CP.W R8,0x1");
        emit("BR{ne} 0x8001a98c");
        emit("MOV R8,0x854");
        emit("ADD R8,R8,R0 << 0x1");
        emit("LD.UH R9,R8[0x0]");
        emit("MOV R8,0x60a0");
        emit("ADD R8,R8,R0 << 0x1");
        emit("LD.SH R8,R8[0x2]");
        emit("ADD R9,R8");
        // Match with a tolerance, not for bit-equality.  Both sides are built
        // from the same transpose at 0x60A0, but that term is not stable:
        // the latch probe measured it reading -485 on some presses and -484
        // on others, because the generated tuning tables round and adjacent
        // octaves land 484 or 485 units apart.  One unit is 2.48 cents, so an
        // exact compare missed, the allocator ran, and the press added a note
        // instead of releasing one.  Semitones are ~40 units apart (484/12),
        // so a tolerance this small cannot reach the neighbouring note.
        //
        // BR{lt} against tolerance+1 rather than BR{le} against the tolerance:
        // {lt} has a two-byte encoding here and {le} does not, and |x| is
        // never negative, so the two tests are the same test.
        emit("SUB R9,R11");
        emit("ABS R9");
        emit(StringFormat("CP.W R9,0x%x",
            number("latch_match_tolerance", 8, 0, 30) + 1));
        emit("BR{lt} 0x8001a9e8");
        padTo(0x8001a98c);
        emit("SUB R0,-0x1");
        emit("CP.W R0,0x1c");
        emit("BR{le} 0x8001a960");
        emit("ADD R9,R10,R12 << 0x0");
        emit("LD.UB R8,R9[0x21b]");
        emit("CP.W R8,0x0");
        emit("BR{eq} 0x8001a9c0");
        emit("MOV R0,0x0");
        padTo(0x8001a9a4);
        emit("ADD R9,R10,R0 << 0x0");
        emit("LD.UB R8,R9[0x21b]");
        emit("CP.W R8,0x0");
        emit("BR{eq} 0x8001a9bc");
        emit("SUB R0,-0x1");
        emit("CP.W R0,0x1c");
        emit("BR{le} 0x8001a9a4");
        emit("MOV R12,-0x1");
        emit("RJMP 0x8001a9e0");
        padTo(0x8001a9bc);
        emit("MOV R12,R0");
        padTo(0x8001a9c0);
        emit("MOV R8,0x854");
        emit("ADD R8,R8,R12 << 0x1");
        emit("LD.UH R9,R8[0x0]");
        emit("SUB R9,R11,R9 << 0x0");
        emit("MOV R8,0x60a0");
        emit("ADD R8,R8,R12 << 0x1");
        emit("ST.H R8[0x2],R9");
        padTo(0x8001a9e0);
        emit("LDM SP++,R0,R7,PC");
        padTo(0x8001a9e8);
        emit("ADD R9,R10,R0 << 0x0");
        emit("MOV R8,0x0");
        emit("ST.B R9[0x21b],R8");
        emit("LD.UB R8,R10[0x21a]");
        emit("CP.W R8,0x0");
        emit("BR{eq} 0x8001aa00");
        emit("SUB R8,0x1");
        emit("ST.B R10[0x21a],R8");
        padTo(0x8001aa00);
        emit("MOV R12,-0x1");
        emit("LDM SP++,R0,R7,PC");
        padTo(0x8001aa08);
        word(0x00003560); // global state base
        finish("latch_pitch_toggle", 0x8001aa0c);

        // One pass over the keys does all of it: subtract the baseline and a
        // spatially local proximity estimate, correct for key colour, publish the result
        // for the portamento weighting, and aggregate the physically held
        // keys for the pressure CV.
        //
        // Order matters here.  The proximity estimate is a raw-domain figure,
        // so it is subtracted BEFORE the colour correction — scaling first
        // and subtracting an unscaled estimate afterwards left roughly
        // (scale-1) x estimate behind on black keys.
        //
        // The factory sources pressure from the last key touched, so adding a
        // second key hands the CV to it and a barely-touched one reads below
        // the floor, cutting the output to zero with the first finger still
        // down.  Only PHYSICALLY held keys aggregate (touch state 2), so
        // latched keys, which have no finger on them, cannot drag it down.
        begin(0x8001aa10);
        emit("STM --SP,R0,R1,R2,R3,R7,LR");
        emit("MOV R7,SP");
        emit("MCALL PC[0x8001ac80]");
        emit("LDDPC R0,0x8001ab1c");
        emit("MOV R1,0x0");
        emit("MOV R2,0x0");
        emit("MOV R3,0x0");
        emit("MOV R9,0x1c");
        padTo(0x8001aa30);
        // Ignore released and latched slots entirely.  Publishing zero for
        // them also prevents the portamento loop from consuming old pressure.
        emit("MOV R11,0x3490");
        emit("ADD R11,R11,R9 << 0x0");
        emit("LD.UB R11,R11[0x0]");
        emit("CP.W R11,0x2");
        emit("BR{ne} 0x8001aab0");
        if (feature("pressure_common_mode")) {
            emit("MOV R12,R9");
            emit("MCALL PC[0x8001ab18]");
            emit("MOV R10,R12");
        } else {
            emit("MOV R10,0x0");
        }
        emit("MOV R8,0x3686");
        emit("ADD R8,R8,R9 << 0x1");
        emit("LD.UH R8,R8[0x0]");
        emit("SUB R8,0x6e");
        emit("SUB R8,R8,R10 << 0x0");
        emit("CP.W R8,0x0");
        emit("BR{gt} 0x8001aa68");
        emit("MOV R8,0x0");
        padTo(0x8001aa68);
        emit("LD.UH R11,R0[R9 << 0x1]");
        emit("MUL R11,R8,R11");
        emit("SUB R11,-0x80");
        emit("LSR R11,0x8");
        emit("ADD R8,R11");
        emit("MOV R11,0x6100");
        emit("ST.H R11[R9 << 0x1],R8");
        emit("ADD R1,R8");
        emit("SUB R2,-0x1");
        emit("CP.W R8,R3");
        emit("BR{ls} 0x8001aaa8");
        emit("MOV R3,R8");
        padTo(0x8001aaa8);
        emit("RJMP 0x8001aac0");
        padTo(0x8001aab0);
        emit("MOV R8,0x0");
        emit("MOV R11,0x6100");
        emit("ST.H R11[R9 << 0x1],R8");
        padTo(0x8001aac0);
        emit("SUB R9,0x1");
        emit("BR{ge} 0x8001aa30");
        if (feature("multi_key_pressure")) {
            // No key under a finger means no pressure.  Say so, rather than
            // leaving the caller's R12 to travel on: it only read as silence
            // because the value it happened to carry sat below the floor.
            emit("MOV R12,0x0");
            emit("CP.W R2,0x0");
            emit("BR{eq} 0x8001aae8");
            if (number("multi_key_max", 0, 0, 1) == 1) {
                emit("MOV R12,R3");
            } else {
                // Round the mean rather than truncating: with two keys a lost
                // half-count is a persistent bias of several DAC counts.
                emit("LSR R11,R2,0x1");
                emit("ADD R1,R11");
                emit("DIVU R8,R1,R2");
                emit("MOV R12,R8");
            }
        }
        padTo(0x8001aae8);
        emit("LDM SP++,R0,R1,R2,R3,R7,PC");
        padTo(0x8001ab18);
        word(0x8001a6a0); // per-held-key proximity estimator
        padTo(0x8001ab1c);
        word(0x8001ab20); // key-colour coefficients, in flash
        finish("pressure_cache", 0x8001ab20);

        // Per-key black-key correction, as a Q8 excess over unity: 0 for a
        // white key, round(scale*256)-256 for a black one.  Copied into RAM at
        // power-up so every consumer can reach it with a short immediate, and
        // so the pressure aggregate and the portamento weighting apply exactly
        // the same numbers.
        begin(0x8001ab20);
        emitTable("black_key_excess");
        finish("black_key_excess_table", 0x8001ab60);

        // Shared first-use bootstrap.  Every handler that consumes custom RAM
        // calls this before its first load, instead of relying on the later
        // pitch-applier housekeeping to happen first.  The build-derived
        // marker is written last, so an interrupted initialisation is retried.
        begin(0x8001ab60);
        emit("STM --SP,R7,R8,R9,R10,R11,R12,LR");
        emit("MOV R7,SP");
        emit("MOV R9,0x602a");
        emit("LD.UH R8,R9[0x0]");
        emit(StringFormat("MOV R11,0x%x", number("init_marker", 0xb007, 0x1000, 0xeffe)));
        emit("CP.W R8,R11");
        emit("BR{eq} 0x8001ac74");
        emit("MOV R8,0x0");
        emit("LDDPC R10,0x8001ac7c");
        // Empty press-order state and every first-read signal-processing cell.
        emit("MOV R9,0x6000");
        emit("ST.B R9[0x0],R8");
        emit("MOV R9,0x604c");
        emit("ST.H R9[0x0],R8");
        emit("MOV R9,0x6024");
        emit("ST.W R9[0x0],R8");
        emit("MOV R9,0x6028");
        emit("ST.H R9[0x0],R8");
        emit("MOV R9,0x6080");
        emit("ST.H R9[0x0],R8");
        emit(StringFormat("MOV R11,0x%x", number("smoothing_taps", 8, 8, 24)));
        emit("MOV R9,0x6082");
        emit("ST.H R9[0x0],R11");
        emit(StringFormat("MOV R11,0x%x", number("output_interpolation_steps", 5, 1, 8)));
        emit("MOV R9,0x6084");
        emit("ST.H R9[0x0],R11");
        emit("MOV R9,0x6086");
        emit("ST.H R9[0x0],R8");
        emit("MOV R9,0x6088");
        emit("ST.W R9[0x0],R8");
        emit("MOV R9,0x608c");
        emit("ST.H R9[0x0],R8");
        emit("ST.B R9[0x4],R8");        // 0x6090 tuning slot 0, the declared default
        emit("ST.W R9[0x8],R8");        // 0x6094 output error accumulator
        emit("ST.H R9[0xc],R8");        // 0x6098 vibrato error accumulator
        // Finite DAC interpolation state and its live slot start together.
        emit("MOV R9,0x602c");
        emit("ST.W R9[0x0],R8");
        emit("MOV R9,0x6036");
        emit("ST.H R9[0x0],R8");
        emit("ST.H R10[0x356],R8");
        // The curve level byte is not touched here.  It sits in factory state
        // and survives a flash, and knob 4 owns it again - forcing it to 0
        // would drop the curve until the knob was next swept.
        // Pitch offsets are read before the first blend scan on some paths.
        emit("MOV R9,0x60a0");
        emit("ST.H R9[0x0],R8");
        emit("MOV R9,0x60e0");
        emit("ST.W R9[0x0],R8");
        // The 29 latch stamps that follow the live transpose.  A slot sounds
        // at table[k] plus its stamp, so an uninitialised stamp gives a slot
        // an arbitrary pitch — read by the latch toggle's match loop and by
        // the transpose shim before anything has written one.  Zero is the
        // rest state: the slot sounds at its own key's nominal pitch.
        emit("MOV R9,0x60a2");
        emit("MOV R12,0x1c");
        padTo(0x8001ac00);
        emit("ST.H R9[0x0],R8");
        emit("SUB R9,-0x2");
        emit("SUB R12,0x1");
        emit("BR{ge} 0x8001ac00");
        // The blend can also precede the pressure pass: publish known-zero
        // samples for all 29 physical keys until that pass fills the cache.
        emit("MOV R9,0x6100");
        emit("MOV R12,0x1c");
        padTo(0x8001ac40);
        emit("ST.H R9[0x0],R8");
        emit("SUB R9,-0x2");
        emit("SUB R12,0x1");
        emit("BR{ge} 0x8001ac40");
        // The scratch inside the factory's dead filter array, cleared off one
        // base to stay inside this cave's remaining bytes.  Zero is the safe
        // rest state for each: no arp rhythm or octave randomness, no pending
        // pulse, and a vibrato depth that clamps to its minimum.  This must
        // stay ahead of the latch section below, which loads the switch
        // position into R8 and so ends the run of zero stores.
        emit("MOV R9,0x60e4");
        emit("ST.H R9[0x0],R8");        // tuning-apply guard
        emit("ST.H R9[0x2],R8");        // 0x60e6 arp knob 2 latch
        emit("ST.H R9[0x6],R8");        // 0x60ea arp knob 3 latch
        emit("ST.B R9[0xa],R8");        // 0x60ee deferred-pulse countdown
        emit("ST.H R9[0xc],R8");        // 0x60f0 vibrato knob latch
        if (feature("arp_latch")) {
            emit("LD.UB R8,R10[0x340]");
            emit("MOV R9,0x608e");
            emit("ST.B R9[0x0],R8");
            emit("MOV R9,0x60ef");
            emit("ST.B R9[0x0],R8");
        }
        // Commit the marker only after all dependent state is coherent.
        emit("MOV R9,0x602a");
        emit(StringFormat("MOV R11,0x%x", number("init_marker", 0xb007, 0x1000, 0xeffe)));
        emit("ST.H R9[0x0],R11");
        padTo(0x8001ac74);
        emit("LDM SP++,R7,R8,R9,R10,R11,R12,PC");
        padTo(0x8001ac7c);
        word(0x00003560); // global state base
        finish("first_use_initializer", 0x8001ac80);

        // One shared pool word keeps every consumer on the same bootstrap.
        begin(0x8001ac80);
        word(0x8001ab60);
        finish("initializer_pool", 0x8001ac84);

        // Scale the effective one-knob vibrato control from 50% at zero
        // pressure to its original value at full pressure. The 0x1000
        // rounding bias also makes pressure 4095 reproduce K exactly.
        begin(0x8001ac84);
        emit("LD.UH R8,R10[0x356]");
        emit("SUB R8,-0x1000");
        emit("MUL R11,R11,R8");
        emit("SUB R11,-0x1000");
        emit("LSR R11,0xd");
        emit("MOV PC,LR");
        finish("pressure_vibrato_scale", 0x8001aca0);

        begin(0x8001aca0);
        word(0x8001ac84);
        finish("pressure_vibrato_pool", 0x8001aca4);

        // The factory's long-hold switch combination also toggles polyphonic
        // MIDI, independently of the edit-mode setting. Preserve its debounce
        // completion flag but skip the toggle, MIDI flush and status flash so
        // the saved edit-mode value has a single owner.
        begin(0x8000456c);
        emit("LDDPC R9,0x800045cc");
        emit("MOV R8,0x1");
        emit("ST.B R9[0x38],R8");
        emit("RJMP 0x800045c6");
        finish("poly_arp_independence", 0x8000458c);

        // One-time migration for settings records written by older firmware.
        // Byte zero of the persisted payload is written but never restored by
        // the factory loader, so it can safely identify the new ownership
        // model without consuming RAM or resetting any other saved setting.
        // Load first, then migrate only poly MIDI and save the whole record.
        begin(0x8001aca4);
        emit("STM --SP,R7,R8,R9,R10,R11,LR");
        emit("MOV R7,SP");
        emit("MCALL PC[0x8001acf0]");
        // Hold the loader's return across the migration: on the migrating
        // boot the saver runs last, and returning *its* R12 would hand the
        // caller a different value than an ordinary boot does.
        emit("ST.W --SP,R12");
        emit("LDDPC R10,0x8001acf4");
        emit("LDDPC R8,0x8001acf8");
        emit("LD.W R8,R8[0x0]");
        emit("LD.UB R9,R8[0x2]");
        emit("MOV R11,0xa5");
        emit("CP.W R9,R11");
        emit("BR{eq} 0x8001ace0");
        emit("MOV R9,0x0");
        emit("ST.B R10[0x84],R9");
        emit("MCALL PC[0x8001acfc]");
        padTo(0x8001ace0);
        emit("LD.W R12,SP++");
        emit("LDM SP++,R7,R8,R9,R10,R11,PC");
        padTo(0x8001acf0);
        word(0x8000a264); // factory persistent-settings loader
        word(0x00003560); // global state base
        word(0x00000968); // pointer to the persisted settings record
        word(0x80009fb8); // factory persistent-settings saver
        finish("poly_settings_migration", 0x8001ad00);

        // Note-off pointer pools -> latch-gated wrapper.
        // Global vibrato on knob 4 (Micro_Easel one-knob law: depth and rate
        // rise together; +-33 cents and 1..6 Hz at full; deadzone = off).
        // Pressure scales the effective knob from one-half to full value.
        // Runs at 200 Hz from applier_plus. RAM: 0x60f0 knob latch
        // (edit-gated — knob 4 in edit still sets the pressure curve),
        // 0x6024 LFO phase, 0x6026 smoothed depth (steps +-1/scan, ~65 ms
        // swell), 0x6028 signed output offset in pitch units.
        begin(0x8001a350);
        emit("STM --SP,R0,R1,R7,LR");
        emit("MOV R7,SP");
        emit("LDDPC R10,0x8001a470");
        emit("LD.UB R8,R10[0x39]");
        emit("CP.W R8,0x1");
        emit("BR{eq} 0x8001a370");
        emit("LD.SH R8,R10[0x310]");
        emit("MOV R9,0x60f0");
        emit("ST.H R9[0x0],R8");
        padTo(0x8001a370);
        emit("MOV R9,0x60f0");
        emit("LD.SH R11,R9[0x0]");
        emit("CP.W R11,0x30");
        emit("BR{ge} 0x8001a384");
        emit("MOV R11,0x30");
        padTo(0x8001a384);
        emit("SUB R11,0x30");
        emit("MCALL PC[0x8001aca0]");
        emit("MOV R8,0xd0");            // depth target in Q4 pitch units
        emit("MUL R8,R8,R11");
        emit("LSR R8,0xa");
        emit("MOV R9,0x6026");
        emit("LD.SH R12,R9[0x0]");
        emit("CP.W R12,0xd0");
        emit("BR{ls} 0x8001a3a8");
        emit("MOV R12,0x0");
        // Depth slews toward the target at 16 Q4-units (one whole pitch unit)
        // per scan, the same ~65 ms swell as the old integer step, and snaps
        // once it is within one step so it lands on the fractional target
        // instead of oscillating around it.
        padTo(0x8001a3a8);
        emit("SUB R1,R8,R12 << 0x0");   // gap = target - depth
        emit("CP.W R1,0x10");
        emit("BR{gt} 0x8001a3bc");
        emit("CP.W R1,-0x10");
        emit("BR{lt} 0x8001a3c2");
        emit("MOV R12,R8");             // within a step: land exactly
        emit("RJMP 0x8001a3c8");
        padTo(0x8001a3bc);
        emit("SUB R12,-0x10");
        emit("RJMP 0x8001a3c8");
        padTo(0x8001a3c2);
        emit("SUB R12,0x10");
        padTo(0x8001a3c8);
        emit("ST.H R9[0x0],R12");
        emit("MOV R8,0x6b8");
        emit("MUL R11,R11,R8");
        emit("LSR R11,0xa");
        emit("SUB R11,-0x148");
        emit("MOV R9,0x6024");
        emit("LD.UH R8,R9[0x0]");
        emit("ADD R8,R11");
        emit("CASTU.H R8");
        emit("ST.H R9[0x0],R8");
        // Interpolate the sine between table entries with the phase fraction.
        // 64 entries over a 16-bit phase is a 5.6-degree step; without this
        // the LFO is a staircase no matter how fine the depth is.  The table
        // carries a 65th entry repeating the first, so the neighbour read
        // needs no wrap.
        emit("MOV R11,R8");             // keep the phase
        emit("LSR R8,0xa");             // table index, 0..63
        emit("LDDPC R0,0x8001a474");
        emit("ADD R0,R0,R8 << 0x1");
        emit("LD.SH R10,R0[0x0]");
        emit("LD.SH R8,R0[0x2]");
        emit("SUB R8,R10");             // delta to the next entry
        emit("BFEXTU R11,R11,0x0,0xa"); // phase fraction
        emit("MUL R8,R8,R11");
        emit("ASR R8,0xa");
        emit("ADD R8,R10");             // Q7 sine, interpolated
        // Amplitude, carrying the remainder between scans.  The offset leaves
        // here in whole pitch units — 2.48 cents each — so at shallow depth
        // plain truncation quantises the modulation into audible steps.
        // Diffusing it lets the average land between them.
        emit("MUL R8,R8,R12");          // Q7 sine * Q4 depth = Q11
        emit("MOV R9,0x6098");
        emit("LD.SH R10,R9[0x0]");
        emit("ADD R8,R10");
        emit("MOV R10,R8");
        emit("ASR R10,0xb");            // floor: the remainder stays positive
        emit("LSL R1,R10,0xb");
        emit("SUB R8,R1");
        emit("ST.H R9[0x0],R8");        // carry the remainder
        emit("MOV R9,0x6028");
        emit("ST.H R9[0x0],R10");
        emit("LDM SP++,R0,R1,R7,PC");
        padTo(0x8001a470);
        word(0x00003560); // global state base
        word(0x80019e98); // sine table, relocated to free the code space
        finish("vibrato_engine", 0x8001a480);

        // The sine, moved out of the engine's cave so the interpolation above
        // fits.  65 entries: the last repeats the first as the interpolation
        // sentinel.
        begin(0x80019e98);
        var sine = [0, 12, 25, 37, 49, 60, 71, 81, 90, 98, 106, 112, 117, 122, 125, 126, 127, 126, 125, 122, 117, 112, 106, 98, 90, 81, 71, 60, 49, 37, 25, 12, 0, 65524, 65511, 65499, 65487, 65476, 65465, 65455, 65446, 65438, 65430, 65424, 65419, 65414, 65411, 65410, 65409, 65410, 65411, 65414, 65419, 65424, 65430, 65438, 65446, 65455, 65465, 65476, 65487, 65499, 65511, 65524];
        for (var __i0 = 0; __i0 < sine.length; __i0++) {
            var v = sine[__i0];
            halfword(v);
        }
        halfword(sine[0]);
        finish("vibrato_sine", 0x80019f1c);

        // Per-scan housekeeping (chained from applier_plus):
        //   (a) run the shared first-use bootstrap before reading custom RAM;
        //   (b) latch-exit watch: on state+0x340 leaving 1 (prev at RAM
        //       0x60ef) clear the held count and all 29 held flags;
        begin(0x8001a480);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("MCALL PC[0x8001ac80]");
        emit("LDDPC R10,0x8001a534");
        padTo(0x8001a4dc);
        if (feature("arp_latch")) {
            // Leaving the latch switch position releases every latched key.
            emit("LD.UB R8,R10[0x340]");
            // Mirror the latch position where the blend can read it cheaply.
            emit("MOV R9,0x608e");
            emit("ST.B R9[0x0],R8");
            emit("MOV R11,0x60ef");
            emit("LD.UB R9,R11[0x0]");
            emit("ST.B R11[0x0],R8");
            emit("CP.W R9,0x1");
            emit("BR{ne} 0x8001a510");
            emit("CP.W R8,0x1");
            emit("BR{eq} 0x8001a510");
            emit("MOV R9,0x0");
            emit("ST.B R10[0x21a],R9");
            // 0..28, the real extent of the array.  Clearing 32 zeroed the
            // same three bytes of adjacent state the selector was misreading.
            emit("MOV R9,0x1c");
            padTo(0x8001a500);
            emit("ADD R12,R10,R9 << 0x0");
            emit("MOV R8,0x0");
            emit("ST.B R12[0x21b],R8");
            emit("SUB R9,0x1");
            emit("BR{ge} 0x8001a500");
        }
        padTo(0x8001a510);
        emit("LDM SP++,R7,PC");
        padTo(0x8001a534);
        word(0x00003560); // global state base
        finish("scan_housekeeping", 0x8001a53c);

        // Note-off pointer pools -> latch-gated wrapper.
        begin(0x80005b18);
        word(0x8001a280);
        finish("noteoff_pool_1", 0x80005b1c);
        begin(0x80006278);
        word(0x8001a280);
        finish("noteoff_pool_2", 0x8000627c);

        // Guard the touch-scan release bookkeeping, in place.
        //
        // The factory keeps two parallel held-key structures.  The note pair
        // at state+0x21a/0x21b is guarded at both ends — 0x80005A04 only
        // counts up when the flag was clear, 0x80005A50 only counts down when
        // it was set — so it cannot drift.  The touch-scan pair at
        // state+0x238/0x239 is guarded on the way up (0x80005B86) and not on
        // the way down: this release path clears the flag and decrements the
        // count unconditionally, with no zero check.  One unpaired release
        // therefore takes the count from 0 to 255 through the byte store, and
        // six sites read it as "some key is down".
        //
        // Nothing branches into these 36 bytes, so they can be rewritten as a
        // unit.  The base pointer moves to R10 so the count store can reuse it
        // instead of loading the pool word a second time; those four bytes,
        // plus the redundant CASTU.B before a byte store, pay for the guard.
        // R10 is free here — the function takes its arguments in R12/R11 and
        // nothing writes R10 before this point.
        begin(0x80005ef0);
        emit("LD.UB R8,R7[-0xc]");          // the released key
        emit("LD.W R10,PC[0x380]");         // state base, from the factory pool
        emit("ADD R9,R10,R8 << 0x0");
        emit("LD.UB R8,R9[0x239]");
        emit("CP.W R8,0x0");
        emit("BR{eq} 0x80005f14");          // never registered: nothing to undo
        emit("MOV R8,0x0");
        emit("ST.B R9[0x239],R8");
        emit("LD.UB R8,R10[0x238]");
        emit("SUB R8,0x1");
        emit("ST.B R10[0x238],R8");
        finish("release_count_guard", 0x80005f14);

        // Repointed pulse-caller pools (arp advance + three key-scan sites).
        begin(0x8000243c);
        word(0x8001a26c);
        finish("pulse_pool_arp", 0x80002440);
        begin(0x80005ed8);
        word(0x8001a26c);
        finish("pulse_pool_key1", 0x80005edc);
        begin(0x800063fc);
        word(0x8001a26c);
        finish("pulse_pool_key2", 0x80006400);
        begin(0x800065a4);
        word(0x8001a26c);
        finish("pulse_pool_key3", 0x800065a8);

        // Hook: the factory rate-table lookup and store routed through the
        // clamp (original: LDDPC/LD.SH/CASTS.H/LDDPC/ST.H, 14 bytes).
        begin(0x800031c2);
        emit("MCALL PC[0x8001a230]");
        padTo(0x800031ce);
        finish("glide_rate_hook", 0x800031ce);

        // Hook 1: gate-off compare routed through knob housekeeping
        // (comparison itself is factory == 3).
        begin(0x800021a0);
        emit("MCALL PC[0x80019d38]");
        padTo(0x800021a6);
        finish("arp_gate_hook", 0x800021a6);

        // Hook 2: arp note value routed through the octave randomizer.
        begin(0x800022f6);
        emit("MCALL PC[0x80019d3c]");
        emit("ST.H R7[-0x8],R8");
        padTo(0x800022fe);
        finish("arp_octave_hook", 0x800022fe);

        // Hook 3: the per-step countdown reload routed through the rhythm
        // randomizer (R12 = tempo).
        begin(0x800021fa);
        emit("LD.SH R12,R7[-0x10]");
        emit("MCALL PC[0x80019d40]");
        padTo(0x80002204);
        finish("arp_rhythm_hook", 0x80002204);

        // Factory selector pointer -> replacement press-order/random selector.
        begin(0x80002420);
        word(0x8001a0a0);
        finish("arp_selector_pool", 0x80002424);

        // Hook: the transpose adder's target store now routes through the
        // blend cave (R12 = unblended base+transpose target).
        begin(0x800038bc);
        emit("LD.SH R12,R7[-0x6]");
        emit("MCALL PC[0x80019c60]");
        padTo(0x800038c6);
        finish("pitch_target_blend_hook", 0x800038c6);

        // Tuning applier and tables.  Selector lives in the old remote-enable
        // byte (state+2, persisted with settings): 0 = Sabat II (default),
        // 1 = slot 1, 2 = slot 2.  On change: copy the 32-entry table to RAM
        // 0x854 and set the LEDs (rem-en = ch 5 = slot 0, trn = ch 8 = slot 1).
        // Outside edit mode the LEDs are re-asserted every scan.  The old
        // transpose-mode byte (state+0x6a) is forced to zero permanently.
        begin(0x80019a40);
        emit("STM --SP,R7,LR");
        emit("MOV R7,SP");
        emit("LDDPC R10,0x80019ae8");
        emit("MOV R9,0x6090");          // tuning slot, off the factory's flags
        emit("LD.UB R8,R9[0x0]");
        emit("CP.W R8,0x2");
        emit("BR{ls} 0x80019a58");
        emit("MOV R8,0x0");
        emit("ST.B R9[0x0],R8");
        padTo(0x80019a58);
        emit("MOV R9,0x0");
        emit("ST.B R10[0x6a],R9");
        emit("MOV R11,0xa5a0");
        emit("ADD R11,R8");
        emit("MOV R9,0x60e4");
        emit("LD.UH R12,R9[0x0]");
        emit("CP.W R12,R11");
        emit("BR{ne} 0x80019a80");
        emit("LD.UB R9,R10[0x39]");
        emit("CP.W R9,0x1");
        emit("BR{ne} 0x80019aa0");
        emit("LDM SP++,R7,PC");
        padTo(0x80019a80);
        emit("ST.H R9[0x0],R11");
        emit("LSL R12,R8,0x6");
        emit("LDDPC R11,0x80019aec");
        emit("ADD R11,R12");
        emit("MOV R12,0x854");
        emit("MOV R9,0x20");
        padTo(0x80019a90);
        emit("LD.UH LR,R11[0x0]");
        emit("ST.H R12[0x0],LR");
        emit("SUB R11,-0x2");
        emit("SUB R12,-0x2");
        emit("SUB R9,0x1");
        emit("BR{ne} 0x80019a90");
        padTo(0x80019aa0);
        emit("CP.W R8,0x0");
        emit("BR{ne} 0x80019ab8");
        emit("MOV R12,0x5");
        emit("MCALL PC[0x80019af0]");
        emit("MOV R12,0x8");
        emit("MCALL PC[0x80019af4]");
        emit("LDM SP++,R7,PC");
        padTo(0x80019ab8);
        emit("CP.W R8,0x1");
        emit("BR{ne} 0x80019ad0");
        emit("MOV R12,0x8");
        emit("MCALL PC[0x80019af0]");
        emit("MOV R12,0x5");
        emit("MCALL PC[0x80019af4]");
        emit("LDM SP++,R7,PC");
        padTo(0x80019ad0);
        emit("MOV R12,0x5");
        emit("MCALL PC[0x80019af4]");
        emit("MOV R12,0x8");
        emit("MCALL PC[0x80019af4]");
        emit("LDM SP++,R7,PC");
        padTo(0x80019ae8);
        word(0x00003560); // global state base
        word(0x80019af8); // the three tuning tables
        word(0x80006808); // LED bit set
        word(0x800068cc); // LED bit clear
        emitTable("tuning_slot0");
        emitTable("tuning_slot1");
        emitTable("tuning_slot2");
        finish("tuning_applier_tables", 0x80019bb8);

        // Edit key 27 (was transpose-mode toggle): slot 1 <-> slot 2.
        // The slot lives at RAM 0x6090, not state+0x2 where the first version
        // put it.  state+0x2 is the factory's remote-enable flag: it gates the
        // MIDI command handler at 0x80004FD2, and two of those commands write
        // it directly.  Sharing the byte meant selecting a tuning other than
        // slot 0 silently switched remote control on, and a remote-enable
        // message silently retuned the instrument.
        begin(0x80003d82);
        emit("MOV R9,0x6090");
        emit("LD.UB R8,R9[0x0]");
        emit("MOV R10,0x1");
        emit("CP.W R8,0x1");
        emit("BR{ne} 0x80003d92");
        emit("MOV R10,0x2");
        padTo(0x80003d92);
        emit("ST.B R9[0x0],R10");
        emit("LDDPC R8,0x80003e24");
        emit("MOV R9,0x0");
        emit("ST.B R8[0x6a],R9");
        emit("MOV R9,0x1");
        emit("ST.B R8[0x3a],R9");
        emit("RJMP 0x80003e10");
        padTo(0x80003db8);
        finish("edit_key27_tuning_slot1", 0x80003db8);

        // Edit key 28 (was remote-enable toggle): slot 0 <-> slot 2.
        begin(0x80003db8);
        emit("MOV R9,0x6090");
        emit("LD.UB R8,R9[0x0]");
        emit("MOV R10,0x0");
        emit("CP.W R8,0x0");
        emit("BR{ne} 0x80003dc8");
        emit("MOV R10,0x2");
        padTo(0x80003dc8);
        emit("ST.B R9[0x0],R10");
        emit("LDDPC R8,0x80003e24");
        emit("MOV R9,0x1");
        emit("ST.B R8[0x3a],R9");
        emit("RJMP 0x80003e10");
        padTo(0x80003de8);
        finish("edit_key28_tuning_slot0", 0x80003de8);

        // Hook: replace the factory pitch-DAC store and last-sent mirror with
        // a call into the remap.  The 0..0xfff clamp still runs just before.
        // After the remap stores the fresh pitch to DAC slot 2, fire any
        // pulse deferred by the flag at RAM 0x60ee — the trigger then always
        // rises with the correct pitch already in the DAC buffer (the arp
        // advance runs at 1 kHz but pitch only updates here at 200 Hz; the
        // factory called the pulse routine immediately, shipping the new
        // gate with the previous note's pitch for up to 5 ms).
        begin(0x80003236);
        emit("LDDPC R8,0x80003368");
        emit("LD.SH R8,R8[0x0]");
        emit("MOV R12,R8");
        emit("MCALL PC[0x8000336c]");
        //
        // The pending mark is a countdown of scans.  Firing in this same pass
        // puts the gate on the correct DAC VALUE, but the pitch CV itself is
        // still moving: the output stage is a single pole of tau ~= 0.9 ms
        // (measured, 1.97 ms 10-90% on the jack), so at the instant the
        // trigger rises the CV has covered 89% of the step — 132 cents short
        // on an octave jump.  gate_settle_scans holds the trigger that many
        // further scans; one scan is 5.6 tau, which lands within 0.4% of the
        // target.  Zero restores the fire-immediately behaviour.
        //
        // The cost is trigger latency: up to one more scan period on top of
        // the up-to-one this hook already imposes.  It also cannot help an arp
        // whose steps are closer together than the countdown, which drops
        // triggers rather than delaying them — see the config note.
        emit("MOV R8,0x60ee");
        emit("LD.UB R9,R8[0x0]");
        emit("CP.W R9,0x0");
        emit("BR{eq} 0x80003256");      // nothing pending
        emit("SUB R9,0x1");
        emit("ST.B R8[0x0],R9");
        emit("CP.W R9,0x0");            // SUB set the flags, but ST.B sits
        emit("BR{ne} 0x80003256");      // between it and the branch
        emit("MCALL PC[0x8001a268]");
        finish("pitch_store_hook", 0x80003256);

        // Repurposed pool word: was the last-sent mirror address (0x3212),
        // now the remap entry point read by the MCALL above.
        begin(0x8000336c);
        if (feature("pressure_blend")) {
            word(0x8001a8f0); // blend-offset shim, chains to the remap
        } else {
            word(0x80019980);
        }
        finish("pitch_hook_pool", 0x80003370);

        // Scan period, in milliseconds.  The main loop registers a periodic
        // task here whose callback posts event 2 — the key/pressure/pitch
        // scan.  This single immediate is the instrument's whole update rate:
        // pressure and pitch reach the DAC once per scan, and the glide
        // engine, the vibrato phase and the pressure attack ramp all advance
        // once per scan too, so their timings scale with it.
        fixedPatch("scan_period", 0x80007c0c, 2,
            StringFormat("MOV R10,0x%x", number("scan_period_ms", 5, 1, 20)));

        // Both cold-start defaults must agree. The persistent-settings loader
        // still runs afterward and restores any value explicitly saved from
        // edit mode; these sites only govern a new/invalid record and reset.
        fixedPatch("poly_powerup_default_off", 0x800071d6, 2, "MOV R8,0x0");
        fixedPatch("poly_factory_reset_default_off", 0x8000a444, 2, "MOV R8,0x0");
        fixedPatch("poly_persistence_marker", 0x80009fc2, 4, "MOV R8,0xa5");
        wordPatch("poly_settings_loader_pool", 0x80007da8, 0x8001aca4,
            "settings loader -> one-time poly-MIDI migration wrapper");

        // Octave-switch reader: redirect the second switch's stores to shadow
        // RAM so flipping it changes only the pressure A/B (debug builds).
        fixedPatch("octsw_redirect_1", 0x800039cc, 4, "ST.B R9[0x2ae7],R8");
        fixedPatch("octsw_redirect_2", 0x800039d4, 4, "ST.B R9[0x2ae6],R8");
        fixedPatch("octsw_redirect_3", 0x800039dc, 4, "ST.W R8[0x2ae8],R9");
        fixedPatch("octsw_redirect_4", 0x800039f2, 4, "ST.B R9[0x2ae6],R8");
        fixedPatch("octsw_redirect_5", 0x800039fa, 4, "ST.B R9[0x2ae7],R8");
        fixedPatch("octsw_redirect_6", 0x80003a02, 4, "ST.W R8[0x2ae8],R9");
        fixedPatch("octsw_redirect_7", 0x80003a0c, 4, "ST.B R9[0x2ae6],R8");
        fixedPatch("octsw_redirect_8", 0x80003a14, 4, "ST.B R9[0x2ae7],R8");
        fixedPatch("octsw_redirect_9", 0x80003a1c, 4, "ST.W R8[0x2ae8],R9");

        singlePatch("pressure_gain_nop", 0x800043a4, "NOP");
        fixedPatch("transpose_force_1", 0x80005466, 4, "MOV R8,0x1");
        fixedPatch("transpose_force_2", 0x800062f8, 4, "MOV R8,0x1");
        fixedPatch("pitch_clamp_skip_1", 0x800033f8, 2, "RJMP 0x80003506");
        fixedPatch("pitch_clamp_skip_2", 0x800033c0, 2, "RJMP 0x800033d6");
        wordPatch("pressure_fn_pool", 0x80003574, 0x80019580,
            "int-to-float pointer -> calibrated pressure curve");
        fixedPatch("transpose_force_3", 0x80005392, 2, "MOV R8,0x1");
        wordPatch("pressure_float_helper_pool", 0x8000357c, 0x80013434,
            "restore original post-gain float-to-int helper");
        wordPatch("knob1_pool", 0x800043c4, 0x800194c0,
            "knob-1 pointer -> pressure-ceiling wrapper");
        wordPatch("knob3_pool", 0x800043cc, 0x80014300,
            "knob-3 pointer -> pressure-floor wrapper");
        wordPatch("knob4_pool", 0x800043d0, 0x80014380,
            "knob-4 pointer -> knob4_curve");
        // Remote-enable guards read constant zero.  Only emitted with a
        // tuning installed: the selector moved out of state+0x2 to RAM
        // 0x6090, so nothing shares the flag and a build without tunings
        // leaves the factory feature alone.
        begin(0x80006528);
        emit("MOV R8,0x0");
        emit("CP.W R8,0x0");
        finish("remote_guard_1", 0x8000652c);
        begin(0x800066ae);
        emit("MOV R8,0x0");
        emit("CP.W R8,0x0");
        finish("remote_guard_2", 0x800066b2);
        begin(0x800085da);
        emit("MOV R8,0x0");
        emit("CP.W R8,0x0");
        finish("remote_guard_3", 0x800085de);
        wordPatch("note_on_pool", 0x80005e8c, 0x80018d00,
            "note-on pointer -> filter-reset wrapper");
        wordPatch("active_key_pool", 0x80006280, 0x80018d40,
            "active-key pointer -> filter-reset wrapper");
}
