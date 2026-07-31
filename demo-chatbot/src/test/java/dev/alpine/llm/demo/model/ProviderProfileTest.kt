package dev.alpine.llm.demo.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class ProviderProfileTest {
    @Test
    fun `all supported provider profiles round trip without credentials`() {
        ProviderType.entries.forEach { type ->
            val profile = validProfile(type)
            val raw = profile.toJson().toString()
            val restored = ProviderProfile.fromJson(profile.toJson())

            assertEquals(profile, restored)
            assertFalse(raw.contains("access_token"))
            assertFalse(raw.contains("refresh_token"))
            assertFalse(raw.contains("client_secret"))
        }
    }

    @Test
    fun `gemini accepts model placeholder but requires it`() {
        val valid = validProfile(ProviderType.GEMINI)
        assertTrue(valid.validationErrors().isEmpty())

        val invalid = valid.copy(
            inferenceEndpoint =
                "https://generativelanguage.googleapis.com/v1beta/models/fixed:generateContent",
        )
        assertTrue(
            invalid.validationErrors().containsKey(
                ProviderProfile.Field.INFERENCE_ENDPOINT,
            ),
        )
    }

    @Test
    fun `profile requires https public client and model`() {
        val invalid = validProfile(ProviderType.ANTHROPIC).copy(
            authorizationEndpoint = "http://provider.example/auth",
            tokenEndpoint = "",
            clientId = "",
            model = "",
            callbackPort = 70000,
        )
        val errors = invalid.validationErrors()

        assertTrue(errors.containsKey(ProviderProfile.Field.AUTHORIZATION_ENDPOINT))
        assertTrue(errors.containsKey(ProviderProfile.Field.TOKEN_ENDPOINT))
        assertTrue(errors.containsKey(ProviderProfile.Field.CLIENT_ID))
        assertTrue(errors.containsKey(ProviderProfile.Field.MODEL))
        assertTrue(errors.containsKey(ProviderProfile.Field.CALLBACK_PORT))
    }

    @Test
    fun `validation rejects credentials embedded in endpoint and malformed urls`() {
        val invalid = validProfile(ProviderType.OPENAI_COMPATIBLE).copy(
            authorizationEndpoint = "https://user:password@identity.example.test/oauth/authorize",
            tokenEndpoint = "not-a-url",
            inferenceEndpoint = "https://",
        )

        val errors = invalid.validationErrors()

        assertTrue(errors.containsKey(ProviderProfile.Field.AUTHORIZATION_ENDPOINT))
        assertTrue(errors.containsKey(ProviderProfile.Field.TOKEN_ENDPOINT))
        assertTrue(errors.containsKey(ProviderProfile.Field.INFERENCE_ENDPOINT))
    }

    @Test
    fun `validation rejects blank and boundary callback ports`() {
        val blank = validProfile(ProviderType.ANTHROPIC).copy(
            label = " ",
            scopes = listOf(" "),
        )
        assertTrue(blank.validationErrors().containsKey(ProviderProfile.Field.LABEL))
        assertTrue(blank.validationErrors().containsKey(ProviderProfile.Field.SCOPES))

        assertTrue(
            validProfile(ProviderType.ANTHROPIC).copy(callbackPort = 0)
                .validationErrors().containsKey(ProviderProfile.Field.CALLBACK_PORT),
        )
        assertTrue(
            validProfile(ProviderType.ANTHROPIC).copy(callbackPort = 65534)
                .validationErrors().containsKey(ProviderProfile.Field.CALLBACK_PORT),
        )
        assertTrue(
            validProfile(ProviderType.ANTHROPIC).copy(callbackPort = 1)
                .validationErrors().containsKey(ProviderProfile.Field.CALLBACK_PORT).not(),
        )
        assertTrue(
            validProfile(ProviderType.ANTHROPIC).copy(callbackPort = 65533)
                .validationErrors().containsKey(ProviderProfile.Field.CALLBACK_PORT).not(),
        )
    }

    @Test
    fun `malformed profile json fails closed`() {
        assertThrows(Exception::class.java) {
            ProviderProfile.fromJson(JSONObject("{\"id\":\"missing-fields\"}"))
        }
        assertThrows(Exception::class.java) {
            ProviderProfile.fromJson(
                JSONObject(validProfile(ProviderType.GEMINI).toJson().toString()).put(
                    "type",
                    "unsupported",
                ),
            )
        }
    }

    @Test
    fun `profile json contains no credential field names or values`() {
        val profile = validProfile(ProviderType.ANTHROPIC)
        val raw = profile.toJson().toString()

        listOf(
            "access_token",
            "refresh_token",
            "token_type",
            "expires_at",
            "client_secret",
            "authorization_code",
            "super-secret-token-value",
        ).forEach { forbidden ->
            assertFalse("Unexpected credential material: $forbidden", raw.contains(forbidden))
        }
    }

    @Test
    fun `oauth identity edits require login but model and endpoint edits do not`() {
        val original = validProfile(ProviderType.ANTHROPIC)

        assertTrue(
            original.copy(clientId = "another-public-client")
                .requiresReauthenticationComparedTo(original),
        )
        assertTrue(
            original.copy(scopes = original.scopes + "models.read")
                .requiresReauthenticationComparedTo(original),
        )
        assertTrue(
            original.copy(
                authorizationEndpoint = "https://identity-2.example.test/oauth/authorize",
            ).requiresReauthenticationComparedTo(original),
        )
        assertTrue(
            original.copy(
                tokenEndpoint = "https://identity-2.example.test/oauth/token",
            ).requiresReauthenticationComparedTo(original),
        )
        assertTrue(
            original.copy(callbackPort = original.callbackPort + 1)
                .requiresReauthenticationComparedTo(original),
        )
        assertFalse(
            original.copy(
                label = "Renamed",
                model = "another-model",
                inferenceEndpoint = "https://api.example.test/v1/messages",
            ).requiresReauthenticationComparedTo(original),
        )
    }

    private fun validProfile(type: ProviderType): ProviderProfile = ProviderProfile(
        id = "profile-${type.wireName}",
        label = type.displayName,
        type = type,
        authorizationEndpoint = "https://identity.example.test/oauth/authorize",
        tokenEndpoint = "https://identity.example.test/oauth/token",
        inferenceEndpoint = type.inferenceEndpointPlaceholder,
        clientId = "android-public-client",
        scopes = type.defaultScopes.split(" "),
        model = "test-model",
        callbackPort = 54545,
        anthropicBeta = if (type == ProviderType.ANTHROPIC) "oauth-test" else null,
        googleProjectId = if (type == ProviderType.GEMINI) "test-project" else null,
        createdAtMs = 1234L,
    )
}
