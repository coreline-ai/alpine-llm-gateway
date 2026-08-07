# GitHub 원격 CI 검증 상태

최종 조회: `2026-08-07 20:16 KST`

## 최신 원격 성공 기준선

- 저장소: `coreline-ai/alpine-llm-gateway` (private)
- 브랜치: `main`
- Workflow: `.github/workflows/ci.yml`
- Commit: `3389fcbf92207bb632dac002d7bb501d0901ff22`
- Run: [30807869557](https://github.com/coreline-ai/alpine-llm-gateway/actions/runs/30807869557)
- 시작: `2026-08-03 20:01:38 KST`
- 완료: `2026-08-03 20:13:53 KST`
- 결과: `completed / success`

## Job 결과

| Job | 결과 | 주요 범위 |
|---|---|---|
| Python 3.11 | 성공 | unit, compile, deterministic Gateway pack, CLI smoke, redacted report와 readiness 검증 |
| Android modules | 성공 | test·lint·assemble, Gradle 9 audit, 19개 SDK publication, 8개 consumer matrix, license report와 internal bundle upload |

Android job 안에서 다음 배포 관련 단계도 성공했다.

- reusable SDK artifact publication
- publication metadata와 payload isolation 검증
- published-artifact consumer matrix release build
- consumer permission·ABI·payload matrix 검증
- x86_64 emulator gate의 가용성 기록(`allow-skip`; 실제 E2E 성공을 의미하지 않음)
- license compliance report 생성
- `INTERNAL_ONLY` SDK release bundle 패키징·artifact upload

## 현재 로컬과 원격의 차이

문서 갱신 시점 로컬 상태:

- local `HEAD`: `bc101f2827dfb6f3aca6f19402cb053f67761a9a`
- remote `origin/main`: `3389fcbf92207bb632dac002d7bb501d0901ff22`
- 로컬 브랜치: 원격보다 commit 1개 앞섬
- README, UI, launcher와 문서 작업: working tree에 존재하며 아직 Push되지 않음

따라서 위 성공 run은 원격 commit `3389fcb`의 기준선만 증명한다. 로컬 `bc101f2`와 현재 working tree 변경은 Push 후 새 workflow가 성공하기 전까지 **원격 CI 미검증**으로 취급한다.

## 판정

| 대상 | 판정 |
|---|---|
| 원격 `main@3389fcb` 기준선 | `READY` |
| 현재 로컬 commit·working tree | `NOT_RUN` |
| 공개 배포 | 계속 `NO-GO` — CI 외 라이선스·source·Provider·Play gate 차단 |

`distribution/release-readiness.json`의 `github_remote_ci` gate는 위 원격 기준선에 대해 `READY`다. 이후 코드·Workflow·배포 입력이 변경되면 새 HEAD의 CI 결과로 다시 검증해야 하며 실패·취소·미실행이면 fail-closed로 취급한다.

## 이전 이력

2026-08-02의 run `30743827724`는 code/test/publication/consumer 검증 후 license compliance report가 없어 package 단계에서 실패했다. Workflow에 report 생성 단계를 추가한 run `30744482850`부터 전체 성공했으며, 현재 최신 성공 기준선은 run `30807869557`이다.
