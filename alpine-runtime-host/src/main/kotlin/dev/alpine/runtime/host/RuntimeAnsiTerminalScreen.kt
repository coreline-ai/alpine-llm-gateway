package dev.alpine.runtime.host

/**
 * A bounded terminal screen snapshot. The host retains a separate bounded byte scrollback; this
 * model represents the currently visible primary or alternate ANSI screen without exposing raw
 * escape sequences to UI consumers.
 */
data class RuntimeTerminalScreen(
    val columns: Int,
    val rows: Int,
    val lines: List<RuntimeTerminalStyledLine>,
    val usesAlternateScreen: Boolean,
) {
    val plainText: String
        get() = lines.joinToString("\n") { line -> line.spans.joinToString("") { it.text } }
}

data class RuntimeTerminalStyledLine(
    val spans: List<RuntimeTerminalStyledSpan>,
)

data class RuntimeTerminalStyledSpan(
    val text: String,
    val style: RuntimeTerminalTextStyle = RuntimeTerminalTextStyle(),
)

data class RuntimeTerminalTextStyle(
    val foreground: RuntimeTerminalColor = RuntimeTerminalColor.DEFAULT,
    val background: RuntimeTerminalColor = RuntimeTerminalColor.DEFAULT,
    val bold: Boolean = false,
    val underline: Boolean = false,
    val inverse: Boolean = false,
)

/** The standard ANSI 16 colour palette. 256/true-colour sequences are safely consumed, not shown. */
enum class RuntimeTerminalColor {
    DEFAULT,
    BLACK,
    RED,
    GREEN,
    YELLOW,
    BLUE,
    MAGENTA,
    CYAN,
    WHITE,
    BRIGHT_BLACK,
    BRIGHT_RED,
    BRIGHT_GREEN,
    BRIGHT_YELLOW,
    BRIGHT_BLUE,
    BRIGHT_MAGENTA,
    BRIGHT_CYAN,
    BRIGHT_WHITE,
}

/**
 * Minimal VT/ANSI screen renderer for a terminal whose raw scrollback remains bounded by the
 * Runtime Host. It intentionally supports the sequences emitted by common shells, editors and
 * full-screen tools: SGR, cursor movement, erase, insert/delete characters, scrolling, save /
 * restore cursor and the 1047/1049 alternate screen. Unsupported control sequences are consumed
 * instead of being displayed as text.
 */
internal object RuntimeAnsiTerminalScreenRenderer {
    private const val MAX_COLUMNS = 240
    private const val MAX_ROWS = 120

