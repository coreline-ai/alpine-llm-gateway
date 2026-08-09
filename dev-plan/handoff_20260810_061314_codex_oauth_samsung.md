# Codex OAuth 삼성 실기기 작업 핸드오프

작성 일시: `2026-08-10 06:13:14 KST`

이 문서는 `/Volumes/Eprojects/project_202607/alpine-llm-gateway`에서 진행한 Codex OAuth 호환 로그인 작업의 현재 상태를 보존한다. 새 작업 세션은 아래 상태와 주의사항을 확인한 뒤 이어서 진행한다.

## 1. 저장소 스냅샷

| 항목 | 현재 값 |
|---|---|
| 저장소 | `/Volumes/Eprojects/project_202607/alpine-llm-gateway` |
| 브랜치 | `main` |
| HEAD | `e5e275e` |
| Working tree | 수정·추가 파일 약 75개, 대부분 이번 OAuth 작업 이전부터 존재 |
| OAuth 관련 commit/stage/push | 하지 않음 |
| 참고 프로젝트 | `/Volumes/Eprojects/meta-skills/openminis` |

중요: working tree에는 Runtime, PTY, Workspace, UI, 문서 등 다른 작업의 변경이 대량으로 섞여 있다. `git reset`, `git restore`, `git clean`, 전체 파일 일괄 포맷, 임의 commit을 하지 않는다.

## 2. 사용자 요구사항과 대상 기기

- 최초 요구: 첨부 OpenMinis 프로젝트를 참고하여 Codex OAuth 로그인을 진행한다.
- 후속 요구: 모든 실기기 작업 대상을 삼성폰으로 고정한다.
- 고정 대상: Samsung `SM-S931N`
- ADB serial: `R3CY40PXCAP`
- 다른 연결 기기 `PD20` (`0123456789ABCDEF`)은 이후 작업에 사용하지 않는다.
- 모든 ADB 명령은 반드시 `-s R3CY40PXCAP`을 명시한다.
- 삼성폰은 USB 연결 중 화면 켜짐 유지가 설정되어 있다: `adb -s R3CY40PXCAP shell svc power stayon true`.

권장 셸 변수:

```bash
ADB=/Users/hwanchoi/Library/Android/sdk/platform-tools/adb
S=R3CY40PXCAP
$ADB -s "$S" devices -l
```

## 3. 이번 작업에서 변경한 소스

### `/Volumes/Eprojects/project_202607/alpine-llm-gateway/android/src/main/java/dev/alpine/llm/CodexOAuthContract.kt`

- Codex loopback callback을 `http://localhost:1455/auth/callback`으로 유지한다.
- authorization 요청에 OpenMinis가 사용하는 다음 호환 파라미터를 추가했다.
  - `codex_cli_simplified_flow=true`
  - `originator=codex_cli_rs`
  - `id_token_add_organizations=true`
- token exchange encoding을 form-urlencoded에서 JSON으로 변경했다.
- `JwtClaimMetadataTokenResponseAdapter`를 사용해 `id_token`의 account/plan metadata를 암호화 token metadata에 병합하도록 했다.

### `/Volumes/Eprojects/project_202607/alpine-llm-gateway/alpine-chat-provider-android/src/main/java/dev/alpine/chat/provider/android/session/ProviderSessionFactory.kt`

- `ProviderType.CODEX`가 generic OAuth 설정을 사용하지 않고 `CodexOAuthContract.providerConfig(...)`를 사용하도록 분기했다.
- 이 분기가 없으면 profile의 callback port만 1455로 입력해도 실제 redirect host/path가 `127.0.0.1/.../oauth/callback`이 되어 Codex 등록 callback과 맞지 않는다.

### `/Volumes/Eprojects/project_202607/alpine-llm-gateway/android/src/test/java/dev/alpine/llm/CodexResponsesOAuthAdapterTest.kt`

- Codex 계약 테스트를 JSON token exchange와 추가 authorization 파라미터 기대값에 맞게 변경했다.

## 4. 참고한 OpenMinis 구현

다음 파일을 근거로 로컬 debug 호환 동작을 맞췄다.

