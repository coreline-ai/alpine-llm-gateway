plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.alpine.runtime.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.alpine.runtime.sample"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.3.0"
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
    implementation(project(":alpine-runtime-host"))
    implementation(project(":alpine-runtime-android"))
    implementation(project(":alpine-runtime-pack-bundled"))
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.DISABLE)
    }
}
