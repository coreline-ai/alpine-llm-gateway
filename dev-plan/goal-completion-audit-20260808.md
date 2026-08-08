# GOAL 완료 감사 — 2026-08-08

대상 GOAL: Alpine AI Workspace의 남은 로컬 구현 가능 항목을 권장 순서대로 완료하고,
자동 테스트·Samsung 실기기 검증·문서 동기화까지 수행한다.

이 문서는 구현 의도나 과거 주장 대신, 현재 저장소 코드·자동 테스트·실기기 증거가 무엇을 직접
증명하는지 분리한다. `PASS`는 해당 범위의 증거가 있고, `NOT_RUN`/`BLOCKED`는 완료가 아니다.

## 최신 자동 검증

| 명령 | 결과 | 범위 |
|---|---|---|
| `python3.11 -m unittest discover -s tests -v` | PASS, 106 (no skip) | Gateway·release·module boundary |
| `backend/mobile_agent_bff/.venv/bin/pytest -q` | PASS, 39 | BFF fault/stream/OIDC boundary |
| Android/Runtime unit + lint + APK/AndroidTest APK Gradle matrix | PASS | Android, Provider, Runtime Android/Host/UI, integrated APK |
| `verify-mobile-oauth-release.py --integrated-product` | PASS, 71 files | copied OAuth/CLI/test APK boundary |
| `verify-release-readiness.py ... --check-evidence` | valid, `release_ready=false` | blocker report consistency |
| `git diff --check` | PASS | whitespace integrity |

### 2026-08-09 로컬·Samsung 재검증

- Python Gateway suite는 sandbox 밖 localhost binding을 포함해 **106 passed, no skip**, BFF suite는
  **39 passed**였다.
- Android unit/lint/APK/AndroidTest APK 전체 Gradle matrix는 재통과했다.
- terminal key 및 tablet scroll fixture 수정 뒤에도 Python **106 passed, no skip**, BFF **39 passed**,
  Android unit/lint/APK/AndroidTest APK matrix, integrated OAuth release scanner, UI design contract,
  release-readiness evidence와 `git diff --check`가 모두 재통과했다.
- Samsung automated matrix는 OAuth core **3/3**, Provider **12/12**, integrated app **10/10** PASS였다.
- 2026-08-09 Samsung 재실행에서도 OAuth core **3/3**, Provider **12/12**, integrated app
  **10/10**이 모두 failure/error/skip 없이 PASS했다. 계측 뒤 current integrated debug APK를
  재설치·cold start했고, 최종 package 점검에서는 integrated product만 확인했다.
- terminal key/tablet regression을 포함한 current debug APK를 Samsung에 replace install한 뒤
  `IntegratedMainActivity` cold start와 first-run credential-free mode guide를 다시 확인했다. final package
  검사에서는 integrated product 외 Demo·Probe·test package가 없었다.
- Compose 계측 전에 기기를 깨우고 keyguard 해제 요청을 수행했다. 잠금·dream 화면은 Compose hierarchy를
  가려 false failure를 만들 수 있으므로, 이 전제는 Samsung 재현 절차의 환경 조건으로 유지한다. 기기 설정,
  Doze, battery restriction, reboot는 변경하지 않았다.
- 이 재검증은 외부 Provider 계정, Play track, 원격 CI, x86_64 emulator 및 destructive lifecycle 승인을
  대신하지 않으며 해당 gate는 그대로 `BLOCKED`/`NOT_RUN`이다.

## GOAL Phase별 증거

| Phase | 현재 상태 | 직접 증거 | 남은 요구/판정 |
|---|---|---|---|
| 1. PRoot dynamic resize | **PARTIAL, production fail-closed** | Samsung Relay31/32 Probe-only private virtual query path는 bounded request, guest fixed-state query, small/large repeat 및 8-step storm을 확인했다. same-PTY tracee/host control evidence와 pinned PRoot static handoff audit도 유지한다. audit은 generic signal→`ptrace` restart와 Android ioctl sysexit trace는 확인했지만 stock terminal hook 부재를 확인했다. production은 `INITIAL_SIZE_ONLY`/`TERMINAL_RESIZE_UNSUPPORTED`이며 Probe artifact는 product APK에서 차단된다. | virtual query state는 physical PTY resize가 아니다. foreground `SIGWINCH`, rotation, alternate-screen TUI, orphan cleanup, actual terminal output/input semantics가 아직 없음. stock source에 안전하게 승격할 local hook이 없으므로 upstream/maintained terminal architecture가 필요하며 `GUEST_SIGWINCH_REPEAT_STRESS_UNSUPPORTED`를 유지한다. acceptance matrix 전체 전 `DYNAMIC` 승격 금지. |
| 2. Terminal/session UI | 구현·자동 회귀 PASS | bounded ANSI screen, sessions, exit summary, Ctrl/Stop/kill, Samsung Runtime Compose 8/8 (IME·Enter/Tab/Esc/Ctrl+C Compose key regression)와 terminal-close event | 외부 키보드 hardware/한글 IME 실사용, vi/nano/top full TUI, FGS notification 제거는 `NOT_RUN` |
| 3. Workspace/SAF/share | 구현·자동 Samsung 회귀 PASS | atomic/bounded workspace, SAF test provider 5/5, FileProvider share boundary | DocumentsUI import→edit→terminal→export/share 수동 흐름 `NOT_RUN` |
| 4. Package/dev tools/Gateway | 안전한 UI·unit workflow 구현 | allowlist/fixed argv/profile/Gateway bounded recovery unit regression | live repository metadata/dependency/disk-full/cancel 및 Gateway crash/runtime restart Samsung matrix `NOT_RUN` |
| 5. Python Gateway lifecycle | lifecycle/recovery code·fake regression PASS | health/bounded recovery/explicit stop/no replay unit regression | real Gateway crash/stale PID-port Samsung matrix `NOT_RUN` |
| 6. Provider 정책 | local hardening/official-link docs PASS | strict input, size limits, redacted stream errors, no copied consumer/CLI contract scanner | 실계정 OAuth/API/models/idempotency/429·5xx fault proxy `NOT_RUN` |
| 7. UI/accessibility/device | Samsung·Android 12 tablet automated core PASS | 두 기기 integrated 10/10, Samsung·tablet Runtime Compose 8/8, fake 10-turn Chat regression, compact/IME/accessibility semantics test, Alpine fallback viewport recovery | landscape/API26/35, TalkBack gesture/voice, contrast manual QA `NOT_RUN` |
| 8. release/security | local checks PASS, public release NO-GO | scanner, internal compliance verifier, SBOM/notice checks, readiness report | license, rootfs exact source, Play, provider owner, delivery owner, Doze/reboot, x86_64 E2E are BLOCKED |

