# ADR 0006: 단일 Host PTY를 guest terminal로 사용한다

상태: `winsize 원인 확인 / SIGWINCH source-level 후속 필요 / production INITIAL_SIZE_ONLY 유지`

## 배경

Android Host는 `/dev/ptmx`에서 만든 PTY slave를 PRoot 표준 입출력에 연결하고 있었다. 그러나 guest
wrapper가 다시 BusyBox `script`를 실행해 두 번째 PTY를 만들었다. Host의 `TIOCSWINSZ`는 바깥 PTY에
적용되고 실제 interactive shell의 `stty`는 안쪽 PTY를 읽었기 때문에, host ioctl 성공과 guest 크기가
서로 달랐다.

PRoot syscall 경로는 guest의 tty ioctl을 host fd로 전달하므로 별도 PTY를 중첩할 이유가 없다. 중첩
PTY는 resize뿐 아니라 foreground process group과 `SIGWINCH` 소유권도 분리한다.

## 결정

- Native PTY 경로는 `setsid -c → PRoot → guest shell`의 단일 PTY를 사용한다.
- guest wrapper는 최초 `stty cols/rows`만 적용하고 shell을 직접 `exec`한다.
- Native PTY launcher는 `COLUMNS`/`LINES` 환경 변수를 exec 전 제거한다. guest wrapper가 최초
  `stty cols/rows`를 적용하는 것은 유지하되, 실행 중 size truth는 tty ioctl만 사용한다.
- Host는 PTY master control fd에 최초 `TIOCSWINSZ`만 적용한다. production API는 이 값을 실행 중
  resize capability로 해석하지 않는다.
- PRoot guest의 post-start size는 현재 `INITIAL_SIZE_ONLY`로 고정하고 이후 `resize()`는
  `TERMINAL_RESIZE_UNSUPPORTED`로 거부한다.
- 이전의 host PID fd 재개방과 guest FIFO watcher는 Guest `stty`/TUI 결과를 증명하지 못했으므로 제품
  실행 경로에서 제거한다. `ALPINE_TERMINAL_RESIZE_CHANNEL=unsupported`만 guest에 명시한다.
- 장래 `DYNAMIC` 지원은 PRoot upstream/maintained source-level terminal hook 또는 PRoot 없이
  controlling terminal을 유지하는 아키텍처에서 해결한다. Host polling, `/proc/<pid>/fd` 재개방, FIFO ack는
  capability 승격 근거가 될 수 없다.

## 안전 조건

- Host ioctl, tracee 메모리 write 또는 FIFO ack만으로 성공을 판정하지 않는다.
- `DYNAMIC`은 guest `stty size`, foreground process `SIGWINCH`, 반복/rotation resize와 대표 TUI의
  실제 재배치가 모두 동일 device/ABI에서 통과할 때만 노출한다.
- 종료 뒤 resize, 잘못된 크기와 pipe fallback은 안정 오류를 반환한다.
- 실기기 검증 실패 시 public contract는 즉시 `INITIAL_SIZE_ONLY`로 유지한다.

## 2026-08-02 및 2026-08-08 Samsung 검증 결과

- Host control fd와 app-private pid로 연 동일 UID shell/watcher fd는 `40 120`으로 변경됐다.
- 배포 PRoot binary의 enter/exit `TIOCGWINSZ` patch는 `EXIT_APPLIED 40 120`과 tracee 메모리
  readback을 기록했지만 guest BusyBox `stty size`는 `28 96`을 유지했다.
- 2026-08-08 Samsung 재현에서도 `resizeSupport=DYNAMIC` 뒤 `TERMINAL_UNAVAILABLE`,
  `MISMATCH 28 96 ... STATE 40 120 ...`가 반복됐다. 따라서 FIFO ready가 public capability를
  `DYNAMIC`으로 승격하던 오류를 확인했다.
- Runtime은 해당 FIFO/pid-fd workaround를 제거하고 `INITIAL_SIZE_ONLY`/`TERMINAL_RESIZE_UNSUPPORTED`로
  fail-closed 처리한다. 당시 release readiness의 `GUEST_WINSIZE_NOT_PROPAGATED`는 `BLOCKED`였다.
- 같은 날 별도 native launcher가 `setsid()`·`TIOCSCTTY`·`tcsetpgrp()` 뒤 PRoot를 `exec`하도록 하고,
  Host-private size state와 existing PRoot hook을 함께 시험했다. Samsung에서 `96×28 → 120×40 → 80×24`
  호출 뒤에도 guest `stty size`는 계속 `28 96`이었고 shell `WINCH` trap도 실행되지 않았다. 이는
  controlling-terminal setup만으로 PRoot tracee의 foreground pgrp/signal 전달이 해결되지 않음을 뜻한다.
  실험 helper는 product source/APK에 남기지 않았다.