    fun render(raw: String, requestedColumns: Int, requestedRows: Int): RuntimeTerminalScreen {
        val columns = requestedColumns.coerceIn(1, MAX_COLUMNS)
        val rows = requestedRows.coerceIn(1, MAX_ROWS)
        val primary = Screen(columns, rows)
        val alternate = Screen(columns, rows)
        var active = primary
        var alternateActive = false
        var savedPrimaryCursor: Cursor? = null
        var style = RuntimeTerminalTextStyle()

        fun switchAlternate(enabled: Boolean, restoreCursor: Boolean) {
            if (enabled) {
                if (!alternateActive) {
                    if (restoreCursor) savedPrimaryCursor = primary.cursor()
                    alternate.clearAll()
                    alternate.setCursor(0, 0)
                    alternateActive = true
                    active = alternate
                }
            } else if (alternateActive) {
                alternateActive = false
                active = primary
                if (restoreCursor) savedPrimaryCursor?.let(primary::restoreCursor)
            }
        }

        var index = 0
        while (index < raw.length) {
            val current = raw[index]
            when (current) {
                '\u001B' -> {
                    if (index + 1 >= raw.length) break
                    when (raw[index + 1]) {
                        '[' -> {
                            val end = findCsiEnd(raw, index + 2)
                            if (end < 0) {
                                index = raw.length
                            } else {
                                val body = raw.substring(index + 2, end)
                                val final = raw[end]
                                val privateMode = body.startsWith('?')
                                val parameters = parseParameters(if (privateMode) body.drop(1) else body)
                                when (final) {
                                    'A' -> active.moveRelative(dy = -count(parameters))
                                    'B' -> active.moveRelative(dy = count(parameters))
                                    'C' -> active.moveRelative(dx = count(parameters))
                                    'D' -> active.moveRelative(dx = -count(parameters))
                                    'E' -> active.nextLine(count(parameters))
                                    'F' -> active.previousLine(count(parameters))
                                    'G', '`' -> active.setColumn(oneBased(parameters.firstOrNull()))
                                    'H', 'f' -> active.setPosition(
                                        oneBased(parameters.getOrNull(1)),
                                        oneBased(parameters.firstOrNull()),
                                    )
                                    'J' -> active.eraseDisplay(parameters.firstOrNull() ?: 0)
                                    'K' -> active.eraseLine(parameters.firstOrNull() ?: 0)
                                    'L' -> active.insertLines(count(parameters))
                                    'M' -> active.deleteLines(count(parameters))
                                    'P' -> active.deleteCharacters(count(parameters))
                                    '@' -> active.insertCharacters(count(parameters))
                                    'X' -> active.eraseCharacters(count(parameters))
                                    'S' -> active.scrollUp(count(parameters))
                                    'T' -> active.scrollDown(count(parameters))
                                    'm' -> style = applySgr(style, parameters)
                                    's' -> active.saveCursor()
                                    'u' -> active.restoreSavedCursor()
                                    'h' -> if (privateMode) {
                                        if (parameters.any { it == 1047 || it == 1049 || it == 47 }) {
                                            switchAlternate(enabled = true, restoreCursor = parameters.any { it == 1049 })
                                        }
                                    }
                                    'l' -> if (privateMode) {
                                        if (parameters.any { it == 1047 || it == 1049 || it == 47 }) {
                                            switchAlternate(enabled = false, restoreCursor = parameters.any { it == 1049 })
                                        }
                                    }
                                }
                                index = end + 1
                            }
                        }
                        ']' -> {
                            index = skipOsc(raw, index + 2)
                        }
                        '7' -> {
                            active.saveCursor()
                            index += 2
                        }
                        '8' -> {
                            active.restoreSavedCursor()
                            index += 2
                        }
                        'c' -> {
                            active.clearAll()
                            active.setCursor(0, 0)
                            style = RuntimeTerminalTextStyle()
                            index += 2
                        }
                        else -> index += 2
                    }
                }
                '\r' -> {
                    active.carriageReturn()
                    index += 1
                }
                '\n', '\u000B', '\u000C' -> {
                    active.lineFeed()
                    index += 1
                }
                '\b' -> {
                    active.backspace()
                    index += 1
                }
                '\t' -> {
                    active.tab(style)
                    index += 1
                }
                in '\u0000'..'\u001F', '\u007F' -> index += 1
                else -> {
                    val codePoint = raw.codePointAt(index)
                    active.write(String(Character.toChars(codePoint)), style)
                    index += Character.charCount(codePoint)
                }
            }
        }

        return active.snapshot(usesAlternateScreen = alternateActive)
    }

    private fun findCsiEnd(value: String, start: Int): Int {
        for (index in start until value.length) {
            if (value[index] in '@'..'~') return index
        }
        return -1
    }

    private fun skipOsc(value: String, start: Int): Int {
        var index = start
        while (index < value.length) {
            if (value[index] == '\u0007') return index + 1
            if (value[index] == '\u001B' && index + 1 < value.length && value[index + 1] == '\\') {
                return index + 2
            }
            index += 1
        }
        return value.length
    }

    private fun parseParameters(value: String): List<Int?> =
        if (value.isEmpty()) emptyList() else value.split(';').map { it.toIntOrNull() }

    private fun count(parameters: List<Int?>): Int = (parameters.firstOrNull() ?: 1).coerceAtLeast(1)

    private fun oneBased(value: Int?): Int = (value ?: 1).coerceAtLeast(1) - 1

    private fun applySgr(
        initial: RuntimeTerminalTextStyle,
        parameters: List<Int?>,
    ): RuntimeTerminalTextStyle {
        var style = initial
        val values = if (parameters.isEmpty()) listOf(0) else parameters
        var index = 0
        while (index < values.size) {
            when (val value = values[index] ?: 0) {
                0 -> style = RuntimeTerminalTextStyle()
                1 -> style = style.copy(bold = true)
                4 -> style = style.copy(underline = true)
                7 -> style = style.copy(inverse = true)
                22 -> style = style.copy(bold = false)
                24 -> style = style.copy(underline = false)
                27 -> style = style.copy(inverse = false)
                in 30..37, in 90..97 -> style = style.copy(foreground = ansiColor(value))
                39 -> style = style.copy(foreground = RuntimeTerminalColor.DEFAULT)
                in 40..47, in 100..107 -> style = style.copy(background = ansiColor(value - 10))
                49 -> style = style.copy(background = RuntimeTerminalColor.DEFAULT)
                38, 48 -> {
                    // Consume 256-colour (38;5;n) and true-colour (38;2;r;g;b) parameters
                    // deterministically. Standard 16 colours remain styled; extended values are
                    // intentionally rendered with the default palette rather than leaking CSI.
                    index += when (values.getOrNull(index + 1)) {
                        2 -> 4
                        5 -> 2
                        else -> 0
                    }
                }
            }
            index += 1
        }
        return style
    }

