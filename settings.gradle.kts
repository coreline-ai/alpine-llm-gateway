pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "alpine-llm-gateway"
include(":android")
include(":sample")
include(":demo-chatbot")
include(":alpine-runtime-probe")
include(":alpine-runtime-api")
include(":alpine-runtime-android")
include(":alpine-runtime-background-android")
include(":alpine-runtime-artifact-play")
include(":alpine-runtime-pack-bundled")
include(":alpine-runtime-pack-x86_64")
include(":alpine-llm-bridge")
include(":alpine-llm-gateway-pack-bundled")
include(":alpine-llm-bridge-probe")
include(":alpine-runtime-ui-compose")
include(":alpine-runtime-testkit")
include(":alpine-runtime-host")
include(":alpine-integration-sample")
include(":integrated-app")
include(":alpine-chat-routing")
include(":alpine-chat-backend-direct")
include(":alpine-chat-backend-alpine")
include(":alpine-workspace-api")
include(":alpine-workspace-android")
