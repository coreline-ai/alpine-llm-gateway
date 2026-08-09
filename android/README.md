# Android 통합

이 디렉터리는 `minSdk 26`의 독립 Android Library 모듈입니다. OpenMinis 코드를 직접 참조하지 않으며, 앱에 Gradle module dependency로 추가할 수 있습니다.

```kotlin
// settings.gradle.kts 예시
include(":alpine-llm")
project(":alpine-llm").projectDir = file("../alpine-llm-gateway/android")

// app/build.gradle.kts
dependencies {
    implementation(project(":alpine-llm"))
}
```

Library Manifest가 `INTERNET` permission, 기본 `/oauth/callback`과 Codex용 `localhost:1455/auth/callback` Activity intent filter를 병합합니다.

## 보안 경계

```text
Alpine llmctl
  └─ ALPINE_LLM_CREDENTIAL_FILE (TTL capability 파일 경로)
       └─ 127.0.0.1 HostBridgeServer
            └─ OAuthLlmSession
                 ├─ Android Keystore 암호화 token 저장
                 └─ HTTPS Provider 요청에만 OAuth token 첨부
```

Alpine에는 OAuth access token, refresh token, client secret을 전달하지 않습니다. Alpine에 전달되는 session token은 해당 앱 프로세스의 loopback bridge만 호출할 수 있는 임시 값이며, Provider token으로 교환할 수 없습니다.

## OAuth 설정

`OAuthManager`는 Authorization Code + PKCE, Custom Tab, loopback callback, state 검증, timeout/취소, encrypted storage, refresh single-flight, logout을 제공합니다.

```kotlin
val oauth = OAuthManager(
    context = context,
    config = OAuthProviderConfig(
        providerId = "my-provider",
        authorizationEndpoint = "https://provider.example.com/oauth/authorize",
        tokenEndpoint = "https://provider.example.com/oauth/token",
        clientId = BuildConfig.OAUTH_CLIENT_ID,
        scopes = listOf("openid", "profile", "offline_access"),
        callbackPort = 1455,
        extraAuthorizationParams = mapOf("prompt" to "consent"),
        tokenRequestEncoding = OAuthTokenRequestEncoding.FORM_URLENCODED,
    ),
)

// Activity/Fragment lifecycleScope 등에서 호출. Activity를 전달하면
// Custom Tab이 같은 task에서 열립니다.
val token = oauth.authorize(requireActivity())
val state = oauth.authenticationState()
```

Provider가 JSON token body를 요구하면 `tokenRequestEncoding = JSON`을 사용합니다. 동적인 `state` 또는 `code_challenge`를 token endpoint에 다시 보내야 하면 `tokenRequestAdapter`에서 추가합니다. 비표준 token response나 account metadata는 `tokenResponseAdapter`로 변환합니다.

사용자가 인증 화면을 닫는 것을 앱이 감지했거나 자체 취소 버튼을 제공하는 경우 `oauth.cancelAuthorization()`을 호출합니다. coroutine 자체를 취소해도 callback server와 transaction은 `finally`에서 정리됩니다.

`OAuthException.kind`는 `USER_DENIED`, `CALLBACK_TIMEOUT`, `STATE_MISMATCH`, `INVALID_GRANT`, `STORAGE_INVALIDATED`, `NETWORK` 등을 구분합니다. `authenticationState()`가 `ReauthenticationRequired`이면 저장된 credential을 복호화할 수 없는 상태이므로 다시 로그인해야 합니다.

> Native Android 앱에 포함된 `clientSecret`은 추출될 수 있습니다. 가능하면 PKCE public client를 등록하고 secret을 앱에 넣지 마세요.

## OAuth Host Bridge 실행

OpenAI 호환 HTTPS endpoint는 기본 adapter로 바로 연결할 수 있습니다.

