#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ABI="${1:-}"
PROOT_SOURCE="${2:-}"
TALLOC_SOURCE="${3:-}"
OUTPUT="${4:-$ROOT/build/runtime-toolchain/$ABI}"
[[ "$OUTPUT" = /* ]] || OUTPUT="$ROOT/$OUTPUT"
case "$OUTPUT/" in
  "$ROOT/build/"*) ;;
  *) echo "output must stay under $ROOT/build: $OUTPUT" >&2; exit 2 ;;
esac
ANDROID_API="${ANDROID_API:-26}"

case "$ABI" in
  arm64-v8a)
    TRIPLE="aarch64-linux-android"; MACHINE_PATTERN="aarch64"; DEFAULT_NDK_VERSION="28.2.13676358" ;;
  x86_64)
    TRIPLE="x86_64-linux-android"; MACHINE_PATTERN="x86-64"; DEFAULT_NDK_VERSION="27.0.12077973" ;;
  *) echo "usage: $0 <arm64-v8a|x86_64> <proot-source> <talloc-source> [output]" >&2; exit 2 ;;
esac
NDK_HOME="${ANDROID_NDK_HOME:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/ndk/$DEFAULT_NDK_VERSION}"
[[ -f "$PROOT_SOURCE/src/GNUmakefile" ]] || { echo "invalid PRoot source: $PROOT_SOURCE" >&2; exit 2; }
[[ -f "$TALLOC_SOURCE/talloc.c" && -f "$TALLOC_SOURCE/talloc.h" ]] || {
  echo "invalid talloc source: $TALLOC_SOURCE" >&2; exit 2;
}
[[ -d "$NDK_HOME" ]] || { echo "Android NDK missing: $NDK_HOME" >&2; exit 2; }

HOST_TAG="darwin-x86_64"
[[ "$(uname -s)" == Linux ]] && HOST_TAG="linux-x86_64"
TOOLCHAIN="$NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG/bin"
CC="$TOOLCHAIN/${TRIPLE}${ANDROID_API}-clang"
AR="$TOOLCHAIN/llvm-ar"
RANLIB="$TOOLCHAIN/llvm-ranlib"
STRIP="$TOOLCHAIN/llvm-strip"
OBJCOPY="$TOOLCHAIN/llvm-objcopy"
OBJDUMP="$TOOLCHAIN/llvm-objdump"
READELF="$TOOLCHAIN/llvm-readelf"
for tool in "$CC" "$AR" "$RANLIB" "$STRIP" "$OBJCOPY" "$OBJDUMP" "$READELF"; do
  [[ -x "$tool" ]] || { echo "missing tool: $tool" >&2; exit 2; }
done

WORK="$OUTPUT/work"
rm -rf "$WORK"
mkdir -p "$WORK/proot" "$WORK/talloc" "$OUTPUT/artifacts"
cp -R "$PROOT_SOURCE/src" "$WORK/proot/"
cp "$TALLOC_SOURCE/talloc.c" "$TALLOC_SOURCE/talloc.h" "$TALLOC_SOURCE/replace.h" "$WORK/talloc/"

WINSIZE_PATCH="$ROOT/scripts/runtime/patches/proot-android-winsize.patch"
[[ -f "$WINSIZE_PATCH" ]] || { echo "missing PRoot winsize patch: $WINSIZE_PATCH" >&2; exit 2; }
patch -d "$WORK/proot" -p1 --forward --batch < "$WINSIZE_PATCH" >/dev/null

# PRoot's freestanding loader has its own linker flags and therefore does not
# inherit the main binary's LDFLAGS. Patch only the disposable source copy so
# both output ELFs satisfy Android's 16 KiB page-size compatibility gate.
python3 - "$WORK/proot/src/GNUmakefile" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
text = path.read_text()
needle = "LOADER_LDFLAGS$1 += -static -nostdlib "
replacement = (
    "LOADER_LDFLAGS$1 += -static -nostdlib "
    "-Wl,-z,max-page-size=16384,-z,common-page-size=16384 "
)
if needle not in text:
    raise SystemExit("unsupported PRoot GNUmakefile: loader flags not found")
path.write_text(text.replace(needle, replacement, 1))
PY

"$CC" -c "$WORK/talloc/talloc.c" -o "$WORK/talloc.o" \
  -I"$WORK/talloc" -fPIC -O2 -Wall -std=gnu99 \
  -DHAVE_STDARG_H=1 -DHAVE_VA_COPY=1 -DHAVE_UNISTD_H=1 -DHAVE_INTPTR_T=1
"$AR" rcs "$WORK/libtalloc.a" "$WORK/talloc.o"
"$RANLIB" "$WORK/libtalloc.a"

CPPFLAGS="-D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -I. -DARG_MAX=131072 -I$WORK/talloc"
CFLAGS="-O2 -Wall -Wextra -fPIE"
LDFLAGS="-Wl,-z,noexecstack -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 -pie -L$WORK -ltalloc"
JOBS="$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)"
(
  cd "$WORK/proot/src"
  make clean >/dev/null 2>&1 || true
  make CC="$CC" STRIP="$STRIP" OBJCOPY="$OBJCOPY" OBJDUMP="$OBJDUMP" \
    CPPFLAGS="$CPPFLAGS" CFLAGS="$CFLAGS" LDFLAGS="$LDFLAGS" -j"$JOBS"
)

PROOT_OUT="$OUTPUT/artifacts/libproot.so"
LOADER_OUT="$OUTPUT/artifacts/libproot-loader.so"
install -m 0755 "$WORK/proot/src/proot" "$PROOT_OUT"
install -m 0755 "$WORK/proot/src/loader/loader" "$LOADER_OUT"
"$STRIP" "$PROOT_OUT" "$LOADER_OUT"
"$OBJDUMP" -a "$PROOT_OUT" | grep -q "$MACHINE_PATTERN"
"$OBJDUMP" -a "$LOADER_OUT" | grep -q "$MACHINE_PATTERN"

PROOT_REVISION="$(git -C "$PROOT_SOURCE" rev-parse HEAD 2>/dev/null || echo UNKNOWN)"
TALLOC_VERSION="${TALLOC_VERSION:-2.4.2}"
sha() { shasum -a 256 "$1" | awk '{print $1}'; }
size() { stat -f %z "$1" 2>/dev/null || stat -c %s "$1"; }
cat > "$OUTPUT/toolchain-lock.json" <<EOF
{
  "schema_version": 1,
  "abi": "$ABI",
  "android_api": $ANDROID_API,
  "ndk_version": "$(basename "$NDK_HOME")",
  "proot_revision": "$PROOT_REVISION",
  "patches": [
    {"path": "scripts/runtime/patches/proot-android-winsize.patch", "sha256": "$(sha "$WINSIZE_PATCH")"}
  ],
  "talloc_version": "$TALLOC_VERSION",
  "artifacts": {
    "libproot.so": {"sha256": "$(sha "$PROOT_OUT")", "size_bytes": $(size "$PROOT_OUT")},
    "libproot-loader.so": {"sha256": "$(sha "$LOADER_OUT")", "size_bytes": $(size "$LOADER_OUT")}
  }
}
EOF
python3 "$ROOT/scripts/verify-runtime-toolchain.py" "$OUTPUT" \
  --report "$OUTPUT/verification-report.json" >/dev/null
echo "Built $ABI PRoot artifacts in $OUTPUT/artifacts"
