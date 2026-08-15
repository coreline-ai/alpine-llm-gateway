# Codex App Server 구현 완료 감사 — 2026-08-15

기준 계획: [`dev-plan/implement_20260815_072222.md`](../dev-plan/implement_20260815_072222.md) Revision 3
감사 시작 base: `fc8b483416728579e69f5b914c32e7c395f7cfec`
검증 release source: `a4ccd4ff56338511a63ff75734d53695e285e5e5`
최신 문서 기준 commit: `f26931a39bae4083cd6692d919d9c281fa214a5c`
대상 기기: Samsung `SM-S931N`, Android 16/API 36, `arm64-v8a`, `PAGE_SIZE=4096`
기술 감사 시각: `2026-08-15 13:31 KST`
최신 제품 상태 반영: `2026-08-15 20:04 KST`

이 문서는 “구현을 더 하면 닫을 수 있는 항목”과 “계정·기기·법무·배포 입력 없이는 닫을 수 없는 항목”을
분리한 완료 감사 기록이다. 미실행 항목을 PASS로 바꾸지 않으며, 로그인 계정·OAuth URL/query·token·prompt·
response·auth 파일 내용은 기록하지 않는다.

> **최신 상태 정정:** 소유자는 GUI/UX 품질이 배포 기준에 미달한다고 판단해 현재 배포 계획을
> 철회했다. 현재 상태는 `INTERNAL_DEVELOPMENT_ONLY` / `NO_DEPLOYMENT_PLANNED`이며, 아래 기술 PASS는
> 배포 승인으로 해석하지 않는다. 과거 `PROCEED_CURRENT_STATE` 기록은 역사적 증거로만 보존한다.

## 1. 결론

| 범위 | 판정 | 근거 |
|---|---|---|
| 공식 App Server 기반 ChatGPT 로그인 구현 | `PASS` | 공식 callback, account/model, refresh, 재시작 후 로그인 유지 |
| Codex Agent 채팅·Stop·lifecycle | `PASS` | Samsung 2-turn, interrupt, no-replay, recreation, Runtime 격리 |
| 기존 Provider/Alpine/direct 회귀 방지 | `PASS` | 최신 확장 ON/OFF 각각 `1821/1821`, Python `140/140`, pytest `147/147` |
| APK/AAB 정적 배포 artifact | `PASS` | exact binary, ELF/zipalign, 동일 signer, Samsung AAB split update/실행 |
| Phase 7 최종 account E2E report | `IN_PROGRESS` | 실제 logout/data isolation과 feature-OFF 실기기 rollback은 명시 승인 필요 |
| ARM64 16 KiB 실제 실행 | `BLOCKED` | 현재 Samsung은 4 KiB이며 대체 가능한 16 KiB 환경이 없음 |
| Codex 법무/provenance | `BLOCKED` | upstream tag unsigned, reviewer와 approval reference 없음 |
| 전체 제품 공개 배포 결정 | `WITHDRAWN / ON_HOLD` | 최신 소유자 지시: GUI/UX 개선 전 배포 예정 없음 |
| signed artifact | `INTERNAL_EVIDENCE_ONLY` | signed APK/AAB와 private GitHub Release가 존재하지만 배포 의사와 분리해 보존 |

현재 환경에서 비파괴적으로 구현·검증 가능한 범위는 완료됐다. 남은 항목은 실패 원인을 모르는 대기 상태가
아니라, 아래의 명시적인 상태 변경 승인 또는 외부 입력을 요구하는 Gate다.

## 2. 요구사항별 감사

