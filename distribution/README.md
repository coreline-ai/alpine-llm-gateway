# Phase 7 배포 고지

이 디렉터리는 SDK release bundle에 포함할 고지와 제3자 라이선스 원문을 관리한다.

- `PROJECT_LICENSE_STATUS.md`: 이 저장소 자체 코드의 배포 허가 상태
- `THIRD_PARTY_NOTICES.md`: Alpine runtime payload와 native 구성요소 출처
- `SOURCE_OFFER_STATUS.md`: copyleft source 제공 상태와 public release gate
- `PROJECT_CODE_OSS_BOUNDARY.md`: 프로젝트 고유 코드와 제3자 OSS artifact 경계
- `GITHUB_CI_STATUS.md`: 최신 원격 성공 기준선과 현재 로컬 변경의 검증 차이
- `release-readiness.json`: 공개 배포·기능 지원 gate의 기계 판독 상태
- `licenses/`: 현재 번들에 직접 포함되는 PRoot/talloc 라이선스 원문

`scripts/package-sdk-release.py`는 이 디렉터리, Maven artifact, lock/SBOM, ABI별 Alpine package
inventory, compliance report와 SHA-256 목록을 하나의 `dist/alpine-sdk-<version>/` 디렉터리로 묶는다.
검증된 native source bundle이 있으면 `oss-sources/native/`에 별도 artifact로 포함한다.

현재 mode는 `INTERNAL_ONLY`다. 내부 bundle 생성 성공은 외부 배포 승인을 의미하지 않으며,
`manifest.json`의 `external_distribution_ready`를 별도로 확인해야 한다.

현재 deliverable 기준 release-blocking gate 7개는 모두 `BLOCKED`다. 과거 GitHub remote CI 성공은
이전 baseline만 증명하며, 미커밋 또는 새 commit 변경은 명시적 Push와 해당 SHA workflow 성공 전까지
`GITHUB_CURRENT_HEAD_CI_NOT_VERIFIED`로 유지한다.
