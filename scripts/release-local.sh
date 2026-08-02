#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
VERSION="$(sed -n 's/^VERSION_NAME=//p' "$PROJECT_DIR/gradle.properties")"
PYTHON_BIN="${PYTHON_BIN:-python3}"

# The published-consumer fixture is an intentionally isolated Gradle build and
# therefore cannot inherit the root project's local.properties. Export the
# root SDK path for that build without copying machine-local configuration into
# the fixture or release bundle.
if [[ -z "${ANDROID_HOME:-}" && -f "$PROJECT_DIR/local.properties" ]]; then
  SDK_DIR="$(sed -n 's/^sdk\.dir=//p' "$PROJECT_DIR/local.properties" | tail -n 1)"
  if [[ -n "$SDK_DIR" && -d "$SDK_DIR" ]]; then
    export ANDROID_HOME="$SDK_DIR"
  fi
fi

if [[ -z "$VERSION" ]]; then
  echo "VERSION_NAME is missing from gradle.properties" >&2
  exit 1
fi

if ! command -v "$PYTHON_BIN" >/dev/null 2>&1; then
  echo "Python executable not found: $PYTHON_BIN" >&2
  exit 1
fi

cd "$PROJECT_DIR"
PYTHONPYCACHEPREFIX="$PROJECT_DIR/build/python-cache" \
  "$PYTHON_BIN" -m unittest discover -s tests -v
PYTHONPYCACHEPREFIX="$PROJECT_DIR/build/python-cache" \
  "$PYTHON_BIN" -m compileall -q alpine_llm
"$PYTHON_BIN" -B -m alpine_llm.cli run --help >/dev/null

"$PYTHON_BIN" scripts/generate-alpine-package-inventory.py \
  --rootfs alpine-runtime-pack-bundled/src/main/assets/alpine-minirootfs.tar.gz.asset \
  --abi arm64-v8a \
  --expected-sha256 ead8a4b37867bd19e7417dd078748e2312c0aea364403d96758d63ea8ff261ea \
  --output runtime/alpine-package-inventory-arm64-v8a.json
"$PYTHON_BIN" scripts/generate-alpine-package-inventory.py \
  --rootfs alpine-runtime-pack-x86_64/src/main/assets/alpine-minirootfs-x86_64.tar.gz.asset \
  --abi x86_64 \
  --expected-sha256 1a694899e406ce55d32334c47ac0b2efb6c06d7e878102d1840892ad44cd5239 \
  --output runtime/alpine-package-inventory-x86_64.json

OSS_SOURCE_INPUT_COUNT=0
for variable in ALPINE_PROOT_SOURCE ALPINE_TALLOC_SOURCE_ARCHIVE ALPINE_TALLOC_BUILD_SOURCE; do
  if [[ -n "${!variable:-}" ]]; then
    OSS_SOURCE_INPUT_COUNT=$((OSS_SOURCE_INPUT_COUNT + 1))
  fi
done
if [[ "$OSS_SOURCE_INPUT_COUNT" -ne 0 && "$OSS_SOURCE_INPUT_COUNT" -ne 3 ]]; then
  echo "Set all of ALPINE_PROOT_SOURCE, ALPINE_TALLOC_SOURCE_ARCHIVE, and ALPINE_TALLOC_BUILD_SOURCE" >&2
  exit 2
fi
if [[ "$OSS_SOURCE_INPUT_COUNT" -eq 3 ]]; then
  "$PYTHON_BIN" scripts/build-oss-source-bundle.py build \
    --proot-source "$ALPINE_PROOT_SOURCE" \
    --talloc-archive "$ALPINE_TALLOC_SOURCE_ARCHIVE" \
    --talloc-build-source "$ALPINE_TALLOC_BUILD_SOURCE" \
    --output "build/compliance/alpine-oss-native-sources-$VERSION.tar.gz"
fi
"$PYTHON_BIN" scripts/verify-license-compliance.py \
  --report build/reports/license-compliance.json

