import com.android.build.gradle.LibraryExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("com.android.library") version "8.10.1" apply false
    id("com.android.application") version "8.10.1" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
}

data class SdkPublication(
    val artifactId: String,
    val displayName: String,
    val description: String,
)

val sdkPublications = mapOf(
    ":android" to SdkPublication(
        "alpine-llm-android",
        "Alpine LLM Android",
        "Android OAuth and Provider adapters for Alpine LLM integrations.",
    ),
    ":alpine-runtime-api" to SdkPublication(
        "alpine-runtime-api",
        "Alpine Runtime API",
        "Android-neutral Alpine runtime lifecycle, process, terminal, and package contracts.",
    ),
    ":alpine-runtime-android" to SdkPublication(
        "alpine-runtime-android",
        "Alpine Runtime Android",
        "Android implementation of the Alpine runtime API with a native PTY adapter.",
    ),
    ":alpine-runtime-background-android" to SdkPublication(
        "alpine-runtime-background-android",
        "Alpine Runtime Background Android",
        "Optional foreground-service and WorkManager policy adapter for user-visible Alpine jobs.",
    ),
    ":alpine-runtime-artifact-play" to SdkPublication(
        "alpine-runtime-artifact-play",
        "Alpine Runtime Play Asset Adapter",
        "Optional Play Asset Delivery provider for Alpine rootfs and auxiliary layers.",
    ),
    ":alpine-runtime-pack-bundled" to SdkPublication(
        "alpine-runtime-pack-bundled",
        "Alpine Runtime Bundled Pack",
        "Checksum-locked Alpine rootfs and PRoot artifact provider for arm64-v8a.",
    ),
    ":alpine-runtime-pack-x86_64" to SdkPublication(
        "alpine-runtime-pack-x86_64",
        "Alpine Runtime x86_64 Pack",
        "Experimental checksum-locked x86_64 Alpine rootfs and PRoot artifact provider.",
    ),
    ":alpine-runtime-host" to SdkPublication(
        "alpine-runtime-host",
        "Alpine Runtime Host",
        "UI-neutral host controller for Alpine runtime lifecycle and terminal state.",
    ),
    ":alpine-runtime-ui-compose" to SdkPublication(
        "alpine-runtime-ui-compose",
        "Alpine Runtime Compose UI",
        "Optional Compose runtime, recovery, terminal, and package management UI.",
    ),
    ":alpine-runtime-testkit" to SdkPublication(
        "alpine-runtime-testkit",
        "Alpine Runtime Testkit",
        "Deterministic fake Alpine runtime implementation for host application tests.",
    ),
    ":alpine-llm-bridge" to SdkPublication(
        "alpine-llm-bridge",
        "Alpine LLM Bridge",
        "Credential-isolating Android Host Bridge and Python Gateway lifecycle integration.",
    ),
    ":alpine-llm-gateway-pack-bundled" to SdkPublication(
        "alpine-llm-gateway-pack-bundled",
        "Alpine LLM Gateway Bundled Pack",
        "Checksum-locked Python Gateway layer for Alpine runtime hosts.",
    ),
    ":alpine-chat-routing" to SdkPublication(
        "alpine-chat-routing",
        "Alpine Chat Routing",
        "Android-neutral fast-chat and Alpine-workspace routing and fallback contracts.",
    ),
    ":alpine-chat-feature" to SdkPublication(
        "alpine-chat-feature",
        "Alpine Chat Feature",
        "Reusable encrypted conversation state, backend-neutral chat orchestration, and Compose UI.",
    ),
    ":alpine-chat-backend-direct" to SdkPublication(
        "alpine-chat-backend-direct",
        "Alpine Direct Chat Backend",
        "Android direct Provider backend for the Alpine chat routing contract.",
    ),
    ":alpine-chat-backend-alpine" to SdkPublication(
        "alpine-chat-backend-alpine",
        "Alpine Workspace Chat Backend",
        "Python Gateway backend for the Alpine chat routing contract.",
    ),
    ":alpine-workspace-api" to SdkPublication(
        "alpine-workspace-api",
        "Alpine Workspace API",
        "Android-neutral safe workspace path, quota, and bounded file operation contracts.",
    ),
    ":alpine-workspace-android" to SdkPublication(
        "alpine-workspace-android",
        "Alpine Workspace Android",
        "App-private atomic Android file store for the Alpine workspace API.",
    ),
)

subprojects {
    val publication = sdkPublications[path] ?: return@subprojects
    group = providers.gradleProperty("GROUP").get()
    version = providers.gradleProperty("VERSION_NAME").get()
    pluginManager.apply("maven-publish")

    fun MavenPublication.configurePom() {
        groupId = providers.gradleProperty("GROUP").get()
        artifactId = publication.artifactId
        version = providers.gradleProperty("VERSION_NAME").get()
        pom {
            name.set(publication.displayName)
            description.set(publication.description)
            url.set("https://github.com/coreline-ai/alpine-llm-gateway")
            scm {
                connection.set("scm:git:https://github.com/coreline-ai/alpine-llm-gateway.git")
                developerConnection.set("scm:git:ssh://git@github.com/coreline-ai/alpine-llm-gateway.git")
                url.set("https://github.com/coreline-ai/alpine-llm-gateway")
            }
        }
    }

    fun PublishingExtension.configureProjectRepository() {
        repositories {
            maven {
                name = "project"
                url = rootProject.layout.buildDirectory.dir("maven-repo").get().asFile.toURI()
            }
        }
    }

    pluginManager.withPlugin("com.android.library") {
        extensions.configure<LibraryExtension> {
            publishing {
                singleVariant("release") { withSourcesJar() }
            }
        }
        afterEvaluate {
            extensions.configure<PublishingExtension> {
                publications.register<MavenPublication>("release") {
                    configurePom()
                    from(components["release"])
                }
                configureProjectRepository()
            }
        }
    }

    pluginManager.withPlugin("java-library") {
        extensions.configure<JavaPluginExtension> { withSourcesJar() }
        afterEvaluate {
            extensions.configure<PublishingExtension> {
                publications.register<MavenPublication>("release") {
                    configurePom()
                    from(components["java"])
                }
                configureProjectRepository()
            }
        }
    }
}

tasks.register("publishPhase7Artifacts") {
    group = "publishing"
    description = "Publishes every reusable SDK artifact to build/maven-repo."
    dependsOn(sdkPublications.keys.map { "$it:publishReleasePublicationToProjectRepository" })
}
