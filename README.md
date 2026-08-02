# Alpine LLM Gateway

Alpine Linux 샌드박스 안에서 Claude, GPT, Gemini 계열 LLM을 호출하기 위한 독립 모듈입니다.

이 모듈은 OpenMinis에 의존하지 않습니다. Python 3 표준 라이브러리 Gateway와 재사용 가능한 Android Library를 포함합니다. Alpine rootfs 안에서는 `llmctl`을 사용하고 Android에서는 OAuth token을 Host에만 보관한 채 loopback bridge로 LLM을 호출할 수 있습니다.

## 구조

```text
Alpine llmctl
      │
      ▼
alpine-llm-gatewayd :8787 (127.0.0.1)
      │
      ├── Policy Engine
      ├── Provider Adapter
      └── HTTPS
            ├── OpenAI-compatible
            ├── Anthropic
            └── Gemini
```

기본값은 `127.0.0.1`에만 바인딩합니다. API Key는 설정 파일에 직접 넣지 않고 환경변수에서 읽는 방식을 권장합니다.

## 빠른 실행

```bash
cd alpine-llm-gateway
cp config.example.json config.json
export LLM_API_KEY="..."
export LLM_MODEL="gpt-4o-mini"

python3 -m alpine_llm.cli serve --config config.json
```

다른 터미널에서:

```bash
python3 -m alpine_llm.cli models
python3 -m alpine_llm.cli run --model auto --prompt "Alpine 환경에서 동작하는지 설명해줘"
python3 -m alpine_llm.cli run --model auto --prompt "스트리밍 테스트" --stream --format jsonl
```

## 입력 계약

Gateway는 OpenAI 호환 메시지 형식을 공통 입력으로 사용합니다.

```json
{
  "model": "auto",
  "messages": [
    {"role": "user", "content": "요청 내용"}
  ],
  "max_tokens": 1024,
  "temperature": 0.2,
  "stream": false
}
```

Provider별 native request 변환은 Gateway가 담당합니다. Alpine 애플리케이션은 Provider별 API 형식을 알 필요가 없습니다.

`stream`은 JSON Boolean만 허용합니다. `"false"`, `0`, `null`처럼 Boolean이 아닌 값은 요청 오류로 거부합니다.

## 정책과 응답 제한

기본 모델과 `allowed_models`에 포함된 모델만 호출할 수 있습니다. 동적으로 지정한 임의 모델을 허용해야 하는 개발 환경에서는 `allow_passthrough: true`를 명시해야 하며 기본값은 `false`입니다.

Provider의 비정상적으로 큰 응답이 Alpine 프로세스 메모리를 소진하지 않도록 다음 제한을 설정할 수 있습니다.

| 설정 | 기본값 | 적용 범위 |
|---|---:|---|
| `max_response_bytes` | 8 MiB | non-stream JSON과 Provider HTTP 오류 body |
| `max_stream_event_bytes` | 1 MiB | SSE 단일 line/event |
| `max_stream_bytes` | 32 MiB | SSE 전체 응답 |

Streaming 요청의 protocol/policy 오류는 SSE 시작 전 JSON 400으로 반환합니다. HTTP 200 이후 Provider 오류는 원문 응답이나 내부 exception을 포함하지 않는 공통 event로 전달하고 `[DONE]`으로 종료합니다.

```text
data: {"type":"error","code":"provider_error","message":"provider stream failed","retryable":true}
data: [DONE]
```

## Provider retry와 circuit breaker

Python Gateway는 408, 429, 500, 502, 503, 504와 Provider 연결 실패만 제한적으로 재시도합니다. `Retry-After`가 있으면 최대 backoff 범위 안에서 우선 적용하고, 그 외에는 jitter가 포함된 지수 backoff를 사용합니다. 일반적인 4xx, 응답 크기 초과와 JSON parsing 오류는 재시도하지 않습니다.

Streaming은 Provider HTTP 연결과 status 응답 단계까지만 재시도합니다. 응답 stream을 연 뒤에는 중복 delta를 막기 위해 재시도하지 않고 공통 SSE error event로 종료합니다. 연속된 retryable 실패가 설정값에 도달하면 Provider 인스턴스의 circuit이 열리며, recovery 시간 뒤 하나의 half-open probe만 허용합니다.

| 설정 | 기본값 | 적용 범위 |
|---|---:|---|
| `provider_retry_max_attempts` | 3 | 최초 요청을 포함한 최대 시도 횟수, 1~10 |
| `provider_retry_initial_backoff_seconds` | 0.5 | 첫 재시도 대기시간 |
| `provider_retry_max_backoff_seconds` | 8.0 | backoff 및 `Retry-After` 상한 |
| `provider_retry_jitter_ratio` | 0.2 | 지수 backoff jitter 비율, 0~1 |
| `provider_circuit_failure_threshold` | 5 | circuit을 여는 연속 실패 요청 수 |
| `provider_circuit_recovery_seconds` | 30 | half-open probe까지의 대기시간 |

