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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
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
val ttyNoIoctlFilterProot = layout.projectDirectory.file(
    "src/main/jniLibs/arm64-v8a/libproot_tty_no_ioctl_filter.so",
)
val ttyNoIoctlFilterProotSha256 =
    "a210b3416504e25f05e8b5b9a5ec5542ef1a6e55f9b408910d0a205a3df374e7"
val ttyPostWinsizeInputTraceProot = layout.projectDirectory.file(
    "src/main/jniLibs/arm64-v8a/libproot_tty_post_winsize_input_trace.so",
)
val ttyPostWinsizeInputTraceProotSha256 =
    "8f441a25ec423c224f68421797c099283b5cc2d0261c286bcb0125bc76f44a75"
val ttyPostWinsizeCommandFlowTraceProot = layout.projectDirectory.file(
    "src/main/jniLibs/arm64-v8a/libproot_tty_post_winsize_command_flow_trace.so",
)
val ttyPostWinsizeCommandFlowTraceProotSha256 =
    "43819777bffd234ac0f2d98a8efa3e53362f796e121d99246f8beb3ebb84428d"
val ttyPostWinsizePostReadFlowTraceProot = layout.projectDirectory.file(
    "src/main/jniLibs/arm64-v8a/libproot_tty_post_winsize_post_read_flow_trace.so",
)
val ttyPostWinsizePostReadFlowTraceProotSha256 =
    "408d5a740de408d758c06bc8b31cd81868c88b18d1500b43c27e9edc9a12e5a6"
val ttySecondTiocgwinszTraceProot = layout.projectDirectory.file(
    "src/main/jniLibs/arm64-v8a/libproot_tty_second_tiocgwinsz_trace.so",
)
val ttySecondTiocgwinszTraceProotSha256 =
    "393dde8b949be2f1579215741aeeb9784dce533738dd87509c2dc8a50c8ab4b2"
val ttySecondTiocgwinszTargetOutputTraceProot = layout.projectDirectory.file(
    "src/main/jniLibs/arm64-v8a/libproot_tty_second_tiocgwinsz_target_output_trace.so",
)
val ttySecondTiocgwinszTargetOutputTraceProotSha256 =
    "46160c628fc90b984cb4e85faf5eeaa876b8dc638b430ac1e5082f7898030912"
val ttyVirtualWinsizeSecondTargetOutputTraceProot = layout.projectDirectory.file(
    "src/main/jniLibs/arm64-v8a/libproot_tty_virtual_winsize_second_target_output_trace.so",
)
val ttyVirtualWinsizeSecondTargetOutputTraceProotSha256 =
    "dbb3fc063d6de7abe861768e183623e6a96e8ebfe3b2c3a3c2dda13f8af8350e"
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
    "e9f9f2c902db00f1d2d4fa6a29c0738488c7139ccc6d4fbb07af7fdba7f68b49"
val ttyHostPtyResizeControl = layout.projectDirectory.file(
    "src/main/jniLibs/arm64-v8a/libtty_host_resize_control.so",
)
val ttyHostPtyResizeControlSha256 =
    "0a09ffa0a30aa7bb80644bacea11100af9cc492be5070edb36f77fb0b2b581af"
val ttyWinsizeHelper = layout.projectDirectory.file(
    "src/main/jniLibs/arm64-v8a/libtty_winsize_probe.so",
)
val ttyWinsizeHelperSha256 =
    "f59c61e972d5ce75ee0fa44d41bba40400585a16d5f2f39034d25829da7212bd"

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

val verifyTtyNoIoctlFilterProot by tasks.registering {
    group = "verification"
    description = "Verifies the Probe-only PRoot Android ioctl-filter bypass control."
    doLast {
        val artifact = ttyNoIoctlFilterProot.asFile
        check(artifact.isFile) { "Missing Probe ioctl-filter control PRoot: ${artifact.path}" }
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
        check(actual == ttyNoIoctlFilterProotSha256) {
            "Probe ioctl-filter control PRoot checksum mismatch: $actual"
        }
    }
}

