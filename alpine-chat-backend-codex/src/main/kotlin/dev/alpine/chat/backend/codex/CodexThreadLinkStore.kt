package dev.alpine.chat.backend.codex

import android.content.Context

interface CodexThreadLinkStore {
    fun get(conversationId: String): String?
    fun put(conversationId: String, threadId: String)
    fun remove(conversationId: String)
}

/** Stores only opaque host/native IDs in app-private preferences. */
class AndroidCodexThreadLinkStore(context: Context) : CodexThreadLinkStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val lock = Any()

    override fun get(conversationId: String): String? = synchronized(lock) {
        preferences.getString(key(conversationId), null)?.let(::opaqueId)
    }

    override fun put(conversationId: String, threadId: String) {
        synchronized(lock) {
            val incomingKey = key(conversationId)
            val storedKeys = preferences.all.keys.filterTo(mutableSetOf()) {
                it.startsWith(CONVERSATION_PREFIX)
            }
            val editor = preferences.edit()
            evictionKeys(storedKeys, incomingKey).forEach(editor::remove)
            check(editor.putString(incomingKey, opaqueId(threadId)).commit())
        }
    }

    override fun remove(conversationId: String) {
        synchronized(lock) {
            check(preferences.edit().remove(key(conversationId)).commit())
        }
    }

    private fun key(conversationId: String): String =
        "$CONVERSATION_PREFIX${opaqueId(conversationId)}"

    private fun opaqueId(value: String): String = value.takeIf {
        it.length in 1..MAX_ID_LENGTH && it.all { character ->
            character.isLetterOrDigit() || character in "-_"
        }
    } ?: throw IllegalArgumentException("opaque id is invalid")

    private companion object {
        const val PREFERENCES_NAME = "codex_agent_thread_links"
        const val MAX_ID_LENGTH = 128
        const val CONVERSATION_PREFIX = "conversation."
    }
}

/**
 * Returns a deterministic, bounded eviction set without exposing conversation or thread values.
 * The fixed synthetic profile owns this store, so the conversation ID is the complete link key.
 */
internal fun evictionKeys(
    storedKeys: Set<String>,
    incomingKey: String,
    maximumEntries: Int = 256,
): List<String> {
    require(maximumEntries > 0)
    val projectedSize = storedKeys.size + if (incomingKey in storedKeys) 0 else 1
    val required = (projectedSize - maximumEntries).coerceAtLeast(0)
    return storedKeys.asSequence()
        .filterNot { it == incomingKey }
        .sorted()
        .take(required)
        .toList()
}
