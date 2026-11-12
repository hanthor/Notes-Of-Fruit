package com.example.applenotes.automation

import com.example.applenotes.data.NotesRepository
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Issue #21: the per-app allow-list that replaced the AUTOMATE permission. */
class AutomationAllowListTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val tasker = VerifiedCaller(TASKER, listOf(TASKER_CERT), "Tasker")

    // ----- Allow-list rules -----

    @Test
    fun unknownCallerBecomesPendingAndApprovalTrustsItsCertificate() {
        val (d, s1) = AllowListRules.decide(AllowListState(), tasker, nowMs = 100)
        d as AccessDecision.NeedsApproval
        assertTrue(d.firstRequest)
        assertFalse(d.certificateChanged)
        assertEquals(listOf(PendingApp(TASKER, listOf(TASKER_CERT), "Tasker", 100)), s1.pending)
        assertTrue(s1.approved.isEmpty())

        // Asking again doesn't re-notify or reset the first-seen time.
        val (again, s2) = AllowListRules.decide(s1, tasker, nowMs = 200)
        assertFalse((again as AccessDecision.NeedsApproval).firstRequest)
        assertEquals(100, s2.pending.single().firstSeenMs)

        val approved = AllowListRules.approve(s2, TASKER, nowMs = 300)
        assertTrue(approved.pending.isEmpty())
        assertEquals(AllowedApp(TASKER, listOf(TASKER_CERT), "Tasker", 300), approved.approved.single())
        assertEquals(AccessDecision.Allowed, AllowListRules.decide(approved, tasker, 400).first)
    }

    @Test
    fun certificateMismatchIsNotTrusted() {
        val state = AllowListState(approved = listOf(AllowedApp(TASKER, listOf(TASKER_CERT), "Tasker", 1)))
        val impostor = VerifiedCaller(TASKER, listOf(OTHER_CERT), "Tasker")
        val (d, next) = AllowListRules.decide(state, impostor, 5)
        d as AccessDecision.NeedsApproval
        assertTrue(d.certificateChanged)
        assertTrue(d.firstRequest)
        // The old approval stays as it was; the new certificate waits for approval.
        assertEquals(state.approved, next.approved)
        assertEquals(listOf(OTHER_CERT), next.pending.single().certSha256)

        // A certificate subset/superset doesn't match either, and no certificate never does.
        val extra = VerifiedCaller(TASKER, listOf(TASKER_CERT, OTHER_CERT), "Tasker")
        assertTrue(AllowListRules.decide(state, extra, 5).first is AccessDecision.NeedsApproval)
        val none = VerifiedCaller(TASKER, emptyList(), "Tasker")
        assertTrue(AllowListRules.decide(state, none, 5).first is AccessDecision.NeedsApproval)
        // Case and order of the digests don't matter.
        val shuffled = AllowListState(approved = listOf(AllowedApp(TASKER, listOf(OTHER_CERT, TASKER_CERT), "T", 1)))
        val upper = VerifiedCaller(TASKER, listOf(TASKER_CERT.uppercase(), OTHER_CERT), "Tasker")
        assertEquals(AccessDecision.Allowed, AllowListRules.decide(shuffled, upper, 5).first)
    }

    @Test
    fun removeRevokesAndDismisses() {
        val state = AllowListState(
            approved = listOf(AllowedApp(TASKER, listOf(TASKER_CERT), "Tasker", 1)),
            pending = listOf(PendingApp("com.other", listOf(OTHER_CERT), "Other", 2)),
        )
        val revoked = AllowListRules.remove(state, TASKER)
        assertTrue(revoked.approved.isEmpty())
        assertTrue(AllowListRules.decide(revoked, tasker, 3).first is AccessDecision.NeedsApproval)
        assertTrue(AllowListRules.remove(state, "com.other").pending.isEmpty())
        // Approving something that never asked changes nothing.
        assertEquals(state, AllowListRules.approve(state, "com.never.asked", 4))
    }

    @Test
    fun pendingListIsCapped() {
        var state = AllowListState()
        repeat(AllowListRules.MAX_PENDING + 5) { i ->
            state = AllowListRules.decide(state, VerifiedCaller("app.n$i", listOf(OTHER_CERT), "n$i"), i.toLong()).second
        }
        assertEquals(AllowListRules.MAX_PENDING, state.pending.size)
        assertFalse(state.pending.any { it.packageName == "app.n0" })
    }

    // ----- Caller resolution per API level -----

    private fun activity(sdk: Int, launched: String? = null, calling: String? = null, referrer: String? = null, creator: String? = null) =
        CallerResolver.resolve(
            CallerSignals(
                CallerSignals.Entry.ACTIVITY, sdk,
                launchedFromPackage = launched, callingPackage = calling,
                systemReferrerPackage = referrer, resultPendingIntentCreator = creator,
            ),
        )

    private fun receiver(sdk: Int, sentFrom: String? = null, creator: String? = null) =
        CallerResolver.resolve(
            CallerSignals(CallerSignals.Entry.RECEIVER, sdk, sentFromPackage = sentFrom, resultPendingIntentCreator = creator),
        )

    @Test
    fun activityReferrerIsTrustedFromAndroid11() {
        assertEquals(CallerResolution.Identified(TASKER, CallerSource.REFERRER), activity(30, referrer = TASKER))
        assertEquals(CallerResolution.Identified(TASKER, CallerSource.REFERRER), activity(36, referrer = TASKER))
        // Android 8-10: the calling package behind the referrer isn't checked by the system.
        assertTrue(activity(29, referrer = TASKER) is CallerResolution.Unidentified)
        assertTrue(activity(26, referrer = TASKER) is CallerResolution.Unidentified)
    }

    @Test
    fun activityPrefersStrongerSources() {
        assertEquals(CallerResolution.Identified("a", CallerSource.LAUNCHED_FROM), activity(34, launched = "a", calling = "a", referrer = "a"))
        assertEquals(CallerResolution.Identified("b", CallerSource.CALLING_PACKAGE), activity(26, calling = "b"))
        assertEquals(CallerResolution.Identified("c", CallerSource.PENDING_INTENT_CREATOR), activity(26, creator = "c"))
        assertEquals(CallerResolution.Identified("c", CallerSource.CALLING_PACKAGE), activity(29, calling = "c", creator = "c"))
        assertTrue(activity(26) is CallerResolution.Unidentified)
        assertTrue(activity(36) is CallerResolution.Unidentified)
    }

    @Test
    fun aPendingIntentFromAnotherAppDoesNotLendItsIdentity() {
        val r = activity(33, referrer = "evil.app", creator = TASKER)
        assertEquals(CallerResolution.Unidentified(CallerResolver.CONFLICT), r)
        assertEquals(CallerResolution.Unidentified(CallerResolver.CONFLICT), receiver(34, sentFrom = "evil.app", creator = TASKER))
    }

    @Test
    fun receiverNeedsAPendingIntentOrSharedIdentity() {
        assertEquals(CallerResolution.Identified(TASKER, CallerSource.PENDING_INTENT_CREATOR), receiver(26, creator = TASKER))
        assertEquals(CallerResolution.Identified(TASKER, CallerSource.SENT_FROM), receiver(34, sentFrom = TASKER))
        assertEquals(CallerResolution.Unidentified(CallerResolver.UNIDENTIFIED_RECEIVER), receiver(36))
        assertEquals(CallerResolution.Unidentified(CallerResolver.UNIDENTIFIED_RECEIVER), receiver(33, sentFrom = TASKER))
    }

    // ----- End to end through the processor -----

    private lateinit var api: FakeNotesApi
    private lateinit var auth: FakeAuthGateway
    private lateinit var repo: NotesRepository
    private val notified = mutableListOf<PendingApp>()

    @Before
    fun setUp() {
        api = FakeNotesApi()
        api.put(noteRecord("NOTE-A", "Shopping\nmilk"))
        auth = FakeAuthGateway(api)
        repo = NotesRepository(auth, { REPLICA_UUID }, CoroutineScope(SupervisorJob() + main.dispatcher))
    }

    private fun processor(store: AllowListStore, packages: PackageLookup = FakePackages(tasker)) = AutomationProcessor(
        object : AutomationGate {
            override fun isEnabled() = true
        },
        repo,
        AutomationAccess(store, packages, { notified += it }, clock = { 1_000 }),
    )

    private val fromTasker = CallerResolution.Identified(TASKER, CallerSource.REFERRER)

    @Test
    fun unknownCallerIsRecordedAndNothingIsWritten() = runTest {
        val store = InMemoryAllowListStore()
        val p = processor(store)
        val r = p.handle(AutomationApi.ACTION_APPEND_TO_NOTE, AutomationExtras(title = "Shopping", text = "secret"), fromTasker)
        assertFalse(r.success)
        assertEquals(AutomationAccess.notApproved("Tasker"), r.error)
        assertEquals(listOf(PendingApp(TASKER, listOf(TASKER_CERT), "Tasker", 1_000)), store.value.pending)
        assertEquals(1, notified.size)
        assertTrue(api.calls.isEmpty())
        assertEquals(0, auth.openSessionCalls)
        // The request isn't stored anywhere.
        assertFalse(store.value.toString().contains("secret"))

        // Running it again: still refused, no second notification.
        assertFalse(p.handle(AutomationApi.ACTION_CREATE_NOTE, AutomationExtras(text = "x"), fromTasker).success)
        assertEquals(1, notified.size)
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun approvedCallerWritesThroughTheRepository() = runTest {
        val store = InMemoryAllowListStore()
        val p = processor(store)
        p.handle(AutomationApi.ACTION_APPEND_TO_NOTE, AutomationExtras(title = "Shopping", text = "eggs"), fromTasker)
        store.update { AllowListRules.approve(it, TASKER, 2_000) }
        val writesBefore = store.writes

        val r = p.handle(AutomationApi.ACTION_APPEND_TO_NOTE, AutomationExtras(title = "Shopping", text = "eggs"), fromTasker)
        assertTrue(r.error, r.success)
        assertEquals(1, api.modifies.size)
        assertEquals("Shopping\nmilk\neggs", api.records.getValue("NOTE-A").visibleText())
        // An approved call only reads the store.
        assertEquals(writesBefore, store.writes)
    }

    @Test
    fun reinstalledUnderTheSameNameWithAnotherKeyIsRefused() = runTest {
        val store = InMemoryAllowListStore(AllowListState(approved = listOf(AllowedApp(TASKER, listOf(TASKER_CERT), "Tasker", 1))))
        val packages = FakePackages(VerifiedCaller(TASKER, listOf(OTHER_CERT), "Tasker"))
        val r = processor(store, packages).handle(AutomationApi.ACTION_CREATE_NOTE, AutomationExtras(text = "x"), fromTasker)
        assertFalse(r.success)
        assertEquals(AutomationAccess.certChanged("Tasker"), r.error)
        assertTrue(api.calls.isEmpty())
        assertEquals(listOf(OTHER_CERT), store.value.pending.single().certSha256)
    }

    @Test
    fun unidentifiedOrInvisibleCallersAreRefusedWithoutRecording() = runTest {
        val store = InMemoryAllowListStore()
        val p = processor(store, FakePackages())
        val unidentified = p.handle(
            AutomationApi.ACTION_CREATE_NOTE,
            AutomationExtras(text = "x"),
            CallerResolution.Unidentified(CallerResolver.UNIDENTIFIED_RECEIVER),
        )
        assertEquals(CallerResolver.UNIDENTIFIED_RECEIVER, unidentified.error)
        val invisible = p.handle(AutomationApi.ACTION_CREATE_NOTE, AutomationExtras(text = "x"), fromTasker)
        assertEquals(AutomationAccess.notVisible(TASKER), invisible.error)
        assertEquals(AllowListState(), store.value)
        assertTrue(notified.isEmpty())
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun disabledToggleRecordsNothing() = runTest {
        val store = InMemoryAllowListStore()
        val p = AutomationProcessor(
            object : AutomationGate {
                override fun isEnabled() = false
            },
            repo,
            AutomationAccess(store, FakePackages(tasker), { notified += it }),
        )
        assertEquals(AutomationProcessor.DISABLED, p.handle(AutomationApi.ACTION_CREATE_NOTE, AutomationExtras(text = "x"), fromTasker).error)
        assertEquals(0, store.writes)
        assertTrue(notified.isEmpty())
    }

    @Test
    fun unknownActionsDontCreatePendingRequests() = runTest {
        val store = InMemoryAllowListStore()
        val r = processor(store).handle("io.github.hanthor.notesoffruit.action.DELETE_NOTE", AutomationExtras(recordName = "NOTE-A"), fromTasker)
        assertFalse(r.success)
        assertEquals(0, store.writes)
    }
}
