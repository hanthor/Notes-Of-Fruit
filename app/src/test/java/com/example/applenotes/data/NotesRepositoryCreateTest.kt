package com.example.applenotes.data

import com.example.applenotes.client.NoteRecord
import com.example.applenotes.data.local.InMemoryNotesStore
import com.example.applenotes.proto.Gzip
import com.example.applenotes.proto.TopotextInvariants
import com.example.applenotes.testing.FakeAuthGateway
import com.example.applenotes.testing.FakeDraftScheduler
import com.example.applenotes.testing.FakeNotesApi
import com.example.applenotes.testing.REPLICA_UUID
import com.example.applenotes.testing.folderRef
import com.example.applenotes.testing.noteRecord
import com.example.applenotes.testing.visibleText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import com.example.applenotes.data.local.PendingDraft
import com.example.applenotes.data.local.spansSignature
import com.example.applenotes.proto.NoteBodyEditor
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import com.example.applenotes.client.NotesError
import org.junit.Before
import org.junit.Test
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** The deferred-create path of [NotesRepository.saveNote] (a draft's first save). */
@OptIn(ExperimentalEncodingApi::class)
class NotesRepositoryCreateTest {
    private lateinit var api: FakeNotesApi
    private lateinit var store: InMemoryNotesStore
    private lateinit var scheduler: FakeDraftScheduler

    @Before
    fun setUp() {
        api = FakeNotesApi()
        api.put(noteRecord("NOTE-A", "Shopping\nmilk\neggs"))
        api.checkTags = true
        store = InMemoryNotesStore()
        scheduler = FakeDraftScheduler()
    }

    private fun TestScope.newRepo() = NotesRepository(
        auth = FakeAuthGateway(api),
        replicaUuidLoader = { REPLICA_UUID },
        externalScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler)),
        clock = { 1_700_000_000_000L },
        backoff = {},
        store = store,
        draftScheduler = scheduler,
    )

    private fun stub(name: String = "draft-1") = NoteRecord(name, "Note", null, emptyMap())

    private val folder = folderRef("DefaultFolder-CloudKit")

    private fun checkProto(record: NoteRecord, text: String) {
        val bytes = Gzip.decompress(Base64.decode(record.stringField("TextDataEncrypted")!!))
        TopotextInvariants.checkInvariants(bytes, text, REPLICA_UUID)
    }

    /** Finding: an empty first line (the title) was dropped by the title/body split. */
    @Test
    fun aLeadingBlankLineIsCreatedExactlyAsTyped() = runTest {
        val repo = newRepo()
        repo.signIn()
        val saved = repo.saveNote(stub(), folder, "\nfoo\nbar", isPureAppend = false, newSpans = null)
        assertEquals("\nfoo\nbar", saved.visibleText())
        assertEquals("\nfoo\nbar", api.records.getValue(saved.recordName).visibleText())
        checkProto(saved, "\nfoo\nbar")
        // The list shows the first non-blank line as the title, like Apple's clients.
        assertEquals("foo", repo.notes.value.first { it.recordName == saved.recordName }.title)
        assertEquals("foo", api.creates.single().title)
    }

    private fun createdNotes() = api.records.keys.filter { it != "NOTE-A" }

    /** Finding: the server committed the create but the answer was lost; the retry made a second note. */
    @Test
    fun aCreateRetriedAfterALostAnswerMakesExactlyOneNote() = runTest {
        val repo = newRepo()
        repo.signIn()
        api.loseResponse["createNote"] = NotesError.Offline()
        try {
            repo.saveNote(stub(), folder, "Groceries\napples", isPureAppend = false, newSpans = null)
            fail("expected Offline")
        } catch (e: NotesError.Offline) {
            // the answer never arrived
        }
        assertEquals(1, createdNotes().size)
        // Retry (editor Retry / back online / worker): same recordName, refused, looked up.
        val saved = repo.saveNote(stub(), folder, "Groceries\napples", isPureAppend = false, newSpans = null)
        assertEquals(1, createdNotes().size)
        assertEquals(createdNotes().single(), saved.recordName)
        assertEquals(2, api.creates.size)
        assertEquals(api.creates[0].recordName, api.creates[1].recordName)
        assertEquals("Groceries\napples", saved.visibleText())
        assertTrue(api.modifies.isEmpty())
        assertEquals(saved.recordName, store.createdRecordName("draft-1"))
        // A third save modifies, never creates.
        repo.saveNote(stub(), folder, "Groceries\napples\npears", isPureAppend = false, newSpans = null)
        assertEquals(2, api.creates.size)
        assertEquals("Groceries\napples\npears", api.records.getValue(saved.recordName).visibleText())
    }

    /** The lost create carried an older version of the draft: the retry brings the note up to date. */
    @Test
    fun aRetryWithNewerTextUpdatesTheNoteThatWasCreated() = runTest {
        val repo = newRepo()
        repo.signIn()
        api.loseResponse["createNote"] = NotesError.Offline()
        runCatching { repo.saveNote(stub(), folder, "Groceries\napples", isPureAppend = false, newSpans = null) }
        val saved = repo.saveNote(stub(), folder, "Groceries\napples\npears", isPureAppend = false, newSpans = null)
        assertEquals(1, createdNotes().size)
        assertEquals("Groceries\napples\npears", api.records.getValue(saved.recordName).visibleText())
        checkProto(api.records.getValue(saved.recordName), "Groceries\napples\npears")
    }

    /** A create that failed before reaching the server is re-sent under the same name. */
    @Test
    fun aCreateThatNeverArrivedIsResentUnderTheSameName() = runTest {
        val repo = newRepo()
        repo.signIn()
        api.failNext["createNote"] = NotesError.Offline()
        runCatching { repo.saveNote(stub(), folder, "Hi", isPureAppend = false, newSpans = null) }
        assertEquals(0, createdNotes().size)
        val saved = repo.saveNote(stub(), folder, "Hi", isPureAppend = false, newSpans = null)
        assertEquals(1, createdNotes().size)
        assertEquals(api.creates[0].recordName, saved.recordName)
    }

    /** Finding: a leftover draft row saved after a create overwrote edits made on another device. */
    @Test
    fun aLeftoverDraftAfterACreateMergesWithRemoteEdits() = runTest {
        val repo = newRepo()
        repo.signIn()
        val created = repo.saveNote(stub(), folder, "Groceries\napples", isPureAppend = false, newSpans = null)
        // The Mac edits the new note's title.
        api.remoteEdit(created.recordName, "Groceries list\napples")
        // The worker / a retry saves the editor's leftover row of the draft.
        repo.saveNote(stub(), folder, "Groceries\napples\npears", isPureAppend = false, newSpans = null)
        val now = api.records.getValue(created.recordName)
        assertEquals("Groceries list\napples\npears", now.visibleText())
        assertEquals(1, api.conflicts)
    }

    @Test
    fun aLeftoverDraftOverlappingARemoteEditIsAConflictNotAnOverwrite() = runTest {
        val repo = newRepo()
        repo.signIn()
        val created = repo.saveNote(stub(), folder, "Groceries\napples", isPureAppend = false, newSpans = null)
        api.remoteEdit(created.recordName, "Groceries\nred apples")
        try {
            repo.saveNote(stub(), folder, "Groceries\ngreen apples", isPureAppend = false, newSpans = null)
            fail("expected EditConflictException")
        } catch (e: EditConflictException) {
            // nothing written
        }
        assertEquals("Groceries\nred apples", api.records.getValue(created.recordName).visibleText())
    }

    private val richText = "Groceries\napples"
    private val richSpans = listOf(
        NoteBodyEditor.AttrSpan(length = 10, style = NoteBodyEditor.ParagraphStyle.TITLE),
        NoteBodyEditor.AttrSpan(length = 6, style = NoteBodyEditor.ParagraphStyle.BODY, bold = true),
    )

    private fun richRow(key: String = "draft-1") = PendingDraft(
        key = key,
        baseRecord = null,
        folderRefJson = folder.toString(),
        fullText = richText,
        spansSignature = spansSignature(richSpans),
        snapshotJson = "{}",
        updatedAtMs = 1,
    )

    private fun boldAt(recordName: String, i: Int): Boolean {
        val r = api.records.getValue(recordName)
        val spans = NoteBodyEditor.parseAttributeRunsFromBase64(r.stringField("TextDataEncrypted")!!)
        return NoteBodyEditor.expandSpansToChars(r.visibleText()!!, spans)[i].bold
    }

    /** The create path itself applies the formatting (createNote writes plain text). */
    @Test
    fun aFormattedDraftIsCreatedAndFormattedInOneSave() = runTest {
        val repo = newRepo()
        repo.signIn()
        store.putDraft(richRow())
        val saved = repo.saveNote(stub(), folder, richText, isPureAppend = false, newSpans = richSpans)
        assertEquals(1, api.creates.size)
        assertEquals(1, api.modifies.size)
        assertTrue(boldAt(saved.recordName, 12))
        checkProto(api.records.getValue(saved.recordName), richText)
        assertTrue(store.allDrafts().isEmpty())
    }

    /**
     * Finding: after the create the outbox row was cleared as if the
     * formatting had been saved. When the follow-up can't be sent now, it
     * must stay in the outbox under the created recordName and be scheduled.
     */
    @Test
    fun aFormattingFollowUpThatFailsStaysInTheOutboxUnderTheCreatedName() = runTest {
        val repo = newRepo()
        repo.signIn()
        store.putDraft(richRow())
        api.failNext["modifyNoteBody"] = NotesError.Offline()
        val saved = repo.saveNote(stub(), folder, richText, isPureAppend = false, newSpans = richSpans)
        // The note exists (plain for now); the save itself succeeded.
        assertEquals(1, api.creates.size)
        assertNull(store.getDraft("draft-1"))
        val row = store.getDraft(saved.recordName)!!
        assertEquals(richText, row.fullText)
        assertEquals(spansSignature(richSpans), row.spansSignature)
        assertEquals(saved.recordName, row.baseRecord!!.recordName)
        assertNull(row.folderRefJson)
        assertTrue(saved.recordName in scheduler.enqueued)
    }

    @Test
    fun onlyBlankLinesAndTrailingNewlinesSurviveTheCreate() = runTest {
        val repo = newRepo()
        repo.signIn()
        for ((i, text) in listOf("\n\nx", "title\n", "a\n\n", " \nb").withIndex()) {
            val saved = repo.saveNote(stub("draft-$i"), folder, text, isPureAppend = false, newSpans = null)
            assertEquals(text, saved.visibleText())
            checkProto(saved, text)
        }
    }
}
