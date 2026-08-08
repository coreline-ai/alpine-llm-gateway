package dev.alpine.chat.provider.android.session

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProviderAuthorizationRecoveryStoreInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun clearBefore() = clearPreferences()

    @After
    fun clearAfter() = clearPreferences()

    @Test
    fun interruptedAttemptSurvivesRecreationAndStaleCompletionCannotClearReplacement() {
        var nowMs = 1_000L
        val ids = AtomicInteger()
        fun store() = ProviderAuthorizationRecoveryStore(
            context = context,
            clock = { nowMs },
            attemptIdFactory = { "attempt-${ids.incrementAndGet()}" },
        )

        val first = store().begin("profile-one")
        assertFalse(store().clearIfCurrent("profile-one", "wrong-attempt"))
        assertTrue(store().markInterrupted("profile-one", first.attemptId))
        assertEquals(
            ProviderAuthorizationRecoveryStore.Recovery.INTERRUPTED,
            store().recover("profile-one"),
        )

        val replacement = store().begin("profile-one")
        assertFalse(store().clearIfCurrent("profile-one", first.attemptId))
        assertTrue(store().markInterrupted("profile-one", replacement.attemptId))
        assertEquals(
            ProviderAuthorizationRecoveryStore.Recovery.INTERRUPTED,
            store().recover("profile-one"),
        )
        assertTrue(store().clearIfCurrent("profile-one", replacement.attemptId))
        assertNull(store().recover("profile-one"))
    }

    @Test
    fun expiredMalformedAndDeletedProfileStateFailClosedAndRemainScoped() {
        var nowMs = 10_000L
        val ids = AtomicInteger()
        val store = ProviderAuthorizationRecoveryStore(
            context = context,
            clock = { nowMs },
            attemptIdFactory = { "recovery-${ids.incrementAndGet()}" },
        )
        store.begin("expired-profile")
        store.begin("kept-profile")
        nowMs += ProviderAuthorizationRecoveryStore.MAX_ATTEMPT_AGE_MS + 1L

        assertEquals(
            ProviderAuthorizationRecoveryStore.Recovery.EXPIRED,
            store.recover("expired-profile"),
        )
        store.prune(setOf("kept-profile"))
        assertNull(store.recover("expired-profile"))
        assertEquals(
            ProviderAuthorizationRecoveryStore.Recovery.EXPIRED,
            store.recover("kept-profile"),
        )

        store.clearProfile("kept-profile")
        nowMs += 1L
        store.begin("malformed-profile")
        val preferences = preferences()
        val storedKey = preferences.all.keys.single()
        assertTrue(preferences.edit().putString(storedKey, "{malformed").commit())
        assertEquals(
            ProviderAuthorizationRecoveryStore.Recovery.INTERRUPTED,
            store.recover("malformed-profile"),
        )
    }

    @Test
    fun persistedLifecycleMarkerContainsNoOAuthSecretMaterial() {
        val store = ProviderAuthorizationRecoveryStore(
            context = context,
            clock = { 50_000L },
            attemptIdFactory = { "opaque-attempt-id" },
        )
        store.begin("public-profile-id")

        val raw = preferences().all.values.single() as String
        val json = JSONObject(raw)
        assertEquals(
            setOf("schema", "profile_id", "attempt_id", "phase", "started_at_ms"),
            json.keys().asSequence().toSet(),
        )
        listOf(
            "access_token",
            "refresh_token",
            "authorization_code",
            "code_verifier",
            "code_challenge",
            "oauth_state",
            "client_secret",
        ).forEach { forbidden ->
            assertFalse("Marker must not contain $forbidden", raw.contains(forbidden))
        }
    }

    private fun preferences() = context.getSharedPreferences(
        ProviderAuthorizationRecoveryStore.FILE_NAME,
        Context.MODE_PRIVATE,
    )

    private fun clearPreferences() {
        preferences().edit().clear().commit()
    }
}
