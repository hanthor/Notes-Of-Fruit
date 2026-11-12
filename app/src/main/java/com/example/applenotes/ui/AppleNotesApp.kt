package com.example.applenotes.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.applenotes.ui.common.AlphaWarningDialog
import com.example.applenotes.ui.common.CenteredSpinner
import com.example.applenotes.ui.common.ErrorScreen
import com.example.applenotes.ui.detail.NoteDetailScreen
import com.example.applenotes.ui.detail.NoteDetailViewModel
import com.example.applenotes.ui.list.NotesListScreen
import com.example.applenotes.ui.login.LoginScreen

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
            onSignOut = appVm::signOut,
            details = s.details,
        )
        AppScreen.NotesList -> {
            val list by appVm.listState.collectAsStateWithLifecycle()
            val message by appVm.listMessage.collectAsStateWithLifecycle()
            val online by appVm.online.collectAsStateWithLifecycle()
            NotesListScreen(
                allNotes = list.notes,
                folders = list.folders,
                selectedFolder = list.selectedFolder,
                onSelectFolder = appVm::selectFolder,
                onSelect = appVm::openNote,
                onRefresh = appVm::refresh,
                onCreateNote = appVm::createNote,
                onSignOut = appVm::signOut,
                message = message,
                onMessageAction = appVm::onListMessageAction,
                onMessageDismissed = appVm::onListMessageDismissed,
                offline = !online,
            )
        }
        is AppScreen.Detail -> DetailRoute(s, appVm, detailVm)
    }
}

@Composable
private fun DetailRoute(
    screen: AppScreen.Detail,
    appVm: AppViewModel,
    detailVm: NoteDetailViewModel,
) {
    LaunchedEffect(screen.openId) {
        detailVm.open(screen.openId, screen.record, screen.pendingFolderRef)
    }
    val status by detailVm.status.collectAsStateWithLifecycle()
    if (status.openId != screen.openId) {
        // The frame before open() has run.
        CenteredSpinner("Loading note…")
        return
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
