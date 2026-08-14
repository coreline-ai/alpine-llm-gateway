# Alpine Chat Provider Android

Android 앱에서 공통 Chat Feature와 OAuth Provider 연결을 조립하는 재사용 AAR이다.
`demo-chatbot`과 `integrated-app`이 같은 구현을 사용하며 Alpine Runtime은 포함하지 않는다.

## 포함 범위

- Claude, Gemini, OpenAI-compatible, Codex, xAI Provider profile 모델과 검증
- Provider profile 추가·편집·삭제와 로그인·로그아웃 Compose Activity
- profile-owned 모델 후보 catalog, legacy migration, 기본 모델과 후보 활성/비활성 편집
- Android OAuth session 생성과 Provider 응답의 공통 `ChatBackendDelta` 변환
- 연결된 계정·모델을 `ChatViewModel`에 공급하는 `DirectChatHostController`
- 기존 demo app의 `demo_llm_profiles` 저장소 이름을 유지하는 update compatibility

## Host 사용

```kotlin
val chat = ViewModelProvider(this, chatFactory)[ChatViewModel::class.java]
val directProvider = DirectChatHostController(this, chat)

override fun onResume() {
    super.onResume()
    directProvider.refreshConnections()
}

// Compose callbacks
AlpineChatScreen(
    state = state,
    onSelectModel = directProvider::selectModel,
    onSend = directProvider::send,
    // 나머지 대화 callback은 ChatViewModel에 연결한다.
)
```

Provider 관리 화면은 `ProviderProfilesActivity`를 명시적 Intent로 연다. Host는 `onStop`에서
대화 저장을 flush하고 `onDestroy`에서 controller를 닫아야 한다.

## 모델 카탈로그 계약

- `ProviderProfile.model`은 기본 모델이며 enabled 후보 중 하나여야 한다.
- 후보는 `PROVIDER_APPROVED`, `USER_ADDED`, `LEGACY_MIGRATED` source와 enabled 상태를 저장한다.
- catalog 없는 이전 JSON은 기존 model을 잃지 않고 legacy 후보 하나로 migration한다.
- descriptor와 model-specific session에는 enabled 후보만 노출한다.
- 후보를 disable하거나 profile을 삭제하면 controller refresh가 stale model session을 cancel/remove한다.
- 후보 source는 계정·region·tier entitlement 증명이 아니다. production non-Gemini profile은 사용자가
  모델 ID를 직접 추가해야 한다.

## 보안 경계

- OAuth token은 Android token store에 남고 Chat Feature나 Alpine Guest로 전달하지 않는다.
- Provider 원문 body, endpoint, credential과 예외 문자열을 공통 UI·로그에 기록하지 않는다.
- 앱 패키지가 다르면 Android sandbox 때문에 다른 앱의 profile/token을 자동으로 읽을 수 없다.
  동일 application ID의 update install은 기존 저장소와 암호화 key alias를 유지한다.
- 실제 Provider 계정 승인과 모델 정책 E2E는 앱 소유 registration으로 별도 수행한다.
- 저장 대화가 unavailable model을 가리키면 자동으로 기본 모델로 바꾸거나 Send/Retry하지 않는다.

## 검증과 발행

```bash
./gradlew \
  :alpine-chat-provider-android:testDebugUnitTest \
  :alpine-chat-provider-android:assembleRelease \
  :alpine-chat-provider-android:lintDebug

ANDROID_SERIAL=R3CY40PXCAP \
  ./gradlew :alpine-chat-provider-android:connectedDebugAndroidTest
```

2026-08-14 Samsung `R3CY40PXCAP`의 격리된 test package에서 catalog 편집·저장, malformed migration,
encrypted conversation restore, unavailable model 보존, compact layout와 IME·200% font를 포함한
`18/18` connected test가 통과했다. 이 결과는 실제 Provider 계정 승인 E2E를 의미하지 않는다.

발행 좌표는 `dev.alpine.llm:alpine-chat-provider-android:0.3.0`이다.
