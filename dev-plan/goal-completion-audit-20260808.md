# GOAL 완료 감사 — 2026-08-08

대상 GOAL: Alpine AI Workspace의 남은 로컬 구현 가능 항목을 권장 순서대로 완료하고,
자동 테스트·Samsung 실기기 검증·문서 동기화까지 수행한다.

이 문서는 구현 의도나 과거 주장 대신, 현재 저장소 코드·자동 테스트·실기기 증거가 무엇을 직접
증명하는지 분리한다. `PASS`는 해당 범위의 증거가 있고, `NOT_RUN`/`BLOCKED`는 완료가 아니다.

## 최신 자동 검증

| 명령 | 결과 | 범위 |
|---|---|---|
| `python3.11 -m unittest discover -s tests -v` | PASS, 104 (local-socket 4 skipped) | Gateway·release·module boundary |
| `backend/mobile_agent_bff/.venv/bin/pytest -q` | PASS, 39 | BFF fault/stream/OIDC boundary |
| Android/Runtime unit + lint + APK/AndroidTest APK Gradle matrix | PASS | Android, Provider, Runtime Android/Host/UI, integrated APK |
| `verify-mobile-oauth-release.py --integrated-product` | PASS, 71 files | copied OAuth/CLI/test APK boundary |
| `verify-release-readiness.py ... --check-evidence` | valid, `release_ready=false` | blocker report consistency |
| `git diff --check` | PASS | whitespace integrity |

## GOAL Phase별 증거

| Phase | 현재 상태 | 직접 증거 | 남은 요구/판정 |
|---|---|---|---|
| 1. PRoot dynamic resize | **PARTIAL, production fail-closed** | Samsung relay21은 initial/active same-PTY guest tracee가 physical foreground pgrp임을 source-level fixed stage로 확인했고, PRoot 없는 host PTY control은 `TIOCSWINSZ → SIGWINCH → 이후 input`을 통과했다. relay24는 host-master resize와 post-launch signal이 없는 private-memfd path도 검증했다. production은 `INITIAL_SIZE_ONLY`/`TERMINAL_RESIZE_UNSUPPORTED`로 유지하며 Probe artifact는 product APK에서 차단된다. | relay24의 memfd store/fd-ready 뒤 guest read/apply와 fixed `printf`·`stty`·helper·follow-up input은 미응답이었다. 같은 private request를 validate/ack만 하는 no-write control도 input을 재개하지 못했으므로 memfd write/read나 `SIGWINCH`만이 원인은 아니다. PRoot terminal session/supervisor interaction의 maintained source-level architecture가 필요하다. rotation, foreground TUI, orphan matrix도 없음. `GUEST_SIGWINCH_REPEAT_STRESS_UNSUPPORTED` 유지; 반복 터미널 semantics가 증명되기 전 `DYNAMIC` 금지. |
| 2. Terminal/session UI | 구현·자동 회귀 PASS | bounded ANSI screen, sessions, exit summary, Ctrl/Stop/kill, Runtime UI test와 Samsung terminal-close event | 외부 키보드/한글 IME 실사용, vi/nano/top full TUI, FGS notification 제거는 `NOT_RUN` |
| 3. Workspace/SAF/share | 구현·자동 Samsung 회귀 PASS | atomic/bounded workspace, SAF test provider 5/5, FileProvider share boundary | DocumentsUI import→edit→terminal→export/share 수동 흐름 `NOT_RUN` |
| 4. Package/dev tools/Gateway | 안전한 UI·unit workflow 구현 | allowlist/fixed argv/profile/Gateway bounded recovery unit regression | live repository metadata/dependency/disk-full/cancel 및 Gateway crash/runtime restart Samsung matrix `NOT_RUN` |
| 5. Python Gateway lifecycle | lifecycle/recovery code·fake regression PASS | health/bounded recovery/explicit stop/no replay unit regression | real Gateway crash/stale PID-port Samsung matrix `NOT_RUN` |
| 6. Provider 정책 | local hardening/official-link docs PASS | strict input, size limits, redacted stream errors, no copied consumer/CLI contract scanner | 실계정 OAuth/API/models/idempotency/429·5xx fault proxy `NOT_RUN` |
| 7. UI/accessibility/device | Samsung automated core PASS | integrated 10/10, fake 10-turn Chat regression, compact/IME/accessibility semantics test | tablet consent dialog, landscape/API26/35, TalkBack gesture/voice, contrast manual QA `NOT_RUN` |
| 8. release/security | local checks PASS, public release NO-GO | scanner, internal compliance verifier, SBOM/notice checks, readiness report | license, rootfs exact source, Play, provider owner, delivery owner, Doze/reboot, x86_64 E2E are BLOCKED |

## Samsung evidence

- device: `SM-S931N`, serial은 별도 report에 저장하지 않음.
- automated evidence: Android OAuth core 3/3, Provider 12/12, Workspace 5/5,
  Runtime Compose 7/7, integrated app 10/10 PASS. Provider test APK target SDK를 명시해 Samsung의
  legacy compatibility dialog가 Compose test tree를 가리던 환경 회귀도 제거했다.
- latest base terminal Probe: initial `stty size=28 96`, terminal close safe event,
  process lifecycle `STARTED:3/STOPPED:3`, restart/repair after `READY` and healthy. Separate relay16
  diagnostic proves one initial shell trap only. resize 직후 fixed command를 write해도 interactive input이
  재개되지 않았고 repeat/storm matrix도 실패했으므로 product evidence가 아니다.
- final cleanup: test Probe/Demo/Sample 없음, `dev.alpine.integrated/.IntegratedMainActivity` cold start.

## 완료 판정

GOAL 전체는 아직 완료가 아니다. 코드로 해결 가능한 기본 경계와 자동 회귀는 대체로 구현됐지만,
Phase 1의 source-level PRoot dynamic terminal capability와 여러 명시적 실기기/외부 조건이 직접
증명되지 않았다. 완료로 가장하지 않고 다음 순서를 유지한다.

1. `SIGWINCH → fixed printf → stty` 뒤 interactive input 재개를 보장하는 maintained source-level
   resize architecture를 설계하고 별도 binary로 검증한다. relay16은 initial-shell diagnostic으로만 유지한다.
2. 승인 또는 물리 입력이 불필요한 Samsung manual matrix를 가능한 범위에서 수행한다.
3. 외부 계정·Play·법무·reboot/Doze·x86_64는 owner/input/evidence가 제공될 때만 실행한다.

세부 dynamic resize 실험은 `implement_20260808_141500.md`, release blocker는
`distribution/release-readiness.json`을 기준으로 한다.
