#!/bin/bash
# Build "218e Rewired Flasher.app" and sign it with Developer ID.
#
# Why an .app at all: a bare .command cannot carry a notarisation ticket -
# stapler refuses shell scripts outright - so the thing the user double-clicks
# has to be a bundle.  The bundle is signed as a whole, which seals the script
# and the tools inside it together.
#
#   ./tools/make-app.sh                 # build and sign
#   ./tools/make-app.sh --notarize      # ...then notarise and staple
#
# Notarisation needs credentials stored once, by you, not by this script:
#   xcrun notarytool store-credentials rewired-notary \
#       --apple-id you@example.com --team-id LRU7FPHFVM
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
APP="$REPO/build/218e Rewired Flasher.app"
PROFILE="rewired-notary"
NOTARIZE=0
[ "${1:-}" = "--notarize" ] && NOTARIZE=1

# Sign by SHA-1, not by name.  Renewing a certificate leaves two in the
# keychain under the identical name, and picking the first listed is a
# coin toss between the new one and the one about to expire.  This takes
# the latest expiry, and REWIRED_SIGN_ID overrides it.
IDENTITY="${REWIRED_SIGN_ID:-}"
if [ -z "$IDENTITY" ]; then
    IDENTITY="$(security find-identity -v -p codesigning 2>/dev/null \
                | awk '/Developer ID Application/ {print $2}' \
                | while read -r h; do
                      end=$(security find-certificate -a -Z -p \
                                -c "Developer ID Application" 2>/dev/null \
                            | awk -v h="$h" '
                                /SHA-1 hash:/ { want = ($3 == h) }
                                /BEGIN CERT/,/END CERT/ { if (want) print }' \
                            | openssl x509 -noout -enddate 2>/dev/null \
                            | cut -d= -f2)
                      [ -n "$end" ] && printf '%s\t%s\n' \
                          "$(date -j -f '%b %e %T %Y %Z' "$end" +%s 2>/dev/null || echo 0)" "$h"
                  done | sort -rn | head -1 | cut -f2)"
fi
if [ -z "$IDENTITY" ]; then
    echo "No 'Developer ID Application' certificate in the keychain." >&2
    exit 1
fi
echo "signing as: $IDENTITY"
security find-identity -v -p codesigning | grep "$IDENTITY" | sed 's/^/  /'

rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"

# --- the payload, read-only inside the bundle ------------------------------
cp -R "$REPO/mac/support" "$APP/Contents/Resources/support"
cp "$REPO/Program218e_v3_Rewired_macOS.command" "$APP/Contents/Resources/"
cp "$REPO/ExitDFU_218e_v3_macOS.command"        "$APP/Contents/Resources/"
rm -f "$APP/Contents/Resources/support/.DS_Store"

# --- the launcher ----------------------------------------------------------
# The flasher is an interactive terminal program, so the app's job is to open
# it in Terminal.  REWIRED_WORKDIR keeps the log and the firmware folder out
# of the bundle, which would otherwise break the seal on first run.
cat > "$APP/Contents/MacOS/launcher" <<'LAUNCH'
#!/bin/bash
RES="$(cd "$(dirname "$0")/../Resources" && pwd)"
WORK="$HOME/Documents/218e Rewired"
mkdir -p "$WORK"
cat > "$WORK/.run.command" <<RUN
#!/bin/bash
export REWIRED_WORKDIR="$WORK"
exec "$RES/Program218e_v3_Rewired_macOS.command"
RUN
chmod +x "$WORK/.run.command"
open -a Terminal "$WORK/.run.command"
LAUNCH
chmod +x "$APP/Contents/MacOS/launcher"

cat > "$APP/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
 "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleName</key>              <string>218e Rewired Flasher</string>
  <key>CFBundleDisplayName</key>       <string>218e Rewired Flasher</string>
  <key>CFBundleIdentifier</key>        <string>hu.triglavmodular.218e-rewired-flasher</string>
  <key>CFBundleVersion</key>           <string>1.0.0</string>
  <key>CFBundleShortVersionString</key><string>1.0</string>
  <key>CFBundleExecutable</key>        <string>launcher</string>
  <key>CFBundlePackageType</key>       <string>APPL</string>
  <key>CFBundleIconFile</key>          <string>AppIcon</string>
  <key>NSHighResolutionCapable</key>   <true/>
</dict>
</plist>
PLIST

# --- icon ------------------------------------------------------------------
ICONSET="$REPO/build/AppIcon.iconset"
rm -rf "$ICONSET"; mkdir -p "$ICONSET"
# Rendered from the vector source at every size.  The favicon it used to
# scale up is 180 square, so the 512 and 1024 slices were an enlargement of
# a thumbnail; these are drawn at the size they are used.
for s in 16 32 128 256 512; do
    sips -s format png -z $s $s "$REPO/mac/AppIcon.svg" \
         --out "$ICONSET/icon_${s}x${s}.png" >/dev/null
    sips -s format png -z $((s*2)) $((s*2)) "$REPO/mac/AppIcon.svg" \
         --out "$ICONSET/icon_${s}x${s}@2x.png" >/dev/null
done
iconutil -c icns "$ICONSET" -o "$APP/Contents/Resources/AppIcon.icns"
rm -rf "$ICONSET"

# --- sign inside out -------------------------------------------------------
# Nested code is sealed before the thing that contains it, or the outer
# signature covers bytes that then change.
while IFS= read -r m; do
    codesign --force --options runtime --timestamp --sign "$IDENTITY" "$m"
done < <(find "$APP/Contents/Resources/support" -type f -perm -u+x \
         -exec sh -c 'file -b "$1" | grep -q Mach-O && echo "$1"' _ {} \;)

codesign --force --options runtime --timestamp --sign "$IDENTITY" \
         "$APP/Contents/MacOS/launcher"
codesign --force --options runtime --timestamp --sign "$IDENTITY" "$APP"

echo
codesign --verify --deep --strict --verbose=2 "$APP" 2>&1 | sed 's/^/  /'
codesign -dv --verbose=2 "$APP" 2>&1 | grep -E "Authority=|TeamIdentifier|flags" | sed 's/^/  /'

if [ "$NOTARIZE" = "1" ]; then
    ZIP="$REPO/build/218e-Rewired-Flasher.zip"
    rm -f "$ZIP"
    # ditto, not zip: the signature lives in extended attributes that a plain
    # zip drops, and the notary service would reject an unsigned upload.
    ditto -c -k --keepParent "$APP" "$ZIP"
    xcrun notarytool submit "$ZIP" --keychain-profile "$PROFILE" --wait
    xcrun stapler staple "$APP"
    xcrun stapler validate "$APP"
    # Rebuild the archive AFTER stapling.  The one just submitted holds the
    # app as it was before the ticket was attached, so shipping it would hand
    # people an app that has to ask Apple at launch and fails when they are
    # offline - the exact case stapling exists to cover.
    rm -f "$ZIP"
    ditto -c -k --keepParent "$APP" "$ZIP"
    # Prove the shipped artefact carries the ticket, not just the app on disk.
    VERIFY="$(mktemp -d)"
    ditto -x -k "$ZIP" "$VERIFY"
    xcrun stapler validate "$VERIFY/$(basename "$APP")"
    spctl -a -vv -t exec "$VERIFY/$(basename "$APP")"
    rm -rf "$VERIFY"
fi
