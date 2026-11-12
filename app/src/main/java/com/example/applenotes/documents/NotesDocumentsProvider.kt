package com.example.applenotes.documents

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import com.example.applenotes.R
import com.example.applenotes.data.AppGraph
import com.example.applenotes.data.NotesRepository
import com.example.applenotes.log.AppLog
import com.example.applenotes.security.AppLockSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileNotFoundException

/**
 * Read-only "Notes of Fruit" location in the system file picker (issue #21):
 * folders → notes, each note a Markdown document generated on demand.
 *
 * - Listings come from the local cache (no network). A note's body comes
 *   from the copy cached when it was last opened, otherwise one lookup
 *   through the repository. Nothing is ever written to iCloud: no document
 *   supports write, delete, rename, move or create.
 * - Guarded by MANAGE_DOCUMENTS in the manifest, so only the system's
 *   documents UI binds it directly; other apps get access to a single
 *   document through the picker's URI grant.
 * - While the app lock is on, or nobody is signed in, there are no roots,
 *   and every call (including documents granted earlier) fails as not
 *   found. Root changes are announced on sign-in / sign-out / lock changes
 *   (see [notifyChanged]).
 */
class NotesDocumentsProvider : DocumentsProvider() {
    override fun onCreate(): Boolean = true

    private val app: Context get() = requireNotNull(context).applicationContext

    private fun repository(): NotesRepository = AppGraph.notesRepository(app)

    /** True when the provider may show anything at all. */
    private fun available(): Boolean {
        val lockOn = runCatching { runBlocking { AppLockSettings(app).enabled.first() } }.getOrDefault(true)
        return !lockOn && repository().hasSavedLogin()
    }

    private fun requireAvailable() {
        if (!available()) throw FileNotFoundException("Notes of Fruit is locked or signed out")
    }

    private fun tree(): NoteDocuments.Tree {
        val repo = repository()
        runBlocking { repo.loadCache() }
        return NoteDocuments.Tree(
            repo.folders.value,
            repo.notes.value,
            NoteDocuments.Labels(
                rootName = app.getString(R.string.app_name),
                defaultFolder = app.getString(R.string.documents_default_folder),
                untitledFolder = app.getString(R.string.documents_untitled_folder),
            ),
        )
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        cursor.setNotificationUri(app.contentResolver, DocumentsContract.buildRootsUri(authority(app)))
        if (!available()) return cursor
        cursor.newRow()
            .add(Root.COLUMN_ROOT_ID, ROOT_ID)
            .add(Root.COLUMN_DOCUMENT_ID, NoteDocuments.encode(NoteDocuments.Id.Root))
            .add(Root.COLUMN_TITLE, app.getString(R.string.app_name))
            .add(Root.COLUMN_SUMMARY, app.getString(R.string.documents_root_summary))
            .add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
            // No create, search or recents: read-only browsing only.
            .add(Root.COLUMN_FLAGS, 0)
            .add(Root.COLUMN_MIME_TYPES, "${NoteDocuments.MIME_DIR}\n${NoteDocuments.MIME_MARKDOWN}")
        return cursor
    }

    override fun queryDocument(documentId: String?, projection: Array<out String>?): Cursor {
        requireAvailable()
        val id = NoteDocuments.parse(documentId) ?: throw FileNotFoundException("unknown document")
        val doc = tree().document(id) ?: throw FileNotFoundException("no such document")
        return MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION).also { addRow(it, doc) }
    }

    override fun queryChildDocuments(
        parentDocumentId: String?,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        requireAvailable()
        val parent = NoteDocuments.parse(parentDocumentId) ?: throw FileNotFoundException("unknown document")
        val children = tree().children(parent) ?: throw FileNotFoundException("not a folder")
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        cursor.setNotificationUri(
            app.contentResolver,
            DocumentsContract.buildChildDocumentsUri(authority(app), parentDocumentId),
        )
        for (doc in children) addRow(cursor, doc)
        return cursor
    }

    override fun openDocument(documentId: String?, mode: String?, signal: CancellationSignal?): ParcelFileDescriptor {
        if (mode != "r") throw UnsupportedOperationException("Notes of Fruit documents are read-only")
        requireAvailable()
        val id = NoteDocuments.parse(documentId) as? NoteDocuments.Id.Note
            ?: throw FileNotFoundException("not a note")
        // Only notes in the cached list (Recently Deleted excluded) can be opened.
        tree().document(id) ?: throw FileNotFoundException("no such note")
        val title = repository().notes.value.firstOrNull { it.recordName == id.recordName }?.title
        signal?.throwIfCanceled()
        val markdown: String? = try {
            runBlocking(Dispatchers.IO) {
                withTimeout(OPEN_TIMEOUT_MS) {
                    NoteDocuments.markdown(repository().noteForReading(id.recordName), title)
                }
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw FileNotFoundException("timed out")
        } catch (e: Exception) {
            AppLog.w(TAG, "couldn't open a note for the documents provider", e)
            throw FileNotFoundException("couldn't load the note")
        }
        if (markdown == null) throw FileNotFoundException("this note can't be shown as Markdown")
        signal?.throwIfCanceled()
        // A private temp file, opened read-only and unlinked straight away:
        // the reader gets a seekable descriptor and nothing stays on disk.
        val dir = File(app.cacheDir, TEMP_DIR).apply { mkdirs() }
        val file = File.createTempFile("note", ".md", dir)
        try {
            file.writeText(markdown)
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } finally {
            file.delete()
        }
    }

    private fun addRow(cursor: MatrixCursor, doc: NoteDocuments.Doc) {
        cursor.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, doc.documentId)
            .add(Document.COLUMN_DISPLAY_NAME, doc.displayName)
            .add(Document.COLUMN_MIME_TYPE, doc.mimeType)
            .add(Document.COLUMN_LAST_MODIFIED, doc.lastModifiedMs?.takeIf { it > 0 })
            // Size unknown until the Markdown is generated.
            .add(Document.COLUMN_SIZE, null)
            // No FLAG_SUPPORTS_WRITE / DELETE / RENAME / MOVE / COPY, no DIR_SUPPORTS_CREATE.
            .add(Document.COLUMN_FLAGS, 0)
    }

    companion object {
        private const val TAG = "NotesDocuments"
        private const val ROOT_ID = "notes"
        private const val TEMP_DIR = "documents"
        private const val OPEN_TIMEOUT_MS = 30_000L

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY,
            Root.COLUMN_ICON, Root.COLUMN_FLAGS, Root.COLUMN_MIME_TYPES,
        )
        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_SIZE, Document.COLUMN_FLAGS,
        )

        /** Must match android:authorities in the manifest. */
        fun authority(context: Context): String = context.packageName + ".documents"

        /**
         * Tell the documents UI that roots and listings changed (sign-in,
         * sign-out, app lock toggled, cache updated).
         */
        fun notifyChanged(context: Context) {
            val auth = authority(context)
            val resolver = context.contentResolver
            runCatching {
                resolver.notifyChange(DocumentsContract.buildRootsUri(auth), null)
                // Every listing URI lives under this prefix; notifying it reaches them all.
                resolver.notifyChange(Uri.Builder().scheme("content").authority(auth).build(), null)
            }.onFailure { AppLog.w(TAG, "couldn't notify document changes", it) }
        }
    }
}
