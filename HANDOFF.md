# Alpine LLM Gateway — 작업 핸드오프

작성 일시: `2026-08-10 KST`
대상 작업 트리: `/Volumes/Eprojects/project_202607/alpine-llm-gateway`

이 문서는 다른 작업자/에이전트가 현재 상태를 안전하게 이어받기 위한 인계 문서다. 기준선은 **원격 `main`에 push된 `107ef9e`** 이며, 다음 작업은 이 commit에서 새 branch를 만들어 시작한다.

## 1. 제품 및 저장소 경계

| 저장소 | 원격 `main` | 역할 |
|---|---:|---|
| [`coreline-ai/alpine-llm-gateway`](https://github.com/coreline-ai/alpine-llm-gateway) | `107ef9e` | Alpine Linux Gateway, Android Runtime SDK, PRoot/PTY, Android Compose 통합 앱 |
| [`coreline-ai/flutter_mobile_agent`](https://github.com/coreline-ai/flutter_mobile_agent) | `f490713` | Android/iOS Flutter MobileAgent, OAuth/OIDC, BFF, 개발 Keycloak fixture |

### 반드시 지킬 경계

- Alpine 저장소에는 Flutter `MobileAgent` 앱, Flutter plugin, MobileAgent BFF, dev IdP를 다시 추가하지 않는다.
- Flutter 저장소에는 Alpine Runtime, PRoot, `alpine_llm/`, Android Runtime SDK를 추가하지 않는다.
- Alpine의 Android 직접 Provider OAuth/Host Bridge와 MobileAgent의 OIDC+BFF 인증 경로는 별개다. token·client registration·credential을 공유하거나 이동하지 않는다.
- 분리 완료 계획: [`implement_20260809_174953.md`](https://github.com/coreline-ai/alpine-llm-gateway/blob/main/dev-plan/implement_20260809_174953.md)

## 2. 현재 Git 상태 — 시작점

### publish된 기준선

- 현재 기준: `main @ 107ef9e`, `origin/main @ 107ef9e`
- `107ef9e`는 `8711104` (Flutter 분리 완료 계획) 위로 runtime/OAuth/검증 묶음을 rebase한 commit이다.
- 이 문서와 구현 묶음은 모두 원격 `main`에 push됐다. 시작 전 `git fetch origin --prune` 후 `git status --short --branch`가 깨끗한지 확인한다.
- 로컬 `backup/pre-main-push-20260810`은 rebase 전 WIP 보존용 branch다. 원격 main의 대체 기준선으로 사용하지 않는다.
- 로컬 `separation/flutter-extraction @ 8272079` branch는 과거 분리 작업의 중간 branch다. 원격 `main`은 더 최신의 분리 완료 상태다.

### 시작 규칙

새 구현은 `main`에 직접 쌓지 말고 topic branch에서 시작한다.

```bash
cd /Volumes/Eprojects/project_202607/alpine-llm-gateway
git fetch origin --prune
git switch main
git pull --ff-only
git switch -c <topic-name>
```

- 충돌 시 Alpine 전용 내용만 유지한다. MobileAgent 전용 path/link/image/CI는 되살리지 않는다.
- `apps/mobile_agent`, `packages/`, `backend/mobile_agent_bff`는 이 저장소의 tracked source가 아니어야 한다.

## 3. `107ef9e`에 포함된 구현 묶음

다음 변경은 publish됐다. 상세 체크리스트는 각 `dev-plan/implement_20260809_*.md`를 기준으로 확인한다.

| 영역 | 핵심 변경 위치 | 상태/주의 |
|---|---|---|
| PRoot/PTY | `alpine-runtime-android/src/main/cpp/pty_bridge.c`, `ProotProcessLauncher.kt`, `NativePtyBridge.kt` | `forkpty()` 기반 실험·검증 코드가 포함되어 있다. 제품 public contract는 아직 `INITIAL_SIZE_ONLY`를 유지해야 한다. |
| Gateway recovery | `alpine-llm-bridge/.../AlpineLlmBridgeRecoverySupervisor.kt`, `integrated-app/.../IntegratedAlpineLlmHost.kt` | Stop 이후 자동 복구 restart 경쟁 조건을 막는 generation/lease 보강. |
| Runtime package 안전성 | `alpine-runtime-api/.../RuntimePackages.kt`, `RuntimePackageInstallerTest.kt`, `RuntimePackagePanel.kt` | `apk --simulate` preflight, metadata bounded-total/overflow 표시, allowlist·승인 경계 유지. |
| Background service | `alpine-runtime-background-android/.../RuntimeForegroundService.kt` | 마지막 Runtime 종료 뒤 foreground service/notification 제거 계약. |
| Workspace SAF | `alpine-workspace-android/.../WorkspaceSafTransfer.kt` | export byte 상한과 실패 시 destination 보존 회귀. |
| Android Provider OAuth | `android/.../HostLlmBridge.kt`, `CodexOAuthContract.kt`, `OAuthLlmSessionTest.kt` | 401 refresh는 다음 사용자 action을 위한 session 갱신만 하며 원 inference POST를 자동 replay하지 않는다. |
| 기기/Emulator test | `*/src/androidTest/**`, `alpine-runtime-probe/**`, `integrated-app/**` | Samsung/tablet, ARM64 API 26/35, x86_64 emulator gate 관련 instrumentation과 evidence 동기화. |
| 문서/릴리스 | `README.md`, `distribution/`, `docs/`, `dev-plan/` | current deliverable의 GitHub CI evidence 전까지 release readiness는 fail-closed `BLOCKED`를 유지. |

## 4. 최근 개발 계획 우선순위

| 우선순위 | 문서 | 핵심 결론 |
|---:|---|---|
| 1 | [`dev-plan/implement_20260809_174816.md`](dev-plan/implement_20260809_174816.md) | 배포 제외 제품 감사. PRoot dynamic resize는 지원 기능이 아니라 명시된 `INITIAL_SIZE_ONLY` 제약이다. |
| 2 | [`dev-plan/implement_20260809_082423.md`](dev-plan/implement_20260809_082423.md) | `forkpty()` 기반 PRoot architecture 실험. 검증 성공 전 API/UI capability 승격 금지. |
| 3 | [`dev-plan/implement_20260809_143000.md`](dev-plan/implement_20260809_143000.md) | x86_64 Android emulator Runtime E2E. 실제 실행 증거와 ARM64 결과를 혼용하지 않는다. |
| 4 | [`dev-plan/implement_20260809_134000.md`](dev-plan/implement_20260809_134000.md) | Provider 401 refresh 뒤 inference replay 금지. `NEVER_AUTOMATIC` 정책 유지. |
| 5 | [`dev-plan/implement_20260809_133500.md`](dev-plan/implement_20260809_133500.md) | current deliverable의 GitHub CI는 push/run evidence 전까지 `BLOCKED`. |

## 5. 재검증 순서

`107ef9e`에서는 아래 로컬 검증을 실행했다: UI design contract PASS, Python `unittest discover` **114 PASS**, Android/Kotlin unit test PASS, arm64-v8a/x86_64 native CMake debug build 및 integrated APK build PASS. 다음 변경 후에는 같은 순서를 재실행한다.

```bash
cd /Volumes/Eprojects/project_202607/alpine-llm-gateway

python3.11 scripts/verify-ui-design-contract.py
python3.11 -m unittest discover -s tests -v
python3.11 scripts/verify-proot-terminal-handoff.py --proot-source <pinned-proot-source>

JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
ANDROID_HOME="$HOME/Library/Android/sdk" \
./gradlew :alpine-runtime-android:assembleDebug :integrated-app:assembleDebug --no-daemon --stacktrace
```

현재 worktree에 Android instrumentation 추가 파일이 있으므로, Android device/emulator 검증은 해당 계획 문서의 명시 task와 package cleanup 절차를 따른다. 실제 Provider 계정 OAuth/API, signed release, Play 배포, 실제 iPhone은 자동 검증 완료로 간주하지 않는다.

## 6. MobileAgent를 이어서 작업해야 할 때

Alpine 작업 트리가 아니라 별도 clone에서 시작한다.

```bash
git clone https://github.com/coreline-ai/flutter_mobile_agent.git
cd flutter_mobile_agent
make mobile-analyze
make mobile-test
make bff-bootstrap
make bff-test
make oauth-release-scan
```

MobileAgent의 실제 OAuth E2E에는 앱 소유 HTTPS issuer, public native client, BFF, Provider account/secret owner가 필요하다. API key, consumer OAuth endpoint, 다른 CLI/app client ID를 앱 또는 저장소에 넣지 않는다.

## 7. 완료 정의와 외부 조건

### 로컬 코드 범위

- Alpine Runtime/PRoot/Provider 안전 경계 및 automated regression은 `107ef9e`에서 재검증됐다. 이후 변경은 다시 검증해야 한다.
- PRoot dynamic resize는 acceptance가 없는 한 `INITIAL_SIZE_ONLY` 제약을 유지한다.
- Provider inference는 idempotency 계약이 확정되기 전 자동 replay하지 않는다.

### 외부 조건 — 완료로 주장 금지

- 실제 Provider OAuth/API E2E 및 account/region/model 권한
- Samsung reboot/Doze/battery restriction/장시간 soak
- 실제 iPhone, Play Internal, TestFlight, signing
- `107ef9e`에 대한 GitHub Actions run evidence
- 라이선스/notice/legal/release owner 승인

## 8. 인계 확인 체크리스트

- [ ] `git fetch origin --prune` 후 `main @ 107ef9e`를 확인했다.
- [ ] 새 작업용 topic branch를 만들었다.
- [ ] Alpine `origin/main`의 `107ef9e` 분리 상태를 기준으로 작업한다.
- [ ] Flutter 작업은 별도 `flutter_mobile_agent` clone에서 수행한다.
- [ ] 변경 후 자동 검증과 Android native/integrated APK build를 재실행했다.
- [ ] 외부 조건을 로컬 PASS 또는 제품 출시 완료로 과장하지 않았다.
