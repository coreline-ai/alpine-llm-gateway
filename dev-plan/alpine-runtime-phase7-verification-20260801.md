# Alpine Runtime Phase 7 배포·전체 통합 검증 기록

- 검증일: `2026-08-01 KST`
- 버전: `0.3.0`
- 범위: Maven publication, 외부 소비자 matrix, R8/manifest/ABI/payload 검증, 내부 release bundle, Samsung 전체 통합 E2E
- 실기기: Samsung `SM-S931N`, Android 16/API 36, `arm64-v8a`, serial `<redacted>`

## 구현 결과

| 영역 | 결과 |
|---|---|
| SDK publication | `dev.alpine.llm` group의 재사용 AAR/JAR 12개와 sources, POM, Gradle metadata, checksum sidecar 발행 |
| 외부 소비자 | 저장소 source project를 참조하지 않는 별도 Gradle fixture와 5개 release/R8 조합 구현 |
| payload 격리 | runtime 미사용 앱에 rootfs, PRoot, loader, PTY와 Python Gateway가 포함되지 않도록 자동 검사 |
| native gate | PRoot, guest loader와 native PTY의 모든 ELF `PT_LOAD` alignment가 16 KiB 이상인지 build/publication에서 직접 검사 |
| terminal 계약 | 고정 PRoot의 동적 resize 거짓 성공을 제거하고 `INITIAL_SIZE_ONLY`/`TERMINAL_RESIZE_UNSUPPORTED`로 공개 |
| 배포 bundle | Maven repository, runtime lock/SBOM, license inventory, notice, 문서, report와 전체 SHA-256을 묶은 내부 bundle 생성 |
| 공개 배포 안전장치 | 프로젝트 license, complete corresponding source와 실계정 승인이 없으면 `remote_distribution_ready=false`로 fail-closed |
| CI | 전체 Android gate 뒤 publication, 외부 release/R8 matrix, bundle 생성과 CI artifact upload 추가 |

## 자동 검증

- Python unit/architecture/provider resilience: `73`개, `OK`.
- Android 전체 로컬 release gate: `1079`개 Gradle task, `BUILD SUCCESSFUL`.
- SDK publication validator: `12`개 artifact와 project dependency metadata, payload owner, sources/checksum, 16 KiB ELF gate 통과.
- 외부 소비자 matrix: `242`개 Gradle task, 5개 앱의 release R8/resource shrink/lint `BUILD SUCCESSFUL`.
- 외부 소비자 validator: minSdk 26, arm64 native, permission과 payload 조합 5개 모두 통과.
- 내부 release bundle: `dist/alpine-sdk-0.3.0`, 파일 `320`개, 약 `6.0 MiB`; `SHA256SUMS`가 나머지 `319`개 파일을 모두 포함함을 재검사했다.
- `bash -n`, Python script compile과 `git diff --check` 통과.

## 외부 소비자 결과

| 조합 | APK 크기 | 포함 payload |
|---|---:|---|
| no-runtime | 54,184 bytes | 없음 |
| runtime-only | 3,977,459 bytes | rootfs, PRoot, loader, PTY |
| runtime-ui | 4,044,844 bytes | rootfs, PRoot, loader, PTY |
| runtime-llm | 4,030,584 bytes | runtime payload + Python Gateway |
| full | 4,061,808 bytes | runtime payload + Python Gateway + UI/두 모드 backend |

`no-runtime` APK는 Alpine native/payload와 runtime 전용 권한을 포함하지 않는다. runtime을 사용하는 네 조합은 현재 지원 ABI인 `arm64-v8a`만 포함한다.

## Samsung 실기기 검증

### 계측 테스트

- Compose 접근성 state/content description, 200% font, 한글 IME와 외부 Enter의 `2`개 test 통과.
- Android OAuth token의 Keystore 암호화·재생성·삭제·비반출 경계 `2`개 instrumentation test 통과.
- Host Bridge loopback/token 경계의 `3`개 instrumentation test 통과.
- 최초 수동 test APK 실행에서 Android library의 test-only APK가 target 26으로 생성돼 Samsung 호환성 안내가 Compose test Activity를 가리는 문제를 발견했다. `:android`, `:alpine-llm-bridge`, `:alpine-runtime-ui-compose`의 `testOptions.targetSdk=36`으로 수정했으며 세 APK가 실제 target 36인지 확인한 뒤 안내 없이 `2+3+2`개 test를 모두 재실행 통과했다.

