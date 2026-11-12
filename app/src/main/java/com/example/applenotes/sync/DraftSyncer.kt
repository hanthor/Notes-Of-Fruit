package com.example.applenotes.sync

import com.example.applenotes.client.NoteRecord
import com.example.applenotes.client.NotesError
import com.example.applenotes.data.DraftSupersededException
import com.example.applenotes.data.EditConflictException
import com.example.applenotes.data.NoteBody
import com.example.applenotes.data.NotesRepository
import com.example.applenotes.data.decodeNoteBody
import com.example.applenotes.data.local.PendingDraft
import com.example.applenotes.log.AppLog
import com.example.applenotes.ui.detail.DraftSnapshot
import com.example.applenotes.ui.detail.hasRichFormatting
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.Json

private const val TAG = "DraftSync"

/**
 * Saves one unsent draft from the outbox through the same repository path
 * the editor uses (per-note lock, create-once mapping, CONFLICT three-way
 * merge). The logic of [DraftSyncWorker], kept free of WorkManager so it is
 * unit tested with fakes.
 *
 * Never resolves an overlapping remote edit in the background: the draft is
 * kept and flagged, and the editor asks the user next time the note opens.
 */
class DraftSyncer(private val repository: NotesRepository) {

    sealed interface Outcome {
        /** Saved, or nothing (left) to save. */
        data object Done : Outcome

        /** Overlapping remote edit: kept and flagged for the editor. */
        data object Conflict : Outcome

        /** Transient (offline, throttled, session not available): try again later. */
        data object Retry : Outcome

        /** Can't be saved in the background (unreadable or read-only); kept in the outbox. */
        data class Failed(val reason: String) : Outcome
    }

    suspend fun sync(key: String): Outcome {
        // The editor (or a previous run) may change the row while we save;
        // re-read and go again a couple of times rather than write stale text.
        repeat(MAX_PASSES) {
            val draft = repository.pendingDraft(key) ?: return Outcome.Done
            if (draft.conflict) return Outcome.Done
            val outcome = try {
                saveOnce(draft)
            } catch (e: DraftSupersededException) {
                null
            }
            if (outcome != null) return outcome
        }
        return Outcome.Retry
    }

    private suspend fun saveOnce(draft: PendingDraft): Outcome {
        val snapshot = DraftSnapshot.decodeOrNull(draft.snapshotJson)
            ?: return Outcome.Failed("unreadable draft")
        val spans = snapshot.spans.map { it.toAttrSpan() }
        val base = draft.baseRecord ?: NoteRecord(draft.key, "Note", null, emptyMap())
        val folderRef = draft.folderRefJson?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }
        if (base.recordChangeTag == null && folderRef == null) return Outcome.Failed("new note without a folder")
        if (base.recordChangeTag != null && decodeNoteBody(base) !is NoteBody.Editable) {
            return Outcome.Failed("read-only note")
        }
        if (!repository.online.value) return Outcome.Retry
        return try {
            repository.prepareForBackgroundWork()
            val saved = repository.saveNote(
                base,
                folderRef,
                draft.fullText,
                isPureAppend = false,
                newSpans = spans,
                requirePendingDraft = draft.key,
            )
            if (base.recordChangeTag == null && hasRichFormatting(draft.fullText, spans)) {
                // createNote writes plain text; apply the formatting with one
                // modify of the created note, kept in the outbox until done.
                val followUp = draft.copy(key = saved.recordName, baseRecord = saved, folderRefJson = null)
                repository.persistDraft(followUp).join()
                repository.saveNote(
                    saved, null, draft.fullText, isPureAppend = false, newSpans = spans,
                    requirePendingDraft = followUp.key,
                )
            }
            AppLog.i(TAG) { "saved unsent draft ${draft.key}" }
            Outcome.Done
        } catch (e: CancellationException) {
            throw e
        } catch (e: DraftSupersededException) {
            throw e
        } catch (e: EditConflictException) {
            AppLog.i(TAG) { "unsent draft ${draft.key} overlaps a remote edit; keeping it for the editor" }
            repository.markDraftConflict(draft.key)
            Outcome.Conflict
        } catch (e: NotesError.Offline) {
            Outcome.Retry
        } catch (e: NotesError.Throttled) {
            Outcome.Retry
        } catch (e: NotesError.Conflict) {
            // Still changing after the merge retries: later.
            Outcome.Retry
        } catch (e: NotesError.AuthExpired) {
            // Comes back once the user signs in again.
            Outcome.Retry
        } catch (e: Throwable) {
            AppLog.w(TAG, "background save of ${draft.key} failed", e)
            Outcome.Failed(e.javaClass.simpleName)
        }
    }

    private companion object {
        const val MAX_PASSES = 3
    }
}
