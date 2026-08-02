# Alpine Runtime 잔여 항목 구현·검증 기록 — 2026-08-02

## 결론

- 로컬에서 구현 가능한 잔여 gate, 보고서 validator, 공급망 동기화, Gradle 9 준비도 개선과
  내부 SDK release bundle 생성을 완료했다.
- Samsung `SM-S931N`에서 최종 production probe를 다시 실행했다. Alpine 설치·명령·workspace·
  restart·repair·health는 정상이나 PRoot guest의 동적 terminal resize는 반영되지 않았다.
- x86_64 pack은 arm64와 동일한 PRoot patch provenance로 동기화했지만 연결된 x86_64 emulator가
  없어 계속 experimental 상태다.
- Provider 실계정, Play test track, reboot/Doze/process-kill, GitHub 원격 CI, 라이선스와 대응 소스는
  외부 승인·환경이 없어 `BLOCKED` 또는 `NOT_RUN`으로 유지한다.
- 공개 배포는 **No-Go**다. 내부 개발용 SDK bundle만 생성했다.

## 1. Samsung terminal resize

검증 기기:

- Samsung `SM-S931N`
- Android 16 / API 36
- ABI `arm64-v8a`
- serial `<redacted>`

추적 결과:

1. Host PTY control fd와 PRoot shell fd에는 `TIOCSWINSZ(40, 120)`가 반영됐다.
2. guest watcher가 사용하는 fd 9도 Host에서 `40 120`으로 확인됐다.
3. PRoot patch는 `EXIT_APPLIED 40 120`을 기록했고 tracee 메모리 readback도 `40 120`이었다.
4. 그러나 guest BusyBox `stty size`는 계속 `28 96`을 반환했다.
5. unpatched/exit-only/enter+exit/late-write/no-seccomp/master-fd/descendant-fd/watcher-fd9 및
   musl preload 후보를 비교했으나 guest 결과는 바뀌지 않았다.

최종 production probe 결과:

```text
success=false
healthy=true
runtime_state=READY
terminal_resize_support=DYNAMIC
terminal_resize_error=TERMINAL_UNAVAILABLE
terminal_size=28 96
terminal_after_resize=28 96
terminal_resize_ack=MISMATCH ... STATE 40 120 PROOT EXIT_APPLIED 40 120 ...
```

결정:

- ioctl 성공만으로 guest resize 성공을 판정하지 않는다.
- probe 성공과 release capability 승격을 차단하는 fail-closed 상태를 유지한다.
- `terminal_dynamic_resize` gate는 `GUEST_WINSIZE_NOT_PROPAGATED`로 계속 `BLOCKED`다.
- public `DYNAMIC` 지원으로 선언하려면 guest `stty`, `SIGWINCH` TUI 재배치와 resize storm이 모두
  실제로 통과해야 한다.

관련 구현:

- `alpine-runtime-android/src/main/cpp/pty_bridge.c`
- `alpine-runtime-android/src/main/kotlin/dev/alpine/runtime/android/internal/NativePtyBridge.kt`
- `alpine-runtime-android/src/main/kotlin/dev/alpine/runtime/android/internal/GuestTerminalResizeChannel.kt`
- `alpine-runtime-android/src/main/kotlin/dev/alpine/runtime/android/internal/ProotProcessLauncher.kt`
- `scripts/runtime/patches/proot-android-winsize.patch`

## 2. Runtime artifact 공급망

공통 PRoot patch:

- SHA-256: `20726d1ccf9bb8c952a6039d5158168dad58ec62bcf7cbf73bc3170b8c4a9a27`

arm64-v8a production pack:

- PRoot: `336627708766ac2065485c4759048412761ea749787ca3111889f225585cfa4c`
- loader: `12d2b63e897fd91a334fce23edea5d2419cae4d5fd2a369f05d03ab75682add0`
- SBOM: `e912cff0fc657259717351198a13f380837e63fec15874e9f5483b2551d81971`

x86_64 experimental pack:

- PRoot: `9c0bf771ba92151514338643b03fce271d80543c30ae6395c472f313a5d98868`
- loader: `4ca6f14810548610501d012144abeb4c27c1530e2e37201cabf30cab2c39a585`
- SBOM: `8115d8bf5e5675e74ed4de6dd1adc29f56cc07967d6fe6dc1b17cc131d183e62`
- emulator gate: `SKIP_NO_X86_64_EMULATOR`

x86_64은 publication/consumer payload와 ELF/16 KiB 검사는 통과했지만 실제 lifecycle E2E가 없으므로
`experimental_requires_emulator_e2e`를 유지한다.

