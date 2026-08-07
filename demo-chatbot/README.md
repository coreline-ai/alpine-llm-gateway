# Alpine LLM Chat Demo

`demo-chatbot`은 `:alpine-chat-feature`와 `:alpine-chat-provider-android`를 조립하는 thin sample host다. 저장된 OAuth LLM 프로필 가운데 인증된 연결을 선택하고, 대화별 Provider·모델·기본 스킬·응답 페르소나로 새 메시지를 스트리밍 요청한다. 여러 대화와 초안은 기기 안에 암호화해 보존한다.

다중 대화, 암호화 저장, ViewModel, Skill·Persona와 Compose 채팅 화면은 공통 Feature가
소유한다. Provider profile CRUD, OAuth session 생성과 Provider event를 공통 delta/error로
바꾸는 Android 직접 adapter는 재사용 Provider 모듈이 소유한다. 앱에는 Activity lifecycle과
화면 callback 조립만 남으며 `integrated-app`도 같은 모듈을 사용한다.

Phase 5부터 저장 모델은 대화별 `FAST_CHAT`/`ALPINE_WORKSPACE` 실행 모드를 포함한다. 기존
schema 1·2 대화는 `FAST_CHAT`으로 안전하게 migration한다. 이 데모의 실제 전송 경로는 계속
빠른 채팅 기준선이며 mode selector와 Alpine adapter 조립은 `integrated-app`이 제공한다.

UI는 Material 3 Compose로 구현되어 light/dark theme, Android 12+ dynamic color,
840dp 최대 콘텐츠 폭과 작은 가로 화면의 스크롤을 지원한다. 세부 token,
컴포넌트, 접근성 및 안정적인 test tag 계약은 [디자인 가이드](DESIGN.md)를
참고한다.

## 포함된 GUI

`LLM connections`에서 `Add LLM`을 누르면 OpenMinis의 Provider 선택·구성 흐름을 참고한 카드형 선택 화면이 열린다.

| 유형 | 공통 입력 외 추가 입력 | 요청 adapter |
|---|---|---|
| Claude / Anthropic | OpenMinis 호환 OAuth 값, beta header, 선택형 모델 | Anthropic Messages |
| Google Gemini | 앱 소유 OAuth client ID, Google Cloud quota project, 선택형 모델 | 고정 Gemini generateContent endpoint |
| OpenAI-compatible | Provider의 chat completions endpoint | OpenAI chat completions |
| Codex compatibility | 앱 소유·승인된 OAuth public client ID 직접 입력, reference 모델 목록 | 고정 Codex Responses endpoint |
| xAI compatibility | 앱 소유·승인된 OAuth public client ID 직접 입력, reference 모델 목록 | 고정 xAI Chat Completions endpoint |

일반 프로필은 표시명, authorization/token/LLM HTTPS endpoint, OAuth public client ID, scopes, 모델, loopback callback port를 입력한다. Claude compatibility profile은 참조 구현에서 확인한 auth/token/Messages endpoint, scopes, `localhost:54545/callback`, 96-byte PKCE, JSON token exchange의 state echo와 `oauth-2025-04-20` 계약을 고정하지만 **client ID는 내장하지 않고** 앱 소유·승인값을 요구한다. Gemini는 Google 공식 authorization/token/generateContent endpoint, scopes, `localhost:8085/oauth2callback`과 reference 모델 목록을 고정하고 앱 소유 OAuth client ID와 quota project를 받는다. Codex·xAI compatibility profile도 endpoint·scope·callback 계약만 고정하며 public client ID는 빈 값으로 시작한다. 같은 유형을 여러 번 추가하면 `Claude 2`, `Gemini 2`처럼 다음 표시명을 제안한다. Provider별 reference 모델 목록은 운영 지원 목록이 아니며 실제 계정·정책 검증 후 사용해야 한다.

