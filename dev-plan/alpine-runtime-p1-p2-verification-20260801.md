# Alpine Runtime P1·P2 검증 기록 — 2026-08-01

## 범위와 결론

- 계획: `dev-plan/implement_20260801_213246.md`
- P1 background, x86_64 공급망, Provider E2E gate와 P2 Play Asset Delivery, workspace,
  Gradle 9 readiness, publication 통합을 구현했다.
- 실제 동적 terminal resize는 Host fd, watcher fd와 PRoot patch까지 `40x120`을 전달했지만 guest
  `stty`를 바꾸지 못했다. 최종 probe는 `TERMINAL_UNAVAILABLE`/`success=false`로 제품 지원 승격을
  차단한다.
- 실제 Provider 계정과 x86_64 emulator E2E는 외부 조건이 없어 성공으로 기록하지 않았다.

## 구현 결과

| 영역 | 결과 | 핵심 증거 |
|---|---|---|
| Background | 완료 | 선택형 FGS/notification/lease/WorkManager 모듈, `START_NOT_STICKY`, 민감 정보 비저장 |
| Play Asset Delivery | 완료 | fetch 상태·timeout adapter, rootfs/aux만 asset pack, native는 base APK JNI |
| Workspace | 완료 | 안전 상대경로, quota, bounded I/O, symlink 차단, atomic write |
| x86_64 공급망 | 구현 완료·E2E 대기 | 공식 Alpine rootfs SHA-256, PRoot/loader/PTY, SBOM, ELF machine/16 KiB gate |
| Provider E2E | gate 완료·실계정 미실행 | redacted report validator가 token/secret/raw payload를 거부 |
| Gradle 9 | 준비도 gate 통과 | Kotlin 2.2.21, public ABI 보존, 전체 경고 0건 |
| Publication | 완료 | 17개 AAR/JAR와 8개 source-independent release/R8/lint 소비자 조합 |

## 자동 검증

### Python

```text
python3.11 -m unittest discover -s tests -v
Ran 88 tests — OK
```

- module boundary, x86 lock checksum, Provider report redaction, response/SSE limit,
  retry/circuit breaker와 기존 Gateway 회귀가 통과했다.

### Android 신규 모듈·통합 앱

```text
:alpine-runtime-android:testDebugUnitTest/lintDebug
:alpine-runtime-background-android:testDebugUnitTest/lintDebug
:alpine-runtime-artifact-play:testDebugUnitTest/lintDebug
:alpine-runtime-pack-x86_64:testDebugUnitTest/lintDebug/verifyX8664RuntimeArtifacts
:alpine-workspace-api:check
:alpine-workspace-android:testDebugUnitTest/lintDebug
:integrated-app:assembleDebug/lintDebug
:alpine-runtime-probe:assembleDebug/lintDebug
BUILD SUCCESSFUL — 전체 로컬 release gate 1,391 tasks 포함
```

### Publication과 외부 소비자

- `scripts/verify-sdk-publication.py`: **17개 publication 통과**
- `scripts/verify-published-consumer.py`: **8개 release variant 통과**
- arm64/x86_64 rootfs·PRoot·loader·PTY owner, 16 KiB alignment, FGS special-use,
  Play data-sync와 notification permission 격리를 검사했다.
- 내부 bundle: `dist/alpine-sdk-0.3.0`, artifact count 17, consumer matrix count 8.

### x86_64

> 2026-08-08 superseded: 아래 PRoot patch/checksum은 당시 `PROOT_WINSIZE_FILE` experiment의
> historical evidence다. 현 제품 pack은 unpatched PRoot build를 사용하며 최신 상태는
> `implement_20260808_203838.md`와 runtime lock을 기준으로 한다.

- Alpine `3.21.3` x86_64 minirootfs SHA-256:
  `1a694899e406ce55d32334c47ac0b2efb6c06d7e878102d1840892ad44cd5239`
- PRoot ELF machine `62`, `PT_LOAD` 4개 모두 `16384`.
- loader ELF machine `62`, `PT_LOAD` 3개 모두 `16384`.
- PRoot SHA-256:
  `9c0bf771ba92151514338643b03fce271d80543c30ae6395c472f313a5d98868`
- loader SHA-256:
  `4ca6f14810548610501d012144abeb4c27c1530e2e37201cabf30cab2c39a585`
- SBOM SHA-256:
  `8115d8bf5e5675e74ed4de6dd1adc29f56cc07967d6fe6dc1b17cc131d183e62`
- arm64와 x86_64 모두 PRoot patch SHA-256
  `20726d1ccf9bb8c952a6039d5158168dad58ec62bcf7cbf73bc3170b8c4a9a27`을 사용한다.
- 연결된 x86_64 emulator가 없어
  `build/reports/x86_64-emulator-gate.json`은 `SKIP_NO_X86_64_EMULATOR`다.
- 따라서 x86_64 pack은 `experimental_requires_emulator_e2e`이며 지원 ABI로 광고하지 않는다.

## Samsung SM-S931N 검증

- 기기: Samsung `SM-S931N`, Android 16/API 36, `arm64-v8a`, serial `<redacted>`.
- 초기 universal runtime probe 결과는 `success=true`, `healthy=true`, elapsed `2806 ms`였다.
- native PRoot/loader 실행, Alpine 3.21.3, aarch64, workspace, restart/repair가 통과했다.
- 2026-08-02 최종 production probe 재실행은 `healthy=true`, install/exec/restart/repair 통과,
  elapsed `4441 ms`였다.
- 최종 resize 진단은 Host/PRoot `40x120`, guest `stty=28x96`,
  `TERMINAL_UNAVAILABLE`, `success=false`였다. 따라서 동적 resize 지원은 계속 차단한다.
- 통합 앱 cold start `Status: ok`, 알림 권한 prompt 표시와 사용자 허용 후 권한 부여를 확인했다.
- terminal process 시작 시 `RuntimeForegroundService`가 `isForeground=true`,
  `foregroundId=41246`, `types=0x40000000(specialUse)`, action 1개로 등록됐다.
- start 허용 근거는 Host UID `TOP`, `START_NOT_STICKY` 결과와 `stopIfKilled=true`를 확인했다.
- terminal을 닫자 마지막 process와 FGS가 제거됐고, runtime 종료 후 active service가 남지 않았다.

## 남은 외부·기술 gate

1. **동적 resize:** 현재 PRoot가 Android PTY master resize를 guest controlling terminal에 반영하지
   않는다. PRoot source-level 전달 설계 또는 terminal architecture 변경 후 별도 재검증한다.
2. **x86_64 E2E:** x86_64 API 35+ emulator에서 install/start/exec/terminal/stop probe가 통과해야
   experimental 표기를 제거할 수 있다.
3. **실제 Provider:** 계정·비용·정책 승인 후 runbook에 따라 실행하고 redacted report에만 기록한다.
4. **Play 전체 E2E:** signed AAB와 Play Console test track이 필요하다.
5. **Gradle 9:** Kotlin plugin 2.2.21과 typed compiler options로 전환했고
   `JvmDefaultMode.DISABLE`로 기존 public ABI를 보존했다. readiness 경고는 0건이며
   `gradle9_ready=true`다. wrapper는 공식 AGP 8.10 기준 Gradle 8.11.1을 유지한다.
6. **운영 복구:** 실제 reboot/Doze/강제 process kill matrix는 사용자 승인된 테스트 창에서 수행한다.
7. **공개 배포:** 프로젝트 전체 license와 complete corresponding source가 없어 계속 차단한다.

후속 상세 검증은 `dev-plan/alpine-runtime-remaining-verification-20260802.md`를 참조한다.
