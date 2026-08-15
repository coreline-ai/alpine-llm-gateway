# Codex App Server · AnyClaw 적용 정밀 분석

분석 일시: `2026-08-15 KST`

대상 프로젝트: `alpine-llm-gateway`

AnyClaw 기준 커밋: `8c331115cf9db2b45bff52112a218c81086605f7`

## 1. 결론

AnyClaw의 **Codex가 소유하는 ChatGPT browser login** 아이디어는 적용할 수 있다. 다만 AnyClaw 전체
구조나 `codex login` stdout parsing, token 복제, OpenClaw gateway 우회는 반입하면 안 된다. OpenAI 공식
`codex app-server`의 stable stdio 계약을 직접 사용해야 한다.

권장 제품 형태는 기존 `ProviderType.CODEX` direct OAuth profile을 변형하는 것이 아니라, 별도의
**`Codex Agent (ChatGPT 로그인)` 연결**을 추가하는 것이다. App Server는 단순 Chat Completions provider가
아니라 thread/turn/tool/approval을 가진 agent이므로 기존 Responses API backend처럼 표현하면 의미와
수명주기, 자동 재시도에서 오류가 생긴다.

권장 실행 topology는 다음과 같다.

1. checksum-pinned Linux arm64 musl Codex binary를 전용 artifact pack에 넣는다.
2. `jniLibs/arm64-v8a/libcodex_app_server.so`로 패키징하고 검증된
   `ApplicationInfo.nativeLibraryDir`에서 shell 없이 직접 실행한다.
3. `CODEX_HOME`은 `noBackupFilesDir` 아래 전용 경로로 격리한다.
4. stdio JSONL App Server process 하나를 앱 단위로 관리한다.
5. direct 실행의 Android CA/DNS 차이는 system-only CA bundle과 official-domain/443로 제한된 authenticated
   loopback CONNECT bridge로 해결한다. 이는 임의 fallback이 아니라 필수 process 경계이며, 실패하면
   fail-closed하고 Alpine/Provider/PRoot로 전환하지 않는다.

기존 `AlpineRuntimeManager` session 공유안은 **기각**한다. 현재 Runtime은 active session 하나를 소유하고
`IntegratedAlpineLlmHost`가 start/restart/stop을 관리하므로, 공유 시 workspace/Gateway 작업이 Codex
로그인 또는 채팅 process를 종료시킬 수 있다.

## 2. 검토 기준과 증거

### 2.1 현재 프로젝트

| 항목 | 확인 결과 | 설계 영향 |
|---|---|---|
| Android SDK | `compileSdk 36`, `targetSdk 36`, `minSdk 26` | AnyClaw의 `targetSdk 28` 우회 금지 |
| ABI | integrated app은 `arm64-v8a` 고정 | 최초 pack은 arm64만 포함 |
| Native packaging | AGP `8.10.1`, `useLegacyPackaging = true` | 기존 PRoot와 같은 추출 실행 경로를 spike에서 검증 |
| Backup/share | `allowBackup=false`; FileProvider는 cache workspace share만 노출 | no-backup CODEX_HOME 격리 가능 |
| Runtime | `execute`는 one-shot, `openTerminal`은 PTY | App Server process를 Runtime public API에 추가하지 않음 |
| Runtime ownership | active session 1개 | Codex와 Alpine guest 공유 금지 |
| Chat contract | `stream(requestJson)` | conversation id용 additive contextual interface 필요 |
| Chat correction | 조건 위반 시 자동 correction turn | Codex Agent에서는 additive policy로 비활성화 |
| Stop | coroutine 취소 중심 | `turn/interrupt` 정확히 1회 필요 |

### 2.2 OpenAI 공식 App Server

