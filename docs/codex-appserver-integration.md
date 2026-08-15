# Codex App Server Android 통합

## 개요

`integrated-app`은 OpenAI 공식 Codex App Server `0.147.0`을 별도 synthetic backend인
`Codex Agent (ChatGPT 로그인)`으로 제공한다. 기존 Provider direct OAuth, ProviderProfile, Alpine Runtime은
변경하거나 공유하지 않는다.

- 기능 기본값: **OFF**
- 지원 ABI: `arm64-v8a`
- 지원 모드: `FAST_CHAT` 전용
- 인증 소유자: 공식 Codex App Server
- credential 위치: `noBackupFilesDir/codex-app-server`
- Android 코드의 credential 파일 read/copy/export/log: 금지
- transport: stable stdio JSONL
- workspace: app-private 빈 workspace, read-only, approval never

## 구성

| 경로 | 역할 |
|---|---|
| `alpine-codex-appserver-pack-android/` | pinned executable, schema, lock, LICENSE, NOTICE, SBOM |
| `alpine-codex-appserver-android/` | artifact preflight, direct process, bounded RPC, account/thread/turn API |
| `alpine-chat-backend-codex/` | conversation-thread mapping, delta, Stop/interrupt, unsafe event 차단 |
| `integrated-app/` | direct connection과 synthetic connection 병합, login/status UI |
| `scripts/verify-codex-appserver.py` | source/artifact/APK/AAB/security 정책 검사 |
| `scripts/verify-codex-appserver-e2e-report.py` | redacted Samsung E2E evidence 검사 |

## Artifact lock

| 항목 | 값 |
|---|---|
| npm package | `@openai/codex@0.147.0-linux-arm64` |
| source tag | `rust-v0.147.0` |
| source commit | `be6e8eac029b183056b7e4402879f15d2c85f61b` |
| binary SHA-256 | `e23d0be344d2496986c985cd3db61e6f649b1ddd900e6afc1b5aaabbffcbb4e2` |
| binary size | `222231296` bytes |
| schema SHA-256 | `f3dec1e031d99a420b137b903f02196d4325eece57620c925bb7130b25f168d2` |
| ELF | AArch64, PT_LOAD alignment `65536` |

다운로드된 executable은 `.codex-artifacts/`에만 두며 Git에 포함하지 않는다. runtime download나
`latest` resolution은 지원하지 않는다.

```bash
python3.11 scripts/import-codex-appserver-artifact.py /path/to/openai-codex-0.147.0-linux-arm64.tgz
python3.11 scripts/verify-codex-appserver.py \
  --binary .codex-artifacts/0.147.0/linux-arm64/codex
```

## Build와 rollback

기본 build는 Codex executable을 포함하지 않는다.

```bash
./gradlew :integrated-app:assembleDebug
python3.11 scripts/verify-codex-appserver.py \
  --archive integrated-app/build/outputs/apk/debug/integrated-app-debug.apk \
  --forbid-binary
```

승인된 내부 Samsung 검증 build만 명시적으로 기능을 켠다. debug ON package는 기존 debug app을 보존하기
위해 `dev.alpine.integrated.codexdebug`를 사용한다.

```bash
./gradlew :integrated-app:assembleDebug -PcodexAppServerEnabled=true
python3.11 scripts/verify-codex-appserver.py \
  --binary .codex-artifacts/0.147.0/linux-arm64/codex \
  --archive integrated-app/build/outputs/apk/debug/integrated-app-debug.apk \
  --expect-binary
```

현재 `codexAppServerEnabled=true`는 **debug 내부 검증 전용**이다. account E2E, 16 KiB device E2E,
배포 provenance/legal Gate가 READY가 되기 전에는 `preReleaseBuild`가 ON release APK/AAB 생성을 명시적으로
거부한다. 또한 feature ON과 executable pack OFF를 동시에 지정할 수 없어 실행 파일이 빠진 ON build도
구성 단계에서 실패한다.

기본 OFF build의 `dev.alpine.integrated.debug`는 기존 debug app과의 회귀 비교용 side-by-side package다.
실제 Codex 검증 package를 data clear 없이 되돌릴 때는 기능은 OFF지만 ON build와 같은
`dev.alpine.integrated.codexdebug` application id를 갖는 전용 rollback APK를 빌드한다.

