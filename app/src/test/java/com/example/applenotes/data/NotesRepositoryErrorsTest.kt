package com.example.applenotes.data

import com.example.applenotes.auth.ICloudSession
import com.example.applenotes.client.NotesError
import com.example.applenotes.testing.FakeAuthGateway
import com.example.applenotes.testing.FakeNotesApi
import com.example.applenotes.testing.REPLICA_UUID
import com.example.applenotes.testing.noteRecord
import com.example.applenotes.testing.visibleText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

/** Issue #11: auth refresh, throttling and typed errors at the repository level. */
class NotesRepositoryErrorsTest {
    private lateinit var api: FakeNotesApi
    private lateinit var auth: FakeAuthGateway
    private val waits = mutableListOf<Long>()

    @Before
    fun setUp() {
        api = FakeNotesApi()
        api.put(noteRecord("NOTE-A", "Shopping\nmilk"))
        auth = FakeAuthGateway(api)
    }

    private fun kotlinx.coroutines.test.TestScope.newRepo() = NotesRepository(
        auth = auth,
        replicaUuidLoader = { REPLICA_UUID },
        externalScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
        clock = { 1_700_000_000_000L },
        backoff = { waits += it },
    )

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit): T {
        try {
            block()
        } catch (t: Throwable) {
            if (t is T) return t
            throw AssertionError("expected ${T::class.simpleName}, got $t", t)
        }
        fail("expected ${T::class.simpleName}")
        throw IllegalStateException()
    }

    @Test
    fun expiredSessionIsRefreshedFromTheCookieJarAndTheReadRetried() = runTest {
        val repo = newRepo()
        repo.signIn()
        api.failNext["lookupNote"] = NotesError.AuthExpired()
        val rec = repo.lookupNote("NOTE-A")
        assertEquals("NOTE-A", rec.recordName)
        assertEquals("signIn + one re-bootstrap", 2, auth.openSessionCalls)
        assertFalse(repo.sessionExpired.value)
    }

    @Test
    fun expiredSessionThatCannotBeRefreshedFlagsSignIn() = runTest {
        val repo = newRepo()
        repo.signIn()
        api.failNext["lookupNote"] = NotesError.AuthExpired()
        auth.failOpen = NotesError.AuthExpired("validate 421")
        assertThrows<NotesError.AuthExpired> { repo.lookupNote("NOTE-A") }
        assertTrue(repo.sessionExpired.value)
        // Signing in again clears it.
        repo.signIn()
        assertFalse(repo.sessionExpired.value)
    }

    @Test
    fun stillExpiredAfterRefreshIsNotRetriedForever() = runTest {
        val repo = newRepo()
        repo.signIn()
        api.failQueue["lookupNote"] = ArrayDeque(listOf(NotesError.AuthExpired(), NotesError.AuthExpired()))
        assertThrows<NotesError.AuthExpired> { repo.lookupNote("NOTE-A") }
        assertEquals(2, auth.openSessionCalls)
        assertEquals(2, api.calls.count { it == "lookupNote" })
        assertTrue(repo.sessionExpired.value)
    }

    @Test
    fun saveAfterAuthRefreshWritesExactlyOnce() = runTest {
        val repo = newRepo()
        repo.signIn()
        api.failNext["modifyNoteBody"] = NotesError.AuthExpired()
        val saved = repo.saveNote(api.records.getValue("NOTE-A"), null, "Shopping\nmilk\neggs", true, null)
        assertEquals("Shopping\nmilk\neggs", saved.visibleText())
        assertEquals(1, api.modifies.size)
        assertEquals(2, auth.openSessionCalls)
    }

    @Test
    fun throttledReadsBackOffAndRetry() = runTest {
        val repo = newRepo()
        repo.signIn()
        api.failQueue["lookupNote"] = ArrayDeque(
            listOf(NotesError.Throttled(retryAfterMs = 2_000), NotesError.Throttled(retryAfterMs = null)),
        )
        assertEquals("NOTE-A", repo.lookupNote("NOTE-A").recordName)
        assertEquals(listOf(2_000L, 4_000L), waits)
    }

    @Test
    fun throttledReadsGiveUpAfterThreeAttemptsAndCapTheWait() = runTest {
        val repo = newRepo()
        repo.signIn()
        api.failQueue["lookupNote"] = ArrayDeque(List(5) { NotesError.Throttled(retryAfterMs = 3_600_000) })
        assertThrows<NotesError.Throttled> { repo.lookupNote("NOTE-A") }
        assertEquals(3, api.calls.count { it == "lookupNote" })
        assertEquals(listOf(30_000L, 30_000L), waits)
    }

    @Test
    fun throttledWritesAreNotRetriedSilently() = runTest {
        val repo = newRepo()
        repo.signIn()
        api.failNext["modifyNoteBody"] = NotesError.Throttled(retryAfterMs = 1_000)
        assertThrows<NotesError.Throttled> {
            repo.saveNote(api.records.getValue("NOTE-A"), null, "Shopping\nmilk\neggs", true, null)
        }
        assertTrue(waits.isEmpty())
        assertTrue(api.modifies.isEmpty())
    }

    @Test
    fun offlineIsSurfacedAsIs() = runTest {
        val repo = newRepo()
        repo.signIn()
        api.failNext["fetchRecents"] = NotesError.Offline(java.net.UnknownHostException("x"))
        assertThrows<NotesError.Offline> { repo.refresh() }
        assertEquals(1, auth.openSessionCalls)
    }

    @Test
    fun sessionDefaultsFollowTheDevice() {
        val s = ICloudSession(dsid = "1", ckdatabasewsUrl = "https://x", cookieHeader = "", clientId = "c")
        assertEquals(TimeZone.getDefault().id, s.timezone)
        assertEquals(Locale.getDefault().toString(), s.locale)
    }
}
