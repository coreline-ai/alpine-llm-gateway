# Third-party notices

## Alpine Linux minirootfs

- Version: `3.21.3`
- aarch64 source: `https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/aarch64/alpine-minirootfs-3.21.3-aarch64.tar.gz`
- aarch64 SHA-256: `ead8a4b37867bd19e7417dd078748e2312c0aea364403d96758d63ea8ff261ea`
- x86_64 source: `https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/x86_64/alpine-minirootfs-3.21.3-x86_64.tar.gz`
- x86_64 SHA-256: `1a694899e406ce55d32334c47ac0b2efb6c06d7e878102d1840892ad44cd5239`
- License: aggregate distribution; package-level identifiers are generated as
  ABI-specific `alpine-package-inventory-*.json` files in the release bundle.

## OpenMinis PRoot Android fork

- Repository: `https://github.com/OpenMinis/proot.git`
- Commit: `8cf13e997cdc9472997aae19df8050c073c9a86c`
- Local patch: `scripts/runtime/patches/proot-android-winsize.patch`
- Patch SHA-256: `20726d1ccf9bb8c952a6039d5158168dad58ec62bcf7cbf73bc3170b8c4a9a27`
- Source-declared license: `GPL-2.0-or-later`
- Combined PRoot+talloc binary conclusion: `NOASSERTION`, review required
- License text: `licenses/PRoot-GPL-2.0-or-later.txt`

## talloc

- Version: `2.4.2`
- Source: `https://download.samba.org/pub/talloc/talloc-2.4.2.tar.gz`
- Source SHA-256: `85ecf9e465e20f98f9950a52e9a411e14320bc555fa257d87697b7e7a9b1d8a6`
- Linkage: statically linked into the pinned PRoot build
- License: `LGPL-3.0-or-later`
- License text: `licenses/LGPL-3.0-or-later.txt`

The authoritative checksums and SPDX relationship data are included from
`runtime/alpine-3.21.3-arm64.lock.json` and
`alpine-runtime-pack-bundled/src/main/resources/META-INF/alpine-runtime/sbom.spdx.json`.
