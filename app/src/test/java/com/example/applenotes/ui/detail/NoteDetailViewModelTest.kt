package com.example.applenotes.ui.detail

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import com.example.applenotes.data.NotesRepository
import com.example.applenotes.proto.NoteBodyEditor
import com.example.applenotes.testing.FakeAuthGateway
import com.example.applenotes.testing.FakeNotesApi
import com.example.applenotes.testing.MainDispatcherRule
import com.example.applenotes.testing.REPLICA_UUID
import com.example.applenotes.testing.folderRef
import com.example.applenotes.testing.noteRecord
import com.example.applenotes.testing.visibleText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class NoteDetailViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private lateinit var api: FakeNotesApi
    private lateinit var repo: NotesRepository
    private lateinit var savedState: SavedStateHandle

    @Before
    fun setUp() = runTest(main.dispatcher) {
        api = FakeNotesApi()
        api.put(noteRecord("NOTE-A", "Shopping\nmilk\neggs"))
        api.put(noteRecord("NOTE-B", "Other note\nbody"))
        repo = NotesRepository(
            auth = FakeAuthGateway(api),
            replicaUuidLoader = { REPLICA_UUID },
            externalScope = CoroutineScope(SupervisorJob() + main.dispatcher),
            clock = { 1_700_000_000_000L },
        )
        repo.signIn()
        savedState = SavedStateHandle()
    }

    private fun newVm(state: SavedStateHandle = savedState) =
        NoteDetailViewModel(repo, state, clock = { 42L })

    private fun NoteDetailViewModel.openExisting(recordName: String, openId: String = "open-1") {
        open(openId, api.records.getValue(recordName), pendingFolderRef = null)
    }

    private fun NoteDetailViewModel.type(body: String) =
        onBodyChange(TextFieldValue(body, TextRange(body.length)))

    @Test
    fun openSplitsTitleAndBody() {
        val vm = newVm()
        vm.openExisting("NOTE-A")
        assertEquals("Shopping", vm.titleDraft)
        assertEquals("milk\neggs", vm.bodyDraft.text)
        assertEquals("Shopping\nmilk\neggs", vm.fullBodyForSave)
        assertFalse(vm.isModified)
        assertEquals("open-1", vm.status.value.openId)
    }

    @Test
    fun reopeningSameOpenIdKeepsTheDraft() {
        // Rotation: the route re-runs open() with the same id.
        val vm = newVm()
        vm.openExisting("NOTE-A")
        vm.type("milk\neggs\nbread")
        vm.openExisting("NOTE-A")
        assertEquals("milk\neggs\nbread", vm.bodyDraft.text)
        assertTrue(vm.isModified)
    }

    @Test
    fun saveModifiesExistingNoteAndStaysOnScreen() {
        val vm = newVm()
        vm.openExisting("NOTE-A")
        vm.type("milk\neggs\nbread")
        assertTrue(vm.isPureAppend)

        vm.save(alsoExit = false)

        val m = api.modifies.single()
        assertEquals("NOTE-A", m.recordName)
        assertEquals("tag-1", m.tag)
        // ReplicaIDToNotesVersionDataEncrypted is passed through unchanged.
        assertEquals("cmVwbGljYS12ZXJzaW9u", m.replicaVersion)
        assertEquals("Shopping\nmilk\neggs\nbread", NoteBodyEditor.readVisibleTextFromBase64(m.b64))
        // Title unchanged → not resent; snippet unchanged ("milk") → not resent.
        assertNull(m.newTitle)
        assertNull(m.newSnippet)

        val s = vm.status.value
        assertFalse(s.saving)
        assertFalse(s.exitRequested)
        assertEquals(42L, s.lastSavedAtMs)
        // Editor now reflects the server's record (new change tag).
        assertEquals("tag-101", vm.record!!.recordChangeTag)
        assertEquals("milk\neggs\nbread", vm.bodyDraft.text)
        assertFalse(vm.isModified)
    }

    @Test
    fun saveAndExitRequestsNavigation() {
        val vm = newVm()
        vm.openExisting("NOTE-A")
        vm.onTitleChange("Groceries")
        vm.save(alsoExit = true)

        assertEquals("Groceries", api.modifies.single().newTitle)
        assertTrue(vm.status.value.exitRequested)
        // The list cache was patched in place.
        val summary = repo.notes.value.first { it.recordName == "NOTE-A" }
        assertEquals("Groceries", summary.title)
    }

    @Test
    fun titleNewlinesAreStripped() {
        val vm = newVm()
        vm.openExisting("NOTE-A")
        vm.onTitleChange("Shop\nping")
        assertEquals("Shopping", vm.titleDraft)
    }

    @Test
    fun draftFirstSaveCreatesNoteWithTitleAndBody() {
        val vm = newVm()
        val draft = com.example.applenotes.client.NoteRecord("draft-123", "Note", null, emptyMap())
        val ref = folderRef("DefaultFolder-CloudKit")
        vm.open("open-d", draft, ref)
        assertFalse(vm.isModified)

        vm.onTitleChange("New idea")
        vm.type("details")
        vm.save(alsoExit = false)

        val c = api.creates.single()
        assertEquals("New idea", c.title)
        assertEquals("details", c.body)
        assertEquals(ref, c.folderRef)
        assertArrayEquals(REPLICA_UUID, c.uuid)
        assertTrue(api.modifies.isEmpty())
        // The new note is pre-pended to the list cache.
        assertEquals("CREATED-1", repo.notes.value.first().recordName)
        assertEquals("New idea", repo.notes.value.first().title)
        // The editor switched to the real record: the next save modifies.
        assertEquals("CREATED-1", vm.record!!.recordName)
        vm.type("details, more")
        vm.save(alsoExit = false)
        assertEquals(1, api.creates.size)
        assertEquals("CREATED-1", api.modifies.single().recordName)
    }

    @Test
    fun saveFinishingAfterLeavingOnlyPatchesTheList() {
        val vm = newVm()
        vm.openExisting("NOTE-A")
        vm.type("milk\neggs\nbread")
        val gate = CompletableDeferred<Unit>()
        api.gates["modifyNoteBody"] = gate
        vm.save(alsoExit = false)
        assertTrue(vm.status.value.saving)

        // User leaves and opens another note while the save is in flight.
        vm.close()
        vm.openExisting("NOTE-B", openId = "open-2")
        gate.complete(Unit)

        assertEquals("NOTE-B", vm.record!!.recordName)
        assertEquals("body", vm.bodyDraft.text)
        assertFalse(vm.status.value.exitRequested)
        assertEquals("milk", repo.notes.value.first { it.recordName == "NOTE-A" }.snippet)
        assertEquals(1, api.modifies.size)
    }

    @Test
    fun saveSurvivesViewModelClearing() {
        // Writes run in the repository's scope, not viewModelScope.
        val vm = newVm()
        vm.openExisting("NOTE-A")
        vm.type("milk\neggs\nbread")
        val gate = CompletableDeferred<Unit>()
        api.gates["modifyNoteBody"] = gate
        vm.save(alsoExit = true)
        vm.viewModelScope.cancel()
        gate.complete(Unit)
        assertEquals(1, api.modifies.size)
        assertEquals("Shopping\nmilk\neggs\nbread", api.records.getValue("NOTE-A").visibleText())
    }

    @Test
    fun deletingADraftNeedsNoNetwork() {
        val vm = newVm()
        vm.open("open-d", com.example.applenotes.client.NoteRecord("draft-1", "Note", null, emptyMap()), folderRef("X"))
        vm.delete()
        assertTrue(vm.status.value.exitRequested)
        assertTrue(api.deletes.isEmpty())
    }

    @Test
    fun deleteRemovesNoteAndExits() {
        val vm = newVm()
        vm.openExisting("NOTE-A")
        vm.delete()
        assertEquals(listOf("NOTE-A" to "tag-1"), api.deletes)
        assertTrue(vm.status.value.exitRequested)
        assertTrue(repo.notes.value.none { it.recordName == "NOTE-A" })
    }

    @Test
    fun checkboxToggleAutoSaves() {
        val vm = newVm()
        vm.openExisting("NOTE-A")
        // Make "milk" a checkbox paragraph, then tap it.
        val full = vm.fullBodyForSave
        vm.onSpansChange(
            NoteBodyEditor.setParagraphStyle(full, vm.spansDraft, 9, 10, NoteBodyEditor.ParagraphStyle.CHECKBOX_LIST),
        )
        vm.toggleCheckbox(0)
        val m = api.modifies.single()
        val spans = NoteBodyEditor.parseAttributeRunsFromBase64(m.b64)
        val checked = NoteBodyEditor.expandSpansToChars(full, spans)[9]
        assertEquals(NoteBodyEditor.ParagraphStyle.CHECKBOX_LIST, checked.style)
        assertTrue(checked.checked)
    }

    @Test
    fun autoSaveOnlyWhenModified() {
        val vm = newVm()
        vm.openExisting("NOTE-A")
        vm.autoSave()
        assertTrue(api.modifies.isEmpty())
        vm.type("milk")
        vm.autoSave()
        assertEquals(1, api.modifies.size)
    }

    @Test
    fun draftSurvivesProcessDeath() {
        val vm = newVm()
        vm.openExisting("NOTE-A")
        vm.type("milk\neggs\nunsaved")

        // New process: fresh ViewModel, same saved state, record not in memory.
        val restored = newVm(savedState)
        restored.open("open-1", record = null, pendingFolderRef = null)
        assertEquals("NOTE-A", restored.record!!.recordName)
        assertEquals("milk\neggs\nunsaved", restored.bodyDraft.text)
        assertEquals(TextRange("milk\neggs\nunsaved".length), restored.bodyDraft.selection)
        assertTrue(restored.isModified)
    }

    @Test
    fun restoredDraftIsDroppedIfTheNoteChangedMeanwhile() {
        val vm = newVm()
        vm.openExisting("NOTE-A")
        vm.type("milk\neggs\nunsaved")
        api.put(noteRecord("NOTE-A", "Shopping\nchanged on Mac", tag = "tag-mac"))

        val restored = newVm(savedState)
        restored.open("open-1", record = null, pendingFolderRef = null)
        assertEquals("changed on Mac", restored.bodyDraft.text)
        assertFalse(restored.isModified)
    }

    @Test
    fun unsavedNewNoteSurvivesProcessDeath() {
        val vm = newVm()
        val ref = folderRef("DefaultFolder-CloudKit")
        vm.open("open-d", com.example.applenotes.client.NoteRecord("draft-9", "Note", null, emptyMap()), ref)
        vm.onTitleChange("Typed before death")

        val restored = newVm(savedState)
        restored.open("open-d", record = null, pendingFolderRef = null)
        assertEquals("draft-9", restored.record!!.recordName)
        assertNull(restored.record!!.recordChangeTag)
        assertEquals("Typed before death", restored.titleDraft)
        restored.save(alsoExit = false)
        assertEquals(ref, api.creates.single().folderRef)
    }

    @Test
    fun closeForgetsSavedState() {
        val vm = newVm()
        vm.openExisting("NOTE-A")
        vm.close()
        assertNull(vm.record)
        val restored = newVm(savedState)
        restored.open("open-1", record = null, pendingFolderRef = null)
        assertTrue(restored.status.value.exitRequested)
        assertNotNull(restored.status.value.openId)
    }
}
