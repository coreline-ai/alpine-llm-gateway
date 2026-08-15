#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
OUTPUT_DIR="$PROJECT_DIR/dist/current-state-public-release"
ALLOW_UNSIGNED=false

usage() {
  cat <<'EOF'
Usage: scripts/build-current-state-public-release.sh [--unsigned-candidate]

Builds the project-owner-authorized current-state Codex ON release APK/AAB. Public upload requires
a durable release signing configuration. By default, the builder requires all four secure local
environment variables listed below and produces signed artifacts. --unsigned-candidate produces a
verified, non-uploadable candidate; it never substitutes the Android debug key.

  ALPINE_RELEASE_KEYSTORE
  ALPINE_RELEASE_KEY_ALIAS
  ALPINE_RELEASE_STORE_PASSWORD
  ALPINE_RELEASE_KEY_PASSWORD
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --unsigned-candidate)
      ALLOW_UNSIGNED=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument" >&2
      usage >&2
      exit 2
      ;;
  esac
done

PYTHON_BIN="${PYTHON_BIN:-python3.11}"
if ! command -v "$PYTHON_BIN" >/dev/null 2>&1; then
  echo "PYTHON_NOT_FOUND" >&2
  exit 2
fi
SIGNING_NAMES=(
  ALPINE_RELEASE_KEYSTORE
  ALPINE_RELEASE_KEY_ALIAS
  ALPINE_RELEASE_STORE_PASSWORD
  ALPINE_RELEASE_KEY_PASSWORD
)
SIGNING_CONFIGURED=true
for name in "${SIGNING_NAMES[@]}"; do
  if [[ -z "${!name:-}" ]]; then
    SIGNING_CONFIGURED=false
  fi
done
if [[ "$ALLOW_UNSIGNED" == true && "$SIGNING_CONFIGURED" == true ]]; then
  echo "UNSIGNED_CANDIDATE_REFUSES_CONFIGURED_RELEASE_KEY" >&2
  exit 2
fi
if [[ "$ALLOW_UNSIGNED" != true && "$SIGNING_CONFIGURED" != true ]]; then
  echo "RELEASE_SIGNING_NOT_CONFIGURED_SET_ALL_ALPINE_RELEASE_VALUES" >&2
  exit 2
fi
if [[ "$SIGNING_CONFIGURED" == true && ! -f "$ALPINE_RELEASE_KEYSTORE" ]]; then
  echo "RELEASE_KEYSTORE_NOT_FOUND" >&2
  exit 2
fi

cd "$PROJECT_DIR"
if [[ "$SIGNING_CONFIGURED" == true && -n "$(git status --porcelain --untracked-files=normal)" ]]; then
  echo "SIGNED_RELEASE_REQUIRES_CLEAN_WORKTREE" >&2
  exit 2
fi
SOURCE_COMMIT="$(git rev-parse HEAD)"
"$PYTHON_BIN" scripts/verify-current-state-release-decision.py --check-evidence >/dev/null
"$PYTHON_BIN" scripts/verify-release-readiness.py \
  distribution/release-readiness.json \
  --check-evidence \
  --allow-current-state-release \
  --require-distribution-authorized >/dev/null

export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
export PATH="$JAVA_HOME/bin:$PATH"
PYTHON_BIN="$PYTHON_BIN" \
./gradlew \
  :integrated-app:assembleRelease \
  :integrated-app:bundleRelease \
  -PcodexAppServerEnabled=true \
  -PcodexCurrentStatePublicRelease=true \
  --rerun-tasks \
  --max-workers=2 \
  --console=plain

if [[ "$SIGNING_CONFIGURED" == true ]]; then
  APK="integrated-app/build/outputs/apk/release/integrated-app-release.apk"
else
  APK="integrated-app/build/outputs/apk/release/integrated-app-release-unsigned.apk"
fi
AAB="integrated-app/build/outputs/bundle/release/integrated-app-release.aab"
BINARY=".codex-artifacts/0.147.0/linux-arm64/codex"
for artifact in "$APK" "$AAB"; do
  if [[ ! -f "$artifact" ]]; then
    echo "CURRENT_STATE_RELEASE_ARTIFACT_MISSING" >&2
    exit 3
  fi
  "$PYTHON_BIN" scripts/verify-codex-appserver.py \
    --binary "$BINARY" --archive "$artifact" --expect-binary >/dev/null
done
"$PYTHON_BIN" scripts/verify-integrated-oauth-release.py \
  --require-default-roots --allow-approved-openminis-debug >/dev/null

