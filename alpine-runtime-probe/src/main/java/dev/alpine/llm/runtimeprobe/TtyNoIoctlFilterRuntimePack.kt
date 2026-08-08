package dev.alpine.llm.runtimeprobe

import dev.alpine.runtime.api.RuntimeArtifactDescriptor
import dev.alpine.runtime.api.RuntimeArtifactKind
import dev.alpine.runtime.api.RuntimeArtifactMetadataKeys
import dev.alpine.runtime.pack.bundled.Alpine321Arm64Pack
import dev.alpine.runtime.pack.bundled.BundledRuntimePack

/** Probe-only control that bypasses PRoot's Android ioctl seccomp filter. */
internal object TtyNoIoctlFilterRuntimePack {
    const val LAUNCHER_LIBRARY_NAME = "libproot_tty_no_ioctl_filter.so"
    private const val RUNTIME_VERSION = "3.21.3-openminis-8cf13e9-relay25-no-ioctl-filter"
    private const val PROOT_SHA256 = "a210b3416504e25f05e8b5b9a5ec5542ef1a6e55f9b408910d0a205a3df374e7"
    private const val PROOT_SIZE_BYTES = 279_064L
    private const val PATCH_SHA256 = "7aa22f663a0e23bfcc083189aed230a408d3109eb2e514ef666805b5712c755d"

    fun create(): BundledRuntimePack {
        val production = Alpine321Arm64Pack.create()
        val launcher = RuntimeArtifactDescriptor(
            id = "proot-arm64-v8a-relay25-no-ioctl-filter",
            kind = RuntimeArtifactKind.NATIVE_LAUNCHER,
            version = "8cf13e997cdc9472997aae19df8050c073c9a86c-relay25-no-ioctl-filter",
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
                            "proot-tty-relay25-no-ioctl-filter.patch@$PATCH_SHA256;" +
                            "ioctl-seccomp-filter:disabled-probe-only;alpine:3.21.3;diagnostic-only:true",
                ),
            ),
            rootfsAssetName = production.rootfsAssetName,
        )
    }
}
