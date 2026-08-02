# Alpine Runtime Phase 3 검증 기록

검증 일시: `2026-08-01 17:35 KST`

## 범위

- 재사용 가능한 Android runtime installer/process/session 구현
- bundled, host-provided, Ed25519 signed-download artifact provider
- arm64-v8a Alpine 3.21.3 + PRoot artifact lock, license, SPDX SBOM
- staging, 원자적 활성화, 취소, rollback, repair, reset
- 기존 `:android` runtime API의 0.x 모듈 이전
- 빠른 채팅 전용 APK의 runtime payload 무포함 보장

## 구현 결과

| 항목 | 결과 |
|---|---|
| SDK 구현 | `:alpine-runtime-android`로 installer/process/health/session 이동 |
| 선택형 payload | `:alpine-runtime-pack-bundled`에만 rootfs/PRoot/loader 포함 |
| 공급 방식 | bundled, host-provided, signed-download 분리 |
| 서명 경계 | Ed25519 서명과 실제 bundle 매니페스트 canonical bytes 결합 검증 |
| 설치 복구 | staging + pending transaction + previous runtime rollback/finalize |
| Host 정책 연결 | process STARTED/STOPPED listener 제공; Foreground Service 정책은 Host 소유 |
| 호환성 | 기존 0.x runtime class 제거 및 migration 문서 제공 |

## Artifact 고정값

정본: `runtime/alpine-3.21.3-arm64.lock.json`

| Artifact | SHA-256 | APK 내부 재검증 |
|---|---|---:|
| Alpine minirootfs 3.21.3 aarch64 | `ead8a4b37867bd19e7417dd078748e2312c0aea364403d96758d63ea8ff261ea` | 일치 |
| PRoot arm64-v8a | `110eb0aca091dcd4c4d887e7b836aee042f6ec0f543971e2ab8a86525b312cec` | 일치 |
| PRoot loader arm64-v8a | `cb5e5b6900e198ca8160e9d355ea5b98d646333887a769411ff74132c1cec5df` | 일치 |
| SPDX-2.3 SBOM | `7d455306d06c34122413876c1e663c5df3c04129f7afd3883fc117afaf570aaa` | 패키징 확인 |

## 자동 테스트

- Python/Gateway/architecture: **57개 통과**
- `:alpine-runtime-api`: **6개 통과**
- `:alpine-runtime-android`: **18개 통과**
  - installer/rollback/recovery 10개
  - provider 서명·bundle 결합·오류 redaction 5개
  - factory 경계 1개
  - reserved environment·중지 session 재실행 차단 2개
- `:alpine-runtime-pack-bundled`: **1개 통과**
- Gradle CI 동등 실행: **BUILD SUCCESSFUL**, 504 tasks
- 최종 lifecycle 보강 후 API/runtime/Lint/probe 재검증: **BUILD SUCCESSFUL**, 109 tasks
- API dump check, Android unit test, Release AAR, Lint, sample/demo/probe APK build 통과
- `:demo-chatbot:verifyNoAlpineRuntimePayload` 통과

실패·경계 회귀에는 checksum mismatch, ABI mismatch, archive/출력 크기 제한,
취소, 활성화 직전 process death, rootfs 활성화 후 process death, marker 활성화 후
process death, 저장소 쓰기 실패, reset 후 workspace 보존이 포함된다.

## 실기기·에뮬레이터 E2E

| 환경 | 결과 | 확인 내용 |
|---|---:|---|
| Samsung SM-S931N, Android 16/API 36, arm64-v8a | 통과 | install/start/exec/stop/restart/repair/reset |
| Android 15/API 35 arm64 emulator | 통과 | install/start/exec/stop/restart/repair/reset |

두 환경에서 공통으로 다음을 확인했다.

- runtime version: `3.21.3-openminis-8cf13e9`
- guest: Alpine `3.21.3`, `aarch64`, uid `0`
- guest working directory: `/workspace`
- 첫 실행과 재시작 명령 exit code `0`
- process listener: STARTED/STOPPED 쌍 2회
- reset 후 상태 `NOT_INSTALLED`
- reset 후 `/workspace/probe.txt` 보존

## 구현 중 발견 이슈와 수정

1. Alpine `/bin/sh`가 절대 심볼릭 링크여서 일반 파일 검사 시 무결성 실패가 발생했다.
   링크 자체 존재를 `NOFOLLOW_LINKS`로 확인하도록 수정했다.
2. 서명 바이트가 실제 전달 bundle과 결합되지 않으면 서명된 다른 매니페스트를 재사용할
   수 있어, 모든 필드를 결정적으로 canonicalize하고 bundle 및 payload descriptor와
   동일한지 추가 검증했다.
3. 프로세스 시작 전 환경 검증 오류가 `PROCESS_START_FAILED`로 덮이는 문제를 수정해
   `INVALID_REQUEST`를 유지했다.
4. 명령 대기·수집 중 예외에서도 child process와 listener 상태가 반드시 정리되도록
   `finally` 정리를 추가했다.
5. API dump 생성 작업에 compiled class input 선언이 없어 stale dump가 통과할 수 있는
   문제를 수정했다.
6. probe 결과 polling이 `set -euo pipefail`에서 결과 파일 생성 전 종료되는 문제를
   안전한 재시도로 수정했다.
7. signed-download source가 동기 예외를 던질 때 transport 원문이 노출되지 않도록
   `ARTIFACT_NOT_FOUND`로 닫힌 오류 변환을 추가했다.
8. manager 중지 후 오래된 session 참조가 새 host process를 만들지 못하도록 session
   활성 등록/해제를 원자적으로 연결하고 `PROCESS_EXITED` 회귀 테스트를 추가했다.

## 결론

Phase 3 완료 조건을 충족한다. Phase 4는 이 runtime에 Android OAuth/Provider Host Bridge와
Python Gateway lifecycle을 연결하되 OAuth credential을 guest에 전달하지 않는 경계를 유지한다.
