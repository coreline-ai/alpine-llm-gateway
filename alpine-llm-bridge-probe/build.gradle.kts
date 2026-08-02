plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.alpine.llm.bridgeprobe"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.alpine.llm.bridgeprobe"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.4-probe"
        ndk { abiFilters += "arm64-v8a" }
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
    implementation(project(":alpine-runtime-android"))
    implementation(project(":alpine-runtime-pack-bundled"))
    implementation(project(":alpine-llm-bridge"))
    implementation(project(":alpine-llm-gateway-pack-bundled"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.DISABLE)
    }
}
