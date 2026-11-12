package com.example.applenotes.export

import com.example.applenotes.proto.NoteBodyEditor
import com.example.applenotes.proto.NoteBodyEditor.AttrSpan
import com.example.applenotes.proto.NoteBodyEditor.ParagraphStyle

/** The object-replacement character Apple puts where an attachment sits. */
internal const val ATTACHMENT_CHAR = '\uFFFC'

/**
 * A note's visible text + attribute runs regrouped into paragraphs of inline
 * runs, the shape both exporters ([NoteHtml], [NoteMarkdown]) walk.
 *
 * Apple stores paragraph styles per character; every char of a paragraph
 * (including its trailing '\n') carries the same style, so a paragraph's style
 * is read from its first char (or from the '\n' of an empty paragraph).
 */
internal data class ExportParagraph(
    val style: ParagraphStyle,
    val checked: Boolean,
    val runs: List<ExportRun>,
) {
    val plainText: String get() = runs.joinToString("") { it.text }
    val isEmpty: Boolean get() = runs.isEmpty()
}

/** A stretch of chars with the same inline formatting, or one attachment char. */
internal data class ExportRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikethrough: Boolean = false,
    val linkUrl: String? = null,
    /** Non-null when this run is a single attachment char; [text] is then U+FFFC. */
    val attachment: AttrSpan? = null,
)

internal fun toExportParagraphs(text: String, spans: List<AttrSpan>): List<ExportParagraph> {
    if (text.isEmpty()) return emptyList()
    val perChar = NoteBodyEditor.expandSpansToChars(text, spans)
    val out = ArrayList<ExportParagraph>()
    var start = 0
    while (start <= text.length) {
        val nl = text.indexOf('\n', start).let { if (it < 0) text.length else it }
        // Style from the paragraph's first char; the '\n' for an empty one.
        val styleChar = perChar.getOrNull(start) ?: perChar.getOrNull(nl)
        out += ExportParagraph(
            style = styleChar?.style ?: ParagraphStyle.BODY,
            checked = styleChar?.checked ?: false,
            runs = runsOf(text, perChar, start, nl),
        )
        if (nl >= text.length) break
        start = nl + 1
        // "a\n" ends with an empty paragraph only in the CRDT's eyes; drop it.
        if (start == text.length) break
    }
    return out
}

private fun runsOf(text: String, perChar: Array<AttrSpan>, from: Int, to: Int): List<ExportRun> {
    val runs = ArrayList<ExportRun>()
    var i = from
    while (i < to) {
        val s = perChar[i]
        if (s.isAttachment || text[i] == ATTACHMENT_CHAR) {
            runs += ExportRun(ATTACHMENT_CHAR.toString(), attachment = s)
            i++
            continue
        }
        var j = i + 1
        while (j < to && text[j] != ATTACHMENT_CHAR && !perChar[j].isAttachment && sameInline(perChar[j], s)) j++
        runs += ExportRun(
            text = text.substring(i, j),
            bold = s.bold,
            italic = s.italic,
            underline = s.underline,
            strikethrough = s.strikethrough,
            linkUrl = s.linkUrl,
        )
        i = j
    }
    return runs
}

private fun sameInline(a: AttrSpan, b: AttrSpan) =
    a.bold == b.bold && a.italic == b.italic && a.underline == b.underline &&
        a.strikethrough == b.strikethrough && a.linkUrl == b.linkUrl

/** Link schemes we emit as real links; anything else is exported as plain text. */
internal fun isSafeLinkUrl(url: String): Boolean {
    val scheme = url.substringBefore(':', missingDelimiterValue = "").lowercase()
    return scheme in setOf("http", "https", "mailto", "tel")
}

internal fun ParagraphStyle.isList(): Boolean = when (this) {
    ParagraphStyle.BULLETED_LIST, ParagraphStyle.DASHED_LIST,
    ParagraphStyle.NUMBERED_LIST, ParagraphStyle.CHECKBOX_LIST -> true
    else -> false
}
