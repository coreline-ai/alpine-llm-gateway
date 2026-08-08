package dev.alpine.llm.runtimeprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TtyProbeMarkersTest {
    @Test
    fun fixedMarkerOutcomeDistinguishesMatchedUnexpectedAndMissingWithoutOutputExposure() {
        assertEquals(
            TtyProbeMarkers.MATCHED,
            TtyProbeMarkers.markerOutcome(
                "terminal_after_repeat_first=24x80\n",
                "terminal_after_repeat_first",
                "24x80",
            ),
        )
        assertEquals(
            TtyProbeMarkers.UNEXPECTED,
            TtyProbeMarkers.markerOutcome(
                "terminal_after_repeat_first=unexpected\n",
                "terminal_after_repeat_first",
                "24x80",
            ),
        )
        assertEquals(
            TtyProbeMarkers.MISSING,
            TtyProbeMarkers.markerOutcome("", "terminal_after_repeat_first", "24x80"),
        )
    }

    @Test
    fun helperMarkerOutcomeUsesOnlyClosedStateBeforeItsFixedMarker() {
        assertEquals(
            TtyProbeMarkers.MATCHED,
            TtyProbeMarkers.helperOutcomeBeforeMarker(
                "tty_winsize_state=alternate\nterminal_after_repeat_first=helper\n",
                "terminal_after_repeat_first",
                "alternate",
            ),
        )
        assertEquals(
            TtyProbeMarkers.UNEXPECTED,
            TtyProbeMarkers.helperOutcomeBeforeMarker(
                "tty_winsize_state=initial\nterminal_after_repeat_first=helper\n",
                "terminal_after_repeat_first",
                "alternate",
            ),
        )
        assertEquals(
            TtyProbeMarkers.MISSING,
            TtyProbeMarkers.helperOutcomeBeforeMarker(
                "tty_winsize_state=alternate\n",
                "terminal_after_repeat_first",
                "alternate",
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            TtyProbeMarkers.helperOutcomeBeforeMarker(
                "",
                "terminal_after_user_value",
                "alternate",
            )
        }
    }

    @Test
    fun winchCountUsesOnlyTheHighestFixedCounterAndRejectsArbitraryTagInput() {
        assertEquals(
            3,
            TtyProbeMarkers.receivedWinchCount(
                "terminal_winch_received_1\nterminal_winch_received_3\n",
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            TtyProbeMarkers.markerOutcome("", "terminal_after_user_value", "24x80")
        }
    }

    @Test
    fun relayModeCountContractDistinguishesForwardedAndWithheldProbes() {
        assertTrue(TtyProbeMarkers.signalCountMatchesRelayMode(TtyProbeMarkers.WINCH_FORWARDED, 1))
        assertTrue(TtyProbeMarkers.signalCountMatchesRelayMode(TtyProbeMarkers.WINCH_TRACEE_FOREGROUND, 1))
        assertTrue(TtyProbeMarkers.signalCountMatchesRelayMode(TtyProbeMarkers.WINCH_WITHHELD, 0))
        assertTrue(
            TtyProbeMarkers.signalCountMatchesRelayMode(
                TtyProbeMarkers.WINCH_WITHHELD_AND_BLOCKED,
                0,
            ),
        )
        assertTrue(
            TtyProbeMarkers.signalCountMatchesRelayMode(
                TtyProbeMarkers.VIRTUAL_WINSIZE_NO_WINCH,
                0,
            ),
        )
        assertFalse(TtyProbeMarkers.signalCountMatchesRelayMode(TtyProbeMarkers.WINCH_FORWARDED, 0))
        assertFalse(TtyProbeMarkers.signalCountMatchesRelayMode(TtyProbeMarkers.WINCH_TRACEE_FOREGROUND, 0))
        assertFalse(TtyProbeMarkers.signalCountMatchesRelayMode(TtyProbeMarkers.WINCH_WITHHELD, 1))
        assertFalse(
            TtyProbeMarkers.signalCountMatchesRelayMode(
                TtyProbeMarkers.WINCH_WITHHELD_AND_BLOCKED,
                1,
            ),
        )
        assertFalse(TtyProbeMarkers.signalCountMatchesRelayMode("unknown", 0))
        assertTrue(TtyProbeMarkers.withholdsGuestWinch(TtyProbeMarkers.WINCH_WITHHELD))
        assertTrue(TtyProbeMarkers.withholdsGuestWinch(TtyProbeMarkers.WINCH_WITHHELD_AND_BLOCKED))
        assertFalse(TtyProbeMarkers.withholdsGuestWinch(TtyProbeMarkers.WINCH_FORWARDED))
        assertTrue(TtyProbeMarkers.expectsGuestWinch(TtyProbeMarkers.WINCH_FORWARDED))
        assertTrue(TtyProbeMarkers.expectsGuestWinch(TtyProbeMarkers.WINCH_TRACEE_FOREGROUND))
        assertFalse(TtyProbeMarkers.expectsGuestWinch(TtyProbeMarkers.WINCH_WITHHELD_AND_BLOCKED))
        assertTrue(TtyProbeMarkers.isVirtualWinsizeMode(TtyProbeMarkers.VIRTUAL_WINSIZE_NO_WINCH))
        assertFalse(TtyProbeMarkers.isVirtualWinsizeMode(TtyProbeMarkers.WINCH_WITHHELD))
    }
}
