# Copyleft source 제공 상태

`scripts/build-oss-source-bundle.py`는 PRoot/talloc native source archive를 생성하고 검증한다.
현재 로컬 검증 bundle에는 다음 자료가 포함된다.

- 고정 PRoot commit의 tracked source와 `COPYING`
- 공식 talloc `2.4.2` 전체 source archive와 SHA-256
  `85ecf9e465e20f98f9950a52e9a411e14320bc555fa257d87697b7e7a9b1d8a6`
- 실제 standalone talloc build input, `replace.h`와 공식 source 대비 delta patch
- Android PRoot patch, native build script, NDK/build lock, arm64/x86_64 binary checksum
- PRoot/talloc license text와 third-party notice

동일 입력으로 두 번 생성한 archive가 동일 SHA-256
`67af3163d2b07cf134f30b28d630858afd8ba7590445d1f43866e1b4853de278`를 갖는 것을 확인했다.
이 checksum은 현재 로컬 검증 기록이며 release 입력이 변경되면 다시 생성해야 한다.

다만 Alpine rootfs의 package-level exact source mirror는 아직 포함되지 않는다. arm64/x86_64 inventory는
각각 15개 package를 확인했고, source 검토 대상 9개 package가 6개 origin source로 묶인다. 따라서 native
source bundle만으로 전체 Android runtime의 외부 배포 source gate를 닫지 않는다.

공개 배포 전 필수 gate:

1. `runtime/alpine-package-inventory-*.json`의 6개 source origin에 대한 exact source/APKBUILD/patch를 보존한다.
2. source mirror manifest의 origin, Alpine commit, archive checksum을 inventory와 연결한다.
3. PRoot+talloc combined binary의 최종 license conclusion을 OSS 검토자가 승인한다.
4. native/rootfs source archive를 binary와 동일 release 식별자·checksum으로 연결한다.
5. 프로젝트 라이선스와 앱에 포함되는 전체 제3자 고지를 법무/배포 담당자가 검토한다.

이 gate가 닫히기 전 `manifest.json`의 `external_distribution_ready`와
`remote_distribution_ready` 값은 모두 `false`다.
