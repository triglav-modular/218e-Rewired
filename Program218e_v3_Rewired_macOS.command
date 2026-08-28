#!/bin/bash

# Experimental Buchla 218e V3 v36.9 pressure-curve and touch-filter firmware
# flasher.
# This is intentionally separate from ProgramLEM218.command.  It flashes any
# structurally valid 218e V3 image; what the checksum decides is what each one is
# called in the list, not whether it may be used.

set -o pipefail

DFU_SESSION_ACTIVE=0
FLASH_VALIDATED=0
ERASE_STARTED=0

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# Inside a signed .app everything here is sealed: writing the log or the
# firmware folder next to the script would break the signature on the first
# run and Gatekeeper would refuse the app from then on.  The launcher sets
# REWIRED_WORKDIR to somewhere writable; run loose from a folder, nothing
# sets it and the script keeps its own directory as before.
WORK_DIR="${REWIRED_WORKDIR:-$SCRIPT_DIR}"
mkdir -p "$WORK_DIR" 2>/dev/null
LOG_FILE="$WORK_DIR/218e_v3_Rewired_flash_log.txt"
DEADLINE_OUT="$(mktemp -t rewired)"
trap 'rm -f "$DEADLINE_OUT"; printf "\033[?25h"' EXIT
EXPECTED_SHA256="de7f98e755ac6903aeeaa10551ebfb0062fb448411c52282071bb6a2cfea316d"
# Buchla's own v36.9 image.  Recognised so that going back to stock is an
# offered choice rather than something to be identified by hand.
FACTORY_SHA256="565f2d0c3466edfd13ddc1626cb7a74204723ff3a01f65eac34a9db99901dd47"
FIRMWARE_VERSION="Rewired 2.0.0 (de7f98e7)"

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
    echo "Keep this command inside the 218e V3v3-Firmware-Flashing package."
    read -r -p "Press return to close. "
    exit 1
fi

FIRMWARE_DIR="${REWIRED_WORKDIR:-$PACKAGE_ROOT}/firmware"

