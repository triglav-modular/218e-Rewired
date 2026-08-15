#!/bin/bash

set -u

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
if [ -x "$SCRIPT_DIR/mac/support/lem218-pressure-readout" ]; then
    READER="$SCRIPT_DIR/mac/support/lem218-pressure-readout"
elif [ -x "$SCRIPT_DIR/support/lem218-pressure-readout" ]; then
    READER="$SCRIPT_DIR/support/lem218-pressure-readout"
else
    echo "The LEM218 pressure-readout executable is missing."
    echo "Keep this command inside the 218ev3-Firmware-Flashing package."
    read -r -p "Press return to close. "
    exit 1
fi

STAMP="$(date '+%Y%m%d-%H%M%S')"
CSV_FILE="$SCRIPT_DIR/LEM218_PressureReadout_${STAMP}.csv"

echo "Buchla 218e PressureFix USB readout"
echo "This is read-only: it does not flash or alter any setting."
echo
"$READER" "$CSV_FILE"
STATUS=$?

echo
if [ "$STATUS" -eq 0 ]; then
    echo "Readout finished. CSV saved at:"
    echo "$CSV_FILE"
else
    echo "Readout stopped with status $STATUS."
fi
read -r -p "Press return to close. "
exit "$STATUS"
