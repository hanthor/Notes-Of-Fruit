package com.example.applenotes.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.applenotes.data.TRASH_RECORD_NAME
import com.example.applenotes.export.BulkExportViewModel
import com.example.applenotes.export.ExportFormat
import com.example.applenotes.export.IMPORT_MIME_TYPES
import com.example.applenotes.export.readImport
import com.example.applenotes.integrations.IncomingRequest
import com.example.applenotes.integrations.NoteShortcuts
import com.example.applenotes.integrations.PendingIncoming
import com.example.applenotes.ui.common.AlphaWarningDialog
import com.example.applenotes.ui.common.CenteredSpinner
import com.example.applenotes.ui.common.ErrorScreen
import com.example.applenotes.ui.detail.NoteDetailScreen
import com.example.applenotes.ui.detail.NoteDetailViewModel
import com.example.applenotes.ui.list.BulkExportProgressDialog
import com.example.applenotes.ui.list.DrawerAction
import com.example.applenotes.ui.list.NotesListScreen
import com.example.applenotes.ui.login.LoginScreen
import com.example.applenotes.ui.settings.AllowedAppsScreen
import com.example.applenotes.ui.settings.SettingsScreen
import androidx.compose.ui.res.stringResource
import com.example.applenotes.R
import com.example.applenotes.data.userMessage
import com.example.applenotes.ui.common.UiText
import com.example.applenotes.ui.common.asString
import com.example.applenotes.ui.common.uiText
import com.example.applenotes.diagnostics.LastOpenedNote
import com.example.applenotes.diagnostics.NoteShape
import com.example.applenotes.update.UpdateBanner
import com.example.applenotes.update.UpdateViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Top-level screen switch. All state lives in [AppViewModel] (navigation,
 * session, list) and [NoteDetailViewModel] (the open note + editor draft),
 * so rotation / dark-mode recreation keeps the current screen and draft.
 */
@Composable
fun AppleNotesApp() {
    val context = LocalContext.current
    val appVm: AppViewModel = viewModel(factory = AppViewModel.factory(context))
    val detailVm: NoteDetailViewModel = viewModel(factory = NoteDetailViewModel.factory(context))

    // Requests from the share sheet, shortcuts, the QS tile, automation, …
    // (posted by IntegrationEntryActivity / AutomationActivity). AppViewModel
    // holds them until the user is signed in.
    val incoming by PendingIncoming.request.collectAsStateWithLifecycle()
    LaunchedEffect(incoming) {
        if (incoming == null) return@LaunchedEffect
        val request = PendingIncoming.take() ?: return@LaunchedEffect
        // Leaving an open note for another one: save its draft first (the
        // ON_PAUSE auto-save normally already did, as the activity paused
        // before the new intent arrived).
        if (appVm.screen.value is AppScreen.Detail) detailVm.autoSave()
        appVm.handleIncoming(request)
    }

    val alphaAck by appVm.alphaAcknowledged.collectAsStateWithLifecycle()
    if (alphaAck == false) {
        AlphaWarningDialog(onAcknowledge = appVm::acknowledgeAlpha)
        return
    }
    if (alphaAck == null) {
        // Still loading the ack flag — render nothing; the splash theme is
        // still showing underneath.
        return
    }

    val signOut = {
        NoteShortcuts.clearAll(context)
        appVm.signOut()
    }

    val screen by appVm.screen.collectAsStateWithLifecycle()
    // Issue #16: wide windows show the list and the open note side by side.
    val twoPane = rememberTwoPane()
    LaunchedEffect(twoPane) { appVm.setTwoPane(twoPane) }
    val current = screen
    if (twoPane && showsListAndDetail(current)) {
        TwoPaneLayout(
            list = { ListRoute(appVm, signOut, openNote = (current as? AppScreen.Detail)?.record?.recordName) },
            detail = {
                when (current) {
                    is AppScreen.Detail -> DetailRoute(current, appVm, detailVm, twoPane = true)
                    is AppScreen.Loading -> CenteredSpinner(current.msg.asString())
                    else -> NoNoteSelectedPane()
                }
            },
        )
        return
    }
    when (val s = current) {
        AppScreen.Splash -> CenteredSpinner(stringResource(R.string.loading_starting))
        AppScreen.Login -> {
            val staleToken by appVm.loginStaleToken.collectAsStateWithLifecycle()
            LoginScreen(onCookiesArrived = appVm::onCookiesArrived, staleToken = staleToken)
        }
        is AppScreen.Loading -> CenteredSpinner(s.msg.asString())
        is AppScreen.Error -> ErrorScreen(
            message = s.msg,
            onRetry = appVm::retryBootstrap,
            onSignOut = signOut,
            details = s.details,
        )
        AppScreen.NotesList -> ListRoute(appVm, signOut)
        AppScreen.Settings -> SettingsScreen(onBack = { appVm.showList() }, onOpenAllowedApps = appVm::showAllowedApps)
        AppScreen.AllowedApps -> AllowedAppsScreen(onBack = appVm::showSettings)
        is AppScreen.Detail -> DetailRoute(s, appVm, detailVm)
    }
}

