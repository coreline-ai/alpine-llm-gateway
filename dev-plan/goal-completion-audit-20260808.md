# GOAL 완료 감사 — 2026-08-08

대상 GOAL: Alpine AI Workspace의 남은 로컬 구현 가능 항목을 권장 순서대로 완료하고,
자동 테스트·Samsung 실기기 검증·문서 동기화까지 수행한다.

이 문서는 구현 의도나 과거 주장 대신, 현재 저장소 코드·자동 테스트·실기기 증거가 무엇을 직접
증명하는지 분리한다. `PASS`는 해당 범위의 증거가 있고, `NOT_RUN`/`BLOCKED`는 완료가 아니다.

## 최신 자동 검증

| 명령 | 결과 | 범위 |
|---|---|---|
| `python3.11 -m unittest discover -s tests -v` | PASS, 117 (no skip) | Gateway·release·module boundary, x86 gate serial-redaction regression |
| `backend/mobile_agent_bff/.venv/bin/pytest -q` | PASS, 39 | BFF fault/stream/OIDC boundary |
| Android/Runtime unit + lint + APK/AndroidTest APK Gradle matrix | PASS | Android, Provider, Runtime Android/Host/UI, integrated APK |
| `verify-mobile-oauth-release.py --integrated-product` | PASS, 71 files | copied OAuth/CLI/test APK boundary |
| `verify-release-readiness.py ... --check-evidence` | valid, `release_ready=false` | blocker report consistency |
| `git diff --check` | PASS | whitespace integrity |

### 2026-08-09 로컬·Samsung 재검증

- Python Gateway suite는 sandbox 밖 localhost binding을 포함해 **111 passed, no skip**, BFF suite는
  **39 passed**였다.
- Android unit/lint/APK/AndroidTest APK 전체 Gradle matrix는 재통과했다.
- terminal key 및 tablet scroll fixture 수정 뒤에도 Python **111 passed, no skip**, BFF **39 passed**,
  Android unit/lint/APK/AndroidTest APK matrix, integrated OAuth release scanner, UI design contract,
  release-readiness evidence와 `git diff --check`가 모두 재통과했다.
- Samsung automated matrix는 OAuth core **3/3**, Provider **12/12**, integrated app **10/10** PASS였다.
- 2026-08-09 Samsung 재실행에서도 OAuth core **3/3**, Provider **12/12**, integrated app
  **10/10**이 모두 failure/error/skip 없이 PASS했다. 계측 뒤 current integrated debug APK를
  재설치·cold start했고, 최종 package 점검에서는 integrated product만 확인했다.
- terminal key/tablet regression을 포함한 current debug APK를 Samsung에 replace install한 뒤
  `IntegratedMainActivity` cold start와 first-run credential-free mode guide를 다시 확인했다. final package
  검사에서는 integrated product 외 Demo·Probe·test package가 없었다.
- Compose 계측 전 test setup은 `KEYCODE_WAKEUP`만 보내 DreamActivity overlay를 해제한다. Keyguard를
  해제하거나 기기 보안 설정을 바꾸지 않으므로 잠긴 기기는 여전히 사용자 상호작용이 필요하다. 또한 test마다
  기존 `IntegratedMainActivity`가 완전히 종료된 뒤 다음 Compose root를 시작하도록 대기한다. Doze,
  battery restriction, reboot는 변경하지 않았다.
- forkpty source-audit 재실행에서는 OAuth core **3/3**, Provider **12/12**, Runtime native forkpty
  **3/3**, integrated app **10/10**, unpatched PRoot expected-negative Probe **1/1**이 통과했다. pinned
  stock PRoot audit은 tracer의 default-ignore policy와 `SIGWINCH` non-special-case를 확인했지만 이를 exact
  signal-gap 원인으로 단정하지 않았다. 계측이 제거한 테스트/Probe package 뒤에는 최신 integrated APK만
  재설치·cold start했다.
