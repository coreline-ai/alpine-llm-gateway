package dev.alpine.runtime.android.internal

import dev.alpine.runtime.api.RuntimeArtifact
import dev.alpine.runtime.api.RuntimeArtifactBundle
import dev.alpine.runtime.api.RuntimeArtifactDescriptor
import dev.alpine.runtime.api.RuntimeArtifactKind
import dev.alpine.runtime.api.RuntimeArtifactMetadataKeys
import dev.alpine.runtime.api.RuntimeErrorCode
import dev.alpine.runtime.api.RuntimeInstallResult
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.CancellationException

internal data class RuntimeInstallLimits(
    val maxRootfsArchiveBytes: Long,
    val maxRootfsExtractedBytes: Long,
    val maxRootfsEntries: Int,
    val maxNativeArtifactBytes: Long,
)

internal enum class RuntimeInstallationCondition {
    NOT_INSTALLED,
    READY,
    REPAIR_REQUIRED,
}

internal data class RuntimeInstallationInspection(
    val condition: RuntimeInstallationCondition,
    val runtimeVersion: String? = null,
    val abi: String? = null,
    val checks: Map<String, Boolean> = emptyMap(),
)

internal data class InstalledRuntime(
    val runtimeId: String,
    val runtimeVersion: String,
    val abi: String,
    val rootfsDirectory: File,
    val workspaceDirectory: File,
    val launcher: File,
    val loader: File,
)

