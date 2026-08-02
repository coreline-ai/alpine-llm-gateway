# Alpine Runtime Phase 2 검증 결과

검증 일자: `2026-08-01 KST`

## 구현 결과

| 항목 | 결과 |
|---|---|
| 앱 중립 공개 계약 | `:alpine-runtime-api`에 lifecycle/install/artifact/session/command/terminal/health/event 계약 추가 |
| Android 경계 | `Context`를 `:alpine-runtime-android`의 factory에만 허용 |
| LLM 연결 SPI | loopback endpoint와 credential file 경로만 전달하는 environment contributor 추가 |
| 선택 UI | `:alpine-runtime-ui-compose`를 별도 모듈로 추가 |
| 테스트 지원 | deterministic dispatcher, fake runtime, virtual artifact, event recorder 추가 |
| 호환성 감시 | `javap` 기반 공개 API dump와 Gradle 검증 task 추가 |
| 구조 감시 | Python architecture test와 GitHub CI module task 추가 |

## 검증 결과

- SDK 5개 모듈의 JVM/Android 단위 테스트 통과
- Android library release AAR 빌드 통과
- Compose 선택 UI release AAR 빌드와 Lint 통과
- Java host smoke test와 Kotlin API test 통과
- 공개 API dump에서 Android/Compose 타입 노출 없음
- 기존 Python Gateway를 포함한 전체 Python 테스트 `57개` 통과
- 빠른 채팅 앱은 신규 Alpine SDK 모듈에 의존하지 않음

## 수정한 구현 이슈

1. 로컬 실행 JBR은 Java 21이지만 별도 Java 17 toolchain이 등록되어 있지 않아 JVM 모듈을 Java 17 bytecode target 방식으로 수정했다.
2. Java `Void` 완료를 generic scheduler에서 `null`로 반환할 수 없는 Kotlin 타입 오류를 전용 void scheduler로 분리했다.

## 다음 단계

후속 Phase 3에서 기존 `AlpineRuntime`의 installer/process 코드가 `:alpine-runtime-android`로 이동했고, bundled artifact 공급·원자적 설치·복구·reset이 공개 계약에 연결되었다.