GRADLE_TASKS=(
  :android:testDebugUnitTest
  :android:lintDebug
  :android:assembleDebugAndroidTest
  :android:assembleRelease
  :alpine-runtime-api:check
  :alpine-runtime-android:testDebugUnitTest
  :alpine-runtime-android:assembleRelease
  :alpine-runtime-android:lintDebug
  :alpine-runtime-background-android:testDebugUnitTest
  :alpine-runtime-background-android:assembleRelease
  :alpine-runtime-background-android:lintDebug
  :alpine-runtime-artifact-play:testDebugUnitTest
  :alpine-runtime-artifact-play:assembleRelease
  :alpine-runtime-artifact-play:lintDebug
  :alpine-runtime-pack-bundled:testDebugUnitTest
  :alpine-runtime-pack-bundled:assembleRelease
  :alpine-runtime-pack-bundled:lintDebug
  :alpine-runtime-pack-bundled:verifyBundledRuntimeArtifacts
  :alpine-runtime-pack-x86_64:testDebugUnitTest
  :alpine-runtime-pack-x86_64:assembleRelease
  :alpine-runtime-pack-x86_64:lintDebug
  :alpine-runtime-pack-x86_64:verifyX8664RuntimeArtifacts
  :alpine-llm-bridge:testDebugUnitTest
  :alpine-llm-bridge:assembleRelease
  :alpine-llm-bridge:assembleDebugAndroidTest
  :alpine-llm-bridge:lintDebug
  :alpine-llm-gateway-pack-bundled:testDebugUnitTest
  :alpine-llm-gateway-pack-bundled:assembleRelease
  :alpine-llm-gateway-pack-bundled:lintDebug
  :alpine-llm-gateway-pack-bundled:verifyBundledPythonGatewayArtifact
  :alpine-runtime-ui-compose:testDebugUnitTest
  :alpine-runtime-ui-compose:assembleRelease
  :alpine-runtime-ui-compose:assembleDebugAndroidTest
  :alpine-runtime-ui-compose:lintDebug
  :alpine-runtime-host:check
  :alpine-runtime-testkit:check
  :alpine-chat-routing:check
  :alpine-chat-backend-direct:testDebugUnitTest
  :alpine-chat-backend-direct:assembleRelease
  :alpine-chat-backend-direct:lintDebug
  :alpine-chat-backend-alpine:testDebugUnitTest
  :alpine-chat-backend-alpine:assembleRelease
  :alpine-chat-backend-alpine:lintDebug
  :alpine-workspace-api:check
  :alpine-workspace-android:testDebugUnitTest
  :alpine-workspace-android:assembleRelease
  :alpine-workspace-android:lintDebug
  :sample:assembleDebug
  :sample:lintDebug
  :demo-chatbot:testDebugUnitTest
  :demo-chatbot:assembleDebug
  :demo-chatbot:assembleDebugAndroidTest
  :demo-chatbot:lintDebug
  :demo-chatbot:verifyNoAlpineRuntimePayload
  :alpine-runtime-probe:assembleDebug
  :alpine-runtime-probe:lintDebug
  :alpine-integration-sample:assembleDebug
  :alpine-integration-sample:lintDebug
  :integrated-app:assembleDebug
  :integrated-app:lintDebug
  :alpine-llm-bridge-probe:assembleDebug
  :alpine-llm-bridge-probe:lintDebug
  publishPhase7Artifacts
)

./gradlew "${GRADLE_TASKS[@]}" \
  -Pkotlin.compiler.execution.strategy=in-process \
  --no-daemon
"$PYTHON_BIN" scripts/verify-sdk-publication.py

./gradlew -p integration-fixtures/published-consumer \
  assembleRelease \
  lintRelease \
  -Pkotlin.compiler.execution.strategy=in-process \
  --no-daemon
"$PYTHON_BIN" scripts/verify-published-consumer.py
"$PYTHON_BIN" scripts/verify-provider-e2e-report.py \
  integration-fixtures/provider-e2e/report.template.json
"$PYTHON_BIN" scripts/verify-play-e2e-report.py \
  integration-fixtures/play-e2e/report.template.json
"$PYTHON_BIN" scripts/verify-samsung-lifecycle-report.py \
  integration-fixtures/samsung-lifecycle/report.template.json
"$PYTHON_BIN" scripts/verify-release-readiness.py \
  distribution/release-readiness.json --check-evidence
scripts/audit-gradle9-readiness.sh
scripts/runtime/run-x86_64-emulator-gate.sh --allow-skip
"$PYTHON_BIN" scripts/package-sdk-release.py

echo "Internal SDK release bundle created: $PROJECT_DIR/dist/alpine-sdk-$VERSION"
echo "Public distribution remains gated by dist/alpine-sdk-$VERSION/manifest.json."
