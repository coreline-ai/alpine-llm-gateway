# 통합 MVP Phase 3 Alpine 작업 모드 검증

- 검증일: `2026-08-03 KST`
- 범위: `integrated-app`의 Runtime·HostBridge·Python Gateway·공통 채팅 통합
- 외부 Provider credential: 사용하지 않음
- 실기기: Dreamus `PD20`, Android 12/API 31, `arm64-v8a`
- Samsung `SM-S931N`: 이번 변경 시점에는 ADB 미연결

## 구현 결과

| 영역 | 결과 |
|---|---|
| Application 단일 owner | `IntegratedAlpineLlmHost`가 Runtime·HostBridge·Gateway lifecycle을 소유 |
| Provider 경계 | Android `ChatCompletionSession`에서만 OAuth/Provider transport를 실행 |
| Guest credential | 15분 TTL capability 파일과 loopback endpoint만 전달 |
| Model 정책 | 현재 선택 모델 1개만 fail-closed allowlist로 Gateway에 제공 |
| 공통 채팅 | `RoutedChatBackendSession`이 기존 `SafeChatRouter`를 `ChatBackendSession`으로 조립 |
| Runtime 공유 | Bridge가 소유한 session을 `RuntimeHostController`가 non-owning 방식으로 bind |
| Alpine UI | Gateway 상태, 시작·health·재시작·종료, 채팅/도구 전환과 runtime 복구 action 연결 |
| Fallback | preparation 실패·사용자 승인·dispatch 전 조건에서만 Android direct 경로 허용 |
| IME 대응 | 채팅 키보드가 열린 동안 Alpine 전용 chrome을 접어 답변·오류 표시 영역 확보 |

## 자동 검증

### JVM·빌드·lint

다음 대상의 unit test, APK/test APK assemble과 lint가 통과했다.

```bash
./gradlew \
  :alpine-chat-feature:testDebugUnitTest \
  :alpine-chat-provider-android:testDebugUnitTest \
  :alpine-runtime-host:test \
  :alpine-llm-bridge:testDebugUnitTest \
  :alpine-chat-backend-direct:testDebugUnitTest \
  :alpine-chat-backend-alpine:testDebugUnitTest \
  :integrated-app:assembleDebug \
  :integrated-app:assembleDebugAndroidTest \
  :integrated-app:lintDebug
```

- 결과: `BUILD SUCCESSFUL`
- routing request JSON·delta 보존 통과
- fallback 거절 시 direct 미호출 통과
- Alpine dispatch 이후 direct replay 금지 통과
- 외부 Runtime session bind 해제와 owner 비이관 통과
- HostBridge normalized stream 경계 통과
- Gateway crash, capability 만료·회전, protocol mismatch와 Gateway 조기 종료 fail-closed 통과

### arm64 통합 앱 instrumentation

```bash
ANDROID_SERIAL=<redacted> ./gradlew :integrated-app:connectedDebugAndroidTest
```

- 결과: `4 tests`, `0 failed`
- 빠른 채팅 login→model→stream→Stop→503→retry 회귀 통과
- 대화별 mode와 process 재생성 복원 통과
- Alpine fallback 승인 전 direct request `0`회 확인
- fallback 승인 후 해당 요청만 direct dispatch 확인
- fallback 거절 후 direct request `0`회와 redacted 복구 UI 확인

### 실제 Alpine Bridge 장치 probe

```bash
./scripts/runtime/run-llm-bridge-probe-device.sh <redacted>
```

- 결과: `success=true`, elapsed `14845 ms`
- Gateway `0.3.0`, protocol `1`
- `models`, non-stream run, `start/delta/done` stream 통과
- cancel 수락과 guest process cleanup 통과
- capability 회전과 config 내 capability 값 부재 확인
- `owner_running`, `runtime_session`, `gateway_process`, `capability_file`, `capability_ttl`,
  `host_bridge`, `python_gateway`, `protocol` health 전부 통과
- stop 뒤 Runtime lifecycle `READY`

### 전체 로컬 release 회귀

```bash
PYTHON_BIN=python3.11 ./scripts/release-local.sh
```

- Python `99 tests` 통과
- SDK publication `19개` 검증 통과
- published consumer `8개` assemble/R8/lint matrix 통과
- `no-runtime` consumer의 Runtime payload 비포함 검증 통과
- Gradle 9 readiness 경고 `0개`
- 내부 SDK bundle 생성 성공
- x86_64는 연결 emulator가 없어 `SKIP_NO_X86_64_EMULATOR`

## 발견 이슈와 수정

arm64 instrumentation에서 Alpine 상태 카드·sub-navigation·공통 채팅 화면이 함께 표시된 상태로
IME가 열리면 답변과 오류 banner의 viewport가 부족한 문제가 재현됐다. 테스트 assertion만
완화하지 않고, Alpine 채팅 중 IME가 보일 때 상태 카드와 sub-navigation을 임시로 접도록
수정했다. 이후 fallback 승인·거절을 포함한 4개 실기기 test가 모두 통과했다.

## 보안·동작 경계

- OAuth access/refresh token, Provider URL/header/body와 raw exception 문자열은 Guest state와 UI에 없다.
- Provider session은 normalized HostBridge stream만 공개한다.
- capability는 app-private workspace에 생성되고 stop/restart/model 변경 시 삭제·회전된다.
- Provider dispatch 또는 첫 delta 이후 Runtime 실패는 direct Backend로 자동 재전송되지 않는다.
- 실제 Provider 계정, 의도적 429·5xx와 Provider별 운영 승인은 이 검증에 포함하지 않았다.

## 현재 판정

Phase 3의 코드 구현과 일반 arm64 내부 MVP 검증은 완료됐다. 다만 계획에서 지정한 Samsung
`SM-S931N`의 두 모드 왕복·Alpine 응답 재검증은 기기 미연결로 남아 있다. 따라서 Phase 3의
기술 구현은 완료로 보되 Samsung 전용 실기기 gate와 실제 Provider 계정 gate는 완료로 표시하지
않는다. 공개 배포는 프로젝트 license, source mirror, Provider 승인, Play track, 파괴적 Samsung
lifecycle 승인과 배포 책임자 gate 때문에 계속 `No-Go`다.
