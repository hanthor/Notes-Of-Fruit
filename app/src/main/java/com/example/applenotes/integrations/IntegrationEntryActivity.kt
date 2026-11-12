package com.example.applenotes.integrations

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.example.applenotes.MainActivity

/**
 * Invisible trampoline for every system entry point that opens the editor:
 * share sheet (ACTION_SEND / SEND_MULTIPLE text), the "Add to Notes"
 * text-selection action (ACTION_PROCESS_TEXT), launcher shortcuts, the Quick
 * Settings tile and the note-taking role (ACTION_CREATE_NOTE).
 *
 * It is exported (see the manifest comment), so its input is untrusted: it
 * only ever posts a "new draft" or a token-checked "open note" request to
 * [PendingIncoming] and brings [MainActivity] to the front, where the user
 * sees the editor. It never saves, and never returns note content (PROCESS_TEXT
 * finishes without a result, i.e. read-only).
 */
class IntegrationEntryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = runCatching {
            parseEntryIntent(EntryIntent.from(intent), ShortcutToken.get(this))
        }.getOrNull()
        if (request != null) PendingIncoming.post(request)
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
        )
        finish()
    }
}
