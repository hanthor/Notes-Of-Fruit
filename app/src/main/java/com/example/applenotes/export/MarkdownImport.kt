package com.example.applenotes.export

import com.example.applenotes.proto.NoteBodyEditor
import com.example.applenotes.proto.NoteBodyEditor.AttrSpan
import com.example.applenotes.proto.NoteBodyEditor.ParagraphStyle

/**
 * A `.md` / `.txt` file turned into the editor's native model: visible text
 * plus [AttrSpan]s in full-body coordinates (first line = title), the same
 * shape the formatting toolbar produces. The editor then saves it like any
 * other draft, so iCloud receives real attribute runs, never literal
 * Markdown (docs/design-decisions.md Q1).
 */
internal data class ImportedNote(val text: String, val spans: List<AttrSpan>)

internal object MarkdownImport {

    /** Plain text: no formatting, line endings normalised. */
    fun plainText(raw: String): ImportedNote {
        val text = normalise(raw).trim('\n')
        return ImportedNote(text, if (text.isEmpty()) emptyList() else listOf(AttrSpan(text.length, ParagraphStyle.BODY)))
    }

    /**
     * Supported: `#`/`##`/`###` (deeper levels → subheading), `-`/`*`/`+`
     * bullets, `1.`/`1)` numbered items, `- [ ]`/`- [x]` checkboxes, fenced
     * code blocks (→ monospaced), `**bold**`/`__bold__`, `*italic*`/`_italic_`,
     * `~~strike~~`, `[text](url)` links, backslash escapes. Inline code keeps
     * its text without the backticks (Apple has no inline monospace).
     * Runs of blank lines collapse to one empty paragraph. Anything else is
     * kept as literal text.
     */
    fun markdown(raw: String): ImportedNote {
        val lines = normalise(raw).split('\n')
        val paras = ArrayList<Para>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val fence = FENCE.find(line)
            if (fence != null) {
                val marker = fence.groupValues[1]
                var j = i + 1
                while (j < lines.size && !lines[j].trimStart().startsWith(marker)) {
                    paras += Para(ParagraphStyle.MONOSPACED, false, plainChars(lines[j]))
                    j++
                }
                i = j + 1
                continue
            }
            if (line.isBlank()) {
                if (paras.isNotEmpty() && !paras.last().isBlank) paras += Para(ParagraphStyle.BODY, false, emptyList())
                i++
                continue
            }
            paras += parseLine(line)
            i++
        }
        while (paras.lastOrNull()?.isBlank == true) paras.removeAt(paras.size - 1)

