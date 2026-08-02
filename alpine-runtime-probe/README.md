# Alpine Runtime Probe

`alpine-runtime-probe` is a debug-only, product-independent Android app used to
prove that the reusable runtime can execute Alpine under the current Android
target SDK. It is not the final SDK module or a user-facing product.

## What it verifies

- target SDK 36 host app
- ABI-specific PRoot PIE from `ApplicationInfo.nativeLibraryDir`
- separately packaged static PRoot guest loader through `PROOT_LOADER`
- verified Alpine 3.21.3 minirootfs extraction
- `/bin/sh`, fake root, working directory and workspace write
- persisted JSON result readable through `adb run-as`

## Bundled artifacts

Runtime binaries are supplied by the optional `:alpine-runtime-pack-bundled`
and `:alpine-runtime-pack-x86_64` modules. The locks are
`../runtime/alpine-3.21.3-arm64.lock.json` and
`../runtime/alpine-3.21.3-x86_64.lock.json`.
To replace the payload from pinned source/build outputs, run:

```bash
./scripts/runtime/stage-probe-assets.sh \
  --rootfs /trusted/alpine-minirootfs.tar.gz \
  --proot /trusted/libproot.so \
  --loader /trusted/proot-source/src/loader/loader
```

The script accepts the pinned unstripped loader, strips it with NDK
`28.2.13676358`, and verifies all final hashes.

## Build and run

```bash
./gradlew :alpine-runtime-probe:assembleDebug
./scripts/runtime/run-probe-device.sh <adb-serial>
./scripts/runtime/run-x86_64-emulator-gate.sh
```

The script exits nonzero unless the result contains `"success": true`.

## Support status

- `arm64-v8a`: Phase-3 verified on Samsung SM-S931N Android 16 and an Android
  15 arm64 emulator, including install/start/exec/stop/restart/repair/reset.
- `x86_64`: rootfs/PRoot/loader/native PTY packaging, checksum, SBOM, ELF machine
  and 16 KiB alignment gates pass. It remains experimental and must not be
  advertised as supported until `run-x86_64-emulator-gate.sh` reports `PASSED`.
