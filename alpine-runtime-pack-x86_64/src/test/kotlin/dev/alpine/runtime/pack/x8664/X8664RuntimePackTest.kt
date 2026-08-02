package dev.alpine.runtime.pack.x8664

import dev.alpine.runtime.api.RuntimeArtifactKind
import dev.alpine.runtime.api.RuntimeArtifactMetadataKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class X8664RuntimePackTest {
    @Test
    fun `experimental pack is x86 only and checksum locked`() {
        val manifest = Alpine321X8664Pack.create().manifest

        assertTrue(manifest.runtimeVersion.endsWith("-x86_64-experimental"))
        assertEquals(setOf("x86_64"), manifest.artifacts.mapNotNull { it.abi }.toSet())
        assertEquals(1, manifest.artifacts.count { it.kind == RuntimeArtifactKind.ROOTFS })
        assertEquals("SPDX-2.3", manifest.metadata[RuntimeArtifactMetadataKeys.SBOM_FORMAT])
        assertTrue(manifest.artifacts.all { it.sha256.length == 64 && !it.license.isNullOrBlank() })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rootfs asset path cannot escape the APK asset root`() {
        val pack = Alpine321X8664Pack.create()
        X8664RuntimePack(pack.manifest, "../rootfs.asset")
    }
}