# The builder page normally stamps this script with the checksum of the image
# it built.  It cannot stamp the signed app - editing a byte of it breaks the
# seal and Gatekeeper stops trusting it - so a download that carries the app
# names its image in a file beside the firmware instead, and we read it here.
# Anyone can edit that file, but anyone can edit a stamped script too, so this
# gives up nothing: both catch a download that arrived damaged or got mixed up
# with another build, which is what the check is for.
MANIFEST="$FIRMWARE_DIR/image.txt"
if [ -f "$MANIFEST" ]; then
    while IFS='=' read -r key value; do
        case "$key" in
            EXPECTED_SHA256)
                case "$value" in
                    [0-9a-f]*) [ ${#value} -eq 64 ] && EXPECTED_SHA256="$value" ;;
                esac ;;
            FIRMWARE_VERSION)
                [ -n "$value" ] && FIRMWARE_VERSION="$value" ;;
        esac
    done < "$MANIFEST"
fi
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
    # dfu-programmer wants a newline after every record including the last:
    # `if ('\n' != c) return -7`.  awk cannot see whether the file ended with
    # one, so it is checked here.
    if [ -n "$(tail -c 1 "$1")" ]; then
        echo "BAD the last record has no newline after it - the flasher refuses that, and it erases before it reads"
        return 1
    fi
    awk '
function h2d(s,   i,v,c){v=0;for(i=1;i<=length(s);i++){c=index("0123456789abcdef",tolower(substr(s,i,1)))-1;if(c<0)return -1;v=v*16+c}return v}
function fail(m){print "BAD " m;failed=1;exit 1}
BEGIN{FLASH=2147483648}
  # A carriage return anywhere but the very end means the line endings are
  # bare CRs, and the parser reads one character past the \r expecting \n.
  {cr=index($0,"\r"); if(cr && cr!=length($0))fail("carriage return inside line " NR " - line endings must be LF or CRLF")}
  # A blank line is read, not skipped: sscanf finds nothing and the parse
  # fails.  After the end-of-file record nothing is read at all.
  /^[[:space:]]*$/{if(!eof)fail("blank line at " NR " - every line before the end-of-file record has to be a record");next}
/^:/{hex=substr($0,2);gsub(/\r/,"",hex)
  if(length(hex)<10||length(hex)%2)fail("malformed record at line " NR)
  sum=0;for(i=1;i<length(hex)+1;i+=2){b=h2d(substr(hex,i,2));if(b<0)fail("non-hex characters at line " NR);sum+=b}
  if(sum%256)fail("checksum mismatch at line " NR " - the file is corrupted")
  type=h2d(substr(hex,7,2));len=h2d(substr(hex,1,2));addr=h2d(substr(hex,3,4))
  if(eof)fail("records after the end-of-file record at line " NR " - the flasher stops there and would never write them")
  # dfu-programmer lets type 4 and type 5 both set the address offset, masking
  # bit 31 off.  FLASH puts it back, so everything below is in flash space.
  # intel_validate_line pins these two exactly.
  if(type==1&&len!=0)fail("end-of-file record at line " NR " carries " len " bytes - it must carry none")
  if(type==4&&(addr!=0||len!=2))fail("type 4 record at line " NR " has address " addr " and " len " bytes - it must be address 0 and 2 bytes")
  if(type==5&&(addr!=0||len!=4))fail("type 5 record at line " NR " has address " addr " and " len " bytes - it must be address 0 and 4 bytes")
  if(type==4)base=(h2d(substr(hex,9,4))*65536)%FLASH
  if(type==5)base=h2d(substr(hex,9,8))%FLASH
  if(type!=0&&type!=1&&type!=4&&type!=5)fail("record type " type " at line " NR " - not an AVR32 firmware image")
  if(len*2+10!=length(hex))fail("record at line " NR " declares " len " bytes but carries " (length(hex)-10)/2)
  if(type==0){a=(base+addr)%FLASH+FLASH
    if(seen&&a<prevend)fail("record at line " NR " runs backwards or overlaps flash already written - no real image is disordered")
    if(!seen||a<lo)lo=a;seen=1;if(a+len-1>hi)hi=a+len-1;cov+=len;prevend=a+len}
  if(type==1)eof=1;next}
NF{fail("line " NR " is not an Intel HEX record")}
END{if(failed)exit 1
  if(!eof){print "BAD no end-of-file record - truncated download?";exit 1}
  if(!seen){print "BAD no data records";exit 1}
  if(lo<2147491840){printf "BAD data at 0x%X - inside the bootloader region, or not AVR32 firmware\n",lo;exit 1}
  if(hi>2147745791){printf "BAD data at 0x%X - beyond the AT32UC3B1256 flash\n",hi;exit 1}
  if(lo!=2147491840){printf "BAD starts at 0x%X, not the reset vector at 0x80002000 - a partial image would erase the application and not replace it\n",lo;exit 1}
  if(cov<16384){printf "BAD only %d bytes of firmware - a real image carries tens of thousands, and flashing this would leave the instrument unbootable\n",cov;exit 1}
  printf "OK 0x%X..0x%X (%d bytes)\n",lo,hi,cov}' "$1"
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
        echo "  No START command was sent, so the 218e V3 is still in DFU."
        echo
        if [ "$ERASE_STARTED" -eq 0 ]; then
            # Nothing was erased: the original application is intact, and the
            # only reason the instrument is in DFU is that we asked it there.
            # A power cycle will NOT help — reading the fuses set ISP_FORCE, so
            # it comes straight back to DFU.  START is what boots it.
            echo "  ${C_GREEN}Your firmware was not touched.${C_RESET}  Nothing was erased."
            echo
            echo "  To put the instrument back to normal right now:"
            # Escaped quotes, so they appear IN the printed command: the
            # app's own path has spaces, and the unquoted form this used to
            # print failed word-split when pasted back.
            echo "    ${C_BOLD}\"$DFUPATH\" at32uc3b1256 start${C_RESET}"
            echo
            echo "  Power-cycling alone will not do it: reading the fuses set"
            echo "  ISP_FORCE, so the 218e V3 returns to DFU until START is sent."
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
        echo "Leave the 218e V3 connected and rerun this command; it should remain in DFU mode."
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
# Searching several places is safe because nothing is flashed without being
# chosen: every candidate is validated against what dfu-programmer's own parser
# would accept, listed with its date, its checksum and whatever the download
# said it was, and the choice is made explicitly.  That is what lets a
# downloaded file be used where it landed, instead of asking anyone to move it.
# The banana is shown twice - once over the warning, once over the result -
# so it lives in one place.  A quoted heredoc keeps every backslash and
# caret in the art literal.
# A menu that answers to the arrow keys and to typing, because both are what
# people reach for.  The caller fills MENU_ITEMS with the lines to choose
# between and MENU_DETAILS with whatever belongs underneath each one, and gets
# back MENU_CHOICE: 1-based, or 0 for nothing chosen.
#
# Not every run has a terminal to draw on - piped into something, or driven by
# a test - and there the only thing that works is a numbered list and a read.
MENU_ITEMS=()
MENU_DETAILS=()
MENU_CHOICE=0
MENU_SEL=0
MENU_LINES=0

# Keep a line inside the window.  A line that wraps takes two rows, the redraw
# moves the cursor up by the number it printed, and the menu walks down the
# screen leaving a copy of itself behind on every keypress.
#
# Which end to cut depends on what the line is.  A path is cut from the front,
# because the end says which file it is and the start says /Users; anything
# else is a sentence, and cutting the front off a sentence takes away the half
# that said what it was about.
menu_fit() {
    local text="$1" width="${MENU_WIDTH:-0}"
    case "$width" in ''|*[!0-9]*) printf '%s' "$text"; return ;; esac
    [ "$width" -gt 20 ] || { printf '%s' "$text"; return; }
    width=$((width - 10))
    if [ "${#text}" -le "$width" ]; then
        printf '%s' "$text"
        return
    fi
    case "$text" in
        /*) printf '...%s' "${text: $(( ${#text} - width + 3 )) }" ;;
        *)  printf '%s...' "${text:0:$(( width - 3 ))}" ;;
    esac
}

menu_draw() {
    local i=0 label detail line
    MENU_LINES=0
    while [ "$i" -lt "${#MENU_ITEMS[@]}" ]; do
        # Air between the entries: two blocks of detail lines with nothing
        # separating them read as one list.
        if [ "$i" -gt 0 ]; then
            printf '\033[K\n'
            MENU_LINES=$((MENU_LINES + 1))
        fi
        label="${MENU_ITEMS[$i]}"
        detail="${MENU_DETAILS[$i]}"
        if [ "$i" -eq "$MENU_SEL" ]; then
            printf '  %s>%s %s%d)%s %s%s%s\033[K\n' \
                   "$C_YELLOW" "$C_RESET" "$C_YELLOW" "$((i + 1))" "$C_RESET" \
                   "$C_BOLD" "$label" "$C_RESET"
        else
            printf '    %s%d)%s %s%s%s\033[K\n' \
                   "$C_YELLOW" "$((i + 1))" "$C_RESET" "$C_DIM" "$label" "$C_RESET"
        fi
        MENU_LINES=$((MENU_LINES + 1))
        if [ -n "$detail" ]; then
            while IFS= read -r line; do
                printf '       %s%s%s\033[K\n' \
                       "$C_DIM" "$(menu_fit "$line")" "$C_RESET"
                MENU_LINES=$((MENU_LINES + 1))
            done <<DETAIL
$detail
DETAIL
        fi
        i=$((i + 1))
    done
    printf '\033[K\n'
    printf '  %sType a number, or move with the arrow keys and press return.%s\033[K\n' \
           "$C_DIM" "$C_RESET"
    MENU_LINES=$((MENU_LINES + 2))
}

menu() {
    local n=${#MENU_ITEMS[@]} key rest chosen i size
    MENU_SEL=0
    MENU_CHOICE=0
    [ "$n" -gt 0 ] || return

    if [ ! -t 0 ] || [ ! -t 1 ]; then
        i=0
        while [ "$i" -lt "$n" ]; do
            printf '    %d) %s\n' "$((i + 1))" "${MENU_ITEMS[$i]}"
            i=$((i + 1))
        done
        read -r -p "  Choose [1-$n]: " key
        case "$key" in
            ''|*[!0-9]*) return ;;
        esac
        [ "$key" -ge 1 ] && [ "$key" -le "$n" ] && MENU_CHOICE=$key
        return
    fi

    MENU_WIDTH=0
    size="$(stty size 2>/dev/null)" && MENU_WIDTH="${size##* }"

    printf '\033[?25l'
    menu_draw
    while :; do
        IFS= read -rsn1 key || key=''
        chosen=0
        case "$key" in
            $'\033')
                # An arrow arrives as ESC [ A or ESC [ B.  This bash has no
                # fractional timeout, so a bare Escape waits a second and then
                # counts as nothing pressed.
                IFS= read -rsn2 -t 1 rest || rest=''
                case "$rest" in
                    '[A') [ "$MENU_SEL" -gt 0 ] && MENU_SEL=$((MENU_SEL - 1)) ;;
                    '[B') [ "$MENU_SEL" -lt $((n - 1)) ] && MENU_SEL=$((MENU_SEL + 1)) ;;
                esac ;;
            k) [ "$MENU_SEL" -gt 0 ] && MENU_SEL=$((MENU_SEL - 1)) ;;
            j) [ "$MENU_SEL" -lt $((n - 1)) ] && MENU_SEL=$((MENU_SEL + 1)) ;;
            [1-9]) [ "$key" -le "$n" ] && { MENU_SEL=$((key - 1)); chosen=1; } ;;
            '') chosen=1 ;;
            q|Q) MENU_SEL=-1; chosen=1 ;;
        esac
        printf '\033[%dA' "$MENU_LINES"
        menu_draw
        [ "$chosen" -eq 1 ] && break
    done
    printf '\033[?25h'
    [ "$MENU_SEL" -ge 0 ] && MENU_CHOICE=$((MENU_SEL + 1))
}

# The builder page writes an image.txt beside the firmware it built, naming
# the image and what went into it.  It is bound by checksum, so a manifest
# left behind by an earlier download cannot describe the wrong file.
image_options() {
    local hexfile="$1" sha="$2" manifest key value want=""
    manifest="$(dirname "$hexfile")/image.txt"
    [ -f "$manifest" ] || return 0
    # The manifest describes both images a download carries: the build it was
    # made for, and the stock image it was made from.  Which set of lines to
    # print is decided by checksum, so a manifest left over from an earlier
    # download cannot describe the wrong file.
    while IFS='=' read -r key value; do
        case "$key" in
            EXPECTED_SHA256) [ "$value" = "$sha" ] && want="OPTION" ;;
            FACTORY_SHA256)  [ "$value" = "$sha" ] && want="FACTORY_OPTION" ;;
        esac
    done < "$manifest"
    [ -n "$want" ] || return 0
    while IFS='=' read -r key value; do
        [ "$key" = "$want" ] && printf '%s\n' "$value"
    done < "$manifest"
}

# Folded in from what used to be a separate ExitDFU script.  A 218e V3 lands in
# DFU when a flash was started and interrupted, and reading the safety fuses
# sets ISP_FORCE, so a power cycle brings it straight back into DFU.  The one
# thing that releases it is the START command.  This flashes nothing and
# erases nothing.
rescue_unquarantine() {
    local target
    # Inside the app the tools are signed and notarised with it, so the
    # quarantine attribute is inert and the bundle is read-only anyway.  This
    # is the same reasoning the flash path uses, decided by the path so it
    # cannot fail on a read-only translocated copy.
    case "$RUNTIME_DIR" in
        *.app/Contents/*) return 0 ;;
    esac
    for target in "$DFU_BUNDLED" "$SENDMIDI"; do
        if xattr -p com.apple.quarantine "$target" >/dev/null 2>&1; then
            # Cleared, not asked about, as on the flash path.
            xattr -dr com.apple.quarantine "$PACKAGE_ROOT" 2>/dev/null
            xattr -dr com.apple.quarantine "$RUNTIME_DIR" 2>/dev/null
            if xattr -p com.apple.quarantine "$DFU_BUNDLED" >/dev/null 2>&1; then
                echo "  The tools are quarantined and it could not be cleared,"
                echo "  which usually means they are somewhere read-only."
                echo "  Approve them in System Settings > Privacy & Security,"
                echo "  or run:"
                echo "    ${C_BOLD}xattr -dr com.apple.quarantine \"$PACKAGE_ROOT\"${C_RESET}"
                return 1
            fi
            ok "Quarantine cleared"
            break
        fi
    done
    return 0
}

run_rescue() {
    local n
    echo ""
    step "Getting the 218e V3 out of DFU mode"
    rescue_unquarantine || return 1
    [ -x "$DFU_BUNDLED" ] || {
        echo "  dfu-programmer is missing or not executable:"
        echo "    $DFU_BUNDLED"
        return 1
    }
    if [ -x "$SENDMIDI" ] && "$SENDMIDI" list 2>/dev/null | grep -q "218e"; then
        ok "The 218e V3 is already running its firmware - it has a MIDI port"
        echo "  Nothing to do."
        return 0
    fi
    if ! "$DFU_BUNDLED" at32uc3b1256 get bootloader-version >/dev/null 2>&1; then
        echo "  ${C_RED}No 218e V3 in DFU mode, and no 218e V3 MIDI port.${C_RESET}"
        echo ""
        echo "  Check the USB cable and that the instrument is powered on.  If it"
        echo "  still does not appear, power-cycle it once and try again."
        return 1
    fi
    ok "Found the 218e V3 in DFU mode"
    echo "  Sending START..."
    if "$DFU_BUNDLED" at32uc3b1256 start; then
        echo "  START sent.  Waiting for the instrument to come back..."
        for n in 1 2 3 4 5 6 7 8 9 10; do
            sleep 1
            if [ -x "$SENDMIDI" ] && "$SENDMIDI" list 2>/dev/null | grep -q "218e"; then
                ok "The 218e V3 is back as a MIDI device"
                return 0
            fi
        done
        echo "  START was sent but the MIDI port has not appeared yet."
        echo "  Power-cycle the instrument once.  It will come up normally."
        return 0
    fi
    echo "  ${C_RED}Could not send START.${C_RESET}  Power-cycle the instrument and try again."
    return 1
}

# Terminal opens 80x24, and the banner alone is 22 lines, so by the time the
# menu is drawn the top of it has scrolled off.  Ask for a window that fits.
# Only ever grow it: a window someone has sized deliberately stays that size.
fit_window() {
    [ -t 1 ] || return 0
    local size rows cols want_rows=40 want_cols=80
    # stty, not tput: tput needs TERM, and when it is unset or unknown the
    # error is silent and the fallback would resize a window that was already
    # the right size.  Not knowing the size means leaving it alone.
    size="$(stty size 2>/dev/null)" || return 0
    rows="${size%% *}"
    cols="${size##* }"
    case "$rows" in ''|*[!0-9]*) return 0 ;; esac
    case "$cols" in ''|*[!0-9]*) return 0 ;; esac
    [ "$rows" -gt "$want_rows" ] && want_rows="$rows"
    [ "$cols" -gt "$want_cols" ] && want_cols="$cols"
    [ "$rows" -lt 40 ] || [ "$cols" -lt 80 ] || return 0

    printf '\033[8;%d;%dt' "$want_rows" "$want_cols"
    # The resize is asynchronous: without a beat the banner is drawn into the
    # old window and scrolls anyway.
    sleep 0.3

    # Whether that did anything is not a matter of opinion, so ask.  Terminal
    # does listen to AppleScript, but only terminals that ignored the sequence
    # get asked that way - which keeps the automation prompt away from the
    # ones where it was never needed.
    size="$(stty size 2>/dev/null)" || return 0
    case "${size%% *}" in ''|*[!0-9]*) return 0 ;; esac
    [ "${size%% *}" -eq "$rows" ] || return 0
    [ "${TERM_PROGRAM:-}" = "Apple_Terminal" ] || return 0
    command -v osascript >/dev/null 2>&1 || return 0
    osascript >/dev/null 2>&1 <<APPLESCRIPT
tell application "Terminal"
    set number of rows of front window to $want_rows
    set number of columns of front window to $want_cols
end tell
APPLESCRIPT
    sleep 0.2
}

# Where an image can be.  WORK_DIR is the folder the app sits in: inside a
# bundle SCRIPT_DIR is Contents/Resources, so "beside the script" means inside
# the app, and an image unzipped next to it would never be found.  Deduped,
# because run loose from a folder several of these are the same place.
search_dirs() {
    local real app parent newest
    {
        printf '%s\n' "${FIRMWARE_DIR%/}"

        # macOS runs a quarantined app from a read-only copy of itself under
        # /AppTranslocation, and from inside that copy the folder it was
        # unzipped into cannot be reached at all - which is where the firmware
        # is.  The system knows the mapping, so ask it.
        case "$SCRIPT_DIR" in
            */AppTranslocation/*)
                real=""
                if [ -x "$RUNTIME_DIR/support/resolve-translocation" ]; then
                    # SCRIPT_DIR is Contents/Resources; the bundle is two up.
                    real="$("$RUNTIME_DIR/support/resolve-translocation" \
                            "$(dirname "$(dirname "$SCRIPT_DIR")")" 2>/dev/null)"
                fi
                if [ -n "$real" ] && [ -d "$(dirname "$real")/firmware" ]; then
                    printf '%s\n' "$(dirname "$real")/firmware"
                else
                    # Nothing answered, so guess: a folder holding this app and
                    # a firmware folder is a download of ours, which a folder of
                    # loose files never is.  Only the obvious places, and only
                    # the most recent - that is the one just opened.
                    newest=""
                    for app in "$HOME/Downloads"/*/"218e Rewired Flasher.app" \
                               "$HOME/Desktop"/*/"218e Rewired Flasher.app"; do
                        [ -d "$app" ] || continue
                        parent="$(dirname "$app")"
                        [ -d "$parent/firmware" ] || continue
                        if [ -z "$newest" ] || [ "$parent" -nt "$newest" ]; then
                            newest="$parent"
                        fi
                    done
                    [ -n "$newest" ] && printf '%s\n' "$newest/firmware"
                fi ;;
        esac
    } | awk 'NF && !seen[$0]++'
}

# Say where it looked and what was in each place.  Without this, an image that
# does not appear in the list gives no clue as to why - and the commonest
# reason is that the app was moved away from the folder it was unzipped into,
# which nothing on screen would otherwise show.
searched_note() {
    local dir count candidate shown
    # Worth saying out loud, because the paths below otherwise look absurd:
    # macOS runs a quarantined app from a random read-only copy of itself, and
    # from there the folder it was unzipped into does not exist.
    # Only worth saying when it actually went wrong.  The launcher resolves the
    # real location before it starts this, so translocation on its own is not
    # news - the firmware folder is right there and the paths look ordinary.
    case "$SCRIPT_DIR" in
        */AppTranslocation/*)
            if [ ! -d "$FIRMWARE_DIR" ]; then
                echo "  ${C_DIM}macOS is running this app from a read-only copy, so the folder it${C_RESET}"
                echo "  ${C_DIM}was unzipped into cannot be seen from here.  Looking for it instead.${C_RESET}"
                echo
            fi ;;
    esac
    echo "  ${C_DIM}Looked in:${C_RESET}"
    while IFS= read -r dir; do
        # The ordinary folder is named the way the README names it.  Spelling
        # out where the package was unzipped to says nothing anyone needs; a
        # folder found some other way is a different matter, and keeps its
        # path, because knowing which one was reached is the whole point.
        shown="$dir"
        [ "$dir" = "${PACKAGE_ROOT%/}/firmware" ] &&
            shown="the firmware folder beside this app"
        if [ -d "$dir" ]; then
            count=0
            for candidate in "$dir"/*.hex; do
                [ -f "$candidate" ] && count=$((count + 1))
            done
            printf '    %s%2d in %s%s\n' "$C_DIM" "$count" "$shown" "$C_RESET"
        else
            printf '    %s -- %s  (no such folder)%s\n' "$C_DIM" "$shown" "$C_RESET"
        fi
    done <<DIRS
$(search_dirs)
DIRS
    echo
}

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

fit_window
echo ""
banana
echo ""

# What to do comes before the warning, because the warning is about flashing
# and getting a stuck keyboard out of DFU is not that: it writes nothing.
MENU_ITEMS=("Flash firmware onto the 218e V3"
            "Get the keyboard out of DFU mode")
MENU_DETAILS=("Erases the chip and writes a new image."
              "For a keyboard left in DFU by an interrupted flash.
Sends START. Flashes nothing, erases nothing.")
echo "  ${C_BOLD}What would you like to do?${C_RESET}"
echo ""
menu
echo ""
case "$MENU_CHOICE" in
    2)
        run_rescue
        rescue_status=$?
        echo ""
        read -r -p "  Press return to close. "
        exit $rescue_status ;;
    1) ;;
    *) fail "Nothing chosen. The instrument was not touched." ;;
esac

echo "======================================================================"
echo "  READ THIS BEFORE YOU FLASH ANYTHING"
echo ""
echo "  THIS FIRMWARE IS FOR THE BUCHLA 218e V3 ONLY.  It won't work on the"
echo "  218, the 218r, the 218e V1 or V2, or any other touchplate controller."
echo ""
echo "  USING THIS TOOL AND FIRMWARE IS ENTIRELY AT YOUR OWN RISK.  This is an"
echo "  experimental, unofficial firmware, not made or supported by Buchla."
echo "  Flashing it will probably void your warranty.  Recovering a bricked"
echo "  unit may need JTAG hardware and opening the instrument, or may not be"
echo "  possible at all."
echo ""
echo "  A failed flash usually leaves the keyboard in DFU mode, where the"
echo "  flasher can try again, but THERE IS NO GUARANTEE THAT IT WILL SUCCEED."
echo "  If losing the use of your 218e V3 would be a problem, stop here and keep"
echo "  the factory firmware."
echo "======================================================================"
echo ""
read -r -p "  Type YES (capitals) to accept and continue: " consent
[ "$consent" = "YES" ] || fail "Not confirmed. Nothing was changed."

step "Locating the firmware image"
searched_note
FIRMWARE=""
CUSTOM_IMAGE=0

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
        while IFS= read -r dir; do
            for candidate in "$dir"/*.hex; do
                [ -f "$candidate" ] || continue
                printf '%s\t%s\n' "$(stat -f '%m' "$candidate" 2>/dev/null || echo 0)" \
                                   "$candidate"
            done
        done <<DIRS
$(search_dirs)
DIRS
    } | sort -rn -k1,1 | cut -f2- | awk '!seen[$0]++' |
    while IFS= read -r candidate; do
        case "$(validate_hex "$candidate")" in OK*) printf '%s\n' "$candidate" ;; esac
    done | head -12 | prefer_expected
}

# The build this download was made for goes first, whatever the dates say.
# Some unzippers stamp every extracted file with the moment of extraction, and
# then the two images in a download carry the same timestamp - which left the
# order to fall out of the filenames, putting the factory image on top and
# preselecting a return to stock.
prefer_expected() {
    local line first="" rest=""
    while IFS= read -r line; do
        if [ -z "$first" ] && \
           [ "$(shasum -a 256 "$line" | cut -d' ' -f1)" = "$EXPECTED_SHA256" ]; then
            first="$line"
        else
            rest="$rest$line
"
        fi
    done
    [ -n "$first" ] && printf '%s\n' "$first"
    printf '%s' "$rest"
}

# Accept a chosen image.  The flasher installs any valid 218e V3 image; the
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

# An image named on the command line wins outright.  This has to sit below
# accept_choice: bash binds a function when it executes the definition, so
# called from above it the call failed with 127 and - there being no set -e -
# the script carried on and flashed whatever the scan turned up instead.
if [ -n "${1:-}" ]; then
    [ -f "$1" ] || fail "No such file: $1"
    case "$(validate_hex "$1")" in
        OK*) ;;
        *) echo "  ${C_RED}$(validate_hex "$1")${C_RESET}"
           fail "That file is not a flashable 218e V3 image. The instrument was not touched." ;;
    esac
    accept_choice "$1"
fi

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
        MENU_ITEMS=()
        MENU_DETAILS=()
        # "Rewired 1.0.0 (sha)" -> "1.0", the way the page shows it.
        rewired_mmv="${FIRMWARE_VERSION#Rewired }"
        rewired_mmv="${rewired_mmv%% *}"
        rewired_mmv="$(printf '%s' "$rewired_mmv" | cut -d. -f1-2)"
        i=0
        while IFS= read -r candidate; do
            sha="$(shasum -a 256 "$candidate" | cut -d" " -f1)"
            when="$(stat -f '%Sm' -t '%Y-%m-%d %H:%M' "$candidate" 2>/dev/null)"
            case "$sha" in
                "$EXPECTED_SHA256")
                    mark="   ${C_GREEN}<- REWIRED firmware v${rewired_mmv}${C_RESET}" ;;
                "$FACTORY_SHA256")
                    mark="   ${C_YELLOW}<- FACTORY firmware v36.9${C_RESET}" ;;
                *)  mark="" ;;
            esac
            # The filename is the label - it is the thing a person
            # recognises - and the name only: every image in the list is in
            # the same folder.  The date and checksum go underneath, with
            # what the image was built with, when the download that carried
            # it said so.
            MENU_ITEMS[$i]="${candidate##*/}$mark"
            detail="$when   ${sha:0:8}"
            opts="$(image_options "$candidate" "$sha")"
            [ -n "$opts" ] && detail="$detail
