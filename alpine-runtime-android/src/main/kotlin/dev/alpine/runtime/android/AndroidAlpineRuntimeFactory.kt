package dev.alpine.runtime.android

import android.content.Context
import dev.alpine.runtime.api.AlpineRuntimeManager
import dev.alpine.runtime.api.RuntimeArtifactProvider
import dev.alpine.runtime.api.RuntimeEnvironmentContributor
import dev.alpine.runtime.api.RuntimeEventSink
import dev.alpine.runtime.api.RuntimeHostProcessListener
import dev.alpine.runtime.android.internal.AndroidAlpineRuntimeManager

/**
 * Android-only construction boundary. No Context escapes into alpine-runtime-api.
 *
 * Keeping this as an interface lets hosts inject the production factory or a test factory without
 * coupling the core contract to Android framework types.
 */
fun interface AndroidAlpineRuntimeFactory {
    fun create(context: Context, configuration: AndroidRuntimeConfiguration): AlpineRuntimeManager
}

data class AndroidRuntimeConfiguration @JvmOverloads constructor(
    val artifactProvider: RuntimeArtifactProvider,
    val environmentContributors: List<RuntimeEnvironmentContributor> = emptyList(),
    val eventSink: RuntimeEventSink = RuntimeEventSink { },
    val processListener: RuntimeHostProcessListener = RuntimeHostProcessListener { },
    val runtimeDirectoryName: String = "alpine-runtime-sdk",
    val workspaceDirectoryName: String = "workspace",
    val maxOutputBytes: Int = 2 * 1024 * 1024,
    val maxRootfsArchiveBytes: Long = 256L * 1024 * 1024,
    val maxRootfsExtractedBytes: Long = 512L * 1024 * 1024,
    val maxRootfsEntries: Int = 100_000,
    val maxNativeArtifactBytes: Long = 64L * 1024 * 1024,
    /**
     * Probe-only switch for a PRoot ioctl topology investigation.
     *
     * The Android adapter additionally requires a debuggable app and a matching
     * manifest opt-in before a record file is created. Production hosts keep
     * this false and never package the diagnostic PRoot launcher.
     */
    val enableTtyIoctlDiagnostics: Boolean = false,
    /**
     * Probe-only PRoot execution-mode isolation. When enabled together with the
     * debug-gated tty diagnostic, the host PRoot process runs without its
     * seccomp acceleration path. This is not guest-controlled and must never
     * be enabled by a product host.
     */
    val disableProotSeccompForTtyDiagnostic: Boolean = false,
    /**
     * Optional Probe-only executable packaged in the app native-library directory.
     * It is considered only with [enableTtyIoctlDiagnostics], a debuggable app,
     * and the diagnostic manifest opt-in; release hosts leave this unset.
     */
    val ttyDiagnosticGuestHelperFileName: String? = null,
    /**
     * Probe-only direct session leader that execs the diagnostic PRoot tracer
     * without a `setsid` wrapper PID. It is valid only with the debug-only
     * diagnostic gate and must never be configured by a product host.
     */
    val ttyDiagnosticSessionLauncherFileName: String? = null,
    /**
     * Enables the Probe-only virtual winsize control experiment. The matching
     * session launcher and PRoot artifact are debug-gated and never package
     * into product hosts. This changes only diagnostic resize routing; it is
     * not a production dynamic-resize capability.
     */
    val ttyDiagnosticVirtualResize: Boolean = false,
    /**
     * Probe-only negative control for the virtual-winsize experiment. The
     * supervisor validates and acknowledges its bounded request but does not
     * write the private memfd. It is valid only with the virtual diagnostic
     * experiment and is never a product terminal setting.
     */
    val ttyDiagnosticVirtualResizeNoWrite: Boolean = false,
    /**
     * Probe-only control that does not invoke the virtual-winsize transport.
     * It distinguishes a session-lifecycle failure from a control-request
     * failure and is never a product terminal setting.
     */
    val ttyDiagnosticVirtualResizeNoRequest: Boolean = false,
    /**
     * Probe-only topology control. It leaves the direct PRoot process group
     * as the session launcher's foreground group instead of moving the first
     * traced guest into a separate foreground group. Never a product setting.
     */
    val ttyDiagnosticDisablePrimaryTraceeForeground: Boolean = false,
    /**
     * Probe-only PRoot post-TIOCGWINSZ source recorder. It records only fixed
     * lifecycle categories, never terminal data, and must only be enabled by
     * the debug-gated diagnostic host.
     */
    val ttyDiagnosticPostWinsizeInputTrace: Boolean = false,
    val ttyDiagnosticCanonicalizeStdio: Boolean = false,
    /**
     * Probe-only exposure of the production forkpty direct-exec architecture.
     *
     * It is valid only behind the existing debuggable+manifest diagnostic gate and lets a
     * controlled instrumentation fixture exercise kernel-delivered resize on an unpatched PRoot
     * binary. Product hosts keep this false until the complete physical acceptance matrix passes.
     */
    val ttyDiagnosticForkPtyDirect: Boolean = false,
) {
    init {
        requireDirectoryName(runtimeDirectoryName, "runtimeDirectoryName")
        requireDirectoryName(workspaceDirectoryName, "workspaceDirectoryName")
        require(maxOutputBytes > 0) { "maxOutputBytes must be positive" }
        require(maxRootfsArchiveBytes > 0) { "maxRootfsArchiveBytes must be positive" }
        require(maxRootfsExtractedBytes > 0) { "maxRootfsExtractedBytes must be positive" }
        require(maxRootfsEntries > 0) { "maxRootfsEntries must be positive" }
        require(maxNativeArtifactBytes > 0) { "maxNativeArtifactBytes must be positive" }
        require(!disableProotSeccompForTtyDiagnostic || enableTtyIoctlDiagnostics) {
            "disableProotSeccompForTtyDiagnostic requires enableTtyIoctlDiagnostics"
        }
        require(!ttyDiagnosticVirtualResize || enableTtyIoctlDiagnostics) {
            "ttyDiagnosticVirtualResize requires enableTtyIoctlDiagnostics"
        }
        require(!ttyDiagnosticVirtualResizeNoWrite || ttyDiagnosticVirtualResize) {
            "ttyDiagnosticVirtualResizeNoWrite requires ttyDiagnosticVirtualResize"
        }
        require(!ttyDiagnosticVirtualResizeNoRequest || ttyDiagnosticVirtualResize) {
            "ttyDiagnosticVirtualResizeNoRequest requires ttyDiagnosticVirtualResize"
        }
        require(!ttyDiagnosticDisablePrimaryTraceeForeground || enableTtyIoctlDiagnostics) {
            "ttyDiagnosticDisablePrimaryTraceeForeground requires enableTtyIoctlDiagnostics"
        }
        require(!ttyDiagnosticPostWinsizeInputTrace || enableTtyIoctlDiagnostics) {
            "ttyDiagnosticPostWinsizeInputTrace requires enableTtyIoctlDiagnostics"
        }
        require(!ttyDiagnosticCanonicalizeStdio || enableTtyIoctlDiagnostics) {
            "ttyDiagnosticCanonicalizeStdio requires enableTtyIoctlDiagnostics"
        }
        require(!ttyDiagnosticForkPtyDirect || enableTtyIoctlDiagnostics) {
            "ttyDiagnosticForkPtyDirect requires enableTtyIoctlDiagnostics"
        }
        ttyDiagnosticGuestHelperFileName?.let { fileName ->
            require(fileName.matches(SAFE_NATIVE_LIBRARY_FILE_NAME)) {
                "ttyDiagnosticGuestHelperFileName must be a native library filename"
            }
        }
        ttyDiagnosticSessionLauncherFileName?.let { fileName ->
            require(fileName.matches(SAFE_NATIVE_LIBRARY_FILE_NAME)) {
                "ttyDiagnosticSessionLauncherFileName must be a native library filename"
            }
        }
    }

    private fun requireDirectoryName(value: String, label: String) {
        require(value.isNotBlank()) { "$label must not be blank" }
        require('/' !in value && '\\' !in value && value != "." && value != "..") {
            "$label must be a single directory name"
        }
    }

    private companion object {
        val SAFE_NATIVE_LIBRARY_FILE_NAME = Regex("lib[A-Za-z0-9_.-]+\\.so")
    }
}

/** Production Android factory. Host apps remain responsible for Service/background policy. */
class DefaultAndroidAlpineRuntimeFactory : AndroidAlpineRuntimeFactory {
    override fun create(
        context: Context,
        configuration: AndroidRuntimeConfiguration,
    ): AlpineRuntimeManager = AndroidAlpineRuntimeManager(
        appContext = context.applicationContext,
        configuration = configuration,
    )
}
