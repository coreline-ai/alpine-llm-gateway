package dev.alpine.llm.runtimeprobe

import dev.alpine.runtime.api.RuntimeArtifactDescriptor
import dev.alpine.runtime.api.RuntimeArtifactKind
import dev.alpine.runtime.api.RuntimeArtifactMetadataKeys
import dev.alpine.runtime.pack.bundled.Alpine321Arm64Pack
import dev.alpine.runtime.pack.bundled.BundledRuntimePack

/**
 * Probe-only PRoot build that classifies the next parent syscall category after
 * a successful guest TIOCGWINSZ and a non-empty post-winsize read. It records
 * no terminal payload or identifiers and is never a production runtime or
 * dynamic-resize implementation.
 */
internal object TtyPostWinsizePostReadFlowTraceRuntimePack {
    const val LAUNCHER_LIBRARY_NAME = "libproot_tty_post_winsize_post_read_flow_trace.so"
    private const val RUNTIME_VERSION = "3.21.3-openminis-8cf13e9-relay28-post-read-syscall-flow-trace"
    private const val PROOT_SHA256 = "408d5a740de408d758c06bc8b31cd81868c88b18d1500b43c27e9edc9a12e5a6"
    private const val PROOT_SIZE_BYTES = 281_176L
    private const val PATCH_SHA256 = "9f0a187272a7abac23346d6b4b71fb4c51b41b718b5b5f168229eee1784a360d"

    fun create(): BundledRuntimePack {
        val production = Alpine321Arm64Pack.create()
        val launcher = RuntimeArtifactDescriptor(
            id = "proot-arm64-v8a-relay28-post-read-syscall-flow-trace",
            kind = RuntimeArtifactKind.NATIVE_LAUNCHER,
            version = "8cf13e997cdc9472997aae19df8050c073c9a86c-relay28-post-read-syscall-flow-trace",
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
                            "proot-tty-relay28-post-read-syscall-flow-trace.patch@$PATCH_SHA256;" +
                            "post-tiocgwinsz-post-read-flow:fixed-stage-trace;alpine:3.21.3;diagnostic-only:true",
                ),
            ),
            rootfsAssetName = production.rootfsAssetName,
        )
    }
}
