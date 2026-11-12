package com.example.applenotes.ui.detail

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import com.example.applenotes.client.NoteRecord
import com.example.applenotes.data.NotesRepository
import com.example.applenotes.data.local.InMemoryNotesStore
import com.example.applenotes.testing.FakeAuthGateway
import com.example.applenotes.testing.FakeConnectivity
import com.example.applenotes.testing.FakeDraftScheduler
import com.example.applenotes.testing.FakeNotesApi
import com.example.applenotes.testing.MainDispatcherRule
import com.example.applenotes.testing.REPLICA_UUID
import com.example.applenotes.testing.folderRef
import com.example.applenotes.testing.noteRecord
import com.example.applenotes.testing.visibleText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Issue #13: drafts on disk, the restore prompt, autosave and the background hand-off. */
@OptIn(ExperimentalCoroutinesApi::class)
class NoteDetailDraftsTest {
    @get:Rule
    val main = MainDispatcherRule()

    private lateinit var api: FakeNotesApi
    private lateinit var store: InMemoryNotesStore
    private lateinit var scheduler: FakeDraftScheduler
    private lateinit var net: FakeConnectivity
    private lateinit var repo: NotesRepository

    @Before
    fun setUp() = runTest(main.dispatcher) {
        api = FakeNotesApi()
        api.put(noteRecord("NOTE-A", "Shopping\nmilk\neggs"))
        api.checkTags = true
        store = InMemoryNotesStore()
        scheduler = FakeDraftScheduler()
        net = FakeConnectivity()
        repo = NotesRepository(
            auth = FakeAuthGateway(api),
            replicaUuidLoader = { REPLICA_UUID },
            externalScope = CoroutineScope(SupervisorJob() + main.dispatcher),
            clock = { 1_700_000_000_000L },
            connectivity = net,
            backoff = {},
            store = store,
            draftScheduler = scheduler,
        )
        repo.signIn()
    }

    private fun newVm(autosave: Long? = null) =
        NoteDetailViewModel(repo, SavedStateHandle(), clock = { 42L }, autosaveDelayMs = autosave)

    private fun NoteDetailViewModel.type(body: String) =
        onBodyChange(TextFieldValue(body, TextRange(body.length)))

    private fun advance(ms: Long) {
        main.dispatcher.scheduler.advanceTimeBy(ms)
        main.dispatcher.scheduler.runCurrent()
    }

    private fun draft(key: String) = runBlocking { store.getDraft(key) }

    @Test
    fun theDraftIsWrittenToDiskAfterASecondWithoutTyping() {
        val vm = newVm()
        vm.open("o1", api.records.getValue("NOTE-A"), null)
        vm.type("milk\neggs\nbr")
        advance(500)
        vm.type("milk\neggs\nbread")
        advance(900)
        assertNull("debounced: still typing", draft("NOTE-A"))
        advance(200)
        val d = draft("NOTE-A")!!
        assertEquals("Shopping\nmilk\neggs\nbread", d.fullText)
        assertEquals("tag-1", d.baseRecord!!.recordChangeTag)
        assertEquals(1, repo.pendingDrafts.value.size)
    }

    @Test
    fun aConfirmedSaveRemovesTheDiskDraft() {
        val vm = newVm()
        vm.open("o1", api.records.getValue("NOTE-A"), null)
        vm.type("milk\neggs\nbread")
        advance(1_100)
        assertNotNull(draft("NOTE-A"))
        vm.save(alsoExit = false)
        assertNull(draft("NOTE-A"))
    }

    @Test
    fun aFailedSaveKeepsTheDiskDraft() {
        val vm = newVm()
        vm.open("o1", api.records.getValue("NOTE-A"), null)
        vm.type("milk\neggs\nbread")
        api.failNext["modifyNoteBody"] = com.example.applenotes.client.NotesError.Offline()
        vm.save(alsoExit = false)
        assertEquals("Shopping\nmilk\neggs\nbread", draft("NOTE-A")!!.fullText)
    }

    @Test
    fun undoingTheEditRemovesTheDiskDraft() {
        val vm = newVm()
        vm.open("o1", api.records.getValue("NOTE-A"), null)
        vm.type("milk\neggs\nbread")
        advance(1_100)
        vm.type("milk\neggs")
        advance(1_100)
        assertNull(draft("NOTE-A"))
    }

