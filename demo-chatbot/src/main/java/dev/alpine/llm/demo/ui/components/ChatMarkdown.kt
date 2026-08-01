package dev.alpine.llm.demo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import java.net.URI

/**
 * Small, safe Markdown renderer for Provider text.
 *
 * It never interprets HTML or images. Only absolute http/https links are interactive, and those
 * require an explicit confirmation before leaving the app. The parser tolerates unfinished
 * streaming input and never requires a closing marker to render text.
 */
@Composable
fun ChatMarkdown(
    source: String,
    modifier: Modifier = Modifier,
) {
    val blocks = ChatMarkdownParser.parse(source)
    val uriHandler = LocalUriHandler.current
    var pendingUrl by remember { mutableStateOf<String?>(null) }

    SelectionContainer(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            blocks.forEach { block ->
                MarkdownBlock(block = block, onLinkRequest = { pendingUrl = it })
            }
        }
    }

    pendingUrl?.let { url ->
        AlertDialog(
            modifier = Modifier.testTag("markdown_link_dialog"),
            onDismissRequest = { pendingUrl = null },
            title = { Text("Open external link?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This link will open outside Alpine LLM Chat.")
                    Text(
                        text = url,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    modifier = Modifier.testTag("markdown_link_open"),
                    onClick = {
                        pendingUrl = null
                        runCatching { uriHandler.openUri(url) }
                    },
                ) {
                    Text("Open")
                }
            },
            dismissButton = {
                TextButton(
                    modifier = Modifier.testTag("markdown_link_cancel"),
                    onClick = { pendingUrl = null },
                ) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun MarkdownBlock(
    block: ChatMarkdownBlock,
    onLinkRequest: (String) -> Unit,
) {
    when (block) {
        is ChatMarkdownBlock.Paragraph -> MarkdownText(
            source = block.text,
            style = MaterialTheme.typography.bodyLarge,
            onLinkRequest = onLinkRequest,
        )

        is ChatMarkdownBlock.Heading -> MarkdownText(
            source = block.text,
            style = when (block.level) {
                1 -> MaterialTheme.typography.headlineSmall
                2 -> MaterialTheme.typography.titleLarge
                else -> MaterialTheme.typography.titleMedium
            },
            fontWeight = FontWeight.SemiBold,
            onLinkRequest = onLinkRequest,
        )

        is ChatMarkdownBlock.UnorderedItem -> ListItem(
            marker = "•",
            text = block.text,
            onLinkRequest = onLinkRequest,
        )
        is ChatMarkdownBlock.OrderedItem -> ListItem(
            marker = "${block.number}.",
            text = block.text,
            onLinkRequest = onLinkRequest,
        )
        is ChatMarkdownBlock.Quote -> Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                Modifier
                    .width(3.dp)
                    .background(
                        MaterialTheme.colorScheme.outline,
                        RoundedCornerShape(2.dp),
                    )
                    .padding(vertical = 12.dp),
            )
            Spacer(Modifier.width(8.dp))
            MarkdownText(
                source = block.text,
                style = MaterialTheme.typography.bodyLarge,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                onLinkRequest = onLinkRequest,
            )
        }

        is ChatMarkdownBlock.CodeBlock -> CodeBlock(block)
        is ChatMarkdownBlock.Table -> MarkdownTable(block, onLinkRequest)
    }
}

@Composable
private fun CodeBlock(block: ChatMarkdownBlock.CodeBlock) {
    val tokens = remember(block.language, block.code) {
        ChatCodeHighlighter.tokenize(block.code, block.language)
    }
    val highlighted = ChatCodeHighlighter.annotated(
        tokens = tokens,
        keywordColor = MaterialTheme.colorScheme.primary,
        stringColor = MaterialTheme.colorScheme.tertiary,
        numberColor = MaterialTheme.colorScheme.secondary,
        commentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("message_code_block"),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            block.language?.let { language ->
                Text(
                    text = language,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = highlighted.ifEmpty { AnnotatedString(" ") },
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun MarkdownTable(
    table: ChatMarkdownBlock.Table,
    onLinkRequest: (String) -> Unit,
) {
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("message_markdown_table"),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            TableRow(
                cells = table.headers,
                header = true,
                borderColor = borderColor,
                onLinkRequest = onLinkRequest,
            )
            table.rows.forEach { cells ->
                TableRow(
                    cells = cells,
                    header = false,
                    borderColor = borderColor,
                    onLinkRequest = onLinkRequest,
                )
            }
        }
    }
}

@Composable
private fun TableRow(
    cells: List<String>,
    header: Boolean,
    borderColor: Color,
    onLinkRequest: (String) -> Unit,
) {
    Row {
        cells.forEach { cell ->
            Box(
                modifier = Modifier
                    .width(148.dp)
                    .border(0.5.dp, borderColor)
                    .background(
                        if (header) {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        } else {
                            Color.Transparent
                        },
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                MarkdownText(
                    source = cell,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (header) FontWeight.SemiBold else null,
                    onLinkRequest = onLinkRequest,
                )
            }
        }
    }
}

@Composable
private fun ListItem(
    marker: String,
    text: String,
    onLinkRequest: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = marker,
            modifier = Modifier.width(28.dp),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
        )
        MarkdownText(
            source = text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
            onLinkRequest = onLinkRequest,
        )
    }
}

@Composable
private fun MarkdownText(
    source: String,
    style: TextStyle,
    onLinkRequest: (String) -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    fontStyle: FontStyle? = null,
) {
    val inline = remember(source) { ChatMarkdownParser.parseInline(source) }
    val annotated = ChatMarkdownParser.annotated(
        inline = inline,
        codeBackground = MaterialTheme.colorScheme.surfaceContainerLowest,
        linkColor = MaterialTheme.colorScheme.primary,
    )
    val effectiveStyle = style.copy(
        color = if (color == Color.Unspecified) style.color else color,
        fontWeight = fontWeight ?: style.fontWeight,
        fontStyle = fontStyle ?: style.fontStyle,
    )
    if (inline.any { it is ChatMarkdownInline.Link }) {
        @Suppress("DEPRECATION")
        ClickableText(
            text = annotated,
            modifier = modifier,
            style = effectiveStyle,
            onClick = { offset ->
                annotated.getStringAnnotations(
                    tag = ChatMarkdownParser.LINK_TAG,
                    start = offset,
                    end = offset,
                ).firstOrNull()?.item?.let(onLinkRequest)
            },
        )
    } else {
        Text(
            text = annotated,
            modifier = modifier,
            style = effectiveStyle,
        )
    }
}

internal sealed interface ChatMarkdownBlock {
    data class Paragraph(val text: String) : ChatMarkdownBlock
    data class Heading(val level: Int, val text: String) : ChatMarkdownBlock
    data class UnorderedItem(val text: String) : ChatMarkdownBlock
    data class OrderedItem(val number: Int, val text: String) : ChatMarkdownBlock
    data class Quote(val text: String) : ChatMarkdownBlock
    data class CodeBlock(val language: String?, val code: String) : ChatMarkdownBlock
    data class Table(val headers: List<String>, val rows: List<List<String>>) : ChatMarkdownBlock
}

internal sealed interface ChatMarkdownInline {
    val text: String

    data class Plain(override val text: String) : ChatMarkdownInline
    data class Strong(override val text: String) : ChatMarkdownInline
    data class Emphasis(override val text: String) : ChatMarkdownInline
    data class Code(override val text: String) : ChatMarkdownInline
    data class Link(override val text: String, val url: String) : ChatMarkdownInline
}

internal object ChatMarkdownParser {
    const val LINK_TAG = "safe_external_link"
    private const val MAX_TABLE_COLUMNS = 12
    private const val MAX_TABLE_ROWS = 100
    private val heading = Regex("^(#{1,6})\\s+(.+)$")
    private val unordered = Regex("^\\s{0,3}[-+*]\\s+(.+)$")
    private val ordered = Regex("^\\s{0,3}(\\d+)[.)]\\s+(.+)$")
    private val quote = Regex("^\\s{0,3}>\\s?(.*)$")
    private val tableDelimiter = Regex("^:?-{3,}:?$")

    fun parse(source: String): List<ChatMarkdownBlock> {
        if (source.isEmpty()) return emptyList()
        val result = mutableListOf<ChatMarkdownBlock>()
        val paragraph = mutableListOf<String>()
        var fence: String? = null
        var language: String? = null
        val code = mutableListOf<String>()
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')

        fun flushParagraph() {
            if (paragraph.isNotEmpty()) {
                result += ChatMarkdownBlock.Paragraph(paragraph.joinToString("\n"))
                paragraph.clear()
            }
        }

        fun flushCode() {
            result += ChatMarkdownBlock.CodeBlock(language, code.joinToString("\n"))
            code.clear()
            fence = null
            language = null
        }

        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            val activeFence = fence
            if (activeFence != null) {
                if (line.trimStart().startsWith(activeFence)) flushCode() else code += line
                index += 1
                continue
            }

            val trimmedStart = line.trimStart()
            val openingFence = when {
                trimmedStart.startsWith("```") -> "```"
                trimmedStart.startsWith("~~~") -> "~~~"
                else -> null
            }
            if (openingFence != null) {
                flushParagraph()
                fence = openingFence
                language = trimmedStart.removePrefix(openingFence).trim().takeIf(String::isNotEmpty)
                index += 1
                continue
            }

            if (line.isBlank()) {
                flushParagraph()
                index += 1
                continue
            }

            val headers = splitTableRow(line)
            val delimiters = lines.getOrNull(index + 1)?.let(::splitTableRow)
            if (
                headers != null &&
                delimiters != null &&
                headers.size == delimiters.size &&
                headers.size in 1..MAX_TABLE_COLUMNS &&
                delimiters.all { tableDelimiter.matches(it.replace(" ", "")) }
            ) {
                flushParagraph()
                index += 2
                val rows = mutableListOf<List<String>>()
                while (index < lines.size && rows.size < MAX_TABLE_ROWS) {
                    val row = splitTableRow(lines[index]) ?: break
                    rows += row.take(headers.size).let { bounded ->
                        bounded + List((headers.size - bounded.size).coerceAtLeast(0)) { "" }
                    }
                    index += 1
                }
                result += ChatMarkdownBlock.Table(headers, rows)
                continue
            }

            val headingMatch = heading.matchEntire(line)
            if (headingMatch != null) {
                flushParagraph()
                result += ChatMarkdownBlock.Heading(
                    level = headingMatch.groupValues[1].length,
                    text = headingMatch.groupValues[2],
                )
                index += 1
                continue
            }
            val unorderedMatch = unordered.matchEntire(line)
            if (unorderedMatch != null) {
                flushParagraph()
                result += ChatMarkdownBlock.UnorderedItem(unorderedMatch.groupValues[1])
                index += 1
                continue
            }
            val orderedMatch = ordered.matchEntire(line)
            if (orderedMatch != null) {
                flushParagraph()
                result += ChatMarkdownBlock.OrderedItem(
                    number = orderedMatch.groupValues[1].toIntOrNull() ?: 1,
                    text = orderedMatch.groupValues[2],
                )
                index += 1
                continue
            }
            val quoteMatch = quote.matchEntire(line)
            if (quoteMatch != null) {
                flushParagraph()
                result += ChatMarkdownBlock.Quote(quoteMatch.groupValues[1])
                index += 1
                continue
            }
            paragraph += line
            index += 1
        }
        if (fence != null) flushCode() else flushParagraph()
        return result
    }

    fun parseInline(source: String): List<ChatMarkdownInline> {
        if (source.isEmpty()) return emptyList()
        val result = mutableListOf<ChatMarkdownInline>()
        val plain = StringBuilder()

        fun flushPlain() {
            if (plain.isNotEmpty()) {
                result += ChatMarkdownInline.Plain(plain.toString())
                plain.clear()
            }
        }

        var index = 0
        while (index < source.length) {
            if (source[index] == '\\' && index + 1 < source.length && source[index + 1] in ESCAPABLE) {
                plain.append(source[index + 1])
                index += 2
                continue
            }
            if (source[index] == '[' && (index == 0 || source[index - 1] != '!')) {
                val labelEnd = source.indexOf("](", index + 1)
                val urlEnd = if (labelEnd >= 0) source.indexOf(')', labelEnd + 2) else -1
                if (labelEnd > index + 1 && urlEnd > labelEnd + 2) {
                    val raw = source.substring(index, urlEnd + 1)
                    val label = source.substring(index + 1, labelEnd)
                    val url = source.substring(labelEnd + 2, urlEnd)
                    if (isSafeExternalUrl(url)) {
                        flushPlain()
                        result += ChatMarkdownInline.Link(label, url)
                    } else {
                        plain.append(raw)
                    }
                    index = urlEnd + 1
                    continue
                }
            }
            val marker = when {
                source.startsWith("**", index) -> "**"
                source.startsWith("__", index) -> "__"
                source[index] == '`' -> "`"
                source[index] == '*' -> "*"
                source[index] == '_' -> "_"
                else -> null
            }
            if (marker == null) {
                plain.append(source[index++])
                continue
            }
            val closing = source.indexOf(marker, index + marker.length)
            if (closing <= index + marker.length) {
                plain.append(marker)
                index += marker.length
                continue
            }
            flushPlain()
            val content = source.substring(index + marker.length, closing)
            result += when (marker) {
                "**", "__" -> ChatMarkdownInline.Strong(content)
                "`" -> ChatMarkdownInline.Code(content)
                else -> ChatMarkdownInline.Emphasis(content)
            }
            index = closing + marker.length
        }
        flushPlain()
        return result
    }

    fun annotated(
        inline: List<ChatMarkdownInline>,
        codeBackground: Color,
        linkColor: Color = Color.Unspecified,
    ): AnnotatedString = buildAnnotatedString {
        inline.forEach { token ->
            when (token) {
                is ChatMarkdownInline.Plain -> append(token.text)
                is ChatMarkdownInline.Strong -> withStyle(
                    SpanStyle(fontWeight = FontWeight.Bold),
                ) { append(token.text) }
                is ChatMarkdownInline.Emphasis -> withStyle(
                    SpanStyle(fontStyle = FontStyle.Italic),
                ) { append(token.text) }
                is ChatMarkdownInline.Code -> withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = codeBackground,
                    ),
                ) { append(token.text) }
                is ChatMarkdownInline.Link -> {
                    pushStringAnnotation(LINK_TAG, token.url)
                    withStyle(
                        SpanStyle(
                            color = linkColor,
                            textDecoration = TextDecoration.Underline,
                        ),
                    ) { append(token.text) }
                    pop()
                }
            }
        }
    }

    private fun splitTableRow(line: String): List<String>? {
        if (!line.contains('|')) return null
        var source = line.trim()
        if (source.startsWith('|')) source = source.drop(1)
        if (source.endsWith('|') && !source.endsWith("\\|")) source = source.dropLast(1)
        val cells = mutableListOf<String>()
        val cell = StringBuilder()
        var index = 0
        while (index < source.length) {
            if (source[index] == '\\' && index + 1 < source.length && source[index + 1] == '|') {
                cell.append('|')
                index += 2
            } else if (source[index] == '|') {
                cells += cell.toString().trim()
                cell.clear()
                index += 1
            } else {
                cell.append(source[index++])
            }
        }
        cells += cell.toString().trim()
        return cells
    }

    private fun isSafeExternalUrl(raw: String): Boolean {
        if (
            raw.length !in 1..2_048 ||
            raw.any { it.isWhitespace() || it.isISOControl() } ||
            '(' in raw || ')' in raw
        ) {
            return false
        }
        return runCatching { URI(raw) }.getOrNull()?.let { uri ->
            uri.scheme?.lowercase() in setOf("http", "https") &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null
        } == true
    }

    private val ESCAPABLE = setOf('\\', '*', '_', '`', '[', ']', '(', ')')
}

