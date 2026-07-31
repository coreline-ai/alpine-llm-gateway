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
- ID token의 표시용 account/plan/email claim metadata adapter
- Host Bridge bounded concurrency, overload 429, request ID

## Provider별 후속 작업

| Provider 유형 | 현재 조합 가능한 설정 | 추가 구현이 필요한 부분 |
|---|---|---|
| Claude/Anthropic 계열 | JSON token request, state echo, Messages request/response adapter, version/beta header | 공식 client registration, 실제 endpoint/scope/account 검증 |
| OpenAI/Codex 계열 | JSON token request, extra authorization params, ID token account/plan metadata | account header 규칙, 공식 client registration과 실제 endpoint 검증 |
| Gemini/Google 계열 | form token request, BODY client auth, generateContent adapter, offline/consent params | userinfo 조회, GCP project discovery/onboarding 및 실제 계정 검증 |
| Kimi 계열 | refresh coordinator와 encrypted token store 재사용 가능 | Authorization Code가 아닌 RFC 8628 Device Authorization Grant, polling interval/`slow_down`, device identity 구현 |
| xAI 계열 | request adapter로 nonce/challenge echo 가능, token metadata 저장 가능 | OIDC discovery, `*.x.ai` endpoint allowlist 검증, nonce 검증, callback CORS preflight, ID token claim adapter |

## 구현 우선순위 제안

1. 실제 앱에서 가장 먼저 지원할 Provider 하나를 정하고 client registration/redirect URI를 확정한다.
2. 해당 Provider의 `OAuthProviderConfig`, `OAuthTokenRequestAdapter`, `OAuthTokenResponseAdapter`를 앱 모듈에 구현한다.
3. 현재 adapter 설정으로 mock inference response와 공통 OpenAI 응답을 검증한다.
4. mock token endpoint로 authorization/refresh HTTP 계약을 추가 검증한다.
5. 실제 계정 테스트는 debug build와 별도 test tenant에서 수행하고 token/body logging을 금지한다.

## 다음 구현 우선순위

1. 실제 Provider 하나의 client registration과 physical-device E2E
2. upstream token delta를 Host Bridge/Alpine까지 전달하는 SSE streaming
3. Gemini userinfo/GCP project discovery
4. 앱 process death와 Keystore invalidation instrumentation test
5. Kimi device flow 또는 xAI OIDC discovery

## 배포 전 필수 검증

- Provider console에 등록한 redirect URI와 runtime의 port/path가 정확히 일치하는지 확인
- Android 앱에 confidential client secret이 포함되지 않는지 확인
- VPN, proxy, Private DNS 환경에서 Custom Tab과 앱 token exchange가 같은 네트워크 경로로 성공하는지 확인
- 앱 process 종료/재시작, Keystore key invalidation, refresh token rotation을 실제 기기에서 검증
- OAuth/Provider 응답 원문, Authorization header, PKCE verifier, session token이 로그·crash report에 포함되지 않는지 확인
- rootfs/PRoot 공급망 검증과 ABI별 실행 테스트 수행