val verifyTtyPostWinsizeInputTraceProot by tasks.registering {
    group = "verification"
    description = "Verifies the Probe-only PRoot post-TIOCGWINSZ input lifecycle tracer."
    doLast {
        val artifact = ttyPostWinsizeInputTraceProot.asFile
        check(artifact.isFile) { "Missing Probe post-winsize input PRoot: ${artifact.path}" }
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
        check(actual == ttyPostWinsizeInputTraceProotSha256) {
            "Probe post-winsize input PRoot checksum mismatch: $actual"
        }
    }
}

val verifyTtyPostWinsizeCommandFlowTraceProot by tasks.registering {
    group = "verification"
    description = "Verifies the Probe-only PRoot post-TIOCGWINSZ command-flow tracer."
    doLast {
        val artifact = ttyPostWinsizeCommandFlowTraceProot.asFile
        check(artifact.isFile) { "Missing Probe post-winsize command-flow PRoot: ${artifact.path}" }
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
        check(actual == ttyPostWinsizeCommandFlowTraceProotSha256) {
            "Probe post-winsize command-flow PRoot checksum mismatch: $actual"
        }
    }
}

val verifyTtyPostWinsizePostReadFlowTraceProot by tasks.registering {
    group = "verification"
    description = "Verifies the Probe-only PRoot post-TIOCGWINSZ post-read syscall-flow tracer."
    doLast {
        val artifact = ttyPostWinsizePostReadFlowTraceProot.asFile
        check(artifact.isFile) { "Missing Probe post-read flow PRoot: ${artifact.path}" }
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
        check(actual == ttyPostWinsizePostReadFlowTraceProotSha256) {
            "Probe post-read flow PRoot checksum mismatch: $actual"
        }
    }
}

val verifyTtySecondTiocgwinszTraceProot by tasks.registering {
    group = "verification"
    description = "Verifies the Probe-only PRoot second-TIOCGWINSZ lifecycle tracer."
    doLast {
        val artifact = ttySecondTiocgwinszTraceProot.asFile
        check(artifact.isFile) { "Missing Probe second-get PRoot: ${artifact.path}" }
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
        check(actual == ttySecondTiocgwinszTraceProotSha256) {
            "Probe second-get PRoot checksum mismatch: $actual"
        }
    }
}

val verifyTtySecondTiocgwinszTargetOutputTraceProot by tasks.registering {
    group = "verification"
    description = "Verifies the Probe-only PRoot second-TIOCGWINSZ target-output tracer."
    doLast {
        val artifact = ttySecondTiocgwinszTargetOutputTraceProot.asFile
        check(artifact.isFile) { "Missing Probe second-get target-output PRoot: ${artifact.path}" }
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
        check(actual == ttySecondTiocgwinszTargetOutputTraceProotSha256) {
            "Probe second-get target-output PRoot checksum mismatch: $actual"
        }
    }
}

val verifyTtyVirtualWinsizeSecondTargetOutputTraceProot by tasks.registering {
    group = "verification"
    description = "Verifies the Probe-only virtual-winsize second-get target-output composite."
    doLast {
        val artifact = ttyVirtualWinsizeSecondTargetOutputTraceProot.asFile
        check(artifact.isFile) { "Missing Probe virtual-winsize composite PRoot: ${artifact.path}" }
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
        check(actual == ttyVirtualWinsizeSecondTargetOutputTraceProotSha256) {
            "Probe virtual-winsize composite PRoot checksum mismatch: $actual"
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
    dependsOn(verifyTtyNoIoctlFilterProot)
    dependsOn(verifyTtyPostWinsizeInputTraceProot)
    dependsOn(verifyTtyPostWinsizeCommandFlowTraceProot)
    dependsOn(verifyTtyPostWinsizePostReadFlowTraceProot)
    dependsOn(verifyTtySecondTiocgwinszTraceProot)
    dependsOn(verifyTtySecondTiocgwinszTargetOutputTraceProot)
    dependsOn(verifyTtyVirtualWinsizeSecondTargetOutputTraceProot)
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