```kotlin
val transport = ResilientOAuthHttpTransport(
    retryPolicy = ProviderRetryPolicy(maxAttempts = 3),
    circuitBreaker = ProviderCircuitBreaker(
        ProviderCircuitBreakerConfig(failureThreshold = 5),
    ),
)
val providerBridge = OAuthHttpLlmBridge(
    adapter = OpenAiCompatibleOAuthAdapter(
        completionEndpoint = "https://provider.example.com/v1/chat/completions",
        extraHeaders = mapOf("X-Provider-Version" to "1"),
    ),
    streamingTransport = transport,
    transport = transport,
)
val session = OAuthLlmSession(oauth, providerBridge)
val hostBridge = HostBridgeServer(
    maxConcurrentRequests = 4,
    overloadRetryAfterSeconds = 1,
    requestTimeoutMs = 180_000,
    streamExecutor = session::stream,
    requestExecutor = session::complete,
)
val endpoint = hostBridge.start()

// 앱 lifecycle 종료 시
hostBridge.stop()
```

Alpine 작업 모드는 더 이상 이 Provider 모듈의 `AlpineRuntime`을 사용하지 않습니다.
`:alpine-runtime-android`의 `RuntimeEnvironmentContributor`와
`:alpine-llm-bridge`를 사용합니다. `AlpineLlmBridgeController`가 bridge, runtime session,
Python Gateway의 start/health/stop/restart를 단일 소유하며 stop 시 capability/config를 삭제합니다.

두 실행 경로를 함께 조립하는 앱은 `:alpine-chat-routing`의 `SafeChatRouter`를 사용합니다.
빠른 채팅은 `:alpine-chat-backend-direct`, Alpine 작업은
`:alpine-chat-backend-alpine` adapter로 분리됩니다. Runtime 준비 실패만 Provider dispatch 전에
fallback 승인 대상으로 전달되며 429·5xx·network·stream 오류는 다른 backend를 자동 호출하지
않습니다. 두 adapter는 검증된 Provider idempotency key가 없으므로 capability를 `NONE`으로
선언합니다.

`OAuthHttpLlmBridge`는 Provider adapter가 JSON/headers를 만든 뒤 transport 단계에서만 Authorization을 추가합니다. Adapter가 Authorization을 직접 덮어쓸 수 없고, HTTPS가 아닌 Provider URL은 거부합니다. Claude/Gemini처럼 request/response 형식이 다른 Provider는 `OAuthProviderHttpAdapter`와 필요 시 `OAuthStreamingProviderHttpAdapter`를 구현합니다.

Provider가 401을 반환하면 같은 access token에 대한 refresh를 single-flight로 한 번 수행하고 요청을 한 번만 재시도합니다. 재시도도 401이면 현재 credential을 제거하고 Alpine에는 재로그인 필요 응답을 반환합니다.

동시 completion이 `maxConcurrentRequests`를 넘으면 Host Bridge는 Provider를 호출하지 않고 429, `Retry-After`, `X-Request-Id`를 반환합니다. `/healthz`의 `active_requests`와 `max_concurrent_requests`로 현재 상태를 확인할 수 있습니다.

## Streaming·retry·운영 event

request JSON에 `"stream": true`를 넣으면 Host Bridge는 `text/event-stream`으로 다음 공통 event를 전달합니다.

```text
data: {"type":"start",...}
data: {"type":"delta","text":"..."}
data: {"type":"done",...}
data: [DONE]
```

`UrlConnectionOAuthHttpTransport`는 SSE multiline data, event/전체 크기 제한, coroutine 취소 시 connection disconnect를 처리합니다. `ResilientOAuthHttpTransport`는 408/429/일부 5xx와 `IOException`만 제한적으로 재시도합니다. stream은 HTTP open/status까지만 재시도하며 첫 delta 이후에는 재시도하지 않습니다.

`GatewayEventSink`는 request ID, operation, status, attempt, elapsed time처럼 닫힌 필드만 제공합니다. Provider URL·header·body·exception message·credential을 받을 수 없는 구조이므로 앱의 metric backend에 연결할 수 있습니다.

```kotlin
val sink = GatewayEventSink { event ->
    metrics.record(event.type.name, event.statusCode, event.elapsedMs)
}
```

`/healthz`는 `successful_requests`, `failed_requests`, `overloaded_requests`, `stream_requests`를 추가로 반환합니다. 지표는 Host Bridge start lifecycle마다 초기화됩니다.

## Image·function tools

공통 OpenAI chat 형식의 다음 필드를 Claude와 Gemini 계약으로 변환합니다.

