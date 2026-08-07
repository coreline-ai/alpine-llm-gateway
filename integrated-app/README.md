# Alpine Integrated App

[![Version](https://img.shields.io/badge/version-0.3.0-B9F227?style=flat-square&labelColor=10120F)](../gradle.properties)
![Android](https://img.shields.io/badge/Android-API%2026--36-3DDC84?style=flat-square&logo=android&logoColor=white)
![Modes](https://img.shields.io/badge/modes-Fast%20Chat%20%7C%20Alpine%20Work-31372F?style=flat-square)
![Distribution](https://img.shields.io/badge/distribution-INTERNAL%20ONLY-F59E0B?style=flat-square)

`dev.alpine.integrated`는 재사용 Chat·Provider·Runtime·Bridge SDK를 한 Android 앱에 조립하는 제품 통합 Host다. 검정·아이보리·라임 디자인 시스템을 사용하며 시스템 dynamic color와 무관하게 동일한 제품 팔레트를 유지한다.

> [!IMPORTANT]
> 이 앱은 `apps/mobile_agent` Flutter 앱과 `demo-chatbot` compatibility 앱과 별도 package·저장소·token 경계를 가진다. 자동으로 profile이나 OAuth token을 공유하지 않는다.

## 화면

<table>
  <tr>
    <td align="center"><a href="../docs/assets/screenshots/01-fast-chat.png"><img src="../docs/assets/screenshots/01-fast-chat.png" width="250" alt="빠른 채팅"></a><br><strong>빠른 채팅</strong></td>
    <td align="center"><a href="../docs/assets/screenshots/07-alpine-gateway-chat.png"><img src="../docs/assets/screenshots/07-alpine-gateway-chat.png" width="250" alt="Alpine Gateway"></a><br><strong>Gateway 채팅</strong></td>
    <td align="center"><a href="../docs/assets/screenshots/08-alpine-runtime-tools.png"><img src="../docs/assets/screenshots/08-alpine-runtime-tools.png" width="250" alt="Runtime 도구"></a><br><strong>Runtime · 터미널</strong></td>
  </tr>
  <tr>
    <td align="center"><a href="../docs/assets/screenshots/02-conversation-history.png"><img src="../docs/assets/screenshots/02-conversation-history.png" width="250" alt="대화 기록"></a><br>대화 기록</td>
    <td align="center"><a href="../docs/assets/screenshots/03-assistant-mode.png"><img src="../docs/assets/screenshots/03-assistant-mode.png" width="250" alt="Assistant 설정"></a><br>Skill · Persona</td>
    <td align="center"><a href="../docs/assets/screenshots/09-runtime-package-manager.png"><img src="../docs/assets/screenshots/09-runtime-package-manager.png" width="250" alt="패키지 설치"></a><br>패키지 allowlist</td>
  </tr>
</table>

전체 10개 화면은 [루트 README 앱 갤러리](../README.md#screens)에서 확인한다. OAuth 계정·client ID·token이 포함될 수 있는 상세 입력 화면은 저장소 이미지에서 제외한다.

## 현재 모드

| 모드 | 현재 동작 |
|---|---|
| **빠른 채팅** | 공통 Chat Feature, Android OAuth Provider 관리, 모델 선택, Skill·Persona, stream·Stop·retry, 암호화 대화 복원 |
| **Alpine 작업** | 공통 Gateway 채팅, Runtime·HostBridge·Python Gateway 상태, 시작·상태·재시작·종료·복구, PTY 터미널·패키지 도구 |

실행 모드는 대화별로 저장된다. 생성 중 Alpine 작업 화면으로 이동해도 Provider 요청을 취소하거나 다시 보내지 않으며, 빠른 채팅으로 돌아와 Stop할 수 있다. Runtime 설치·실패 상태는 Android 직접 Provider 빠른 채팅 경로를 차단하지 않는다.

Alpine 채팅은 `IntegratedApplication`이 소유하는 단일 Bridge lifecycle을 사용한다. OAuth token은 Android Provider session 밖으로 이동하지 않으며 Alpine Guest에는 15분 TTL capability 파일과 loopback endpoint만 전달된다. Provider 또는 모델이 바뀌면 이전 Bridge process를 닫고 capability를 교체한다.

Alpine 준비가 dispatch 전에 실패한 경우에도 사용자가 dialog에서 승인한 해당 요청만 빠른 채팅으로 보낼 수 있다. Provider dispatch 또는 첫 delta 이후에는 자동 재전송하지 않는다.

## Provider 시작 순서

1. `빠른 채팅`에서 `LLM connection` 또는 우측 상단 연결 아이콘을 연다.
2. Provider profile을 추가하고 **앱 소유·승인된** OAuth public client registration으로 로그인한다.
3. 빠른 채팅으로 돌아와 계정과 모델을 선택한다.
4. Skill·Persona를 선택하고 메시지를 전송한다.
5. 생성 중에는 Stop, redacted 오류에는 허용된 Retry를 사용한다.

Anthropic/Codex/xAI direct OAuth 유형은 compatibility/reference 경로다. 다른 앱·CLI의 client ID를 복사해 제품 connector로 사용하지 않는다. 실제 출시 인증 경계는 [Provider OAuth adapter 요구사항](../docs/provider-oauth-adapters.md)을 따른다.

## Alpine 작업 시작 순서

1. Provider profile에 로그인하고 사용할 모델을 선택한다.
2. 상단에서 `Alpine 작업`을 선택한다.
3. 처음 사용하는 경우 `터미널·도구`에서 Runtime을 설치한다.
4. `Alpine LLM Gateway` 카드에서 `시작`을 누르고 통합 health를 확인한다.
5. `Gateway 채팅`에서 메시지를 보내거나 `터미널·도구`에서 terminal/package 기능을 사용한다.

키보드가 열린 동안에는 작은 화면에서 답변과 오류가 가려지지 않도록 Alpine 상태 카드와 sub-navigation을 임시로 접는다. 키보드를 닫으면 다시 표시된다.

패키지 설치는 `curl`, `git`, `openssh-client`, `py3-pip`, `python3`의 exact allowlist와 사용자 확인을 모두 통과해야 한다. 임의 shell 문자열은 실행하지 않는다.

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

2026-08-07 기준:

| 검증 | 결과 |
|---|---|
| Chat·Runtime UI compile/unit | PASS |
| Integrated App lint/APK | PASS |
| Samsung fast chat instrumentation | **4/4 PASS** |
| 빠른 채팅·Gateway·Runtime·패키지 화면 | Samsung portrait 확인 |
| 실제 Provider 계정 OAuth/API E2E | `NOT_RUN` — 외부 승인 필요 |

Instrumentation은 실제 credential을 사용하지 않는 fake Provider로 로그인→모델 선택→stream→모드 왕복→Stop→503→retry, 대화 복원과 Alpine fallback 승인·거절 전 direct Provider 미호출을 검증한다. 장치 probe는 실제 PRoot Runtime, loopback HostBridge, bundled Python Gateway, `llmctl`, stream·cancel과 capability 회전을 별도로 검증한다.

## 알려진 제한

- 현재 PRoot guest terminal resize는 최초 PTY 크기만 지원한다.
- 큰 글꼴·가장 긴 Provider/모델명·가로 화면 조합과 실제 TalkBack 제스처는 확장 QA가 남아 있다.
- x86_64 Runtime은 emulator E2E 전까지 실험 상태다.
- 공개 배포는 프로젝트 라이선스와 corresponding source gate로 차단되어 있다.
