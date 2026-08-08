# 2026-08-08 로컬·실기기 검증 기록

## 범위

- 변경 범위: bounded Gateway 자동 복구, workspace line diff, SAF `content://` import/export·cache share publish 경계, allowlist 기반 package delete/update UI, package metadata snapshot/approval 경계, 10턴 fake Provider 회귀, actual arm64 PRoot terminal close lifecycle evidence
- 기준 앱: `:integrated-app` debug
- 제외: 실제 Provider 계정 OAuth/API, Gateway process crash, reboot·Doze, x86_64 emulator, Play test track

## 통과한 자동 검증

| 구분 | 결과 | 근거 |
|---|---|---|
| Python Gateway·Android boundary | PASS | `python3.11 -m unittest discover -s tests -v` — 106 passed, no skip |
| MobileAgent BFF | PASS | `backend/mobile_agent_bff/.venv/bin/pytest -q` — 39/39 |
| Android unit·lint·APK | PASS | Runtime API public-surface dump, 관련 module unit test, `:integrated-app:lintDebug`, `assembleDebug`, `assembleDebugAndroidTest` |
| ANSI terminal renderer·exit summary | PASS | SGR/OSC redaction, cursor, erase, alternate screen, CJK width, dimension cap, raw output 없는 terminal close exit summary unit regression |
| Developer tool smoke workflow | PASS | fixed Python/Git/SSH/Node argv validation, shell/environment rejection, Host dispatch/result-redaction regression |
| Package metadata snapshot | PASS | `RuntimePackageCatalog` unknown/duplicate/invalid-source regression + allowlist별 license/download/installed payload snapshot UI |
| Workspace SAF·share boundary | PASS | Samsung `:alpine-workspace-android:connectedDebugAndroidTest` — 5/5: `content://` import/export, filename sanitize, size cap, provider I/O redaction, app-private atomic share publish |
| Android OAuth·Provider instrumentation | PASS | Samsung `:android:connectedDebugAndroidTest` 3/3 + `:alpine-chat-provider-android:connectedDebugAndroidTest` 12/12 |
| Integrated OAuth/app boundary | PASS | `verify-mobile-oauth-release.py --integrated-product` 및 scanner regression 7/7 — consumer/CLI fingerprint, probable key/private key, demo/probe/sample package 차단 |
| Runtime Compose instrumentation | PASS | Samsung `:alpine-runtime-ui-compose:connectedDebugAndroidTest` — 8/8, Korean IME·Enter/Tab/Esc/Ctrl+C Compose key regression·terminal semantics·exit accessibility·confirmed SIGTERM/SIGKILL·fixed Git smoke·package/workspace boundary |
| Android 12 tablet Runtime Compose | PASS | `:alpine-runtime-ui-compose:connectedDebugAndroidTest` — 8/8, scroll container에서 package review 및 workspace import/export/share action과 terminal key regression 통과 |
| Runtime Probe actual terminal lifecycle | PASS | Samsung arm64 actual PRoot: initial `stty size=28 96`, `INITIAL_SIZE_ONLY`, `TERMINAL_RESIZE_UNSUPPORTED`, safe terminal exit event, Host process `STARTED:3`/`STOPPED:3`, restart/repair `READY`·healthy |
| Probe-only winsize root-cause diagnostic | PASS (diagnostic only) | Samsung: same slave PTY trace + independent helper + BusyBox `stty size=40 120` after removing stale `COLUMNS`/`LINES`; tracee-fork `SIGWINCH=SIG_DFL` and recorder self-test PASS, but `TIOCSWINSZ` creates no guest signal-stop; production capability is unchanged |
| Probe-only SIGWINCH relay repeat/storm | **FAIL (diagnostic evidence)** | relay16 initial shell trap/primary-tracee ptrace relay is PASS. relay21은 active same-PTY tracee physical foreground와 host-only PTY `SIGWINCH → 이후 input` PASS를 추가 확인했지만, PRoot host-master resize 뒤 guest trap/stop-restart는 `0/0`이고 fixed marker·helper·follow-up input은 미응답이었다. relay24 private-memfd control도 Samsung에서 store/fd-ready까지만 확인되고 guest read/apply와 input 재개는 실패했다. 같은 private socket request를 validate/ack만 하는 no-write control도 input 미응답이어서, memfd write/read나 guest signal만을 원인으로 좁힐 수 없다. repeat/storm도 제품 승격 근거가 아니다. production stays `INITIAL_SIZE_ONLY` |
| Foreground-service process lease | PASS (local) | background module 4/4: first start/last stop, nested terminal·command, duplicate close, FGS start rejection host-policy callback |
| UI/release/static | PASS | `verify-ui-design-contract.py`, readiness evidence check, `git diff --check` |
| OSS/compliance verifier | PASS (internal-only) | native boundary 검사 통과; project license·rootfs corresponding source mirror는 계속 `BLOCKED` |
| Samsung 통합 instrumentation | PASS | `:integrated-app:connectedDebugAndroidTest` — workspace action forwarding·package snapshot 반영본에서 10/10 |
| PRoot unpatched source-to-binary | PASS | arm64/x86_64 pinned OpenMinis source 재빌드, ELF 16 KiB alignment, packaged payload/lock/SPDX/source-offer 및 OSS native source bundle 일치 |
| 2026-08-09 Runtime Compose 재실행 | PASS | Runtime Compose 8/8 — Tab key-down이 Compose 기본 focus 이동으로 소비되던 결함을 수정하고 Enter/Tab/Esc/Ctrl+C key regression을 추가했다. test APK 제거 뒤 integrated product cold start를 확인했다. |
| 2026-08-09 tablet Runtime Compose 재실행 | PASS | 첫 실행에서 standalone panel fixture가 viewport 밖 action을 실제 click하지 못한 것을 재현했다. `RuntimeWorkspaceScreen`과 같은 scroll container + `performScrollTo()`로 fixture를 보정한 뒤 8/8 통과했고 test APK를 제거했다. |
| 2026-08-09 current integrated APK install | PASS | Samsung에 최신 debug APK replace install 후 cold start 성공. credential-free first-run mode guide 시각 점검과 product-only package 검사를 수행했고 Demo/Probe/test package는 없었다. |
| 2026-08-09 mandatory Samsung instrumentation rerun | PASS | OAuth core 3/3, Provider 12/12, integrated fast chat 10/10이 모두 failure/error/skip 없이 통과했다. 계측 뒤 latest integrated debug APK를 재설치·cold start했고 최종 package는 통합 제품만 확인했다. |

