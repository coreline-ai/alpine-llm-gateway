import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.alpine.llm.demo"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.alpine.llm.demo"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

lint {
        disable += "AndroidGradlePluginVersion"
    }
}

dependencies {
    implementation(project(":android"))
    implementation(project(":alpine-chat-feature"))
    implementation(project(":alpine-chat-routing"))
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")

    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

val verifyNoAlpineRuntimePayload by tasks.registering {
    group = "verification"
    description = "Fails if the fast-chat-only APK accidentally packages Alpine runtime payloads."
    dependsOn("assembleDebug")
    doLast {
        val apk = layout.buildDirectory.file("outputs/apk/debug/demo-chatbot-debug.apk").get().asFile
        check(apk.isFile) { "Debug APK was not produced: ${apk.absolutePath}" }
        val forbiddenFragments = listOf(
            "alpine-minirootfs",
            "libproot.so",
            "libproot-loader.so",
            "META-INF/alpine-runtime/sbom.spdx.json",
        )
        val packaged = ZipFile(apk).use { archive ->
            archive.entries().asSequence().map { it.name }.toList()
        }
        val forbidden = packaged.filter { entry ->
            forbiddenFragments.any(entry::contains)
        }
        check(forbidden.isEmpty()) {
            "Fast-chat APK must not contain Alpine runtime payloads: ${forbidden.joinToString()}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.DISABLE)
    }
}
