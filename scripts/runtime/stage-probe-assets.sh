#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ROOTFS="${ALPINE_PROBE_ROOTFS:-}"
PROOT="${ALPINE_PROBE_PROOT:-}"
LOADER="${ALPINE_PROBE_LOADER:-}"

usage() {
    cat <<'EOF'
Usage:
  stage-probe-assets.sh --rootfs /path/alpine-minirootfs.tar.gz \
    --proot /path/libproot.so --loader /path/libproot-loader.so

The files are verified against runtime/alpine-3.21.3-arm64.lock.json and copied
into the optional :alpine-runtime-pack-bundled module.
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --rootfs) ROOTFS="$2"; shift 2 ;;
        --proot) PROOT="$2"; shift 2 ;;
        --loader) LOADER="$2"; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
    esac
done

[[ -f "$ROOTFS" ]] || { echo "Missing --rootfs file" >&2; exit 2; }
[[ -f "$PROOT" ]] || { echo "Missing --proot file" >&2; exit 2; }
[[ -f "$LOADER" ]] || { echo "Missing --loader file" >&2; exit 2; }

EXPECTED_ROOTFS="ead8a4b37867bd19e7417dd078748e2312c0aea364403d96758d63ea8ff261ea"
EXPECTED_PROOT="5d2959c3a58f82609c8b95a92496835099a96faa8efc12f68e171a3597b5bc29"
EXPECTED_LOADER="12d2b63e897fd91a334fce23edea5d2419cae4d5fd2a369f05d03ab75682add0"
EXPECTED_UNSTRIPPED_LOADER="cdf53b7656bc3dc9db9a8592e62fe895204b8530f1bdfeb3ea6c365357a59a8b"

sha256() {
    if command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | awk '{print $1}'
    else
        sha256sum "$1" | awk '{print $1}'
    fi
}

ACTUAL_ROOTFS="$(sha256 "$ROOTFS")"
ACTUAL_PROOT="$(sha256 "$PROOT")"
ACTUAL_LOADER="$(sha256 "$LOADER")"
[[ "$ACTUAL_ROOTFS" == "$EXPECTED_ROOTFS" ]] || {
    echo "Rootfs checksum mismatch: $ACTUAL_ROOTFS" >&2
    exit 1
}
[[ "$ACTUAL_PROOT" == "$EXPECTED_PROOT" ]] || {
    echo "PRoot checksum mismatch: $ACTUAL_PROOT" >&2
    exit 1
}
if [[ "$ACTUAL_LOADER" == "$EXPECTED_UNSTRIPPED_LOADER" ]]; then
    NDK_HOME="${ANDROID_NDK_HOME:-$HOME/Library/Android/sdk/ndk/28.2.13676358}"
    case "$(uname -s)" in
        Darwin) HOST_TAG="darwin-x86_64" ;;
        Linux) HOST_TAG="linux-x86_64" ;;
        *) echo "Unsupported host for loader stripping" >&2; exit 1 ;;
    esac
    STRIP="$NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG/bin/llvm-strip"
    [[ -x "$STRIP" ]] || { echo "Missing NDK llvm-strip: $STRIP" >&2; exit 1; }
    STRIPPED_LOADER="$(mktemp -t alpine-proot-loader.XXXXXX)"
    trap 'rm -f "${STRIPPED_LOADER:-}"' EXIT
    cp "$LOADER" "$STRIPPED_LOADER"
    "$STRIP" "$STRIPPED_LOADER"
    LOADER="$STRIPPED_LOADER"
    ACTUAL_LOADER="$(sha256 "$LOADER")"
fi
[[ "$ACTUAL_LOADER" == "$EXPECTED_LOADER" ]] || {
    echo "PRoot loader checksum mismatch: $ACTUAL_LOADER" >&2
    exit 1
}

ASSETS="$ROOT/alpine-runtime-pack-bundled/src/main/assets"
JNILIBS="$ROOT/alpine-runtime-pack-bundled/src/main/jniLibs/arm64-v8a"
mkdir -p "$ASSETS" "$JNILIBS"
rm -f "$ASSETS/alpine-minirootfs.tar.gz" "$ASSETS/alpine-minirootfs.tar"
cp "$ROOTFS" "$ASSETS/alpine-minirootfs.tar.gz.asset"
cp "$PROOT" "$JNILIBS/libproot.so"
cp "$LOADER" "$JNILIBS/libproot-loader.so"
chmod 0644 \
    "$ASSETS/alpine-minirootfs.tar.gz.asset" \
    "$JNILIBS/libproot.so" \
    "$JNILIBS/libproot-loader.so"

echo "Staged and verified bundled Alpine runtime assets:"
echo "  rootfs: $ASSETS/alpine-minirootfs.tar.gz.asset"
echo "  proot:  $JNILIBS/libproot.so"
echo "  loader: $JNILIBS/libproot-loader.so"
