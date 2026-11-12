package com.example.applenotes.export

import com.example.applenotes.proto.NoteBodyEditor
import com.example.applenotes.proto.NoteBodyEditor.AttrSpan
import com.example.applenotes.proto.NoteBodyEditor.ParagraphStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {

    /** Per-paragraph (text, style, checked) of an import. */
    private fun paragraphs(n: ImportedNote): List<Triple<String, ParagraphStyle, Boolean>> =
        toExportParagraphs(n.text, n.spans).map { Triple(it.plainText, it.style, it.checked) }

    private fun charAt(n: ImportedNote, needle: String): AttrSpan {
        val i = n.text.indexOf(needle)
        require(i >= 0) { "'$needle' not in '${n.text}'" }
        return NoteBodyEditor.expandSpansToChars(n.text, n.spans)[i]
    }

    @Test
    fun importMapsBlocksToParagraphStyles() {
        val n = MarkdownImport.markdown(
            """
            # Groceries
            ## Today
            ### Later
            - milk
            * eggs
            1. first
            2) second
            - [ ] bread
            - [x] jam
            plain line
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                Triple("Groceries", ParagraphStyle.TITLE, false),
                Triple("Today", ParagraphStyle.HEADING, false),
                Triple("Later", ParagraphStyle.SUBHEADING, false),
                Triple("milk", ParagraphStyle.BULLETED_LIST, false),
                Triple("eggs", ParagraphStyle.BULLETED_LIST, false),
                Triple("first", ParagraphStyle.NUMBERED_LIST, false),
                Triple("second", ParagraphStyle.NUMBERED_LIST, false),
                Triple("bread", ParagraphStyle.CHECKBOX_LIST, false),
                Triple("jam", ParagraphStyle.CHECKBOX_LIST, true),
                Triple("plain line", ParagraphStyle.BODY, false),
            ),
            paragraphs(n),
        )
        // No literal Markdown reaches the note text.
        assertFalse(n.text.contains("#") || n.text.contains("[ ]") || n.text.contains("- "))
        // Spans cover the text exactly.
        assertEquals(n.text.length, n.spans.sumOf { it.length })
    }

    @Test
    fun importMapsInlineFormatting() {
        val n = MarkdownImport.markdown("a **bold** and *it* and __b2__ _i2_ ***both*** ~~gone~~ [site](https://x.org)")
        assertEquals("a bold and it and b2 i2 both gone site", n.text)
        assertTrue(charAt(n, "bold").bold)
        assertFalse(charAt(n, "bold").italic)
        assertTrue(charAt(n, "it ").italic)
        assertTrue(charAt(n, "b2").bold)
        assertTrue(charAt(n, "i2").italic)
        assertTrue(charAt(n, "both").bold && charAt(n, "both").italic)
        assertTrue(charAt(n, "gone").strikethrough)
        assertEquals("https://x.org", charAt(n, "site").linkUrl)
        assertFalse(charAt(n, "a ").bold)
    }

    @Test
    fun importLeavesUnmatchedAndIntrawordDelimitersAlone() {
        val n = MarkdownImport.markdown("2 * 3 = 6, snake_case_name, \\*not italic\\*, [x](javascript:alert(1))")
        assertEquals("2 * 3 = 6, snake_case_name, *not italic*, [x](javascript:alert(1))", n.text)
        assertTrue(n.spans.none { it.bold || it.italic || it.linkUrl != null })
    }

    @Test
    fun importFencedCodeIsMonospacedAndLiteral() {
        val n = MarkdownImport.markdown("Code\n```kotlin\nval x = **1**\n# not a heading\n```\nafter")
        assertEquals(
            listOf(
                Triple("Code", ParagraphStyle.BODY, false),
                Triple("val x = **1**", ParagraphStyle.MONOSPACED, false),
                Triple("# not a heading", ParagraphStyle.MONOSPACED, false),
                Triple("after", ParagraphStyle.BODY, false),
            ),
            paragraphs(n),
        )
    }

    @Test
    fun importCollapsesBlankLinesAndNormalisesLineEndings() {
        val n = MarkdownImport.markdown("\uFEFFa\r\n\r\n\r\nb\r\n\r\n")
        assertEquals("a\n\nb", n.text)
    }

    @Test
    fun plainTextImportHasNoFormatting() {
        val n = MarkdownImport.plainText("# not parsed\n**x**\n")
        assertEquals("# not parsed\n**x**", n.text)
        assertEquals(listOf(AttrSpan(n.text.length, ParagraphStyle.BODY)), n.spans)
    }

    @Test
    fun importPrefillPicksParserByName() {
        assertEquals("Title", importPrefill("# Title", "a.md", null).text)
        assertEquals("# Title", importPrefill("# Title", "a.txt", "text/plain").text)
        assertEquals("Title", importPrefill("# Title", "notes", "text/markdown").text)
        // Empty file: the file name becomes the title line.
        assertEquals("Shopping", importPrefill("\n\n", "Shopping.md", null).text)
    }

    @Test
    fun importDropsObjectReplacementChars() {
        val md = importPrefill("# T￼itle\n**b￼**", "a.md", null)
        assertEquals("Title\nb", md.text)
        assertEquals(md.text.length, md.spans.sumOf { it.length })
        assertEquals("ab", importPrefill("a￼b", "a.txt", "text/plain").text)
        assertEquals("Pic", importPrefill("￼", "Pic.txt", "text/plain").text)
    }

    @Test
    fun exportRendersMarkdown() {
        val (t, s) = note(
            "Title\n" to span(ParagraphStyle.TITLE),
            "Some " to span(),
            "bold" to span(bold = true),
            " text_with*stars\n" to span(),
            "a\n" to span(ParagraphStyle.BULLETED_LIST),
            "b\n" to span(ParagraphStyle.DASHED_LIST),
            "one\n" to span(ParagraphStyle.NUMBERED_LIST),
            "two\n" to span(ParagraphStyle.NUMBERED_LIST),
            "todo\n" to span(ParagraphStyle.CHECKBOX_LIST),
            "done\n" to span(ParagraphStyle.CHECKBOX_LIST, checked = true),
            "code *x*\n" to span(ParagraphStyle.MONOSPACED),
            "# literal hash\n" to span(),
            "link" to span(link = "https://e.com/a b"),
        )
        assertEquals(
            "# Title\n\n" +
                "Some **bold** text\\_with\\*stars\n\n" +
                "- a\n\n" +
                "- b\n\n" +
                "1. one\n2. two\n\n" +
                "- [ ] todo\n- [x] done\n\n" +
                "```\ncode *x*\n```\n\n" +
                "\\# literal hash\n\n" +
                "[link](https://e.com/a%20b)\n",
            NoteMarkdown.render(t, s),
        )
    }

    @Test
    fun exportKeepsSpacesOutsideDelimiters() {
        val (t, s) = note("a" to span(), " b " to span(bold = true), "c" to span())
        assertEquals("a **b** c\n", NoteMarkdown.render(t, s))
    }

    @Test
    fun markdownRoundTripsThroughImport() {
        val (t, s) = note(
            "Title\n" to span(ParagraphStyle.TITLE),
            "x " to span(),
            "y" to span(italic = true),
            "\n" to span(),
            "item\n" to span(ParagraphStyle.CHECKBOX_LIST, checked = true),
            "z" to span(ParagraphStyle.HEADING),
        )
        val back = MarkdownImport.markdown(NoteMarkdown.render(t, s))
        val paras = paragraphs(back).filter { it.first.isNotEmpty() }
        assertEquals(
            listOf(
                Triple("Title", ParagraphStyle.TITLE, false),
                Triple("x y", ParagraphStyle.BODY, false),
                Triple("item", ParagraphStyle.CHECKBOX_LIST, true),
                Triple("z", ParagraphStyle.HEADING, false),
            ),
            paras,
        )
        assertTrue(charAt(back, "y").italic)
    }
}
