# x86_64 bundled runtime source and license notice

This optional module redistributes an x86_64 Alpine Linux minirootfs and Android PRoot executable.

| Component | Source | Revision | License |
|---|---|---|---|
| Alpine minirootfs | https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/x86_64/ | 3.21.3 | Package-level licenses |
| PRoot Android fork | https://github.com/OpenMinis/proot | `8cf13e997cdc9472997aae19df8050c073c9a86c` | GPL-2.0-or-later (source declared); combined binary review required |
| talloc | https://download.samba.org/pub/talloc/ | 2.4.2 | LGPL-3.0-or-later |

The exact checksums and build parameters are recorded in
`runtime/alpine-3.21.3-x86_64.lock.json`. The machine-readable component notice is
`src/main/resources/META-INF/alpine-runtime/x86_64/sbom.spdx.json`.

The packaged executable is built from the pinned PRoot source revision without local PRoot
patches. The reproducible build script only applies Android toolchain and linker settings to its
disposable build copy.

This pack remains experimental until the x86_64 emulator device gate passes. Downstream
distributors must preserve the GPL/LGPL notices and provide corresponding source under the
applicable license terms.
