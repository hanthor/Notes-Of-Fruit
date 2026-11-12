package com.example.applenotes.data

import com.example.applenotes.client.FolderSummary
import com.example.applenotes.client.NoteSummary
import com.example.applenotes.client.NotesError
import com.example.applenotes.client.ZoneChanges
import com.example.applenotes.data.local.InMemoryNotesStore
import com.example.applenotes.data.local.PendingDraft
import com.example.applenotes.data.local.StoreMeta
import com.example.applenotes.testing.FakeAuthGateway
import com.example.applenotes.testing.FakeConnectivity
import com.example.applenotes.testing.FakeDraftScheduler
import com.example.applenotes.testing.FakeNotesApi
import com.example.applenotes.testing.REPLICA_UUID
import com.example.applenotes.testing.noteRecord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Issue #12: the local cache, incremental sync and its fallback. */
class NotesRepositorySyncTest {
    private lateinit var api: FakeNotesApi
    private lateinit var auth: FakeAuthGateway
    private lateinit var store: InMemoryNotesStore
    private lateinit var net: FakeConnectivity
    private val scheduler = FakeDraftScheduler()
    private var now = 1_700_000_000_000L

    @Before
    fun setUp() {
        api = FakeNotesApi()
        api.put(noteRecord("NOTE-A", "Shopping\nmilk"))
        api.put(noteRecord("NOTE-B", "Other\nbody", folder = "FOLDER-F"))
        auth = FakeAuthGateway(api)
        store = InMemoryNotesStore()
        net = FakeConnectivity()
    }

