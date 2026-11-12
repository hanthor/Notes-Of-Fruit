package com.example.applenotes.integrations

import android.content.Intent
import com.example.applenotes.ui.detail.DraftPrefill

/** Intent actions handled by [IntegrationEntryActivity] that aren't platform constants. */
internal object EntryActions {
    /** Static "New note" shortcut and the Quick Settings tile. */
    const val NEW_NOTE = "io.github.hanthor.notesoffruit.action.NEW_NOTE"

    /** Dynamic / pinned shortcut to one note. Needs [EXTRA_RECORD_NAME] and [EXTRA_TOKEN]. */
    const val OPEN_NOTE_SHORTCUT = "io.github.hanthor.notesoffruit.action.SHORTCUT_OPEN_NOTE"

    /** Intent.ACTION_CREATE_NOTE (API 34); spelled out so it compiles against any level. */
    const val CREATE_NOTE = "android.intent.action.CREATE_NOTE"

    const val EXTRA_RECORD_NAME = "record_name"
    const val EXTRA_TOKEN = "token"
}

/**
 * The parts of an incoming [Intent] the parser looks at, so parsing can be
 * unit-tested without Android's Intent (a stub in local tests).
 */
internal data class EntryIntent(
    val action: String?,
    val type: String? = null,
    val text: CharSequence? = null,
    val subject: CharSequence? = null,
    val textList: List<CharSequence>? = null,
    val processText: CharSequence? = null,
    val recordName: String? = null,
    val token: String? = null,
) {
    companion object {
        fun from(intent: Intent): EntryIntent = EntryIntent(
            action = intent.action,
            type = intent.type,
            text = runCatching { intent.getCharSequenceExtra(Intent.EXTRA_TEXT) }.getOrNull(),
            subject = runCatching { intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT) }.getOrNull(),
            textList = runCatching { intent.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT) }.getOrNull(),
            processText = runCatching { intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT) }.getOrNull(),
            recordName = runCatching { intent.getStringExtra(EntryActions.EXTRA_RECORD_NAME) }.getOrNull(),
            token = runCatching { intent.getStringExtra(EntryActions.EXTRA_TOKEN) }.getOrNull(),
        )
    }
}

/** A CloudKit recordName as we have seen them (UUIDs, "DefaultFolder-CloudKit", …). */
private val RECORD_NAME = Regex("[A-Za-z0-9._-]{1,200}")

internal fun isPlausibleRecordName(s: String?): Boolean = s != null && RECORD_NAME.matches(s)

/**
 * Map an incoming intent to what the UI should show, or null to ignore it.
 *
 * Every result opens the editor in front of the user; nothing is saved and
 * no note content is returned to the sender. Opening a specific note needs
 * the per-install shortcut [expectedToken], so other apps can't open notes
 * through this exported entry point (shortcuts we publish carry it).
 */
internal fun parseEntryIntent(e: EntryIntent, expectedToken: String): IncomingRequest? {
    return when (e.action) {
        Intent.ACTION_SEND -> {
            if (e.type?.startsWith("text/") != true) return null
            val text = e.text?.toString().orEmpty()
            val subject = e.subject?.toString()
            if (text.isBlank() && subject.isNullOrBlank()) return null
            IncomingRequest.NewDraft(DraftPrefill.ofText(text, subject))
        }
        Intent.ACTION_SEND_MULTIPLE -> {
            if (e.type?.startsWith("text/") != true) return null
            val parts = e.textList.orEmpty().map { it.toString() }.filter { it.isNotBlank() }
            val subject = e.subject?.toString()
            if (parts.isEmpty() && subject.isNullOrBlank()) return null
            IncomingRequest.NewDraft(DraftPrefill.ofText(parts.joinToString("\n\n"), subject))
        }
        Intent.ACTION_PROCESS_TEXT -> {
            val text = e.processText?.toString().orEmpty()
            if (text.isBlank()) return null
            IncomingRequest.NewDraft(DraftPrefill.ofText(text))
        }
        EntryActions.CREATE_NOTE, EntryActions.NEW_NOTE ->
            IncomingRequest.NewDraft(DraftPrefill(""))
        EntryActions.OPEN_NOTE_SHORTCUT -> {
            val rn = e.recordName
            if (!isPlausibleRecordName(rn) || expectedToken.isEmpty() || e.token != expectedToken) return null
            IncomingRequest.OpenNote(rn!!)
        }
        else -> null
    }
}