## Samsung smoke

- Android 16 arm64 Samsung 기기에서 최신 debug APK를 재설치하고 `IntegratedMainActivity` cold start를 확인했다. package/workspace/terminal-exit UI 반영본에서 workspace 5/5, Runtime Compose 7/7와 integrated 10/10을 실행한 뒤, 계측 도구가 제거한 product APK를 다시 설치했다. 최종 설치 package는 `dev.alpine.integrated` 하나이고 `topResumedActivity`는 `IntegratedMainActivity`다.
- 개발 전용 Runtime Probe를 일시 설치해 actual bundled arm64 PRoot terminal을 재검증했다. initial `stty size=28 96`와 explicit unsupported resize 계약, safe terminal close exit event, Host process lifecycle `STARTED:3`/`STOPPED:3`, restart/repair 뒤 `READY`·healthy를 확인했다. 동적 resize 해결, SIGWINCH·rotation·storm·TUI 검증은 여전히 별도 `BLOCKED` 범위다.
- native controlling-terminal launcher와 Host-private PRoot window-state 실험도 별도 Probe에서 실행했다.
  `120×40`, `80×24` 요청 뒤 guest `stty`는 여전히 `28 96`, shell `WINCH` trap은 미수신이었다.
  실험은 product source/APK에서 제거했으며, 이후 fail-closed Probe PASS와 final integrated cold start를 다시 확인했다.
