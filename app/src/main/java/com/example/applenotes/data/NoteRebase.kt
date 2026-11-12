package com.example.applenotes.data

import com.example.applenotes.client.NoteRecord
import com.example.applenotes.proto.NoteAppender
import com.example.applenotes.proto.NoteBodyEditor
import com.example.applenotes.proto.NoteBodyEditor.AttrSpan

/**
 * The user's edits overlap a change made on another device since they
 * started editing ([TextMerge] found a conflict). Nothing was written.
 * [fresh] is the server's current record and [remoteText] its visible text.
 */
class EditConflictException(
    val fresh: NoteRecord,
    val remoteText: String,
) : Exception("Edits overlap a change made on another device")

/**
 * Re-applying a user's edit on top of a newer server version of the note
 * after a CONFLICT (issue #4). See [TextMerge] for the text merge itself.
 */
internal object NoteRebase {

    /**
     * Heading of the block "Keep both" appends: the user's version goes
     * below the other device's text, clearly marked, so nothing is lost.
     */
    const val KEEP_BOTH_SEPARATOR = "--- Conflicting copy from Android ---"

    /** Stand-in for an attachment in the appended copy (see [keepBoth]). */
    const val ATTACHMENT_PLACEHOLDER = "[attachment]"

    /**
     * Formatting for [merged]'s text, char by char:
     *
     * - chars from a remote-only change keep the remote formatting;
     * - chars the user typed keep the user's formatting;
     * - chars present in all three versions get the user's formatting if
     *   the user changed it (it differs from base), otherwise the remote's,
     *   so a bold / heading / checkbox change on either side survives.
     *
     * Paragraph styles are stored per char too, so this is per char as
     * well; line-level merging means both sides never edit the same line's
     * text, which keeps mixed-style paragraphs rare.
     */
    fun mergeSpans(
        merged: TextMerge.Clean,
        baseText: String,
        baseSpans: List<AttrSpan>,
        localText: String,
        localSpans: List<AttrSpan>,
        remoteText: String,
        remoteSpans: List<AttrSpan>,
    ): List<AttrSpan> {
        val bp = NoteBodyEditor.expandSpansToChars(baseText, baseSpans)
        val lp = NoteBodyEditor.expandSpansToChars(localText, localSpans)
        val rp = NoteBodyEditor.expandSpansToChars(remoteText, remoteSpans)
        val out = Array(merged.text.length) { k ->
            val b = merged.baseIndex[k]
            val l = merged.localIndex[k]
            val r = merged.remoteIndex[k]
            when {
                l >= 0 && r >= 0 && b >= 0 -> if (lp[l] != bp[b]) lp[l] else rp[r]
                l >= 0 -> lp[l]
                r >= 0 -> rp[r]
                else -> AttrSpan(length = 1, style = NoteBodyEditor.ParagraphStyle.BODY)
            }
        }
        return NoteBodyEditor.compactCharsToSpans(out)
    }

    /**
     * New `TextDataEncrypted` for the merged text, spliced onto the FRESH
     * record's proto so the other device's char IDs, tombstones and replica
     * clocks are kept. Local changes are applied one at a time
     * ([TextMerge.Clean.steps]), so remote text between two local edits is
     * never tombstoned and re-inserted. Intermediate steps let the splice
     * carry runs over; the last one applies [mergedSpans] (null: the
     * caller had no explicit formatting, keep the runs the splice infers).
     */
    fun rebaseBody(
        freshB64: String,
        ourReplicaUuid: ByteArray,
        merged: TextMerge.Clean,
        mergedSpans: List<AttrSpan>?,
        nowEpochSec: Long,
    ): String {
        if (merged.steps.isEmpty()) {
            return NoteAppender.setBodyBase64(freshB64, ourReplicaUuid, merged.text, nowEpochSec, mergedSpans)
        }
        var cur = freshB64
        for ((i, step) in merged.steps.withIndex()) {
            val spans = if (i == merged.steps.lastIndex) mergedSpans else null
            cur = NoteAppender.setBodyBase64(cur, ourReplicaUuid, step, nowEpochSec, spans)
        }
        return cur
    }

    /**
     * "Keep both": [remoteText] unchanged, then a blank line, the separator
     * line and the user's whole version. Attachments in the user's copy
     * become [ATTACHMENT_PLACEHOLDER] text: a second U+FFFC pointing at the
     * same attachment record is not something we have seen Apple's clients
     * accept. The copy's title line is shown as body text, not as a second
     * title.
     */
    fun keepBoth(
        remoteText: String,
        remoteSpans: List<AttrSpan>,
        localText: String,
        localSpans: List<AttrSpan>,
    ): Pair<String, List<AttrSpan>> {
        val body = AttrSpan(length = 1, style = NoteBodyEditor.ParagraphStyle.BODY)
        val lp = NoteBodyEditor.expandSpansToChars(localText, localSpans)
        val copy = StringBuilder()
        val copySpans = ArrayList<AttrSpan>()
        for ((i, ch) in localText.withIndex()) {
            if (ch == '￼') {
                copy.append(ATTACHMENT_PLACEHOLDER)
                repeat(ATTACHMENT_PLACEHOLDER.length) { copySpans.add(body) }
            } else {
                copy.append(ch)
                val s = lp[i]
                copySpans.add(
                    s.copy(
                        style = if (s.style == NoteBodyEditor.ParagraphStyle.TITLE) {
                            NoteBodyEditor.ParagraphStyle.BODY
                        } else {
                            s.style
                        },
                        isAttachment = false,
                        attachmentRecordName = null,
                        attachmentType = null,
                    ),
                )
            }
        }
        if (remoteText.isEmpty()) {
            return copy.toString() to NoteBodyEditor.compactCharsToSpans(copySpans.toTypedArray())
        }
        val glue = (if (remoteText.endsWith("\n")) "" else "\n") + "\n" + KEEP_BOTH_SEPARATOR + "\n"
        val text = remoteText + glue + copy
        val perChar = ArrayList<AttrSpan>(text.length)
        perChar.addAll(NoteBodyEditor.expandSpansToChars(remoteText, remoteSpans))
        repeat(glue.length) { perChar.add(body) }
        perChar.addAll(copySpans)
        return text to NoteBodyEditor.compactCharsToSpans(perChar.toTypedArray())
    }
}
