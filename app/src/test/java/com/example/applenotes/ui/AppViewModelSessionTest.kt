package com.example.applenotes.ui

import com.example.applenotes.R
import com.example.applenotes.ui.common.UiText
import com.example.applenotes.ui.common.uiText
import androidx.lifecycle.SavedStateHandle
import com.example.applenotes.client.NotesError
import com.example.applenotes.data.NotesRepository
import com.example.applenotes.testing.FakeAlphaGate
import com.example.applenotes.testing.FakeAuthGateway
import com.example.applenotes.testing.FakeConnectivity
import com.example.applenotes.testing.FakeNotesApi
import com.example.applenotes.testing.MainDispatcherRule
import com.example.applenotes.testing.REPLICA_UUID
import com.example.applenotes.testing.noteRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Issue #11: expired sessions, sign-in round trips and offline behaviour of the app shell. */
class AppViewModelSessionTest {
    @get:Rule
    val main = MainDispatcherRule()

    private lateinit var api: FakeNotesApi
    private lateinit var auth: FakeAuthGateway
    private lateinit var net: FakeConnectivity
    private lateinit var repo: NotesRepository
    private val savedState = SavedStateHandle()

    @Before
    fun setUp() {
        api = FakeNotesApi()
        api.put(noteRecord("NOTE-A", "Shopping\nmilk"))
        auth = FakeAuthGateway(api)
        net = FakeConnectivity()
        repo = NotesRepository(
            auth = auth,
            replicaUuidLoader = { REPLICA_UUID },
            externalScope = CoroutineScope(SupervisorJob() + main.dispatcher),
            connectivity = net,
            backoff = {},
        )
    }

    private fun newVm() = AppViewModel(repo, FakeAlphaGate(), savedState)

    @Test
    fun expiredSavedCookiesGoToSignInAndWaitForAFreshToken() {
        auth.failOpen = NotesError.AuthExpired("HTTP 421")
        auth.authToken = "stale-token"
        val vm = newVm()
        assertEquals(AppScreen.Login, vm.screen.value)
        assertEquals("stale-token", vm.loginStaleToken.value)

        vm.onCookiesArrived()
        assertEquals(AppScreen.NotesList, vm.screen.value)
        assertNull(vm.loginStaleToken.value)
    }

    @Test
    fun authFailureRightAfterSignInIsAnErrorNotALoop() {
        auth.savedLogin = false
        val vm = newVm()
        auth.failOpen = NotesError.AuthExpired("HTTP 421")
        vm.onCookiesArrived()
        val s = vm.screen.value as AppScreen.Error
        assertEquals(UiText.Res(R.string.error_session_expired), s.msg)
        assertTrue(s.details!!.contains("421"))
    }

    @Test
    fun signInAgainFromTheEditorReturnsToTheOpenNote() {
        val vm = newVm()
        vm.openNote(vm.listState.value.notes.single())
        val detail = vm.screen.value as AppScreen.Detail

        vm.signInAgain()
        assertEquals(AppScreen.Login, vm.screen.value)
        assertEquals("token-1", vm.loginStaleToken.value)

        vm.onCookiesArrived()
        // Same open id: the detail ViewModel's open() is a no-op, so the
        // editor and its unsaved draft are exactly as they were.
        assertEquals(AppScreen.Detail(detail.openId, record = null), vm.screen.value)
    }

    @Test
    fun listRefreshWithAnUnrecoverableSessionGoesToSignIn() {
        val vm = newVm()
        api.failNext["fetchRecents"] = NotesError.AuthExpired()
        auth.failOpen = NotesError.AuthExpired()
        vm.refresh()
        assertEquals(AppScreen.Login, vm.screen.value)
    }

    @Test
    fun refreshWhileOfflineDoesNotTouchTheNetwork() {
        val vm = newVm()
        net.online.value = false
        val calls = api.calls.size
        vm.refresh()
        assertEquals(calls, api.calls.size)
        assertEquals(AppScreen.NotesList, vm.screen.value)
        assertEquals(UiText.Res(R.string.error_offline), vm.listMessage.value!!.text)
        assertEquals(false, vm.online.value)
    }

    @Test
    fun listFailuresCarryDetails() {
        val vm = newVm()
        api.failNext["fetchRecents"] = NotesError.Server(502, null, "<html>Bad gateway</html>")
        vm.refresh()
        val m = vm.listMessage.value!!
        assertEquals(uiText(R.string.list_refresh_failed, uiText(R.string.error_server, 502)), m.text)
        assertTrue(m.details!!.contains("Bad gateway"))
    }
}
