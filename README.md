<div align="center">
  <a href="https://github.com/coreline-ai/alpine-llm-gateway">
    <img src="branding/alpine-fold-play-512.png" width="112" alt="Alpine AI Workspace icon">
  </a>

  <h1>Alpine AI Workspace</h1>

  <p><strong>Android 외부 LLM 채팅과 로컬 Alpine Linux 작업 환경을 하나의 모듈형 워크스페이스로 결합합니다.</strong></p>
  <p>빠른 채팅은 Android에서 Provider에 직접 연결하고, Alpine 작업 모드는 PRoot·Python Gateway·터미널·패키지 도구를 사용합니다.</p>

  <p>
    <a href="https://github.com/coreline-ai/alpine-llm-gateway/actions/workflows/ci.yml"><img alt="CI" src="https://github.com/coreline-ai/alpine-llm-gateway/actions/workflows/ci.yml/badge.svg?branch=main"></a>
    <img alt="Version 0.3.0" src="https://img.shields.io/badge/version-0.3.0-B9F227?style=flat-square&labelColor=10120F">
    <img alt="Android API 26+" src="https://img.shields.io/badge/Android-API%2026%2B-3DDC84?style=flat-square&logo=android&logoColor=white">
    <img alt="Kotlin 2.2.21" src="https://img.shields.io/badge/Kotlin-2.2.21-7F52FF?style=flat-square&logo=kotlin&logoColor=white">
    <img alt="Python 3.11+" src="https://img.shields.io/badge/Python-3.11%2B-3776AB?style=flat-square&logo=python&logoColor=white">
    <img alt="Alpine 3.21.3" src="https://img.shields.io/badge/Alpine-3.21.3-0D597F?style=flat-square&logo=alpinelinux&logoColor=white">
    <img alt="Distribution internal only" src="https://img.shields.io/badge/distribution-INTERNAL%20ONLY-F59E0B?style=flat-square">
  </p>

  <p>
    <a href="#screens">앱 화면</a> ·
    <a href="#quick-start">빠른 시작</a> ·
    <a href="#architecture">아키텍처</a> ·
    <a href="#modules">모듈</a> ·
    <a href="#verification">검증</a> ·
    <a href="#docs">문서</a>
  </p>
</div>

> [!IMPORTANT]
> 현재 `0.3.0`은 **내부 개발·검증용**입니다. 프로젝트 전체 라이선스, Alpine package-level corresponding source, 실제 Provider 계정 승인, Play test track과 배포 책임자가 확정되기 전에는 공개 Maven·스토어 배포가 허용된 상태가 아닙니다.

## ✨ 프로젝트 개요

`alpine-llm-gateway`는 다음 네 가지 제품·SDK 경계를 한 저장소에서 관리합니다.

| 영역 | 역할 | 현재 위치 |
|---|---|---|
| **Alpine AI Workspace** | 빠른 채팅과 Alpine 작업 모드를 한 Android 앱에서 제공 | `:integrated-app` |
| **재사용 Android SDK** | 채팅, OAuth Provider, Runtime, Bridge, 터미널, Workspace를 선택 조립 | 19개 AAR/JAR 모듈 |
| **Python Gateway** | Alpine 안에서 Provider 차이를 숨기는 dependency-free gateway와 `llmctl` | `alpine_llm/` |
| **MobileAgent 제품 경로** | 별도 Flutter Android/iOS 앱과 OIDC/BFF 기반 Provider 호출 | `apps/mobile_agent/`, `backend/mobile_agent_bff/` |

`integrated-app`, `demo-chatbot`, `apps/mobile_agent`는 **서로 다른 앱**입니다. 이 README의 화면은 Android 통합 제품인 `dev.alpine.integrated`를 기준으로 합니다.

### 핵심 특징

- ⚡ **빠른 채팅 모드** — Android OAuth/Keystore에서 Provider를 직접 호출
- 🐧 **Alpine 작업 모드** — Alpine 3.21.3, PRoot, Python Gateway, 터미널과 패키지 도구
- 🔐 **Credential 격리** — OAuth token은 Android Host에만 보관하고 Guest에는 전달하지 않음
- 🔁 **안전한 라우팅** — dispatch 전 사용자 승인 fallback, dispatch 후 자동 재전송 금지
- 💬 **공통 채팅 Feature** — 다중 대화, 암호화 저장, 모델, Skill, Persona, Stop과 Retry
- 📦 **선택형 SDK** — Runtime payload 없이 채팅만, 또는 Runtime·Bridge·UI까지 조립 가능
- 🧯 **방어적 Gateway** — fail-closed 모델 정책, 응답 크기 제한, retry/circuit breaker, redacted 오류
- 🧪 **실기기 검증** — Samsung Android 16 arm64에서 통합 채팅과 Runtime/Bridge probe 검증