Chat Completion POST는 Provider가 idempotency 계약을 제공하지 않으면 연결 실패 시 중복 처리될 가능성을 완전히 제거할 수 없습니다. 중복 비용이 더 중요한 운영 환경은 `provider_retry_max_attempts: 1`로 재시도를 끌 수 있습니다.

## Android + OAuth

Android 모듈은 다음 두 실행 방식을 분리합니다.

- 서버/개발 모드: Python Gateway가 환경변수의 API key로 Provider를 직접 호출
- Android 배포 모드: Android가 OAuth/Keystore/Provider HTTPS를 담당하고 Alpine은 임시 Host Bridge session token만 사용

Android 배포 경로는 `OAuthManager → OAuthLlmSession → OAuthHttpLlmBridge → HostBridgeServer`로 구현되어 있습니다. OpenAI-compatible, Anthropic Messages, Gemini generateContent와 별도 Codex account Responses adapter가 포함됩니다. non-stream JSON과 Provider SSE를 공통 `start/delta/done` event로 전달합니다.

Phase 4부터 `HostBridgeServer`와 Gateway client는 선택형 `:alpine-llm-bridge`에 있으며,
`AlpineLlmBridgeController`가 Host Bridge·runtime session·Python Gateway를 단일 lifecycle로
관리합니다. Python package는 `:alpine-llm-gateway-pack-bundled`의 별도 checksum lock 자산이므로
runtime-only 앱에는 포함되지 않습니다. guest config에는 OAuth token 대신 TTL capability 파일
경로만 기록됩니다.

Phase 5의 `:alpine-chat-routing`은 빠른 채팅과 Alpine 작업 모드의 공통
request/stream/failure/audit 계약을 제공합니다. `:alpine-chat-backend-direct`는 기존 Android
OAuth session을, `:alpine-chat-backend-alpine`은 Python Gateway를 연결합니다. Alpine 준비
실패 fallback은 사용자 승인 전까지만 허용하며, Provider dispatch 또는 첫 delta 이후에는
다른 backend로 자동 재전송하지 않습니다. request ledger가 동시·완료 request ID 재사용을
차단하고 현재 두 adapter의 Provider idempotency capability는 안전하게 `NONE`입니다.

Phase 6의 `:alpine-runtime-host`는 Compose/Activity와 무관한 install/start/health/repair/reset,
command, terminal과 package 상태 controller를 제공합니다. `:alpine-runtime-ui-compose`는 같은
상태를 렌더링하는 선택형 화면이며 `:alpine-integration-sample`은 Compose 없이 XML/View만으로,
`:integrated-app`은 Compose와 빠른 채팅/Alpine 작업 mode selector로 조립됩니다. 패키지 설치는
정확한 allowlist와 사용자 승인을 모두 통과해야 하며 임의 shell 문자열을 실행하지 않습니다.

Host Bridge는 bounded concurrency, overload 429/`Retry-After`, request timeout, request ID와 누적 health 지표를 제공합니다. 선택적 resilient transport는 제한적 retry/backoff와 circuit breaker를 제공하고 운영 event schema에는 URL·header·body·credential 필드가 존재하지 않습니다.

이미지 입력은 허용 media type의 5 MiB 이하 base64 data URL만 지원하며, Claude/Gemini/Codex tool definition·tool call·tool result를 OpenAI 공통 형식으로 정규화합니다.

통합 코드는 [Android 통합 가이드](android/README.md), Provider별 남은 작업은 [OAuth adapter 요구사항](docs/provider-oauth-adapters.md)을 참고하세요.

## 선택형 멀티 LLM 데모 앱

별도 `:demo-chatbot` 앱은 Claude/Anthropic, Gemini, OpenAI-compatible과 Codex OAuth 프로필을 GUI에서 복수 등록하고, 인증된 연결 가운데 하나를 선택해 일반 채팅과 스트리밍 취소를 검증한다. Codex endpoint/scope/callback과 CLI 호환 public client ID는 고정값으로 표시하고, 실제 CLI 호출로 검증한 모델 목록에서 기본 모델을 선택한다. assistant Markdown은 표·안전 링크 확인·경량 코드 강조를 포함한 native Compose로 표시한다. 명시적인 단어·문장·목록 수 제한과 앱에 없는 웹 검증 주장 방지는 완료 후 로컬 검증하며, 두 검사 합계로 같은 요청에서 최대 1회만 자동 교정한다.

설정 방법과 OAuth callback 제한은 [데모 챗봇 가이드](demo-chatbot/README.md)를 참고한다.

## 테스트

