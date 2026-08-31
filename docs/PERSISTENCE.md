# Preset and sequencer persistence

`persist = true` in `[options]`, which is the default. This saves the four
remapped preset voltages and the sequence, not the factory's settings. The factory preset path is unchanged
when knob remapping is disabled.

## Saving and restarting

Saving is automatic at the end of an edit:

- The sequence saves on finishing WRITE, including directly into normal
  PLAY, or on explicit CLEAR from stopped, recording or playing.
- Bare pad 2 previews the take once and returns to WRITE without saving.
  Preview keeps the WRITE session open. An explicit STOP during preview
  finishes and saves the take; RECORD resumes WRITE without saving, and
  CLEAR saves the cleared take.
- Bare pad 3 backspaces while recording. Even deleting the last step is
  an unfinished edit, not CLEAR: save it by finishing WRITE or using the
  pad-4 + pad-3 CLEAR chord. Releasing a preset meanwhile still saves only
  that preset, not the unfinished sequence.
- A preset saves when its pad is fully released after a new value was
  written. The intermediate touched-but-not-held state is not a release.
- Only changed musical data causes a commit. An unchanged take, an empty
  clear, a pad tap without editing, or a value returned to its old setting
  does not write flash, including when storage is still empty.

The commit runs in the **same control scan** that handles the completed
gesture. There is no idle timer and no requirement to turn the arp off,
stop incoming clocks, stop playback or release other controls.

Each gesture updates only its own part of a completed-edit snapshot.
Releasing one preset does not save another held preset or an unfinished
recording. Leaving record saves the sequence without saving held preset
edits. Simultaneous completions share one record commit.

