package dev.alpine.runtime.android.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestTerminalResizeProtocolTest {
    @Test
    fun `command is numeric row column protocol`() {
        assertEquals(
            "7 40 120\n",
            GuestTerminalResizeProtocol.command(7, columns = 120, rows = 40)
                .toString(Charsets.US_ASCII),
        )
    }

    @Test
    fun `ack must match sequence and dimensions exactly`() {
        assertTrue(GuestTerminalResizeProtocol.matchesAck("7 40 120\n", 7, 120, 40))
        assertFalse(GuestTerminalResizeProtocol.matchesAck("6 40 120", 7, 120, 40))
        assertFalse(GuestTerminalResizeProtocol.matchesAck("7 28 96", 7, 120, 40))
        assertFalse(GuestTerminalResizeProtocol.matchesAck(null, 7, 120, 40))
    }
}
