package com.example.applenotes.data

import com.example.applenotes.client.NotesError
import com.example.applenotes.proto.Gzip
import com.example.applenotes.proto.NoteBodyEditor
import com.example.applenotes.proto.TopotextInvariants
import com.example.applenotes.testing.FakeAuthGateway
import com.example.applenotes.testing.FakeNotesApi
import com.example.applenotes.testing.REPLICA_UUID
import com.example.applenotes.testing.noteRecord
import com.example.applenotes.testing.visibleText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Issue #4: a save that hits CONFLICT re-applies the edit to the fresh record. */
@OptIn(ExperimentalEncodingApi::class)
class NotesRepositoryConflictTest {
    private lateinit var api: FakeNotesApi

    @Before
    fun setUp() {
        api = FakeNotesApi()
        api.put(noteRecord("NOTE-A", "Shopping\nmilk\neggs"))
        api.checkTags = true
    }

    private fun TestScope.newRepo() = NotesRepository(
        auth = FakeAuthGateway(api),
        replicaUuidLoader = { REPLICA_UUID },
        externalScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
        clock = { 1_700_000_000_000L },
        backoff = {},
    )

    private fun bytes(b64: String) = Gzip.decompress(Base64.decode(b64))

    private fun spansOf(text: String) =
        listOf(NoteBodyEditor.AttrSpan(length = text.length, style = NoteBodyEditor.ParagraphStyle.BODY))

    @Test
    fun conflictThenSuccessMergesOntoTheFreshRecord() = runTest {
        val repo = newRepo()
        repo.signIn()
        val stale = api.records.getValue("NOTE-A")
        val remote = api.remoteEdit("NOTE-A", "Shopping list\nmilk\neggs")
        val local = "Shopping\nmilk\noat milk\neggs"

        val saved = repo.saveNote(stale, null, local, isPureAppend = false, newSpans = spansOf(local))

        assertEquals("Shopping list\nmilk\noat milk\neggs", saved.visibleText())
        assertEquals(1, api.conflicts)
        val m = api.modifies.single()
        // Written against the FRESH record: its tag and its replica-version field.
        assertEquals(remote.recordChangeTag, m.tag)
        assertEquals("cmVtb3RlLXZlcnNpb24=", m.replicaVersion)
        // The proto is the fresh one plus our edit: valid, nothing of the
        // other device's lost or re-attributed.
        val remoteBytes = bytes(remote.stringField("TextDataEncrypted")!!)
        val out = bytes(m.b64)
        TopotextInvariants.checkInvariants(out, "Shopping list\nmilk\noat milk\neggs", REPLICA_UUID)
        TopotextInvariants.checkMonotonic(remoteBytes, out, REPLICA_UUID)
        // Title came from the remote and didn't change in the merge.
        assertNull(m.newTitle)
        assertEquals("Shopping list", repo.notes.value.single().title)
    }

    @Test
    fun postCreateTagBumpJustWorks() = runTest {
        val repo = newRepo()
        repo.signIn()
        val stale = api.records.getValue("NOTE-A")
        // Mac's notesync bumped the tag; the text is unchanged.
        api.put(stale.copy(recordChangeTag = "bumped"))
        val saved = repo.saveNote(stale, null, "Shopping\nmilk\neggs\nbread", isPureAppend = true, newSpans = null)
        assertEquals("Shopping\nmilk\neggs\nbread", saved.visibleText())
        assertEquals("bumped", api.modifies.single().tag)
        TopotextInvariants.checkInvariants(bytes(api.modifies.single().b64), "Shopping\nmilk\neggs\nbread", REPLICA_UUID)
    }

    @Test
    fun overlappingEditsAreNotWritten() = runTest {
        val repo = newRepo()
        repo.signIn()
        val stale = api.records.getValue("NOTE-A")
        api.remoteEdit("NOTE-A", "Shopping\nmilk\n6 eggs")
        try {
            repo.saveNote(stale, null, "Shopping\nmilk\nbrown eggs", false, spansOf("Shopping\nmilk\nbrown eggs"))
            fail("expected a conflict")
        } catch (e: EditConflictException) {
            assertEquals("Shopping\nmilk\n6 eggs", e.remoteText)
            assertEquals("Shopping\nmilk\n6 eggs", e.fresh.visibleText())
        }
        assertTrue(api.modifies.isEmpty())
        assertEquals("Shopping\nmilk\n6 eggs", api.records.getValue("NOTE-A").visibleText())
    }

    @Test
    fun persistentConflictStopsAfterThreeAttempts() = runTest {
        val repo = newRepo()
        repo.signIn()
        api.checkTags = false
        api.failQueue["modifyNoteBody"] = ArrayDeque(List(10) { NotesError.Conflict() })
        try {
            repo.saveNote(api.records.getValue("NOTE-A"), null, "Shopping\nmilk\neggs\nX", true, null)
            fail("expected the conflict to surface")
        } catch (e: NotesError.Conflict) {
            // expected
        }
        assertEquals(MAX_SAVE_ATTEMPTS, api.calls.count { it == "modifyNoteBody" })
        assertTrue(api.modifies.isEmpty())
    }

    @Test
    fun conflictThatClearsOnTheSecondRetrySucceeds() = runTest {
        val repo = newRepo()
        repo.signIn()
        val stale = api.records.getValue("NOTE-A")
        api.remoteEdit("NOTE-A", "Shopping!\nmilk\neggs")
        // One more remote change lands between our lookup and our retry.
        api.beforeModify = { n -> if (n == 2) api.remoteEdit("NOTE-A", "Shopping!\nmilk\neggs!") }
        val saved = repo.saveNote(stale, null, "Shopping\nsoy milk\neggs", false, spansOf("Shopping\nsoy milk\neggs"))
        assertEquals("Shopping!\nsoy milk\neggs!", saved.visibleText())
        assertEquals(2, api.conflicts)
        assertEquals(1, api.modifies.size)
        TopotextInvariants.checkInvariants(bytes(api.modifies.single().b64), "Shopping!\nsoy milk\neggs!", REPLICA_UUID)
    }

    @Test
    fun remoteAlreadyHasTheSameEditSoNothingIsWritten() = runTest {
        val repo = newRepo()
        repo.signIn()
        val stale = api.records.getValue("NOTE-A")
        val remote = api.remoteEdit("NOTE-A", "Shopping\nmilk\neggs\nbread")
        val saved = repo.saveNote(stale, null, "Shopping\nmilk\neggs\nbread", true, null)
        assertEquals(remote.recordChangeTag, saved.recordChangeTag)
        assertTrue(api.modifies.isEmpty())
    }

    @Test
    fun remoteTurnedReadOnlyIsAConflictNotAWrite() = runTest {
        val repo = newRepo()
        repo.signIn()
        val stale = api.records.getValue("NOTE-A")
        api.put(noteRecord("NOTE-A", text = null, tag = "new", textB64 = "AAAA"))
        try {
            repo.saveNote(stale, null, "Shopping\nmilk\neggs\nX", true, null)
            fail("expected a conflict")
        } catch (e: EditConflictException) {
            // expected
        }
        assertTrue(api.modifies.isEmpty())
    }
}
