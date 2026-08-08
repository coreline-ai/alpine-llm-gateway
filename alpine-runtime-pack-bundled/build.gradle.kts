import java.security.MessageDigest
import java.nio.ByteBuffer
import java.nio.ByteOrder

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.alpine.runtime.pack.bundled"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

androidResources {
        noCompress += "asset"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    lint {
        disable += "AndroidGradlePluginVersion"
    }
}

dependencies {
    api(project(":alpine-runtime-api"))
    testImplementation("junit:junit:4.13.2")
}

val lockedArtifacts = mapOf(
    "src/main/assets/alpine-minirootfs.tar.gz.asset" to
        "ead8a4b37867bd19e7417dd078748e2312c0aea364403d96758d63ea8ff261ea",
    "src/main/jniLibs/arm64-v8a/libproot.so" to
        "5d2959c3a58f82609c8b95a92496835099a96faa8efc12f68e171a3597b5bc29",
    "src/main/jniLibs/arm64-v8a/libproot-loader.so" to
        "12d2b63e897fd91a334fce23edea5d2419cae4d5fd2a369f05d03ab75682add0",
    "src/main/resources/META-INF/alpine-runtime/sbom.spdx.json" to
        "678ed604a09a22d5e63c3f2289225de0a85b7c868f05e78817f7a54e4d1d42bc",
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

fun elf64LoadAlignments(file: File): List<Long> {
    val bytes = file.readBytes()
    check(bytes.size >= 64 && bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(0x7f, 0x45, 0x4c, 0x46))) {
        "Not an ELF file: ${file.path}"
    }
    check(bytes[4].toInt() == 2 && bytes[5].toInt() == 1) {
        "Expected little-endian ELF64: ${file.path}"
    }
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val programOffset = buffer.getLong(32).toInt()
    val entrySize = buffer.getShort(54).toInt() and 0xffff
    val entryCount = buffer.getShort(56).toInt() and 0xffff
    return (0 until entryCount).mapNotNull { index ->
        val offset = programOffset + index * entrySize
        check(offset >= 0 && offset + entrySize <= bytes.size) { "Invalid ELF program header: ${file.path}" }
        if (buffer.getInt(offset) == 1) buffer.getLong(offset + 48) else null
    }.also { check(it.isNotEmpty()) { "ELF has no PT_LOAD segments: ${file.path}" } }
}

val verifyBundledRuntimeArtifacts by tasks.registering {
    group = "verification"
    description = "Verifies the locked Alpine rootfs, PRoot, loader, and SPDX SBOM."
    doLast {
        lockedArtifacts.forEach { (path, expected) ->
            val artifact = layout.projectDirectory.file(path).asFile
            check(artifact.isFile) { "Missing bundled runtime artifact: $path" }
            check(sha256(artifact) == expected) { "Bundled runtime checksum mismatch: $path" }
        }
        listOf(
            "src/main/jniLibs/arm64-v8a/libproot.so",
            "src/main/jniLibs/arm64-v8a/libproot-loader.so",
        ).forEach { path ->
            val alignments = elf64LoadAlignments(layout.projectDirectory.file(path).asFile)
            check(alignments.all { it >= 16 * 1024 }) {
                "Bundled native artifact is not 16 KiB page aligned: $path $alignments"
            }
        }
    }
}

tasks.named("preBuild") {
    dependsOn(verifyBundledRuntimeArtifacts)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.DISABLE)
    }
}
