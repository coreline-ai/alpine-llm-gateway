package dev.alpine.workspace.android

import android.content.Context
import dev.alpine.workspace.api.WorkspaceEntry
import dev.alpine.workspace.api.WorkspaceEntryType
import dev.alpine.workspace.api.WorkspaceErrorCode
import dev.alpine.workspace.api.WorkspaceLimits
import dev.alpine.workspace.api.WorkspaceOperationException
import dev.alpine.workspace.api.WorkspacePath
import dev.alpine.workspace.api.WorkspaceStore
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.UUID

class AppPrivateWorkspaceStore @JvmOverloads constructor(
    context: Context,
    directoryName: String = "alpine-workspace",
    override val limits: WorkspaceLimits = WorkspaceLimits(),
) : WorkspaceStore {
    private val delegate: WorkspaceStore

    init {
        require(directoryName.isNotBlank() && '/' !in directoryName && '\\' !in directoryName &&
            directoryName != "." && directoryName != "..")
        delegate = AtomicFileWorkspaceStore(File(context.filesDir, directoryName), limits)
    }

    override fun stat(path: WorkspacePath) = delegate.stat(path)
    override fun list(directory: WorkspacePath) = delegate.list(directory)
    override fun read(path: WorkspacePath) = delegate.read(path)
    override fun write(path: WorkspacePath, bytes: ByteArray, overwrite: Boolean) =
        delegate.write(path, bytes, overwrite)
    override fun createDirectory(path: WorkspacePath) = delegate.createDirectory(path)
    override fun move(source: WorkspacePath, target: WorkspacePath, replace: Boolean) =
        delegate.move(source, target, replace)
    override fun delete(path: WorkspacePath) = delegate.delete(path)
}

