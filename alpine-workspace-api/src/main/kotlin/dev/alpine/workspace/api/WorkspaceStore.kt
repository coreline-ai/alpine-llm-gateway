package dev.alpine.workspace.api

enum class WorkspaceEntryType { FILE, DIRECTORY }

data class WorkspaceEntry(
    val path: WorkspacePath,
    val type: WorkspaceEntryType,
    val sizeBytes: Long,
    val modifiedAtEpochMillis: Long,
)

data class WorkspaceLimits @JvmOverloads constructor(
    val maxReadBytes: Long = 2L * 1024L * 1024L,
    val maxWriteBytes: Long = 16L * 1024L * 1024L,
    val maxListEntries: Int = 10_000,
) {
    init {
        require(maxReadBytes > 0 && maxWriteBytes > 0 && maxListEntries > 0)
    }
}

enum class WorkspaceErrorCode {
    NOT_FOUND,
    ALREADY_EXISTS,
    INVALID_PATH,
    NOT_A_FILE,
    NOT_A_DIRECTORY,
    DIRECTORY_NOT_EMPTY,
    LIMIT_EXCEEDED,
    SYMLINK_NOT_ALLOWED,
    NOT_TEXT,
    IO_FAILED,
}

class WorkspaceOperationException(val errorCode: WorkspaceErrorCode) : RuntimeException(errorCode.name)

interface WorkspaceStore {
    val limits: WorkspaceLimits
    fun stat(path: WorkspacePath): WorkspaceEntry
    fun list(directory: WorkspacePath = WorkspacePath.ROOT): List<WorkspaceEntry>
    fun read(path: WorkspacePath): ByteArray
    fun write(path: WorkspacePath, bytes: ByteArray, overwrite: Boolean = true): WorkspaceEntry
    fun createDirectory(path: WorkspacePath): WorkspaceEntry
    fun move(source: WorkspacePath, target: WorkspacePath, replace: Boolean = false): WorkspaceEntry
    fun delete(path: WorkspacePath)
}
