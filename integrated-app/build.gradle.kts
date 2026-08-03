plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.alpine.integrated"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.alpine.integrated"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildFeatures { compose = true }
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
    implementation(project(":alpine-runtime-background-android"))
    implementation(project(":alpine-runtime-pack-bundled"))
    implementation(project(":alpine-runtime-ui-compose"))
    implementation(project(":alpine-chat-routing"))
    implementation(project(":alpine-chat-feature"))
    implementation(project(":alpine-chat-provider-android"))
    implementation(project(":alpine-chat-backend-direct"))
    implementation(project(":alpine-chat-backend-alpine"))
    implementation(project(":alpine-llm-gateway-pack-bundled"))

    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.DISABLE)
    }
}
