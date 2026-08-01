# Provider OAuth adapter 요구사항

이 문서는 OpenMinis의 Provider별 구현에서 확인한 차이를 독립 모듈의 확장 지점에 대응시킨다. Endpoint, client id, scope는 Provider 정책에 따라 바뀔 수 있으므로 라이브러리에 고정하지 않는다.

## 현재 공통 모듈이 처리하는 부분

- Authorization Code + PKCE(S256), state 검증, loopback callback
- form-urlencoded 또는 JSON token request
- 동적 token request parameter adapter
- 표준/비표준 token response adapter와 encrypted metadata 저장
- 만료 5분 전 refresh, Provider별 single-flight, refresh token rotation 보존
- `invalid_grant` credential 정리와 transient failure 시 아직 유효한 access token 유지
- Android Keystore AES/GCM 저장 및 복호화 실패 시 재로그인 상태
- Host Bridge session token과 OAuth Provider token의 분리
- OpenAI-compatible completion HTTP adapter
- Anthropic Messages 및 Gemini generateContent protocol adapter
- OpenMinis 참조 기반 Claude compatibility contract, 96-byte PKCE, JSON state echo
- OpenAI-compatible, Anthropic, Gemini SSE event normalization
- Codex account 전용 고정 OAuth contract와 Responses request/response/SSE normalization
- xAI Grok OIDC discovery, exact-host 검증, hex PKCE, nonce, challenge echo와 CORS preflight
- Claude/Gemini inline image와 function tool request/response 변환
- ID token의 표시용 account/plan/email claim metadata adapter
- Host Bridge bounded concurrency, overload 429, request timeout, request ID와 누적 지표
- retry/backoff/`Retry-After`, circuit breaker와 closed-schema 운영 event

## Provider별 후속 작업

| Provider 유형 | 현재 조합 가능한 설정 | 추가 구현이 필요한 부분 |
|---|---|---|
| Claude/Anthropic 계열 | `AnthropicOAuthContract`, `localhost:54545/callback`, 96-byte PKCE, JSON state echo, Messages request/response/SSE adapter, image/tool 변환, version/OAuth beta header | 앱 소유·승인 client registration, 공개 source에 없는 Claude Code 식별 prompt를 위장하지 않는 공식 inference 계약, 실제 endpoint/scope/account 검증 |
| OpenAI-compatible 계열 | form/JSON token request, extra authorization params, 표준 Chat Completions adapter | 공식 client registration과 실제 endpoint/scope 검증 |
| Codex account 계열 | `localhost:1455/auth/callback`, form-urlencoded exchange/refresh, transport 오류 3회 backoff, account metadata header, 고정 Codex Responses text/image/tool/SSE adapter | 앱 소유 client registration, 실제 ChatGPT 계정·endpoint·정책 E2E, 동의 페이지 장애 시 device-code 대체 흐름 |
| Gemini/Google 계열 | `GeminiOAuthContract`, Google 공식 auth/token endpoint, `localhost:8085/oauth2callback`, app-owned public client, generateContent/SSE adapter, inlineData/function 변환, 최신 text/chat 모델 선택, offline/consent params | 앱 소유 client ID·quota project 주입, Android App Link callback 전환, 실제 계정 검증 |
| Kimi 계열 | refresh coordinator와 encrypted token store 재사용 가능 | Authorization Code가 아닌 RFC 8628 Device Authorization Grant, polling interval/`slow_down`, device identity 구현 |
| xAI Grok 계열 | `XaiOAuthContract`, `127.0.0.1:56121/callback`, exact `auth.x.ai` discovery 검증, hex PKCE, nonce, challenge echo, 제한된 CORS preflight, Chat Completions/SSE와 ID token 표시 claim | 앱 소유 client registration, 계정 등급별 403·실제 모델 접근 검증, device-code 대체 흐름 |

## 구현 우선순위 제안

1. 실제 앱에서 가장 먼저 지원할 Provider 하나를 정하고 앱 소유 client registration/redirect URI를 확정한다.
2. 해당 Provider의 `OAuthProviderConfig`, `OAuthTokenRequestAdapter`, `OAuthTokenResponseAdapter`를 앱 모듈에 구현한다.
3. 현재 adapter 설정으로 mock inference response와 공통 OpenAI 응답을 검증한다.
4. mock token endpoint로 authorization/refresh HTTP 계약을 추가 검증한다.
5. 실제 계정 테스트는 debug build와 별도 test tenant에서 수행하고 token/body logging을 금지한다.

## 다음 구현 우선순위

1. 실제 Provider 하나의 client registration과 physical-device E2E
2. Gemini userinfo/GCP project discovery
3. 앱 process death와 Keystore invalidation instrumentation test를 emulator/실기기에서 실행
4. Kimi device flow 또는 xAI device-code 대체 흐름
5. 실제 Provider tool/image/stream physical-device E2E

## 배포 전 필수 검증

- Provider console에 등록한 redirect URI와 runtime의 port/path가 정확히 일치하는지 확인
- Android 앱에 confidential client secret이 포함되지 않는지 확인
- VPN, proxy, Private DNS 환경에서 Custom Tab과 앱 token exchange가 같은 네트워크 경로로 성공하는지 확인
- 앱 process 종료/재시작, Keystore key invalidation, refresh token rotation을 실제 기기에서 검증
- OAuth/Provider 응답 원문, Authorization header, PKCE verifier, session token이 로그·crash report에 포함되지 않는지 확인
- rootfs/PRoot 공급망 검증과 ABI별 실행 테스트 수행