internal class RuntimeInstallException(
    val errorCode: RuntimeErrorCode,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal enum class RuntimeInstallCheckpoint {
    BEFORE_ACTIVATION,
    AFTER_BACKUP,
    AFTER_ROOTFS_ACTIVATION,
    AFTER_MARKER_ACTIVATION,
}

internal fun interface RuntimeInstallFaultInjector {
    fun checkpoint(checkpoint: RuntimeInstallCheckpoint)

    companion object {
        val NONE = RuntimeInstallFaultInjector { }
    }
}

internal class RuntimeArtifactInstaller(
    private val runtimeDirectory: File,
    private val workspaceDirectory: File,
    private val nativeLibraryDirectory: File,
    private val limits: RuntimeInstallLimits,
    private val faultInjector: RuntimeInstallFaultInjector = RuntimeInstallFaultInjector.NONE,
) {
    private val rootfs = File(runtimeDirectory, "rootfs")
    private val rootfsStaging = File(runtimeDirectory, "rootfs.installing")
    private val rootfsPrevious = File(runtimeDirectory, "rootfs.previous")
    private val archiveStaging = File(runtimeDirectory, "rootfs.installing.tar.gz")
    private val marker = File(runtimeDirectory, "runtime.properties")
    private val markerNext = File(runtimeDirectory, "runtime.properties.next")
    private val markerPrevious = File(runtimeDirectory, "runtime.properties.previous")
    private val activationPending = File(runtimeDirectory, "activation.pending")

    @Synchronized
    fun recoverInterruptedActivation() {
        runtimeDirectory.mkdirs()
        workspaceDirectory.mkdirs()
        if (activationPending.isFile) {
            val pending = readProperties(activationPending)
            val expectedFingerprint = pending?.getProperty(KEY_EXPECTED_FINGERPRINT)
            val active = readProperties(marker)
            val committed = expectedFingerprint != null &&
                active != null &&
                fingerprint(active) == expectedFingerprint &&
                rootfsShellExists(rootfs)
            if (committed) {
                rootfsPrevious.deleteRecursively()
                markerPrevious.delete()
                clearTransactionFiles()
                return
            }
            rollbackActivation()
            return
        }

        if (!rootfs.exists() && rootfsPrevious.exists()) {
            move(rootfsPrevious, rootfs)
        }
        if (!marker.exists() && markerPrevious.exists()) {
            move(markerPrevious, marker)
        }
        rootfsStaging.deleteRecursively()
        archiveStaging.delete()
        markerNext.delete()
    }

    @Synchronized
    fun inspect(): RuntimeInstallationInspection {
        recoverInterruptedActivation()
        val properties = readProperties(marker)
        if (properties == null) {
            val partial = rootfs.exists() || markerPrevious.exists() || rootfsPrevious.exists()
            return RuntimeInstallationInspection(
                if (partial) RuntimeInstallationCondition.REPAIR_REQUIRED
                else RuntimeInstallationCondition.NOT_INSTALLED,
            )
        }
        val launcher = nativeFile(properties.getProperty(KEY_LAUNCHER_FILE))
        val loader = nativeFile(properties.getProperty(KEY_LOADER_FILE))
        val checks = linkedMapOf(
            "marker" to true,
            "rootfsShell" to rootfsShellExists(rootfs),
            "launcher" to verifyExecutable(launcher, properties.getProperty(KEY_LAUNCHER_SHA256)),
            "loader" to verifyExecutable(loader, properties.getProperty(KEY_LOADER_SHA256)),
        )
        return RuntimeInstallationInspection(
            condition = if (checks.values.all { it }) {
                RuntimeInstallationCondition.READY
            } else {
                RuntimeInstallationCondition.REPAIR_REQUIRED
            },
            runtimeVersion = properties.getProperty(KEY_RUNTIME_VERSION),
            abi = properties.getProperty(KEY_ABI),
            checks = checks,
        )
    }

    @Synchronized
    fun activeRuntime(): InstalledRuntime {
        val inspection = inspect()
        if (inspection.condition != RuntimeInstallationCondition.READY) {
            throw RuntimeInstallException(
                RuntimeErrorCode.HEALTH_CHECK_FAILED,
                "runtime installation is not ready",
            )
        }
        val properties = readProperties(marker)!!
        return InstalledRuntime(
            runtimeId = properties.getProperty(KEY_RUNTIME_ID),
            runtimeVersion = properties.getProperty(KEY_RUNTIME_VERSION),
            abi = properties.getProperty(KEY_ABI),
            rootfsDirectory = rootfs,
            workspaceDirectory = workspaceDirectory,
            launcher = nativeFile(properties.getProperty(KEY_LAUNCHER_FILE)),
            loader = nativeFile(properties.getProperty(KEY_LOADER_FILE)),
        )
    }

    @Synchronized
    fun install(
        bundle: RuntimeArtifactBundle,
        supportedAbis: List<String>,
        forceReinstall: Boolean,
        isCancelled: () -> Boolean,
    ): RuntimeInstallResult {
        recoverInterruptedActivation()
        val selected = selectAndValidate(bundle, supportedAbis)
        val newMarker = markerProperties(bundle, selected)
        val existingMarker = readProperties(marker)
        if (!forceReinstall &&
            existingMarker != null &&
            fingerprint(existingMarker) == fingerprint(newMarker) &&
            inspect().condition == RuntimeInstallationCondition.READY
        ) {
            return RuntimeInstallResult(
                runtimeVersion = bundle.manifest.runtimeVersion,
                installedArtifactIds = selected.artifacts.map { it.descriptor.id },
                reusedExistingInstall = true,
            )
        }

        throwIfCancelled(isCancelled)
        verifyNativeArtifact(selected.launcher, selected.launcherFile, isCancelled)
        verifyNativeArtifact(selected.loader, selected.loaderFile, isCancelled)

        rootfsStaging.deleteRecursively()
        archiveStaging.delete()
        check(rootfsStaging.mkdirs()) { "failed to create runtime staging directory" }
        try {
            selected.rootfs.openStream().use { input ->
                copyVerified(
                    input = input,
                    destination = archiveStaging,
                    descriptor = selected.rootfs.descriptor,
                    maxBytes = limits.maxRootfsArchiveBytes,
                    isCancelled = isCancelled,
                )
            }
            archiveStaging.inputStream().use { input ->
                TarGzExtractor.extract(
                    input = input,
                    destination = rootfsStaging,
                    maxExtractedBytes = limits.maxRootfsExtractedBytes,
                    maxEntries = limits.maxRootfsEntries,
                    isCancelled = isCancelled,
                )
            }
            if (!rootfsShellExists(rootfsStaging)) {
                throw RuntimeInstallException(
                    RuntimeErrorCode.ARTIFACT_INTEGRITY_FAILED,
                    "rootfs does not contain /bin/sh",
                )
            }
            throwIfCancelled(isCancelled)
            activate(newMarker)
        } catch (error: CancellationException) {
            rollbackActivation()
            throw error
        } catch (error: RuntimeInstallException) {
            rollbackActivation()
            throw error
        } catch (error: IOException) {
            rollbackActivation()
            throw RuntimeInstallException(
                RuntimeErrorCode.STORAGE_UNAVAILABLE,
                "runtime installation could not write app storage",
                error,
            )
        } catch (error: Exception) {
            rollbackActivation()
            throw RuntimeInstallException(
                RuntimeErrorCode.ARTIFACT_INTEGRITY_FAILED,
                "runtime artifact installation failed",
                error,
            )
        } finally {
            archiveStaging.delete()
        }

        return RuntimeInstallResult(
            runtimeVersion = bundle.manifest.runtimeVersion,
            installedArtifactIds = selected.artifacts.map { it.descriptor.id },
            reusedExistingInstall = false,
        )
    }

    @Synchronized
    fun reset() {
        rootfs.deleteRecursively()
        rootfsStaging.deleteRecursively()
        rootfsPrevious.deleteRecursively()
        archiveStaging.delete()
        marker.delete()
        markerNext.delete()
        markerPrevious.delete()
        activationPending.delete()
        // User workspace intentionally survives runtime reset.
    }

    private fun selectAndValidate(
        bundle: RuntimeArtifactBundle,
        supportedAbis: List<String>,
    ): SelectedArtifacts {
        val metadata = bundle.manifest.metadata
        requireMetadata(metadata, RuntimeArtifactMetadataKeys.MANIFEST_SCHEMA, "1")
        requireMetadata(metadata, RuntimeArtifactMetadataKeys.ROOTFS_FORMAT, "tar.gz")
        requireMetadata(metadata, RuntimeArtifactMetadataKeys.SBOM_FORMAT)
        val sbomSha256 = requireMetadata(metadata, RuntimeArtifactMetadataKeys.SBOM_SHA256)
        requireSha256(sbomSha256, "SBOM checksum")

        val launcherDescriptors = bundle.manifest.artifacts.filter {
            it.kind == RuntimeArtifactKind.NATIVE_LAUNCHER
        }
        val loaderDescriptors = bundle.manifest.artifacts.filter {
            it.kind == RuntimeArtifactKind.NATIVE_LOADER
        }
        val abi = supportedAbis.firstOrNull { supported ->
            launcherDescriptors.any { it.abi == supported } &&
                loaderDescriptors.any { it.abi == supported }
        } ?: throw RuntimeInstallException(
            RuntimeErrorCode.UNSUPPORTED_ABI,
            "runtime artifacts do not support this device ABI",
        )
        val rootfsDescriptors = bundle.manifest.artifacts.filter {
            it.kind == RuntimeArtifactKind.ROOTFS && (it.abi == null || it.abi == abi)
        }
        if (rootfsDescriptors.size != 1) {
            throw RuntimeInstallException(
                RuntimeErrorCode.ARTIFACT_INTEGRITY_FAILED,
                "runtime manifest must contain exactly one matching rootfs",
            )
        }
        val launcherDescriptor = launcherDescriptors.singleOrNull { it.abi == abi }
            ?: throw invalidNativeManifest("launcher", abi)
        val loaderDescriptor = loaderDescriptors.singleOrNull { it.abi == abi }
            ?: throw invalidNativeManifest("loader", abi)
        val byId = bundle.artifacts.associateBy { it.descriptor.id }
        val rootfsArtifact = byId.getValue(rootfsDescriptors.single().id)
        val launcherArtifact = byId.getValue(launcherDescriptor.id)
        val loaderArtifact = byId.getValue(loaderDescriptor.id)
        listOf(rootfsArtifact, launcherArtifact, loaderArtifact).forEach { artifact ->
            if (artifact.descriptor.license.isNullOrBlank()) {
                throw RuntimeInstallException(
                    RuntimeErrorCode.ARTIFACT_INTEGRITY_FAILED,
                    "runtime artifact license metadata is missing",
                )
            }
        }
        val launcherName = safeFileName(
            requireMetadata(metadata, RuntimeArtifactMetadataKeys.NATIVE_LAUNCHER_FILE_NAME),
        )
        val loaderName = safeFileName(
            requireMetadata(metadata, RuntimeArtifactMetadataKeys.NATIVE_LOADER_FILE_NAME),
        )
        return SelectedArtifacts(
            abi = abi,
            rootfs = rootfsArtifact,
            launcher = launcherArtifact,
            loader = loaderArtifact,
            launcherFile = File(nativeLibraryDirectory, launcherName),
            loaderFile = File(nativeLibraryDirectory, loaderName),
        )
    }

    private fun verifyNativeArtifact(
        artifact: RuntimeArtifact,
        packagedFile: File,
        isCancelled: () -> Boolean,
    ) {
        artifact.openStream().use { input ->
            verifyStream(
                input,
                artifact.descriptor,
                limits.maxNativeArtifactBytes,
                isCancelled,
            )
        }
        if (!verifyExecutable(packagedFile, artifact.descriptor.sha256) ||
            packagedFile.length() != artifact.descriptor.sizeBytes
        ) {
            throw RuntimeInstallException(
                RuntimeErrorCode.ARTIFACT_INTEGRITY_FAILED,
                "packaged native runtime artifact is unavailable or invalid",
            )
        }
    }

    private fun activate(properties: Properties) {
        markerNext.delete()
        markerPrevious.delete()
        rootfsPrevious.deleteRecursively()
        writeProperties(markerNext, properties)
        val pending = Properties().apply {
            setProperty(KEY_EXPECTED_FINGERPRINT, fingerprint(properties))
        }
        writeProperties(activationPending, pending)
        faultInjector.checkpoint(RuntimeInstallCheckpoint.BEFORE_ACTIVATION)
        try {
            if (rootfs.exists()) move(rootfs, rootfsPrevious)
            if (marker.exists()) move(marker, markerPrevious)
            faultInjector.checkpoint(RuntimeInstallCheckpoint.AFTER_BACKUP)
            move(rootfsStaging, rootfs)
            faultInjector.checkpoint(RuntimeInstallCheckpoint.AFTER_ROOTFS_ACTIVATION)
            move(markerNext, marker)
            faultInjector.checkpoint(RuntimeInstallCheckpoint.AFTER_MARKER_ACTIVATION)
            activationPending.delete()
            rootfsPrevious.deleteRecursively()
            markerPrevious.delete()
        } catch (error: Exception) {
            rollbackActivation()
            throw error
        }
    }

    private fun rollbackActivation() {
        if (activationPending.exists()) {
            val pending = readProperties(activationPending)
            val expected = pending?.getProperty(KEY_EXPECTED_FINGERPRINT)
            val active = readProperties(marker)
            val activeIsNew = expected != null && active != null && fingerprint(active) == expected
            val previousWasMoved = rootfsPrevious.exists() || markerPrevious.exists()
            if (previousWasMoved || active == null || activeIsNew) {
                rootfs.deleteRecursively()
                marker.delete()
            }
            if (rootfsPrevious.exists()) move(rootfsPrevious, rootfs)
            if (markerPrevious.exists()) move(markerPrevious, marker)
        }
        clearTransactionFiles()
    }

    private fun clearTransactionFiles() {
        rootfsStaging.deleteRecursively()
        archiveStaging.delete()
        markerNext.delete()
        activationPending.delete()
    }

    private fun markerProperties(
        bundle: RuntimeArtifactBundle,
        selected: SelectedArtifacts,
    ): Properties = Properties().apply {
        setProperty(KEY_RUNTIME_ID, bundle.manifest.runtimeId)
        setProperty(KEY_RUNTIME_VERSION, bundle.manifest.runtimeVersion)
        setProperty(KEY_ABI, selected.abi)
        setProperty(KEY_ROOTFS_SHA256, selected.rootfs.descriptor.sha256)
        setProperty(KEY_LAUNCHER_SHA256, selected.launcher.descriptor.sha256)
        setProperty(KEY_LOADER_SHA256, selected.loader.descriptor.sha256)
        setProperty(KEY_LAUNCHER_FILE, selected.launcherFile.name)
        setProperty(KEY_LOADER_FILE, selected.loaderFile.name)
        setProperty(
            KEY_ARTIFACT_IDS,
            selected.artifacts.joinToString(",") { it.descriptor.id },
        )
    }

    private fun copyVerified(
        input: InputStream,
        destination: File,
        descriptor: RuntimeArtifactDescriptor,
        maxBytes: Long,
        isCancelled: () -> Boolean,
    ) {
        if (descriptor.sizeBytes > maxBytes) {
            throw RuntimeInstallException(
                RuntimeErrorCode.STORAGE_UNAVAILABLE,
                "runtime artifact exceeds configured size limit",
            )
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        try {
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    throwIfCancelled(isCancelled)
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maxBytes || total > descriptor.sizeBytes) {
                        throw RuntimeInstallException(
                            RuntimeErrorCode.ARTIFACT_INTEGRITY_FAILED,
                            "runtime artifact size does not match manifest",
                        )
                    }
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
                output.fd.sync()
            }
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
        verifyDigestAndSize(descriptor, total, digest.digest())
    }

    private fun verifyStream(
        input: InputStream,
        descriptor: RuntimeArtifactDescriptor,
        maxBytes: Long,
        isCancelled: () -> Boolean,
    ) {
        if (descriptor.sizeBytes > maxBytes) {
            throw RuntimeInstallException(
                RuntimeErrorCode.ARTIFACT_INTEGRITY_FAILED,
                "native runtime artifact exceeds configured size limit",
            )
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        val buffer = ByteArray(64 * 1024)
        while (true) {
            throwIfCancelled(isCancelled)
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes || total > descriptor.sizeBytes) {
                throw RuntimeInstallException(
                    RuntimeErrorCode.ARTIFACT_INTEGRITY_FAILED,
                    "native runtime artifact size does not match manifest",
                )
            }
            digest.update(buffer, 0, count)
        }
        verifyDigestAndSize(descriptor, total, digest.digest())
    }

    private fun verifyDigestAndSize(
        descriptor: RuntimeArtifactDescriptor,
        actualSize: Long,
        digest: ByteArray,
    ) {
        val actualSha256 = digest.toHex()
        if (actualSize != descriptor.sizeBytes ||
            !MessageDigest.isEqual(
                actualSha256.toByteArray(Charsets.US_ASCII),
                descriptor.sha256.lowercase().toByteArray(Charsets.US_ASCII),
            )
        ) {
            throw RuntimeInstallException(
                RuntimeErrorCode.ARTIFACT_INTEGRITY_FAILED,
                "runtime artifact checksum or size does not match manifest",
            )
        }
    }

    private fun verifyExecutable(file: File, expectedSha256: String?): Boolean {
        if (!file.isFile || !file.canExecute() || expectedSha256 == null) return false
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            MessageDigest.isEqual(
                digest.digest().toHex().toByteArray(Charsets.US_ASCII),
                expectedSha256.lowercase().toByteArray(Charsets.US_ASCII),
            )
        }.getOrDefault(false)
    }

    private fun nativeFile(name: String?): File {
        val safeName = name?.let(::safeFileName) ?: return File(nativeLibraryDirectory, "missing")
        return File(nativeLibraryDirectory, safeName)
    }

    private fun rootfsShellExists(directory: File): Boolean = Files.exists(
        File(directory, "bin/sh").toPath(),
        LinkOption.NOFOLLOW_LINKS,
    )

    private fun safeFileName(value: String): String {
        if (value.isBlank() || '/' in value || '\\' in value || value == "." || value == "..") {
            throw RuntimeInstallException(
                RuntimeErrorCode.ARTIFACT_INTEGRITY_FAILED,
                "native artifact file name is invalid",
            )
        }
        return value
    }

    private fun requireMetadata(
        metadata: Map<String, String>,
        key: String,
        expected: String? = null,
    ): String {
        val value = metadata[key]
        if (value.isNullOrBlank() || (expected != null && value != expected)) {
            throw RuntimeInstallException(
                RuntimeErrorCode.ARTIFACT_INTEGRITY_FAILED,
                "runtime manifest metadata is invalid: $key",
            )
        }
        return value
    }

    private fun invalidNativeManifest(kind: String, abi: String) = RuntimeInstallException(
        RuntimeErrorCode.ARTIFACT_INTEGRITY_FAILED,
        "runtime manifest must contain exactly one $kind for $abi",
    )

    private fun requireSha256(value: String, label: String) {
        if (!value.matches(Regex("[0-9a-f]{64}"))) {
            throw RuntimeInstallException(
                RuntimeErrorCode.ARTIFACT_INTEGRITY_FAILED,
                "$label is invalid",
            )
        }
    }

    private fun writeProperties(file: File, properties: Properties) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { output ->
            properties.store(output, null)
            output.fd.sync()
        }
    }

    private fun readProperties(file: File): Properties? = if (!file.isFile) {
        null
    } else {
        runCatching {
            Properties().apply { file.inputStream().use(::load) }
        }.getOrNull()
    }

    private fun fingerprint(properties: Properties): String {
        val canonical = properties.stringPropertyNames().sorted()
            .joinToString("\n") { key -> "$key=${properties.getProperty(key)}" }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .toHex()
    }

    private fun move(source: File, destination: File) {
        destination.parentFile?.mkdirs()
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun throwIfCancelled(isCancelled: () -> Boolean) {
        if (isCancelled()) throw CancellationException("runtime installation cancelled")
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }

    private data class SelectedArtifacts(
        val abi: String,
        val rootfs: RuntimeArtifact,
        val launcher: RuntimeArtifact,
        val loader: RuntimeArtifact,
        val launcherFile: File,
        val loaderFile: File,
    ) {
        val artifacts: List<RuntimeArtifact>
            get() = listOf(rootfs, launcher, loader)
    }

    companion object {
        private const val KEY_RUNTIME_ID = "runtime.id"
        private const val KEY_RUNTIME_VERSION = "runtime.version"
        private const val KEY_ABI = "runtime.abi"
        private const val KEY_ROOTFS_SHA256 = "rootfs.sha256"
        private const val KEY_LAUNCHER_SHA256 = "launcher.sha256"
        private const val KEY_LOADER_SHA256 = "loader.sha256"
        private const val KEY_LAUNCHER_FILE = "launcher.file"
        private const val KEY_LOADER_FILE = "loader.file"
        private const val KEY_ARTIFACT_IDS = "artifact.ids"
        private const val KEY_EXPECTED_FINGERPRINT = "expected.fingerprint"
    }
}
