# Alpine Chat Feature

Android 앱에서 재사용할 수 있는 공통 채팅 Feature 모듈이다. `demo-chatbot`의 대화 상태와
Compose UI를 Host/Provider 구현에서 분리하며, Alpine Runtime 없이도 단독으로 빌드된다.

## 포함 범위

- 다중 대화, 대화별 draft와 생성 상태
- AES-GCM 기반 대화 저장소와 process 재시작 복원
- Provider/모델 선택 상태
- Skill·Persona 선택과 system instruction 구성
- 스트리밍, 중단, 재시도와 redacted 오류 UI
- Markdown, 대화 기록과 공통 Compose 채팅 화면
- Provider 원문 event를 받지 않는 `ChatBackendSession` 계약

## Host 책임

이 모듈은 OAuth, Provider profile CRUD, 실제 HTTP 호출과 Alpine Runtime을 포함하지 않는다.
Host 앱은 `ChatBackendSession`을 구현하고 연결 목록을 `ChatBackendConnection`으로 공급한다.

```kotlin
val descriptor = ChatBackendDescriptor(
    profileId = "account-1",
    label = "Provider account",
    model = "model-id",
)

val session = object : ChatBackendSession {
    override val descriptor = descriptor

    override suspend fun stream(requestJson: String): ChatBackendStreamResult =
        ChatBackendStreamResult(events = providerTextDeltas)
}
```

Provider adapter는 raw body, URL, header, credential 또는 예외 문자열을 Feature로 넘기지 않고
`ChatBackendDelta`와 닫힌 `ChatBackendFailureCode`로 정규화해야 한다.

## 검증

```bash
./gradlew \
  :alpine-chat-feature:testDebugUnitTest \
  :alpine-chat-feature:assembleRelease \
  :alpine-chat-feature:lintDebug
```

발행 좌표는 `dev.alpine.llm:alpine-chat-feature:0.3.0`이다. 발행 AAR은
`alpine-chat-routing`만 project dependency로 가지며 rootfs, PRoot, PTY와 Python Gateway
payload를 포함하지 않는다.
