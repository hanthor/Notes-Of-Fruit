package com.example.applenotes.ui

import android.content.Context
import android.util.Log
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
import com.example.applenotes.data.runCatchingCancellable
import com.example.applenotes.data.userMessage
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
    /** Full-screen error. Reserved for failures with nothing to fall back to. */
    data class Error(val msg: String) : AppScreen
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

    private fun bootstrap(loadingMsg: String) {
        _screen.value = AppScreen.Loading(loadingMsg)
        viewModelScope.launch {
            runCatchingCancellable { repository.signIn() }
                .onSuccess {
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
                    Log.e(TAG, "bootstrap failed", it)
                    _screen.value = AppScreen.Error(userMessage(it))
                }
        }
    }

    fun onCookiesArrived() = bootstrap("Signing in…")

    fun retryBootstrap() = bootstrap("Retrying…")

    fun signOut() {
        repository.signOut()
        savedState.remove<String>(KEY_DETAIL_OPEN_ID)
        _screen.value = AppScreen.Login
    }

    fun selectFolder(folderRecordName: String?) {
        savedState[KEY_SELECTED_FOLDER] = folderRecordName ?: ALL_NOTES
    }

    fun refresh() {
        _screen.value = AppScreen.Loading("Refreshing…")
        viewModelScope.launch {
            _screen.value = runCatchingCancellable { repository.refresh() }.fold(
                onSuccess = { AppScreen.NotesList },
                onFailure = { AppScreen.Error(userMessage(it)) },
            )
        }
    }

    fun openNote(note: NoteSummary) {
        _screen.value = AppScreen.Loading("Loading note…")
        viewModelScope.launch {
            runCatchingCancellable { repository.lookupNote(note.recordName) }
                .onSuccess { showDetail(it, pendingFolderRef = null) }
                .onFailure { _screen.value = AppScreen.Error(userMessage(it)) }
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
                    Log.e(TAG, "createNote (folder lookup) failed", it)
                    _screen.value = AppScreen.Error(userMessage(it))
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
    fun showList() {
        savedState.remove<String>(KEY_DETAIL_OPEN_ID)
        _screen.value = AppScreen.NotesList
    }

    /** A failure on the detail screen that has no in-place recovery. */
    fun showError(msg: String) {
        savedState.remove<String>(KEY_DETAIL_OPEN_ID)
        _screen.value = AppScreen.Error(msg)
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
