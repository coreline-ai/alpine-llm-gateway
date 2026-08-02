package dev.alpine.runtime.artifact.play

import dev.alpine.runtime.api.RuntimeArtifactKind
import dev.alpine.runtime.api.RuntimeArtifactManifest

data class PlayAssetRuntimePack(
    val assetPackName: String,
    val manifest: RuntimeArtifactManifest,
    val payloadPaths: Map<String, String>,
) {
    init {
        require(PACK_NAME.matches(assetPackName)) { "assetPackName is invalid" }
        val fileBacked = manifest.artifacts.filter {
            it.kind == RuntimeArtifactKind.ROOTFS || it.kind == RuntimeArtifactKind.AUXILIARY
        }
        require(payloadPaths.keys == fileBacked.map { it.id }.toSet()) {
            "payloadPaths must map every rootfs and auxiliary artifact exactly once"
        }
        payloadPaths.values.forEach(::requireSafeRelativePath)
    }

    private fun requireSafeRelativePath(value: String) {
        require(value.isNotBlank() && value.length <= 4096) { "asset path is invalid" }
        require(!value.startsWith('/') && '\\' !in value && '\u0000' !in value)
        require(value.split('/').none { it.isBlank() || it == "." || it == ".." })
    }

    companion object {
        private val PACK_NAME = Regex("[a-z][a-z0-9_]{0,49}")
    }
}
