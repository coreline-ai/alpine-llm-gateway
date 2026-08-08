package dev.alpine.llm.runtimeprobe

import dev.alpine.runtime.api.RuntimeArtifactDescriptor
import dev.alpine.runtime.api.RuntimeArtifactKind
import dev.alpine.runtime.api.RuntimeArtifactMetadataKeys
import dev.alpine.runtime.pack.bundled.Alpine321Arm64Pack
import dev.alpine.runtime.pack.bundled.BundledRuntimePack

/**
 * Probe-only PRoot build that records the fixed lifecycle between a successful
 * guest TIOCGWINSZ and the next observed read syscall. Read coverage requires
 * the Probe seccomp-off control. It is diagnostic-only, never a production
 * runtime or dynamic-resize implementation.
 */
internal object TtyPostWinsizeInputTraceRuntimePack {
    const val LAUNCHER_LIBRARY_NAME = "libproot_tty_post_winsize_input_trace.so"
    private const val RUNTIME_VERSION = "3.21.3-openminis-8cf13e9-relay26-post-tiocgwinsz-input-trace"
    private const val PROOT_SHA256 = "8f441a25ec423c224f68421797c099283b5cc2d0261c286bcb0125bc76f44a75"
    private const val PROOT_SIZE_BYTES = 280_040L
    private const val PATCH_SHA256 = "31a0dc2521e17e8853e81db25f88c93c349572ca4ce01d83921ed580ca6bebbf"

    fun create(): BundledRuntimePack {
        val production = Alpine321Arm64Pack.create()
        val launcher = RuntimeArtifactDescriptor(
            id = "proot-arm64-v8a-relay26-post-tiocgwinsz-input-trace",
            kind = RuntimeArtifactKind.NATIVE_LAUNCHER,
            version = "8cf13e997cdc9472997aae19df8050c073c9a86c-relay26-post-tiocgwinsz-input-trace",
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
                            "proot-tty-relay26-post-tiocgwinsz-input-trace.patch@$PATCH_SHA256;" +
                            "post-tiocgwinsz-input:fixed-stage-trace;alpine:3.21.3;diagnostic-only:true",
                ),
            ),
            rootfsAssetName = production.rootfsAssetName,
        )
    }
}
