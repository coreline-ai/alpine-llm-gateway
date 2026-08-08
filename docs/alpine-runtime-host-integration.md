# Alpine Runtime Host 통합 가이드

## 목적

`alpine-runtime-host`는 Activity, Compose, XML View와 무관한 단일 상태 소유자다. Host 앱은
Application 또는 장기 실행 Service에서 `AlpineRuntimeManager`와 `RuntimeHostController`를 한
번 생성하고, 화면은 `RuntimeHostState`만 구독한다. 화면 회전 때 controller를 다시 만들거나
runtime을 중지하지 않는다.

## 선택 가능한 통합 방식

| 방식 | 의존성 | 예제 |
|---|---|---|
| 자체 XML/View UI | `runtime-host` + `runtime-android` + artifact provider | `:alpine-integration-sample` |
| SDK Compose UI | 위 구성 + `runtime-ui-compose` | `:integrated-app`의 Alpine 작업 화면 |
| UI 없는 Service/Worker | `runtime-host` + `runtime-android` + artifact provider | 같은 controller API 직접 호출 |

두 UI 방식은 모두 `RuntimeHostController`의 install/start/health/repair/reset/execute/terminal/package
API를 호출하므로 lifecycle 결과와 오류 코드가 같다. Compose 모듈을 제거해도 core와 XML sample은
빌드된다.

## Application 소유권

```kotlin
class App : Application() {
    lateinit var manager: AlpineRuntimeManager
    lateinit var controller: RuntimeHostController

    override fun onCreate() {
        super.onCreate()
        manager = DefaultAndroidAlpineRuntimeFactory().create(
            this,
            AndroidRuntimeConfiguration(
                artifactProvider = BundledRuntimeArtifactProvider(
                    this,
                    Alpine321Arm64Pack.create(),
                ),
            ),
        )
        controller = RuntimeHostController(manager)
    }
}
```

- Activity/Fragment는 `onStart`에서 listener를 등록하고 `onStop`에서 listener만 해제한다.
- listener 해제는 runtime 종료가 아니다. 사용자가 종료을 선택하거나 Host background 정책이
  발동할 때만 `controller.stop(...)`을 호출한다.
- 앱 프로세스가 다시 생성되면 manager는 app-private 설치 marker와 checksum을 다시 검사한다.
  이전 프로세스의 RUNNING 상태를 복원하지 않고 `READY`, `REPAIR_REQUIRED`, `NOT_INSTALLED` 중
  실제 저장 상태를 보고한다.

## Background와 Foreground Service

긴 명령, 패키지 설치, Gateway를 화면 밖에서도 계속 실행하려면 Host 앱이 Foreground Service를
소유해야 한다. 기본 정책이 맞는 앱은 선택 모듈 `alpine-runtime-background-android`를 사용할 수 있고,
제품별 알림·작업 유형이 다르면 같은 process listener 계약으로 자체 Service를 구현한다.

1. `AndroidRuntimeConfiguration.processListener`로 첫 process `STARTED`와 마지막 `STOPPED`를 센다.
2. 첫 process 시작 전에 Host Service를 시작하고 즉시 `startForeground()`를 호출한다.
3. 알림에는 실행 중인 작업, 사용자 종료 action, 앱으로 돌아가기 action을 제공한다.
4. 사용자가 알림에서 종료하면 `RuntimeStopReason.USER_REQUEST`, 제품의 background 제한이면
   `HOST_BACKGROUND_POLICY`를 사용한다.
5. Android 재부팅 후 runtime을 자동 시작하지 않는다. 사용자가 명시적으로 다시 시작하도록 한다.

기본 adapter는 첫 user-started process에서 FGS를 시작하고 마지막 process 종료 시 해제한다.
알림의 **작업 중지** action은 process-local Host binding을 통해 runtime stop으로 연결되며
`START_NOT_STICKY`이므로 OS 종료나 process death 뒤 자동 재시작하지 않는다. Android 12+의
background start 거부는 안정 상태로 변환해 runtime을 `HOST_BACKGROUND_POLICY`로 중지한다.
WorkManager는 15분 이상 멈춘 상태 전이만 정리하며 Alpine 작업을 시작하거나 replay하지 않는다.

background module의 deterministic lease regression은 첫 start/마지막 stop, terminal과 command가
겹친 상태, duplicate stop, FGS start rejection의 host-policy callback을 검증한다. 이는 Service의
실제 notification 표시·제거 또는 OEM background lifecycle 실기기 증거가 아니며, 해당 Samsung
수동 matrix는 Doze/reboot 승인 전까지 `NOT_RUN`이다.

