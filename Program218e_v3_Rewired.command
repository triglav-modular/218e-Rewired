#!/bin/bash

# Experimental Buchla 218e V3 v36.9 pressure-curve and touch-filter firmware
# flasher.
# This is intentionally separate from ProgramLEM218.command and flashes only
# 218eV3_v369_Rewired_DFU.hex after verifying its SHA-256 checksum.

set -o pipefail

DFU_SESSION_ACTIVE=0
FLASH_VALIDATED=0

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
LOG_FILE="$SCRIPT_DIR/218e_v3_Rewired_flash_log.txt"
EXPECTED_SHA256="5d9a352e380e9ad26e354408755e2be150ccdd8ae1a3957950ff2ae5373ec78b"

# Support launching from either the package root or its mac directory.  The
# macOS tools live under mac/, but the firmware image is shared with the
# Windows flasher and sits at the package root.
if [ -d "$SCRIPT_DIR/mac/support" ]; then
    RUNTIME_DIR="$SCRIPT_DIR/mac"
    PACKAGE_ROOT="$SCRIPT_DIR"
elif [ -d "$SCRIPT_DIR/support" ]; then
    RUNTIME_DIR="$SCRIPT_DIR"
    PACKAGE_ROOT="$(dirname "$SCRIPT_DIR")"
else
    echo "Could not find the mac/support directory next to this script."
    echo "Keep this command inside the 218ev3-Firmware-Flashing package."
    read -r -p "Press return to close. "
    exit 1
fi

FIRMWARE_DIR="$PACKAGE_ROOT/firmware"
FIRMWARE_NAME="218eV3_v369_Rewired_DFU.hex"
SENDMIDI="$RUNTIME_DIR/support/sendmidi"
DFU_BUNDLED="$RUNTIME_DIR/support/buchla-dfu/dfu/dfu-programmer"
DFU_SYSTEM="$RUNTIME_DIR/support/dfu-programmer"

# Colour only when attached to a terminal, so the log file stays plain text.
if [ -t 1 ] && [ -z "${NO_COLOR:-}" ]; then
    C_RESET=$'\033[0m'; C_DIM=$'\033[2m'; C_BOLD=$'\033[1m'
    C_GREEN=$'\033[32m'; C_RED=$'\033[31m'; C_YELLOW=$'\033[33m'; C_BLUE=$'\033[36m'
else
    C_RESET=; C_DIM=; C_BOLD=; C_GREEN=; C_RED=; C_YELLOW=; C_BLUE=
fi

TOTAL_STEPS=6
STEP=0

timestamp() {
    date '+%Y-%m-%d %H:%M:%S'
}

log() {
    echo "[$(timestamp)] $*" >> "$LOG_FILE"
    echo "${C_DIM}$*${C_RESET}"
}

# A step banner plus a progress bar, so it is obvious how far along this is and
# — more usefully — which steps have already passed.
step() {
    STEP=$((STEP + 1))
    local filled=$((STEP * 24 / TOTAL_STEPS)) bar= i=0
    while [ "$i" -lt 24 ]; do
        if [ "$i" -lt "$filled" ]; then bar="$bar█"; else bar="$bar·"; fi
        i=$((i + 1))
    done
    echo
    echo "${C_BLUE}${bar}${C_RESET} ${C_DIM}${STEP}/${TOTAL_STEPS}${C_RESET}  ${C_BOLD}$*${C_RESET}"
    echo "[$(timestamp)] === step $STEP/$TOTAL_STEPS: $* ===" >> "$LOG_FILE"
}

ok() {
    echo "  ${C_GREEN}✓${C_RESET} $*"
    echo "[$(timestamp)] OK: $*" >> "$LOG_FILE"
}

warn() {
    echo "  ${C_YELLOW}!${C_RESET} $*"
    echo "[$(timestamp)] WARN: $*" >> "$LOG_FILE"
}

