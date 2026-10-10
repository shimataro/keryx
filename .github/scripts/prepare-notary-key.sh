#!/usr/bin/env bash
# Writes the App Store Connect API key (.p8) that notarytool signs in with from the
# APPLE_NOTARY_KEY secret, restoring its line breaks first.
#
# notarytool rejects a key whose PEM layout is damaged with just `invalidPEMDocument`, and only
# once the app is built and submitted. A secret that was pasted into a form can lose its line
# breaks (or gain spaces, CRLFs, blank lines or indentation) without anything looking wrong, so the
# key is rebuilt from its base64 body — header and footer lines, 64 characters per line — and
# checked to parse as a private key before the job spends minutes on the build.
#
# Only the shape of the value is reported on failure (line count, whether the markers are present,
# its length), never any of its content.
#
# Used by release.yml's package-macos job. See docs/build.md's "Release (CD)".
#
# Usage: APPLE_NOTARY_KEY=<secret> prepare-notary-key.sh <output path>
#
# Inputs:
#   APPLE_NOTARY_KEY  The text of the .p8 file (the whole PEM, however its whitespace got mangled).
#   $1                Where to write the cleaned key; created with mode 600.
set -euo pipefail

fail() {
  echo "::error::$*" >&2
  exit 1
}

: "${APPLE_NOTARY_KEY:?APPLE_NOTARY_KEY is not set}"
output="${1:?Usage: prepare-notary-key.sh <output path>}"

begin='-----BEGIN PRIVATE KEY-----'
end='-----END PRIVATE KEY-----'

raw="${APPLE_NOTARY_KEY//$'\r'/}"
body="${raw//"$begin"/}"
body="${body//"$end"/}"
body="$(printf '%s' "$body" | tr -d ' \n\t')"

# The metadata is printed before the key is judged, so a failure explains itself.
lines="$(printf '%s\n' "$raw" | wc -l | tr -d ' ')"
has_begin=no
has_end=no
[[ "$raw" == *"$begin"* ]] && has_begin=yes
[[ "$raw" == *"$end"* ]] && has_end=yes
echo "APPLE_NOTARY_KEY: $lines lines, ${#raw} characters, BEGIN marker: $has_begin, END marker: $has_end, key body: ${#body} characters"

[ -n "$body" ] || fail "APPLE_NOTARY_KEY has no key body. Register the whole contents of the AuthKey_<id>.p8 file."
[[ "$body" =~ ^[A-Za-z0-9+/=]+$ ]] \
  || fail "APPLE_NOTARY_KEY is not base64 once the markers and whitespace are removed. Register the contents of the AuthKey_<id>.p8 file."

umask 077
{
  printf '%s\n' "$begin"
  printf '%s\n' "$body" | fold -w 64
  printf '%s\n' "$end"
} > "$output"

/usr/bin/openssl pkey -in "$output" -noout > /dev/null 2>&1 \
  || { rm -f "$output"; fail "APPLE_NOTARY_KEY does not parse as a private key. Register the whole contents of the AuthKey_<id>.p8 file."; }

echo "Wrote the notarization key to $output."
