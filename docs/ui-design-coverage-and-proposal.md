# UI 디자인 적용 범위와 검증 기준

갱신 일시: `2026-08-15 20:04 KST`

## 목적

`Alpine AI Workspace` Android 통합 앱의 제품 화면·진단 화면·문서용 캡처 범위를 관리한다. 이 문서는 디자인 정본, 현재 구현 상태, README 캡처 계약과 남은 시각 QA를 함께 관리한다.

> **제품 판정:** 2026-08-15 소유자 검토에서 현재 GUI/UX는 배포 수준 미달로 판정되었다.
> 기존 token/theme 적용과 자동 접근성 계약 PASS는 기술적 적용 증거일 뿐, 시각 품질·정보 구조·사용성
> 승인으로 간주하지 않는다. 현재 배포 계획은 없으며 GUI/UX 전면 개선이 다음 제품 우선순위다.

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

| 제품/영역 | 실제 코드 경계 | 기술 적용 상태 | 2026-08-15 제품 판정 |
|---|---|---|---|
| Alpine AI Workspace shell·모드 전환 | `integrated-app/.../IntegratedMainActivity.kt` | 제품 theme·header·mode segment | 재설계 필요 |
| 빠른 채팅·History·Skill·Persona | `alpine-chat-feature/.../ui/` | 공통 card·상태·composer | 정보 구조·대화 UX 재설계 필요 |
| Provider 목록·빈 상태 | `alpine-chat-provider-android/.../ProviderScreens.kt` | `AlpineProductHeader`, `AlpineEmptyState`, `AlpinePrimaryAction` | 연결 흐름·시각 위계 재설계 필요 |
| Provider chooser | `ProviderScreens.kt:ProviderChooser` | `ModalBottomSheet`, Provider card·지원 상태 | 선택·지원 상태 UX 재검토 필요 |
| Provider edit | `ProviderScreens.kt:ProviderEditScreen` | 제품 theme와 form validation | 입력 밀도·도움말·오류 UX 재검토 필요 |
| Alpine Runtime dashboard | `alpine-runtime-ui-compose/.../RuntimeDashboard.kt` | 설치·상태·복구 action | 상태·작업 우선순위 재설계 필요 |
| Linux terminal | `RuntimeTerminalPanel.kt` | dark output·명령 bar·중단/종료 control | 모바일 조작·가독성 재설계 필요; full-screen TUI/dynamic resize는 미지원 |
| Package·workspace | `RuntimePackagePanel.kt`, `RuntimeWorkspacePanel.kt` | allowlist·승인·bounded workspace 작업 | 복잡도 축소와 task flow 재설계 필요 |
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

## GUI/UX 개선 우선순위

| 우선순위 | 작업 | 완료 기준 |
|---:|---|---|
| P0 | 현재 화면·상태·navigation 전수 감사 | 핵심 사용자 목표, 화면별 문제, 중복/막힘/오류 상태를 증거와 함께 목록화 |
| P0 | 제품 정보 구조와 핵심 여정 재설계 | 첫 실행 → Codex 로그인 → 대화, Runtime 준비 → 작업의 최소 흐름 확정 |
| P0 | 시각 방향·component hierarchy 재정의 | typography, spacing, elevation, colour, action hierarchy와 상태 패턴 승인 |
| P0 | 핵심 shell·채팅·로그인 화면 개편 | Samsung에서 주요 작업이 설명 없이 완료되고 상태/오류/복구가 명확함 |
| P1 | Runtime·terminal·package·workspace 개편 | 복잡도를 단계적으로 노출하고 위험 작업·진행 상태·복구 action 구분 |
| P1 | TalkBack·Switch Access 수동 검사 | 모드 전환, 로그인, History, terminal control의 label·focus 순서 기록 |
| P1 | compact/medium·dark/light·200% text scale golden | Android overflow·IME·inset·contrast 회귀 없음 |
| P2 | terminal 10분 렌더링/frame 계측 | 현재 `INITIAL_SIZE_ONLY` 계약 안에서 성능 증거 수집 |

UI 자동 계약이 통과해도 소유자 시각/사용성 승인이 없으면 배포 준비 완료로 판정하지 않는다.

## 검증 명령

```bash
python3 scripts/verify-ui-design-contract.py

```

기기 캡처는 현재 자동 CI가 아니라 민감정보를 제거한 수동 실기기 절차다. 해상도·파일 참조·README 미리보기 계약만 CI가 강제한다.
