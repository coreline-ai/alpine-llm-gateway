package dev.alpine.llm

import java.nio.charset.StandardCharsets
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XaiOAuthContractTest {
    @Test
    fun xaiContractKeepsDiscoveryAndUsesStandardOAuthRequestParameters() {
        val config = XaiOAuthContract.providerConfig("xai-profile", "public-client")

        assertEquals(XaiOAuthContract.DISCOVERY_ENDPOINT, config.discoveryEndpoint)
        assertEquals(setOf("auth.x.ai"), config.trustedDiscoveryEndpointHosts)
        assertEquals("http://127.0.0.1:56121/callback", config.redirectUri())
        assertTrue(config.callbackFallbackPorts.isEmpty())
        assertEquals(OAuthPkceMode.STANDARD, config.pkceMode)
        assertTrue(config.includeAuthorizationNonce)
        assertTrue(config.extraAuthorizationParams.isEmpty())
        val input = mapOf("grant_type" to "authorization_code", "code_verifier" to "verifier")
        assertEquals(
            input,
            config.tokenRequestAdapter.adapt(
                OAuthTokenRequestContext(OAuthTokenGrantType.AUTHORIZATION_CODE, input),
            ),
        )
    }

    @Test
    fun xaiTokenMetadataKeepsSafeDisplayClaimsWithoutRawIdToken() {
        val payload = JSONObject()
            .put("sub", "xai-user")
            .put("email", "safe@example.test")
            .put("name", "Safe User")
            .toString()
        val idToken = "${base64Url("{\"alg\":\"none\"}")}.${base64Url(payload)}."
        val token = XaiOAuthContract.providerConfig("xai-profile", "public-client")
            .tokenResponseAdapter.parse(
                JSONObject().put("access_token", "access").put("id_token", idToken),
                nowMs = 1_000L,
            )

        assertEquals("xai-user", token.metadata["account_id"])
        assertEquals("safe@example.test", token.metadata["email"])
        assertEquals("Safe User", token.metadata["name"])
        assertFalse(token.metadata.values.any { it.contains(idToken) })
    }

    private fun base64Url(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))
}
