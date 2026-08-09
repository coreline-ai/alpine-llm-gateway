# Alpine Integrated App

[![Version](https://img.shields.io/badge/version-0.3.0-B9F227?style=flat-square&labelColor=10120F)](../gradle.properties)
![Android](https://img.shields.io/badge/Android-API%2026--36-3DDC84?style=flat-square&logo=android&logoColor=white)
![Modes](https://img.shields.io/badge/modes-Fast%20Chat%20%7C%20Alpine%20Work-31372F?style=flat-square)
![Distribution](https://img.shields.io/badge/distribution-INTERNAL%20ONLY-F59E0B?style=flat-square)

`dev.alpine.integrated`는 재사용 Chat·Provider·Runtime·Bridge SDK를 한 Android 앱에 조립하는 제품 통합 Host다. 검정·아이보리·라임 디자인 시스템을 사용하며 시스템 dynamic color와 무관하게 동일한 제품 팔레트를 유지한다.

> [!IMPORTANT]
> 이 앱은 `demo-chatbot` compatibility 앱과 별도 package·OAuth token 경계를 가진다. 자동으로 profile이나 OAuth token을 공유하지 않는다.

## 📱 실제 화면

공개 가능한 실제 Android 화면 중 제품 흐름을 대표하는 8개입니다. 이미지의 원래 기기 비율을 유지하며, 전체 15개 갤러리는 [루트 README](../README.md#screens)에서 확인할 수 있습니다.

<table>
  <tr>
    <td align="center"><a href="../docs/assets/screenshots/10-mode-guide.png"><img src="../docs/assets/screenshots/10-mode-guide.png" width="180" alt="첫 실행 모드 안내"></a><br><strong>첫 실행 모드 안내</strong></td>
    <td align="center"><a href="../docs/assets/screenshots/01-fast-chat.png"><img src="../docs/assets/screenshots/01-fast-chat.png" width="180" alt="빠른 채팅"></a><br><strong>빠른 채팅</strong></td>
    <td align="center"><a href="../docs/assets/screenshots/14-provider-empty-state.png"><img src="../docs/assets/screenshots/14-provider-empty-state.png" width="180" alt="LLM 연결 빈 상태"></a><br><strong>LLM 연결</strong></td>
    <td align="center"><a href="../docs/assets/screenshots/02-conversation-history.png"><img src="../docs/assets/screenshots/02-conversation-history.png" width="180" alt="대화 기록"></a><br><strong>대화 기록</strong></td>
  </tr>
  <tr>
    <td align="center"><a href="../docs/assets/screenshots/11-alpine-workspace-gateway.png"><img src="../docs/assets/screenshots/11-alpine-workspace-gateway.png" width="180" alt="Alpine 작업 준비"></a><br><strong>Alpine 작업 준비</strong></td>
    <td align="center"><a href="../docs/assets/screenshots/12-runtime-install-dashboard.png"><img src="../docs/assets/screenshots/12-runtime-install-dashboard.png" width="180" alt="Runtime 설치 대시보드"></a><br><strong>Runtime 설치</strong></td>
    <td align="center"><a href="../docs/assets/screenshots/13-runtime-terminal-command.png"><img src="../docs/assets/screenshots/13-runtime-terminal-command.png" width="180" alt="Linux terminal 명령 패널"></a><br><strong>Linux terminal</strong></td>
    <td align="center"><a href="../docs/assets/screenshots/09-runtime-package-manager.png"><img src="../docs/assets/screenshots/09-runtime-package-manager.png" width="180" alt="패키지 설치"></a><br><strong>패키지 allowlist</strong></td>
  </tr>
</table>

OAuth 계정·client ID·token, 실제 terminal 출력·workspace 경로, 파괴적 확인 dialog는 의도적으로 제외한다. 이는 제품 핵심 화면의 안전한 문서화이며 모든 사용자별 상태의 전체 덤프가 아니다.

## 현재 모드

| 모드 | 현재 동작 |
|---|---|
| **빠른 채팅** | 공통 Chat Feature, Android OAuth Provider 관리, 모델 선택, Skill·Persona, stream·Stop·retry, 암호화 대화 복원 |
| **Alpine 작업** | 공통 Gateway 채팅, Runtime·HostBridge·Python Gateway 상태, 시작·상태·재시작·종료·복구, PTY 터미널·패키지 도구 |

실행 모드는 대화별로 저장된다. 생성 중 Alpine 작업 화면으로 이동해도 Provider 요청을 취소하거나 다시 보내지 않으며, 빠른 채팅으로 돌아와 Stop할 수 있다. Runtime 설치·실패 상태는 Android 직접 Provider 빠른 채팅 경로를 차단하지 않는다.

Alpine 채팅은 `IntegratedApplication`이 소유하는 단일 Bridge lifecycle을 사용한다. OAuth token은 Android Provider session 밖으로 이동하지 않으며 Alpine Guest에는 15분 TTL capability 파일과 loopback endpoint만 전달된다. Provider 또는 모델이 바뀌면 이전 Bridge process를 닫고 capability를 교체한다.

Alpine 준비가 dispatch 전에 실패한 경우에도 사용자가 dialog에서 승인한 해당 요청만 빠른 채팅으로 보낼 수 있다. Provider dispatch 또는 첫 delta 이후에는 자동 재전송하지 않는다.

## 첫 실행 모드 안내

앱을 처음 실행하면 빠른 채팅과 Alpine 작업을 비교하는 완전 확장 bottom sheet가 자동으로 열린다.

- **빠른 채팅**: Android가 외부 Provider에 직접 연결한다. OAuth 로그인과 모델 선택이 필요하며 Linux 도구는 제공하지 않는다.
- **Alpine 작업**: Runtime·HostBridge·Python Gateway를 사용한다. 터미널·Python·Git·package 기능을 제공하지만 Runtime 설치와 시작이 먼저 필요하다.
- **안전한 fallback**: Alpine이 준비되지 않아도 사용자 승인 없이 빠른 채팅으로 자동 전송하지 않는다.
- **복구**: 빠른 채팅은 LLM 연결에서 재로그인하고, Alpine 작업은 상태·재시작·복구와 터미널·도구에서 Runtime을 점검한다.

`빠른 채팅으로 시작` 또는 `Alpine 작업으로 시작`을 누르면 guide version만 앱 전용 preference에 저장한다. `나중에`는 현재 화면만 닫으므로 다음 새 실행에서 다시 표시된다. 완료한 뒤에는 상단 mode selector의 `안내` 버튼으로 다시 열 수 있다. Alpine 작업을 선택하는 동작 자체는 Runtime 설치·시작이나 Provider 요청을 실행하지 않는다.

## Provider 시작 순서

1. `빠른 채팅`에서 `LLM connection` 또는 우측 상단 연결 아이콘을 연다.
2. Provider profile을 추가하고 **앱 소유·승인된** OAuth public client registration으로 로그인한다.
3. 빠른 채팅으로 돌아와 계정과 모델을 선택한다.
4. Skill·Persona를 선택하고 메시지를 전송한다.
5. 생성 중에는 Stop, redacted 오류에는 허용된 Retry를 사용한다.

OAuth 브라우저가 열린 동안 Activity 또는 app process가 종료되면 이전 Authorization Code + PKCE
transaction을 자동으로 이어서 교환하지 않는다. 다음 `LLM 연결` 진입에서
`AUTH_FLOW_INTERRUPTED` 또는 `AUTH_SESSION_EXPIRED`를 표시하고, encrypted pending transaction을
폐기한 뒤 사용자가 **로그인**을 다시 눌러야 한다. 이미 token 저장까지 성공한 profile은 stale
lifecycle marker만 정리하며 정상 연결과 token을 유지한다.

Anthropic/Codex/xAI direct OAuth 유형은 compatibility/reference 경로다. 다른 앱·CLI의 client ID를 복사해 제품 connector로 사용하지 않는다. 실제 출시 인증 경계는 [Provider OAuth adapter 요구사항](../docs/provider-oauth-adapters.md)을 따른다.

## Alpine 작업 시작 순서

1. Provider profile에 로그인하고 사용할 모델을 선택한다.
2. 상단에서 `Alpine 작업`을 선택한다.
3. 처음 사용하는 경우 `터미널·도구`에서 Runtime을 설치한다.
4. `Alpine LLM Gateway` 카드에서 `시작`을 누르고 통합 health를 확인한다.
5. `Gateway 채팅`에서 메시지를 보내거나 `터미널·도구`에서 terminal/package 기능을 사용한다.

키보드가 열린 동안에는 작은 화면에서 답변과 오류가 가려지지 않도록 Alpine 상태 카드와 sub-navigation을 임시로 접는다. 키보드를 닫으면 다시 표시된다.

패키지 설치는 `curl`, `git`, `openssh-client`, `py3-pip`, `python3`, `nodejs`, `npm`의 exact allowlist와 사용자 확인을 모두 통과해야 한다. 임의 shell 문자열은 실행하지 않는다. 승인 전 화면은 Alpine `v3.21/aarch64` snapshot 기준의 license·download/installed payload와 network 필요 여부를 표시한다. 이 값은 dependency/index/cache/filesystem overhead나 이후 repository 변경을 포함하지 않는 표시용 추정치이며 install 권한이나 실제 결과를 보장하지 않는다. Python·Git·SSH·Node profile의 `검사` action은 fixed direct-argv version check만 실행하고 Guest 출력은 UI 상태에 저장하지 않는다.

## 빌드·설치

요구사항은 JDK 17, Android SDK 36, Android 8.0(API 26) 이상이다.

```bash
./gradlew :integrated-app:assembleDebug
adb install -r integrated-app/build/outputs/apk/debug/integrated-app-debug.apk
```

## 검증

```bash
./gradlew \
  :alpine-chat-feature:testDebugUnitTest \
  :alpine-runtime-ui-compose:testDebugUnitTest \
  :integrated-app:assembleDebug \
  :integrated-app:assembleDebugAndroidTest \
  :integrated-app:lintDebug

ANDROID_SERIAL=<device-serial> \
  ./gradlew :integrated-app:connectedDebugAndroidTest
```

2026-08-08 기준:

| 검증 | 결과 |
|---|---|
| Chat·Runtime UI compile/unit | PASS |
| Runtime Compose package/tool smoke | **Samsung 7/7 PASS** — fixed Git argv 선택·terminal semantics·한글 IME·terminal exit accessibility·confirmed SIGTERM/SIGKILL·package snapshot/workspace action forwarding |
| Runtime Probe actual terminal lifecycle | **Samsung PASS** — actual PRoot initial `stty size=28 96`, fail-closed `INITIAL_SIZE_ONLY`/`TERMINAL_RESIZE_UNSUPPORTED`, safe terminal exit event, Host process `STARTED:3`/`STOPPED:3`, restart/repair healthy |
| Foreground-service process lease | **Local 4/4 PASS** — first start/last stop, nested terminal·command, duplicate close, FGS start-rejection host policy; actual notification removal은 `NOT_RUN` |
| Workspace SAF·share boundary | **Samsung 5/5 PASS** — bounded `content://` import/export, filename sanitize, provider I/O stable error, cache FileProvider share publish |
| Package metadata snapshot UI | PASS — license·payload·network/estimate boundary AndroidTest 회귀 |
| Integrated App lint/APK | PASS |
| Samsung integrated instrumentation | **10/10 PASS** — 기존 채팅 4건 + first-run guide 5건 + 접근성·한글 IME 1건 |
| Samsung Demo 전체 회귀 | **35/35 PASS** — Provider·Chat·lifecycle·Markdown·theme |
| OAuth lifecycle recovery | **PASS** — OAuth core 3건, Provider 12건, 복구 GUI 3건, credential-free `am force-stop` cold start |
| Integrated OAuth/app boundary scan | **Local PASS** — consumer/CLI fingerprint, known copied registration/API key/private key 및 demo/probe/sample package fail-closed 검사 |
| 빠른 채팅·Gateway·Runtime·패키지 화면 | Samsung portrait 확인 |
| 실제 Provider 계정 OAuth/API E2E | `NOT_RUN` — 외부 승인 필요 |

Instrumentation은 실제 credential을 사용하지 않는 fake Provider로 로그인→모델 선택→stream→모드 왕복→Stop→503→retry, 대화 복원과 Alpine fallback 승인·거절 전 direct Provider 미호출을 검증한다. OAuth lifecycle 회귀는 Activity 재생성, orphaned encrypted transaction 폐기, 성공 token 우선, stale attempt 격리와 명시적 재로그인을 검증한다. 추가 접근성 회귀는 한국어 icon label, 메시지 sender/status semantics, History·Assistant 닫기, 48dp action과 한글 IME 단일 전송을 검증한다. 장치 probe는 실제 PRoot Runtime, loopback HostBridge, bundled Python Gateway, `llmctl`, stream·cancel과 capability 회전을 별도로 검증한다.

## 알려진 제한

- 현재 PRoot guest terminal resize는 최초 PTY 크기만 지원한다.
- Workspace의 Android DocumentsUI full import → edit → terminal → export/share 수동 흐름은 `NOT_RUN`이다. 자동화는 test provider와 app-private explicit share URI까지만 다룬다.
- terminal UI는 bounded ANSI colour·cursor·clear·alternate screen snapshot과 raw guest output을 저장하지 않는 마지막 exit code 요약을 표시하지만, vi/nano/top의
  full TUI compatibility와 실제 외부 keyboard matrix는 아직 실기기 검증 전이다.
- terminal process 종료 뒤 실제 Samsung notification/FGS 제거 및 OEM background lifecycle은 Doze/reboot/battery restriction 승인 전 `NOT_RUN`이다.
- Provider·Chat·History·Assistant·first-run guide의 semantics, 48dp, 한글 IME와 200% font·compact 자동 검증은 통과했지만 실제 TalkBack 음성·focus gesture, Switch Access와 foldable 화면은 확장 QA가 남아 있다.
- 실제 Provider 계정 browser callback 도중 process kill, refresh/logout과 OEM background 정책은 승인된 계정·기기 창에서 추가 검증해야 한다.
- Gemini 외 direct Provider는 Provider가 승인한 model catalog를 아직 번들하지 않는다. 앱 소유 OAuth·endpoint·scope·model을 명시 입력해도 실계정 OAuth/API E2E 전에는 `NOT_RUN`이다.
- x86_64 Runtime은 emulator E2E 전까지 실험 상태다.
- 공개 배포는 프로젝트 라이선스와 corresponding source gate로 차단되어 있다.
