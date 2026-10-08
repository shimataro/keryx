#!/usr/bin/env bash
# Generates the Sparkle appcast (`appcast.xml`) for one SwiftUI macOS release archive.
#
# Sparkle's generate_appcast signs the archive with the release EdDSA key and writes an appcast
# whose single item points at the archive's GitHub Release download URL. The app reads it from
# `releases/latest/download/appcast.xml` (SUFeedURL in appleApp/project.yml), so the file is
# attached to the release alongside the archive. Only this release's archive goes in: earlier
# releases are not carried over, and no delta updates or channels are produced.
#
# Used by release.yml's package-macos-swiftui job. See docs/build.md's "Release (CD)".
#
# Inputs (environment variables):
#   ZIP_PATH            Path to the notarized, stapled `.zip` of Keryx.app to publish.
#   RELEASE_TAG         The release's tag, e.g. v0.1.0 (it is part of the download URL).
#   OUTPUT_DIR          Directory to write appcast.xml into (created if missing).
#   DERIVED_DATA        The xcodebuild -derivedDataPath used for the build; Sparkle's command-line
#                       tools are taken from its resolved package artifacts.
#   SPARKLE_PRIVATE_KEY Contents of the exported EdDSA private key (`generate_keys -x`). Optional:
#                       without it the key is read from the login Keychain, for running locally.
#   GITHUB_REPOSITORY   owner/name of the repository (set by GitHub Actions); defaults to
#                       shimataro/keryx.
set -euo pipefail

fail() {
  echo "::error::$*" >&2
  exit 1
}

: "${ZIP_PATH:?ZIP_PATH is not set}"
: "${RELEASE_TAG:?RELEASE_TAG is not set}"
: "${OUTPUT_DIR:?OUTPUT_DIR is not set}"
: "${DERIVED_DATA:?DERIVED_DATA is not set}"
repository="${GITHUB_REPOSITORY:-shimataro/keryx}"

# The tag becomes part of a URL and of a directory prefix: accept only what the release workflow
# itself accepts (vMAJOR.MINOR.PATCH with an optional pre-release suffix).
[[ "$RELEASE_TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z]+(\.[0-9A-Za-z]+)*)?$ ]] \
  || fail "Invalid release tag: '$RELEASE_TAG'."
[ -s "$ZIP_PATH" ] || fail "Archive not found or empty: $ZIP_PATH"
[[ "$ZIP_PATH" == *.zip ]] || fail "Not a .zip archive: $ZIP_PATH"

tool="$DERIVED_DATA/SourcePackages/artifacts/sparkle/Sparkle/bin/generate_appcast"
[ -x "$tool" ] || fail "generate_appcast not found at $tool (was the app built with this derived data path?)"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

archive_name="$(basename "$ZIP_PATH")"
cp "$ZIP_PATH" "$work/$archive_name"

download_prefix="https://github.com/$repository/releases/download/$RELEASE_TAG/"
release_page="https://github.com/$repository/releases/tag/$RELEASE_TAG"

args=(
  --download-url-prefix "$download_prefix"
  --full-release-notes-url "$release_page"
)
if [ -n "${SPARKLE_PRIVATE_KEY:-}" ]; then
  # '-' reads the key from standard input, so it never appears in a process listing or a log.
  printf '%s' "$SPARKLE_PRIVATE_KEY" | "$tool" "${args[@]}" --ed-key-file - "$work"
else
  echo "SPARKLE_PRIVATE_KEY is not set; signing with the key in the login Keychain."
  "$tool" "${args[@]}" "$work"
fi

appcast="$work/appcast.xml"
[ -s "$appcast" ] || fail "generate_appcast produced no appcast.xml."

expected_url="$download_prefix$archive_name"
grep -q "<enclosure url=\"$expected_url\"" "$appcast" \
  || fail "appcast.xml has no enclosure for $expected_url."
# generate_appcast leaves the signature out (and only warns) when the signing key does not match
# the SUPublicEDKey embedded in the app: every client would reject such an update.
grep -q 'sparkle:edSignature=' "$appcast" \
  || fail "appcast.xml enclosure carries no EdDSA signature; does SPARKLE_PRIVATE_KEY match the app's SUPublicEDKey?"

mkdir -p "$OUTPUT_DIR"
cp "$appcast" "$OUTPUT_DIR/appcast.xml"
echo "Wrote $OUTPUT_DIR/appcast.xml for $archive_name."
