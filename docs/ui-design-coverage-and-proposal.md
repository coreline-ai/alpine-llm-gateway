# UI 디자인 적용 범위와 검증 기준

갱신 일시: `2026-08-09 KST`

## 목적

`Alpine AI Workspace` Android 통합 앱의 제품 화면·진단 화면·문서용 캡처 범위를 관리한다. 이 문서는 디자인 정본, 현재 구현 상태, README 캡처 계약과 남은 시각 QA를 함께 관리한다.

## 디자인 정본

| 역할 | Token |
|---|---|
| Paper | `#F4F3ED` |
| Ink | `#10120F` |
| Acid action | `#B9F227` |
| Slate | `#31372F` |
| Warning | `#FFE5A3` |
| Raised surface | `#FFFEF8` |

정본은 [`docs/design/alpine-product-tokens.json`](design/alpine-product-tokens.json)이다. Android Compose는 `alpine-chat-feature` theme·designsystem을 사용하며, `scripts/verify-ui-design-contract.py`가 token drift와 README 캡처 계약을 검사한다.

## 현재 제품 화면 상태

| 제품/영역 | 실제 코드 경계 | 상태 | 2026-08-09 판단 |
|---|---|---|---|
| Alpine AI Workspace shell·모드 전환 | `integrated-app/.../IntegratedMainActivity.kt` | 제품 theme·header·mode segment | 적용됨 |
| 빠른 채팅·History·Skill·Persona | `alpine-chat-feature/.../ui/` | 공통 card·상태·composer | 적용됨 |
| Provider 목록·빈 상태 | `alpine-chat-provider-android/.../ProviderScreens.kt` | `AlpineProductHeader`, `AlpineEmptyState`, `AlpinePrimaryAction` | 적용됨 · PD20 캡처 확인 |
| Provider chooser | `ProviderScreens.kt:ProviderChooser` | `ModalBottomSheet`, Provider card·지원 상태 | 적용됨 · 실제 OAuth 입력 전 화면만 문서화 가능 |
| Provider edit | `ProviderScreens.kt:ProviderEditScreen` | 제품 theme와 form validation | 적용됨 · 실 client ID/로그인 E2E는 외부 승인 필요 |
| Alpine Runtime dashboard | `alpine-runtime-ui-compose/.../RuntimeDashboard.kt` | 설치·상태·복구 action | 적용됨 |
| Linux terminal | `RuntimeTerminalPanel.kt` | dark output·명령 bar·중단/종료 control | 적용됨 · full-screen TUI/dynamic resize는 미지원 |
| Package·workspace | `RuntimePackagePanel.kt`, `RuntimeWorkspacePanel.kt` | allowlist·승인·bounded workspace 작업 | 적용됨 |
| Probe·sample 앱 | `*-probe`, `sample`, `demo-chatbot` | 진단/예제 | 제품 디자인 범위 밖 |

## README 실제 화면 캡처

루트 [`README.md`](../README.md#screens)는 사용자 정보가 없는 **15개 실제 Android PNG**를 참조한다.

| 구분 | 개수 | 해상도 | 포함 화면 |
|---|---:|---|---|
| Alpine AI Workspace 기존 실기기 캡처 | 10 | `1080 × 2340` | 빠른 채팅, History, Assistant, Provider, Gateway, Runtime, package |
| Alpine AI Workspace 2026-08-09 PD20 캡처 | 5 | `1080 × 2160` | first-run guide, Provider 빈 상태, Gateway 준비, Runtime 설치, terminal 명령 패널 |

원본 이미지는 `docs/assets/screenshots/`에 둔다. README는 `width="260"`만 지정해 기기별 실제 세로 비율을 보존한다. 캡처 해상도 허용 목록·파일 수·경로·중복 참조는 `scripts/verify-ui-design-contract.py`로 CI에서 검사한다.

### 캡처 제외 규칙

다음은 문서에 저장하지 않는다.

- 실제 OAuth browser/callback, account, client ID, authorization code, token, credential
- 사용자 workspace 경로·파일명·terminal 출력·shell history
- destructive Runtime/package action의 개인화된 결과

따라서 README 갤러리는 모든 상태의 덤프가 아니라, 현재 검토 가능한 **핵심 공개 플로우**다.

## 남은 디자인 QA

| 우선순위 | 작업 | 완료 기준 |
|---:|---|---|
| P0 | TalkBack·VoiceOver 수동 검사 | 모드 전환, Provider, History, terminal control의 label·focus 순서 기록 |
| P1 | compact/medium·dark/light·200% text scale golden | Android overflow·IME·inset 회귀 없음 |
| P1 | terminal 10분 렌더링/frame 계측 | full-screen TUI가 아닌 현재 `INITIAL_SIZE_ONLY` 계약 안에서 증거 수집 |

## 검증 명령

```bash
python3 scripts/verify-ui-design-contract.py

```

기기 캡처는 현재 자동 CI가 아니라 민감정보를 제거한 수동 실기기 절차다. 해상도·파일 참조·README 미리보기 계약만 CI가 강제한다.
