package com.example.applenotes.ui.detail

import com.example.applenotes.data.NotesRepository
import com.example.applenotes.data.local.PendingDraft
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Write the unsent draft to disk after this long without typing (issue #13). */
internal const val DRAFT_PERSIST_DELAY_MS = 1_000L

/** Save to iCloud after this long without typing, when online (issue #13). */
internal const val AUTOSAVE_DELAY_MS = 3_000L

/**
 * The editor's side of the outbox (issue #13): debounced writes of the
 * unsent draft to disk, the debounced network autosave, and handing the
 * draft to the background worker when the editor pauses or is left.
 *
 * The editor supplies [current] — the draft as it stands, or null when
 * there is nothing unsaved — and [autosave]; this class only does timing
 * and bookkeeping, so [NoteDetailViewModel] stays mostly as it was.
 */
internal class EditorOutbox(
    private val repository: NotesRepository,
    private val scope: CoroutineScope,
    private val persistDelayMs: Long,
    /** Null disables the network autosave (tests that count saves). */
    private val autosaveDelayMs: Long?,
    private val current: () -> PendingDraft?,
    private val autosave: () -> Unit,
) {
    private var persistJob: Job? = null
    private var autosaveJob: Job? = null

    /** Keys this editor wrote rows under, so it can drop them once iCloud has the edit. */
    private val written = mutableSetOf<String>()

    /**
     * While true (the check for an older unsent draft of the opened note is
     * still running) nothing is written, so that draft can't be overwritten
     * before the user decides whether to restore it.
     */
    var holdWrites: Boolean = false

    /** The user typed / formatted: restart both timers. */
    fun onEdited() {
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(persistDelayMs)
            writeNow()
        }
        autosaveJob?.cancel()
        val wait = autosaveDelayMs ?: return
        autosaveJob = scope.launch {
            delay(wait)
            autosave()
        }
    }

    /** Write the draft now (before a save, on pause, on leaving); drops our row when nothing is unsaved. */
    fun writeNow(): Job? {
        persistJob?.cancel()
        if (holdWrites) return null
        val draft = current()
        if (draft == null) {
            val keys = written.toList()
            written.clear()
            if (keys.isEmpty()) return null
            return scope.launch { keys.forEach { repository.discardDraft(it) } }
        }
        written += draft.key
        return repository.persistDraft(draft)
    }

    /**
     * The editor's state is now saved, or was discarded: forget rows
     * under keys other than the one that [current] still needs, and
     * re-write that one (or drop it when nothing is unsaved).
     */
    fun afterSaveOrDiscard(alsoForget: Collection<String> = emptyList()) {
        val keep = if (holdWrites) null else current()?.key
        val drop = (written + alsoForget).filter { it != keep }.toSet()
        written.removeAll(drop)
        if (drop.isNotEmpty()) scope.launch { drop.forEach { repository.discardDraft(it) } }
        writeNow()
    }

    /** Pause / leave with unsaved changes: write it and let the background worker send it. */
    fun handOff() {
        autosaveJob?.cancel()
        val draft = if (holdWrites) null else current()
        writeNow()
        draft?.let { repository.scheduleDraftSync(it.key) }
    }

    fun cancelTimers() {
        persistJob?.cancel()
        autosaveJob?.cancel()
    }

    /** A different note is opened. */
    fun reset() {
        cancelTimers()
        written.clear()
        holdWrites = false
    }
}
