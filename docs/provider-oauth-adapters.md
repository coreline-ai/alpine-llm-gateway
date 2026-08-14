# Provider OAuth·inference 경계

이 문서는 `dev.alpine.integrated`의 Android 직접 Provider OAuth 경로를 설명한다.

## 현재 제품 규칙

1. Provider public client ID·authorization endpoint·token endpoint·scope·model은 앱 소유자가
   등록·승인한 값만 입력한다.
2. 다른 앱/공식 CLI/consumer subscription의 client ID, endpoint, scope, User-Agent, Originator,
   account header를 앱 기본값으로 포함하지 않는다.
3. OAuth identity 설정이 비어 있거나 HTTPS 검증에 실패하면 저장·로그인·dispatch하지 않는다.
4. Provider 오류 원문, OAuth token, Authorization header, prompt는 UI·로그·Gateway capability에
   기록하지 않는다.
5. 자동 재전송은 하지 않는다. 429·5xx·SSE 중단은 안정화된 오류 상태로 끝내고 사용자가
   Retry를 명시적으로 눌러야 한다.

## Provider별 번들 상태

| Provider 유형 | 앱에 고정된 항목 | 사용자가 입력해야 하는 항목 | debug compatibility evidence | 공식 product E2E |
|---|---|---|---|---|
| Gemini | Google OAuth endpoint·scope·loopback callback·generateContent protocol | 앱 소유 public client ID, quota project, 계정에서 허용된 model | 이번 작업에서 실행하지 않음 | `NOT_RUN` |
| OpenAI-compatible | OAuth/Chat Completions 공통 adapter | OAuth endpoint·scope·client ID·model·HTTPS endpoint | `NOT_RUN` | `NOT_RUN` |
| OpenAI Responses / Codex | Responses JSON/SSE 변환 adapter | 승인된 OAuth endpoint·scope·client ID·model·Responses HTTPS endpoint | Samsung debug OAuth + 1턴 stream `PASS` | `NOT_RUN` |
| Anthropic Messages / Claude | Messages JSON/SSE 변환과 protocol version header | 승인된 OAuth endpoint·scope·client ID·model·Messages HTTPS endpoint | code/unit/build만 `PASS`, 계정 E2E `NOT_RUN` | `NOT_RUN` |
| xAI Chat Completions / Grok | Chat Completions JSON/SSE 변환 | 승인된 OAuth endpoint·scope·client ID·model·HTTPS endpoint | Samsung debug OAuth + 1턴 stream `PASS` | `NOT_RUN` |

`OpenAI Responses`, `Anthropic`, `xAI`는 release 앱에 소비자 OAuth 또는 CLI compatibility 기본값을
제공하지 않는다. 표시된 유형은 user-owned configuration을 보관하고 adapter를 선택하는 기능일
뿐, Provider가 모바일 public client와 inference grant를 승인했다는 뜻이 아니다. 사용자가 명시
승인한 OpenMinis 호환은 `integrated-app/src/debug`의 process-local registry에서만 설치할 수 있고,
debug APK를 제외한 모든 source/artifact에서는 verifier가 해당 registration·scope·attribution을
거부한다.

Samsung debug 결과는 serial `R3CY40PXCAP`의 side-by-side debug package에서 사용자가 승인한
compatibility로 확인한 제한 증거다. 앱 소유 registration, Provider의 모바일 public-client 승인,
refresh/logout/Stop/error lifecycle 또는 공개 지원 상태를 증명하지 않는다.

## 모델 정책

- Gemini의 앱 내 선택 목록은 UI 편의용 후보이며 계정·region·tier 접근 권한의 증명이 아니다.
- 각 profile은 기본 model과 로컬 후보 catalog를 저장한다. catalog가 없는 이전 profile은 기존
  model 하나를 `LEGACY_MIGRATED` 후보로 보존한다.
- Gemini 외 production profile은 승인된 model catalog를 추측해 번들하지 않고 사용자가 추가한
  `USER_ADDED` 후보만 노출한다. debug compatibility seed도 entitlement나 release catalog가 아니다.
- disabled 후보는 새 session option에서 제외한다. 저장 대화가 disabled/삭제 model을 가리키면 model ID를
  자동 변경하거나 요청을 보내지 않고, 사용자가 enabled 후보를 명시적으로 다시 선택해야 한다.
- 실제 model list·preview lifecycle·region 권한은 실계정 E2E 당일 확인한다. 확인 전에는
  model 지원을 `NOT_RUN`으로 기록한다.

## artifact clean-room 검사

아래 verifier는 integrated product source와 빌드된 debug APK를 함께 검사한다.

```bash
python3.11 scripts/verify-integrated-oauth-release.py \
  --require-default-roots \
  --allow-approved-openminis-debug
```

검사 대상은 다음과 같다.

- consumer endpoint, CLI fingerprint/scope/attribution, known copied registration hash
- probable Provider API key와 private key
- demo·runtime probe·bridge probe·sample package가 integrated APK에 섞이는 경우

GitHub Actions에도 동일 검사를 연결했지만, 현재 변경은 아직 push하지 않았으므로 원격 CI 결과는
`NOT_RUN`이다.


## 실제 계정 E2E 전제

다음 항목은 코드·fake provider test가 대체할 수 없다.

1. Provider가 발급한 app-owned public client registration 및 exact redirect URI 승인
2. 선택한 account/region/tier의 OAuth scope·model·inference endpoint grant
3. login → stream → Stop → explicit retry → refresh → logout/revoke
4. 429·5xx·비정상 SSE·browser callback 중 process kill의 redacted evidence
5. 실시간 생성 API의 idempotency/deduplication 계약

승인과 redacted report가 없으면 위 Provider direct OAuth는 `NOT_RUN`이며 공개 배포 근거가 아니다.
