# Alpine Runtime SDK 모듈 가이드

## 목적

Alpine 기능을 특정 채팅 앱에 고정하지 않고, 필요한 기능만 골라 어떤 Android 앱에도 조립할 수 있도록 모듈 경계를 정의한다. 검증형 artifact 설치와 PRoot process/session 구현은 Phase 3에서 독립 모듈로 이전되었다.

## 모듈 선택

| 모듈 | 용도 | Android/Compose 강제 여부 |
|---|---|---|
| `:alpine-runtime-api` | 상태, 설치, artifact, session, command, terminal, health 계약 | 없음 |
| `:alpine-runtime-android` | Android `Context`를 받는 runtime 생성 경계 | Android만 필요 |
| `:alpine-runtime-background-android` | 사용자 시작 작업용 FGS·알림 중지·process lease·WorkManager 점검 | 장시간 작업 시 선택 |
| `:alpine-runtime-artifact-play` | Play Asset Delivery에서 rootfs/layer를 받는 provider | Play AAB 공급 시 선택 |
| `:alpine-runtime-pack-bundled` | 검증된 rootfs·PRoot·loader와 SPDX SBOM 공급 | 오프라인 Alpine 사용 시 필요 |
| `:alpine-runtime-pack-x86_64` | checksum·SBOM이 고정된 x86_64 실험 pack | emulator 검증용, 제품 지원 아님 |
| `:alpine-runtime-host` | install/start/복구/터미널/패키지의 UI 중립 상태 제어 | Android/Compose 없음 |
| `:alpine-llm-bridge` | Host Bridge, capability, Python Gateway lifecycle과 Android LLM 연결 | Android LLM 선택 시 필요 |
| `:alpine-llm-gateway-pack-bundled` | 검증된 Python Gateway/`llmctl` layer 공급 | Alpine LLM 사용 시 선택 |
| `:alpine-runtime-ui-compose` | 선택형 Compose 상태·복구·터미널·패키지 UI | Compose 선택 시에만 필요 |
| `:alpine-runtime-testkit` | fake runtime, 결정적 dispatcher, virtual artifact | 테스트에서만 필요 |
| `:alpine-chat-routing` | 공통 request/stream/failure, mode, fallback, audit, request ledger 계약 | Android/Compose 없음 |
| `:alpine-chat-feature` | 다중 대화·암호화 저장·모델·Skill·Persona·생성 상태와 Compose 채팅 UI | Android/Compose 필요, Runtime 없음 |
| `:alpine-chat-provider-android` | OAuth Provider profile CRUD·계정 UI·직접 채팅 Host 조립 | Android/Compose 필요, Runtime 없음 |
| `:alpine-chat-backend-direct` | 기존 Android OAuth/Provider 빠른 채팅 adapter | Android LLM만 필요 |
| `:alpine-chat-backend-alpine` | Runtime/Bridge/Python Gateway 작업 모드 adapter | Alpine LLM 사용 시 필요 |
| `:alpine-workspace-api` | 안전 상대경로·quota·bounded 파일 작업 계약 | Android/Compose 없음 |
| `:alpine-workspace-android` | app-private, symlink 차단, atomic write 구현 | 파일 작업공간 사용 시 선택 |

모든 재사용 모듈은 `dev.alpine.llm:<artifact>:0.3.0` 좌표로 로컬 Maven repository에
발행된다. 예를 들어 자체 UI runtime 앱은 다음처럼 조합한다.

```kotlin
dependencies {
    implementation("dev.alpine.llm:alpine-runtime-android:0.3.0")
    implementation("dev.alpine.llm:alpine-runtime-host:0.3.0")
    implementation("dev.alpine.llm:alpine-runtime-pack-bundled:0.3.0")
}
```

저장소 source project를 참조하지 않는 실제 소비자 예제는
`integration-fixtures/published-consumer`의 8개 release app matrix다.

## 권장 조합

| 앱 요구사항 | 추가할 모듈 |
|---|---|
| 자체 UI로 Alpine 실행 | `api` + `host` + `android` + artifact provider |
| 오프라인 bundled Alpine 실행 | `api` + `android` + `pack-bundled` |
| 화면 밖 장시간 Alpine 작업 | 위 구성 + `runtime-background-android` |
| Play Asset Delivery rootfs | `api` + `android` + `runtime-artifact-play`; PRoot/loader는 base APK JNI에 유지 |
| app-private 파일 작업공간 | `workspace-api` + `workspace-android` |
| x86_64 emulator 실험 | `api` + `android` + `pack-x86_64`; device gate 통과 전 제품 지원 금지 |
| Alpine에서 Android LLM/OAuth 사용 | `api` + `android` + runtime artifact + `llm-bridge` + Gateway artifact |
| SDK 제공 Compose UI 사용 | 위 구성 + `host` + `ui-compose` |
| JVM 계약 테스트 | `api` + `testkit` |
| 자체 UI로 Android Provider 사용 | `:android` + 자체 profile/session Host |
| 공통 UI로 빠른 채팅 사용 | `:alpine-chat-feature` + `:alpine-chat-provider-android` |
| 빠른 채팅 공통 routing 사용 | 위 구성 + `:alpine-chat-backend-direct` |
| 두 모드 통합 | 위 구성 + runtime/bridge/Gateway artifact + `:alpine-chat-backend-alpine` |

