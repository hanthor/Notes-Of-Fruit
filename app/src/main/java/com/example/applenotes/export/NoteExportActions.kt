package com.example.applenotes.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.example.applenotes.data.ImageSource
import com.example.applenotes.proto.NoteBodyEditor.AttrSpan
import com.example.applenotes.ui.detail.AttachmentContent
import com.example.applenotes.ui.detail.AttachmentImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "NotesExport"

/** What the export / share / print actions need from the open note. */
internal class NoteSnapshot(
    val title: String,
    /** Full visible text (title line + body). */
    val text: String,
    /** Attribute runs over [text]. */
    val spans: List<AttrSpan>,
    /**
     * Image attachments by attachment recordName: the original image when
     * known, else its preview. Downloaded (or read from the attachment
     * cache) only when an export needs them; see [loadImages].
     */
    val imageSources: Map<String, ImageSource>,
) {
    /** The images' bytes; ones that can't be downloaded are left out (they export as placeholders). */
    suspend fun loadImages(load: suspend (ImageSource) -> ByteArray?): Map<String, ExportImage> =
        imageSources.mapNotNull { (rn, source) -> load(source)?.let { rn to ExportImage(it) } }.toMap()

    companion object {
        fun of(title: String, text: String, spans: List<AttrSpan>, attachments: Map<String, AttachmentContent>) =
            NoteSnapshot(
                title = title,
                text = text,
                spans = spans,
                imageSources = attachments.mapNotNull { (rn, c) ->
                    (c as? AttachmentContent.Image)?.let { rn to it.full }
                }.toMap(),
            )
    }
}

/** Share / export / print for the detail screen. Read-only: nothing here writes to iCloud. */
internal class NoteExportActions(
    val share: () -> Unit,
    val exportAs: (ExportFormat) -> Unit,
    /** Markdown plus the already-loaded images as separate files, into a folder. */
    val exportWithImages: () -> Unit,
    val print: () -> Unit,
)

@Composable
internal fun rememberNoteExportActions(snapshot: () -> NoteSnapshot): NoteExportActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val imageLoader = remember(context) { AttachmentImageLoader.get(context) }
    suspend fun images(s: NoteSnapshot) = s.loadImages { imageLoader.bytes(it) }
    // Keeps the print WebView alive until the print dialog has its adapter.
    val printView = remember { mutableStateOf<WebView?>(null) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    fun writeSingle(uri: Uri?, format: ExportFormat) {
        if (uri == null) return
        val s = snapshot()
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val refs = if (format == ExportFormat.HTML) images(s).mapValues { dataUri(it.value) } else emptyMap()
                    SafIo.writeText(context.contentResolver, uri, renderNoteForExport(format, s.title, s.text, s.spans, refs))
                }
            }
            result.onFailure { Log.w(TAG, "export failed", it) }
            toast(if (result.isSuccess) "Exported" else "Export failed: ${result.exceptionOrNull()?.message}")
        }
    }

    val mdLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.MARKDOWN.mimeType)) {
        writeSingle(it, ExportFormat.MARKDOWN)
    }
    val htmlLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.HTML.mimeType)) {
        writeSingle(it, ExportFormat.HTML)
    }
    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree == null) return@rememberLauncherForActivityResult
        val s = snapshot()
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { writeWithImages(context, tree, s, images(s)) }
            }
            result.onFailure { Log.w(TAG, "export with images failed", it) }
            toast(if (result.isSuccess) "Exported" else "Export failed: ${result.exceptionOrNull()?.message}")
        }
    }

    return remember(mdLauncher, htmlLauncher, treeLauncher) {
        NoteExportActions(
            share = {
                val s = snapshot()
                context.startActivity(shareIntent(s))
            },
            exportAs = { format ->
                val name = ExportFileNames().next(snapshot().title, format.extension)
                when (format) {
                    ExportFormat.MARKDOWN -> mdLauncher.launch(name)
                    ExportFormat.HTML -> htmlLauncher.launch(name)
                }
            },
            exportWithImages = { treeLauncher.launch(null) },
            print = {
                val s = snapshot()
                scope.launch {
                    val html = withContext(Dispatchers.IO) {
                        val refs = images(s).mapValues { dataUri(it.value) }
                        NoteHtml.document(s.title, s.text, s.spans) { span ->
                            span.attachmentRecordName?.let { refs[it] }?.let { "<img src=\"${NoteHtml.escape(it)}\" alt=\"image\">" }
                        }
                    }
                    printView.value = NotePrinter.print(context, s.title.ifBlank { "Note" }, html)
                }
            },
        )
    }
}

private fun writeWithImages(context: Context, tree: Uri, s: NoteSnapshot, images: Map<String, ExportImage>) {
    val resolver = context.contentResolver
    val dir = SafIo.treeDocument(tree)
    val names = ExportFileNames()
    val noteName = names.next(s.title, ExportFormat.MARKDOWN.extension)
    val base = noteName.removeSuffix(".${ExportFormat.MARKDOWN.extension}")
    val refs = LinkedHashMap<String, String>()
    var n = 1
    for ((rn, image) in images) {
        val imageName = names.next("$base image ${n++}", image.extension)
        SafIo.createFile(resolver, dir, imageName, image.mimeType, image.bytes)
        refs[rn] = imageName
    }
    val md = renderNoteForExport(ExportFormat.MARKDOWN, s.title, s.text, s.spans, refs)
    SafIo.createFile(resolver, dir, noteName, ExportFormat.MARKDOWN.mimeType, md.toByteArray())
}

/**
 * The share sheet intent: plain text (attachments as "[attachment]") plus an
 * HTML version with the note's formatting for apps that accept it.
 */
internal fun shareIntent(s: NoteSnapshot): Intent {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, s.text.replace(ATTACHMENT_CHAR.toString(), NoteHtml.PLACEHOLDER))
        putExtra(Intent.EXTRA_HTML_TEXT, NoteHtml.fragment(s.text, s.spans))
        s.title.takeIf { it.isNotBlank() }?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
    }
    return Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
