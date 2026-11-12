package com.example.applenotes.ui.detail

import com.example.applenotes.export.ATTACHMENT_CHAR

/** Snackbar shown when an edit that would remove an attachment is refused. */
internal const val ATTACHMENT_DELETE_BLOCKED = "Attachments can't be removed from Android yet"

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
internal fun removesAttachment(oldText: String, newText: String): Boolean {
    if (oldText == newText || ATTACHMENT_CHAR !in oldText) return false
    var lcp = 0
    val maxPrefix = minOf(oldText.length, newText.length)
    while (lcp < maxPrefix && oldText[lcp] == newText[lcp]) lcp++
    var lcs = 0
    val maxSuffix = minOf(oldText.length - lcp, newText.length - lcp)
    while (lcs < maxSuffix && oldText[oldText.length - 1 - lcs] == newText[newText.length - 1 - lcs]) lcs++
    for (i in lcp until oldText.length - lcs) {
        if (oldText[i] == ATTACHMENT_CHAR) return true
    }
    return false
}
