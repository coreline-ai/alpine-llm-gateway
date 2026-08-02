package dev.alpine.runtime.gateway.pack.bundled

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BundledPythonGatewayPackTest {
    @Test
    fun `gateway package is versioned separately from runtime`() {
        val descriptor = AlpineLlmGateway030Pack.descriptor()
        val manifest = AlpineLlmGateway030Pack.manifest()

        assertEquals("0.3.0", descriptor.packageVersion)
        assertEquals("1", descriptor.protocolVersion)
        assertEquals(descriptor.packageVersion, manifest.packageVersion)
        assertTrue(manifest.entrypoints.values.all { it.startsWith('/') })
    }
}
