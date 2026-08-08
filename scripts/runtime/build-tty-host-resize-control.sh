#!/usr/bin/env bash
set -euo pipefail

# Builds an app-private Probe executable which isolates the Android host PTY
# resize path from PRoot. It is never packaged by a production runtime pack.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SOURCE="$ROOT/scripts/runtime/experiments/tty_host_resize_control.c"
OUTPUT="${1:-$ROOT/alpine-runtime-probe/src/main/jniLibs/arm64-v8a/libtty_host_resize_control.so}"
[[ "$OUTPUT" = /* ]] || OUTPUT="$ROOT/$OUTPUT"
EXPECTED="$ROOT/alpine-runtime-probe/src/main/jniLibs/arm64-v8a/libtty_host_resize_control.so"
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
TMP="$(mktemp "${TMPDIR:-/tmp}/tty-host-resize-control.XXXXXX")"
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
printf 'Built Probe-only host PTY resize control: %s\nsha256=%s\nsize_bytes=%s\n' "$OUTPUT" "$SHA256" "$SIZE"
