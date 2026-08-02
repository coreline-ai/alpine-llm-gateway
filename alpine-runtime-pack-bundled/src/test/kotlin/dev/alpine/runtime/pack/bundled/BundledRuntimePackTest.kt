package dev.alpine.runtime.pack.bundled

import dev.alpine.runtime.api.RuntimeArtifactKind
import dev.alpine.runtime.api.RuntimeArtifactMetadataKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BundledRuntimePackTest {
    @Test
    fun `locked pack declares checksums licenses and sbom`() {
        val manifest = Alpine321Arm64Pack.create().manifest

        assertEquals("1", manifest.metadata[RuntimeArtifactMetadataKeys.MANIFEST_SCHEMA])
        assertEquals("SPDX-2.3", manifest.metadata[RuntimeArtifactMetadataKeys.SBOM_FORMAT])
        assertTrue(manifest.artifacts.all { it.sha256.length == 64 && !it.license.isNullOrBlank() })
        assertEquals(1, manifest.artifacts.count { it.kind == RuntimeArtifactKind.ROOTFS })
    }
}
