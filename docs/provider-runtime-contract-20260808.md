# Provider runtime contract review — 2026-08-08

이 문서는 Provider별 실계정 E2E 전까지 적용할 안전한 retry·model·request correlation
기준이다. 구현이 검증하지 않은 Provider-side deduplication을 가정하지 않는다.

## 공통 결론

- 대화 생성과 SSE는 **사용자가 누르는 재시도만** 새 요청으로 보낸다. timeout, 429, 5xx,
  stream 단절 뒤 자동 replay는 하지 않는다. 같은 prompt의 비용·side effect·중복 응답을
  안전하게 dedupe할 공통 Provider 계약이 확인되지 않았기 때문이다.
- 429·503은 UI에 stable failure로 표시한다. Provider가 명시한 경우에만 bounded exponential
  backoff 정책을 E2E에서 검증하며, background retry는 prompt 재전송으로 해석되지 않게 한다.
- Provider가 보내는 request ID는 redacted diagnostic correlation에만 사용한다. prompt,
  OAuth credential, HTTP Authorization, raw Provider body를 기록하지 않는다.
- 모델은 앱 기본값이 아니라 로그인한 profile의 명시적 선택값을 사용한다. 계정·region·tier와
  preview lifecycle에 따라 model list/권한이 달라질 수 있으므로 실제 목록과 선택 가능 여부는
  E2E 당일 다시 확인한다.

## Provider별 확인 결과

| Provider | 공식 확인 내용 | 현재 앱 정책 | Idempotency 판정 |
|---|---|---|---|
| OpenAI | [`X-Client-Request-Id`](https://platform.openai.com/docs/api-reference/debugging-requests)는 request correlation/log lookup 용도이며, `x-request-id`와 rate-limit header가 제공된다. | 고유 correlation ID는 지원하되, 생성 요청 자동 replay는 금지한다. API key는 Android/guest에 넣지 않는다. | Chat/Responses 생성의 dedupe 보장 header는 이 검토에서 확인하지 못함. `NOT_CONFIRMED` |
| Anthropic | [API overview](https://platform.claude.com/docs/en/api/overview)는 Models API와 모든 response의 `request-id`를, [Errors](https://platform.claude.com/docs/en/api/errors)는 429 rate limit을 명시한다. SDK retry 존재가 server dedupe를 뜻하지는 않는다. | `request-id`로만 상관관계를 남기고 429/5xx는 사용자가 재시도한다. | Messages 생성에 대한 일반 idempotency key/dedupe 계약을 이 검토에서 확인하지 못함. `NOT_CONFIRMED` |
| Gemini | [rate limits](https://ai.google.dev/gemini-api/docs/rate-limits)는 project/model/tier별 quota와 429를, [troubleshooting](https://ai.google.dev/gemini-api/docs/troubleshooting)은 429·5xx에 exponential backoff/jitter를 권장한다. | SDK 또는 adapter retry는 취소·중복 생성 E2E가 통과한 뒤에만 활성화한다. 현재는 명시적 재시도만 허용한다. | `generateContent` request dedupe key 계약을 이 검토에서 확인하지 못함. `NOT_CONFIRMED` |
| xAI | [rate limits](https://docs.x.ai/developers/rate-limits)는 429와 exponential backoff를 안내한다. [Batch API](https://docs.x.ai/developers/advanced-api-usage/batch-api)의 `batch_request_id` idempotency는 batch 범위다. | 실시간 chat stream은 batch idempotency를 적용하지 않고 자동 replay를 금지한다. | batch 외 실시간 chat 생성 dedupe 계약은 이 검토에서 확인하지 못함. `NOT_CONFIRMED` |

## Model policy

1. Gemini의 번들 후보 목록은 UI convenience일 뿐 account/region/tier 권한의 근거가 아니다.
2. Gemini 외 direct Provider는 승인된 catalog를 번들하지 않고 profile에 사용자가 명시한 model
   하나만 선택 후보로 보여준다. 비어 있는 model은 validation에서 fail-closed 한다.
3. 실제 model list가 확인되면 기존 선택이 계속 허용되는지 재검증하고, 허용되지 않으면 사용자가
   다시 골라야 한다.
4. preview·experimental model은 stable model과 별도 표기하고 release QA target에 고정하지 않는다.
5. model ID·provider·profile revision을 실패 correlation에만 기록하고 user prompt는 기록하지 않는다.

## E2E 승인 질문

각 Provider·계정·선택 model마다 아래를 redacted report로 답해야 한다.

1. model list/권한/region이 실제 profile과 일치하는가?
2. 첫 token 전 429·5xx와 첫 token 후 SSE 단절이 어떤 safe UI 상태로 끝나는가?
3. Stop 직후 재시도가 실제로 하나의 새 request만 만드는가?
4. timeout 뒤 같은 request를 transport가 자동 replay하지 않는가?
5. Provider support가 제공한 request ID만으로 raw prompt 없이 trace 가능한가?
6. Provider가 명시적으로 idempotency key를 지원한다면 endpoint·header·TTL·scope·충돌 응답을
   인용해 이 표를 `CONFIRMED`로 갱신했는가?

## 상태

- 문서 조사: `PASS` (2026-08-08 확인)
- 실계정 model/OAuth/API/SSE/429/5xx E2E: `NOT_RUN`
- Provider별 inference idempotency 계약: 위 표 외 `NOT_CONFIRMED`
- 공개 배포 승인: `BLOCKED`