    private fun ansiColor(value: Int): RuntimeTerminalColor = when (value) {
        30, 90 -> if (value == 90) RuntimeTerminalColor.BRIGHT_BLACK else RuntimeTerminalColor.BLACK
        31, 91 -> if (value == 91) RuntimeTerminalColor.BRIGHT_RED else RuntimeTerminalColor.RED
        32, 92 -> if (value == 92) RuntimeTerminalColor.BRIGHT_GREEN else RuntimeTerminalColor.GREEN
        33, 93 -> if (value == 93) RuntimeTerminalColor.BRIGHT_YELLOW else RuntimeTerminalColor.YELLOW
        34, 94 -> if (value == 94) RuntimeTerminalColor.BRIGHT_BLUE else RuntimeTerminalColor.BLUE
        35, 95 -> if (value == 95) RuntimeTerminalColor.BRIGHT_MAGENTA else RuntimeTerminalColor.MAGENTA
        36, 96 -> if (value == 96) RuntimeTerminalColor.BRIGHT_CYAN else RuntimeTerminalColor.CYAN
        37, 97 -> if (value == 97) RuntimeTerminalColor.BRIGHT_WHITE else RuntimeTerminalColor.WHITE
        else -> RuntimeTerminalColor.DEFAULT
    }

    private data class Cursor(val x: Int, val y: Int)

    private data class Cell(
        val glyph: String = " ",
        val style: RuntimeTerminalTextStyle = RuntimeTerminalTextStyle(),
        val continuation: Boolean = false,
    )