```kotlin
val background = RuntimeForegroundServiceController(applicationContext)
background.normalizeAfterProcessStart()
val processListener = RuntimeForegroundProcessListener(
    background,
    Runnable { controller.stop(RuntimeStopReason.HOST_BACKGROUND_POLICY) },
)
val binding = RuntimeBackgroundHostRegistry.bind {
    controller.stop(RuntimeStopReason.USER_REQUEST)
}
```

adapter manifest는 `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`와
`specialUse` service type을 선언한다. Android 13+에서는 알림 표시를 위해 Host가
`POST_NOTIFICATIONS` runtime 권한을 사용자에게 요청해야 한다. 단순 제한 우회를 위해 제품 목적과
맞지 않는 service type을 사용해서는 안 된다.

## Manifest와 패키징

Bundled runtime을 사용하는 application 모듈은 다음을 유지한다.

```kotlin
android {
    defaultConfig { ndk { abiFilters += "arm64-v8a" } } // 제품 지원 ABI
    androidResources { noCompress += "asset" }
    packaging { jniLibs { useLegacyPackaging = true } }
}
```

- `libproot.so`와 loader는 `ApplicationInfo.nativeLibraryDir`에서만 실행한다.
- package install 또는 Python Gateway가 네트워크를 사용하면 Host manifest에 `INTERNET` 권한을
  선언한다. runtime-only offline 앱에는 불필요한 권한을 추가하지 않는다.
- rootfs와 workspace는 app-private `filesDir` 아래에 둔다. 외부 공용 저장소에 executable,
  capability, credential을 저장하지 않는다.
- Host Bridge를 사용할 때 OAuth access/refresh token은 Android Keystore 경계 밖으로 내보내지 않는다.
- x86_64 실험 fixture는 `abiFilters += "x86_64"`와 `alpine-runtime-pack-x86_64`를 사용한다.
  `scripts/runtime/run-x86_64-emulator-gate.sh`가 통과하기 전에는 제품 지원으로 표시하지 않는다.

## Play Asset Delivery와 Workspace

- `PlayAssetRuntimeArtifactProvider`는 rootfs·auxiliary payload만 asset pack에서 가져온다.
- `libproot.so`와 `libproot-loader.so`는 install-time asset으로 옮기지 않고 base APK JNI에 둔다.
- pack 미설치, fetch 실패/취소, 사용자 확인 대기 timeout과 경로 누락은 안정 오류로 종료한다.
- `AppPrivateWorkspaceStore`는 `filesDir` 하위 단일 root만 사용한다. absolute path, `..`, NUL,
  backslash와 symlink를 거부하고 read/write/list 제한을 적용한다.
- 파일 교체는 temp file `fsync` 후 atomic move를 사용하며 비어 있지 않은 디렉터리 recursive delete를
  제공하지 않는다.
- `WorkspaceSafTransfer`는 사용자가 그 순간 선택한 `content://` URI만 읽거나 쓰고 URI permission을
  보존하지 않는다. provider stream 실패는 raw provider message 대신 stable workspace I/O 오류로 축소한다.
- 외부 공유는 `WorkspaceShareFilePublisher`가 `cacheDir/workspace-shares` 안에 bounded atomic file을
  만들고 canonical parent를 확인한 뒤에만 Host `FileProvider` URI로 변환한다. Host는 explicit share
  Intent에만 temporary read grant를 부여하고, credential·capability·실행 파일은 공유하지 않는다.

## Consumer ProGuard/R8

현재 public runtime API는 reflection에 의존하지 않으므로 Host 앱의 광범위한 keep rule이 필요하지
않다. 각 AAR의 `consumer-rules.pro`를 사용하고 다음 원칙을 지킨다.

- `dev.alpine.**` 전체를 무조건 keep하지 않는다.
- JSON reflection 라이브러리를 Host가 별도로 추가했다면 그 라이브러리 모델만 좁게 keep한다.
- release 빌드에서 `libproot.so`, `libproot-loader.so`, rootfs asset과 lock된 checksum이 존재하는지
  검사한다.
- 축소 빌드 후 install/start/exec/stop smoke test를 다시 수행한다.

## 터미널

