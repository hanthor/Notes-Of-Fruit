package com.example.applenotes.ui.detail

import com.example.applenotes.R
import com.example.applenotes.ui.common.UiText
import com.example.applenotes.ui.common.uiText
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import com.example.applenotes.data.NoteRebase
import com.example.applenotes.data.NotesRepository
import com.example.applenotes.proto.Gzip
import com.example.applenotes.proto.Topotext
import com.example.applenotes.testing.FakeAuthGateway
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Issue #4 at the editor: merge silently when possible, ask when edits overlap. */
@OptIn(ExperimentalEncodingApi::class)
class NoteDetailConflictTest {
    @get:Rule
    val main = MainDispatcherRule()

    private lateinit var api: FakeNotesApi
    private lateinit var repo: NotesRepository
    private val savedState = SavedStateHandle()

    @Before
    fun setUp() = runTest(main.dispatcher) {
        api = FakeNotesApi()
        api.put(noteRecord("NOTE-A", "Shopping\nmilk\neggs"))
        api.checkTags = true
        repo = NotesRepository(
            auth = FakeAuthGateway(api),
            replicaUuidLoader = { REPLICA_UUID },
            externalScope = CoroutineScope(SupervisorJob() + main.dispatcher),
            clock = { 1_700_000_000_000L },
            backoff = {},
        )
        repo.signIn()
    }

    private fun newVm(state: SavedStateHandle = savedState) =
        NoteDetailViewModel(repo, state, clock = { 42L }).apply {
            open("open-1", api.records.getValue("NOTE-A"), pendingFolderRef = null)
        }

    private fun NoteDetailViewModel.type(body: String) =
        onBodyChange(TextFieldValue(body, TextRange(body.length)))

    @Test
    fun nonOverlappingRemoteEditIsMergedAndShown() {
        val vm = newVm()
        vm.type("milk\neggs\nbread")
        api.remoteEdit("NOTE-A", "Shopping list\nmilk\neggs")
        vm.save(alsoExit = false)

        assertEquals("Shopping list\nmilk\neggs\nbread", api.records.getValue("NOTE-A").visibleText())
        assertEquals("Shopping list", vm.titleDraft)
        assertEquals("milk\neggs\nbread", vm.bodyDraft.text)
        assertFalse(vm.isModified)
        val s = vm.status.value
        assertNull(s.conflict)
        assertFalse(s.saveFailed)
        assertEquals(UiText.Res(R.string.detail_saved_merged), s.message!!.text)
    }

    @Test
    fun overlappingEditShowsTheConflictAndWritesNothing() {
        val vm = newVm()
        vm.type("milk\nbrown eggs")
        api.remoteEdit("NOTE-A", "Shopping\nmilk\n6 eggs")
        vm.save(alsoExit = true)

        val c = assertNotNull(vm.status.value.conflict).let { vm.status.value.conflict!! }
        assertEquals("Shopping\nmilk\n6 eggs", c.remoteText)
        assertTrue(api.modifies.isEmpty())
        // The draft is untouched, and we didn't navigate away.
        assertEquals("milk\nbrown eggs", vm.bodyDraft.text)
        assertFalse(vm.status.value.exitRequested)
        assertTrue(vm.status.value.saveFailed)
        assertEquals(true, savedState.get<Boolean>(NoteDetailViewModel.KEY_CONFLICT))

        // Saving (or auto-saving) the draft as-is is blocked until resolved.
        vm.save(alsoExit = false)
        vm.autoSave()
        assertTrue(api.modifies.isEmpty())
    }

    @Test
    fun keepBothAppendsTheUsersVersionBelowTheRemote() {
        val vm = newVm()
        vm.type("milk\nbrown eggs")
        api.remoteEdit("NOTE-A", "Shopping\nmilk\n6 eggs")
        vm.save(alsoExit = false)

        vm.keepBoth()

        val expected = "Shopping\nmilk\n6 eggs\n\n${NoteRebase.KEEP_BOTH_SEPARATOR}\nShopping\nmilk\nbrown eggs"
        assertEquals(expected, api.records.getValue("NOTE-A").visibleText())
        val written = api.modifies.single()
        Topotext.checkInvariants(Gzip.decompress(Base64.decode(written.b64)), expected, REPLICA_UUID)
        assertNull(vm.status.value.conflict)
        assertFalse(vm.status.value.saveFailed)
        assertFalse(vm.isModified)
        assertNull(savedState.get<Boolean>(NoteDetailViewModel.KEY_CONFLICT))
    }

    @Test
    fun discardMyChangesShowsTheRemoteVersion() {
        val vm = newVm()
        vm.type("milk\nbrown eggs")
        api.remoteEdit("NOTE-A", "Shopping\nmilk\n6 eggs")
        vm.save(alsoExit = false)

        vm.discardLocalChanges()

        assertEquals("milk\n6 eggs", vm.bodyDraft.text)
        assertFalse(vm.isModified)
        assertNull(vm.status.value.conflict)
        assertFalse(vm.status.value.saveFailed)
        assertTrue(api.modifies.isEmpty())
    }

    @Test
    fun draftRestoredAfterProcessDeathIsMergedOntoAChangedNote() {
        val vm = newVm()
        vm.type("milk\neggs\nbread")
        // Process dies before saving; meanwhile another device edits the title.
        api.remoteEdit("NOTE-A", "Shopping list\nmilk\neggs")
        val restoredState = SavedStateHandle(savedState.keys().associateWith { savedState.get<Any>(it) })
        val restored = NoteDetailViewModel(repo, restoredState, clock = { 42L })
        restored.open("open-1", record = null, pendingFolderRef = null)

        assertEquals("Shopping list", restored.titleDraft)
        assertEquals("milk\neggs\nbread", restored.bodyDraft.text)
        assertNull(restored.status.value.conflict)
        restored.save(alsoExit = false)
        assertEquals("Shopping list\nmilk\neggs\nbread", api.records.getValue("NOTE-A").visibleText())
    }

    @Test
    fun overlappingDraftRestoredAfterProcessDeathAsksInsteadOfReverting() {
        val vm = newVm()
        vm.type("milk\nbrown eggs")
        api.remoteEdit("NOTE-A", "Shopping\nmilk\n6 eggs")
        val restoredState = SavedStateHandle(savedState.keys().associateWith { savedState.get<Any>(it) })
        val restored = NoteDetailViewModel(repo, restoredState, clock = { 42L })
        restored.open("open-1", record = null, pendingFolderRef = null)

        assertNotNull(restored.status.value.conflict)
        assertEquals("milk\nbrown eggs", restored.bodyDraft.text)
        restored.save(alsoExit = false)
        assertTrue(api.modifies.isEmpty())
    }

    @Test
    fun typingDuringAMergingSaveIsRebasedToo() {
        val vm = newVm()
        vm.type("milk\neggs\nbread")
        api.remoteEdit("NOTE-A", "Shopping list\nmilk\neggs")
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        api.gates["modifyNoteBody"] = gate
        vm.save(alsoExit = false)
        // First attempt is parked; the user keeps typing.
        vm.type("milk\neggs\nbread\njam")
        gate.complete(Unit)

        // The save merged the remote title; the draft keeps the new line AND the title.
        assertEquals("Shopping list", vm.titleDraft)
        assertEquals("milk\neggs\nbread\njam", vm.bodyDraft.text)
        assertTrue(vm.isModified)
        vm.save(alsoExit = false)
        assertEquals("Shopping list\nmilk\neggs\nbread\njam", api.records.getValue("NOTE-A").visibleText())
    }
}