## 3. Provider 계정·idempotency

- Provider report template과 validator가 모델 목록, non-stream, stream, cancel, logout 및
  Provider별 idempotency 계약 검토 결과를 요구한다.
- 안정적인 Provider request key가 명시되지 않은 POST는 자동 재시도하지 않는다.
- Provider dispatch 이후 다른 backend로 자동 fallback하지 않는 fail-closed 라우팅 계약을 유지한다.
- fake 429·5xx·비정상 SSE·response limit 회귀 테스트는 통과했다.
- 실제 계정/공식 client registration/비용 승인이 없어 `executed=false`이며 제품 E2E는 미실행이다.

## 4. Play Asset Delivery

- fetch 실패·취소·timeout을 안정 오류로 정규화하고 redacted report template/validator를 연결했다.
- native PRoot/loader/PTY는 base APK JNI 경계에 남고 Play 공급 대상은 rootfs/보조 layer로 제한한다.
- Play Console test track, application ID, signing owner와 signed AAB가 없어 실제 설치·업데이트·offline·
  rollback 검증은 미실행이다.

## 5. Samsung background·복구

- FGS `START_NOT_STICKY`, 명시적 Stop, stale lease 정규화, WorkManager 상태 점검 전용 정책과
  redacted lifecycle report gate를 구현했다.
- 정상 terminal lifecycle에서 FGS 생성·제거는 이전 Samsung smoke test로 확인했다.
- reboot, Doze, 강제 process kill과 배터리 제한은 파괴적 실기기 테스트 승인이 없어 미실행이다.
- 이전 command/prompt를 자동 재실행하는 동작은 허용하지 않는다.

## 6. Gradle 9 준비도와 ABI 보존

- Kotlin Gradle Plugin을 `2.2.21`로 갱신했다.
- 모든 Kotlin 모듈을 typed `compilerOptions`로 전환하고 Java 8 interface default method ABI가
  바뀌지 않도록 `JvmDefaultMode.DISABLE`을 명시했다.
- `:alpine-runtime-api:verifyPublicApiDump`가 기존 public ABI와 동일함을 확인했다.
- readiness audit 결과는 project-owned/external/unattributed warning 모두 `0`,
  `gradle9_ready=true`다.
- wrapper는 공식 AGP 8.10 조합의 Gradle `8.11.1` 기준선을 유지했다. 실제 Gradle 9 wrapper로
  올렸다고 주장하지 않는다.

공식 호환성 근거:

- Kotlin Gradle 호환 범위: <https://kotlinlang.org/docs/gradle-configure-project.html>
- Kotlin 2.2 JVM default 변경: <https://kotlinlang.org/docs/compatibility-guide-22.html>
- AGP 8.10 요구 Gradle/SDK: <https://developer.android.com/build/releases/agp-8-10-0-release-notes>

## 7. 전체 로컬 release gate

`PYTHON_BIN=python3.11 scripts/release-local.sh` 최종 결과:

- Python: `88 tests`, **OK**
- Android 전체 debug/release/lint/unit/native gate: **통과**
- SDK publication: `17`개 **통과**
- 외부 published-consumer release/R8/lint matrix: `8`개 **통과**
- 공개 API dump: **통과**
- Gradle 9 readiness warning: `0`
- x86_64 emulator gate: `SKIP_NO_X86_64_EMULATOR`
- 내부 bundle: `dist/alpine-sdk-0.3.0`

격리된 published-consumer build가 root `local.properties`를 볼 수 없어 Android SDK 경로를 찾지
못하던 문제는 `scripts/release-local.sh`가 root `sdk.dir`를 `ANDROID_HOME`으로만 전달하도록 수정했다.
machine-local 파일은 fixture나 bundle에 복사하지 않는다.

## 8. 남은 공개 배포 차단 항목

Release blocking:

1. `PROJECT_LICENSE_UNDECLARED`
2. `CORRESPONDING_SOURCE_MISSING`
3. `PROVIDER_APPROVAL_REQUIRED`
4. `PLAY_TRACK_NOT_CONFIGURED`
5. `DESTRUCTIVE_DEVICE_TEST_APPROVAL_REQUIRED`
6. `REMOTE_CI_NOT_VERIFIED`
7. `RELEASE_DESTINATION_UNASSIGNED`

Capability blocking:

1. `GUEST_WINSIZE_NOT_PROPAGATED`
2. `X86_64_EMULATOR_UNAVAILABLE`

따라서 내부 bundle은 개발·검증용으로만 사용하며 공개 Maven/Play 배포는 진행하지 않는다.