| 요구사항 | 상태 | 구현·검증 증거 |
|---|---|---|
| 앱 소유 OpenAI OAuth 등록 없이 ChatGPT 로그인 | `PASS` | OpenAI Codex App Server `0.147.0`이 browser login/callback/token refresh 소유 |
| Android가 token/auth 파일을 읽지 않음 | `PASS` | noBackup `CODEX_HOME`, source/artifact OAuth scan `75 files` PASS |
| pinned ARM64 binary/schema/provenance lock | `ENGINEERING PASS` | binary/schema/source SHA lock, exact APK/AAB verifier PASS; 법무 승인은 별도 BLOCKED |
| Android direct stdio process | `PASS` | initialize/account/model round trip와 child singleton/orphan audit PASS |
| Android DNS/TLS 호환 | `PASS` | system CA 전용 PEM + 인증된 loopback CONNECT DNS bridge 적용 후 공식 callback PASS |
| bounded JSONL/RPC | `PASS` | malformed/oversized/id/order/EOF/timeout/writer/close race unit suite PASS |
| managed auth state | `PASS` | signed-out/pending/signed-in/timeout/cancel/restart/refresh 계약과 실기기 확인 |
| synthetic Codex Agent | `PASS` | ProviderProfile schema 변경 없이 별도 FAST_CHAT connection 제공 |
| multi-turn/stream/Stop | `PASS` | Samsung 2-turn, Stop/interrupt-once, next-turn no-replay PASS |
| unsafe agent action 차단 | `PASS` | tool/MCP/action/unknown current-turn event fail-closed; safe metadata만 output 없이 무시 |
| Runtime 격리 | `PASS` | Codex lifecycle 전후 Alpine Runtime state 동일 |
| feature OFF rollback artifact | `LOCAL PASS` | 동일 package/signer, binary/UI/runtime 부재 test와 최신 확장 `1821/1821` 회귀 PASS |
| feature OFF 실기기 rollback | `APPROVAL_REQUIRED` | logout 뒤 실행해야 하며 앱을 OFF·signed-out 상태로 남김 |
| Samsung AAB delivery | `PASS` | 4 split 설치, download `111,029,355` bytes, installed `156,006,761` bytes, login/data 유지 |
| ARM64 16 KiB | `STATIC PASS / DEVICE BLOCKED` | ELF `[65536,65536]`, zipalign PASS; `PAGE_SIZE=16384` 실행 환경 없음 |
| 전체 제품 공개 배포 | `ON_HOLD` | GUI/UX 품질 개선 전 배포·Play 등록·추가 publication을 진행하지 않음 |

## 3. callback 미전달 원인과 수정

초기 OAuth callback 미완료는 사용자 입력 대기가 원인이 아니었다.

1. pinned static Linux ARM64 binary가 Android system CA bundle 경로를 발견하지 못했다.
2. 일반 Linux의 `/etc/resolv.conf` 대신 Android netd DNS를 사용해야 했다.
3. OAuth 완료 Chrome tab이 다른 앱 뒤에 있어 localhost callback 재개가 지연됐다.

다음과 같이 수정하고 재검증했다.

- Android system CA만 app-private PEM으로 작성하고 `CODEX_CA_CERTIFICATE`/`SSL_CERT_FILE`로 전달
- ephemeral Basic credential, official OpenAI/ChatGPT domain의 443만 허용하는 loopback CONNECT bridge 추가
- private/loopback/link-local destination, 과대 header, 무제한 연결·timeout을 fail-closed
- TLS terminate/inspect와 raw URL/query/credential logging 금지
- 완료 Chrome tab 전면 복귀 후 공식 callback, `account/read=CHATGPT`, model list 확인

## 4. 자동 회귀와 artifact 근거

| 검증 | 결과 |
|---|---|
| Codex ON unit/APK/AndroidTest/lint/AAB | 최신 확장 `1821/1821 PASS`; 기존 focused `737/737 PASS` |
| same-package feature OFF rollback | 최신 확장 `1821/1821 PASS`; 기존 focused `729/729 PASS` |
| Python unittest | `140/140 PASS` |
| pytest | `147/147 PASS` |
| OAuth forbidden material scan | `PASS (75 files)` |
| Codex app-owned `client_id`/`client_secret` | production Codex modules/integrated main `0건` |
| ON APK/AAB exact binary | `PASS` |
| rollback binary forbidden | `PASS` |
| ON/OFF APK 16 KiB zipalign | `PASS` |
| ON/OFF signer match | `PASS` |
| Samsung 기본 비파괴 runner | `PASS` |
| Samsung 200% font/Korean IME/Back/accessibility | `OK (3 tests)`, persistent store mutation 없음 |
| Samsung AAB split account/model/refresh | `OK (2 tests)` |
| Samsung AAB split turn/Stop/restart/lifecycle | `OK (4 tests)` |

### 2026-08-15 13:31 KST 현재 환경 전수 재검증

- default feature-OFF release `595/595` PASS, Codex binary 부재 PASS
- 첫 unlimited-worker ON 실행의 AndroidTest 패키징 일시 실패는 해당 task 단독 `47/47` PASS와
  `--max-workers=2` ON/OFF 각 `1821/1821` PASS로 닫음; release script 기본 worker도 `2`
- feature ON release와 feature ON+pack OFF 조합은 의도대로 fail-closed
- SDK publication `19/19`, published consumer `8/8`, Gradle 9 warning `0` PASS
- Samsung runner에 UI/accessibility를 포함한 비파괴 경로 전체 PASS
- Samsung을 최종 AAB 4 split으로 복원한 뒤 account/model/refresh `OK (2 tests)` 및
  turn/Stop/restart/lifecycle/UI `OK (7 tests)` PASS
