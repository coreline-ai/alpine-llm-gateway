# Alpine LLM Gateway — Codex App Server 구현 핸드오프

갱신: `2026-08-15 20:04 KST`
작업 트리: `/Volumes/Eprojects/project_202607/alpine-llm-gateway`

이 문서는 OpenAI 공식 Codex App Server 기반 `Codex Agent (ChatGPT 로그인)` 구현의 현재 상태
정본이다. 과거 OAuth 호환 실험 문서와 충돌하면 아래 문서 순서로 판단한다.

> **최신 소유자 방침 (2026-08-15 20:04 KST)**
> 현재 제품은 **배포 예정이 없는 내부 개발·검증 단계**다. GUI/UX 품질이 배포 수준에
> 도달하지 않았으며, 별도 지시 전까지 Play Store 등록·공개 릴리스·추가 배포 자동화를
> 진행하지 않는다. 과거 `PROCEED_CURRENT_STATE` 결정은 이 지시로 대체되었다.

1. [`dev-plan/implement_20260815_072222.md`](dev-plan/implement_20260815_072222.md) Revision 3
2. [`docs/codex-appserver-completion-audit-20260815.md`](docs/codex-appserver-completion-audit-20260815.md)
3. [`docs/codex-appserver-integration.md`](docs/codex-appserver-integration.md)
4. 이 문서

## 1. Git 상태

| 항목 | 값 |
|---|---|
| branch | `main` |
| 구현 시작 base | `fc8b483416728579e69f5b914c32e7c395f7cfec` |
| 최신 확인 commit | `f26931a39bae4083cd6692d919d9c281fa214a5c` |
| remote 동기화 | `main...origin/main = 0 ahead / 0 behind` |
| working tree | 최신 상태 문서 수정 중; `git status --short`를 정본으로 사용 |

금지 사항:

- `git reset`, `git clean`, `git stash`, 기존 변경 일괄 복원 금지
- 별도 요청 없는 reset/clean/stash와 강제 push 금지
- 다른 구현과 섞인 파일을 임의로 삭제하거나 전체 포맷하지 않음

## 2. 구현 목표와 고정 계약

- OpenAI 공식 `codex app-server --listen stdio://`만 사용한다.
- Android 앱이 OAuth client identity나 token exchange를 구현하지 않는다.
- credential의 유일한 소유자는 app-private `noBackupFilesDir` 아래에서 실행되는 App Server다.
- Android는 auth credential 파일의 내용을 읽기·복사·로그하지 않는다.
- synthetic Provider profile, `FAST_CHAT`, 빈 private workspace만 허용한다.
- 기존 ProviderProfile, Alpine Runtime public API, direct/Alpine backend 동작을 보존한다.
- feature 기본값은 OFF이며 ON release는 기본적으로 fail-closed한다. 코드에는 과거
  `CURRENT_STATE_OWNER_DECISION` 경로가 남아 있으나, 최신 배포 보류 지시에 따라 현재 사용하지 않는다.
- 자동 fallback, replay, model 보정, approval/tool action, shell, WebSocket은 금지한다.

## 3. 구현 완료 범위

### 신규 모듈

- `alpine-codex-appserver-pack-android`
  - pinned 실행 파일·schema·artifact lock·NOTICE·SBOM 패키징
  - release Gate와 exact artifact verifier
- `alpine-codex-appserver-android`
  - direct stdio process, bounded JSONL/RPC, official ChatGPT auth, model/thread/turn client
  - Android system CA만 app-private PEM으로 고정하고 authenticated loopback CONNECT bridge로
    정적 Linux binary의 Android DNS 차이를 보완
  - request timeout/writer failure 이후 terminal 폐기
  - strict schema type 및 `result`/`error` exact one-of
  - malformed server request ID/method 무반사, 지원하지 않는 정상 request는 `-32601`
- `alpine-chat-backend-codex`
  - synthetic profile 전용 FAST_CHAT session
  - bounded thread link store, stream/Stop/interrupt-once, no replay/no reroute
  - safe thread metadata와 unsafe/unknown event fail-closed 분류

### 통합