<a id="screens"></a>
## 📱 앱 화면

실제 Samsung 기기에서 캡처한 `Alpine AI Workspace`입니다. 이미지에는 OAuth 계정, client ID, token 또는 기기 serial을 포함하지 않았습니다. 이미지를 누르면 원본 크기로 볼 수 있습니다.

<table>
  <tr>
    <td align="center"><a href="docs/assets/screenshots/01-fast-chat.png"><img src="docs/assets/screenshots/01-fast-chat.png" width="260" alt="빠른 채팅"></a><br><strong>빠른 채팅</strong></td>
    <td align="center"><a href="docs/assets/screenshots/07-alpine-gateway-chat.png"><img src="docs/assets/screenshots/07-alpine-gateway-chat.png" width="260" alt="Alpine Gateway 채팅"></a><br><strong>Alpine Gateway</strong></td>
    <td align="center"><a href="docs/assets/screenshots/08-alpine-runtime-tools.png"><img src="docs/assets/screenshots/08-alpine-runtime-tools.png" width="260" alt="Alpine Runtime 도구"></a><br><strong>Runtime · 터미널</strong></td>
  </tr>
</table>

<details open>
<summary><strong>전체 화면 갤러리 펼치기</strong></summary>
<br>
<table>
  <tr>
    <td align="center"><a href="docs/assets/screenshots/01-fast-chat.png"><img src="docs/assets/screenshots/01-fast-chat.png" width="235" alt="빠른 채팅 연결 전 화면"></a><br>연결 전 빠른 채팅</td>
    <td align="center"><a href="docs/assets/screenshots/02-conversation-history.png"><img src="docs/assets/screenshots/02-conversation-history.png" width="235" alt="대화 기록 drawer"></a><br>암호화 대화 기록</td>
    <td align="center"><a href="docs/assets/screenshots/03-assistant-mode.png"><img src="docs/assets/screenshots/03-assistant-mode.png" width="235" alt="Assistant skill 선택"></a><br>Assistant Skill</td>
  </tr>
  <tr>
    <td align="center"><a href="docs/assets/screenshots/04-assistant-personas.png"><img src="docs/assets/screenshots/04-assistant-personas.png" width="235" alt="Skill과 Persona 목록"></a><br>Skill · Persona 카탈로그</td>
    <td align="center"><a href="docs/assets/screenshots/05-assistant-defaults.png"><img src="docs/assets/screenshots/05-assistant-defaults.png" width="235" alt="Persona와 기본값 설정"></a><br>Persona · 기본값</td>
    <td align="center"><a href="docs/assets/screenshots/06-provider-connections.png"><img src="docs/assets/screenshots/06-provider-connections.png" width="235" alt="LLM 연결 목록"></a><br>LLM 연결 관리</td>
  </tr>
  <tr>
    <td align="center"><a href="docs/assets/screenshots/06a-provider-types.png"><img src="docs/assets/screenshots/06a-provider-types.png" width="235" alt="Provider 유형 선택"></a><br>Provider 유형</td>
    <td align="center"><a href="docs/assets/screenshots/07-alpine-gateway-chat.png"><img src="docs/assets/screenshots/07-alpine-gateway-chat.png" width="235" alt="Alpine Gateway 채팅 화면"></a><br>Gateway 채팅</td>
    <td align="center"><a href="docs/assets/screenshots/08-alpine-runtime-tools.png"><img src="docs/assets/screenshots/08-alpine-runtime-tools.png" width="235" alt="Runtime과 Linux 터미널"></a><br>Runtime · Linux 터미널</td>
  </tr>
  <tr>
    <td align="center"><a href="docs/assets/screenshots/09-runtime-package-manager.png"><img src="docs/assets/screenshots/09-runtime-package-manager.png" width="235" alt="Alpine 패키지 설치 UI"></a><br>패키지 allowlist UI</td>
    <td colspan="2"><strong>보안상 제외한 화면</strong><br>실제 OAuth 브라우저, 계정 정보, public client ID 입력값, token/credential이 포함될 수 있는 상세 설정은 저장소 이미지로 남기지 않습니다.</td>
  </tr>
