import java.security.MessageDigest

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.alpine.runtime.gateway.pack.bundled"
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
    lint { disable += "AndroidGradlePluginVersion" }
}

dependencies {
    api(project(":alpine-llm-bridge"))
    testImplementation("junit:junit:4.13.2")
}

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

val verifyBundledPythonGatewayArtifact by tasks.registering {
    group = "verification"
    description = "Verifies the locked, rootfs-independent Python Gateway layer."
    doLast {
        val artifact = layout.projectDirectory
            .file("src/main/assets/alpine-llm-gateway.tar.gz.asset").asFile
        check(artifact.isFile) { "Missing bundled Python Gateway artifact" }
        check(artifact.length() == 13_019L) { "Bundled Python Gateway size mismatch" }
        check(sha256(artifact) == "c6e79f12c9902c728a2e2336b2b3bf9ce2bae7fe9ef37bbd3060de1cbbb22a96") {
            "Bundled Python Gateway checksum mismatch"
        }
    }
}

tasks.named("preBuild") { dependsOn(verifyBundledPythonGatewayArtifact) }

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.DISABLE)
    }
}