- contextual additive `ChatBackend` API와 기존 호출 호환
- `ChatViewModel`의 profile-scoped Codex stream cancel/join
- `IntegratedChatHostController`의 stop/join 이후 logout 순서 보장
- process-lifetime auth controller, Activity recreation 유지, 10분 login timeout/cancel
- active Codex turn cancel/join 후 App Server만 교체하는 중복 방지 controlled restart UI
- graceful/forcible 종료 후 생존 child를 `PROCESS_TERMINATION_FAILED`로 차단하는 orphan fail-close
- 모델 목록, synthetic connection, 로그인/로그아웃 UI
- ON debug package `.codexdebug`, 동일 패키지/서명의 OFF rollback build
- release readiness 13 Gate와 redacted account/ARM64 16 KiB E2E report, legal approval
  status/verifier/CI 문서화

## 4. 고정 artifact

| 항목 | 값 |
|---|---|
| package | `@openai/codex@0.147.0-linux-arm64` |
| local binary | `.codex-artifacts/0.147.0/linux-arm64/codex` (gitignored) |
| binary SHA-256 | `e23d0be344d2496986c985cd3db61e6f649b1ddd900e6afc1b5aaabbffcbb4e2` |
| binary size | `222231296` bytes |
| ELF PT_LOAD | `[65536, 65536]` |
| schema SHA-256 | `f3dec1e031d99a420b137b903f02196d4325eece57620c925bb7130b25f168d2` |
| upstream source commit | `be6e8eac029b183056b7e4402879f15d2c85f61b` |

`rust-v0.147.0` tag가 unsigned이므로 engineering lock과 별개로 legal provenance Gate는 계속
`BLOCKED`다.

## 5. 최종 로컬 검증

| 범위 | 결과 |
|---|---|
| 영향 범위 targeted Gradle | `250/250 task PASS` |
| feature ON 전체 unit/APK/androidTest/lint/AAB | 최신 확장 실행 `1821/1821 task PASS`; 기존 focused `737/737 PASS` |
| feature ON AAB standalone 기준 | 기존 `343/343 task PASS`; 최종 AAB는 위 737-task run에 포함 |
| same-package feature OFF rollback 전체 회귀 | 최신 확장 실행 `1821/1821 task PASS`; 기존 focused `729/729 PASS` |
| controlled restart/orphan targeted | unit/compile `250 task PASS`, AndroidTest compile `218 task PASS`, 종료 보강 `240 task PASS` |
| Python unittest | completion baseline `140/140`; 최종 publication 확장 `148/148 PASS` |
| Pytest | completion baseline `147/147`; 최종 publication 확장 `155/155 PASS` |
| ON APK/AAB exact binary | `PASS`, version `0.147.0`, PT_LOAD `[65536,65536]` |
| rollback binary 금지 | `PASS` |
| APK 16 KiB zipalign | ON/rollback `PASS` |
| OAuth source/APK/AAB scan | approved OpenMinis debug 예외를 명시해 `PASS (75 files)` |
| Codex app-owned `client_id`/`client_secret` scan | production Codex modules/integrated main `0건` |
| fail-closed legal status | artifact lock 일치 `PASS`, review 상태는 의도대로 `BLOCKED` |
| ON/rollback signer 동일성 | `PASS` |
| default feature-OFF release | `595/595 task PASS`, Codex binary 부재 PASS |
| ON release / ON+pack-OFF fail-close | 둘 다 의도대로 `BLOCKED` |
| SDK publication / consumer matrix | `19/19`, `8/8` verifier PASS |
| Gradle 9 readiness | warning `0`, `gradle9_ready=true` |
| `git diff --check` | `PASS` |

최신 비파괴 검증 산출물:

