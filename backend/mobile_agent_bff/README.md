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
.venv/bin/uvicorn app.main:app --host 127.0.0.1 --port 8081
```

Provider key와 model allowlist를 secret manager/environment로 함께 주입해야 해당 Provider가
`configured: true`가 됩니다. Key가 없으면 fail-closed입니다.

## 구현된 경계

- OIDC discovery/JWKS cache와 RS256 `iss`·`aud`·`azp`·`exp`·`iat`·`sub` 검증
- OpenAI Responses, Anthropic Messages, xAI chat completions 공식 HTTPS adapter
- normalized SSE, request/model/prompt bound, per-user concurrency와 duplicate request 차단
- client disconnect 또는 cancel endpoint에서 upstream coroutine/httpx socket 즉시 취소
- Provider credential·prompt·원문 Provider error 비로그, docs/openapi 비활성화

현재 request owner/cancel/revocation registry는 **단일 BFF process의 memory 구현**입니다. 여러 replica
운영 전 Redis request ownership·cancel pub/sub·quota/revocation TTL로 교체해야 합니다.

```bash
.venv/bin/pytest -q
```
