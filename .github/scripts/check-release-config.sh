#!/usr/bin/env bash
# Decides whether release.yml's package-macos-swiftui job has everything it needs to sign, notarize
# and publish the SwiftUI macOS app, and writes `configured=true|false` to $GITHUB_OUTPUT.
#
# Every later step of that job is gated on this output, so a repository that has not set the Apple
# Developer ID and notarization secrets up yet keeps releasing exactly as before: the job reports
# which secrets are missing and succeeds without building or uploading anything. An unset secret
# reaches the job as an empty string, so empty (or whitespace-only) counts as missing. Only the
# names of the missing secrets are printed, never a value.
#
# Used by release.yml's package-macos-swiftui job. See docs/build.md's "Release (CD)".
#
# Inputs (environment variables, one per required secret; see `required` below):
#   Each may be unset or empty. GITHUB_OUTPUT is where the result is written; it defaults to
#   standard output so the script can be run by hand.
set -euo pipefail

required=(
  APPLE_DEVELOPER_ID_CERT_P12
  APPLE_DEVELOPER_ID_CERT_PASSWORD
  APPLE_TEAM_ID
  APPLE_PROVISIONING_PROFILE
  APPLE_NOTARY_KEY
  APPLE_NOTARY_KEY_ID
  APPLE_NOTARY_ISSUER_ID
  SPARKLE_PRIVATE_KEY
)

missing=()
for name in "${required[@]}"; do
  value="${!name:-}"
  if [ -z "${value//[[:space:]]/}" ]; then
    missing+=("$name")
  fi
done

output="${GITHUB_OUTPUT:-/dev/stdout}"
if [ "${#missing[@]}" -eq 0 ]; then
  echo "configured=true" >> "$output"
else
  echo "::notice::Skipping the SwiftUI macOS release: not configured yet (missing secrets: ${missing[*]})."
  echo "configured=false" >> "$output"
fi