| 파일 | SHA-256 |
|---|---|
| `/tmp/alpine-codex-final.apk` | `0398458cde1fd67ea347a359b964af1ff9eeb1da242bbab1e504388011bffec9` |
| `/tmp/alpine-codex-final-androidTest.apk` | `1fb5343881230248eb4c5a8ada29b2b4ec7264f2b178b2b0b7f10cb6094f6fe1` |
| `/tmp/alpine-codex-final.aab` | `b7174b252978fc7bc42c71e974d5d4abe6dc355b2a7cb669577b5477928e102f` |
| `/tmp/alpine-codex-rollback.apk` | `94ed8e17e1c259f576c9cab49d6e504865c49438977e689f4df3eb3b66395ca8` |
| `/tmp/alpine-codex-rollback-androidTest.apk` | `b99ecd2b115599d3793dc282e79568514763dc22d95081b6526d22dd71901605` |

ON/rollback signer SHA-256:
`feec8e9daf4e22af8fb2b7cd4e7ed1329968bc3ade106ba3b19e0849f6b296c5`

최신 ON 전수 검증의 첫 unlimited-worker 실행은 외장 볼륨에서
`:alpine-chat-feature:packageDebugAndroidTest` 패키징 task가 일시 실패했다. 해당 task 단독 `47/47` PASS 후
`--max-workers=2`로 ON/OFF 전체를 각각 `1821/1821` 재실행해 PASS했다. 제품 코드 결함은 재현되지 않았고
`scripts/release-local.sh`도 기본 worker 수를 이미 `2`로 제한한다.

## 6. Samsung 고정 상태

- 유일 대상: Samsung `SM-S931N`, serial `R3CY40PXCAP`
- 다른 연결 기기 `0123456789ABCDEF`는 절대 사용하지 않는다.
- 모든 명령은 `adb -s R3CY40PXCAP`을 명시한다.
- 최초 callback 실패 원인은 pinned 정적 Linux ARM64 binary가 Android의 CA bundle과 netd DNS를 직접
  발견하지 못한 호환성 결함이었다. Chrome이 다른 앱 뒤로 밀려 완료 tab의 callback 재개도 지연됐다.
- Android system CA 전용 PEM과 authenticated loopback CONNECT bridge를 추가한 뒤 기존 Chrome OAuth
  tab을 전면으로 복귀시켜 공식 callback을 완료했다.
- 공식 `account/read`는 `CHATGPT`, `model/list`는 non-empty이며 same-package upgrade/cold start와
  controlled App Server restart 뒤에도 유지된다.
- 공식 `account/read(refreshToken=true)` credential refresh, 2-turn, Stop/interrupt cleanup, next-turn
  no-replay, background/foreground, Activity recreation, Alpine Runtime state 격리가 모두 PASS했다.
- 최종 통합 6개 실기기 test는 `19.113s`, `OK (6 tests)`이며 종료 뒤 App Server child `0`, 앱 재실행
  뒤 child `1`로 duplicate/orphan 부재를 확인했다.
- Samsung 고정 runner 기본 비파괴 경로도 최종 PASS했다. 승인 argument 없이 logout class는 실제
  Samsung에서 skip됐고, 이어진 account/refresh probe `OK (3 tests)`로 로그인 유지가 재확인됐다.
- persistent store를 읽거나 변경하지 않는 전용 UI contract 계측으로 200% font action, Korean IME,
  system Back을 `OK (3 tests)`로 확인했다. 직후 official account/model/refresh `OK (2 tests)`와 앱 재실행
  child `1`/전체 `1`로 로그인·process ownership 보존을 재확인했다.
- 최종 AAB를 bundletool `1.18.3`으로 Samsung device spec(arm64-v8a/API 36/density 480)에 전달했다.
  download size `111,029,355` bytes, 설치 4 split 합계 `156,006,761` bytes이며 same-package split update
  뒤 account/model/refresh `OK (2 tests)`, 2-turn/Stop/restart/lifecycle `OK (4 tests)`가 PASS했다.
- 2026-08-15 13:31 KST 비파괴 전수 재검증에서 Samsung 고정 runner(account/model/refresh/turn/Stop/restart/
  lifecycle/Runtime/UI/accessibility/orphan/singleton)가 다시 PASS했다. 이어서 최종 AAB 4 split을 복원한 뒤
  account/model/refresh `OK (2 tests)`, turn/Stop/restart/lifecycle/UI `OK (7 tests)`, child `1`/전체 `1`을
  재확인했다. 설치 split 합계는 `156,006,761` bytes다.
