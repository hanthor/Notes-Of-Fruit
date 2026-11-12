package com.example.applenotes.documents

import com.example.applenotes.client.FolderSummary
import com.example.applenotes.client.NoteRecord
import com.example.applenotes.client.NoteSummary
import com.example.applenotes.data.DEFAULT_FOLDER_RECORD_NAME
import com.example.applenotes.data.NoteBody
import com.example.applenotes.data.TRASH_RECORD_NAME
import com.example.applenotes.data.decodeNoteBody
import com.example.applenotes.export.ExportFileNames
import com.example.applenotes.export.ExportFormat
import com.example.applenotes.export.exportTitleOf
import com.example.applenotes.export.renderNoteForExport
import com.example.applenotes.integrations.isPlausibleRecordName

/**
 * The Android-free half of [NotesDocumentsProvider]: the document-id scheme,
 * the folder → note tree built from the cached list, and the Markdown a note
 * is served as. Everything is read-only.
 *
 * Document ids:
 *  - `root`               the provider's single root (a directory of folders)
 *  - `folder:<recordName>` a folder (directory of notes)
 *  - `note:<recordName>`   a note, served as `text/markdown`
 *
 * recordNames are checked against the shape CloudKit uses, so a crafted id
 * can't smuggle anything else in.
 */
internal object NoteDocuments {
    const val MIME_MARKDOWN = "text/markdown"

    /** Mirrors DocumentsContract.Document.MIME_TYPE_DIR (kept here so this file stays Android-free). */
    const val MIME_DIR = "vnd.android.document/directory"

    sealed interface Id {
        data object Root : Id
        data class Folder(val recordName: String) : Id
        data class Note(val recordName: String) : Id
    }

    private const val ROOT = "root"
    private const val FOLDER_PREFIX = "folder:"
    private const val NOTE_PREFIX = "note:"

    fun encode(id: Id): String = when (id) {
        Id.Root -> ROOT
        is Id.Folder -> FOLDER_PREFIX + id.recordName
        is Id.Note -> NOTE_PREFIX + id.recordName
    }

    fun parse(documentId: String?): Id? = when {
        documentId == null -> null
        documentId == ROOT -> Id.Root
        documentId.startsWith(FOLDER_PREFIX) ->
            documentId.removePrefix(FOLDER_PREFIX).takeIf(::isPlausibleRecordName)?.let { Id.Folder(it) }
        documentId.startsWith(NOTE_PREFIX) ->
            documentId.removePrefix(NOTE_PREFIX).takeIf(::isPlausibleRecordName)?.let { Id.Note(it) }
        else -> null
    }

    /** One row of a documents cursor. */
    data class Doc(
        val id: Id,
        val displayName: String,
        val mimeType: String,
        val lastModifiedMs: Long?,
    ) {
        val documentId: String get() = encode(id)
    }

    /** The user-visible labels (resources in the app, plain strings in tests). */
    data class Labels(val rootName: String, val defaultFolder: String, val untitledFolder: String)

    /**
     * The cached list as a tree: every folder that holds a note or that
     * iCloud listed, except Recently Deleted (as in the app's default view).
     */
    class Tree(folders: List<FolderSummary>, notes: List<NoteSummary>, private val labels: Labels) {
        private val visibleNotes = notes.filter { !it.deleted && folderOf(it) != TRASH_RECORD_NAME }
        private val byFolder: Map<String, List<NoteSummary>> = visibleNotes.groupBy(::folderOf)

        private val folderDocs: List<Doc> = run {
            val known = folders.filter { !it.isTrash && it.recordName != TRASH_RECORD_NAME }
                .filter { isPlausibleRecordName(it.recordName) }
            val names = known.associate { it.recordName to folderName(it.recordName, it.displayName, it.isDefault) }
            val all = LinkedHashMap(names)
            for (rn in byFolder.keys) if (rn !in all && isPlausibleRecordName(rn)) all[rn] = folderName(rn, null, false)
            all.map { (rn, name) ->
                Doc(Id.Folder(rn), name, MIME_DIR, byFolder[rn]?.maxOfOrNull { it.modificationTimestampMs ?: 0L })
            }.sortedWith(
                // The default "Notes" folder first, then by name.
                compareBy<Doc>({ (it.id as Id.Folder).recordName != DEFAULT_FOLDER_RECORD_NAME }, { it.displayName.lowercase() }),
            )
        }

        private fun folderName(recordName: String, displayName: String?, isDefault: Boolean): String = when {
            isDefault || recordName == DEFAULT_FOLDER_RECORD_NAME -> displayName?.takeIf { it.isNotBlank() } ?: labels.defaultFolder
            else -> displayName?.trim()?.takeIf { it.isNotEmpty() } ?: labels.untitledFolder
        }

        /** Children of [parent], or null if [parent] is not a directory in this tree. */
        fun children(parent: Id): List<Doc>? = when (parent) {
            Id.Root -> folderDocs
            is Id.Folder -> if (folderDocs.none { it.id == parent }) null else notesIn(parent.recordName)
            is Id.Note -> null
        }

        /** The row for [id], or null if it isn't in the cached list. */
        fun document(id: Id): Doc? = when (id) {
            Id.Root -> Doc(Id.Root, labels.rootName, MIME_DIR, null)
            is Id.Folder -> folderDocs.firstOrNull { it.id == id }
            is Id.Note -> visibleNotes.firstOrNull { it.recordName == id.recordName }
                ?.let { n -> notesIn(folderOf(n)).firstOrNull { it.id == id } }
        }

        /** `Title.md` names, unique within the folder and stable between calls (sorted by recordName). */
        private fun notesIn(folder: String): List<Doc> {
            val names = ExportFileNames()
            return byFolder[folder].orEmpty()
                .sortedBy { it.recordName }
                .map { n ->
                    Doc(Id.Note(n.recordName), names.next(n.title, ExportFormat.MARKDOWN.extension), MIME_MARKDOWN, n.modificationTimestampMs)
                }
                .sortedByDescending { it.lastModifiedMs ?: 0L }
        }

        private fun folderOf(n: NoteSummary): String = n.folderRecordName ?: DEFAULT_FOLDER_RECORD_NAME
    }

    /**
     * The note as Markdown (the same converter as Export as Markdown), or
     * null when its body can't be shown: password-locked notes (never
     * decrypted), Apple's newer format, or an undecodable body. Only cleanly
     * decoded bodies are served: the best-effort fallback the editor shows
     * read-only can include deleted (tombstoned) text, which must not leave
     * the app. Attachments become placeholders; their bytes aren't fetched.
     */
    fun markdown(record: NoteRecord, fallbackTitle: String?): String? {
        val body = decodeNoteBody(record) as? NoteBody.Editable ?: return null
        val text = body.text
        val title = exportTitleOf(text).ifEmpty { fallbackTitle.orEmpty() }
        return renderNoteForExport(ExportFormat.MARKDOWN, title, text, body.spans)
    }
}
