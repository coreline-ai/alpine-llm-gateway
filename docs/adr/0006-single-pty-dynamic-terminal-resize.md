# ADR 0006: 단일 Host PTY를 guest terminal로 사용한다

상태: `실기기 검증 실패 / capability 승격 차단`

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
- Host는 동일 PTY slave control fd에 `TIOCSWINSZ`를 적용한다.
- PRoot가 물려 받은 실제 tracee fd와 Host control fd가 다른 terminal view를 보이는 Android 조합에서는,
  app-private pid file로 확인한 동일 UID tracee의 `/proc/<pid>/fd/0`을 다시 열어 같은 ioctl을 적용한다.
- Android/PRoot 조합에서 post-start winsize 조회가 Host ioctl을 반영하지 않는 경우를 위해 app-private
  FIFO로 숫자형 resize 명령을 전달한다. guest watcher가 `/dev/tty`에 `stty`를 적용하고 동일 sequence를
  ack file에 기록해야만 resize 성공으로 처리한다.
- FIFO 준비 handshake가 완료된 Native PTY session만 `DYNAMIC`을 후보로 제공하고 pipe fallback은 계속
  `INITIAL_SIZE_ONLY`를 반환한다.
- 최종 `DYNAMIC` 배포 여부는 Samsung probe에서 guest `stty`와 TUI resize가 확인된 뒤 확정한다.

## 안전 조건

- ioctl 반환값만으로 성공을 판정하지 않고 guest의 행·열 변경을 함께 검증한다.
- FIFO 이름은 session별 UUID이고 mode `0600`이며 명령은 sequence/rows/columns 숫자만 허용한다.
- 종료 후 resize, 잘못된 크기와 fallback 경로는 안정 오류를 반환한다.
- 실기기 검증에 실패하면 이 변경의 capability 승격을 되돌리고 기존 fail-closed 계약을 유지한다.

## 2026-08-02 Samsung 검증 결과

- Host control fd, shell fd 0과 watcher fd 9는 모두 `40 120`으로 변경됐다.
- PRoot patch도 `EXIT_APPLIED 40 120`을 기록했지만 guest BusyBox `stty size`는 `28 96`을 유지했다.
- 최종 probe는 `TERMINAL_UNAVAILABLE`과 `success=false`를 반환해 거짓 성공을 차단했다.
- 따라서 `DYNAMIC` 제품 지원 승격은 보류하며 release readiness는
  `GUEST_WINSIZE_NOT_PROPAGATED`로 `BLOCKED`다.
- 상세 증거는 `dev-plan/alpine-runtime-remaining-verification-20260802.md`에 기록한다.
