package dev.alpine.chat.provider.android.model

import dev.alpine.llm.AnthropicOAuthContract
import dev.alpine.llm.CodexOAuthContract
import dev.alpine.llm.GeminiOAuthContract
import dev.alpine.llm.XaiOAuthContract
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
    fun `anthropic draft requires a host owned client registration`() {
        val draft = ProviderProfile.draft(ProviderType.ANTHROPIC, "Claude")

        assertEquals(AnthropicOAuthContract.AUTHORIZATION_ENDPOINT, draft.authorizationEndpoint)
        assertEquals(AnthropicOAuthContract.TOKEN_ENDPOINT, draft.tokenEndpoint)
        assertEquals(AnthropicOAuthContract.MESSAGES_ENDPOINT, draft.inferenceEndpoint)
        assertTrue(draft.clientId.isBlank())
        assertEquals(AnthropicOAuthContract.SCOPES, draft.scopes)
        assertEquals(AnthropicOAuthContract.CALLBACK_PORT, draft.callbackPort)
        assertEquals(AnthropicOAuthContract.OAUTH_BETA, draft.anthropicBeta)
        assertEquals(AnthropicProfileDefaults.DEFAULT_MODEL, draft.model)
        assertTrue(AnthropicProfileDefaults.DEFAULT_MODEL in AnthropicProfileDefaults.MODELS)
        assertEquals(
            "Public client ID is required",
            draft.validationErrors()[ProviderProfile.Field.CLIENT_ID],
        )
    }

    @Test
    fun `anthropic profile rejects modified compatibility contract`() {
        val invalid = validProfile(ProviderType.ANTHROPIC).copy(
            authorizationEndpoint = "https://identity.example.test/authorize",
            tokenEndpoint = "https://identity.example.test/token",
            inferenceEndpoint = "https://relay.example.test/messages",
            clientId = "host-owned-public-client",
            scopes = listOf("openid"),
            callbackPort = 54544,
            anthropicBeta = "different-beta",
        )

        val errors = invalid.validationErrors()
        assertTrue(errors.containsKey(ProviderProfile.Field.AUTHORIZATION_ENDPOINT))
        assertTrue(errors.containsKey(ProviderProfile.Field.TOKEN_ENDPOINT))
        assertTrue(errors.containsKey(ProviderProfile.Field.INFERENCE_ENDPOINT))
        assertFalse(errors.containsKey(ProviderProfile.Field.CLIENT_ID))
        assertTrue(errors.containsKey(ProviderProfile.Field.SCOPES))
        assertTrue(errors.containsKey(ProviderProfile.Field.CALLBACK_PORT))
        assertTrue(errors.containsKey(ProviderProfile.Field.ANTHROPIC_BETA))
    }

    @Test
    fun `gemini draft prefills official protocol and current model catalog`() {
        val draft = ProviderProfile.draft(ProviderType.GEMINI, "Gemini")

        assertEquals(GeminiOAuthContract.AUTHORIZATION_ENDPOINT, draft.authorizationEndpoint)
        assertEquals(GeminiOAuthContract.TOKEN_ENDPOINT, draft.tokenEndpoint)
        assertEquals(GeminiOAuthContract.GENERATE_CONTENT_ENDPOINT, draft.inferenceEndpoint)
        assertEquals(GeminiOAuthContract.SCOPES, draft.scopes)
        assertEquals(GeminiOAuthContract.CALLBACK_PORT, draft.callbackPort)
        assertEquals(GeminiProfileDefaults.DEFAULT_MODEL, draft.model)
        assertTrue(GeminiProfileDefaults.DEFAULT_MODEL in GeminiProfileDefaults.MODELS)
        assertTrue(draft.clientId.isBlank())
        assertTrue(draft.validationErrors().containsKey(ProviderProfile.Field.CLIENT_ID))
    }

    @Test
    fun `gemini profile rejects modified official protocol contract`() {
        val invalid = validProfile(ProviderType.GEMINI).copy(
            authorizationEndpoint = "https://identity.example.test/authorize",
            tokenEndpoint = "https://identity.example.test/token",
            inferenceEndpoint = "https://relay.example.test/models/{model}:generateContent",
            scopes = listOf("openid"),
            callbackPort = 54545,
        )

        val errors = invalid.validationErrors()
        assertTrue(errors.containsKey(ProviderProfile.Field.AUTHORIZATION_ENDPOINT))
        assertTrue(errors.containsKey(ProviderProfile.Field.TOKEN_ENDPOINT))
        assertTrue(errors.containsKey(ProviderProfile.Field.INFERENCE_ENDPOINT))
        assertTrue(errors.containsKey(ProviderProfile.Field.SCOPES))
        assertTrue(errors.containsKey(ProviderProfile.Field.CALLBACK_PORT))
    }

    @Test
    fun `codex draft requires a host owned client registration`() {
        val draft = ProviderProfile.draft(ProviderType.CODEX, "Codex")

        assertEquals(CodexOAuthContract.AUTHORIZATION_ENDPOINT, draft.authorizationEndpoint)
        assertEquals(CodexOAuthContract.TOKEN_ENDPOINT, draft.tokenEndpoint)
        assertEquals(CodexOAuthContract.RESPONSES_ENDPOINT, draft.inferenceEndpoint)
        assertEquals(CodexOAuthContract.SCOPES, draft.scopes)
        assertEquals(CodexOAuthContract.CALLBACK_PORT, draft.callbackPort)
        assertTrue(draft.clientId.isBlank())
        assertEquals(CodexProfileDefaults.DEFAULT_MODEL, draft.model)
        assertTrue(CodexProfileDefaults.DEFAULT_MODEL in CodexProfileDefaults.MODELS)
        assertTrue(draft.validationErrors().containsKey(ProviderProfile.Field.CLIENT_ID))
    }

    @Test
    fun `codex profile rejects modified endpoint scope and callback contract`() {
        val invalid = validProfile(ProviderType.CODEX).copy(
            authorizationEndpoint = "https://identity.example.test/authorize",
            tokenEndpoint = "https://identity.example.test/token",
            inferenceEndpoint = "https://relay.example.test/responses",
            scopes = listOf("openid"),
            callbackPort = 54545,
        )

        val errors = invalid.validationErrors()
        assertTrue(errors.containsKey(ProviderProfile.Field.AUTHORIZATION_ENDPOINT))
        assertTrue(errors.containsKey(ProviderProfile.Field.TOKEN_ENDPOINT))
        assertTrue(errors.containsKey(ProviderProfile.Field.INFERENCE_ENDPOINT))
        assertTrue(errors.containsKey(ProviderProfile.Field.SCOPES))
        assertTrue(errors.containsKey(ProviderProfile.Field.CALLBACK_PORT))
    }

    @Test
    fun `xai draft requires a host owned client registration`() {
        val draft = ProviderProfile.draft(ProviderType.XAI, "Grok")

        assertEquals(XaiOAuthContract.AUTHORIZATION_ENDPOINT, draft.authorizationEndpoint)
        assertEquals(XaiOAuthContract.TOKEN_ENDPOINT, draft.tokenEndpoint)
        assertEquals(XaiOAuthContract.CHAT_COMPLETIONS_ENDPOINT, draft.inferenceEndpoint)
        assertEquals(XaiOAuthContract.SCOPES, draft.scopes)
        assertEquals(XaiOAuthContract.CALLBACK_PORT, draft.callbackPort)
        assertTrue(draft.clientId.isBlank())
        assertEquals(XaiProfileDefaults.DEFAULT_MODEL, draft.model)
        assertTrue(XaiProfileDefaults.DEFAULT_MODEL in XaiProfileDefaults.MODELS)
        assertTrue(draft.validationErrors().containsKey(ProviderProfile.Field.CLIENT_ID))
    }

    @Test
    fun `xai profile rejects modified endpoint scope and callback contract`() {
        val invalid = validProfile(ProviderType.XAI).copy(
            authorizationEndpoint = "https://identity.example.test/authorize",
            tokenEndpoint = "https://identity.example.test/token",
            inferenceEndpoint = "https://relay.example.test/chat/completions",
            scopes = listOf("openid"),
            callbackPort = 54545,
        )

        val errors = invalid.validationErrors()
        assertTrue(errors.containsKey(ProviderProfile.Field.AUTHORIZATION_ENDPOINT))
        assertTrue(errors.containsKey(ProviderProfile.Field.TOKEN_ENDPOINT))
        assertTrue(errors.containsKey(ProviderProfile.Field.INFERENCE_ENDPOINT))
        assertTrue(errors.containsKey(ProviderProfile.Field.SCOPES))
        assertTrue(errors.containsKey(ProviderProfile.Field.CALLBACK_PORT))
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
        val blank = validProfile(ProviderType.OPENAI_COMPATIBLE).copy(
            label = " ",
            scopes = listOf(" "),
        )
        assertTrue(blank.validationErrors().containsKey(ProviderProfile.Field.LABEL))
        assertTrue(blank.validationErrors().containsKey(ProviderProfile.Field.SCOPES))

        assertTrue(
            validProfile(ProviderType.OPENAI_COMPATIBLE).copy(callbackPort = 0)
                .validationErrors().containsKey(ProviderProfile.Field.CALLBACK_PORT),
        )
        assertTrue(
            validProfile(ProviderType.OPENAI_COMPATIBLE).copy(callbackPort = 65534)
                .validationErrors().containsKey(ProviderProfile.Field.CALLBACK_PORT),
        )
        assertTrue(
            validProfile(ProviderType.OPENAI_COMPATIBLE).copy(callbackPort = 1)
                .validationErrors().containsKey(ProviderProfile.Field.CALLBACK_PORT).not(),
        )
        assertTrue(
            validProfile(ProviderType.OPENAI_COMPATIBLE).copy(callbackPort = 65533)
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

    private fun validProfile(type: ProviderType): ProviderProfile {
        val draft = ProviderProfile.draft(type, type.displayName)
        return draft.copy(
            id = "profile-${type.wireName}",
            authorizationEndpoint = if (
                type == ProviderType.ANTHROPIC || type == ProviderType.GEMINI ||
                    type == ProviderType.CODEX || type == ProviderType.XAI
            ) {
                draft.authorizationEndpoint
            } else {
                "https://identity.example.test/oauth/authorize"
            },
            tokenEndpoint = if (
                type == ProviderType.ANTHROPIC || type == ProviderType.GEMINI ||
                type == ProviderType.CODEX || type == ProviderType.XAI
            ) {
                draft.tokenEndpoint
            } else {
                "https://identity.example.test/oauth/token"
            },
            clientId = "host-owned-public-client",
            model = "test-model",
            anthropicBeta = draft.anthropicBeta,
            googleProjectId = if (type == ProviderType.GEMINI) "test-project" else null,
            createdAtMs = 1234L,
        )
    }
}
