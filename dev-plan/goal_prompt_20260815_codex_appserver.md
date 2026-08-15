# Codex App Server 구현 Goal 실행 프롬프트

아래 프롬프트를 새 Codex 작업에 그대로 붙여 넣으면 된다. 이 프롬프트를 실행하는 것은 v1에서 OpenAI
공식 file credential store를 Android app-private/no-backup 경계 안에서 사용하는 보안 결정을 승인한다는
의미다. Android 코드는 credential 파일 내용을 읽거나 복사하지 않아야 한다.

---

## 붙여 넣을 프롬프트

```text
다음 작업을 장기 실행 Goal로 생성하고, 계획의 Phase 순서를 지키면서 구현 완료까지 진행해줘.
명시적인 token budget은 설정하지 마.

## Goal objective

/Volumes/Eprojects/project_202607/alpine-llm-gateway 프로젝트에 OpenAI 공식 Codex App Server 기반
`Codex Agent (ChatGPT 로그인)` 기능을 구현한다.

AnyClaw의 아이디어 중 Codex-managed ChatGPT browser login만 참고하고, 기존 Provider OAuth·Alpine
Runtime·채팅·workspace의 observable regression 없이 feature flag 뒤에서 통합한다. 현재 환경에서 가능한
코드·자동 테스트·artifact 검증을 모두 완료하고, Samsung 실기기 또는 외부 계정이 필요한 단계는 준비와
검증 절차까지 완성한 뒤 실제 실행 가능 시 계속 수행한다.

## 작업 위치

- Repository: /Volumes/Eprojects/project_202607/alpine-llm-gateway
- Branch: 현재 branch를 우선 사용한다. 새 branch가 필요하면 `codex/` prefix를 사용한다.
- 기존 사용자 변경을 reset, checkout, clean, stash, 덮어쓰기 하지 않는다.
- 시작 시 `git status --short --branch`와 최근 commit을 확인하되 dirty 상태 자체를 이유로 작업을 중단하지
  않는다. 이번 작업 파일과 기존 사용자 변경을 구분한다.

## 반드시 먼저 읽을 정본

1. /Volumes/Eprojects/project_202607/alpine-llm-gateway/dev-plan/implement_20260815_072222.md
2. /Volumes/Eprojects/project_202607/alpine-llm-gateway/docs/codex-appserver-anyclaw-analysis.md
3. /Volumes/Eprojects/project_202607/alpine-llm-gateway/docs/provider-oauth-adapters.md
4. /Volumes/Eprojects/project_202607/alpine-llm-gateway/docs/alpine-runtime-host-integration.md
5. /Volumes/Eprojects/project_202607/alpine-llm-gateway/docs/sdk-publication-and-distribution.md
6. /Volumes/Eprojects/project_202607/alpine-llm-gateway/distribution/release-readiness.json

구현 전 공식 최신 계약도 다시 확인한다.

- OpenAI Codex App Server 공식 README
- OpenAI Codex authentication 공식 문서
- OpenAI Codex stable release/package metadata
- Android 16 KiB page-size와 Google Play target API 공식 문서

기술 질문에 web 검색이 필요하면 공식 OpenAI, Android, GitHub source 같은 primary source만 사용한다.
AnyClaw은 반드시 commit `8c331115cf9db2b45bff52112a218c81086605f7` 기준으로만 비교한다.

## 승인된 v1 결정

- 기존 `ProviderProfile`, direct OAuth JSON, token store, session cache schema를 변경하지 않는다.
- 별도 synthetic connection id를 가진 `Codex Agent (ChatGPT 로그인)` backend로 구현한다.
- 기존 Alpine Runtime active session과 Codex process를 공유하지 않는다.
- pinned arm64 musl Codex binary를 별도 artifact pack에 넣고
  `ApplicationInfo.nativeLibraryDir/libcodex_app_server.so`에서 shell 없이 직접 실행한다.
- direct process가 Samsung에서 DNS/TLS/callback에 실패해도 proxy, PRoot, direct Provider로 자동 fallback하지
  않는다. stable error로 중단하고 별도 ADR 필요 항목으로 기록한다.
- App Server transport는 stable stdio JSONL만 사용한다. WebSocket/experimental API는 금지한다.
- v1은 FAST_CHAT-only다. ALPINE_WORKSPACE routing과 사용자 workspace를 연결하지 않는다.
- 전용 빈 app-private workspace, `sandbox=read-only`, `approvalPolicy=never`를 사용한다.
- approval, user-input, command, file, tool side-effect event를 자동 승인하거나 chat text로 가장하지 않는다.
- App Server account는 단일 CODEX_HOME/account만 지원하고 model은 `model/list`에서 가져온다.
- v1 credential store는 공식 Codex file store 사용을 승인한다. CODEX_HOME은
  `noBackupFilesDir/codex-app-server`로 제한하고 password와 동일하게 취급한다.
- Android/Kotlin 코드는 auth.json을 열거나 parsing, copy, export, backup, share, log하지 않는다.
- 기존 ChatBackendSession과 Runtime public API/ABI는 깨지 않는다. 필요한 chat context/policy는 additive
  interface로 추가한다.
- feature flag 기본값은 OFF다. 전체 local regression과 Samsung non-destructive E2E 통과 전 release에서
  ON하지 않는다.
- x86_64 ABI/emulator를 arm64-only release에 억지로 추가하지 않는다. 16 KiB는 Samsung 16 KiB,
  Samsung Remote Test Lab 또는 ARM64 16 KiB 환경으로 검증한다.

## 절대 반입 금지

- AnyClaw/OpenClaw source 복사 또는 fork merge
- `codex login` stdout auth URL regex parsing
- `.codex/auth.json` 또는 access/refresh token parsing·복제
- OpenClaw auth profile과 auth-none/device-auth-disable gateway
- insecure apt, unauthenticated install, `@latest` runtime dependency
- targetSdk 하향, global cleartext, app-data direct executable, shell command 조립
- raw token, auth URL query, callback code, account email/ID, prompt, RPC/Provider body logging
- 자동 turn replay, automatic correction, silent backend/model switch, automatic approval
- App Server 실패 후 direct/Alpine/proxy fallback

## 구현 순서

dev-plan의 Phase 0부터 Phase 8까지 순서를 따른다. 각 Phase에서 다음을 반드시 수행한다.

1. 해당 Phase 태스크를 작은 유지보수 단위로 구현한다.
2. 자체 테스트를 실행하고 실패를 같은 Phase에서 수정한다.
3. 발견 이슈와 해결 내용을 계획 문서의 `이슈 및 수정`에 기록한다.
4. 실제 완료한 체크박스만 `[x]`로 업데이트한다.
5. 테스트가 통과하기 전에 다음 Phase로 넘어가지 않는다.

Rollback slice는 다음 순서를 유지한다.

- Slice A: artifact pack + process/protocol, UI/routing 미연결
- Slice B: fake-backed contextual contract + synthetic connection, feature OFF
- Slice C: auth UI + FAST_CHAT 연결, Samsung PASS 뒤 활성화

각 Slice는 독립적으로 revert 가능한 commit 경계를 유지한다. 사용자가 명시하지 않은 squash, force-push,
history rewrite는 하지 않는다.

## 필수 구현 결과

### Artifact/process

- `alpine-codex-appserver-pack-android`
- `alpine-codex-appserver-android`
- exact stable version/source commit/package origin/SHA-256/ABI/schema lock
- LICENSE/NOTICE/SBOM/provenance
- canonical nativeLibraryDir path, regular/executable/size/SHA/ABI preflight
- allowlisted environment와 noBackup CODEX_HOME/HOME/temp
- process singleton, initialize-once, graceful close, bounded forced termination, orphan 방지

### Protocol/auth

- bounded persistent stdin/stdout/stderr
- fragmented/multiple/oversized/non-JSON frame 처리
- request id correlation, timeout/cancel/exit/close race exactly-once
- account read/login start/login complete/cancel/logout/device-code 상태기계
- structured HTTPS auth URL exact-host 검증과 external browser
- lifecycle recreation/cold-start에서 action replay 금지
- error는 credential 없는 stable code로만 노출

### Chat/composition

- `alpine-chat-backend-codex`
- additive contextual backend session과 request policy
- `(syntheticProfileId, conversationId) -> threadId` bounded private mapping
- `thread/start|resume`, `turn/start|interrupt`
- agent message delta/completed만 chat event로 정규화
- Stop/coroutine cancellation 시 interrupt 정확히 1회
- thread mapping 유실/resume 실패 시 history를 몰래 재전송하지 않고 fail-closed
- direct connection과 synthetic Codex connection을 integrated composition root에서 병합
- FAST_CHAT-only login/status/model UI와 feature OFF 회귀 보장

### Verification

- exact checksum Codex binary만 opaque approved artifact로 처리
- source/DEX/resource/다른 APK entry에는 기존 OAuth 금지 검사를 유지
- AnyClaw 금지 marker negative fixtures
- schema/version/checksum/ABI/license/notice/SBOM verifier
- ELF와 APK/AAB 16 KiB alignment 검사
- binary 포함 AAB size/install/update/rollback 검사
- 기존 Provider OAuth, Runtime, Gateway, workspace, direct/Alpine chat 전체 회귀

## 회귀 acceptance

- 기존 ProviderProfile JSON/token alias/session cache behavior 변경 0건
- 기존 Runtime public API/ABI diff 0건
- direct/Alpine golden test의 승인 없는 변경 0건
- test 삭제 또는 skip 증가 0건
- feature OFF의 UI/state/route observable diff 0건
- silent fallback, automatic replay/correction/approval 0건
- partial delta + EOF를 성공 처리하는 경로 0건
- source/APK/AAB secret 또는 copied client fingerprint 0건
- logout이 다른 Provider/data를 변경하는 경로 0건

## 테스트 원칙

- 먼저 현재 repository의 기존 검증 script와 Gradle task를 찾아 실제 baseline을 기록한다.
- 작은 unit/contract test부터 실행하고 마지막에 clean full regression을 실행한다.
- 실패를 기존 문제라고 단정하지 말고 이번 diff와 baseline을 비교해 원인을 확인한다.
- test count 감소, skip 추가, assertion 완화, verifier 예외 확대 방식으로 통과시키지 않는다.
- 대용량 Codex binary 내부 문자열 때문에 verifier가 오탐하면 whole-file checksum이 맞는 정확한 entry만
  예외 처리한다. source/DEX/resource 예외는 만들지 않는다.
- build/test output에는 secret/raw auth URL/prompt가 없어야 한다.

## Samsung 원칙

- 연결된 Samsung arm64를 우선 사용한다.
- 기본 E2E: install/upgrade → login → model list → 2-turn → Stop → next turn → restart → logout.
- browser의 실제 계정 입력은 사용자가 직접 수행하게 하고 credential을 요청하거나 읽지 않는다.
- rotation, recreation, background/foreground, cold start는 non-destructive 범위에서 진행한다.
- process kill, Doze, network loss, force-stop, data clear 같은 파괴 테스트는 실행 직전에 사용자 승인을 받는다.
- evidence에는 build SHA, Samsung model, OS/API, state/result만 기록한다.
- Samsung이 4 KiB이면 16 KiB release test는 Remote Test Lab 또는 ARM64 16 KiB 환경으로 별도 수행한다.

## 막힘 처리

- 먼저 코드, 문서, local tool, 공식 문서로 스스로 해결한다.
- 동일 blocking condition이 반복되기 전에는 Goal을 blocked로 표시하지 않는다.
- 사용자 account/browser action 또는 파괴 테스트 승인만 남으면 필요한 행동을 한 문장으로 정확히 요청한다.
- network/DNS sandbox 실패가 실제 구현에 중요하면 승인된 escalation 절차를 사용한다.
- provider 계정, Samsung 연결, legal/Play 같은 외부 조건이 없으면 상태를 `NOT_RUN` 또는 `BLOCKED`로
  정확히 남기고 통과했다고 쓰지 않는다.
- 구현과 local acceptance가 끝나지 않았으면 token/시간이 많이 들었다는 이유로 Goal을 complete 처리하지 않는다.

## Git 규칙

- 관련 파일만 stage한다.
- credential, auth cache, downloaded secret, local device evidence 원본을 commit하지 않는다.
- commit 전 `git status`, `git diff --check`, staged diff, secret scan을 검토한다.
- 각 Slice마다 의미 있는 commit message와 실행한 test 결과를 기록한다.
- 사용자 요청 없이 push, force-push, tag, release, Play upload는 하지 않는다.

## 보고 형식

진행 보고는 다음 순서로 간결하게 작성한다.

1. 현재 Phase/Slice
2. 변경 파일과 동작
3. 실행한 테스트와 PASS/FAIL/NOT_RUN
4. 발견 리스크와 수정
5. 다음 단계 또는 필요한 사용자 행동

최종 보고에는 다음을 포함한다.

- Phase별 완료 상태
- 구현된 module/contract/UI 요약
- 전체 test 명령과 결과
- Samsung/16 KiB/AAB evidence 상태
- 남은 외부 release blocker
- commit 목록과 rollback 방법
- 변경된 개발 계획/분석/README/runbook/readiness 문서 경로

Goal은 코드·자동 테스트·문서가 실제 완료되고 필수 acceptance가 통과한 경우에만 complete로 표시한다.
Samsung 실계정 E2E 같은 objective 내 필수 항목이 남으면 complete로 과장하지 말고 사용자 action을 요청해
계속 이어서 수행한다. 다른 Provider 승인, root project license, Alpine corresponding source, Play 계정/트랙은
본 Codex 구현과 분리해 readiness blocker로 유지한다.

지금 Goal을 생성하고 Phase 0 확인부터 시작해줘.
```
