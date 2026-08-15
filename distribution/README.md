# 배포 증거와 현재 보류 상태

이 디렉터리는 SDK release bundle에 포함할 고지와 제3자 라이선스 원문을 관리한다.

- `PROJECT_LICENSE_STATUS.md`: 이 저장소 자체 코드의 배포 허가 상태
- `THIRD_PARTY_NOTICES.md`: Alpine runtime payload와 native 구성요소 출처
- `SOURCE_OFFER_STATUS.md`: copyleft source 제공 상태와 public release gate
- `PROJECT_CODE_OSS_BOUNDARY.md`: 프로젝트 고유 코드와 제3자 OSS artifact 경계
- `GITHUB_CI_STATUS.md`: 최신 원격 성공 기준선과 현재 로컬 변경의 검증 차이
- `release-readiness.json`: 공개 배포·기능 지원 gate의 기계 판독 상태
- `current-state-release-decision.json`: 미완료 증거를 보존한 현재 상태 전체 배포 소유자 결정
- `licenses/`: 현재 번들에 직접 포함되는 PRoot/talloc 라이선스 원문

`scripts/package-sdk-release.py`는 이 디렉터리, Maven artifact, lock/SBOM, ABI별 Alpine package
inventory, compliance report와 SHA-256 목록을 하나의 `dist/alpine-sdk-<version>/` 디렉터리로 묶는다.
검증된 native source bundle이 있으면 `oss-sources/native/`에 별도 artifact로 포함한다.

evidence-ready mode는 `INTERNAL_ONLY`이며 `manifest.json`의
`external_distribution_ready=false`를 유지한다. 과거 `CURRENT_STATE_OWNER_DECISION` 경로의
`PROCEED_CURRENT_STATE` 승인은 2026-08-15 최신 소유자 지시로 대체되었다. 현재 상태는
`NO_DEPLOYMENT_PLANNED`이며 아래 명령은 역사적 검증 재현용일 뿐, 새 배포 승인으로 사용하지 않는다.

과거 authorization override 명령은 현재 실행하지 않는다. readiness 상태 조회가 필요하면 override 없이
`scripts/verify-release-readiness.py`의 일반 감사 경로만 사용한다.

현재 deliverable 기준 release-blocking gate 7개는 모두 `BLOCKED`다. 과거 GitHub remote CI 성공은
이전 baseline만 증명하며, 미커밋 또는 새 commit 변경은 명시적 Push와 해당 SHA workflow 성공 전까지
`GITHUB_CURRENT_HEAD_CI_NOT_VERIFIED`로 유지한다.

## 최신 소유자 방침

- GUI/UX 품질 개선 전까지 공개 배포, Play 등록, 신규 release/tag/publication을 진행하지 않는다.
- private `v0.3.0` Release와 signed artifact는 내부 검증 이력으로만 보존한다.
- JSON 결정 파일은 과거 시점 증거이며 현재 승인 상태의 정본은 [`HANDOFF.md`](../HANDOFF.md)다.
- 배포를 재개하려면 소유자의 새로운 명시적 지시와 당시 readiness 재감사가 필요하다.