- `codex app-server`는 rich client 통합 interface이며 기본 transport는 stdio JSONL이다.
- WebSocket은 experimental/unsupported이므로 production 범위에서 제외한다.
- 연결마다 `initialize`와 `initialized`가 선행되어야 한다.
- stable schema는 Codex version별 `generate-json-schema`로 생성한다. experimental API는 쓰지 않는다.
- `account/login/start(type=chatgpt)`는 `authUrl`과 `loginId`를 구조화해 반환하고 App Server가 localhost
  callback, token 저장, refresh를 소유한다.
- browser callback가 불가능하면 공식 `chatgptDeviceCode` flow를 쓸 수 있다.
- 대화는 `thread/start|resume`과 `turn/start|interrupt`이며 command/file/tool/approval event도 발생할 수 있다.
- file credential store의 `CODEX_HOME/auth.json`은 plaintext token cache다. no-backup app sandbox는
  노출면을 줄이지만 Android Keystore와 동등하지 않다.

### 2.3 AnyClaw 고정 커밋

참고할 아이디어는 네 가지뿐이다.

| 분류 | 판단 | 이유 |
|---|---|---|
| Codex가 OAuth callback/token refresh 소유 | 반입 | 앱 소유 OAuth registration 제거의 핵심 |
| 외부 browser login | 반입 | WebView credential capture 방지 |
| device-code 대체 flow | 공식 App Server 방식만 반입 | callback 실패 대응 |
| Android arm64 process 실행 가능성 | spike 근거로만 사용 | SDK·network·packaging 조건이 다름 |

반입 금지 항목은 다음과 같다.

| AnyClaw 구현 | 위험 | 현재 프로젝트 정책 |
|---|---|---|
| `codex login` stdout auth URL regex | 비계약 출력·URL log 노출 | 구조화된 login RPC만 사용 |
| `.codex/auth.json` parsing/token 복제 | credential 경계 붕괴·refresh race | App Server 외부에서 읽지 않음 |
| OpenClaw auth profile token 복사 | credential 중복·logout 불일치 | 미사용 |
| gateway `auth.mode=none` | 로컬 공격면 확대 | 미사용 |
| insecure apt/unauthenticated install | 공급망 검증 상실 | 미사용 |
| `@latest` install | 비재현 build·schema mismatch | version/SHA/schema 고정 |
| `targetSdk = 28` | Play/보안 요구 위반 | `targetSdk 36` 유지 |
| cleartext dashboard/full auto-approval | 공격면·agent 부작용 | stdio와 격리 workspace 사용 |

AnyClaw는 Codex `0.104.0`의 예전 package layout을 전제로 한다. 현재 감사한 `0.147.0`의 binary path는
`vendor/aarch64-unknown-linux-musl/bin/codex`이므로 hard-coded path도 재사용할 수 없다.

## 3. Pinned artifact 정적 감사

분석용으로 `@openai/codex@0.147.0-linux-arm64` npm tarball을 정적 확인했다. 아래 값은 계획 기준선이며,
구현 시작 시 최신 stable을 재선정해 lock한다.

| 항목 | 확인값 |
|---|---|
| npm package | `@openai/codex@0.147.0-linux-arm64` |
| license metadata | `Apache-2.0` |
| binary | `vendor/aarch64-unknown-linux-musl/bin/codex` |
| file type | ELF 64-bit, ARM aarch64, statically linked, stripped |
| binary size | `222,231,296` bytes |
| gzip -9 참고 크기 | `91,278,003` bytes |
| SHA-256 | `e23d0be344d2496986c985cd3db61e6f649b1ddd900e6afc1b5aaabbffcbb4e2` |
| ELF LOAD alignment | `0x10000`(64 KiB)로 정적 확인 |

주의 사항:

- ELF 정렬만으로 16 KiB 대응이 끝나지 않는다. 최종 APK/AAB zip alignment와 실제 16 KiB 실행이 필요하다.
- platform tarball에는 LICENSE/NOTICE 원문이 보이지 않았다. OpenAI Codex의 해당 tag/commit LICENSE·NOTICE를
  release notice/SBOM에 포함하고 법적 검토해야 한다.
