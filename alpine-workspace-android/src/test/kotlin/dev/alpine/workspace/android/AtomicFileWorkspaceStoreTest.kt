package dev.alpine.workspace.android

import dev.alpine.workspace.api.WorkspaceEntryType
import dev.alpine.workspace.api.WorkspaceErrorCode
import dev.alpine.workspace.api.WorkspaceLimits
import dev.alpine.workspace.api.WorkspaceOperationException
import dev.alpine.workspace.api.WorkspacePath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class AtomicFileWorkspaceStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `write is atomic reopenable and bounded`() {
        val root = temporaryFolder.newFolder("workspace")
        val store = AtomicFileWorkspaceStore(root, WorkspaceLimits(maxReadBytes = 4, maxWriteBytes = 8))
        store.createDirectory(WorkspacePath("docs"))
        val written = store.write(WorkspacePath("docs/a.txt"), byteArrayOf(1, 2, 3, 4))
        assertEquals(WorkspaceEntryType.FILE, written.type)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), store.read(written.path))
        assertTrue(root.walkTopDown().none { it.name.contains(".tmp-") })

        val error = assertThrows(WorkspaceOperationException::class.java) {
            store.write(WorkspacePath("docs/large"), ByteArray(9))
        }
        assertEquals(WorkspaceErrorCode.LIMIT_EXCEEDED, error.errorCode)
    }

    @Test
    fun `symlink escape is rejected`() {
        val root = temporaryFolder.newFolder("symlink-workspace")
        val outside = temporaryFolder.newFolder("outside")
        runCatching { Files.createSymbolicLink(root.toPath().resolve("escape"), outside.toPath()) }
            .getOrElse { return }
        val store = AtomicFileWorkspaceStore(root)
        val error = assertThrows(WorkspaceOperationException::class.java) {
            store.list(WorkspacePath("escape"))
        }
        assertEquals(WorkspaceErrorCode.SYMLINK_NOT_ALLOWED, error.errorCode)
    }

    @Test
    fun `non empty directory cannot be deleted and move does not overwrite by default`() {
        val store = AtomicFileWorkspaceStore(temporaryFolder.newFolder("moves"))
        store.createDirectory(WorkspacePath("a"))
        store.write(WorkspacePath("a/one"), byteArrayOf(1))
        store.write(WorkspacePath("two"), byteArrayOf(2))
        val directoryError = assertThrows(WorkspaceOperationException::class.java) {
            store.delete(WorkspacePath("a"))
        }
        assertEquals(WorkspaceErrorCode.DIRECTORY_NOT_EMPTY, directoryError.errorCode)
        val moveError = assertThrows(WorkspaceOperationException::class.java) {
            store.move(WorkspacePath("a/one"), WorkspacePath("two"))
        }
        assertEquals(WorkspaceErrorCode.ALREADY_EXISTS, moveError.errorCode)
        assertFalse(store.read(WorkspacePath("two")).isEmpty())
    }
}