`openTerminal()`은 PRoot guest 셸을 장기 process로 열고 bounded replay buffer, UTF-8 입력,
Ctrl+C/Ctrl+D, terminate/kill과 process cleanup을 제공한다. guest wrapper가 최초 크기를 `stty`에
적용하며, Native PTY launcher는 stale launch-time `COLUMNS`/`LINES` hint를 exec 전에 제거한다.
지원 ABI에서는 `alpine-runtime-android`의 작은 native adapter가 `/dev/ptmx`를
열고 PRoot를 controlling terminal에 연결하며 최초 창 크기를 적용한다. native PTY를 열 수 없는
환경에서는 대화형 pipe 셸로 안전하게 fallback한다. 현재 PRoot는 시작 후 Android master의
`TIOCSWINSZ` 변경을 guest `SIGWINCH`로 신뢰성 있게 전달하는 production contract가 없다. Probe는 같은
PTY에서 guest `stty size=40 120`과 raw helper winsize를 확인했지만 shell `WINCH` trap이 수신되지 않아,
SDK는 이를 성공으로 가장하지 않고 `resizeSupport=INITIAL_SIZE_ONLY`와
`TERMINAL_RESIZE_UNSUPPORTED`를 반환한다. 최초 크기와
`ALPINE_TERMINAL_MODE`는 device probe에서 검증한다. guest에는
`ALPINE_TERMINAL_RESIZE_CHANNEL=unsupported`를 전달한다. 동적 resize는 PRoot source-level terminal
hook 또는 대체 terminal architecture가 guest `stty`, foreground `SIGWINCH`, resize storm 및 대표 TUI를
실기기에서 통과한 artifact에서만 `DYNAMIC`으로 활성화한다. FIFO ready, Host ioctl, tracee fd 조회는
승격 근거가 아니다.

2026-08-08 Samsung Probe는 PRoot tracee가 `SIGWINCH=SIG_DFL`을 상속하도록 한 뒤에도 Android master
`TIOCSWINSZ`가 guest signal-stop/ptrace reinjection을 만들지 않음을 기록했다. 별도 native foreground-group
signal candidate는 일관된 guest trap을 만들지 못해 제거했다. 후속 Probe-only relay는 initial shell 한 번의
`WINCH` trap과 PRoot primary-tracee ptrace reinjection을 확인했지만, resize 직후 fixed command를 write해도
`printf` acknowledgement·`stty` marker·helper follow-up이 재개되지 않았다. 반복 `120×40 ↔ 80×24`/8회 storm도
실패했다. 따라서 현재 해결 대상은 winsize 자체가 아니라 ptrace reinjection 뒤 interactive input까지 보장하는
Android PTY signal/session semantics이며, runtime은 PID 재개방·host blind signal dispatch를 사용하지 않는다.

추가 `relay21` Samsung evidence는 PRoot source가 initial tracee와 실제 same-PTY `TIOCGWINSZ` tracee 모두를
physical tty foreground group으로 확인했음에도, Host master `TIOCSWINSZ` 성공 후 guest `SIGWINCH` stop/restart는
`0/0`이고 이후 fixed marker·helper·input이 재개되지 않았음을 보였다. 반면 PRoot 없이 같은 PTY/session을 만든
host-only control은 `TIOCSWINSZ → SIGWINCH → 이후 input`을 통과했다.

`relay24` Probe-only private-memfd control은 host-master resize와 post-launch signal을 모두 생략했지만, Samsung에서
store/fd-ready 뒤 guest read/apply와 input 재개에 실패했다. memfd write 없이 동일 socket request를 validate/ack만
하는 negative control도 input을 재개하지 못했으므로 production dynamic resize 근거가 아니다.
따라서 현재 blocker는 Android PTY
일반 기능이나 guest foreground-pgrp 설정이 아니라 **PRoot가 있는 host-master resize 경로**로 한정한다. 이
Probe artifact는 product pack에 포함되지 않으며 production contract는 계속 `INITIAL_SIZE_ONLY`다.

터미널 출력은 SDK controller에서 기본 256 KiB까지만 보존한다. 장기 log는 사용자가 명시한
workspace 파일로 저장하고 화면 메모리에 무제한 누적하지 않는다.

Compose UI는 raw escape byte를 직접 출력하지 않는다. `RuntimeHostController`는 bounded scrollback을
유지하면서 standard ANSI SGR colour, cursor 이동, erase, insert/delete, scrolling, cursor save/restore,
`1047`/`1049` alternate screen을 해석한 `RuntimeTerminalScreen` snapshot을 제공한다. OSC title 같은
비표시 control payload와 알 수 없는 sequence는 소비한다. renderer는 `240 × 120` cell로 상한을 두며,
scroll region·mouse·graphics protocol과 vi/nano/top의 full compatibility는 실기기 matrix가 통과하기
전까지 보장하지 않는다.

