package com.example.applenotes.integrations

import android.content.Context
import android.content.Intent
import com.example.applenotes.log.AppLog
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.example.applenotes.R

/**
 * Launcher shortcuts for notes: dynamic shortcuts for the most recently
 * opened notes, and "Pin to home screen" from the editor.
 *
 * Only the note *title* is published (the launcher shows it on long-press);
 * never a snippet or body. Each shortcut's intent goes to
 * [IntegrationEntryActivity] with the per-install [ShortcutToken].
 */
internal object NoteShortcuts {
    private const val TAG = "NotesShortcuts"
    private const val ID_PREFIX = "note:"
    const val MAX_RECENT = 4

    private fun shortcutId(recordName: String) = ID_PREFIX + recordName

    private fun build(context: Context, recordName: String, title: String?, rank: Int = 0): ShortcutInfoCompat {
        val label = title?.trim()?.takeIf { it.isNotEmpty() }?.take(40)
            ?: context.getString(R.string.shortcut_untitled_note)
        val intent = Intent(context, IntegrationEntryActivity::class.java)
            .setAction(EntryActions.OPEN_NOTE_SHORTCUT)
            .putExtra(EntryActions.EXTRA_RECORD_NAME, recordName)
            .putExtra(EntryActions.EXTRA_TOKEN, ShortcutToken.get(context))
        return ShortcutInfoCompat.Builder(context, shortcutId(recordName))
            .setShortLabel(label.take(25))
            .setLongLabel(label)
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_note))
            .setIntent(intent)
            .setRank(rank)
            .build()
    }

    /** The user opened a saved note: make it the first of the recent-note shortcuts. */
    fun reportOpened(context: Context, recordName: String, title: String?) {
        runCatching {
            val newest = build(context, recordName, title)
            val others = ShortcutManagerCompat.getDynamicShortcuts(context)
                .filter { it.id != newest.id && it.id.startsWith(ID_PREFIX) }
                .sortedBy { it.rank }
                .take(MAX_RECENT - 1)
                .mapIndexed { i, s ->
                    build(context, s.id.removePrefix(ID_PREFIX), s.longLabel?.toString() ?: s.shortLabel.toString(), rank = i + 1)
                }
            ShortcutManagerCompat.setDynamicShortcuts(context, listOf(newest) + others)
            // Keep any pinned copy's title current.
            ShortcutManagerCompat.updateShortcuts(context, listOf(newest))
            ShortcutManagerCompat.reportShortcutUsed(context, newest.id)
        }.onFailure { AppLog.w(TAG, "couldn't update recent-note shortcuts", it) }
    }

    /** Drop shortcuts for notes that no longer exist (deleted elsewhere / here). */
    fun prune(context: Context, existingRecordNames: Set<String>) {
        runCatching {
            val stale = ShortcutManagerCompat.getDynamicShortcuts(context)
                .map { it.id }
                .filter { it.startsWith(ID_PREFIX) && it.removePrefix(ID_PREFIX) !in existingRecordNames }
            if (stale.isNotEmpty()) {
                ShortcutManagerCompat.removeDynamicShortcuts(context, stale)
                ShortcutManagerCompat.disableShortcuts(context, stale, context.getString(R.string.shortcut_disabled_deleted))
            }
        }.onFailure { AppLog.w(TAG, "couldn't prune shortcuts", it) }
    }

    /** Sign-out: remove every note shortcut and invalidate pinned ones. */
    fun clearAll(context: Context) {
        runCatching {
            val ids = ShortcutManagerCompat.getDynamicShortcuts(context).map { it.id } +
                ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED).map { it.id }
            ShortcutManagerCompat.removeAllDynamicShortcuts(context)
            val noteIds = ids.filter { it.startsWith(ID_PREFIX) }.distinct()
            if (noteIds.isNotEmpty()) {
                ShortcutManagerCompat.disableShortcuts(context, noteIds, context.getString(R.string.shortcut_disabled_signed_out))
            }
        }.onFailure { AppLog.w(TAG, "couldn't clear shortcuts", it) }
        ShortcutToken.rotate(context)
    }

    /** Ask the launcher to pin a shortcut for this note. False if unsupported. */
    fun requestPin(context: Context, recordName: String, title: String?): Boolean {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return false
        return runCatching {
            ShortcutManagerCompat.requestPinShortcut(context, build(context, recordName, title), null)
        }.getOrDefault(false)
    }
}