```bash
./gradlew \
  :integrated-app:testDebugUnitTest \
  :integrated-app:assembleDebug \
  -PcodexAppServerRollbackBuild=true
python3.11 scripts/verify-codex-appserver.py \
  --archive integrated-app/build/outputs/apk/debug/integrated-app-debug.apk \
  --forbid-binary
adb -s R3CY40PXCAP install -r integrated-app/build/outputs/apk/debug/integrated-app-debug.apk
```

`codexAppServerEnabled`와 `codexAppServerRollbackBuild`는 동시에 켤 수 없다. rollback build는 Codex UI와
executable을 포함하지 않으며 동일 debug signing key/version의 ON APK 위에만 `install -r`한다. 기존
package를 uninstall하거나 data clear하지 않는다. 다른 signing key로 설치된 package는 Android가
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`로 거부하므로 파괴적으로 우회하지 않는다.

정상 rollback은 App Server logout과 signed-out 재확인을 먼저 끝낸 뒤 OFF APK를 설치한다. 장애로 logout이
불가능한 긴급 rollback은 `install -r` 특성상 app-private `noBackup` data를 보존한다. OFF Android 코드는
credential 파일을 읽지 않고 UI/process도 시작하지 않지만 credential purge가 필요한 경우에는 사용자 승인
뒤 app data 삭제 또는 uninstall이라는 별도 파괴 절차가 필요하다.

## Login 동작

1. 앱이 executable의 canonical path, 크기, SHA-256, 실행 가능 여부를 확인한다.
2. shell 없이 `app-server --listen stdio://`를 실행한다.
3. `initialize`와 `account/read`를 수행한다.
4. signed-out이면 browser login 또는 공식 device-code flow를 사용자가 선택한다.
5. browser URI는 HTTPS, exact official host, 기본/443 port, userinfo·fragment 없음 조건을 만족해야 한다.
6. 로그인 완료 notification 뒤 `account/read`와 `model/list`를 다시 확인한다.
7. token, account email/ID, auth URL query는 persistent state·evidence·log에 저장하지 않는다.

Android가 authorization activity를 열지 못하면 pending server login을 한 번 취소하고
`BROWSER_UNAVAILABLE`로 닫는다. device-code는 사용자가 명시적으로 선택할 때만 시작하며 자동 fallback하지
않는다. pending 화면의 “브라우저 다시 열기”도 동일한 fail-closed 경계를 사용한다.

Browser/device authorization URI와 user code는 사용자가 동작을 완료하는 동안 process memory에만 존재한다.
process-lifetime auth controller가 Activity recreation 사이에서 pending action을 보존하되 두 번째 login action을
자동 실행하지 않으며, 10분 timeout이면 server-side login을 한 번 취소하고 `REQUEST_TIMEOUT`으로 닫는다.
process death 뒤에는 pending action을 복원하거나 replay하지 않는다.

Pinned `0.147.0` source의 browser issuer는 `auth.openai.com`이다. protocol README 호환을 위해
`chatgpt.com`도 exact-host allowlist에 포함하지만 lookalike host, cleartext, userinfo, 임의 port, fragment는
거부한다.

## Android network compatibility

Pinned 정적 Linux ARM64 binary는 Android에서 일반 Linux CA bundle 경로와 `/etc/resolv.conf`를 발견하지
못한다. 실제 Samsung에서 process/initialize는 성공했지만 최초 OAuth callback 뒤 token exchange가 이 차이로
완료되지 않았다. 이를 임의 network fallback으로 숨기지 않고 다음 전용 bridge를 App Server process 수명에
필수로 연결한다.

- Android system CA 디렉터리만 X.509로 파싱하고 SHA-256으로 중복 제거·정렬해 app-private PEM을 원자적으로
  생성한다. user-added CA는 반입하지 않으며 파일 권한은 `0400`이다.
- 공식 source가 지원하는 `CODEX_CA_CERTIFICATE`와 `SSL_CERT_FILE`에 이 PEM만 전달한다.
- loopback-only ephemeral CONNECT proxy가 Android/Java resolver로 DNS를 수행한다. ephemeral Basic credential,
  최대 connection/header/timeouts를 강제한다.
