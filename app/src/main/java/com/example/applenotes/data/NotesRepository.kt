package com.example.applenotes.data

import com.example.applenotes.log.AppLog
import com.example.applenotes.auth.DeviceIdentity
import com.example.applenotes.client.FolderSummary
import com.example.applenotes.client.NoteRecord
import com.example.applenotes.client.NoteSummary
import com.example.applenotes.client.NotesError
import com.example.applenotes.client.extractReferenceRecordName
import com.example.applenotes.proto.NoteAppender
import com.example.applenotes.proto.NoteBodyEditor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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

/** Retries of a throttled read before giving up (so at most 3 attempts). */
private const val MAX_THROTTLE_RETRIES = 2
private const val MIN_BACKOFF_MS = 500L
private const val MAX_BACKOFF_MS = 30_000L

/** Writes per save, counting re-tries after a CONFLICT (issue #4). */
internal const val MAX_SAVE_ATTEMPTS = 3

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
    connectivity: ConnectivityMonitor = AlwaysOnline,
    /** Backoff between retries of a throttled read; a parameter so tests can skip it. */
    private val backoff: suspend (Long) -> Unit = { delay(it) },
) {
    /** Device connectivity (see [ConnectivityMonitor]); drives the offline indicator. */
    val online: StateFlow<Boolean> = connectivity.online

    private val _sessionExpired = MutableStateFlow(false)

    /**
     * True once a request failed with [NotesError.AuthExpired] and a fresh
     * bootstrap from the cookie jar didn't fix it: the user has to sign in
     * again. Cleared by the next successful [signIn].
     */
    val sessionExpired: StateFlow<Boolean> = _sessionExpired.asStateFlow()

    private val sessionLock = Mutex()

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
            AppLog.i(TAG) { "device replica uuid=${DeviceIdentity.hexEncode(it)}" }
        }
    }

    private fun requireApi(): NotesApi = api ?: throw NotesError.AuthExpired("Not signed in.")

    /** See [AuthGateway.currentAuthToken]. */
    fun currentAuthToken(): String? = auth.currentAuthToken()

    /**
     * Run [block] against the current session, handling the two failures a
     * retry can fix (issue #11):
     *
     * - [NotesError.AuthExpired]: bootstrap a new session from the WebView
     *   cookie jar once and retry. If that bootstrap fails too, set
     *   [sessionExpired] and rethrow, so the UI sends the user to sign in.
     *   A request rejected for auth never reached the record, so retrying a
     *   write is safe.
     * - [NotesError.Throttled]: only for [idempotent] reads, wait
     *   `retryAfter` (bounded) and retry, up to [MAX_THROTTLE_RETRIES] times.
     *   Writes surface it to the user with a Retry action instead.
     */
    private suspend fun <T> withApi(idempotent: Boolean, block: suspend (NotesApi) -> T): T {
        var current = requireApi()
        var reauthed = false
        var throttled = 0
        while (true) {
            try {
                return block(current)
            } catch (e: NotesError.AuthExpired) {
                if (reauthed) {
                    _sessionExpired.value = true
                    throw e
                }
                reauthed = true
                current = reauthenticate(current)
            } catch (e: NotesError.Throttled) {
                if (!idempotent || throttled >= MAX_THROTTLE_RETRIES) throw e
                throttled++
                val wait = (e.retryAfterMs ?: (1_000L shl throttled)).coerceIn(MIN_BACKOFF_MS, MAX_BACKOFF_MS)
                AppLog.w(TAG, "throttled; retrying read in ${wait}ms (attempt $throttled)")
                backoff(wait)
            }
        }
    }

    /**
     * Replace [failed] with a freshly bootstrapped session. Concurrent callers
     * that all saw the same expired session share one bootstrap.
     */
    private suspend fun reauthenticate(failed: NotesApi): NotesApi = sessionLock.withLock {
        api?.takeIf { it !== failed }?.let { return@withLock it }
        AppLog.i(TAG) { "session expired; re-bootstrapping from the cookie jar" }
        val fresh = try {
            auth.openSession()
        } catch (e: NotesError.AuthExpired) {
            _sessionExpired.value = true
            throw e
        }
        api = fresh
        fresh
    }

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
            AppLog.w(TAG, "lookupFolders failed; falling back to ID-only list", it)
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
        _sessionExpired.value = false
        _notes.value = notes
        _folders.value = folders
    }

    /** Refetch the note list (and folder names, keeping the old ones if that lookup fails). */
    suspend fun refresh() {
        val notes = withApi(idempotent = true) { fetchNoteList(it) }
        val folderIds = notes.mapNotNull { it.folderRecordName }.toSet()
        val folders = runCatchingCancellable { withApi(idempotent = true) { it.lookupFolders(folderIds) } }
            .getOrElse { _folders.value }
        _notes.value = notes
        _folders.value = folders
    }

    fun signOut() {
        auth.clearLogin()
        api = null
        _notes.value = emptyList()
        _folders.value = emptyList()
    }

    suspend fun lookupNote(recordName: String): NoteRecord =
        withApi(idempotent = true) { it.lookupNote(recordName) }

    suspend fun fetchAssetBytes(downloadUrl: String): ByteArray? =
        withApi(idempotent = true) { it.fetchAssetBytes(downloadUrl) }

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
        val sampleRecord = lookupNote(sample.recordName)
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
        withApi(idempotent = false) { client ->
            doSaveNote(client, record, pendingFolderRef, newBody, isPureAppend, newSpans)
        }
    }.await()

    private suspend fun doSaveNote(
        client: NotesApi,
        record: NoteRecord,
        pendingFolderRef: JsonElement?,
        newBody: String,
        isPureAppend: Boolean,
        newSpans: List<NoteBodyEditor.AttrSpan>?,
    ): NoteRecord {
        val uuid = replicaUuid()
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
            AppLog.v(TAG) {
                "save (draft→createNote): title='${createTitle.take(30)}' " +
                    "body.len=${createBody.length} snippet='${newSnippet.take(30)}'"
            }
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
            updated = modifyWithRebase(client, uuid, record, newBody, isPureAppend, newSpans)
        }
        // The editor re-decodes the returned record. If CloudKit didn't echo
        // the body field, fetch it so a just-saved note doesn't look body-less
        // (and therefore read-only).
        if (updated.stringField("TextDataEncrypted") == null) {
            runCatchingCancellable { client.lookupNote(updated.recordName) }
                .getOrNull()
                ?.takeIf { it.stringField("TextDataEncrypted") != null }
                ?.let { return it }
        }
        return updated
    }

    /**
     * Write [newBody] over [record] (the version the user started editing),
     * re-applying the edit to the server's current version on CONFLICT
     * instead of overwriting it (issue #4):
     *
     * 1. First attempt: the edit spliced onto [record]'s proto, with its tag.
     * 2. On [NotesError.Conflict]: look the note up again and three-way merge
     *    the text ([TextMerge]: base = [record]'s text, local = [newBody],
     *    remote = the fresh text). Clean → splice the merged text onto the
     *    FRESH proto ([NoteRebase.rebaseBody], so the other device's char
     *    IDs survive), with merged formatting ([NoteRebase.mergeSpans]) and
     *    the fresh record's tag and ReplicaIDToNotesVersionDataEncrypted.
     *    Overlapping edits → [EditConflictException] and nothing is written.
     * 3. At most [MAX_SAVE_ATTEMPTS] writes, so a note that keeps changing
     *    can't loop; the last Conflict is rethrown.
     *
     * The common post-create case (Mac's notesync bumps the tag without
     * touching the text) is base == remote, which merges trivially.
     */
    private suspend fun modifyWithRebase(
        client: NotesApi,
        uuid: ByteArray,
        record: NoteRecord,
        newBody: String,
        isPureAppend: Boolean,
        newSpans: List<NoteBodyEditor.AttrSpan>?,
    ): NoteRecord {
        val baseB64 = record.stringField("TextDataEncrypted")
            ?: error("No body field on this note.")
        val baseText = NoteBodyEditor.readVisibleTextFromBase64(baseB64)
            ?: NoteBodyEditor.readTextFromBase64(baseB64).orEmpty()
        val now = clock() / 1000L

        // Pure-append edits go through the cheap APPEND path (one new
        // substring at the sentinel slot). Anything else (replace / insert /
        // delete in the middle) goes through the splice path, which mirrors
        // iCloud.com's pattern: slot promotion + split-and-tombstone + fresh
        // ts on tombstones + new chunks for inserts.
        var target = record
        var targetText = baseText
        var text = newBody
        var b64 = if (isPureAppend && newSpans == null) {
            val appended = newBody.removePrefix(baseText)
            require(appended.isNotEmpty()) { "Pure-append marked but no new chars" }
            NoteAppender.appendBase64(baseB64, uuid, appended, now)
        } else {
            NoteAppender.setBodyBase64(baseB64, uuid, newBody, now, newSpans)
        }
        var attempt = 1
        while (true) {
            val (oldTitle, oldSnippet) = computeTitleAndSnippet(targetText)
            val (newTitle, newSnippet) = computeTitleAndSnippet(text)
            AppLog.v(TAG) {
                "save[a$attempt]: title '${oldTitle.take(30)}'->'${newTitle.take(30)}' " +
                    "snippet '${oldSnippet.take(30)}'->'${newSnippet.take(30)}'"
            }
            val conflict = try {
                val updated = client.modifyNoteBody(
                    target.recordName,
                    target.recordChangeTag ?: error("No change tag on ${target.recordName}"),
                    b64,
                    newTitle = newTitle.takeIf { it != oldTitle },
                    newSnippet = newSnippet.takeIf { it != oldSnippet },
                    // ReplicaIDToNotesVersionDataEncrypted must be passed through
                    // unchanged (from the record the tag belongs to): Mac uses
                    // changes to it as its cache-invalidation signal and
                    // duplicates the body without it (see README).
                    replicaVersionPassThroughB64 = target.stringField("ReplicaIDToNotesVersionDataEncrypted"),
                )
                patchSummary(record.recordName, newTitle, newSnippet)
                return updated
            } catch (e: NotesError.Conflict) {
                e
            }
            if (attempt >= MAX_SAVE_ATTEMPTS) {
                AppLog.w(TAG, "save: still conflicting after $attempt attempts; giving up")
                throw conflict
            }
            attempt++
            val fresh = conflict.fresh ?: client.lookupNote(record.recordName)
            val freshB64 = fresh.stringField("TextDataEncrypted")
                ?: throw EditConflictException(fresh, remoteText = "")
            val remoteBody = decodeNoteBody(fresh)
            if (remoteBody !is NoteBody.Editable) {
                // The other device turned it into something we can't splice
                // (e.g. the newer note format): never write over that.
                throw EditConflictException(fresh, remoteBody.text.orEmpty())
            }
            val remoteText = remoteBody.text
            val merged = TextMerge.merge(baseText, newBody, remoteText)
            if (merged !is TextMerge.Clean) {
                AppLog.i(TAG) { "save: edits overlap a remote change; not writing" }
                throw EditConflictException(fresh, remoteText)
            }
            val mergedSpans = newSpans?.let {
                NoteRebase.mergeSpans(
                    merged,
                    baseText = baseText,
                    baseSpans = NoteBodyEditor.parseAttributeRunsFromBase64(baseB64),
                    localText = newBody,
                    localSpans = it,
                    remoteText = remoteText,
                    remoteSpans = remoteBody.spans,
                )
            }
            if (merged.text == remoteText && (mergedSpans == null || mergedSpans == remoteBody.spans)) {
                // The other device already made exactly this change.
                AppLog.i(TAG) { "save: remote already has these edits; nothing to write" }
                return fresh
            }
            AppLog.i(TAG) { "save: CONFLICT; re-applying the edit to the fresh record (attempt $attempt)" }
            b64 = NoteRebase.rebaseBody(freshB64, uuid, merged, mergedSpans, clock() / 1000L)
            target = fresh
            targetText = remoteText
            text = merged.text
        }
    }

    /**
     * Patch the cached list summary so back-navigation shows the updated
     * title / snippet without a refetch.
     */
    private fun patchSummary(recordName: String, title: String, snippet: String) {
        _notes.update { list ->
            list.map { note ->
                if (note.recordName == recordName) {
                    note.copy(title = title, snippet = snippet, modificationTimestampMs = clock())
                } else {
                    note
                }
            }
        }
    }

    /**
     * Delete an existing (already-saved) note and drop it from the cache.
     * Runs in [externalScope]. Drafts never reach here — they are discarded
     * locally by the caller.
     */
    suspend fun deleteNote(record: NoteRecord) {
        val tag = record.recordChangeTag ?: error("Cannot delete a note that was never saved.")
        externalScope.async {
            withApi(idempotent = false) { it.deleteNote(record.recordName, tag) }
        }.await()
        _notes.update { list -> list.filter { it.recordName != record.recordName } }
    }
}