internal class AtomicFileWorkspaceStore(
    rootDirectory: File,
    override val limits: WorkspaceLimits = WorkspaceLimits(),
) : WorkspaceStore {
    private val root: File = rootDirectory.apply { mkdirs() }.canonicalFile

    init {
        require(root.isDirectory) { "workspace root is unavailable" }
        require(!Files.isSymbolicLink(root.toPath())) { "workspace root must not be a symlink" }
    }

    override fun stat(path: WorkspacePath): WorkspaceEntry = protect {
        val file = resolveExisting(path)
        entry(path, file)
    }

    override fun list(directory: WorkspacePath): List<WorkspaceEntry> = protect {
        val folder = resolveExisting(directory)
        if (!folder.isDirectory) fail(WorkspaceErrorCode.NOT_A_DIRECTORY)
        val children = folder.listFiles() ?: fail(WorkspaceErrorCode.IO_FAILED)
        if (children.size > limits.maxListEntries) fail(WorkspaceErrorCode.LIMIT_EXCEEDED)
        children.map { child ->
            if (Files.isSymbolicLink(child.toPath())) fail(WorkspaceErrorCode.SYMLINK_NOT_ALLOWED)
            entry(directory.resolve(child.name), child)
        }.sortedBy { it.path }
    }

    override fun read(path: WorkspacePath): ByteArray = protect {
        val file = resolveExisting(path)
        if (!file.isFile) fail(WorkspaceErrorCode.NOT_A_FILE)
        if (file.length() > limits.maxReadBytes || file.length() > Int.MAX_VALUE) {
            fail(WorkspaceErrorCode.LIMIT_EXCEEDED)
        }
        file.inputStream().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > limits.maxReadBytes) fail(WorkspaceErrorCode.LIMIT_EXCEEDED)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    override fun write(path: WorkspacePath, bytes: ByteArray, overwrite: Boolean): WorkspaceEntry = protect {
        if (path.isRoot) fail(WorkspaceErrorCode.INVALID_PATH)
        if (bytes.size.toLong() > limits.maxWriteBytes) fail(WorkspaceErrorCode.LIMIT_EXCEEDED)
        val target = resolveForCreate(path)
        if (target.exists() && !overwrite) fail(WorkspaceErrorCode.ALREADY_EXISTS)
        if (target.exists() && !target.isFile) fail(WorkspaceErrorCode.NOT_A_FILE)
        val parent = target.parentFile ?: fail(WorkspaceErrorCode.INVALID_PATH)
        if (!parent.isDirectory) fail(WorkspaceErrorCode.NOT_A_DIRECTORY)
        val temporary = File(parent, ".${target.name}.tmp-${UUID.randomUUID()}")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            moveAtomically(temporary, target, overwrite)
        } finally {
            temporary.delete()
        }
        entry(path, target)
    }

    override fun createDirectory(path: WorkspacePath): WorkspaceEntry = protect {
        if (path.isRoot) return@protect entry(path, root)
        val directory = resolveForCreate(path)
        if (directory.exists()) fail(WorkspaceErrorCode.ALREADY_EXISTS)
        if (!directory.mkdir()) fail(WorkspaceErrorCode.IO_FAILED)
        entry(path, directory)
    }

    override fun move(source: WorkspacePath, target: WorkspacePath, replace: Boolean): WorkspaceEntry = protect {
        if (source.isRoot || target.isRoot) fail(WorkspaceErrorCode.INVALID_PATH)
        val sourceFile = resolveExisting(source)
        val targetFile = resolveForCreate(target)
        if (targetFile.exists() && !replace) fail(WorkspaceErrorCode.ALREADY_EXISTS)
        moveAtomically(sourceFile, targetFile, replace)
        entry(target, targetFile)
    }

    override fun delete(path: WorkspacePath): Unit = protect {
        if (path.isRoot) fail(WorkspaceErrorCode.INVALID_PATH)
        val file = resolveExisting(path)
        if (file.isDirectory && file.list()?.isNotEmpty() == true) {
            fail(WorkspaceErrorCode.DIRECTORY_NOT_EMPTY)
        }
        if (!file.delete()) fail(WorkspaceErrorCode.IO_FAILED)
    }

    private fun resolveExisting(path: WorkspacePath): File {
        val file = resolveInsideRoot(path)
        if (!file.exists()) fail(WorkspaceErrorCode.NOT_FOUND)
        rejectSymlinks(path, includeLeaf = true)
        return file
    }

    private fun resolveForCreate(path: WorkspacePath): File {
        val file = resolveInsideRoot(path)
        rejectSymlinks(path, includeLeaf = file.exists())
        return file
    }

    private fun resolveInsideRoot(path: WorkspacePath): File {
        val candidate = if (path.isRoot) root else File(root, path.value)
        val normalized = candidate.toPath().normalize()
        if (!normalized.startsWith(root.toPath())) fail(WorkspaceErrorCode.INVALID_PATH)
        return normalized.toFile()
    }

    private fun rejectSymlinks(path: WorkspacePath, includeLeaf: Boolean) {
        var current = root
        val segments = if (path.isRoot) emptyList() else path.value.split('/')
        segments.forEachIndexed { index, segment ->
            current = File(current, segment)
            val isLeaf = index == segments.lastIndex
            if ((!isLeaf || includeLeaf) && Files.isSymbolicLink(current.toPath())) {
                fail(WorkspaceErrorCode.SYMLINK_NOT_ALLOWED)
            }
            if (!isLeaf && !current.isDirectory) {
                if (!current.exists()) fail(WorkspaceErrorCode.NOT_FOUND)
                fail(WorkspaceErrorCode.NOT_A_DIRECTORY)
            }
        }
    }

    private fun entry(path: WorkspacePath, file: File): WorkspaceEntry {
        val attributes = Files.readAttributes(
            file.toPath(),
            java.nio.file.attribute.BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        if (attributes.isSymbolicLink) fail(WorkspaceErrorCode.SYMLINK_NOT_ALLOWED)
        val type = when {
            attributes.isRegularFile -> WorkspaceEntryType.FILE
            attributes.isDirectory -> WorkspaceEntryType.DIRECTORY
            else -> fail(WorkspaceErrorCode.INVALID_PATH)
        }
        return WorkspaceEntry(path, type, if (type == WorkspaceEntryType.FILE) attributes.size() else 0L,
            attributes.lastModifiedTime().toMillis())
    }

    private fun moveAtomically(source: File, target: File, replace: Boolean) {
        val options = mutableListOf<java.nio.file.CopyOption>(StandardCopyOption.ATOMIC_MOVE)
        if (replace) options += StandardCopyOption.REPLACE_EXISTING
        try {
            Files.move(source.toPath(), target.toPath(), *options.toTypedArray())
        } catch (_: AtomicMoveNotSupportedException) {
            val fallback = if (replace) arrayOf(StandardCopyOption.REPLACE_EXISTING) else emptyArray()
            Files.move(source.toPath(), target.toPath(), *fallback)
        }
    }

    private inline fun <T> protect(block: () -> T): T = try {
        block()
    } catch (error: WorkspaceOperationException) {
        throw error
    } catch (_: SecurityException) {
        fail(WorkspaceErrorCode.IO_FAILED)
    } catch (_: java.io.IOException) {
        fail(WorkspaceErrorCode.IO_FAILED)
    }

    private fun fail(code: WorkspaceErrorCode): Nothing = throw WorkspaceOperationException(code)
}
