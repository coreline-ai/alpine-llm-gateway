# UI 디자인 적용 범위와 개선 제안

작성 일시: `2026-08-07 KST`

## 목적

현재 제품 화면과 개발용 검증 화면을 구분하고, `Alpine AI Workspace`에 아직 일관되게 적용되지 않은 화면을 같은 시각 언어로 이식하기 위한 코드 기준 제안이다.

## 현재 디자인 기준

제품의 기준 디자인은 밝은 paper 배경, ink 외곽선, acid-green 핵심 action, terminal을 연상시키는 상태 표현이다.

| 역할 | 현재 token |
|---|---|
| Paper | `#F4F3ED` |
| Ink | `#10120F` |
| Acid action | `#B9F227` |
| Slate | `#31372F` |
| Warning | `#FFE5A3` |
| Raised surface | `#FFFEF8` |

정본은 `alpine-chat-feature/src/main/java/dev/alpine/chat/feature/ui/theme/Color.kt`와 `Theme.kt`다. 통합 앱은 `IntegratedMainActivity.kt:119`에서 light·non-dynamic theme를 강제해 이 정본을 사용한다.

## 화면별 코드 분석

| 화면/앱 | 주요 코드 | 현재 디자인 상태 | 판단 |
|---|---|---|---|
| 통합 앱 shell·모드 전환 | `integrated-app/.../IntegratedMainActivity.kt:209`, `:313`, `:372` | 브랜드 header, paper/ink/acid segment 적용 | 완료 기준 화면 |
| 빠른 채팅 | `alpine-chat-feature/.../AlpineChatScreen.kt:101` | 브랜드 card, 빈 상태, message bubble, composer 적용 | 적용됨 |
| 대화 기록 | `alpine-chat-feature/.../ConversationHistory.kt:52` | 공통 theme와 card 계층 사용 | 적용됨 |
| Skill·Persona | `alpine-chat-feature/.../AssistantModeSheet.kt:48` | 공통 theme 사용 | 적용됨, sheet hierarchy 보완 가능 |
| Runtime dashboard | `alpine-runtime-ui-compose/.../RuntimeDashboard.kt:59` | 공통 `MaterialTheme`를 상속해 통합 앱에서는 브랜드 색 사용 | 적용됨, 정보 밀도 보완 필요 |
| Linux terminal | `alpine-runtime-ui-compose/.../RuntimeTerminalPanel.kt:49` | terminal 전용 dark output과 브랜드 외곽선 사용 | 부분 적용, 전용 workspace 필요 |
| Package UI | `alpine-runtime-ui-compose/.../RuntimePackagePanel.kt:50` | 공통 색은 적용되나 comma 입력+기본 dialog 중심 | 부분 적용 |
| Provider 목록 | `alpine-chat-provider-android/.../ProviderScreens.kt:78` | 기본 TopAppBar/FAB/Card 중심 | **우선 개선 필요** |
| Provider 추가/수정 | `alpine-chat-provider-android/.../ProviderScreens.kt:380` | 긴 기본 Material form, 기술 필드가 한 화면에 노출 | **우선 개선 필요** |
| Provider 종류 dialog | `alpine-chat-provider-android/.../ProviderScreens.kt:325` | 기본 AlertDialog 안에 긴 5개 목록 | **우선 개선 필요** |
| MobileAgent Flutter | `apps/mobile_agent/lib/src/mobile_agent_app.dart:43`, `:74`, `:497` | 별도 구현이지만 동일 paper/ink/acid 방향 적용 | 적용됨, token 동기화 필요 |
| demo-chatbot | `demo-chatbot/.../MainActivity.kt:35` | 채팅 Feature는 적용되나 독립 앱 shell/identity가 약함 | 개발 데모로 분류 |
| Runtime/Bridge probe | `alpine-runtime-probe/.../RuntimeProbeActivity.kt:38`, `alpine-llm-bridge-probe/.../LlmBridgeProbeActivity.kt:38` | programmatic LinearLayout, raw JSON 결과 | 디자인 대상이 아닌 진단 도구 |
| SDK sample | `sample/.../MainActivity.kt:35`, `alpine-integration-sample/.../RuntimeSampleActivity.kt:19` | 기본 Android View/XML | 외부 SDK 예제 또는 내부 샘플로 분류 |

## 가장 큰 불일치의 직접 원인

Provider 화면은 통합 앱에서 별도 `Activity`로 열린다. 통합 앱은 다음처럼 제품 theme를 고정한다.

```kotlin
AlpineChatTheme(darkTheme = false, dynamicColor = false) { ... }
```

반면 아래 두 Activity는 인자 없는 `AlpineChatTheme`를 사용한다.

- `ProviderProfilesActivity.kt:43`
- `ProviderEditActivity.kt:43`

