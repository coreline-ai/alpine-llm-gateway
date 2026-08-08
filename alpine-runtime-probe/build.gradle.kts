import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.alpine.llm.runtimeprobe"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.alpine.llm.runtimeprobe"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-probe"
        ndk { abiFilters += setOf("arm64-v8a", "x86_64") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

androidResources {
        // AAPT transparently inflates a file whose final extension is .gz,
        // even when it appears in noCompress. The staged probe uses an opaque
        // .asset suffix so the verified gzip bytes and SHA-256 stay intact.
        noCompress += "asset"
    }

    packaging {
        jniLibs {
            // PRoot is an executable PIE packaged as libproot.so. The probe
            // requires an extracted nativeLibraryDir path for ProcessBuilder.
            useLegacyPackaging = true
        }
    }

    lint {
        disable += "AndroidGradlePluginVersion"
    }
}

dependencies {
    implementation(project(":alpine-runtime-android"))
    implementation(project(":alpine-runtime-pack-bundled"))
    implementation(project(":alpine-runtime-pack-x86_64"))

    testImplementation("junit:junit:4.13.2")
}

val ttyDiagnosticProot = layout.projectDirectory.file(
    "src/main/jniLibs/arm64-v8a/libproot_tty_trace.so",
)
val ttyDiagnosticProotSha256 =
    "1e71d3bfb02e9a1b408dd64334548cc54f32b561b9619d4431989a1e6db12aba"
val ttyResizeRelayProot = layout.projectDirectory.file(
    "src/main/jniLibs/arm64-v8a/libproot_tty_resize_relay.so",
)
val ttyResizeRelayProotSha256 =
    "d423f4242b9213ff0daa38ea60cfa74cec37ca7b4600b0f16b4c0fa5b4c44df7"
val ttySessionRelayLauncher = layout.projectDirectory.file(
    "src/main/jniLibs/arm64-v8a/libtty_session_relay_launcher.so",
)
val ttySessionRelayLauncherSha256 =
    "be47f2e7b9715d9a7684100195980e43337af97a18051aa427b3777d238bf3f0"
val ttySessionTraceeForegroundLauncher = layout.projectDirectory.file(
    "src/main/jniLibs/arm64-v8a/libtty_session_tracee_foreground_launcher.so",
)
val ttySessionTraceeForegroundLauncherSha256 =
    "61b2e86a946ddc5f9a2301b91443fdf392e72efa50812625bdf172303ee6f45b"
val ttySessionVirtualResizeLauncher = layout.projectDirectory.file(
    "src/main/jniLibs/arm64-v8a/libtty_session_virtual_resize_launcher.so",
)
val ttySessionVirtualResizeLauncherSha256 =
    "3bf5bde1192e8e07ceaf683ab282a135654572fce2f6fde286cf01a72654dde7"
val ttyHostPtyResizeControl = layout.projectDirectory.file(
    "src/main/jniLibs/arm64-v8a/libtty_host_resize_control.so",
)
val ttyHostPtyResizeControlSha256 =
    "a02a1a4589ceb6748ceb0f45214e35f98f7ab32c4105850d96cea77a66d94624"
val ttyWinsizeHelper = layout.projectDirectory.file(
    "src/main/jniLibs/arm64-v8a/libtty_winsize_probe.so",
)
val ttyWinsizeHelperSha256 =
    "d7b56fbc41a10edd19e848bab7af91c3ab7cd96dcf3aee593a4f7e6d7e4e7c24"

val verifyTtyDiagnosticProot by tasks.registering {
    group = "verification"
    description = "Verifies the Probe-only PRoot ioctl diagnostic launcher."
    doLast {
        val artifact = ttyDiagnosticProot.asFile
        check(artifact.isFile) { "Missing Probe diagnostic PRoot: ${artifact.path}" }
        val digest = MessageDigest.getInstance("SHA-256")
        artifact.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        check(actual == ttyDiagnosticProotSha256) {
            "Probe diagnostic PRoot checksum mismatch: $actual"
        }
    }
}