internal enum class ChatCodeTokenKind {
    PLAIN,
    KEYWORD,
    STRING,
    NUMBER,
    COMMENT,
}

internal data class ChatCodeToken(val kind: ChatCodeTokenKind, val text: String)

internal object ChatCodeHighlighter {
    private const val MAX_HIGHLIGHT_CHARS = 100_000
    private val kotlinKeywords = setOf(
        "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if",
        "in", "interface", "is", "null", "object", "package", "return", "super", "this",
        "throw", "true", "try", "typealias", "typeof", "val", "var", "when", "while",
    )
    private val javaKeywords = setOf(
        "abstract", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
        "continue", "default", "do", "double", "else", "enum", "extends", "false", "final",
        "finally", "float", "for", "if", "implements", "import", "instanceof", "int",
        "interface", "long", "native", "new", "null", "package", "private", "protected",
        "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized",
        "this", "throw", "throws", "transient", "true", "try", "void", "volatile", "while",
    )
    private val pythonKeywords = setOf(
        "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del",
        "elif", "else", "except", "False", "finally", "for", "from", "global", "if", "import",
        "in", "is", "lambda", "None", "nonlocal", "not", "or", "pass", "raise", "return",
        "True", "try", "while", "with", "yield",
    )
    private val shellKeywords = setOf(
        "case", "do", "done", "elif", "else", "esac", "fi", "for", "function", "if", "in",
        "select", "then", "time", "until", "while",
    )

