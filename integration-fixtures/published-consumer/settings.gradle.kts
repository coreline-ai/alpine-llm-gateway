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
        maven { url = uri("../../build/maven-repo") }
        google()
        mavenCentral()
    }
}

rootProject.name = "alpine-sdk-published-consumer"
include(":no-runtime")
include(":runtime-only")
include(":runtime-ui")
include(":runtime-llm")
include(":full")
include(":runtime-background")
include(":runtime-play-workspace")
include(":runtime-x86_64")
