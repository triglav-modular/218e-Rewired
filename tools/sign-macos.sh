#!/bin/bash
# Sign, notarise and staple the macOS package.
#
# What this buys: without it, macOS blocks the flasher and then blocks
# dfu-programmer again mid-run, and a browser download is quarantined on top.
# A notarised, stapled disk image clears all of that, offline, first try.
#
# ---------------------------------------------------------------------------
# One-time setup, done by you, not by this script:
#
#   1. In developer.apple.com > Certificates, create a "Developer ID
#      Application" certificate and install it.  Check it is there with:
#         security find-identity -v -p codesigning
#
#   2. Store notarisation credentials in the keychain, once:
#         xcrun notarytool store-credentials rewired-notary \
#             --apple-id you@example.com --team-id TEAMID
#      It will ask for an app-specific password from appleid.apple.com.
#
# The password lives in your keychain from then on.  Nothing here reads it,
# and no credential belongs in this repository.
# ---------------------------------------------------------------------------
#
#   ./tools/sign-macos.sh                      # uses profile "rewired-notary"
#   ./tools/sign-macos.sh --profile other      # a different keychain profile
#   ./tools/sign-macos.sh --sign-only          # skip notarisation

set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
PROFILE="rewired-notary"
SIGN_ONLY=0
while [ $# -gt 0 ]; do
    case "$1" in
        --profile) PROFILE="$2"; shift 2 ;;
        --sign-only) SIGN_ONLY=1; shift ;;
        *) echo "unknown option: $1" >&2; exit 2 ;;
    esac
done

# `|| true` so that finding no certificate reaches the message below rather
# than tripping `set -e` and exiting silently.
IDENTITY="$(security find-identity -v -p codesigning 2>/dev/null \
            | grep "Developer ID Application" | head -1 \
            | sed -E 's/.*"(.*)".*/\1/' || true)"
if [ -z "$IDENTITY" ]; then
    echo "No 'Developer ID Application' certificate in the keychain." >&2
    echo "Create one at developer.apple.com > Certificates and install it." >&2
    exit 1
fi
echo "Signing as: $IDENTITY"

# Everything Mach-O that ships.  Hardened runtime and a secure timestamp are
# both required for notarisation; --force replaces the ad-hoc signatures these
# binaries already carry.
BINARIES=(
    "mac/support/dfu-programmer"
    "mac/support/sendmidi"
    "mac/support/lem218-pressure-readout"
    "mac/support/buchla-dfu/dfu/dfu-programmer"
    "mac/support/buchla-dfu/Frameworks/libusb-1.0.0.dylib"
    "mac/support/buchla-dfu/Frameworks/libusb-0.1.4.dylib"
)
for rel in "${BINARIES[@]}"; do
    path="$REPO/$rel"
    [ -f "$path" ] || { echo "  skip (absent): $rel"; continue; }
    codesign --force --options runtime --timestamp --sign "$IDENTITY" "$path"
    codesign --verify --strict --verbose=1 "$path" 2>&1 | sed 's/^/    /'
    echo "  signed: $rel"
done

if [ "$SIGN_ONLY" -eq 1 ]; then
    echo "Signed. Notarisation skipped."
    exit 0
fi

# Notarisation needs a container it can staple to.  A ticket cannot be stapled
# to a loose binary or to a zip, so the package is shipped as a disk image:
# staple that, and a first run works without network access.
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
PKG="$STAGE/218e v3 Rewired"
mkdir -p "$PKG"
cp -R "$REPO/mac" "$PKG/"
cp -R "$REPO/web" "$PKG/"
cp "$REPO/Program218e_v3_Rewired.command" "$PKG/"
cp "$REPO/ReadLEM218_Rewired.command" "$PKG/" 2>/dev/null || true
cp "$REPO/README.md" "$REPO/UNLICENSE" "$REPO/THIRD-PARTY.md" "$PKG/"
mkdir -p "$PKG/firmware"
cat > "$PKG/firmware/PUT YOUR FACTORY IMAGE HERE.txt" <<'NOTE'
Put your own 218eV3_v369_DFU.hex in this folder.

No firmware ships with this package: the factory image is Buchla's, and the
patched one is that firmware with our changes in it.  Get the stock image from
Buchla's flashing kit:

    https://buchla.com/firmwarefiles/218ev3-Firmware-Flashing.zip
NOTE

DMG="$REPO/build/218e-v3-Rewired.dmg"
mkdir -p "$REPO/build"
rm -f "$DMG"
hdiutil create -volname "218e v3 Rewired" -srcfolder "$PKG" \
    -ov -format UDZO "$DMG" >/dev/null
codesign --force --timestamp --sign "$IDENTITY" "$DMG"
echo "Built and signed $DMG"

echo "Submitting for notarisation (this usually takes a few minutes)..."
xcrun notarytool submit "$DMG" --keychain-profile "$PROFILE" --wait

xcrun stapler staple "$DMG"
xcrun stapler validate "$DMG"
spctl --assess --type open --context context:primary-signature -v "$DMG"

echo
echo "Done: $DMG"
echo "It is signed, notarised and stapled, so it opens without warnings"
echo "even on a machine that has never seen it and is offline."
