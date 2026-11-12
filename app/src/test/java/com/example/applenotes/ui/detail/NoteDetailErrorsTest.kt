package com.example.applenotes.ui.detail

import com.example.applenotes.R
import com.example.applenotes.ui.common.UiText
import com.example.applenotes.ui.common.uiText
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
        assertEquals(UiText.Res(R.string.detail_save_offline), s.message!!.text)
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
        assertEquals(UiText.Res(R.string.action_sign_in), m.actionLabel)
        // "Your iCloud session expired. Your changes are kept; sign in again to save them."
        assertEquals(UiText.Res(R.string.detail_save_session_expired), m.text)
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

    /**
     * Finding: the session expired, someone signed in with a different Apple
     * ID, and the open editor saved account A's draft into account B.
     */
    @Test
    fun aDraftIsNeverSavedIntoAnotherAppleId() {
        val vm = newVm()
        vm.type("milk\neggs")
        api.failNext["modifyNoteBody"] = NotesError.AuthExpired("HTTP 421")
        auth.failOpen = NotesError.AuthExpired("validate 421")
        vm.save(alsoExit = false)
        assertTrue(repo.sessionExpired.value)
        api.modifies.clear()

        // A different account signs in; the same note id happens to exist there.
        api.accountId = "dsid-2"
        api.records.clear()
        api.put(noteRecord("NOTE-A", "Someone else's note"))
        kotlinx.coroutines.runBlocking { repo.signIn() }
        vm.open("open-1", record = null, pendingFolderRef = null)
        vm.autoSave()
        vm.save(alsoExit = false)
        vm.close()

        assertTrue(api.modifies.isEmpty())
        assertEquals("Someone else's note", api.records.getValue("NOTE-A").visibleText())
        assertTrue(repo.pendingDrafts.value.isEmpty())
        // Account A's text is gone from the editor too.
        assertFalse(vm.bodyDraft.text.contains("eggs"))
        assertTrue(savedState.get<String>(NoteDetailViewModel.KEY_DRAFT)?.contains("eggs") != true)
    }

    /** A draft stamped with one account is refused once another one is signed in. */
    @Test
    fun drafsAndSavesForAnotherAccountAreRefused() = runTest(main.dispatcher) {
        val a = repo.account.value!!
        api.accountId = "dsid-2"
        repo.signIn()
        assertTrue(repo.account.value != a)
        repo.persistDraft(
            com.example.applenotes.data.local.PendingDraft(
                "NOTE-A", api.records.getValue("NOTE-A"), null, "x", "", "{}", 1, account = a,
            ),
        ).join()
        assertTrue(repo.pendingDrafts.value.isEmpty())
        try {
            repo.saveNote(api.records.getValue("NOTE-A"), null, "x", false, null, expectedAccount = a)
            org.junit.Assert.fail("expected AccountChangedException")
        } catch (e: com.example.applenotes.data.AccountChangedException) {
            // expected
        }
        assertTrue(api.modifies.isEmpty())
    }

    @Test
    fun throttledSaveIsSurfacedWithRetry() {
        val vm = newVm()
        vm.type("milk\neggs")
        api.failNext["modifyNoteBody"] = NotesError.Throttled(retryAfterMs = 5_000)
        vm.save(alsoExit = false)
        val m = vm.status.value.message!!
        assertEquals(NoteDetailViewModel.DetailAction.RetrySave(alsoExit = false), m.action)
        // "… Try again in 5 seconds."
        assertEquals(uiText(R.string.detail_save_throttled, UiText.Plural(R.plurals.retry_hint_seconds, 5)), m.text)
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
        assertEquals(
            uiText(R.string.detail_save_failed, uiText(R.string.error_server_with_code, 500, "INTERNAL_ERROR")),
            m.text,
        )
        assertTrue(m.details!!.contains("boom"))
    }
}
