# MobileAgent OAuth 구현 검증 기록

검증 일시: `2026-08-04 KST`

대상: Flutter Android/iOS MobileAgent OAuth 로그인, native secure token vault, native authorized SSE,
MobileAgent LLM BFF, OpenAI·Anthropic·xAI server adapter와 local OIDC fixture.

## 구현 결과

| 영역 | 구현 위치 | 판정 |
|---|---|---|
| Flutter 앱/GUI | `apps/mobile_agent` | `PASS_LOCAL` |
| Flutter OAuth 계약 | `packages/mobile_agent_auth` | `PASS_LOCAL` |
| Flutter LLM transport 계약 | `packages/mobile_agent_llm_transport` | `PASS_LOCAL` |
| Android AppAuth/Keystore/SSE | `packages/mobile_agent_auth/android` | `PASS_BUILD` |
| iOS AppAuth/Keychain/SSE | `packages/mobile_agent_auth/ios` | `PASS_SIMULATOR_BUILD` |
| 기기 로컬 ConversationStore | Flutter + Android Keystore/iOS Keychain vault | `PASS_LOCAL_AND_BUILD` |
| OIDC/JWT BFF | `backend/mobile_agent_bff` | `PASS_SINGLE_PROCESS` |
| Keycloak Authorization Code + PKCE | `backend/dev-idp` | `PASS_LOCAL_REAL_FLOW` |
| 외부 Provider 실계정 | 외부 staging 필요 | `NOT_RUN` |

## 실행한 검증

| 검증 | 결과 |
|---|---|
| `packages/mobile_agent_auth` Flutter analyze/test | 0 issue, 14 PASS |
| `packages/mobile_agent_llm_transport` Flutter analyze/test | 0 issue, 7 PASS |
| `apps/mobile_agent` Flutter analyze/widget/controller/store test | 0 issue, 16 PASS |
| Android native OAuth configuration unit test | 6 PASS |
| 기존 Android OAuth/reference regression | Gradle 2개 module `BUILD SUCCESSFUL` |
| BFF pytest | 21 PASS |
| 저장소 Python regression | 101 PASS, 4 SKIP, 45 subtests PASS |
| Keycloak health/realm import | HTTP 200, realm import success |
| 실제 local OIDC flow | login, PKCE code, BFF audience, refresh rotation, replay reject, revoke PASS |
| BFF smoke | health 200, unauthenticated session 401, docs 404 |
| Android Flutter build | `app-debug.apk` PASS |
| iOS Flutter Simulator build | `Runner.app` PASS |
| copied-client/private-endpoint/secret source scan | PASS |
| 압축 APK 내부 scan | PASS |
| Simulator `.app` scan | PASS |

### 기기 로컬 대화 vault 추가 검증

- Flutter snapshot schema는 최근 20개 대화, 대화당 64개 메시지, 메시지 32 KiB, 전체 1 MiB로
  fail-closed 검증한다.
- Dart contract test는 한글 검색, unknown/token-like field 거부, 과대 메시지 거부와 native channel에
  `schemaVersion`/`payload`만 전달되는지를 검증했다.
- Android Debug APK와 iOS Simulator `Runner.app`을 새 Keystore AES/GCM·Keychain/CryptoKit vault
  소스까지 포함해 컴파일했다.
- release scanner는 source, `app-debug.apk`, Simulator `Runner.app`에서 OAuth secret/forbidden
  provider fingerprint가 없는지 다시 통과했다.

이는 **native encrypted file의 실제 read/write를 Android 실기기 또는 실제 iPhone에서 수행한 결과는
아니다**. device lock, Keychain/Keystore invalidation, low storage, reinstall과 encrypted vault recovery는
실기기 release E2E에서 별도로 기록해야 한다.

Keycloak 검증은 mock callback 성공으로 대체하지 않았다. 실제 로그인 HTML form에 개발 계정으로
로그인하고 Authorization Code를 PKCE verifier로 교환한 뒤 access token의 `iss`·`aud`·`azp`·
`sub`·`iat`·`exp`, offline refresh rotation, 이전 refresh token replay 거부와 revoke를 확인했다.
Token 값은 검증 출력과 문서에 기록하지 않았다.

