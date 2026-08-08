#!/usr/bin/env bash
set -euo pipefail

# Build the Probe-only session leader that establishes the direct PRoot bootstrap group
# without changing its PID. This artifact is never included in a production
# runtime pack or integrated application.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SOURCE="$ROOT/scripts/runtime/experiments/tty_session_tracee_foreground_launcher.c"
OUTPUT="${1:-$ROOT/alpine-runtime-probe/src/main/jniLibs/arm64-v8a/libtty_session_tracee_foreground_launcher.so}"
[[ "$OUTPUT" = /* ]] || OUTPUT="$ROOT/$OUTPUT"
EXPECTED="$ROOT/alpine-runtime-probe/src/main/jniLibs/arm64-v8a/libtty_session_tracee_foreground_launcher.so"
[[ "$OUTPUT" == "$EXPECTED" ]] || {
  echo "output must be the Probe-only asset: $EXPECTED" >&2
  exit 2
}

ANDROID_API="${ANDROID_API:-26}"
NDK_VERSION="${ANDROID_NDK_VERSION:-28.2.13676358}"
NDK_HOME="${ANDROID_NDK_HOME:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/ndk/$NDK_VERSION}"
HOST_TAG="darwin-x86_64"
[[ "$(uname -s)" == Linux ]] && HOST_TAG="linux-x86_64"
CC="$NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG/bin/aarch64-linux-android${ANDROID_API}-clang"
STRIP="$NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG/bin/llvm-strip"
READELF="$NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG/bin/llvm-readelf"

[[ -f "$SOURCE" ]] || { echo "missing source: $SOURCE" >&2; exit 2; }
for tool in "$CC" "$STRIP" "$READELF"; do
  [[ -x "$tool" ]] || { echo "missing tool: $tool" >&2; exit 2; }
done

mkdir -p "$(dirname "$OUTPUT")"
TMP="$(mktemp "${TMPDIR:-/tmp}/tty-session-tracee-foreground-launcher.XXXXXX")"
trap 'rm -f "$TMP"' EXIT
"$CC" "$SOURCE" -o "$TMP" \
  -fPIE -pie -O2 -Wall -Wextra -Werror \
  -Wl,-z,noexecstack,-z,max-page-size=16384,-z,common-page-size=16384
"$STRIP" "$TMP"
ELF_HEADER="$("$READELF" -h "$TMP")"
grep -q 'Class:.*ELF64' <<<"$ELF_HEADER"
grep -q 'Machine:.*AArch64' <<<"$ELF_HEADER"
install -m 0755 "$TMP" "$OUTPUT"
SHA256="$(shasum -a 256 "$OUTPUT" | awk '{print $1}')"
SIZE="$(stat -f %z "$OUTPUT" 2>/dev/null || stat -c %s "$OUTPUT")"
printf 'Built Probe-only tty session relay launcher: %s\nsha256=%s\nsize_bytes=%s\n' "$OUTPUT" "$SHA256" "$SIZE"
