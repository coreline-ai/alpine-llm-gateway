package dev.alpine.chat.feature.ui.components

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMarkdownParserTest {
    @Test
    fun `parses supported blocks and keeps HTML inert`() {
        val source = """
            # Heading

            A **bold** paragraph with <script>alert(1)</script>.

            - first
            2. second
            > quoted

            ```kotlin
            val answer = 42
            ```
        """.trimIndent()

        val blocks = ChatMarkdownParser.parse(source)

        assertEquals(ChatMarkdownBlock.Heading(1, "Heading"), blocks[0])
        assertEquals(
            ChatMarkdownBlock.Paragraph("A **bold** paragraph with <script>alert(1)</script>."),
            blocks[1],
        )
        assertEquals(ChatMarkdownBlock.UnorderedItem("first"), blocks[2])
        assertEquals(ChatMarkdownBlock.OrderedItem(2, "second"), blocks[3])
        assertEquals(ChatMarkdownBlock.Quote("quoted"), blocks[4])
        assertEquals(
            ChatMarkdownBlock.CodeBlock("kotlin", "val answer = 42"),
            blocks[5],
        )
    }

    @Test
    fun `unfinished streaming fence becomes a code block`() {
        val blocks = ChatMarkdownParser.parse("```shell\necho safe")

        assertEquals(listOf(ChatMarkdownBlock.CodeBlock("shell", "echo safe")), blocks)
    }

    @Test
    fun `unfinished inline markers remain visible without throwing`() {
        val inline = ChatMarkdownParser.parseInline("start **unfinished and `code")

        assertEquals("start **unfinished and `code", inline.joinToString("") { it.text })
        assertTrue(inline.all { it is ChatMarkdownInline.Plain })
    }

    @Test
    fun `inline parser removes supported markers from annotated display text`() {
        val inline = ChatMarkdownParser.parseInline(
            "A **bold** and *soft* value with `code` plus \\*literal\\*.",
        )
        val annotated = ChatMarkdownParser.annotated(inline, Color.Transparent)

        assertEquals("A bold and soft value with code plus *literal*.", annotated.text)
        assertTrue(inline.any { it is ChatMarkdownInline.Strong && it.text == "bold" })
        assertTrue(inline.any { it is ChatMarkdownInline.Emphasis && it.text == "soft" })
        assertTrue(inline.any { it is ChatMarkdownInline.Code && it.text == "code" })
    }

    @Test
    fun `markdown image and link syntax stays inert text`() {
        val source = "![alt](https://example.test/a.png) [label](javascript:alert(1))"
        val blocks = ChatMarkdownParser.parse(source)
        val paragraph = blocks.single() as ChatMarkdownBlock.Paragraph
        val inline = ChatMarkdownParser.parseInline(paragraph.text)

        assertEquals(source, inline.joinToString("") { it.text })
    }

    @Test
    fun `parses bounded table and normalizes missing cells`() {
        val blocks = ChatMarkdownParser.parse(
            """
                | Name | Value |
                | --- | :---: |
                | Alpine | small |
                | Empty | |
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                ChatMarkdownBlock.Table(
                    headers = listOf("Name", "Value"),
                    rows = listOf(
                        listOf("Alpine", "small"),
                        listOf("Empty", ""),
                    ),
                ),
            ),
            blocks,
        )
    }

    @Test
    fun `unfinished or invalid table stays visible as paragraph text`() {
        val source = "| Name | Value |\n| not-a-delimiter | --- |"

        assertEquals(listOf(ChatMarkdownBlock.Paragraph(source)), ChatMarkdownParser.parse(source))
    }

    @Test
    fun `only absolute http links become annotated actions`() {
        val source =
            "[Docs](https://alpinelinux.org/docs) [bad](javascript:alert(1)) " +
                "![image](https://example.test/a.png)"
        val inline = ChatMarkdownParser.parseInline(source)
        val annotated = ChatMarkdownParser.annotated(inline, Color.Transparent, Color.Blue)

        assertTrue(
            inline.any {
                it is ChatMarkdownInline.Link &&
                    it.text == "Docs" &&
                    it.url == "https://alpinelinux.org/docs"
            },
        )
        assertTrue(inline.none { it is ChatMarkdownInline.Link && it.text == "bad" })
        assertEquals(
            "https://alpinelinux.org/docs",
            annotated.getStringAnnotations(ChatMarkdownParser.LINK_TAG, 0, annotated.length)
                .single()
                .item,
        )
        assertTrue(annotated.text.contains("[bad](javascript:alert(1))"))
        assertTrue(annotated.text.contains("![image](https://example.test/a.png)"))
    }

    @Test
    fun `link with user info whitespace or non web scheme remains inert`() {
        listOf(
            "[x](https://user@example.test/path)",
            "[x](https://example.test/a b)",
            "[x](file:///tmp/a)",
            "[x](intent://scan)",
            "[x](https://example.test/path(foo))",
        ).forEach { source ->
            assertTrue(ChatMarkdownParser.parseInline(source).none { it is ChatMarkdownInline.Link })
        }
    }

    @Test
    fun `long single line code remains one bounded parse block`() {
        val code = "x".repeat(4_096)
        val blocks = ChatMarkdownParser.parse("```text\n$code\n```")

        assertEquals(listOf(ChatMarkdownBlock.CodeBlock("text", code)), blocks)
    }

    @Test
    fun `known language highlighter preserves source and classifies safe token kinds`() {
        val source = "val answer = \"forty two\" // note\nreturn 42"
        val tokens = ChatCodeHighlighter.tokenize(source, "kotlin")

        assertEquals(source, tokens.joinToString("") { it.text })
        assertTrue(tokens.any { it.kind == ChatCodeTokenKind.KEYWORD && "val" in it.text })
        assertTrue(tokens.any { it.kind == ChatCodeTokenKind.STRING && "forty two" in it.text })
        assertTrue(tokens.any { it.kind == ChatCodeTokenKind.COMMENT && "note" in it.text })
        assertTrue(tokens.any { it.kind == ChatCodeTokenKind.NUMBER && "42" in it.text })
    }

    @Test
    fun `unknown language remains one plain code token`() {
        val source = "custom syntax 123"

        assertEquals(
            listOf(ChatCodeToken(ChatCodeTokenKind.PLAIN, source)),
            ChatCodeHighlighter.tokenize(source, "unknown"),
        )
    }
}
