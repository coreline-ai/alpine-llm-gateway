# mobile_agent_llm_transport

MobileAgent Flutter UI와 Android/iOS native authorized transport 사이의 공통 계약입니다.

- `LlmStreamRequest`: Provider, 승인 model, bounded message, request ID
- `NativeLlmTransport.start`: native SSE 시작
- `NativeLlmTransport.cancel`: mobile socket/task 즉시 취소 후 BFF `202/404/오류` 확인 결과 반환
- `NativeLlmTransport.requestState`: `preparing`, `streaming`, `cancelling`, `notFound` local 상태 확인
- `LlmCancelResult`: local cancel과 server acknowledgment를 분리해 성공 오보고 방지
- `LlmStreamEvent`: `start`, `delta`, `usage`, `done`, `cancelled`, `error`

Access/refresh token, Provider API key, Authorization header는 Dart 모델에 존재하지 않습니다.
`BFF_BASE_URL`은 query/fragment/userinfo가 없는 HTTPS URL만 허용합니다. 실제 Android/iOS 구현은
`mobile_agent_auth` plugin에 함께 등록되어 같은 native AppAuth state를 사용합니다.

Stop은 native 실행을 먼저 즉시 끊습니다. 이후 `accepted`, `notActive`, `notRequired`, `unavailable`
중 하나를 반환하며, `unavailable`은 local cancel 완료를 server cancel 완료로 간주하지 않습니다.

```bash
flutter analyze
flutter test
```