    private class Screen(
        private val columns: Int,
        private val rows: Int,
    ) {
        private val cells = Array(rows) { Array(columns) { Cell() } }
        private var x = 0
        private var y = 0
        private var savedCursor = Cursor(0, 0)

        fun cursor(): Cursor = Cursor(x, y)

        fun restoreCursor(cursor: Cursor) {
            x = cursor.x.coerceIn(0, columns - 1)
            y = cursor.y.coerceIn(0, rows - 1)
        }

        fun saveCursor() {
            savedCursor = cursor()
        }

        fun restoreSavedCursor() = restoreCursor(savedCursor)

        fun setCursor(column: Int, row: Int) {
            x = column.coerceIn(0, columns - 1)
            y = row.coerceIn(0, rows - 1)
        }

        fun setColumn(column: Int) {
            x = column.coerceIn(0, columns - 1)
        }

        fun setPosition(column: Int, row: Int) = setCursor(column, row)

        fun moveRelative(dx: Int = 0, dy: Int = 0) {
            x = (x + dx).coerceIn(0, columns - 1)
            y = (y + dy).coerceIn(0, rows - 1)
        }

        fun nextLine(count: Int) {
            y = (y + count).coerceIn(0, rows - 1)
            x = 0
        }

        fun previousLine(count: Int) {
            y = (y - count).coerceIn(0, rows - 1)
            x = 0
        }

        fun carriageReturn() {
            x = 0
        }

        fun lineFeed() {
            if (y == rows - 1) scrollUp(1) else y += 1
        }

        fun backspace() {
            x = (x - 1).coerceAtLeast(0)
        }

        fun tab(style: RuntimeTerminalTextStyle) {
            val nextStop = ((x / 8) + 1) * 8
            repeat((nextStop - x).coerceAtLeast(1)) { write(" ", style) }
        }

        fun write(glyph: String, style: RuntimeTerminalTextStyle) {
            val width = displayWidth(glyph)
            if (width == 2 && x == columns - 1) {
                x = 0
                lineFeed()
            }
            if (x >= columns) {
                x = 0
                lineFeed()
            }
            cells[y][x] = Cell(glyph = glyph, style = style)
            if (width == 2 && x + 1 < columns) {
                cells[y][x + 1] = Cell(style = style, continuation = true)
            }
            x += width
        }

        fun clearAll() {
            cells.indices.forEach { row -> clearRow(row) }
        }

        fun eraseDisplay(mode: Int) {
            when (mode) {
                1 -> {
                    for (row in 0..y) {
                        val end = if (row == y) x else columns - 1
                        clearRange(row, 0, end)
                    }
                }
                2, 3 -> clearAll()
                else -> {
                    clearRange(y, x, columns - 1)
                    for (row in (y + 1) until rows) clearRow(row)
                }
            }
        }

        fun eraseLine(mode: Int) {
            when (mode) {
                1 -> clearRange(y, 0, x)
                2 -> clearRow(y)
                else -> clearRange(y, x, columns - 1)
            }
        }

        fun eraseCharacters(count: Int) = clearRange(y, x, (x + count - 1).coerceAtMost(columns - 1))

        fun insertCharacters(count: Int) {
            val amount = count.coerceAtMost(columns - x)
            for (column in columns - 1 downTo x + amount) cells[y][column] = cells[y][column - amount]
            clearRange(y, x, x + amount - 1)
        }

        fun deleteCharacters(count: Int) {
            val amount = count.coerceAtMost(columns - x)
            for (column in x until columns - amount) cells[y][column] = cells[y][column + amount]
            clearRange(y, columns - amount, columns - 1)
        }

        fun insertLines(count: Int) {
            val amount = count.coerceAtMost(rows - y)
            for (row in rows - 1 downTo y + amount) copyRow(row - amount, row)
            for (row in y until y + amount) clearRow(row)
        }

        fun deleteLines(count: Int) {
            val amount = count.coerceAtMost(rows - y)
            for (row in y until rows - amount) copyRow(row + amount, row)
            for (row in rows - amount until rows) clearRow(row)
        }

        fun scrollUp(count: Int) {
            val amount = count.coerceIn(1, rows)
            for (row in 0 until rows - amount) copyRow(row + amount, row)
            for (row in rows - amount until rows) clearRow(row)
        }

        fun scrollDown(count: Int) {
            val amount = count.coerceIn(1, rows)
            for (row in rows - 1 downTo amount) copyRow(row - amount, row)
            for (row in 0 until amount) clearRow(row)
        }

        fun snapshot(usesAlternateScreen: Boolean): RuntimeTerminalScreen {
            val lastVisible = cells.indices.lastOrNull { row -> cells[row].any { it.glyph != " " && !it.continuation } }
            val lastRow = maxOf(lastVisible ?: -1, y)
            val lines = if (lastRow < 0) emptyList() else (0..lastRow).map(::styledLine)
            return RuntimeTerminalScreen(columns, rows, lines, usesAlternateScreen)
        }

        private fun styledLine(row: Int): RuntimeTerminalStyledLine {
            val lastColumn = cells[row].indexOfLast { it.glyph != " " && !it.continuation }
            if (lastColumn < 0) return RuntimeTerminalStyledLine(emptyList())
            val spans = mutableListOf<RuntimeTerminalStyledSpan>()
            var text = StringBuilder()
            var currentStyle: RuntimeTerminalTextStyle? = null
            fun flush() {
                val style = currentStyle ?: return
                if (text.isNotEmpty()) spans += RuntimeTerminalStyledSpan(text.toString(), style)
                text = StringBuilder()
            }
            for (column in 0..lastColumn) {
                val cell = cells[row][column]
                if (cell.continuation) continue
                if (currentStyle != cell.style) {
                    flush()
                    currentStyle = cell.style
                }
                text.append(cell.glyph)
            }
            flush()
            return RuntimeTerminalStyledLine(spans)
        }

        private fun clearRange(row: Int, from: Int, to: Int) {
            if (from > to || row !in cells.indices) return
            for (column in from.coerceAtLeast(0)..to.coerceAtMost(columns - 1)) cells[row][column] = Cell()
        }

        private fun clearRow(row: Int) {
            for (column in 0 until columns) cells[row][column] = Cell()
        }

        private fun copyRow(from: Int, to: Int) {
            for (column in 0 until columns) cells[to][column] = cells[from][column]
        }
    }

    private fun displayWidth(glyph: String): Int {
        val codePoint = glyph.codePointAt(0)
        return if (
            codePoint in 0x1100..0x115F ||
            codePoint in 0x2329..0x232A ||
            codePoint in 0x2E80..0xA4CF ||
            codePoint in 0xAC00..0xD7A3 ||
            codePoint in 0xF900..0xFAFF ||
            codePoint in 0xFE10..0xFE19 ||
            codePoint in 0xFE30..0xFE6F ||
            codePoint in 0xFF00..0xFF60 ||
            codePoint in 0xFFE0..0xFFE6
        ) 2 else 1
    }
}