- credential-free topology Probe는 normal native-PTY Guest가 tty에 연결돼 있고, Guest process group이
  tty foreground group과 같으며, 그 group이 session leader 자체는 아님을 확인했다. 즉 이후 수정은
  `setsid`만 재시도하는 대신 Host master와 Guest tty의 동일성, `TIOCSWINSZ` 뒤 실제 foreground pgrp의
  signal, PRoot의 `TIOCGWINSZ` 결과를 각각 증명해야 한다. PID·command·terminal output은 report에 저장하지 않는다.
- 실패한 `PROOT_WINSIZE_FILE`/`TIOCGWINSZ` memory override는 2026-08-08에 PRoot source build,
  arm64/x86_64 packaged binary, lock, SPDX/SBOM 및 corresponding-source bundle에서 제거했다.
  새 artifact는 `3.21.3-openminis-8cf13e9-unpatched1`이며 이 정리는 dynamic resize 해결을 의미하지 않는다.
  Samsung에서 새 binary도 initial `28×96`, `INITIAL_SIZE_ONLY`,
  `TERMINAL_RESIZE_UNSUPPORTED`를 유지함을 확인했다.
- 같은 날 Probe 전용 `libproot_tty_trace.so`로 Host slave device와 tracee ioctl fd를 비교했다.
  `TIOCGWINSZ` requested-size pair는 동일 PTY에서 extension 전후 모두 `40×120`을 보고했고, 이후
  `28×96`으로 reset하는 guest `TIOCSWINSZ`도 관찰되지 않았다. 반면 Probe의 guest `stty` state marker는
  initial size를 계속 보고했다. 이 불일치는 source-level 해결의 근거가 아니므로 production binary/API는
  계속 `INITIAL_SIZE_ONLY`다. diagnostic artifact는 Probe 전용 manifest opt-in과 product APK scanner로
  분리하며 raw terminal output/PID/command를 저장하지 않는다.
- 같은 날 Probe 전용 독립 guest helper와 tracee class(`busybox`/`tty_helper`/`other`) 안전 분류를 추가했다.
  Host `TIOCSWINSZ` 뒤 helper가 실제 `40×120`을 읽었고, Native PTY launcher에서 `COLUMNS`/`LINES`를
  제거하면 BusyBox `stty size`도 `40 120`을 반환했다. 즉 기존 불일치는 PRoot가 winsize를 전달하지
  못한 것이 아니라 launch-time environment hint가 BusyBox 관측을 고정한 원인이었다. PRoot production
  patch는 필요하지 않으며 diagnostic artifact는 Probe에만 남긴다.
- 그러나 같은 Samsung Probe에서 foreground shell의 `WINCH` trap은 armed 상태였지만 수신되지 않았다.
  native `setsid`/`TIOCSCTTY`/`tcsetpgrp` launcher와 host signal dispatch 실험은 이 신호를 신뢰성 있게
  전달하지 못하거나 terminal lifecycle을 불안정하게 했으므로 product source/APK에서 제거했다. 당시 release
  readiness blocker는 `GUEST_SIGWINCH_NOT_PROPAGATED`였고, guest signal·storm/rotation·TUI·orphan matrix가
  source-level/maintained 해법으로 통과하기 전에는 production `DYNAMIC`을 노출하지 않는다.
- 마지막 Probe (`tty-diagnostic9`)는 PRoot가 tracee fork **전에** `SIGWINCH` disposition을 `SIG_DFL`로
  되돌려 Android launcher의 inherited ignore 가능성을 제거했고, PRoot-local fixed-literal self-test로 signal
  recorder가 실제 동작함을 확인했다. 그 상태에서도 Host `TIOCSWINSZ` 뒤에는 self-test 외 host signal,
  tracee stop, ptrace reinjection이 모두 `0`이었고 shell trap도 미수신이었다. 즉 Samsung Android PTY에서
  winsize는 변경되지만 자동 `SIGWINCH`가 발생하지 않는다는 증거가 추가됐다.
- 같은 terminal fd에서 `TIOCGPGRP`로 얻은 foreground group에 직접 `SIGWINCH`를 보내는 native candidate도
  Probe에서 성공/실패가 일관되지 않았고 guest trap을 증명하지 못했다. PID 재개방·blind signal dispatch와
  함께 product source/APK에서 제거했다. 이 실패는 guest event loop·TUI를 손상시키지 않는 유지보수 가능한
  session architecture 또는 upstream/maintained terminal hook이 필요함을 뜻한다.
- 후속 `tty-resize-relay16`은 **Probe 전용** source-level relay로, app-private socket의 fixed one-byte
  요청을 session supervisor가 direct PRoot child에만 전달하고 PRoot가 `launch_process()`에서 아는 최초
  tracee에만 relay하도록 제한했다. Samsung 기본 Probe에서 initial shell `WINCH` trap, primary tracee
  dispatch와 ptrace stop/restart는 확인됐다. raw output·PID·command는 기록하지 않았다.
