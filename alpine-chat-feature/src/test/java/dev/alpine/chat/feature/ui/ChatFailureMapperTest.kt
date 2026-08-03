package dev.alpine.chat.feature.ui

import dev.alpine.chat.feature.backend.ChatBackendException
import dev.alpine.chat.feature.backend.ChatBackendFailureCode
import dev.alpine.chat.feature.ui.state.ChatFailureKind
import dev.alpine.chat.feature.ui.state.ChatFailureMapper
import dev.alpine.chat.feature.ui.state.ChatRecoveryAction
import dev.alpine.chat.feature.ui.state.SafeProviderStatus
import dev.alpine.chat.feature.ui.state.SafeProviderStatusException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatFailureMapperTest {
    @Test
    fun `backend failure codes map without provider exception types`() {
        val expected = mapOf(
            ChatBackendFailureCode.REAUTHENTICATION_REQUIRED to
                ChatFailureKind.REAUTHENTICATION_REQUIRED,
            ChatBackendFailureCode.TIMEOUT to ChatFailureKind.TIMEOUT,
            ChatBackendFailureCode.CIRCUIT_OPEN to ChatFailureKind.CIRCUIT_OPEN,
            ChatBackendFailureCode.INVALID_RESPONSE to ChatFailureKind.INVALID_RESPONSE,
            ChatBackendFailureCode.NETWORK to ChatFailureKind.NETWORK,
            ChatBackendFailureCode.UNKNOWN to ChatFailureKind.UNKNOWN,
        )

        expected.forEach { (code, kind) ->
            assertEquals(kind, ChatFailureMapper.map(ChatBackendException(code)).kind)
        }
    }

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

    @Test
    fun `runtime failures expose closed recovery actions`() {
        val install = ChatFailureMapper.map(
            ChatBackendException(ChatBackendFailureCode.RUNTIME_NOT_INSTALLED),
        )
        val repair = ChatFailureMapper.map(
            ChatBackendException(ChatBackendFailureCode.RUNTIME_REPAIR_REQUIRED),
        )
        val restart = ChatFailureMapper.map(
            ChatBackendException(ChatBackendFailureCode.RUNTIME_START_FAILED),
        )

        assertEquals(ChatRecoveryAction.INSTALL_RUNTIME, install.recoveryAction)
        assertEquals(ChatRecoveryAction.REPAIR_RUNTIME, repair.recoveryAction)
        assertEquals(ChatRecoveryAction.RESTART_RUNTIME, restart.recoveryAction)
    }
}
