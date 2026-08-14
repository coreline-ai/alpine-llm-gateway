# GitHub 원격 CI 검증 상태

원격 Actions 재조회: `2026-08-14 KST`

## 최신 성공 기준선

- 저장소: `coreline-ai/alpine-llm-gateway` (private)
- 브랜치: `main`
- Workflow: `.github/workflows/ci.yml` / `CI`
- Commit: `b81a7d8ee12af72ff95180bfeadabe68e5be950e`
- Run: [`31358819831`](https://github.com/coreline-ai/alpine-llm-gateway/actions/runs/31358819831)
- 시작: `2026-08-10 14:30:38 KST`
- 완료: `2026-08-10 14:44:02 KST`
- 결과: `completed / success`

## Job 결과

| Job | 시간(KST) | 결과 | 주요 범위 |
|---|---|---|---|
| Python 3.11 | 14:30:40–14:30:51 | `success` | unit, compile, UI design contract, deterministic Gateway, CLI smoke, redacted report와 readiness verifier |
| Android modules | 14:30:41–14:44:01 | `success` | test·lint·assemble, OAuth/app boundary, Gradle 9, SDK publication, consumer matrix, license report, internal bundle |

Android job 안에서 다음 단계가 성공했다.

- integrated APK OAuth/app-boundary policy 검사
- reusable SDK artifact publication
- publication metadata와 payload isolation 검증
- published-artifact consumer matrix build
- consumer permission·ABI·payload matrix 검증
- x86_64 emulator gate 가용성 기록(`allow-skip`; 실제 emulator E2E 성공 아님)
- license compliance report 생성
- `INTERNAL_ONLY` SDK release bundle 패키징·artifact upload

## 현재 작업과 기준선의 차이

현재 작업 branch는 `codex/model-catalog-hardening-20260814`이며 `main@b81a7d8`에서 시작했다.
working tree에는 영속 모델 카탈로그, Provider 모델 편집, stale session 제거,
`no-silent-switch`, unavailable 안내와 관련 테스트·문서 변경이 있다.

run `31358819831`은 `b81a7d8` 기준선까지만 증명한다. 현재 변경은 commit·push 후 해당 SHA의
workflow가 성공하기 전까지 **원격 CI `NOT_RUN`**이다.

## 판정

| 대상 | 판정 |
|---|---|
| 원격 `main@b81a7d8` 기준선 | `READY` — run `31358819831` |
| 현재 topic branch working tree | `NOT_RUN` |
| 공개 배포 | `NO-GO` — CI 외 6개 blocker와 current-head CI가 차단 |

`distribution/release-readiness.json`의 `github_remote_ci`는 current deliverable을 대상으로 하므로
`GITHUB_CURRENT_HEAD_CI_NOT_VERIFIED` / `BLOCKED`를 유지한다. topic branch 또는 final main SHA의
Python·Android job 성공과 run URL·시각을 기록하고, 그 evidence 문서 commit 자체의 CI까지 확인한
뒤에만 `READY`로 바꿀 수 있다.

## Historical record

- run `30807869557` / `3389fcb`는 2026-08-03 당시 최신 성공 기준선이었다.
- run `30743827724`는 license compliance report 누락으로 package 단계에서 실패했다.
- run `30744482850`부터 report 생성 단계가 포함됐다.

위 이력은 현재 기준선 판정이 아니며, 최신 판정에는 run `31358819831`을 사용한다.
