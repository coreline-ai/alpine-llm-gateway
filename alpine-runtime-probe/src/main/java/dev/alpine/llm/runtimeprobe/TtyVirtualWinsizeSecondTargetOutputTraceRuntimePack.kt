package dev.alpine.llm.runtimeprobe

import dev.alpine.runtime.api.RuntimeArtifactDescriptor
import dev.alpine.runtime.api.RuntimeArtifactKind
import dev.alpine.runtime.api.RuntimeArtifactMetadataKeys
import dev.alpine.runtime.pack.bundled.Alpine321Arm64Pack
import dev.alpine.runtime.pack.bundled.BundledRuntimePack

/**
 * Probe-only composite: Relay24's private virtual winsize state plus Relay30's
 * second-get fixed lifecycle recorder. It is never a product runtime pack.
 */
internal object TtyVirtualWinsizeSecondTargetOutputTraceRuntimePack {
    const val LAUNCHER_LIBRARY_NAME =
        "libproot_tty_virtual_winsize_second_target_output_trace.so"
    private const val RUNTIME_VERSION =
        "3.21.3-openminis-8cf13e9-relay31-virtual-winsize-second-target-output"
    private const val PROOT_SHA256 =
        "dbb3fc063d6de7abe861768e183623e6a96e8ebfe3b2c3a3c2dda13f8af8350e"
    private const val PROOT_SIZE_BYTES = 285_912L
    private const val PATCH_SHA256 =
        "e2e24b7fe3f9d87d36a726624355a2fc816bd74caddeaaa8b8066c749d89781e"

    fun create(): BundledRuntimePack {
        val production = Alpine321Arm64Pack.create()
        val launcher = RuntimeArtifactDescriptor(
            id = "proot-arm64-v8a-relay31-virtual-winsize-second-target-output",
            kind = RuntimeArtifactKind.NATIVE_LAUNCHER,
            version = "8cf13e997cdc9472997aae19df8050c073c9a86c-" +
                "relay31-virtual-winsize-second-target-output",
            sha256 = PROOT_SHA256,
            sizeBytes = PROOT_SIZE_BYTES,
            abi = "arm64-v8a",
            license = "GPL-2.0-or-later (declared); dev-only diagnostic artifact",
        )
        return BundledRuntimePack(
            manifest = production.manifest.copy(
                runtimeVersion = RUNTIME_VERSION,
                artifacts = production.manifest.artifacts.map { artifact ->
                    if (artifact.kind == RuntimeArtifactKind.NATIVE_LAUNCHER) launcher else artifact
                },
                metadata = production.manifest.metadata + mapOf(
                    RuntimeArtifactMetadataKeys.NATIVE_LAUNCHER_FILE_NAME to LAUNCHER_LIBRARY_NAME,
                    RuntimeArtifactMetadataKeys.SOURCE_REVISION to
                        "proot:8cf13e997cdc9472997aae19df8050c073c9a86c;" +
                            "local-patches:scripts/runtime/experiments/" +
                            "proot-tty-relay31-virtual-winsize-second-target-output-composite.patch@" +
                            "$PATCH_SHA256;virtual-winsize:private-memfd;" +
                            "second-tiocgwinsz-target-output:fixed-stage-trace;" +
                            "alpine:3.21.3;diagnostic-only:true",
                ),
            ),
            rootfsAssetName = production.rootfsAssetName,
        )
    }
}
