package com.example.applenotes.ui

import android.content.Context
import com.example.applenotes.log.AppLog
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.applenotes.client.FolderSummary
import com.example.applenotes.client.NoteRecord
import com.example.applenotes.client.NoteSummary
import com.example.applenotes.data.AlphaGate
import com.example.applenotes.data.AppGraph
import com.example.applenotes.data.DEFAULT_FOLDER_RECORD_NAME
import com.example.applenotes.data.DRAFT_RECORD_PREFIX
import com.example.applenotes.data.NotesRepository
import com.example.applenotes.client.NotesError
import com.example.applenotes.data.errorDetails
import com.example.applenotes.data.TRASH_RECORD_NAME
import com.example.applenotes.integrations.IncomingRequest
import com.example.applenotes.data.runCatchingCancellable
import com.example.applenotes.data.userMessage
import com.example.applenotes.R
import com.example.applenotes.ui.common.UiMessage
import com.example.applenotes.ui.common.UiText
import com.example.applenotes.ui.common.uiText
import com.example.applenotes.ui.detail.DraftPrefill
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

private const val TAG = "AppleNotesUi"

/** Top-level navigation state. */
sealed interface AppScreen {
    data object Splash : AppScreen
    data object Login : AppScreen
    /**
     * [inDetailPane]: loading a note to open (or a new draft). On a wide
     * window the list stays on screen and only the detail pane spins.
     */
    data class Loading(val msg: UiText, val inDetailPane: Boolean = false) : AppScreen
    /**
     * Full-screen error. Reserved for bootstrap (sign-in) failures.
     * [details] is the raw error text behind the "Details" button.
     */
    data class Error(val msg: UiText, val details: String? = null) : AppScreen
    data object NotesList : AppScreen

    /**
     * The detail/editor screen. [openId] identifies this particular "open"
     * of a note; [NoteDetailViewModel.open] is idempotent per id, so
     * re-composition after rotation doesn't reset the editor.
     *
     * [record] is null when the screen is being restored after process
     * death — the detail ViewModel then rebuilds it from its own saved state.
     *
     * [pendingFolderRef] is set when this Detail represents a brand-new note
     * that has NOT yet been written to iCloud. The first save calls
     * `createNote` with the user's typed content (title + body in one shot);
     * subsequent saves use `modifyNoteBody`. We match iCloud.com's pattern:
     * it never does empty-create-then-modify — it only creates the record
     * once there's content to write. Empty-create + modify races Mac's
     * notesync housekeeping and produces records Mac later trashes.
     * When non-null, [record] is a stub (recordChangeTag = null,
     * recordName = "draft-<uuid>", empty fields).
     */
    data class Detail(
        val openId: String,
        val record: NoteRecord?,
        val pendingFolderRef: JsonElement? = null,
        /** Initial (unsaved) content for a new draft opened from outside the app. */
        val prefill: DraftPrefill? = null,
        /** [record] is the copy cached on this device, opened without a network (issue #12). */
        val offlineCopy: Boolean = false,
    ) : AppScreen

    /** The minimal settings screen (automation toggle). */
    data object Settings : AppScreen

    /** Settings → Allowed apps: the automation allow-list (issue #21). */
    data object AllowedApps : AppScreen
}

/**
 * Where to go once the open note has been left (two-pane mode, issue #16):
 * picking another note, "New note" or Settings while a note is open first
 * runs the editor's normal leave path (save-on-leave, unsaved-changes
 * dialog), and only then switches.
 */
sealed interface PendingSwitch {
    data class Open(val note: NoteSummary) : PendingSwitch
    data object Create : PendingSwitch
    data object Settings : PendingSwitch
    data object AllowedApps : PendingSwitch
}

/** Snackbar actions offered by the list screen. */
sealed interface ListAction {
    data object RetryRefresh : ListAction
    data class RetryOpen(val note: NoteSummary) : ListAction
    data object RetryCreate : ListAction

    /** Retry the background saves of unsent drafts (issue #13). */
    data object RetryUnsent : ListAction
    data class RetryCreatePrefilled(val prefill: DraftPrefill) : ListAction
}

