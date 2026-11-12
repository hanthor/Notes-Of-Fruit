package com.example.applenotes.sync

import com.example.applenotes.client.NoteRecord
import com.example.applenotes.client.NotesError
import com.example.applenotes.data.NotesRepository
import com.example.applenotes.data.local.InMemoryNotesStore
import com.example.applenotes.data.local.PendingDraft
import com.example.applenotes.data.local.spansSignature
import com.example.applenotes.proto.NoteBodyEditor
import com.example.applenotes.testing.FakeAuthGateway
import com.example.applenotes.testing.FakeConnectivity
import com.example.applenotes.testing.FakeNotesApi
import com.example.applenotes.testing.REPLICA_UUID
import com.example.applenotes.testing.folderRef
import com.example.applenotes.testing.noteRecord
import com.example.applenotes.testing.visibleText
import com.example.applenotes.ui.detail.DraftSnapshot
import com.example.applenotes.ui.detail.SavedSpan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Issue #13: the background save of an unsent draft (the worker's logic). */
class DraftSyncerTest {
    private lateinit var api: FakeNotesApi
    private lateinit var store: InMemoryNotesStore
    private lateinit var net: FakeConnectivity

    @Before
    fun setUp() {
        api = FakeNotesApi()
        api.put(noteRecord("NOTE-A", "Shopping\nmilk\neggs"))
        api.checkTags = true
        store = InMemoryNotesStore()
        net = FakeConnectivity()
    }