인자 없는 theme의 기본값은 시스템 dark mode와 Android dynamic color를 따른다. 따라서 통합 앱의 밝은 paper/acid 화면에서 Provider 화면으로 이동하면 검은 배경과 파란 Material 색으로 바뀐다. 캡처된 Provider 목록·종류 화면이 다른 앱처럼 보이는 직접 원인이다.

추가로 `AlpineBrandHeader`, mode segment와 제품용 surface가 `IntegratedMainActivity.kt`의 `private` composable로 묶여 있어 Provider 모듈이 재사용할 수 없다.

## 권장 디자인 방향

기존 시각 언어를 바꾸기보다 **Linux 작업 상태가 읽히는 Field Console**로 정돈한다.

- 제품 header는 화면마다 장식적으로 반복하지 않고 현재 위치와 상태를 전달한다.
- acid-green은 한 화면의 주 action 한 곳에만 사용한다.
- 검은 terminal surface는 실제 command/output 또는 핵심 상태 hero에만 사용한다.
- 모든 상태 화면은 `현재 상태 → 의미 → 다음 action` 순서로 읽히게 한다.
- 한국어를 주 언어로 사용하고 Provider명, model명, protocol명만 영문 technical label로 유지한다.
- 고유 signature는 **작업 상태 rail**이다. Runtime, OAuth, streaming 모두 짧은 상태 label과 다음 action을 같은 위치에 표시한다.

## 구현 제안

### P0. Provider 화면을 즉시 제품 theme로 고정

영향 파일:

- `ProviderProfilesActivity.kt`
- `ProviderEditActivity.kt`

제안:

1. 두 Activity 모두 제품용 `darkTheme = false`, `dynamicColor = false`를 사용한다.
2. status/navigation bar도 통합 앱과 같은 light edge-to-edge 정책을 적용한다.
3. 이 변경만으로도 검은 배경·파란 FAB 불일치는 제거된다.

### P1. 재사용 가능한 Alpine 디자인 시스템 추출

새 경계 제안:

```text
alpine-chat-feature/ui/designsystem/
├── AlpineProductTheme.kt
├── AlpineProductScaffold.kt
├── AlpineBrandBar.kt
├── AlpineSectionCard.kt
├── AlpineStatusRail.kt
├── AlpinePrimaryAction.kt
├── AlpineEmptyState.kt
└── AlpineConfirmDialog.kt
```

- `IntegratedMainActivity`의 private header/segment 구현을 공통 모듈로 이동한다.
- Provider와 Runtime 모듈은 color hex를 복사하지 않고 공통 token만 사용한다.
- library consumer가 자신의 theme를 주입할 수 있도록 Runtime UI 자체가 강제로 theme를 덮지는 않는다.
- 제품 앱과 SDK library의 책임을 분리하기 위해 `AlpineProductTheme`는 app shell에서 한 번만 적용한다.

### P1. Provider 목록 재설계

현재 문제:

- 빈 화면의 정보량이 적고 FAB가 제품 CTA와 다른 언어를 사용한다.
- 연결 상태, 모델, 로그인 action의 시각 우선순위가 약하다.
- 모두 영문이라 통합 앱의 한국어 flow와 단절된다.

제안 구조:

```text
┌ LLM 연결 ───────────────── 상태 rail ┐
│ 외부 LLM 계정을 안전하게 연결합니다  │
│ [ + 새 LLM 연결 ]                    │
├ Provider card ───────────────────────┤
│ Claude                    연결됨      │
│ Anthropic · claude-...               │
│ [모델 변경] [로그아웃] [⋯]           │
└──────────────────────────────────────┘
```

- FAB를 화면 상단의 acid primary action으로 이동한다.
- 빈 상태에도 `새 LLM 연결` action을 함께 제공한다.
- Provider card에 상태 badge, model, 최근 오류의 redacted code와 명확한 다음 action을 표시한다.
- 연결 중에는 해당 card에만 progress를 표시하고 다른 profile action 정책을 명확히 한다.

### P1. Provider 추가/수정 화면 재설계

현재 문제:

- OAuth endpoint, scope, callback port와 model이 한 긴 form에 같은 우선순위로 노출된다.
- 일반 사용자와 compatibility/개발자 설정이 섞여 있다.
- 저장 action이 top bar와 하단에 중복된다.

제안:

- 1단계 `Provider 선택` → 2단계 `앱 소유 Client ID` → 3단계 `모델 선택` 흐름으로 나눈다.
- 고정 endpoint/scope/callback은 읽기 전용 텍스트 필드가 아니라 `프로토콜 정보` 접힘 영역으로 옮긴다.
- `OpenAI-compatible`만 고급 endpoint 편집을 허용한다.
- sticky bottom action은 `저장하고 로그인`, 보조 action은 `나중에 로그인`으로 통일한다.
- 다른 앱/CLI의 Client ID를 복사하지 말라는 경고를 Client ID 입력 바로 아래에 배치한다.
- 오류는 필드 아래에서 `무엇이 잘못됐는지 + 어떻게 수정하는지`를 함께 설명한다.