- `content: [{"type":"text",...},{"type":"image_url",...}]`
- top-level `tools`와 `tool_choice`
- assistant `tool_calls`
- `role: "tool"`의 `tool_call_id`, 선택적 `name`

Android Host가 원격 URL을 대신 가져오지 않도록 image는 PNG/JPEG/GIF/WebP의 5 MiB 이하 base64 data URL만 허용합니다. Gemini function response는 message `name`을 우선 사용하고 없으면 `tool_call_id`를 이름으로 사용합니다.

## Claude·Gemini adapter

Claude 계열은 JSON token 교환과 state echo를 설정하고 Messages API adapter를 연결할 수 있습니다. `AnthropicOAuthContract`는 reference-only endpoint, scope, `localhost:54545/callback`, 96-byte PKCE와 OAuth beta를 제공합니다. Client ID는 번들하지 않으며 호출 앱이 사용 권한이 있는 registration을 명시적으로 전달해야 합니다.

```kotlin
val claudeOAuthConfig = AnthropicOAuthContract.providerConfig(
    providerId = "claude",
    clientId = BuildConfig.ANTHROPIC_PUBLIC_CLIENT_ID,
)
val claudeBridge = OAuthHttpLlmBridge(
    AnthropicMessagesOAuthAdapter(
        messagesEndpoint = AnthropicOAuthContract.MESSAGES_ENDPOINT,
        anthropicBeta = AnthropicOAuthContract.OAUTH_BETA,
    ),
)
```

OpenMinis 공개 mirror는 OAuth inference에 필요한 Claude Code 식별 system prompt 값을 의도적으로 제공하지 않습니다. Alpine도 이 값이나 official CLI fingerprint를 추측·위장하지 않습니다. 따라서 login/token exchange 성공은 Messages inference 성공을 보장하지 않습니다.

Gemini generateContent adapter는 OpenAI 공통 role/message를 Gemini `contents`, `systemInstruction`, `generationConfig`로 변환합니다. `GeminiOAuthContract`는 Google 공식 authorization/token endpoint, cloud-platform/userinfo scopes, `localhost:8085/oauth2callback`, offline consent와 고정 generateContent endpoint를 제공합니다. Client ID는 반드시 Alpine 앱 소유 registration을 전달해야 합니다.

```kotlin
val geminiOAuth = OAuthManager(
    context = context,
    config = GeminiOAuthContract.providerConfig(
        providerId = "gemini",
        clientId = BuildConfig.GEMINI_OAUTH_CLIENT_ID,
    ),
)
val geminiBridge = OAuthHttpLlmBridge(
    GeminiGenerateContentOAuthAdapter(
        endpointTemplate = GeminiOAuthContract.GENERATE_CONTENT_ENDPOINT,
        extraHeaders = mapOf("X-Goog-User-Project" to selectedProjectId),
    ),
)
```

OpenMinis 참조 구현은 Gemini CLI client ID와 placeholder client secret을 포함하지만,
그 registration은 Alpine 소유가 아니며 그대로 복사하면 안 됩니다. Google 공식 Gemini
API OAuth 흐름은 사용자 소유 Cloud project에서 OAuth client와 consent screen을 만들고
Generative Language API 및 quota project 권한을 설정해야 합니다. Android OAuth client의
loopback redirect는 deprecated 상태이므로 완전 통합 배포 전 App Link/공식 Android 인증
흐름으로 교체해야 합니다.

OpenAI/Codex 또는 OIDC Provider의 표시용 ID token claim은 다음처럼 encrypted metadata에 병합할 수 있습니다.

```kotlin
val config = existingProviderConfig.copy(
    tokenResponseAdapter = JwtClaimMetadataTokenResponseAdapter(),
)
```

JWT claim 추출은 계정명 표시용이며 signature 검증이나 권한 판단을 대체하지 않습니다.

## Codex account OAuth adapter

Codex account 경로는 범용 `OpenAiCompatibleOAuthAdapter`와 분리되어 있습니다. Host가 소유한 public client registration을 전달하면 고정 callback, form-urlencoded token 교환·갱신, account claim 추출과 Codex Responses 변환을 조합할 수 있습니다. Codex token 요청은 transport 실패에만 최대 3회 backoff 재시도를 적용하며 HTTP/provider 오류는 재시도하지 않습니다.