Claude 호환 프로필은 로컬 데모 검증용이다. OpenMinis 공개 mirror가 의도적으로 제외한 Claude Code 식별 system prompt와 CLI fingerprint를 Alpine이 추측하거나 위장해 넣지 않으므로, 계정 로그인이 성공해도 Messages inference는 Provider 정책에 따라 거절될 수 있다. 배포 앱은 Anthropic이 해당 앱에 발급·승인한 registration과 공식 API 계약을 사용해야 한다.

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
3. Claude, Gemini, Codex와 xAI 프로필은 바로 아래 `Quick model switch` 칩에서 모델을 한 번 눌러
   즉시 바꿀 수 있다. 선택값은 저장되며 기존 OAuth 로그인은 유지된다.
4. `Assistant mode` 칩에서 기본 스킬 1개와 응답 페르소나 1개를 선택한다. 내장 스킬은
   일반·Alpine/Linux·코딩·오류 분석·코드 리뷰·명령어·문서·학습이고, 페르소나는
   균형형·간결형·초보자 친화형·전문 엔지니어·단계별·비판적 검토·문서 작성자다.
5. `Use as default for new chats`를 켜면 이후 새 대화가 같은 조합을 상속한다. 기존 대화의
   선택은 바뀌지 않으며 `Reset`은 현재 대화를 `General assistant · Balanced`로 되돌린다.
6. 메시지를 보내면 해당 대화가 선택한 Provider·모델의 session 하나만 호출된다. 스킬과
   페르소나는 bounded system instruction으로만 전달되며 명령 실행·파일·네트워크 권한을
   부여하지 않는다.
7. 응답 중에도 Assistant mode와 다음 초안을 바꿀 수 있다. 변경은 다음 메시지부터 적용되고
   현재 응답과 재시도는 전송 시작 당시 설정을 유지한다.
8. 응답 중에도 다른 대화로 이동해 새 요청을 보낼 수 있다. 전체 동시 생성은 최대
   2개이고, 같은 대화에서는 한 번에 하나만 생성한다. 세 번째 요청은 Provider 호출
   전에 안전하게 차단한다.
9. `New chat`은 확인 팝업 없이 빈 대화를 열고 기존 대화와 진행 중 요청을 보존한다.
   현재 대화가 완전히 비어 있으면 불필요한 빈 대화를 추가하지 않고 재사용한다.
10. 왼쪽 위 `History`에서 이전 대화로 돌아가며 제목 변경과 삭제를 할 수 있다.
   삭제에만 확인 팝업을 표시한다. 생성 중 대화에는 `Generating`, 백그라운드에서
   완료된 대화에는 `New`, 실패·중단에는 각각 상태 배지를 표시한다.
11. `Stop`은 현재 보고 있는 대화의 스트림만 중단하고 수신한 부분 응답은 보존한다.
   다른 대화의 생성은 계속된다.
12. assistant bubble에는 응답에 실제 사용된 프로필·모델·스킬·페르소나가 고정 표시된다.
13. assistant 응답의 제목·문단·목록·인용·표·굵게·기울임·inline code·fenced code block은
   native Compose로 표시한다. 알려진 code fence 언어는 경량 구문 강조를 적용하고 표 영역은
   작은 화면에서 가로 스크롤한다. HTML, Markdown image와 원격 리소스는 실행하거나 자동
   로드하지 않고 inert text로 유지한다. 절대 `http`/`https` 링크만 인식하며 탭 후 외부 열기
   확인 dialog를 거쳐야 한다. `javascript:`, `file:`, `intent:` 등 다른 scheme은 클릭할 수 없다.
14. 최신 사용자 요청에 `under 90 words`, `두 문장으로`, `three bullets`처럼 로컬에서
   측정 가능한 형식 제한이 있으면 system instruction에 반영하고 완료 응답을 다시 검사한다.
   위반하면 같은 Provider·모델·Assistant mode로 최대 1회 자동 교정하며, 다시 위반하거나
   교정 호출이 실패하면 추가 호출 없이 응답 보존과 안전한 상태 안내로 종료한다.