- 설치 split `4`, 합계 `156,006,761` bytes, 로그인 유지, 최종 child `1`/전체 `1`
- 현재 `/data` 여유 `159,164,616 KiB`; 저용량 조건 조작은 하지 않아 `NOT_RUN`
- logout/data isolation·feature-OFF 실기기 설치는 별도 명시 승인 없이는 실행하지 않아 로그인/ON 상태를 보존
- 이후 release/publication 검증 확장에서 Python unittest `148/148`, pytest `155/155`, release source CI
  run `31866945292`, publication evidence CI run `31875767445`가 PASS했다.

최신 artifact SHA-256은 [`HANDOFF.md`](../HANDOFF.md)의 “최종 로컬 검증”을 정본으로 사용한다.

## 5. 잔여 승인·외부 입력과 최신 완료 근거

| 항목 | 필요한 입력 | 이유 |
|---|---|---|
| logout/data isolation | 사용자 명시 승인 | 현재 공식 로그인을 삭제하며 되돌리려면 다시 browser login이 필요 |
| feature-OFF 실기기 rollback | logout 승인 + rollback 승인 | 동일 package를 OFF로 교체하고 완료 뒤 앱을 OFF 상태로 남김 |
| process kill/Doze/network loss/force-stop/저용량 | 별도 파괴 테스트 승인 | 앱·기기 상태를 강제로 변경하므로 비파괴 범위 밖 |
| ARM64 16 KiB E2E | `PAGE_SIZE=16384` Samsung/RTL/ARM64 환경 | 4 KiB 결과와 정적 alignment로 대체할 수 없음 |
| legal/provenance | reviewer, 시각, approval reference, unsigned-tag exception 결정 | engineering NOTICE/SBOM만으로 재배포 승인이 아님 |
| 최종 CI/evidence link | `main@f26931a`, run `31875767445` | publication evidence commit의 원격 CI success 확인 |
| 추가 외부 upload | 새로운 소유자 배포 재개 지시 | 현재는 Play/GitHub 추가 배포·재게시를 진행하지 않음 |

`x86_64` emulator는 arm64-only 제품 필수 조건이 아니다. 16 KiB는 ARM64 `PAGE_SIZE=16384` 환경으로
검증해야 한다.

## 6. 배포 재개 시에만 사용할 명령과 순서

> 현재는 배포 재개 지시가 없으므로 이 절의 release/upload 명령을 실행하지 않는다.

1. logout과 OFF rollback 상태 변경을 승인한 경우에만 다음을 실행한다.

   ```bash
   scripts/run-codex-appserver-samsung-e2e.sh \
     --approve-account-logout \
     --approve-feature-off-rollback
   ```

2. 18개 core check와 미승인 destructive 4개 `NOT_RUN`을 redacted report에 기록하고 검증한다.

   ```bash
   python3.11 scripts/verify-codex-appserver-e2e-report.py \
     /path/to/report.json --require-executed
   ```

3. 별도 ARM64 16 KiB 환경에서 install/login/stream/Stop/logout/AAB/update/rollback을 실행하고 16 KiB
   report verifier를 통과시킨다.
4. legal status를 승인한 reviewer가 `distribution/codex-appserver-legal-status.json`을 채우고
   `--require-approved` verifier를 통과시킨다.
5. 최종 commit/CI/evidence link를 고정하고, 현재 상태 소유자 결정 경로와 증거 readiness를 별도 판정한다.

과거 current-state artifact 재현 명령(현재 실행 보류):

```bash
scripts/build-current-state-public-release.sh --unsigned-candidate
```

실제 서명 build는 `ALPINE_RELEASE_KEYSTORE`, `ALPINE_RELEASE_KEY_ALIAS`,
`ALPINE_RELEASE_STORE_PASSWORD`, `ALPINE_RELEASE_KEY_PASSWORD`를 secure local environment로 주입한 뒤
동일 script를 `--unsigned-candidate` 없이 실행한다. debug key는 공개 배포 키로 대체 사용하지 않는다.

## 7. 최종 판정

- 구현 결함 때문에 멈춘 항목: `없음`
- 현재 환경 비파괴 구현·검증: `완료`
- 2026-08-15 13:31 KST live 상태: Samsung 연결, 4개 AAB split 설치, 로그인 유지, 앱 실행 중, App Server owned
  child `1`/전체 child `1`
- 상태 변경 승인 대기: `logout/data isolation`, `feature-OFF 실기기 rollback`, 파괴 테스트
- 외부 환경·조직 증거 대기: ARM64 16 KiB, legal/provenance
- 제품 단계: `INTERNAL_DEVELOPMENT_ONLY`
- 배포 계획: `NO_DEPLOYMENT_PLANNED`
- 배포 보류 사유: GUI/UX 품질 미달
- 기존 signed artifact/private GitHub Release: `INTERNAL_EVIDENCE_ONLY`
- 다음 우선순위: GUI/UX 감사·재설계·Samsung 실기기 사용성/접근성 QA
