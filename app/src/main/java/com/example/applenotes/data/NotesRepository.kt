package com.example.applenotes.data

import com.example.applenotes.log.AppLog
import com.example.applenotes.auth.DeviceIdentity
import com.example.applenotes.client.FolderSummary
import com.example.applenotes.client.NoteRecord
import com.example.applenotes.client.NoteSummary
import com.example.applenotes.client.ZoneChanges
import com.example.applenotes.client.NotesError
import com.example.applenotes.client.extractReferenceRecordName
import com.example.applenotes.proto.NoteAppender
import com.example.applenotes.proto.NoteBodyEditor
import com.example.applenotes.client.newNoteRecordName
import com.example.applenotes.data.local.CreatedDraft
import com.example.applenotes.data.local.InMemoryNotesStore
import com.example.applenotes.data.local.NotesStore
import com.example.applenotes.data.local.PendingDraft
import com.example.applenotes.data.local.StoreMeta
import com.example.applenotes.data.local.spansSignature
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    /** On-device cache and unsent-draft outbox (issues #12, #13). */
    private val store: NotesStore = InMemoryNotesStore(),
    /** Runs background saves of unsent drafts (WorkManager in the app). */
    private val draftScheduler: DraftScheduler = DraftScheduler.None,
    /** Also run on sign-out / account change: other on-device copies of note content (attachment cache). */
    private val clearOtherLocalData: suspend () -> Unit = {},
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

    private val _syncing = MutableStateFlow(false)

    /** True while the list is being refreshed from iCloud (the "Updating…" indicator). */
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _pendingDrafts = MutableStateFlow<List<PendingDraft>>(emptyList())

    /** Edits not yet confirmed by iCloud (the outbox), oldest first. */
    val pendingDrafts: StateFlow<List<PendingDraft>> = _pendingDrafts.asStateFlow()

    /** When the last sync finished successfully (epoch ms), for throttling refresh-on-resume. */
    @Volatile
    var lastSyncAtMs: Long? = null
        private set

    /**
     * Bumped on sign-out, so a sync or draft write still in flight for the
     * previous session can tell it must not touch the (cleared) cache.
     */
    @Volatile
    private var generation = 0

    @Volatile
    private var cacheLoaded = false

    /**
     * The app was launched with a saved login and is showing the cached list
     * before the session is up: requests may open the session lazily (see
     * [currentApi]). Cleared by [signOut].
     */
    @Volatile
    private var sessionRestorable = false

    private val syncLock = Any()
    private var syncJob: Job? = null
    private var syncReady: CompletableDeferred<Unit>? = null

    /** Notes the app created or saved while a full sync was running: never pruned by it. */
    private val touchedDuringSync: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** One save at a time per note (the editor, the background worker and automation share it). */
    private val saveLocks = ConcurrentHashMap<String, Mutex>()

    /** Serialises draft-row writes with the "clear it once saved" check. */
    private val draftLock = Mutex()

    init {
        externalScope.launch {
            runCatchingCancellable { store.observeDrafts().collect { _pendingDrafts.value = it } }
                .onFailure { AppLog.e(TAG, "observing unsent drafts failed", it) }
        }
    }

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

    /**
     * The session to use. After a launch from the cache the session is
     * opened lazily here (the background sign-in may have failed offline);
     * otherwise "not signed in" is [NotesError.AuthExpired].
     */
    private suspend fun currentApi(): NotesApi = api ?: sessionLock.withLock {
        api ?: run {
            if (!sessionRestorable) throw NotesError.AuthExpired("Not signed in.")
            AppLog.i(TAG) { "opening the session on demand" }
            val opened = try {
                auth.openSession()
            } catch (e: NotesError.AuthExpired) {
                _sessionExpired.value = true
                throw e
            }
            adoptAccount(opened)
            api = opened
            _sessionExpired.value = false
            opened
        }
    }

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
        var current = currentApi()
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

    // ----- Local cache and sync (issue #12) -----

    /**
     * Load the cached note list and folders into [notes] / [folders] (once
     * per process). Returns true when there is a cached list to show while
     * the session and the sync start in the background. Never touches the
     * network.
     */
    suspend fun loadCache(): Boolean {
        // Never read the store while a sign-out / account-switch clear is
        // still deleting it (we'd show, and re-cache, the old account's data).
        awaitPendingClear()
        if (!cacheLoaded) {
            val gen = generation
            val notes = runCatchingCancellable { store.loadSummaries() }
                .onFailure { AppLog.e(TAG, "reading the note cache failed", it) }
                .getOrDefault(emptyList())
            val folders = runCatchingCancellable { store.loadFolders() }.getOrDefault(emptyList())
            if (gen == generation) {
                if (_notes.value.isEmpty()) _notes.value = sortNotes(notes.filter { !it.deleted })
                if (_folders.value.isEmpty()) _folders.value = folders
                cacheLoaded = true
                if (auth.hasSavedLogin()) sessionRestorable = true
            }
            AppLog.i(TAG) { "cache: ${notes.size} notes, ${folders.size} folders" }
        }
        return _notes.value.isNotEmpty()
    }

    /**
     * Bootstrap the CloudKit session from the cookie jar and sync the list.
     * Returns once the list is usable: the incremental sync is done, or the
     * first page of a full refetch is in (the rest keeps paging into the
     * cache in the background; see [syncing]). Throws on failure.
     */
    suspend fun signIn() {
        val newApi = auth.openSession()
        adoptAccount(newApi)
        api = newApi
        sessionRestorable = true
        _sessionExpired.value = false
        loadCache()
        awaitSync()
    }

    /**
     * Sync the list with iCloud (see [signIn] for when it returns). A sync
     * already running is joined rather than started twice.
     */
    suspend fun refresh() {
        awaitSync()
    }

    private suspend fun awaitSync() {
        val ready = synchronized(syncLock) {
            syncReady?.takeIf { syncJob?.isActive == true } ?: startSync()
        }
        ready.await()
    }

    private fun startSync(): CompletableDeferred<Unit> {
        val ready = CompletableDeferred<Unit>()
        val gen = generation
        syncReady = ready
        syncJob = externalScope.launch {
            _syncing.value = true
            try {
                runSync(gen, ready)
                if (gen == generation) lastSyncAtMs = clock()
                ready.complete(Unit)
            } catch (e: CancellationException) {
                ready.completeExceptionally(e)
                throw e
            } catch (e: Throwable) {
                // Before "ready" the caller gets the error; after it (paging
                // in the background) the next refresh simply tries again.
                if (!ready.completeExceptionally(e)) AppLog.w(TAG, "background sync stopped", e)
            } finally {
                if (gen == generation) _syncing.value = false
            }
        }
        return ready
    }

    /**
     * Incremental sync with the stored zone token when we have one; a full
     * refetch of the recents index otherwise, when the token is rejected or
     * the changes endpoint answers anything unexpected, and at least once a
     * day as a safety net. The incremental path is not yet confirmed against
     * a real account, so the full refetch is the default that always works.
     */
    private suspend fun runSync(gen: Int, ready: CompletableDeferred<Unit>) {
        val token = runCatchingCancellable { store.getMeta(StoreMeta.SYNC_TOKEN) }.getOrNull()
        val lastFull = runCatchingCancellable { store.getMeta(StoreMeta.LAST_FULL_SYNC_MS) }.getOrNull()?.toLongOrNull()
        val fullDue = lastFull == null || clock() - lastFull > FULL_SYNC_INTERVAL_MS
        if (token != null && !fullDue) {
            val applied = try {
                applyZoneChanges(gen, fetchAllZoneChanges(token))
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: NotesError.Offline) {
                throw e
            } catch (e: NotesError.AuthExpired) {
                throw e
            } catch (e: NotesError.Throttled) {
                throw e
            } catch (e: Throwable) {
                AppLog.w(TAG, "incremental sync failed; dropping the token and refetching everything", e)
                safeStore("drop sync token") { store.setMeta(StoreMeta.SYNC_TOKEN, null) }
                false
            }
            if (applied) return
        }
        fullSync(gen, ready)
        primeSyncToken(gen)
    }

    /** Every page of zone changes since [token] (null = from the start), merged. */
    private suspend fun fetchAllZoneChanges(token: String?): MergedChanges {
        val merged = MergedChanges()
        var t = token
        var pages = 0
        while (true) {
            val page = withApi(idempotent = true) { it.fetchZoneChanges(t, ZONE_PAGE_LIMIT) }
            merged.add(page)
            t = page.syncToken
            pages++
            if (!page.moreComing) break
            if (pages >= MAX_ZONE_PAGES) throw IllegalStateException("changes/zone: still more after $pages pages")
        }
        merged.syncToken = t
        return merged
    }

    /**
     * Apply zone changes to the cache, then store the new token. Refuses
     * (throws, so the caller falls back to a full refetch) a batch that would
     * remove most of the cached notes: more likely a misread response than
     * a real mass deletion, and a full refetch settles it either way.
     */
    private suspend fun applyZoneChanges(gen: Int, changes: MergedChanges) {
        val cachedNames = _notes.value.mapTo(HashSet()) { it.recordName }
        val cached = cachedNames.size
        val removingCached = changes.removed.count { it in cachedNames }
        if (cached >= 10 && removingCached > cached / 2) {
            throw IllegalStateException("changes/zone would remove $removingCached of $cached notes")
        }
        applyNotes(gen, changes.notes.values, changes.removed)
        applyFolders(gen, changes.folders.values, changes.removed)
        if (gen != generation) return
        safeStore("store sync token") { store.setMeta(StoreMeta.SYNC_TOKEN, changes.syncToken) }
        AppLog.i(TAG) {
            "incremental sync: ${changes.notes.size} changed, ${changes.removed.size} removed, " +
                "${changes.folders.size} folders"
        }
    }

    /**
     * Page through the whole recents index into the cache, with no overall
     * cap (issue #12: notes beyond 1,000 used to be invisible). Signals
     * [ready] after the first page so the UI can show the list while the
     * rest arrives. Only a complete pass prunes cached notes the server no
     * longer lists.
     */
    private suspend fun fullSync(gen: Int, ready: CompletableDeferred<Unit>) {
        touchedDuringSync.clear()
        val seen = HashSet<String>()
        var continuation: String? = null
        var pages = 0
        var complete = false
        while (true) {
            val marker = continuation
            val page = withApi(idempotent = true) { it.fetchRecentsPage(marker, RECENTS_PAGE_LIMIT) }
            if (gen != generation) return
            pages++
            page.notes.forEach { seen += it.recordName }
            applyNotes(gen, page.notes.filter { !it.deleted }, page.notes.filter { it.deleted }.map { it.recordName })
            continuation = page.continuationMarker
            if (continuation == null) {
                complete = true
                break
            }
            if (page.notes.isEmpty() || pages >= MAX_RECENTS_PAGES) {
                AppLog.w(TAG, "full sync: stopping after $pages pages (empty page or page cap); not pruning")
                break
            }
            // The list is usable now; keep paging in the background.
            ready.complete(Unit)
        }
        if (complete) {
            val stale = _notes.value.map { it.recordName }.filter { it !in seen && it !in touchedDuringSync }
            if (stale.isNotEmpty()) applyNotes(gen, emptyList(), stale)
            safeStore("store full sync time") { store.setMeta(StoreMeta.LAST_FULL_SYNC_MS, clock().toString()) }
        }
        refreshFolderNames(gen)
        AppLog.i(TAG) { "full sync: ${seen.size} notes in $pages pages (complete=$complete)" }
    }

    /**
     * Look up every folder the notes reference plus every folder already
     * known (so empty folders keep their names). A folder the lookup no
     * longer returns is gone. When the lookup fails, keep what we had and
     * show unknown ones by ID.
     */
    private suspend fun refreshFolderNames(gen: Int) {
        val known = _folders.value.associateBy { it.recordName }
        val ids = _notes.value.mapNotNull { it.folderRecordName }.toSet() + known.keys
        if (ids.isEmpty()) return
        val looked = runCatchingCancellable { withApi(idempotent = true) { it.lookupFolders(ids) } }
        if (gen != generation) return
        looked.onSuccess { found ->
            if (found.isEmpty()) return@onSuccess
            val gone = known.keys - found.map { it.recordName }.toSet()
            applyFolders(gen, found, gone)
        }.onFailure {
            AppLog.w(TAG, "lookupFolders failed; keeping cached folder names", it)
            val unknown = ids.filter { it !in known }.map { rn ->
                FolderSummary(
                    recordName = rn,
                    displayName = null,
                    isTrash = rn == TRASH_RECORD_NAME,
                    isDefault = rn == DEFAULT_FOLDER_RECORD_NAME,
                )
            }
            applyFolders(gen, unknown, emptyList())
        }
    }

    /**
     * After a full refetch, try to get a zone sync token so the next refresh
     * is incremental. Best effort: any failure just leaves no token. The
     * enumeration also brings in Folder records, which is how empty folders
     * appear at all (notes only reference folders that hold something).
     */
    private suspend fun primeSyncToken(gen: Int) {
        val existing = runCatchingCancellable { store.getMeta(StoreMeta.SYNC_TOKEN) }.getOrNull()
        if (existing != null || gen != generation) return
        runCatchingCancellable { applyZoneChanges(gen, fetchAllZoneChanges(null)) }
            .onFailure {
                if (it is UnsupportedOperationException) return@onFailure
                AppLog.i(TAG) { "no zone sync token (${it.javaClass.simpleName}); refreshes stay full refetches" }
            }
    }

    private suspend fun applyNotes(gen: Int, upserts: Collection<NoteSummary>, removed: Collection<String>) {
        if (gen != generation || (upserts.isEmpty() && removed.isEmpty())) return
        val removedSet = removed.toSet()
        _notes.update { list ->
            val byName = LinkedHashMap<String, NoteSummary>(list.size + upserts.size)
            list.forEach { byName[it.recordName] = it }
            removedSet.forEach { byName.remove(it) }
            upserts.forEach { byName[it.recordName] = it }
            sortNotes(byName.values)
        }
        safeStore("cache notes") {
            if (gen != generation) return@safeStore
            if (upserts.isNotEmpty()) store.upsertSummaries(upserts)
            if (removedSet.isNotEmpty()) {
                store.deleteSummaries(removedSet)
                store.deleteBodies(removedSet)
            }
        }
    }

    private suspend fun applyFolders(gen: Int, upserts: Collection<FolderSummary>, removed: Collection<String>) {
        if (gen != generation || (upserts.isEmpty() && removed.isEmpty())) return
        val removedSet = removed.toSet()
        _folders.update { list ->
            val byName = LinkedHashMap<String, FolderSummary>()
            list.forEach { byName[it.recordName] = it }
            removedSet.forEach { byName.remove(it) }
            upserts.forEach { byName[it.recordName] = it }
            byName.values.toList()
        }
        safeStore("cache folders") {
            if (gen != generation) return@safeStore
            if (upserts.isNotEmpty()) store.upsertFolders(upserts)
            if (removedSet.isNotEmpty()) store.deleteFolders(removedSet)
        }
    }

    private fun sortNotes(notes: Collection<NoteSummary>): List<NoteSummary> =
        notes.sortedByDescending { it.modificationTimestampMs ?: 0L }

    /**
     * The cache belongs to one iCloud account. If the session that just
     * opened is a different account (the session expired and someone signed
     * in with another Apple ID), drop the previous account's cache and
     * drafts rather than showing them, or saving them into this account.
     */
    private suspend fun adoptAccount(newApi: NotesApi) {
        awaitPendingClear()
        val account = newApi.accountId?.let(::accountFingerprint) ?: return
        val previous = runCatchingCancellable { store.getMeta(StoreMeta.ACCOUNT) }.getOrNull()
        if (previous != null && previous != account) {
            AppLog.w(TAG, "signed in to a different account; clearing the previous account's cache and drafts")
            // The clear also deletes `meta`: finish it before writing ACCOUNT
            // and before anything re-populates the store.
            clearLocalData().join()
        }
        safeStore("store account") { store.setMeta(StoreMeta.ACCOUNT, account) }
    }

    /** Search the visible text of cached (previously opened) note bodies. */
    suspend fun searchCachedBodies(query: String): Set<String> {
        if (query.isBlank()) return emptySet()
        return runCatchingCancellable { store.searchBodies(query.trim(), MAX_BODY_SEARCH_RESULTS).toSet() }
            .getOrDefault(emptySet())
    }

    fun signOut() {
        auth.clearLogin()
        api = null
        sessionRestorable = false
        lastSyncAtMs = null
        clearLocalData()
    }

    /** The last [clearLocalData] run; anything that reads or fills the store waits for it. */
    @Volatile
    private var pendingClear: Job? = null

    private suspend fun awaitPendingClear() {
        pendingClear?.join()
    }

    /**
     * Forget the cache, sync state and unsent drafts (sign-out: they're this
     * account's). The in-memory state goes at once; the returned job deletes
     * the store (in [externalScope], so it finishes even if the caller goes
     * away). [loadCache] and [adoptAccount] wait for it.
     */
    private fun clearLocalData(): Job {
        generation++
        synchronized(syncLock) {
            syncJob?.cancel()
            syncJob = null
            syncReady = null
        }
        _syncing.value = false
        _notes.value = emptyList()
        _folders.value = emptyList()
        _pendingDrafts.value = emptyList()
        cacheLoaded = false
        draftScheduler.cancelAll()
        val previous = pendingClear
        return externalScope.launch {
            previous?.join()
            safeStore("clear local data") { store.clearAll() }
            safeStore("clear other local data") { clearOtherLocalData() }
        }.also { pendingClear = it }
    }

    /** Run a store operation; a storage failure is logged, never fatal (the cache is rebuildable). */
    private suspend inline fun safeStore(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            AppLog.e(TAG, "local store: $what failed", e)
        }
    }

    suspend fun lookupNote(recordName: String): NoteRecord =
        withApi(idempotent = true) { it.lookupNote(recordName) }.also { cacheBody(it) }

    /**
     * Open a note for the editor: from iCloud when possible, otherwise (no
     * network, or the request failed for lack of one) from the copy cached
     * the last time it was opened. [OpenedNote.fromCache] drives the
     * "Offline — showing saved copy" banner.
     */
    suspend fun openNote(recordName: String): OpenedNote {
        if (!online.value) {
            cachedBody(recordName)?.let { return OpenedNote(it, fromCache = true) }
            throw NotesError.Offline()
        }
        return try {
            OpenedNote(lookupNote(recordName), fromCache = false)
        } catch (e: NotesError.Offline) {
            cachedBody(recordName)?.let { OpenedNote(it, fromCache = true) } ?: throw e
        }
    }

    /**
     * Read-only access for the DocumentsProvider: the record cached when the
     * note was last opened, else a lookup (which caches it). Never writes.
     */
    suspend fun noteForReading(recordName: String): NoteRecord = cachedBody(recordName) ?: lookupNote(recordName)

    private suspend fun cachedBody(recordName: String): NoteRecord? =
        runCatchingCancellable { store.getBody(recordName) }
            .onFailure { AppLog.w(TAG, "reading the cached note failed", it) }
            .getOrNull()

    /** Keep the full record of an opened / saved note for offline reading (Note records only). */
    private suspend fun cacheBody(record: NoteRecord) {
        if (record.recordType != "Note" || record.recordChangeTag == null) return
        if (record.stringField("TextDataEncrypted") == null) return
        val gen = generation
        val text = runCatching { decodeNoteBody(record).text }.getOrNull()
        safeStore("cache note body") {
            if (gen == generation) store.putBody(record, text, clock())
        }
    }

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
        /**
         * Background draft saves (issue #13): only write if the outbox still
         * holds exactly this edit under this key when the save gets its turn;
         * otherwise throw [DraftSupersededException] without writing.
         */
        requirePendingDraft: String? = null,
    ): NoteRecord = externalScope.async {
        saveLock(record.recordName).withLock {
            if (requirePendingDraft != null) {
                val row = runCatchingCancellable { store.getDraft(requirePendingDraft) }.getOrNull()
                if (row == null || row.conflict || row.fullText != newBody ||
                    row.spansSignature != spansSignature(newSpans)
                ) {
                    throw DraftSupersededException()
                }
            }
            val updated = if (record.recordChangeTag == null) {
                saveDraftNote(record.recordName, pendingFolderRef, newBody, newSpans)
            } else {
                withApi(idempotent = false) { client ->
                    doSaveNote(client, record, newBody, isPureAppend, newSpans)
                }
            }
            cacheBody(updated)
            clearDraftIfSaved(record.recordName, newBody, newSpans)
            updated
        }
    }.await()

    private fun saveLock(key: String): Mutex = saveLocks.getOrPut(key) { Mutex() }

    /**
     * Save a local draft ([draftKey] = "draft-<uuid>") that has no change tag.
     *
     * - Already created (by the editor or the background worker): modify that
     *   note ([saveOverCreated]), never create it twice.
     * - Otherwise create it, under a recordName chosen once per draft and
     *   stored *before* the request goes out. When the answer is lost (a
     *   timeout after the server committed, process death), the retry sends
     *   the same name: CloudKit refuses it ([NotesError.AlreadyExists]), and
     *   the note that exists is looked up and used instead of a duplicate.
     *
     * The created record is stored with the mapping as the merge base for
     * later saves of the draft.
     */
    private suspend fun saveDraftNote(
        draftKey: String,
        pendingFolderRef: JsonElement?,
        newBody: String,
        newSpans: List<NoteBodyEditor.AttrSpan>?,
    ): NoteRecord {
        val known = runCatchingCancellable { store.createdDraft(draftKey) }.getOrNull()
        if (known != null && known.confirmed) {
            AppLog.i(TAG) { "save: draft $draftKey was already created; modifying it" }
            return saveOverCreated(known, newBody, newSpans)
        }
        val folderRef = pendingFolderRef ?: error("Draft has no folderRef — refresh first.")
        val recordName = known?.recordName ?: newNoteRecordName().also { name ->
            // If this can't be stored, the create still goes out (a lost
            // answer could then duplicate the note, as before).
            safeStore("reserve record name") { store.putCreatedDraft(CreatedDraft(draftKey, name, confirmed = false)) }
        }
        val created = try {
            withApi(idempotent = false) { client -> createNote(client, recordName, folderRef, newBody) }
        } catch (e: NotesError.AlreadyExists) {
            AppLog.i(TAG) { "save: $recordName already exists (an earlier create went through); using it" }
            withApi(idempotent = true) { it.lookupNote(recordName) }
        }
        val entry = CreatedDraft(draftKey, created.recordName, confirmed = true, record = created)
        // Remember the created note at once: a second save of this draft
        // (worker vs. editor, or a retry) must modify it, not create another.
        safeStore("record created draft") { store.putCreatedDraft(entry) }
        addCreatedSummary(created.recordName, folderRef, newBody)
        val createdBody = decodeNoteBody(created)
        if (createdBody is NoteBody.Editable && createdBody.text == newBody) return created
        // The note exists with other text (an earlier attempt sent an older
        // version of the draft): bring it up to date through the modify path.
        return saveOverCreated(entry, newBody, newSpans)
    }

    /**
     * Save [newBody] over a note created from a draft. The base is the record
     * our create (or our last write through this mapping) left, not the
     * server's current one: an edit made on another device since then is
     * merged through the CONFLICT path instead of being overwritten.
     */
    private suspend fun saveOverCreated(
        entry: CreatedDraft,
        newBody: String,
        newSpans: List<NoteBodyEditor.AttrSpan>?,
    ): NoteRecord = saveLock(entry.recordName).withLock {
        val name = entry.recordName
        val base = entry.record?.takeIf { it.recordChangeTag != null && it.stringField("TextDataEncrypted") != null }
            ?: withApi(idempotent = true) { it.lookupNote(name) }
        val baseBody = decodeNoteBody(base)
        if (baseBody is NoteBody.Editable && baseBody.text == newBody &&
            (newSpans == null || newSpans == baseBody.spans)
        ) {
            // Nothing of ours left to write; hand back the current version.
            return@withLock runCatchingCancellable { withApi(idempotent = true) { it.lookupNote(name) } }
                .getOrDefault(base)
        }
        val saved = withApi(idempotent = false) { client ->
            doSaveNote(client, base, newBody, isPureAppend = false, newSpans)
        }
        safeStore("record created draft") { store.putCreatedDraft(entry.copy(record = saved)) }
        saved
    }

    /**
     * createNote with the typed content in one shot, matching iCloud.com's
     * pattern: NoteCreator's proto already has the full content as a single
     * substring + matching attribute_run, so Mac's notesync sees a
     * self-consistent record on first sight.
     */
    private suspend fun createNote(
        client: NotesApi,
        recordName: String,
        folderRef: JsonElement,
        newBody: String,
    ): NoteRecord {
        val (newTitle, newSnippet) = computeTitleAndSnippet(newBody)
        // The proto gets exactly what was typed (newBody), including an empty
        // first line or a trailing newline. Title / body only fill the list
        // fields: the first line and the rest, as before, except that a
        // blank first line doesn't make a blank title (Apple's lists show
        // the first non-blank line).
        val firstNlIdx = newBody.indexOf('\n')
        val firstLine = if (firstNlIdx < 0) newBody else newBody.substring(0, firstNlIdx)
        val createTitle = firstLine.ifBlank { newTitle }
        val createBody = if (firstNlIdx < 0) "" else newBody.substring(firstNlIdx + 1)
        AppLog.v(TAG) {
            "save (draft→createNote): title='${createTitle.take(30)}' " +
                "body.len=${createBody.length} snippet='${newSnippet.take(30)}'"
        }
        val created = client.createNote(
            title = createTitle,
            body = createBody,
            folderRef = folderRef,
            ourReplicaUuid = replicaUuid(),
            noteText = newBody,
            recordName = recordName,
        )
        return withBody(client, created)
    }

    /** Pre-pend a summary of a just-created note so back-nav shows it without a refetch. */
    private suspend fun addCreatedSummary(recordName: String, folderRef: JsonElement, text: String) {
        val (title, snippet) = computeTitleAndSnippet(text)
        val newSummary = NoteSummary(
            recordName = recordName,
            title = title,
            snippet = snippet,
            modificationTimestampMs = clock(),
            deleted = false,
            folderRecordName = extractReferenceRecordName(folderRef),
        )
        touchedDuringSync += recordName
        _notes.update { listOf(newSummary) + it.filter { n -> n.recordName != recordName } }
        val gen = generation
        safeStore("cache created note") { if (gen == generation) store.upsertSummaries(listOf(newSummary)) }
    }

    /** The incremental modify path for a note that exists on the server. */
    private suspend fun doSaveNote(
        client: NotesApi,
        record: NoteRecord,
        newBody: String,
        isPureAppend: Boolean,
        newSpans: List<NoteBodyEditor.AttrSpan>?,
    ): NoteRecord {
        requireNotNull(record.recordChangeTag) { "modify needs a saved note" }
        val updated = modifyWithRebase(client, replicaUuid(), record, newBody, isPureAppend, newSpans)
        return withBody(client, updated)
    }

    /**
     * The editor re-decodes the returned record. If CloudKit didn't echo the
     * body field, fetch it so a just-saved note doesn't look body-less (and
     * therefore read-only).
     */
    private suspend fun withBody(client: NotesApi, updated: NoteRecord): NoteRecord {
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
    private suspend fun patchSummary(recordName: String, title: String, snippet: String) {
        touchedDuringSync += recordName
        var patched: NoteSummary? = null
        _notes.update { list ->
            list.map { note ->
                if (note.recordName == recordName) {
                    note.copy(title = title, snippet = snippet, modificationTimestampMs = clock()).also { patched = it }
                } else {
                    note
                }
            }.sortedByDescending { it.modificationTimestampMs ?: 0L }
        }
        val gen = generation
        patched?.let { p -> safeStore("cache saved note") { if (gen == generation) store.upsertSummaries(listOf(p)) } }
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
        safeStore("forget deleted note") {
            store.deleteSummaries(listOf(record.recordName))
            store.deleteBodies(listOf(record.recordName))
        }
        discardDraft(record.recordName)
    }

    // ----- Unsent drafts / outbox (issue #13) -----

    /** The outbox entry for [key] (a recordName or a local "draft-<uuid>"), if any. */
    suspend fun pendingDraft(key: String): PendingDraft? =
        runCatchingCancellable { store.getDraft(key) }
            .onFailure { AppLog.e(TAG, "reading an unsent draft failed", it) }
            .getOrNull()

    /**
     * Write [draft] to the outbox. Runs in [externalScope] so it completes
     * even if the editor that asked for it goes away (e.g. on ON_PAUSE).
     */
    fun persistDraft(draft: PendingDraft): Job {
        val gen = generation
        return externalScope.launch {
            draftLock.withLock {
                if (gen != generation) return@withLock
                runCatchingCancellable { store.putDraft(draft) }
                    .onFailure { AppLog.e(TAG, "saving an unsent draft to disk failed", it) }
            }
        }
    }

    /** Drop the outbox entry for [key] (the user discarded it, or the editor knows it's saved). */
    suspend fun discardDraft(key: String) {
        draftLock.withLock {
            runCatchingCancellable { store.deleteDraft(key) }
                .onFailure { AppLog.e(TAG, "deleting an unsent draft failed", it) }
        }
        draftScheduler.cancel(key)
    }

    /** A background save found overlapping edits: keep the draft for the editor to resolve. */
    suspend fun markDraftConflict(key: String) {
        draftLock.withLock {
            val row = runCatchingCancellable { store.getDraft(key) }.getOrNull() ?: return@withLock
            runCatchingCancellable { store.putDraft(row.copy(conflict = true)) }
        }
    }

    /** Ask the background worker to save [key]'s draft (network-constrained, retried with backoff). */
    fun scheduleDraftSync(key: String) = draftScheduler.enqueue(key)

    /** "N unsent changes" tapped: retry every draft that isn't waiting on a conflict decision. */
    fun retryPendingDrafts() {
        _pendingDrafts.value.filter { !it.conflict }.forEach { draftScheduler.enqueue(it.key) }
    }

    /**
     * For the background worker in a fresh process: load the cache and allow
     * the session to be opened on demand from the saved cookies.
     */
    suspend fun prepareForBackgroundWork() {
        loadCache()
        if (auth.hasSavedLogin()) sessionRestorable = true
    }

    /**
     * After a confirmed save of [savedText]/[savedSpans] under [key]: drop
     * the outbox row if it still holds exactly that edit. A row with newer
     * text (typed while the save was in flight) stays.
     */
    private suspend fun clearDraftIfSaved(
        key: String,
        savedText: String,
        savedSpans: List<NoteBodyEditor.AttrSpan>?,
    ) {
        draftLock.withLock {
            val row = runCatchingCancellable { store.getDraft(key) }.getOrNull() ?: return@withLock
            if (row.fullText == savedText && row.spansSignature == spansSignature(savedSpans)) {
                runCatchingCancellable { store.deleteDraft(key) }
            }
        }
    }
}

