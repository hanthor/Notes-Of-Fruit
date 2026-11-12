package com.example.applenotes.ui.detail

import androidx.compose.foundation.layout.size
import com.example.applenotes.proto.NoteBodyEditor

/**
 * Re-align an AttrSpan list with edited text. Approach: longest-common-prefix
 * + longest-common-suffix diff; chars in the deleted middle are removed from
 * the per-char span array; chars in the inserted middle inherit the format of
 * the char immediately before the cut (or default body if at the start).
 *
 * Imperfect but good enough — handles typing/backspace/IME composition
 * without losing existing formatting on either side.
 */
internal fun syncSpansToText(
    oldText: String,
    oldSpans: List<NoteBodyEditor.AttrSpan>,
    newText: String,
): List<NoteBodyEditor.AttrSpan> {
    if (oldText == newText) return oldSpans
    val perChar = NoteBodyEditor.expandSpansToChars(oldText, oldSpans)
    var lcp = 0
    val maxPrefix = minOf(oldText.length, newText.length)
    while (lcp < maxPrefix && oldText[lcp] == newText[lcp]) lcp++
    var lcs = 0
    val maxSuffix = minOf(oldText.length - lcp, newText.length - lcp)
    while (lcs < maxSuffix &&
        oldText[oldText.length - 1 - lcs] == newText[newText.length - 1 - lcs]) lcs++
    val deletedRange = lcp until (oldText.length - lcs)
    val insertedLen = newText.length - lcp - lcs

    val template = (perChar.getOrNull(lcp - 1) ?: perChar.firstOrNull())
        ?.copy(length = 1)
        ?: NoteBodyEditor.AttrSpan(length = 1, style = NoteBodyEditor.ParagraphStyle.BODY)

    val newPerChar = ArrayList<NoteBodyEditor.AttrSpan>(newText.length)
    for (i in 0 until lcp) newPerChar.add(perChar[i])
    repeat(insertedLen) {
        // Newly inserted chars carry inline formatting from the previous char,
        // but reset to BODY paragraph style if the insert begins on or after a
        // paragraph break (avoids accidentally extending a Heading run when
        // hitting Enter at end of line).
        val carry = if (template.style == NoteBodyEditor.ParagraphStyle.TITLE ||
            template.style == NoteBodyEditor.ParagraphStyle.HEADING ||
            template.style == NoteBodyEditor.ParagraphStyle.SUBHEADING) {
            template.copy(style = NoteBodyEditor.ParagraphStyle.BODY)
        } else template
        newPerChar.add(carry)
    }
    for (i in (oldText.length - lcs) until oldText.length) newPerChar.add(perChar[i])
    if (newPerChar.size != newText.length) {
        // Defensive — return a single body span covering the whole new text.
        return listOf(NoteBodyEditor.AttrSpan(
            length = newText.length, style = NoteBodyEditor.ParagraphStyle.BODY,
        ))
    }
    return NoteBodyEditor.compactCharsToSpans(newPerChar.toTypedArray())
}

/**
 * Drop the first [skipChars] characters' worth of attribute spans, splitting
 * the boundary span if the cut falls inside one. Used to align spans parsed
 * from the full visible body (which includes the title line) with the body
 * text we render below the app bar.
 */
internal fun attrSpansAfter(
    spans: List<NoteBodyEditor.AttrSpan>,
    skipChars: Int,
): List<NoteBodyEditor.AttrSpan> {
    if (skipChars <= 0) return spans
    val out = mutableListOf<NoteBodyEditor.AttrSpan>()
    var consumed = 0
    for (s in spans) {
        if (out.isNotEmpty()) {
            out.add(s)
            continue
        }
        val end = consumed + s.length
        when {
            end <= skipChars -> consumed = end
            consumed >= skipChars -> out.add(s)
            else -> {
                out.add(s.copy(length = end - skipChars))
                consumed = end
            }
        }
    }
    return out
}
