#!/bin/bash
# Checks a built Keryx.app (macOS or iOS) for the settings App Store upload and review depend on,
# so a later edit to project.yml or PrivacyInfo.xcprivacy cannot drop one silently. CI runs it on
# the iOS Simulator build and the macOS Release archive. See docs/build.md's "App Store submission
# materials" for why each one is there.
#
# Usage: verify-store-requirements.sh <path to Keryx.app>
set -euo pipefail

if [ $# -ne 1 ] || [ ! -d "$1" ]; then
    echo "usage: $0 <path to Keryx.app>" >&2
    exit 2
fi
app="$1"

# macOS bundles keep everything under Contents/; iOS bundles are flat.
if [ -d "$app/Contents" ]; then
    platform="macOS"
    info="$app/Contents/Info.plist"
    manifest="$app/Contents/Resources/PrivacyInfo.xcprivacy"
else
    platform="iOS"
    info="$app/Info.plist"
    manifest="$app/PrivacyInfo.xcprivacy"
fi

failures=0
fail() {
    echo "error: $1" >&2
    failures=$((failures + 1))
}

# Print a value from a plist, or nothing when the key path does not exist. plutil reports a missing
# key on stdout rather than stderr, so its output is used only when it succeeds.
plist_extract() {
    # $1 format, $2 plist, $3 key path
    local out
    if out="$(plutil -extract "$3" "$1" -o - "$2" 2>/dev/null)"; then
        printf '%s' "$out"
    fi
}
read_raw() { plist_extract raw "$1" "$2"; }
read_json() { plist_extract json "$1" "$2"; }

# Privacy manifest: no tracking, and every required-reason API category the binary uses declared
# with the reason Keryx uses it for (ITMS-91053).
if [ ! -f "$manifest" ]; then
    fail "$platform: PrivacyInfo.xcprivacy is missing from the bundle ($manifest)"
else
    [ "$(read_raw "$manifest" NSPrivacyTracking)" = "false" ] ||
        fail "$platform: NSPrivacyTracking must be false"
    count="$(read_raw "$manifest" NSPrivacyAccessedAPITypes)"
    declared=""
    for ((i = 0; i < ${count:-0}; i++)); do
        type="$(read_raw "$manifest" "NSPrivacyAccessedAPITypes.$i.NSPrivacyAccessedAPIType")"
        reasons="$(read_json "$manifest" "NSPrivacyAccessedAPITypes.$i.NSPrivacyAccessedAPITypeReasons")"
        declared+="$type $reasons"$'\n'
    done
    for expected in \
        "NSPrivacyAccessedAPICategoryUserDefaults CA92.1" \
        "NSPrivacyAccessedAPICategoryFileTimestamp C617.1" \
        "NSPrivacyAccessedAPICategoryDiskSpace E174.1"; do
        category="${expected% *}"
        reason="${expected#* }"
        found=0
        while IFS= read -r entry; do
            if [[ "$entry" == "$category "* && "$entry" == *"\"$reason\""* ]]; then
                found=1
            fi
        done <<<"$declared"
        [ "$found" = 1 ] ||
            fail "$platform: PrivacyInfo.xcprivacy does not declare $category with reason $reason"
    done
fi

# ATS: feeds the user subscribes to may be plain http://. NSAllowsArbitraryLoadsInWebContent must
# stay absent, since its presence makes the OS ignore NSAllowsArbitraryLoads.
[ "$(read_raw "$info" NSAppTransportSecurity.NSAllowsArbitraryLoads)" = "true" ] ||
    fail "$platform: NSAppTransportSecurity.NSAllowsArbitraryLoads must be true"
[ -z "$(read_raw "$info" NSAppTransportSecurity.NSAllowsArbitraryLoadsInWebContent)" ] ||
    fail "$platform: NSAppTransportSecurity.NSAllowsArbitraryLoadsInWebContent must not be set"

# Export compliance: HTTPS and hashing only, so the per-upload question is answered here.
[ "$(read_raw "$info" ITSAppUsesNonExemptEncryption)" = "false" ] ||
    fail "$platform: ITSAppUsesNonExemptEncryption must be false"

# iPad multitasking needs all four orientations (ITMS-90474).
if [ "$platform" = "iOS" ]; then
    ipad="$(read_json "$info" "UISupportedInterfaceOrientations~ipad")"
    for orientation in Portrait PortraitUpsideDown LandscapeLeft LandscapeRight; do
        case "$ipad" in
            *"\"UIInterfaceOrientation$orientation\""*) ;;
            *) fail "iOS: UISupportedInterfaceOrientations~ipad lacks UIInterfaceOrientation$orientation" ;;
        esac
    done
    [ -n "$(read_json "$info" "UISupportedInterfaceOrientations~iphone")" ] ||
        fail "iOS: UISupportedInterfaceOrientations~iphone is missing"
fi

if [ "$failures" -gt 0 ]; then
    echo "$failures App Store requirement(s) missing from $app" >&2
    exit 1
fi
echo "$platform: App Store requirements present in $app"
