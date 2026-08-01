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
    fun xaiContractPinsDiscoveryLoopbackPkceAndCorsBoundaries() {
        val config = XaiOAuthContract.providerConfig(
            providerId = "xai-profile",
            clientId = "public-client",
        )

        assertEquals(XaiOAuthContract.DISCOVERY_ENDPOINT, config.discoveryEndpoint)
        assertEquals(setOf("auth.x.ai"), config.trustedDiscoveryEndpointHosts)
        assertEquals("http://127.0.0.1:56121/callback", config.redirectUri())
        assertTrue(config.callbackFallbackPorts.isEmpty())
        assertEquals(OAuthPkceMode.HEX_32_BYTES, config.pkceMode)
        assertTrue(config.includeAuthorizationNonce)
        assertEquals("generic", config.extraAuthorizationParams["plan"])
        assertEquals(XaiOAuthContract.REFERRER, config.extraAuthorizationParams["referrer"])
        assertEquals(
            setOf("https://auth.x.ai", "https://accounts.x.ai"),
            config.callbackCorsAllowedOrigins,
        )
        assertEquals(3, config.tokenRequestMaxAttempts)
    }

    @Test
    fun xaiTokenExchangeEchoesChallengeButRefreshDoesNot() {
        val config = XaiOAuthContract.providerConfig("xai-profile", "public-client")
        val exchange = config.tokenRequestAdapter.adapt(
            OAuthTokenRequestContext(
                grantType = OAuthTokenGrantType.AUTHORIZATION_CODE,
                parameters = mapOf("grant_type" to "authorization_code"),
                codeChallenge = "challenge",
            ),
        )
        val refresh = config.tokenRequestAdapter.adapt(
            OAuthTokenRequestContext(
                grantType = OAuthTokenGrantType.REFRESH_TOKEN,
                parameters = mapOf("grant_type" to "refresh_token"),
            ),
        )

        assertEquals("challenge", exchange["code_challenge"])
        assertEquals("S256", exchange["code_challenge_method"])
        assertFalse("code_challenge" in refresh)
    }

    @Test
    fun xaiTokenMetadataExtractsDisplayClaimsWithoutKeepingRawIdToken() {
        val payload = JSONObject()
            .put("sub", "xai-user")
            .put("email", "safe@example.test")
            .put("name", "Safe User")
            .toString()
        val idToken = "${base64Url("{\"alg\":\"none\"}")}.${base64Url(payload)}."
        val adapter = XaiOAuthContract.providerConfig("xai-profile", "public-client")
            .tokenResponseAdapter

        val token = adapter.parse(
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
