#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
VERSION="$(sed -n 's/^VERSION_NAME=//p' "$PROJECT_DIR/gradle.properties")"

if [[ -z "$VERSION" ]]; then
  echo "VERSION_NAME is missing from gradle.properties" >&2
  exit 1
fi

cd "$PROJECT_DIR"
PYTHONPYCACHEPREFIX="$PROJECT_DIR/build/python-cache" \
  python3 -m unittest discover -s tests -v
PYTHONPYCACHEPREFIX="$PROJECT_DIR/build/python-cache" \
  python3 -m compileall -q alpine_llm
python3 -B -m alpine_llm.cli run --help >/dev/null

./gradlew \
  :android:testDebugUnitTest \
  :android:lintDebug \
  :android:assembleDebugAndroidTest \
  :android:assembleRelease \
  :android:publishReleasePublicationToProjectRepository \
  :sample:assembleDebug \
  :sample:lintDebug \
  --no-daemon

REPOSITORY_DIR="$PROJECT_DIR/android/build/repo/dev/alpine/llm/alpine-llm-android/$VERSION"
BUNDLE_DIR="$PROJECT_DIR/dist/alpine-llm-android-$VERSION"
mkdir -p "$BUNDLE_DIR"

cp "$PROJECT_DIR/android/build/outputs/aar/android-release.aar" \
  "$BUNDLE_DIR/alpine-llm-android-$VERSION.aar"
cp "$REPOSITORY_DIR/alpine-llm-android-$VERSION-sources.jar" "$BUNDLE_DIR/"
cp "$REPOSITORY_DIR/alpine-llm-android-$VERSION.pom" "$BUNDLE_DIR/"
cp "$REPOSITORY_DIR/alpine-llm-android-$VERSION.module" "$BUNDLE_DIR/"

(
  cd "$BUNDLE_DIR"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum \
      "alpine-llm-android-$VERSION.aar" \
      "alpine-llm-android-$VERSION-sources.jar" \
      "alpine-llm-android-$VERSION.pom" \
      "alpine-llm-android-$VERSION.module" > SHA256SUMS
  else
    shasum -a 256 \
      "alpine-llm-android-$VERSION.aar" \
      "alpine-llm-android-$VERSION-sources.jar" \
      "alpine-llm-android-$VERSION.pom" \
      "alpine-llm-android-$VERSION.module" > SHA256SUMS
  fi
)

echo "Release bundle created: $BUNDLE_DIR"
