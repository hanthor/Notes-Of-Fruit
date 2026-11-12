package com.example.applenotes.ui.detail

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import com.example.applenotes.client.NotesError
import com.example.applenotes.data.NotesRepository
import com.example.applenotes.testing.FakeAuthGateway
import com.example.applenotes.testing.FakeConnectivity
import com.example.applenotes.testing.FakeNotesApi
import com.example.applenotes.testing.MainDispatcherRule
import com.example.applenotes.testing.REPLICA_UUID
import com.example.applenotes.testing.noteRecord
import com.example.applenotes.testing.visibleText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Issue #11 at the editor: typed save failures never lose the draft. */
class NoteDetailErrorsTest {
    @get:Rule
    val main = MainDispatcherRule()

    private lateinit var api: FakeNotesApi
    private lateinit var auth: FakeAuthGateway
    private lateinit var net: FakeConnectivity
    private lateinit var repo: NotesRepository
    private val savedState = SavedStateHandle()

    @Before
    fun setUp() = runTest(main.dispatcher) {
        api = FakeNotesApi()
        api.put(noteRecord("NOTE-A", "Shopping\nmilk"))
        auth = FakeAuthGateway(api)
        net = FakeConnectivity()
        repo = NotesRepository(
            auth = auth,
            replicaUuidLoader = { REPLICA_UUID },
            externalScope = CoroutineScope(SupervisorJob() + main.dispatcher),
            clock = { 1_700_000_000_000L },
            connectivity = net,
            backoff = {},
        )
        repo.signIn()
    }

    private fun newVm() = NoteDetailViewModel(repo, savedState, clock = { 42L }).apply {
        open("open-1", api.records.getValue("NOTE-A"), pendingFolderRef = null)
    }

    private fun NoteDetailViewModel.type(body: String) =
        onBodyChange(TextFieldValue(body, TextRange(body.length)))

    @Test
    fun offlineSaveKeepsTheDraftSaysSoAndRetriesOnceWhenBackOnline() {
        val vm = newVm()
        vm.type("milk\neggs")
        api.failNext["modifyNoteBody"] = NotesError.Offline(java.net.UnknownHostException("x"))
        net.online.value = false
        vm.save(alsoExit = false)

        val s = vm.status.value
        assertTrue(s.saveFailed)
        assertEquals("You're offline. Your changes are kept on this device until you retry.", s.message!!.text)
        assertNotNull("raw error behind Details", s.message!!.details)
        assertEquals("milk\neggs", vm.bodyDraft.text)
        assertTrue(savedState.get<String>(NoteDetailViewModel.KEY_DRAFT)!!.contains("eggs"))

        // While offline, lifecycle auto-save doesn't touch the network.
        val callsBefore = api.calls.size
        vm.autoSave()
        assertEquals(callsBefore, api.calls.size)

        net.online.value = true
        assertEquals("Shopping\nmilk\neggs", api.records.getValue("NOTE-A").visibleText())
        assertFalse(vm.status.value.saveFailed)
        assertFalse(vm.isModified)
    }

    @Test
    fun expiredSessionOffersSignInAndSavesTheKeptDraftAfterwards() {
        val vm = newVm()
        vm.type("milk\neggs")
        api.failNext["modifyNoteBody"] = NotesError.AuthExpired("HTTP 421")
        auth.failOpen = NotesError.AuthExpired("validate 421")
        vm.save(alsoExit = false)

        val m = vm.status.value.message!!
        assertEquals(NoteDetailViewModel.DetailAction.SignIn, m.action)
        assertEquals("Sign in", m.actionLabel)
        assertTrue(m.text.contains("kept"))
        assertTrue(repo.sessionExpired.value)
        assertEquals("milk\neggs", vm.bodyDraft.text)
        assertTrue(api.modifies.isEmpty())

        // The user signs in; AppViewModel bootstraps and reopens the same entry.
        vm.onMessageAction(m)
        kotlinx.coroutines.runBlocking { repo.signIn() }
        vm.open("open-1", record = null, pendingFolderRef = null)

        assertEquals("Shopping\nmilk\neggs", api.records.getValue("NOTE-A").visibleText())
        assertFalse(vm.status.value.saveFailed)
        assertEquals("milk\neggs", vm.bodyDraft.text)
    }

    @Test
    fun throttledSaveIsSurfacedWithRetry() {
        val vm = newVm()
        vm.type("milk\neggs")
        api.failNext["modifyNoteBody"] = NotesError.Throttled(retryAfterMs = 5_000)
        vm.save(alsoExit = false)
        val m = vm.status.value.message!!
        assertEquals(NoteDetailViewModel.DetailAction.RetrySave(alsoExit = false), m.action)
        assertTrue(m.text, m.text.contains("5s"))
        vm.onMessageAction(m)
        assertEquals("Shopping\nmilk\neggs", api.records.getValue("NOTE-A").visibleText())
    }

    @Test
    fun serverErrorShowsAFriendlyLineWithRawBodyInDetails() {
        val vm = newVm()
        vm.type("milk\neggs")
        api.failNext["modifyNoteBody"] = NotesError.Server(500, "INTERNAL_ERROR", "{\"reason\":\"boom\"}")
        vm.save(alsoExit = false)
        val m = vm.status.value.message!!
        assertEquals("Couldn't save: iCloud returned an error (HTTP 500, INTERNAL_ERROR).", m.text)
        assertTrue(m.details!!.contains("boom"))
    }
}
