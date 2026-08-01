package dev.alpine.llm.demo.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseConstraintsTest {
    @Test
    fun `detects English word sentence and bullet constraints`() {
        val detected = ResponseConstraintDetector.detect(
            "Explain PKCE in under ninety words, exactly two sentences, and three bullets.",
        )

        assertEquals(89, detected.maxWords)
        assertEquals(2, detected.exactSentences)
        assertEquals(3, detected.exactBullets)
    }

    @Test
    fun `detects Korean limits and ignores unrelated numbers`() {
        val detected = ResponseConstraintDetector.detect(
            "포트 8080의 상태를 90단어 이하, 두 문장으로, 세 개 불릿으로 설명해줘.",
        )

        assertEquals(90, detected.maxWords)
        assertEquals(2, detected.exactSentences)
        assertEquals(3, detected.exactBullets)
        assertTrue(ResponseConstraintDetector.detect("포트 8080을 확인해줘").isEmpty)
    }

    @Test
    fun `ambiguous exact counts are not enforced`() {
        val detected = ResponseConstraintDetector.detect("Use two or three sentences.")

        assertNull(detected.exactSentences)
    }

    @Test
    fun `validates markdown aware counts`() {
        val constraints = ResponseConstraints(maxWords = 6, exactSentences = 2, exactBullets = 2)
        val response = "- **First** item works.\n- `Second` item works!"

        assertTrue(constraints.validate(response).isEmpty())
        assertEquals(6, ResponseConstraintDetector.wordCount(response))
        assertEquals(2, ResponseConstraintDetector.sentenceCount(response))
        assertEquals(2, ResponseConstraintDetector.bulletCount(response))
    }

    @Test
    fun `reports safe bounded violations and correction instruction`() {
        val constraints = ResponseConstraints(maxWords = 4)
        val violations = constraints.validate("one two three four five")
        val instruction = constraints.augmentSystemInstruction("base", violations)

        assertEquals(1, violations.size)
        assertTrue(instruction.contains("at most 4 words"))
        assertTrue(instruction.contains("5 words, maximum 4"))
        assertTrue(
            instruction.toByteArray().size <= AssistantPromptComposer.MAX_SYSTEM_INSTRUCTION_BYTES,
        )
    }

    @Test
    fun `counts visible fenced code words but not code bullets as response bullets`() {
        val response = """
            One sentence.

            ```text
            - code value
            ```
        """.trimIndent()

        assertEquals(4, ResponseConstraintDetector.wordCount(response))
        assertEquals(1, ResponseConstraintDetector.sentenceCount(response))
        assertEquals(0, ResponseConstraintDetector.bulletCount(response))
    }
}
