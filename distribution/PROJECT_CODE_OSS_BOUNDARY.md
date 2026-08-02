# 프로젝트 고유 코드와 OSS payload 경계

이 문서는 현재 기술적 분리 상태를 설명하며 프로젝트 코드에 라이선스를 부여하거나 법률 결론을
확정하지 않는다. 프로젝트 전체 라이선스는 저장소 소유자가 별도로 결정해야 한다.

## 프로젝트 고유 코드 후보

- `alpine-runtime-api`, `alpine-runtime-android`, runtime host/UI/background adapter
- Provider/OAuth Android library, Python Gateway, HostBridge와 chat routing
- workspace API/Android adapter, demo와 통합 앱 UI
- 프로젝트 JNI library `alpine-runtime-pty`

위 코드는 PRoot/talloc을 JNI 또는 native library로 링크하지 않는다. PRoot executable은
`ProcessBuilder`, argument/environment, PTY/fd와 파일 경계를 통해 별도 process로 실행된다.
OpenMinis를 참고한 파일은 `compliance/code-provenance.json`에 별도 표시하며 파일 단위 검토가
완료되기 전에는 clean 구현 승인을 확정하지 않는다.

## 별도 OSS payload

- `alpine-runtime-pack-bundled`: arm64 PRoot/loader/Alpine rootfs
- `alpine-runtime-pack-x86_64`: 실험 x86_64 PRoot/loader/Alpine rootfs
- PRoot source, talloc source, local patch와 native build/relink 자료
- Alpine rootfs package별 제3자 source와 license notice

fast-chat 모듈은 runtime pack에 의존하지 않는다. Alpine host/probe/integrated app만 명시적으로
runtime pack을 조합할 수 있으며 이 dependency 경계는 자동 테스트로 검사한다.

## 배포 artifact

| Artifact | 내용 | 현재 상태 |
|---|---|---|
| 앱/SDK binary | 프로젝트 고유 코드와 선택한 runtime pack | 내부 검증만 허용 |
| native OSS source | PRoot/talloc exact source, build input, patch, build script | 로컬 생성·결정적 재생성 검증 완료 |
| Alpine package source | rootfs package별 exact source/APKBUILD/patch | 미완료, 외부 배포 차단 |
| notice/SBOM | 선언 license, 결론 상태, checksum, source 위치 | 자동 정합성 검사 적용 |

## 향후 EULA 필수 예외

프로젝트 고유 코드에 proprietary EULA를 선택하더라도 다음 원칙을 포함해야 한다.

- EULA 제한은 별도 고지된 GPL/LGPL/기타 OSS 구성요소의 권리를 축소하지 않는다.
- OSS 구성요소에는 해당 원 라이선스가 우선 적용된다.
- 수령자는 제공된 source 위치, license text, modification/build 정보를 확인할 수 있다.
- 일괄적인 역공학·수정·재배포 금지가 OSS 권리까지 덮어쓰지 않는다.

최종 문구와 결합 저작물 판단은 외부 배포 전에 OSS 전문 검토자가 승인해야 한다.
