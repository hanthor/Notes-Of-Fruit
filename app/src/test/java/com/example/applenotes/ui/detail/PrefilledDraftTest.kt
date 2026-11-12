package com.example.applenotes.ui.detail

import androidx.lifecycle.SavedStateHandle
import com.example.applenotes.client.NoteRecord
import com.example.applenotes.data.NotesRepository
import com.example.applenotes.export.MarkdownImport
import com.example.applenotes.proto.NoteBodyEditor
import com.example.applenotes.proto.NoteBodyEditor.AttrSpan
import com.example.applenotes.proto.NoteBodyEditor.ParagraphStyle
import com.example.applenotes.testing.FakeAuthGateway
import com.example.applenotes.testing.FakeNotesApi
import com.example.applenotes.testing.MainDispatcherRule
import com.example.applenotes.testing.REPLICA_UUID
import com.example.applenotes.testing.folderRef
import com.example.applenotes.testing.visibleText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Drafts opened prefilled (share / import) and formatting on a draft's first save. */
class PrefilledDraftTest {
    @get:Rule
    val main = MainDispatcherRule()

    private lateinit var api: FakeNotesApi
    private lateinit var repo: NotesRepository

    @Before
    fun setUp() = runTest(main.dispatcher) {
        api = FakeNotesApi()
        repo = NotesRepository(FakeAuthGateway(api), { REPLICA_UUID }, CoroutineScope(SupervisorJob() + main.dispatcher))
        repo.signIn()
    }

    private fun draft() = NoteRecord("draft-1", "Note", null, emptyMap())

    @Test
    fun prefillOpensAsUnsavedChanges() {
        val vm = NoteDetailViewModel(repo, SavedStateHandle())
        vm.open("o", draft(), folderRef("DefaultFolder-CloudKit"), DraftPrefill.ofText("hello\nworld", "Subj"))
        assertEquals("Subj", vm.titleDraft)
        assertEquals("hello\nworld", vm.bodyDraft.text)
        assertTrue(vm.isModified)
        assertTrue(api.creates.isEmpty())

        vm.save(alsoExit = true)
        assertEquals("Subj", api.creates.single().title)
        assertEquals("hello\nworld", api.creates.single().body)
        // Plain text: no follow-up modify.
        assertTrue(api.modifies.isEmpty())
        assertTrue(vm.status.value.exitRequested)
    }

    @Test
    fun prefillIsIgnoredForSavedNotes() {
        val vm = NoteDetailViewModel(repo, SavedStateHandle())
        val rec = com.example.applenotes.testing.noteRecord("NOTE-A", "Existing\nbody")
        api.put(rec)
        vm.open("o", rec, null, DraftPrefill("Injected"))
        assertEquals("Existing", vm.titleDraft)
        assertFalse(vm.isModified)
    }

    @Test
    fun formattedDraftGetsItsSpansAppliedAfterCreate() {
        val imported = MarkdownImport.markdown("# Groceries\n- [ ] milk\n**bold** line")
        val vm = NoteDetailViewModel(repo, SavedStateHandle())
        vm.open("o", draft(), folderRef("DefaultFolder-CloudKit"), DraftPrefill.of(imported.text, imported.spans))
        assertEquals("Groceries", vm.titleDraft)

        vm.save(alsoExit = true)
        // createNote writes the text, then one modify applies the formatting
        // through the normal splice path, then the editor exits.
        assertEquals(1, api.creates.size)
        assertEquals(1, api.modifies.size)
        val saved = api.records.getValue(api.created())
        assertEquals("Groceries\nmilk\nbold line", saved.visibleText())
        val spans = NoteBodyEditor.parseAttributeRunsFromBase64(saved.stringField("TextDataEncrypted")!!)
        val chars = NoteBodyEditor.expandSpansToChars(saved.visibleText()!!, spans)
        assertEquals(ParagraphStyle.CHECKBOX_LIST, chars["Groceries\n".length].style)
        assertTrue(chars["Groceries\nmilk\n".length].bold)
        assertTrue(vm.status.value.exitRequested)
    }

    @Test
    fun richFormattingDetection() {
        assertFalse(hasRichFormatting("T\nb", listOf(AttrSpan(2, ParagraphStyle.TITLE), AttrSpan(1, ParagraphStyle.BODY))))
        assertTrue(hasRichFormatting("T\nb", listOf(AttrSpan(2, ParagraphStyle.BODY), AttrSpan(1, ParagraphStyle.TITLE))))
        assertTrue(hasRichFormatting("T", listOf(AttrSpan(1, ParagraphStyle.BODY, italic = true))))
        assertTrue(hasRichFormatting("T", listOf(AttrSpan(1, ParagraphStyle.BULLETED_LIST))))
        assertFalse(hasRichFormatting("", emptyList()))
    }
}