    fun tokenize(code: String, language: String?): List<ChatCodeToken> {
        if (code.isEmpty()) return emptyList()
        if (code.length > MAX_HIGHLIGHT_CHARS) return listOf(ChatCodeToken(ChatCodeTokenKind.PLAIN, code))
        val normalized = language?.trim()?.lowercase()?.split(Regex("\\s+"))?.firstOrNull()
        val keywords = when (normalized) {
            "kotlin", "kt", "kts" -> kotlinKeywords
            "java" -> javaKeywords
            "python", "py" -> pythonKeywords
            "bash", "sh", "shell", "zsh" -> shellKeywords
            "json" -> emptySet()
            else -> return listOf(ChatCodeToken(ChatCodeTokenKind.PLAIN, code))
        }
        val slashComments = normalized in setOf("kotlin", "kt", "kts", "java")
        val hashComments = normalized in setOf("python", "py", "bash", "sh", "shell", "zsh")
        val blockComments = slashComments
        val result = mutableListOf<ChatCodeToken>()
        var index = 0

        fun add(kind: ChatCodeTokenKind, end: Int) {
            if (end > index) result += ChatCodeToken(kind, code.substring(index, end))
            index = end
        }

        while (index < code.length) {
            if (slashComments && code.startsWith("//", index)) {
                add(ChatCodeTokenKind.COMMENT, code.indexOf('\n', index).takeIf { it >= 0 } ?: code.length)
                continue
            }
            if (blockComments && code.startsWith("/*", index)) {
                val close = code.indexOf("*/", index + 2)
                add(ChatCodeTokenKind.COMMENT, if (close >= 0) close + 2 else code.length)
                continue
            }
            if (hashComments && code[index] == '#') {
                add(ChatCodeTokenKind.COMMENT, code.indexOf('\n', index).takeIf { it >= 0 } ?: code.length)
                continue
            }
            if (code[index] == '\'' || code[index] == '"') {
                val quote = code[index]
                val triple = index + 2 < code.length && code[index + 1] == quote && code[index + 2] == quote
                var cursor = index + if (triple) 3 else 1
                while (cursor < code.length) {
                    if (code[cursor] == '\\') {
                        cursor = (cursor + 2).coerceAtMost(code.length)
                    } else if (
                        triple && cursor + 2 < code.length &&
                        code[cursor] == quote && code[cursor + 1] == quote && code[cursor + 2] == quote
                    ) {
                        cursor += 3
                        break
                    } else if (!triple && code[cursor] == quote) {
                        cursor += 1
                        break
                    } else {
                        cursor += 1
                    }
                }
                add(ChatCodeTokenKind.STRING, cursor)
                continue
            }
            if (code[index].isDigit()) {
                var cursor = index + 1
                while (cursor < code.length && (code[cursor].isLetterOrDigit() || code[cursor] in "._")) cursor += 1
                add(ChatCodeTokenKind.NUMBER, cursor)
                continue
            }
            if (code[index].isLetter() || code[index] == '_') {
                var cursor = index + 1
                while (cursor < code.length && (code[cursor].isLetterOrDigit() || code[cursor] == '_')) cursor += 1
                val word = code.substring(index, cursor)
                add(
                    if (word in keywords) ChatCodeTokenKind.KEYWORD else ChatCodeTokenKind.PLAIN,
                    cursor,
                )
                continue
            }
            add(ChatCodeTokenKind.PLAIN, index + 1)
        }
        return result.mergeAdjacent()
    }

