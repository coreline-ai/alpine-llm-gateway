plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val codexAppServerEnabled = providers.gradleProperty("codexAppServerEnabled")
    .map(String::toBooleanStrict)
    .orElse(false)
val codexAppServerRollbackBuild = providers.gradleProperty("codexAppServerRollbackBuild")
    .map(String::toBooleanStrict)
    .orElse(false)
val codexCurrentStatePublicRelease = providers.gradleProperty("codexCurrentStatePublicRelease")
    .map(String::toBooleanStrict)
    .orElse(false)
val codexEnabled = codexAppServerEnabled.get()
val codexRollback = codexAppServerRollbackBuild.get()
val currentStatePublicRelease = codexCurrentStatePublicRelease.get()
val releaseStorePath = providers.environmentVariable("ALPINE_RELEASE_KEYSTORE").orNull
val releaseKeyAlias = providers.environmentVariable("ALPINE_RELEASE_KEY_ALIAS").orNull
val releaseStorePassword = providers.environmentVariable("ALPINE_RELEASE_STORE_PASSWORD").orNull
val releaseKeyPassword = providers.environmentVariable("ALPINE_RELEASE_KEY_PASSWORD").orNull
val releaseSigningValues = listOf(
    releaseStorePath,
    releaseKeyAlias,
    releaseStorePassword,
    releaseKeyPassword,
)
val releaseSigningConfigured = releaseSigningValues.all { !it.isNullOrBlank() }
val releaseSigningPartiallyConfigured = releaseSigningValues.any { !it.isNullOrBlank() } &&
    !releaseSigningConfigured
require(!(codexEnabled && codexRollback)) {
    "codexAppServerEnabled and codexAppServerRollbackBuild are mutually exclusive"
}
require(!currentStatePublicRelease || codexEnabled) {
    "codexCurrentStatePublicRelease requires codexAppServerEnabled"
}
require(!releaseSigningPartiallyConfigured) {
    "Release signing requires all ALPINE_RELEASE_* environment variables"
}
val codexDebugPackage = codexEnabled || codexRollback

android {
    namespace = "dev.alpine.integrated"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.alpine.integrated"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.3.0"
        manifestPlaceholders["appLabel"] = "Alpine AI Workspace"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
        buildConfigField("boolean", "CODEX_APP_SERVER_ENABLED", codexEnabled.toString())
        buildConfigField("boolean", "CODEX_APP_SERVER_ROLLBACK_BUILD", codexRollback.toString())
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = rootProject.file(requireNotNull(releaseStorePath))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = if (codexDebugPackage) ".codexdebug" else ".debug"
            versionNameSuffix = "-debug"
            manifestPlaceholders["appLabel"] = when {
                codexEnabled -> "Alpine AI Workspace (Codex Debug)"
                codexRollback -> "Alpine AI Workspace (Codex Rollback)"
                else -> "Alpine AI Workspace (Debug)"
            }
        }
        getByName("release") {
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    androidResources { noCompress += "asset" }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/libcodex_app_server.so"
        }
    }
    lint { disable += "AndroidGradlePluginVersion" }
}

dependencies {
    implementation(project(":alpine-runtime-host"))
    implementation(project(":alpine-runtime-android"))
    implementation(project(":alpine-runtime-background-android"))
    implementation(project(":alpine-runtime-pack-bundled"))
    implementation(project(":alpine-runtime-ui-compose"))
    implementation(project(":alpine-workspace-android"))
    implementation(project(":alpine-chat-routing"))
    implementation(project(":alpine-chat-feature"))
    implementation(project(":alpine-chat-provider-android"))
    implementation(project(":alpine-chat-backend-direct"))
    implementation(project(":alpine-chat-backend-alpine"))
    implementation(project(":alpine-chat-backend-codex"))
    implementation(project(":alpine-codex-appserver-android"))
    implementation(project(":alpine-llm-gateway-pack-bundled"))

    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.core:core-ktx:1.15.0")
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
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.DISABLE)
    }
}

val verifyCurrentStatePublicReleaseDecision by tasks.registering(Exec::class) {
    group = "verification"
    description = "Verifies the explicit project-owner current-state distribution decision."
    workingDir(rootProject.projectDir)
    val python = providers.environmentVariable("PYTHON_BIN").orElse("python3")
    commandLine(
        python.get(),
        "scripts/verify-current-state-release-decision.py",
        "--check-evidence",
    )
}

// Release stays fail-closed by default. The project owner's explicit current-state decision is a
// separate opt-in path: it authorizes artifact creation without rewriting NOT_RUN/BLOCKED evidence
// as PASS. Signing and upload destination remain external release inputs.
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    if (codexEnabled && currentStatePublicRelease) {
        dependsOn(verifyCurrentStatePublicReleaseDecision)
    }
    doFirst {
        require(!codexEnabled || currentStatePublicRelease) {
            "Codex App Server release build is blocked by unresolved distribution gates"
        }
    }
}
