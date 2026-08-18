# AVR32 encoder

Replaces the one thing Ghidra does in the build: turning an instruction string
into bytes. `src/AssemblePressureFix.java` uses Ghidra for a single call —

```java
byte[] encoded = assembler.assembleLine(addr(pc), instruction);
```

— so an encoder covering the instructions this firmware actually uses removes
the Ghidra and JDK dependency from the build, and lets the whole thing run in
a browser. Ghidra stays for disassembly and verification (`RecoverPressurePatch.java`,
`ExportAnalysis.java`), which is what it is genuinely irreplaceable at.

The goal is not a complete AVR32 assembler. It is to **agree with Ghidra, byte
for byte**, on the shapes in the corpus.

## Running

```bash
python3 tools/avr32/build_js.py                  # build the image, check golden_sha256
jsc tools/avr32/encoder.js tools/avr32/test_corpus.js   # encoder unit test
python3 tools/avr32/extract_corpus.py            # rebuild corpus.json from build/assemble*.log
```

`jsc` ships with macOS at
`/System/Library/Frameworks/JavaScriptCore.framework/Versions/A/Helpers/jsc`,
so no runtime install is needed. The same files run under Node once it is
available.

To re-check against Ghidra after changing the Java:

```bash
$GHIDRA_HOME/support/analyzeHeadless build/ghidra_project buchla218 \
  -import mac/firmware/218eV3_v369_DFU.hex -processor avr32:BE:32:default \
  -noanalysis -readOnly -scriptPath src \
  -postScript AssemblePressureFix.java build/build.properties \
  > build/reference.ghidra.log
sed -E 's/^INFO  AssemblePressureFix\.java> //; s/ \(GhidraScript\) *$//' \
  build/reference.ghidra.log | grep -E '^(EXTENT|BLOCK|SKIP|PATCH|[0-9a-f]{8}  )' \
  > build/reference.ghidra.records
jsc tools/avr32/encoder.js tools/avr32/runtime.js tools/avr32/program.js \
  tools/avr32/assemble.js | grep -E '^(EXTENT|BLOCK|SKIP|PATCH|[0-9a-f]{8}  )' \
  | diff build/reference.ghidra.records -
```

## Pieces

| File | Role |
|---|---|
| `encoder.js` | AVR32 instruction encoder — replaces `assembler.assembleLine` |
| `runtime.js` | the DSL (`begin`/`emit`/`padTo`/`finish`/...) and record output |
| `transpile.py` | translates `run()` in the Java into `program.js` |
| `program.js` | **generated** — do not edit |
| `assemble.js` | driver: reads `build.properties`, prints the record stream |
| `build_js.py` | transpiles, assembles, applies patches, checks `golden_sha256` |
| `test_corpus.js` | encoder unit test against `corpus.json` |
| `extract_corpus.py` | builds `corpus.json` from `build/assemble*.log` |
| `samples.py` | shows corpus samples for one shape, for deriving layouts |

`build_js.py` re-runs `transpile.py` every time, so `program.js` cannot drift
from the Java.

## Status

**The JavaScript toolchain reproduces the firmware bit-for-bit.**

```
python3 tools/avr32/build_js.py
  ...
  built    0134880586e556167d2676aa9f45ef9f0d26fe64e149b8e6fe1818dbab69be22
  golden   0134880586e556167d2676aa9f45ef9f0d26fe64e149b8e6fe1818dbab69be22
  MATCHES
```

- **Encoder**: 3,731 / 3,731 corpus instructions, all 71 shapes, zero mismatches.
- **Structure**: the transpiled program emits every EXTENT / BLOCK / SKIP /
  listing / PATCH record *identically* to a fresh Ghidra run — checked on both
  the shipped configuration and `[pressure].multi_key = "factory"`.
- **Image**: applying those patches reproduces `golden_sha256`, and matches
  `tools/build.py` on the factory-pressure configuration too.

Corpus coverage is now complete: every mnemonic in
`AssemblePressureFix.java` appears in `corpus.json` and encodes correctly.

Ghidra and the JDK are no longer needed to build. Ghidra stays only for
disassembly and verification (`RecoverPressurePatch.java`, `ExportAnalysis.java`).

## Derived layouts

All of these came from `samples.py`, not from the manual.

| Form | Encoding |
|---|---|
| `.hword` / `.word` | big-endian data |
| `NOP` | `d703` |
| `MOV Rd,imm` compact | `0x3000 \| (imm8 << 4) \| Rd` — imm8 **signed** |
| `MOV Rd,imm` extended | `0xE06 << 20 \| Rd << 16 \| imm16` |
| `MOV Rd,Rs` | `(Rs << 9) \| 0x90 \| Rd` |
| `CP.W Rd,imm` compact | `0x5800 \| ((imm6 & 0x3F) << 4) \| Rd` — signed 6-bit |
| `CP.W Rd,imm` extended | `0xE04 << 20 \| Rd << 16 \| imm16` |
| `CP.W Rd,Rs` | `(Rs << 9) \| 0x30 \| Rd` |
| `SUB SP,imm` | `0x2000 \| ((imm/4 & 0xFF) << 4) \| 13` — **word-scaled** |
| `SUB Rd,imm` compact | `0x2000 \| (imm8 << 4) \| Rd` — signed |
| `SUB Rd,imm` extended | `0xE02` if positive, `0xFE3` if negative |