- 이후 credential-free topology Probe는 normal native PTY Guest의 tty attached=true,
  process-group=foreground=true, process-group≠session leader를 확인했다. PID·command는 result에
  저장하지 않았으며, source-level 후속은 simple `setsid` 재시도가 아니라 master/guest tty identity,
  actual foreground signal 및 PRoot `TIOCGWINSZ` syscall 증명으로 좁혔다.
- Foreground-service lease regression 반영본도 Samsung에 재설치·cold start했다. 최종 `dev.alpine.integrated`만 설치돼 있으며 실제 notification 제거/OEM lifecycle은 승인 전 `NOT_RUN`으로 유지한다.
- 최신 전체 Android run에서 OAuth core 3/3, Provider 12/12, Runtime Compose 6/6, integrated-app 10/10을 재실행했다. Samsung에는 저장소의 `dev.alpine.llm.demo`, `dev.alpine.llm.runtimeprobe`, `dev.alpine.llm.bridgeprobe`, `dev.alpine.runtime.sample` package가 없고 최종 제품 `dev.alpine.integrated`만 확인했다.
- 실패했던 `PROOT_WINSIZE_FILE` PRoot override를 shipped binary와 native source bundle에서 제거한 뒤,
  fresh unpatched arm64 Probe가 `runtime_version=...unpatched1`, `healthy=true`, initial `28×96`,
  `INITIAL_SIZE_ONLY`와 `TERMINAL_RESIZE_UNSUPPORTED`를 확인했다. 이는 dynamic resize 성공이 아니라
  false-positive resize 경로가 제품에 남지 않았음을 검증한 것이다.
- Probe-only PRoot topology diagnostic을 추가로 Samsung에서 실행했다. Host/tracee가 동일 slave PTY를
  사용하고 Host `TIOCSWINSZ`와 tracee `TIOCGWINSZ` requested-size pair가 성공함을 safe numeric evidence로
  확인했다. 독립 raw-syscall helper도 `dynamic`을 반환했고 Native PTY exec에서 stale `COLUMNS`/`LINES`를
  제거한 뒤 BusyBox `stty size`가 `40 120`을 반환했다. 따라서 winsize root cause는 확인됐지만 foreground
  shell `WINCH` trap은 여전히 미수신이었다. PRoot tracee fork 전 `SIGWINCH=SIG_DFL` 정정과 host-local recorder
  self-test 뒤에도 self-test 외 host/tracee signal event는 `0`이었다. terminal fd foreground group signal
  candidate는 일관되게 guest trap을 만들지 못해 제거했다. dynamic resize, SIGWINCH/rotation/storm/TUI는 계속
  `BLOCKED`이며 production contract는 변경하지 않았다. Probe는 제거하고 final `dev.alpine.integrated` APK cold start를
  다시 확인했다.
- 최신 Samsung 계측 실행의 첫 integrated attempt는 Dream overlay 때문에 Compose hierarchy를 찾지 못했다.
  화면을 깨운 뒤 OAuth core `3/3`, Provider `12/12`, integrated `10/10`이 모두 PASS했고,
  `dev.alpine.integrated/.IntegratedMainActivity`가 foreground이며 demo/probe/test package가 남지 않음을 확인했다.