- current GOAL local refresh에서는 Python **111/111**, BFF **39/39**, GOAL Android
  unit/API/lint/debug APK/AndroidTest APK matrix, Samsung OAuth core **3/3**·Provider **12/12**·integrated
  **10/10**, latest integrated product-only cold start를 다시 확인했다. OAuth product scanner(71 files),
  SDK publication(19 variants), published consumer(8 variants), UI contract, packaged runtime checksum/16 KiB
  alignment과 `git diff --check`도 통과했다. local OSS source bundle에는 Git metadata가 없으므로 PRoot
  static handoff audit은 `--skip-revision-check` invariant-only 결과이며, source revision을 새로 증명하는
  근거는 아니다.
- 후속 pinned provenance refresh는 runtime lock과 일치하는 local OpenMinis read-only checkout으로
  static handoff revision check를 통과했고, arm64 packaged launcher/loader checksum·ABI·16 KiB alignment 및
  Gradle bundled-artifact verifier도 다시 통과했다. 이는 source/binary 경계의 증거를 보완하지만 physical
  guest resize·foreground `SIGWINCH`·repeat/storm·rotation·TUI·orphan acceptance를 증명하지 않는다.
- README gallery verifier는 source dimension·link·preview aspect에 더해 bounded file size, PNG chunk
  length/CRC/IEND boundary와 strict allowlist를 검사한다. `tEXt` metadata injection, corrupted CRC 및
  oversized PNG negative regression도 추가했다. 이는 EXIF/text/time metadata와 과대 payload의 저장소 혼입을
  fail-closed로 막지만 visible pixel의 공개 가능 판단은 human review로 남긴다. 이 변경 뒤 sandbox 밖 full
  Python suite는 **115/115, no skip**으로 통과했다.
- read-only/headless ARM64 API 35 및 API 26 emulator에서 current integrated fake-Provider instrumentation을
  각각 **10/10**, failure/error/skip 없이 통과했다. API 26에서는 별도 Provider Activity가 Compose root를
  등록하기 전 발생한 test synchronization race를 재현·보완했다. 두 AVD의 test/product package를 제거하고
  종료했으며, ARM64 결과로 x86_64 emulator gate를 해제하지 않았다.
- x86_64 gate의 generated report는 schema `3`부터 ADB serial을 보관하지 않으며 fake ADB regression이
  fixture serial 미포함을 확인한다. 승인 뒤 Android 35 Google APIs x86_64 image와 일회용 AVD를 만들었지만,
  aarch64 host의 Android QEMU2 emulator가 x86_64 guest CPU architecture를 지원하지 않아 headless boot가
  즉시 거부되었다. 연결된 x86_64 emulator가 없으므로 gate 결과는 계속 `SKIP_NO_X86_64_EMULATOR`다.
  AVD는 삭제했으며 Intel/AMD x86_64 host 또는 연결된 x86_64 emulator가 있어야 lifecycle E2E를 실행할 수
  있다. x86 pack ABI/static 검증이나 ARM64 emulator PASS로 이를 대체하지 않는다.
- 마지막으로 문서화된 GitHub Actions 성공은 과거 baseline만 직접 증명한다. 현재 worktree는
  commit·Push·해당 SHA workflow success 증거가 없으므로 `github_remote_ci`를
  `GITHUB_CURRENT_HEAD_CI_NOT_VERIFIED` / `BLOCKED`로 정정했다.
- Provider 공식 정책 재검토에서 built-in real-time inference adapter의 idempotency 계약은 계속
  `NOT_CONFIRMED`임을 유지했다. 이에 OAuth 401 refresh 뒤 동일 inference POST를 자동 replay하던
  session 경로를 제거하고, stream/non-stream no-replay와 모든 built-in adapter의
  `NEVER_AUTOMATIC` contract unit regression을 통과시켰다.
- x86_64 E2E 실행 시도의 로컬 재검증에서 Python **117/117**, BFF **39/39**, Android/Provider/Runtime
  unit·host/UI test, integrated lint·debug APK·AndroidTest APK 생성을 다시 통과했다. image 설치와 AVD
  생성은 완료했지만 host architecture 제한으로 x86 E2E는 `SKIP_NO_X86_64_EMULATOR` / `BLOCKED` 그대로이며,
  ARM64 결과나 정적 artifact 검증으로 대체하지 않는다.
