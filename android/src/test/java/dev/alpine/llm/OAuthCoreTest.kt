package dev.alpine.llm

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.net.Socket

class OAuthCoreTest {
    @Test
    fun pkceChallengeMatchesVerifierDigest() {
        val value = OAuthPkce.create()
        val expected = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256")
                .digest(value.verifier.toByteArray(StandardCharsets.US_ASCII)),
        )

        assertEquals(expected, value.challenge)
        assertFalse(value.verifier.contains("="))
        assertFalse(value.challenge.contains("="))
    }

    @Test
    fun callbackValidationClassifiesDenialStateAndExpiration() {
        val transaction = OAuthTokenStore.Transaction("expected", "verifier", 1_000L)

        assertFailureKind(OAuthFailureKind.USER_DENIED) {
            OAuthCallbackValidator.validate(
                OAuthCallbackServer.Callback(null, null, "access_denied", "cancelled"),
                transaction,
                1_500L,
                1_000L,
            )
        }
        assertFailureKind(OAuthFailureKind.STATE_MISMATCH) {
            OAuthCallbackValidator.validate(
                OAuthCallbackServer.Callback("code", "wrong", null),
                transaction,
                1_500L,
                1_000L,
            )
        }
        assertFailureKind(OAuthFailureKind.TRANSACTION_EXPIRED) {
            OAuthCallbackValidator.validate(
                OAuthCallbackServer.Callback("code", "expected", null),
                transaction,
                2_001L,
                1_000L,
            )
        }
        assertEquals(
            "code",
            OAuthCallbackValidator.validate(
                OAuthCallbackServer.Callback("code", "expected", null),
                transaction,
                1_500L,
                1_000L,
            ),
        )
    }

    @Test
    fun callbackWaiterClassifiesTimeout() = runTest {
        val error = runCatching {
            OAuthCallbackAwaiter.await(CompletableDeferred(), timeoutMs = 1L)
        }.exceptionOrNull()

        assertTrue(error is OAuthException)
        assertEquals(OAuthFailureKind.CALLBACK_TIMEOUT, (error as OAuthException).kind)
    }

    @Test
    fun callbackRegistryRequiresActivePortPathAndState() {
        val port = 20_000 + (OAuthPkce.state(2).hashCode() and 0x3fff)
        val state = OAuthPkce.state()
        OAuthCallbackRegistry.register(port, "/oauth/callback", state)
        try {
            assertTrue(OAuthCallbackRegistry.matches(port, "/oauth/callback", state))
            assertFalse(OAuthCallbackRegistry.matches(port, "/wrong", state))
            assertFalse(OAuthCallbackRegistry.matches(port, "/oauth/callback", "wrong"))
            assertFalse(OAuthCallbackRegistry.matches(port + 1, "/oauth/callback", state))
        } finally {
            OAuthCallbackRegistry.unregister(port, state)
        }
        assertFalse(OAuthCallbackRegistry.matches(port, "/oauth/callback", state))
    }

    @Test
    fun standardTokenAdapterPreservesProviderMetadata() {
        val token = StandardOAuthTokenResponseAdapter().parse(
            JSONObject()
                .put("access_token", "access")
                .put("refresh_token", "refresh")
                .put("expires_in", 3600)
                .put("account_id", "acct")
                .put("plan_type", "pro")
                .put("nested", JSONObject().put("ignored", true)),
            nowMs = 10_000L,
        )

        assertEquals("access", token.accessToken)
        assertEquals("refresh", token.refreshToken)
        assertEquals(3_610_000L, token.expiresAtMs)
        assertEquals(mapOf("account_id" to "acct", "plan_type" to "pro"), token.metadata)
    }

    @Test
    fun tokenRequestAdapterCanAddDynamicProviderFieldsAndJsonEncoding() {
        val adapter = OAuthTokenRequestAdapter { context ->
            context.parameters + mapOf(
                "state" to requireNotNull(context.state),
                "code_challenge" to requireNotNull(context.codeChallenge),
            )
        }
        val parameters = adapter.adapt(
            OAuthTokenRequestContext(
                grantType = OAuthTokenGrantType.AUTHORIZATION_CODE,
                parameters = mapOf("grant_type" to "authorization_code"),
                state = "state-value",
                codeChallenge = "challenge-value",
            ),
        )
        val encoded = OAuthTokenRequestEncoder.encode(
            parameters,
            OAuthTokenRequestEncoding.JSON,
        )

        assertEquals("application/json", encoded.contentType)
        assertEquals("state-value", JSONObject(encoded.body).getString("state"))
        assertEquals("challenge-value", JSONObject(encoded.body).getString("code_challenge"))
    }

    @Test
    fun tokenCodecRoundTripsMetadataAndRejectsCorruption() {
        val original = OAuthTokenStore.Token(
            accessToken = "access",
            refreshToken = "refresh",
            expiresAtMs = 123_456L,
            metadata = mapOf("email" to "user@example.com"),
        )

        assertEquals(original, OAuthTokenJsonCodec.decode(OAuthTokenJsonCodec.encode(original)))
        assertNull(OAuthTokenJsonCodec.decode("not-json"))
        assertNull(OAuthTokenJsonCodec.decode("""{"refresh_token":"missing-access"}"""))
    }

    @Test
    fun concurrentRefreshUsesRotatedTokenOnlyOnce() = runBlocking {
        var stored = OAuthTokenStore.Token(
            accessToken = "expired",
            refreshToken = "refresh-1",
            expiresAtMs = 1L,
        )
        var refreshCount = 0
        val providerId = "concurrent-${OAuthPkce.state(12)}"

        val results = (1..20).map {
            async {
                OAuthRefreshCoordinator.resolve(
                    providerId = providerId,
                    refreshSkewMs = 300_000L,
                    clock = { 1_000_000L },
                    loadLatest = { stored },
                    refresh = { _, refreshToken ->
                        refreshCount++
                        assertEquals("refresh-1", refreshToken)
                        delay(5)
                        OAuthTokenStore.Token(
                            accessToken = "fresh",
                            refreshToken = "refresh-2",
                            expiresAtMs = 9_000_000L,
                        ).also { stored = it }
                    },
                    clearCredential = { error("credential must not be cleared") },
                )
            }
        }.awaitAll()

        assertEquals(1, refreshCount)
        assertTrue(results.all { it?.accessToken == "fresh" })
        assertEquals("refresh-2", stored.refreshToken)
    }

    @Test
    fun invalidGrantClearsCredential() = runBlocking {
        var stored: OAuthTokenStore.Token? = OAuthTokenStore.Token(
            accessToken = "expired",
            refreshToken = "revoked",
            expiresAtMs = 1L,
        )

        val result = OAuthRefreshCoordinator.resolve(
            providerId = "invalid-grant-${OAuthPkce.state(12)}",
            refreshSkewMs = 0L,
            clock = { 2L },
            loadLatest = { stored },
            refresh = { _, _ ->
                throw OAuthException("revoked", OAuthFailureKind.INVALID_GRANT)
            },
            clearCredential = { stored = null },
        )

        assertNull(result)
        assertNull(stored)
    }

    @Test
    fun rejectedAccessTokenRefreshesOnceAndReusesConcurrentRotation() = runBlocking {
        var stored = OAuthTokenStore.Token(
            accessToken = "rejected",
            refreshToken = "refresh-1",
            expiresAtMs = 9_000_000L,
        )
        var refreshCount = 0
        val providerId = "rejected-${OAuthPkce.state(12)}"

        val results = (1..20).map {
            async {
                OAuthRefreshCoordinator.resolveRejected(
                    providerId = providerId,
                    rejectedAccessToken = "rejected",
                    loadLatest = { stored },
                    refresh = { _, refreshToken ->
                        refreshCount++
                        assertEquals("refresh-1", refreshToken)
                        delay(5)
                        OAuthTokenStore.Token(
                            accessToken = "rotated",
                            refreshToken = "refresh-2",
                            expiresAtMs = 10_000_000L,
                        ).also { stored = it }
                    },
                    clearCredential = { error("credential must not be cleared") },
                )
            }
        }.awaitAll()

        assertEquals(1, refreshCount)
        assertTrue(results.all { it?.accessToken == "rotated" })
    }

    @Test
    fun loopbackCallbackParsesEncodedProviderError() {
        var received: OAuthCallbackServer.Callback? = null
        val latch = CountDownLatch(1)
        val server = OAuthCallbackServer(
            requestedPort = 0,
            redirectPath = "/oauth/callback",
        ) {
            received = it
            latch.countDown()
        }
        server.start()
        try {
            Socket("127.0.0.1", server.boundPort).use { socket ->
                val request = "GET /oauth/callback?error=access_denied" +
                    "&error_description=user%20cancelled HTTP/1.1\r\n" +
                    "Host: 127.0.0.1\r\nConnection: close\r\n\r\n"
                socket.getOutputStream().apply {
                    write(request.toByteArray(StandardCharsets.US_ASCII))
                    flush()
                }
                socket.getInputStream().bufferedReader().use { it.readLine() }
            }
            assertTrue(latch.await(2, TimeUnit.SECONDS))
            assertEquals("access_denied", received?.error)
            assertEquals("user cancelled", received?.errorDescription)
        } finally {
            server.stop()
        }
    }

    private fun assertFailureKind(kind: OAuthFailureKind, block: () -> Unit) {
        val error = runCatching(block).exceptionOrNull()
        assertTrue(error is OAuthException)
        assertEquals(kind, (error as OAuthException).kind)
    }
}