- 현재 `/data` 여유 공간은 `159,164,616 KiB`라 저용량 조건을 만들지 않았고 `NOT_RUN`으로 유지했다.
- `/tmp/alpine-codex-final.apk`와 AndroidTest APK는 `install -r`로 갱신해 앱 데이터와 서명을 보존했다.
- 화면/Chrome URL/query, account, prompt, token, auth 파일 내용은 캡처하거나 출력하지 않는다.

승인 없이 하지 않을 작업:

- uninstall, `pm clear`, force-stop, process kill, reboot
- Doze 강제, 네트워크 차단, account 삭제, destructive storage test

## 7. 다음 작업 순서

1. 이미 PASS한 callback/account/model/refresh/2-turn/Stop/restart/lifecycle/runtime-isolation 결과를
   민감 정보 없는 executed report evidence로 고정한다.
2. 이미 실행된 10분 browser timeout·server cancel-once 결과를 redacted report evidence로 고정한다.
3. logout은 다른 계정/Provider data 보존과 orphan process를 확인해야 하므로 가장 마지막에 수행한다.
4. 명시 승인을 받은 경우에만 runner의 `--approve-account-logout`과
   `--approve-feature-off-rollback`을 함께 사용해 logout/data-isolation과 동일 서명 OFF 복귀를 연속
   검증한다. rollback만 단독 실행할 수 없고 완료 뒤 앱은 OFF 상태다.
5. redacted E2E report의 다음 18개 core 항목을 판정한다.
   - `install_upgrade`
   - `browser_login_callback`
   - `browser_cancel_timeout`
   - `model_list`
   - `first_turn`
   - `second_turn`
   - `stop_interrupt_once`
   - `next_turn_no_replay`
   - `background_foreground`
   - `rotation_recreation`
   - `cold_start_account_read`
   - `credential_refresh`
   - `controlled_restart`
   - `logout_data_isolation`
   - `no_orphan_process`
   - `runtime_isolation`
   - `redaction_audit`
   - `feature_off_rollback`
6. 미승인 process kill/Doze/network loss/force-stop 4개는 report에서 정확히 `NOT_RUN`으로 남긴다.
7. report verifier를 통과한 뒤에만 Phase 7 완료로 표시한다.

instrumentation은 app preferences에 영향을 줄 수 있으므로 실제 계정 manual E2E가 끝난 뒤 판단한다.

승인형 test harness는 이미 구현·컴파일됐다. 승인 argument가 없으면 live logout/rollback class는 skip되며,
runner는 raw instrumentation output을 저장·출력하지 않는다.

## 8. 배포 보류 결정과 기존 산출물

현재 제품 판정은 `INTERNAL_DEVELOPMENT_ONLY` / `NO_DEPLOYMENT_PLANNED`다.

- 소유자가 `2026-08-15 20:04 KST`에 GUI/UX 품질 미달을 이유로 배포 계획이 없음을 명확히 지시했다.
- `2026-08-15 13:39 KST` 기록의 `PROCEED_CURRENT_STATE`는 과거 결정이며 최신 지시로 대체되었다.
- `distribution/current-state-release-decision.json`은 과거 승인 증거다. 현재 배포 승인으로
  해석하거나 `--allow-current-state-release`를 사용하지 않는다.
- GitHub private Release `v0.3.0`과 signed APK/AAB는 이미 생성·게시된 **내부 검증 산출물**로만
  보존한다. 별도 지시 없이 삭제·교체·재게시하지 않는다.
- Play submission local package `dist/play-submission-v0.3.0/`는 참고 자료로만 보존하고,
  Play Console 계정 등록·AAB upload·테스트 트랙·공개 트랙을 진행하지 않는다.
- 신규 release build, tag, GitHub Release, Play 등록, 서명 자료 변경은 사용자의 새로운
  명시적 지시가 있을 때만 재개한다.

기존 내부 검증 산출물:

