package com.example.applenotes.data

import com.example.applenotes.client.NoteRecord
import com.example.applenotes.proto.NoteBodyEditor
import com.example.applenotes.proto.NoteProtoKind

/**
 * A note's body as the editor sees it. Separates "decoded cleanly (possibly
 * empty)" from "could not be decoded" — the two used to collapse into "",
 * which opened an undecodable note as a blank *editable* note.
 */
sealed interface NoteBody {
    /** Visible text (title line + body); null when nothing could be decoded. */
    val text: String?

    /** Attribute runs over [text], in full-body coordinates. */
    val spans: List<NoteBodyEditor.AttrSpan>

    /**
     * A cleanly decoded NOTE_STORE_PROTO body (which may legitimately be
     * empty), or a brand-new local draft with no body yet. Safe to edit.
     */
    data class Editable(
        override val text: String,
        override val spans: List<NoteBodyEditor.AttrSpan>,
    ) : NoteBody

    /**
     * Anything else. Shown read-only — no editing, formatting, saving or
     * auto-save — so we never write a body we don't understand back to
     * iCloud. [text] is a best-effort rendering, or null.
     */
    data class ReadOnly(
        val reason: ReadOnlyReason,
        override val text: String?,
        override val spans: List<NoteBodyEditor.AttrSpan> = emptyList(),
    ) : NoteBody
}

enum class ReadOnlyReason(val explanation: String) {
    /** NoteProtoKind.MERGABLE_DATA: Apple's newer body format. */
    MODERN_FORMAT(
        "This note uses Apple's newer note format, which this app can't edit yet. " +
            "It's open read-only so it can't be damaged.",
    ),

    /** NoteProtoKind.UNKNOWN: not a body shape we recognise at all. */
    UNRECOGNIZED_FORMAT(
        "This note's format wasn't recognised, so it's open read-only and may not display correctly.",
    ),

    /** Looks like NOTE_STORE_PROTO but its text couldn't be reconstructed. */
    DECODE_FAILED(
        "This note couldn't be fully decoded, so it's open read-only. " +
            "What's shown may not match the note on your other devices.",
    ),

    /** An existing note whose record has no TextDataEncrypted field. */
    MISSING_BODY(
        "This note has no body data this app can read, so it's open read-only.",
    ),
}

/**
 * Probe and decode [record]'s `TextDataEncrypted`.
 *
 * - Brand-new draft (recordChangeTag == null) without the field → editable, empty.
 * - Existing note without the field → read-only ([ReadOnlyReason.MISSING_BODY]).
 * - [NoteProtoKind.NOTE_STORE_PROTO] whose visible text decodes → editable.
 * - NOTE_STORE_PROTO whose substring walk fails → read-only, showing the raw
 *   `note_text` if available ([ReadOnlyReason.DECODE_FAILED]).
 * - [NoteProtoKind.MERGABLE_DATA] / [NoteProtoKind.UNKNOWN] → read-only.
 */
fun decodeNoteBody(record: NoteRecord): NoteBody {
    val b64 = record.stringField("TextDataEncrypted")
    if (b64 == null) {
        return if (record.recordChangeTag == null) {
            NoteBody.Editable("", emptyList())
        } else {
            NoteBody.ReadOnly(ReadOnlyReason.MISSING_BODY, text = null)
        }
    }
    val spans = runCatching { NoteBodyEditor.parseAttributeRunsFromBase64(b64) }.getOrNull().orEmpty()
    return when (NoteBodyEditor.probeBase64(b64)) {
        NoteProtoKind.NOTE_STORE_PROTO -> {
            val visible = runCatching { NoteBodyEditor.readVisibleTextFromBase64(b64) }.getOrNull()
            if (visible != null) {
                NoteBody.Editable(visible, spans)
            } else {
                val flat = runCatching { NoteBodyEditor.readTextFromBase64(b64) }.getOrNull()
                // The flat note_text includes tombstoned chars, so the
                // attribute runs don't line up with it; show it unstyled.
                NoteBody.ReadOnly(ReadOnlyReason.DECODE_FAILED, flat, emptyList())
            }
        }
        NoteProtoKind.MERGABLE_DATA -> NoteBody.ReadOnly(ReadOnlyReason.MODERN_FORMAT, text = null)
        NoteProtoKind.UNKNOWN -> NoteBody.ReadOnly(ReadOnlyReason.UNRECOGNIZED_FORMAT, text = null)
    }
}
