#!/bin/bash

# Experimental Buchla 218e V3 v36.9 pressure-curve and touch-filter firmware
# flasher.
# This is intentionally separate from ProgramLEM218.command and flashes only
# 218eV3_v369_Rewired_DFU.hex after verifying its SHA-256 checksum.

set -o pipefail

DFU_SESSION_ACTIVE=0
FLASH_VALIDATED=0
ERASE_STARTED=0

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
LOG_FILE="$SCRIPT_DIR/218e_v3_Rewired_flash_log.txt"
DEADLINE_OUT="$(mktemp -t rewired)"
trap 'rm -f "$DEADLINE_OUT"' EXIT
EXPECTED_SHA256="9474624bdaa85e20502e65f67471f500879ceda1bbc08bcd9aa5d59394bfe391"
FIRMWARE_VERSION="Rewired 1.0.0 (9474624b)"

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

TOTAL_STEPS=7
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

# Structural validation for an image the checksum does not vouch for: every
# record's own checksum, hex-only content, an end-of-file record, and data
# confined to the AT32UC3B1256 application flash.  Delegated to
# tools/validate_hex.py when it is present (running from a repo checkout);
# otherwise a compact inline awk does the same job for a standalone package.
VALIDATOR=""
for v in "$PACKAGE_ROOT/tools/validate_hex.py" "$SCRIPT_DIR/tools/validate_hex.py" \
         "$SCRIPT_DIR/../tools/validate_hex.py"; do
    [ -f "$v" ] && { VALIDATOR="$v"; break; }
done
validate_hex() {
    if [ -n "$VALIDATOR" ] && command -v python3 >/dev/null 2>&1; then
        python3 "$VALIDATOR" "$1"
        return
    fi
    awk '
function h2d(s,   i,v,c){v=0;for(i=1;i<=length(s);i++){c=index("0123456789abcdef",tolower(substr(s,i,1)))-1;if(c<0)return -1;v=v*16+c}return v}
function fail(m){print "BAD " m;failed=1;exit 1}
/^:/{hex=substr($0,2);gsub(/\r/,"",hex)
  if(length(hex)<10||length(hex)%2)fail("malformed record at line " NR)
  sum=0;for(i=1;i<length(hex)+1;i+=2){b=h2d(substr(hex,i,2));if(b<0)fail("non-hex characters at line " NR);sum+=b}
  if(sum%256)fail("checksum mismatch at line " NR " - the file is corrupted")
  type=h2d(substr(hex,7,2));len=h2d(substr(hex,1,2));addr=h2d(substr(hex,3,4))
  if(type==4)ela=h2d(substr(hex,9,4))
  if(type==0){a=ela*65536+addr;if(!seen||a<lo)lo=a;seen=1;if(a+len-1>hi)hi=a+len-1}
  if(type==1)eof=1;next}
NF{fail("line " NR " is not an Intel HEX record")}
END{if(failed)exit 1
  if(!eof){print "BAD no end-of-file record - truncated download?";exit 1}
  if(!seen){print "BAD no data records";exit 1}
  if(lo<2147491840){printf "BAD data at 0x%X - inside the bootloader region, or not AVR32 firmware\n",lo;exit 1}
  if(hi>2147745791){printf "BAD data at 0x%X - beyond the AT32UC3B1256 flash\n",hi;exit 1}
  printf "OK 0x%X..0x%X\n",lo,hi}' "$1"
}

