package dev.alpine.runtime.bridge

import java.io.InputStream
import java.util.concurrent.CompletionStage

data class PythonGatewayArtifactDescriptor(
    val id: String,
    val packageVersion: String,
    val protocolVersion: String,
    val minimumPythonVersion: String,
    val sha256: String,
    val sizeBytes: Long,
    val license: String,
) {
    init {
        require(id.isNotBlank()) { "id must not be blank" }
        require(packageVersion.isNotBlank()) { "packageVersion must not be blank" }
        require(protocolVersion.isNotBlank()) { "protocolVersion must not be blank" }
        require(minimumPythonVersion.matches(Regex("[0-9]+\\.[0-9]+"))) {
            "minimumPythonVersion must use major.minor"
        }
        require(sha256.matches(Regex("[0-9a-fA-F]{64}"))) {
            "sha256 must contain 64 hexadecimal characters"
        }
        require(sizeBytes > 0) { "sizeBytes must be positive" }
        require(license.isNotBlank()) { "license must not be blank" }
    }
}

data class PythonGatewayArtifactManifest(
    val packageId: String,
    val packageVersion: String,
    val protocolVersion: String,
    val entrypoints: Map<String, String>,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(packageId.isNotBlank()) { "packageId must not be blank" }
        require(packageVersion.isNotBlank()) { "packageVersion must not be blank" }
        require(protocolVersion.isNotBlank()) { "protocolVersion must not be blank" }
        require(entrypoints.keys.containsAll(setOf("llmctl", "gatewayd"))) {
            "llmctl and gatewayd entrypoints are required"
        }
        require(entrypoints.values.all { it.startsWith('/') && ".." !in it.split('/') }) {
            "entrypoints must be absolute normalized guest paths"
        }
    }
}

interface PythonGatewayArtifact {
    val descriptor: PythonGatewayArtifactDescriptor

    /** Returns a fresh stream. The caller closes it. */
    fun openStream(): InputStream
}

data class PythonGatewayArtifactBundle(
    val manifest: PythonGatewayArtifactManifest,
    val artifact: PythonGatewayArtifact,
) {
    init {
        require(manifest.packageId == artifact.descriptor.id)
        require(manifest.packageVersion == artifact.descriptor.packageVersion)
        require(manifest.protocolVersion == artifact.descriptor.protocolVersion)
    }
}

fun interface PythonGatewayArtifactProvider {
    fun resolve(): CompletionStage<PythonGatewayArtifactBundle>
}
