package com.example.applenotes.ui.detail

import com.example.applenotes.proto.NoteBodyEditor
import kotlinx.serialization.Serializable

/**
 * Initial content for a brand-new draft opened from outside the editor
 * (share sheet, "Add to Notes" text selection, file import, …).
 *
 * [text] is the note's full visible text (first line = title) and [spans]
 * are attribute runs over it in full-body coordinates, exactly what the
 * formatting toolbar produces. The editor opens the draft with this content
 * as *unsaved* changes; nothing reaches iCloud until the user saves (or
 * leaves the editor, which saves) through the normal draft path.
 */
@Serializable
data class DraftPrefill(
    val text: String,
    val spans: List<SavedSpan> = emptyList(),
) {
    internal fun toSnapshot(): DraftSnapshot {
        val firstNl = text.indexOf('\n')
        val title = if (firstNl < 0) text else text.substring(0, firstNl)
        val body = if (firstNl < 0) "" else text.substring(firstNl + 1)
        val hasSpans = spans.isNotEmpty() && spans.sumOf { it.length } == text.length
        return DraftSnapshot(
            title = title,
            body = body,
            selectionStart = body.length,
            selectionEnd = body.length,
            spans = if (hasSpans) {
                spans
            } else if (text.isEmpty()) {
                emptyList()
            } else {
                listOf(SavedSpan(text.length, NoteBodyEditor.ParagraphStyle.BODY.name))
            },
            viewMode = false,
        )
    }

    companion object {
        /** Plain text, optionally with a separate subject that becomes the title line. */
        fun ofText(text: String, subject: String? = null): DraftPrefill {
            val body = text.replace("\r\n", "\n").trim('\n')
            val title = subject?.replace('\n', ' ')?.trim().orEmpty()
            val full = when {
                title.isEmpty() -> body
                body.isEmpty() -> title
                // Don't duplicate a subject the sender already put on the first line.
                body.lineSequence().first().trim() == title -> body
                else -> "$title\n$body"
            }
            return DraftPrefill(full)
        }

        internal fun of(text: String, spans: List<NoteBodyEditor.AttrSpan>) =
            DraftPrefill(text, spans.map { SavedSpan.of(it) })
    }
}