    private fun TestScope.newRepo() = NotesRepository(
        auth = auth,
        replicaUuidLoader = { REPLICA_UUID },
        externalScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler)),
        clock = { now },
        connectivity = net,
        backoff = {},
        store = store,
        draftScheduler = scheduler,
    )

    private fun summary(rn: String, title: String = rn, folder: String = "DefaultFolder-CloudKit", mod: Long = 1L) =
        NoteSummary(rn, title, "", mod, deleted = false, folderRecordName = folder)

    private fun changes(
        token: String,
        notes: List<NoteSummary> = emptyList(),
        removed: List<String> = emptyList(),
        folders: List<FolderSummary> = emptyList(),
        more: Boolean = false,
    ) = ZoneChanges(notes, folders, removed, ignored = 0, syncToken = token, moreComing = more)

    @Test
    fun cachedListIsShownBeforeAnyNetworkCall() = runTest {
        store.upsertSummaries(listOf(summary("CACHED-1", mod = 2), summary("CACHED-2", mod = 5)))
        store.upsertFolders(listOf(FolderSummary("FOLDER-E", "Empty", isTrash = false, isDefault = false)))
        val repo = newRepo()
        assertTrue(repo.loadCache())
        assertEquals(listOf("CACHED-2", "CACHED-1"), repo.notes.value.map { it.recordName })
        assertEquals("Empty", repo.folders.value.single().displayName)
        assertTrue(api.calls.isEmpty())
        assertEquals(0, auth.openSessionCalls)
    }

    @Test
    fun fullSyncReplacesTheCacheAndPrunesNotesThatAreGone() = runTest {
        store.upsertSummaries(listOf(summary("DELETED-ELSEWHERE")))
        val repo = newRepo()
        repo.signIn()
        assertEquals(setOf("NOTE-A", "NOTE-B"), repo.notes.value.map { it.recordName }.toSet())
        assertEquals(setOf("NOTE-A", "NOTE-B"), store.loadSummaries().map { it.recordName }.toSet())
        assertTrue(store.getMeta(StoreMeta.LAST_FULL_SYNC_MS) != null)

        api.records.remove("NOTE-B")
        api.put(noteRecord("NOTE-C", "New\nnote"))
        repo.refresh()
        assertEquals(setOf("NOTE-A", "NOTE-C"), repo.notes.value.map { it.recordName }.toSet())
        assertEquals(setOf("NOTE-A", "NOTE-C"), store.loadSummaries().map { it.recordName }.toSet())
    }

    @Test
    fun pagesPastAThousandNotesWithTheFirstPageShownEarly() = runTest {
        api.recentsPageSize = 13
        repeat(1_300) { api.extraSummaries += summary("BULK-$it", mod = 10_000L - it) }
        val gate = CompletableDeferred<Unit>()
        api.laterPagesGate = gate
        val repo = newRepo()
        repo.signIn()
        // signIn returned with the first page; the rest is still coming.
        assertEquals(13, repo.notes.value.size)
        assertTrue(repo.syncing.value)
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(1_302, repo.notes.value.size)
        assertEquals(1_302, store.loadSummaries().size)
        assertFalse(repo.syncing.value)
        assertTrue(api.calls.count { it == "fetchRecentsPage" } > 100)
    }

    @Test
    fun anInterruptedFullSyncDoesNotPrune() = runTest {
        api.recentsPageSize = 1
        store.upsertSummaries(listOf(summary("ONLY-IN-CACHE")))
        val repo = newRepo()
        val gate = CompletableDeferred<Unit>()
        api.laterPagesGate = gate
        repo.signIn()
        api.failNext["fetchRecentsPage"] = NotesError.Offline()
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertTrue(repo.notes.value.any { it.recordName == "ONLY-IN-CACHE" })
    }

    @Test
    fun withATokenTheSyncIsIncremental() = runTest {
        store.setMeta(StoreMeta.SYNC_TOKEN, "token-1")
        store.setMeta(StoreMeta.LAST_FULL_SYNC_MS, now.toString())
        store.upsertSummaries(listOf(summary("NOTE-A"), summary("NOTE-B"), summary("NOTE-OLD")))
        store.putBody(noteRecord("NOTE-OLD", "old"), "old", now)
        api.zoneChanges = { token ->
            assertEquals("token-1", token)
            changes(
                "token-2",
                notes = listOf(summary("NOTE-A", title = "Renamed", mod = 99)),
                removed = listOf("NOTE-OLD"),
                folders = listOf(FolderSummary("FOLDER-EMPTY", "Empty", isTrash = false, isDefault = false)),
            )
        }
        val repo = newRepo()
        repo.signIn()
        assertEquals(0, api.calls.count { it == "fetchRecents" })
        assertEquals("Renamed", repo.notes.value.first { it.recordName == "NOTE-A" }.title)
        assertFalse(repo.notes.value.any { it.recordName == "NOTE-OLD" })
        assertNull(store.getBody("NOTE-OLD"))
        assertEquals("token-2", store.getMeta(StoreMeta.SYNC_TOKEN))
        // Empty folders from sync responses are kept.
        assertTrue(repo.folders.value.any { it.recordName == "FOLDER-EMPTY" })
    }

    @Test
    fun incrementalSyncFollowsMoreComing() = runTest {
        store.setMeta(StoreMeta.SYNC_TOKEN, "t1")
        store.setMeta(StoreMeta.LAST_FULL_SYNC_MS, now.toString())
        api.zoneChanges = { token ->
            when (token) {
                "t1" -> changes("t2", notes = listOf(summary("X1")), more = true)
                else -> changes("t3", notes = listOf(summary("X2")))
            }
        }
        val repo = newRepo()
        repo.signIn()
        assertEquals(listOf("t1", "t2"), api.zoneTokens)
        assertTrue(repo.notes.value.map { it.recordName }.containsAll(listOf("X1", "X2")))
        assertEquals("t3", store.getMeta(StoreMeta.SYNC_TOKEN))
    }

    @Test
    fun aRejectedTokenFallsBackToAFullRefetch() = runTest {
        store.setMeta(StoreMeta.SYNC_TOKEN, "expired")
        store.setMeta(StoreMeta.LAST_FULL_SYNC_MS, now.toString())
        var calls = 0
        api.zoneChanges = { token ->
            calls++
            if (token == "expired") throw NotesError.Server(400, "BAD_REQUEST")
            changes("fresh-token")
        }
        val repo = newRepo()
        repo.signIn()
        assertEquals(1, api.calls.count { it == "fetchRecents" })
        assertEquals(setOf("NOTE-A", "NOTE-B"), repo.notes.value.map { it.recordName }.toSet())
        // ...and a new token was primed after the full refetch.
        assertEquals("fresh-token", store.getMeta(StoreMeta.SYNC_TOKEN))
        assertEquals(listOf("expired", null), api.zoneTokens)
    }

    @Test
    fun noChangesEndpointMeansFullRefetchesAndNoToken() = runTest {
        val repo = newRepo()
        repo.signIn()
        repo.refresh()
        assertEquals(2, api.calls.count { it == "fetchRecents" })
        assertNull(store.getMeta(StoreMeta.SYNC_TOKEN))
    }

    @Test
    fun aChangesBatchThatWouldWipeTheCacheIsDistrusted() = runTest {
        store.setMeta(StoreMeta.SYNC_TOKEN, "t1")
        store.setMeta(StoreMeta.LAST_FULL_SYNC_MS, now.toString())
        val many = (1..20).map { summary("N$it") }
        store.upsertSummaries(many)
        api.zoneChanges = { token ->
            if (token == "t1") changes("t2", removed = many.map { it.recordName }) else throw UnsupportedOperationException()
        }
        val repo = newRepo()
        repo.signIn()
        // Fell back to the full refetch, which is the truth.
        assertEquals(1, api.calls.count { it == "fetchRecents" })
        assertEquals(setOf("NOTE-A", "NOTE-B"), repo.notes.value.map { it.recordName }.toSet())
        assertNull(store.getMeta(StoreMeta.SYNC_TOKEN))
    }

    @Test
    fun offlineDuringIncrementalSyncKeepsTheToken() = runTest {
        store.setMeta(StoreMeta.SYNC_TOKEN, "t1")
        store.setMeta(StoreMeta.LAST_FULL_SYNC_MS, now.toString())
        api.zoneChanges = { throw NotesError.Offline() }
        val repo = newRepo()
        try {
            repo.signIn()
        } catch (e: NotesError.Offline) {
            // expected
        }
        assertEquals("t1", store.getMeta(StoreMeta.SYNC_TOKEN))
        assertEquals(0, api.calls.count { it == "fetchRecents" })
    }

    @Test
    fun aStaleFullSyncIsRedoneEvenWithAToken() = runTest {
        store.setMeta(StoreMeta.SYNC_TOKEN, "t1")
        store.setMeta(StoreMeta.LAST_FULL_SYNC_MS, (now - 2L * 24 * 3600 * 1000).toString())
        api.zoneChanges = { changes("t2") }
        val repo = newRepo()
        repo.signIn()
        assertEquals(1, api.calls.count { it == "fetchRecents" })
    }

    @Test
    fun openedNotesCanBeReadOffline() = runTest {
        val repo = newRepo()
        repo.signIn()
        val online = repo.openNote("NOTE-A")
        assertFalse(online.fromCache)

        net.online.value = false
        val calls = api.calls.size
        val offline = repo.openNote("NOTE-A")
        assertTrue(offline.fromCache)
        assertEquals(online.record, offline.record)
        assertEquals(calls, api.calls.size)

        // A network failure while "online" also falls back to the copy.
        net.online.value = true
        api.failNext["lookupNote"] = NotesError.Offline()
        assertTrue(repo.openNote("NOTE-A").fromCache)

        // Never opened → nothing to show offline.
        net.online.value = false
        try {
            repo.openNote("NOTE-B")
            throw AssertionError("expected Offline")
        } catch (e: NotesError.Offline) {
            // expected
        }
    }

    @Test
    fun searchCoversCachedBodies() = runTest {
        val repo = newRepo()
        repo.signIn()
        repo.openNote("NOTE-A")
        assertEquals(setOf("NOTE-A"), repo.searchCachedBodies("MILK"))
        assertTrue(repo.searchCachedBodies("nothing like it").isEmpty())
    }

    @Test
    fun signOutClearsTheCacheAndDrafts() = runTest {
        val repo = newRepo()
        repo.signIn()
        repo.openNote("NOTE-A")
        repo.persistDraft(
            PendingDraft("NOTE-A", noteRecord("NOTE-A", "x"), null, "x y", "", "{}", now),
        ).join()
        assertEquals(1, repo.pendingDrafts.value.size)

        repo.signOut()
        testScheduler.advanceUntilIdle()
        assertTrue(repo.notes.value.isEmpty())
        assertTrue(repo.folders.value.isEmpty())
        assertTrue(repo.pendingDrafts.value.isEmpty())
        assertTrue(store.loadSummaries().isEmpty())
        assertTrue(store.allDrafts().isEmpty())
        assertEquals(0, store.bodyCount)
        assertNull(store.getMeta(StoreMeta.ACCOUNT))
        assertEquals(1, scheduler.cancelledAll)
        // The next launch has nothing cached to show.
        assertFalse(newRepo().loadCache())
    }

    @Test
    fun aDifferentAccountNeverSeesThePreviousCache() = runTest {
        val repo = newRepo()
        repo.signIn()
        repo.persistDraft(PendingDraft("NOTE-A", noteRecord("NOTE-A", "x"), null, "x y", "", "{}", now)).join()

        api.accountId = "dsid-2"
        api.records.clear()
        api.put(noteRecord("OTHER-1", "Other account"))
        repo.signIn()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("OTHER-1"), repo.notes.value.map { it.recordName })
        assertTrue(store.allDrafts().isEmpty())
    }

    @Test
    fun savesAreCachedForTheNextLaunch() = runTest {
        val repo = newRepo()
        repo.signIn()
        val rec = api.records.getValue("NOTE-A")
        repo.saveNote(rec, null, "Shopping list\nmilk", isPureAppend = false, newSpans = null)
        assertEquals("Shopping list", store.loadSummaries().first { it.recordName == "NOTE-A" }.title)
        // Next launch, offline: the saved version is what's cached.
        net.online.value = false
        val next = newRepo()
        assertTrue(next.loadCache())
        assertEquals("Shopping list", next.notes.value.first { it.recordName == "NOTE-A" }.title)
    }
}
