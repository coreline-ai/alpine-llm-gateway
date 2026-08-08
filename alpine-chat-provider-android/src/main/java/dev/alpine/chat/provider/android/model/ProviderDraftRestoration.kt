package dev.alpine.chat.provider.android.model

import java.util.UUID

/** Restores only the non-secret identity needed to keep a new form's saveable state stable. */
internal object ProviderDraftRestoration {
    fun restoreIdentity(
        freshDraft: ProviderProfile,
        savedId: String?,
        savedCreatedAtMs: Long?,
    ): ProviderProfile {
        val restoredId = savedId
            ?.takeIf(::isUuid)
            ?: return freshDraft
        val restoredCreatedAtMs = savedCreatedAtMs
            ?.takeIf { it > 0L }
            ?: return freshDraft
        return freshDraft.copy(
            id = restoredId,
            createdAtMs = restoredCreatedAtMs,
        )
    }

    private fun isUuid(value: String): Boolean = runCatching {
        UUID.fromString(value)
    }.isSuccess
}
