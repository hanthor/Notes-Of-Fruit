package com.example.applenotes.documents

import com.example.applenotes.client.FolderSummary
import com.example.applenotes.client.NoteRecord
import com.example.applenotes.client.NoteSummary
import com.example.applenotes.documents.NoteDocuments.Id
import com.example.applenotes.proto.NoteBodyEditor
import com.example.applenotes.proto.NoteBodyEditor.ParagraphStyle
import com.example.applenotes.proto.NoteCreator
import com.example.applenotes.testing.REPLICA_UUID
import com.example.applenotes.testing.noteRecord
import com.example.applenotes.testing.stringField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteDocumentsTest {
    private val labels = NoteDocuments.Labels(rootName = "Notes of Fruit", defaultFolder = "Notes", untitledFolder = "Untitled folder")

    private fun note(rn: String, title: String?, folder: String?, ts: Long = 1, deleted: Boolean = false) =
        NoteSummary(rn, title, null, ts, deleted, folder)

    // --- document ids ----------------------------------------------------

    @Test
    fun idsRoundTrip() {
        for (id in listOf(Id.Root, Id.Folder("DefaultFolder-CloudKit"), Id.Note("6A1C3F2E-0B9D-4E57-8F12-ABCDEF012345"))) {
            assertEquals(id, NoteDocuments.parse(NoteDocuments.encode(id)))
        }
        assertEquals("root", NoteDocuments.encode(Id.Root))
        assertEquals("folder:F1", NoteDocuments.encode(Id.Folder("F1")))
        assertEquals("note:N1", NoteDocuments.encode(Id.Note("N1")))
    }

    @Test
    fun rejectsMalformedIds() {
        for (bad in listOf(null, "", "Root", "note:", "folder:", "note:../etc/passwd", "note:a/b", "note:a b",
            "nope:x", "note:" + "x".repeat(201), "note:a\u0000")) {
            assertNull(bad, NoteDocuments.parse(bad))
        }
    }

    // --- tree --------------------------------------------------------------

    @Test
    fun foldersThenNotesWithoutTrash() {
        val folders = listOf(
            FolderSummary("F-work", "Work", isTrash = false, isDefault = false),
            FolderSummary("DefaultFolder-CloudKit", null, isTrash = false, isDefault = true),
            FolderSummary("TrashFolder-CloudKit", "Recently Deleted", isTrash = true, isDefault = false),
            FolderSummary("F-empty", "  ", isTrash = false, isDefault = false),
        )
        val notes = listOf(
            note("n1", "Shopping", "DefaultFolder-CloudKit", ts = 10),
            note("n2", "Plan", "F-work", ts = 30),
            note("n3", "Old", "TrashFolder-CloudKit"),
            note("n4", "Gone", "F-work", deleted = true),
            note("n5", "No folder", null, ts = 20),
            note("n6", "Elsewhere", "F-unlisted", ts = 5),
        )
        val tree = NoteDocuments.Tree(folders, notes, labels)
        val root = tree.children(Id.Root)!!
        assertEquals(listOf("Notes", "Untitled folder", "Untitled folder", "Work"), root.map { it.displayName })
        assertEquals(Id.Folder("DefaultFolder-CloudKit"), root.first().id)
        assertTrue(root.all { it.mimeType == NoteDocuments.MIME_DIR })
        assertTrue(root.none { (it.id as Id.Folder).recordName == "TrashFolder-CloudKit" })

        val inDefault = tree.children(Id.Folder("DefaultFolder-CloudKit"))!!
        // Newest first; a note with no folder reference lives in the default folder.
        assertEquals(listOf("No folder.md", "Shopping.md"), inDefault.map { it.displayName })
        assertTrue(inDefault.all { it.mimeType == "text/markdown" })
        assertEquals(listOf("Plan.md"), tree.children(Id.Folder("F-work"))!!.map { it.displayName })
        assertEquals(listOf("Elsewhere.md"), tree.children(Id.Folder("F-unlisted"))!!.map { it.displayName })

        assertNull("trash is not browsable", tree.children(Id.Folder("TrashFolder-CloudKit")))
        assertNull("trashed notes can't be opened", tree.document(Id.Note("n3")))
        assertNull("deleted notes can't be opened", tree.document(Id.Note("n4")))
        assertNull("a note is not a directory", tree.children(Id.Note("n1")))
        assertNull(tree.children(Id.Folder("F-unknown")))
        assertEquals("Shopping.md", tree.document(Id.Note("n1"))!!.displayName)
        assertEquals(10L, tree.document(Id.Note("n1"))!!.lastModifiedMs)
        assertEquals("Notes of Fruit", tree.document(Id.Root)!!.displayName)
    }

    @Test
    fun noteNamesAreSanitizedUniqueAndStable() {
        val notes = listOf(
            note("b", "Same/Title", "F", ts = 1),
            note("a", "Same/Title", "F", ts = 2),
            note("c", null, "F", ts = 3),
        )
        val tree = NoteDocuments.Tree(emptyList(), notes, labels)
        val names = tree.children(Id.Folder("F"))!!.associate { (it.id as Id.Note).recordName to it.displayName }
        assertEquals(mapOf("a" to "Same_Title.md", "b" to "Same_Title (2).md", "c" to "Untitled note.md"), names)
        // queryDocument gives the same name as the listing.
        assertEquals("Same_Title (2).md", tree.document(Id.Note("b"))!!.displayName)
        // ... and so does a listing built from the same list in another order.
        val again = NoteDocuments.Tree(emptyList(), notes.reversed(), labels)
        assertEquals("Same_Title (2).md", again.document(Id.Note("b"))!!.displayName)
    }

    // --- markdown ----------------------------------------------------------

    @Test
    fun markdownUsesTheExportConverter() {
        val bytes = NoteCreator.buildEmptyNoteBytes(REPLICA_UUID, "Groceries\nmilk\neggs")
        val text = NoteBodyEditor.readVisibleText(bytes)!!
        val spans = NoteBodyEditor.setParagraphStyle(
            text, NoteBodyEditor.parseAttributeRuns(bytes), 10, 14, ParagraphStyle.CHECKBOX_LIST,
        )
        val styled = com.example.applenotes.proto.NoteAppender.setBodySpliceBytes(bytes, REPLICA_UUID, text, 0, spans)
        val b64 = kotlin.io.encoding.Base64.encode(com.example.applenotes.proto.Gzip.compress(styled))
        val md = NoteDocuments.markdown(noteRecord("n1", text = null, textB64 = b64), fallbackTitle = "ignored")!!
        assertTrue(md, md.startsWith("Groceries\n"))
        assertTrue(md, md.contains("- [ ] milk"))
        assertTrue(md, md.contains("eggs"))
    }

    @Test
    fun undecodableAndLockedNotesAreNotServed() {
        // Password-locked: never decrypted.
        val locked = NoteRecord(
            "n1", "Note", "tag",
            mapOf(
                "TextDataEncrypted" to stringField("AAAA"),
                "CryptoInitializationVector" to stringField("aXY="),
                "CryptoTag" to stringField("dGFn"),
                "CryptoWrappedKey" to stringField("a2V5"),
            ),
        )
        assertNull(NoteDocuments.markdown(locked, "Locked"))
        // Not a body we understand.
        assertNull(NoteDocuments.markdown(noteRecord("n2", text = null, textB64 = "bm90IGEgcHJvdG8="), "x"))
        // A saved record without a body.
        assertNull(NoteDocuments.markdown(noteRecord("n3", text = null), "x"))
    }
}