| 항목 | 값 |
|---|---|
| source | `v0.3.0@a4ccd4ff56338511a63ff75734d53695e285e5e5` |
| signed APK SHA-256 | `6173d23517ba6a2715098022c145cebb6c6b08193c01d6df8204f1eee0e263d6` |
| signed AAB SHA-256 | `b022a218d02eb7ba3d767014030239399dcd97b02f85768ad5b268927c6a4358` |
| signing certificate SHA-256 | `32364f692c1b3151a409720fcd3e6f86d17658bf7e60e90e15fa2955d4f1fcdd` |
| private GitHub Release | `https://github.com/coreline-ai/alpine-llm-gateway/releases/tag/v0.3.0` |
| release source CI | run `31866945292` `SUCCESS` |
| publication evidence CI | `main@f26931a`, run `31875767445` `SUCCESS` |

산출물 존재는 현재 배포 의사를 의미하지 않는다. readiness verifier는 계속
`release_ready=false`를 유지하며 미실행 검증은 `NOT_RUN`, 미승인 review는 `BLOCKED`로 보존한다.

미완료 증거 항목:

- `CODEX_ACCOUNT_E2E_REQUIRED` — 핵심 login/chat/lifecycle와 timeout은 PASS, logout/data isolation·
  feature-OFF 실기기 rollback의 마지막 순서와 executed report 고정이 남음
- `CODEX_16K_DEVICE_E2E_REQUIRED` — 지정 Samsung은 4 KiB라 별도 ARM64 16 KiB 기기 필요
- `CODEX_DISTRIBUTION_LEGAL_REVIEW_REQUIRED` — unsigned upstream tag/provenance 검토 필요

법무 reviewer는 `/Volumes/Eprojects/project_202607/alpine-llm-gateway/distribution/codex-appserver-legal-status.json`의
artifact lock 정합성을 유지한 채 모든 결정을 승인하고 approval reference를 기록해야 한다. 단순
LICENSE/NOTICE/SBOM 존재나 readiness JSON 수동 변경만으로는 Gate가 READY가 되지 않는다.

현재 내부 개발 상태에서 남아 있는 증거:

- Samsung 파괴 테스트 승인
- 프로젝트 LICENSE / exact corresponding source
- Provider approval / Play track / release destination owner가 필요한 작업은 배포 재개 전까지 보류
- guest SIGWINCH 반복 stress 환경

`x86_64 emulator`는 arm64-only Codex release 필수 조건이 아니다. x86_64 ABI를 지원할 때만 별도
artifact/test 계획으로 진행한다.

## 9. 현재 판정

- 로컬 구현·회귀·ON/OFF artifact: `PASS`
- Samsung 공식 callback/account/model/refresh/chat/Stop/restart/lifecycle/runtime isolation: `PASS`
- Samsung Phase 7 전체 report: `IN_PROGRESS` — logout/data isolation, feature-OFF rollback과 redacted
  executed report를 계정 검증의 마지막 순서로 실행해야 함
- Samsung 4 KiB AAB device delivery/실제 실행: `PASS`; 저용량 설치·rollback 측정은 pending
- 16 KiB 실제 기기 및 legal provenance: `BLOCKED`
- 제품 단계: `INTERNAL_DEVELOPMENT_ONLY`
- 배포 계획: `NO_DEPLOYMENT_PLANNED`
- 배포 중단 사유: GUI/UX 품질이 배포 기준에 미달
- 기존 `v0.3.0` private GitHub Release/signed artifact: 내부 검증 이력으로만 보존
- 다음 제품 우선순위: GUI/UX 현황 감사 → 디자인 방향 확정 → 화면 구조/상태 흐름 개편 →
  Samsung 실기기 사용성·접근성·회귀 QA

요구사항별 완료 근거와 “현재 환경에서 구현 가능”/“승인·외부 입력 필요” 경계는
[`docs/codex-appserver-completion-audit-20260815.md`](docs/codex-appserver-completion-audit-20260815.md)에
고정했다. 현재 남은 항목은 원인 미상의 대기가 아니라 실제 로그인 삭제·OFF 설치 승인, ARM64 16 KiB
실행 환경과 legal/provenance 입력이 필요한 증거 Gate다. 이 Gate들은 배포 재개 요건으로만
보존하며, 현재 우선순위는 GUI/UX 개선이다.
