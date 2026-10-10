#!/usr/bin/env bash
# Builds, signs (Developer ID), notarizes and packages the SwiftUI macOS app for a GitHub Release.
#
# Produces, in OUTPUT_DIR:
#   Keryx-<version>-macos-arm64<suffix>.zip   the stapled Keryx.app (what Sparkle updates from)
#   Keryx-<version>-macos-arm64<suffix>.dmg   a stapled disk image (stable releases only)
#
# Nothing is written to the GitHub Release here: release.yml uploads the files afterwards, and only
# if every step below succeeded. The build is archived and exported through Xcode, which also
# re-signs Sparkle's nested helpers and XPC services (Sparkle's documented, recommended route), so
# this script only verifies them. Do not add `codesign --deep` to a signing step.
#
# Used by release.yml's package-macos-swiftui job. See docs/build.md's "Release (CD)".
#
# Inputs (environment variables):
#   VERSION                   Release version without the leading "v", e.g. 0.1.0 or 0.1.0-beta.1
#                             (becomes CFBundleShortVersionString).
#   BUILD_NUMBER              Positive integer, increasing with every release (CFBundleVersion;
#                             Sparkle compares updates by it).
#   IS_PRERELEASE             "true" for a pre-release tag: then no .dmg is produced.
#   OUTPUT_DIR                Directory to write the .zip (and .dmg) into (created if missing).
#   DERIVED_DATA              xcodebuild -derivedDataPath; later steps find Sparkle's tools in it.
#   APPLE_TEAM_ID             Team ID of the Developer ID certificate.
#   PROVISIONING_PROFILE_PATH Developer ID provisioning profile for works.merc.keryx (needed by
#                             the keychain-access-groups entitlement).
#   NOTARY_KEY_PATH           App Store Connect API key (.p8) for notarytool.
#   NOTARY_KEY_ID             Its key ID.
#   NOTARY_ISSUER_ID          Its issuer ID.
#   NOTARY_TIMEOUT            Optional: how long to wait for each notarization, e.g. 30m (default),
#                             2h. The first submissions of a new Apple account can take hours.
#   ARTIFACT_SUFFIX           Optional suffix before the extension; defaults to "-swiftui" so the
#                             files cannot replace the Compose build's Keryx-<v>-macos-arm64.zip
#                             until that build is retired (then set it to empty).
#   SIGNING_IDENTITY          Optional name or 40-digit SHA-1 of the certificate to sign with;
#                             defaults to "Developer ID Application", which is enough when the
#                             keychain holds a single such certificate (as release.yml's does). Set
#                             a SHA-1 when more than one matches, e.g. when running this by hand.
#   OAuth client ids (DROPBOX_APP_KEY, ONEDRIVE_CLIENT_ID, GOOGLE_DRIVE_APPLE_CLIENT_ID) are read
#   from the environment by the Gradle build that Xcode runs.
#
# The signing identity must already be in a keychain on the search list (release.yml imports it
# into a temporary one).
set -euo pipefail

fail() {
  echo "::error::$*" >&2
  exit 1
}

: "${VERSION:?VERSION is not set}"
: "${BUILD_NUMBER:?BUILD_NUMBER is not set}"
: "${IS_PRERELEASE:?IS_PRERELEASE is not set}"
: "${OUTPUT_DIR:?OUTPUT_DIR is not set}"
: "${DERIVED_DATA:?DERIVED_DATA is not set}"
: "${APPLE_TEAM_ID:?APPLE_TEAM_ID is not set}"
: "${PROVISIONING_PROFILE_PATH:?PROVISIONING_PROFILE_PATH is not set}"
: "${NOTARY_KEY_PATH:?NOTARY_KEY_PATH is not set}"
: "${NOTARY_KEY_ID:?NOTARY_KEY_ID is not set}"
: "${NOTARY_ISSUER_ID:?NOTARY_ISSUER_ID is not set}"
suffix="${ARTIFACT_SUFFIX--swiftui}"
signing_identity="${SIGNING_IDENTITY:-Developer ID Application}"
notary_timeout="${NOTARY_TIMEOUT:-30m}"

[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z]+(\.[0-9A-Za-z]+)*)?$ ]] \
  || fail "Invalid version: '$VERSION'."
[[ "$BUILD_NUMBER" =~ ^[1-9][0-9]*$ ]] || fail "Invalid build number: '$BUILD_NUMBER'."
[[ "$APPLE_TEAM_ID" =~ ^[A-Z0-9]{10}$ ]] || fail "Invalid Apple team id."
# The identity is written into an xcconfig and a plist below, so accept only what a certificate
# name or SHA-1 can contain.
identity_pattern='^[A-Za-z0-9 :().,-]+$'
[[ "$signing_identity" =~ $identity_pattern ]] || fail "Unexpected characters in SIGNING_IDENTITY."
[[ "$notary_timeout" =~ ^[1-9][0-9]*[smh]$ ]] || fail "Invalid NOTARY_TIMEOUT: '$notary_timeout' (use e.g. 30m, 2h)."
[[ "$IS_PRERELEASE" == "true" || "$IS_PRERELEASE" == "false" ]] \
  || fail "IS_PRERELEASE must be true or false."
