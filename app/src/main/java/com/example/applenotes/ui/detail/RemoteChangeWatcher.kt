package com.example.applenotes.ui.detail

import com.example.applenotes.client.NoteRecord
import com.example.applenotes.data.runCatchingCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "Updated on another device" indicator (issue #16): while a note is open
 * and its screen is visible, look the record up every [intervalMs] and
 * publish the server's newer version in [newer] when its `recordChangeTag`
 * differs from the one the editor shows. Read-only: it never reloads or
 * writes anything itself; the screen offers "Reload".
 *
 * Skips a round when offline, when there's nothing saved to compare against
 * (a draft), or while [watch]'s `paused` says so (a save, conflict or
 * delete in flight, whose own record update would look like a remote
 * change). A result is also dropped if the editor's record changed while the
 * lookup was in flight.
 */
internal class RemoteChangeWatcher(
    private val lookup: suspend (recordName: String) -> NoteRecord,
    private val online: StateFlow<Boolean>,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
) {
    private val _newer = MutableStateFlow<NoteRecord?>(null)

    /** The server's newer version of the open note, or null. */
    val newer: StateFlow<NoteRecord?> = _newer.asStateFlow()

    /** Poll until cancelled (run it only while the screen is visible). */
    suspend fun watch(current: () -> NoteRecord?, paused: () -> Boolean) {
        while (true) {
            delay(intervalMs)
            checkOnce(current, paused)
        }
    }

    /** One poll. Exposed for tests. */
    suspend fun checkOnce(current: () -> NoteRecord?, paused: () -> Boolean) {
        if (!online.value || paused()) return
        val shown = current() ?: return
        val tag = shown.recordChangeTag ?: return
        val fresh = runCatchingCancellable { lookup(shown.recordName) }.getOrNull() ?: return
        val now = current()
        if (now?.recordName != shown.recordName || now.recordChangeTag != tag || paused()) return
        val freshTag = fresh.recordChangeTag ?: return
        if (freshTag != tag && fresh.recordName == shown.recordName) _newer.value = fresh
    }

    /** Forget the newer version (the editor loaded a record, or the user dismissed the banner). */
    fun clear() {
        _newer.value = null
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 60_000L
    }
}
