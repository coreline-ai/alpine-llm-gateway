package dev.alpine.chat.provider.android.data

import android.content.Context
import dev.alpine.chat.provider.android.model.ProviderProfile
import dev.alpine.chat.provider.android.model.ProviderType
import org.json.JSONArray

class ProviderProfileStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        FILE_NAME,
        Context.MODE_PRIVATE,
    )

    @Synchronized
    fun load(): List<ProviderProfile> {
        val raw = preferences.getString(KEY_PROFILES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                repeat(array.length()) { index ->
                    val profile = runCatching {
                        ProviderProfile.fromJson(array.getJSONObject(index))
                    }.getOrNull()
                    if (profile != null) add(profile)
                }
            }.sortedBy(ProviderProfile::createdAtMs)
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun find(id: String): ProviderProfile? = load().firstOrNull { it.id == id }

    @Synchronized
    fun upsert(profile: ProviderProfile) {
        require(profile.validationErrors().isEmpty()) { "Provider profile is invalid" }
        val updated = load().toMutableList()
        val index = updated.indexOfFirst { it.id == profile.id }
        if (index >= 0) updated[index] = profile else updated += profile
        save(updated)
    }

    @Synchronized
    fun delete(id: String) {
        save(load().filterNot { it.id == id })
    }

    fun nextLabel(type: ProviderType): String {
        val existing = load().map(ProviderProfile::label).toSet()
        val base = type.displayName.substringBefore(" /")
        if (base !in existing) return base
        var suffix = 2
        while ("$base $suffix" in existing) suffix++
        return "$base $suffix"
    }

    private fun save(profiles: List<ProviderProfile>) {
        val array = JSONArray()
        profiles.forEach { array.put(it.toJson()) }
        check(preferences.edit().putString(KEY_PROFILES, array.toString()).commit()) {
            "Unable to save provider profiles"
        }
    }

    companion object {
        const val FILE_NAME = "demo_llm_profiles"
        private const val KEY_PROFILES = "profiles"
    }
}
