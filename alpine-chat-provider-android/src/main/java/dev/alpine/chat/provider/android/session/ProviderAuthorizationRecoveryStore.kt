package dev.alpine.chat.provider.android.session

import android.content.Context
import android.content.SharedPreferences
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import org.json.JSONObject

/**
 * Persists only the non-secret lifecycle state of a Provider authorization attempt.
 *
 * OAuth state, authorization codes, PKCE verifiers and tokens belong to the encrypted
 * OAuthTokenStore and must never be copied here. An interrupted attempt is deliberately
 * not resumed: the UI discards the encrypted transaction and asks the user to sign in again.
 */
class ProviderAuthorizationRecoveryStore(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
    private val attemptIdFactory: () -> String = { UUID.randomUUID().toString() },
) {
    enum class Phase {
        IN_PROGRESS,
        INTERRUPTED,
    }

    enum class Recovery {
        INTERRUPTED,
        EXPIRED,
    }

    data class Attempt(
        val profileId: String,
        val attemptId: String,
        val phase: Phase,
        val startedAtMs: Long,
    )

    private val preferences = context.applicationContext.getSharedPreferences(
        FILE_NAME,
        Context.MODE_PRIVATE,
    )

    /** Writes synchronously so an immediate process kill cannot lose the marker. */
    @Synchronized
    fun begin(profileId: String): Attempt {
        require(profileId.isNotBlank()) { "profileId must not be blank" }
        val nowMs = clock()
        val attempt = Attempt(
            profileId = profileId,
            attemptId = attemptIdFactory().also {
                require(it.isNotBlank()) { "attemptId must not be blank" }
            },
            phase = Phase.IN_PROGRESS,
            startedAtMs = nowMs,
        )
        check(write(attempt)) { "Unable to persist Provider authorization attempt" }
        return attempt
    }

    /**
     * Converts only the matching active attempt. A late coroutine from an older attempt
     * therefore cannot overwrite a newer login started for the same profile.
     */
    @Synchronized
    fun markInterrupted(profileId: String, attemptId: String): Boolean {
        val current = read(profileId) as? ReadResult.Available ?: return false
        if (current.attempt.attemptId != attemptId) return false
        if (current.attempt.phase == Phase.INTERRUPTED) return true
        return write(current.attempt.copy(phase = Phase.INTERRUPTED))
    }

    /** Clears only the matching attempt, protecting a newer attempt from stale completion. */
    @Synchronized
    fun clearIfCurrent(profileId: String, attemptId: String): Boolean {
        val current = read(profileId) as? ReadResult.Available ?: return false
        if (current.attempt.attemptId != attemptId) return false
        return preferences.edit().remove(key(profileId)).commit()
    }

    @Synchronized
    fun clearProfile(profileId: String): Boolean {
        val storedKey = key(profileId)
        if (!preferences.contains(storedKey)) return false
        return preferences.edit().remove(storedKey).commit()
    }

    /**
     * Returns a stable interruption state across Activity recreation. IN_PROGRESS becomes
     * INTERRUPTED on first recovery; malformed records fail closed as INTERRUPTED.
     */
    @Synchronized
    fun recover(profileId: String): Recovery? {
        val nowMs = clock()
        val current = when (val result = read(profileId)) {
            ReadResult.Missing -> return null
            ReadResult.Malformed -> {
                val replacement = Attempt(
                    profileId = profileId,
                    attemptId = attemptIdFactory(),
                    phase = Phase.INTERRUPTED,
                    startedAtMs = nowMs,
                )
                check(write(replacement)) {
                    "Unable to replace malformed Provider authorization attempt"
                }
                replacement
            }
            is ReadResult.Available -> result.attempt
        }
        val ageMs = nowMs - current.startedAtMs
        if (ageMs < 0L || ageMs > MAX_ATTEMPT_AGE_MS) return Recovery.EXPIRED
        if (current.phase == Phase.IN_PROGRESS) {
            check(write(current.copy(phase = Phase.INTERRUPTED))) {
                "Unable to persist interrupted Provider authorization attempt"
            }
        }
        return Recovery.INTERRUPTED
    }

    /** Records an encrypted OAuth transaction left by an older app build without a marker. */
    @Synchronized
    fun recordOrphaned(profileId: String): Recovery {
        val existing = recover(profileId)
        if (existing != null) return existing
        val attempt = Attempt(
            profileId = profileId,
            attemptId = attemptIdFactory(),
            phase = Phase.INTERRUPTED,
            startedAtMs = clock(),
        )
        check(write(attempt)) { "Unable to persist orphaned Provider authorization attempt" }
        return Recovery.INTERRUPTED
    }

    /** Removes markers whose profile no longer exists. */
    @Synchronized
    fun prune(knownProfileIds: Set<String>) {
        val editor = preferences.edit()
        var changed = false
        preferences.all.forEach { (storedKey, _) ->
            if (!storedKey.startsWith(KEY_PREFIX)) return@forEach
            val result = readRaw(storedKey)
            val profileId = (result as? ReadResult.Available)?.attempt?.profileId
            if (profileId == null || profileId !in knownProfileIds) {
                editor.remove(storedKey)
                changed = true
            }
        }
        if (changed) check(editor.commit()) { "Unable to prune Provider authorization attempts" }
    }

    private fun write(attempt: Attempt): Boolean = preferences.edit()
        .putString(
            key(attempt.profileId),
            JSONObject()
                .put("schema", SCHEMA_VERSION)
                .put("profile_id", attempt.profileId)
                .put("attempt_id", attempt.attemptId)
                .put("phase", attempt.phase.name)
                .put("started_at_ms", attempt.startedAtMs)
                .toString(),
        )
        .commit()

    private fun read(profileId: String): ReadResult {
        val result = readRaw(key(profileId))
        return if (result is ReadResult.Available && result.attempt.profileId != profileId) {
            ReadResult.Malformed
        } else {
            result
        }
    }

    private fun readRaw(storedKey: String): ReadResult {
        val raw = preferences.getString(storedKey, null) ?: return ReadResult.Missing
        return runCatching {
            val json = JSONObject(raw)
            require(json.optInt("schema") == SCHEMA_VERSION)
            Attempt(
                profileId = json.optString("profile_id").ifBlank {
                    error("profile_id is missing")
                },
                attemptId = json.optString("attempt_id").ifBlank {
                    error("attempt_id is missing")
                },
                phase = Phase.valueOf(json.optString("phase")),
                startedAtMs = json.optLong("started_at_ms").takeIf { it > 0L }
                    ?: error("started_at_ms is missing"),
            )
        }.fold(
            onSuccess = ReadResult::Available,
            onFailure = { ReadResult.Malformed },
        )
    }

    private fun key(profileId: String): String {
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(
            profileId.toByteArray(StandardCharsets.UTF_8),
        )
        return KEY_PREFIX + encoded
    }

    private sealed interface ReadResult {
        data object Missing : ReadResult
        data object Malformed : ReadResult
        data class Available(val attempt: Attempt) : ReadResult
    }

    companion object {
        const val FILE_NAME = "alpine_provider_authorization_recovery"
        const val MAX_ATTEMPT_AGE_MS = 5 * 60 * 1000L
        private const val SCHEMA_VERSION = 1
        private const val KEY_PREFIX = "attempt_"
    }
}
