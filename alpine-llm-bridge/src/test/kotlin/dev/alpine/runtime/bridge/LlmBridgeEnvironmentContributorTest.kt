package dev.alpine.runtime.bridge

import dev.alpine.runtime.api.RuntimeEnvironmentContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class LlmBridgeEnvironmentContributorTest {
    @Test
    fun `exports endpoint and credential path without token value`() {
        val contributor = LlmBridgeEnvironmentContributor {
            LlmBridgeEndpoint(
                "http://127.0.0.1:8765",
                "/workspace/.alpine-runtime/bridge-credential",
            )
        }

        val environment = contributor.contribute(RuntimeEnvironmentContext("session", "/workspace"))

        assertEquals("http://127.0.0.1:8765", environment["ALPINE_LLM_BRIDGE_URL"])
        assertEquals(
            "/workspace/.alpine-runtime/bridge-credential",
            environment["ALPINE_LLM_CREDENTIAL_FILE"],
        )
        assertFalse(environment.keys.any { it.contains("TOKEN") })
    }

    @Test
    fun `rejects non-loopback endpoint`() {
        assertThrows(IllegalArgumentException::class.java) {
            LlmBridgeEndpoint(
                "https://example.com",
                "/workspace/.alpine-runtime/credential",
            )
        }
    }

    @Test
    fun `rejects endpoint userinfo and credential traversal`() {
        assertThrows(IllegalArgumentException::class.java) {
            LlmBridgeEndpoint(
                "http://127.0.0.1:8765@evil.example",
                "/workspace/.alpine-runtime/credential",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            LlmBridgeEndpoint(
                "http://127.0.0.1:8765",
                "/workspace/.alpine-runtime/../credential",
            )
        }
    }

    @Test
    fun `registry exposes and clears only current endpoint`() {
        val registry = LlmBridgeEndpointRegistry()
        val context = RuntimeEnvironmentContext("session", "/workspace")
        val endpoint = LlmBridgeEndpoint(
            "http://127.0.0.1:8765",
            "/workspace/.alpine-runtime/credential",
        )

        registry.update(endpoint)
        assertEquals(endpoint, registry.endpointFor(context))
        registry.clear()
        assertEquals(null, registry.endpointFor(context))
    }
}
