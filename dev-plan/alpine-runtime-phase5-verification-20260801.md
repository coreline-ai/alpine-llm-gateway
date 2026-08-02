# Alpine Runtime Phase 5 검증 기록

- 검증일: `2026-08-01 KST`
- 대상: 두 모드 chat routing, 승인 fallback, request ledger, mode migration

## 구현 결과

| 영역 | 결과 |
|---|---|
| 공통 계약 | `:alpine-chat-routing`에 Android 없는 request/stream/failure/mode/audit 계약 구현 |
| 빠른 채팅 | `:alpine-chat-backend-direct`가 기존 Android OAuth/Provider stream을 공통 event로 변환 |
| Alpine 작업 | `:alpine-chat-backend-alpine`이 runtime 상태·Bridge lifecycle·Python Gateway를 변환 |
| fallback | runtime 준비 실패 + 사용자 승인 전만 허용, dispatch 이후 자동 fallback 금지 |
| 중복 방지 | 동시 실행 및 완료된 request ID 재사용을 bounded ledger로 거부 |
| 저장 | conversation schema 3/index schema 2에 mode 저장, 구버전은 `FAST_CHAT` migration |
| audit | mode/backend/model/fallback/first delta/terminal 상태만 기록하고 원문 오류·prompt 제외 |

## 자동 검증

- Alpine 준비 실패 승인 후 direct backend가 정확히 한 번 호출된다.
- fallback 거부 시 Provider stream이 시작되지 않고 새 request ID로 재시도 가능하다.
- 첫 delta 이후 및 delta 전 Provider dispatch 실패 모두 direct backend를 자동 호출하지 않는다.
- 같은 request ID의 동시 실행과 완료 후 replay가 거부된다.
- conversation/workspace mode store가 scope를 분리한다.
- schema 1·2 conversation과 schema 1 index가 `FAST_CHAT`으로 migration된다.
- 기존 빠른 채팅 ViewModel, OAuth stream/retry/model 선택과 대화 저장 테스트가 통과한다.

최종 검증에서는 Python/architecture `70`개가 모두 통과했고 CI와 동일한 Gradle 범위
`889`개 task가 `BUILD SUCCESSFUL`로 완료됐다. 신규 두 Android backend의 unit/release AAR/lint,
기존 Android/Runtime/Bridge/Gateway pack/UI/Testkit/Sample/Demo/Probe를 함께 검증했다.

Samsung `SM-S931N`에는 `adb install -r`로 Phase 5 demo APK만 덮어써 기존 앱 데이터와 OAuth
저장소를 보존했다. 설치가 성공했고 `dev.alpine.llm.demo/.MainActivity` process/resumed 상태를
확인했다. 실제 두 모드 selector/fallback dialog device E2E는 Phase 6 통합 Host UI 범위다.

## Idempotency 결정

공통으로 검증된 upstream Provider idempotency key가 없으므로 현재 두 adapter는
`ChatBackendIdempotency.NONE`이다. Router는 Provider dispatch 이후 backend를 바꾸거나 같은
request를 replay하지 않는다. Provider별 key 지원은 계약과 실제 header 전달을 각각 검증한
뒤 별도 capability로 활성화한다.

## 다음 단계

Phase 6에서 Compose mode selector, fallback 승인 화면, 상태·복구·terminal UI와 통합 sample
host를 동일 routing 계약 위에 조립한다.
