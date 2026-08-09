package dev.alpine.runtime.api

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream

class RuntimeApiTest {
    @Test
    fun `state validates progress bounds`() {
        assertEquals(
            50,
            RuntimeState(RuntimeLifecycleState.INSTALLING, progressPercent = 50).progressPercent,
        )
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeState(RuntimeLifecycleState.INSTALLING, progressPercent = 101)
        }
    }

    @Test
    fun `command rejects relative working directory`() {
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeCommandRequest("echo", workingDirectory = "workspace")
        }
    }

    @Test
    fun `artifact bundle requires exact manifest match`() {
        val descriptor = RuntimeArtifactDescriptor(
            id = "rootfs",
            kind = RuntimeArtifactKind.ROOTFS,
            version = "3.21.3",
            sha256 = "a".repeat(64),
            sizeBytes = 1,
        )
        val manifest = RuntimeArtifactManifest("alpine", "3.21.3", listOf(descriptor))
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeArtifactBundle(manifest, emptyList())
        }
    }

    @Test
    fun `artifact bundle rejects a payload descriptor that differs from manifest`() {
        val signedDescriptor = RuntimeArtifactDescriptor(
            id = "rootfs",
            kind = RuntimeArtifactKind.ROOTFS,
            version = "3.21.3",
            sha256 = "a".repeat(64),
            sizeBytes = 1,
        )
        val substitutedDescriptor = signedDescriptor.copy(sha256 = "b".repeat(64))
        val artifact = object : RuntimeArtifact {
            override val descriptor = substitutedDescriptor
            override fun openStream() = ByteArrayInputStream(byteArrayOf(1))
        }

        assertThrows(IllegalArgumentException::class.java) {
            RuntimeArtifactBundle(
                RuntimeArtifactManifest("alpine", "3.21.3", listOf(signedDescriptor)),
                listOf(artifact),
            )
        }
    }

    @Test
    fun `canonical manifest is stable across collection insertion order`() {
        val first = RuntimeArtifactDescriptor(
            id = "a",
            kind = RuntimeArtifactKind.ROOTFS,
            version = "1",
            sha256 = "a".repeat(64),
            sizeBytes = 1,
        )
        val second = first.copy(id = "b", sha256 = "b".repeat(64))
        val ordered = RuntimeArtifactManifest(
            "alpine",
            "1",
            listOf(first, second),
            linkedMapOf("a" to "1", "b" to "2"),
        )
        val reversed = RuntimeArtifactManifest(
            "alpine",
            "1",
            listOf(second, first),
            linkedMapOf("b" to "2", "a" to "1"),
        )

        assertArrayEquals(
            RuntimeArtifactManifestCanonicalizer.canonicalBytes(ordered),
            RuntimeArtifactManifestCanonicalizer.canonicalBytes(reversed),
        )
    }

    @Test
    fun `default developer tool smoke workflows use fixed direct version argv`() {
        assertEquals(setOf("python", "git", "ssh", "node"), DefaultRuntimeDeveloperToolProfiles.map { it.id }.toSet())
        DefaultRuntimeDeveloperToolProfiles.forEach { profile ->
            assertTrue(profile.smokeRequest.executable.startsWith("/"))
            assertFalse(profile.smokeRequest.executable in setOf("/bin/sh", "/bin/ash", "/usr/bin/env"))
            assertTrue(profile.smokeRequest.arguments.all { argument -> argument.startsWith("-") })
            assertTrue(profile.packages.isNotEmpty())
        }
    }

    @Test
    fun `developer tool smoke workflow rejects shell execution`() {
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeDeveloperToolProfile(
                id = "bad-shell",
                label = "Bad",
                packages = listOf("git"),
                smokeRequest = RuntimeCommandRequest("/bin/sh", listOf("-c", "id")),
            )
        }
    }

    @Test
    fun `package catalog estimates only known package archives and preserves missing names`() {
        val git = packageMetadata(
            packageName = "git",
            downloadBytes = 3_414_900,
            installedBytes = 6_997_971,
        )
        val catalog = RuntimePackageCatalog(listOf(git))

        val estimate = catalog.estimate(listOf("git", "unknown-package", "git"))

        assertEquals(listOf(git), estimate.metadata)
        assertEquals(listOf("unknown-package"), estimate.missingPackageNames)
        assertEquals(3_414_900, estimate.downloadBytes)
        assertEquals(6_997_971, estimate.installedBytes)
        assertFalse(estimate.isComplete)
    }

    @Test
    fun `package catalog saturates overflowed byte totals without reporting a complete estimate`() {
        val catalog = RuntimePackageCatalog(
            listOf(
                packageMetadata(
                    packageName = "git",
                    downloadBytes = Long.MAX_VALUE,
                    installedBytes = Long.MAX_VALUE,
                ),
                packageMetadata(
                    packageName = "curl",
                    downloadBytes = 1,
                    installedBytes = 1,
                ),
            ),
        )

        val estimate = catalog.estimate(listOf("git", "curl"))

        assertEquals(Long.MAX_VALUE, estimate.downloadBytes)
        assertEquals(Long.MAX_VALUE, estimate.installedBytes)
        assertTrue(estimate.totalBytesOverflowed)
        assertFalse(estimate.isComplete)
    }

    @Test
    fun `package catalog rejects duplicate names and invalid snapshot urls`() {
        val git = packageMetadata(packageName = "git")
        assertThrows(IllegalArgumentException::class.java) {
            RuntimePackageCatalog(listOf(git, git.copy(version = "2.0")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            packageMetadata(packageName = "git", sourceUrl = "http://example.test/index")
        }
    }

    private fun packageMetadata(
        packageName: String,
        downloadBytes: Long = 1,
        installedBytes: Long = 2,
        sourceUrl: String = "https://example.test/index",
    ) = RuntimePackageMetadata(
        packageName = packageName,
        version = "1.0-r0",
        licenseExpression = "MIT",
        downloadBytes = downloadBytes,
        installedBytes = installedBytes,
        repository = "main",
        architecture = "aarch64",
        snapshotId = "test snapshot",
        sourceUrl = sourceUrl,
    )
}
