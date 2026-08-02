package dev.alpine.runtime.artifact.play

import android.content.Context
import dev.alpine.runtime.api.RuntimeArtifact
import dev.alpine.runtime.api.RuntimeArtifactBundle
import dev.alpine.runtime.api.RuntimeArtifactDescriptor
import dev.alpine.runtime.api.RuntimeArtifactKind
import dev.alpine.runtime.api.RuntimeArtifactMetadataKeys
import dev.alpine.runtime.api.RuntimeArtifactProvider
import dev.alpine.runtime.api.RuntimeArtifactRequest
import dev.alpine.runtime.api.RuntimeErrorCode
import dev.alpine.runtime.api.RuntimeOperationException
import java.io.File
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Supplies rootfs/auxiliary files from a Play asset pack while keeping executable PRoot/loader
 * libraries in the base APK's nativeLibraryDir. Every stream is still checksum/size verified by
 * the runtime installer.
 */
class PlayAssetRuntimeArtifactProvider internal constructor(
    private val pack: PlayAssetRuntimePack,
    private val fetchTimeoutMillis: Long,
    private val client: PlayAssetPackClient,
    private val nativeDirectory: File,
    private val scheduler: ScheduledExecutorService,
) : RuntimeArtifactProvider {

    @JvmOverloads
    constructor(
        context: Context,
        pack: PlayAssetRuntimePack,
        fetchTimeoutMillis: Long = 5L * 60L * 1000L,
    ) : this(
        pack = pack,
        fetchTimeoutMillis = fetchTimeoutMillis,
        client = GooglePlayAssetPackClient(context),
        nativeDirectory = File(context.applicationInfo.nativeLibraryDir),
        scheduler = SHARED_SCHEDULER,
    )

    init {
        require(fetchTimeoutMillis > 0) { "fetchTimeoutMillis must be positive" }
    }

    override fun resolve(request: RuntimeArtifactRequest): CompletionStage<RuntimeArtifactBundle> {
        validateRequest(request)?.let { return failed(it) }
        installedDirectory()?.let { return resolveFromDirectory(it) }

        val result = CompletableFuture<RuntimeArtifactBundle>()
        val subscription = AtomicReference<PlayAssetFetchSubscription?>()
        val fetchReachedTerminalState = AtomicBoolean(false)
        val timeout = scheduler.schedule(
            { result.completeExceptionally(RuntimeOperationException(RuntimeErrorCode.ARTIFACT_NOT_FOUND)) },
            fetchTimeoutMillis,
            TimeUnit.MILLISECONDS,
        )
        result.whenComplete { _, _ ->
            timeout.cancel(false)
            subscription.getAndSet(null)?.close()
            if (!fetchReachedTerminalState.get()) runCatching { client.cancel(pack.assetPackName) }
        }
        val registered = client.fetch(pack.assetPackName) { state ->
            when (state) {
                PlayAssetFetchState.COMPLETED -> {
                    fetchReachedTerminalState.set(true)
                    val directory = installedDirectory()
                    if (directory == null) {
                        result.completeExceptionally(
                            RuntimeOperationException(RuntimeErrorCode.ARTIFACT_NOT_FOUND),
                        )
                    } else {
                        resolveFromDirectory(directory).whenComplete { bundle, error ->
                            if (error != null) result.completeExceptionally(error) else result.complete(bundle)
                        }
                    }
                }
                PlayAssetFetchState.FAILED,
                PlayAssetFetchState.CANCELED,
                -> {
                    fetchReachedTerminalState.set(true)
                    result.completeExceptionally(RuntimeOperationException(RuntimeErrorCode.ARTIFACT_NOT_FOUND))
                }
                PlayAssetFetchState.FETCHING,
                PlayAssetFetchState.WAITING_FOR_USER,
                -> Unit
            }
        }
        subscription.set(registered)
        if (result.isDone) subscription.getAndSet(null)?.close()
        return result
    }

    private fun validateRequest(request: RuntimeArtifactRequest): RuntimeOperationException? {
        if (request.runtimeId != pack.manifest.runtimeId ||
            request.version != null && request.version != pack.manifest.runtimeVersion
        ) {
            return RuntimeOperationException(RuntimeErrorCode.ARTIFACT_NOT_FOUND)
        }
        val nativeAbis = pack.manifest.artifacts.filter {
            it.kind == RuntimeArtifactKind.NATIVE_LAUNCHER || it.kind == RuntimeArtifactKind.NATIVE_LOADER
        }.mapNotNull { it.abi }.toSet()
        if (request.supportedAbis.isNotEmpty() && nativeAbis.isNotEmpty() &&
            request.supportedAbis.none { it in nativeAbis }
        ) {
            return RuntimeOperationException(RuntimeErrorCode.UNSUPPORTED_ABI)
        }
        return null
    }

    private fun installedDirectory(): File? = client.installedAssetsDirectory(pack.assetPackName)

    private fun resolveFromDirectory(directory: File): CompletionStage<RuntimeArtifactBundle> = try {
        val root = directory.canonicalFile
        val launcher = pack.manifest.metadata[RuntimeArtifactMetadataKeys.NATIVE_LAUNCHER_FILE_NAME]
        val loader = pack.manifest.metadata[RuntimeArtifactMetadataKeys.NATIVE_LOADER_FILE_NAME]
        val artifacts = pack.manifest.artifacts.map { descriptor ->
            val file = when (descriptor.kind) {
                RuntimeArtifactKind.ROOTFS,
                RuntimeArtifactKind.AUXILIARY,
                -> safeFile(root, pack.payloadPaths.getValue(descriptor.id))
                RuntimeArtifactKind.NATIVE_LAUNCHER -> nativeFile(launcher)
                RuntimeArtifactKind.NATIVE_LOADER -> nativeFile(loader)
            }
            require(file.isFile) { "runtime artifact is missing" }
            FileRuntimeArtifact(descriptor, file)
        }
        CompletableFuture.completedFuture(RuntimeArtifactBundle(pack.manifest, artifacts))
    } catch (_: Exception) {
        failed(RuntimeOperationException(RuntimeErrorCode.ARTIFACT_NOT_FOUND))
    }

    private fun safeFile(root: File, relativePath: String): File {
        val candidate = File(root, relativePath).canonicalFile
        require(candidate.toPath().startsWith(root.toPath())) { "asset path escaped its pack" }
        return candidate
    }

    private fun nativeFile(name: String?): File {
        require(name != null && name.isNotBlank() && '/' !in name && '\\' !in name)
        return File(nativeDirectory, name)
    }

    private fun <T> failed(error: Throwable): CompletionStage<T> =
        CompletableFuture<T>().also { it.completeExceptionally(error) }

    private class FileRuntimeArtifact(
        override val descriptor: RuntimeArtifactDescriptor,
        private val file: File,
    ) : RuntimeArtifact {
        override fun openStream(): InputStream = file.inputStream()
    }

    companion object {
        private val SHARED_SCHEDULER = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "alpine-play-asset-timeout").apply { isDaemon = true }
        }
    }
}
