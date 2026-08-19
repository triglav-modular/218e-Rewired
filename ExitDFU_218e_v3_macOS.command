#!/bin/bash
# Get a 218e out of DFU mode and back to being an instrument.
#
# A 218e lands in DFU when a flash was started and interrupted, and reading the
# safety fuses sets ISP_FORCE, so a power cycle alone brings it straight back
# into DFU.  The one thing that releases it is the START command, which this
# script sends.  It flashes nothing and erases nothing.

set -u
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
if [ -d "$SCRIPT_DIR/mac/support" ]; then
    RUNTIME_DIR="$SCRIPT_DIR/mac"
elif [ -d "$SCRIPT_DIR/support" ]; then
    RUNTIME_DIR="$SCRIPT_DIR"
else
    echo "Could not find the mac/support directory next to this script."
    echo "Keep this command inside the 218e-Rewired package."
    exit 1
fi
DFU="$RUNTIME_DIR/support/dfu/bin/dfu-programmer"
SENDMIDI="$RUNTIME_DIR/support/sendmidi"

# Quarantine has to go before either tool is run.  macOS does not fail a
# quarantined unsigned binary, it holds it behind a dialog, so every probe
# below would come back empty and this script would blame the cable for what
# is really Gatekeeper.  Reading the attribute never blocks.
for candidate in "$DFU" "$SENDMIDI"; do
    if xattr -p com.apple.quarantine "$candidate" >/dev/null 2>&1; then
        echo "These tools are quarantined because the package was downloaded."
        echo "macOS will not run them until that is cleared."
        echo
        echo "It affects only the files in this package, on this machine."
        echo
        read -r -p "Clear it now? [Y/n] " unquarantine
        case "$unquarantine" in
            [nN]*)
                echo "Then clear it yourself and run this again:"
                echo "  xattr -dr com.apple.quarantine \"$RUNTIME_DIR\""
                exit 1
                ;;
        esac
        xattr -dr com.apple.quarantine "$RUNTIME_DIR" 2>/dev/null
        xattr -dr com.apple.quarantine "$SCRIPT_DIR" 2>/dev/null
        if xattr -p com.apple.quarantine "$DFU" >/dev/null 2>&1; then
            echo "Could not clear it.  Approve the tools in System Settings >"
            echo "Privacy & Security, then run this again."
            exit 1
        fi
        echo "Quarantine cleared."
        break
    fi
done

echo "Looking for the 218e..."

if [ -x "$SENDMIDI" ] && "$SENDMIDI" list 2>/dev/null | grep -q "218e"; then
    echo "The 218e is already running its firmware - it has a MIDI port."
    echo "Nothing to do."
    exit 0
fi

if ! "$DFU" at32uc3b1256 get bootloader-version >/dev/null 2>&1; then
    echo "No 218e in DFU mode and no 218e MIDI port."
    echo
    echo "Check the USB cable and that the instrument is powered on.  If it"
    echo "still does not appear, power-cycle it once and run this again."
    exit 1
fi

echo "Found the 218e in DFU mode.  Sending START..."
if "$DFU" at32uc3b1256 start; then
    echo "START sent.  Waiting for the instrument to come back..."
    for _ in 1 2 3 4 5 6 7 8 9 10; do
        sleep 1
        if [ -x "$SENDMIDI" ] && "$SENDMIDI" list 2>/dev/null | grep -q "218e"; then
            echo "The 218e is back as a MIDI device.  Done."
            exit 0
        fi
    done
    echo "START was sent but the MIDI port has not appeared yet."
    echo "Power-cycle the instrument once - after START that brings it up"
    echo "normally, because leaving DFU cleared the forced-DFU flag."
    exit 0
fi

echo "Could not send START.  Power-cycle the instrument and run this again."
exit 1
