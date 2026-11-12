package com.example.applenotes.ui

import androidx.lifecycle.SavedStateHandle
import com.example.applenotes.client.NoteSummary
import com.example.applenotes.client.NotesError
import com.example.applenotes.data.NotesRepository
import com.example.applenotes.data.local.InMemoryNotesStore
import com.example.applenotes.data.local.PendingDraft
import com.example.applenotes.testing.FakeAlphaGate
import com.example.applenotes.testing.FakeAuthGateway
import com.example.applenotes.testing.FakeConnectivity
import com.example.applenotes.testing.FakeDraftScheduler
import com.example.applenotes.testing.FakeNotesApi
import com.example.applenotes.testing.MainDispatcherRule
import com.example.applenotes.testing.REPLICA_UUID
import com.example.applenotes.testing.noteRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Issues #12 / #13 at the app shell: launching from the cache, refresh, the outbox chip. */
class AppViewModelCacheTest {
    @get:Rule
    val main = MainDispatcherRule()

    private lateinit var api: FakeNotesApi
    private lateinit var auth: FakeAuthGateway
    private lateinit var net: FakeConnectivity
    private lateinit var store: InMemoryNotesStore
    private val scheduler = FakeDraftScheduler()
    private lateinit var repo: NotesRepository

    @Before
    fun setUp() {
        api = FakeNotesApi()
        api.put(noteRecord("NOTE-A", "Shopping\nmilk"))
        auth = FakeAuthGateway(api)
        net = FakeConnectivity()
        store = InMemoryNotesStore()
        runBlocking {
            store.upsertSummaries(listOf(NoteSummary("CACHED", "Cached", "", 1L, false, "DefaultFolder-CloudKit")))
        }
        repo = NotesRepository(
            auth = auth,
            replicaUuidLoader = { REPLICA_UUID },
            externalScope = CoroutineScope(SupervisorJob() + main.dispatcher),
            connectivity = net,
            backoff = {},
            store = store,
            draftScheduler = scheduler,
        )
    }

    private fun newVm() = AppViewModel(repo, FakeAlphaGate(), SavedStateHandle())

    @Test
    fun theCachedListShowsEvenWhenTheSessionCannotOpenOffline() {
        net.online.value = false
        auth.failOpen = NotesError.Offline()
        val vm = newVm()
        assertEquals(AppScreen.NotesList, vm.screen.value)
        assertEquals(listOf("CACHED"), vm.listState.value.notes.map { it.recordName })
        // Offline is already on screen as a banner: no error, no snackbar.
        assertNull(vm.listMessage.value)

        // Back online: the session opens and the list syncs.
        net.online.value = true
        assertEquals(listOf("NOTE-A"), vm.listState.value.notes.map { it.recordName })
        assertEquals(2, auth.openSessionCalls)
    }

    @Test
    fun theCachedListIsReplacedByTheSyncedOne() {
        val vm = newVm()
        assertEquals(AppScreen.NotesList, vm.screen.value)
        assertEquals(listOf("NOTE-A"), vm.listState.value.notes.map { it.recordName })
        assertFalse(vm.listState.value.syncing)
    }

    @Test
    fun anExpiredSessionBehindTheCacheGoesToSignIn() {
        auth.failOpen = NotesError.AuthExpired("421")
        val vm = newVm()
        assertEquals(AppScreen.Login, vm.screen.value)
    }

    @Test
    fun aBackgroundSyncFailureIsASnackbarNotAnErrorScreen() {
        api.failNext["fetchRecents"] = NotesError.Server(502, null)
        val vm = newVm()
        assertEquals(AppScreen.NotesList, vm.screen.value)
        assertEquals(ListAction.RetryRefresh, vm.listMessage.value!!.action)
        assertEquals(listOf("CACHED"), vm.listState.value.notes.map { it.recordName })
    }

    @Test
    fun pullToRefreshKeepsTheListOnScreen() {
        val vm = newVm()
        api.put(noteRecord("NOTE-B", "More\nnotes"))
        vm.refresh()
        assertEquals(AppScreen.NotesList, vm.screen.value)
        assertFalse(vm.listState.value.refreshing)
        assertEquals(2, vm.listState.value.notes.size)
    }

    @Test
    fun resumeRefreshIsThrottled() {
        val vm = newVm()
        val before = api.calls.count { it == "fetchRecents" }
        vm.onAppResumed()
        assertEquals(before, api.calls.count { it == "fetchRecents" })
    }

    @Test
    fun openingOfflineUsesTheCachedCopy() {
        val vm = newVm()
        vm.openNote(vm.listState.value.notes.single())
        vm.showList()
        net.online.value = false
        vm.openNote(vm.listState.value.notes.single())
        val s = vm.screen.value as AppScreen.Detail
        assertTrue(s.offlineCopy)
        assertEquals("NOTE-A", s.record!!.recordName)
    }

    @Test
    fun unsentChangesAreCountedAndRetried() {
        val vm = newVm()
        repo.persistDraft(PendingDraft("NOTE-A", noteRecord("NOTE-A", "x"), null, "x y", "", "{}", 1))
        repo.persistDraft(PendingDraft("NOTE-C", null, "{}", "c", "", "{}", 2, conflict = true))
        assertEquals(2, vm.listState.value.unsentCount)
        vm.onListMessageAction(com.example.applenotes.ui.common.UiMessage(1, com.example.applenotes.ui.common.UiText.Plain(""), ListAction.RetryUnsent))
        // Conflicted drafts wait for the user instead.
        assertEquals(listOf("NOTE-A"), scheduler.enqueued)
    }

    @Test
    fun signOutEmptiesTheCache() {
        val vm = newVm()
        vm.signOut()
        assertTrue(vm.listState.value.notes.isEmpty())
        assertTrue(runBlocking { store.loadSummaries() }.isEmpty())
    }
}
