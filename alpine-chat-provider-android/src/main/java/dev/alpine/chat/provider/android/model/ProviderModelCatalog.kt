package dev.alpine.chat.provider.android.model

import java.util.Locale

/** Origin of a locally stored model candidate. It is not proof of account entitlement. */
enum class ProviderModelSource(val wireName: String) {
    PROVIDER_APPROVED("provider_approved"),
    USER_ADDED("user_added"),
    LEGACY_MIGRATED("legacy_migrated"),
    ;

    companion object {
        fun fromWireName(value: String): ProviderModelSource? =
            entries.firstOrNull { it.wireName == value }
    }
}

data class ProviderModelCandidate(
    val modelId: String,
    val source: ProviderModelSource,
    val enabled: Boolean = true,
)

internal fun normalizeModelCatalog(
    candidates: List<ProviderModelCandidate>,
): List<ProviderModelCandidate> {
    val normalized = linkedMapOf<String, ProviderModelCandidate>()
    candidates.forEach { candidate ->
        val modelId = candidate.modelId.trim()
        if (modelId.isEmpty()) return@forEach
        val key = modelId.lowercase(Locale.ROOT)
        if (key !in normalized) {
            normalized[key] = candidate.copy(modelId = modelId)
        }
    }
    return normalized.values.toList()
}

internal fun legacyModelCatalog(model: String): List<ProviderModelCandidate> =
    model.trim().takeIf(String::isNotEmpty)?.let { modelId ->
        listOf(
            ProviderModelCandidate(
                modelId = modelId,
                source = ProviderModelSource.LEGACY_MIGRATED,
            ),
        )
    }.orEmpty()