mkdir -p "$OUTPUT_DIR"
rm -f "$OUTPUT_DIR"/*.apk "$OUTPUT_DIR"/*.aab "$OUTPUT_DIR/SHA256SUMS" "$OUTPUT_DIR/manifest.json"

if [[ "$SIGNING_CONFIGURED" == true ]]; then
  APKSIGNER="$(find "${ANDROID_HOME:-$HOME/Library/Android/sdk}/build-tools" -type f -name apksigner | sort -V | tail -1)"
  if [[ -z "$APKSIGNER" ]] || ! "$APKSIGNER" verify "$APK" >/dev/null 2>&1; then
    echo "SIGNED_APK_VERIFICATION_FAILED" >&2
    exit 4
  fi
  AAB_VERIFY_OUTPUT="$(mktemp)"
  if ! "$JAVA_HOME/bin/jarsigner" -verify "$AAB" >"$AAB_VERIFY_OUTPUT" 2>&1 ||
     ! grep -q "jar verified" "$AAB_VERIFY_OUTPUT" ||
     grep -q "jar is unsigned" "$AAB_VERIFY_OUTPUT"; then
    rm -f "$AAB_VERIFY_OUTPUT"
    echo "SIGNED_AAB_VERIFICATION_FAILED" >&2
    exit 4
  fi
  rm -f "$AAB_VERIFY_OUTPUT"
  APK_CERT_OUTPUT="$(mktemp)"
  AAB_CERT_OUTPUT="$(mktemp)"
  "$APKSIGNER" verify --print-certs "$APK" >"$APK_CERT_OUTPUT"
  "$JAVA_HOME/bin/keytool" -J-Duser.language=en -printcert -jarfile "$AAB" >"$AAB_CERT_OUTPUT"
  APK_CERT_SHA256="$(
    awk -F ': ' '/Signer #1 certificate SHA-256 digest/ {print $2; exit}' "$APK_CERT_OUTPUT" |
      tr '[:upper:]' '[:lower:]'
  )"
  AAB_CERT_SHA256="$(
    sed -n 's/^[[:space:]]*SHA256: //p' "$AAB_CERT_OUTPUT" |
      head -1 |
      tr -d ':' |
      tr '[:upper:]' '[:lower:]'
  )"
  rm -f "$APK_CERT_OUTPUT" "$AAB_CERT_OUTPUT"
  if [[ ! "$APK_CERT_SHA256" =~ ^[0-9a-f]{64}$ ]] ||
     [[ "$APK_CERT_SHA256" != "$AAB_CERT_SHA256" ]]; then
    echo "RELEASE_SIGNING_CERTIFICATE_MISMATCH" >&2
    exit 4
  fi
  APK_NAME="alpine-ai-workspace-current-state-signed.apk"
  AAB_NAME="alpine-ai-workspace-current-state-signed.aab"
  SIGNED=true
  ARTIFACT_UPLOAD_READY=true
  REASON="DESTINATION_REQUIRED"
  SIGNING_CERTIFICATE_JSON="\"$APK_CERT_SHA256\""
else
  APK_NAME="alpine-ai-workspace-current-state-unsigned.apk"
  AAB_NAME="alpine-ai-workspace-current-state-unsigned.aab"
  SIGNED=false
  ARTIFACT_UPLOAD_READY=false
  REASON="RELEASE_SIGNING_AND_DESTINATION_REQUIRED"
  SIGNING_CERTIFICATE_JSON=null
fi

cp "$APK" "$OUTPUT_DIR/$APK_NAME"
cp "$AAB" "$OUTPUT_DIR/$AAB_NAME"
(
  cd "$OUTPUT_DIR"
  shasum -a 256 "$APK_NAME" "$AAB_NAME" > SHA256SUMS
)

cat > "$OUTPUT_DIR/manifest.json" <<EOF
{
  "schema_version": 1,
  "decision_mode": "CURRENT_STATE_OWNER_DECISION",
  "distribution_authorized": true,
  "source_commit": "$SOURCE_COMMIT",
  "evidence_ready": false,
  "signed": $SIGNED,
  "signing_certificate_sha256": $SIGNING_CERTIFICATE_JSON,
  "artifact_upload_ready": $ARTIFACT_UPLOAD_READY,
  "destination_configured": false,
  "upload_ready": false,
  "reason": "$REASON",
  "apk": "$APK_NAME",
  "aab": "$AAB_NAME",
  "checksums": "SHA256SUMS"
}
EOF

echo "current_state_release_candidate=PASS signed=$SIGNED artifact_upload_ready=$ARTIFACT_UPLOAD_READY upload_ready=false"
echo "output=$OUTPUT_DIR"
