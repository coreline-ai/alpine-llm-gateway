# Provider OAuth adapter 요구사항

이 문서는 기존 compatibility Provider 구현과 신규 MobileAgent 제품 경로를 분리한다. Endpoint,
client ID와 scope는 Provider 정책과 앱 소유 registration에 따라 관리하며 다른 앱/CLI 값을 고정하지 않는다.

## MobileAgent Android/iOS 제품 결정

Android/iOS Flutter 제품의 기본 출시 인증 경로는 **MobileAgent OIDC Authorization Code + PKCE → MobileAgent LLM BFF → Provider 공식 API**로 고정한다.

- OpenAI·Anthropic·xAI server credential은 BFF secret boundary에만 저장한다.
- 모바일은 MobileAgent OAuth token만 Android Keystore/iOS Keychain에 저장하고 raw token을 Dart나 Alpine Guest에 전달하지 않는다.
- OpenAI는 공식 Responses API, Anthropic은 공식 Messages API, xAI는 공식 inference API를 사용한다.
- ChatGPT/Claude/Grok consumer subscription이나 공식 CLI client ID를 MobileAgent 제품 인증으로 재사용하지 않는다.
- Provider 직접 OAuth는 MobileAgent 명의 native client registration과 inference 권한을 Provider가 승인한 경우에만 조건부로 활성화한다.
- Android/iOS 실제 계정에서 `login → stream → cancel → refresh → restart → logout/revoke`가 모두 통과하기 전에는 지원 완료로 표시하지 않는다.

통합 구현 순서와 완료 기준은 [Android/iOS Flutter OAuth 실동작 계획](../dev-plan/implement_20260804_202612.md)을 따른다.

## 현재 사실 기준선

| 영역 | 현재 상태 | 제품 판정 |
|---|---|---|
| Android OAuth core | 기존 compatibility core와 신규 AppAuth/Keystore Flutter plugin 구현 | local build/test PASS |
| Android Provider UI | copied client 기본값 제거, 명시적 앱 소유 client만 입력 | reference-only |
| Flutter | 공통 앱·OAuth 계약·LLM transport·Run Card 구현 | local widget/contract PASS |
| iOS | AppAuth-iOS, Keychain vault, native URLSession SSE/cancel 구현 | Simulator build PASS |
| OIDC/BFF | Keycloak PKCE/refresh/revoke와 3개 공식 API server adapter 구현 | single-instance local PASS |
| 실제 Provider E2E | 외부 Provider key/account가 없어 호출하지 않음 | `NOT_RUN` |
| 외부 배포 | OAuth provenance review와 프로젝트 license gate가 남음 | 차단 |

기존 `CodexOAuthContract`·`XaiOAuthContract` 등은 Android reference/compatibility 자산이다. Flutter
MobileAgent 제품은 이 모듈을 의존하지 않고 AppAuth→MobileAgent OIDC→BFF만 사용한다.

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
- 기존 Claude compatibility contract, 96-byte PKCE, JSON state echo
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

위 표의 direct OAuth 조합은 reference/compatibility 상태다. Flutter 제품의 필수 출시 범위는 다음과 같다.

| 제품 Provider | 모바일 인증 | Provider 호출 인증 | 필수 실제 검증 |
|---|---|---|---|
| OpenAI | MobileAgent OIDC + PKCE | BFF의 OpenAI API key | Responses stream/cancel/usage/오류 |
| Anthropic | MobileAgent OIDC + PKCE | BFF의 API key 또는 승인된 workload identity federation | Messages stream/cancel/usage/오류 |
| xAI | MobileAgent OIDC + PKCE | BFF의 xAI API key Bearer | inference stream/cancel/usage/오류 |

제품 UI는 공식 Provider API 연결과 consumer account OAuth를 구분한다. Provider가 승인하지 않은
`chatgpt.com/backend-api`, Claude Code 식별 prompt/CLI fingerprint 또는 Grok CLI registration은
release artifact에 포함하지 않는다.

## 다음 구현 우선순위

1. MobileAgent 소유 HTTPS issuer/BFF domain과 Android/iOS public client registration 확정
2. Redis multi-instance request ownership/cancel/revocation과 backend secret manager 배포
3. Android App Link·iOS Universal Link signing association 및 실기기 OAuth E2E
4. OpenAI·Anthropic·xAI 실제 staging secret/billing cap으로 stream/cancel E2E
5. cancel server ACK, delete account, auth state stream과 accessibility/lifecycle GUI E2E
6. Gemini direct OAuth와 Kimi device flow는 앱 소유 공식 registration의 별도 후속 계획으로 분리

## 배포 전 필수 검증

- MobileAgent OIDC issuer에 Android/iOS native client, exact redirect와 PKCE requirement가 등록됐는지 확인
- Android App Link/Digital Asset Links와 iOS Universal Link/AASA가 production signing identity와 연결되는지 확인
- APK/IPA에 confidential client secret과 OpenAI·Anthropic·xAI API key가 포함되지 않는지 확인
- VPN, proxy, Private DNS 환경에서 system browser와 native token exchange가 성공하는지 확인
- Android/iPhone의 process 종료·재시작, Keystore/Keychain invalidation과 refresh token rotation을 검증
- OAuth/Provider 응답 원문, Authorization header, PKCE verifier, prompt와 token이 Dart·Guest·로그·crash report에 포함되지 않는지 확인
- 세 Provider의 actual stream/cancel/refresh/logout signed redacted E2E report를 확인
- Android Alpine 기능을 포함하는 배포에서는 rootfs/PRoot 공급망과 ABI별 실행 테스트를 별도로 수행