    @Test
    fun autosaveRunsAfterThreeIdleSecondsOnlyOnline() {
        val vm = newVm(autosave = 3_000)
        vm.open("o1", api.records.getValue("NOTE-A"), null)
        vm.type("milk\neggs\nbread")
        advance(2_900)
        assertTrue(api.modifies.isEmpty())
        advance(200)
        assertEquals(1, api.modifies.size)
        assertFalse(vm.isModified)

        net.online.value = false
        vm.type("milk\neggs\nbread\nbutter")
        advance(5_000)
        assertEquals(1, api.modifies.size)
    }

    @Test
    fun autosaveNeverCreatesAnEmptyNote() {
        val vm = newVm(autosave = 3_000)
        vm.open("o1", NoteRecord("draft-1", "Note", null, emptyMap()), folderRef("DefaultFolder-CloudKit"))
        vm.onTitleChange("  ")
        advance(5_000)
        assertTrue(api.creates.isEmpty())
        vm.onTitleChange("Groceries")
        advance(5_000)
        assertEquals(1, api.creates.size)
    }

    @Test
    fun pausingWithUnsavedChangesHandsTheDraftToTheWorker() {
        val vm = newVm()
        vm.open("o1", api.records.getValue("NOTE-A"), null)
        net.online.value = false
        vm.type("milk\neggs\nbread")
        vm.autoSave()
        assertEquals(listOf("NOTE-A"), scheduler.enqueued)
        assertEquals("Shopping\nmilk\neggs\nbread", draft("NOTE-A")!!.fullText)
    }

    @Test
    fun leavingANewNoteDraftKeepsItForTheWorker() {
        val vm = newVm()
        vm.open("o1", NoteRecord("draft-9", "Note", null, emptyMap()), folderRef("DefaultFolder-CloudKit"))
        vm.onTitleChange("Groceries")
        vm.close()
        assertEquals(listOf("draft-9"), scheduler.enqueued)
        val d = draft("draft-9")!!
        assertNull(d.baseRecord)
        assertEquals("Groceries", d.fullText)
        assertNotNull(d.folderRefJson)
    }

    @Test
    fun leavingWithoutChangesSchedulesNothing() {
        val vm = newVm()
        vm.open("o1", api.records.getValue("NOTE-A"), null)
        vm.close()
        assertTrue(scheduler.enqueued.isEmpty())
        assertTrue(runBlocking { store.allDrafts() }.isEmpty())
    }

    @Test
    fun discardingUnsavedChangesDropsTheDiskDraft() {
        val vm = newVm()
        vm.open("o1", api.records.getValue("NOTE-A"), null)
        vm.type("milk\neggs\nbread")
        advance(1_100)
        vm.discardUnsavedChanges()
        vm.close()
        assertNull(draft("NOTE-A"))
        assertTrue(scheduler.enqueued.isEmpty())
    }

    @Test
    fun openingANoteWithAnOlderUnsentDraftOffersToRestoreIt() {
        // Session 1: typed, then the process died before saving.
        val first = newVm()
        first.open("o1", api.records.getValue("NOTE-A"), null)
        net.online.value = false
        first.type("milk\neggs\nbread")
        first.autoSave()

        // Session 2: opened again from the list.
        net.online.value = true
        val vm = newVm()
        vm.open("o2", api.records.getValue("NOTE-A"), null)
        assertNotNull(vm.status.value.savedDraft)
        assertEquals("milk\neggs", vm.bodyDraft.text)
        // Nothing overwrites the old draft while the question is open.
        assertEquals("Shopping\nmilk\neggs\nbread", draft("NOTE-A")!!.fullText)

        vm.restoreSavedDraft()
        assertNull(vm.status.value.savedDraft)
        assertEquals("Shopping\nmilk\neggs\nbread", api.records.getValue("NOTE-A").visibleText())
        assertNull(draft("NOTE-A"))
    }