    /** A repository as the worker gets it in a fresh process: not signed in, saved cookies. */
    private fun TestScope.newRepo() = NotesRepository(
        auth = FakeAuthGateway(api),
        replicaUuidLoader = { REPLICA_UUID },
        externalScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler)),
        connectivity = net,
        backoff = {},
        store = store,
    )

    private fun plain(text: String) =
        listOf(NoteBodyEditor.AttrSpan(length = text.length, style = NoteBodyEditor.ParagraphStyle.BODY))

    private fun draftOf(
        key: String,
        base: NoteRecord?,
        text: String,
        spans: List<NoteBodyEditor.AttrSpan> = plain(text),
        folder: String? = null,
    ): PendingDraft {
        val nl = text.indexOf('\n')
        val snapshot = DraftSnapshot(
            title = if (nl < 0) text else text.substring(0, nl),
            body = if (nl < 0) "" else text.substring(nl + 1),
            selectionStart = 0,
            selectionEnd = 0,
            spans = spans.map { SavedSpan.of(it) },
            viewMode = false,
        )
        return PendingDraft(
            key = key,
            baseRecord = base,
            folderRefJson = folder?.let { folderRef(it).toString() },
            fullText = text,
            spansSignature = spansSignature(spans),
            snapshotJson = snapshot.encode(),
            updatedAtMs = 1,
        )
    }

    @Test
    fun savesAnExistingNoteAndClearsTheDraft() = runTest {
        val repo = newRepo()
        store.putDraft(draftOf("NOTE-A", api.records.getValue("NOTE-A"), "Shopping\nmilk\neggs\nbread"))
        assertEquals(DraftSyncer.Outcome.Done, DraftSyncer(repo).sync("NOTE-A"))
        assertEquals("Shopping\nmilk\neggs\nbread", api.records.getValue("NOTE-A").visibleText())
        assertNull(store.getDraft("NOTE-A"))
    }

    @Test
    fun aStaleBaseGoesThroughTheConflictMerge() = runTest {
        val repo = newRepo()
        val base = api.records.getValue("NOTE-A")
        api.remoteEdit("NOTE-A", "Shopping list\nmilk\neggs")
        store.putDraft(draftOf("NOTE-A", base, "Shopping\nmilk\neggs\nbread"))
        assertEquals(DraftSyncer.Outcome.Done, DraftSyncer(repo).sync("NOTE-A"))
        assertEquals("Shopping list\nmilk\neggs\nbread", api.records.getValue("NOTE-A").visibleText())
        assertEquals(1, api.conflicts)
    }

    @Test
    fun overlappingRemoteEditsAreKeptForTheEditor() = runTest {
        val repo = newRepo()
        val base = api.records.getValue("NOTE-A")
        api.remoteEdit("NOTE-A", "Shopping\nsoy milk\neggs")
        store.putDraft(draftOf("NOTE-A", base, "Shopping\noat milk\neggs"))
        assertEquals(DraftSyncer.Outcome.Conflict, DraftSyncer(repo).sync("NOTE-A"))
        assertTrue(api.modifies.isEmpty())
        val kept = store.getDraft("NOTE-A")!!
        assertTrue(kept.conflict)
        assertEquals("Shopping\noat milk\neggs", kept.fullText)
        // A later run leaves a flagged draft alone.
        assertEquals(DraftSyncer.Outcome.Done, DraftSyncer(repo).sync("NOTE-A"))
        assertTrue(api.modifies.isEmpty())
    }

    @Test
    fun offlineIsRetriedAndTheDraftKept() = runTest {
        val repo = newRepo()
        store.putDraft(draftOf("NOTE-A", api.records.getValue("NOTE-A"), "Shopping\nmilk\neggs\nbread"))
        net.online.value = false
        assertEquals(DraftSyncer.Outcome.Retry, DraftSyncer(repo).sync("NOTE-A"))
        net.online.value = true
        api.failNext["modifyNoteBody"] = NotesError.Offline()
        assertEquals(DraftSyncer.Outcome.Retry, DraftSyncer(repo).sync("NOTE-A"))
        assertNotNull(store.getDraft("NOTE-A"))
        assertTrue(api.modifies.isEmpty())
        // Next run succeeds.
        assertEquals(DraftSyncer.Outcome.Done, DraftSyncer(repo).sync("NOTE-A"))
        assertEquals(1, api.modifies.size)
    }

    @Test
    fun otherFailuresKeepTheDraft() = runTest {
        val repo = newRepo()
        store.putDraft(draftOf("NOTE-A", api.records.getValue("NOTE-A"), "Shopping\nmilk\neggs\nbread"))
        api.failNext["modifyNoteBody"] = NotesError.Server(500, null)
        assertTrue(DraftSyncer(repo).sync("NOTE-A") is DraftSyncer.Outcome.Failed)
        assertNotNull(store.getDraft("NOTE-A"))
    }

    @Test
    fun nothingToDoWithoutADraft() = runTest {
        assertEquals(DraftSyncer.Outcome.Done, DraftSyncer(newRepo()).sync("NOTE-A"))
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun aNewNoteIsCreatedExactlyOnce() = runTest {
        val repo = newRepo()
        store.putDraft(draftOf("draft-1", null, "Groceries\napples", folder = "DefaultFolder-CloudKit"))
        assertEquals(DraftSyncer.Outcome.Done, DraftSyncer(repo).sync("draft-1"))
        assertEquals(1, api.creates.size)
        assertNull(store.getDraft("draft-1"))
        assertEquals(api.created(), store.createdRecordName("draft-1"))

        // The editor still holds the never-saved stub and saves it again
        // (it was open while the worker ran): that modifies, never re-creates.
        val stub = NoteRecord("draft-1", "Note", null, emptyMap())
        val saved = repo.saveNote(stub, folderRef("DefaultFolder-CloudKit"), "Groceries\napples\npears", false, null)
        assertEquals(1, api.creates.size)
        assertEquals(api.created(), saved.recordName)
        assertEquals("Groceries\napples\npears", api.records.getValue(api.created()).visibleText())
    }

    @Test
    fun theSameSaveFromTheEditorAfterTheWorkerWritesNothing() = runTest {
        val repo = newRepo()
        store.putDraft(draftOf("draft-1", null, "Groceries\napples", folder = "DefaultFolder-CloudKit"))
        DraftSyncer(repo).sync("draft-1")
        val stub = NoteRecord("draft-1", "Note", null, emptyMap())
        repo.saveNote(stub, folderRef("DefaultFolder-CloudKit"), "Groceries\napples", false, null)
        assertEquals(1, api.creates.size)
        assertTrue(api.modifies.isEmpty())
    }

    @Test
    fun aFormattedNewNoteGetsItsFormattingInAFollowUpModify() = runTest {
        val repo = newRepo()
        val text = "Groceries\napples"
        val spans = listOf(
            NoteBodyEditor.AttrSpan(length = 10, style = NoteBodyEditor.ParagraphStyle.TITLE),
            NoteBodyEditor.AttrSpan(length = 6, style = NoteBodyEditor.ParagraphStyle.BODY, bold = true),
        )
        store.putDraft(draftOf("draft-1", null, text, spans = spans, folder = "DefaultFolder-CloudKit"))
        assertEquals(DraftSyncer.Outcome.Done, DraftSyncer(repo).sync("draft-1"))
        assertEquals(1, api.creates.size)
        assertEquals(1, api.modifies.size)
        assertTrue(store.allDrafts().isEmpty())
    }

    @Test
    fun aDraftReplacedWhileWaitingIsSavedInItsLatestForm() = runTest {
        val repo = newRepo()
        val base = api.records.getValue("NOTE-A")
        store.putDraft(draftOf("NOTE-A", base, "Shopping\nmilk\neggs\nbread"))
        // The editor rewrote the row between the worker reading it and saving.
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        api.gates["modifyNoteBody"] = gate
        val job = async { DraftSyncer(repo).sync("NOTE-A") }
        runCurrent()
        store.putDraft(draftOf("NOTE-A", base, "Shopping\nmilk\neggs\nbread\nbutter"))
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(DraftSyncer.Outcome.Done, job.await())
        assertEquals("Shopping\nmilk\neggs\nbread\nbutter", api.records.getValue("NOTE-A").visibleText())
    }
}
