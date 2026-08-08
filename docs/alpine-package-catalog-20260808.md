# Alpine package catalog snapshot — 2026-08-08

`dev.alpine.integrated`의 Alpine 작업 모드가 package 설치 승인 전에 표시하는 정보의 기준 문서다.

## 성격

- 기준 Runtime: bundled Alpine `3.21.3`, repository branch `v3.21`, architecture `aarch64`
- 확인 시점: `2026-08-08 KST`
- 입력: Alpine 공식 `main` 및 `community` aarch64 `APKINDEX.tar.gz`
- 용도: 선택된 **직접 package archive/payload**의 license·download·installed-size 안내
- 비용: metadata는 install policy나 command 생성에 절대 사용하지 않는다. 실행 권한은 `RuntimePackageAllowlistPolicy`와 사용자의 명시적 승인만 결정한다.

## 표시 값

| 요청 이름 | snapshot resolved package | version | license | download bytes | installed payload bytes | repository |
|---|---|---|---|---:|---:|---|
| `git` | `git` | `2.47.3-r0` | `GPL-2.0-only` | 3,414,900 | 6,997,971 | main |
| `python3` | `python3` | `3.12.13-r0` | `PSF-2.0` | 8,078,719 | 26,338,596 | main |
| `py3-pip` | `py3-pip` | `24.3.1-r0` | `MIT` | 1,695,108 | 5,656,209 | community |
| `curl` | `curl` | `8.14.1-r2` | `curl` | 165,197 | 274,878 | main |
| `openssh-client` | `openssh-client-default` | `9.9_p2-r0` | `SSH-OpenSSH` | 367,738 | 854,664 | main |
| `nodejs` | `nodejs` | `22.23.2-r0` | `MIT` | 17,436,759 | 48,276,512 | main |
| `npm` | `npm` | `10.9.1-r0` | `Artistic-2.0` | 2,184,455 | 7,933,969 | community |

`openssh-client`는 virtual selection이며 이 snapshot에서 선택되는 provider를 `openssh-client-default`로 표시한다. 실제 repository의 provider 정책은 이후 변경될 수 있다.

## 사용자가 보는 한계

다음은 snapshot 합계에 포함하지 않는다.

- 현재 repository dependency solver가 결정하는 transitive dependency
- repository index의 다운로드와 cache
- filesystem block/metadata 여유 공간
- 이미 설치된 package 상태 및 update/remove에 따른 의존성 정리
- repository의 이후 version, license expression, provider 변경

따라서 값은 설치를 허용하거나 성공을 보장하는 preflight가 아니다. 실제 repository preflight, network failure, cancel, disk-full, actual installed-size Samsung matrix는 `NOT_RUN`이다.

## 출처

- [Alpine v3.21 main aarch64 APKINDEX](https://dl-cdn.alpinelinux.org/alpine/v3.21/main/aarch64/APKINDEX.tar.gz)
- [Alpine v3.21 community aarch64 APKINDEX](https://dl-cdn.alpinelinux.org/alpine/v3.21/community/aarch64/APKINDEX.tar.gz)