- `codex-code-mode-host`, `rg`, `bwrap`은 필요성이 입증되기 전 pack에 넣지 않는다.
- 약 91 MB 압축 증가가 예상되므로 bundletool download/install-size와 저용량 설치 검증이 필요하다.

## 4. 최종 권장 아키텍처

### 4.1 모듈 경계

| 신규 단위 | 책임 | 금지 경계 |
|---|---|---|
| `alpine-codex-appserver-pack-android` | binary, schema, checksum, SBOM, notice | runtime download, `latest`, public Maven 자동 배포 |
| `alpine-codex-appserver-android` | process, JSONL, protocol, auth, lifecycle | Provider token store, shell, Alpine session 공유 |
| `alpine-chat-backend-codex` | thread/turn을 chat contract로 정규화 | raw RPC/credential UI 전달 |
| integrated composition | direct와 synthetic Codex Agent 연결 병합 | ProviderProfile schema/credential 변경 |

대용량 binary pack은 최초에는 integrated app 내부 dependency로만 둔다. 기존 SDK publication에 자동 포함하지
않고 배포·라이선스 Gate가 닫힌 뒤 공개 artifact 정책을 별도로 결정한다.

### 4.2 기존 ProviderProfile을 유지하는 이유

기존 `ProviderProfile`은 authorization/token/inference endpoint와 client ID가 필수인 direct OAuth 계약이다.
이를 nullable/union으로 바꾸면 JSON migration, cache key, CRUD, token alias가 모두 영향을 받는다. 따라서
기존 Codex direct profile은 그대로 보존하고 App Server connection을 별도로 합성한다.

- synthetic id 예: `managed:codex-app-server`
- 표시명: `Codex Agent (ChatGPT 로그인)`
- 앱의 단일 `CODEX_HOME`/account만 지원
- model은 `model/list`에서만 선택하고 unavailable model로 silent switch하지 않음
- generic Provider screen/token store를 쓰지 않는 별도 account controller/UI
- direct Provider들과 credential lifecycle 완전 분리

### 4.3 Process와 storage

- executable은 `ApplicationInfo.nativeLibraryDir/libcodex_app_server.so`로만 resolve한다.
- canonical path, regular/executable, size cap, ABI, whole-file SHA-256을 시작 전에 확인한다.
- shell 없이 `binary app-server --listen stdio://` argv로 시작한다.
- 환경은 allowlist로 구성하고 `CODEX_HOME`, `HOME`, private temp, locale만 명시한다.
- stdout은 protocol 전용, stderr는 bounded/redacted diagnostic 전용이다.
- CODEX_HOME은 `noBackupFilesDir/codex-app-server`에 두고 FileProvider 비노출을 검증한다.
- 앱 singleton process에서 bounded queue/line, request correlation, timeout, exactly-once completion,
  graceful close 후 강제 종료를 구현한다.

### 4.4 Chat/대화 계약

기존 interface를 깨지 않고 additive 계약만 추가한다.

- `ContextualChatBackendSession`: `conversationId`, request JSON, user action id를 전달한다.
- `ChatBackendRequestPolicy`: App Server의 automatic correction/replay를 금지한다.
- 기존 backend는 현재 interface와 behavior를 유지한다.
- App Server는 `(syntheticProfileId, conversationId) -> threadId`를 bounded app-private store에 저장한다.
- 첫 전송은 `thread/start`, 이후는 `thread/resume` 확인 후 `turn/start`를 사용한다.
- mapping 유실/resume 실패 시 history를 몰래 재전송하거나 새 thread로 위장하지 않고 fail-closed 한다.
- Stop/cancel은 `turn/interrupt`를 1회만 보내고 bounded completion을 기다린다.
- partial delta 뒤 EOF는 성공이 아니다.

conversation 삭제와 App Server thread 삭제는 v1에서 자동 결합하지 않는다. mapping은 LRU/개수 제한으로
정리하고 명시적 archive/delete UX는 후속 범위로 둔다.