    @Test
    fun restoringOntoAChangedNoteGoesThroughTheConflictMerge() {
        val first = newVm()
        first.open("o1", api.records.getValue("NOTE-A"), null)
        net.online.value = false
        first.type("milk\neggs\nbread")
        first.autoSave()

        net.online.value = true
        api.remoteEdit("NOTE-A", "Shopping list\nmilk\neggs")
        val vm = newVm()
        vm.open("o2", api.records.getValue("NOTE-A"), null)
        vm.restoreSavedDraft()
        assertEquals("Shopping list\nmilk\neggs\nbread", api.records.getValue("NOTE-A").visibleText())
        assertEquals(1, api.conflicts)
    }

    @Test
    fun aDraftFlaggedByTheWorkerEndsInTheConflictDialog() {
        val first = newVm()
        first.open("o1", api.records.getValue("NOTE-A"), null)
        net.online.value = false
        first.type("oat milk\neggs")
        first.autoSave()
        runBlocking { repo.markDraftConflict("NOTE-A") }

        net.online.value = true
        api.remoteEdit("NOTE-A", "Shopping\nsoy milk\neggs")
        val vm = newVm()
        vm.open("o2", api.records.getValue("NOTE-A"), null)
        assertTrue(vm.status.value.savedDraft!!.conflict)
        vm.restoreSavedDraft()
        assertNotNull(vm.status.value.conflict)
        assertTrue(api.modifies.isEmpty())
        vm.keepBoth()
        assertTrue(api.records.getValue("NOTE-A").visibleText()!!.contains("oat milk"))
        assertTrue(api.records.getValue("NOTE-A").visibleText()!!.contains("soy milk"))
    }

    @Test
    fun discardingTheRestorableDraftKeepsTheServerVersion() {
        val first = newVm()
        first.open("o1", api.records.getValue("NOTE-A"), null)
        net.online.value = false
        first.type("milk\neggs\nbread")
        first.autoSave()

        net.online.value = true
        val vm = newVm()
        vm.open("o2", api.records.getValue("NOTE-A"), null)
        vm.discardSavedDraft()
        assertNull(vm.status.value.savedDraft)
        assertNull(draft("NOTE-A"))
        assertEquals("milk\neggs", vm.bodyDraft.text)
        assertTrue(api.modifies.isEmpty())
    }

    @Test
    fun aDraftThatMatchesTheServerIsDroppedSilently() {
        runBlocking {
            store.putDraft(
                com.example.applenotes.data.local.PendingDraft(
                    key = "NOTE-A",
                    baseRecord = api.records.getValue("NOTE-A"),
                    folderRefJson = null,
                    fullText = "Shopping\nmilk\neggs",
                    spansSignature = com.example.applenotes.data.local.spansSignature(
                        com.example.applenotes.data.decodeNoteBody(api.records.getValue("NOTE-A")).spans,
                    ),
                    snapshotJson = DraftSnapshot("Shopping", "milk\neggs", 0, 0, emptyList(), false).encode(),
                    updatedAtMs = 1,
                ),
            )
        }
        val vm = newVm()
        vm.open("o1", api.records.getValue("NOTE-A"), null)
        assertNull(vm.status.value.savedDraft)
        assertNull(draft("NOTE-A"))
    }

    @Test
    fun theOfflineCopyFlagReachesTheStatus() {
        val vm = newVm()
        vm.open("o1", api.records.getValue("NOTE-A"), null, offlineCopy = true)
        assertTrue(vm.status.value.offlineCopy)
    }

    // ----- Our own background save is not "another device" -----

    private fun noConflictAnd(vm: NoteDetailViewModel, text: String, recordName: String = "NOTE-H") {
        assertNull(vm.status.value.conflict)
        assertFalse(vm.status.value.saveFailed)
        assertEquals(text, api.records.getValue(recordName).visibleText())
        assertNull(draft(recordName))
    }

