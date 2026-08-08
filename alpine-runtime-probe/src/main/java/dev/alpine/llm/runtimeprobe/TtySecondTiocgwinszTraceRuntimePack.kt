package dev.alpine.llm.runtimeprobe

import dev.alpine.runtime.api.RuntimeArtifactDescriptor
import dev.alpine.runtime.api.RuntimeArtifactKind
import dev.alpine.runtime.api.RuntimeArtifactMetadataKeys
import dev.alpine.runtime.pack.bundled.Alpine321Arm64Pack
import dev.alpine.runtime.pack.bundled.BundledRuntimePack

/**
 * Probe-only PRoot build that classifies whether the next guest TIOCGWINSZ
 * after the first successful get enters and returns. It records no payload,
 * identifiers, syscall arguments, or errno value and is never a product pack.
 */
internal object TtySecondTiocgwinszTraceRuntimePack {
    const val LAUNCHER_LIBRARY_NAME = "libproot_tty_second_tiocgwinsz_trace.so"
    private const val RUNTIME_VERSION = "3.21.3-openminis-8cf13e9-relay29-second-tiocgwinsz-trace"
    private const val PROOT_SHA256 = "393dde8b949be2f1579215741aeeb9784dce533738dd87509c2dc8a50c8ab4b2"
    private const val PROOT_SIZE_BYTES = 279_880L
    private const val PATCH_SHA256 = "160b5d285d716785d6b9111570859f3ae865f178f36b699c0fce223b2d312f77"

    fun create(): BundledRuntimePack {
        val production = Alpine321Arm64Pack.create()
        val launcher = RuntimeArtifactDescriptor(
            id = "proot-arm64-v8a-relay29-second-tiocgwinsz-trace",
            kind = RuntimeArtifactKind.NATIVE_LAUNCHER,
            version = "8cf13e997cdc9472997aae19df8050c073c9a86c-relay29-second-tiocgwinsz-trace",
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
                            "proot-tty-relay29-second-tiocgwinsz-trace.patch@$PATCH_SHA256;" +
                            "post-tiocgwinsz-second-get:fixed-stage-trace;alpine:3.21.3;diagnostic-only:true",
                ),
            ),
            rootfsAssetName = production.rootfsAssetName,
        )
    }
}