### P1. Provider 종류 dialog를 full-height sheet로 전환

- 기본 `AlertDialog` 대신 검색 가능하거나 최소한 스크롤 가능한 `ModalBottomSheet`를 사용한다.
- 각 row는 Provider명, 공식 inference 경로, 제품 지원/compatibility 상태를 분리한다.
- `지원`, `호환성`, `설정 필요` badge로 사용자가 실제 지원 범위를 오해하지 않게 한다.
- 작은 화면과 200% font에서 마지막 Provider와 취소 action이 잘리지 않게 한다.

### P2. Runtime·Terminal·Package 화면 보강

- Runtime dashboard: `설치 필요/실행 중/복구 필요`마다 가장 필요한 action 하나만 acid로 표시한다.
- 파괴적인 `초기화`는 별도 danger 영역과 확인 문구로 이동한다.
- Terminal: dashboard card 안의 작은 창이 아니라 session header, scrollback, command bar가 있는 전용 화면을 제공한다.
- Package: 허용 package를 긴 문장 대신 filter chip/list로 표시하고 선택→용량/권한/라이선스 확인→설치 순서로 바꾼다.
- 설치 결과를 단순 문장 대신 성공/거부/취소 status rail과 다음 action으로 표시한다.

### P2. MobileAgent와 Android token 동기화

Flutter는 `_ink`, `_paper`, `_acid`, `_slate`를 별도로 선언한다. 값은 현재 일치하지만 drift를 막을 계약이 없다.

- `docs/design/alpine-product-tokens.json` 또는 코드 생성이 가능한 단일 token 명세를 둔다.
- Android Compose와 Flutter는 이름·hex·spacing·radius mapping 테스트를 가진다.
- 앱은 서로 다른 제품명이므로 launcher icon과 subtitle은 구분하되 시각 계보만 공유한다.

### P2. 개발용 앱은 디자인보다 배포 경계를 우선

Runtime Probe, Bridge Probe, SDK sample은 제품 화면으로 미화하지 않는 편이 낫다.

- launcher label과 icon에 `DEV`, `PROBE`, `SAMPLE`을 명확히 표시한다.
- release variant 또는 공개 Play artifact에는 포함하지 않는다.
- 필요하면 공통 `DiagnosticConsole` skin만 적용하되 raw JSON, test action과 증거 저장 기능은 유지한다.
- 사용자가 제품 앱으로 오인하지 않도록 통합 앱과 같은 launcher icon을 사용하지 않는다.

## 테스트 제안

현재 Provider 모듈에는 model/session unit test는 있지만 Compose UI instrumentation test가 없다.

우선 추가할 검증:

1. Provider 목록 empty/connected/reauth/authorizing 상태 Compose test
2. Provider chooser 200% font와 작은 화면 scroll test
3. Provider edit validation, IME, 회전과 process recreation test
4. 통합 앱→Provider Activity→복귀 시 theme와 선택 상태 유지 test
5. Samsung 1080×2340 screenshot baseline과 360×800 emulator compact baseline
6. TalkBack label, 48dp touch target, contrast와 keyboard navigation 점검
7. README screenshot은 모두 `1080 × 2340`인지 CI에서 검사

## 권장 구현 순서

| 순서 | 작업 | 크기 | 효과 |
|---:|---|---|---|
| 1 | Provider Activity theme 고정 | S | 검은/파란 기본 화면 즉시 제거 |
| 2 | 공통 product scaffold·header·status component 추출 | M | 새 화면까지 일관성 확보 |
| 3 | Provider 목록·종류 선택 재설계 | M | 가장 눈에 띄는 미디자인 구간 해결 |
| 4 | Provider 편집 form 단계화 | M | OAuth 설정 난이도와 오류 감소 |
| 5 | Runtime/Terminal/Package 정보 구조 개선 | L | Alpine 작업 모드의 제품성 향상 |
| 6 | MobileAgent token 계약과 앱 identity 분리 | M | 다중 앱 혼동과 디자인 drift 방지 |
| 7 | Provider UI·접근성·screenshot 회귀 gate | M | 이후 디자인 퇴행 방지 |

## 결론

전체 앱이 미디자인 상태인 것은 아니다. 통합 shell, 채팅, 대화 기록, Assistant와 Runtime 기본 panel은 이미 동일한 디자인 언어를 사용한다. 가장 큰 단절은 별도 Activity에서 시스템 dark/dynamic theme를 사용하는 Provider 관리 화면이다. 따라서 **Provider theme 고정 → 공통 디자인 시스템 추출 → Provider 목록/편집 재설계** 순서가 비용 대비 효과가 가장 크다.