- 이 재검증은 외부 Provider 계정, Play track, 원격 CI, x86_64 emulator 및 destructive lifecycle 승인을
  대신하지 않으며 해당 gate는 그대로 `BLOCKED`/`NOT_RUN`이다.

## GOAL Phase별 증거

| Phase | 현재 상태 | 직접 증거 | 남은 요구/판정 |
|---|---|---|---|
| 1. PRoot dynamic resize | **PARTIAL, production fail-closed** | Samsung native `forkpty` direct shell은 physical size·kernel `SIGWINCH`·post-resize input을 통과했고 unsafe input/child lifecycle도 검증했다. 같은 topology의 unpatched PRoot Probe는 guest initial/dynamic `stty`, repeat/storm, input, tracked-close를 통과했으나 guest foreground shell `SIGWINCH` trap은 미수신이었다. pinned PRoot static handoff audit은 generic signal→`ptrace` restart와 stock terminal hook 부재, tracer가 `SIGWINCH`를 별도 처리하지 않고 default-ignore 범위에 둔다는 사실을 확인했다. production은 `INITIAL_SIZE_ONLY`/`TERMINAL_RESIZE_UNSUPPORTED`이며 Probe artifact는 product APK에서 차단된다. | guest signal 미수신 때문에 physical PRoot resize acceptance가 성립하지 않는다. static tracer policy는 exact root cause를 단정하지 않는다. rotation, alternate-screen TUI, orphan cleanup의 full matrix도 `NOT_RUN`이다. stock source에 안전하게 승격할 local hook이 없으므로 upstream/maintained terminal architecture가 필요하며 `GUEST_SIGWINCH_REPEAT_STRESS_UNSUPPORTED`를 유지한다. acceptance matrix 전체 전 `DYNAMIC` 승격 금지. |
| 2. Terminal/session UI | 구현·자동 회귀 PASS | bounded ANSI screen, sessions, exit summary, Ctrl/Stop/kill, Samsung Runtime Compose 10/10 (IME·Enter/Tab/Esc/Ctrl+C Compose key regression), terminal-close event와 Samsung background module 1/1(FGS·test-only permission notification 제거) | 외부 키보드 hardware/한글 IME 실사용, vi/nano/top full TUI, 통합 제품의 사용자 permission UX 및 OEM lifecycle은 `NOT_RUN` |
| 3. Workspace/SAF/share | 구현·자동 Samsung 회귀 PASS | atomic/bounded workspace, SAF test provider 6/6, export size cap이 destination open/write 전 거부되는 회귀, FileProvider share boundary | DocumentsUI import→edit→terminal→export/share 수동 흐름 `NOT_RUN` |
| 4. Package/dev tools/Gateway | 안전한 UI·unit workflow 구현 | allowlist·approval 뒤 fixed `apk --simulate` failure/timeout/transport error이면 mutation을 dispatch하지 않는 API/Host/Compose regression, byte-total overflow를 exact capacity로 표시하지 않는 catalog/UI regression, fixed profile/Gateway bounded recovery | live repository index freshness/network/dependency total/disk-full/cancel 및 Gateway crash/runtime restart Samsung matrix `NOT_RUN` |
| 5. Python Gateway lifecycle | lifecycle/recovery code·fake regression PASS | health/bounded recovery/explicit Stop/no replay unit regression. Stop 또는 owner swap은 recovery lease를 즉시 revoke하고 in-flight restart도 owner를 Stop으로 정리한다. | real Gateway crash/stale PID-port Samsung matrix `NOT_RUN` |
| 6. Provider 정책 | local hardening/official-link docs PASS | strict input, size limits, redacted stream errors, no copied consumer/CLI contract scanner, 401 refresh 뒤 no-replay, built-in adapter `NEVER_AUTOMATIC` regression | 실계정 OAuth/API/models/idempotency/429·5xx fault proxy `NOT_RUN` |
| 7. UI/accessibility/device | Samsung·tablet·ARM64 API 26/35 automated core PASS | Samsung·tablet integrated 10/10, Samsung Runtime Compose 10/10·tablet Runtime Compose 8/8, ARM64 API 26/35 integrated fake-Provider 10/10씩, fake 10-turn Chat regression, compact/IME/accessibility semantics test, Alpine fallback viewport recovery | landscape, TalkBack gesture/voice, contrast, physical keyboard, foldable 및 full-screen TUI manual QA는 `NOT_RUN`; x86_64 emulator E2E는 별도 `BLOCKED` |
| 8. release/security | local checks PASS, public release NO-GO | scanner, internal compliance verifier, SBOM/notice checks, readiness report | license, rootfs exact source, Play, provider owner, delivery owner, Doze/reboot, x86_64 E2E are BLOCKED |