15. 최신·오늘·실시간 정보나 명시적 웹 확인 요청을 감지하면, 현재 chat runtime에 웹·검색
   도구가 없음을 system instruction에 추가한다. 응답이 실제로 웹을 확인했다고 명시적으로
   허위 주장하면 형식 제한과 공유하는 1회 예산 안에서 교정한다. 이는 일반적인 사실 정확성
   판정이나 웹 검색 기능이 아니다.
16. timeout, 429, 일부 5xx, circuit open과 protocol 오류는 Provider 원문 없이
   안전한 상태로 표시하며 실패한 요청만 한 번 재시도할 수 있다.
17. 401, invalid grant와 token storage invalidation은 재시도 대신 OAuth 재연결을
   안내한다.

## 대화 저장과 복구

- 저장 위치: 앱의 자동 백업 대상이 아닌 `noBackupFilesDir/encrypted-conversations-v1`
- 저장 단위: 최근 수정순 index 1개와 대화별 파일 1개
- 암호화: OAuth token 키와 분리된 Android Keystore AES-GCM alias
  `alpine_demo_conversation_aes_gcm_v1`
- 쓰기: `AtomicFile`을 사용하며 delta 연속 수신 중에는 bounded debounce, 완료·실패·중단
  시에는 즉시 저장한다.
- 복구: index 또는 대화 한 파일이 손상되면 읽을 수 없는 파일만 격리하고 나머지를
  스캔해 복원한다. ciphertext·내부 복호화 예외·대화 원문은 사용자 오류나 로그에
  노출하지 않는다.
- 프로세스 종료: 저장된 대화·초안·선택 Provider·모델·실행 모드·스킬·페르소나와 마지막 active 대화를 복원한다.
  종료 당시 `STREAMING`이던 응답은 다음 시작에서 `CANCELLED`로 정상화해 생성 중 상태가
  영구 고착되지 않게 한다.
- 삭제: 해당 대화 파일과 index 항목을 로컬에서 제거한다. 서버 동기화·기기간 복원·검색·
  내보내기는 현재 범위에 포함하지 않는다.

## 빌드와 자동 검증

```bash
./gradlew \
  :android:testDebugUnitTest \
  :demo-chatbot:testDebugUnitTest \
  :demo-chatbot:assembleDebug \
  :demo-chatbot:assembleDebugAndroidTest \
  :demo-chatbot:lintDebug
```

Compose instrumentation은 OAuth 실사용 정보가 없는 emulator/test 전용 기기에서 실행한다.

```bash
adb -s <emulator-serial> install -r demo-chatbot/build/outputs/apk/debug/demo-chatbot-debug.apk
adb -s <emulator-serial> install -r \
  demo-chatbot/build/outputs/apk/androidTest/debug/demo-chatbot-debug-androidTest.apk
adb -s <emulator-serial> shell am instrument -w \
  dev.alpine.llm.demo.test/androidx.test.runner.AndroidJUnitRunner
```

삼성 실기기에는 테스트 APK/instrumentation을 실행하지 않고 `adb install -r`로 앱 APK만
덮어써서 기존 OAuth 프로필과 Keystore token을 보존한다.

JVM test는 프로필 검증·JSON 왕복, 요청 변환, Provider 전환 metadata, stream 취소,
redacted 오류·retry뿐 아니라 Markdown 표·안전 링크·code tokenizer, 응답 형식 제한과
최신성/외부 검증 안전 경계의 감지·검증·공유 1회 교정,
Assistant catalog·prompt 경계·schema migration·대화 codec·제목 경계·AES-GCM 변조·손상 격리·대화별
draft/Provider/model/Assistant mode·동시 생성 제한을 검증한다. instrumentation test는 초기 Compose 화면,
light/dark theme, Provider별 동적 필드, Provider 고정 OAuth 계약·모델 선택·credential 비저장,
fake Provider 전송, Markdown 표/code block/링크 확인 dialog 표시, 형식 제한·웹 검증 주장 자동 교정,
429·503·malformed/interrupted stream·timeout 오류 주입과 재시도, 빠른 모델 전환,
Assistant mode/default, profile CRUD, 응답 중 UI 조작, Activity 재생성,
새 대화·기록 복귀·프로세스 재실행 복원·이름 변경·삭제 확인과 encrypted store 복구를
실제 앱 프로세스에서 검증한다. Fake session과
fixture는 `androidTest` APK에만 포함되고 production APK에는 포함되지 않는다. 실제 Provider
계정에는 rate limit이나 5xx를 만들기 위한 부하·오류 요청을 보내지 않는다.

