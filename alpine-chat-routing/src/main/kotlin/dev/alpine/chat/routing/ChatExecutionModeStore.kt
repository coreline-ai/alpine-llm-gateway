package dev.alpine.chat.routing

enum class ChatExecutionScopeKind {
    CONVERSATION,
    WORKSPACE,
}

data class ChatExecutionScope(
    val kind: ChatExecutionScopeKind,
    val id: String,
) {
    init {
        require(Regex("[A-Za-z0-9._:-]{1,160}").matches(id)) { "scope id is invalid" }
    }
}

/** Host-neutral persistence SPI; apps may back this with files, Room, or encrypted storage. */
interface ChatExecutionModeStore {
    suspend fun load(scope: ChatExecutionScope): ChatExecutionMode?
    suspend fun save(scope: ChatExecutionScope, mode: ChatExecutionMode)
    suspend fun delete(scope: ChatExecutionScope)
}

class InMemoryChatExecutionModeStore : ChatExecutionModeStore {
    private val values = mutableMapOf<ChatExecutionScope, ChatExecutionMode>()

    override suspend fun load(scope: ChatExecutionScope): ChatExecutionMode? = synchronized(values) {
        values[scope]
    }

    override suspend fun save(scope: ChatExecutionScope, mode: ChatExecutionMode) {
        synchronized(values) { values[scope] = mode }
    }

    override suspend fun delete(scope: ChatExecutionScope) {
        synchronized(values) { values.remove(scope) }
    }
}
