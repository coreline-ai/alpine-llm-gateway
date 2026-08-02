package dev.alpine.runtime.background.android

import android.content.Context

internal class RuntimeBackgroundLeaseStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    fun snapshot(): RuntimeBackgroundSnapshot {
        val state = preferences.getString(KEY_STATE, null)
            ?.let { runCatching { RuntimeBackgroundState.valueOf(it) }.getOrNull() }
            ?: RuntimeBackgroundState.STOPPED
        return RuntimeBackgroundSnapshot(
            state = state,
            updatedAtEpochMillis = preferences.getLong(KEY_UPDATED_AT, 0L),
        )
    }

    @Synchronized
    fun set(state: RuntimeBackgroundState, now: Long = System.currentTimeMillis()) {
        preferences.edit()
            .putString(KEY_STATE, state.name)
            .putLong(KEY_UPDATED_AT, now)
            .commit()
    }

    @Synchronized
    fun normalizeAfterProcessStart(now: Long = System.currentTimeMillis()): Boolean {
        val previous = snapshot().state
        if (previous == RuntimeBackgroundState.STOPPED ||
            previous == RuntimeBackgroundState.RECOVERED_AFTER_PROCESS_DEATH
        ) {
            return false
        }
        set(RuntimeBackgroundState.RECOVERED_AFTER_PROCESS_DEATH, now)
        return true
    }

    @Synchronized
    fun recoverStaleTransition(
        now: Long = System.currentTimeMillis(),
        timeoutMillis: Long = STALE_TRANSITION_MILLIS,
    ): Boolean {
        val current = snapshot()
        val transition = current.state == RuntimeBackgroundState.START_REQUESTED ||
            current.state == RuntimeBackgroundState.STOP_REQUESTED
        if (!transition || now - current.updatedAtEpochMillis < timeoutMillis) return false
        set(RuntimeBackgroundState.STOPPED, now)
        return true
    }

    companion object {
        internal const val STALE_TRANSITION_MILLIS = 15L * 60L * 1000L
        private const val PREFERENCES = "alpine-runtime-background-state"
        private const val KEY_STATE = "lifecycle-state"
        private const val KEY_UPDATED_AT = "updated-at-epoch-millis"
    }
}
