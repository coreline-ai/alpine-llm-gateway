# MobileAgent LLM BFF

Android/iOS MobileAgent access token을 검증하고 OpenAI·Anthropic·xAI 공식 API를 호출하는
server-side credential boundary입니다. Provider credential과 prompt/response body를 로그로
기록하지 않습니다.

## Local run

```bash
python3.11 -m venv .venv
.venv/bin/pip install -e '.[test]'
OIDC_ISSUER=https://your-owned-issuer.example/realms/mobileagent \
OIDC_AUDIENCE=mobile-agent-bff \
OIDC_ALLOWED_AZP=mobile-agent-native \
MAX_PROVIDER_EVENT_BYTES=1048576 \
MAX_PROVIDER_STREAM_BYTES=33554432 \
.venv/bin/uvicorn app.main:app --host 127.0.0.1 --port 8081
```

Provider key와 model allowlist를 secret manager/environment로 함께 주입해야 해당 Provider가
`configured: true`가 됩니다. Key가 없으면 fail-closed입니다.

## 구현된 경계

- OIDC discovery/JWKS cache와 RS256 `iss`·`aud`·`azp`·`exp`·`iat`·`sub` 검증
- OpenAI Responses, Anthropic Messages, xAI chat completions 공식 HTTPS adapter
- strict UTF-8 normalized SSE, `text/event-stream`, event 1 MiB·stream 32 MiB 기본 상한
- request/model/prompt bound, per-user concurrency와 duplicate request 차단
- Provider 401/403/404/429/5xx·timeout·disconnect·malformed SSE를 고정 오류 code로 redaction
- client disconnect 또는 cancel endpoint에서 upstream coroutine/httpx socket 즉시 취소
- Provider credential·prompt·원문 Provider error 비로그, docs/openapi 비활성화

stream이 열린 뒤에는 자동 retry하지 않습니다. 크기 초과·잘못된 content type·invalid UTF-8·JSON이
발생하면 `provider_stream_too_large` 또는 `provider_stream_invalid`로 종료하고 Provider body는 반환하지
않습니다. event 상한은 전체 stream 상한보다 클 수 없으며 잘못된 환경 변수는 앱 시작 시 거부됩니다.

현재 request owner/cancel/revocation registry는 **단일 BFF process의 memory 구현**입니다. 여러 replica
운영 전 Redis request ownership·cancel pub/sub·quota/revocation TTL로 교체해야 합니다.

```bash
.venv/bin/pytest -q
```

현재 결정론 fault matrix는 39개 BFF 테스트로 HTTP status, no-retry, UTF-8 chunk split,
invalid UTF-8, multiline SSE, event/stream 크기, timeout과 HTTP 200 이후 redacted error event를 검증합니다.
