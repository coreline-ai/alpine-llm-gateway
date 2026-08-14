package dev.alpine.chat.provider.android

import dev.alpine.chat.provider.android.model.ProviderModelCandidate
import dev.alpine.chat.provider.android.model.ProviderModelSource
import dev.alpine.chat.provider.android.model.ProviderProfile
import dev.alpine.chat.provider.android.model.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DirectChatHostControllerTest {
    @Test
    fun `stale model sessions are removed and cancelled while enabled sessions remain`() {
        val cache = mutableMapOf(
            "profile-a\u0000model-a" to "active",
            "profile-a\u0000model-b" to "disabled",
            "profile-b\u0000model-c" to "deleted-profile",
        )
        val cancelled = mutableListOf<String>()

        evictStaleSessionEntries(
            cache = cache,
            enabledKeys = setOf("profile-a\u0000model-a"),
            onEvict = cancelled::add,
        )

        assertEquals(mapOf("profile-a\u0000model-a" to "active"), cache)
        assertEquals(listOf("disabled", "deleted-profile"), cancelled)
    }

    @Test
    fun `session key ignores catalog metadata but changes with captured transport configuration`() {
        val profile = ProviderProfile.draft(ProviderType.OPENAI_COMPATIBLE, "Custom").copy(
            id = "profile-a",
            authorizationEndpoint = "https://identity.example.test/authorize",
            tokenEndpoint = "https://identity.example.test/token",
            inferenceEndpoint = "https://api.example.test/v1/chat/completions",
            clientId = "app-owned-public-client",
            scopes = listOf("openid"),
            model = "model-a",
            modelCatalog = listOf(
                ProviderModelCandidate("model-a", ProviderModelSource.USER_ADDED),
            ),
        )
        val original = ProviderSessionKey.from(profile, "model-a")
        val catalogOnly = ProviderSessionKey.from(
            profile.copy(
                label = "Renamed",
                modelCatalog = profile.modelCatalog +
                    ProviderModelCandidate("model-b", ProviderModelSource.USER_ADDED),
            ),
            "model-a",
        )
        val endpointChanged = ProviderSessionKey.from(
            profile.copy(inferenceEndpoint = "https://api.example.test/v2/responses"),
            "model-a",
        )

        assertEquals(original, catalogOnly)
        assertNotEquals(original, endpointChanged)
    }
}
