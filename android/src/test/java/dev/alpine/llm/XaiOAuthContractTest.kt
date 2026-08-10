package dev.alpine.llm

import java.nio.charset.StandardCharsets
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test

class XaiOAuthContractTest {
    @After
    fun clearCompatibility() {
        XaiOAuthCompatibilityRegistry.clear()
    }

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

    @Test
    fun approvedDebugCompatibilityAddsMetadataAndChallengeOnlyToCodeExchange() {
        val compatibility = XaiOAuthCompatibilityConfig(
            sourceRevision = "reference@revision",
            clientId = "approved-debug-public-client",
            chatCompletionsEndpoint = XaiOAuthContract.CHAT_COMPLETIONS_ENDPOINT,
            scopes = XaiOAuthContract.SCOPES + "debug.compatibility",
            defaultModel = "grok-current",
            modelOptions = listOf("grok-current", "grok-fast"),
            extraAuthorizationParams = mapOf("approved_flow" to "true"),
        )
        XaiOAuthCompatibilityRegistry.installApprovedDebug(compatibility)

        val config = XaiOAuthContract.providerConfig("xai-profile", compatibility.clientId)
        val codeInput = mapOf(
            "grant_type" to "authorization_code",
            "code_verifier" to "verifier",
        )
        val refreshInput = mapOf(
            "grant_type" to "refresh_token",
            "refresh_token" to "refresh",
        )

        assertEquals(compatibility.scopes, config.scopes)
        assertEquals(compatibility.extraAuthorizationParams, config.extraAuthorizationParams)
        assertEquals(
            codeInput + mapOf("code_challenge" to "challenge", "code_challenge_method" to "S256"),
            config.tokenRequestAdapter.adapt(
                OAuthTokenRequestContext(
                    grantType = OAuthTokenGrantType.AUTHORIZATION_CODE,
                    parameters = codeInput,
                    codeChallenge = "challenge",
                ),
            ),
        )
        assertEquals(
            refreshInput,
            config.tokenRequestAdapter.adapt(
                OAuthTokenRequestContext(
                    grantType = OAuthTokenGrantType.REFRESH_TOKEN,
                    parameters = refreshInput,
                ),
            ),
        )
    }

    private fun base64Url(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))
}