## 공개 계약 원칙

- `:alpine-runtime-api`는 Android `Context`, Compose, OAuth, Provider 구현을 참조하지 않는다.
- 공개 비동기 API는 Java/Kotlin 공용 표준인 `CompletionStage`를 사용한다.
- 오류는 raw 예외 문자열 대신 `RuntimeErrorCode`로 노출한다.
- Host가 전달하는 환경 확장은 `RuntimeEnvironmentContributor`로 제한한다.
- artifact 공급은 `RuntimeArtifactProvider`로 분리하며 stream은 매 호출마다 다시 열 수 있어야 한다.
- signed-download 공급자는 `RuntimeArtifactManifestCanonicalizer`가 만든 바이트에 Ed25519
  서명하고, 전달된 bundle과 서명 대상이 정확히 일치해야 한다.
- raw OAuth token은 Linux 환경 변수로 전달하지 않는다. LLM bridge는 loopback URL과 TTL capability file 경로만 기여한다.
- fallback은 runtime 준비 실패에 한해 사용자 승인 전에만 가능하고 Provider dispatch 이후에는 금지한다.
- 현재 backend idempotency capability는 `NONE`이다. 검증되지 않은 Provider 요청을 router가 재전송하지 않는다.
- 패키지 설치는 exact allowlist와 명시적 승인을 모두 통과한 이름만 고정 `apk add` 명령으로 실행한다.
- 현재 PRoot는 최초 PTY 크기만 guest에 반영한다. `RuntimeTerminalSession.resizeSupport`가
  `INITIAL_SIZE_ONLY`이므로 Host는 동적 resize를 성공한 것처럼 표시하지 않는다.
- background adapter는 `START_NOT_STICKY`이며 부팅·process death 뒤 runtime을 자동 시작하지 않는다.
  SharedPreferences에는 lifecycle 상태와 시각만 기록하고 command, prompt, token을 저장하지 않는다.
- WorkManager는 stale transition 점검만 수행하며 사용자 작업이나 Alpine process를 시작·재실행하지 않는다.
- Play asset provider는 rootfs/auxiliary만 asset pack에서 열고 native launcher/loader는 항상 base APK의
  `nativeLibraryDir`에서 가져온다. 최종 설치기는 기존과 같이 크기와 SHA-256을 다시 검증한다.
- workspace adapter는 absolute/traversal/NUL/symlink 경로를 거부하며 bounded read/write와 같은 디렉터리
  atomic move를 사용한다.

## 현재 상태와 다음 단계

- `:alpine-runtime-android`가 원자적 install/rollback/repair/reset과 PRoot command session을 구현한다.
- `:alpine-runtime-pack-bundled`가 arm64 Alpine 3.21.3, PRoot, loader, lock과 SPDX SBOM을 공급한다.
- `:alpine-runtime-pack-x86_64`는 별도 rootfs/PRoot/loader lock과 16 KiB ELF gate를 제공하지만,
  현재 연결된 x86_64 emulator가 없어 상태는 `experimental_requires_emulator_e2e`다.
- 선택형 background, Play Asset Delivery와 workspace 모듈은 runtime core/빠른 채팅에 역의존하지 않는다.
- host-provided와 Ed25519 signed-download provider 경계가 분리되어 있다.
- `:demo-chatbot`과 `:integrated-app`은 동일 `:alpine-chat-feature`와
  `:alpine-chat-provider-android`를 조립하며 Provider 구현을 복사하지 않는다.
- `AlpineLlmBridgeController`가 Host Bridge, runtime session, Python Gateway의 start/health/stop/restart를 단일 소유한다.
- Python Gateway `0.3.0`/protocol `1` layer는 rootfs와 별도 checksum lock으로 공급된다.
- 삼성 `SM-S931N` API 36에서 `llmctl models/run/stream/cancel`, token 회전과 process cleanup을 통과했다.
- Phase 5 공통 router가 빠른 채팅/Alpine 작업 선택, 승인 fallback, request ledger와 closed audit를 구현한다.
- 기존 conversation schema는 실행 모드가 없으면 `FAST_CHAT`으로 migration하며 새 데이터는 대화별 mode를 저장한다.
- Phase 6에서 Android-free `RuntimeHostController`, 상태·복구·터미널·패키지 Compose UI,
  Compose 없는 XML sample과 `:integrated-app`의 명시적 mode selector를 연결했다.
- 통합 제품 Phase 2에서 `:integrated-app`의 빠른 채팅을 실제 Provider 계정 UI·모델 선택·
  stream/Stop/retry·대화 복원에 연결했다. Alpine Gateway 채팅 결합은 Phase 3 범위다.
- Host lifecycle/service/notification/manifest/저장소 지침은
  [Host 통합 가이드](alpine-runtime-host-integration.md)를 따른다.
- 현재 19개 AAR/JAR의 sources/POM/Gradle metadata/checksum, 8개 외부 release 축소 앱,
  payload·permission·arm64/x86_64 ABI 분리를 자동 검증한다. 공개 배포 전 프로젝트 라이선스와 copyleft
  source archive gate는 [배포 가이드](sdk-publication-and-distribution.md)를 따른다.
