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
