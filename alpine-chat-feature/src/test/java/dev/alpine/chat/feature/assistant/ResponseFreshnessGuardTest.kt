package dev.alpine.chat.feature.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseFreshnessGuardTest {
    @Test
    fun `detects explicit web and time sensitive requests in English and Korean`() {
        listOf(
            "Search the web and verify today's Seoul weather.",
            "What is the latest stable Alpine release?",
            "오늘 서울 날씨를 웹에서 확인해줘.",
            "최신 환율을 알려줘.",
        ).forEach { prompt ->
            assertTrue(prompt, ResponseFreshnessGuardDetector.detect(prompt).isActive)
        }
    }

    @Test
    fun `does not treat local current project or stable questions as live verification`() {
        listOf(
            "현재 프로젝트 구조를 설명해줘.",
            "Explain Alpine package management.",
            "Verify this checksum against the value I supplied.",
        ).forEach { prompt ->
            assertFalse(prompt, ResponseFreshnessGuardDetector.detect(prompt).isActive)
        }
    }

    @Test
    fun `flags positive browsing claims but accepts explicit tool limitation`() {
        val guard = ResponseFreshnessGuard(requiresExternalVerification = true)

        assertNotNull(guard.validate("I checked the web today and verified the value."))
        assertNotNull(guard.validate("웹에서 직접 확인했습니다."))
        assertNull(
            guard.validate(
                "I cannot access the live web in this app, so I cannot verify today's value.",
            ),
        )
        assertNull(guard.validate("웹에 접속할 수 없어 오늘 값은 확인할 수 없습니다."))
        assertNull(guard.validate("I checked the values you supplied and found no mismatch."))
    }

    @Test
    fun `inactive guard does not validate ordinary output`() {
        assertNull(
            ResponseFreshnessGuard().validate("I checked the web today and verified the value."),
        )
    }

    @Test
    fun `system supplement states tool boundary and correction without raw response`() {
        val guard = ResponseFreshnessGuard(requiresExternalVerification = true)
        val first = guard.augmentSystemInstruction("Base")
        val corrected = guard.augmentSystemInstruction("Base", ResponseFreshnessViolation)

        assertTrue(first.contains("has no web, browser, search, or live-data tool"))
        assertTrue(first.contains("cannot verify it live"))
        assertTrue(corrected.contains("previous draft claimed external verification"))
    }
}
