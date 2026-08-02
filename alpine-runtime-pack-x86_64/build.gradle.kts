import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.alpine.runtime.pack.x8664"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

androidResources { noCompress += "asset" }
    packaging { jniLibs { useLegacyPackaging = true } }
    lint { disable += "AndroidGradlePluginVersion" }
}

dependencies {
    api(project(":alpine-runtime-api"))
    testImplementation("junit:junit:4.13.2")
}

val lockedArtifacts = mapOf(
    "src/main/assets/alpine-minirootfs-x86_64.tar.gz.asset" to
        "1a694899e406ce55d32334c47ac0b2efb6c06d7e878102d1840892ad44cd5239",
    "src/main/jniLibs/x86_64/libproot.so" to
        "9c0bf771ba92151514338643b03fce271d80543c30ae6395c472f313a5d98868",
    "src/main/jniLibs/x86_64/libproot-loader.so" to
        "4ca6f14810548610501d012144abeb4c27c1530e2e37201cabf30cab2c39a585",
    "src/main/resources/META-INF/alpine-runtime/x86_64/sbom.spdx.json" to
        "1aa09d877b3fec8a9149bfc967f86489087b0e4799d71d067ff2f3a971c2ecc4",
)

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

fun verifyX8664Elf(file: File) {
    val bytes = file.readBytes()
    check(bytes.size >= 64 && bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(0x7f, 0x45, 0x4c, 0x46)))
    check(bytes[4].toInt() == 2 && bytes[5].toInt() == 1) { "Expected little-endian ELF64: ${file.path}" }
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    check((buffer.getShort(18).toInt() and 0xffff) == 62) { "Expected x86_64 ELF: ${file.path}" }
    val programOffset = buffer.getLong(32).toInt()
    val entrySize = buffer.getShort(54).toInt() and 0xffff
    val entryCount = buffer.getShort(56).toInt() and 0xffff
    val alignments = (0 until entryCount).mapNotNull { index ->
        val offset = programOffset + index * entrySize
        check(offset >= 0 && offset + entrySize <= bytes.size) { "Invalid ELF program header: ${file.path}" }
        if (buffer.getInt(offset) == 1) buffer.getLong(offset + 48) else null
    }
    check(alignments.isNotEmpty() && alignments.all { it >= 16 * 1024 }) {
        "x86_64 native artifact is not 16 KiB page aligned: ${file.path} $alignments"
    }
}

val verifyX8664RuntimeArtifacts by tasks.registering {
    group = "verification"
    description = "Verifies the experimental x86_64 rootfs, native artifacts, and SPDX SBOM."
    doLast {
        lockedArtifacts.forEach { (path, expected) ->
            val artifact = layout.projectDirectory.file(path).asFile
            check(artifact.isFile) { "Missing x86_64 runtime artifact: $path" }
            check(sha256(artifact) == expected) { "x86_64 runtime checksum mismatch: $path" }
        }
        listOf(
            "src/main/jniLibs/x86_64/libproot.so",
            "src/main/jniLibs/x86_64/libproot-loader.so",
        ).forEach { verifyX8664Elf(layout.projectDirectory.file(it).asFile) }
    }
}

tasks.named("preBuild") { dependsOn(verifyX8664RuntimeArtifacts) }

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.DISABLE)
    }
}
