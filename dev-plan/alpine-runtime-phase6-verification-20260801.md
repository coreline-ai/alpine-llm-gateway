# Alpine Runtime Phase 6 검증 기록

- 검증일: `2026-08-01 KST`
- 대상: 재사용 Host controller, 선택형 Compose UI, native PTY, package 승인, XML sample, 통합 앱 shell
- 실기기: Samsung `SM-S931N`, Android 16/API 36, `arm64-v8a`, serial `<redacted>`

## 구현 결과

| 영역 | 결과 |
|---|---|
| Host 경계 | `:alpine-runtime-host`에 UI와 Android component에 독립적인 lifecycle·health·command·terminal·package controller 구현 |
| 실제 터미널 | `/dev/ptmx` native PTY, controlling terminal, 최초 창 크기, bounded replay, Ctrl+C/Ctrl+D, process cleanup 구현 |
| Compose UI | 설치·상태·health·repair/reset·terminal·package 승인 화면을 `:alpine-runtime-ui-compose`에 구현 |
| 자체 UI | `:alpine-integration-sample`이 Compose 없이 XML/View와 공개 SDK만 사용해 install/start/exec/terminal/stop 구현 |
| 통합 shell | `:integrated-app`이 빠른 채팅/Alpine 작업 모드를 명시적으로 선택·저장하고 Alpine workspace UI를 조립 |
| 패키지 보안 | 빈 allowlist fail-closed, 검증된 패키지명, 명시적 사용자 승인, 고정 `/sbin/apk add --no-progress` 명령 적용 |
| 수명주기 | Application 단일 owner로 회전 중 session을 유지하고, background/foreground와 process death에서 실제 저장 상태를 재동기화 |
| 문서 | Host lifecycle/service/notification/storage/manifest/ProGuard/terminal/package 통합 가이드와 ADR 추가 |

## 자동 검증

- Python/architecture: `73`개 실행, `OK`, 로컬 socket bind가 금지된 Gateway 4개만 sandbox 사유로 skip.
- Android CI 범위: unit test, public API dump, release AAR, debug AndroidTest APK, lint, sample/demo/probe/integrated APK와 runtime/gateway asset lock을 포함한 `1031`개 task가 `BUILD SUCCESSFUL`.
- Compose 실기기 instrumentation: 접근성 state/content description, 200% font, 한글 IME Send와 외부 Enter의 `2`개 test가 Samsung 기기에서 통과.
- Host unit test: SDK/자체 UI lifecycle parity, terminal 256 KiB bound/reset, ANSI/OSC presentation sanitizing 통과.
- Architecture test: XML sample의 Compose/demo 무의존, integrated shell의 demo 무의존, package fail-closed/UI-neutral 경계 통과.

## Samsung 실기기 검증

- runtime probe가 Alpine `3.21.3-openminis-8cf13e9` install/start/health/command/restart/repair/stop과 process cleanup을 통과했다.
- terminal은 `native-pty`, 최초 크기 `28x96`, guest 출력 `terminal=ok`, `terminal_size=28 96`을 확인했다.
- XML sample에서 Alpine command와 터미널 명령 `terminal_ui_ok`를 실행하고 background→foreground에서 `RUNNING`, force-stop 후 새 process에서 `READY` 복구를 확인했다.
- 통합 앱에서 `빠른 채팅`/`Alpine 작업` selector, Alpine Runtime/Linux 터미널 화면, 선택 mode의 process 재시작 후 보존을 확인했다.
- 최종 APK를 `adb install -r`로 업데이트해 다른 OAuth/demo 앱 데이터는 변경하지 않았고 `dev.alpine.integrated/.IntegratedMainActivity` cold start가 성공했다.

## 발견 이슈와 처리

1. BusyBox shell의 pipe만으로는 실제 PTY semantics를 제공할 수 없어 source-built native PTY adapter를 추가했다.
2. 신규 PTY shared library는 Android 16 16 KiB page 요구에 맞게 ELF `LOAD Align 0x4000`으로 생성했다.
3. XML sample의 API 27 전용 theme 속성이 minSdk 26 lint를 실패시켜 `values-v27` resource로 분리했다.
4. lifecycle 변경 시 이전 health 문구가 남지 않게 상태를 초기화하고 terminal control sequence를 UI 표시 전에 제거했다.

## Phase 7 후속 확인

- Phase 7에서 PRoot, loader와 native PTY의 ELF `PT_LOAD` alignment가 모두 `0x4000`임을 확인하고 build/publication 자동 gate로 고정했다. 이 항목은 닫혔다.
- 고정 PRoot가 열린 terminal의 후속 `TIOCSWINSZ`를 guest에 전달하지 않는 것을 실기기에서 재현했다. 거짓 best-effort 성공 대신 `INITIAL_SIZE_ONLY` capability를 공개하고 후속 `resize()`는 `TERMINAL_RESIZE_UNSUPPORTED`로 거부한다.
- 빠른 채팅 shell은 reusable backend를 조립하지만 제품별 OAuth 계정 화면과 실제 Provider credential은 Host 앱이 주입해야 한다. 실제 계정 E2E는 별도 운영 검증이다.
- 기기 재부팅 자동 복구, foreground service/notification 정책과 장기 background 작업은 publication matrix 및 장기 workspace 단계의 후속 범위다.

## 다음 단계

Phase 7 내부 publication과 전체 조합 matrix는 완료됐다. 다음 개발은 장기 계획의 workspace 기능 또는 공개 배포 외부 gate를 선택해 진행한다.
