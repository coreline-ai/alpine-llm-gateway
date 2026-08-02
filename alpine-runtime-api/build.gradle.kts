plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-library`
}

group = providers.gradleProperty("GROUP").get()
version = providers.gradleProperty("VERSION_NAME").get()

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.DISABLE)
    }
}

dependencies {
    api(kotlin("stdlib"))
    testImplementation("junit:junit:4.13.2")
}

val generatedApiDump = layout.buildDirectory.file("api/alpine-runtime-api.txt")
val checkedInApiDump = layout.projectDirectory.file("api/alpine-runtime-api.txt")

tasks.register("generatePublicApiDump") {
    group = "verification"
    description = "Generates the javap-based public API dump for this module."
    dependsOn(tasks.named("classes"))
    inputs.files(sourceSets.main.get().output.classesDirs)
    outputs.file(generatedApiDump)

    doLast {
        val classDirectories = sourceSets.main.get().output.classesDirs.files.filter(File::exists)
        val classNames = classDirectories
            .flatMap { root ->
                root.walkTopDown()
                    .filter { it.isFile && it.extension == "class" }
                    .map { file ->
                        file.relativeTo(root).invariantSeparatorsPath
                            .removeSuffix(".class")
                            .replace('/', '.')
                    }
                    .filterNot { it.contains('$') }
                    .toList()
            }
            .sorted()
        check(classNames.isNotEmpty()) { "No compiled API classes found" }

        val output = providers.exec {
            executable(File(System.getProperty("java.home"), "bin/javap").absolutePath)
            args("-public", "-classpath", sourceSets.main.get().runtimeClasspath.asPath)
            args(classNames)
        }.standardOutput.asText.get()
        generatedApiDump.get().asFile.apply {
            parentFile.mkdirs()
            writeText(output.replace("\r\n", "\n"))
        }
    }
}

tasks.register("verifyPublicApiDump") {
    group = "verification"
    description = "Fails when the public API differs from the checked-in contract."
    dependsOn("generatePublicApiDump")
    inputs.file(checkedInApiDump)

    doLast {
        val expected = checkedInApiDump.asFile.readText().replace("\r\n", "\n")
        val actual = generatedApiDump.get().asFile.readText()
        check(expected == actual) {
            "Public API changed. Review the change and refresh alpine-runtime-api/api/alpine-runtime-api.txt."
        }
    }
}

tasks.named("check") {
    dependsOn("verifyPublicApiDump")
}
