package com.example.applenotes.ui.detail

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.example.applenotes.R
import com.example.applenotes.export.ATTACHMENT_CHAR
import com.example.applenotes.ui.common.UiText

/** Snackbar shown when an edit that would remove an attachment is refused. */
internal val ATTACHMENT_DELETE_BLOCKED: UiText = UiText.Res(R.string.attachment_delete_blocked)

/**
 * True when turning [oldText] into [newText] removes or replaces at least one
 * attachment character (issue #14).
 *
 * Attachments (images, tables, sketches…) are U+FFFC characters in the text,
 * tied to separate CloudKit records through their attribute run. We can't
 * verify that deleting one round-trips cleanly with Apple's clients (the
 * Attachment record would be orphaned, or the note could reference a
 * missing object), so the editor refuses such edits instead of silently
 * writing them.
 *
 * Uses the same longest-common-prefix/suffix diff as [syncSpansToText]: the
 * edit replaced `oldText[lcp, oldText.length - lcs)`. If that range holds a
 * U+FFFC, an attachment would go. Typing or deleting anywhere else, and
 * moving the cursor / IME composition changes, are unaffected.
 */
internal fun removesAttachment(
    oldText: String,
    newText: String,
    /** Whether the U+FFFC at this index of [oldText] is a real attachment (has an attachment run). */
    isAttachmentAt: (Int) -> Boolean = { true },
): Boolean {
    if (oldText == newText || ATTACHMENT_CHAR !in oldText) return false
    var lcp = 0
    val maxPrefix = minOf(oldText.length, newText.length)
    while (lcp < maxPrefix && oldText[lcp] == newText[lcp]) lcp++
    var lcs = 0
    val maxSuffix = minOf(oldText.length - lcp, newText.length - lcp)
    while (lcs < maxSuffix && oldText[oldText.length - 1 - lcs] == newText[newText.length - 1 - lcs]) lcs++
    for (i in lcp until oldText.length - lcs) {
        if (oldText[i] == ATTACHMENT_CHAR && isAttachmentAt(i)) return true
    }
    return false
}

/**
 * [new] with any U+FFFC the edit from [oldText] inserted removed (pasted or
 * typed text can't carry an attachment; see [stripAttachmentChars]). The
 * selection is moved left by the chars dropped before it; the IME
 * composition is dropped when anything changed. Returns [new] unchanged
 * when the inserted text has none.
 */
internal fun dropInsertedAttachmentChars(oldText: String, new: TextFieldValue): TextFieldValue {
    val newText = new.text
    if (ATTACHMENT_CHAR !in newText) return new
    var lcp = 0
    val maxPrefix = minOf(oldText.length, newText.length)
    while (lcp < maxPrefix && oldText[lcp] == newText[lcp]) lcp++
    var lcs = 0
    val maxSuffix = minOf(oldText.length - lcp, newText.length - lcp)
    while (lcs < maxSuffix && oldText[oldText.length - 1 - lcs] == newText[newText.length - 1 - lcs]) lcs++
    val insEnd = newText.length - lcs
    if ((lcp until insEnd).none { newText[it] == ATTACHMENT_CHAR }) return new
    val out = StringBuilder(newText.length)
    out.append(newText, 0, lcp)
    for (i in lcp until insEnd) if (newText[i] != ATTACHMENT_CHAR) out.append(newText[i])
    out.append(newText, insEnd, newText.length)
    fun map(pos: Int): Int {
        val p = pos.coerceIn(0, newText.length)
        val dropped = (lcp until minOf(p, insEnd)).count { newText[it] == ATTACHMENT_CHAR }
        return p - dropped
    }
    return TextFieldValue(out.toString(), TextRange(map(new.selection.start), map(new.selection.end)))
}
