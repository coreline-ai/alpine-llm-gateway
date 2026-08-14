package dev.alpine.chat.provider.android.data

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.alpine.chat.feature.backend.ChatBackendConnection
import dev.alpine.chat.feature.backend.ChatBackendConnectionState
import dev.alpine.chat.feature.backend.ChatBackendDescriptor
import dev.alpine.chat.feature.backend.ChatBackendSession
import dev.alpine.chat.feature.backend.ChatBackendStreamResult
import dev.alpine.chat.feature.data.ConversationRepository
import dev.alpine.chat.feature.data.ConversationStore
import dev.alpine.chat.feature.ui.ChatViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationRestoreInstrumentedTest {
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context
        get() = instrumentation.targetContext
    private val store: ConversationStore
        get() = ConversationStore(context)

    @Before
    fun clearBefore() = store.clear()

    @After
    fun clearAfter() = store.clear()

    @Test
    fun encryptedRestartPreservesUnavailableModelWithoutSilentFallback() = runBlocking {
        val originalRepository = ConversationRepository(
            storage = store,
            clock = { 100L },
            idFactory = { "encrypted-model-restore" },
        )
        val original = originalRepository.initialSnapshot(
            selectedProfileId = "profile-a",
            selectedModel = "model-disabled",
        )
        originalRepository.requestPersistence(1)
        originalRepository.persist(original, 1)

        val plaintext = "model-disabled".encodeToByteArray()
        assertFalse(
            store.storageDirectoryForTests().listFiles().orEmpty().any { file ->
                file.readBytes().containsSubsequence(plaintext)
            },
        )

        val restoredRepository = ConversationRepository(storage = store)
        val restored = restoredRepository.load()
        assertEquals("model-disabled", restored.activeConversation.selectedModel)

        val descriptor = ChatBackendDescriptor(
            profileId = "profile-a",
            label = "Provider A",
            model = "model-enabled",
            modelOptions = listOf("model-enabled"),
        )
        val session = object : ChatBackendSession {
            override val descriptor = descriptor

            override suspend fun stream(requestJson: String) = ChatBackendStreamResult()
        }
        val viewModelStore = ViewModelStore()
        val viewModel = ChatViewModel(restoredRepository)
        viewModelStore.put("chat", viewModel)
        try {
            waitUntil { !viewModel.state.value.isLoadingConversations }
            instrumentation.runOnMainSync {
                viewModel.updateConnections(
                    listOf(
                        ChatBackendConnection(
                            descriptor = descriptor,
                            state = ChatBackendConnectionState.AVAILABLE,
                            session = session,
                        ),
                    ),
                )
            }

            assertEquals("model-disabled", viewModel.state.value.selectedModel)
            assertEquals("model-disabled", viewModel.state.value.unavailableModel)
        } finally {
            instrumentation.runOnMainSync(viewModelStore::clear)
        }
    }

    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            instrumentation.waitForIdleSync()
            SystemClock.sleep(25L)
        }
        check(predicate()) { "Timed out while waiting for encrypted conversation restore" }
    }
}

private fun ByteArray.containsSubsequence(candidate: ByteArray): Boolean {
    if (candidate.isEmpty()) return true
    if (candidate.size > size) return false
    return indices.take(size - candidate.size + 1).any { start ->
        candidate.indices.all { offset -> this[start + offset] == candidate[offset] }
    }
}
