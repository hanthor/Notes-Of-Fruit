package com.example.applenotes.export

import com.example.applenotes.proto.NoteBodyEditor.AttrSpan
import com.example.applenotes.proto.NoteBodyEditor.ParagraphStyle

/**
 * Note (visible text + attribute runs) → Markdown, for the Markdown *file*
 * export. (Markdown is never written into iCloud; see
 * docs/design-decisions.md Q1.)
 *
 * Title/Heading/Subheading → `#`/`##`/`###`; bulleted and dashed lists → `-`;
 * numbered → `1.`, `2.`, …; checklists → `- [ ]` / `- [x]`; monospaced
 * paragraphs → a fenced code block; bold/italic → `**`/`*`, strikethrough →
 * `~~`, underline → `<u>` (Markdown has none); links → `[text](url)`.
 * Body paragraphs are separated by blank lines; list items are not.
 */
internal object NoteMarkdown {

    fun render(
        text: String,
        spans: List<AttrSpan>,
        attachmentMarkdown: (AttrSpan) -> String? = { null },
    ): String {
        val paras = toExportParagraphs(text, spans)
        val blocks = ArrayList<String>()
        var i = 0
        while (i < paras.size) {
            val p = paras[i]
            when {
                p.style == ParagraphStyle.MONOSPACED -> {
                    var j = i
                    val lines = ArrayList<String>()
                    while (j < paras.size && paras[j].style == ParagraphStyle.MONOSPACED) {
                        lines += paras[j].runs.joinToString("") { r ->
                            if (r.attachment != null) NoteHtml.PLACEHOLDER else r.text
                        }
                        j++
                    }
                    val body = lines.joinToString("\n")
                    val fence = if (body.contains("```")) "~~~~" else "```"
                    blocks += "$fence\n$body\n$fence"
                    i = j
                }
                p.style.isList() -> {
                    val kind = p.style
                    var j = i
                    var n = 1
                    val items = ArrayList<String>()
                    while (j < paras.size && paras[j].style == kind) {
                        val item = paras[j]
                        val marker = when (kind) {
                            ParagraphStyle.NUMBERED_LIST -> "${n++}."
                            ParagraphStyle.CHECKBOX_LIST -> if (item.checked) "- [x]" else "- [ ]"
                            else -> "-"
                        }
                        items += "$marker ${inline(item, attachmentMarkdown)}".trimEnd()
                        j++
                    }
                    blocks += items.joinToString("\n")
                    i = j
                }
                else -> {
                    if (!p.isEmpty) {
                        val prefix = when (p.style) {
                            ParagraphStyle.TITLE -> "# "
                            ParagraphStyle.HEADING -> "## "
                            ParagraphStyle.SUBHEADING -> "### "
                            else -> ""
                        }
                        val body = inline(p, attachmentMarkdown)
                        blocks += prefix + if (prefix.isEmpty()) escapeLineStart(body) else body
                    }
                    i++
                }
            }
        }
        return if (blocks.isEmpty()) "" else blocks.joinToString("\n\n") + "\n"
    }

    private fun inline(p: ExportParagraph, attachmentMarkdown: (AttrSpan) -> String?): String {
        val sb = StringBuilder()
        for (r in p.runs) {
            if (r.attachment != null) {
                sb.append(attachmentMarkdown(r.attachment) ?: escapeInline(NoteHtml.PLACEHOLDER))
                continue
            }
            // Delimiters must hug non-space text ("**a **" is not bold), so
            // keep leading/trailing whitespace outside them.
            val core = r.text.trim()
            if (core.isEmpty()) {
                sb.append(r.text)
                continue
            }
            val lead = r.text.substring(0, r.text.indexOf(core))
            val trail = r.text.substring(lead.length + core.length)
            var inner = escapeInline(core)
            if (r.underline) inner = "<u>$inner</u>"
            if (r.strikethrough) inner = "~~$inner~~"
            if (r.italic) inner = "*$inner*"
            if (r.bold) inner = "**$inner**"
            val url = r.linkUrl
            if (url != null && isSafeLinkUrl(url)) {
                inner = "[$inner](${url.replace(" ", "%20").replace(")", "%29").replace("(", "%28")})"
            }
            sb.append(lead).append(inner).append(trail)
        }
        return sb.toString()
    }

    private val INLINE_SPECIAL = setOf('\\', '`', '*', '_', '[', ']', '<', '>', '~', '|')

    internal fun escapeInline(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            if (c in INLINE_SPECIAL) sb.append('\\')
            sb.append(c)
        }
        return sb.toString()
    }

    /** A body paragraph that would otherwise read as a heading, list item or quote. */
    private fun escapeLineStart(s: String): String {
        if (s.isEmpty()) return s
        if (s[0] == '#' || s[0] == '-' || s[0] == '+' || s[0] == '=') return "\\$s"
        val m = Regex("^(\\d+)([.)])").find(s) ?: return s
        return m.groupValues[1] + "\\" + s.substring(m.groupValues[1].length)
    }
}