</table>
</details>

## 🧭 두 가지 실행 모드

| 모드 | 실행 경로 | 적합한 작업 | Runtime 필요 |
|---|---|---|---|
| **빠른 채팅** | Android → OAuth session → Provider HTTPS | 일반 대화, 빠른 응답, Runtime 장애 시 독립 사용 | 아니요 |
| **Alpine 작업** | Android → PRoot/Alpine → Python Gateway → loopback Host Bridge → Provider HTTPS | 터미널, Python, Git, 패키지, Linux 기반 Skill과 자동화 | 예 |

두 모드는 동일한 대화·모델·Skill·Persona 상태를 사용합니다. Alpine 준비 실패 시 fallback은 Provider dispatch 전에 사용자가 승인한 **해당 요청 한 번**만 빠른 채팅으로 전송하며, 첫 Provider dispatch 또는 delta 이후에는 중복 비용을 막기 위해 다른 backend로 자동 전환하지 않습니다.

<a id="architecture"></a>
## 🏗 아키텍처

```mermaid
flowchart LR
    UI["Alpine AI Workspace<br/>Compose UI"]
    ROUTER["Safe Chat Router<br/>request ledger"]
    DIRECT["Fast Chat Backend"]
    HOST["Android OAuth · Keystore<br/>Provider session"]
    PROVIDER["External LLM Provider<br/>HTTPS"]

    RUNTIME["Runtime Host<br/>install · health · recovery"]
    GUEST["PRoot · Alpine 3.21.3<br/>PTY · apk · workspace"]
    GATEWAY["Python Gateway · llmctl<br/>127.0.0.1:8787"]
    BRIDGE["Host Bridge<br/>TTL capability · loopback"]

    UI --> ROUTER
    ROUTER --> DIRECT --> HOST --> PROVIDER
    ROUTER --> RUNTIME --> GUEST --> GATEWAY --> BRIDGE --> HOST
```

### Credential 경계

1. OAuth access/refresh token은 Android Keystore-backed store에만 저장합니다.
2. Alpine Guest에는 token 대신 loopback endpoint와 짧은 TTL capability 파일만 제공합니다.
3. Host Bridge는 bounded concurrency, timeout, request ID, redacted error와 health metric을 적용합니다.
4. Python Gateway는 기본 `127.0.0.1` bind, 모델 allowlist, 입력·출력·SSE 크기 제한을 적용합니다.
5. 패키지 설치는 exact allowlist와 사용자 승인 모두를 통과한 이름만 고정 `apk add` 명령으로 실행합니다.

<a id="modules"></a>
## 🧩 모듈 구성

| 그룹 | 주요 모듈 | 책임 |
|---|---|---|
| Chat | `alpine-chat-feature`, `alpine-chat-routing` | 대화 상태, 암호화 저장, UI, backend 중립 계약 |
| Provider | `alpine-chat-provider-android`, `android` | OAuth profile CRUD, Keystore, Provider adapter |
| Backend | `alpine-chat-backend-direct`, `alpine-chat-backend-alpine` | 빠른 채팅과 Gateway 경로 연결 |
| Runtime | `alpine-runtime-api`, `-android`, `-host`, `-ui-compose` | 설치, 실행, 복구, PTY, 터미널, 패키지 상태 |
| Artifact | `alpine-runtime-pack-bundled`, `-pack-x86_64`, `-artifact-play` | rootfs·PRoot·loader 공급과 checksum/SBOM |
| LLM Bridge | `alpine-llm-bridge`, `alpine-llm-gateway-pack-bundled` | capability, Host Bridge와 Python Gateway lifecycle |
| Workspace | `alpine-workspace-api`, `alpine-workspace-android` | app-private 경로, quota, bounded atomic file 작업 |
| Background/Test | `alpine-runtime-background-android`, `alpine-runtime-testkit` | FGS/WorkManager 정책과 결정적 fake runtime |

전체 artifact 역할과 권장 조합은 [Alpine Runtime SDK 모듈 가이드](docs/alpine-runtime-sdk-modules.md)를 참고하세요.

<a id="quick-start"></a>
## 🚀 빠른 시작

### Android 통합 앱

요구사항: JDK 17, Android SDK 36, Android 8.0(API 26) 이상

