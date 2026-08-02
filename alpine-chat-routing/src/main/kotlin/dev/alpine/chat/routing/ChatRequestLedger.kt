package dev.alpine.chat.routing

/** Claim remains terminal after completion so an accidental UI replay cannot double bill. */
interface ChatRequestLedger {
    fun claim(requestId: String): Boolean
    fun complete(requestId: String)
}

class InMemoryChatRequestLedger @JvmOverloads constructor(
    private val maxTerminalEntries: Int = 4_096,
) : ChatRequestLedger {
    private val active = mutableSetOf<String>()
    private val terminal = linkedSetOf<String>()

    init {
        require(maxTerminalEntries > 0) { "maxTerminalEntries must be positive" }
    }

    @Synchronized
    override fun claim(requestId: String): Boolean {
        if (requestId in active || requestId in terminal) return false
        active += requestId
        return true
    }

    @Synchronized
    override fun complete(requestId: String) {
        if (!active.remove(requestId)) return
        terminal += requestId
        while (terminal.size > maxTerminalEntries) {
            terminal.remove(terminal.first())
        }
    }
}