```kotlin
val codexOAuth = OAuthManager(
    context = context,
    config = CodexOAuthContract.providerConfig(
        providerId = "codex-account",
        clientId = BuildConfig.CODEX_PUBLIC_CLIENT_ID,
    ),
)
val codexBridge = OAuthHttpLlmBridge(
    adapter = CodexResponsesOAuthAdapter(),
    streamingTransport = transport,
    transport = transport,
)
val codexSession = OAuthLlmSession(codexOAuth, codexBridge)
```

이 contract는 `http://localhost:1455/auth/callback`을 정확히 사용하고 fallback port를 허용하지 않습니다. Provider endpoint도 Codex backend로 고정하여 account token이 사용자 입력 relay로 전송되지 않게 합니다. OpenAI 공통 message, inline image와 function tool을 Responses 입력으로 변환하고 text/tool/usage terminal SSE를 공통 Host event로 정규화합니다.

`CodexOAuthContract`에는 public client ID가 포함되어 있지 않습니다. 다른 앱이나 CLI의 client ID를 복사하지 말고 앱 소유자가 사용 권한을 가진 registration을 준비해야 합니다. 현재 자동 테스트는 mock JSON/SSE 계약만 검증하며 실제 ChatGPT 계정 로그인, endpoint 사용 가능성 및 정책 적합성은 별도 E2E 항목입니다.

## xAI Grok account OAuth adapter

`XaiOAuthContract`는 xAI OIDC discovery와 브라우저 OAuth에 필요한 고정 계약을
제공합니다. discovery 문서에서 받은 authorization/token endpoint는 HTTPS와
정확한 `auth.x.ai` host를 모두 만족해야 하며, 임의 relay endpoint를 거부합니다.

```kotlin
val xaiOAuth = OAuthManager(
    context = context,
    config = XaiOAuthContract.providerConfig(
        providerId = "xai-account",
        clientId = BuildConfig.XAI_PUBLIC_CLIENT_ID,
    ),
)
val xaiBridge = OAuthHttpLlmBridge(
    adapter = OpenAiCompatibleOAuthAdapter(
        completionEndpoint = XaiOAuthContract.CHAT_COMPLETIONS_ENDPOINT,
    ),
    streamingTransport = transport,
    transport = transport,
)
```

이 contract는 `http://127.0.0.1:56121/callback`, 32-byte hex PKCE,
OIDC nonce, form-urlencoded token 교환, token 단계의 PKCE challenge echo,
xAI origin만 허용하는 callback CORS preflight와 refresh token rotation 보존을
조합합니다. OAuth token은 고정 xAI inference endpoint 외의 사용자 입력
endpoint로 전송하지 않습니다.

참조 앱의 공개 client ID는 호환성 검증용일 뿐 Alpine 소유 registration이
아닙니다. 배포 앱은 xAI가 발급하거나 사용을 승인한 public client registration을
사용해야 합니다. 브라우저 로그인 성공과 inference 권한은 별개이며 계정·구독
등급에 따라 API 요청이 403으로 거부될 수 있습니다.

## Alpine runtime asset

Runtime 설치와 PRoot process 구현은 `:android` Provider 모듈에서 분리되었습니다.
오프라인 arm64 구성을 사용하는 앱은 다음 모듈을 선택적으로 추가합니다.

```kotlin
implementation(project(":alpine-runtime-api"))
implementation(project(":alpine-runtime-android"))
implementation(project(":alpine-runtime-pack-bundled"))
```

```kotlin
val provider = BundledRuntimeArtifactProvider(
    context,
    Alpine321Arm64Pack.create(),
)
val runtime = DefaultAndroidAlpineRuntimeFactory().create(
    context,
    AndroidRuntimeConfiguration(artifactProvider = provider),
)
runtime.install(RuntimeInstallRequest()).thenCompose {
    runtime.start(RuntimeStartRequest())
}.thenCompose { session ->
    session.execute(
        RuntimeCommandRequest(
            executable = "/bin/sh",
            arguments = listOf("-lc", "python3 --version"),
        ),
    )
}
```

