package dev.alpine.chat.feature.assistant

import dev.alpine.chat.feature.llm.ChatRequestBuilder
import dev.alpine.chat.feature.model.AssistantSelection
import dev.alpine.chat.feature.model.ChatMessage
import dev.alpine.chat.feature.model.ChatRole
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantModeTest {
    @Test
    fun `catalog exposes stable recommended defaults and resolves unknown ids safely`() {
        assertEquals(8, AssistantCatalog.skills.size)
        assertEquals(7, AssistantCatalog.personas.size)
        assertEquals("general", AssistantCatalog.resolve("missing", "missing").skillId)
        assertEquals("balanced", AssistantCatalog.resolve("missing", "missing").personaId)
        assertEquals(8, AssistantCatalog.skills.map { it.id }.distinct().size)
        assertEquals(7, AssistantCatalog.personas.map { it.id }.distinct().size)
    }

    @Test
    fun `prompt composition is deterministic and combines core skill then persona`() {
        val selection = AssistantSelection("alpine_linux", "step_by_step")
        val first = AssistantPromptComposer.compose(selection)
        val second = AssistantPromptComposer.compose(selection)

        assertEquals(first, second)
        assertTrue(first.startsWith("You are the assistant in Alpine LLM Gateway."))
        assertTrue(first.indexOf("Alpine Linux specialist") < first.indexOf("ordered diagnostic"))
        assertTrue(first.contains("Do not claim"))
        assertTrue(first.contains("read-only inspection"))
        assertTrue(first.contains("angle-bracket placeholders"))
        assertTrue(first.toByteArray().size <= AssistantPromptComposer.MAX_SYSTEM_INSTRUCTION_BYTES)

        val allPrompts = AssistantCatalog.skills.flatMap { skill ->
            AssistantCatalog.personas.map { persona ->
                val candidate = AssistantPromptComposer.compose(
                    AssistantSelection(skill.id, persona.id),
                )
                assertEquals(
                    candidate,
                    AssistantPromptComposer.compose(AssistantSelection(skill.id, persona.id)),
                )
                assertTrue(
                    candidate.toByteArray().size <=
                        AssistantPromptComposer.MAX_SYSTEM_INSTRUCTION_BYTES,
                )
                candidate
            }
        }
        assertEquals(56, allPrompts.distinct().size)

        val codingReview = AssistantPromptComposer.compose(
            AssistantSelection("code_review", "expert_engineer"),
        )
        assertTrue(codingReview.contains("silently change return"))
        val shell = AssistantPromptComposer.compose(
            AssistantSelection("shell_guide", "step_by_step"),
        )
        assertTrue(shell.contains("primary flow non-mutating"))
    }

    @Test
    fun `request builder includes bounded system guidance without changing message roles`() {
        val prompt = AssistantPromptComposer.compose(AssistantSelection("coding", "concise"))
        val request = JSONObject(
            ChatRequestBuilder.build(
                model = "test-model",
                messages = listOf(ChatMessage(role = ChatRole.USER, text = "hello")),
                systemInstruction = prompt,
            ),
        )

        assertEquals(prompt, request.getString("system"))
        assertEquals("user", request.getJSONArray("messages").getJSONObject(0).getString("role"))
        assertFalse(
            JSONObject(
                ChatRequestBuilder.build(
                    "test-model",
                    listOf(ChatMessage(role = ChatRole.USER, text = "hello")),
                ),
            ).has("system"),
        )
        assertTrue(
            runCatching {
                ChatRequestBuilder.build(
                    "test-model",
                    listOf(ChatMessage(role = ChatRole.USER, text = "hello")),
                    systemInstruction = "unsafe\u0000instruction",
                )
            }.isFailure,
        )
        val privateInstruction = "private-value-" + "x".repeat(17_000)
        val failure = runCatching {
            ChatRequestBuilder.build(
                "test-model",
                listOf(ChatMessage(role = ChatRole.USER, text = "hello")),
                systemInstruction = privateInstruction,
            )
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertFalse(requireNotNull(failure).message.orEmpty().contains(privateInstruction))
        val unicode = JSONObject(
            ChatRequestBuilder.build(
                "test-model",
                listOf(ChatMessage(role = ChatRole.USER, text = "hello")),
                systemInstruction = "한국어 설명과 예시 😀",
            ),
        )
        assertEquals("한국어 설명과 예시 😀", unicode.getString("system"))
    }
}
