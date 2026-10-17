#!/usr/bin/env bash
# Fails a publishing job, before anything is built, unless every secret it names is set.
#
# release.yml's package-macos job uses it because the macOS release consists of the SwiftUI app
# alone, and deploy-pages waits for the job, so a missing secret must stop the release visibly
# rather than let it go out without a macOS package — and it must never fall back to an unsigned
# build. publish-app-store.yml uses it so a missing signing or OAuth secret fails in seconds rather
# than after the build. An unset secret reaches the job as an empty string, so empty (or
# whitespace-only) counts as missing. Only the names of the missing secrets are printed, never a
# value.
#
# See docs/build.md's "Release (CD)".
#
# Usage: check-release-config.sh <what is being published> <secret name>...
#   e.g. check-release-config.sh "The macOS release" APPLE_TEAM_ID SPARKLE_PRIVATE_KEY
#
# Inputs (environment variables): one per secret named on the command line, always mapped in the
# step's `env:` even when the secret is not set — an unset secret then arrives as an empty string,
# which counts as missing. A name with no variable at all is a mistake in the workflow (the two
# lists drifted apart), and is reported as that instead.
set -euo pipefail

[ $# -ge 2 ] || { echo "usage: $0 <what is being published> <secret name>..." >&2; exit 2; }
label="$1"
shift

missing=()
for name in "$@"; do
  # The name is used for an indirect expansion, so accept only an identifier.
  [[ "$name" =~ ^[A-Z_][A-Z0-9_]*$ ]] || { echo "::error::Invalid secret name: '$name'." >&2; exit 2; }
  [ -n "${!name+x}" ] \
    || { echo "::error::$label: $name is not passed to this step; map it in the step's env." >&2; exit 2; }
  value="${!name:-}"
  if [ -z "${value//[[:space:]]/}" ]; then
    missing+=("$name")
  fi
done

if [ "${#missing[@]}" -gt 0 ]; then
  echo "::error::$label cannot be signed and published: missing secrets: ${missing[*]}."
  exit 1
fi
echo "$label: all secrets are set."
