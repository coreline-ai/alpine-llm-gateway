package dev.alpine.workspace.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WorkspacePathTest {
    @Test
    fun `safe relative paths preserve parent and child`() {
        val path = WorkspacePath("src/main.kt")
        assertEquals("main.kt", path.name)
        assertEquals(WorkspacePath("src"), path.parent)
        assertEquals(path, WorkspacePath("src").resolve("main.kt"))
    }

    @Test
    fun `absolute traversal empty segment backslash and nul are rejected`() {
        listOf("/root", "../secret", "a/../b", "a//b", "a\\b", "a\u0000b", "a/").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { WorkspacePath(value) }
        }
    }
}
