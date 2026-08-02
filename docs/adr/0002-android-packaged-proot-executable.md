# ADR-0002: Execute PRoot only from the packaged native-library path

- Status: Accepted for target SDK 36 feasibility testing
- Date: 2026-08-01

## Context

Android 10 removed direct `execve()` permission for files in a writable app
home for untrusted apps targeting API 29 or newer. The initial runtime wrapper
copied a PRoot asset into `filesDir` and marked it executable, which is not a
valid target SDK 36 production strategy.

OpenMinis packages its Android PRoot PIE as
`jniLibs/<abi>/libproot.so` and resolves it through
`ApplicationInfo.nativeLibraryDir`. Its source is pinned to
`OpenMinis/proot@8cf13e997cdc9472997aae19df8050c073c9a86c` and links talloc
2.4.2 statically.

## Decision

- Modern host apps execute PRoot only from the APK-packaged
  `nativeLibraryDir/libproot.so` path.
- The PRoot guest loader is packaged separately as
  `nativeLibraryDir/libproot-loader.so` and supplied through `PROOT_LOADER`.
  Extracting the embedded loader into a writable cache directory is not a
  valid target SDK 36 execution path.
- The host packaging must make a filesystem path available for the executable;
  the Phase-1 probe uses extracted native-library packaging.
- PRoot is verified against the ABI-specific artifact manifest before rootfs
  installation or command execution.
- Asset-copy execution is retained only for host apps targeting below API 29
  and is never selected for this project's target SDK 36 apps.
- Rootfs remains data and is extracted into app-private storage with archive,
  size, entry, path and checksum validation.
- The first supported probe artifact is arm64-v8a. An x86_64 artifact must have
  a separate checksum and device/emulator result before it is declared
  supported.

## Phase-1 artifact lock

`runtime/alpine-3.21.3-arm64.lock.json` pins Alpine 3.21.3 aarch64, the PRoot source
commit, NDK/API build inputs, hashes and licenses. Binary artifacts are staged
locally and are not committed to Git.

## Failure policy

If the packaged native executable is absent, non-executable or has the wrong
checksum, installation fails before activating a rootfs. The SDK must not fall
back to copied executable code on a modern target SDK.
