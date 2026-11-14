#!/usr/bin/env bash
# Selects the newest non-beta Xcode installed on the runner and makes the rest of the job use it.
#
# The Apple jobs (ci.yml's build-apple, release.yml's package-macos and publish-app-store.yml's
# publish) run on the `xcode-27` image and build with whatever release Xcode it has newest, rather
# than a pinned path, so a new Xcode on the image is picked up without a workflow change. A beta is
# never picked: App Store Connect does not accept a build made with one. Nothing is checked against a
# minimum SDK; the Xcode and SDK versions picked are reported every time instead (a job notice and
# the step summary), so a release can be checked for, say, the iOS 27.1 SDK the iPhone Duo badge
# needs. See docs/build.md's "Building the SwiftUI app".
#
# A beta is recognized by its path (Xcode_27_beta_3.app, Xcode-beta.app) or by its build number,
# which ends in a lowercase letter for a beta (27A5218g) and not for a release (27A9275).
#
# Usage: select-xcode.sh
#
# Inputs:
#   XCODE_SEARCH_DIR    Directory holding the Xcode*.app bundles (default /Applications); only
#                       overridden to test this script.
#   GITHUB_ENV          Where DEVELOPER_DIR is written for the following steps; without it the
#                       assignment is only printed.
#   GITHUB_STEP_SUMMARY Where the summary is written; without it, to stdout.
set -euo pipefail

search_dir="${XCODE_SEARCH_DIR:-/Applications}"

best_app=""
best_version=""
best_build=""
# Bundles already looked at, by their resolved path, so Xcode.app pointing at Xcode_27.1.app is
# considered once. Delimited with "|" since bash 3.2 (macOS's /bin/bash) has no associative arrays.
seen="|"

shopt -s nullglob
for app in "$search_dir"/Xcode*.app; do
    real="$(realpath "$app")" || continue
    case "$seen" in
        *"|$real|"*) continue ;;
    esac
    seen="$seen$real|"

    paths_lower="$(printf '%s %s' "$app" "$real" | tr '[:upper:]' '[:lower:]')"
    if [[ "$paths_lower" == *beta* ]]; then
        echo "Skipping $app: a beta (by its path)"
        continue
    fi

    xcodebuild="$real/Contents/Developer/usr/bin/xcodebuild"
    if [ ! -x "$xcodebuild" ]; then
        echo "Skipping $app: no xcodebuild"
        continue
    fi
    if ! out="$("$xcodebuild" -version 2>/dev/null)"; then
        echo "Skipping $app: xcodebuild -version failed"
        continue
    fi
    version="$(sed -n 's/^Xcode \([0-9][0-9.]*\).*$/\1/p' <<<"$out" | head -n 1)"
    build="$(sed -n 's/^Build version \([^ ]*\).*$/\1/p' <<<"$out" | head -n 1)"
    if [ -z "$version" ] || [ -z "$build" ]; then
        echo "Skipping $app: unrecognized xcodebuild -version output"
        continue
    fi
    if [[ "$build" =~ [a-z]$ ]]; then
        echo "Skipping $app: Xcode $version ($build) is a beta (by its build number)"
        continue
    fi
    echo "Found $app: Xcode $version ($build)"

    if [ -z "$best_version" ] ||
        { [ "$version" != "$best_version" ] &&
            [ "$(printf '%s\n%s\n' "$best_version" "$version" | sort -V | tail -n 1)" = "$version" ]; }; then
        best_app="$real"
        best_version="$version"
        best_build="$build"
    fi
done

if [ -z "$best_app" ]; then
    echo "::error::No release (non-beta) Xcode found in $search_dir"
    exit 1
fi

developer_dir="$best_app/Contents/Developer"
# $GITHUB_ENV takes effect only from the next step, so this step queries the selected xcodebuild by
# its path.
sdk_version() {
    "$developer_dir/usr/bin/xcodebuild" -version -sdk "$1" SDKVersion 2>/dev/null || echo "unknown"
}
ios_sdk="$(sdk_version iphoneos)"
macos_sdk="$(sdk_version macosx)"

if [ -n "${GITHUB_ENV:-}" ]; then
    echo "DEVELOPER_DIR=$developer_dir" >>"$GITHUB_ENV"
fi
echo "DEVELOPER_DIR=$developer_dir"

echo "::notice title=Xcode::Xcode $best_version ($best_build), iOS SDK $ios_sdk, macOS SDK $macos_sdk"

summary="${GITHUB_STEP_SUMMARY:-/dev/stdout}"
{
    echo "### Xcode"
    echo
    echo "| Xcode | Build | iOS SDK | macOS SDK | Path |"
    echo "| --- | --- | --- | --- | --- |"
    echo "| $best_version | $best_build | $ios_sdk | $macos_sdk | \`$best_app\` |"
} >>"$summary"