[ -s "$PROVISIONING_PROFILE_PATH" ] || fail "Provisioning profile not found: $PROVISIONING_PROFILE_PATH"
[ -s "$NOTARY_KEY_PATH" ] || fail "Notary API key not found: $NOTARY_KEY_PATH"

# A bad notarization credential (a damaged key, a wrong key id or issuer) would otherwise only show
# when the app is submitted, after the 15 minute build. Ask the notary service for the submission
# history first: a read-only call that fails within seconds if it does not accept the credentials.
notary_auth=(--key "$NOTARY_KEY_PATH" --key-id "$NOTARY_KEY_ID" --issuer "$NOTARY_ISSUER_ID")
xcrun notarytool history "${notary_auth[@]}" > /dev/null \
  || fail "Apple's notary service could not be reached or did not accept the notarization credentials (see notarytool's error above); check the API key, its key id and the issuer id."

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
app_dir="$root/appleApp"
local_xcconfig="$app_dir/Local.xcconfig"

# Local.xcconfig is how the signing settings reach the Xcode project (Shared.xcconfig includes it),
# which keeps them off the xcodebuild command line where they would also apply to Swift package
# targets. A developer's own copy must never be overwritten or deleted.
[ ! -e "$local_xcconfig" ] \
  || fail "$local_xcconfig already exists; refusing to overwrite a local signing configuration."

work="$(mktemp -d)"
installed_profile=""
cleanup() {
  rm -f "$local_xcconfig"
  rm -rf "$work"
  # Only a profile this run installed: one that was already there is the developer's own.
  if [ -n "$installed_profile" ]; then rm -f "$installed_profile"; fi
}
trap cleanup EXIT

mkdir -p "$OUTPUT_DIR"
base_name="Keryx-$VERSION-macos-arm64$suffix"

# --- Provisioning profile: install it and read the name Xcode needs to select it. ---
profile_plist="$work/profile.plist"
security cms -D -i "$PROVISIONING_PROFILE_PATH" > "$profile_plist"
profile_name="$(/usr/libexec/PlistBuddy -c 'Print :Name' "$profile_plist")"
profile_uuid="$(/usr/libexec/PlistBuddy -c 'Print :UUID' "$profile_plist")"
profile_team="$(/usr/libexec/PlistBuddy -c 'Print :TeamIdentifier:0' "$profile_plist")"
[ "$profile_team" = "$APPLE_TEAM_ID" ] \
  || fail "The provisioning profile belongs to a different team than APPLE_TEAM_ID."
[[ "$profile_name" =~ ^[A-Za-z0-9._\ -]+$ ]] || fail "Unexpected characters in the provisioning profile name."
[[ "$profile_uuid" =~ ^[0-9A-Fa-f-]+$ ]] || fail "Unexpected provisioning profile UUID."
# Xcode only loads a macOS profile named <uuid>.provisionprofile (iOS's is .mobileprovision); any
# other extension is ignored with "No provisioning profile provider found".
profiles_dir="$HOME/Library/Developer/Xcode/UserData/Provisioning Profiles"
profile_target="$profiles_dir/$profile_uuid.provisionprofile"
mkdir -p "$profiles_dir"
if [ ! -e "$profile_target" ]; then
  cp "$PROVISIONING_PROFILE_PATH" "$profile_target"
  installed_profile="$profile_target"
elif ! cmp -s "$PROVISIONING_PROFILE_PATH" "$profile_target"; then
  # Never overwrite the developer's own file, and never sign with a stale copy of the profile.
  fail "A different provisioning profile is already installed at $profile_target; remove or replace it and run again."
fi

cat > "$local_xcconfig" <<EOF
CODE_SIGN_STYLE = Manual
CODE_SIGN_IDENTITY = $signing_identity
DEVELOPMENT_TEAM = $APPLE_TEAM_ID
PROVISIONING_PROFILE_SPECIFIER = $profile_name
EOF

# --- Build. The XCFramework and the String Catalog must exist before xcodegen runs. ---
echo "Building the shared framework and the String Catalog..."
(cd "$root" && ./gradlew :shared:assembleKeryxSharedReleaseXCFramework :composeApp:generateStringCatalog)
(cd "$app_dir" && xcodegen generate)

archive="$work/Keryx.xcarchive"
echo "Archiving Keryx $VERSION ($BUILD_NUMBER)..."
xcodebuild archive \
  -project "$app_dir/Keryx.xcodeproj" \
  -scheme Keryx \
  -configuration Release \
  -destination 'generic/platform=macOS' \
  -archivePath "$archive" \
  -derivedDataPath "$DERIVED_DATA" \
  MARKETING_VERSION="$VERSION" \
  CURRENT_PROJECT_VERSION="$BUILD_NUMBER"

export_options="$work/ExportOptions.plist"
cat > "$export_options" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>method</key><string>developer-id</string>
  <key>teamID</key><string>$APPLE_TEAM_ID</string>
  <key>signingStyle</key><string>manual</string>
  <key>signingCertificate</key><string>$signing_identity</string>
  <key>provisioningProfiles</key>
  <dict>
    <key>works.merc.keryx</key><string>$profile_name</string>
  </dict>
