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

Library Manifest가 `INTERNET` permission과 기본 `/oauth/callback` Activity intent filter를 병합합니다.

## 보안 경계

```text
Alpine llmctl
  └─ ALPINE_LLM_SESSION_TOKEN (임시 로컬 capability)
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
val providerBridge = OAuthHttpLlmBridge(
    OpenAiCompatibleOAuthAdapter(
        completionEndpoint = "https://provider.example.com/v1/chat/completions",
        extraHeaders = mapOf("X-Provider-Version" to "1"),
    ),
)
val session = OAuthLlmSession(oauth, providerBridge)
val hostBridge = HostBridgeServer(
    maxConcurrentRequests = 4,
    overloadRetryAfterSeconds = 1,
    requestExecutor = session::complete,
)
val endpoint = hostBridge.start()

val alpine = AlpineRuntime(context)
alpine.attachHostBridge(endpoint)

// Dispatchers.IO 등 background context에서 실행
val result = alpine.exec(
    "llmctl run --model provider-model --prompt 'hello'",
)

// 앱 lifecycle 종료 시
hostBridge.stop()
alpine.detachHostBridge()
```

`OAuthHttpLlmBridge`는 Provider adapter가 JSON/headers를 만든 뒤 transport 단계에서만 Authorization을 추가합니다. Adapter가 Authorization을 직접 덮어쓸 수 없고, HTTPS가 아닌 Provider URL은 거부합니다. Claude/Gemini처럼 request/response 형식이 다른 Provider는 `OAuthProviderHttpAdapter`를 구현합니다.

Provider가 401을 반환하면 같은 access token에 대한 refresh를 single-flight로 한 번 수행하고 요청을 한 번만 재시도합니다. 재시도도 401이면 현재 credential을 제거하고 Alpine에는 재로그인 필요 응답을 반환합니다.

동시 completion이 `maxConcurrentRequests`를 넘으면 Host Bridge는 Provider를 호출하지 않고 429, `Retry-After`, `X-Request-Id`를 반환합니다. `/healthz`의 `active_requests`와 `max_concurrent_requests`로 현재 상태를 확인할 수 있습니다.

## Claude·Gemini adapter

Claude 계열은 JSON token 교환과 state echo를 설정하고 Messages API adapter를 연결할 수 있습니다. Endpoint, client id, scope는 앱 설정에서 주입해야 합니다.

```kotlin
val claudeOAuthConfig = OAuthProviderConfig(
    providerId = "claude",
    authorizationEndpoint = BuildConfig.CLAUDE_AUTH_ENDPOINT,
    tokenEndpoint = BuildConfig.CLAUDE_TOKEN_ENDPOINT,
    clientId = BuildConfig.CLAUDE_CLIENT_ID,
    scopes = BuildConfig.CLAUDE_SCOPES.split(" "),
    callbackPort = 54545,
    redirectPath = "/callback",
    tokenRequestEncoding = OAuthTokenRequestEncoding.JSON,
    tokenRequestAdapter = OAuthTokenRequestAdapter { context ->
        if (context.grantType == OAuthTokenGrantType.AUTHORIZATION_CODE) {
            context.parameters + ("state" to requireNotNull(context.state))
        } else {
            context.parameters
        }
    },
)
val claudeBridge = OAuthHttpLlmBridge(
    AnthropicMessagesOAuthAdapter(
        messagesEndpoint = BuildConfig.CLAUDE_MESSAGES_ENDPOINT,
        anthropicBeta = BuildConfig.CLAUDE_BETA.takeIf { it.isNotBlank() },
    ),
)
```

Gemini generateContent adapter는 OpenAI 공통 role/message를 Gemini `contents`, `systemInstruction`, `generationConfig`로 변환합니다.

```kotlin
val geminiBridge = OAuthHttpLlmBridge(
    GeminiGenerateContentOAuthAdapter(
        endpointTemplate = BuildConfig.GEMINI_ENDPOINT_TEMPLATE,
        extraHeaders = mapOf("X-Goog-User-Project" to selectedProjectId),
    ),
)
```

OpenAI/Codex 또는 OIDC Provider의 표시용 ID token claim은 다음처럼 encrypted metadata에 병합할 수 있습니다.

```kotlin
val config = existingProviderConfig.copy(
    tokenResponseAdapter = JwtClaimMetadataTokenResponseAdapter(),
)
```

JWT claim 추출은 계정명 표시용이며 signature 검증이나 권한 판단을 대체하지 않습니다.

## Alpine runtime asset

앱의 `src/main/assets`에 다음 파일이 필요합니다.

```text
alpine-rootfs.tar.gz
proot-aarch64
```

```kotlin
val alpine = AlpineRuntime(context)
alpine.installIfNeeded()

// background context에서 실행
val result = alpine.exec("python3 --version")
```

이 저장소는 rootfs/PRoot 바이너리를 배포하지 않습니다. 사용할 Alpine release와 PRoot binary의 checksum·서명·라이선스를 앱 빌드 파이프라인에서 검증해야 합니다.

`exec()`는 stdout을 별도 thread에서 계속 drain하므로 timeout이 출력 대기 때문에 멈추지 않습니다. 출력 제한을 넘기면 `ExecResult.outputTruncated`가 `true`가 됩니다. rootfs extractor는 tar checksum, 경로 traversal, entry/총 크기 제한과 실행 권한을 검증하며 교체 실패 시 이전 rootfs를 복원합니다.

## Manifest 주의

기본 redirect path는 `/oauth/callback`입니다. Provider가 `/callback`, `/auth/callback`, `/oauth2callback`처럼 다른 고정 경로를 요구하면 앱 Manifest에 같은 Activity의 추가 `<intent-filter>`를 선언해야 합니다. `OAuthProviderConfig.redirectPath`와 Manifest path가 반드시 같아야 합니다.

기본 callback forwarding은 raw loopback socket을 사용하므로 앱 전체에 `usesCleartextTraffic="true"`를 설정할 필요가 없습니다.

## 운영 주의

- `AlpineRuntime`은 Linux kernel/VM이 아니라 Android kernel 위의 Alpine user space입니다.
- `proot-aarch64`는 arm64 전용입니다. 지원 ABI별 실행 파일과 asset 선택 로직이 추가로 필요합니다.
- rootfs 설치와 모든 네트워크/프로세스 호출은 UI thread 밖에서 실행해야 합니다.
- 장시간 runtime을 유지하면 Foreground Service 및 사용자 알림 정책을 적용해야 합니다.
- session token은 process environment에 있으므로 다른 앱과 공유되는 외부 저장소나 로그에 환경 전체를 출력하면 안 됩니다.
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
android/build/repo/dev/alpine/llm/alpine-llm-android/0.2.0/
```

다른 프로젝트에서 local Maven repository로 사용할 때:

```kotlin
repositories {
    maven { url = uri("/absolute/path/alpine-llm-gateway/android/build/repo") }
}

dependencies {
    implementation("dev.alpine.llm:alpine-llm-android:0.2.0")
}
```
