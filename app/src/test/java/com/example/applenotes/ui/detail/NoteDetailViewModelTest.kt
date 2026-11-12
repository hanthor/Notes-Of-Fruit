package com.example.applenotes.ui.detail

import com.example.applenotes.R
import com.example.applenotes.ui.common.UiText
import com.example.applenotes.ui.common.uiText
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
    fun restoredDraftIsNotAppliedOverAChangedNote() {
        // Before issue #4 the draft was silently dropped here. It must still
        // never be written over the other change, but it is now kept and the
        // user is asked (the overlapping case; a non-overlapping change is
        // merged, see NoteDetailConflictTest).
        val vm = newVm()
        vm.openExisting("NOTE-A")
        vm.type("milk\neggs\nunsaved")
        api.put(noteRecord("NOTE-A", "Shopping\nchanged on Mac", tag = "tag-mac"))

        val restored = newVm(savedState)
        restored.open("open-1", record = null, pendingFolderRef = null)
        assertNotNull(restored.status.value.conflict)
        assertEquals("milk\neggs\nunsaved", restored.bodyDraft.text)
        restored.save(alsoExit = false)
        assertTrue(api.modifies.isEmpty())

        restored.discardLocalChanges()
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

    // ----- #2: failures keep the draft -----

    @Test
    fun failedSaveKeepsDraftAndOffersRetry() {
        val vm = newVm()
        vm.openExisting("NOTE-A")
        vm.type("milk\neggs\nbread")
        api.failNext["modifyNoteBody"] = RuntimeException("HTTP 503")

        vm.save(alsoExit = false)

        val s = vm.status.value
        assertFalse(s.saving)
        assertTrue(s.saveFailed)
        assertFalse(s.exitRequested)
        assertEquals("open-1", s.openId)
        assertEquals(uiText(R.string.detail_save_failed, UiText.Plain("HTTP 503")), s.message!!.text)
        assertEquals(NoteDetailViewModel.DetailAction.RetrySave(alsoExit = false), s.message!!.action)
        // The typed text is untouched and still counts as unsaved.
        assertEquals("milk\neggs\nbread", vm.bodyDraft.text)
        assertTrue(vm.isModified)
        assertEquals("tag-1", vm.record!!.recordChangeTag)

        vm.onMessageAction(s.message!!)
        assertNull(vm.status.value.message)
        assertFalse(vm.status.value.saveFailed)
        assertFalse(vm.isModified)
        assertEquals("Shopping\nmilk\neggs\nbread", api.records.getValue("NOTE-A").visibleText())
    }

    @Test
    fun failedSaveAndExitStaysOnTheNote() {
        val vm = newVm()
        vm.openExisting("NOTE-A")
        vm.type("milk\nunsaved")
        api.failNext["modifyNoteBody"] = RuntimeException("offline")

        vm.save(alsoExit = true)

        assertFalse(vm.status.value.exitRequested)
        assertEquals("milk\nunsaved", vm.bodyDraft.text)
        val retry = vm.status.value.message!!
        assertEquals(NoteDetailViewModel.DetailAction.RetrySave(alsoExit = true), retry.action)
        vm.onMessageAction(retry)
        assertTrue(vm.status.value.exitRequested)
    }

    @Test
    fun failedFirstSaveOfANewNoteKeepsItAsADraft() {
        val vm = newVm()
        vm.open("open-d", com.example.applenotes.client.NoteRecord("draft-1", "Note", null, emptyMap()), folderRef("F"))
        vm.onTitleChange("Important")
        api.failNext["createNote"] = RuntimeException("timeout")

        vm.save(alsoExit = true)

        assertEquals("draft-1", vm.record!!.recordName)
        assertNull(vm.record!!.recordChangeTag)
        assertEquals("Important", vm.titleDraft)
        assertFalse(vm.status.value.exitRequested)
        assertTrue(api.creates.isEmpty())

        vm.onMessageAction(vm.status.value.message!!)
        assertEquals(1, api.creates.size)
        assertTrue(vm.status.value.exitRequested)
    }

    @Test
    fun failedDeleteStaysOnTheNote() {
        val vm = newVm()
        vm.openExisting("NOTE-A")
        vm.type("milk\nedited")
        api.failNext["deleteNote"] = RuntimeException("HTTP 500")

        vm.delete()

        val s = vm.status.value
        assertFalse(s.deleting)
        assertFalse(s.exitRequested)
        assertEquals(NoteDetailViewModel.DetailAction.RetryDelete, s.message!!.action)
        assertEquals("milk\nedited", vm.bodyDraft.text)
        assertTrue(repo.notes.value.any { it.recordName == "NOTE-A" })

        vm.onMessageAction(s.message!!)
        assertTrue(vm.status.value.exitRequested)
        assertEquals(1, api.deletes.size)
    }

    @Test
    fun dismissingTheMessageClearsIt() {
        val vm = newVm()
        vm.openExisting("NOTE-A")
        vm.type("x")
        api.failNext["modifyNoteBody"] = RuntimeException("nope")
        vm.save(alsoExit = false)
        vm.onMessageDismissed(vm.status.value.message!!)
        assertNull(vm.status.value.message)
        assertTrue(vm.status.value.saveFailed)
    }

    @Test
    fun textTypedDuringASaveIsKept() {
        val vm = newVm()
        vm.openExisting("NOTE-A")
        vm.type("milk\neggs\nbread")
        val gate = CompletableDeferred<Unit>()
        api.gates["modifyNoteBody"] = gate
        vm.save(alsoExit = false)
        vm.type("milk\neggs\nbread\nbutter")
        gate.complete(Unit)

        assertEquals("milk\neggs\nbread\nbutter", vm.bodyDraft.text)
        assertEquals("tag-101", vm.record!!.recordChangeTag)
        assertEquals("Shopping\nmilk\neggs\nbread", vm.originalBody)
        assertTrue(vm.isModified)
        assertEquals(1, api.modifies.size)
    }

    @Test
    fun backDuringASaveLeavesWhenItLands() {
        val vm = newVm()
        vm.openExisting("NOTE-A")
        vm.type("milk\neggs\nbread")
        val gate = CompletableDeferred<Unit>()
        api.gates["modifyNoteBody"] = gate
        vm.save(alsoExit = false)
        vm.save(alsoExit = true) // back pressed while saving
        assertFalse(vm.status.value.exitRequested)
        gate.complete(Unit)
        assertTrue(vm.status.value.exitRequested)
        assertEquals(1, api.modifies.size)
    }

    @Test
    fun backDuringASaveSavesLaterTypingBeforeLeaving() {
        val vm = newVm()
        vm.openExisting("NOTE-A")
        vm.type("milk\neggs\nbread")
        val gate = CompletableDeferred<Unit>()
        api.gates["modifyNoteBody"] = gate
        vm.save(alsoExit = false)
        vm.type("milk\neggs\nbread\nbutter")
        vm.save(alsoExit = true)
        gate.complete(Unit)
        assertEquals(2, api.modifies.size)
        assertEquals("tag-101", api.modifies[1].tag)
        assertEquals("Shopping\nmilk\neggs\nbread\nbutter", api.records.getValue("NOTE-A").visibleText())
        assertTrue(vm.status.value.exitRequested)
    }

    @Test
    fun overlappingSavesOfANewNoteCreateItOnce() {
        val vm = newVm()
        vm.open("open-d", com.example.applenotes.client.NoteRecord("draft-1", "Note", null, emptyMap()), folderRef("F"))
        vm.onTitleChange("Title")
        val gate = CompletableDeferred<Unit>()
        api.gates["createNote"] = gate
        vm.save(alsoExit = false)   // e.g. ON_PAUSE auto-save
        vm.type("body")
        vm.save(alsoExit = false)   // e.g. Save button
        gate.complete(Unit)
        assertEquals(1, api.creates.size)
        assertEquals("CREATED-1", api.modifies.single().recordName)
        assertEquals("Title\nbody", api.records.getValue("CREATED-1").visibleText())
    }

    @Test
    fun failedRestoreLookupReturnsToTheListWithAMessage() {
        val vm = newVm()
        vm.openExisting("NOTE-A")
        val restored = newVm(savedState)
        api.failNext["lookupNote"] = RuntimeException("offline")
        restored.open("open-1", record = null, pendingFolderRef = null)
        assertTrue(restored.status.value.exitRequested)
        assertEquals(uiText(R.string.detail_reopen_failed, UiText.Plain("offline")), restored.status.value.exitMessage)
    }

    // ----- #3: unsupported / undecodable notes open read-only -----

    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    private fun mergeableB64(): String {
        val w = com.example.applenotes.proto.ProtobufWire
        fun msg(n: Int, vararg c: com.example.applenotes.proto.ProtobufWire.Field) =
            com.example.applenotes.proto.ProtobufWire.Field(n, w.WIRE_LENGTH_DELIM, w.encode(c.toList()))
        val leaf = com.example.applenotes.proto.ProtobufWire.Field(3, w.WIRE_LENGTH_DELIM, byteArrayOf(0x08, 0x01))
        return kotlin.io.encoding.Base64.encode(
            com.example.applenotes.proto.Gzip.compress(w.encode(listOf(msg(2, msg(3, leaf))))),
        )
    }

    @Test
    fun modernNoteOpensReadOnlyAndCannotBeSaved() {
        api.put(noteRecord("NOTE-M", text = null, textB64 = mergeableB64()))
        val vm = newVm()
        vm.openExisting("NOTE-M")

        assertTrue(vm.isReadOnly)
        assertEquals(com.example.applenotes.data.ReadOnlyReason.MODERN_FORMAT, vm.readOnlyReason)
        assertTrue(vm.viewMode)
        assertEquals("", vm.fullBodyForSave)

        // Every edit path is inert…
        vm.onTitleChange("typed")
        vm.type("typed")
        vm.onSpansChange(listOf(NoteBodyEditor.AttrSpan(5, NoteBodyEditor.ParagraphStyle.HEADING)))
        vm.enterEditMode()
        vm.toggleCheckbox(0)
        assertEquals("", vm.titleDraft)
        assertEquals("", vm.bodyDraft.text)
        assertTrue(vm.viewMode)
        assertFalse(vm.isModified)
        // …and nothing is ever written back.
        vm.save(alsoExit = false)
        vm.autoSave()
        assertTrue(api.modifies.isEmpty())
        assertFalse(vm.status.value.saving)
    }

    @Test
    fun readOnlyNoteCanStillBeDeleted() {
        api.put(noteRecord("NOTE-M", text = null, textB64 = mergeableB64()))
        val vm = newVm()
        vm.openExisting("NOTE-M")
        vm.delete()
        assertEquals("NOTE-M", api.deletes.single().first)
        assertTrue(vm.status.value.exitRequested)
    }

    @Test
    fun existingNoteWithoutBodyIsReadOnlyButNewDraftIsEditable() {
        api.put(noteRecord("NOTE-E", text = null))
        val vm = newVm()
        vm.openExisting("NOTE-E")
        assertEquals(com.example.applenotes.data.ReadOnlyReason.MISSING_BODY, vm.readOnlyReason)

        vm.close()
        vm.open("open-d", com.example.applenotes.client.NoteRecord("draft-1", "Note", null, emptyMap()), folderRef("F"))
        assertFalse(vm.isReadOnly)
        vm.onTitleChange("hello")
        assertEquals("hello", vm.titleDraft)
    }

    @Test
    fun emptyButValidNoteIsEditable() {
        api.put(noteRecord("NOTE-0", ""))
        val vm = newVm()
        vm.openExisting("NOTE-0")
        assertFalse(vm.isReadOnly)
        vm.type("first words")
        vm.save(alsoExit = false)
        assertEquals("first words", api.records.getValue("NOTE-0").visibleText())
    }
}
