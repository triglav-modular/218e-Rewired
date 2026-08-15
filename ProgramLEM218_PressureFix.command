#!/bin/bash

# Experimental Buchla 218e V3 v36.9 pressure-curve and touch-filter firmware
# flasher.
# This is intentionally separate from ProgramLEM218.command and flashes only
# 218eV3_v369_PressureFix_DFU.hex after verifying its SHA-256 checksum.

set -o pipefail

DFU_SESSION_ACTIVE=0
FLASH_VALIDATED=0

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
LOG_FILE="$SCRIPT_DIR/LEM218_PressureFix_fwflash_log.txt"
EXPECTED_SHA256="426ff750d48187f8d04f9bbe11115d8bc5de3ede05ad38edd941e95e0bec0c2c"

# Support launching from either the package root or its mac directory.
if [ -d "$SCRIPT_DIR/mac/support" ]; then
    RUNTIME_DIR="$SCRIPT_DIR/mac"
elif [ -d "$SCRIPT_DIR/support" ]; then
    RUNTIME_DIR="$SCRIPT_DIR"
else
    echo "Could not find the mac/support directory next to this script."
    echo "Keep this command inside the 218ev3-Firmware-Flashing package."
    read -r -p "Press return to close. "
    exit 1
fi

FIRMWARE="$RUNTIME_DIR/firmware/218eV3_v369_PressureFix_DFU.hex"
SENDMIDI="$RUNTIME_DIR/support/sendmidi"
DFU_BUNDLED="$RUNTIME_DIR/support/buchla-dfu/dfu/dfu-programmer"
DFU_SYSTEM="$RUNTIME_DIR/support/dfu-programmer"

timestamp() {
    date '+%Y-%m-%d %H:%M:%S'
}

log() {
    echo "[$(timestamp)] $*" | tee -a "$LOG_FILE"
}

fail() {
    log "ERROR: $*"
    if [ "$DFU_SESSION_ACTIVE" -eq 1 ] && [ "$FLASH_VALIDATED" -eq 0 ]; then
        echo
        echo "RECOVERY-SAFE STOP"
        echo "Do NOT run 'start'. Do NOT disconnect or power-cycle the 218e."
        echo "Leave it in DFU mode and run this PressureFix command again."
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
log "Starting Buchla LEM218 PressureFix programming."
log "Runtime directory: $RUNTIME_DIR"

# Prevent macOS idle/system sleep for the lifetime of this launcher. The
# instrument still needs its own stable power source throughout programming.
if command -v caffeinate >/dev/null 2>&1; then
    caffeinate -dimsu -w $$ >/dev/null 2>&1 &
    log "macOS sleep prevention enabled for this programming session."
fi

[ "$(uname -s)" = "Darwin" ] || fail "This launcher is for macOS."
[ -f "$FIRMWARE" ] || fail "Patched firmware is missing: $FIRMWARE"
[ -x "$SENDMIDI" ] || fail "sendmidi is missing or not executable: $SENDMIDI"

actual_sha256="$(shasum -a 256 "$FIRMWARE" | awk '{print $1}')"
[ "$actual_sha256" = "$EXPECTED_SHA256" ] || \
    fail "Firmware checksum mismatch; refusing to erase the instrument."
log "Verified patched firmware SHA-256: $actual_sha256"

# Match the selection logic used by Buchla's original command. The bundled
# version carries its own libusb; the other build uses Homebrew's libusb.
if [ -d /usr/local/opt/libusb ] && [ -x "$DFU_SYSTEM" ]; then
    DFUPATH="$DFU_SYSTEM"
else
    DFUPATH="$DFU_BUNDLED"
fi
[ -x "$DFUPATH" ] || fail "dfu-programmer is missing or not executable: $DFUPATH"
log "Using dfu-programmer: $DFUPATH"

echo
echo "This is EXPERIMENTAL firmware based on Buchla 218e V3 v36.9."
echo "Ordinary edit mode now provides a complete saved pressure calibration:"
echo "  knob 1 = full-pressure point (turn right for greater sensitivity)"
echo "  knob 3 = pressure floor/deadband, now covering raw 0..1023"
echo "  knob 4 = curve, from linear (left) to a strong threshold curve (right)"
echo "Falling sensor readings now refresh every scan instead of every tenth"
echo "scan, removing the original periodic peak-hold behavior."
echo "Pressure is averaged over sixteen raw samples, normalized between the"
echo "saved floor and full-pressure point, and then curved. The old second gain"
echo "stage is removed, so the curve no longer depends on a guessed sensor range."
echo "Each new touch seeds the filter immediately, avoiding a time-based ramp and"
echo "preventing one touch from inheriting the previous touch's pressure."
echo "While ordinary edit mode and USB MIDI are active, a rate-limited diagnostic"
echo "stream reports the held key's two pre-subtraction scan components."
echo "These diagnostic values do not alter the pressure output in this build."
echo "Run ReadLEM218_Pressure.command after flashing to view and record it."
echo "Minimum keyboard velocity is fixed at its full-range value; its ordinary"
echo "knob-3 setting is repurposed as the pressure floor."
echo "Pad 3 + knob 3 still controls minimum arpeggiation velocity."
echo "The special key-sensitivity mode of knob 1 is also preserved."
echo
echo "After flashing, calibrate once in ordinary edit mode:"
echo "  1. Turn knob 4 fully left for a linear response."
echo "  2. Run ReadLEM218_Pressure.command. With no key held, adjust knob 1 and"
echo "     type 'settings' until the reported ceiling is near 840."
echo "  3. Adjust knob 3 until 'settings' reports a floor near 580."
echo "  4. Verify light/mid/max touches, then turn knob 4 right to taste."
echo "  5. Leave edit mode to save all three parameters."
echo
echo "Before continuing:"
echo "  - use stable instrument power; do not switch off the boat"
echo "  - connect USB directly if possible; avoid a loose cable or unpowered hub"
echo "  - do not unplug anything until the script reports verified success"
echo "  - if any operation fails, leave the 218e in DFU and rerun this command"
echo
read -r -p "Press return to continue with the connected 218e. "

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
log "Erasing AT32UC3B1256 application flash."
run_logged "$DFUPATH" at32uc3b1256 erase || fail "Chip erase failed."

log "Flashing only: $FIRMWARE"
run_logged "$DFUPATH" at32uc3b1256 flash --suppress-bootloader-mem "$FIRMWARE" || \
    fail "Firmware programming failed. Do not disconnect; retry the flasher while the unit remains in DFU mode."

FLASH_VALIDATED=1
log "Programming and dfu-programmer read-back validation completed successfully."
sleep 3
echo
echo "The patched application has passed read-back validation."
echo "Only now is it safe to leave DFU mode."
read -r -p "Press return to send START and restart the 218e. "
run_logged "$DFUPATH" at32uc3b1256 start || fail "The DFU start command failed."
DFU_SESSION_ACTIVE=0

sleep 4
if "$SENDMIDI" list 2>/dev/null | grep -q "218e"; then
    log "The 218e returned as a MIDI device. Finished."
else
    log "Programming finished, but the 218e MIDI port is not visible yet. Power-cycle the instrument if needed."
fi

echo
echo "PressureFix flashing is complete."
read -r -p "Press return to close. "
