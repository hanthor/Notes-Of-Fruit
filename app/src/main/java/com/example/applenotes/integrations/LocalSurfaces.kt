package com.example.applenotes.integrations

import android.content.Context
import com.example.applenotes.client.NoteSummary
import com.example.applenotes.data.NotesRepository
import com.example.applenotes.documents.NotesDocumentsProvider
import com.example.applenotes.security.AppLockSettings
import com.example.applenotes.widget.RecentNotesWidget
import com.example.applenotes.widget.WidgetContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the surfaces outside the app that show cached notes (the home-screen
 * widget and the documents provider) in step with the cache, the sign-in
 * state and the app lock. The hook is deliberately small: it observes
 * [NotesRepository.notes] (which every sync, save, delete and sign-out
 * updates) and [AppLockSettings.enabled], and asks both surfaces to refresh;
 * they re-read what they show themselves.
 */
internal object LocalSurfaces {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Process start: follow the app-lock toggle. */
    fun start(app: Context) {
        scope.launch {
            AppLockSettings(app).enabled
                .drop(1) // the current value; the surfaces read it themselves when they render
                .collect { changed(app) }
        }
    }

    /** The repository was created: follow the cache and the sign-in state. */
    fun attach(app: Context, repository: NotesRepository) {
        scope.launch {
            repository.notes
                .map { snapshot(repository.hasSavedLogin(), it) }
                .distinctUntilChanged()
                .drop(1)
                .conflate()
                .collect { changed(app) }
        }
    }

    /** What, from the cache, can change what the surfaces show (lock aside). */
    internal fun snapshot(signedIn: Boolean, notes: List<NoteSummary>): Pair<WidgetContent, Set<Triple<String, String?, String?>>> =
        WidgetContent.of(appLockOn = false, signedIn = signedIn, notes = notes) to
            notes.map { Triple(it.recordName, it.folderRecordName, it.title) }.toSet()

    private suspend fun changed(app: Context) {
        NotesDocumentsProvider.notifyChanged(app)
        RecentNotesWidget.refresh(app)
    }
}
