# Alpine Runtime 0.x 모듈 이전 가이드

## 결정

기존 `:android`의 `dev.alpine.llm.AlpineRuntime`, `AlpineRuntimeAssetSet`과
`attachHostBridge()` API는 호환 facade 없이 제거한다. 프로젝트가 아직 0.x인 동안
runtime 책임을 영구 모듈 경계로 바로잡기 위한 의도적인 breaking change다.

호환 facade를 `:android`에 남기면 빠른 채팅만 사용하는 앱도 runtime process 코드 또는
rootfs/PRoot payload를 전이해서 받을 수 있으므로 모듈 분리 목적과 충돌한다.

## 교체 방법

| 이전 API | 새 API |
|---|---|
| `AlpineRuntime(context)` | `DefaultAndroidAlpineRuntimeFactory().create(context, configuration)` |
| `installIfNeeded()` | `AlpineRuntimeManager.install(RuntimeInstallRequest())` |
| `exec(command)` | `start()` 후 `RuntimeSession.execute(RuntimeCommandRequest(...))` |
| `installationStatus()` | `currentState()` 또는 `health()` |
| `stop()` | `RuntimeSession.stop()` 또는 `AlpineRuntimeManager.stop()` |
| `AlpineRuntimeAssetSet` | `RuntimeArtifactManifest` + `RuntimeArtifactProvider` |
| `attachHostBridge()` | `LlmBridgeEndpointRegistry` + `LlmBridgeEnvironmentContributor` + `AlpineLlmBridgeController` |

## 기본 bundled 구성

```kotlin
val provider = BundledRuntimeArtifactProvider(
    context,
    Alpine321Arm64Pack.create(),
)
val runtime = DefaultAndroidAlpineRuntimeFactory().create(
    context,
    AndroidRuntimeConfiguration(artifactProvider = provider),
)
```

앱은 `:alpine-runtime-api`, `:alpine-runtime-android`와 원하는 artifact provider만
의존한다. 오프라인 arm64 payload가 필요한 앱만 `:alpine-runtime-pack-bundled`를 추가한다.

외부 앱은 저장소 project path 대신 `dev.alpine.llm:alpine-runtime-*:0.3.0` Maven 좌표를
사용한다. bundled runtime을 포함하면 application ABI를 `arm64-v8a`로 제한하고
`useLegacyPackaging=true`를 유지해야 native executable 경로를 확보할 수 있다.

## 두 모드 routing 이전

Phase 5부터 Host 앱은 실행 모드를 `ChatExecutionMode.FAST_CHAT` 또는
`ChatExecutionMode.ALPINE_WORKSPACE`로 명시한다. 공통 계약은 `:alpine-chat-routing`, 기존
Android Provider adapter는 `:alpine-chat-backend-direct`, Alpine Gateway adapter는
`:alpine-chat-backend-alpine`에 있다.

기존 저장 대화에는 실행 모드가 없으므로 conversation schema 1·2와 index schema 1을 읽을
때 `FAST_CHAT`으로 migration한다. fallback은 Alpine 준비 실패와 사용자 승인 전까지로
제한하며 Provider dispatch 이후에는 같은 요청을 다른 backend로 재전송하지 않는다.
