package dev.alpine.runtime.api

import java.io.InputStream
import java.util.Base64
import java.util.concurrent.CompletionStage

object RuntimeArtifactMetadataKeys {
    const val MANIFEST_SCHEMA = "manifest.schema"
    const val ROOTFS_FORMAT = "rootfs.format"
    const val NATIVE_LAUNCHER_FILE_NAME = "native.launcher.fileName"
    const val NATIVE_LOADER_FILE_NAME = "native.loader.fileName"
    const val SBOM_FORMAT = "sbom.format"
    const val SBOM_PATH = "sbom.path"
    const val SBOM_SHA256 = "sbom.sha256"
    const val SOURCE_REVISION = "source.revision"
}

enum class RuntimeArtifactKind {
    ROOTFS,
    NATIVE_LAUNCHER,
    NATIVE_LOADER,
    AUXILIARY,
}

data class RuntimeArtifactDescriptor @JvmOverloads constructor(
    val id: String,
    val kind: RuntimeArtifactKind,
    val version: String,
    val sha256: String,
    val sizeBytes: Long,
    val abi: String? = null,
    val license: String? = null,
) {
    init {
        require(id.isNotBlank()) { "id must not be blank" }
        require(version.isNotBlank()) { "version must not be blank" }
        require(sha256.matches(Regex("[0-9a-fA-F]{64}"))) { "sha256 must contain 64 hexadecimal characters" }
        require(sizeBytes >= 0) { "sizeBytes must not be negative" }
    }
}

/** Re-openable artifact content. The caller owns and closes each returned stream. */
interface RuntimeArtifact {
    val descriptor: RuntimeArtifactDescriptor

    fun openStream(): InputStream
}

data class RuntimeArtifactManifest @JvmOverloads constructor(
    val runtimeId: String,
    val runtimeVersion: String,
    val artifacts: List<RuntimeArtifactDescriptor>,
    val metadata: Map<String, String> = emptyMap(),
)

data class RuntimeArtifactBundle(
    val manifest: RuntimeArtifactManifest,
    val artifacts: List<RuntimeArtifact>,
) {
    init {
        val expected = manifest.artifacts.associateBy { it.id }
        val actual = artifacts.associateBy { it.descriptor.id }
        require(expected.size == manifest.artifacts.size) { "manifest artifact ids must be unique" }
        require(actual.size == artifacts.size) { "artifact ids must be unique" }
        require(expected.keys == actual.keys) { "manifest and artifact ids must match" }
        require(expected.all { (id, descriptor) -> actual.getValue(id).descriptor == descriptor }) {
            "manifest and artifact descriptors must match"
        }
    }
}

/**
 * Stable, transport-independent representation signed by an artifact publisher.
 * All manifest fields are length-safe encoded and collections are sorted.
 */
object RuntimeArtifactManifestCanonicalizer {
    @JvmStatic
    fun canonicalBytes(manifest: RuntimeArtifactManifest): ByteArray = buildString {
        appendLine("alpine-runtime-manifest-v1")
        appendField("runtimeId", manifest.runtimeId)
        appendField("runtimeVersion", manifest.runtimeVersion)
        manifest.metadata.toSortedMap().forEach { (key, value) ->
            appendField("metadata", key, value)
        }
        manifest.artifacts.sortedBy { it.id }.forEach { artifact ->
            appendField(
                "artifact",
                artifact.id,
                artifact.kind.name,
                artifact.version,
                artifact.sha256.lowercase(),
                artifact.sizeBytes.toString(),
                artifact.abi.orEmpty(),
                artifact.license.orEmpty(),
            )
        }
    }.toByteArray(Charsets.UTF_8)

    private fun StringBuilder.appendField(type: String, vararg values: String) {
        append(type)
        values.forEach { value ->
            append(':')
            append(Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8)))
        }
        append('\n')
    }
}

data class RuntimeArtifactRequest @JvmOverloads constructor(
    val runtimeId: String = "alpine",
    val version: String? = null,
    val supportedAbis: List<String> = emptyList(),
)

fun interface RuntimeArtifactProvider {
    fun resolve(request: RuntimeArtifactRequest): CompletionStage<RuntimeArtifactBundle>
}
