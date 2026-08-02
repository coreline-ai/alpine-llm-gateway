# Integrated MVP Phase 2 빠른 채팅 검증

작성 일시: `2026-08-02 KST`

## 구현 결과

- `:alpine-chat-provider-android`를 추가해 Provider profile/OAuth UI/session/direct host를
  `demo-chatbot`과 `integrated-app`이 함께 사용하게 했다.
- `integrated-app`의 빠른 채팅 안내 화면을 실제 `AlpineChatScreen`으로 교체했다.
- 대화별 실행 모드, Provider·모델, draft, history와 assistant 설정을 동일 ViewModel에서 복원한다.
- 스트리밍 중 모드 왕복을 허용하되 생성 job을 취소하거나 재전송하지 않는다.
- Provider 오류 로그에서 원문 예외 문자열을 제거했다.

## 검증 결과

| 검증 | 결과 |
|---|---|
| Python 정책·경계 테스트 | PASS, 99 tests |
| 공통 Chat Feature JVM | PASS, 43 tests |
| Android Provider JVM | PASS, 17 tests |
| demo ChatViewModel JVM | PASS, 29 tests |
| integrated-app Samsung instrumentation | PASS, 2 tests |
| Provider module / integrated lint·test APK | PASS |
| SDK local Maven publication | PASS, 19 artifacts |
| published consumer release/lint/payload matrix | PASS, 8 variants |
| no-runtime published consumer Samsung 실행 | PASS |
| demo update-install profile·대화 digest / reload | PASS |

Samsung 검증 기기는 `SM-S931N`, Android 16이다. instrumentation의 Provider 로그인과 오류는
credential-free fake이며 실제 Provider 계정·과금 API를 호출하지 않았다. update-install 검증은
signed-out Codex OAuth profile과 production 암호화 대화 저장소를 만든 뒤 private data 전체의
digest가 동일하고 profile이 다시 로드되는지 확인했다. 민감 데이터 내용은 출력하지 않았다.

## 남은 범위

- 실제 Provider 계정 OAuth/API와 현재 모델 정책 승인은 Phase 9 opt-in E2E다.
- Alpine Runtime·HostBridge·Python Gateway를 공통 채팅 Backend로 연결하는 작업은 Phase 3다.
- `demo-chatbot`과 `integrated-app`은 Android sandbox가 다른 별도 앱이므로 private token을 자동
  이관하지 않는다. 현재는 동일 application ID update compatibility만 보장한다.
