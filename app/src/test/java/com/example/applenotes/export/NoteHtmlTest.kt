package com.example.applenotes.export

import com.example.applenotes.proto.NoteBodyEditor.AttrSpan
import com.example.applenotes.proto.NoteBodyEditor.ParagraphStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Builds (text, spans) from (chunk, span-template) pairs. */
internal fun note(vararg parts: Pair<String, AttrSpan>): Pair<String, List<AttrSpan>> {
    val text = parts.joinToString("") { it.first }
    val spans = parts.map { (t, s) -> s.copy(length = t.length) }
    return text to spans
}

internal fun span(
    style: ParagraphStyle = ParagraphStyle.BODY,
    checked: Boolean = false,
    bold: Boolean = false,
    italic: Boolean = false,
    underline: Boolean = false,
    strike: Boolean = false,
    link: String? = null,
) = AttrSpan(0, style, checked, bold, italic, underline, strike, link)

class NoteHtmlTest {

    private fun html(vararg parts: Pair<String, AttrSpan>): String {
        val (t, s) = note(*parts)
        return NoteHtml.fragment(t, s)
    }

    @Test
    fun headingsAndBody() {
        val out = html(
            "Title\n" to span(ParagraphStyle.TITLE),
            "Head\n" to span(ParagraphStyle.HEADING),
            "Sub\n" to span(ParagraphStyle.SUBHEADING),
            "Body" to span(),
        )
        assertEquals("<h1>Title</h1>\n<h2>Head</h2>\n<h3>Sub</h3>\n<p>Body</p>\n", out)
    }

    @Test
    fun inlineFormattingNests() {
        val out = html(
            "a " to span(),
            "bold" to span(bold = true),
            " " to span(),
            "all" to span(bold = true, italic = true, underline = true, strike = true),
        )
        assertEquals("<p>a <b>bold</b> <b><i><u><s>all</s></u></i></b></p>\n", out)
    }

    @Test
    fun listsAreGroupedAndChecklistsShowState() {
        val out = html(
            "one\n" to span(ParagraphStyle.BULLETED_LIST),
            "two\n" to span(ParagraphStyle.BULLETED_LIST),
            "first\n" to span(ParagraphStyle.NUMBERED_LIST),
            "todo\n" to span(ParagraphStyle.CHECKBOX_LIST),
            "done" to span(ParagraphStyle.CHECKBOX_LIST, checked = true),
        )
        assertEquals(
            "<ul>\n<li>one</li>\n<li>two</li>\n</ul>\n" +
                "<ol>\n<li>first</li>\n</ol>\n" +
                "<ul class=\"checklist\">\n<li>☐ todo</li>\n<li>☑ done</li>\n</ul>\n",
            out,
        )
    }

    @Test
    fun monospacedParagraphsBecomeOnePreBlock() {
        val out = html(
            "x\n" to span(),
            "a < b\n" to span(ParagraphStyle.MONOSPACED),
            "  c" to span(ParagraphStyle.MONOSPACED, bold = true),
        )
        assertEquals("<p>x</p>\n<pre><code>a &lt; b\n  c</code></pre>\n", out)
    }

    @Test
    fun textAndLinksAreEscaped() {
        val out = html(
            "<script>&\"'" to span(),
            " " to span(),
            "link" to span(link = "https://example.com/?a=1&b=\"2\""),
        )
        assertEquals(
            "<p>&lt;script&gt;&amp;&quot;&#39; " +
                "<a href=\"https://example.com/?a=1&amp;b=&quot;2&quot;\">link</a></p>\n",
            out,
        )
    }

    @Test
    fun unsafeLinkSchemesAreDropped() {
        val out = html("click" to span(link = "javascript:alert(1)"))
        assertEquals("<p>click</p>\n", out)
        assertFalse(out.contains("javascript"))
    }

    @Test
    fun attachmentsBecomePlaceholdersOrSuppliedMarkup() {
        val text = "Pic\n\uFFFC"
        val spans = listOf(
            AttrSpan(4, ParagraphStyle.BODY),
            AttrSpan(1, ParagraphStyle.BODY, isAttachment = true, attachmentRecordName = "IMG-1"),
        )
        assertEquals("<p>Pic</p>\n<p>[attachment]</p>\n", NoteHtml.fragment(text, spans))
        val withImg = NoteHtml.fragment(text, spans) { if (it.attachmentRecordName == "IMG-1") "<img src=\"a.png\">" else null }
        assertTrue(withImg.contains("<p><img src=\"a.png\"></p>"))
    }

    @Test
    fun emptyParagraphsAndMissingSpans() {
        // Spans shorter than the text: the rest is plain body.
        assertEquals("<p>a</p>\n<p><br></p>\n<p>b</p>\n", NoteHtml.fragment("a\n\nb", emptyList()))
        assertEquals("", NoteHtml.fragment("", emptyList()))
    }

    @Test
    fun documentHasEscapedTitleAndCharset() {
        val doc = NoteHtml.document("A <b>", "x", emptyList())
        assertTrue(doc.contains("<meta charset=\"utf-8\">"))
        assertTrue(doc.contains("<title>A &lt;b&gt;</title>"))
        assertTrue(doc.contains("<p>x</p>"))
    }
}
