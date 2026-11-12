package com.example.applenotes.data.local

import com.example.applenotes.client.FolderSummary
import com.example.applenotes.client.NoteRecord
import com.example.applenotes.client.NoteSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * On-device storage for the note cache (issue #12) and the unsent-draft
 * outbox (issue #13).
 *
 * Production uses [RoomNotesStore]; unit tests (and anything that doesn't
 * want a database) use [InMemoryNotesStore]. The repository only talks to
 * this interface, so its sync / draft logic is tested on the JVM without
 * Robolectric.
 *
 * Everything here belongs to the signed-in account and is wiped by
 * [clearAll] on sign-out. The database lives in the app's private storage,
 * which backup and device transfer already exclude (see res/xml).
 */
interface NotesStore {
    // ----- Note list cache -----
    suspend fun loadSummaries(): List<NoteSummary>
    suspend fun upsertSummaries(notes: Collection<NoteSummary>)
    suspend fun deleteSummaries(recordNames: Collection<String>)

    // ----- Folders (kept even when they hold no note) -----
    suspend fun loadFolders(): List<FolderSummary>
    suspend fun upsertFolders(folders: Collection<FolderSummary>)
    suspend fun deleteFolders(recordNames: Collection<String>)

    // ----- Small key/value state (sync token, account, last full sync) -----
    suspend fun getMeta(key: String): String?
    suspend fun setMeta(key: String, value: String?)

    // ----- Bodies of notes the user opened (offline reading) -----
    suspend fun getBody(recordName: String): NoteRecord?
    suspend fun putBody(record: NoteRecord, visibleText: String?, nowMs: Long)
    suspend fun deleteBodies(recordNames: Collection<String>)

    /** recordNames of cached bodies whose visible text contains [query] (case-insensitive). */
    suspend fun searchBodies(query: String, limit: Int): List<String>

    // ----- Unsent drafts (the outbox) -----
    suspend fun getDraft(key: String): PendingDraft?
    suspend fun putDraft(draft: PendingDraft)
    suspend fun deleteDraft(key: String)
    suspend fun allDrafts(): List<PendingDraft>
    fun observeDrafts(): Flow<List<PendingDraft>>

    /**
     * Local draft id ("draft-<uuid>") → the CloudKit recordName chosen for
     * the note. Written *before* the create is sent ([CreatedDraft.confirmed]
     * false), so a retry after a lost response sends the same name and can't
     * duplicate the note, and confirmed with the record createNote returned
     * the moment it returns, so a second save of the same draft (the app and
     * the background worker racing, a retry) modifies that note instead.
     */
    suspend fun createdDraft(draftId: String): CreatedDraft?
    suspend fun putCreatedDraft(entry: CreatedDraft)

    /** The recordName a draft was created as, once the create is confirmed. */
    suspend fun createdRecordName(draftId: String): String? =
        createdDraft(draftId)?.takeIf { it.confirmed }?.recordName

    /** Sign-out: forget everything (cache, drafts, sync state). */
    suspend fun clearAll()
}

/**
 * An edit that has not been confirmed by iCloud yet.
 *
 * [key] is the recordName the editor had when the draft was written: the
 * CloudKit recordName of an existing note, or the local "draft-<uuid>" of a
 * note that doesn't exist on the server yet.
 */
data class PendingDraft(
    val key: String,
    /**
     * The record the edit started from (its recordChangeTag and body proto),
     * so a save can splice onto it and detect, through CloudKit's oplock,
     * that the note changed meanwhile. Null for a never-created note.
     */
    val baseRecord: NoteRecord?,
    /** Folder CKReference (JSON) for a never-created note; null otherwise. */
    val folderRefJson: String?,
    /** The full visible text (title line + body) to save. */
    val fullText: String,
    /** [spansSignature] of the formatting to save, to tell two drafts apart. */
    val spansSignature: String,
    /** Opaque editor state (title, body, selection, spans) — ui.detail.DraftSnapshot JSON. */
    val snapshotJson: String,
    val updatedAtMs: Long,
    /** A background save found overlapping remote edits; resolved in the editor. */
    val conflict: Boolean = false,
) {
    val isNewNote: Boolean get() = baseRecord == null
}

/** See [NotesStore.createdDraft]. */
data class CreatedDraft(
    val draftId: String,
    val recordName: String,
    /** False while the create may or may not have reached iCloud. */
    val confirmed: Boolean,
    /**
     * The record as our create (or our last save through this mapping) left
     * it: the merge base for later saves of the draft, so changes made on
     * another device since are merged, not overwritten. Null for rows written
     * before this was stored.
     */
    val record: NoteRecord? = null,
)

/** Keys for [NotesStore.getMeta]. */
object StoreMeta {
    const val SYNC_TOKEN = "zoneSyncToken"
    const val LAST_FULL_SYNC_MS = "lastFullSyncMs"
    const val ACCOUNT = "account"
}

/** In-memory [NotesStore] for tests and for running without a database. */
class InMemoryNotesStore : NotesStore {
    private val lock = Mutex()
    private val summaries = linkedMapOf<String, NoteSummary>()
    private val folders = linkedMapOf<String, FolderSummary>()
    private val meta = mutableMapOf<String, String>()
    private val bodies = linkedMapOf<String, Pair<NoteRecord, String?>>()
    private val created = mutableMapOf<String, CreatedDraft>()
    private val drafts = MutableStateFlow<Map<String, PendingDraft>>(emptyMap())

    /** Test hook: how many bodies are cached. */
    val bodyCount: Int get() = bodies.size

    override suspend fun loadSummaries() = lock.withLock { summaries.values.toList() }
    override suspend fun upsertSummaries(notes: Collection<NoteSummary>) = lock.withLock {
        notes.forEach { summaries[it.recordName] = it }
    }
    override suspend fun deleteSummaries(recordNames: Collection<String>) = lock.withLock {
        recordNames.forEach { summaries.remove(it) }
    }

    override suspend fun loadFolders() = lock.withLock { folders.values.toList() }
    override suspend fun upsertFolders(folders: Collection<FolderSummary>) = lock.withLock {
        folders.forEach { this.folders[it.recordName] = it }
    }
    override suspend fun deleteFolders(recordNames: Collection<String>) = lock.withLock {
        recordNames.forEach { folders.remove(it) }
    }

    override suspend fun getMeta(key: String) = lock.withLock { meta[key] }
    override suspend fun setMeta(key: String, value: String?) {
        lock.withLock { if (value == null) meta.remove(key) else meta[key] = value }
    }

    override suspend fun getBody(recordName: String) = lock.withLock { bodies[recordName]?.first }
    override suspend fun putBody(record: NoteRecord, visibleText: String?, nowMs: Long) = lock.withLock {
        bodies.remove(record.recordName)
        bodies[record.recordName] = record to visibleText
        while (bodies.size > MAX_CACHED_BODIES) bodies.remove(bodies.keys.first())
    }
    override suspend fun deleteBodies(recordNames: Collection<String>) = lock.withLock {
        recordNames.forEach { bodies.remove(it) }
    }
    override suspend fun searchBodies(query: String, limit: Int) = lock.withLock {
        bodies.filter { it.value.second?.contains(query, ignoreCase = true) == true }.keys.take(limit)
    }

    override suspend fun getDraft(key: String) = drafts.value[key]
    override suspend fun putDraft(draft: PendingDraft) = drafts.update { it + (draft.key to draft) }
    override suspend fun deleteDraft(key: String) = drafts.update { it - key }
    override suspend fun allDrafts() = drafts.value.values.toList()
    override fun observeDrafts(): Flow<List<PendingDraft>> = drafts.map { it.values.toList() }

    override suspend fun createdDraft(draftId: String) = lock.withLock { created[draftId] }
    override suspend fun putCreatedDraft(entry: CreatedDraft) = lock.withLock {
        created[entry.draftId] = entry
    }

    override suspend fun clearAll() {
        lock.withLock {
            summaries.clear()
            folders.clear()
            meta.clear()
            bodies.clear()
            created.clear()
        }
        drafts.value = emptyMap()
    }
}

/** At most this many opened-note bodies are kept for offline reading (least recently opened go first). */
const val MAX_CACHED_BODIES = 300
