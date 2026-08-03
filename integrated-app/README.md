# Alpine Integrated App

재사용 SDK를 한 Android 앱에 조립하는 제품 통합 Host다.

## 현재 모드

| 모드 | 현재 동작 |
|---|---|
| 빠른 채팅 | 공통 Chat Feature, Android OAuth Provider 관리, 모델 선택, stream·Stop·retry, 대화 복원 |
| Alpine 작업 | 공통 Gateway 채팅, Runtime·HostBridge·Python Gateway 통합 상태, 시작·상태·재시작·종료·복구, 터미널·패키지 도구 |

실행 모드는 대화별로 저장된다. 생성 중 Alpine 작업 화면으로 이동해도 Provider 요청을
취소하거나 다시 보내지 않으며, 빠른 채팅으로 돌아와 Stop할 수 있다. Runtime의 설치·실패
상태는 Android 직접 Provider 빠른 채팅 경로를 차단하지 않는다.

Alpine 채팅은 `IntegratedApplication`이 소유하는 단일 Bridge lifecycle을 사용한다. OAuth token은
Android Provider session 밖으로 이동하지 않으며 Alpine Guest에는 15분 TTL capability 파일과
loopback endpoint만 전달된다. Provider 또는 모델이 바뀌면 이전 Bridge process를 닫고 capability를
교체한다. Alpine 준비가 dispatch 전에 실패한 경우에도 사용자가 대화상자에서 승인한 해당 요청만
빠른 채팅으로 보낼 수 있고, Provider dispatch 이후에는 자동 재전송하지 않는다.

## Provider 시작 순서

1. 빠른 채팅 우측 상단의 `LLM connections`를 연다.
2. Provider profile을 추가하고 앱 소유 OAuth registration으로 로그인한다.
3. 빠른 채팅으로 돌아와 계정과 모델을 선택한다.
4. 메시지를 보내고 필요하면 Stop 또는 redacted 오류의 Retry를 사용한다.

## Alpine 작업 시작 순서

1. Provider profile에 로그인하고 사용할 모델을 선택한다.
2. 상단에서 `Alpine 작업`을 선택한다.
3. 처음 사용하는 경우 `터미널·도구`에서 Runtime을 설치한다.
4. `Alpine LLM Gateway` 카드에서 `시작`을 누르고 통합 health가 정상이 되는지 확인한다.
5. `Gateway 채팅`에서 메시지를 보내거나 `터미널·도구`에서 terminal/package 기능을 사용한다.

키보드가 열린 동안에는 작은 화면에서도 답변과 오류가 가려지지 않도록 Alpine 상태 카드와
sub-navigation을 임시로 접는다. 키보드를 닫으면 다시 표시된다.

`demo-chatbot`과 `integrated-app`은 서로 다른 application ID이므로 Android private profile과
token을 자동 공유하지 않는다. 통합 앱 안에서 새 profile을 만들거나 향후 명시적 사용자 승인
migration 기능을 별도 설계해야 한다.

## 검증

```bash
./gradlew :integrated-app:assembleDebug :integrated-app:assembleDebugAndroidTest :integrated-app:lintDebug
ANDROID_SERIAL=<device-serial> ./gradlew :integrated-app:connectedDebugAndroidTest
./gradlew :alpine-llm-bridge-probe:assembleDebug
./scripts/runtime/run-llm-bridge-probe-device.sh <arm64-device-serial>
```

instrumentation은 실제 credential을 사용하지 않는 fake Provider로 로그인→모델 선택→stream→
모드 왕복→Stop→503→retry, 새 대화→이전 대화→process 재시작 복원과 Alpine fallback
승인·거절 전 direct Provider 미호출을 검증한다. 장치 probe는 실제 PRoot Runtime, loopback
HostBridge, bundled Python Gateway, `llmctl`, stream·cancel과 capability 회전을 검증한다.
실제 계정 OAuth/API 승인은 Phase 9의 opt-in E2E에서 별도 검증한다.