- 목적지는 `openai.com`/`chatgpt.com`과 하위 도메인의 443만 허용하고 private/loopback/link-local/
  site-local/multicast/IPv6 ULA 결과는 거부한다.
- TLS는 terminate/inspect하지 않고 byte tunnel만 수행한다. proxy URL과 credential은 process memory/child
  environment에만 존재하며 log/evidence에 남기지 않는다.
- CA 생성 또는 bridge 시작 실패는 `TRUST_STORE_UNAVAILABLE`/`NETWORK_BRIDGE_FAILED`로 fail-closed하며
  Alpine/Provider/PRoot로 자동 전환하지 않는다.

## Chat 경계

- connection ID: `codex-agent-chatgpt`
- App Server에는 기존 chat history가 아니라 현재 user prompt만 전송한다.
- conversation별 opaque thread ID만 app-private preferences에 저장하며 최대 256개로 제한한다.
- 기존 mapping의 resume 실패 시 새 thread를 만들거나 history를 replay하지 않는다.
- agent message delta만 기존 chat delta로 변환한다.
- command/file/tool/approval/user-input event는 `UNSUPPORTED_AGENT_ACTION`으로 중단하고 interrupt를 1회 보낸다.
- server `model/rerouted`는 silent model switch하지 않고 `UNSUPPORTED_AGENT_ACTION`으로 중단한다.
- `contextCompaction`, stable reasoning/usage, thread status/name metadata는 chat text로 노출하지 않는다.
- 실제 정상 turn에서 관찰되는 `mcpServer/startupStatus/updated`, generic `warning`,
  `thread/goal/updated|cleared`는 text/params를 노출하지 않고 non-output metadata로 무시한다.
- 실제 MCP/tool item/action, hook/realtime/environment/settings 및 unknown current-turn event는 turnId 유무와
  관계없이 fail-closed한다.
- 사용 중인 response/notification 필드는 pinned schema의 JSON type을 exact 검증하며 문자열·숫자·boolean
  coercion을 허용하지 않는다.
- response는 result/error 중 정확히 하나만 허용하고, unsupported server request도 bounded string/integer ID와
  exact method type만 `-32601`로 거부한다. malformed request는 echo하지 않고 protocol을 닫는다.
- request timeout, writer/EOF, malformed protocol은 RPC channel과 child stdin을 terminal-close하며 다음 요청이나
  process reuse를 허용하지 않는다.
- Stop/coroutine cancellation은 backend failure로 변환하지 않고 active turn interrupt 1회와 join을 끝낸다.
- logout은 foreground/background의 모든 Codex generation만 stop/join한 뒤 실행하며 direct/Alpine generation은
  유지한다.
- automatic correction, model/backend switch, Alpine/direct fallback은 사용하지 않는다.

## 로컬 검증

```bash
python3.11 -m unittest discover -s tests -v
python3.11 -m pytest -q

./gradlew \
  :alpine-codex-appserver-pack-android:testDebugUnitTest \
  :alpine-codex-appserver-android:testDebugUnitTest \
  :alpine-chat-backend-codex:testDebugUnitTest \
  :alpine-chat-feature:testDebugUnitTest \
  :alpine-chat-provider-android:testDebugUnitTest \
  --rerun-tasks

./gradlew :integrated-app:lintDebug :integrated-app:assembleDebugAndroidTest
```

## Samsung E2E

실제 account 입력은 사용자가 Chrome에서 직접 수행한다. 도구와 evidence는 auth URL, query, token,
account identity, prompt를 수집하지 않는다.

기존 Chrome의 ChatGPT 웹 로그인이나 다른 package/Provider의 OAuth 상태는 현재 앱의 app-private
`CODEX_HOME` 로그인으로 간주하지 않는다. 공식 `account/read`가 `SIGNED_OUT`이면 App Server가 발급한
browser login을 새로 시작하고 사용자가 Chrome에서 계속/승인을 완료해야 한다. credential 파일을 읽거나
다른 앱/호스트에서 복사해 callback을 우회하지 않는다.

