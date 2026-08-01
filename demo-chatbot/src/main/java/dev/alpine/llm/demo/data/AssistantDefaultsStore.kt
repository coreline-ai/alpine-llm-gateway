package dev.alpine.llm.demo.data

import android.content.Context
import dev.alpine.llm.demo.assistant.AssistantCatalog
import dev.alpine.llm.demo.model.AssistantSelection

class AssistantDefaultsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        FILE_NAME,
        Context.MODE_PRIVATE,
    )

    @Synchronized
    fun load(): AssistantSelection = AssistantCatalog.resolve(
        runCatching { preferences.getString(KEY_SKILL_ID, null) }.getOrNull(),
        runCatching { preferences.getString(KEY_PERSONA_ID, null) }.getOrNull(),
    )

    @Synchronized
    fun save(selection: AssistantSelection) {
        val resolved = AssistantCatalog.resolve(selection)
        check(
            preferences.edit()
                .putString(KEY_SKILL_ID, resolved.skillId)
                .putString(KEY_PERSONA_ID, resolved.personaId)
                .commit(),
        ) { "Unable to save assistant defaults" }
    }

    @Synchronized
    fun clear() {
        check(preferences.edit().clear().commit()) { "Unable to clear assistant defaults" }
    }

    companion object {
        const val FILE_NAME = "demo_assistant_defaults"
        private const val KEY_SKILL_ID = "skill_id"
        private const val KEY_PERSONA_ID = "persona_id"
    }
}