```bash
git clone https://github.com/coreline-ai/alpine-llm-gateway.git
cd alpine-llm-gateway

./gradlew :integrated-app:assembleDebug
adb install -r integrated-app/build/outputs/apk/debug/integrated-app-debug.apk
```

앱 실행 후:

1. `빠른 채팅`에서 `LLM connection`을 엽니다.
2. 앱 소유·승인된 OAuth public client registration으로 profile을 만듭니다.
3. 로그인 후 Provider와 모델을 선택합니다.
4. Alpine 기능은 `Alpine 작업 → 터미널·도구 → 설치` 순서로 준비합니다.
5. Gateway health가 정상이면 Gateway 채팅, 터미널과 허용 패키지 설치를 사용할 수 있습니다.

> [!CAUTION]
> 다른 앱이나 공식 CLI의 public client ID·fingerprint를 복사해 제품에 포함하지 마세요. 현재 Android direct OAuth 화면의 Anthropic/Codex/xAI 항목은 compatibility/reference 경로이며 공식 제품 승인을 의미하지 않습니다.

### Python Gateway

요구사항: Python 3.11 이상. 런타임 의존성은 Python 표준 라이브러리만 사용합니다.

```bash
python3.11 -m venv .venv
source .venv/bin/activate
pip install -e .

cp config.example.json config.json
export LLM_API_KEY="..."

llm-gatewayd serve --config config.json
```

다른 터미널에서:

```bash
llmctl models
llmctl run --model auto --prompt "Alpine에서 동작하는지 설명해줘"
llmctl run --model auto --prompt "스트리밍 테스트" --stream --format jsonl
```

공통 입력은 OpenAI-compatible message 형식이며 Provider native request 변환은 Gateway가 담당합니다. `stream`은 JSON Boolean만 허용하고, `allowed_models`가 비어 있거나 요청 모델이 허용되지 않으면 `allow_passthrough: true`를 명시하지 않는 한 fail-closed 처리합니다.

## 🛡 Gateway 안전 기본값

| 설정 | 기본값 | 의미 |
|---|---:|---|
| `max_response_bytes` | 8 MiB | non-stream 응답과 Provider 오류 body 상한 |
| `max_stream_event_bytes` | 1 MiB | SSE 단일 line/event 상한 |
| `max_stream_bytes` | 32 MiB | SSE 전체 응답 상한 |
| `provider_retry_max_attempts` | 3 | 최초 요청 포함 retryable 요청 최대 시도 |
| `provider_retry_max_backoff_seconds` | 8초 | backoff와 `Retry-After` 상한 |
| `provider_circuit_failure_threshold` | 5 | circuit open 연속 실패 기준 |
| `provider_circuit_recovery_seconds` | 30초 | half-open probe 대기시간 |
| `allow_passthrough` | `false` | 모델 allowlist 우회 기본 차단 |

Streaming은 Provider HTTP 연결·status 단계까지만 재시도합니다. stream이 열린 뒤에는 delta 중복을 막기 위해 재시도하지 않고 redacted SSE error event와 `[DONE]`으로 종료합니다.

<a id="verification"></a>
## 🧪 검증

### 자주 사용하는 로컬 검증

```bash
python3 -m unittest discover -s tests -v

./gradlew \
  :alpine-chat-feature:testDebugUnitTest \
  :alpine-runtime-ui-compose:testDebugUnitTest \
  :integrated-app:assembleDebug \
  :integrated-app:assembleDebugAndroidTest \
  :integrated-app:lintDebug
```

Samsung 실기기 통합 E2E:

```bash
ANDROID_SERIAL=<device-serial> ./gradlew :integrated-app:connectedDebugAndroidTest
```

내부 SDK release bundle 전체 검증:

```bash
./scripts/release-local.sh
```

### 현재 검증 상태

| 항목 | 상태 | 기준 |
|---|---|---|
| Python unit/compile/smoke | ✅ CI PASS | GitHub Actions `Python 3.11` |
| Android modules·publication matrix | ✅ CI PASS | remote run `30807869557`, commit `3389fcb` |
| 통합 앱 compile·unit·lint·APK | ✅ Local PASS | 2026-08-07 UI/가독성 패치 |
| Samsung 통합 채팅 instrumentation | ✅ 4/4 PASS | Android 16 arm64 |
| arm64 Runtime·PTY·Bridge·Gateway probe | ✅ PASS | Samsung `SM-S931N` |
| 실제 Provider 계정 OAuth/API E2E | ⏳ `NOT_RUN` | 앱 소유 registration·계정 승인 필요 |
| x86_64 emulator E2E | ⛔ BLOCKED | 연결된 검증 emulator 없음 |
| 공개 배포 | ⛔ `NO-GO` | release gate 7개 중 6개 BLOCKED |

