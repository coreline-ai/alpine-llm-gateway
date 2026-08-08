package dev.alpine.llm.runtimeprobe

/**
 * Probe-private fixed marker parsing. It deliberately returns only enum-like
 * diagnostic states and signal counts; it never exposes terminal output.
 */
internal object TtyProbeMarkers {
    const val MATCHED = "MATCHED"
    const val UNEXPECTED = "UNEXPECTED"
    const val MISSING = "MISSING"
    const val WINCH_FORWARDED = "FORWARDED"
    const val WINCH_WITHHELD = "WITHHELD"
    /** PRoot stays background; kernel delivers SIGWINCH to the guest foreground group. */
    const val WINCH_TRACEE_FOREGROUND = "TRACEE_FOREGROUND"
    /**
     * Probe-only control: PRoot withholds its relay and the initial guest
     * tracee inherits a blocked SIGWINCH mask.  It proves whether a physical
     * foreground-group delivery, rather than PRoot reinjection, stops input.
     */
    const val WINCH_WITHHELD_AND_BLOCKED = "WITHHELD_AND_BLOCKED"
    /**
     * Relay22 updates only guest TIOCGWINSZ through PRoot's private control
     * pipe. No host master TIOCSWINSZ and no guest SIGWINCH are sent.
     */
    const val VIRTUAL_WINSIZE_NO_WINCH = "VIRTUAL_WINSIZE_NO_WINCH"

    private val safeTag = Regex("terminal_after_(repeat_first|repeat_second|storm)")
    private val safeExpected = Regex("[1-9][0-9]{0,3}x[1-9][0-9]{0,3}")
    private val safeHelperState = Regex("dynamic|alternate|initial|unexpected|unavailable")
    private val helperState = Regex("tty_winsize_state=(dynamic|alternate|initial|unexpected|unavailable)")
    private val winchMarker = Regex("terminal_winch_received_([1-9][0-9]{0,2})")

    fun markerOutcome(output: String, tag: String, expected: String): String {
        require(safeTag.matches(tag))
        require(safeExpected.matches(expected))
        return when {
            output.contains("$tag=$expected") -> MATCHED
            output.contains("$tag=unexpected") -> UNEXPECTED
            else -> MISSING
        }
    }

    /**
     * Classifies only the fixed helper state emitted immediately before a
     * fixed Probe marker. The caller never receives terminal output.
     */
    fun helperOutcomeBeforeMarker(output: String, tag: String, expectedState: String): String {
        require(safeTag.matches(tag))
        require(safeHelperState.matches(expectedState))
        val markerIndex = output.indexOf("$tag=helper")
        if (markerIndex < 0) return MISSING
        val observed = helperState.findAll(output.substring(0, markerIndex))
            .lastOrNull()
            ?.groupValues
            ?.getOrNull(1)
            ?: return MISSING
        return if (observed == expectedState) MATCHED else UNEXPECTED
    }

    fun receivedWinchCount(output: String): Int = winchMarker
        .findAll(output)
        .mapNotNull { match -> match.groupValues[1].toIntOrNull() }
        .maxOrNull()
        ?: 0

    /** Fixed Probe modes only: a no-delivery experiment must observe no guest trap. */
    fun signalCountMatchesRelayMode(mode: String, count: Int): Boolean = when (mode) {
        WINCH_FORWARDED -> count >= 1
        WINCH_TRACEE_FOREGROUND -> count >= 1
        WINCH_WITHHELD,
        WINCH_WITHHELD_AND_BLOCKED,
        VIRTUAL_WINSIZE_NO_WINCH,
        -> count == 0
        else -> false
    }

    fun withholdsGuestWinch(mode: String): Boolean = mode == WINCH_WITHHELD ||
        mode == WINCH_WITHHELD_AND_BLOCKED || mode == VIRTUAL_WINSIZE_NO_WINCH

    fun expectsGuestWinch(mode: String): Boolean = mode == WINCH_FORWARDED ||
        mode == WINCH_TRACEE_FOREGROUND

    fun isVirtualWinsizeMode(mode: String): Boolean = mode == VIRTUAL_WINSIZE_NO_WINCH
}
