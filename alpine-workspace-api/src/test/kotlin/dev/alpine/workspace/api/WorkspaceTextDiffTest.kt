package dev.alpine.workspace.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceTextDiffTest {
    @Test
    fun `unchanged text has no diff rows`() {
        val diff = workspaceTextDiff("same\ntext", "same\ntext")

        assertFalse(diff.changed)
        assertTrue(diff.lines.isEmpty())
    }

    @Test
    fun `changed middle reports bounded removed and added rows`() {
        val diff = workspaceTextDiff(
            savedText = "first\nold\nlast",
            editedText = "first\nnew\nlast",
        )

        assertTrue(diff.changed)
        assertEquals(1, diff.removedLineCount)
        assertEquals(1, diff.addedLineCount)
        assertTrue(diff.lines.any { it.kind == WorkspaceDiffLineKind.REMOVED && it.text == "old" })
        assertTrue(diff.lines.any { it.kind == WorkspaceDiffLineKind.ADDED && it.text == "new" })
    }

    @Test
    fun `large changed block stays within explicit display bound`() {
        val diff = workspaceTextDiff(
            savedText = (1..100).joinToString("\n") { "old-$it" },
            editedText = (1..100).joinToString("\n") { "new-$it" },
            maxLines = 12,
        )

        assertEquals(100, diff.removedLineCount)
        assertEquals(100, diff.addedLineCount)
        assertEquals(12, diff.lines.size)
        assertEquals(WorkspaceDiffLineKind.TRUNCATED, diff.lines.last().kind)
    }
}
