#!/usr/bin/env bash
set -euo pipefail

VERSION="${1:-3.21.3}"
ARCH="${2:-x86_64}"
OUTPUT="${3:-build/runtime-toolchain/$ARCH/alpine-minirootfs-$VERSION-$ARCH.tar.gz}"
BASE="https://dl-cdn.alpinelinux.org/alpine/v${VERSION%.*}/releases/$ARCH"
NAME="alpine-minirootfs-$VERSION-$ARCH.tar.gz"
mkdir -p "$(dirname "$OUTPUT")"
curl -fsSL "$BASE/$NAME" -o "$OUTPUT"
EXPECTED="$(curl -fsSL "$BASE/$NAME.sha256" | awk '{print $1}')"
ACTUAL="$(shasum -a 256 "$OUTPUT" | awk '{print $1}')"
[[ "$EXPECTED" == "$ACTUAL" ]] || { echo "rootfs checksum mismatch" >&2; exit 1; }
printf '%s  %s\n' "$ACTUAL" "$(basename "$OUTPUT")" > "$OUTPUT.sha256"
echo "Fetched verified Alpine $VERSION $ARCH rootfs: $OUTPUT"
