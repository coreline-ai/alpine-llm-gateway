package dev.alpine.chat.provider.android.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.alpine.chat.provider.android.model.ProviderModelCandidate
import dev.alpine.chat.provider.android.model.ProviderModelSource
import dev.alpine.chat.provider.android.model.ProviderProfile
import dev.alpine.chat.provider.android.model.ProviderType
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProviderProfileStoreInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun clearBefore() = clear()

    @After
    fun clearAfter() = clear()

    @Test
    fun catalogRoundTripsAndMalformedItemsDoNotDropTheProfile() {
        val store = ProviderProfileStore(context)
        val profile = validProfile()
        store.upsert(profile)

        assertEquals(profile, store.find(profile.id))

        val malformed = profile.toJson().apply {
            put(
                "model_catalog",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("model_id", "other-model")
                            .put("source", ProviderModelSource.USER_ADDED.wireName),
                    )
                    .put(
                        JSONObject()
                            .put("model_id", profile.model)
                            .put("source", "future-or-unknown-source"),
                    ),
            )
        }
        context.getSharedPreferences(ProviderProfileStore.FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("profiles", JSONArray().put(malformed).toString())
            .commit()

        val restored = store.load().single()
        assertEquals(profile.id, restored.id)
        assertEquals(listOf("other-model", "model-a"), restored.enabledModelIds())
        assertTrue(restored.validationErrors().isEmpty())
    }

    private fun validProfile() = ProviderProfile.draft(
        ProviderType.OPENAI_COMPATIBLE,
        "Store Test",
    ).copy(
        id = "store-profile",
        authorizationEndpoint = "https://identity.example.test/authorize",
        tokenEndpoint = "https://identity.example.test/token",
        inferenceEndpoint = "https://api.example.test/v1/chat/completions",
        clientId = "app-owned-public-client",
        scopes = listOf("openid"),
        model = "model-a",
        modelCatalog = listOf(
            ProviderModelCandidate("model-a", ProviderModelSource.USER_ADDED),
            ProviderModelCandidate("model-b", ProviderModelSource.USER_ADDED, enabled = false),
        ),
        createdAtMs = 1L,
    )

    private fun clear() {
        context.getSharedPreferences(ProviderProfileStore.FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }
}