- `/Volumes/Eprojects/meta-skills/openminis/src/android/app/src/main/java/com/openminis/app/auth/OpenAIOAuthManager.kt`
- `/Volumes/Eprojects/meta-skills/openminis/src/android/app/src/main/java/com/openminis/app/provider/openai/OpenAIProvider.kt`
- `/Volumes/Eprojects/meta-skills/openminis/src/android/app/src/main/java/com/openminis/app/provider/openai/OpenAIModelsApi.kt`

삼성폰의 app-private `demo_llm_profiles.xml`에는 다음 종류의 값이 이미 들어 있다.

- OpenAI authorization/token endpoint
- Codex loopback callback port `1455`
- scopes: `openid profile email offline_access`
- model: `gpt-5.6-sol`
- OpenMinis에서 확인한 public client identifier
- Codex Responses 호환 endpoint

public client identifier 원문은 이 핸드오프 문서나 새 소스 파일에 복사하지 않는다. 필요하면 위 OpenMinis 파일에서 다시 확인한다. OAuth access/refresh/ID token, callback code, PKCE verifier/state는 절대 문서·로그·셸 환경·Git 파일로 복사하지 않는다.

## 5. 빌드·설치 상태

### 검증 완료

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew :android:testDebugUnitTest \
  --tests dev.alpine.llm.CodexResponsesOAuthAdapterTest
```

- 결과: `BUILD SUCCESSFUL`
- report: `/Volumes/Eprojects/project_202607/alpine-llm-gateway/android/build/test-results/testDebugUnitTest/TEST-dev.alpine.llm.CodexResponsesOAuthAdapterTest.xml`
- 테스트 5개, failure/error 0개

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew :integrated-app:assembleDebug
```

- 결과: `BUILD SUCCESSFUL`
- APK: `/Volumes/Eprojects/project_202607/alpine-llm-gateway/integrated-app/build/outputs/apk/debug/integrated-app-debug.apk`
- APK 크기: 약 29 MiB
- 빌드 시각: `2026-08-09 19:21 KST`

### 삼성 설치 상태

- package: `dev.alpine.integrated`
- version: `0.3.0` / versionCode `1`
- debug build이며 `run-as` 가능
- 위 APK를 Samsung `R3CY40PXCAP`에 `adb install -r`로 설치 완료
- 프로필은 app-private shared preferences에 주입 완료
- token 원문은 조회하거나 추출하지 않았다.

## 6. 현재 삼성폰 OAuth 상태

2026-08-10 06:13 KST 조회 기준:

- Chrome Custom Tab이 top resumed activity로 남아 있다.
- 뒤에는 `ProviderProfilesActivity`가 살아 있다.
- app-private 파일 `alpine_llm_oauth.xml`과 `alpine_provider_authorization_recovery.xml`이 존재한다.
- 이 파일 존재만으로 로그인 성공을 뜻하지 않는다. transaction/recovery marker만 있어도 생성된다.
- `연결됨` UI를 확인하지 못했으므로 **실제 OAuth 로그인 완료 상태는 NOT_CONFIRMED**다.
- 인증 시도는 2026-08-09 22:39 KST경 시작됐고 callback 제한은 5분이므로, 현재 열린 Custom Tab은 만료된 흐름으로 간주해야 한다.
- 기존 callback URL/code를 재사용하거나 수동 token exchange하지 않는다.

## 7. 다음 세션의 정확한 재개 절차

1. ADB 대상이 `R3CY40PXCAP`인지 확인한다.
2. 현재 오래된 Chrome Custom Tab을 닫고 Alpine 앱으로 돌아간다.
3. `LLM 연결` → `Codex OAuth` → `로그인`을 눌러 **새** PKCE transaction을 시작한다.
4. 사용자가 삼성폰에서 이메일/SSO, 비밀번호, MFA, consent를 직접 완료하도록 한다.
5. 에이전트는 credential 입력·캡처·덤프를 하지 않는다.
6. callback 후 앱으로 돌아오면 UI에서 다음 항목만 확인한다.
   - `1개 profile 중 1개 연결됨`
   - profile 상태 `연결됨`
   - action이 `로그아웃`으로 바뀜
7. token 파일의 내용이나 callback query를 출력하지 않는다.
8. 로그인 확인 뒤에만 별도 사용자 승인 하에 최소 non-stream/stream smoke를 진행한다.

재개 시 안전한 상태 확인 명령:

```bash
ADB=/Users/hwanchoi/Library/Android/sdk/platform-tools/adb
S=R3CY40PXCAP
$ADB -s "$S" devices -l
$ADB -s "$S" shell dumpsys activity activities \
  | rg -m 4 'topResumedActivity|mResumedActivity'
```

