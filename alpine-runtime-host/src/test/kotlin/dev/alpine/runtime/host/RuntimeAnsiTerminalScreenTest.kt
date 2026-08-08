package dev.alpine.runtime.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeAnsiTerminalScreenTest {
    @Test
    fun `sgr colours and OSC are rendered safely without escape text`() {
        val screen = RuntimeAnsiTerminalScreenRenderer.render(
            "\u001B]0;private title\u0007\u001B[31mred\u001B[0m plain",
            requestedColumns = 80,
            requestedRows = 24,
        )

        assertEquals("red plain", screen.plainText)
        assertEquals(RuntimeTerminalColor.RED, screen.lines.single().spans.first().style.foreground)
        assertEquals(RuntimeTerminalColor.DEFAULT, screen.lines.single().spans.last().style.foreground)
        assertFalse(screen.plainText.contains("private title"))
        assertFalse(screen.plainText.contains('\u001B'))
    }

    @Test
    fun `cursor movement and erase update the visible screen`() {
        val overwritten = RuntimeAnsiTerminalScreenRenderer.render(
            "hello\u001B[2D!!",
            requestedColumns = 20,
            requestedRows = 4,
        )
        val cleared = RuntimeAnsiTerminalScreenRenderer.render(
            "stale\u001B[2J\u001B[Hfresh",
            requestedColumns = 20,
            requestedRows = 4,
        )

        assertEquals("hel!!", overwritten.plainText)
        assertEquals("fresh", cleared.plainText)
    }

    @Test
    fun `alternate screen returns to primary and exposes active alternate state`() {
        val restored = RuntimeAnsiTerminalScreenRenderer.render(
            "primary\u001B[?1049halt\u001B[?1049l",
            requestedColumns = 20,
            requestedRows = 4,
        )
        val active = RuntimeAnsiTerminalScreenRenderer.render(
            "primary\u001B[?1049halt",
            requestedColumns = 20,
            requestedRows = 4,
        )

        assertEquals("primary", restored.plainText)
        assertFalse(restored.usesAlternateScreen)
        assertEquals("alt", active.plainText)
        assertTrue(active.usesAlternateScreen)
    }

    @Test
    fun `screen renderer bounds dimensions and keeps Korean wide characters intact`() {
        val screen = RuntimeAnsiTerminalScreenRenderer.render(
            "한글\u001B[2DOK",
            requestedColumns = 10_000,
            requestedRows = 10_000,
        )

        assertEquals(240, screen.columns)
        assertEquals(120, screen.rows)
        assertEquals("한OK", screen.plainText)
    }
}