## 핵심 보안 계약

- 모바일 client secret과 OpenAI·Anthropic·xAI API key는 APK/IPA/Dart asset에 넣지 않는다.
- Android/iOS raw access/refresh/ID token은 MethodChannel/EventChannel 반환 타입에 없다.
- Android는 Keystore AES/GCM, iOS는 Keychain `WhenUnlockedThisDeviceOnly`로 AppAuth state를 저장한다.
- native transport는 플랫폼별 shared AppAuth state를 사용해 concurrent refresh를 하나의 state에서 처리한다.
- BFF는 RS256과 exact issuer/audience/authorized-party를 검증하며 JWKS는 issuer same-host HTTPS만 허용한다.
- Stop은 mobile socket/task를 즉시 취소하고 BFF cancel endpoint의 `202/404/오류`를 native에서
  `accepted/notActive/unavailable`로 구분한다. Flutter는 local cancel과 server 확인을 별도로 표시한다.
- native auth protocol capability와 redacted auth event stream이 Android/iOS에서 같은 payload를 사용한다.
- 앱 resume에서 local request가 사라졌으면 자동 재전송하지 않는다.
- ConversationStore는 OAuth token vault와 별개로 user/assistant 텍스트만 암호화하며, local delete는
  server/Provider account 삭제로 표시하지 않는다.
- copied third-party client registration, consumer private endpoint, CLI fingerprint와 probable secret을
  source뿐 아니라 APK/AAB/IPA 압축 entry 내부까지 검사한다.

## 아직 완료로 판정하지 않는 항목

| 항목 | 이유 |
|---|---|
| Android/iOS 실제 OAuth 로그인 | MobileAgent 소유 HTTPS issuer/domain/client registration 미제공 |
| Android App Link/iOS Universal Link | signing SHA-256, Apple Team ID, association domain 미제공 |
| OpenAI·Anthropic·xAI 실제 stream | Provider account/key/billing cap/secret owner 미제공 |
| 실제 iPhone | Apple signing과 기기 검증 필요 |
| BFF multi-instance | Redis request ownership/cancel/revocation/분산 quota 미구현 |
| Play Internal/TestFlight | release signing, privacy/legal/notice gate 필요 |
| Built-in Kotlin | 현재 Flutter 3.44.8, 활성화는 Flutter 공식 가이드상 3.47+ 필요 |

## 실 OAuth 페이지 확인 절차

### Local 실제 Keycloak 페이지

```bash
make dev-up
python3 scripts/verify-dev-oidc-flow.py
```

이 fixture는 실제 OAuth server/login page이지만 HTTP local 개발용이다. MobileAgent 앱은 보안상 HTTP
issuer를 거부하므로 local 결과를 Android/iOS 제품 E2E로 집계하지 않는다.

### Android/iOS 제품 페이지

MobileAgent 소유 staging issuer에 public native client와 exact callback을 등록한 뒤 다음처럼 실행한다.

```bash
cd apps/mobile_agent
flutter run \
  --dart-define=OIDC_ISSUER=https://<owned-auth-domain>/<issuer-path> \
  --dart-define=OIDC_CLIENT_ID=<owned-public-native-client-id> \
  --dart-define=OIDC_REDIRECT_URI=ai.coreline.mobileagent:/oauth/callback \
  --dart-define=OIDC_AUDIENCE=mobile-agent-bff \
  --dart-define=BFF_BASE_URL=https://<owned-bff-domain> \
  --dart-define=OPENAI_MODEL=<approved-model> \
  --dart-define=ANTHROPIC_MODEL=<approved-model> \
  --dart-define=XAI_MODEL=<approved-model>
```

앱의 `OAuth 로그인 페이지 열기`를 누르면 Android Custom Tab 또는 iOS
`ASWebAuthenticationSession`에서 **실제 issuer 로그인 페이지**가 열린다. 완료 증거는
`login → callback → session restore → Provider stream → Stop → refresh → restart → logout/revoke`
전체 흐름으로 기록한다.
