package com.example.applenotes.widget

import com.example.applenotes.client.NoteSummary
import com.example.applenotes.data.TRASH_RECORD_NAME

/**
 * What the "Recent notes" widget may show, decided without Android so it can
 * be unit-tested. Only ever built from the local cache: the widget never
 * touches the network.
 */
internal sealed interface WidgetContent {
    /** App lock is on: no titles, just the New note button. */
    data object Locked : WidgetContent

    /** Nobody is signed in (or they just signed out): nothing cached to show. */
    data object SignedOut : WidgetContent

    /** Up to [MAX_NOTES] most recently modified notes, title only. */
    data class Notes(val notes: List<Entry>) : WidgetContent

    /** [title] is null for a note without one; the widget shows "Untitled note". */
    data class Entry(val recordName: String, val title: String?)

    companion object {
        const val MAX_NOTES = 8

        /**
         * The lock wins over everything: while it is on, the widget must not
         * reveal even whether there are notes. Notes in Recently Deleted are
         * left out, as in the app's default list.
         */
        fun of(appLockOn: Boolean, signedIn: Boolean, notes: List<NoteSummary>): WidgetContent = when {
            appLockOn -> Locked
            !signedIn -> SignedOut
            else -> Notes(
                notes.asSequence()
                    .filter { !it.deleted && it.folderRecordName != TRASH_RECORD_NAME }
                    .sortedByDescending { it.modificationTimestampMs ?: 0L }
                    .take(MAX_NOTES)
                    .map { Entry(it.recordName, it.title?.trim()?.takeIf(String::isNotEmpty)?.take(MAX_TITLE)) }
                    .toList(),
            )
        }

        private const val MAX_TITLE = 80
    }
}
