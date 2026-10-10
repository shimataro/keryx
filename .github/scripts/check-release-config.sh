#!/usr/bin/env bash
# Fails release.yml's package-macos job, before anything is built, unless every secret it needs to
# sign, notarize and publish the SwiftUI macOS app is set.
#
# The macOS release consists of this app alone, and deploy-pages waits for the job, so a missing
# secret must stop the release visibly rather than let it go out without a macOS package — and it
# must never fall back to an unsigned build. An unset secret reaches the job as an empty string, so
# empty (or whitespace-only) counts as missing. Only the names of the missing secrets are printed,
# never a value.
#
# Used by release.yml's package-macos job. See docs/build.md's "Release (CD)".
#
# Inputs (environment variables, one per required secret; see `required` below):
#   Each may be unset or empty.
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

if [ "${#missing[@]}" -gt 0 ]; then
  echo "::error::The macOS release cannot be signed and published: missing secrets: ${missing[*]}."
  exit 1
fi
echo "All secrets for the macOS release are set."
