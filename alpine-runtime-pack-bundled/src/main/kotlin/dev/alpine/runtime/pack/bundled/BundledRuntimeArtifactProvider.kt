package dev.alpine.runtime.pack.bundled

import android.content.Context
import dev.alpine.runtime.api.RuntimeArtifact
import dev.alpine.runtime.api.RuntimeArtifactBundle
import dev.alpine.runtime.api.RuntimeArtifactDescriptor
import dev.alpine.runtime.api.RuntimeArtifactKind
import dev.alpine.runtime.api.RuntimeArtifactManifest
import dev.alpine.runtime.api.RuntimeArtifactMetadataKeys
import dev.alpine.runtime.api.RuntimeArtifactProvider
import dev.alpine.runtime.api.RuntimeArtifactRequest
import dev.alpine.runtime.api.RuntimeErrorCode
import dev.alpine.runtime.api.RuntimeOperationException
import java.io.File
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

data class BundledRuntimePack(
    val manifest: RuntimeArtifactManifest,
    val rootfsAssetName: String,
) {
    init {
        require(rootfsAssetName.isNotBlank()) { "rootfsAssetName must not be blank" }
        require('/' !in rootfsAssetName && '\\' !in rootfsAssetName)
        requireSingleFileName(
            manifest.metadata.getValue(RuntimeArtifactMetadataKeys.NATIVE_LAUNCHER_FILE_NAME),
        )
        requireSingleFileName(
            manifest.metadata.getValue(RuntimeArtifactMetadataKeys.NATIVE_LOADER_FILE_NAME),
        )
    }

    private fun requireSingleFileName(value: String) {
        require(value.isNotBlank() && '/' !in value && '\\' !in value && value != "." && value != "..")
    }
}

/**
 * Resolves assets and packaged JNI executables from the consuming APK.
 *
 * The binary payload remains in this optional artifact/module boundary. Apps that do not depend
 * on a pack do not receive rootfs or PRoot payloads.
 */
class BundledRuntimeArtifactProvider(
    context: Context,
    private val pack: BundledRuntimePack,
) : RuntimeArtifactProvider {
    private val appContext = context.applicationContext

    override fun resolve(request: RuntimeArtifactRequest): CompletionStage<RuntimeArtifactBundle> {
        if (request.runtimeId != pack.manifest.runtimeId) {
            return failedFuture(RuntimeOperationException(RuntimeErrorCode.ARTIFACT_NOT_FOUND))
        }
        if (request.version != null && request.version != pack.manifest.runtimeVersion) {
            return failedFuture(RuntimeOperationException(RuntimeErrorCode.ARTIFACT_NOT_FOUND))
        }
        val supported = request.supportedAbis.toSet()
        val nativeDescriptors = pack.manifest.artifacts.filter {
            it.kind == RuntimeArtifactKind.NATIVE_LAUNCHER ||
                it.kind == RuntimeArtifactKind.NATIVE_LOADER
        }
        if (supported.isNotEmpty() && nativeDescriptors.none { it.abi in supported }) {
            return failedFuture(RuntimeOperationException(RuntimeErrorCode.UNSUPPORTED_ABI))
        }

        val launcherName = pack.manifest.metadata.getValue(
            RuntimeArtifactMetadataKeys.NATIVE_LAUNCHER_FILE_NAME,
        )
        val loaderName = pack.manifest.metadata.getValue(
            RuntimeArtifactMetadataKeys.NATIVE_LOADER_FILE_NAME,
        )
        val nativeDirectory = File(appContext.applicationInfo.nativeLibraryDir)
        val artifacts = pack.manifest.artifacts.map { descriptor ->
            when (descriptor.kind) {
                RuntimeArtifactKind.ROOTFS -> AssetRuntimeArtifact(
                    descriptor,
                    pack.rootfsAssetName,
                    appContext,
                )
                RuntimeArtifactKind.NATIVE_LAUNCHER -> FileRuntimeArtifact(
                    descriptor,
                    File(nativeDirectory, launcherName),
                )
                RuntimeArtifactKind.NATIVE_LOADER -> FileRuntimeArtifact(
                    descriptor,
                    File(nativeDirectory, loaderName),
                )
                RuntimeArtifactKind.AUXILIARY -> throw IllegalArgumentException(
                    "BundledRuntimePack requires an explicit source for auxiliary artifacts",
                )
            }
        }
        return CompletableFuture.completedFuture(RuntimeArtifactBundle(pack.manifest, artifacts))
    }

    private fun <T> failedFuture(error: Throwable): CompletionStage<T> =
        CompletableFuture<T>().also { it.completeExceptionally(error) }
}