### Runtime·PTY probe

- Alpine `3.21.3-openminis-8cf13e9`, target SDK 36, arm64 packaged PRoot/loader install·재사용, command·restart·repair·stop 통과.
- native PTY와 최초 크기 `28x96`, Ctrl/signal/process cleanup 통과.
- 후속 resize는 `INITIAL_SIZE_ONLY`, `TERMINAL_RESIZE_UNSUPPORTED`로 명시적으로 거부되고 guest 크기가 `28x96`으로 유지됨을 확인했다.
- probe 결과 `success=true`, `healthy=true`, 실행 시간 `2584 ms`.

### Python Gateway·Host Bridge probe

- Gateway/package version `0.3.0`, protocol `1` health 통과.
- `llmctl models`, non-stream `run`, SSE `stream`, 실행 취소와 process cleanup 통과.
- capability rotation, config에서 capability 비노출, owner/runtime/gateway/bridge health가 모두 통과했다.
- probe 결과 `success=true`, 실행 시간 `2909 ms`.

### 통합 앱

- 최신 `integrated-app-debug.apk`를 설치하고 cold start 성공.
- `Alpine 작업` → `빠른 채팅` → `Alpine 작업`을 전환하고 force-stop/cold start 뒤 `Alpine 작업` 선택이 보존됨을 확인했다.
- 통합 앱 자체에서 runtime 설치, 시작, native PTY terminal 열기와 guest `echo` 명령의 UI→PTY→guest→UI 왕복을 확인했다.
- 화면에 `터미널 크기는 열 때 적용됩니다.` 안내가 노출되고 검증 후 terminal과 runtime을 정상 종료해 다시 시작 가능한 `READY` 상태로 복구했다.

## 리스크 처리 결과

### 닫힘

1. PRoot/loader/PTY Android 16 16 KiB alignment는 모두 `0x4000`이며 자동 gate가 막는다.
2. runtime 미사용 APK payload·권한 오염은 외부 fixture로 자동 차단한다.
3. published artifact의 transitive dependency, sources, POM/module/checksum과 release R8 조합을 자동 검증한다.
4. 동적 resize 거짓 성공은 제거했고 현재 capability를 UI/API에 정확히 표시한다.

### 외부 조건 대기

1. 프로젝트 전체 라이선스 선언.
2. 고정 PRoot/talloc complete corresponding source archive와 재현 build 절차.
3. 공식 Provider client registration, 실계정과 비용 승인에 기반한 opt-in E2E.
4. Maven/App Store 배포 위치와 signing key owner.

### 기술 후속

| 우선순위 | 항목 | 다음 완료 조건 |
|---:|---|---|
| P1 | 장기 background 실행 | Host Foreground Service/notification adapter, WorkManager, Doze/process death/reboot matrix |
| P1 | 실제 동적 terminal resize | PRoot patch·재빌드 후 Samsung에서 resize storm과 TUI 검증, capability를 `DYNAMIC`으로 승격 |
| P1 | x86_64 지원 | rootfs/PRoot/loader/PTY lock·SBOM·16 KiB gate와 emulator E2E 추가 |
| P1 | 실제 Provider 운영 E2E | 소유자 승인 계정으로 model/non-stream/stream/cancel/logout 검증; token 추출 금지 |
| P2 | Store asset adapter | AAB 크기 측정, Play Asset Delivery 또는 signed download install/update/rollback 검증 |
| P2 | Gradle 9 migration | deprecated feature 전체 위치를 열거하고 AGP/Kotlin/빌드 스크립트 호환성을 새 CI matrix에서 검증 |
| P2 | 장기 workspace 기능 | 파일·Git/SSH·AI runner·자동화를 별도 `alpine-workspace-*` 모듈로 순차 구현 |

실제 기기 재부팅은 사용자 작업을 중단하는 파괴적 검증이므로 이번 실행에서는 수행하지 않았다. SDK는 재부팅 후 임의 자동 시작하지 않으며, process death에서 거짓 `RUNNING` 상태가 남지 않는 계약까지 검증했다.

## 결론

Phase 7의 **기술 구현, 내부 publication, 외부 소비자 전체 조합과 Samsung 통합 검증은 완료**됐다. 내부 배포물은 사용할 수 있지만 공개 원격 배포는 소유자·법적·운영 gate가 충족될 때까지 의도적으로 차단된다.
