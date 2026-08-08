package dev.alpine.llm.runtimeprobe

import dev.alpine.runtime.api.RuntimeArtifactDescriptor
import dev.alpine.runtime.api.RuntimeArtifactKind
import dev.alpine.runtime.api.RuntimeArtifactMetadataKeys
import dev.alpine.runtime.pack.bundled.Alpine321Arm64Pack
import dev.alpine.runtime.pack.bundled.BundledRuntimePack

/**
 * Probe-only PRoot build that classifies the second get-size target's first
 * output lifecycle with fixed enums only. It is never a product runtime pack.
 */
internal object TtySecondTiocgwinszTargetOutputTraceRuntimePack {
    const val LAUNCHER_LIBRARY_NAME = "libproot_tty_second_tiocgwinsz_target_output_trace.so"
    private const val RUNTIME_VERSION = "3.21.3-openminis-8cf13e9-relay30-second-tiocgwinsz-target-output-trace"
    private const val PROOT_SHA256 = "46160c628fc90b984cb4e85faf5eeaa876b8dc638b430ac1e5082f7898030912"
    private const val PROOT_SIZE_BYTES = 280_520L
    private const val PATCH_SHA256 = "eac28ea1f188fbfa8246b7da17ebe492c941921041a5cc967f70478165abfbfb"

    fun create(): BundledRuntimePack {
        val production = Alpine321Arm64Pack.create()
        val launcher = RuntimeArtifactDescriptor(
            id = "proot-arm64-v8a-relay30-second-tiocgwinsz-target-output-trace",
            kind = RuntimeArtifactKind.NATIVE_LAUNCHER,
            version = "8cf13e997cdc9472997aae19df8050c073c9a86c-relay30-second-tiocgwinsz-target-output-trace",
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
                            "proot-tty-relay30-second-tiocgwinsz-target-output-trace.patch@$PATCH_SHA256;" +
                            "post-tiocgwinsz-second-target-output:fixed-stage-trace;alpine:3.21.3;diagnostic-only:true",
                ),
            ),
            rootfsAssetName = production.rootfsAssetName,
        )
    }
}
