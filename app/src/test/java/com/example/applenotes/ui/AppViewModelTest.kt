package com.example.applenotes.ui

import androidx.lifecycle.SavedStateHandle
import com.example.applenotes.data.NotesRepository
import com.example.applenotes.testing.FakeAlphaGate
import com.example.applenotes.testing.FakeAuthGateway
import com.example.applenotes.testing.FakeNotesApi
import com.example.applenotes.testing.MainDispatcherRule
import com.example.applenotes.testing.REPLICA_UUID
import com.example.applenotes.testing.folderRef
import com.example.applenotes.testing.noteRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class AppViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private lateinit var api: FakeNotesApi
    private lateinit var auth: FakeAuthGateway
    private lateinit var repo: NotesRepository
    private val alpha = FakeAlphaGate()
    private val savedState = SavedStateHandle()

    @Before
    fun setUp() {
        api = FakeNotesApi()
        api.put(noteRecord("NOTE-A", "Shopping\nmilk"))
        api.put(noteRecord("NOTE-B", "In folder F\nbody", folder = "FOLDER-F"))
        api.put(noteRecord("NOTE-T", "Trashed\nbody", folder = "TrashFolder-CloudKit"))
        auth = FakeAuthGateway(api)
        repo = NotesRepository(
            auth = auth,
            replicaUuidLoader = { REPLICA_UUID },
            externalScope = CoroutineScope(SupervisorJob() + main.dispatcher),
        )
    }

    private fun newVm(state: SavedStateHandle = savedState) = AppViewModel(repo, alpha, state)

    @Test
    fun savedLoginBootstrapsToTheList() {
        val vm = newVm()
        assertEquals(true, vm.alphaAcknowledged.value)
        assertEquals(AppScreen.NotesList, vm.screen.value)
        assertEquals(3, vm.listState.value.notes.size)
        assertEquals("DefaultFolder-CloudKit", vm.listState.value.selectedFolder)
        assertEquals(1, auth.openSessionCalls)
    }

    @Test
    fun noSavedLoginShowsLogin() {
        auth.savedLogin = false
        val vm = newVm()
        assertEquals(AppScreen.Login, vm.screen.value)
        vm.onCookiesArrived()
        assertEquals(AppScreen.NotesList, vm.screen.value)
    }

    @Test
    fun nothingStartsBeforeTheAlphaWarningIsAcknowledged() {
        alpha.acknowledged = false
        val vm = newVm()
        assertEquals(false, vm.alphaAcknowledged.value)
        assertEquals(AppScreen.Splash, vm.screen.value)
        assertEquals(0, auth.openSessionCalls)
        vm.acknowledgeAlpha()
        assertTrue(alpha.acknowledged)
        assertEquals(AppScreen.NotesList, vm.screen.value)
    }

    @Test
    fun bootstrapFailureIsAFullScreenError() {
        auth.failOpen = IllegalStateException("cookies expired")
        val vm = newVm()
        assertEquals(AppScreen.Error("cookies expired"), vm.screen.value)
        vm.retryBootstrap()
        assertEquals(AppScreen.NotesList, vm.screen.value)
    }

    @Test
    fun signOutClearsLoginAndGoesToLogin() {
        val vm = newVm()
        vm.signOut()
        assertTrue(auth.cleared)
        assertEquals(AppScreen.Login, vm.screen.value)
        assertTrue(vm.listState.value.notes.isEmpty())
    }

    @Test
    fun openNoteLooksItUpAndShowsDetail() {
        val vm = newVm()
        vm.openNote(vm.listState.value.notes.first { it.recordName == "NOTE-A" })
        val s = vm.screen.value as AppScreen.Detail
        assertEquals("NOTE-A", s.record!!.recordName)
        assertNull(s.pendingFolderRef)
        vm.showList()
        assertEquals(AppScreen.NotesList, vm.screen.value)
    }

    @Test
    fun createNoteOpensADeferredDraftInTheSelectedFolder() {
        val vm = newVm()
        vm.selectFolder("FOLDER-F")
        vm.createNote()
        val s = vm.screen.value as AppScreen.Detail
        val draft = s.record!!
        assertTrue(draft.recordName.startsWith("draft-"))
        assertNull(draft.recordChangeTag)
        assertTrue(draft.rawFields.isEmpty())
        assertEquals(folderRef("FOLDER-F"), s.pendingFolderRef)
        // Deferred create: nothing written yet.
        assertTrue(api.creates.isEmpty())
    }

    @Test
    fun createNoteInAllNotesFallsBackToANonTrashFolder() {
        val vm = newVm()
        vm.selectFolder(null)
        assertNull(vm.listState.value.selectedFolder)
        vm.createNote()
        val s = vm.screen.value as AppScreen.Detail
        assertEquals(folderRef("DefaultFolder-CloudKit"), s.pendingFolderRef)
    }

    @Test
    fun selectedFolderAndOpenNoteSurviveProcessDeath() {
        val vm = newVm()
        vm.selectFolder(null)
        vm.openNote(vm.listState.value.notes.first())
        val openId = (vm.screen.value as AppScreen.Detail).openId

        val restored = newVm(savedState)
        assertNull(restored.listState.value.selectedFolder)
        // Restored Detail has no record: the detail ViewModel rebuilds it.
        assertEquals(AppScreen.Detail(openId, record = null), restored.screen.value)
    }

    @Test
    fun leavingDetailIsNotRestored() {
        val vm = newVm()
        vm.openNote(vm.listState.value.notes.first())
        vm.showList()
        val restored = newVm(savedState)
        assertEquals(AppScreen.NotesList, restored.screen.value)
        assertFalse(restored.screen.value is AppScreen.Detail)
    }
}