### Loads and stores

Four families, all confirmed against every corpus entry:

| Family | Layout |
|---|---|
| compact base+disp | `op3(15..13) \| Rb(12..9) \| field(8..4) \| Rreg(3..0)` |
| extended base+disp | `111(31..29) \| Rb(28..25) \| subop5(24..20) \| Rreg(19..16) \| imm16` |
| indexed | `111(31..29) \| Rb(28..25) \| Ri(19..16) \| sub8(15..8) \| shift(7..4) \| Rreg(3..0)` |
| pre-dec / post-inc | fixed fields inside `op3 = 0` |

For the compact form `field = base + disp/scale`, so **the displacement is
scaled by the access width** and must be non-negative and aligned to it:

| Kind | op3 | base | scale | max disp | extended subop5 | indexed sub8 |
|---|---|---|---|---|---|---|
| `LD.UB` | 0 | 24 | 1 | 7 | 19 | `0x07` |
| `LD.W` | 3 | 0 | 4 | 124 | 15 | — |
| `LD.SH` | 4 | 0 | 2 | 14 | 16 | `0x04` |
| `LD.UH` | 4 | 8 | 2 | 14 | 17 | `0x05` |
| `ST.W` | 4 | 16 | 4 | 28 | 20 | — |
| `ST.H` | 5 | 0 | 2 | 14 | 21 | `0x0A` |
| `ST.B` | 5 | 8 | 1 | 7 | 22 | — |

`LD.W` is the exception: it uses the whole 5-bit field rather than reserving
the top two bits for a sub-opcode, which is why it reaches disp 124 where the
others stop at 14.

Single-register stack moves sit in `op3 = 0` with fixed fields:
`ST.W --Rb,Rs` = `(Rb << 9) | 0xD0 | Rs`, `LD.W Rd,Rb++` = `(Rb << 9) | 0x100 | Rd`.

Stores carry no signedness (`ST.H`/`ST.B` against `LD.UH`/`LD.SH`/`LD.UB`) —
the same split `tools/build.py` accounts for in its RAM coverage scan. In the
match order, longer alternatives come first so `UB` is not matched as `B`.

Two traps the corpus caught that reading the source would not have:

- **Compact immediates are signed.** `MOV R11,0xa0` (160) does not fit a
  signed imm8 and goes extended, while `MOV R12,-0x1` stays compact. Getting
  this backwards changes instruction *width*, which shifts every following
  address.
- **`SUB SP,imm` scales by 4.** `SUB SP,0x20` encodes as `208d`, which is byte
  for byte what an unscaled `SUB Rd,0x8` would produce.

Encoding *selection* matters as much as validity: the firmware deliberately
picks compact forms (`BR{ge}` over `BR{hi}`, `BR{lt}` over `BR{le}`) to fit
its code caves, and `padTo`/`finish` assert exact block end addresses.

### PC-relative

| Form | Encoding |
|---|---|
| `BR{cc}` compact | `0xC000 \| ((delta/2 & 0xFF) << 4) \| cond` — **cond must be < 8** |
| `BR{cc}` extended | `(0xE080 \| cond)` if forwards, `(0xFE90 \| cond)` if backwards, then `delta/2` as imm16 |
| `RJMP` | `0xC000 \| ((d & 0xFF) << 4) \| 0x8 \| ((d >> 8) & 3)`, `d = delta/2` signed **10-bit** |
| `MCALL PC[a]` | `0xF01F0000 \| slot`, `slot = (a - alignedPC)/4` |
| `LDDPC Rd,a` | `0x4800 \| (slot << 4) \| Rd`, `slot = (a - alignedPC)/4`, 7-bit |

Condition codes, proven by the corpus: `eq`=0, `ne`=1, `ge`=4, `lt`=5,
`ls`=8, `gt`=9, `le`=10, `hi`=11. `COND` is deliberately partial — a condition
never assembled here returns null rather than an unverified encoding.

Three traps in this family:

- **Compact `BR` gives the condition only bits 2..0**, because bit 3 is what
  separates it from `RJMP` in the shared `0xC000` space. So `ls`/`gt`/`le`/`hi`
  (codes 8–11) have *no* compact form — which is exactly why
  `AssemblePressureFix.java` reaches for `BR{ge}` over `BR{hi}` and `BR{lt}`
  over `BR{le}` when it needs the two-byte encoding. The data confirms the
  reasoning already written into the firmware source.
- **`RJMP` carries a 10-bit displacement**, with the top 2 bits spilling into
  bits 1..0. A backwards jump therefore reads as `0x..b` where a forwards one
  reads `0x..8`.