$opts"
            MENU_DETAILS[$i]="$detail"
            i=$((i + 1))
        done <<EOF
$images
EOF
        menu
        [ "$MENU_CHOICE" -ge 1 ] || fail "Nothing chosen. The instrument was not touched."
        chosen="$(printf '%s\n' "$images" | sed -n "${MENU_CHOICE}p")"
        accept_choice "$chosen"
    elif [ "$count" -eq 1 ]; then
        accept_choice "$images"
    fi
fi

if [ -z "$FIRMWARE" ]; then
    echo
    echo "  No flashable 218e V3 image is in the firmware folder."
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
           fail "That file is not a flashable 218e V3 image. The instrument was not touched." ;;
    esac
    accept_choice "$other"
fi
ok "Found $(basename "$FIRMWARE")"
ok "${C_BOLD}$FIRMWARE_VERSION${C_RESET}"
# With one image in reach there is no menu to read the options off, and this
# is the last point before the chip is erased at which they can be checked.
chosen_options="$(image_options "$FIRMWARE" "$actual_sha256")"
if [ -n "$chosen_options" ]; then
    while IFS= read -r line; do
        echo "      ${C_DIM}$line${C_RESET}"
    done <<EOF
$chosen_options
EOF
fi

# The image is flashed from where it is.  It used to be copied into the working
# folder first, which was the package root and therefore somewhere the person
# could see - now that folder is inside Library, and the copy only turned up
# again on the next run as a nameless extra entry in the list, checksummed but
# with nothing to say where it came from.
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
echo "  - if any operation fails, leave the 218e V3 in DFU and rerun this command"
echo
read -r -p "Press return to continue with the connected 218e V3. "

