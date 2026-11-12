package com.example.applenotes.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import com.example.applenotes.R
import com.example.applenotes.data.NotesRepository
import com.example.applenotes.testing.FakeAlphaGate
import com.example.applenotes.testing.FakeAuthGateway
import com.example.applenotes.testing.FakeNotesApi
import com.example.applenotes.testing.MainDispatcherRule
import com.example.applenotes.testing.REPLICA_UUID
import com.example.applenotes.testing.noteRecord
import com.example.applenotes.testing.visibleText
import com.example.applenotes.ui.common.UiText
import com.example.applenotes.ui.detail.NoteDetailViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Issue #16: list + detail side by side, and switching notes from the list. */
class TwoPaneNavigationTest {
    @get:Rule
    val main = MainDispatcherRule()

    private lateinit var api: FakeNotesApi
    private lateinit var repo: NotesRepository
    private val savedState = SavedStateHandle()

    @Before
    fun setUp() {
        api = FakeNotesApi()
        api.put(noteRecord("NOTE-A", "Shopping\nmilk"))
        api.put(noteRecord("NOTE-B", "Other\nbody"))
        repo = NotesRepository(
            auth = FakeAuthGateway(api),
            replicaUuidLoader = { REPLICA_UUID },
            externalScope = CoroutineScope(SupervisorJob() + main.dispatcher),
        )
    }

    private fun newVm() = AppViewModel(repo, FakeAlphaGate(), savedState).also { it.setTwoPane(true) }
    private fun AppViewModel.note(name: String) = listState.value.notes.first { it.recordName == name }
    private fun AppViewModel.openRecord() = (screen.value as AppScreen.Detail).record!!.recordName

    @Test
    fun widthThresholdIsTheExpandedClass() {
        assertFalse(isTwoPaneWidth(411f))
        assertFalse(isTwoPaneWidth(839f))
        assertTrue(isTwoPaneWidth(840f))
        assertTrue(isTwoPaneWidth(1280f))
    }

    @Test
    fun onlyTheListAndNoteScreensSplit() {
        assertTrue(showsListAndDetail(AppScreen.NotesList))
        assertTrue(showsListAndDetail(AppScreen.Detail("x", record = null)))
        assertTrue(showsListAndDetail(AppScreen.Loading(UiText.Res(R.string.loading_note), inDetailPane = true)))
        assertFalse(showsListAndDetail(AppScreen.Loading(UiText.Res(R.string.loading_signing_in))))
        assertFalse(showsListAndDetail(AppScreen.Settings))
        assertFalse(showsListAndDetail(AppScreen.Login))
    }

    @Test
    fun selectingFromTheListWithNothingOpenOpensAtOnce() {
        val vm = newVm()
        vm.selectNote(vm.note("NOTE-A"))
        assertEquals("NOTE-A", vm.openRecord())
        assertNull(vm.detailLeaveRequest.value)
    }

    @Test
    fun switchingNotesLeavesTheOpenOneFirst() {
        val vm = newVm()
        vm.selectNote(vm.note("NOTE-A"))
        vm.selectNote(vm.note("NOTE-B"))
        // Still on A until its screen has run the Back path.
        assertEquals("NOTE-A", vm.openRecord())
        assertNotNull(vm.detailLeaveRequest.value)
        assertEquals(PendingSwitch.Open(vm.note("NOTE-B")), vm.pendingSwitch)
        vm.onDetailLeaveRequestHandled()
        assertNull(vm.detailLeaveRequest.value)

        // The Back path ends in showList (DetailRoute's leave()).
        vm.showList()
        assertEquals("NOTE-B", vm.openRecord())
        assertNull(vm.pendingSwitch)
    }

    @Test
    fun selectingTheOpenNoteAgainDoesNothing() {
        val vm = newVm()
        vm.selectNote(vm.note("NOTE-A"))
        val before = vm.screen.value
        vm.selectNote(vm.note("NOTE-A"))
        assertEquals(before, vm.screen.value)
        assertNull(vm.detailLeaveRequest.value)
        assertNull(vm.pendingSwitch)
    }

    @Test
    fun stayingOnTheNoteDropsTheSwitch() {
        val vm = newVm()
        vm.selectNote(vm.note("NOTE-A"))
        vm.selectNote(vm.note("NOTE-B"))
        // e.g. the save failed, or "Keep editing" in the unsaved-changes dialog.
        vm.cancelPendingSwitch()
        assertNull(vm.detailLeaveRequest.value)
        // A later Back just closes the note.
        vm.showList()
        assertEquals(AppScreen.NotesList, vm.screen.value)
    }

    @Test
    fun newNoteAndSettingsAlsoLeaveTheOpenNoteFirst() {
        val vm = newVm()
        vm.selectNote(vm.note("NOTE-A"))
        vm.requestCreateNote()
        assertEquals("NOTE-A", vm.openRecord())
        vm.showList()
        val draft = vm.screen.value as AppScreen.Detail
        assertNotNull("a new, unsaved draft", draft.pendingFolderRef)

        vm.requestSettings()
        assertTrue(vm.screen.value is AppScreen.Detail)
        vm.showList()
        assertEquals(AppScreen.Settings, vm.screen.value)
    }

    @Test
    fun leavingTwoPaneModeForgetsAHalfDoneSwitch() {
        val vm = newVm()
        vm.selectNote(vm.note("NOTE-A"))
        vm.selectNote(vm.note("NOTE-B"))
        vm.setTwoPane(false)
        assertNull(vm.pendingSwitch)
        vm.showList()
        assertEquals(AppScreen.NotesList, vm.screen.value)
    }

    @Test
    fun switchingAwayFromAModifiedNoteSavesItThroughTheBackPath() {
        val app = newVm()
        val detail = NoteDetailViewModel(repo, SavedStateHandle(), clock = { 42L })
        app.selectNote(app.note("NOTE-A"))
        val s = app.screen.value as AppScreen.Detail
        detail.open(s.openId, s.record, s.pendingFolderRef, s.prefill, s.offlineCopy)
        detail.onBodyChange(TextFieldValue("milk\neggs", TextRange(9)))

        app.selectNote(app.note("NOTE-B"))
        // What NoteDetailScreen's Back path does for a modified note.
        assertTrue(detail.isModified)
        detail.save(alsoExit = true)
        assertTrue(detail.status.value.exitRequested)
        assertEquals("Shopping\nmilk\neggs", api.records.getValue("NOTE-A").visibleText())
        // DetailRoute's leave().
        val exitMessage = detail.status.value.exitMessage
        detail.close()
        app.showList(exitMessage)
        assertEquals("NOTE-B", app.openRecord())
    }
}
