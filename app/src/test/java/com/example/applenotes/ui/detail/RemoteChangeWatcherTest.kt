package com.example.applenotes.ui.detail

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import com.example.applenotes.data.NotesRepository
import com.example.applenotes.testing.FakeAuthGateway
import com.example.applenotes.testing.FakeConnectivity
import com.example.applenotes.testing.FakeNotesApi
import com.example.applenotes.testing.MainDispatcherRule
import com.example.applenotes.testing.REPLICA_UUID
import com.example.applenotes.testing.noteRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteChangeWatcherTest {
    @get:Rule
    val main = MainDispatcherRule()

    private lateinit var api: FakeNotesApi
    private lateinit var net: FakeConnectivity
    private lateinit var repo: NotesRepository
    private lateinit var vm: NoteDetailViewModel

    @Before
    fun setUp() = runTest(main.dispatcher) {
        api = FakeNotesApi()
        api.put(noteRecord("NOTE-A", "Shopping\nmilk\neggs"))
        net = FakeConnectivity()
        repo = NotesRepository(
            auth = FakeAuthGateway(api),
            replicaUuidLoader = { REPLICA_UUID },
            externalScope = CoroutineScope(SupervisorJob() + main.dispatcher),
            clock = { 1L },
            connectivity = net,
            backoff = {},
        )
        repo.signIn()
        vm = NoteDetailViewModel(repo, SavedStateHandle(), clock = { 2L })
        vm.open("open-1", api.records.getValue("NOTE-A"), pendingFolderRef = null)
    }

    private fun lookups() = api.calls.count { it == "lookupNote" }

    @Test
    fun noChangeNoBanner() = runTest(main.dispatcher) {
        vm.checkForRemoteChange()
        assertNull(vm.remoteChange.value)
        assertEquals(1, lookups())
    }

    @Test
    fun remoteEditShowsBannerAndReloadLoadsIt() = runTest(main.dispatcher) {
        val remote = api.remoteEdit("NOTE-A", "Shopping\nmilk\neggs\nbutter")
        vm.checkForRemoteChange()
        assertEquals(remote.recordChangeTag, vm.remoteChange.value?.recordChangeTag)
        // Nothing reloads on its own.
        assertEquals("milk\neggs", vm.bodyDraft.text)

        vm.reloadRemoteChange()
        assertEquals("milk\neggs\nbutter", vm.bodyDraft.text)
        assertEquals(remote.recordChangeTag, vm.record?.recordChangeTag)
        assertNull(vm.remoteChange.value)
        assertTrue(api.modifies.isEmpty())
    }

    @Test
    fun unsavedChangesAreNeverReplacedByReload() = runTest(main.dispatcher) {
        vm.onBodyChange(TextFieldValue("milk\neggs\nbread", TextRange(15)))
        api.remoteEdit("NOTE-A", "Shopping\nmilk\neggs\nbutter")
        vm.checkForRemoteChange()
        assertNotNull(vm.remoteChange.value)
        vm.reloadRemoteChange()
        assertEquals("milk\neggs\nbread", vm.bodyDraft.text)
        assertNotNull(vm.remoteChange.value)
        assertTrue(api.modifies.isEmpty())
    }

    @Test
    fun dismissClearsTheBanner() = runTest(main.dispatcher) {
        api.remoteEdit("NOTE-A", "Shopping\nchanged")
        vm.checkForRemoteChange()
        vm.dismissRemoteChange()
        assertNull(vm.remoteChange.value)
    }

    @Test
    fun noPollingWhileOffline() = runTest(main.dispatcher) {
        net.online.value = false
        api.remoteEdit("NOTE-A", "Shopping\nchanged")
        vm.checkForRemoteChange()
        assertEquals(0, lookups())
        assertNull(vm.remoteChange.value)
    }

    @Test
    fun ourOwnSaveIsNotReportedAsARemoteChange() = runTest(main.dispatcher) {
        vm.onBodyChange(TextFieldValue("milk\neggs\nbread", TextRange(15)))
        vm.save(alsoExit = false)
        vm.checkForRemoteChange()
        assertNull(vm.remoteChange.value)
    }

    @Test
    fun aResultThatRacesASaveIsDropped() = runTest(main.dispatcher) {
        // The lookup is in flight when the editor's record changes (our save lands).
        val gate = CompletableDeferred<Unit>()
        api.gates["lookupNote"] = gate
        api.remoteEdit("NOTE-A", "Shopping\nother")
        val job = launch { vm.checkForRemoteChange() }
        vm.onBodyChange(TextFieldValue("milk\neggs\nbread", TextRange(15)))
        vm.save(alsoExit = false)
        gate.complete(Unit)
        job.join()
        assertNull(vm.remoteChange.value)
    }

    @Test
    fun watchPollsEveryMinuteUntilCancelled() = runTest(main.dispatcher) {
        val job = launch { vm.watchForRemoteChanges() }
        advanceTimeBy(59_000); runCurrent()
        assertEquals(0, lookups())
        advanceTimeBy(2_000); runCurrent()
        assertEquals(1, lookups())
        api.remoteEdit("NOTE-A", "Shopping\nlater")
        advanceTimeBy(60_000); runCurrent()
        assertEquals(2, lookups())
        assertNotNull(vm.remoteChange.value)
        job.cancel()
        advanceTimeBy(600_000); runCurrent()
        assertEquals(2, lookups())
    }
}
