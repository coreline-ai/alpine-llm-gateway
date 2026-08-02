# Alpine Integrated App

재사용 SDK를 한 Android 앱에 조립하는 제품 통합 Host다.

## 현재 모드

| 모드 | 현재 동작 |
|---|---|
| 빠른 채팅 | 공통 Chat Feature, Android OAuth Provider 관리, 모델 선택, stream·Stop·retry, 대화 복원 |
| Alpine 작업 | Runtime 상태·설치·복구·터미널·패키지 화면. 공통 채팅의 Gateway routing 연결은 Phase 3 범위 |

실행 모드는 대화별로 저장된다. 생성 중 Alpine 작업 화면으로 이동해도 Provider 요청을
취소하거나 다시 보내지 않으며, 빠른 채팅으로 돌아와 Stop할 수 있다. Runtime의 설치·실패
상태는 Android 직접 Provider 빠른 채팅 경로를 차단하지 않는다.

## Provider 시작 순서

1. 빠른 채팅 우측 상단의 `LLM connections`를 연다.
2. Provider profile을 추가하고 앱 소유 OAuth registration으로 로그인한다.
3. 빠른 채팅으로 돌아와 계정과 모델을 선택한다.
4. 메시지를 보내고 필요하면 Stop 또는 redacted 오류의 Retry를 사용한다.

`demo-chatbot`과 `integrated-app`은 서로 다른 application ID이므로 Android private profile과
token을 자동 공유하지 않는다. 통합 앱 안에서 새 profile을 만들거나 향후 명시적 사용자 승인
migration 기능을 별도 설계해야 한다.

## 검증

```bash
./gradlew :integrated-app:assembleDebug :integrated-app:assembleDebugAndroidTest :integrated-app:lintDebug
ANDROID_SERIAL=<device-serial> ./gradlew :integrated-app:connectedDebugAndroidTest
```

instrumentation은 실제 credential을 사용하지 않는 fake Provider로 로그인→모델 선택→stream→
모드 왕복→Stop→503→retry와 새 대화→이전 대화→process 재시작 복원을 검증한다. 실제 계정
OAuth/API 승인은 Phase 9의 opt-in E2E에서 별도 검증한다.