val verifyTtyResizeRelayProot by tasks.registering {
    group = "verification"
    description = "Verifies the Probe-only PRoot primary-tracee foreground diagnostic launcher."
    doLast {
        val artifact = ttyResizeRelayProot.asFile
        check(artifact.isFile) { "Missing Probe resize relay PRoot: ${artifact.path}" }
        val digest = MessageDigest.getInstance("SHA-256")
        artifact.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        check(actual == ttyResizeRelayProotSha256) {
            "Probe resize relay PRoot checksum mismatch: $actual"
        }
    }
}

val verifyTtySessionRelayLauncher by tasks.registering {
    group = "verification"
    description = "Verifies the Probe-only direct PRoot session leader."
    doLast {
        val artifact = ttySessionRelayLauncher.asFile
        check(artifact.isFile) { "Missing Probe session relay launcher: ${artifact.path}" }
        val digest = MessageDigest.getInstance("SHA-256")
        artifact.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        check(actual == ttySessionRelayLauncherSha256) {
            "Probe session relay launcher checksum mismatch: $actual"
        }
    }
}

val verifyTtySessionTraceeForegroundLauncher by tasks.registering {
    group = "verification"
    description = "Verifies the Probe-only PRoot primary-tracee foreground session leader."
    doLast {
        val artifact = ttySessionTraceeForegroundLauncher.asFile
        check(artifact.isFile) { "Missing Probe tracee foreground launcher: ${artifact.path}" }
        val digest = MessageDigest.getInstance("SHA-256")
        artifact.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        check(actual == ttySessionTraceeForegroundLauncherSha256) {
            "Probe tracee foreground launcher checksum mismatch: $actual"
        }
    }
}

val verifyTtySessionVirtualResizeLauncher by tasks.registering {
    group = "verification"
    description = "Verifies the Probe-only PRoot virtual-winsize session supervisor."
    doLast {
        val artifact = ttySessionVirtualResizeLauncher.asFile
        check(artifact.isFile) { "Missing Probe virtual winsize launcher: ${artifact.path}" }
        val digest = MessageDigest.getInstance("SHA-256")
        artifact.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        check(actual == ttySessionVirtualResizeLauncherSha256) {
            "Probe virtual winsize launcher checksum mismatch: $actual"
        }
    }
}

val verifyTtyHostPtyResizeControl by tasks.registering {
    group = "verification"
    description = "Verifies the Probe-only host PTY resize control executable."
    doLast {
        val artifact = ttyHostPtyResizeControl.asFile
        check(artifact.isFile) { "Missing Probe host PTY resize control: ${artifact.path}" }
        val digest = MessageDigest.getInstance("SHA-256")
        artifact.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        check(actual == ttyHostPtyResizeControlSha256) {
            "Probe host PTY resize control checksum mismatch: $actual"
        }
    }
}

val verifyTtyWinsizeHelper by tasks.registering {
    group = "verification"
    description = "Verifies the Probe-only guest TIOCGWINSZ helper."
    doLast {
        val artifact = ttyWinsizeHelper.asFile
        check(artifact.isFile) { "Missing Probe tty helper: ${artifact.path}" }
        val digest = MessageDigest.getInstance("SHA-256")
        artifact.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        check(actual == ttyWinsizeHelperSha256) {
            "Probe tty helper checksum mismatch: $actual"
        }
    }
}

tasks.named("preBuild") {
    dependsOn(verifyTtyDiagnosticProot)
    dependsOn(verifyTtyResizeRelayProot)
    dependsOn(verifyTtySessionRelayLauncher)
    dependsOn(verifyTtySessionTraceeForegroundLauncher)
    dependsOn(verifyTtySessionVirtualResizeLauncher)
    dependsOn(verifyTtyHostPtyResizeControl)
    dependsOn(verifyTtyWinsizeHelper)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.DISABLE)
    }
}