/** A note opened for the editor; [fromCache] = the saved offline copy, not a fresh lookup. */
data class OpenedNote(val record: NoteRecord, val fromCache: Boolean)

/** A background save found the outbox entry changed or gone by the time it ran; nothing was written. */
class DraftSupersededException : Exception("The unsent draft changed or was already saved")

/** Starts background saves of unsent drafts; see the WorkManager implementation in `sync/`. */
interface DraftScheduler {
    /** Save [key]'s draft when there is a network; unique per key, so one run per note at a time. */
    fun enqueue(key: String)
    fun cancel(key: String)
    fun cancelAll()

    /** Tests and previews: nothing runs in the background. */
    object None : DraftScheduler {
        override fun enqueue(key: String) = Unit
        override fun cancel(key: String) = Unit
        override fun cancelAll() = Unit
    }
}

/** Zone-change pages folded together (later pages win). */
private class MergedChanges {
    val notes = LinkedHashMap<String, NoteSummary>()
    val folders = LinkedHashMap<String, FolderSummary>()
    val removed = LinkedHashSet<String>()
    var syncToken: String? = null

    fun add(page: ZoneChanges) {
        for (n in page.notes) {
            removed -= n.recordName
            notes[n.recordName] = n
        }
        for (f in page.folders) {
            removed -= f.recordName
            folders[f.recordName] = f
        }
        for (rn in page.removedRecordNames) {
            notes -= rn
            folders -= rn
            removed += rn
        }
    }
}

/** Store a one-way fingerprint of the account id rather than the id itself. */
private fun accountFingerprint(accountId: String): String =
    MessageDigest.getInstance("SHA-256").digest(accountId.toByteArray())
        .joinToString("") { ((it.toInt() and 0xFF) + 0x100).toString(16).substring(1) }

/** Recents-index page size we ask for (CloudKit answers ~13 per page regardless). */
private const val RECENTS_PAGE_LIMIT = 200

/** Safety cap on one full refetch: ~13 notes/page, so roughly 65,000 notes. */
private const val MAX_RECENTS_PAGES = 5_000
private const val ZONE_PAGE_LIMIT = 200
private const val MAX_ZONE_PAGES = 2_000

/** Even with a working zone token, refetch everything this often, in case changes were missed. */
private const val FULL_SYNC_INTERVAL_MS = 24L * 60 * 60 * 1000
private const val MAX_BODY_SEARCH_RESULTS = 500
