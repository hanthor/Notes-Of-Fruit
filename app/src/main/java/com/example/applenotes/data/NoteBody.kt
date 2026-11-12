package com.example.applenotes.data

import com.example.applenotes.client.NoteRecord
import com.example.applenotes.proto.NoteBodyEditor
import com.example.applenotes.proto.NoteProtoKind
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import androidx.annotation.StringRes
import com.example.applenotes.R
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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

/** Why a note is open read-only; [explanation] is the banner text (a string resource). */
enum class ReadOnlyReason(@param:StringRes val explanation: Int) {
    /** NoteProtoKind.MERGABLE_DATA: Apple's newer body format. */
    MODERN_FORMAT(R.string.read_only_modern_format),

    /** NoteProtoKind.UNKNOWN: not a body shape we recognise at all. */
    UNRECOGNIZED_FORMAT(R.string.read_only_unrecognized_format),

    /** Looks like NOTE_STORE_PROTO but its text couldn't be reconstructed. */
    DECODE_FAILED(R.string.read_only_decode_failed),

    /** An existing note whose record has no TextDataEncrypted field. */
    MISSING_BODY(R.string.read_only_missing_body),

    /** A password-protected ("locked") note; see [isLockedNote]. Never decrypted. */
    LOCKED(R.string.read_only_locked),
}

/**
 * Fields Apple's locked (password-protected) notes carry on the Note record,
 * per public reverse-engineering of the Notes database (e.g.
 * apple_cloud_notes_parser: ZCRYPTOINITIALIZATIONVECTOR, ZCRYPTOTAG,
 * ZCRYPTOSALT, ZCRYPTOITERATIONCOUNT, ZCRYPTOWRAPPEDKEY). That the CloudKit
 * record uses these names has NOT been verified against a real account.
 */
val LOCKED_NOTE_FIELDS = listOf(
    "CryptoInitializationVector",
    "CryptoTag",
    "CryptoSalt",
    "CryptoIterationCount",
    "CryptoWrappedKey",
)

/** Possible names of a locked note's password hint field (unverified, like [LOCKED_NOTE_FIELDS]). */
private val PASSWORD_HINT_FIELDS = listOf("PasswordHint", "PasswordHintEncrypted", "CryptoPasswordHint")

private fun JsonElement?.hasValue(): Boolean {
    val value = (this as? JsonObject)?.let { it["value"] } ?: this ?: return false
    return when (value) {
        is JsonNull -> false
        is JsonPrimitive -> value.content.isNotEmpty()
        is JsonArray -> value.isNotEmpty()
        is JsonObject -> value.isNotEmpty()
    }
}

/** True when [record] has any of the [LOCKED_NOTE_FIELDS] with a non-empty value. */
fun isLockedNote(record: NoteRecord): Boolean =
    LOCKED_NOTE_FIELDS.any { record.rawFields[it].hasValue() }

/**
 * A locked note's password hint, if a hint-like field is present and decodes
 * to text. `ENCRYPTED_*` CloudKit fields arrive as base64 of the plaintext
 * (see docs/protocol.md); plain STRING fields as-is.
 */
fun lockedNoteHint(record: NoteRecord): String? {
    for (name in PASSWORD_HINT_FIELDS) {
        val field = record.rawFields[name] as? JsonObject ?: continue
        val raw = (field["value"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
        val encrypted = (field["type"] as? JsonPrimitive)?.content?.contains("ENCRYPTED") == true ||
            name.endsWith("Encrypted")
        val text = if (encrypted) runCatching { Base64.decode(raw).decodeToString() }.getOrNull() else raw
        text?.trim()
            ?.takeIf { it.isNotEmpty() && it.none { c -> c.isISOControl() } }
            ?.let { return it.take(200) }
    }
    return null
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
 * - Checked first: a locked (password-protected) note → read-only
 *   ([ReadOnlyReason.LOCKED]), body untouched.
 */
fun decodeNoteBody(record: NoteRecord): NoteBody {
    // A locked note's body is encrypted with the note's password: never try
    // to decode (let alone decrypt) it.
    if (isLockedNote(record)) return NoteBody.ReadOnly(ReadOnlyReason.LOCKED, text = null)
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
