package dev.alpine.workspace.api

import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

enum class WorkspaceHostOperation {
    IDLE,
    REFRESHING,
    OPENING,
    SAVING,
    CREATING,
    RENAMING,
    DELETING,
    SEARCHING,
}

/** UI-neutral state for an app-private workspace. It contains no filesystem paths or raw errors. */
data class WorkspaceHostState @JvmOverloads constructor(
    val operation: WorkspaceHostOperation = WorkspaceHostOperation.IDLE,
    val directory: WorkspacePath = WorkspacePath.ROOT,
    val entries: List<WorkspaceEntry> = emptyList(),
    val selectedFile: WorkspacePath? = null,
    val editorText: String = "",
    val searchQuery: String = "",
    val searchResults: List<WorkspaceEntry> = emptyList(),
    val lastErrorCode: WorkspaceErrorCode? = null,
)

fun interface WorkspaceHostStateListener {
    fun onStateChanged(state: WorkspaceHostState)
}

/**
 * Reusable, UI-neutral coordinator for [WorkspaceStore]. All I/O is serialised off the caller
 * thread, editor contents remain bounded by [WorkspaceLimits], and only stable error codes leave
 * the storage boundary.
 */
class WorkspaceHostController(
    private val store: WorkspaceStore,
) : AutoCloseable {
    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<WorkspaceHostStateListener>()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "alpine-workspace-host").apply { isDaemon = true }
    }
    @Volatile private var state = WorkspaceHostState()

    fun currentState(): WorkspaceHostState = state

    fun addStateListener(listener: WorkspaceHostStateListener): AutoCloseable {
        listeners += listener
        runCatching { listener.onStateChanged(state) }
        return AutoCloseable { listeners -= listener }
    }

    fun refresh(): CompletionStage<List<WorkspaceEntry>> = perform(
        WorkspaceHostOperation.REFRESHING,
        block = {
            val directory = currentState().directory
            directory to store.list(directory)
        },
    ) { current, (directory, entries) ->
        current.copy(directory = directory, entries = entries, lastErrorCode = null)
    }.thenApply { it.second }

    fun navigate(directory: WorkspacePath): CompletionStage<List<WorkspaceEntry>> = perform(
        WorkspaceHostOperation.REFRESHING,
        block = {
            val entry = store.stat(directory)
            if (entry.type != WorkspaceEntryType.DIRECTORY) fail(WorkspaceErrorCode.NOT_A_DIRECTORY)
            directory to store.list(directory)
        },
    ) { current, (resolvedDirectory, entries) ->
        current.copy(
            directory = resolvedDirectory,
            entries = entries,
            selectedFile = null,
            editorText = "",
            lastErrorCode = null,
        )
    }.thenApply { it.second }

    fun open(path: WorkspacePath): CompletionStage<String> = perform(
        WorkspaceHostOperation.OPENING,
        block = {
            val entry = store.stat(path)
            if (entry.type != WorkspaceEntryType.FILE) fail(WorkspaceErrorCode.NOT_A_FILE)
            val bytes = store.read(path)
            if (bytes.any { it == 0.toByte() }) fail(WorkspaceErrorCode.NOT_TEXT)
            path to bytes.toString(StandardCharsets.UTF_8)
        },
    ) { current, (file, text) ->
        current.copy(selectedFile = file, editorText = text, lastErrorCode = null)
    }.thenApply { it.second }

    fun saveSelected(text: String): CompletionStage<WorkspaceEntry> {
        val target = currentState().selectedFile ?: return failed(WorkspaceErrorCode.NOT_FOUND)
        return perform(
            WorkspaceHostOperation.SAVING,
            block = {
                val entry = store.write(target, text.toByteArray(StandardCharsets.UTF_8), overwrite = true)
                entry to store.list(target.parent ?: WorkspacePath.ROOT)
            },
        ) { current, (entry, entries) ->
            current.copy(entries = entries, selectedFile = entry.path, editorText = text, lastErrorCode = null)
        }.thenApply { it.first }
    }

    /** Imports already-bounded bytes obtained by a platform-specific picker into an explicit folder. */
    fun importBytes(
        directory: WorkspacePath,
        name: String,
        bytes: ByteArray,
    ): CompletionStage<WorkspaceEntry> = perform(
        WorkspaceHostOperation.CREATING,
        block = {
            val entry = store.write(directory.resolve(name), bytes, overwrite = false)
            entry to store.list(directory)
        },
    ) { current, (entry, entries) ->
        val visibleEntries = if (current.directory == directory) entries else current.entries
        current.copy(entries = visibleEntries, lastErrorCode = null)
    }.thenApply { it.first }

    /** Reads a bounded workspace file for an explicit platform export action. */
    fun readForExport(path: WorkspacePath): CompletionStage<ByteArray> = perform(
        WorkspaceHostOperation.OPENING,
        block = {
            val entry = store.stat(path)
            if (entry.type != WorkspaceEntryType.FILE) fail(WorkspaceErrorCode.NOT_A_FILE)
            store.read(path)
        },
    ) { current, _ -> current.copy(lastErrorCode = null) }

    /** Lets a platform adapter surface a stable picker/output error without leaking raw causes. */
    fun reportExternalFailure(code: WorkspaceErrorCode) = update { it.copy(lastErrorCode = code) }

    fun createTextFile(name: String): CompletionStage<WorkspaceEntry> = performInCurrentDirectory(
        WorkspaceHostOperation.CREATING,
        name,
    ) { path -> store.write(path, byteArrayOf(), overwrite = false) }

    fun createDirectory(name: String): CompletionStage<WorkspaceEntry> = performInCurrentDirectory(
        WorkspaceHostOperation.CREATING,
        name,
        store::createDirectory,
    )

    fun renameSelected(name: String): CompletionStage<WorkspaceEntry> {
        val source = currentState().selectedFile ?: return failed(WorkspaceErrorCode.NOT_FOUND)
        return perform(
            WorkspaceHostOperation.RENAMING,
            block = {
                val target = (source.parent ?: WorkspacePath.ROOT).resolve(name)
                val entry = store.move(source, target, replace = false)
                entry to store.list(target.parent ?: WorkspacePath.ROOT)
            },
        ) { current, (entry, entries) ->
            current.copy(entries = entries, selectedFile = entry.path, lastErrorCode = null)
        }.thenApply { it.first }
    }

    fun deleteSelected(): CompletionStage<Unit> {
        val selected = currentState().selectedFile ?: return failed(WorkspaceErrorCode.NOT_FOUND)
        return perform(
            WorkspaceHostOperation.DELETING,
            block = {
                store.delete(selected)
                store.list(selected.parent ?: WorkspacePath.ROOT)
            },
        ) { current, entries ->
            current.copy(entries = entries, selectedFile = null, editorText = "", lastErrorCode = null)
        }.thenApply { Unit }
    }

    fun search(query: String): CompletionStage<List<WorkspaceEntry>> {
        val normalized = query.trim().take(MAX_SEARCH_QUERY_LENGTH)
        if (normalized.isEmpty()) {
            update { it.copy(searchQuery = "", searchResults = emptyList(), lastErrorCode = null) }
            return CompletableFuture.completedFuture(emptyList())
        }
        return perform(
            WorkspaceHostOperation.SEARCHING,
            block = { searchTree(normalized) },
        ) { current, results ->
            current.copy(searchQuery = normalized, searchResults = results, lastErrorCode = null)
        }
    }

    fun clearError() = update { it.copy(lastErrorCode = null) }

    override fun close() {
        listeners.clear()
        executor.shutdownNow()
    }

    private fun performInCurrentDirectory(
        operation: WorkspaceHostOperation,
        name: String,
        action: (WorkspacePath) -> WorkspaceEntry,
    ): CompletionStage<WorkspaceEntry> = perform(
        operation,
        block = {
            val directory = currentState().directory
            val entry = action(directory.resolve(name))
            entry to store.list(directory)
        },
    ) { current, (entry, entries) -> current.copy(entries = entries, lastErrorCode = null) }
        .thenApply { it.first }

    private fun searchTree(query: String): List<WorkspaceEntry> {
        val lowerQuery = query.lowercase()
        val results = mutableListOf<WorkspaceEntry>()
        val directories = ArrayDeque<WorkspacePath>().apply { add(WorkspacePath.ROOT) }
        var inspected = 0
        while (directories.isNotEmpty() && inspected < MAX_SEARCH_ENTRIES && results.size < MAX_SEARCH_RESULTS) {
            val directory = directories.removeFirst()
            store.list(directory).forEach { entry ->
                if (inspected++ >= MAX_SEARCH_ENTRIES || results.size >= MAX_SEARCH_RESULTS) return@forEach
                if (entry.type == WorkspaceEntryType.DIRECTORY) directories.add(entry.path)
                if (entry.path.value.lowercase().contains(lowerQuery)) results += entry
            }
        }
        return results.sortedBy { it.path }
    }

    private fun <T> perform(
        operation: WorkspaceHostOperation,
        block: () -> T,
        success: (WorkspaceHostState, T) -> WorkspaceHostState,
    ): CompletionStage<T> {
        val future = CompletableFuture<T>()
        update { it.copy(operation = operation, lastErrorCode = null) }
        executor.execute {
            try {
                val value = block()
                update { success(it, value).copy(operation = WorkspaceHostOperation.IDLE) }
                future.complete(value)
            } catch (error: Throwable) {
                update {
                    it.copy(
                        operation = WorkspaceHostOperation.IDLE,
                        lastErrorCode = errorCode(error),
                    )
                }
                future.completeExceptionally(WorkspaceOperationException(errorCode(error)))
            }
        }
        return future
    }

    private fun <T> failed(code: WorkspaceErrorCode): CompletionStage<T> =
        CompletableFuture<T>().also { it.completeExceptionally(WorkspaceOperationException(code)) }

    private fun update(transform: (WorkspaceHostState) -> WorkspaceHostState) {
        val updated = synchronized(lock) { transform(state).also { state = it } }
        listeners.forEach { listener -> runCatching { listener.onStateChanged(updated) } }
    }

    private fun errorCode(error: Throwable): WorkspaceErrorCode = when (error) {
        is WorkspaceOperationException -> error.errorCode
        is IllegalArgumentException -> WorkspaceErrorCode.INVALID_PATH
        else -> WorkspaceErrorCode.IO_FAILED
    }

    private fun fail(code: WorkspaceErrorCode): Nothing = throw WorkspaceOperationException(code)

    companion object {
        private const val MAX_SEARCH_QUERY_LENGTH = 120
        private const val MAX_SEARCH_ENTRIES = 2_000
        private const val MAX_SEARCH_RESULTS = 200
    }
}