## Samsung evidence

- device: `SM-S931N`, serial은 별도 report에 저장하지 않음.
- automated evidence: Android OAuth core 3/3, Provider 12/12, Workspace 6/6,
  Runtime Compose 10/10, integrated app 10/10 PASS. Runtime Compose에는 IME/Enter와 Tab·Esc·Ctrl+C
  external key event regression이 포함된다. Provider test APK target SDK를 명시해 Samsung의
  legacy compatibility dialog가 Compose test tree를 가리던 환경 회귀도 제거했다.
- 2026-08-09 재실행에서도 OAuth core 3/3, Provider 12/12, integrated app 10/10이
  failure/error/skip 없이 통과했다. 계측 APK 정리 뒤 current integrated debug APK를 다시
  설치·cold start했으며 최종 package 점검에는 integrated product만 남겼다.
  이후 Samsung에서 Provider UI 9개가 empty Compose rule과 `ActivityScenario` test host의 root 등록 경쟁으로
  일시 실패한 것을 test-only synchronous host launch, `RESUMED`/explicit-root 대기, teardown으로 보강하고
  재실행했다. 최신 matrix는 OAuth core **3/3**, Provider **12/12**, integrated app **10/10** 모두
  failure/error/skip 없이 PASS이며 product-only cold start도 다시 확인했다. 이는 fake/credential-free
  instrumentation evidence이고 실제 Provider OAuth/API 실행을 의미하지 않는다.
  DreamActivity가 화면을 가리면 Compose hierarchy가 표시되지 않아 false failure가 날 수 있으므로,
  계측 시작 전에 화면만 깨우고 이전 Activity 종료를 기다린다. Keyguard 우회·보안 설정 변경은 하지 않았고
  제품 코드·기기 정책도 변경하지 않았다.
- latest base terminal Probe: initial `stty size=28 96`, terminal close safe event,
  process lifecycle `STARTED:3/STOPPED:3`, restart/repair after `READY` and healthy. Separate relay16
  diagnostic proves one initial shell trap only. resize 직후 fixed command를 write해도 interactive input이
  재개되지 않았고 repeat/storm matrix도 실패했으므로 product evidence가 아니다.
- final cleanup: test Probe/Demo/Sample 없음, `dev.alpine.integrated/.IntegratedMainActivity` cold start.

## Tablet evidence

- Android 12 tablet에서 Runtime Compose instrumentation 8/8과 integrated app fake-Provider instrumentation
  10/10이 통과했다. integrated 범위는 login/model/stream/Stop/retry/history/fallback과 200% font guide
  reachability를 다루며, Runtime Compose는 Enter·Tab·Esc·Ctrl+C Compose key event와 package review,
  workspace import/export/share action을 실제 scroll container에서 검증했다.
- 첫 실행은 component만 배치한 test fixture가 viewport 밖 action의 click을 보장하지 못해 2건 실패했다.
  product `RuntimeWorkspaceScreen`의 scroll 구조와 동일한 test wrapper 및 `performScrollTo()`로 보정한
  뒤 재실행에서 8/8이 통과했다. production UI contract나 Provider/terminal data 경계는 변경하지 않았다.
- physical keyboard hardware, TalkBack gesture/voice, contrast, landscape, foldable 및 full-screen TUI의 수동
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
