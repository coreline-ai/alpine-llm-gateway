import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.alpine.codex.appserver.pack"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    sourceSets.getByName("main").jniLibs.srcDir(
        layout.buildDirectory.dir("generated/codex-appserver/jniLibs"),
    )
    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/libcodex_app_server.so"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { disable += "AndroidGradlePluginVersion" }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

private val expectedBinarySha256 =
    "e23d0be344d2496986c985cd3db61e6f649b1ddd900e6afc1b5aaabbffcbb4e2"
private val expectedBinarySize = 222_231_296L

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}

fun elf64Arm64LoadAlignments(file: File): List<Long> = RandomAccessFile(file, "r").use { raf ->
    val headerBytes = ByteArray(64)
    raf.readFully(headerBytes)
    check(headerBytes.copyOfRange(0, 4).contentEquals(byteArrayOf(0x7f, 0x45, 0x4c, 0x46))) {
        "Codex artifact is not an ELF file"
    }
    check(headerBytes[4].toInt() == 2 && headerBytes[5].toInt() == 1) {
        "Codex artifact must be little-endian ELF64"
    }
    val header = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
    check(header.getShort(18).toInt() and 0xffff == 183) { "Codex artifact must be AArch64" }
    val programOffset = header.getLong(32)
    val entrySize = header.getShort(54).toInt() and 0xffff
    val entryCount = header.getShort(56).toInt() and 0xffff
    check(entrySize >= 56 && entryCount in 1..1024) { "Invalid Codex ELF program header" }
    (0 until entryCount).mapNotNull { index ->
        val bytes = ByteArray(entrySize)
        raf.seek(programOffset + index.toLong() * entrySize)
        raf.readFully(bytes)
        val entry = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (entry.getInt(0) == 1) entry.getLong(48) else null
    }.also { check(it.isNotEmpty()) { "Codex ELF has no PT_LOAD segments" } }
}

val featureEnabled = providers.gradleProperty("codexAppServerEnabled")
    .map(String::toBooleanStrict)
    .orElse(false)
val packEnabled = providers.gradleProperty("codexAppServerPackEnabled")
    .map(String::toBooleanStrict)
    .orElse(featureEnabled)
val rollbackBuild = providers.gradleProperty("codexAppServerRollbackBuild")
    .map(String::toBooleanStrict)
    .orElse(false)
require(!(featureEnabled.get() && !packEnabled.get())) {
    "codexAppServerEnabled cannot disable the Codex executable pack"
}
require(!(rollbackBuild.get() && packEnabled.get())) {
    "codexAppServerRollbackBuild cannot include the Codex executable pack"
}
val defaultBinary = rootProject.layout.projectDirectory.file(
    ".codex-artifacts/0.147.0/linux-arm64/codex",
)
val configuredBinary = providers.gradleProperty("codexAppServerBinary")
    .map { rootProject.file(it) }
    .orElse(defaultBinary.asFile)
val generatedBinary = layout.buildDirectory.file(
    "generated/codex-appserver/jniLibs/arm64-v8a/libcodex_app_server.so",
)

val prepareCodexAppServerArtifact by tasks.registering {
    group = "build setup"
    description = "Verifies and stages the optional pinned Codex App Server arm64 executable."
    inputs.property("enabled", packEnabled)
    inputs.property("expectedSha256", expectedBinarySha256)
    outputs.file(generatedBinary)
    doLast {
        val output = generatedBinary.get().asFile
        output.parentFile.deleteRecursively()
        if (!packEnabled.get()) return@doLast

        val source = configuredBinary.get()
        check(source.isFile) {
            "Missing pinned Codex artifact. Run scripts/import-codex-appserver-artifact.py first."
        }
        check(source.length() == expectedBinarySize) { "Codex artifact size mismatch" }
        check(sha256(source) == expectedBinarySha256) { "Codex artifact checksum mismatch" }
        val alignments = elf64Arm64LoadAlignments(source)
        check(alignments.all { it >= 16 * 1024 }) {
            "Codex artifact is not 16 KiB page aligned: $alignments"
        }
        output.parentFile.mkdirs()
        source.copyTo(output, overwrite = true)
        check(output.setExecutable(true, true)) { "Cannot mark staged Codex artifact executable" }
    }
}

tasks.named("preBuild") { dependsOn(prepareCodexAppServerArtifact) }

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.DISABLE)
    }
}
