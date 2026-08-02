package dev.alpine.runtime.api

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
}
