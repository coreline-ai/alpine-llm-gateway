# Alpine runtime Phase 1 verification — 2026-08-01

## Artifact baseline

- Host target SDK: 36
- Alpine: 3.21.3 aarch64 minirootfs
- PRoot source: `OpenMinis/proot@8cf13e997cdc9472997aae19df8050c073c9a86c`
- PRoot package path: `nativeLibraryDir/libproot.so`
- Guest loader path: `nativeLibraryDir/libproot-loader.so`
- Artifact lock: `runtime/probe/artifacts.lock.json`

## Samsung physical-device result

- Device: Samsung SM-S931N (`arm64-v8a`)
- Android API: 36
- App target SDK: 36
- Result: PASS

```json
{
  "sdk_int": 36,
  "target_sdk": 36,
  "supported_abis": "arm64-v8a",
  "native_proot_exists": true,
  "native_proot_executable": true,
  "native_loader_exists": true,
  "native_loader_executable": true,
  "success": true,
  "exit_code": 0,
  "timed_out": false,
  "output_truncated": false,
  "stdout": "probe=ok\nalpine=3.21.3\nmachine=aarch64\nuid=0\npwd=/workspace\nworkspace-ok\n",
  "elapsed_ms": 360
}
```

## Emulator result

- AVD: Phone_API_35
- ABI: `arm64-v8a`
- Android API: 35
- App target SDK: 36
- Result: PASS

```json
{
  "sdk_int": 35,
  "target_sdk": 36,
  "supported_abis": "arm64-v8a",
  "native_proot_exists": true,
  "native_proot_executable": true,
  "native_loader_exists": true,
  "native_loader_executable": true,
  "success": true,
  "exit_code": 0,
  "timed_out": false,
  "output_truncated": false,
  "stdout": "probe=ok\nalpine=3.21.3\nmachine=aarch64\nuid=0\npwd=/workspace\nworkspace-ok\n",
  "elapsed_ms": 722
}
```

## Failure found and fixed during the gate

1. Copying PRoot into writable app storage is invalid for modern target SDKs.
   PRoot now resolves from the APK-packaged native-library directory.
2. AAPT inflated a `.tar.gz` asset and renamed it to `.tar`. The probe now
   stages verified gzip bytes under an opaque `.asset` suffix.
3. Embedded PRoot loader extraction used a writable cache path and guest
   `/bin/sh` failed with `Permission denied`. The stripped static loader is now
   packaged separately in the APK and passed through `PROOT_LOADER`.

## ABI decision

The available Apple Silicon AVDs are arm64. Phase 1 therefore declares only
`arm64-v8a` supported. `x86_64` remains an explicit CI/Linux-host follow-up and
will require its own PRoot/rootfs hashes; it is not a blocker for creating the
ABI-neutral API module.
