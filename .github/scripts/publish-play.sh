#!/usr/bin/env bash
# Publishes one AAB to one or more Google Play tracks in a single, atomic Play Developer API edit.
#
# Play refuses a second upload of an already-used versionCode, so publishing the same build to
# several tracks has to upload it once and then reference that versionCode from every track's
# release, all inside the same edit. The edit is committed only after every step succeeded; on
# any failure it is deleted instead, so a track is never left published without the others.
#
# Shared by release.yml's publish-play job and publish-play.yml. See docs/build.md's
# "Publishing to Google Play".
#
# Inputs (environment variables):
#   SERVICE_ACCOUNT_JSON  Full contents of the service account's JSON key.
#   PACKAGE_NAME          Application id, e.g. works.merc.keryx.
#   AAB_PATH              Path to the .aab to upload.
#   TRACKS                Comma-separated Play Developer API track ids, e.g. "internal,alpha".
#   WHATSNEW_DIR          Directory of whatsnew-<locale> files (release notes, one per locale).
set -euo pipefail

API="https://androidpublisher.googleapis.com/androidpublisher/v3/applications"
UPLOAD_API="https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications"
SCOPE="https://www.googleapis.com/auth/androidpublisher"

fail() {
  echo "::error::$*" >&2
  exit 1
}

: "${SERVICE_ACCOUNT_JSON:?SERVICE_ACCOUNT_JSON is not set}"
: "${PACKAGE_NAME:?PACKAGE_NAME is not set}"
: "${AAB_PATH:?AAB_PATH is not set}"
: "${TRACKS:?TRACKS is not set}"
: "${WHATSNEW_DIR:?WHATSNEW_DIR is not set}"

# Validate every track id before touching the network: they are interpolated into URL paths,
# and publish-play.yml takes them from free-form workflow_dispatch input.
IFS=',' read -r -a tracks <<< "$TRACKS"
[ "${#tracks[@]}" -gt 0 ] || fail "TRACKS is empty."
for track in "${tracks[@]}"; do
  [[ "$track" =~ ^[a-z0-9_-]+$ ]] || fail "Invalid Play track id: '$track'."
done
[ -f "$AAB_PATH" ] || fail "AAB not found: $AAB_PATH"

# Release notes, built once and reused for every track.
release_notes='[]'
for file in "$WHATSNEW_DIR"/whatsnew-*; do
  [ -f "$file" ] || continue
  locale="${file##*/whatsnew-}"
  release_notes=$(jq -c --arg language "$locale" --rawfile text "$file" \
    '. + [{language: $language, text: $text}]' <<< "$release_notes")
done

work_dir=$(mktemp -d "${RUNNER_TEMP:-/tmp}/publish-play.XXXXXX")
edit_id=""
access_token=""

cleanup() {
  # Discard an edit that was opened but never committed.
  if [ -n "$edit_id" ] && [ -n "$access_token" ]; then
    echo "Deleting uncommitted edit $edit_id." >&2
    curl --silent --show-error --output /dev/null --request DELETE \
      --header "Authorization: Bearer $access_token" \
      "$API/$PACKAGE_NAME/edits/$edit_id" || true
  fi
  rm -rf "$work_dir"
}
trap cleanup EXIT

base64url() {
  openssl base64 -A | tr '+/' '-_' | tr -d '='
}

# api METHOD URL [curl args...] — prints the response body; on an HTTP error, reports the body
# on stderr and fails. Never echoes the Authorization header.
api() {
  local method="$1" url="$2" response
  shift 2
  if ! response=$(curl --silent --show-error --fail-with-body --request "$method" \
    --header "Authorization: Bearer $access_token" "$@" "$url"); then
    echo "::error::Play API $method $url failed: $response" >&2
    return 1
  fi
  printf '%s' "$response"
}

# --- Access token (OAuth 2.0 JWT bearer grant for a service account) ---
client_email=$(jq -er .client_email <<< "$SERVICE_ACCOUNT_JSON") || fail "client_email missing from service account JSON."
token_uri=$(jq -er '.token_uri // "https://oauth2.googleapis.com/token"' <<< "$SERVICE_ACCOUNT_JSON")
key_file="$work_dir/key.pem"
(umask 077 && jq -er .private_key <<< "$SERVICE_ACCOUNT_JSON" > "$key_file") || fail "private_key missing from service account JSON."

now=$(date +%s)
header=$(printf '%s' '{"alg":"RS256","typ":"JWT"}' | base64url)
claims=$(jq -cn --arg iss "$client_email" --arg scope "$SCOPE" --arg aud "$token_uri" \
  --argjson iat "$now" --argjson exp "$((now + 600))" \
  '{iss: $iss, scope: $scope, aud: $aud, iat: $iat, exp: $exp}' | base64url)
signature=$(printf '%s' "$header.$claims" | openssl dgst -sha256 -sign "$key_file" -binary | base64url)
rm -f "$key_file"

token_response=$(curl --silent --show-error --fail-with-body --request POST \
  --data-urlencode "grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer" \
  --data-urlencode "assertion=$header.$claims.$signature" \
  "$token_uri") || fail "Failed to obtain an access token: $token_response"
access_token=$(jq -er .access_token <<< "$token_response") || fail "Token response carried no access_token."
echo "::add-mask::$access_token"

# --- Edit: upload once, assign to every track, commit ---
edit_id=$(api POST "$API/$PACKAGE_NAME/edits" | jq -er .id)
echo "Opened edit $edit_id."

version_code=$(api POST "$UPLOAD_API/$PACKAGE_NAME/edits/$edit_id/bundles?uploadType=media" \
  --header "Content-Type: application/octet-stream" \
  --data-binary "@$AAB_PATH" | jq -er .versionCode)
echo "Uploaded bundle as versionCode $version_code."

for track in "${tracks[@]}"; do
  body=$(jq -cn --arg track "$track" --arg versionCode "$version_code" --argjson notes "$release_notes" \
    '{track: $track, releases: [{versionCodes: [$versionCode], status: "completed"}
      + (if ($notes | length) > 0 then {releaseNotes: $notes} else {} end)]}')
  api PUT "$API/$PACKAGE_NAME/edits/$edit_id/tracks/$track" \
    --header "Content-Type: application/json" --data "$body" > /dev/null
  echo "Assigned versionCode $version_code to track '$track'."
done

api POST "$API/$PACKAGE_NAME/edits/$edit_id:commit" > /dev/null
edit_id=""
echo "Committed: versionCode $version_code published to ${tracks[*]}."
