package com.example.applenotes.export

import android.content.Context
import android.net.Uri
import com.example.applenotes.log.AppLog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.applenotes.client.NoteRecord
import com.example.applenotes.client.NoteSummary
import com.example.applenotes.data.AppGraph
import com.example.applenotes.data.NotesRepository
import com.example.applenotes.data.decodeNoteBody
import com.example.applenotes.data.runCatchingCancellable
import com.example.applenotes.data.userMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "NotesExport"

internal data class BulkExportResult(val written: Int, val failed: Int)

/**
 * Export [notes] one file per note. Read-only: each note is fetched with
 * [lookup] (the repository's lookupNote), one at a time, decoded with the
 * same [decodeNoteBody] the editor uses, rendered and handed to [write]
 * under a sanitized, unique file name. A note that can't be fetched or
 * decoded is counted as failed and skipped. Cancellation stops between notes.
 * Attachments become placeholders (their bytes aren't fetched here).
 */
internal suspend fun bulkExport(
    notes: List<NoteSummary>,
    format: ExportFormat,
    lookup: suspend (String) -> NoteRecord,
    write: suspend (fileName: String, mimeType: String, bytes: ByteArray) -> Unit,
    onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
): BulkExportResult {
    val names = ExportFileNames()
    var written = 0
    var failed = 0
    onProgress(0, notes.size)
    for ((i, summary) in notes.withIndex()) {
        val ok = runCatchingCancellable {
            val body = decodeNoteBody(lookup(summary.recordName))
            val text = body.text ?: error("note body could not be decoded")
            val title = exportTitleOf(text).ifEmpty { summary.title.orEmpty() }
            val content = renderNoteForExport(format, title, text, body.spans)
            write(names.next(title, format.extension), format.mimeType, content.toByteArray())
        }.onFailure { AppLog.w(TAG, "skipping a note in bulk export", it) }.isSuccess
        if (ok) written++ else failed++
        onProgress(i + 1, notes.size)
    }
    return BulkExportResult(written, failed)
}

/** Drives [bulkExport] into a SAF tree for the list screen (progress + cancel). */
class BulkExportViewModel(private val repository: NotesRepository) : ViewModel() {

    data class State(
        val running: Boolean = false,
        val done: Int = 0,
        val total: Int = 0,
        /** Shown once the export ends (finished, cancelled or failed). */
        val message: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var job: Job? = null

    internal fun start(context: Context, tree: Uri, notes: List<NoteSummary>, format: ExportFormat) {
        if (job?.isActive == true) return
        val resolver = context.applicationContext.contentResolver
        _state.value = State(running = true, total = notes.size)
        job = viewModelScope.launch {
            val message = try {
                val dir = SafIo.treeDocument(tree)
                val r = bulkExport(
                    notes = notes,
                    format = format,
                    lookup = { repository.lookupNote(it) },
                    write = { name, mime, bytes ->
                        withContext(Dispatchers.IO) { SafIo.createFile(resolver, dir, name, mime, bytes) }
                    },
                    onProgress = { done, total -> _state.value = _state.value.copy(done = done, total = total) },
                )
                "Exported ${r.written} note${if (r.written == 1) "" else "s"}" +
                    if (r.failed > 0) " (${r.failed} couldn't be exported)" else ""
            } catch (e: CancellationException) {
                _state.value = State(message = "Export cancelled")
                throw e
            } catch (e: Exception) {
                AppLog.w(TAG, "bulk export failed", e)
                "Export failed: ${userMessage(e)}"
            }
            _state.value = State(message = message)
        }
    }

    fun cancel() {
        job?.cancel()
    }

    fun messageShown() {
        _state.value = _state.value.copy(message = null)
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            val app = context.applicationContext
            return viewModelFactory { initializer { BulkExportViewModel(AppGraph.notesRepository(app)) } }
        }
    }
}
