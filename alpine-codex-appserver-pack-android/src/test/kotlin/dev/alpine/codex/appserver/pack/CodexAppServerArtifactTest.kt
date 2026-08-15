package dev.alpine.codex.appserver.pack

import org.junit.Assert.assertEquals
import org.junit.Test

class CodexAppServerArtifactTest {
    @Test
    fun `public lock constants match expected stable artifact`() {
        assertEquals("0.147.0", CodexAppServerArtifact.VERSION)
        assertEquals(64, CodexAppServerArtifact.BINARY_SHA256.length)
        assertEquals(64, CodexAppServerArtifact.SCHEMA_SHA256.length)
        assertEquals("libcodex_app_server.so", CodexAppServerArtifact.NATIVE_LIBRARY_NAME)
    }
}
