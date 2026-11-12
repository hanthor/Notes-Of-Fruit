package com.example.applenotes.integrations

import com.example.applenotes.ui.detail.DraftPrefill
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Something another part of the system asked the app to show: the share
 * sheet, a text-selection action, a launcher shortcut, the Quick Settings
 * tile, the note-taking role, or a file import.
 *
 * Every request ends in the editor, in front of the user: a new *unsaved*
 * draft (nothing is written to iCloud until the user saves it through the
 * normal draft path), or an existing note opened for viewing. None of them
 * reads note content back to the caller.
 */
@Serializable
internal sealed interface IncomingRequest {
    /** Open a new draft prefilled with [prefill]. */
    @Serializable
    @SerialName("new_draft")
    data class NewDraft(val prefill: DraftPrefill) : IncomingRequest

    /** Open the existing note [recordName]. */
    @Serializable
    @SerialName("open_note")
    data class OpenNote(val recordName: String) : IncomingRequest

    /** Open the single non-deleted note titled exactly [title] (automation OPEN_NOTE). */
    @Serializable
    @SerialName("open_note_by_title")
    data class OpenNoteByTitle(val title: String) : IncomingRequest

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        fun encode(request: IncomingRequest): String = JSON.encodeToString(serializer(), request)

        fun decodeOrNull(s: String?): IncomingRequest? =
            s?.let { runCatching { JSON.decodeFromString(serializer(), it) }.getOrNull() }
    }
}