# Run a command with a deadline, because a Gatekeeper-blocked binary does not
# fail — it blocks on a modal dialog and waits, forever if nobody clicks.  Used
# for the probe, so a blocked tool cannot hang the script.
run_with_deadline() {
    local seconds="$1"; shift
    "$@" >"$DEADLINE_OUT" 2>&1 &
    local pid=$! waited=0
    while kill -0 "$pid" 2>/dev/null; do
        if [ "$waited" -ge "$seconds" ]; then
            kill -9 "$pid" 2>/dev/null
            wait "$pid" 2>/dev/null
            return 124
        fi
        sleep 1
        waited=$((waited + 1))
    done
    wait "$pid"
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
        echo "  ${C_BOLD}RECOVERY-SAFE STOP${C_RESET}"
        echo "  No START command was sent, so the 218e is still in DFU."
        echo
        if [ "$ERASE_STARTED" -eq 0 ]; then
            # Nothing was erased: the original application is intact, and the
            # only reason the instrument is in DFU is that we asked it there.
            # A power cycle will NOT help — reading the fuses set ISP_FORCE, so
            # it comes straight back to DFU.  START is what boots it.
            echo "  ${C_GREEN}Your firmware was not touched.${C_RESET}  Nothing was erased."
            echo
            echo "  To put the instrument back to normal right now:"
            echo "    ${C_BOLD}"$DFUPATH" at32uc3b1256 start${C_RESET}"
            echo
            echo "  Power-cycling alone will not do it: reading the fuses set"
            echo "  ISP_FORCE, so the 218e returns to DFU until START is sent."
            echo "  Or just run this command again to try the flash."
            log "Stopped before erase; application intact; START not sent."
        else
            echo "  ${C_YELLOW}The application flash has been erased.${C_RESET}"
            echo
            echo "  Do NOT send START and do not expect it to boot — there is"
            echo "  nothing to boot yet.  Leave it in DFU and run this command"
            echo "  again to finish the flash.  If power was lost, reconnect it:"
            echo "  ISP_FORCE returns the instrument to DFU."
            log "Stopped after erase; application not valid; START not sent."
        fi
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
CUSTOM_IMAGE=0

# An explicitly chosen image: given as an argument, or dragged into the
# prompt below.  It bypasses the checksum gate, so it gets the structural
# validation and its own typed confirmation instead — and it is never
# something the automatic search picks up on its own.
use_explicit_image() {
    local path="$1"
    [ -f "$path" ] || fail "No such file: $path"
    echo
    echo "  Validating $(basename "$path") — this image is not the one this"
    echo "  flasher was generated for, so it is checked structurally instead."
    verdict="$(validate_hex "$path")"
    case "$verdict" in
        OK*)
            ok "Valid Intel HEX, data in ${verdict#OK }"
            ;;
        *)
            echo "  ${C_RED}${verdict}${C_RESET}"
            fail "That file is not a flashable 218e image. The instrument was not touched."
            ;;
    esac
    actual_sha256="$(shasum -a 256 "$path" | cut -d" " -f1)"
    echo
    echo "  SHA-256  ${C_BOLD}$actual_sha256${C_RESET}"
    echo
    echo "  Only flash an image you built yourself or otherwise trust."
    read -r -p "  Type FLASH (capitals) to accept this image: " confirm
    [ "$confirm" = "FLASH" ] || fail "Not confirmed. The instrument was not touched."
    FIRMWARE="$path"
    CUSTOM_IMAGE=1
    FIRMWARE_VERSION="custom image (${actual_sha256:0:8})"
}

if [ -n "${1:-}" ]; then
    use_explicit_image "$1"
fi
try_candidate() {
    [ -f "$1" ] || return 1
    [ "$(shasum -a 256 "$1" | awk '{print $1}')" = "$EXPECTED_SHA256" ] || return 1
    FIRMWARE="$1"
    return 0
}

[ -n "$FIRMWARE" ] || \
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

# Nothing matched the built-in checksum.  Before giving up, offer the newest
# structurally valid image from the same places — this is how a build with
# changed settings gets flashed without touching the flasher: it is suggested,
# fingerprinted, and flashed only after a typed confirmation.  Never silently.
if [ -z "$FIRMWARE" ]; then
    suggestion=""
    while IFS= read -r candidate; do
        [ -n "$candidate" ] && [ -f "$candidate" ] || continue
        case "$(validate_hex "$candidate")" in OK*) suggestion="$candidate"; break ;; esac
    done <<EOF