# Run a long command, showing a spinner while it works.  dfu-programmer writes
# its own progress to stderr, which is kept in the log rather than shown, so
# the screen stays readable.
spin() {
    local label="$1"; shift
    "$@" >> "$LOG_FILE" 2>&1 &
    local pid=$! frames='|/-\' i=0
    if [ -t 1 ]; then
        while kill -0 "$pid" 2>/dev/null; do
            i=$(((i + 1) % 4))
            printf '\r  %s %s' "${frames:$i:1}" "$label"
            sleep 0.1
        done
        printf '\r%*s\r' $((${#label} + 6)) ''
    else
        wait "$pid"
    fi
    wait "$pid"
}

fail() {
    echo
    echo "  ${C_RED}✗ $*${C_RESET}"
    echo "[$(timestamp)] ERROR: $*" >> "$LOG_FILE"
    if [ "$DFU_SESSION_ACTIVE" -eq 1 ] && [ "$FLASH_VALIDATED" -eq 0 ]; then
        echo
        echo "RECOVERY-SAFE STOP"
        echo "Do NOT run 'start'. Do NOT disconnect or power-cycle the 218e."
        echo "Leave it in DFU mode and run this Rewired command again."
        echo "If power was already lost, reconnect power: ISP_FORCE should return it to DFU."
        log "No START command was sent; the 218e was intentionally left in DFU mode."
    fi
    read -r -p "Press return to close. "
    exit 1
}

interrupted() {
    echo
    if [ "$DFU_SESSION_ACTIVE" -eq 1 ] && [ "$FLASH_VALIDATED" -eq 0 ]; then
        log "Interrupted before validated flashing completed. No START command will be sent."
        echo "Leave the 218e connected and rerun this command; it should remain in DFU mode."
    else
        log "Interrupted; exiting."
    fi
    exit 130
}

trap interrupted INT TERM HUP

check_dfu_device() {
    ioreg -p IOUSB -w0 2>/dev/null | grep -q "AT32UC3B"
}

check_218_usb_device() {
    ioreg -p IOUSB -w0 2>/dev/null | grep -q "218e"
}

wait_for_dfu_device() {
    waited=0
    while [ "$waited" -lt 60 ]; do
        check_dfu_device && return 0
        sleep 1
        waited=$((waited + 1))
        case "$waited" in
            10|20|30|40|50)
                log "Still waiting for AT32UC3B DFU enumeration (${waited}/60 seconds)."
                ;;
        esac
    done
    return 1
}

run_logged() {
    "$@" 2>&1 | tee -a "$LOG_FILE"
    return ${PIPESTATUS[0]}
}

read_fuse_decimal() {
    fuse_name="$1"
    fuse_label="$2"
    fuse_output="$("$DFUPATH" at32uc3b1256 getfuse "$fuse_name" 2>&1)"
    fuse_status=$?
    printf '%s\n' "$fuse_output" | tee -a "$LOG_FILE" >&2
    [ "$fuse_status" -eq 0 ] || return "$fuse_status"
    printf '%s\n' "$fuse_output" | awk -v label="$fuse_label" \
        'index($0, label) { gsub(/[^0-9]+$/, ""); if (match($0, /[0-9]+$/)) print substr($0, RSTART, RLENGTH) }' | tail -1
}

: > "$LOG_FILE"
log "Starting Buchla LEM218 Rewired programming."
log "Runtime directory: $RUNTIME_DIR"

# Prevent macOS idle/system sleep for the lifetime of this launcher. The
# instrument still needs its own stable power source throughout programming.
if command -v caffeinate >/dev/null 2>&1; then
    caffeinate -dimsu -w $$ >/dev/null 2>&1 &
    log "macOS sleep prevention enabled for this programming session."
fi

[ "$(uname -s)" = "Darwin" ] || fail "This launcher is for macOS."
[ -x "$SENDMIDI" ] || fail "sendmidi is missing or not executable: $SENDMIDI"

# --- find the image -----------------------------------------------------
# Searching several places is safe because the checksum decides: only an image
# matching the one this flasher was generated for is accepted, so a stray .hex
# is skipped rather than flashed.  That is what lets a downloaded file be used
# where it landed, instead of asking anyone to move it.
step "Locating the firmware image"
FIRMWARE=""
try_candidate() {
    [ -f "$1" ] || return 1
    [ "$(shasum -a 256 "$1" | awk '{print $1}')" = "$EXPECTED_SHA256" ] || return 1
    FIRMWARE="$1"
    return 0
}

for candidate in \
    "$FIRMWARE_DIR/$FIRMWARE_NAME" \
    "$SCRIPT_DIR/$FIRMWARE_NAME" \
    "$HOME/Downloads/$FIRMWARE_NAME" \
    "$HOME/Desktop/$FIRMWARE_NAME"
do
    try_candidate "$candidate" && break
done

# Then the newest .hex files in Downloads, so a browser that renamed the file
# to "...(1).hex" still works.
if [ -z "$FIRMWARE" ]; then
    while IFS= read -r candidate; do
        [ -n "$candidate" ] || continue
        try_candidate "$candidate" && break
    done <<EOF
$(ls -t "$HOME/Downloads"/*.hex "$HOME/Desktop"/*.hex 2>/dev/null | head -20)
EOF
fi

if [ -z "$FIRMWARE" ]; then
    echo
    echo "  Looked in firmware/, beside this script, Downloads and Desktop."
    echo "  Nothing there matches the image this flasher installs:"
    echo "    ${C_BOLD}$EXPECTED_SHA256${C_RESET}"
    echo
    echo "  No firmware ships with this package — the patched image is Buchla's"
    echo "  firmware with our changes in it, so it is not ours to redistribute."
    echo "  Build one from your own factory image with the page in web/, or:"
    echo "    ${C_BOLD}python3 tools/build.py --no-ghidra${C_RESET}"
    fail "No matching firmware image found."
fi
ok "Found $(basename "$FIRMWARE")"
ok "Checksum matches the image this flasher installs"

# Keep it where it belongs, so the next run finds it first and the log records
# one canonical location.  A failure here is not fatal: the image was already
# verified, and flashing it from where it sits is equally correct.
if [ "$FIRMWARE" != "$FIRMWARE_DIR/$FIRMWARE_NAME" ]; then
    mkdir -p "$FIRMWARE_DIR" 2>/dev/null
    if cp "$FIRMWARE" "$FIRMWARE_DIR/$FIRMWARE_NAME" 2>/dev/null; then
        FIRMWARE="$FIRMWARE_DIR/$FIRMWARE_NAME"
        ok "Copied into firmware/"
    fi
fi
log "Using $FIRMWARE"

# Match the selection logic used by Buchla's original command. The bundled
# version carries its own libusb; the other build uses Homebrew's libusb.
if [ -d /usr/local/opt/libusb ] && [ -x "$DFU_SYSTEM" ]; then
    DFUPATH="$DFU_SYSTEM"
else
    DFUPATH="$DFU_BUNDLED"
fi
[ -x "$DFUPATH" ] || fail "dfu-programmer is missing or not executable: $DFUPATH"
log "Using dfu-programmer: $DFUPATH"

echo ""
echo "======================================================================"
echo "  THIS IS FOR THE BUCHLA 218e VERSION 3 ONLY, RUNNING v36.9"
echo ""
echo "  Not the 218.  Not the 218r.  Not the 218e v1 or v2.  Not any other"
echo "  touchplate controller.  Flashing this into anything else will not"
echo "  work and may leave it unusable."
echo ""
echo "  YOU DO THIS ENTIRELY AT YOUR OWN RISK."
echo ""
echo "  This is experimental, unofficial firmware.  It is not made by or"
echo "  supported by Buchla.  It has been tested on ONE instrument.  It can"
echo "  brick your keyboard.  Recovery may need JTAG hardware and opening"
echo "  the instrument, and may not be possible at all."
echo ""
echo "  No warranty of any kind.  Nobody is liable for damage, loss of use,"
echo "  or a keyboard that no longer works."
echo "======================================================================"
echo ""
read -r -p "  Type YES (capitals) to accept and continue: " consent
[ "$consent" = "YES" ] || fail "Not confirmed. Nothing was changed."

echo
echo "This is EXPERIMENTAL firmware based on Buchla 218e V3 v36.9."
# --- BEGIN GENERATED SUMMARY (tools/build.py rewrites this block) ---
echo "Ordinary edit mode provides the pressure calibration:"
echo "  knob 1 = pressure calibration, scaling both endpoints (592/893 at centre)"
echo "  knob 3 = factory behaviour"
echo "  knob 4 = curve, linear (left) to full 218r (right), default 0"
echo "Outside edit mode those knobs control the arpeggiator and vibrato."
echo "Arp switch: latch / regular / off. In latch, keys toggle by"
echo "sounding pitch, so any octave position can release a note."
echo "Portamento knob = pressure needed to bend between held notes."
echo ""
echo "Calibrating, in ordinary edit mode:"
echo "  1. Knob 4 fully left for a linear response."
echo "  2. Run ReadLEM218_Pressure.command; with no key held, turn knob 1"
echo "     and type 'settings' until floor/ceiling read near 592/893 — the built-in calibration,"
echo "     at about 78% of knob travel."
echo "  3. Play light/mid/max touches; knob 1 scales the whole window,"
echo "     so one control follows a change in how the instrument couples."
echo "  4. Turn knob 4 right to taste, then leave edit mode to save."
# --- END GENERATED SUMMARY ---
echo
echo "Before continuing:"
echo "  - use stable instrument power; do not switch off the boat"
echo "  - connect USB directly if possible; avoid a loose cable or unpowered hub"
echo "  - do not unplug anything until the script reports verified success"
echo "  - if any operation fails, leave the 218e in DFU and rerun this command"
echo
read -r -p "Press return to continue with the connected 218e. "

step "Putting the instrument into DFU"
if check_dfu_device; then
    log "MIDI port was unavailable, but the 218e is already in DFU mode."
else
    midi_ports="$("$SENDMIDI" list 2>&1)"
    midi_list_status=$?
    printf '%s\n' "$midi_ports" | tee -a "$LOG_FILE"
    if [ "$midi_list_status" -ne 0 ] || ! printf '%s\n' "$midi_ports" | grep -q "218e"; then
        fail "The 218e CoreMIDI output port is unavailable. Nothing was erased; power-cycle the 218e, reconnect USB directly, and retry."
    fi

    log "Asking the 218e to enter DFU mode over MIDI."
    sysex_output="$("$SENDMIDI" dev 218e syx 0 2 55 2 1 1 2>&1)"
    sysex_status=$?
    printf '%s\n' "$sysex_output" | tee -a "$LOG_FILE"
    if [ "$sysex_status" -ne 0 ] || \
       printf '%s\n' "$sysex_output" | grep -Eq "Couldn't find|No valid MIDI|CoreMIDI error"; then
        fail "SendMIDI could not deliver the DFU request. Nothing was erased; power-cycle the 218e and retry."
    fi
    log "DFU SysEx delivered; waiting up to 60 seconds for USB re-enumeration."
fi

if ! wait_for_dfu_device; then
    if check_218_usb_device; then
        fail "The 218e stayed in application mode after the DFU request. Nothing was erased; power-cycle it and retry."
    else
        fail "The AT32UC3B DFU device did not appear on USB. Nothing was erased; reconnect USB directly and retry."
    fi
fi
DFU_SESSION_ACTIVE=1
log "AT32UC3B DFU device detected."

# Each accepted ISP command sets ISP_FORCE=1. START is the only ISP operation
# below that clears it. Keeping it set until read-back validation succeeds is
# what makes an interrupted application flash recoverable over USB.
step "Checking the bootloader and safety fuses"
log "Reading bootloader version and safety fuses before erase."
run_logged "$DFUPATH" at32uc3b1256 get bootloader-version || \
    fail "The DFU bootloader did not answer the version query."

bootprot_value="$(read_fuse_decimal BOOTPROT "Bootloader protected area")"
[ "$bootprot_value" = "3" ] || \
    fail "BOOTPROT is '$bootprot_value', not 3 (8 KiB); refusing to erase."
log "Verified BOOTPROT=3: the 8 KiB DFU bootloader region is protected."

isp_force_value="$(read_fuse_decimal ISP_FORCE "ISP Force")"
[ "$isp_force_value" = "1" ] || \
    fail "ISP_FORCE did not read back as 1; refusing to erase."
log "Verified ISP_FORCE=1: an interrupted session should boot back into DFU."

echo
read -r -p "Press return to begin the chip erase. "
step "Erasing the application flash"
spin "erasing…" "$DFUPATH" at32uc3b1256 erase || fail "Chip erase failed."
ok "Application flash erased"

step "Writing and validating the firmware"
spin "writing and verifying…" "$DFUPATH" at32uc3b1256 flash \
    --suppress-bootloader-mem "$FIRMWARE" || \
    fail "Firmware programming failed. Do not disconnect; retry the flasher while the unit remains in DFU mode."

FLASH_VALIDATED=1
ok "Written and validated by read-back"
log "Programming and dfu-programmer read-back validation completed successfully."
sleep 1

step "Restarting the instrument"
echo "  ${C_GREEN}The patched application has passed read-back validation.${C_RESET}"
echo "  Only now is it safe to leave DFU mode."
echo
read -r -p "  Press return to send START and restart the 218e. "
spin "restarting…" "$DFUPATH" at32uc3b1256 start || fail "The DFU start command failed."
DFU_SESSION_ACTIVE=0

sleep 4
if "$SENDMIDI" list 2>/dev/null | grep -q "218e"; then
    ok "The 218e returned as a MIDI device"
else
    warn "The 218e MIDI port is not visible yet — power-cycle the instrument if needed"
fi

echo
echo "  ${C_GREEN}${C_BOLD}✓ Flashing complete.${C_RESET}"
echo "  ${C_DIM}Log: $LOG_FILE${C_RESET}"
echo
read -r -p "  Press return to close. "
