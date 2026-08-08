package dev.alpine.runtime.pack.x8664

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

data class X8664RuntimePack(
    val manifest: RuntimeArtifactManifest,
    val rootfsAssetName: String,
) {
    init {
        requireSingleFileName(rootfsAssetName)
        requireSingleFileName(
            manifest.metadata.getValue(RuntimeArtifactMetadataKeys.NATIVE_LAUNCHER_FILE_NAME),
        )
        requireSingleFileName(
            manifest.metadata.getValue(RuntimeArtifactMetadataKeys.NATIVE_LOADER_FILE_NAME),
        )
        require(manifest.artifacts.all { it.abi == "x86_64" }) {
            "x86_64 pack must not contain another ABI"
        }
    }

    private fun requireSingleFileName(value: String) {
        require(value.isNotBlank() && '/' !in value && '\\' !in value && value != "." && value != "..")
    }
}

/** Optional x86_64 pack. Support must not be advertised until the emulator gate passes. */
class X8664RuntimeArtifactProvider(
    context: Context,
    private val pack: X8664RuntimePack = Alpine321X8664Pack.create(),
) : RuntimeArtifactProvider {
    private val appContext = context.applicationContext

    override fun resolve(request: RuntimeArtifactRequest): CompletionStage<RuntimeArtifactBundle> {
        if (request.runtimeId != pack.manifest.runtimeId ||
            (request.version != null && request.version != pack.manifest.runtimeVersion)
        ) {
            return failed(RuntimeOperationException(RuntimeErrorCode.ARTIFACT_NOT_FOUND))
        }
        if (request.supportedAbis.isNotEmpty() && "x86_64" !in request.supportedAbis) {
            return failed(RuntimeOperationException(RuntimeErrorCode.UNSUPPORTED_ABI))
        }
        val nativeDirectory = File(appContext.applicationInfo.nativeLibraryDir)
        val artifacts = pack.manifest.artifacts.map { descriptor ->
            when (descriptor.kind) {
                RuntimeArtifactKind.ROOTFS -> AssetArtifact(
                    descriptor,
                    pack.rootfsAssetName,
                    appContext,
                )
                RuntimeArtifactKind.NATIVE_LAUNCHER -> FileArtifact(
                    descriptor,
                    File(nativeDirectory, Alpine321X8664Pack.LAUNCHER_LIBRARY_NAME),
                )
                RuntimeArtifactKind.NATIVE_LOADER -> FileArtifact(
                    descriptor,
                    File(nativeDirectory, Alpine321X8664Pack.LOADER_LIBRARY_NAME),
                )
                RuntimeArtifactKind.AUXILIARY -> return failed(
                    RuntimeOperationException(RuntimeErrorCode.ARTIFACT_NOT_FOUND),
                )
            }
        }
        return CompletableFuture.completedFuture(RuntimeArtifactBundle(pack.manifest, artifacts))
    }

    private fun <T> failed(error: Throwable): CompletionStage<T> =
        CompletableFuture<T>().also { it.completeExceptionally(error) }
}

private class AssetArtifact(
    override val descriptor: RuntimeArtifactDescriptor,
    private val name: String,
    private val context: Context,
) : RuntimeArtifact {
    override fun openStream(): InputStream = context.assets.open(name)
}

private class FileArtifact(
    override val descriptor: RuntimeArtifactDescriptor,
    private val file: File,
) : RuntimeArtifact {
    override fun openStream(): InputStream = file.inputStream()
}

object Alpine321X8664Pack {
    const val ROOTFS_ASSET_NAME = "alpine-minirootfs-x86_64.tar.gz.asset"
    const val LAUNCHER_LIBRARY_NAME = "libproot.so"
    const val LOADER_LIBRARY_NAME = "libproot-loader.so"

    @JvmStatic
    fun create(): X8664RuntimePack {
        val rootfs = RuntimeArtifactDescriptor(
            id = "alpine-minirootfs-x86_64",
            kind = RuntimeArtifactKind.ROOTFS,
            version = "3.21.3",
            sha256 = "1a694899e406ce55d32334c47ac0b2efb6c06d7e878102d1840892ad44cd5239",
            sizeBytes = 3_507_952,
            abi = "x86_64",
            license = "Alpine package-level licenses; see SPDX SBOM",
        )
        val launcher = RuntimeArtifactDescriptor(
            id = "proot-x86_64",
            kind = RuntimeArtifactKind.NATIVE_LAUNCHER,
            version = "8cf13e997cdc9472997aae19df8050c073c9a86c",
            sha256 = "f0356742d84e200773c0dce508931aef31119ee2917c266a43e80fe90f13fbfd",
            sizeBytes = 292_584,
            abi = "x86_64",
            license = "GPL-2.0-or-later (declared); binary conclusion review required",
        )
        val loader = RuntimeArtifactDescriptor(
            id = "proot-loader-x86_64",
            kind = RuntimeArtifactKind.NATIVE_LOADER,
            version = "8cf13e997cdc9472997aae19df8050c073c9a86c",
            sha256 = "4ca6f14810548610501d012144abeb4c27c1530e2e37201cabf30cab2c39a585",
            sizeBytes = 17_832,
            abi = "x86_64",
            license = "GPL-2.0-or-later (declared); static talloc LGPL-3.0-or-later; " +
                "binary conclusion review required",
        )
        return X8664RuntimePack(
            manifest = RuntimeArtifactManifest(
                runtimeId = "alpine",
                runtimeVersion = "3.21.3-openminis-8cf13e9-unpatched1-x86_64-experimental",
                artifacts = listOf(rootfs, launcher, loader),
                metadata = mapOf(
                    RuntimeArtifactMetadataKeys.MANIFEST_SCHEMA to "1",
                    RuntimeArtifactMetadataKeys.ROOTFS_FORMAT to "tar.gz",
                    RuntimeArtifactMetadataKeys.NATIVE_LAUNCHER_FILE_NAME to LAUNCHER_LIBRARY_NAME,
                    RuntimeArtifactMetadataKeys.NATIVE_LOADER_FILE_NAME to LOADER_LIBRARY_NAME,
                    RuntimeArtifactMetadataKeys.SBOM_FORMAT to "SPDX-2.3",
                    RuntimeArtifactMetadataKeys.SBOM_PATH to
                        "META-INF/alpine-runtime/x86_64/sbom.spdx.json",
                    RuntimeArtifactMetadataKeys.SBOM_SHA256 to
                        "6ba3166b9f7a53f75cc2f333e52fb511b098b4786ff3385f8c1c09b1c3470abe",
                    RuntimeArtifactMetadataKeys.SOURCE_REVISION to
                        "proot:8cf13e997cdc9472997aae19df8050c073c9a86c;" +
                            "local-patches:none;" +
                            "alpine:3.21.3;x86_64:experimental",
                ),
            ),
            rootfsAssetName = ROOTFS_ASSET_NAME,
        )
    }
}
