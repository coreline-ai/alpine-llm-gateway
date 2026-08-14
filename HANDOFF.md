# Alpine LLM Gateway — 현재 작업 핸드오프

갱신: `2026-08-14 KST`
작업 트리: `/Volumes/Eprojects/project_202607/alpine-llm-gateway`

이 문서는 다른 작업자/에이전트가 현재 개발을 안전하게 이어받기 위한 **현재 상태 정본**이다.
과거 계획·handoff 안의 `107ef9e`, `3389fcb`, `NOT_CONFIRMED` 표기는 historical record이며,
현재 상태와 충돌할 때는 이 문서와
[`dev-plan/implement_20260814_190004.md`](dev-plan/implement_20260814_190004.md)를 우선한다.

## 1. Git·CI 기준선

| 항목 | 현재 값 |
|---|---|
| 원격 기준선 | `main@493546fd8efdfee61b04a2d16ad0fd1ef6384c12` |
| 로컬 기준선 | `main@493546fd8efdfee61b04a2d16ad0fd1ef6384c12` |
| 통합 source branch | `codex/model-catalog-hardening-20260814` |
| main CI | run [`31796454558`](https://github.com/coreline-ai/alpine-llm-gateway/actions/runs/31796454558), `completed/success` |
| topic CI | run [`31795327701`](https://github.com/coreline-ai/alpine-llm-gateway/actions/runs/31795327701), `completed/success` |
| 현재 evidence 갱신 | commit·push 후 current-head CI 전까지 fail-closed |
| 공개 배포 | `NO-GO` — CI 외 6개 release-blocking gate가 계속 `BLOCKED` |

run `31796454558`은 `2026-08-14 20:30:01 KST`에 시작해 `20:43:00 KST`에 완료됐다.
`Python 3.11`과 `Android modules` job이 모두 성공했다. 상세 범위와 선행 topic CI는
[`distribution/GITHUB_CI_STATUS.md`](distribution/GITHUB_CI_STATUS.md)에 있다.

## 2. 현재 개발 목표

실행 계획: [`dev-plan/implement_20260814_190004.md`](dev-plan/implement_20260814_190004.md)

현재 환경에서 먼저 끝낼 범위:

1. 기준선·CI·Provider 상태 문서 정합성
2. profile-owned 영속 모델 카탈로그와 legacy migration
3. Provider 편집 UI의 모델 추가·기본 선택·활성/비활성
4. enabled 모델 descriptor와 stale session 제거
5. unavailable 모델의 `no-silent-switch`, Send/Retry 차단
6. 로컬 전체 검증과 credential-free Samsung UI 회귀

외부 조건 때문에 후속으로 남기는 범위:

- 앱 소유 registration을 사용한 Provider 공식 login/stream/refresh/logout E2E
- Samsung reboot·Doze·process kill·network·disk-full·soak
- x86_64 emulator와 PRoot guest `SIGWINCH`/full-screen TUI
- 프로젝트 라이선스, exact corresponding source, Play track, release destination

## 3. 현재 구현 상태

### 기준선에 이미 포함된 구현

- `b81a7d8`까지 Codex/xAI/Claude compatibility가 `integrated-app/src/debug`에만 격리돼 있다.
- Codex와 xAI는 Samsung `R3CY40PXCAP`의 side-by-side debug 앱에서 OAuth 연결과 1턴
  streaming 성공 evidence가 있다.
- 이는 debug compatibility evidence이며 앱 소유 registration 기반 공식 product E2E는 아니다.
- Claude는 debug compatibility code/unit/build까지만 확인됐고 실계정 E2E는 `NOT_RUN`이다.
- compact chat context UI는 구현돼 있으나 이번 branch에서 unavailable-model 회귀를 추가 검증한다.

### `main@493546f`에 반영된 구현

- `ProviderModelCandidate`와 `PROVIDER_APPROVED`/`USER_ADDED`/`LEGACY_MIGRATED` source를 추가했다.
- `ProviderProfile` JSON에 model catalog를 저장하고 catalog 없는 legacy profile을 migration한다.
- blank·case-insensitive duplicate·malformed candidate를 정규화하고 default/enabled 계약을 검증한다.
- Provider 편집 화면에 후보 추가, default 선택, 비기본 후보 활성/비활성, entitlement 주의 문구를 추가했다.
- `ChatBackendDescriptor`에는 enabled 후보만 노출하고 refresh 시 disabled/deleted session cache를 제거한다.
- session cache key에는 model과 OAuth/transport 설정을 포함하되 catalog metadata는 제외해, catalog 편집은
  session을 유지하고 endpoint/client/scopes 변경은 stale session을 재사용하지 않게 했다.
- 저장 대화의 unavailable model ID를 보존하고 명시적 재선택 전 Send·Retry를 차단한다.
- unavailable 안내 UI와 단위/Compose/integrated AndroidTest 회귀를 추가했다.

Phase 1~5 로컬/Samsung 검증과 topic/main 원격 CI는 완료했다. 현재 evidence 문서 갱신 commit 자체의
CI를 확인하기 전까지 `github_remote_ci`만 fail-closed `BLOCKED`로 유지한다.

## 4. Provider evidence 구분

| Provider | 로컬 adapter/unit | debug compatibility Samsung | 공식 product E2E |
|---|---|---|---|
| Codex / OpenAI Responses | `PASS` | OAuth 연결 + 1턴 stream `PASS` | `NOT_RUN` |
| xAI / Grok | `PASS` | OAuth 연결 + 1턴 stream `PASS` | `NOT_RUN` |
| Claude / Anthropic | `PASS` | code/build만 확인, 계정 E2E `NOT_RUN` | `NOT_RUN` |
| Gemini | `PASS` | 이번 branch에서 실행하지 않음 | `NOT_RUN` |
| OpenAI-compatible | 공통 adapter 있음 | `NOT_RUN` | `NOT_RUN` |

모델 후보는 계정·region·tier·preview lifecycle 권한의 증명이 아니다. debug compatibility 값을
production source, release artifact, public support 표기로 이동하지 않는다.

## 5. 보안·작업 경계

- OAuth token, authorization code, callback query, PKCE state/verifier, Authorization header,
  Provider raw body, prompt, account 식별자를 source·log·fixture·handoff에 기록하지 않는다.
- 다른 앱/공식 CLI의 client ID·scope·fingerprint·endpoint를 production source로 이동하지 않는다.
- inference 실패, Retry target, 대화 기록을 자동 replay하거나 다른 model/backend로 자동 전송하지 않는다.
- profile/model catalog 변경은 OAuth identity 변경이 아니며 token 삭제·재로그인을 유발하지 않는다.
- MobileAgent, MobileAgent BFF, dev IdP를 이 저장소에 다시 추가하지 않는다.
- PRoot terminal public contract는 계속 `INITIAL_SIZE_ONLY`다.

## 6. Samsung 고정 규칙

- 실제 기기 대상은 Samsung `SM-S931N`, serial `R3CY40PXCAP` 하나로 고정한다.
- 모든 ADB/Gradle connected 명령은 `ANDROID_SERIAL=R3CY40PXCAP` 또는 `adb -s R3CY40PXCAP`을 명시한다.
- 다른 연결 기기는 사용하지 않는다.
- 승인 없이 `pm clear`, uninstall, 계정 삭제, reboot, Doze 강제, 네트워크 차단을 실행하지 않는다.
- 현재 계획의 기기 범위는 `install -r`과 fake/credential-free profile·model UI 회귀뿐이다.

## 7. 이번 branch 검증 결과

| 범위 | 결과 |
|---|---|
| Python 전체 | `114/114 PASS` |
| UI design contract | `PASS` |
| production/debug OAuth artifact scan | 각각 `PASS` |
| Android 계획 matrix | unit·routing/backend·integrated debug APK·AndroidTest APK·lint `PASS` |
| SDK publication | `19개 PASS` |
| published consumer matrix | `8개 PASS` |
| license/readiness verifier | `PASS`; 공개 배포는 의도대로 `INTERNAL_ONLY`; evidence CI 전 7, 이후에도 외부 blocker 6개 |
| Samsung Provider test package | `18/18 PASS` — catalog UI, encrypted restore, unavailable model, IME·200% font 포함 |
| Samsung integrated debug `install -r` | `NOT_RUN/BLOCKED` — 기존 package와 signing signature 불일치 |
| integrated connected test | `NOT_RUN` — 기존 OAuth/profile/대화 데이터 보호를 위해 uninstall/clear하지 않음 |

Samsung 결과는 `R3CY40PXCAP`만 사용했다. 다른 연결 기기는 사용하지 않았다.

## 8. 다음 실행 순서

현재 바로 남은 것은 Phase 6 evidence 갱신 commit의 current-head 원격 CI와 gate 상태 정리다.

```bash
cd /Volumes/Eprojects/project_202607/alpine-llm-gateway

git status --short --branch
git rev-list --left-right --count HEAD...origin/main
gh run list --branch main --limit 3
```

evidence 문서를 commit/push한 뒤 GitHub Actions의 Python 3.11·Android modules를 확인한다.
그 CI 성공 후에만 `github_remote_ci` gate를 `READY`로 바꾸며 다른 외부 blocker 6개는 유지한다.

## 9. 인계 체크리스트

- [x] topic branch와 `main@493546f`를 동기화했다.
- [x] `dev-plan/implement_20260814_190004.md` 체크박스를 실제 결과와 맞췄다.
- [x] unit/AndroidTest APK compile 이후 전체 local matrix를 실행했다.
- [x] Samsung 작업은 `R3CY40PXCAP`에 credential-free·non-destructive 범위로만 실행했다.
- [x] topic/main의 동일 SHA 원격 CI를 모두 확인했다.
- [x] evidence 갱신 commit CI 전에는 `github_remote_ci`를 `READY`로 바꾸지 않았다.
- [x] 외부 Provider·법무·Play·파괴 테스트 항목을 `NOT_RUN/BLOCKED`로 유지했다.
