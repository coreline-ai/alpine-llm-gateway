import com.android.build.api.dsl.ApplicationExtension

plugins {
    id("com.android.application") version "8.10.1" apply false
}

val sdkVersion = providers.gradleProperty("sdkVersion").orElse("0.3.0")
val sdk = { artifact: String -> "dev.alpine.llm:$artifact:${sdkVersion.get()}" }

val matrixDependencies = mapOf(
    "no-runtime" to listOf(
        "alpine-llm-android",
        "alpine-chat-feature",
        "alpine-chat-provider-android",
    ),
    "runtime-only" to listOf(
        "alpine-runtime-android",
        "alpine-runtime-pack-bundled",
    ),
    "runtime-ui" to listOf(
        "alpine-runtime-android",
        "alpine-runtime-pack-bundled",
        "alpine-runtime-ui-compose",
    ),
    "runtime-llm" to listOf(
        "alpine-runtime-android",
        "alpine-runtime-pack-bundled",
        "alpine-llm-bridge",
        "alpine-llm-gateway-pack-bundled",
    ),
    "full" to listOf(
        "alpine-runtime-android",
        "alpine-runtime-pack-bundled",
        "alpine-runtime-background-android",
        "alpine-runtime-artifact-play",
        "alpine-runtime-ui-compose",
        "alpine-llm-bridge",
        "alpine-llm-gateway-pack-bundled",
        "alpine-chat-routing",
        "alpine-chat-feature",
        "alpine-chat-provider-android",
        "alpine-chat-backend-direct",
        "alpine-chat-backend-alpine",
        "alpine-workspace-android",
    ),
    "runtime-background" to listOf(
        "alpine-runtime-background-android",
    ),
    "runtime-play-workspace" to listOf(
        "alpine-runtime-artifact-play",
        "alpine-workspace-android",
    ),
    "runtime-x86_64" to listOf(
        "alpine-runtime-android",
        "alpine-runtime-pack-x86_64",
    ),
)

subprojects {
    pluginManager.apply("com.android.application")

    extensions.configure<ApplicationExtension> {
        namespace = "dev.alpine.fixture.${project.name.replace("-", "")}"
        compileSdk = 36
        defaultConfig {
            applicationId = "dev.alpine.fixture.${project.name.replace("-", "")}"
            minSdk = 26
            targetSdk = 36
            versionCode = 1
            versionName = sdkVersion.get()
            when (project.name) {
                "no-runtime", "runtime-background", "runtime-play-workspace" -> Unit
                "runtime-x86_64" -> ndk { abiFilters += "x86_64" }
                else -> ndk { abiFilters += "arm64-v8a" }
            }
        }
        buildTypes {
            getByName("release") {
                isMinifyEnabled = true
                isShrinkResources = true
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    rootProject.file("consumer-proguard-rules.pro"),
                )
            }
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
        matrixDependencies.getValue(project.name).forEach { artifact ->
            add("implementation", sdk(artifact))
        }
    }
}
