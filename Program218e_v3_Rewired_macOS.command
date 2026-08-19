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
EXPECTED_SHA256="ee6ae7dc0e959b9e9492b652c25f5066aab79d9e282df4b5986120c6fe528078"
# Buchla's own v36.9 image.  Recognised so that going back to stock is an
# offered choice rather than something to be identified by hand.
FACTORY_SHA256="565f2d0c3466edfd13ddc1626cb7a74204723ff3a01f65eac34a9db99901dd47"
FIRMWARE_VERSION="Rewired 1.0.0 (ee6ae7dc)"

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
DFU_BUNDLED="$RUNTIME_DIR/support/dfu/bin/dfu-programmer"

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
# The banana is shown twice - once over the warning, once over the result -
# so it lives in one place.  A quoted heredoc keeps every backslash and
# caret in the art literal.
banana() {
cat <<'BANANA'
                                  .-==-:
                                 -=:...-=:
                               .=-      .=:
                               =          =
                              -=  ^    ^  =:
                              =-          =-
                             :=.   \__/   =-
                             =-           =-
                            -=           .=:
                      .:---===:          :=.
                   .--:.  -=-:==:       .-=
                  :=:    -=-   -=- :-=--:-=
                 :=.   :==:    :==-:      =-
                 ==  :==-      -==:       ==
 .:::::::.......:==-==:      .=: =-       -=
:=--=::::---------:.       .--.  -=       ==
== .=:.                  :--.    -=      .=:
:=-=:::----:::......::-=-:       -=      -=
 :==:      .....:::...        .:===     -=.
   :-==:..                .:-==-.-=    -=.
      .:-====----------===--:.   :=-:-=:
           .:---=====--:.          ::.
BANANA
}

echo ""
banana
echo ""
echo "======================================================================"
echo "  THIS FIRMWARE IS ONLY FOR THE BUCHLA 218e V3"
echo ""
echo "  It won't work on the 218, the 218r, the 218e v1 or v2, or any other"
echo "  touchplate controller."
echo ""
echo "  USING THIS TOOL AND FIRMWARE IS ENTIRELY AT YOUR OWN RISK."
echo ""
echo "  This is an experimental, unofficial firmware, not made or supported"
echo "  by Buchla.  It also probably voids your warranty.  It has been tested"
echo "  on ONE instrument.  It can brick your keyboard.  Recovering a bricked"
echo "  unit may need JTAG hardware and opening the instrument, or may not be"
echo "  possible at all."
echo ""
echo "  A failed flash usually leaves the keyboard in DFU mode, where the"
echo "  flasher can try again, but there is no guarantee that it will"
echo "  succeed.  If losing the use of your 218e would be a problem, stop"
echo "  here and keep the factory firmware."
echo ""
echo "  No warranty of any kind.  Not the authors, nor Buchla is liable for"
echo "  damage, loss of use, or a keyboard that no longer works."
echo "======================================================================"
echo ""
read -r -p "  Type YES (capitals) to accept and continue: " consent
[ "$consent" = "YES" ] || fail "Not confirmed. Nothing was changed."

step "Locating the firmware image"
FIRMWARE=""
CUSTOM_IMAGE=0

if [ -n "${1:-}" ]; then
    [ -f "$1" ] || fail "No such file: $1"
    case "$(validate_hex "$1")" in
        OK*) ;;
        *) echo "  ${C_RED}$(validate_hex "$1")${C_RESET}"
           fail "That file is not a flashable 218e image. The instrument was not touched." ;;
    esac
    accept_choice "$1"