    fun annotated(
        tokens: List<ChatCodeToken>,
        keywordColor: Color,
        stringColor: Color,
        numberColor: Color,
        commentColor: Color,
    ): AnnotatedString = buildAnnotatedString {
        tokens.forEach { token ->
            val style = when (token.kind) {
                ChatCodeTokenKind.PLAIN -> null
                ChatCodeTokenKind.KEYWORD -> SpanStyle(
                    color = keywordColor,
                    fontWeight = FontWeight.SemiBold,
                )
                ChatCodeTokenKind.STRING -> SpanStyle(color = stringColor)
                ChatCodeTokenKind.NUMBER -> SpanStyle(color = numberColor)
                ChatCodeTokenKind.COMMENT -> SpanStyle(
                    color = commentColor,
                    fontStyle = FontStyle.Italic,
                )
            }
            if (style == null) append(token.text) else withStyle(style) { append(token.text) }
        }
    }

    private fun List<ChatCodeToken>.mergeAdjacent(): List<ChatCodeToken> {
        if (isEmpty()) return this
        val merged = mutableListOf<ChatCodeToken>()
        forEach { token ->
            val previous = merged.lastOrNull()
            if (previous?.kind == token.kind) {
                merged[merged.lastIndex] = previous.copy(text = previous.text + token.text)
            } else {
                merged += token
            }
        }
        return merged
    }
}