1. upgrade install
2. process start, initialize, account/read
3. browser login callback 또는 device code와 cancel/timeout
4. model list
5. first turn, second turn
6. Stop → interrupt 1회 → next turn, replay 없음
7. background/foreground, rotation/recreation, non-destructive cold start, controlled restart
8. official App Server token refresh와 Alpine Runtime/Codex process 격리
9. logout·signed-out 재확인과 다른 Provider/conversation/workspace 보존
10. 동일 package OFF APK `install -r` rollback, Codex UI/binary/process 부재 확인
11. report/log/screenshot redaction audit

Signed-in 카드의 `연결 다시 시작`은 controlled restart 전용이다. 동일 profile의 active Codex turn을
먼저 cancel/join해 interrupt cleanup을 끝낸 뒤 App Server process만 교체하고, 같은 app-private
`CODEX_HOME`에서 새 client/auth controller를 연결해 `account/read`와 model list를 다시 확인한다.
연속 탭은 단일 restart로 합쳐지며 direct Provider와 Alpine Runtime generation은 중단하지 않는다.
기존 child 종료는 graceful wait 후 forcible termination을 최대 두 번 확인한다. 제한 시간 뒤에도 OS
process가 살아 있으면 `PROCESS_TERMINATION_FAILED`로 실패하고 새 App Server를 시작하지 않으므로,
unverified orphan과 duplicate process를 정상 재연결로 숨기지 않는다.

결과는 `integration-fixtures/codex-appserver-e2e/report.template.json`을 복사해 redacted report로 작성한다.
실행 report의 18개 core check는 모두 `PASS`여야 한다. process kill, Doze, network loss, force-stop은
`destructive_tests_approved=false`일 때 4개 모두 `NOT_RUN`이어야 하며, 승인 없이 실행 결과를 만들 수 없다.

Samsung serial 고정과 실수 방지를 위해 다음 runner를 사용한다. 기본 실행은 로그인 상태를 유지하며 ON
artifact 검증, `install -r`, account/model/credential refresh, 2-turn/Stop/next-turn, restart/lifecycle/
Runtime isolation, process ownership과 persistent store를 사용하지 않는 UI·접근성 contract를 실행한다.
instrumentation stdout은 memory에서 PASS signature만
판정하고 raw output이나 account/prompt/response를 파일·화면에 출력하지 않는다.

```bash
scripts/run-codex-appserver-samsung-e2e.sh
```

logout/data isolation과 feature-OFF rollback은 별도 승인 flag 없이는 실행되지 않는다. logout test는 Provider
profile, Provider credential **availability state**, encrypted conversation directory, workspace digest와 Alpine
Runtime state가 전후 동일한지만 비교하며 값이나 파일명을 출력하지 않는다. rollback은 logout 승인과 rollback
승인을 모두 요구하며 완료 뒤 앱을 feature-OFF 상태로 남긴다.

```bash
scripts/run-codex-appserver-samsung-e2e.sh \
  --approve-account-logout \
  --approve-feature-off-rollback
```

runner는 uninstall, data clear, force-stop, reboot, Doze/network 변경을 수행하지 않는다. approval flag가 있어도
process kill/Doze/network loss/force-stop 4개 파괴 항목은 별도 범위다.

```bash
python3.11 scripts/verify-codex-appserver-e2e-report.py \
  distribution/evidence/codex-appserver-e2e.json \
  --require-executed
```

ARM64 16 KiB 검증은 4 KiB Samsung report로 대체하지 않는다. 별도의
`integration-fixtures/codex-appserver-16k/report.template.json`을 사용해 page size `16384`, ABI
`arm64-v8a`, APK/AAB 설치·크기·저용량·update·rollback 측정을 기록한다.

현재 4 KiB Samsung에서는 bundletool `1.18.3` device spec delivery를 별도로 검증했다. spec은
`arm64-v8a`/API 36/density 480이고, 최종 AAB의 download size는 `111,029,355` bytes, 설치된 4개 split의
APK 합계는 `156,006,761` bytes다. 동일 패키지/서명 split update 뒤 official account/model/refresh와 실제
2-turn/Stop/restart/lifecycle이 PASS해 데이터와 로그인이 유지됐다. 이 결과는 AAB delivery 근거지만 4 KiB
기기이므로 16 KiB report나 저용량 설치·feature-OFF rollback 근거를 대신하지 않는다.