- **`MCALL` and `LDDPC` count from the word-aligned PC**, not the instruction's
  own address. Half the corpus sites sit at odd halfword addresses where the
  two differ, and using the raw PC there gives a displacement that is not even
  a multiple of 4.

### ALU

| Form | Encoding |
|---|---|
| `ADD`/`SUB`/`OR`/`CP.W`/`MOV` `Rd,Rs` | `(Rs << 9) \| (sub << 4) \| Rd`, sub = 0 / 1 / 4 / 3 / 9 |
| `ABS`/`CASTU.H`/`SR{EQ}` `Rd` | `(op12 << 4) \| Rd`, op12 = `0x5C4` / `0x5C7` / `0x5F0` |
| `ASR`/`LSL`/`LSR` `Rd,sh` | `base \| ((sh >> 1) << 9) \| ((sh & 1) << 4) \| Rd`, base = `0xA140` / `0xA160` / `0xA180` |
| `ADD`/`SUB` `Rd,Rs,Ri << sh` | triadic, sub8 = 0 / 1, extra nibble = `sh` |
| `MUL`/`DIVS`/`DIVU` `Rd,Rs,Ri` | triadic, sub8 = `0x02` / `0x0C` / `0x0D`, extra nibble = 4 / 0 / 0 |
| `LSL`/`LSR` `Rd,Rs,sh` | dyadic, tail = `0x1500 \| sh` / `0x1600 \| sh` |
| `CLZ`/`CP.H` `Ra,Rb` | dyadic, tail = `0x1200` / `0x1900` |
| `ANDL`/`ANDH` `Rd,imm` | `extended(0xE01 / 0xE41, Rd, imm)` |
| `BFEXTU Rd,Rs,off,wid` | `111 \| Rd(28..25) \| 0x1D(24..20) \| Rs(19..16) \| (0xC000 \| off << 5 \| wid)` |

### Register lists

`LDM Rb++,<list>` = `(0xE3C0 \| Rb) << 16 \| mask`, `STM --Rb,<list>` =
`(0xEBC0 \| Rb) << 16 \| mask`, where `mask` sets one bit per register number.
All fourteen `LDM`/`STM` "shapes" the counter reports are this one encoding —
it just splits them by register-list length.

where **triadic** is `111 \| Rs(28..25) \| Ri(19..16) \| sub8(15..8) \| extra4(7..4) \| Rd(3..0)`
— the same layout as an indexed load/store — and **dyadic** is
`111 \| Rb(28..25) \| Ra(19..16) \| tail16`.

Two ordering traps here:

- **`dyadic` reverses the text order.** In `CLZ Rd,Rs` it is the *second*
  operand that lands in the high field at bits 28..25.
- **`BFEXTU` does the opposite** — its *first* operand takes the high field.
  Two neighbouring instruction families, opposite conventions.

## Coverage caveat

100% of the corpus is not 100% of the instruction set. Three limits:

- **One build never covers the whole program.** `finish()` prints a block's
  listing only when that block is enabled, and a branch guarded by
  `!feature(x)` is unreachable in any build where `x` is on. `corpus.json` is
  therefore merged across several configurations —
  `build/assemble*.log`, deduped — including runs that later failed, since the
  part they did assemble is still valid Ghidra output.
- **Operand ranges are proven only where the corpus exercises them.** See below.

## Open questions

- **`MOV Rd,imm21` sign.** The scatter is derived and exact
  (k[20:17] -> bits 28..25, k[16] -> bit 20, base `0xE0600000`), but only
  non-negative values are accepted: every extended `MOV` in the corpus is
  positive, negatives all fitting the compact signed imm8. A negative imm21
  returns null rather than guessing.
- **`LDM`/`STM` base register.** Only SP has ever been the base, so bits 19..16
  = Rb is an inference — well-supported (that nibble reads exactly 13 in all
  22 forms) but not proven.
- **Extended `SUB` range boundaries** — the positive/negative opcode split is
  proven only over the observed values (0..0x1e4, -0x1000..-0xf2). A value
  outside those would be caught by the corpus test, not by the encoder. The
  same caveat applies to the extended `BR{cc}` sign split.
- **The corpus only covers what the current config builds.** `CASTS.H`, `MFSR`
  and `ORH` appear in `AssemblePressureFix.java` but not in the corpus,
  because the blocks holding them were skipped by `config/218e.toml` on the
  build that produced the log. Before trusting the encoder against arbitrary
  configs, regenerate the corpus from a build with every feature enabled.

## Remaining shapes, by volume

**Register-mask (66)** — `LDM R++,...` and `STM --R,...` with 2 to 9
registers. One encoding, not fourteen: the register list becomes a bitmask.

**`MOV Rd,imm21` (1)** — see Open questions.

## Design rule

An unsupported shape returns `null`, never a guess. A wrong encoding of the
right width would shift nothing visible and corrupt the image silently; `null`
fails loudly. `FAIL` in the test output is a defect, `skip` is a to-do.
