package dev.alpine.llm.runtimeprobe

import dev.alpine.runtime.api.RuntimeArtifactDescriptor
import dev.alpine.runtime.api.RuntimeArtifactKind
import dev.alpine.runtime.api.RuntimeArtifactMetadataKeys
import dev.alpine.runtime.pack.bundled.Alpine321Arm64Pack
import dev.alpine.runtime.pack.bundled.BundledRuntimePack

/**
 * Deliberately Probe-local runtime manifest for the single PRoot ioctl
 * topology experiment. It must never be consumed by an application module.
 */
internal object TtyDiagnosticRuntimePack {
    const val LAUNCHER_LIBRARY_NAME = "libproot_tty_resize_relay.so"
    const val GUEST_HELPER_LIBRARY_NAME = "libtty_winsize_probe.so"
    const val SESSION_LAUNCHER_LIBRARY_NAME = "libtty_session_virtual_resize_launcher.so"
    const val HOST_PTY_RESIZE_CONTROL_LIBRARY_NAME = "libtty_host_resize_control.so"
    /** Probe-only experiment mode; never a production terminal capability. */
    const val SIGWINCH_RELAY_MODE = TtyProbeMarkers.VIRTUAL_WINSIZE_NO_WINCH
    const val TERMINAL_MODE = "probe-virtual-winsize"
    const val RUNTIME_VERSION = "3.21.3-openminis-8cf13e9-tty-session-relay24-virtual-winsize-memfd"
    const val PROOT_SHA256 = "d423f4242b9213ff0daa38ea60cfa74cec37ca7b4600b0f16b4c0fa5b4c44df7"
    const val PROOT_SIZE_BYTES = 284_696L
    const val PATCH_SHA256 = "2885a7a1a1e8491d5831b067e04361a721fc4e9a99efb5a156ded36d651c96aa"

    fun create(): BundledRuntimePack {
        val production = Alpine321Arm64Pack.create()
        val launcher = RuntimeArtifactDescriptor(
            id = "proot-arm64-v8a-tty-resize-relay24-virtual-winsize-memfd",
            kind = RuntimeArtifactKind.NATIVE_LAUNCHER,
            version = "8cf13e997cdc9472997aae19df8050c073c9a86c-tty-resize-relay24-virtual-winsize-memfd",
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
                            "proot-tty-resize-relay24-virtual-winsize-memfd.patch@$PATCH_SHA256;" +
                            "virtual-winsize:event-driven-private-memfd-no-post-launch-signal;" +
                            "alpine:3.21.3;diagnostic-only:true",
                ),
            ),
            rootfsAssetName = production.rootfsAssetName,
        )
    }
}
