package dev.alpine.chat.provider.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ProviderDraftRestorationTest {
    @Test
    fun `valid saved identity keeps a new draft stable`() {
        val fresh = ProviderProfile.draft(ProviderType.GEMINI, "Gemini")
        val savedId = "8f0a6023-c11d-4988-8bc9-d93ee10765bf"

        val restored = ProviderDraftRestoration.restoreIdentity(
            freshDraft = fresh,
            savedId = savedId,
            savedCreatedAtMs = 1234L,
        )

        assertEquals(savedId, restored.id)
        assertEquals(1234L, restored.createdAtMs)
        assertEquals(fresh.copy(id = savedId, createdAtMs = 1234L), restored)
    }

    @Test
    fun `invalid or incomplete saved identity falls back to fresh draft`() {
        val fresh = ProviderProfile.draft(ProviderType.CODEX, "Codex")

        assertEquals(
            fresh,
            ProviderDraftRestoration.restoreIdentity(fresh, "not-a-uuid", 1234L),
        )
        assertEquals(
            fresh,
            ProviderDraftRestoration.restoreIdentity(
                fresh,
                "8f0a6023-c11d-4988-8bc9-d93ee10765bf",
                0L,
            ),
        )
        assertNotEquals("not-a-uuid", fresh.id)
    }
}
