# Alpine LLM Chat Demo

`demo-chatbot`은 `:android` 라이브러리를 직접 사용하는 별도 Android 테스트 앱이다. 저장된 OAuth LLM 프로필 가운데 인증된 연결을 선택하고, 선택한 한 Provider에만 현재 대화와 새 메시지를 스트리밍 요청한다.

UI는 Material 3 Compose로 구현되어 light/dark theme, Android 12+ dynamic color,
840dp 최대 콘텐츠 폭과 작은 가로 화면의 스크롤을 지원한다. 세부 token,
컴포넌트, 접근성 및 안정적인 test tag 계약은 [디자인 가이드](DESIGN.md)를
참고한다.

## 포함된 GUI

`LLM connections`에서 `Add LLM`을 누르면 OpenMinis의 Provider 선택·구성 흐름을 참고한 카드형 선택 화면이 열린다.

| 유형 | 공통 입력 외 추가 입력 | 요청 adapter |
|---|---|---|
| Claude / Anthropic | Anthropic beta header(선택) | Anthropic Messages |
| Google Gemini | Google Cloud project ID(선택), `{model}` endpoint template | Gemini generateContent |
| OpenAI-compatible | Provider의 chat completions endpoint | OpenAI chat completions |

모든 프로필은 표시명, authorization/token/LLM HTTPS endpoint, OAuth public client ID, scopes, 모델, loopback callback port를 입력한다. 같은 유형을 여러 번 추가하면 `Claude 2`, `Gemini 2`처럼 다음 표시명을 제안한다.

저장 후 프로필 행에서 다음 작업을 할 수 있다.

- OAuth 연결·재연결
- 연결 상태 확인
- 로그아웃
- 설정 수정
- 프로필과 해당 OAuth credential 삭제

authorization/token endpoint, public client ID, scopes 또는 callback port를 수정하면 기존 토큰은 자동 삭제되어 다시 로그인해야 한다. 표시명, 모델 또는 LLM endpoint만 수정하면 현재 OAuth 연결은 유지된다.

OAuth access/refresh token은 프로필 JSON에 저장하지 않고 `:android`의 Android Keystore 기반 `OAuthTokenStore`에만 저장한다. client secret/API key 입력란도 제공하지 않는다.

## 챗봇 흐름

1. 하나 이상의 프로필을 저장하고 `Connect`로 OAuth를 완료한다.
2. 채팅 화면 상단에서 인증된 `프로필 이름 · 모델`을 선택한다.
3. 메시지를 보내면 선택된 session 하나만 호출된다.
4. 응답 중에는 Provider 전환과 중복 전송이 비활성화된다.
5. `Stop`은 수신한 부분 응답을 보존하고 스트림을 취소한다.
6. assistant bubble에는 응답에 사용된 프로필과 모델이 고정 표시된다.
7. timeout, 429, 일부 5xx, circuit open과 protocol 오류는 Provider 원문 없이
   안전한 상태로 표시하며 실패한 요청만 한 번 재시도할 수 있다.
8. 401, invalid grant와 token storage invalidation은 재시도 대신 OAuth 재연결을
   안내한다.

대화는 `ViewModel` 메모리에만 보관한다. 앱 프로세스가 종료되면 대화는 사라지지만, 비민감 프로필과 Keystore credential은 유지된다.

## 빌드와 자동 검증

```bash
./gradlew \
  :demo-chatbot:testDebugUnitTest \
  :demo-chatbot:assembleDebug \
  :demo-chatbot:assembleDebugAndroidTest \
  :demo-chatbot:lintDebug
```

Samsung 실기기 예시:

```bash
adb -s <serial> install -r \
  demo-chatbot/build/outputs/apk/debug/demo-chatbot-debug.apk
adb -s <serial> install -r \
  demo-chatbot/build/outputs/apk/androidTest/debug/demo-chatbot-debug-androidTest.apk
adb -s <serial> shell am instrument -w \
  dev.alpine.llm.demo.test/androidx.test.runner.AndroidJUnitRunner
```

JVM test 25개는 프로필 검증·JSON 왕복, 공통 요청 변환, 인증된 프로필
필터링, Provider 전환 metadata, stream 취소, redacted 오류 매핑과 중복 없는
retry를 검증한다. instrumentation test 12개는 초기 Compose 화면, light/dark
brand theme, 세 Provider별 동적 필드, Gemini 프로필 저장·credential 비저장과
fake 3-provider 선택 전송, profile CRUD, logout/delete 격리와 Activity 재생성을
실제 앱 프로세스에서 검증한다. Fake session과
fixture는 `androidTest` APK에만 포함되고 production APK에는 포함되지 않는다.

2026-07-31 기준 Samsung `SM-S931N`, Android 16에서 데모 instrumentation
12개를 2회 연속 통과했고, compact portrait·landscape·home 복귀를 smoke test했다.

## OAuth 적용 전 확인

이 앱은 OAuth Authorization Code + PKCE public-client 흐름과 `http://127.0.0.1:<port>` callback을 사용한다. 실제 연결 전 다음 외부 조건을 반드시 확인해야 한다.

- Provider가 Android 앱에 public client ID를 발급하는가
- Provider가 loopback redirect URI와 동적 fallback port를 허용하는가
- 요청한 scopes가 LLM API 호출 권한을 실제로 부여하는가
- Anthropic 계열 token endpoint가 JSON token request와 state echo를 요구하는가
- Gemini quota project를 사용하는 경우 해당 프로젝트 권한이 있는가
- OpenAI-compatible 서비스가 API key가 아닌 OAuth endpoint를 실제 제공하는가

Google Android OAuth 정책 등으로 loopback redirect가 허용되지 않는 경우 custom scheme/App Link callback을 `OAuthManager`에 먼저 추가해야 한다. 외부 계정과 client registration이 없는 자동 테스트에서는 실제 Provider 로그인·과금 API 호출을 수행하지 않는다.
