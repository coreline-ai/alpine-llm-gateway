package dev.alpine.chat.feature.backend

import kotlinx.coroutines.flow.emptyFlow
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

    private fun descriptor(id: String) = ChatBackendDescriptor(
        profileId = id,
        label = id,
        model = "model",
    )
}