2026-08-01 최종 자동 검증에서는 Android Library JVM 76개, demo JVM 83개, Python 49개와
API 35 emulator Compose instrumentation 30개가 통과했고 debug APK·test APK·lint도
성공했다. Samsung `SM-S931N`에는 앱 APK만 `adb install -r`로 설치했으며 기존 Codex OAuth
연결과 `gpt-5.3-codex-spark` 모델, 저장 대화와 Assistant mode가 유지됐다. 실제 Codex에
`under twenty words`와 `exactly two bullet points`를 함께 요청한 smoke에서 최종 응답이 19 words,
2개 native bullet로 표시되는 것을 확인했다. 자동 교정의 2차 Provider 호출은 fake Provider
instrumentation과 JVM에서 검증했으며 실기기 첫 응답은 이미 제약을 충족했다. 후속 실제 Codex
smoke에서는 `Search the web for latest Seoul weather` 요청에 앱이 live web에 접근할 수 없음을
명시하고 임의의 현재 날씨를 만들지 않았다. 증거는
`../dev-plan/alpine-followup-freshness-samsung.png`와 대응 XML에 저장했다.

## OAuth 적용 전 확인

이 앱은 OAuth Authorization Code + PKCE public-client 흐름을 사용한다. 일반 Provider는 `http://127.0.0.1:<port>/oauth/callback`과 fallback port를 사용하고 Codex는 `http://localhost:1455/auth/callback`을 고정 사용한다. 실제 연결 전 다음 외부 조건을 반드시 확인해야 한다.

- Provider가 Android 앱에 public client ID를 발급하는가
- Provider가 loopback redirect URI와 동적 fallback port를 허용하는가
- 요청한 scopes가 LLM API 호출 권한을 실제로 부여하는가
- Claude 호환 registration이 `localhost:54545/callback`, JSON token request, state echo와 96-byte PKCE를 계속 허용하는가
- Claude OAuth inference에 필요한 공개되지 않은 식별 prompt/클라이언트 계약을 앱이 정식으로 제공받았는가
- Gemini OAuth client ID가 Alpine 앱 소유 registration이며 Generative Language API를 사용할 quota project 권한이 있는가
- OpenAI-compatible 서비스가 API key가 아닌 OAuth endpoint를 실제 제공하는가
- Codex public client registration을 앱이 사용할 권한이 있고 고정 redirect URI가 등록되어 있는가
- Codex account OAuth와 ChatGPT backend 사용이 현재 Provider 정책에서 허용되는가
- Codex token endpoint가 현재 first-party 계약과 같은 form-urlencoded grant를 허용하는가
- OpenAI 동의 페이지가 멈추는 경우 새 거래로 즉시 재시도하거나 device-code 대체 흐름을 제공하는가
- xAI가 사용할 public client registration과 `127.0.0.1:56121/callback`을 승인했는가
- xAI 로그인 계정의 구독 등급이 OAuth inference API 사용 권한을 실제 제공하는가

Google은 Android OAuth client의 loopback redirect를 deprecated 처리했다. 이 데모의 `localhost:8085` 경로를 실제로 사용하려면 앱 소유 Desktop OAuth client에 해당 흐름이 허용돼야 하며, 배포 전에는 Android App Link/Google Identity 기반 callback으로 전환해야 한다. 외부 계정과 client registration이 없는 자동 테스트에서는 실제 Provider 로그인·과금 API 호출을 수행하지 않는다. OpenMinis, Gemini CLI, Codex CLI 등 다른 앱의 public client ID를 복사해 배포하지 않는다.