@Composable
private fun ListRoute(
    appVm: AppViewModel,
    signOut: () -> Unit,
    /** Two-pane mode: the note shown next to the list, highlighted in it. */
    openNote: String? = null,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val exportVm: BulkExportViewModel = viewModel(factory = BulkExportViewModel.factory(context))
    val list by appVm.listState.collectAsStateWithLifecycle()
    val message by appVm.listMessage.collectAsStateWithLifecycle()
    val online by appVm.online.collectAsStateWithLifecycle()
    val exportState by exportVm.state.collectAsStateWithLifecycle()
    // Opt-in update check (issue #22): once per process, at most daily.
    val updateVm: UpdateViewModel = viewModel(factory = UpdateViewModel.factory(context))
    val update by updateVm.available.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { updateVm.checkOnLaunch() }

    // Refresh when the list comes back to the foreground (throttled in the ViewModel; issue #12).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) appVm.onAppResumed()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Launcher shortcuts for notes that no longer exist are removed.
    LaunchedEffect(list.notes) {
        if (list.notes.isNotEmpty()) NoteShortcuts.prune(context, list.notes.map { it.recordName }.toSet())
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { readImport(context.contentResolver, uri) } }
                // Opens a prefilled, unsaved draft in the editor.
                .onSuccess { appVm.handleIncoming(IncomingRequest.NewDraft(it)) }
                .onFailure {
                    val text = uiText(R.string.import_failed, userMessage(it)).resolve(resources)
                    Toast.makeText(context, text, Toast.LENGTH_LONG).show()
                }
        }
    }
    val exportFormat = remember { mutableStateOf(ExportFormat.MARKDOWN) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree == null) return@rememberLauncherForActivityResult
        val folder = list.selectedFolder
        val notes = when (folder) {
            null -> list.notes.filter { it.folderRecordName != TRASH_RECORD_NAME }
            else -> list.notes.filter { it.folderRecordName == folder }
        }
        exportVm.start(context, tree, notes, exportFormat.value)
    }

    LaunchedEffect(exportState.message) {
        exportState.message?.let {
            Toast.makeText(context, it.resolve(resources), Toast.LENGTH_LONG).show()
            exportVm.messageShown()
        }
    }

    NotesListScreen(
        allNotes = list.notes,
        folders = list.folders,
        selectedFolder = list.selectedFolder,
        onSelectFolder = appVm::selectFolder,
        // Through the two-pane-aware entry points: with a note open beside
        // the list, it is left (and saved) first.
        onSelect = appVm::selectNote,
        selectedNote = openNote,
        topBanner = { update?.let { UpdateBanner(it, onDismiss = updateVm::dismiss) } },
        onRefresh = appVm::refresh,
        onCreateNote = appVm::requestCreateNote,
        onSignOut = signOut,
        message = message,
        onMessageAction = appVm::onListMessageAction,
        onMessageDismissed = appVm::onListMessageDismissed,
        offline = !online,
        syncing = list.syncing,
        refreshing = list.refreshing,
        unsentCount = list.unsentCount,
        onRetryUnsent = appVm::retryUnsent,
        searchBodies = appVm::searchCachedBodies,
        drawerActions = listOf(
            DrawerAction(UiText.Res(R.string.drawer_import), Icons.Default.FileOpen) {
                importLauncher.launch(IMPORT_MIME_TYPES)
            },
            DrawerAction(UiText.Res(R.string.drawer_export_markdown), Icons.Default.Description) {
                exportFormat.value = ExportFormat.MARKDOWN
                exportLauncher.launch(null)
            },
            DrawerAction(UiText.Res(R.string.drawer_export_html), Icons.Default.Code) {
                exportFormat.value = ExportFormat.HTML
                exportLauncher.launch(null)
            },
            DrawerAction(UiText.Res(R.string.drawer_settings), Icons.Default.Settings) { appVm.requestSettings() },
        ),
    )
    if (exportState.running) {
        BulkExportProgressDialog(done = exportState.done, total = exportState.total, onCancel = exportVm::cancel)
    }
}

@Composable
private fun DetailRoute(
    screen: AppScreen.Detail,
    appVm: AppViewModel,
    detailVm: NoteDetailViewModel,
    twoPane: Boolean = false,
) {
    val context = LocalContext.current
    LaunchedEffect(screen.openId) {
        detailVm.open(screen.openId, screen.record, screen.pendingFolderRef, screen.prefill, screen.offlineCopy)
    }
    val status by detailVm.status.collectAsStateWithLifecycle()
    if (status.openId != screen.openId) {
        // The frame before open() has run.
        CenteredSpinner(stringResource(R.string.loading_note))
        return
    }
    // Recent-note launcher shortcuts: saved notes only, title only.
    val record = detailVm.record
    // Counts only, for "Copy diagnostic report" (issue #22).
    LaunchedEffect(record?.recordName, record?.recordChangeTag) {
        if (record != null) LastOpenedNote.shape = withContext(Dispatchers.Default) { NoteShape.of(record) }
    }
    LaunchedEffect(record?.recordName, record?.recordChangeTag != null) {
        if (record != null && record.recordChangeTag != null) {
            NoteShortcuts.reportOpened(context, record.recordName, detailVm.titleDraft)
        }
    }
    val leave = { message: UiText? ->
        detailVm.close()
        appVm.showList(message)
    }
    LaunchedEffect(status.exitRequested) {
        if (status.exitRequested) leave(status.exitMessage)
    }
    // Two-pane switching (issue #16): another note / New note / Settings was
    // picked while this one is open. The screen runs its Back path; if the
    // note stays open instead (the save failed, a question is up), the
    // switch is dropped so a later Back just closes the note.
    val leaveRequest by appVm.detailLeaveRequest.collectAsStateWithLifecycle()
    LaunchedEffect(status.saving, status.saveFailed, status.conflict != null, status.savedDraft != null) {
        val stays = status.saveFailed || status.conflict != null || status.savedDraft != null
        if (!status.saving && !status.exitRequested && stays) appVm.cancelPendingSwitch()
    }
    NoteDetailScreen(
        vm = detailVm,
        onBack = { leave(null) },
        onSignIn = appVm::signInAgain,
        leaveRequest = leaveRequest,
        onLeaveRequestHandled = appVm::onDetailLeaveRequestHandled,
        onLeaveCancelled = appVm::cancelPendingSwitch,
        inTwoPane = twoPane,
    )
}
