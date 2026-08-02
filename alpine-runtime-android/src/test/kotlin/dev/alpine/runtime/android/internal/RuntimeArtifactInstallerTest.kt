package dev.alpine.runtime.android.internal

import dev.alpine.runtime.api.RuntimeArtifact
import dev.alpine.runtime.api.RuntimeArtifactBundle
import dev.alpine.runtime.api.RuntimeArtifactDescriptor
import dev.alpine.runtime.api.RuntimeArtifactKind
import dev.alpine.runtime.api.RuntimeArtifactManifest
import dev.alpine.runtime.api.RuntimeArtifactMetadataKeys
import dev.alpine.runtime.api.RuntimeErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.zip.GZIPOutputStream

class RuntimeArtifactInstallerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `installs atomically and reuses identical healthy runtime`() {
        val fixture = fixture()
        val installer = fixture.installer()

        val first = installer.install(fixture.bundle("v1", "shell-v1"), ABIS, false) { false }
        val second = installer.install(fixture.bundle("v1", "shell-v1"), ABIS, false) { false }

        assertFalse(first.reusedExistingInstall)
        assertTrue(second.reusedExistingInstall)
        assertEquals(RuntimeInstallationCondition.READY, installer.inspect().condition)
        assertEquals("v1", installer.inspect().runtimeVersion)
        assertEquals("shell-v1", fixture.rootfsShell().readText())
    }

    @Test
    fun `checksum mismatch preserves active runtime`() {
        val fixture = fixture()
        val installer = fixture.installer()
        installer.install(fixture.bundle("v1", "shell-v1"), ABIS, false) { false }

        val error = assertThrows(RuntimeInstallException::class.java) {
            installer.install(
                fixture.bundle("v2", "shell-v2", rootfsChecksum = "0".repeat(64)),
                ABIS,
                true,
            ) { false }
        }

        assertEquals(RuntimeErrorCode.ARTIFACT_INTEGRITY_FAILED, error.errorCode)
        assertEquals(RuntimeInstallationCondition.READY, installer.inspect().condition)
        assertEquals("v1", installer.inspect().runtimeVersion)
        assertEquals("shell-v1", fixture.rootfsShell().readText())
    }

    @Test
    fun `ABI mismatch preserves active runtime`() {
        val fixture = fixture()
        val installer = fixture.installer()
        installer.install(fixture.bundle("v1", "shell-v1"), ABIS, false) { false }

        val error = assertThrows(RuntimeInstallException::class.java) {
            installer.install(fixture.bundle("v2", "shell-v2"), listOf("x86_64"), true) { false }
        }

        assertEquals(RuntimeErrorCode.UNSUPPORTED_ABI, error.errorCode)
        assertEquals("v1", installer.inspect().runtimeVersion)
        assertEquals("shell-v1", fixture.rootfsShell().readText())
    }

    @Test
    fun `archive size limit preserves active runtime`() {
        val fixture = fixture()
        fixture.installer().install(fixture.bundle("v1", "shell-v1"), ABIS, false) { false }
        val limited = fixture.installer(maxArchiveBytes = 8)

        val error = assertThrows(RuntimeInstallException::class.java) {
            limited.install(fixture.bundle("v2", "shell-v2"), ABIS, true) { false }
        }

        assertEquals(RuntimeErrorCode.STORAGE_UNAVAILABLE, error.errorCode)
        assertEquals("v1", limited.inspect().runtimeVersion)
        assertEquals("shell-v1", fixture.rootfsShell().readText())
    }

    @Test
    fun `cancelled reinstall preserves active runtime`() {
        val fixture = fixture()
        val installer = fixture.installer()
        installer.install(fixture.bundle("v1", "shell-v1"), ABIS, false) { false }

        assertThrows(CancellationException::class.java) {
            installer.install(fixture.bundle("v2", "shell-v2"), ABIS, true) { true }
        }

        assertEquals("v1", installer.inspect().runtimeVersion)
        assertEquals("shell-v1", fixture.rootfsShell().readText())
    }

    @Test
    fun `process death after rootfs activation rolls back on recovery`() {
        val fixture = fixture()
        fixture.installer().install(fixture.bundle("v1", "shell-v1"), ABIS, false) { false }
        val interrupted = fixture.installer(
            faultInjector = RuntimeInstallFaultInjector { checkpoint ->
                if (checkpoint == RuntimeInstallCheckpoint.AFTER_ROOTFS_ACTIVATION) {
                    throw SimulatedProcessDeath()
                }
            },
        )

        assertThrows(SimulatedProcessDeath::class.java) {
            interrupted.install(fixture.bundle("v2", "shell-v2"), ABIS, true) { false }
        }

        val recovered = fixture.installer()
        recovered.recoverInterruptedActivation()
        assertEquals(RuntimeInstallationCondition.READY, recovered.inspect().condition)
        assertEquals("v1", recovered.inspect().runtimeVersion)
        assertEquals("shell-v1", fixture.rootfsShell().readText())
    }

    @Test
    fun `process death before activation preserves active runtime on recovery`() {
        val fixture = fixture()
        fixture.installer().install(fixture.bundle("v1", "shell-v1"), ABIS, false) { false }
        val interrupted = fixture.installer(
            faultInjector = RuntimeInstallFaultInjector { checkpoint ->
                if (checkpoint == RuntimeInstallCheckpoint.BEFORE_ACTIVATION) {
                    throw SimulatedProcessDeath()
                }
            },
        )

        assertThrows(SimulatedProcessDeath::class.java) {
            interrupted.install(fixture.bundle("v2", "shell-v2"), ABIS, true) { false }
        }

        val recovered = fixture.installer()
        recovered.recoverInterruptedActivation()
        assertEquals(RuntimeInstallationCondition.READY, recovered.inspect().condition)
        assertEquals("v1", recovered.inspect().runtimeVersion)
        assertEquals("shell-v1", fixture.rootfsShell().readText())
    }

    @Test
    fun `storage failure during activation preserves active runtime`() {
        val fixture = fixture()
        fixture.installer().install(fixture.bundle("v1", "shell-v1"), ABIS, false) { false }
        val failing = fixture.installer(
            faultInjector = RuntimeInstallFaultInjector { checkpoint ->
                if (checkpoint == RuntimeInstallCheckpoint.BEFORE_ACTIVATION) {
                    throw IOException("simulated storage failure")
                }
            },
        )

        val error = assertThrows(RuntimeInstallException::class.java) {
            failing.install(fixture.bundle("v2", "shell-v2"), ABIS, true) { false }
        }

        assertEquals(RuntimeErrorCode.STORAGE_UNAVAILABLE, error.errorCode)
        assertEquals(RuntimeInstallationCondition.READY, failing.inspect().condition)
        assertEquals("v1", failing.inspect().runtimeVersion)
        assertEquals("shell-v1", fixture.rootfsShell().readText())
    }

    @Test
    fun `process death after marker activation finalizes new runtime`() {
        val fixture = fixture()
        fixture.installer().install(fixture.bundle("v1", "shell-v1"), ABIS, false) { false }
        val interrupted = fixture.installer(
            faultInjector = RuntimeInstallFaultInjector { checkpoint ->
                if (checkpoint == RuntimeInstallCheckpoint.AFTER_MARKER_ACTIVATION) {
                    throw SimulatedProcessDeath()
                }
            },
        )

        assertThrows(SimulatedProcessDeath::class.java) {
            interrupted.install(fixture.bundle("v2", "shell-v2"), ABIS, true) { false }
        }

        val recovered = fixture.installer()
        recovered.recoverInterruptedActivation()
        assertEquals("v2", recovered.inspect().runtimeVersion)
        assertEquals("shell-v2", fixture.rootfsShell().readText())
    }

    @Test
    fun `reset removes runtime but preserves workspace`() {
        val fixture = fixture()
        val installer = fixture.installer()
        installer.install(fixture.bundle("v1", "shell-v1"), ABIS, false) { false }
        val userFile = File(fixture.workspaceDirectory, "user.txt").apply { writeText("keep") }

        installer.reset()

        assertEquals(RuntimeInstallationCondition.NOT_INSTALLED, installer.inspect().condition)
        assertTrue(userFile.isFile)
        assertEquals("keep", userFile.readText())
    }

    private fun fixture(): InstallFixture {
        val base = temporaryFolder.newFolder("runtime-${System.nanoTime()}")
        val native = File(base, "native").apply { mkdirs() }
        val launcherBytes = "packaged-launcher".toByteArray()
        val loaderBytes = "packaged-loader".toByteArray()
        File(native, "libproot.so").apply {
            writeBytes(launcherBytes)
            setExecutable(true)
        }
        File(native, "libproot-loader.so").apply {
            writeBytes(loaderBytes)
            setExecutable(true)
        }
        return InstallFixture(
            runtimeDirectory = File(base, "install"),
            workspaceDirectory = File(base, "install/workspace"),
            nativeDirectory = native,
            launcherBytes = launcherBytes,
            loaderBytes = loaderBytes,
        )
    }

    private data class InstallFixture(
        val runtimeDirectory: File,
        val workspaceDirectory: File,
        val nativeDirectory: File,
        val launcherBytes: ByteArray,
        val loaderBytes: ByteArray,
    ) {
        fun installer(
            maxArchiveBytes: Long = 1024 * 1024,
            faultInjector: RuntimeInstallFaultInjector = RuntimeInstallFaultInjector.NONE,
        ) = RuntimeArtifactInstaller(
            runtimeDirectory = runtimeDirectory,
            workspaceDirectory = workspaceDirectory,
            nativeLibraryDirectory = nativeDirectory,
            limits = RuntimeInstallLimits(
                maxRootfsArchiveBytes = maxArchiveBytes,
                maxRootfsExtractedBytes = 1024 * 1024,
                maxRootfsEntries = 100,
                maxNativeArtifactBytes = 1024 * 1024,
            ),
            faultInjector = faultInjector,
        )

        fun rootfsShell(): File = File(runtimeDirectory, "rootfs/bin/sh")

        fun bundle(
            version: String,
            shellContent: String,
            rootfsChecksum: String? = null,
        ): RuntimeArtifactBundle {
            val rootfsBytes = tarGz("bin/sh", shellContent.toByteArray(), 0b111_101_101)
            val rootfs = descriptor(
                id = "rootfs-$version",
                kind = RuntimeArtifactKind.ROOTFS,
                version = version,
                bytes = rootfsBytes,
                checksum = rootfsChecksum,
            )
            val launcher = descriptor(
                id = "launcher",
                kind = RuntimeArtifactKind.NATIVE_LAUNCHER,
                version = "1",
                bytes = launcherBytes,
            )
            val loader = descriptor(
                id = "loader",
                kind = RuntimeArtifactKind.NATIVE_LOADER,
                version = "1",
                bytes = loaderBytes,
            )
            val artifacts = listOf(
                ByteArrayArtifact(rootfs, rootfsBytes),
                ByteArrayArtifact(launcher, launcherBytes),
                ByteArrayArtifact(loader, loaderBytes),
            )
            val manifest = RuntimeArtifactManifest(
                runtimeId = "alpine",
                runtimeVersion = version,
                artifacts = artifacts.map { it.descriptor },
                metadata = mapOf(
                    RuntimeArtifactMetadataKeys.MANIFEST_SCHEMA to "1",
                    RuntimeArtifactMetadataKeys.ROOTFS_FORMAT to "tar.gz",
                    RuntimeArtifactMetadataKeys.NATIVE_LAUNCHER_FILE_NAME to "libproot.so",
                    RuntimeArtifactMetadataKeys.NATIVE_LOADER_FILE_NAME to "libproot-loader.so",
                    RuntimeArtifactMetadataKeys.SBOM_FORMAT to "SPDX-2.3",
                    RuntimeArtifactMetadataKeys.SBOM_SHA256 to "a".repeat(64),
                ),
            )
            return RuntimeArtifactBundle(manifest, artifacts)
        }

        private fun descriptor(
            id: String,
            kind: RuntimeArtifactKind,
            version: String,
            bytes: ByteArray,
            checksum: String? = null,
        ) = RuntimeArtifactDescriptor(
            id = id,
            kind = kind,
            version = version,
            sha256 = checksum ?: sha256(bytes),
            sizeBytes = bytes.size.toLong(),
            abi = "arm64-v8a",
            license = "test-license",
        )
    }

    private class ByteArrayArtifact(
        override val descriptor: RuntimeArtifactDescriptor,
        private val bytes: ByteArray,
    ) : RuntimeArtifact {
        override fun openStream(): InputStream = ByteArrayInputStream(bytes)
    }

    private class SimulatedProcessDeath : Error()

    companion object {
        private val ABIS = listOf("arm64-v8a")

        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }

        private fun tarGz(path: String, contents: ByteArray, mode: Int): ByteArray {
            val tar = ByteArrayOutputStream()
            val header = ByteArray(512)
            putString(header, 0, 100, path)
            putOctal(header, 100, 8, mode.toLong())
            putOctal(header, 108, 8, 0)
            putOctal(header, 116, 8, 0)
            putOctal(header, 124, 12, contents.size.toLong())
            putOctal(header, 136, 12, 0)
            for (index in 148 until 156) header[index] = ' '.code.toByte()
            header[156] = '0'.code.toByte()
            putString(header, 257, 6, "ustar")
            putString(header, 263, 2, "00")
            val checksum = header.sumOf { it.toInt() and 0xff }
            putString(header, 148, 6, checksum.toString(8).padStart(6, '0'))
            header[154] = 0
            header[155] = ' '.code.toByte()
            tar.write(header)
            tar.write(contents)
            tar.write(ByteArray((512 - contents.size % 512) % 512))
            tar.write(ByteArray(1024))
            return ByteArrayOutputStream().also { compressed ->
                GZIPOutputStream(compressed).use { it.write(tar.toByteArray()) }
            }.toByteArray()
        }

        private fun putString(target: ByteArray, offset: Int, length: Int, value: String) {
            val bytes = value.toByteArray(StandardCharsets.US_ASCII)
            require(bytes.size <= length)
            bytes.copyInto(target, offset)
        }

        private fun putOctal(target: ByteArray, offset: Int, length: Int, value: Long) {
            putString(target, offset, length - 1, value.toString(8).padStart(length - 1, '0'))
            target[offset + length - 1] = 0
        }
    }
}
