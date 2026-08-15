package dev.alpine.chat.feature.backend

import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ChatBackendContractTest {
    @Test
    fun `descriptor requires selected model in bounded host catalog`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ChatBackendDescriptor(
                profileId = "profile",
                label = "Provider",
                model = "missing-model",
                modelOptions = listOf("allowed-model"),
            )
        }

        assertEquals("selected model must be present in modelOptions", error.message)
    }

    @Test
    fun `connection rejects descriptor substitution`() {
        val session = object : ChatBackendSession {
            override val descriptor = descriptor("profile-a")
            override suspend fun stream(requestJson: String) =
                ChatBackendStreamResult(events = emptyFlow())
        }

        assertThrows(IllegalArgumentException::class.java) {
            ChatBackendConnection(
                descriptor = descriptor("profile-b"),
                state = ChatBackendConnectionState.AVAILABLE,
                session = session,
            )
        }
    }

    @Test
    fun `contextual session receives stable host identifiers`() = runTest {
        var captured: ChatBackendRequestContext? = null
        val session = object : ContextualChatBackendSession {
            override val descriptor = descriptor("contextual")
            override suspend fun stream(request: ChatBackendRequestContext): ChatBackendStreamResult {
                captured = request
                return ChatBackendStreamResult()
            }
        }

        session.stream(
            ChatBackendRequestContext(
                conversationId = "conversation-1",
                actionId = "action-1",
                requestJson = "{}",
            ),
        )

        assertEquals("conversation-1", captured?.conversationId)
        assertEquals("action-1", captured?.actionId)
    }

    private fun descriptor(id: String) = ChatBackendDescriptor(
        profileId = id,
        label = id,
        model = "model",
    )
}
