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
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.DISABLE)
    }
}
