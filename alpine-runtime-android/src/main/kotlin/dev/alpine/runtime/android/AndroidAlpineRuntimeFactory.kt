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
) {
    init {
        requireDirectoryName(runtimeDirectoryName, "runtimeDirectoryName")
        requireDirectoryName(workspaceDirectoryName, "workspaceDirectoryName")
        require(maxOutputBytes > 0) { "maxOutputBytes must be positive" }
        require(maxRootfsArchiveBytes > 0) { "maxRootfsArchiveBytes must be positive" }
        require(maxRootfsExtractedBytes > 0) { "maxRootfsExtractedBytes must be positive" }
        require(maxRootfsEntries > 0) { "maxRootfsEntries must be positive" }
        require(maxNativeArtifactBytes > 0) { "maxNativeArtifactBytes must be positive" }
    }

    private fun requireDirectoryName(value: String, label: String) {
        require(value.isNotBlank()) { "$label must not be blank" }
        require('/' !in value && '\\' !in value && value != "." && value != "..") {
            "$label must be a single directory name"
        }
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