```bash
python3 -m unittest discover -s tests -v
./gradlew :android:testDebugUnitTest :android:assembleDebug
./gradlew :sample:assembleDebug :android:assembleDebugAndroidTest
./gradlew :demo-chatbot:testDebugUnitTest :demo-chatbot:assembleDebug :demo-chatbot:lintDebug
./gradlew :alpine-runtime-api:check :alpine-runtime-android:testDebugUnitTest
./gradlew :alpine-runtime-pack-bundled:verifyBundledRuntimeArtifacts :alpine-runtime-probe:assembleDebug
./gradlew :alpine-runtime-pack-x86_64:verifyX8664RuntimeArtifacts
./gradlew :alpine-runtime-background-android:testDebugUnitTest :alpine-runtime-artifact-play:testDebugUnitTest
./gradlew :alpine-workspace-api:check :alpine-workspace-android:testDebugUnitTest
./gradlew :alpine-llm-bridge:testDebugUnitTest :alpine-llm-gateway-pack-bundled:verifyBundledPythonGatewayArtifact
./gradlew :alpine-llm-bridge-probe:assembleDebug
./gradlew :alpine-chat-routing:check :alpine-chat-backend-direct:testDebugUnitTest :alpine-chat-backend-alpine:testDebugUnitTest
./gradlew :alpine-runtime-host:check :alpine-runtime-ui-compose:testDebugUnitTest
./gradlew :alpine-integration-sample:assembleDebug :integrated-app:assembleDebug
./scripts/runtime/run-llm-bridge-probe-device.sh <samsung-device-serial>
ANDROID_SERIAL=<device-serial> ./gradlew :android:connectedDebugAndroidTest
./gradlew :android:assembleRelease :android:publishReleasePublicationToProjectRepository
```

테스트는 외부 LLM/OAuth API를 호출하지 않고 protocol, policy, Provider request/stream 변환, PKCE/state, callback, refresh single-flight, retry/circuit breaker, Host Bridge 인증과 credential 비노출을 검증합니다. 데모 앱은 deterministic fake session으로 429, 503, 잘못된 stream, 중단된 stream과 timeout의 redacted 오류·재시도 E2E도 검증합니다. 실제 계정에 의도적으로 rate limit이나 장애를 발생시키지는 않습니다.

CI 없이 검증과 release bundle 생성을 한 번에 수행할 수 있습니다.

```bash
./scripts/release-local.sh
```

결과는 `dist/alpine-sdk-0.3.0/`에 17개 AAR/JAR의 Maven repository, sources, POM,
Gradle module metadata, license/SBOM과 `SHA256SUMS`로 생성됩니다.

## 현재 제한

- 기존 `:android`와 `:demo-chatbot`에는 rootfs/PRoot를 포함하지 않습니다. 선택형
  `:alpine-runtime-pack-bundled`가 검증된 arm64-v8a Alpine 3.21.3 구성을 제공합니다.
  `:alpine-runtime-pack-x86_64`는 별도 lock/SBOM/16 KiB gate를 통과한 실험 artifact지만,
  x86_64 emulator E2E 전에는 제품 지원 ABI가 아닙니다.
- 장시간 작업의 FGS/알림/복구, Play Asset Delivery와 app-private workspace는 각각 선택형
  `:alpine-runtime-background-android`, `:alpine-runtime-artifact-play`,
  `:alpine-workspace-*` 모듈로 분리되어 빠른 채팅 앱에 포함되지 않습니다.
- PRoot/loader와 신규 native PTY의 모든 ELF `PT_LOAD` segment는 자동 검사에서 Android 16
  16 KiB 기준인 `0x4000` 이상인지 확인합니다.
- terminal 최초 크기는 native PTY에 적용됩니다. 현재 PRoot가 실행 후 창 크기 변경을 guest에
  전달하지 않으므로 session은 `INITIAL_SIZE_ONLY`를 명시하고 동적 resize 요청을 거부합니다.
- 실제 Provider OAuth client registration/client id는 앱 개발자가 준비해야 합니다.
- Codex 경로는 로컬 mock 계약까지 구현했으며 실제 ChatGPT 계정, client registration 사용 권한과 Provider 정책 E2E는 아직 검증하지 않았습니다. 다른 앱의 public client ID를 복사해 배포하지 마세요.
- Kimi device flow, xAI OIDC discovery와 Gemini project onboarding은 후속 범위입니다.
- Keystore instrumentation 2건, loopback Host Bridge 3건과 Compose 접근성·입력 2건은 Samsung `SM-S931N` Android 16 `arm64-v8a`에서 검증했습니다. runtime/PTY와 Python Gateway는 별도 device probe로 같은 기기에서 검증합니다.
- 외부 Provider 실제 계정 end-to-end 검증은 credential이 없는 로컬 테스트에서 수행하지 않습니다.
- 현재 저장소에는 프로젝트 전체 `LICENSE`가 없습니다. PRoot/talloc native source bundle 생성기는
  구현되어 release bundle에 별도 artifact로 포함할 수 있지만, Alpine package-level exact source
  mirror와 최종 OSS 검토가 남아 있으므로 `dist/alpine-sdk-0.3.0`은 계속 내부 검증용입니다.
  공개 Maven/스토어 배포는 `external_distribution_ready=true`가 된 뒤 진행해야 합니다.
