#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
OUTPUT_DIR="$PROJECT_DIR/dist/current-state-public-release"
TAG="${1:-v0.3.0}"
REPOSITORY="coreline-ai/alpine-llm-gateway"
NOTES="$PROJECT_DIR/distribution/releases/$TAG.md"

cd "$PROJECT_DIR"
if [[ -n "$(git status --porcelain --untracked-files=normal)" ]]; then
  echo "GITHUB_RELEASE_REQUIRES_CLEAN_WORKTREE" >&2
  exit 2
fi
if [[ ! -f "$NOTES" ]]; then
  echo "GITHUB_RELEASE_NOTES_MISSING" >&2
  exit 2
fi
if [[ "$(git rev-parse "$TAG^{commit}" 2>/dev/null || true)" != "$(git rev-parse HEAD)" ]]; then
  echo "GITHUB_RELEASE_TAG_MUST_POINT_TO_HEAD" >&2
  exit 2
fi

MANIFEST="$OUTPUT_DIR/manifest.json"
"${PYTHON_BIN:-python3.11}" - "$MANIFEST" <<'PY'
import json
import sys
from pathlib import Path

manifest = json.loads(Path(sys.argv[1]).read_text())
if manifest.get("signed") is not True or manifest.get("artifact_upload_ready") is not True:
    raise SystemExit("GITHUB_RELEASE_REQUIRES_SIGNED_UPLOAD_READY_ARTIFACT")
if manifest.get("source_commit") is None:
    raise SystemExit("GITHUB_RELEASE_SOURCE_COMMIT_MISSING")
PY

APK="$(python3.11 -c 'import json; print(json.load(open("dist/current-state-public-release/manifest.json"))["apk"])')"
AAB="$(python3.11 -c 'import json; print(json.load(open("dist/current-state-public-release/manifest.json"))["aab"])')"
for name in "$APK" "$AAB" SHA256SUMS manifest.json; do
  [[ -f "$OUTPUT_DIR/$name" ]] || { echo "GITHUB_RELEASE_ASSET_MISSING" >&2; exit 2; }
done
(cd "$OUTPUT_DIR" && shasum -a 256 -c SHA256SUMS >/dev/null)

credential="$(printf 'protocol=https\nhost=github.com\n\n' | git credential fill)"
api_token="$(printf '%s\n' "$credential" | sed -n 's/^password=//p')"
unset credential
if [[ -z "$api_token" ]]; then
  echo "GITHUB_API_CREDENTIAL_MISSING" >&2
  exit 2
fi
CURL_CONFIG="$(mktemp)"
TMP_DIR="$(mktemp -d)"
cleanup() {
  rm -f "$CURL_CONFIG"
  rm -rf "$TMP_DIR"
  unset api_token
}
trap cleanup EXIT
chmod 600 "$CURL_CONFIG"
printf 'header = "Authorization: Bearer %s"\nheader = "Accept: application/vnd.github+json"\n' \
  "$api_token" >"$CURL_CONFIG"
unset api_token

API="https://api.github.com/repos/$REPOSITORY"
http_code="$(curl --config "$CURL_CONFIG" -sS -o "$TMP_DIR/release.json" -w '%{http_code}' "$API/releases/tags/$TAG")"
if [[ "$http_code" == 404 ]]; then
  python3.11 - "$TAG" "$NOTES" "$TMP_DIR/create.json" <<'PY'
import json
import sys
from pathlib import Path

tag, notes, output = sys.argv[1:]
Path(output).write_text(json.dumps({
    "tag_name": tag,
    "name": f"Alpine AI Workspace {tag.removeprefix('v')}",
    "body": Path(notes).read_text(),
    "draft": False,
    "prerelease": False,
    "generate_release_notes": False,
}))
PY
  http_code="$(curl --config "$CURL_CONFIG" -sS \
    -H 'Content-Type: application/json' \
    --data-binary "@$TMP_DIR/create.json" \
    -o "$TMP_DIR/release.json" -w '%{http_code}' "$API/releases")"
  [[ "$http_code" == 201 ]] || { echo "GITHUB_RELEASE_CREATE_FAILED_HTTP_$http_code" >&2; exit 3; }
elif [[ "$http_code" != 200 ]]; then
  echo "GITHUB_RELEASE_LOOKUP_FAILED_HTTP_$http_code" >&2
  exit 3
fi

RELEASE_ID="$(python3.11 -c 'import json,sys; print(json.load(open(sys.argv[1]))["id"])' "$TMP_DIR/release.json")"
RELEASE_URL="$(python3.11 -c 'import json,sys; print(json.load(open(sys.argv[1]))["html_url"])' "$TMP_DIR/release.json")"
EXISTING_ASSETS="$(python3.11 -c 'import json,sys; print("\n".join(a["name"] for a in json.load(open(sys.argv[1])).get("assets", [])))' "$TMP_DIR/release.json")"

publish_asset() {
  local path="$1"
  local name
  local encoded
  local code
  name="$(basename "$path")"
  if grep -Fxq "$name" <<<"$EXISTING_ASSETS"; then
    echo "github_asset=$name status=EXISTS"
    return
  fi
  encoded="$(python3.11 -c 'import sys,urllib.parse; print(urllib.parse.quote(sys.argv[1]))' "$name")"
  code="$(curl --config "$CURL_CONFIG" -sS \
    -H 'Content-Type: application/octet-stream' \
    --data-binary "@$path" \
    -o "$TMP_DIR/upload.json" -w '%{http_code}' \
    "https://uploads.github.com/repos/$REPOSITORY/releases/$RELEASE_ID/assets?name=$encoded")"
  [[ "$code" == 201 ]] || { echo "GITHUB_ASSET_UPLOAD_FAILED_${name}_HTTP_$code" >&2; exit 3; }
  echo "github_asset=$name status=UPLOADED"
}

publish_asset "$OUTPUT_DIR/$APK"
publish_asset "$OUTPUT_DIR/$AAB"
publish_asset "$OUTPUT_DIR/SHA256SUMS"
publish_asset "$OUTPUT_DIR/manifest.json"

python3.11 - "$OUTPUT_DIR/github-release-publication.json" "$TAG" "$RELEASE_URL" <<'PY'
import datetime
import json
import subprocess
import sys
from pathlib import Path

output, tag, url = sys.argv[1:]
Path(output).write_text(json.dumps({
    "schema_version": 1,
    "destination": "GITHUB_RELEASE",
    "repository_visibility": "PRIVATE",
    "distribution_scope": "REPOSITORY_ACCESS",
    "published": True,
    "tag": tag,
    "source_commit": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
    "published_at": datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
    "release_url": url,
}, indent=2) + "\n")
PY
publish_asset "$OUTPUT_DIR/github-release-publication.json"

echo "github_release=PASS"
echo "release_url=$RELEASE_URL"