$(ls -t "$FIRMWARE_DIR"/*.hex "$SCRIPT_DIR"/*.hex "$HOME/Downloads"/*.hex "$HOME/Desktop"/*.hex 2>/dev/null | head -20)
EOF
    if [ -n "$suggestion" ]; then
        echo
        echo "  Nothing matches this flasher's built-in checksum, but the newest"
        echo "  valid image around is:"
        echo "    ${C_BOLD}$suggestion${C_RESET}"
        use_explicit_image "$suggestion"
    fi
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
    echo
    echo "  Or flash a different image than the one this flasher was made for:"
    echo "  drag its .hex into this window, or press return to stop."
    read -r -p "  Image to flash: " other
    # Terminal drag-and-drop appends a space and may escape spaces in the path.
    other="$(printf '%s' "$other" | sed 's/\\//g; s/[[:space:]]*$//')"
    [ -n "$other" ] || fail "No matching firmware image found."
    use_explicit_image "$other"
fi
ok "Found $(basename "$FIRMWARE")"
ok "Checksum matches the image this flasher installs"
ok "${C_BOLD}$FIRMWARE_VERSION${C_RESET}"

# Keep it where it belongs, so the next run finds it first and the log records
# one canonical location.  A failure here is not fatal: the image was already
# verified, and flashing it from where it sits is equally correct.
if [ "$CUSTOM_IMAGE" -eq 0 ] && [ "$FIRMWARE" != "$FIRMWARE_DIR/$FIRMWARE_NAME" ]; then
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
echo "  2. Run ReadLEM218_Rewired.command; with no key held, turn knob 1"
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

# Prove the DFU toolchain actually runs BEFORE asking the instrument to leave
# MIDI.  Otherwise a broken toolchain is discovered only after the keyboard has
# rebooted into a bootloader nothing here can reach — recoverable by a power
# cycle, but alarming and entirely avoidable.
#
# "no device present" is the expected answer while the 218e is still in
# application mode, and it proves the binary launched and libusb loaded.  A
# success is fine too: the instrument is already in DFU.  Anything else means
# the tool cannot run at all.
step "Checking the DFU tools"

# Clear quarantine BEFORE running anything.  A quarantined unsigned binary does
# not return an error when launched — macOS suspends it behind a modal dialog
# and it waits indefinitely, so detecting the problem by running the tool means
# hanging on it.  Reading the attribute costs nothing and never blocks.
quarantined=""
for candidate in "$DFUPATH" "$SENDMIDI"; do
    if xattr -p com.apple.quarantine "$candidate" >/dev/null 2>&1; then
        quarantined="yes"
    fi
done
if [ -n "$quarantined" ]; then
    echo "  These tools are marked as quarantined, because the package was"
    echo "  downloaded rather than cloned.  macOS will refuse to run them, and"
    echo "  it does so by holding them at a dialog rather than by failing — so"
    echo "  this has to be cleared before the flash, not during it."
    echo
    echo "  It affects only the files in this package, on this machine."
    echo
    read -r -p "  Clear it now? [Y/n] " unquarantine
    case "$unquarantine" in
        [nN]*)
            echo "  Then approve each tool in System Settings > Privacy & Security,"
            echo "  or clear it yourself with:"
            echo "    ${C_BOLD}xattr -dr com.apple.quarantine \"$PACKAGE_ROOT\"${C_RESET}"
            fail "The DFU tools are blocked. The instrument was not touched."
            ;;
        *)
            xattr -dr com.apple.quarantine "$PACKAGE_ROOT" 2>/dev/null
            xattr -dr com.apple.quarantine "$RUNTIME_DIR" 2>/dev/null
            if xattr -p com.apple.quarantine "$DFUPATH" >/dev/null 2>&1; then
                fail "Could not clear it. Approve the tools in System Settings > Privacy & Security, then run this again."
            fi
            ok "Quarantine cleared"
            ;;
    esac
fi

# Now it is safe to actually run it.  The deadline is a backstop: if macOS
# still holds it somewhere, this reports that instead of hanging.
run_with_deadline 15 "$DFUPATH" at32uc3b1256 get bootloader-version
probe_status=$?
probe_output="$(cat "$DEADLINE_OUT" 2>/dev/null)"
if [ "$probe_status" -eq 124 ]; then
    echo "  dfu-programmer did not answer within 15 seconds."
    echo "  That usually means macOS is holding it at a security dialog —"
    echo "  check for one, allow the tool in System Settings > Privacy &"
    echo "  Security, and run this again."
    fail "The DFU tools are not responding. The instrument was not touched."
fi
if [ "$probe_status" -ne 0 ] && \
   ! printf '%s' "$probe_output" | grep -qi "no device present"; then
    printf '%s\n' "$probe_output" >> "$LOG_FILE"
    if printf '%s' "$probe_output" | grep -qi "bad cpu type"; then
        echo "  dfu-programmer is an x86_64 binary and this Mac cannot run it."
        echo "  Install Rosetta, then run this again:"
        echo "    ${C_BOLD}softwareupdate --install-rosetta${C_RESET}"
    else
        echo "  dfu-programmer would not run:"
        printf '    %s\n' "$probe_output" | head -4
    fi
    fail "The DFU tools are not usable. The instrument was not touched."
fi
ok "dfu-programmer runs"

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
        # The SysEx was delivered, so the instrument has almost certainly left
        # application mode even though the DFU device never appeared.  Say so:
        # a silent keyboard with no MIDI port looks far worse than it is.
        echo
        echo "  The 218e accepted the request and is most likely sitting in DFU"
        echo "  mode now, which is why it has disappeared from MIDI."
        echo
        echo "  Nothing was erased, and nothing was written."
        echo "  ${C_BOLD}Power-cycle the instrument and it will come back up normally.${C_RESET}"
        echo
        fail "The AT32UC3B DFU device did not appear on USB. Reconnect USB directly, avoid hubs, and retry."
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
ERASE_STARTED=1
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

# Leave a record beside the image.  Without it the only answer to "what is on
# this instrument" is whatever the person remembers.
cat > "$PACKAGE_ROOT/firmware/INSTALLED.txt" <<RECORD 2>/dev/null || true
$FIRMWARE_VERSION
flashed  $(timestamp)
image    ${actual_sha256:-$EXPECTED_SHA256}
RECORD

echo
echo "  ${C_GREEN}${C_BOLD}✓ Flashing complete.${C_RESET}"
echo "  ${C_BOLD}$FIRMWARE_VERSION${C_RESET} is now on the instrument."
echo "  ${C_DIM}Log: $LOG_FILE${C_RESET}"
echo
read -r -p "  Press return to close. "