- Provider clean-room 변경 뒤 OAuth core 3/3, Provider 12/12, Demo 35/35, integrated-app 10/10을 Samsung에서 재실행했다. Demo·test package를 제거하고 최신 `dev.alpine.integrated/.IntegratedMainActivity` cold start를 다시 확인했다.
- 첫 실행 mode guide와 `Alpine 작업` 선택 뒤 Gateway 상태 card가 `중지됨`으로 표시되고, Runtime 설치 전 Gateway 시작이 비활성화된 것을 확인했다.
- 외부 녹음/benchmark 앱이 foreground를 가로채는 1회 간섭이 있었으나 앱 crash는 없었다. 외부 앱을 종료한 clean run에서 target test 1/1과 integrated suite 10/10이 통과했다.
- 검증 종료 뒤 Samsung과 연결 태블릿에서 sample/demo/probe APK를 제거했다. 두 기기에는 `dev.alpine.integrated`만 남기고, Samsung에서는 다시 cold start한 foreground를 확인했다.
- 최신 Probe-only diagnostic 뒤에도 Samsung에서 `dev.alpine.llm.runtimeprobe`, `dev.alpine.llm.demo`,
  `dev.alpine.llm.bridgeprobe`, `dev.alpine.runtime.sample`을 다시 제거했다. `:integrated-app:assembleDebug`와
  integrated product scanner를 통과한 APK만 재설치했고, 화면 보호기 overlay를 해제한 최종 확인에서
  `dev.alpine.integrated/.IntegratedMainActivity`가 top-resumed activity였다.
- 최종 `tty-diagnostic9` Probe는 `stty size=40 120`, helper `dynamic`, same-slave ioctl trace를 다시
  확인했다. PRoot-local `SIGWINCH` recorder self-test는 `host=1`로 검증됐지만 resize가 추가 host/tracee
  signal event를 만들지 않았고 shell trap도 미수신이었다. Probe를 다시 제거한 뒤 최종 integrated APK만
  cold start했다.
- 후속 `tty-resize-relay16` Probe는 supervisor가 direct PRoot child만, PRoot가 primary tracee만
  source-level로 relay하는 범위를 Samsung에서 확인했다. 기본 Probe는 initial shell trap과 ptrace
  stop/restart를 통과했지만, pending trap 대기 순서를 제거해 resize 직후 fixed command를 write해도
  fixed `printf` acknowledgement조차 재개되지 않았다. repeat/storm run도 signal dispatch 11건과
  stop/restart 19건 뒤 repeated size marker/helper command가 미응답이었다. 이는 production 동적 resize가
  아니라 PRoot ptrace reinjection 뒤 interactive input 재개가 남았다는 failure evidence이며, final product
  capability를 변경하지 않는다.
- 최신 Samsung 자동 회귀는 화면 보호기를 깨운 직후 실행했다. OAuth core 3/3, Provider 12/12,
  Workspace 5/5, Runtime Compose 7/7, integrated 10/10이 모두 통과했다. Provider Compose test host는
  library test APK가 minSdk를 legacy target으로 사용해 Samsung platform dialog를 표시하던 문제를
  `testOptions.targetSdk=36`으로 수정했다. 이는 제품 runtime target을 바꾸지 않고 test host만
  현재 platform policy로 명시하는 변경이다.

## 보류·차단

- package live repository preflight, dependency/disk-full/network/cancel 실제 검증 (현재 UI는 2026-08-08 `APKINDEX` display-only snapshot)
- Gateway process crash/runtime restart Samsung matrix
- Android DocumentsUI를 통한 full SAF import → edit → terminal → export/share 수동 흐름 (자동 테스트는 test `content://` provider와 explicit share file publish까지만 검증)
- tablet 수동 smoke: Play Protect 데이터 전송 선택 dialog가 표시되어 사용자 선택 없이 중단
- true guest dynamic terminal resize (`SIGWINCH → fixed printf → stty` input-resume 회귀, repeat/storm,
  rotation, TUI, orphan matrix), x86_64 emulator E2E
- actual Samsung notification/FGS removal after terminal close and OEM background lifecycle (Doze/reboot/battery restriction approval 필요)
- release readiness의 외부 승인 gate

상세 release blocker는 [`distribution/release-readiness.json`](../distribution/release-readiness.json)을 기준으로 한다.