data class NotesListUiState(
    val notes: List<NoteSummary> = emptyList(),
    val folders: List<FolderSummary> = emptyList(),
    /**
     * Currently-selected folder recordName. Null = "All notes" pseudo-folder
     * (every note across user-visible folders). Use TRASH_RECORD_NAME to
     * show Recently Deleted explicitly.
     */
    val selectedFolder: String? = DEFAULT_FOLDER_RECORD_NAME,
    /** A sync with iCloud is running (the subtle "Updating…" indicator; issue #12). */
    val syncing: Boolean = false,
    /** A pull-to-refresh is waiting for its first results. */
    val refreshing: Boolean = false,
    /** Edits not yet confirmed by iCloud ("N unsent changes"; issue #13). */
    val unsentCount: Int = 0,
)

/**
 * Owns navigation, sign-in and the notes list. Survives configuration
 * changes; the bits that must survive process death (selected folder, which
 * note is open) live in [savedState].
 */
class AppViewModel(
    private val repository: NotesRepository,
    private val alphaGate: AlphaGate,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private val _alphaAcknowledged = MutableStateFlow<Boolean?>(null)

    /** null while loading the flag; false → show the disclaimer; true → app runs. */
    val alphaAcknowledged: StateFlow<Boolean?> = _alphaAcknowledged.asStateFlow()

    private val _screen = MutableStateFlow<AppScreen>(AppScreen.Splash)
    val screen: StateFlow<AppScreen> = _screen.asStateFlow()

    private val selectedFolder: StateFlow<String?> = savedState
        .getStateFlow(KEY_SELECTED_FOLDER, DEFAULT_FOLDER_RECORD_NAME)
        .map { if (it == ALL_NOTES) null else it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, DEFAULT_FOLDER_RECORD_NAME)

    private val _listMessage = MutableStateFlow<UiMessage<ListAction>?>(null)
    private var messageCounter = 0L

    /**
     * Snackbar for the list screen. Failures to refresh, open or create a
     * note land here (with Retry) instead of replacing the screen; the
     * full-screen [AppScreen.Error] is only for bootstrap failures.
     */
    val listMessage: StateFlow<UiMessage<ListAction>?> = _listMessage.asStateFlow()

    /** Device connectivity, for the offline indicator. */
    val online: StateFlow<Boolean> = repository.online

    private val _loginStaleToken = MutableStateFlow<String?>(null)

    /**
     * When the sign-in screen is shown because a session expired: the auth
     * token cookie that stopped working. The screen waits for a different
     * one instead of handing the stale cookie straight back (which would
     * loop). Null for a first sign-in.
     */
    val loginStaleToken: StateFlow<String?> = _loginStaleToken.asStateFlow()

    private val _refreshing = MutableStateFlow(false)

    val listState: StateFlow<NotesListUiState> =
        combine(
            combine(repository.notes, repository.folders, selectedFolder) { notes, folders, folder ->
                NotesListUiState(notes, folders, folder)
            },
            repository.syncing,
            _refreshing,
            repository.pendingDrafts,
        ) { base, syncing, refreshing, drafts ->
            base.copy(syncing = syncing, refreshing = refreshing, unsentCount = drafts.size)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, NotesListUiState())

    private val _twoPane = MutableStateFlow(false)

    /** The window is wide enough for list + note side by side (set by the UI; issue #16). */
    val twoPane: StateFlow<Boolean> = _twoPane.asStateFlow()

    private val _detailLeaveRequest = MutableStateFlow<Long?>(null)
    private var leaveRequestCounter = 0L

    /**
     * Non-null: the open note should leave exactly as if Back was pressed
     * (two-pane switching). The detail screen runs its back path and calls
     * [onDetailLeaveRequestHandled]; [showList] then performs [pendingSwitch].
     */
    val detailLeaveRequest: StateFlow<Long?> = _detailLeaveRequest.asStateFlow()

    /** What to show after the open note has been left (see [PendingSwitch]). */
    internal var pendingSwitch: PendingSwitch? = null
        private set

    /** Last automatic (resume / reconnect) refresh attempt, for throttling. */
    private var lastAutoRefreshMs: Long? = null

    init {
        // Back online: catch up (throttled), e.g. after a launch from the
        // cache with no network.
        viewModelScope.launch {
            var wasOnline = online.value
            online.collect { now ->
                if (now && !wasOnline) onAppResumed(reconnected = true)
                wasOnline = now
            }
        }
        // A different Apple ID signed in (after a session expired): forget
        // the previous account's open note and waiting requests.
        viewModelScope.launch { repository.account.collect { forgetOtherAccountsState() } }
        // First-launch alpha disclaimer. Block all app behaviour behind this —
        // we don't even start the auth bootstrap until the user has acknowledged.
        viewModelScope.launch {
            val ack = alphaGate.isAcknowledged()
            _alphaAcknowledged.value = ack
            if (ack) start()
        }
    }

    fun acknowledgeAlpha() {
        viewModelScope.launch {
            alphaGate.acknowledge()
            _alphaAcknowledged.value = true
            start()
        }
    }

    /**
     * Compare the signed-in account with the one this screen state was built
     * for (kept in saved state, so it survives process death). On a change,
     * drop the remembered open note (its draft is the previous account's;
     * the detail ViewModel discards its copy) and any pending request, and
     * leave an open note. Runs from the account flow and, synchronously,
     * right after each sign-in, before the remembered note is reopened.
     */
    private fun forgetOtherAccountsState() {
        val now = repository.account.value ?: return
        val before = savedState.get<String>(KEY_ACCOUNT)
        savedState[KEY_ACCOUNT] = now
        if (before == null || before == now) return
        AppLog.w(TAG, "a different iCloud account signed in; dropping the previous account's open note and requests")
        savedState.remove<String>(KEY_DETAIL_OPEN_ID)
        pendingIncoming = null
        cancelPendingSwitch()
        if (_screen.value is AppScreen.Detail) _screen.value = AppScreen.NotesList
    }

    private fun start() {
        // Load (or generate on first run) our stable per-device replica UUID
        // up front, as before; saves await it anyway.
        viewModelScope.launch { runCatchingCancellable { repository.replicaUuid() } }
        // On launch, check for existing session cookies and try to restore.
        if (repository.hasSavedLogin()) {
            bootstrap(UiText.Res(R.string.loading_restoring_session))
        } else {
            _screen.value = AppScreen.Login
        }
    }

    /**
     * [afterSignIn]: the user just signed in through the WebView. An
     * AuthExpired then is final (full-screen error, "Sign in again" clears
     * the cookies) instead of another trip to the sign-in screen, which
     * could loop.
     */
    private fun bootstrap(loadingMsg: UiText, afterSignIn: Boolean = false) {
        _screen.value = AppScreen.Loading(loadingMsg)
        viewModelScope.launch {
            // Issue #12: with a cached list, show it at once and sign in /
            // sync in the background. (Not right after a WebView sign-in:
            // that one should surface its own failure.)
            if (!afterSignIn && runCatchingCancellable { repository.loadCache() }.getOrDefault(false)) {
                showSignedInScreen()
                syncInBackground()
                return@launch
            }
            runCatchingCancellable { repository.signIn() }
                .onSuccess {
                    _loginStaleToken.value = null
                    forgetOtherAccountsState()
                    // Process-death restore: reopen the note that was on
                    // screen. The detail ViewModel rebuilds it (and any
                    // unsaved draft) from its own saved state.
                    val openId = savedState.get<String>(KEY_DETAIL_OPEN_ID)
                    if (openId != null) {
                        // A request that arrived meanwhile waits until the
                        // restored note is closed (see showList), so the
                        // restored draft isn't replaced.
                        _screen.value = AppScreen.Detail(openId, record = null)
                    } else {
                        _screen.value = AppScreen.NotesList
                        processPendingIncoming()
                    }
                }
                .onFailure {
                    AppLog.e(TAG, "bootstrap failed", it)
                    if (it is NotesError.AuthExpired && !afterSignIn) {
                        // Saved cookies no longer work: sign in again, and
                        // come back to the open note (if any) afterwards.
                        signInAgain()
                    } else {
                        _screen.value = AppScreen.Error(userMessage(it), errorDetails(it))
                    }
                }
        }
    }

    /** After sign-in (or with the cache shown): the note that was open before process death, else the list. */
    private fun showSignedInScreen() {
        val openId = savedState.get<String>(KEY_DETAIL_OPEN_ID)
        if (openId != null) {
            _screen.value = AppScreen.Detail(openId, record = null)
        } else {
            _screen.value = AppScreen.NotesList
            if (repository.isSignedIn) processPendingIncoming()
        }
    }

    /** The cached list is on screen; open the session and sync behind it. */
    private fun syncInBackground() {
        lastAutoRefreshMs = System.currentTimeMillis()
        viewModelScope.launch {
            runCatchingCancellable { repository.signIn() }
                .onSuccess {
                    _loginStaleToken.value = null
                    forgetOtherAccountsState()
                    if (_screen.value == AppScreen.NotesList) processPendingIncoming()
                }
                .onFailure {
                    AppLog.w(TAG, "background sign-in failed; showing the cached list", it)
                    when (it) {
                        is NotesError.AuthExpired -> signInAgain()
                        // The offline banner already says so; reconnecting retries.
                        is NotesError.Offline -> Unit
                        else -> postListMessage(
                            uiText(R.string.list_update_failed, userMessage(it)),
                            ListAction.RetryRefresh,
                            errorDetails(it),
                        )
                    }
                }
        }
    }

    fun onCookiesArrived() = bootstrap(UiText.Res(R.string.loading_signing_in), afterSignIn = true)

    /**
     * Send the user to the sign-in screen because the session expired,
     * WITHOUT forgetting the open note: [KEY_DETAIL_OPEN_ID] stays, the
     * detail ViewModel keeps its draft (also in its SavedStateHandle), and
     * the bootstrap after sign-in reopens that note.
     */
    fun signInAgain() {
        _loginStaleToken.value = repository.currentAuthToken()
        _screen.value = AppScreen.Login
    }

    /**
     * The session expired and a fresh bootstrap didn't fix it. From the list
     * (nothing unsaved there) we go straight to sign-in; the detail screen
     * instead offers "Sign in" next to the kept draft.
     */
    private fun needsSignIn(e: Throwable) = e is NotesError.AuthExpired && repository.sessionExpired.value

    fun retryBootstrap() = bootstrap(UiText.Res(R.string.loading_retrying))

    fun signOut() {
        _loginStaleToken.value = null
        cancelPendingSwitch()
        // Don't carry content shared before sign-out into another account.
        pendingIncoming = null
        repository.signOut()
        savedState.remove<String>(KEY_DETAIL_OPEN_ID)
        _listMessage.value = null
        _screen.value = AppScreen.Login
    }

    fun selectFolder(folderRecordName: String?) {
        savedState[KEY_SELECTED_FOLDER] = folderRecordName ?: ALL_NOTES
    }

    fun refresh() {
        // Don't hit the network (or blank the list) while offline.
        if (!online.value) {
            postListMessage(UiText.Res(R.string.error_offline), ListAction.RetryRefresh)
            return
        }
        // The cached list stays on screen (pull-to-refresh indicator; issue #12).
        _refreshing.value = true
        lastAutoRefreshMs = System.currentTimeMillis()
        viewModelScope.launch {
            runCatchingCancellable { repository.refresh() }
                .onFailure {
                    AppLog.e(TAG, "refresh failed", it)
                    _refreshing.value = false
                    if (needsSignIn(it)) return@launch signInAgain()
                    postListMessage(uiText(R.string.list_refresh_failed, userMessage(it)), ListAction.RetryRefresh, errorDetails(it))
                }
            _refreshing.value = false
        }
    }

    /**
     * The list came to the foreground (or the network came back): refresh
     * quietly, at most once a minute, never offline, never before sign-in.
     */
    fun onAppResumed(reconnected: Boolean = false) {
        if (!online.value || !listVisible()) return
        if (repository.sessionExpired.value) return
        val now = System.currentTimeMillis()
        val last = maxOf(lastAutoRefreshMs ?: 0L, repository.lastSyncAtMs ?: 0L)
        // Coming back online catches up at once unless a sync just succeeded.
        val since = if (reconnected) repository.lastSyncAtMs ?: 0L else last
        if (now - since < AUTO_REFRESH_INTERVAL_MS) return
        lastAutoRefreshMs = now
        viewModelScope.launch {
            runCatchingCancellable { repository.refresh() }
                .onFailure {
                    AppLog.w(TAG, "automatic refresh failed", it)
                    if (needsSignIn(it)) signInAgain()
                }
        }
    }

    /** "N unsent changes" tapped: send them again now (issue #13). */
    fun retryUnsent() = repository.retryPendingDrafts()

    /** recordNames of notes whose cached (previously opened) body contains [query]. */
    suspend fun searchCachedBodies(query: String): Set<String> = repository.searchCachedBodies(query)

    fun openNote(note: NoteSummary) {
        _screen.value = AppScreen.Loading(UiText.Res(R.string.loading_note), inDetailPane = true)
        viewModelScope.launch {
            runCatchingCancellable { repository.openNote(note.recordName) }
                .onSuccess { showDetail(it.record, pendingFolderRef = null, offlineCopy = it.fromCache) }
                .onFailure {
                    AppLog.e(TAG, "lookupNote failed", it)
                    if (needsSignIn(it)) return@onFailure signInAgain()
                    _screen.value = AppScreen.NotesList
                    postListMessage(
                        uiText(R.string.list_open_failed, userMessage(it)),
                        ListAction.RetryOpen(note),
                        errorDetails(it),
                    )
                }
        }
    }

    fun createNote() = createNote(prefill = null)

    /**
     * New draft in the selected folder (the default folder when "Recently
     * Deleted" is selected), optionally prefilled. Still a deferred create:
     * nothing is written until the editor saves.
     */
    fun createNote(prefill: DraftPrefill?) {
        _screen.value = AppScreen.Loading(UiText.Res(R.string.loading_creating_note), inDetailPane = true)
        val folder = selectedFolder.value.takeIf { it != TRASH_RECORD_NAME } ?: DEFAULT_FOLDER_RECORD_NAME
        viewModelScope.launch {
            runCatchingCancellable { repository.folderRefForNewNote(folder) }
                .onSuccess { folderRef ->
                    // DEFERRED CREATE: do NOT call createNote yet. iCloud.com
                    // doesn't either — clicking "new note" just opens an
                    // empty editor; the server-side record only gets
                    // created on the first save with the user's actual
                    // content. Empty-create-then-modify races Mac's
                    // notesync housekeeping and produces records that
                    // Mac later trashes (we observed this: notes appearing
                    // briefly then vanishing on Mac restart).
                    //
                    // The draft gets a unique local recordName ("draft-<uuid>")
                    // so the post-save state-update can disambiguate
                    // multiple drafts in flight (back, FAB-again, type,
                    // save races back into the original Detail).
                    val draftId = DRAFT_RECORD_PREFIX + java.util.UUID.randomUUID().toString()
                    val draftRecord = NoteRecord(
                        recordName = draftId,
                        recordType = "Note",
                        recordChangeTag = null,
                        rawFields = emptyMap(),
                    )
                    showDetail(draftRecord, pendingFolderRef = folderRef, prefill = prefill)
                }
                .onFailure {
                    AppLog.e(TAG, "createNote (folder lookup) failed", it)
                    if (needsSignIn(it)) return@onFailure signInAgain()
                    _screen.value = AppScreen.NotesList
                    postListMessage(
                        uiText(R.string.list_create_failed, userMessage(it)),
                        if (prefill == null) ListAction.RetryCreate else ListAction.RetryCreatePrefilled(prefill),
                        errorDetails(it),
                    )
                }
        }
    }

    private fun showDetail(
        record: NoteRecord,
        pendingFolderRef: JsonElement?,
        prefill: DraftPrefill? = null,
        offlineCopy: Boolean = false,
    ) {
        val openId = java.util.UUID.randomUUID().toString()
        savedState[KEY_DETAIL_OPEN_ID] = openId
        _screen.value = AppScreen.Detail(openId, record, pendingFolderRef, prefill, offlineCopy)
    }

    // ----- Requests from outside the app (share, shortcuts, tile, import) -----

    /** Waiting for sign-in / the list; mirrored into [savedState] when small enough. */
    private var pendingIncoming: IncomingRequest?
        get() = pendingIncomingInMemory
            ?: savedState.get<String>(KEY_PENDING_INCOMING)?.let { IncomingRequest.decodeOrNull(it) }
        set(value) {
            pendingIncomingInMemory = value
            val encoded = value?.let { IncomingRequest.encode(it) }
            if (encoded != null && encoded.length <= MAX_SAVED_INCOMING_CHARS) {
                savedState[KEY_PENDING_INCOMING] = encoded
            } else {
                savedState.remove<String>(KEY_PENDING_INCOMING)
            }
        }
    private var pendingIncomingInMemory: IncomingRequest? = null

    /**
     * Show [request] now if the user is signed in and on the list, settings
     * or a loaded note (the editor auto-saves the open draft first; see
     * DetailRoute). Otherwise keep it and show it once sign-in finishes.
     */
    internal fun handleIncoming(request: IncomingRequest) {
        val s = _screen.value
        val ready = repository.isSignedIn &&
            (s == AppScreen.NotesList || s == AppScreen.Settings || s == AppScreen.AllowedApps || (s is AppScreen.Detail && s.record != null))
        if (ready) {
            pendingIncoming = null
            process(request)
        } else {
            pendingIncoming = request
        }
    }

    /** True while a request is waiting for sign-in / the list. */
    internal val hasPendingIncoming: Boolean get() = pendingIncoming != null

    private fun processPendingIncoming() {
        val request = pendingIncoming ?: return
        pendingIncoming = null
        process(request)
    }

    private fun process(request: IncomingRequest) {
        when (request) {
            is IncomingRequest.NewDraft -> createNote(request.prefill)
            is IncomingRequest.OpenNote -> openNoteByRecordName(request.recordName)
            IncomingRequest.ReviewAutomationApps ->
                if (_screen.value is AppScreen.Detail) requestSwitch(PendingSwitch.AllowedApps) else showAllowedApps()
            is IncomingRequest.OpenNoteByTitle -> {
                val wanted = request.title.trim()
                val matches = repository.notes.value.filter {
                    it.folderRecordName != TRASH_RECORD_NAME && it.title?.trim() == wanted
                }
                if (matches.size == 1) {
                    openNote(matches[0])
                } else {
                    postListMessage(
                        if (matches.isEmpty()) {
                            uiText(R.string.list_no_note_titled, wanted)
                        } else {
                            UiText.Plural(R.plurals.list_notes_titled, matches.size, listOf(matches.size, wanted))
                        },
                        action = null,
                    )
                }
            }
        }
    }

    /** Open a note by recordName (launcher shortcut / automation). */
    fun openNoteByRecordName(recordName: String) {
        val summary = repository.notes.value.firstOrNull { it.recordName == recordName }
            ?: NoteSummary(
                recordName = recordName,
                title = null,
                snippet = null,
                modificationTimestampMs = null,
                deleted = false,
                folderRecordName = null,
            )
        openNote(summary)
    }

    fun showSettings() {
        _screen.value = AppScreen.Settings
    }

    fun showAllowedApps() {
        _screen.value = AppScreen.AllowedApps
    }

    // ----- Two-pane list + detail (issue #16) -----

    /** Called by the UI whenever the window width class changes. */
    fun setTwoPane(enabled: Boolean) {
        _twoPane.value = enabled
        // Back to one pane: a half-done switch would surprise later.
        if (!enabled) cancelPendingSwitch()
    }

    /** The notes list is on screen (alone, or next to a note in two-pane mode). */
    private fun listVisible(): Boolean = when (val s = _screen.value) {
        AppScreen.NotesList -> true
        is AppScreen.Detail -> _twoPane.value
        is AppScreen.Loading -> _twoPane.value && s.inDetailPane
        else -> false
    }

    /**
     * A note tapped in the list. With a note open next to the list, that
     * note is left first through its normal back path (saving it, or asking
     * about unsaved changes); otherwise the note opens straight away.
     */
    fun selectNote(note: NoteSummary) {
        val s = _screen.value
        if (s is AppScreen.Detail) {
            if (s.record?.recordName == note.recordName) return
            requestSwitch(PendingSwitch.Open(note))
        } else {
            openNote(note)
        }
    }

    /** "New note" (FAB), leaving the open note first in two-pane mode. */
    fun requestCreateNote() {
        if (_screen.value is AppScreen.Detail) requestSwitch(PendingSwitch.Create) else createNote()
    }

    /** Drawer → Settings, leaving the open note first in two-pane mode. */
    fun requestSettings() {
        if (_screen.value is AppScreen.Detail) requestSwitch(PendingSwitch.Settings) else showSettings()
    }

    private fun requestSwitch(target: PendingSwitch) {
        pendingSwitch = target
        _detailLeaveRequest.value = ++leaveRequestCounter
    }

    /** The detail screen started its back path for [detailLeaveRequest]. */
    fun onDetailLeaveRequestHandled() {
        _detailLeaveRequest.value = null
    }

    /**
     * The open note stays (the save failed, a conflict / restore question is
     * up, or the user chose "Keep editing"): forget the switch, so a later
     * Back just closes the note.
     */
    fun cancelPendingSwitch() {
        pendingSwitch = null
        _detailLeaveRequest.value = null
    }

    /**
     * Back to the cached list. No network round trip — the repository patched
     * its cache in place when we saved, so the snippet for the just-edited
     * note already reflects our changes.
     */
    fun showList(message: UiText? = null) {
        savedState.remove<String>(KEY_DETAIL_OPEN_ID)
        _screen.value = AppScreen.NotesList
        if (message != null) postListMessage(message, action = null)
        val switch = pendingSwitch
        pendingSwitch = null
        _detailLeaveRequest.value = null
        when (switch) {
            is PendingSwitch.Open -> openNote(switch.note)
            PendingSwitch.Create -> createNote()
            PendingSwitch.Settings -> showSettings()
            PendingSwitch.AllowedApps -> showAllowedApps()
            null -> processPendingIncoming()
        }
    }

    private fun postListMessage(text: UiText, action: ListAction?, details: String? = null) {
        _listMessage.value = UiMessage(++messageCounter, text, action, details = details)
    }

    fun onListMessageAction(m: UiMessage<ListAction>) {
        onListMessageDismissed(m)
        when (val a = m.action) {
            ListAction.RetryRefresh -> refresh()
            is ListAction.RetryOpen -> openNote(a.note)
            ListAction.RetryCreate -> createNote()
            is ListAction.RetryCreatePrefilled -> createNote(a.prefill)
            ListAction.RetryUnsent -> retryUnsent()
            null -> Unit
        }
    }

    fun onListMessageDismissed(m: UiMessage<ListAction>) {
        if (_listMessage.value?.id == m.id) _listMessage.value = null
    }

    companion object {
        internal const val KEY_SELECTED_FOLDER = "selectedFolder"
        internal const val KEY_DETAIL_OPEN_ID = "detailOpenId"
        internal const val KEY_PENDING_INCOMING = "pendingIncoming"
        internal const val KEY_ACCOUNT = "account"
        private const val MAX_SAVED_INCOMING_CHARS = 100_000

        /** Refresh-on-resume throttle (issue #12). */
        internal const val AUTO_REFRESH_INTERVAL_MS = 60_000L

        /** Saved-state stand-in for the null "All notes" folder. */
        private const val ALL_NOTES = "\u0000all-notes"

        fun factory(context: Context): ViewModelProvider.Factory {
            val app = context.applicationContext
            return viewModelFactory {
                initializer {
                    AppViewModel(
                        AppGraph.notesRepository(app),
                        AppGraph.alphaGate(app),
                        createSavedStateHandle(),
                    )
                }
            }
        }
    }
}