        val text = StringBuilder()
        val perChar = ArrayList<AttrSpan>()
        for ((idx, p) in paras.withIndex()) {
            for (c in p.chars) {
                text.append(c.ch)
                perChar += AttrSpan(
                    length = 1,
                    style = p.style,
                    checked = p.checked,
                    bold = c.bold,
                    italic = c.italic,
                    strikethrough = c.strike,
                    linkUrl = c.link,
                )
            }
            if (idx < paras.size - 1) {
                text.append('\n')
                // The paragraph break carries the paragraph's style, as in
                // NoteBodyEditor.setParagraphStyle.
                perChar += AttrSpan(length = 1, style = p.style, checked = p.checked)
            }
        }
        return ImportedNote(text.toString(), NoteBodyEditor.compactCharsToSpans(perChar.toTypedArray()))
    }

    private data class Ch(
        val ch: Char,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val strike: Boolean = false,
        val link: String? = null,
    )

    private class Para(val style: ParagraphStyle, val checked: Boolean, val chars: List<Ch>) {
        val isBlank get() = chars.isEmpty() && style == ParagraphStyle.BODY
    }

    private val FENCE = Regex("^\\s{0,3}(```+|~~~+)")
    private val HEADING = Regex("^\\s{0,3}(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val CHECKBOX = Regex("^\\s*[-*+]\\s+\\[([ xX])]\\s+(.*)$")
    private val BULLET = Regex("^\\s*[-*+]\\s+(.*)$")
    private val NUMBERED = Regex("^\\s*\\d{1,9}[.)]\\s+(.*)$")

    private fun parseLine(line: String): Para {
        HEADING.find(line)?.let { m ->
            val style = when (m.groupValues[1].length) {
                1 -> ParagraphStyle.TITLE
                2 -> ParagraphStyle.HEADING
                else -> ParagraphStyle.SUBHEADING
            }
            return Para(style, false, inline(m.groupValues[2]))
        }
        CHECKBOX.find(line)?.let { m ->
            return Para(ParagraphStyle.CHECKBOX_LIST, m.groupValues[1] != " ", inline(m.groupValues[2]))
        }
        BULLET.find(line)?.let { m -> return Para(ParagraphStyle.BULLETED_LIST, false, inline(m.groupValues[1])) }
        NUMBERED.find(line)?.let { m -> return Para(ParagraphStyle.NUMBERED_LIST, false, inline(m.groupValues[1])) }
        return Para(ParagraphStyle.BODY, false, inline(line.trimEnd()))
    }

    private fun plainChars(s: String) = s.map { Ch(it) }

    private const val ESCAPABLE = "\\`*_{}[]()#+-.!|~<>"

    /** Inline Markdown → chars with flags. Unmatched delimiters stay literal. */
    private fun inline(s: String): List<Ch> = InlineParser(s).parse(0, s.length, Ch(' '))

    private class InlineParser(val s: String) {
        fun parse(from: Int, to: Int, fmt: Ch): List<Ch> {
            val out = ArrayList<Ch>()
            var i = from
            while (i < to) {
                val c = s[i]
                if (c == '\\' && i + 1 < to && s[i + 1] in ESCAPABLE) {
                    out += fmt.copy(ch = s[i + 1]); i += 2; continue
                }
                if (c == '`') {
                    val close = s.indexOf('`', i + 1).takeIf { it in (i + 2)..<to }
                    if (close != null) {
                        for (k in i + 1 until close) out += fmt.copy(ch = s[k])
                        i = close + 1; continue
                    }
                }
                if (c == '[') {
                    val link = matchLink(i, to)
                    if (link != null) {
                        out += parse(link.first.first, link.first.last + 1, fmt.copy(link = link.second))
                        i = link.third; continue
                    }
                }
                val delim = when {
                    s.startsWith("***", i) -> "***"
                    s.startsWith("**", i) -> "**"
                    s.startsWith("__", i) -> "__"
                    s.startsWith("~~", i) -> "~~"
                    c == '*' -> "*"
                    c == '_' -> "_"
                    else -> null
                }
                if (delim != null) {
                    val close = findClose(delim, i + delim.length, to)
                    if (close != null) {
                        val inner = when (delim) {
                            "***" -> fmt.copy(bold = true, italic = true)
                            "**", "__" -> fmt.copy(bold = true)
                            "~~" -> fmt.copy(strike = true)
                            else -> fmt.copy(italic = true)
                        }
                        out += parse(i + delim.length, close, inner)
                        i = close + delim.length; continue
                    }
                    // Literal delimiter.
                    for (k in delim.indices) out += fmt.copy(ch = s[i + k])
                    i += delim.length; continue
                }
                out += fmt.copy(ch = c)
                i++
            }
            return out
        }

        /** Closing delimiter: content non-empty, not space-padded, `_` not intraword. */
        private fun findClose(delim: String, start: Int, to: Int): Int? {
            if (start >= to || s[start].isWhitespace()) return null
            if (delim[0] == '_' && start - delim.length - 1 >= 0 && s[start - delim.length - 1].isLetterOrDigit()) return null
            var j = start + 1
            while (j <= to - delim.length) {
                if (s[j] == '\\') { j += 2; continue }
                if (s.startsWith(delim, j) && !s[j - 1].isWhitespace()) {
                    // "**a*" must not close "*" on the first "*" of "**".
                    val longer = delim.length == 1 && j + 1 < to && s[j + 1] == delim[0]
                    val intraword = delim[0] == '_' && j + delim.length < to && s[j + delim.length].isLetterOrDigit()
                    if (!longer && !intraword) return j
                    if (longer) { j += 2; continue }
                }
                j++
            }
            return null
        }

        /** `[text](url)` → (text range, url, index after the link). */
        private fun matchLink(open: Int, to: Int): Triple<IntRange, String, Int>? {
            val closeBracket = s.indexOf(']', open + 1).takeIf { it in (open + 2)..<to } ?: return null
            if (closeBracket + 1 >= to || s[closeBracket + 1] != '(') return null
            val closeParen = s.indexOf(')', closeBracket + 2).takeIf { it in (closeBracket + 3)..<(to + 1) } ?: return null
            val url = s.substring(closeBracket + 2, closeParen).trim().substringBefore(' ')
            if (url.isEmpty() || !isSafeLinkUrl(url)) return null
            return Triple((open + 1) until closeBracket, url, closeParen + 1)
        }
    }

    private fun normalise(raw: String) = raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
}
