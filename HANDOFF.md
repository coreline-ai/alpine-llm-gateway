# Alpine LLM Gateway — 작업 핸드오프

작성 일시: `2026-08-10 KST`
대상 작업 트리: `/Volumes/Eprojects/project_202607/alpine-llm-gateway`

이 문서는 다른 작업자/에이전트가 현재 상태를 안전하게 이어받기 위한 인계 문서다. **현재 작업 트리에는 대량의 미커밋 변경이 있으며, 원격 `main`보다 뒤처져 있다.** 먼저 이 문서를 읽고 Git 상태를 보존해야 한다.

## 1. 제품 및 저장소 경계

| 저장소 | 원격 `main` | 역할 |
|---|---:|---|
| [`coreline-ai/alpine-llm-gateway`](https://github.com/coreline-ai/alpine-llm-gateway) | `8711104` | Alpine Linux Gateway, Android Runtime SDK, PRoot/PTY, Android Compose 통합 앱 |
| [`coreline-ai/flutter_mobile_agent`](https://github.com/coreline-ai/flutter_mobile_agent) | `f490713` | Android/iOS Flutter MobileAgent, OAuth/OIDC, BFF, 개발 Keycloak fixture |

### 반드시 지킬 경계

- Alpine 저장소에는 Flutter `MobileAgent` 앱, Flutter plugin, MobileAgent BFF, dev IdP를 다시 추가하지 않는다.
- Flutter 저장소에는 Alpine Runtime, PRoot, `alpine_llm/`, Android Runtime SDK를 추가하지 않는다.
- Alpine의 Android 직접 Provider OAuth/Host Bridge와 MobileAgent의 OIDC+BFF 인증 경로는 별개다. token·client registration·credential을 공유하거나 이동하지 않는다.
- 분리 완료 계획: [`implement_20260809_174953.md`](https://github.com/coreline-ai/alpine-llm-gateway/blob/main/dev-plan/implement_20260809_174953.md)

## 2. 현재 Git 상태 — 가장 중요

### 로컬 작업 트리

- 현재 checkout: `main @ e5e275e`
- `origin/main`: `8711104`
- 상태: `main...origin/main [behind 4]`
- tracked 변경: **52개 파일**, 약 **2,149 insertions / 524 deletions**
- untracked 변경: Android test 4개, Python test 2개, 개발 계획 문서 다수
- 로컬 `separation/flutter-extraction @ 8272079` branch는 과거 분리 작업의 중간 branch다. 원격 `main`은 이미 더 최신의 분리 완료 상태다.

### 금지 사항

현재 작업 트리에서 아래를 바로 실행하지 않는다.

```bash
git pull
git reset --hard
git checkout origin/main
git clean -fd
```

위 명령은 현재 PRoot/Runtime/OAuth 작업을 잃게 하거나 대규모 충돌을 만든다.

### 안전한 재개 절차

1. 먼저 현재 변경을 독립 WIP branch에 보존한다.
2. 그 뒤 `origin/main` 기반 분리 상태에 rebase한다.
3. Flutter 분리로 삭제된 경로를 되살리는 충돌 해결은 금지한다.

권장 절차:

```bash
cd /Volumes/Eprojects/project_202607/alpine-llm-gateway
git status --short --branch
git switch -c wip/runtime-oauth-20260810
git add -A
git commit -m "wip: preserve runtime and OAuth follow-up work"
git fetch origin --prune
git rebase origin/main
```

- rebase 충돌 예상 위치: `README.md`, `docs/`, `scripts/verify-ui-design-contract.py`, release/readiness 문서.
- 충돌 시 Alpine 전용 내용만 유지한다. MobileAgent 전용 path/link/image/CI는 원격 `main`의 삭제 상태를 유지한다.
- rebase와 전체 검증이 끝나기 전에는 `main`에 직접 push하지 않는다.

## 3. 미커밋 구현 묶음

현재 변경은 다음 주제로 묶여 있다. 상세 체크리스트는 각 `dev-plan/implement_20260809_*.md`를 기준으로 확인한다.

| 영역 | 핵심 변경 위치 | 상태/주의 |
|---|---|---|
| PRoot/PTY | `alpine-runtime-android/src/main/cpp/pty_bridge.c`, `ProotProcessLauncher.kt`, `NativePtyBridge.kt` | `forkpty()` 기반 실험·검증 코드가 포함되어 있다. 제품 public contract는 아직 `INITIAL_SIZE_ONLY`를 유지해야 한다. |
| Gateway recovery | `alpine-llm-bridge/.../AlpineLlmBridgeRecoverySupervisor.kt`, `integrated-app/.../IntegratedAlpineLlmHost.kt` | Stop 이후 자동 복구 restart 경쟁 조건을 막는 generation/lease 보강. |
| Runtime package 안전성 | `alpine-runtime-api/.../RuntimePackages.kt`, `RuntimePackageInstallerTest.kt`, `RuntimePackagePanel.kt` | `apk --simulate` preflight, metadata bounded-total/overflow 표시, allowlist·승인 경계 유지. |
| Background service | `alpine-runtime-background-android/.../RuntimeForegroundService.kt` | 마지막 Runtime 종료 뒤 foreground service/notification 제거 계약. |
| Workspace SAF | `alpine-workspace-android/.../WorkspaceSafTransfer.kt` | export byte 상한과 실패 시 destination 보존 회귀. |
| Android Provider OAuth | `android/.../HostLlmBridge.kt`, `CodexOAuthContract.kt`, `OAuthLlmSessionTest.kt` | 401 refresh는 다음 사용자 action을 위한 session 갱신만 하며 원 inference POST를 자동 replay하지 않는다. |
| 기기/Emulator test | `*/src/androidTest/**`, `alpine-runtime-probe/**`, `integrated-app/**` | Samsung/tablet, ARM64 API 26/35, x86_64 emulator gate 관련 instrumentation과 evidence 동기화. |
| 문서/릴리스 | `README.md`, `distribution/`, `docs/`, `dev-plan/` | 원격 CI는 current uncommitted deliverable 기준 `BLOCKED`로 fail-closed 유지. |

## 4. 최근 개발 계획 우선순위

| 우선순위 | 문서 | 핵심 결론 |
|---:|---|---|
| 1 | [`dev-plan/implement_20260809_174816.md`](dev-plan/implement_20260809_174816.md) | 배포 제외 제품 감사. PRoot dynamic resize는 지원 기능이 아니라 명시된 `INITIAL_SIZE_ONLY` 제약이다. |
| 2 | [`dev-plan/implement_20260809_082423.md`](dev-plan/implement_20260809_082423.md) | `forkpty()` 기반 PRoot architecture 실험. 검증 성공 전 API/UI capability 승격 금지. |
| 3 | [`dev-plan/implement_20260809_143000.md`](dev-plan/implement_20260809_143000.md) | x86_64 Android emulator Runtime E2E. 실제 실행 증거와 ARM64 결과를 혼용하지 않는다. |
| 4 | [`dev-plan/implement_20260809_134000.md`](dev-plan/implement_20260809_134000.md) | Provider 401 refresh 뒤 inference replay 금지. `NEVER_AUTOMATIC` 정책 유지. |
| 5 | [`dev-plan/implement_20260809_133500.md`](dev-plan/implement_20260809_133500.md) | current deliverable의 GitHub CI는 push/run evidence 전까지 `BLOCKED`. |

## 5. 재검증 순서

rebase 후 다음 순서로 실행한다. 경로/SDK는 로컬 환경에 맞게 조정한다.

```bash
cd /Volumes/Eprojects/project_202607/alpine-llm-gateway

python3 scripts/verify-ui-design-contract.py
python3 scripts/verify-proot-terminal-handoff.py --proot-source <pinned-proot-source>
python3 -m pytest -q \
  tests/test_verify_proot_terminal_handoff.py \
  tests/test_android_module_boundaries.py \
  tests/test_verify_ui_design_contract.py \
  tests/test_x86_64_emulator_gate.py

JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
ANDROID_HOME="$HOME/Library/Android/sdk" \
./gradlew :integrated-app:compileDebugKotlin --no-daemon --stacktrace
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

- Alpine Runtime/PRoot/Provider 안전 경계 및 automated regression은 rebase 후 다시 검증해야 한다.
- PRoot dynamic resize는 acceptance가 없는 한 `INITIAL_SIZE_ONLY` 제약을 유지한다.
- Provider inference는 idempotency 계약이 확정되기 전 자동 replay하지 않는다.

### 외부 조건 — 완료로 주장 금지

- 실제 Provider OAuth/API E2E 및 account/region/model 권한
- Samsung reboot/Doze/battery restriction/장시간 soak
- 실제 iPhone, Play Internal, TestFlight, signing
- current commit에 대한 GitHub Actions run evidence
- 라이선스/notice/legal/release owner 승인

## 8. 인계 확인 체크리스트

- [ ] `git status --short --branch`에서 기존 dirty state를 확인했다.
- [ ] WIP commit/branch를 만든 뒤에만 `origin/main` rebase를 시작했다.
- [ ] Alpine `origin/main`의 `8711104` 분리 상태를 기준으로 작업한다.
- [ ] Flutter 작업은 별도 `flutter_mobile_agent` clone에서 수행한다.
- [ ] rebase 후 자동 검증과 Android compile을 재실행했다.
- [ ] 외부 조건을 로컬 PASS 또는 제품 출시 완료로 과장하지 않았다.