    /**
     * Finding: the worker saved the editor's draft (R0 → R1) while the
     * editor stayed open; the editor's next save used the stale R0 tag,
     * CONFLICTed and merged base "hello" / local "hello world" / remote
     * "hello wor" into a conflict dialog against the user's own text.
     */
    @Test
    fun aBackgroundSaveOfTheOpenDraftIsNotAConflict() = runTest(main.dispatcher) {
        api.put(noteRecord("NOTE-H", "hello"))
        val vm = newVm()
        vm.open("o1", api.records.getValue("NOTE-H"), null)
        vm.onTitleChange("hello wor")
        net.online.value = false
        vm.autoSave() // ON_PAUSE while offline: handed to the worker…
        assertEquals(listOf("NOTE-H"), scheduler.enqueued.distinct())
        assertTrue(api.modifies.isEmpty())
        net.online.value = true
        // …which runs while the editor is still open.
        assertEquals(com.example.applenotes.sync.DraftSyncer.Outcome.Done, com.example.applenotes.sync.DraftSyncer(repo).sync("NOTE-H"))
        vm.onTitleChange("hello world")
        vm.save(alsoExit = false)
        noConflictAnd(vm, "hello world")
        assertNull(vm.status.value.message)
    }

    /** The editor never heard of the worker's write: the repository rebases onto it. */
    @Test
    fun aSaveFromAStaleBaseRebasesOntoOurOwnLastWrite() = runTest(main.dispatcher) {
        api.put(noteRecord("NOTE-H", "hello"))
        val r0 = api.records.getValue("NOTE-H")
        repo.saveNote(r0, null, "hello wor", isPureAppend = false, newSpans = null)
        val saved = repo.saveNote(r0, null, "hello world", isPureAppend = false, newSpans = null)
        assertEquals("hello world", saved.visibleText())
        assertEquals("hello world", api.records.getValue("NOTE-H").visibleText())
    }

    /**
     * A real remote edit after our write is still merged, not overwritten:
     * the merge base is what we last wrote (the other device started from it).
     */
    @Test
    fun aRemoteEditAfterOurOwnWriteStillGoesThroughTheMerge() = runTest(main.dispatcher) {
        api.put(noteRecord("NOTE-H", "hello\nline two"))
        val r0 = api.records.getValue("NOTE-H")
        repo.saveNote(r0, null, "hello wor\nline two", isPureAppend = false, newSpans = null)
        api.remoteEdit("NOTE-H", "hello wor\nline 2")
        repo.saveNote(r0, null, "hello world\nline two", isPureAppend = false, newSpans = null)
        assertEquals("hello world\nline 2", api.records.getValue("NOTE-H").visibleText())
    }

    /** A write by automation is not part of the editor's draft: never used as its merge base. */
    @Test
    fun anAutomationWriteIsNotTreatedAsTheEditorsOwn() = runTest(main.dispatcher) {
        api.put(noteRecord("NOTE-H", "hello"))
        val r0 = api.records.getValue("NOTE-H")
        repo.saveNote(r0, null, "hello\nappended", isPureAppend = true, newSpans = null, draftLineage = false)
        // Rebasing onto it would silently drop the appended line; the merge
        // against the editor's real base reports the overlap instead.
        try {
            repo.saveNote(r0, null, "hello world", isPureAppend = false, newSpans = null)
            org.junit.Assert.fail("expected a conflict")
        } catch (e: com.example.applenotes.data.EditConflictException) {
            // expected
        }
        assertEquals("hello\nappended", api.records.getValue("NOTE-H").visibleText())
    }

    /** ON_PAUSE lands while an autosave is in flight: the worker's copy follows the editor's write. */
    @Test
    fun pauseDuringAnInFlightAutosaveIsNotAConflict() = runTest(main.dispatcher) {
        api.put(noteRecord("NOTE-H", "hello"))
        val vm = newVm()
        vm.open("o1", api.records.getValue("NOTE-H"), null)
        vm.onTitleChange("hello wor")
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        api.gates["modifyNoteBody"] = gate
        vm.save(alsoExit = false) // the autosave, in flight
        vm.onTitleChange("hello world")
        vm.autoSave() // ON_PAUSE: hand-off to the worker (row based on R0)
        val worker = kotlinx.coroutines.CoroutineScope(main.dispatcher).async {
            com.example.applenotes.sync.DraftSyncer(repo).sync("NOTE-H")
        }
        gate.complete(Unit)
        advance(10)
        worker.await()
        noConflictAnd(vm, "hello world")
        assertEquals(0, repo.pendingDrafts.value.count { it.conflict })
    }
}
