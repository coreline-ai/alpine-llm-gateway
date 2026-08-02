# 통합 MVP Phase 0 기준선 검증 — 2026-08-02

작성 일시: `2026-08-02 19:26:45 KST`

## 결론

- 현재 Python Gateway, Android LLM, Runtime SDK, Workspace, Sample과 배포 도구의 로컬 통합 검증은 통과했다.
- 기존 `:android`에서 삭제된 Runtime·HostBridge 책임은 신규 독립 모듈로 이동됐고 경계 테스트가 이를 강제한다.
- 생성된 `.cxx` 중간 파일, 기기 serial과 로컬 절대 경로는 Git 기준선에 포함되지 않도록 정리했다.
- 내부 SDK bundle은 생성 가능하지만 외부 배포 Gate 6개와 기능 Gate 2개는 계속 fail-closed다.
- 기능군별 Commit, main Push, clean checkout 재현과 GitHub 원격 CI까지 확인해 Phase 0 기준선을 확정했다.

## 변경 분류

| 영역 | 주요 책임 |
|---|---|
| Python Gateway | 입력 검증, response/SSE limit, retry/circuit, Android HostBridge adapter |
| Android LLM | OAuth Provider adapter, bounded stream, Host credential 소유 |
| Runtime SDK | API, Android Runtime, artifact pack, Host controller, background, Compose UI |
| Chat Routing | Direct/Alpine Backend, no post-dispatch fallback, duplicate rejection |
| Workspace | app-private bounded file API와 Android 구현 |
| Product/Sample | runtime probe, bridge probe, custom sample, integrated shell |
| Verification | publication, consumer matrix, Provider/Play/Samsung report validator |
| Compliance | provenance, component policy, native source bundle, release readiness |

## 이동·모듈 경계 확인

- `HostBridgeServer`와 `AlpineLlmGatewayClient`는 `:android`에서 `:alpine-llm-bridge`로 이동했다.
- Runtime install, PRoot launch, PTY와 artifact 선택은 `:alpine-runtime-android`와 pack 모듈로 이동했다.
- `:alpine-runtime-api`, `:alpine-runtime-host`, `:alpine-chat-routing`은 Android/Compose/Provider 타입을 참조하지 않는다.
- 빠른 채팅 `:demo-chatbot`은 Runtime pack을 의존하거나 APK에 포함하지 않는다.
- PRoot/talloc은 앱 JNI link 대상이 아니며 별도 executable/process payload 경계를 유지한다.
- 승인된 Host 앱만 Runtime pack을 직접 소비한다.

## 저장소 위생

- `.gitignore`에 `**/.cxx/`를 추가해 NDK/CMake machine-local 산출물을 제외했다.
- unified diff 원본의 context whitespace를 보존하는 `*.patch` 전용 `.gitattributes` 규칙을 추가했다.
- private key, API key, Bearer token 형태의 credential sentinel에서 검출 항목이 없었다.
- 과거 QA 문서에 기록된 Samsung serial을 `<redacted>`로 치환했다.
- OpenMinis 참조 문서의 로컬 절대 경로를 `<OPENMINIS_ROOT>` placeholder로 치환했다.
- `local.properties`, Gradle cache, build output과 `dist/`는 Git 추적 대상에서 제외된다.

## 로컬 검증 결과

실행 명령:

```bash
JAVA_HOME='<JDK_HOME>' \
PYTHON_BIN=python3.11 \
scripts/release-local.sh
```

결과:

| 검증 | 결과 |
|---|---|
| Python unit/socket tests | `97 tests`, 모두 통과 |
| Android unit/lint/debug/release | 통과 |
| Root Gradle | `BUILD SUCCESSFUL`, 1,391 tasks |
| SDK publication | 17개 통과 |
| Published consumer | 8개 variant 통과 |
| Consumer R8/lint | 통과 |
| Gradle 9 readiness | warning 0, ready true |
| x86_64 emulator | `SKIP_NO_X86_64_EMULATOR` |
| Internal SDK bundle | `dist/alpine-sdk-0.3.0` 생성 |
| clean checkout | Commit `c88d316`에서 전체 release script 통과 |
| GitHub CI | Run `30744482850`, Python·Android·Artifact 모두 성공 |

## 유지되는 차단 상태

Release blocking:

1. `PROJECT_LICENSE_UNDECLARED`
2. `ROOTFS_PACKAGE_SOURCE_MIRROR_MISSING`
3. `PROVIDER_APPROVAL_REQUIRED`
4. `PLAY_TRACK_NOT_CONFIGURED`
5. `DESTRUCTIVE_DEVICE_TEST_APPROVAL_REQUIRED`
6. `RELEASE_DESTINATION_UNASSIGNED`

Capability blocking:

1. `GUEST_WINSIZE_NOT_PROPAGATED`
2. `X86_64_EMULATOR_UNAVAILABLE`

## Phase 0 완료 기록

- [x] 기능군별 Commit 생성
- [x] `main` 원격 Push
- [x] GitHub 원격 CI 확인
- [x] clean checkout에서 동일 bundle 구조 재현 확인

첫 원격 Run `30743827724`는 license compliance report 생성 단계 누락으로 SDK package가 실패했다.
`.github/workflows/ci.yml`에 report 생성 단계를 추가한 뒤 Run `30744482850`이 전체 성공했으며,
세부 근거는 `distribution/GITHUB_CI_STATUS.md`에 기록했다.
