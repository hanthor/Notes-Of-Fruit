package com.example.applenotes.data

import android.util.Log
import com.example.applenotes.auth.DeviceIdentity
import com.example.applenotes.client.FolderSummary
import com.example.applenotes.client.NoteRecord
import com.example.applenotes.client.NoteSummary
import com.example.applenotes.client.extractReferenceRecordName
import com.example.applenotes.proto.NoteAppender
import com.example.applenotes.proto.NoteBodyEditor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement

private const val TAG = "AppleNotesUi"

/** Prefix of the local recordName given to a FAB-created note before its first save. */
internal const val DRAFT_RECORD_PREFIX = "draft-"

/**
 * Application-scoped owner of the iCloud session and the cached note list.
 *
 * Lives outside composition (see [AppGraph]) so rotation / dark-mode changes
 * don't re-bootstrap the session or refetch the list, and so one [NotesApi]
 * (one `AppleNotesClient`, one shared `HttpClient`) is used per session
 * instead of one per call.
 *
 * Writes ([saveNote], [deleteNote]) run in [externalScope] rather than the
 * caller's scope: a save started by "back" or by the lifecycle auto-save
 * keeps going even if the screen / ViewModel that asked for it goes away.
 */
class NotesRepository(
    private val auth: AuthGateway,
    private val replicaUuidLoader: suspend () -> ByteArray,
    private val externalScope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _notes = MutableStateFlow<List<NoteSummary>>(emptyList())

    /**
     * Cached note summaries (deleted=true filtered out, newest first). Patched
     * in place on save/delete so back-nav shows the new snippet without a
     * `fetchRecents` round trip.
     */
    val notes: StateFlow<List<NoteSummary>> = _notes.asStateFlow()

    private val _folders = MutableStateFlow<List<FolderSummary>>(emptyList())
    val folders: StateFlow<List<FolderSummary>> = _folders.asStateFlow()

    @Volatile
    private var api: NotesApi? = null

    private val uuidLock = Mutex()
    private var replicaUuid: ByteArray? = null

    val isSignedIn: Boolean get() = api != null

    fun hasSavedLogin(): Boolean = auth.hasSavedLogin()

    /**
     * Our stable per-device replica UUID. Same UUID for every note we ever
     * edit on this install; getting a fresh one every session would burn
     * through Apple's per-note replica cap.
     */
    suspend fun replicaUuid(): ByteArray = uuidLock.withLock {
        replicaUuid ?: replicaUuidLoader().also {
            replicaUuid = it
            Log.i(TAG, "device replica uuid=${DeviceIdentity.hexEncode(it)}")
        }
    }

    private fun requireApi(): NotesApi = api ?: error("Not signed in.")

    private suspend fun fetchNoteList(api: NotesApi): List<NoteSummary> =
        api.fetchRecents(limit = 1000)
            .filter { !it.deleted }
            .sortedByDescending { it.modificationTimestampMs ?: 0L }

    /** Bootstrap the CloudKit session from the cookie jar and load the list. Throws on failure. */
    suspend fun signIn() {
        val newApi = auth.openSession()
        val notes = fetchNoteList(newApi)
        val folderIds = notes.mapNotNull { it.folderRecordName }.toSet()
        val folders = runCatching { newApi.lookupFolders(folderIds) }.getOrElse {
            Log.w(TAG, "lookupFolders failed; falling back to ID-only list", it)
            folderIds.map { rn ->
                FolderSummary(
                    recordName = rn,
                    displayName = null,
                    isTrash = rn == TRASH_RECORD_NAME,
                    isDefault = rn == DEFAULT_FOLDER_RECORD_NAME,
                )
            }
        }
        api = newApi
        _notes.value = notes
        _folders.value = folders
    }

    /** Refetch the note list (and folder names, keeping the old ones if that lookup fails). */
    suspend fun refresh() {
        val api = requireApi()
        val notes = fetchNoteList(api)
        val folderIds = notes.mapNotNull { it.folderRecordName }.toSet()
        val folders = runCatching { api.lookupFolders(folderIds) }.getOrElse { _folders.value }
        _notes.value = notes
        _folders.value = folders
    }

    fun signOut() {
        auth.clearLogin()
        api = null
        _notes.value = emptyList()
        _folders.value = emptyList()
    }

    suspend fun lookupNote(recordName: String): NoteRecord = requireApi().lookupNote(recordName)

    suspend fun fetchAssetBytes(downloadUrl: String): ByteArray? = requireApi().fetchAssetBytes(downloadUrl)

    /**
     * Grab a Folder CKReference from any existing note in the user-visible
     * set. We need this BEFORE the user types anything because createNote
     * requires a folder ref. Prefer the currently-selected folder so the new
     * note lands where the user is browsing.
     */
    suspend fun folderRefForNewNote(selectedFolder: String?): JsonElement {
        val notes = _notes.value
        val sample = notes.firstOrNull { it.folderRecordName == selectedFolder }
            ?: notes.firstOrNull { it.folderRecordName != TRASH_RECORD_NAME }
            ?: error("No existing note to copy folder reference from")
        val sampleRecord = requireApi().lookupNote(sample.recordName)
        return sampleRecord.rawFields["Folder"]
            ?: error("Sample note has no Folder field")
    }

    /**
     * Persist [newBody] for [record]. A record with `recordChangeTag == null`
     * is a local draft and is created (see below); anything else goes through
     * the incremental modify path. Returns the server's updated record and
     * patches [notes] so the list reflects the save.
     *
     * Runs in [externalScope]: cancelling the caller does not cancel the write.
     */
    suspend fun saveNote(
        record: NoteRecord,
        pendingFolderRef: JsonElement?,
        newBody: String,
        isPureAppend: Boolean,
        newSpans: List<NoteBodyEditor.AttrSpan>?,
    ): NoteRecord = externalScope.async {
        doSaveNote(record, pendingFolderRef, newBody, isPureAppend, newSpans)
    }.await()

    private suspend fun doSaveNote(
        record: NoteRecord,
        pendingFolderRef: JsonElement?,
        newBody: String,
        isPureAppend: Boolean,
        newSpans: List<NoteBodyEditor.AttrSpan>?,
    ): NoteRecord {
        val uuid = replicaUuid()
        val client = requireApi()
        val (newTitle, newSnippet) = computeTitleAndSnippet(newBody)

        val updated: NoteRecord

        if (record.recordChangeTag == null) {
            // DRAFT: this is the first save for a FAB-created
            // note. Call createNote with the typed content in
            // one shot — matching iCloud.com's pattern. The
            // proto produced by NoteCreator already has the
            // full content as a single substring + matching
            // attribute_run, so Mac's notesync sees a
            // self-consistent record on first sight.
            val folderRef = pendingFolderRef
                ?: error("Draft has no folderRef — refresh first.")
            // Split fullBodyForSave back into title + body the
            // same way it was assembled upstream (see
            // NoteDetailViewModel.fullBodyForSave): the first '\n' is the
            // title/body separator. computeTitleAndSnippet's
            // `newTitle` is derived heuristically (first
            // non-blank line, possibly trimmed) and would
            // misbehave under leading whitespace / leading
            // blank lines, duplicating content into the proto.
            val firstNlIdx = newBody.indexOf('\n')
            val createTitle = if (firstNlIdx < 0) newBody else newBody.substring(0, firstNlIdx)
            val createBody = if (firstNlIdx < 0) "" else newBody.substring(firstNlIdx + 1)
            Log.i(
                TAG,
                "save (draft→createNote): title='${createTitle.take(30)}' " +
                    "body.len=${createBody.length} snippet='${newSnippet.take(30)}'",
            )
            updated = client.createNote(
                title = createTitle,
                body = createBody,
                folderRef = folderRef,
                ourReplicaUuid = uuid,
            )
            // Pre-pend a fresh summary into the cache so back-nav
            // shows the new note immediately without a refetch.
            val folderRecordName = extractReferenceRecordName(folderRef)
            val newSummary = NoteSummary(
                recordName = updated.recordName,
                title = newTitle,
                snippet = newSnippet,
                modificationTimestampMs = clock(),
                deleted = false,
                folderRecordName = folderRecordName,
            )
            _notes.update { listOf(newSummary) + it }
        } else {
            // EXISTING NOTE: incremental modify path.
            val tag = record.recordChangeTag
            val textB64 = record.stringField("TextDataEncrypted")
                ?: error("No body field on this note.")
            val originalBody = NoteBodyEditor.readVisibleTextFromBase64(textB64)
                ?: NoteBodyEditor.readTextFromBase64(textB64).orEmpty()
            val now = clock() / 1000L
            // Pure-append edits go through the cheap APPEND path
            // (one new substring at the sentinel slot). Anything
            // else (replace/insert/delete in the middle) goes
            // through the splice path which mirrors iCloud.com's
            // pattern: slot promotion + split-and-tombstone +
            // fresh ts on tombstones + new chunks for inserts.
            val newB64 = if (isPureAppend && newSpans == null) {
                val appended = newBody.removePrefix(originalBody)
                require(appended.isNotEmpty()) { "Pure-append marked but no new chars" }
                NoteAppender.appendBase64(textB64, uuid, appended, now)
            } else {
                NoteAppender.setBodyBase64(textB64, uuid, newBody, now, newSpans)
            }
            val (oldTitle, oldSnippet) = computeTitleAndSnippet(originalBody)
            Log.i(
                TAG,
                "save: title '${oldTitle.take(30)}'->'${newTitle.take(30)}' " +
                    "snippet '${oldSnippet.take(30)}'->'${newSnippet.take(30)}'",
            )
            updated = client.modifyNoteBody(
                record.recordName,
                tag,
                newB64,
                newTitle = newTitle.takeIf { it != oldTitle },
                newSnippet = newSnippet.takeIf { it != oldSnippet },
                // ReplicaIDToNotesVersionDataEncrypted must be passed through
                // unchanged — Mac uses changes to it as its cache-invalidation
                // signal and duplicates the body without it (see README).
                replicaVersionPassThroughB64 = record.stringField("ReplicaIDToNotesVersionDataEncrypted"),
            )
            // Patch the cached list summary for this note so when
            // the user navigates back they see the updated
            // snippet/title without a refetch.
            _notes.update { list ->
                list.map { note ->
                    if (note.recordName == record.recordName) {
                        note.copy(
                            title = newTitle,
                            snippet = newSnippet,
                            modificationTimestampMs = clock(),
                        )
                    } else note
                }
            }
        }
        return updated
    }

    /**
     * Delete an existing (already-saved) note and drop it from the cache.
     * Runs in [externalScope]. Drafts never reach here — they are discarded
     * locally by the caller.
     */
    suspend fun deleteNote(record: NoteRecord) {
        val tag = record.recordChangeTag ?: error("Cannot delete a note that was never saved.")
        val client = requireApi()
        externalScope.async { client.deleteNote(record.recordName, tag) }.await()
        _notes.update { list -> list.filter { it.recordName != record.recordName } }
    }
}