민감한 callback query가 UI hierarchy의 Chrome title에 포함될 수 있다. callback 단계에서는 전체 `uiautomator` XML이나 Chrome URL을 출력하지 말고, 앱으로 복귀한 다음 Alpine UI의 연결 문구만 필터링한다.

## 8. 중요한 미완료 항목

- [ ] 삼성폰에서 새 OAuth flow 시작
- [ ] 사용자의 로그인·MFA·consent 완료
- [ ] UI `연결됨` 확인
- [ ] refresh token 갱신 경로 확인
- [ ] 로그아웃 후 credential 삭제 확인
- [ ] 실제 Codex inference non-stream smoke
- [ ] 실제 Codex inference stream/Stop smoke
- [ ] redacted Provider E2E report 작성
- [ ] 전체 unit/instrumentation/clean-room verifier 재실행

## 9. 로그인 이후 예상되는 추가 구현 리스크

현재 `/Volumes/Eprojects/project_202607/alpine-llm-gateway/android/src/main/java/dev/alpine/llm/CodexResponsesOAuthAdapter.kt`는 원래 제품 정책에 맞춰 다음 Codex consumer/CLI 호환 header를 보내지 않는다.

- version/header fingerprint
- experimental Responses header
- CLI User-Agent/Originator
- ChatGPT account ID header

따라서 OAuth token 저장까지 성공해도 OpenMinis와 동일한 consumer Codex Responses inference가 바로 성공한다고 가정하면 안 된다. inference smoke가 실패하면 Provider body/token을 로그에 노출하지 말고 HTTP status category만 확인한 뒤, 이 header 경계를 유지할지 debug-only 호환으로 확장할지 사용자와 먼저 결정한다.

## 10. 제품 정책과의 충돌

현재 프로젝트 문서는 다른 앱/CLI client identity와 fingerprint를 제품에 포함하지 않는 clean-room 정책을 명시한다.

- `/Volumes/Eprojects/project_202607/alpine-llm-gateway/docs/provider-oauth-adapters.md`
- `/Volumes/Eprojects/project_202607/alpine-llm-gateway/docs/provider-account-e2e-runbook.md`
- `/Volumes/Eprojects/project_202607/alpine-llm-gateway/docs/provider-official-policy-review-20260809.md`

이번 변경은 사용자의 명시 요청으로 OpenMinis 호환값을 참고한 **로컬 debug 실험**이다. 다음 사항을 반드시 구분한다.

- 실제 로그인 성공 여부: 아직 `NOT_CONFIRMED`
- 공개 배포 적합성: `NO-GO`
- app-owned OAuth registration 여부: 확인되지 않음
- `scripts/verify-mobile-oauth-release.py --integrated-product`: CLI fingerprint 때문에 실패할 가능성이 높으며 아직 재실행하지 않음
- source 변경을 유지할지, debug-only로 격리할지, 원래 clean-room 계약으로 되돌릴지는 사용자 결정이 필요

## 11. 하지 말아야 할 작업

- 다른 연결 기기 PD20에서 로그인/설치/테스트하지 않는다.
- 오래된 callback URL의 code/state를 복사하거나 재사용하지 않는다.
- token/authorization/cookie/계정 식별자를 `adb`, 파일, report, Git diff에 출력하지 않는다.
- 삼성 앱 데이터를 지우는 `pm clear`, uninstall, 테스트 orchestrator 초기화를 하지 않는다.
- 실계정에서 429/5xx를 의도적으로 유발하지 않는다.
- 현재 working tree의 다른 변경을 되돌리거나 함께 commit하지 않는다.
- 로그인 성공만으로 inference/E2E/배포 준비 완료라고 기록하지 않는다.

## 12. 완료 기준

- 삼성 `SM-S931N`에서 새 OAuth transaction으로 사용자 로그인이 완료된다.
- Alpine UI가 token 원문 없이 `연결됨`을 표시한다.
- 승인된 범위에서 non-stream/stream/Stop/logout이 redacted evidence와 함께 검증된다.
- 로컬 debug 호환 변경의 유지·격리·폐기 결정을 사용자와 확정한다.
- 관련 테스트와 정책 verifier 결과를 실제 상태대로 기록한다.
