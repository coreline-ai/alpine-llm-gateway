# Bundled runtime source and license notice

This optional module redistributes an Alpine Linux minirootfs and an Android PRoot executable.

| Component | Source | Revision | License |
|---|---|---|---|
| Alpine minirootfs | https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/aarch64/ | 3.21.3 | Package-level licenses |
| PRoot Android fork | https://github.com/OpenMinis/proot | `8cf13e997cdc9472997aae19df8050c073c9a86c` | GPL-2.0-or-later (source declared); combined binary review required |
| talloc | https://download.samba.org/pub/talloc/ | 2.4.2 | LGPL-3.0-or-later |

The exact checksums, build Android API and linkage are recorded in
`runtime/alpine-3.21.3-arm64.lock.json`. The machine-readable component notice is
`src/main/resources/META-INF/alpine-runtime/sbom.spdx.json`.

The PRoot source revision above plus
`scripts/runtime/patches/proot-android-winsize.patch` is the source used for the packaged
executable. The patch is applied only to the disposable build copy by
`scripts/runtime/build-proot-android.sh`.
Downstream distributors must preserve the GPL/LGPL notices and provide corresponding source under
the applicable license terms.