Flash programming temporarily blocks execution from flash; playback,
output timing and incoming edge capture can pause during the save.
Consequently, saves during a running clock can miss edges. The owner
validated this on the instrument on 2026-08-29 and accepts the dropped
edges: a save happens at the end of a deliberate gesture, which is not a
moment anyone is counting pulses through. The capture ISR,
its thresholds and queued clock state are unchanged, and firmware does not
invent replacement edges. This is immediate gesture saving, **not** a
gapless background writer; see section 14.5 of the
[AT32UC3B datasheet](https://ww1.microchip.com/downloads/en/DeviceDoc/doc32059.pdf).

There is no save-complete LED or power-fail hold-up. Let the gesture and
its flash operation finish before powering off. Power loss during a save
falls back to the previous committed record; unfinished edits are not saved.

Startup loads musical data before GPIO interrupt setup. The sequence always
starts stopped, at its beginning. Recording mode, strip borrowing, touch
history, preset pickup, clock state and pending triggers are not restored.
Warm resets reload too; the factory strip-mode setting is left alone.
Empty/corrupt storage uses initialized defaults.

DFU updates erase this main-array storage. Experimental version-1 raw-RAM
records are intentionally rejected, not migrated; re-enter those presets
and sequences after updating.

## Flash format and failure handling

Eight 512-byte pages occupy `0x8003e000..0x8003efff`. Neither factory settings
at `0x8003f000` nor the bootloader's User Page is written. Each page holds
one version-2 record; multi-byte values are big-endian.

| Offset | Bytes | Meaning |
|---|---:|---|
| `0x00` | 4 | Commit marker `0x32313850`; erased until final commit |
| `0x04` | 2 | Version `2` |
| `0x06` | 2 | Payload length `204` |
| `0x08` | 4 | Nonzero generation, wrapping `0xffffffff` to `1` |
| `0x0c` | 4 | CRC-32/ISO-HDLC |
| `0x10` | 8 | Four presets, `0..1023` |
| `0x18` | 1 | Sequence length, `0..64` |
| `0x19` | 3 | Reserved, zero |
| `0x1c` | 128 | 64 pitches; `0..4095`, rest `0x7ffe`, tie `0x7fff` |
| `0x9c` | 64 | Key indexes `0..28`; rest/tie and inactive keys are zero |
| `0xdc` | 4 | Zero alignment padding |

Unused pitches are zero. CRC covers bytes `0x04..0x0b` followed by
`0x10..0xdb`; it excludes the marker and itself. Polynomial `0xedb88320`,
initial value and final XOR `0xffffffff`. The loader checks marker, version,
length, generation, CRC and active-value bounds before copying musical
state. Serial-number comparison handles generation wrap.

Every attempt uses the factory wrapper at `0x800108fc`:

1. Stage 224 bytes at aligned RAM `0x6300`, with the marker erased.
2. Write to a page-aligned destination with erase enabled.
3. Compare all 224 flash bytes with staging. Never commit a mismatch.
4. Set the staged marker; write the first eight bytes at the same
   page-aligned destination with erase **disabled**.
5. Compare all 224 bytes again and validate the committed record.

The second call changes only the completely erased marker word. The
version/length word and the tail preserved by the wrapper are unchanged.
This follows the EEPROM-emulation procedure in section 14.4.7 of the
[AT32UC3B datasheet](https://ww1.microchip.com/downloads/en/DeviceDoc/doc32059.pdf).
Aligned calls avoid the wrapper's unaligned read/modify/write paths.

Retries never target the newest valid page: at most seven other pages are
attempted, or eight when no valid record exists. Failed bodies and commits
advance the same bounded counter. Exhaustion latches failure instead of
starting another erase loop on the next scan. Another changed, completed
edit re-arms the request; an unchanged gesture does not retry it. This is
not a permanent bad-page blacklist.

Debugger state: `0x62e0` is `0` clean, `1` pending, `2` failed; `0x62e1` is
the last loaded/saved page index (`0xff` if none); `0x62e4` is its generation.
Read-back detects failed writes even without a FLASHC command error. CRC
detects corruption but cannot guarantee detection of every multi-bit fault.
The final commit separates an unverified body from a boot-loadable record.

The completed-edit snapshot is the 204-byte musical payload at
`0x6400..0x64cb`, initialized from restored data (or defaults) on every boot.
`0x62f8` tracks logical mode (preview counts as WRITE); `0x62fe` is the
preview flag. Explicit CLEAR latches an event at `0x62ff`, consumed by the
same scan. Length reaching zero is not used to infer CLEAR.
`0x62f9..0x62fc` latch which presets were edited until each pad is fully
released. `persist_capture` at `0x8001d280` accepts a mask: bits 0–3 select
preset pads and bit 4 selects the sequence. It canonicalizes and compares
only selected data before the save code stages the combined record.

## Verification and remaining bench checks

Run `python3 tools/test_persistence.py` (requires Ghidra). `--quick` omits
only the clock frequency/duty sweep. The runner builds presets-only,
sequencer, clock, and sequencer+clock variants in private `build/` folders,
restores shared build metadata and never invokes a flasher.
For the same sequencer edit/transport checks with persistence disabled, run
`python3 tools/test_persistence.py --mode seq --no-persist --quick` and
repeat with `--mode seq-clock`.

`src/PersistenceRegression.java` executes emitted save/load/CRC instructions,
startup/gesture hooks and the real factory copy wrapper. Only controller
commands, the write-only flash page buffer and physical I/O are modeled.
Coverage includes rotation, no-change saves, retry exhaustion, retained
backups, body/marker power cuts, corruption/bounds, generation wrap,
same-scan clear/record-exit gestures, independent/overlapping preset edits,
saving during record/playback, and cold/warm startup without phantom steps.
It drives real clock/output paths before and after a release save and a
modeled scheduling pause, without fabricating unobserved input events.
`src/PersistenceClockRegression.java` reruns the clock suite while a changed
preset remains held and fails if that unfinished edit writes flash.
Both assemblers and the browser builder must also agree on image bytes.

`src/SequenceEditRegression.java` drives real pad scans and internal/external
clock dispatch: 0/1/4/64-step previews, three arp-switch positions, recorded
order with BLEND turned up, rests/ties, normal looping, preview cancellation,
unarmed pad-4 holds, repeated backspaces, empty-edit save isolation and restart.
It shares the transport suite's narrowly scoped factory soft-float workaround;
the sequencer, gesture and save decisions are not mocked.

The same runner checks independent sequence transport; see [CLOCK.md](CLOCK.md).

On the instrument, verify power cycles immediately after completed edits,
clear-and-restart, warm reset, save latency and interrupted-save recovery.
Release a changed preset while recording and playing; verify that only
completed edits return after restart. Check clock behavior during an actual
save and clean continuation afterward. Preview to completion, cancel a
preview with STOP, and backspace to empty before CLEAR; check the next take
still saves. Emulation does not measure physical
flash, supply collapse, analog conditioning or output timing.
