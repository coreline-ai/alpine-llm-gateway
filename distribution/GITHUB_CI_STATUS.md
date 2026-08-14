# GitHub 원격 CI 검증 상태

원격 Actions 재조회: `2026-08-14 20:43 KST`

## 최신 성공 기준선

- 저장소: `coreline-ai/alpine-llm-gateway` (private)
- 브랜치: `main`
- Workflow: `.github/workflows/ci.yml` / `CI`
- Commit: `493546fd8efdfee61b04a2d16ad0fd1ef6384c12`
- Run: [`31796454558`](https://github.com/coreline-ai/alpine-llm-gateway/actions/runs/31796454558)
- 시작: `2026-08-14 20:30:01 KST`
- 완료: `2026-08-14 20:43:00 KST`
- 결과: `completed / success`

## Job 결과

| Job | 시간(KST) | 결과 | 주요 범위 |
|---|---|---|---|
| Python 3.11 | 20:30:04–20:30:18 | `success` | unit, compile, UI design contract, deterministic Gateway, CLI smoke, redacted report와 readiness verifier |
| Android modules | 20:30:04–20:42:59 | `success` | test·lint·assemble, OAuth/app boundary, Gradle 9, SDK publication, consumer matrix, license report, internal bundle |

Android job 안에서 다음 단계가 성공했다.

- integrated APK OAuth/app-boundary policy 검사
- reusable SDK artifact publication
- publication metadata와 payload isolation 검증
- published-artifact consumer matrix build
- consumer permission·ABI·payload matrix 검증
- x86_64 emulator gate 가용성 기록(`allow-skip`; 실제 emulator E2E 성공 아님)
- license compliance report 생성
- `INTERNAL_ONLY` SDK release bundle 패키징·artifact upload

## Topic branch 선행 CI

- 브랜치: `codex/model-catalog-hardening-20260814`
- 동일 Commit: `493546fd8efdfee61b04a2d16ad0fd1ef6384c12`
- Run: [`31795327701`](https://github.com/coreline-ai/alpine-llm-gateway/actions/runs/31795327701)
- 시작/완료: `2026-08-14 20:13:15–20:29:12 KST`
- `Python 3.11`: `success`
- `Android modules`: `success`

topic branch CI 성공을 확인한 뒤 원격 `main` 변경이 없음을 재검사하고 `--ff-only`로 main에 반영했다.

## 현재 작업과 기준선의 차이

영속 모델 카탈로그, Provider 모델 편집, stale session 제거, `no-silent-switch`, unavailable 안내와
관련 테스트·문서는 `main@493546f`에 반영됐고 위 topic/main CI가 모두 성공했다.

이 파일과 handoff/readiness의 evidence 갱신 commit은 새 current head이므로, 그 commit 자체의
Python·Android workflow가 성공하기 전까지 `github_remote_ci` gate는 fail-closed `BLOCKED`를 유지한다.

## 판정

| 대상 | 판정 |
|---|---|
| 원격 `main@493546f` 구현·문서 | `READY` — run `31796454558` |
| topic `493546f` | `READY` — run `31795327701` |
| 이 evidence 갱신 commit | `NOT_RUN` — push 후 current-head CI 필요 |
| 공개 배포 | `NO-GO` — CI 외 6개 release blocker가 계속 차단 |

`distribution/release-readiness.json`의 `github_remote_ci`는 evidence 갱신 commit의 CI까지 성공한 뒤에만
`READY`로 바꾼다. 다른 6개 release blocker는 owner/evidence 없이 변경하지 않는다.

## Historical record

- run `31358819831` / `b81a7d8`는 2026-08-10의 직전 성공 기준선이었다.
- run `30807869557` / `3389fcb`는 2026-08-03 당시 최신 성공 기준선이었다.
- run `30743827724`는 license compliance report 누락으로 package 단계에서 실패했다.
- run `30744482850`부터 report 생성 단계가 포함됐다.

위 이력은 현재 기준선 판정이 아니며, 최신 판정에는 run `31796454558`을 사용한다.
