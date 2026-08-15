package dev.alpine.chat.backend.codex

import org.junit.Assert.assertEquals
import org.junit.Test

class CodexThreadLinkStoreTest {
    @Test
    fun `new link evicts deterministic excess while updates remain stable`() {
        val full = setOf("conversation.c", "conversation.a", "conversation.b")

        assertEquals(
            listOf("conversation.a"),
            evictionKeys(full, "conversation.d", maximumEntries = 3),
        )
        assertEquals(
            emptyList<String>(),
            evictionKeys(full, "conversation.b", maximumEntries = 3),
        )
    }

    @Test
    fun `already oversized store is repaired in one bounded write`() {
        val oversized = (1..6).mapTo(mutableSetOf()) { "conversation.$it" }

        assertEquals(
            listOf("conversation.1", "conversation.2", "conversation.3"),
            evictionKeys(oversized, "conversation.7", maximumEntries = 4),
        )
    }
}
