package com.example.applenotes.ui.detail

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import com.example.applenotes.data.NotesRepository
import com.example.applenotes.testing.FakeAuthGateway
import com.example.applenotes.testing.FakeNotesApi
import com.example.applenotes.testing.MainDispatcherRule
import com.example.applenotes.testing.REPLICA_UUID
import com.example.applenotes.testing.noteRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AttachmentGuardTest {
    private val obj = '￼'

    // ----- removesAttachment -----

    @Test
    fun deletingTheAttachmentCharIsDetected() {
        assertTrue(removesAttachment("a${obj}b", "ab"))
        // Backspace right after the attachment.
        assertTrue(removesAttachment("line\n$obj\n", "line\n\n"))
    }

    @Test
    fun deletingARangeContainingAnAttachmentIsDetected() {
        assertTrue(removesAttachment("one\n$obj\ntwo", "one"))
        assertTrue(removesAttachment("one\n$obj\ntwo", ""))
    }

    @Test
    fun replacingASelectionContainingAnAttachmentIsDetected() {
        assertTrue(removesAttachment("x${obj}y", "xZy"))
        // Even with another U+FFFC pasted in its place (it wouldn't be the same attachment).
        assertTrue(removesAttachment("ab${obj}cd", "aZ${obj}Zd"))
    }

    @Test
    fun editsElsewhereAreAllowed() {
        assertFalse(removesAttachment("a${obj}b", "a${obj}bc"))
        assertFalse(removesAttachment("abc${obj}def", "ac${obj}def"))
        assertFalse(removesAttachment("hello $obj world", "hello $obj word"))
        assertFalse(removesAttachment("hello $obj world", "hllo $obj world"))
        assertFalse(removesAttachment("plain", ""))
        assertFalse(removesAttachment("${obj}", "${obj}"))
        // Inserting next to an attachment.
        assertFalse(removesAttachment("$obj", "x$obj"))
        assertFalse(removesAttachment("$obj", "${obj}x"))
    }

    @Test
    fun adjacentDuplicateAttachmentsStillCount() {
        // "￼￼" → "￼": one of them goes, whichever the diff picks.
        assertTrue(removesAttachment("$obj$obj", "$obj"))
    }

    // ----- NoteDetailViewModel -----

    @get:Rule
    val main = MainDispatcherRule()

    private fun vmWith(text: String): Pair<NoteDetailViewModel, FakeNotesApi> {
        val api = FakeNotesApi()
        api.put(noteRecord("NOTE-A", text))
        val repo = NotesRepository(
            auth = FakeAuthGateway(api),
            replicaUuidLoader = { REPLICA_UUID },
            externalScope = CoroutineScope(SupervisorJob() + main.dispatcher),
            clock = { 1L },
        )
        kotlinx.coroutines.runBlocking { repo.signIn() }
        val vm = NoteDetailViewModel(repo, SavedStateHandle(), clock = { 2L })
        vm.open("open-1", api.records.getValue("NOTE-A"), pendingFolderRef = null)
        return vm to api
    }

    @Test
    fun editorRejectsDeletingAnAttachmentAndKeepsThePreviousValue() = runTest(main.dispatcher) {
        val (vm, _) = vmWith("Title\nbefore\n$obj\nafter")
        val before = vm.bodyDraft
        assertEquals("before\n$obj\nafter", before.text)
        val spansBefore = vm.spansDraft

        vm.onBodyChange(TextFieldValue("before\n\nafter", TextRange(7)))

        assertEquals(before, vm.bodyDraft)
        assertEquals(spansBefore, vm.spansDraft)
        assertFalse(vm.isModified)
        assertEquals(ATTACHMENT_DELETE_BLOCKED, vm.status.value.message?.text)
        assertNull(vm.status.value.message?.action)
    }

    @Test
    fun editorRejectsReplacingASelectionWithAnAttachment() = runTest(main.dispatcher) {
        val (vm, _) = vmWith("Title\nbefore\n$obj\nafter")
        val before = vm.bodyDraft
        vm.onBodyChange(TextFieldValue("bX", TextRange(2)))
        assertEquals(before, vm.bodyDraft)
        assertEquals(ATTACHMENT_DELETE_BLOCKED, vm.status.value.message?.text)
    }

    @Test
    fun editorAllowsTypingAndDeletingElsewhere() = runTest(main.dispatcher) {
        val (vm, _) = vmWith("Title\nbefore\n$obj\nafter")
        vm.onBodyChange(TextFieldValue("before\n$obj\nafter!", TextRange(14)))
        assertEquals("before\n$obj\nafter!", vm.bodyDraft.text)
        vm.onBodyChange(TextFieldValue("bfore\n$obj\nafter!", TextRange(1)))
        assertEquals("bfore\n$obj\nafter!", vm.bodyDraft.text)
        assertTrue(vm.isModified)
        assertNull(vm.status.value.message)
    }
}
