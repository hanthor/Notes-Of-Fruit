package com.example.applenotes.export

import com.example.applenotes.proto.NoteBodyEditor.AttrSpan
import com.example.applenotes.proto.NoteBodyEditor.ParagraphStyle

/**
 * Note (visible text + attribute runs, full-body coordinates) → HTML.
 *
 * Used for the share sheet's `EXTRA_HTML_TEXT`, HTML export and printing.
 * Mapping: Title/Heading/Subheading → h1/h2/h3, Body → p, Monospaced → one
 * `<pre><code>` per run of monospaced paragraphs, bulleted/dashed → ul,
 * numbered → ol, checklist → `ul.checklist` with ☐/☑, bold/italic/underline/
 * strikethrough → b/i/u/s, links → a (http/https/mailto/tel only).
 * All note text is HTML-escaped; attachments become "[attachment]" unless
 * [attachmentHtml] supplies markup for them.
 */
internal object NoteHtml {

    /**
     * The body as an HTML fragment (no `<html>` wrapper).
     *
     * @param attachmentHtml returns trusted markup for an attachment span (for
     *  example an `<img>` for an image that was already downloaded), or null
     *  for the placeholder.
     */
    fun fragment(
        text: String,
        spans: List<AttrSpan>,
        attachmentHtml: (AttrSpan) -> String? = { null },
    ): String {
        val paras = toExportParagraphs(text, spans)
        val sb = StringBuilder()
        var i = 0
        while (i < paras.size) {
            val p = paras[i]
            when {
                p.style == ParagraphStyle.MONOSPACED -> {
                    var j = i
                    sb.append("<pre><code>")
                    while (j < paras.size && paras[j].style == ParagraphStyle.MONOSPACED) {
                        if (j > i) sb.append('\n')
                        // Code blocks keep only the text: no inline markup.
                        sb.append(escape(paras[j].runs.joinToString("") { r ->
                            if (r.attachment != null) PLACEHOLDER else r.text
                        }))
                        j++
                    }
                    sb.append("</code></pre>\n")
                    i = j
                }
                p.style.isList() -> {
                    val listKind = p.style
                    val (open, close) = when (listKind) {
                        ParagraphStyle.NUMBERED_LIST -> "<ol>" to "</ol>"
                        ParagraphStyle.CHECKBOX_LIST -> "<ul class=\"checklist\">" to "</ul>"
                        ParagraphStyle.DASHED_LIST -> "<ul class=\"dashed\">" to "</ul>"
                        else -> "<ul>" to "</ul>"
                    }
                    sb.append(open).append('\n')
                    var j = i
                    while (j < paras.size && paras[j].style.isList() && paras[j].style == listKind) {
                        val item = paras[j]
                        sb.append("<li>")
                        if (item.style == ParagraphStyle.CHECKBOX_LIST) {
                            sb.append(if (item.checked) "☑ " else "☐ ")
                        }
                        appendRuns(sb, item, attachmentHtml)
                        sb.append("</li>\n")
                        j++
                    }
                    sb.append(close).append('\n')
                    i = j
                }
                else -> {
                    val tag = when (p.style) {
                        ParagraphStyle.TITLE -> "h1"
                        ParagraphStyle.HEADING -> "h2"
                        ParagraphStyle.SUBHEADING -> "h3"
                        else -> "p"
                    }
                    sb.append('<').append(tag).append('>')
                    if (p.isEmpty) sb.append("<br>") else appendRuns(sb, p, attachmentHtml)
                    sb.append("</").append(tag).append(">\n")
                    i++
                }
            }
        }
        return sb.toString()
    }

    /** A standalone HTML document (for export and printing). */
    fun document(
        title: String,
        text: String,
        spans: List<AttrSpan>,
        attachmentHtml: (AttrSpan) -> String? = { null },
    ): String = buildString {
        append("<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"utf-8\">\n")
        append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
        append("<title>").append(escape(title.ifBlank { "Untitled note" })).append("</title>\n")
        append("<style>\n")
        append("body{font-family:sans-serif;line-height:1.45;max-width:46em;margin:1.5em auto;padding:0 1em}\n")
        append("ul.checklist,ul.dashed{list-style:none;padding-left:1.2em}\n")
        append("ul.dashed li::before{content:\"– \"}\n")
        append("pre{background:#f4f4f4;padding:.6em;white-space:pre-wrap}\n")
        append("img{max-width:100%}\n")
        append("</style>\n</head>\n<body>\n")
        append(fragment(text, spans, attachmentHtml))
        append("</body>\n</html>\n")
    }

    private fun appendRuns(
        sb: StringBuilder,
        p: ExportParagraph,
        attachmentHtml: (AttrSpan) -> String?,
    ) {
        for (r in p.runs) {
            if (r.attachment != null) {
                sb.append(attachmentHtml(r.attachment) ?: escape(PLACEHOLDER))
                continue
            }
            var inner = escape(r.text)
            if (r.strikethrough) inner = "<s>$inner</s>"
            if (r.underline) inner = "<u>$inner</u>"
            if (r.italic) inner = "<i>$inner</i>"
            if (r.bold) inner = "<b>$inner</b>"
            val url = r.linkUrl
            if (url != null && isSafeLinkUrl(url)) {
                inner = "<a href=\"${escape(url)}\">$inner</a>"
            }
            sb.append(inner)
        }
    }

    const val PLACEHOLDER = "[attachment]"

    /** Escapes text for use in element content and double/single-quoted attributes. */
    fun escape(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (c in s) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&#39;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}
