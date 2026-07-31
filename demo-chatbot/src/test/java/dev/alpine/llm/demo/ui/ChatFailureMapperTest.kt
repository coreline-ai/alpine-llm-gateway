package dev.alpine.llm.demo.ui

import dev.alpine.llm.demo.ui.state.ChatFailureKind
import dev.alpine.llm.demo.ui.state.ChatFailureMapper
import dev.alpine.llm.demo.ui.state.ChatRecoveryAction
import dev.alpine.llm.demo.ui.state.SafeProviderStatus
import dev.alpine.llm.demo.ui.state.SafeProviderStatusException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatFailureMapperTest {
    @Test
    fun `safe retry after metadata is preserved without provider response data`() {
        val failure = ChatFailureMapper.map(
            SafeProviderStatusException(SafeProviderStatus(statusCode = 429, retryAfterSeconds = 45)),
        )

        assertEquals(ChatFailureKind.OVERLOADED, failure.kind)
        assertEquals(ChatRecoveryAction.RETRY, failure.recoveryAction)
        assertEquals(45, failure.retryAfterSeconds)
    }

    @Test
    fun `negative retry after metadata is ignored`() {
        val failure = ChatFailureMapper.map(
            SafeProviderStatusException(SafeProviderStatus(statusCode = 429, retryAfterSeconds = -1)),
        )

        assertNull(failure.retryAfterSeconds)
    }

    @Test
    fun `gateway malformed protocol status is invalid response`() {
        val failure = ChatFailureMapper.map(
            SafeProviderStatusException(SafeProviderStatus(statusCode = 502)),
        )

        assertEquals(ChatFailureKind.INVALID_RESPONSE, failure.kind)
    }
}
