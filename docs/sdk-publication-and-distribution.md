# SDK publication과 배포 가이드

## 발행 대상

`dev.alpine.llm` group 아래 19개 재사용 artifact를 발행한다. 앱 모듈과 probe는
발행하지 않는다. 각 artifact에는 AAR/JAR, sources JAR, POM, Gradle module metadata와 Gradle이
생성한 checksum sidecar가 있다.

공통 채팅 UI와 상태는 `dev.alpine.llm:alpine-chat-feature:0.3.0`으로 발행한다. 이 AAR의
유일한 project dependency는 `alpine-chat-routing`이며 Provider/OAuth 구현과 Runtime payload는
포함하지 않는다.

Android OAuth Provider 조립은 `dev.alpine.llm:alpine-chat-provider-android:0.3.0`으로
별도 발행한다. 이 AAR은 `alpine-chat-feature`와 `alpine-llm-android`만 project dependency로
가지며 rootfs, PRoot, PTY와 Python Gateway payload를 포함하지 않는다.

```bash
./gradlew publishPhase7Artifacts
python3 scripts/verify-sdk-publication.py
```

로컬 repository는 `build/maven-repo/`에 생성된다. 검증기는 project dependency 좌표, sources,
optional payload owner, consumer metadata와 PRoot/loader/PTY ELF 16 KiB alignment를 검사한다.

## 외부 소비자 matrix

`integration-fixtures/published-consumer`는 저장소의 `project(":...")`를 사용하지 않는다.

| 앱 | 포함 범위 | 없어야 하는 payload |
|---|---|---|
| `no-runtime` | Android OAuth/Provider + 공통 Chat Feature | rootfs, PRoot, PTY, Python Gateway |
| `runtime-only` | runtime+bundled Alpine | Python Gateway |
| `runtime-ui` | runtime+Compose UI | Python Gateway |
| `runtime-llm` | runtime+Host Bridge+Gateway | Compose UI 직접 의존 |
| `full` | runtime+UI+LLM+두 모드 backend | 없음 |
| `runtime-background` | FGS·WorkManager adapter만 | 모든 runtime binary payload |
| `runtime-play-workspace` | Play asset adapter+app-private workspace | 모든 bundled runtime payload |
| `runtime-x86_64` | x86_64 runtime+실험 pack | arm64 payload, Python Gateway |

제품 runtime 앱은 `arm64-v8a`, x86 fixture는 `x86_64`, 모든 앱은 minSdk 26과 release
R8/resource shrink로 빌드한다. fixture의 keep rule은 비어 있으므로 발행 artifact의 consumer
rule만으로 축소 빌드가 성공해야 한다.

## 내부 release bundle

```bash
./scripts/release-local.sh
```

결과 `dist/alpine-sdk-0.3.0/`에는 Maven repository, lock/SBOM, Alpine package license inventory,
통합 문서, 검증 report와 전체 `SHA256SUMS`가 포함된다.

## Sideload와 Play 배포

| 방식 | 권장 artifact 공급 | 주의점 |
|---|---|---|
| 내부 sideload APK | `alpine-runtime-pack-bundled` | offline 가능, APK 크기 증가, arm64 제품 지원 |
| Play AAB | `alpine-runtime-artifact-play` | rootfs/layer만 asset pack, native executable은 base APK JNI 유지 |
| 사내 signed download | `SignedDownloadRuntimeArtifactProvider` | Ed25519 manifest, TLS, rollback·offline cache 정책 필요 |

PRoot executable을 writable app home으로 복사해 실행하지 않는다. bundled 방식은
`ApplicationInfo.nativeLibraryDir`, `useLegacyPackaging=true`와 선택한 ABI filter를 유지한다.

`alpine-runtime-pack-x86_64`는 checksum/SBOM/ELF 16 KiB와 외부 consumer build까지만 검증된
실험 artifact다. `scripts/runtime/run-x86_64-emulator-gate.sh` 결과가 `PASSED`가 되기 전에는
지원 ABI 목록에 포함하지 않는다. Play Asset Delivery 전체 E2E는 signed AAB와 Play test track이
필요하므로 로컬 fake state test와 분리한다.

## 공개 배포 gate

현재 bundle의 `manifest.json`은 `distribution_mode=INTERNAL_ONLY`,
`internal_distribution_ready=true`, `external_distribution_ready=false`다.

`scripts/build-oss-source-bundle.py`는 PRoot/talloc native source artifact를 별도로 만들며,
`scripts/package-sdk-release.py`는 검증된 archive가 있으면 `oss-sources/native/`에 연결한다.
arm64/x86_64 Alpine package inventory도 각각 포함된다. native source 생성 성공만으로 rootfs package
source mirror나 프로젝트 라이선스 gate가 자동 완료되지는 않는다.

1. 저장소 소유자가 프로젝트 전체 라이선스를 명시한다.
2. 고정 PRoot/talloc native source와 Alpine package-level source mirror를 release에 연결한다.
3. 실제 Provider 계정 E2E는 자격 증명을 추출하지 않는 opt-in 절차로 승인·실행한다.
4. Maven/App Store 배포 위치와 서명 key owner를 확정한다.

이 문서는 법률 자문이 아니며 공개 배포 전 담당 검토가 필요하다.
