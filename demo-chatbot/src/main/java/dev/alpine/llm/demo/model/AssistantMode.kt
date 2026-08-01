package dev.alpine.llm.demo.model

data class AssistantSelection(
    val skillId: String = DEFAULT_SKILL_ID,
    val personaId: String = DEFAULT_PERSONA_ID,
) {
    init {
        requireValidId(skillId)
        requireValidId(personaId)
    }

    companion object {
        const val DEFAULT_SKILL_ID = "general"
        const val DEFAULT_PERSONA_ID = "balanced"
        private val ID_PATTERN = Regex("[a-z0-9_]{1,64}")
        val DEFAULT = AssistantSelection()

        fun requireValidId(id: String) {
            require(ID_PATTERN.matches(id)) { "Assistant mode id is invalid" }
        }
    }
}

data class AssistantSkill(
    val id: String,
    val title: String,
    val description: String,
    val instruction: String,
)

data class ResponsePersona(
    val id: String,
    val title: String,
    val description: String,
    val instruction: String,
)