## Samsung evidence

- device: `SM-S931N`, serial은 별도 report에 저장하지 않음.
- automated evidence: Android OAuth core 3/3, Provider 12/12, Workspace 5/5,
  Runtime Compose 8/8, integrated app 10/10 PASS. Runtime Compose에는 IME/Enter와 Tab·Esc·Ctrl+C
  external key event regression이 포함된다. Provider test APK target SDK를 명시해 Samsung의
  legacy compatibility dialog가 Compose test tree를 가리던 환경 회귀도 제거했다.
- 2026-08-09 재실행에서도 OAuth core 3/3, Provider 12/12, integrated app 10/10이
  failure/error/skip 없이 통과했다. 계측 APK 정리 뒤 current integrated debug APK를 다시
  설치·cold start했으며 최종 package 점검에는 integrated product만 남겼다.
  화면이 잠기거나 dream 상태이면 Compose hierarchy가 표시되지 않아 false failure가 날 수 있으므로,
  계측 시작 전에 화면을 깨운 뒤 keyguard 해제 요청을 하는 절차를 적용했다. 제품 코드·기기 정책은
  변경하지 않았다.
- latest base terminal Probe: initial `stty size=28 96`, terminal close safe event,
  process lifecycle `STARTED:3/STOPPED:3`, restart/repair after `READY` and healthy. Separate relay16
  diagnostic proves one initial shell trap only. resize 직후 fixed command를 write해도 interactive input이
  재개되지 않았고 repeat/storm matrix도 실패했으므로 product evidence가 아니다.
- final cleanup: test Probe/Demo/Sample 없음, `dev.alpine.integrated/.IntegratedMainActivity` cold start.

## Tablet evidence

- Android 12 tablet에서 Runtime Compose instrumentation 8/8이 통과했다. Enter·Tab·Esc·Ctrl+C
  Compose key event와 package review, workspace import/export/share action을 실제 scroll container에서
  검증했다.
- 첫 실행은 component만 배치한 test fixture가 viewport 밖 action의 click을 보장하지 못해 2건 실패했다.
  product `RuntimeWorkspaceScreen`의 scroll 구조와 동일한 test wrapper 및 `performScrollTo()`로 보정한
  뒤 재실행에서 8/8이 통과했다. production UI contract나 Provider/terminal data 경계는 변경하지 않았다.
- physical keyboard hardware, TalkBack gesture/voice, contrast, landscape 및 full-screen TUI의 수동
  검증은 계속 `NOT_RUN`이다.

## 완료 판정

GOAL 전체는 아직 완료가 아니다. 코드로 해결 가능한 기본 경계와 자동 회귀는 대체로 구현됐지만,
Phase 1의 source-level PRoot dynamic terminal capability와 여러 명시적 실기기/외부 조건이 직접
증명되지 않았다. 완료로 가장하지 않고 다음 순서를 유지한다.

1. `SIGWINCH → fixed printf → stty` 뒤 interactive input 재개를 보장하는 upstream/maintained
   resize architecture를 확보하고 별도 binary로 검증한다. pinned stock PRoot source에는 승격 가능한
   terminal hook이 없으므로 relay artifact를 product workaround로 확장하지 않는다.
2. 승인 또는 물리 입력이 불필요한 Samsung manual matrix를 가능한 범위에서 수행한다.
3. 외부 계정·Play·법무·reboot/Doze·x86_64는 owner/input/evidence가 제공될 때만 실행한다.

세부 dynamic resize 실험은 `implement_20260808_141500.md`, release blocker는
`distribution/release-readiness.json`을 기준으로 한다.
