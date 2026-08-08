# Provider 공식 정책 재검토 — 2026-08-09

검토 일시: `2026-08-09 KST`

이 문서는 실제 Provider 계정·OAuth token·API key를 사용하지 않은 공개 문서 재검토다. 따라서
Provider별 model 접근 권한, 모바일 public-client 승인, endpoint별 idempotency와 실제 fault 응답은
계속 `NOT_RUN` 또는 `NOT_CONFIRMED`다.

## 결론

- Android direct/Alpine Gateway backend는 계속 `ChatBackendIdempotency.NONE`으로 유지한다.
- 429·5xx·I/O·SSE 중단은 UI가 **사용자의 Retry**를 제안할 수 있어도 transport 자동 재전송의
  근거가 되지 않는다. Provider 문서의 SDK backoff 안내도 server-side deduplication 보장이 아니다.
- `X-Client-Request-Id`, `request-id`, batch 요청 식별자는 support correlation 또는 batch 범위의
  용도이며 실시간 inference 재전송 권한으로 해석하지 않는다.
- 모델 문서는 catalog 변동·tier·계정 권한을 보여 주는 참고 자료일 뿐 현재 로그인 profile의
  사용 가능 모델을 증명하지 않는다. profile의 명시 선택과 실계정 당일 확인을 유지한다.

## 공식 문서 근거

| Provider | 모델·권한 확인 근거 | 오류·재시도 확인 근거 | 실시간 inference idempotency 판정 |
|---|---|---|---|
| OpenAI | [Models](https://developers.openai.com/api/docs/models), [Models API](https://developers.openai.com/api/reference/models) | [API overview / request debugging](https://developers.openai.com/api/reference/overview#debugging-requests)는 `x-request-id`, rate-limit headers, `X-Client-Request-Id`를 상관관계·지원 조회 용도로 설명한다. | Chat/Responses 생성의 dedupe header·TTL·충돌 동작을 이 검토에서 확인하지 못했다. `NOT_CONFIRMED` |
| Anthropic | [Models overview](https://platform.claude.com/docs/en/about-claude/models/overview), [Models API](https://platform.claude.com/docs/en/api/models) | [Errors](https://platform.claude.com/docs/en/api/errors)는 429/500/529, SDK backoff 및 SSE가 HTTP 200 뒤에도 error가 날 수 있음을 설명한다. 모든 응답의 `request-id`는 correlation용이다. | Messages 실시간 생성의 일반 idempotency key·TTL·충돌 동작을 이 검토에서 확인하지 못했다. `NOT_CONFIRMED` |
| Gemini | [Models](https://ai.google.dev/gemini-api/docs/models), [rate limits](https://ai.google.dev/gemini-api/docs/rate-limits)는 model/tier/account에 따라 제한이 달라짐을 설명한다. | [Troubleshooting](https://ai.google.dev/gemini-api/docs/troubleshooting)는 429/503에 exponential backoff를 권장하며 SDK 기본 retry도 설명한다. | `generateContent` 실시간 생성의 dedupe key·TTL·충돌 동작을 이 검토에서 확인하지 못했다. `NOT_CONFIRMED` |
| xAI | [Models](https://docs.x.ai/developers/models), [rate limits](https://docs.x.ai/developers/rate-limits)는 모델별 tier 제한을 설명한다. | rate-limit 문서는 HTTP 429와 backoff를 안내한다. [Batch API](https://docs.x.ai/developers/advanced-api-usage/batch-api)는 `batch_request_id`의 idempotency 용도를 batch에 한정한다. | Chat inference의 idempotency key·TTL·충돌 동작을 이 검토에서 확인하지 못했다. `NOT_CONFIRMED` |

## 승격 규칙

`PROVIDER_VERIFIED_KEY` 또는 자동 재전송은 Provider별로 **모두** 충족할 때만 검토한다.

1. 앱 소유자 승인 계정에서 사용할 정확한 inference endpoint와 인증 grant가 확정돼야 한다.
2. 공식 문서 또는 Provider owner 계약이 header/key 이름, 적용 endpoint, TTL/scope, 동일 key 충돌
   응답을 명시해야 한다.
3. Android direct·Alpine Gateway의 실제 forwarding 경로가 그 key를 누락·변형하지 않는 unit/fake
   transport 검증을 통과해야 한다.
4. 물리 기기 실계정 E2E에서 timeout·429·5xx·first-token 전후 SSE 단절·Stop 이후 재시도를
   redacted evidence로 확인해야 한다.
5. 새 retry가 배포 전 정책상 허용된 횟수·취소·비용 표기·감사 경계를 지키는지 보안/Provider
   owner가 승인해야 한다.

그 전까지 `ChatBackendRequest.idempotencyKey`는 검증된 미래 Provider adapter가 사용할 수 있는
형식 검증 필드일 뿐, 현재 adapter가 Provider header로 보내거나 router가 요청을 replay한다는 뜻이
아니다.

## 현 상태

| 항목 | 상태 |
|---|---|
| 공식 문서 재검토 | `PASS` |
| 자동 재전송 차단 regression | `PASS` (local unit test) |
| 실제 모델 목록·권한·OAuth/API E2E | `NOT_RUN` |
| 429·5xx·비정상 SSE 실제 Provider/fault proxy 검증 | `NOT_RUN` |
| Provider별 실시간 inference idempotency 계약 | `NOT_CONFIRMED` |
| 공개 배포 승인 | `BLOCKED` |
