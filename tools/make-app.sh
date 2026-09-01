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
cp "$REPO/mac/Program218e_v3_Rewired_macOS.command" "$APP/Contents/Resources/"
find "$APP/Contents/Resources/support" -name .DS_Store -delete

# A manifest of what the payload was built FROM.  Signing rewrites every
# Mach-O below, so the bundle's own bytes cannot be compared with the repo's
# - but the source hashes can, and CI does: a rebuilt tool in mac/support
# would otherwise ship stale inside this zip with every workflow green.
(
  cd "$REPO"
  { shasum -a 256 "mac/Program218e_v3_Rewired_macOS.command"
    find mac/support -type f ! -name .DS_Store -print0 | sort -z | \
      xargs -0 shasum -a 256
  } > "$APP/Contents/Resources/SOURCES.sha256"
)

# --- the launcher ----------------------------------------------------------
# The flasher is an interactive terminal program, so the app's job is to open
# it in Terminal.  REWIRED_WORKDIR keeps the log and the firmware folder out
# of the bundle, which would otherwise break the seal on first run.
# The shell work lives in Resources; Contents/MacOS/launcher is a native
# binary that starts it.  A shell script has no architecture, so with one as
# the bundle executable LaunchServices could not tell whether the app was
# native, offered "Open using Rosetta" in Get Info with the box already
# ticked, and counted the app among those whose Intel support is ending -
# while every binary inside it was universal all along.
cat > "$APP/Contents/Resources/launch.sh" <<'LAUNCH'
#!/bin/bash
RES="$(cd "$(dirname "$0")" && pwd)"
BUNDLE="$(cd "$(dirname "$0")/../.." && pwd)"

# A quarantined app runs from a read-only copy of itself, and that copy is not
# where the person put anything.  The system will say where the original is, so
# the log and the record can go where they were always meant to: beside the app,
# in the folder that was unzipped.
case "$BUNDLE" in
    */AppTranslocation/*)
        REAL="$("$RES/support/resolve-translocation" "$BUNDLE" 2>/dev/null)"
        [ -n "$REAL" ] && BUNDLE="$REAL" ;;
esac
BESIDE="$(dirname "$BUNDLE")"

# Only if that folder can be written to.  Dragged into /Applications, or
# translocated with nothing willing to resolve it, it cannot be - and then
# there is nothing worth keeping anyway, so it goes somewhere temporary rather
# than into a folder of ours that would outlive the run.
if [ -w "$BESIDE" ]; then
    WORK="$BESIDE"
else
    WORK="${TMPDIR:-/tmp}"
fi

# Terminal opens a file; it cannot be handed an environment.  This shim carries
# it, and lives in the temporary directory because it is worth nothing after
# the run and the system clears it out on its own.
#
# printf %q, not a here-document.  These paths come from wherever the app was
# put, the shim is a shell script, and bash reads it: a folder named
# $(something) inside double quotes is a command, and it ran.  %q quotes them
# so they come back out as the strings they are.
RUNNER="${TMPDIR:-/tmp}/218e-rewired-run.command"
printf '#!/bin/bash\nexport REWIRED_WORKDIR=%q\nexec %q\n' \
       "$WORK" "$RES/Program218e_v3_Rewired_macOS.command" > "$RUNNER"
chmod +x "$RUNNER"
open -a Terminal "$RUNNER"
LAUNCH
chmod +x "$APP/Contents/Resources/launch.sh"

# The entry point itself: universal, so the app is unambiguously native.
for A in arm64 x86_64; do
    clang -O2 -target $A-apple-macos11 -o "$REPO/build/_launcher-$A" \
          "$REPO/mac/support/Launcher.c"
done
lipo -create -output "$APP/Contents/MacOS/launcher" \
     "$REPO/build/_launcher-arm64" "$REPO/build/_launcher-x86_64"
rm -f "$REPO/build/_launcher-arm64" "$REPO/build/_launcher-x86_64"
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
  <key>LSRequiresNativeExecution</key> <true/>
  <key>LSArchitecturePriority</key>    <array>
    <string>arm64</string>
    <string>x86_64</string>
  </array>
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
    #
    # --norsrc --noextattr drops the ._ AppleDouble twins ditto writes for the
    # extended attributes.  Here they carry nothing but com.apple.provenance,
    # and an unzip that does not understand them leaves twenty-five ._ files
    # scattered through the bundle.  The ticket is an ordinary file, so it
    # travels either way.
    rm -f "$ZIP"
    ditto -c -k --keepParent --norsrc --noextattr "$APP" "$ZIP"
    # Prove the shipped artefact carries the ticket, not just the app on disk.
    VERIFY="$(mktemp -d)"
    ditto -x -k "$ZIP" "$VERIFY"
    xcrun stapler validate "$VERIFY/$(basename "$APP")"
    spctl -a -vv -t exec "$VERIFY/$(basename "$APP")"
    rm -rf "$VERIFY"

    # The builder page hands out this exact archive, so it has to be in the
    # repository: the page is published by CI, and CI has neither the Developer
    # ID nor the notary credentials - deliberately.
    cp "$ZIP" "$REPO/mac/Flasher.zip"
    echo "  updated mac/Flasher.zip - commit it to publish the app"
fi
