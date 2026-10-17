#!/usr/bin/env bash
# Builds, signs (Apple Distribution), validates and uploads the SwiftUI iOS app to App Store Connect.
#
# Uploading only adds a build: attaching it to an App Store version and submitting it for review is
# app-store-connect.sh's `submit`, which publish-app-store.yml runs afterwards. The build is
# archived and exported through Xcode with manual signing, like package-macos-swiftui.sh, then
# checked for the settings review depends on (verify-store-requirements.sh) and run through
# altool's validation before the upload.
#
# Used by publish-app-store.yml. See docs/build.md's "Publishing to the App Store".
#
# Inputs (environment variables):
#   VERSION                   Marketing version, x.y.z (CFBundleShortVersionString); App Store
#                             versions take no pre-release suffix.
#   BUILD_NUMBER              Positive integer not yet uploaded for VERSION (CFBundleVersion); see
#                             app-store-connect.sh's `next-build-number`.
#   DERIVED_DATA              xcodebuild -derivedDataPath.
#   APPLE_TEAM_ID             Team ID of the Apple Distribution certificate.
#   PROVISIONING_PROFILE_PATH iOS App Store Connect distribution profile for works.merc.keryx.
#   ASC_KEY_DIR               Directory holding the App Store Connect API key as AuthKey_<id>.p8
#                             (where altool looks for it).
#   ASC_KEY_ID                Its key ID.
#   ASC_ISSUER_ID             The team's issuer ID.
#   REPO_ROOT                 Optional checkout to build; defaults to the one this script is in.
#                             publish-app-store.yml takes its scripts from the workflow's own ref
#                             and builds the requested ref, so it sets this.
#   SIGNING_IDENTITY          Optional name or 40-digit SHA-1 of the certificate to sign with;
#                             defaults to "Apple Distribution", which is enough when the keychain
#                             holds a single such certificate (as publish-app-store.yml's does).
#   OAuth client ids (DROPBOX_APP_KEY, ONEDRIVE_CLIENT_ID, GOOGLE_DRIVE_APPLE_CLIENT_ID) are read
#   from the environment by the Gradle build.
#
# The signing identity must already be in a keychain on the search list (publish-app-store.yml
# imports it into a temporary one).
set -euo pipefail

fail() {
  echo "::error::$*" >&2
  exit 1
}

: "${VERSION:?VERSION is not set}"
: "${BUILD_NUMBER:?BUILD_NUMBER is not set}"
: "${DERIVED_DATA:?DERIVED_DATA is not set}"
: "${APPLE_TEAM_ID:?APPLE_TEAM_ID is not set}"
: "${PROVISIONING_PROFILE_PATH:?PROVISIONING_PROFILE_PATH is not set}"
: "${ASC_KEY_DIR:?ASC_KEY_DIR is not set}"
: "${ASC_KEY_ID:?ASC_KEY_ID is not set}"
: "${ASC_ISSUER_ID:?ASC_ISSUER_ID is not set}"
signing_identity="${SIGNING_IDENTITY:-Apple Distribution}"