fi
# Every .hex the flasher can see, newest first, structurally valid, deduped by
# resolved path.  One list, one ordering, so what is offered and what is chosen
# can never disagree.
scan_images() {
    # Sort by mtime here rather than with ls -t: given an unmatched glob among
    # its operands, BSD ls groups the results by directory instead of sorting
    # them together, which silently puts an older image above a newer one.
    # Trailing slashes are stripped so the same file reached through two
    # patterns dedupes as one string.
    {
        for dir in "${FIRMWARE_DIR%/}" "${SCRIPT_DIR%/}" \
                   "$HOME/Downloads" "$HOME/Desktop"; do
            for candidate in "$dir"/*.hex; do
                [ -f "$candidate" ] || continue
                printf '%s\t%s\n' "$(stat -f '%m' "$candidate" 2>/dev/null || echo 0)" \
                                   "$candidate"
            done
        done
    } | sort -rn -k1,1 | cut -f2- | awk '!seen[$0]++' |
    while IFS= read -r candidate; do
        case "$(validate_hex "$candidate")" in OK*) printf '%s\n' "$candidate" ;; esac
    done | head -12
}

# Accept a chosen image.  The flasher installs any valid 218e image; the
# checksum it was built with is only a label, marking the build that shipped
# with this package so it can be told apart in the list.  It is not a gate.
accept_choice() {
    local path="$1" sha
    sha="$(shasum -a 256 "$path" | cut -d" " -f1)"
    # The installed-record must name the image that actually went in, not the
    # default this flasher was built for.
    actual_sha256="$sha"
    FIRMWARE="$path"
    case "$sha" in
        "$EXPECTED_SHA256")
            ok "$FIRMWARE_VERSION" ;;
        "$FACTORY_SHA256")
            CUSTOM_IMAGE=1
            FIRMWARE_VERSION="factory firmware v36.9"
            ok "$FIRMWARE_VERSION"
            echo "    ${C_YELLOW}This is Buchla's stock image: it removes every Rewired change.${C_RESET}" ;;
        *)
            CUSTOM_IMAGE=1
            FIRMWARE_VERSION="image ${sha:0:8}"
            ok "$FIRMWARE_VERSION" ;;
    esac
    echo "    ${C_DIM}$sha${C_RESET}"
}

if [ -z "$FIRMWARE" ]; then
    images="$(scan_images)"
    count=0
    [ -n "$images" ] && count="$(printf '%s\n' "$images" | wc -l | tr -d ' ')"

    if [ "$count" -gt 1 ]; then
        # More than one flashable image is in reach.  Picking silently is how
        # the wrong firmware gets installed — a stale build in firmware/ would
        # always win on checksum alone — so list them and let the choice be
        # made explicitly.  Newest first, because that is usually the intent.
        echo
        echo "  ${C_BOLD}$count flashable images found.${C_RESET}  Newest first:"
        echo
        i=0
        while IFS= read -r candidate; do
            i=$((i + 1))
            sha="$(shasum -a 256 "$candidate" | cut -d" " -f1)"
            when="$(stat -f '%Sm' -t '%Y-%m-%d %H:%M' "$candidate" 2>/dev/null)"
            case "$sha" in
                "$EXPECTED_SHA256")
                    mark="  ${C_GREEN}<- the default Rewired build${C_RESET}" ;;
                "$FACTORY_SHA256")
                    mark="  ${C_YELLOW}<- FACTORY firmware, back to stock v36.9${C_RESET}" ;;
                *)  mark="" ;;
            esac
            printf '    %d) %s   %s\n' "$i" "$when" "${sha:0:8}"
            printf '       %s%s\n' "$candidate" "$mark"
            i2=$i
        done <<EOF
$images
EOF
        echo
        read -r -p "  Which one? [1-$count, or return to stop] " pick
        [ -n "$pick" ] || fail "Nothing chosen. The instrument was not touched."
        case "$pick" in
            ''|*[!0-9]*) fail "Not a number. The instrument was not touched." ;;
        esac
        [ "$pick" -ge 1 ] && [ "$pick" -le "$count" ] || \
            fail "No image $pick in the list. The instrument was not touched."
        chosen="$(printf '%s\n' "$images" | sed -n "${pick}p")"
        accept_choice "$chosen"
    elif [ "$count" -eq 1 ]; then
        accept_choice "$images"
    fi
fi

if [ -z "$FIRMWARE" ]; then
    echo
    echo "  Looked in firmware/, beside this script, Downloads and Desktop."
    echo "  No flashable 218e image is there."
    echo
    echo "  No firmware ships with this package — the patched image is Buchla's"
    echo "  firmware with our changes in it, so it is not ours to redistribute."
    echo "  Build one from your own factory image with the page in web/ and"
    echo "  save it to Downloads, or build locally:"
    echo "    ${C_BOLD}python3 tools/build.py --no-ghidra${C_RESET}"
    echo "  which writes ${C_BOLD}build/218eV3_v369_Rewired_DFU.hex${C_RESET} — deliberately outside"
    echo "  the searched folders, so copy it into firmware/ to flash it."
    echo
    echo "  Or point this at one: drag its .hex into this window, or press"
    echo "  return to stop."
    read -r -p "  Image to flash: " other
    # Terminal drag-and-drop appends a space and may escape spaces in the path.
    other="$(printf '%s' "$other" | sed 's/\\//g; s/[[:space:]]*$//')"
    [ -n "$other" ] || fail "No firmware image found."
    [ -f "$other" ] || fail "No such file: $other"
    case "$(validate_hex "$other")" in
        OK*) ;;
        *) echo "  ${C_RED}$(validate_hex "$other")${C_RESET}"
           fail "That file is not a flashable 218e image. The instrument was not touched." ;;
    esac
    accept_choice "$other"
fi
ok "Found $(basename "$FIRMWARE")"
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

# The bundled dfu-programmer is universal and carries its own libusb, so it
# runs natively on both architectures with nothing installed.  Buchla's command
# preferred a Homebrew build when /usr/local/opt/libusb existed, which only
# mattered while the bundled one was x86_64-only.  There is no longer a system
# fallback: the one that used to sit here was Buchla's x86_64 1.0.0, linked
# against /usr/local/opt/libusb, so it could only ever have loaded on an Intel
# Mac with Homebrew libusb installed.  Failing here with the path named beats
# falling through to a binary that cannot start.
DFUPATH="$DFU_BUNDLED"
[ -x "$DFUPATH" ] || fail "dfu-programmer is missing or not executable: $DFUPATH"
log "Using dfu-programmer: $DFUPATH"


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
    echo "  These tools are quarantined because the package was downloaded."
    echo "  macOS will not run them until that is cleared."
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
read -r -p "Press return to begin the chip erase and flash. "
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

# Clear first so the good news is the first line in the window rather than
# the last line of a long scroll.  Only on the success path, and only
# after read-back validation has already passed.
clear 2>/dev/null || true
echo "${C_GREEN}${C_BOLD}Flashing successful, enjoy.${C_RESET}"
echo
banana
echo
echo "  ${C_BOLD}$FIRMWARE_VERSION${C_RESET} is now on the instrument."
echo "  ${C_DIM}Log: $LOG_FILE${C_RESET}"
echo
read -r -p "  Press return to close. "