- Probe는 pending `WINCH` trap을 기다린 뒤 command를 write하던 진단 순서를 폐기하고, resize 직후 fixed
  command를 write하도록 보정했다. 그 뒤에도 단일 resize에서 trap은 1회 수신됐으나 바로 다음 fixed
  `printf` acknowledgement, `stty` marker 및 helper follow-up이 모두 미수신이었다. 따라서 이는
  marker timeout이나 `stty` 관측 오류가 아니라, **direct primary-tracee signal을 ptrace reinjection 한 뒤
  interactive shell input command path가 재개되지 않는 현상**으로 좁혀진 source-level 후보이다.
- 동일 artifact의 반복 `120×40 ↔ 80×24`와 8회 storm에서는 supervisor/PRoot/primary-tracee relay
  input이 11건, tracee stop/restart가 19건 기록됐지만 안전 marker와 helper command가 응답하지 않았다.
  이는 **initial-shell relay proof**일 뿐 repeated terminal semantics나 TUI 호환 증거가 아니다. 다음 해법은
  direct tracee `kill`의 확장이 아니라 maintained PRoot terminal/job-control hook 또는 대체
  controlling-terminal architecture여야 하며, 먼저 `SIGWINCH → fixed printf → stty` 단일 회귀부터
  repeated/storm·rotation·foreground TUI·orphan matrix를 통과해야 한다. release readiness blocker는
  `GUEST_SIGWINCH_REPEAT_STRESS_UNSUPPORTED`로 유지하며, product contract는 계속
  `INITIAL_SIZE_ONLY`/`TERMINAL_RESIZE_UNSUPPORTED`다.
- `relay21`은 source-level `setpgid`/`tcsetpgrp` 성공만으로 topology를 추정하지 않았다. initial tracee와
  same-PTY `TIOCGWINSZ`를 실제 호출한 active guest tracee가 모두 physical tty foreground group임을 fixed
  enum stage로 확인했다. 별도 app-private host-only PTY control은 동일 기기에서
  `TIOCSWINSZ → SIGWINCH → 이후 input`을 통과했다. 그럼에도 PRoot session의 Host master resize 뒤에는
  guest trap/ptrace stop-restart가 `0/0`이고 fixed marker·helper·follow-up input이 모두 미응답이었다.
  그러므로 Android PTY 일반 기능, stale `COLUMNS`/`LINES`, guest foreground-pgrp 배치는 원인으로 승격할 수
  없으며, PRoot가 있는 host-master resize 상호작용이 남은 source-level blocker다. current Probe artifact는
  product binary/API에 포함하지 않고 `GUEST_SIGWINCH_REPEAT_STRESS_UNSUPPORTED`를 유지한다.

### Relay24 virtual memfd negative result (2026-08-09)

- Probe-only `relay24`는 host-master `TIOCSWINSZ`와 모든 post-launch signal을 제거했다. bounded private
  supervisor request가 direct PRoot에만 상속된 4-byte memfd를 갱신하고, PRoot가 successful guest
  `TIOCGWINSZ` exit에서만 그 결과를 대체하도록 했다.
- Samsung single run에서 supervisor store와 PRoot fd-ready는 확인됐지만 guest read/apply는 발생하지 않았고
  dynamic marker, helper 및 follow-up input도 재개되지 않았다. 같은 request를 validate/ack만 하고 memfd
  write를 생략하는 Probe-only no-write control에서도 fixed control stage `1`과 guest stop/restart `0/0` 뒤
  input은 동일하게 미응답이었다.
- 따라서 이 증거는 memfd write/read나 `SIGWINCH`만을 원인으로 확정하지 않는다. private resize-request
  transaction과 session-supervisor/PRoot interaction까지 남은 blocker로 보며, product API는 계속
  `INITIAL_SIZE_ONLY`/`TERMINAL_RESIZE_UNSUPPORTED`다.
- 이어진 no-request control은 public `resize()`가 성공으로 반환하되 native socket/memfd request를 전혀
  수행하지 않았고, skip-resize control은 `resize()` 호출까지 생략했다. 두 control도 same session topology에서
  dynamic marker·helper·follow-up input이 미응답이었다. 즉 해당 session의 input lifecycle은 resize request
  이전부터 불안정하며, `SIGWINCH`, memfd write 또는 private socket을 단독 root cause로 취급할 수 없다.
- primary tracee를 foreground group으로 옮기지 않는 control은 Samsung에서 bounded Probe result를 쓰기 전에
  hang했다. 이 결과는 direct tracee foreground assignment를 제거하는 것만으로는 terminal architecture를
  복구하지 못함을 뜻한다. supervisor/PRoot job-control topology 전체가 blocker이며, product는 계속
  `INITIAL_SIZE_ONLY`를 유지한다.
- 상세 증거는 `dev-plan/alpine-runtime-remaining-verification-20260802.md`와
  `dev-plan/implement_20260808_113133.md`, `dev-plan/implement_20260808_141500.md`,
  `dev-plan/implement_20260808_203838.md`, `dev-plan/implement_20260808_223000.md`에 기록한다.