[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "Invalid version: '$VERSION' (expected x.y.z)."
[[ "$BUILD_NUMBER" =~ ^[1-9][0-9]*$ ]] || fail "Invalid build number: '$BUILD_NUMBER'."
[[ "$APPLE_TEAM_ID" =~ ^[A-Z0-9]{10}$ ]] || fail "Invalid Apple team id."
[[ "$ASC_KEY_ID" =~ ^[A-Z0-9]{10}$ ]] || fail "Invalid App Store Connect key ID."
# The identity is written into an xcconfig and a plist below, so accept only what a certificate
# name or SHA-1 can contain.
identity_pattern='^[A-Za-z0-9 :().,-]+$'
[[ "$signing_identity" =~ $identity_pattern ]] || fail "Unexpected characters in SIGNING_IDENTITY."
[ -s "$PROVISIONING_PROFILE_PATH" ] || fail "Provisioning profile not found: $PROVISIONING_PROFILE_PATH"
[ -s "$ASC_KEY_DIR/AuthKey_$ASC_KEY_ID.p8" ] || fail "App Store Connect API key not found in $ASC_KEY_DIR."

root="${REPO_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
[ -f "$root/appleApp/project.yml" ] || fail "No appleApp/project.yml under $root."
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
# An App Store profile lists no devices; a development or ad hoc one would not be accepted.
if /usr/libexec/PlistBuddy -c 'Print :ProvisionedDevices' "$profile_plist" > /dev/null 2>&1; then
  fail "The provisioning profile lists devices; use an App Store Connect distribution profile."
fi
# Xcode loads an iOS profile named <uuid>.mobileprovision (macOS's is .provisionprofile).
profiles_dir="$HOME/Library/Developer/Xcode/UserData/Provisioning Profiles"
profile_target="$profiles_dir/$profile_uuid.mobileprovision"
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
echo "Archiving Keryx $VERSION ($BUILD_NUMBER) for iOS..."
xcodebuild archive \
  -project "$app_dir/Keryx.xcodeproj" \
  -scheme Keryx \
  -configuration Release \
  -destination 'generic/platform=iOS' \
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
  <key>method</key><string>app-store-connect</string>
  <key>destination</key><string>export</string>
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

ipa="$work/export/Keryx.ipa"
[ -s "$ipa" ] || fail "Export produced no Keryx.ipa."

# --- Check what was exported before spending an upload on it. ---
ditto -x -k "$ipa" "$work/ipa"
app="$work/ipa/Payload/Keryx.app"
[ -d "$app" ] || fail "Keryx.ipa contains no Payload/Keryx.app."
"$app_dir/Scripts/verify-store-requirements.sh" "$app"

codesign --verify --deep --strict --verbose=2 "$app" || fail "Signature check failed: $app"
# Captured first: `codesign ... | grep -q` would make grep quit at its match and codesign die of
# SIGPIPE, which `set -o pipefail` reports as a failure.
app_details="$(codesign -dv --verbose=4 "$app" 2>&1)"
grep -q "^TeamIdentifier=$APPLE_TEAM_ID\$" <<< "$app_details" \
  || fail "$app is not signed by team $APPLE_TEAM_ID."
grep -q '^Authority=Apple Distribution' <<< "$app_details" \
  || fail "$app is not signed with an Apple Distribution certificate."
version_in_app="$(plutil -extract CFBundleShortVersionString raw -o - "$app/Info.plist")"
build_in_app="$(plutil -extract CFBundleVersion raw -o - "$app/Info.plist")"
[ "$version_in_app" = "$VERSION" ] && [ "$build_in_app" = "$BUILD_NUMBER" ] \
  || fail "The app says $version_in_app ($build_in_app), expected $VERSION ($BUILD_NUMBER)."

# --- Validate, then upload. altool finds AuthKey_<id>.p8 through API_PRIVATE_KEYS_DIR. ---
altool_auth=(-t ios --apiKey "$ASC_KEY_ID" --apiIssuer "$ASC_ISSUER_ID")
echo "Validating Keryx $VERSION ($BUILD_NUMBER)..."
API_PRIVATE_KEYS_DIR="$ASC_KEY_DIR" xcrun altool --validate-app -f "$ipa" "${altool_auth[@]}" \
  || fail "App Store Connect rejected the build in validation (see altool's output above)."
echo "Uploading Keryx $VERSION ($BUILD_NUMBER)..."
API_PRIVATE_KEYS_DIR="$ASC_KEY_DIR" xcrun altool --upload-app -f "$ipa" "${altool_auth[@]}" \
  || fail "The upload to App Store Connect failed (see altool's output above)."
echo "Uploaded Keryx $VERSION ($BUILD_NUMBER)."
