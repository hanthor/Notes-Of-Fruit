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
import com.example.applenotes.data.runCatchingCancellable
import com.example.applenotes.data.userMessage
import com.example.applenotes.ui.common.UiMessage
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
    data class Loading(val msg: String) : AppScreen
    /**
     * Full-screen error. Reserved for bootstrap (sign-in) failures.
     * [details] is the raw error text behind the "Details" button.
     */
    data class Error(val msg: String, val details: String? = null) : AppScreen
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
    ) : AppScreen
}

/** Snackbar actions offered by the list screen. */
sealed interface ListAction {
    data object RetryRefresh : ListAction
    data class RetryOpen(val note: NoteSummary) : ListAction
    data object RetryCreate : ListAction
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

    val listState: StateFlow<NotesListUiState> =
        combine(repository.notes, repository.folders, selectedFolder) { notes, folders, folder ->
            NotesListUiState(notes, folders, folder)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, NotesListUiState())

    init {
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

    private fun start() {
        // Load (or generate on first run) our stable per-device replica UUID
        // up front, as before; saves await it anyway.
        viewModelScope.launch { runCatchingCancellable { repository.replicaUuid() } }
        // On launch, check for existing session cookies and try to restore.
        if (repository.hasSavedLogin()) {
            bootstrap("Restoring session…")
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
    private fun bootstrap(loadingMsg: String, afterSignIn: Boolean = false) {
        _screen.value = AppScreen.Loading(loadingMsg)
        viewModelScope.launch {
            runCatchingCancellable { repository.signIn() }
                .onSuccess {
                    _loginStaleToken.value = null
                    // Process-death restore: reopen the note that was on
                    // screen. The detail ViewModel rebuilds it (and any
                    // unsaved draft) from its own saved state.
                    val openId = savedState.get<String>(KEY_DETAIL_OPEN_ID)
                    _screen.value = if (openId != null) {
                        AppScreen.Detail(openId, record = null)
                    } else {
                        AppScreen.NotesList
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

    fun onCookiesArrived() = bootstrap("Signing in…", afterSignIn = true)

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

    fun retryBootstrap() = bootstrap("Retrying…")

    fun signOut() {
        _loginStaleToken.value = null
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
            postListMessage("You're offline. Check your connection and try again.", ListAction.RetryRefresh)
            return
        }
        _screen.value = AppScreen.Loading("Refreshing…")
        viewModelScope.launch {
            runCatchingCancellable { repository.refresh() }
                .onFailure {
                    AppLog.e(TAG, "refresh failed", it)
                    if (needsSignIn(it)) return@launch signInAgain()
                    postListMessage("Couldn't refresh: ${userMessage(it)}", ListAction.RetryRefresh, errorDetails(it))
                }
            // Either way the (possibly stale) cached list is still good.
            _screen.value = AppScreen.NotesList
        }
    }

    fun openNote(note: NoteSummary) {
        _screen.value = AppScreen.Loading("Loading note…")
        viewModelScope.launch {
            runCatchingCancellable { repository.lookupNote(note.recordName) }
                .onSuccess { showDetail(it, pendingFolderRef = null) }
                .onFailure {
                    AppLog.e(TAG, "lookupNote failed", it)
                    if (needsSignIn(it)) return@onFailure signInAgain()
                    _screen.value = AppScreen.NotesList
                    postListMessage("Couldn't open the note: ${userMessage(it)}", ListAction.RetryOpen(note), errorDetails(it))
                }
        }
    }

    fun createNote() {
        _screen.value = AppScreen.Loading("Creating note…")
        viewModelScope.launch {
            runCatchingCancellable { repository.folderRefForNewNote(selectedFolder.value) }
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
                    showDetail(draftRecord, pendingFolderRef = folderRef)
                }
                .onFailure {
                    AppLog.e(TAG, "createNote (folder lookup) failed", it)
                    if (needsSignIn(it)) return@onFailure signInAgain()
                    _screen.value = AppScreen.NotesList
                    postListMessage("Couldn't create a note: ${userMessage(it)}", ListAction.RetryCreate, errorDetails(it))
                }
        }
    }

    private fun showDetail(record: NoteRecord, pendingFolderRef: JsonElement?) {
        val openId = java.util.UUID.randomUUID().toString()
        savedState[KEY_DETAIL_OPEN_ID] = openId
        _screen.value = AppScreen.Detail(openId, record, pendingFolderRef)
    }

    /**
     * Back to the cached list. No network round trip — the repository patched
     * its cache in place when we saved, so the snippet for the just-edited
     * note already reflects our changes.
     */
    fun showList(message: String? = null) {
        savedState.remove<String>(KEY_DETAIL_OPEN_ID)
        _screen.value = AppScreen.NotesList
        if (message != null) postListMessage(message, action = null)
    }

    private fun postListMessage(text: String, action: ListAction?, details: String? = null) {
        _listMessage.value = UiMessage(++messageCounter, text, action, details = details)
    }

    fun onListMessageAction(m: UiMessage<ListAction>) {
        onListMessageDismissed(m)
        when (val a = m.action) {
            ListAction.RetryRefresh -> refresh()
            is ListAction.RetryOpen -> openNote(a.note)
            ListAction.RetryCreate -> createNote()
            null -> Unit
        }
    }

    fun onListMessageDismissed(m: UiMessage<ListAction>) {
        if (_listMessage.value?.id == m.id) _listMessage.value = null
    }

    companion object {
        internal const val KEY_SELECTED_FOLDER = "selectedFolder"
        internal const val KEY_DETAIL_OPEN_ID = "detailOpenId"

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
