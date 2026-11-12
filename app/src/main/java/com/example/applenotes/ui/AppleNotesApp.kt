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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
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
import com.example.applenotes.ui.settings.SettingsScreen
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
    when (val s = screen) {
        AppScreen.Splash -> CenteredSpinner("Starting up…")
        AppScreen.Login -> {
            val staleToken by appVm.loginStaleToken.collectAsStateWithLifecycle()
            LoginScreen(onCookiesArrived = appVm::onCookiesArrived, staleToken = staleToken)
        }
        is AppScreen.Loading -> CenteredSpinner(s.msg)
        is AppScreen.Error -> ErrorScreen(
            message = s.msg,
            onRetry = appVm::retryBootstrap,
            onSignOut = signOut,
            details = s.details,
        )
        AppScreen.NotesList -> ListRoute(appVm, signOut)
        AppScreen.Settings -> SettingsScreen(onBack = { appVm.showList() })
        is AppScreen.Detail -> DetailRoute(s, appVm, detailVm)
    }
}

@Composable
private fun ListRoute(appVm: AppViewModel, signOut: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exportVm: BulkExportViewModel = viewModel(factory = BulkExportViewModel.factory(context))
    val list by appVm.listState.collectAsStateWithLifecycle()
    val message by appVm.listMessage.collectAsStateWithLifecycle()
    val online by appVm.online.collectAsStateWithLifecycle()
    val exportState by exportVm.state.collectAsStateWithLifecycle()

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
                .onFailure { Toast.makeText(context, "Couldn't import: ${it.message}", Toast.LENGTH_LONG).show() }
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
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            exportVm.messageShown()
        }
    }

    NotesListScreen(
        allNotes = list.notes,
        folders = list.folders,
        selectedFolder = list.selectedFolder,
        onSelectFolder = appVm::selectFolder,
        onSelect = appVm::openNote,
        onRefresh = appVm::refresh,
        onCreateNote = appVm::createNote,
        onSignOut = signOut,
        message = message,
        onMessageAction = appVm::onListMessageAction,
        onMessageDismissed = appVm::onListMessageDismissed,
        offline = !online,
        drawerActions = listOf(
            DrawerAction("Import .txt / .md file…", Icons.Default.FileOpen) {
                importLauncher.launch(IMPORT_MIME_TYPES)
            },
            DrawerAction("Export folder as Markdown…", Icons.Default.Description) {
                exportFormat.value = ExportFormat.MARKDOWN
                exportLauncher.launch(null)
            },
            DrawerAction("Export folder as HTML…", Icons.Default.Code) {
                exportFormat.value = ExportFormat.HTML
                exportLauncher.launch(null)
            },
            DrawerAction("Settings", Icons.Default.Settings) { appVm.showSettings() },
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
) {
    val context = LocalContext.current
    LaunchedEffect(screen.openId) {
        detailVm.open(screen.openId, screen.record, screen.pendingFolderRef, screen.prefill)
    }
    val status by detailVm.status.collectAsStateWithLifecycle()
    if (status.openId != screen.openId) {
        // The frame before open() has run.
        CenteredSpinner("Loading note…")
        return
    }
    // Recent-note launcher shortcuts: saved notes only, title only.
    val record = detailVm.record
    LaunchedEffect(record?.recordName, record?.recordChangeTag != null) {
        if (record != null && record.recordChangeTag != null) {
            NoteShortcuts.reportOpened(context, record.recordName, detailVm.titleDraft)
        }
    }
    val leave = { message: String? ->
        detailVm.close()
        appVm.showList(message)
    }
    LaunchedEffect(status.exitRequested) {
        if (status.exitRequested) leave(status.exitMessage)
    }
    NoteDetailScreen(vm = detailVm, onBack = { leave(null) }, onSignIn = appVm::signInAgain)
}