# Prove the DFU toolchain actually runs BEFORE asking the instrument to leave
# MIDI.  Otherwise a broken toolchain is discovered only after the keyboard has
# rebooted into a bootloader nothing here can reach — recoverable by a power
# cycle, but alarming and entirely avoidable.
#
# "no device present" is the expected answer while the 218e V3 is still in
# application mode, and it proves the binary launched and libusb loaded.  A
# success is fine too: the instrument is already in DFU.  Anything else means
# the tool cannot run at all.
step "Checking the DFU tools"

# Clear quarantine BEFORE running anything.  A quarantined unsigned binary does
# not return an error when launched — macOS suspends it behind a modal dialog
# and it waits indefinitely, so detecting the problem by running the tool means
# hanging on it.  Reading the attribute costs nothing and never blocks.
# Signed tools are a different matter.  Inside the app each of these carries
# the same Developer ID as the app and was notarised with it, so Gatekeeper
# runs them and the attribute means nothing - while the bundle is read-only,
# doubly so when macOS is running it from a translocated copy, so trying to
# clear it fails and the run stops for a problem that was never there.
tools_are_signed() {
    local candidate info
    # Inside the app the answer is not in doubt: make-app.sh signs every tool
    # in support/ with the same Developer ID as the app and notarises them with
    # it, so being in the bundle IS being signed.  This is decided by the path,
    # which cannot misfire the way `codesign | grep` did on a read-only,
    # translocated copy where a slow codesign lost the race with the pipe.
    case "$RUNTIME_DIR" in
        *.app/Contents/*) return 0 ;;
    esac
    # A loose checkout is not signed and this returns non-zero, which is the
    # honest answer.  Output is captured, not piped, so no SIGPIPE can turn a
    # match into a failure under `set -o pipefail`.
    for candidate in "$DFUPATH" "$SENDMIDI"; do
        info="$(codesign -dv --verbose=2 "$candidate" 2>&1)"
        case "$info" in
            *"Authority=Developer ID"*) ;;
            *) return 1 ;;
        esac
    done
    return 0
}

quarantined=""
for candidate in "$DFUPATH" "$SENDMIDI"; do
    if xattr -p com.apple.quarantine "$candidate" >/dev/null 2>&1; then
        quarantined="yes"
    fi
done
if [ -n "$quarantined" ] && tools_are_signed; then
    quarantined=""
fi
if [ -n "$quarantined" ]; then
    # Cleared, not asked about.  It affects only the files in this package, on
    # this machine, and the only other answer on offer was to stop.
    xattr -dr com.apple.quarantine "$PACKAGE_ROOT" 2>/dev/null
    xattr -dr com.apple.quarantine "$RUNTIME_DIR" 2>/dev/null
    if xattr -p com.apple.quarantine "$DFUPATH" >/dev/null 2>&1; then
        echo "  The tools are quarantined and it could not be cleared, which"
        echo "  usually means they are somewhere read-only."
        echo "  Approve them in System Settings > Privacy & Security, or run:"
        echo "    ${C_BOLD}xattr -dr com.apple.quarantine \"$PACKAGE_ROOT\"${C_RESET}"
        fail "The DFU tools are blocked. The instrument was not touched."
    fi
    ok "Quarantine cleared"
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
# The jump to the bootloader sometimes detaches from USB and never
# re-attaches: ioreg then shows no 218e and no AT32UC3B at all, and only a
# power cycle brings the instrument back (in application mode - nothing was
# written).  Observed repeatedly on real hardware, and intermittent.  So a
# failed re-enumeration is not the end: the script asks for a power cycle,
# waits for the instrument to reappear on MIDI, and sends the DFU request
# again, up to four attempts in all.
DFU_ATTEMPTS=4
attempt=1
while :; do
    if check_dfu_device; then
        log "The 218e V3 is in DFU mode."
        break
    fi

    midi_ports="$("$SENDMIDI" list 2>&1)"
    midi_list_status=$?
    printf '%s\n' "$midi_ports" >> "$LOG_FILE"
    if [ "$midi_list_status" -ne 0 ] || ! printf '%s\n' "$midi_ports" | grep -q "218e"; then
        fail "The 218e V3 CoreMIDI output port is unavailable. Nothing was erased; power-cycle the 218e V3, reconnect USB directly, and retry."
    fi

    log "Asking the 218e V3 to enter DFU mode over MIDI (attempt ${attempt}/${DFU_ATTEMPTS})."
    sysex_output="$("$SENDMIDI" dev 218e syx 0 2 55 2 1 1 2>&1)"
    sysex_status=$?
    printf '%s\n' "$sysex_output" >> "$LOG_FILE"
    if [ "$sysex_status" -ne 0 ] || \
       printf '%s\n' "$sysex_output" | grep -Eq "Couldn't find|No valid MIDI|CoreMIDI error"; then
        fail "SendMIDI could not deliver the DFU request. Nothing was erased; power-cycle the 218e V3 and retry."
    fi
    log "DFU SysEx delivered; waiting up to 60 seconds for USB re-enumeration."

    wait_for_dfu_device && break

    if check_218_usb_device; then
        fail "The 218e V3 stayed in application mode after the DFU request. Nothing was erased; power-cycle it and retry."
    fi

    if [ "$attempt" -ge "$DFU_ATTEMPTS" ]; then
        echo
        echo "  Nothing was erased, and nothing was written."
        echo "  ${C_BOLD}Power-cycle the instrument and it will come back up normally.${C_RESET}"
        echo
        fail "The AT32UC3B DFU device did not appear on USB after ${DFU_ATTEMPTS} attempts. Reconnect USB directly, avoid hubs, and retry."
    fi

    # Vanished from USB entirely: the request was accepted, the instrument
    # detached, and the bootloader never re-attached.  A power cycle
    # recovers it, and the retry loop takes it from there.
    echo
    echo "  The DFU device did not appear on USB. The 218e V3 accepted the"
    echo "  request and detached, but the bootloader did not re-attach —"
    echo "  this happens now and then, and nothing was erased or written."
    echo
    echo "  ${C_BOLD}Power-cycle the 218e V3 now.${C_RESET} It will come back up normally,"
    echo "  and this script will ask it to enter DFU again as soon as it"
    echo "  reappears (attempt $((attempt + 1)) of ${DFU_ATTEMPTS})."
    echo
    log "DFU device absent and 218e gone from USB; waiting for a power cycle (attempt ${attempt}/${DFU_ATTEMPTS} failed)."

    waited=0
    came_back=0
    while [ "$waited" -lt 300 ]; do
        if check_dfu_device; then
            came_back=2
            break
        fi
        if "$SENDMIDI" list 2>/dev/null | grep -q "218e"; then
            came_back=1
            break
        fi
        sleep 1
        waited=$((waited + 1))
        case "$waited" in
            30|60|120|180|240)
                log "Still waiting for the power-cycled 218e V3 (${waited}/300 seconds)."
                ;;
        esac
    done
    if [ "$came_back" -eq 2 ]; then
        log "The AT32UC3B DFU device appeared while waiting."
        break
    fi
    if [ "$came_back" -eq 0 ]; then
        fail "The 218e V3 did not reappear within 5 minutes. Nothing was erased; power-cycle it and rerun this script."
    fi
    ok "The 218e V3 is back — asking it again"
    # CoreMIDI registration can trail the port listing; a beat of settling
    # keeps the re-sent SysEx from racing it.
    sleep 2
    attempt=$((attempt + 1))
done
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
read -r -p "  Press return to send START and restart the 218e V3. "
spin "restarting…" "$DFUPATH" at32uc3b1256 start || fail "The DFU start command failed."
DFU_SESSION_ACTIVE=0

# Poll rather than glance: USB re-enumeration plus CoreMIDI registration
# often takes longer than a fixed nap, and each `sendmidi list` is a fresh
# CoreMIDI client whose first device snapshot can also trail reality.  A
# single early look raced both, and sometimes reported a missing port on an
# instrument that was already back.
waited=0
while [ "$waited" -lt 30 ]; do
    if "$SENDMIDI" list 2>/dev/null | grep -q "218e"; then
        break
    fi
    sleep 1
    waited=$((waited + 1))
done
if [ "$waited" -lt 30 ]; then
    ok "The 218e V3 returned as a MIDI device"
    log "MIDI port reappeared after ${waited}s."
else
    "$SENDMIDI" list >> "$LOG_FILE" 2>&1
    warn "No 218e V3 MIDI port after 30 seconds — power-cycle the instrument"
    echo "  ${C_DIM}Audio MIDI Setup can keep showing a greyed-out entry from before the${C_RESET}"
    echo "  ${C_DIM}flash; only a non-greyed one means the port is really back.${C_RESET}"
fi

# Leave a record beside the image.  Without it the only answer to "what is on
# this instrument" is whatever the person remembers.
#
# Beside the image, not under PACKAGE_ROOT: inside the app that is a folder in
# the bundle, which is read-only and read-only again for being a translocated
# copy, so the record was silently going nowhere on every run.  If the image
# sits somewhere unwritable too, the working folder takes it.
RECORD_DIR="$(dirname "$FIRMWARE")"
[ -w "$RECORD_DIR" ] || RECORD_DIR="$WORK_DIR"
cat > "$RECORD_DIR/INSTALLED.txt" <<RECORD 2>/dev/null || true
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
echo
echo "  ${C_BOLD}Press the reset button before you play.${C_RESET}"
echo "  ${C_DIM}Flashing does not clear the instrument's own memory, so a held${C_RESET}"
echo "  ${C_DIM}note or a stuck gate from before the update can survive it.  Reset${C_RESET}"
echo "  ${C_DIM}clears that and recalibrates the keys - keep your hands away for${C_RESET}"
echo "  ${C_DIM}the few seconds the pad LEDs are lit.${C_RESET}"
echo
echo "  ${C_DIM}Log: $LOG_FILE${C_RESET}"
echo
read -r -p "  Press return to close. "