Remote CI의 최신 성공은 원격 `main`의 기준선입니다. 현재 로컬 commit 또는 working tree가 Push되지 않았다면 그 변경은 원격 CI로 검증된 것으로 간주하지 않습니다.

## ⚠️ 현재 제한

- arm64-v8a는 제품 검증 대상이며 x86_64 pack은 emulator E2E 전까지 실험 상태입니다.
- 현재 PRoot는 최초 PTY 크기만 Guest에 반영합니다. 실행 중 동적 resize는 `INITIAL_SIZE_ONLY`로 명시됩니다.
- 실제 Provider direct OAuth는 앱 소유 registration과 inference 사용 승인이 필요합니다.
- MobileAgent OIDC/BFF의 실제 staging Provider E2E는 external account/secret이 없어 아직 실행하지 않았습니다.
- BFF request/cancel/revocation registry는 단일 process memory 구현이며 다중 replica 전 Redis 경계가 필요합니다.
- Play Asset Delivery 전체 E2E는 signed AAB와 Play test track이 필요합니다.
- Samsung 재부팅·Doze·process-kill 복구는 파괴적 기기 테스트 승인 창이 필요합니다.
- 루트 프로젝트 `LICENSE`와 Alpine package-level exact source mirror가 없어 외부 배포는 차단됩니다.

전체 gate는 [SDK publication과 배포 가이드](docs/sdk-publication-and-distribution.md)와 [release readiness](distribution/release-readiness.json)에서 확인할 수 있습니다.

<a id="docs"></a>
## 📚 문서 지도

| 문서 | 내용 |
|---|---|
| [통합 앱 가이드](integrated-app/README.md) | 빠른 채팅·Alpine 작업 사용법과 실기기 검증 |
| [Android 통합](android/README.md) | OAuth, Host Bridge, Provider adapter와 manifest |
| [Runtime SDK 모듈](docs/alpine-runtime-sdk-modules.md) | 19개 artifact 역할과 권장 조합 |
| [Runtime Host 통합](docs/alpine-runtime-host-integration.md) | Application owner, FGS, terminal, package, recovery |
| [Provider OAuth adapter](docs/provider-oauth-adapters.md) | 제품 OIDC/BFF와 compatibility direct OAuth 경계 |
| [Provider 계정 E2E](docs/provider-account-e2e-runbook.md) | opt-in 실제 계정 검증 절차 |
| [Samsung lifecycle E2E](docs/samsung-background-lifecycle-e2e.md) | reboot·Doze·process-kill 검증 절차 |
| [배포 가이드](docs/sdk-publication-and-distribution.md) | Maven bundle, source, Play와 공개 gate |
| [OSS 경계](distribution/PROJECT_CODE_OSS_BOUNDARY.md) | 프로젝트 코드와 PRoot/Alpine OSS 분리 |
| [라이선스 상태](distribution/PROJECT_LICENSE_STATUS.md) | 프로젝트 전체 라이선스 미확정 상태 |
| [GitHub CI 상태](distribution/GITHUB_CI_STATUS.md) | 원격 성공 기준선과 로컬 차이 |
| [개발 계획](dev-plan/) | Phase별 구현·검증 이력 |

## 📄 라이선스와 배포

이 저장소에는 현재 프로젝트 전체 코드에 적용되는 루트 `LICENSE`가 없습니다. 따라서 코드를 공개 사용·재배포할 수 있다는 허가를 이 README가 부여하지 않습니다.

PRoot/talloc source bundle 생성과 라이선스 고지는 구현되어 있지만 Alpine rootfs package-level exact source mirror와 최종 OSS 검토가 남아 있습니다. 생성되는 `dist/alpine-sdk-0.3.0/`은 `INTERNAL_ONLY`이며 `external_distribution_ready=false`입니다.

자세한 내용은 [프로젝트 라이선스 상태](distribution/PROJECT_LICENSE_STATUS.md), [corresponding source 상태](distribution/SOURCE_OFFER_STATUS.md), [제3자 고지](distribution/THIRD_PARTY_NOTICES.md)를 확인하세요.
