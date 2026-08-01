package dev.alpine.llm.demo.assistant

import dev.alpine.llm.demo.model.AssistantSelection
import dev.alpine.llm.demo.model.AssistantSkill
import dev.alpine.llm.demo.model.ResponsePersona

object AssistantCatalog {
    val skills: List<AssistantSkill> = listOf(
        AssistantSkill(
            id = "general",
            title = "General assistant",
            description = "Questions, writing, summaries, and everyday problem solving",
            instruction = "Handle the user's request as a capable general assistant. Focus on the requested outcome and avoid unrelated expansion.",
        ),
        AssistantSkill(
            id = "alpine_linux",
            title = "Alpine/Linux expert",
            description = "Alpine, apk, OpenRC, musl, and shell guidance",
            instruction = "Act as an Alpine Linux specialist. Account for apk, OpenRC, BusyBox, musl, Android-kernel or PRoot constraints when relevant. Distinguish verified commands from assumptions.",
        ),
        AssistantSkill(
            id = "coding",
            title = "Coding assistant",
            description = "Kotlin, Python, Shell, JavaScript, and implementation help",
            instruction = "Prioritize correct, maintainable code. State material assumptions, preserve existing contracts, and include concise verification steps when proposing changes. If a fix changes return values, nullability, or exception behavior, identify that contract change and present the safest compatible alternative.",
        ),
        AssistantSkill(
            id = "debugging",
            title = "Error analysis",
            description = "Logs, stack traces, command failures, and recovery steps",
            instruction = "Diagnose the evidence before proposing fixes. Separate observed symptoms, likely causes, read-only checks, and the safest ordered recovery steps. Do not invent unseen log details. Put file creation, ownership changes, installs, restarts, and other mutations in a clearly labeled later step that requires confirmation.",
        ),
        AssistantSkill(
            id = "code_review",
            title = "Code review",
            description = "Bugs, maintainability, performance, and security risks",
            instruction = "Review for concrete correctness, regression, security, performance, and maintainability issues. Prioritize actionable findings by impact and cite the relevant code context supplied by the user. Flag fixes that silently change return, nullability, error, persistence, or compatibility contracts.",
        ),
        AssistantSkill(
            id = "shell_guide",
            title = "Command guide",
            description = "Shell commands with explanations and safety notes",
            instruction = "Propose shell commands but never claim they were executed. Explain working directory, expected effect, destructive or network impact, and a verification or rollback step when relevant. For read-only requests, keep the primary flow non-mutating and separate installs or writes as optional confirmed changes. Do not put angle-bracket placeholders such as <service> in copyable shell commands; use a quoted example or a named environment variable.",
        ),
        AssistantSkill(
            id = "documentation",
            title = "Documentation",
            description = "README, plans, API explanations, and change records",
            instruction = "Produce clear, implementation-oriented documentation with stable terminology, useful headings, exact paths or commands when provided, and explicit current state and limitations.",
        ),
        AssistantSkill(
            id = "learning",
            title = "Learning assistant",
            description = "Concept explanations, examples, and guided practice",
            instruction = "Teach progressively. Define unfamiliar terms, connect them to a simple example, verify understanding through a compact recap, and avoid assuming advanced background.",
        ),
    )

    val personas: List<ResponsePersona> = listOf(
        ResponsePersona(
            id = "balanced",
            title = "Balanced",
            description = "A practical balance of conclusions, context, and examples",
            instruction = "Use a balanced response: lead with the answer, add only the context and examples needed to act on it, and keep the structure easy to scan.",
        ),
        ResponsePersona(
            id = "concise",
            title = "Concise",
            description = "Short, direct answers focused on the next action",
            instruction = "Be concise and direct. Prefer the conclusion and essential action over background, while retaining warnings that materially affect correctness or safety.",
        ),
        ResponsePersona(
            id = "beginner_friendly",
            title = "Beginner friendly",
            description = "Plain language, terminology help, and examples",
            instruction = "Use beginner-friendly language. Explain necessary technical terms, use short steps and concrete examples, and do not talk down to the user.",
        ),
        ResponsePersona(
            id = "expert_engineer",
            title = "Expert engineer",
            description = "Technical evidence, edge cases, code, and verification",
            instruction = "Respond for an experienced engineer. Emphasize contracts, tradeoffs, edge cases, code-level detail, and reproducible verification without explaining basic concepts unless needed.",
        ),
        ResponsePersona(
            id = "step_by_step",
            title = "Step-by-step solver",
            description = "Ordered diagnosis and execution steps",
            instruction = "Organize the response as an ordered diagnostic or implementation flow. Make prerequisites, decision points, checks, and completion criteria explicit.",
        ),
        ResponsePersona(
            id = "critical_reviewer",
            title = "Critical reviewer",
            description = "Risks, missing cases, counterexamples, and improvements",
            instruction = "Adopt a constructive critical-review stance. Lead with the highest-impact gaps, challenge assumptions with specific counterexamples, and provide a practical correction for each finding.",
        ),
        ResponsePersona(
            id = "document_writer",
            title = "Document writer",
            description = "Polished headings, tables, lists, and consistent terminology",
            instruction = "Write polished documentation. Use clear headings, compact tables or ordered lists where they improve comprehension, consistent terminology, and an explicit status or next-action section.",
        ),
    )

    private val skillsById = skills.associateBy(AssistantSkill::id)
    private val personasById = personas.associateBy(ResponsePersona::id)

    init {
        require(skills.size == skillsById.size) { "Assistant skill ids must be unique" }
        require(personas.size == personasById.size) { "Assistant persona ids must be unique" }
        require(skillsById.containsKey(AssistantSelection.DEFAULT_SKILL_ID)) {
            "Default assistant skill is missing"
        }
        require(personasById.containsKey(AssistantSelection.DEFAULT_PERSONA_ID)) {
            "Default assistant persona is missing"
        }
        skills.forEach { validateEntry(it.id, it.title, it.description, it.instruction) }
        personas.forEach { validateEntry(it.id, it.title, it.description, it.instruction) }
    }

    fun skill(id: String): AssistantSkill = skillsById[id]
        ?: checkNotNull(skillsById[AssistantSelection.DEFAULT_SKILL_ID])

    fun persona(id: String): ResponsePersona = personasById[id]
        ?: checkNotNull(personasById[AssistantSelection.DEFAULT_PERSONA_ID])

    fun resolve(selection: AssistantSelection): AssistantSelection = AssistantSelection(
        skillId = skill(selection.skillId).id,
        personaId = persona(selection.personaId).id,
    )

    fun resolve(skillId: String?, personaId: String?): AssistantSelection = resolve(
        AssistantSelection(
            skillId = skillId?.takeIf(::isValidId) ?: AssistantSelection.DEFAULT_SKILL_ID,
            personaId = personaId?.takeIf(::isValidId) ?: AssistantSelection.DEFAULT_PERSONA_ID,
        ),
    )

    fun containsSkill(id: String): Boolean = skillsById.containsKey(id)

    fun containsPersona(id: String): Boolean = personasById.containsKey(id)

    private fun validateEntry(id: String, title: String, description: String, instruction: String) {
        AssistantSelection.requireValidId(id)
        require(title.isNotBlank() && description.isNotBlank() && instruction.isNotBlank()) {
            "Assistant catalog entry is incomplete"
        }
    }

    private fun isValidId(id: String): Boolean = runCatching {
        AssistantSelection.requireValidId(id)
    }.isSuccess
}
