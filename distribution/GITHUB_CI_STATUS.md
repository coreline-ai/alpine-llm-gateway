# GitHub 원격 CI 검증 상태

원격 Actions 재조회: `2026-08-14 21:03 KST`

## 최신 성공 기준선

- 저장소: `coreline-ai/alpine-llm-gateway` (private)
- 브랜치: `main`
- Workflow: `.github/workflows/ci.yml` / `CI`
- Commit: `a1985f335276d238293463bce863b2480fd795be`
- Run: [`31797601916`](https://github.com/coreline-ai/alpine-llm-gateway/actions/runs/31797601916)
- 시작: `2026-08-14 20:46:56 KST`
- 완료: `2026-08-14 21:03:04 KST`
- 결과: `completed / success`

## Job 결과

| Job | 시간(KST) | 결과 | 주요 범위 |
|---|---|---|---|
| Python 3.11 | 20:47:00–20:47:14 | `success` | unit, compile, UI design contract, deterministic Gateway, CLI smoke, redacted report와 readiness verifier |
| Android modules | 20:46:59–21:03:03 | `success` | test·lint·assemble, OAuth/app boundary, Gradle 9, SDK publication, consumer matrix, license report, internal bundle |

Android job 안에서 다음 단계가 성공했다.

- integrated APK OAuth/app-boundary policy 검사
- reusable SDK artifact publication
- publication metadata와 payload isolation 검증
- published-artifact consumer matrix build
- consumer permission·ABI·payload matrix 검증
- x86_64 emulator gate 가용성 기록(`allow-skip`; 실제 emulator E2E 성공 아님)
- license compliance report 생성
- `INTERNAL_ONLY` SDK release bundle 패키징·artifact upload

## 구현 commit과 topic branch 선행 CI

- main 구현·문서 Commit: `493546fd8efdfee61b04a2d16ad0fd1ef6384c12`
- main Run: [`31796454558`](https://github.com/coreline-ai/alpine-llm-gateway/actions/runs/31796454558), `completed/success`
- 시작/완료: `2026-08-14 20:30:01–20:43:00 KST`

- 브랜치: `codex/model-catalog-hardening-20260814`
- 동일 Commit: `493546fd8efdfee61b04a2d16ad0fd1ef6384c12`
- Run: [`31795327701`](https://github.com/coreline-ai/alpine-llm-gateway/actions/runs/31795327701)
- 시작/완료: `2026-08-14 20:13:15–20:29:12 KST`
- `Python 3.11`: `success`
- `Android modules`: `success`

topic branch CI 성공을 확인한 뒤 원격 `main` 변경이 없음을 재검사하고 `--ff-only`로 main에 반영했다.

## 현재 작업과 기준선의 차이

영속 모델 카탈로그, Provider 모델 편집, stale session 제거, `no-silent-switch`, unavailable 안내와
관련 테스트·문서는 `main@493546f`에 반영됐고 topic/main CI가 모두 성공했다.

그 결과를 기록한 evidence commit `a1985f3` 자체도 run `31797601916`에서 Python·Android가 모두
성공했다. 따라서 `github_remote_ci` gate를 `READY`로 변경할 조건을 충족했다.

## 판정

| 대상 | 판정 |
|---|---|
| 원격 `main@a1985f3` evidence commit | `READY` — run `31797601916` |
| 원격 `main@493546f` 구현·문서 | `READY` — run `31796454558` |
| topic `493546f` | `READY` — run `31795327701` |
| `github_remote_ci` gate | `READY` |
| 공개 배포 | `NO-GO` — 외부 조건 6개 release blocker가 계속 차단 |

`distribution/release-readiness.json`의 `github_remote_ci`만 `READY`로 변경한다. 다른 6개 release
blocker는 owner/evidence 없이 변경하지 않는다. 이 gate 상태 commit도 push 후 workflow로 재확인한다.

## Historical record

- run `31358819831` / `b81a7d8`는 2026-08-10의 직전 성공 기준선이었다.
- run `30807869557` / `3389fcb`는 2026-08-03 당시 최신 성공 기준선이었다.
- run `30743827724`는 license compliance report 누락으로 package 단계에서 실패했다.
- run `30744482850`부터 report 생성 단계가 포함됐다.

위 이력은 현재 기준선 판정이 아니며, 최신 판정에는 run `31797601916`을 사용한다.