bundled pack은 Alpine 3.21.3 rootfs, packaged PRoot/loader, checksum lock과 SPDX SBOM을 포함합니다.
설치는 staging 후 원자적으로 활성화하며 checksum·ABI·용량·취소·process death 실패 시 기존 활성 runtime을 보존합니다.
host-provided 또는 Ed25519 signed-download 공급은 같은 `RuntimeArtifactProvider` 계약으로 교체할 수 있습니다.

## Manifest 주의

기본 redirect path는 `/oauth/callback`입니다. Codex의 `localhost:1455/auth/callback` filter는 Library Manifest에 포함됩니다. 그 외 Provider가 `/callback`, `/oauth2callback`처럼 다른 고정 경로를 요구하면 앱 Manifest에 같은 Activity의 추가 `<intent-filter>`를 선언해야 합니다. `OAuthProviderConfig.redirectHost`, port, path와 Manifest가 반드시 같아야 합니다.

기본 callback forwarding은 raw loopback socket을 사용하므로 앱 전체에 `usesCleartextTraffic="true"`를 설정할 필요가 없습니다.

## 운영 주의

- Alpine runtime은 Linux kernel/VM이 아니라 Android kernel 위의 Alpine user space입니다.
- 현재 bundled pack은 arm64-v8a 전용입니다. 추가 ABI는 별도 manifest와 native artifact가 필요합니다.
- rootfs 설치와 모든 네트워크/프로세스 호출은 UI thread 밖에서 실행해야 합니다.
- 장시간 runtime을 유지하면 Foreground Service 및 사용자 알림 정책을 적용해야 합니다.
- raw OAuth token은 guest process environment에 전달하지 않습니다. Phase 4 bridge는 credential file/capability 방식으로 연결합니다.
- Provider별 작업은 [Provider OAuth adapter 요구사항](../docs/provider-oauth-adapters.md)을 참고하세요.

## Release artifact

release AAR과 sources JAR를 프로젝트 내부 Maven repository에 생성할 수 있습니다.

```bash
./gradlew \
  :android:assembleRelease \
  :android:publishReleasePublicationToProjectRepository
```

산출물:

```text
android/build/outputs/aar/android-release.aar
android/build/repo/dev/alpine/llm/alpine-llm-android/0.3.0/
```

다른 프로젝트에서 local Maven repository로 사용할 때:

```kotlin
repositories {
    maven { url = uri("/absolute/path/alpine-llm-gateway/android/build/repo") }
}

dependencies {
    implementation("dev.alpine.llm:alpine-llm-android:0.3.0")
}
```

전체 로컬 검증과 배포용 bundle/checksum 생성:

```bash
./scripts/release-local.sh
```

`dist/alpine-llm-android-0.3.0/`에 AAR, sources JAR, POM, Gradle module metadata와 `SHA256SUMS`가 생성됩니다.

## Sample·instrumentation

credential을 포함하지 않는 샘플 앱은 `sample/`에 있습니다.

```bash
./gradlew :sample:assembleDebug
./gradlew :android:assembleDebugAndroidTest
```

instrumentation source는 Android Keystore token 저장/재생성/삭제와 physical-device ABI selector를 검증합니다. 실제 실행에는 emulator 또는 연결된 Android 기기가 필요합니다.

연결된 기기 하나를 명시적으로 선택해 실행하려면:

```bash
ANDROID_SERIAL=<device-serial> ./gradlew :android:connectedDebugAndroidTest
```

2026-07-31 기준 Samsung `SM-S931N`, Android 16(API 36), `arm64-v8a`에서 Provider/Keystore/Host Bridge instrumentation 6건을 실행했습니다. 별도로 2026-08-01에는 같은 삼성폰과 Android 15 arm64 emulator에서 선택형 runtime SDK의 install/start/exec/stop/restart/repair/reset 및 workspace 보존을 검증했습니다. 상세 결과는 [Phase 3 검증 기록](../dev-plan/alpine-runtime-phase3-verification-20260801.md)에 있습니다. 실제 외부 Provider 계정 OAuth/API 검증은 credential이 필요하므로 이 두 로컬 검증에 포함되지 않습니다.
