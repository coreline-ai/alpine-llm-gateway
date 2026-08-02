package dev.alpine.runtime.api

import java.util.concurrent.CompletionStage

data class RuntimeEnvironmentContext @JvmOverloads constructor(
    val sessionId: String,
    val workspacePath: String,
    val attributes: Map<String, String> = emptyMap(),
)

fun interface RuntimeEnvironmentContributor {
    fun contribute(context: RuntimeEnvironmentContext): Map<String, String>
}

data class RuntimeCommandRequest @JvmOverloads constructor(
    val executable: String,
    val arguments: List<String> = emptyList(),
    val workingDirectory: String = "/workspace",
    val environment: Map<String, String> = emptyMap(),
    val timeoutMillis: Long = 60_000,
) {
    init {
        require(executable.isNotBlank()) { "executable must not be blank" }
        require('\u0000' !in executable) { "executable must not contain NUL" }
        require(workingDirectory.startsWith('/')) { "workingDirectory must be absolute" }
        require('\u0000' !in workingDirectory) { "workingDirectory must not contain NUL" }
        require(arguments.none { '\u0000' in it }) { "arguments must not contain NUL" }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
    }
}

data class RuntimeCommandResult @JvmOverloads constructor(
    val exitCode: Int,
    val standardOutput: ByteArray = byteArrayOf(),
    val standardError: ByteArray = byteArrayOf(),
    val durationMillis: Long = 0,
    val timedOut: Boolean = false,
)

data class RuntimeTerminalRequest @JvmOverloads constructor(
    val shell: String = "/bin/sh",
    val columns: Int = 80,
    val rows: Int = 24,
    val workingDirectory: String = "/workspace",
    val environment: Map<String, String> = emptyMap(),
) {
    init {
        require(shell.startsWith('/')) { "shell must be absolute" }
        require('\u0000' !in shell) { "shell must not contain NUL" }
        require(Regex("/[A-Za-z0-9_./+-]+").matches(shell)) { "shell path contains unsupported characters" }
        require(columns > 0 && rows > 0) { "terminal dimensions must be positive" }
        require(columns <= 1_000 && rows <= 1_000) { "terminal dimensions are too large" }
        require(workingDirectory.startsWith('/')) { "workingDirectory must be absolute" }
        require('\u0000' !in workingDirectory) { "workingDirectory must not contain NUL" }
    }
}

enum class RuntimeTerminalSignal {
    INTERRUPT,
    TERMINATE,
    KILL,
    END_OF_FILE,
}

enum class RuntimeTerminalResizeSupport {
    /** The requested size is applied while opening the PTY; later resize calls are rejected. */
    INITIAL_SIZE_ONLY,

    /** The guest observes resize calls and receives the new terminal dimensions. */
    DYNAMIC,
}

fun interface RuntimeTerminalOutputListener {
    fun onOutput(bytes: ByteArray)
}

interface RuntimeTerminalSession : AutoCloseable {
    val id: String
    val isOpen: Boolean
    val resizeSupport: RuntimeTerminalResizeSupport
        get() = RuntimeTerminalResizeSupport.INITIAL_SIZE_ONLY

    fun addOutputListener(listener: RuntimeTerminalOutputListener): RuntimeSubscription
    fun write(bytes: ByteArray): CompletionStage<Void>
    fun resize(columns: Int, rows: Int): CompletionStage<Void>
    fun signal(signal: RuntimeTerminalSignal): CompletionStage<Void>
    fun closeAsync(): CompletionStage<Void>

    override fun close() {
        closeAsync()
    }
}
