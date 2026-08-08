package dev.alpine.workspace.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CompletionException

class WorkspaceHostControllerTest {
    @Test
    fun `editor workflow is serial bounded and exposes no raw filesystem data`() {
        val controller = WorkspaceHostController(MemoryWorkspaceStore())
        try {
            controller.refresh().toCompletableFuture().join()
            controller.createTextFile("note.txt").toCompletableFuture().join()
            controller.open(WorkspacePath("note.txt")).toCompletableFuture().join()
            controller.saveSelected("Alpine workspace").toCompletableFuture().join()
            controller.search("NOTE").toCompletableFuture().join()

            assertEquals("Alpine workspace", controller.currentState().editorText)
            assertEquals(listOf(WorkspacePath("note.txt")), controller.currentState().searchResults.map { it.path })

            controller.renameSelected("renamed.txt").toCompletableFuture().join()
            assertEquals(WorkspacePath("renamed.txt"), controller.currentState().selectedFile)
            controller.deleteSelected().toCompletableFuture().join()
            assertTrue(controller.currentState().entries.isEmpty())
        } finally {
            controller.close()
        }
    }

    @Test
    fun `binary editor content is rejected with a stable error`() {
        val store = MemoryWorkspaceStore().apply {
            write(WorkspacePath("image.bin"), byteArrayOf(1, 0, 2))
        }
        val controller = WorkspaceHostController(store)
        try {
            val error = runCatching {
                controller.open(WorkspacePath("image.bin")).toCompletableFuture().join()
            }.exceptionOrNull()
            assertTrue(error is CompletionException)
            assertEquals(WorkspaceErrorCode.NOT_TEXT, controller.currentState().lastErrorCode)
        } finally {
            controller.close()
        }
    }

    private class MemoryWorkspaceStore : WorkspaceStore {
        override val limits = WorkspaceLimits()
        private val files = linkedMapOf<WorkspacePath, ByteArray>()
        private val directories = linkedSetOf(WorkspacePath.ROOT)

        override fun stat(path: WorkspacePath): WorkspaceEntry = when {
            path in directories -> WorkspaceEntry(path, WorkspaceEntryType.DIRECTORY, 0, 1)
            path in files -> WorkspaceEntry(path, WorkspaceEntryType.FILE, files.getValue(path).size.toLong(), 1)
            else -> fail(WorkspaceErrorCode.NOT_FOUND)
        }

        override fun list(directory: WorkspacePath): List<WorkspaceEntry> {
            if (directory !in directories) fail(WorkspaceErrorCode.NOT_A_DIRECTORY)
            return (directories + files.keys)
                .filter { it.parent == directory }
                .map(::stat)
                .sortedBy { it.path }
        }

        override fun read(path: WorkspacePath): ByteArray = files[path]?.copyOf() ?: fail(WorkspaceErrorCode.NOT_A_FILE)

        override fun write(path: WorkspacePath, bytes: ByteArray, overwrite: Boolean): WorkspaceEntry {
            if (path.parent !in directories) fail(WorkspaceErrorCode.NOT_A_DIRECTORY)
            if (path in files && !overwrite) fail(WorkspaceErrorCode.ALREADY_EXISTS)
            files[path] = bytes.copyOf()
            return stat(path)
        }

        override fun createDirectory(path: WorkspacePath): WorkspaceEntry {
            if (path.parent !in directories) fail(WorkspaceErrorCode.NOT_A_DIRECTORY)
            if (!directories.add(path)) fail(WorkspaceErrorCode.ALREADY_EXISTS)
            return stat(path)
        }

        override fun move(source: WorkspacePath, target: WorkspacePath, replace: Boolean): WorkspaceEntry {
            val bytes = files.remove(source) ?: fail(WorkspaceErrorCode.NOT_A_FILE)
            if (target in files && !replace) fail(WorkspaceErrorCode.ALREADY_EXISTS)
            files[target] = bytes
            return stat(target)
        }

        override fun delete(path: WorkspacePath) {
            if (files.remove(path) == null) fail(WorkspaceErrorCode.NOT_FOUND)
        }

        private fun fail(code: WorkspaceErrorCode): Nothing = throw WorkspaceOperationException(code)
    }
}
