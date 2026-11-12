package com.example.applenotes.ui

import com.example.applenotes.R
import com.example.applenotes.ui.common.UiText
import com.example.applenotes.ui.common.uiText
import androidx.lifecycle.SavedStateHandle
import com.example.applenotes.data.NotesRepository
import com.example.applenotes.integrations.IncomingRequest
import com.example.applenotes.testing.FakeAlphaGate
import com.example.applenotes.testing.FakeAuthGateway
import com.example.applenotes.testing.FakeNotesApi
import com.example.applenotes.testing.MainDispatcherRule
import com.example.applenotes.testing.REPLICA_UUID
import com.example.applenotes.testing.folderRef
import com.example.applenotes.testing.noteRecord
import com.example.applenotes.ui.detail.DraftPrefill
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Share-in / shortcuts / import: requests from outside the app (issue #20/#21). */
class IncomingRequestsTest {
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
        repo = NotesRepository(auth, { REPLICA_UUID }, CoroutineScope(SupervisorJob() + main.dispatcher))
    }

    private fun newVm(state: SavedStateHandle = savedState) = AppViewModel(repo, alpha, state)

    private val shared = IncomingRequest.NewDraft(DraftPrefill.ofText("hello", "Subject"))

    @Test
    fun sharedTextOpensAnUnsavedPrefilledDraft() {
        val vm = newVm()
        vm.handleIncoming(shared)
        val d = vm.screen.value as AppScreen.Detail
        assertNull(d.record!!.recordChangeTag)
        assertEquals(folderRef("DefaultFolder-CloudKit"), d.pendingFolderRef)
        assertEquals("Subject\nhello", d.prefill!!.text)
        // Nothing is written until the editor saves.
        assertTrue(api.creates.isEmpty())
        assertTrue(api.modifies.isEmpty())
    }

    @Test
    fun requestWaitsForSignInAndResumesAfterBootstrap() {
        auth.savedLogin = false
        val vm = newVm()
        assertEquals(AppScreen.Login, vm.screen.value)
        vm.handleIncoming(shared)
        assertEquals(AppScreen.Login, vm.screen.value)
        assertTrue(vm.hasPendingIncoming)

        vm.onCookiesArrived()
        val d = vm.screen.value as AppScreen.Detail
        assertEquals("Subject\nhello", d.prefill!!.text)
        assertFalse(vm.hasPendingIncoming)
    }

    @Test
    fun requestWaitsBehindTheAlphaWarning() {
        alpha.acknowledged = false
        val vm = newVm()
        vm.handleIncoming(shared)
        assertEquals(AppScreen.Splash, vm.screen.value)
        vm.acknowledgeAlpha()
        assertTrue(vm.screen.value is AppScreen.Detail)
    }

    @Test
    fun pendingRequestSurvivesProcessDeath() {
        auth.savedLogin = false
        newVm().handleIncoming(shared)
        auth.savedLogin = true
        val restored = newVm(savedState)
        val d = restored.screen.value as AppScreen.Detail
        assertEquals("Subject\nhello", d.prefill!!.text)
    }

    @Test
    fun signOutDropsAPendingRequest() {
        auth.savedLogin = false
        val vm = newVm()
        vm.handleIncoming(shared)
        vm.signOut()
        assertFalse(vm.hasPendingIncoming)
    }

    @Test
    fun aRestoredNoteKeepsTheScreenUntilItIsClosed() {
        val vm = newVm()
        vm.openNote(vm.listState.value.notes.first { it.recordName == "NOTE-A" })
        // Process death with a note open, then a share arrives before bootstrap.
        auth.savedLogin = true
        val restored = AppViewModel(repo, alpha, savedState)
        assertTrue(restored.screen.value is AppScreen.Detail)
        restored.handleIncoming(shared)
        assertNull((restored.screen.value as AppScreen.Detail).prefill)
        restored.showList()
        assertEquals("Subject\nhello", (restored.screen.value as AppScreen.Detail).prefill!!.text)
    }

    @Test
    fun newDraftFromTrashGoesToTheDefaultFolder() {
        val vm = newVm()
        vm.selectFolder("TrashFolder-CloudKit")
        vm.handleIncoming(shared)
        assertEquals(folderRef("DefaultFolder-CloudKit"), (vm.screen.value as AppScreen.Detail).pendingFolderRef)
    }

    @Test
    fun openNoteByRecordNameAndTitle() {
        val vm = newVm()
        vm.handleIncoming(IncomingRequest.OpenNote("NOTE-B"))
        assertEquals("NOTE-B", (vm.screen.value as AppScreen.Detail).record!!.recordName)

        vm.showList()
        vm.handleIncoming(IncomingRequest.OpenNoteByTitle("Shopping"))
        assertEquals("NOTE-A", (vm.screen.value as AppScreen.Detail).record!!.recordName)

        vm.showList()
        vm.handleIncoming(IncomingRequest.OpenNoteByTitle("Trashed"))
        assertEquals(AppScreen.NotesList, vm.screen.value)
        assertEquals(uiText(R.string.list_no_note_titled, "Trashed"), vm.listMessage.value!!.text)
    }

    @Test
    fun staleShortcutShowsARetryableMessage() {
        val vm = newVm()
        vm.handleIncoming(IncomingRequest.OpenNote("GONE"))
        assertEquals(AppScreen.NotesList, vm.screen.value)
        assertTrue(vm.listMessage.value!!.action is ListAction.RetryOpen)
    }
}
