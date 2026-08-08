# implement_20260808_131321_runtime-probe.md

작성 일시: `2026-08-08 13:13:21 KST`

## 목적

개발 전용 Runtime Probe를 Samsung에서 일시 실행해 실제 bundled arm64 PRoot의 install/start/terminal initial size, fail-closed resize, terminal close exit event, restart/repair를 다시 확인한다. 종료 뒤 Probe를 제거하고 `dev.alpine.integrated`만 복구한다.

## 안전 경계

- Probe는 fixed `printf`, `/etc/alpine-release`, `uname`, `id`, workspace fixture만 실행한다. Provider·OAuth·사용자 prompt·SSH key는 사용하지 않는다.
- result에는 safe lifecycle state, numeric exit code, 고정 fixture 결과만 기록한다. token, provider body, credential, 사용자 파일은 기록하지 않는다.
- dynamic resize 성공을 주장하지 않는다. `INITIAL_SIZE_ONLY`과 `TERMINAL_RESIZE_UNSUPPORTED`가 기대 결과다.
- reboot/Doze/battery restriction은 실행하지 않는다.

## 작업

- [x] terminal close event의 numeric exit code를 Probe result에 안전하게 포함한다.
- [x] Probe debug APK를 Samsung에 일시 설치하고 auto-run 결과를 확인한다.
- [x] Probe를 제거하고 integrated-app APK cold start를 복구한다.
- [x] 결과를 Phase/검증 문서에 `PASS`와 `BLOCKED` 경계로 반영한다.

## 완료 조건

- [x] actual arm64 PRoot terminal이 initial `stty size=28 96`, resize channel `unsupported`, resize error `TERMINAL_RESIZE_UNSUPPORTED`를 보인다.
- [x] terminal close event에 safe numeric exit code가 있고 process STARTED/STOPPED가 균형을 이룬다.
- [x] restart/repair가 통과하고, final Samsung에는 repository Probe/Demo/Sample이 남지 않는다.

## 결과 (2026-08-08 KST)

- Samsung arm64 실제 Probe가 `success=true`, `READY`, `healthy=true`, 고정 command exit code `0`을 반환했다.
- native PTY 초기 크기는 `28 × 96`이고, 동적 요청은 의도대로 `INITIAL_SIZE_ONLY` 및
  `TERMINAL_RESIZE_UNSUPPORTED`로 거부됐다. 이는 dynamic resize 해결의 성공 증거가 아니라 false-success 방지 계약 검증이다.
- terminal close event의 safe numeric exit code가 관찰됐고, Host process lifecycle은 `STARTED:3`,
  `STOPPED:3`으로 균형을 이뤘다. PID·guest 출력·사용자 데이터는 결과 문서에 기록하지 않았다.
- Probe를 다시 제거한 뒤 `dev.alpine.integrated`만 재설치·cold start했고 `IntegratedMainActivity`가 foreground인 것을 확인했다.
