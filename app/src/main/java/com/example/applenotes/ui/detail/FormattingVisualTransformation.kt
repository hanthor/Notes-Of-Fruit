package com.example.applenotes.ui.detail

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import com.example.applenotes.proto.NoteBodyEditor

/**
 * VisualTransformation that styles a plaintext body in real-time as the user
 * types. Inline runs (bold/italic/underline/strike/link/monospace) get
 * SpanStyles; heading paragraphs get a bigger fontSize. List paragraphs
 * (bulleted/dashed/numbered/checkbox) get a visible marker prefix that's not
 * part of the underlying text — the OffsetMapping skips it so the cursor and
 * selection stay aligned with the raw chars.
 *
 * Limits we accept (vs. true block-by-block layout):
 *  - Markers are inline characters, not Compose Icons. Looks reasonable
 *    using "•", "–", "1.", "☐", "☑".
 *  - Heading sizes via SpanStyle.fontSize, no per-paragraph block padding.
 *  - Tables/images render as their U+FFFC placeholder still — block-level
 *    embeds aren't possible inside a single BasicTextField.
 */
internal class FormattingVisualTransformation(
    private val spans: List<NoteBodyEditor.AttrSpan>,
) : androidx.compose.ui.text.input.VisualTransformation {

    override fun filter(text: AnnotatedString): androidx.compose.ui.text.input.TransformedText {
        val raw = text.text
        val perChar = NoteBodyEditor.expandSpansToChars(raw, spans)
        val builder = AnnotatedString.Builder()
        // Per-raw-index running insertion offset: rawToTransformed[i] = position
        // in the displayed string corresponding to raw char i.
        val rawToTransformed = IntArray(raw.length + 1)
        var added = 0
        var listCounter = 0

        var i = 0
        while (i <= raw.length) {
            rawToTransformed[i] = i + added
            if (i == raw.length) break

            // At a paragraph start (idx 0 or just after a \n), maybe inject a
            // marker that visually represents the paragraph style.
            val isParaStart = i == 0 || raw[i - 1] == '\n'
            if (isParaStart) {
                val span = perChar.getOrNull(i)
                val style = span?.style ?: NoteBodyEditor.ParagraphStyle.BODY
                if (style == NoteBodyEditor.ParagraphStyle.NUMBERED_LIST) {
                    listCounter++
                } else {
                    listCounter = 0
                }
                val prefix = when (style) {
                    NoteBodyEditor.ParagraphStyle.BULLETED_LIST -> "•  "
                    NoteBodyEditor.ParagraphStyle.DASHED_LIST -> "–  "
                    NoteBodyEditor.ParagraphStyle.NUMBERED_LIST -> "$listCounter.  "
                    NoteBodyEditor.ParagraphStyle.CHECKBOX_LIST ->
                        if (span?.checked == true) "☑  " else "☐  "
                    else -> ""
                }
                if (prefix.isNotEmpty()) {
                    builder.withStyle(SpanStyle(color = MARKER_COLOR)) {
                        append(prefix)
                    }
                    added += prefix.length
                    rawToTransformed[i] = i + added
                }
            }

            // Group consecutive chars sharing a span (and not crossing
            // paragraph boundaries) and emit them with a single style block.
            var j = i + 1
            while (j < raw.length && raw[j - 1] != '\n' &&
                perChar.getOrNull(j) === perChar.getOrNull(i)
            ) j++
            val span = perChar.getOrNull(i)
            val sub = raw.substring(i, j)
            val style = paragraphSpanStyle(span) merge inlineSpanStyle(span)
            if (style != null) {
                builder.withStyle(style) { append(sub) }
            } else {
                builder.append(sub)
            }
            // Fill rawToTransformed for chars in (i..j].
            for (k in i + 1..j) rawToTransformed[k] = k + added
            i = j
        }

        return androidx.compose.ui.text.input.TransformedText(
            builder.toAnnotatedString(),
            object : androidx.compose.ui.text.input.OffsetMapping {
                override fun originalToTransformed(offset: Int): Int =
                    rawToTransformed[offset.coerceIn(0, raw.length)]

                override fun transformedToOriginal(offset: Int): Int {
                    // First raw index whose transformed offset >= offset.
                    var lo = 0
                    var hi = raw.length
                    while (lo < hi) {
                        val mid = (lo + hi) ushr 1
                        if (rawToTransformed[mid] < offset) lo = mid + 1 else hi = mid
                    }
                    return lo
                }
            },
        )
    }

    private fun paragraphSpanStyle(s: NoteBodyEditor.AttrSpan?): SpanStyle? {
        if (s == null) return null
        return when (s.style) {
            NoteBodyEditor.ParagraphStyle.TITLE ->
                SpanStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold)
            NoteBodyEditor.ParagraphStyle.HEADING ->
                SpanStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            NoteBodyEditor.ParagraphStyle.SUBHEADING ->
                SpanStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            NoteBodyEditor.ParagraphStyle.MONOSPACED ->
                SpanStyle(fontFamily = FontFamily.Monospace)
            NoteBodyEditor.ParagraphStyle.CHECKBOX_LIST ->
                if (s.checked) SpanStyle(textDecoration = TextDecoration.LineThrough)
                else null
            else -> null
        }
    }

    /** SpanStyle merge — non-null fields of [other] win. */
    private infix fun SpanStyle?.merge(other: SpanStyle?): SpanStyle? = when {
        this == null -> other
        other == null -> this
        else -> this.merge(other)
    }

    companion object {
        private val MARKER_COLOR = Color(0xFF8A8A8A)
    }
}