```bash
python3.11 scripts/verify-codex-appserver-16k-report.py \
  distribution/evidence/codex-appserver-16k.json \
  --require-executed
```

readiness의 `codex_appserver_e2e`/`codex_appserver_16k` Gate는 각각 위 실제 report가 evidence에
연결되고 verifier가 `passed=true`를 반환해야만 `READY`를 허용한다. template나 문서 존재만으로는
READY가 되지 않는다.

legal/provenance Gate도 LICENSE/NOTICE/SBOM 파일의 존재만으로 READY가 되지 않는다.
`distribution/codex-appserver-legal-status.json`은 artifact lock의 version/source commit/tag object/
binary·schema·license·notice checksum과 정확히 일치해야 한다. 지정 reviewer, review 시각, approval
reference, binary redistribution·license/notice·SBOM·source provenance 결정과 unsigned tag exception이
모두 `APPROVED`인 경우에만 다음 명령과 readiness가 통과한다.

```bash
python3.11 scripts/verify-codex-appserver-legal-status.py --require-approved
```

```bash
python3.11 scripts/verify-codex-appserver-e2e-report.py /path/to/report.json --require-executed
```

process kill, Doze, network loss, force-stop, data clear는 별도 파괴 테스트 승인 전 실행하지 않는다.

## 현재 distribution Gate — 보류

`distribution/current-state-release-decision.json`의 `PROCEED_CURRENT_STATE`는 과거 시점 결정이다.
2026-08-15 최신 소유자 지시에 따라 현재 제품은 `INTERNAL_DEVELOPMENT_ONLY` /
`NO_DEPLOYMENT_PLANNED`이며 GUI/UX 개선 전 공개 배포, Play 등록, 신규 tag/Release를 진행하지 않는다.

- verifier의 `release_ready=false`, `NOT_RUN`, `BLOCKED` 증거를 그대로 유지한다.
- 과거 `distribution_authorized=true` 결과를 현재 배포 승인으로 해석하지 않는다.
- `scripts/build-current-state-public-release.sh`과 production signing 경로는 내부 재현 가능성만 보존하며
  별도 배포 재개 지시 전에는 실행하지 않는다.
- 이미 게시된 private `v0.3.0`과 signed artifact는 내부 검증 이력으로만 취급한다.

- local artifact/process와 Android CA/DNS bridge: 검증됨
- Samsung 공식 browser callback, `account/read=CHATGPT`, model list, explicit credential refresh: PASS
- Samsung 2-turn, Stop/next-turn no-replay, controlled restart, background/foreground, Activity recreation,
  Alpine Runtime state isolation과 process orphan audit: PASS
- Samsung 200% font action/Korean IME/system Back/accessibility: `OK (3 tests)`; persistent store mutation 없음,
  이후 official account/model/refresh와 child singleton 재확인 PASS
- Samsung browser timeout/server cancel-once: PASS; redacted report 증거 고정 pending
- Samsung logout/data isolation, same-package feature-OFF 실기기 rollback 및 최종 redacted 18-check report:
  pending
- 16 KiB ELF 정적 alignment: PASS
- 현재 Samsung `SM-S931N`: 4 KiB page이므로 16 KiB device E2E는 별도 환경 필요
- source tag: unsigned이므로 배포 provenance/legal 승인 필요
- SBOM/LICENSE/NOTICE: engineering artifact는 준비됐으나 최종 legal review 전 배포 승인 아님
- 다른 Provider, root license, corresponding source, Play track/destination Gate는 별도 BLOCKED 유지
- 2026-08-15 13:31 KST 재검증: ON/OFF 각각 `1821/1821`, default OFF release `595/595`, Samsung 기본
  비파괴 runner와 AAB split account `2 tests`/turn·lifecycle·UI `7 tests` PASS

## 공식 참조

- [OpenAI Codex App Server README](https://github.com/openai/codex/blob/main/codex-rs/app-server/README.md)
- [Pinned browser login server source](https://github.com/openai/codex/blob/be6e8eac029b183056b7e4402879f15d2c85f61b/codex-rs/login/src/server.rs)
- [Pinned custom CA source](https://github.com/openai/codex/blob/be6e8eac029b183056b7e4402879f15d2c85f61b/codex-rs/http-client/src/custom_ca.rs)
