package dev.alpine.llm.runtimeprobe

import dev.alpine.runtime.api.RuntimeArtifactDescriptor
import dev.alpine.runtime.api.RuntimeArtifactKind
import dev.alpine.runtime.api.RuntimeArtifactMetadataKeys
import dev.alpine.runtime.pack.bundled.Alpine321Arm64Pack
import dev.alpine.runtime.pack.bundled.BundledRuntimePack

/**
 * Probe-only PRoot build that classifies the fixed parent/child flow after a
 * successful guest TIOCGWINSZ. It records no terminal payload or identifiers
 * and is never a production runtime or dynamic-resize implementation.
 */
internal object TtyPostWinsizeCommandFlowTraceRuntimePack {
    const val LAUNCHER_LIBRARY_NAME = "libproot_tty_post_winsize_command_flow_trace.so"
    private const val RUNTIME_VERSION = "3.21.3-openminis-8cf13e9-relay27-post-tiocgwinsz-command-flow-trace"
    private const val PROOT_SHA256 = "43819777bffd234ac0f2d98a8efa3e53362f796e121d99246f8beb3ebb84428d"
    private const val PROOT_SIZE_BYTES = 280_296L
    private const val PATCH_SHA256 = "e0f3bcfbe4dd2c34c72135e83bc2be5e6543e1829a7bc3003e63ef2fb0a35830"

    fun create(): BundledRuntimePack {
        val production = Alpine321Arm64Pack.create()
        val launcher = RuntimeArtifactDescriptor(
            id = "proot-arm64-v8a-relay27-post-tiocgwinsz-command-flow-trace",
            kind = RuntimeArtifactKind.NATIVE_LAUNCHER,
            version = "8cf13e997cdc9472997aae19df8050c073c9a86c-relay27-post-tiocgwinsz-command-flow-trace",
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
                            "proot-tty-relay27-post-tiocgwinsz-command-flow-trace.patch@$PATCH_SHA256;" +
                            "post-tiocgwinsz-command-flow:fixed-stage-trace;alpine:3.21.3;diagnostic-only:true",
                ),
            ),
            rootfsAssetName = production.rootfsAssetName,
        )
    }
}
