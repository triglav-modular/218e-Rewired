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
        // Proven against the factory's interrupt mask instructions and the
        // Ghidra clock regression build. Do not accept other SR bit numbers.
        { re: /^SSRF 0x10$/, fn: function () { return half(0xD303); } },

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
                // Negatives are accepted as imm21 too, two's complement in the
                // same scattered field.  This was refused while nothing proved
                // it: the corpus held no negative extended MOV, every negative
                // in it fitting the compact signed imm8.  The base image
                // settles it - the factory's own octave-down offset at
                // 0x80003776 is MOV R8,-0x1e4 encoded fe78fe1c, and the
                // formula below reproduces those four bytes exactly - and a
                // build now emits one, so the corpus covers it from here on.
                // The field is sign-extended by the CPU, so it holds
                // -0x100000..0xFFFFF and nothing else: 0x100000 through
                // 0x1FFFFF would come back negative, and -0x200000 through
                // -0x100001 positive, when this accepted the whole 21 bits.
                if (!fits(v, 21)) return null;
                if (v < 0) v += 0x200000;
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
                // Positive only.  The imm21 format scatters imm[20:17] into
                // bits 28..25 and imm[16] into bit 20 - SUB's negative prefix
                // 0xFE3 is exactly 0xE02 with those bits folded in - and this
                // rule used to hand negatives to extended() bare, which
                // encodes them as large POSITIVE comparisons.  Nothing in the
                // program emits one, so per the corpus-is-the-oracle rule the
                // unproven encoding (it would be 0xFE5) is refused loudly
                // rather than guessed: an unimplemented error at build time,
                // never silent wrong bytes.
                if (v >= 0 && v <= 0xFFFF) return extended(0xE04, rd, v);
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
                // R13 IS SP: written either way it takes the word-scaled
                // stack form, or "SUB R13,0x20" encoded as an unscaled
                // SUB Rd and moved the stack four times as far.
                if (rd === 13) {
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
            re: /^(ADD|SUB|RSUB|OR|EOR) (\S+),(\S+)$/, fn: function (m) {
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
                // Two bits of shift, as for the indexed loads and stores; a
                // larger one spilled into the reserved bits above it.
                if (sh < 0 || sh > 3) return null;
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
            // System-register read; COUNT and SR are verified against Ghidra.
            re: /^MFSR (\S+),(\w+)$/, fn: function (m) {
                var rd = reg(m[1]), sysreg = SYSREG[m[2]];
                if (rd === null || sysreg === undefined) return null;
                return extended(0xE1B, rd, sysreg);
            }
        },
        {
            re: /^MTSR SR,(\S+)$/, fn: function (m) {
                var rs = reg(m[1]);
                return rs === null ? null : extended(0xE3B, rs, 0);
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
    // EOR format I: AVR32 SLEIGH op4_5=5, also checked by the Ghidra corpus.
    var REGREG = { 'ADD': 0x0, 'SUB': 0x1, 'RSUB': 0x2, 'CP.W': 0x3, 'OR': 0x4, 'EOR': 0x5, 'MOV': 0x9 };

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
        'LD.UH': [4, 8, 2, 7], 'ST.W': [4, 16, 4, 15], 'ST.H': [5, 0, 2, 7],
        'ST.B': [5, 8, 1, 7]
    };
    var DISP_EXTENDED = {
        'LD.W': 15, 'LD.SH': 16, 'LD.UH': 17, 'LD.UB': 19,
        'ST.W': 20, 'ST.H': 21, 'ST.B': 22
    };
    var SYSREG = { 'COUNT': 0x42, 'SR': 0 };

    var INDEXED = { 'LD.SH': 0x04, 'LD.UH': 0x05, 'LD.UB': 0x07, 'ST.H': 0x0A,
                    'ST.B': 0x0B };

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