### 4.5 Agent 안전 경계

- `FAST_CHAT` 위치에서만 사용하고 `ALPINE_WORKSPACE` routing에는 연결하지 않는다.
- 전용 빈 app-private workspace를 사용하며 사용자 workspace를 bind하지 않는다.
- stable schema만 사용하고 `experimentalApi`는 켜지 않는다.
- `sandbox=read-only`, `approvalPolicy=never`를 기본으로 고정한다.
- approval/user-input request는 자동 승인하지 않고 stable unsupported 응답으로 거절한다.
- command/file/tool event는 채팅 text로 가장하지 않고 예상하지 않은 side-effect event는 turn을 실패시킨다.

`approvalPolicy=never`가 명령 실행 자체를 금지하는 것은 아니다. 따라서 빈 격리 workspace와 no shared Runtime가
핵심 방어선이다. 실제 workspace agent와 approval UI는 별도 계획이 필요하다.

## 5. 인증과 lifecycle

- 시작 시 `account/read(refreshToken=false)`만 수행하고 pending login/turn을 replay하지 않는다.
- auth URL은 HTTPS, no userinfo, default port, bounded length, versioned exact-host allowlist를 검증한다.
- Activity background 중 callback process 생존을 먼저 bound lifecycle로 검증하며 증거 없이 FGS를 넣지 않는다.
- browser callback 실패 시 공식 device-code flow만 쓴다.
- logout 후 `account/read`로 확인하며 다른 Provider/data는 삭제하지 않는다.
- `auth.json` 내용을 Android 코드가 읽지 않는 것을 source/APK verifier로 강제한다.

## 6. 회귀 0 지향 적용 순서

“회귀 0”은 무결함 보장이 아니라 기존 동작의 observable diff를 Gate로 차단한다는 뜻이다.

1. **Artifact/process slice**: 신규 pack/process/protocol만 추가, UI·routing 미연결
2. **Fake contract slice**: contextual session/policy와 synthetic connection, feature OFF
3. **Product slice**: auth UI와 FAST_CHAT 연결, 전체 자동 검증 후 Samsung에서만 ON

각 slice는 독립 commit/revert 가능 상태로 유지한다.

- 기존 direct profile JSON/token/cache behavior 변경 0건
- direct·Alpine golden test 변경 0건, test 삭제/skip 증가 0건
- App Server silent fallback/automatic replay 0건
- Runtime public ABI/API 변경 0건
- feature OFF build의 UI/state/route diff 0건
- source/APK secret·forbidden-pattern scan PASS
- final APK/AAB ABI, 16 KiB, size, install/upgrade/rollback PASS

## 7. Verifier 변경 원칙

공식 Codex binary 내부의 endpoint 문자열이 기존 OAuth verifier에 오탐될 수 있으므로 경계를 분리한다.

1. Android source, DEX, resource, config에는 기존 금지 규칙을 유지한다.
2. 정확히 lock된 `libcodex_app_server.so`만 whole-file SHA-256이 맞을 때 opaque artifact로 취급한다.
3. 다른 entry에 동일 문자열이 있으면 실패한다.
4. auth file parsing/copy, OpenClaw profile, `auth.mode=none`, insecure apt, `@latest`, target downgrade,
   global cleartext를 negative fixture로 추가한다.

## 8. 구현 전 Blocking Gate

| Gate | 현재 상태 | 닫는 방법 |
|---|---|---|
| Samsung target 36 direct exec | `PASS` | pinned nativeLibraryDir process 실기기 확인 |
| Samsung DNS/TLS/model list | `PASS` | system CA/DNS bridge + official account/model 실기기 확인 |
| browser localhost callback/background | `PASS` | 공식 callback과 background/foreground·recreation 실기기 확인 |
| plaintext credential 위험 수용 | `CLOSED` | App Server-only, app-private noBackup 경계 채택 |
| stable schema/version | `LOCKED` | `0.147.0`/source commit/schema/checksum 고정 |
| license/notice/SBOM | `BLOCKED` | source tag provenance와 법적 검토 |
| 16 KiB 실제 실행 | `NOT_RUN` | Samsung 16 KiB/Remote Test Lab/ARM64 emulator |
| AAB size/install | `PARTIAL PASS` | Samsung 4 KiB split size/install/update는 PASS; 저용량·OFF rollback은 승인 뒤 실행 |

