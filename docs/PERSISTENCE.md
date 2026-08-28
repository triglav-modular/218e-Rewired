# Preset and sequencer persistence

Enable `persist = true` in `[options]`. It remains off by default pending
instrument testing. This saves the four remapped preset voltages and the
sequence, not the factory's settings. The factory preset path is unchanged
when knob remapping is disabled.

## Saving and restarting

Leaving record mode, clearing a nonempty sequence (from stopped, recording,
or playing), or releasing a preset after knob pickup queues a save. Edits
coalesce while busy. Unchanged musical data does not write flash, even if
playback position, touch history or inactive steps changed.

Before powering off:

1. Finish recording and stop the sequence.
2. Switch the arpeggiator OFF and release keys, pads and preset controls.
3. Stop the incoming clock if clock division is enabled.
4. Allow about three seconds of continuous idle time.

The exact threshold is **more than 2.6 seconds**, using the factory CPU
frequency and COUNT. Gates and deferred triggers must be inactive.
Clock-divider builds also require that much time since the last captured
input; the waits run concurrently. Flash programming stalls the CPU, so
saving during playback could lose edges. The working capture ISR is unchanged.

There is no save-complete LED or power-fail hold-up. Powering off before the
idle save completes loses pending edits; the previous committed record
remains available. A newly started performance during the brief flash
operation can still be delayed. This is an idle-save policy, not background
flash programming with real-time guarantees.

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
starting another erase loop on the next scan. Another completed edit
re-arms the request. This is not a permanent bad-page blacklist.

Debugger state: `0x62e0` is `0` clean, `1` pending, `2` failed; `0x62e1` is
the last loaded/saved page index (`0xff` if none); `0x62e4` is its generation.
Read-back detects failed writes even without a FLASHC command error. CRC
detects corruption but cannot guarantee detection of every multi-bit fault.
The final commit separates an unverified body from a boot-loadable record.

## Verification and remaining bench checks

Run `python3 tools/test_persistence.py` (requires Ghidra). `--quick` omits
only the clock frequency/duty sweep. The runner builds presets-only,
sequencer, clock, and sequencer+clock variants in private `build/` folders,
restores shared build metadata and never invokes a flasher.

`src/PersistenceRegression.java` executes emitted save/load/CRC instructions,
startup/gesture hooks and the real factory copy wrapper. Only controller
commands, the write-only flash page buffer and physical I/O are modeled.
Coverage includes rotation, no-change saves, retry exhaustion, retained
backups, body/marker power cuts, corruption/bounds, generation/COUNT wrap,
clear gestures, preset editing during record, and cold/warm startup without
phantom steps. `src/PersistenceClockRegression.java` reruns the clock suite
with a pending save and fails if playback enters flash code. Both assemblers
and the browser builder must also agree on the image bytes.

On the instrument, verify preset/sequence power cycles, clear-and-restart,
warm reset, save latency and interrupted-save recovery. Check square and
descending-saw clocks over 0.5–200 Hz while edits are pending, then stop
playback/clock and confirm they save. Emulation does not measure physical
flash, supply collapse, analog conditioning or output timing.