Terminal process가 닫히면 Android adapter는 event에 random terminal ID와 `0..255` numeric exit code만
넣는다. `RuntimeHostController`는 tab을 제거한 뒤에도 마지막 종료 title/code 하나만 화면에 남기며,
guest output·command·PID는 event나 UI state에 넣지 않는다. 새 terminal을 열거나 Runtime session을
stop/reset하면 이 종료 요약은 지워진다.

Compose terminal은 선택된 open tab에만 Ctrl+C, 확인 뒤 `SIGTERM`, 별도 확인 뒤 `SIGKILL`을 전달한다.
`SIGKILL` 확인을 취소하면 signal을 전혀 dispatch하지 않는다. 이 action은 runtime 전체 stop, Gateway
replay 또는 다른 tab 종료를 의미하지 않는다.

2026-08-08 Samsung arm64 Runtime Probe는 actual bundled PRoot에서 initial `stty size=28 96`,
`INITIAL_SIZE_ONLY`/`TERMINAL_RESIZE_UNSUPPORTED`, terminal close의 safe numeric exit event,
Host process `STARTED:3`/`STOPPED:3`, restart/repair 뒤 healthy `READY`를 확인했다. 이 증거는
동적 resize, `SIGWINCH`, rotation/storm 또는 vi/nano/top의 full TUI 호환을 의미하지 않는다.

## 패키지 설치

UI에서 문자열을 그대로 `/bin/sh`에 전달하지 않는다.

1. `RuntimePackageInstallRequest`가 패키지 이름과 개수를 검증한다.
2. `RuntimePackageAllowlistPolicy`가 정확한 이름만 허용한다. 빈 allowlist는 모두 거부한다.
3. Host UI가 패키지·network·저장 공간 사용을 보여주고 `RuntimePackageApproval`을 완료한다.
4. core는 고정된 `/sbin/apk add --no-progress <validated names>`만 실행한다.
5. 정책 거부와 사용자 취소는 command를 전혀 dispatch하지 않는다.

`RuntimePackageCatalog`은 Host/Runtime pack이 제공하는 **표시 전용** metadata contract다. 통합 앱은
Alpine `v3.21/aarch64` `APKINDEX` snapshot의 version/license/download/installed payload를 표시하지만,
이 값은 dependency solver, index/cache, filesystem overhead, repository 변경 또는 실제 transaction 결과를
포함하지 않는다. catalog에 없는 package는 완전한 용량 estimate로 표시하지 않으며, catalog는 allowlist
정책이나 command argv에 영향을 주지 않는다. 기준 값은
[Alpine package catalog snapshot](alpine-package-catalog-20260808.md)을 참고한다.

`RuntimeDeveloperToolProfile`은 Python, Git, SSH, Node의 설치 bundle과 fixed direct-argv
first-run 확인 요청을 분리해 제공한다. `RuntimeHostController.runToolSmoke()`는 선택한 profile의
version-only command만 실행하고 UI state에는 성공/실패와 profile ID만 보관한다. Guest stdout/stderr는
표시·저장하지 않으며 shell, environment injection, user-supplied command는 profile 계약에서 거부한다.
이 검사는 설치 완료나 live repository metadata의 진실성을 대체하지 않는다. 실제 full dependency
version/license/download/disk size와 Samsung network matrix는 `NOT_RUN`이다.

## 상태 복구 확인표

- 회전: listener만 교체되고 install/session/terminal 소유자는 Application에 남는다.
- background/foreground: Host 정책이 중지하지 않았다면 같은 controller snapshot을 다시 표시한다.
- process death: 새 manager가 설치 파일을 검사하고 거짓 RUNNING 상태를 만들지 않는다.
- 부분 설치: 원자적 activation/rollback 후 `READY` 또는 `REPAIR_REQUIRED`가 된다.
- reset: runtime 설치는 제거하지만 사용자 workspace는 보존한다.
- 접근성: 상태는 live region/state description으로 읽히고, 고정 높이 본문 대신 scroll을 사용한다.
- 입력: IME Send, 한글 UTF-8, 외부 키보드 Enter, Ctrl+C 흐름을 확인한다.