`x86_64` emulator는 arm64-only 제품의 필수 조건이 아니다. 16 KiB 검증은 Samsung 16 KiB 기기/Remote Test
Lab 또는 ARM64 16 KiB 환경으로 충족할 수 있다. 현재 Samsung이 4 KiB이면 별도 16 KiB 환경은 필요하다.

## 9. 배포 가능성 판단

공식 App Server가 Samsung E2E를 통과하면 **Codex에 한해** 앱 소유 OAuth client 등록 없이 ChatGPT login을
제공할 수 있다. 그러나 다른 Provider 승인, root license, Alpine corresponding source, Play 트랙/정책,
서명/배포 목적지, Codex notice/SBOM은 자동 해결되지 않는다. Codex-only gate와 전체 release gate를 분리하고
Codex PASS만으로 `external_distribution_ready=true`로 바꾸면 안 된다.

## 10. 최종 제안

- 별도 synthetic Codex Agent 연결
- 별도 native process와 isolated CODEX_HOME
- additive chat context/policy interface만 추가
- FAST_CHAT-only, empty private workspace, no experimental API
- artifact/process → fake contract → Samsung product 연결 순서
- 모든 구현은 Gate 통과 전 feature OFF

이 구조가 현재 코드에서 가장 작은 회귀면과 가장 명확한 rollback 경계를 제공한다.

## 11. 구현 반영 상태 — 2026-08-15

Revision 2 구조는 다음 모듈로 구현됐다.

- `alpine-codex-appserver-pack-android`: feature OFF 기본의 pinned arm64 artifact pack
- `alpine-codex-appserver-android`: direct stdio process, bounded JSONL RPC, managed auth, thread/turn API
- `alpine-chat-backend-codex`: synthetic FAST_CHAT session, 최대 256개 thread link, fail-closed agent event
- `integrated-app`: application-owned process/auth lifecycle, separate Codex debug package, 전용 account UI,
  동일 package feature-OFF rollback build

Samsung `SM-S931N`에서 exact binary start/initialize, 공식 browser callback, `account/read=CHATGPT`, model
list, explicit credential refresh, 2-turn, Stop/next-turn no-replay, controlled restart, background/foreground,
Activity recreation, Alpine Runtime state isolation과 orphan/duplicate audit까지 확인했다. 최초 callback 실패는
정적 Linux binary가 Android CA bundle과 netd DNS를 발견하지 못한 것이 원인이었으며, system-only CA PEM과
authenticated loopback CONNECT bridge로 수정했다. Chrome 완료 tab이 다른 앱 뒤에 있어 callback 재개가
지연된 조건도 전면 복귀로 해소했다.
추가 비파괴 UI contract 계측에서 200% font action, Korean IME, system Back/accessibility 3건도 PASS했고,
persistent Provider/conversation/workspace/Codex credential store는 읽거나 변경하지 않았다.

Phase 7에는 logout/data isolation, same-package feature-OFF 실기기 rollback과 최종 redacted report 고정이
남아 있다. 현재 기기는 4 KiB page이므로 16 KiB device E2E, unsigned source tag의 legal/provenance 승인도
별도 release blocker로 유지한다. 구현·빌드·Samsung 절차는
[`docs/codex-appserver-integration.md`](codex-appserver-integration.md)를 정본으로 사용한다.
요구사항별 완료 판정과 외부 입력 경계는
[`codex-appserver-completion-audit-20260815.md`](codex-appserver-completion-audit-20260815.md)를 따른다.