private class AssetRuntimeArtifact(
    override val descriptor: RuntimeArtifactDescriptor,
    private val assetName: String,
    private val context: Context,
) : RuntimeArtifact {
    override fun openStream(): InputStream = context.assets.open(assetName)
}

private class FileRuntimeArtifact(
    override val descriptor: RuntimeArtifactDescriptor,
    private val file: File,
) : RuntimeArtifact {
    override fun openStream(): InputStream = file.inputStream()
}

object Alpine321Arm64Pack {
    const val ROOTFS_ASSET_NAME = "alpine-minirootfs.tar.gz.asset"
    const val LAUNCHER_LIBRARY_NAME = "libproot.so"
    const val LOADER_LIBRARY_NAME = "libproot-loader.so"

    @JvmStatic
    fun create(): BundledRuntimePack {
        val rootfs = RuntimeArtifactDescriptor(
            id = "alpine-minirootfs-aarch64",
            kind = RuntimeArtifactKind.ROOTFS,
            version = "3.21.3",
            sha256 = "ead8a4b37867bd19e7417dd078748e2312c0aea364403d96758d63ea8ff261ea",
            sizeBytes = 3_850_365,
            abi = "arm64-v8a",
            license = "Alpine package-level licenses; see SPDX SBOM",
        )
        val launcher = RuntimeArtifactDescriptor(
            id = "proot-arm64-v8a",
            kind = RuntimeArtifactKind.NATIVE_LAUNCHER,
            version = "8cf13e997cdc9472997aae19df8050c073c9a86c",
            sha256 = "336627708766ac2065485c4759048412761ea749787ca3111889f225585cfa4c",
            sizeBytes = 280_440,
            abi = "arm64-v8a",
            license = "GPL-2.0-or-later (declared); binary conclusion review required",
        )
        val loader = RuntimeArtifactDescriptor(
            id = "proot-loader-arm64-v8a",
            kind = RuntimeArtifactKind.NATIVE_LOADER,
            version = "8cf13e997cdc9472997aae19df8050c073c9a86c",
            sha256 = "12d2b63e897fd91a334fce23edea5d2419cae4d5fd2a369f05d03ab75682add0",
            sizeBytes = 17_624,
            abi = "arm64-v8a",
            license = "GPL-2.0-or-later (declared); static talloc LGPL-3.0-or-later; " +
                "binary conclusion review required",
        )
        return BundledRuntimePack(
            manifest = RuntimeArtifactManifest(
                runtimeId = "alpine",
                runtimeVersion = "3.21.3-openminis-8cf13e9-winsize1",
                artifacts = listOf(rootfs, launcher, loader),
                metadata = mapOf(
                    RuntimeArtifactMetadataKeys.MANIFEST_SCHEMA to "1",
                    RuntimeArtifactMetadataKeys.ROOTFS_FORMAT to "tar.gz",
                    RuntimeArtifactMetadataKeys.NATIVE_LAUNCHER_FILE_NAME to LAUNCHER_LIBRARY_NAME,
                    RuntimeArtifactMetadataKeys.NATIVE_LOADER_FILE_NAME to LOADER_LIBRARY_NAME,
                    RuntimeArtifactMetadataKeys.SBOM_FORMAT to "SPDX-2.3",
                    RuntimeArtifactMetadataKeys.SBOM_PATH to
                        "META-INF/alpine-runtime/sbom.spdx.json",
                    RuntimeArtifactMetadataKeys.SBOM_SHA256 to
                        "2bc527c3aa878765bab4393437886d11caa6f3dca86ba345e59b5c29f7d58b2e",
                    RuntimeArtifactMetadataKeys.SOURCE_REVISION to
                        "proot:8cf13e997cdc9472997aae19df8050c073c9a86c;" +
                            "patch:proot-android-winsize@20726d1ccf9b;alpine:3.21.3",
                ),
            ),
            rootfsAssetName = ROOTFS_ASSET_NAME,
        )
    }
}