</dict>
</plist>
EOF

xcodebuild -exportArchive \
  -archivePath "$archive" \
  -exportPath "$work/export" \
  -exportOptionsPlist "$export_options"

app="$work/export/Keryx.app"
[ -d "$app" ] || fail "Export produced no Keryx.app."

# --- Verify the signature before spending a notarization round on it. ---
verify_signed() {
  local target="$1" details
  codesign --verify --strict --verbose=2 "$target" || fail "Signature check failed: $target"
  details="$(codesign -dv --verbose=4 "$target" 2>&1)"
  grep -q "^TeamIdentifier=$APPLE_TEAM_ID\$" <<< "$details" \
    || fail "$target is not signed by team $APPLE_TEAM_ID."
  grep -q '^Authority=Developer ID Application' <<< "$details" \
    || fail "$target is not signed with a Developer ID Application certificate."
}
verify_signed "$app"
# Captured first: `codesign ... | grep -q` would make grep quit at its match and codesign die of
# SIGPIPE, which `set -o pipefail` reports as a failure even though the flag is there.
app_details="$(codesign -dv --verbose=4 "$app" 2>&1)"
grep -q 'flags=.*(runtime)' <<< "$app_details" || fail "The app is not signed with the hardened runtime."
codesign --verify --deep --strict "$app" || fail "Deep signature check failed."

# Xcode re-signs Sparkle's helpers on export; confirm none was left with Sparkle's own signature.
sparkle="$app/Contents/Frameworks/Sparkle.framework"
[ -d "$sparkle" ] || fail "Sparkle.framework is missing from the app."
sparkle_parts=0
while IFS= read -r -d '' part; do
  verify_signed "$part"
  sparkle_parts=$((sparkle_parts + 1))
done < <(find "$sparkle" \( -name '*.xpc' -o -name 'Updater.app' -o -name 'Autoupdate' \) -print0)
[ "$sparkle_parts" -ge 3 ] || fail "Expected Sparkle's installer helpers; found $sparkle_parts."
verify_signed "$sparkle"

# --- Notarize and staple. ---
notarize() {
  local file="$1" json id status
  json="$work/notary-$(basename "$file").json"
  # Submit first and wait separately: the submission id is then known (and printed) before the long
  # part, so a timeout can be reported and resumed. Submitting fails right here on bad credentials.
  xcrun notarytool submit "$file" "${notary_auth[@]}" --no-wait --output-format json > "$json"
  id="$(plutil -extract id raw -o - "$json" 2> /dev/null)" || id=""
  [[ "$id" =~ ^[0-9A-Fa-f-]{36}$ ]] || fail "notarytool returned no submission id for $(basename "$file")."
  echo "Submitted $(basename "$file") for notarization: $id"
  # `wait` exits non-zero on a timeout; the outcome is read from `info` below either way.
  xcrun notarytool wait "$id" "${notary_auth[@]}" --timeout "$notary_timeout" || true
  xcrun notarytool info "$id" "${notary_auth[@]}" --output-format json > "$json"
  status="$(plutil -extract status raw -o - "$json" 2> /dev/null)" || status=""
  if [ "$status" = "In Progress" ]; then
    fail "Notarization of $(basename "$file") is still in progress after $notary_timeout (submission $id). The first submissions of a new Apple account can take hours: wait with 'xcrun notarytool wait $id' (same credentials), then run again."
  fi
  # Always read the log, even on success: it lists warnings worth fixing before they become errors.
  xcrun notarytool log "$id" "${notary_auth[@]}" || true
  [ "$status" = "Accepted" ] || fail "Notarization of $(basename "$file") ended as '$status' (submission $id)."
}

echo "Notarizing the app..."
ditto -c -k --keepParent "$app" "$work/notarize.zip"
notarize "$work/notarize.zip"
xcrun stapler staple "$app"
xcrun stapler validate "$app"
spctl --assess --type execute --verbose=2 "$app" || fail "Gatekeeper rejects the notarized app."

# --- Package. The zip is made from the stapled app, so the ticket travels with it. ---
zip_path="$OUTPUT_DIR/$base_name.zip"
ditto -c -k --sequesterRsrc --keepParent "$app" "$zip_path"
echo "Wrote $zip_path"

if [ "$IS_PRERELEASE" = "false" ]; then
  staging="$work/dmg"
  mkdir -p "$staging"
  cp -R "$app" "$staging/Keryx.app"
  ln -s /Applications "$staging/Applications"
  dmg_path="$OUTPUT_DIR/$base_name.dmg"
  hdiutil create -volname Keryx -srcfolder "$staging" -ov -format UDZO "$dmg_path"
  codesign --sign "$signing_identity" --timestamp "$dmg_path"
  echo "Notarizing the disk image..."
  notarize "$dmg_path"
  xcrun stapler staple "$dmg_path"
  xcrun stapler validate "$dmg_path"
  spctl --assess --type open --context context:primary-signature --verbose=2 "$dmg_path" \
    || fail "Gatekeeper rejects the notarized disk image."
  echo "Wrote $dmg_path"
fi
