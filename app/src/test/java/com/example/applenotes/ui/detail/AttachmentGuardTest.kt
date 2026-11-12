package com.example.applenotes.ui.detail

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import com.example.applenotes.data.NotesRepository
import com.example.applenotes.testing.FakeAuthGateway
import com.example.applenotes.testing.FakeNotesApi
import com.example.applenotes.testing.MainDispatcherRule
import com.example.applenotes.testing.REPLICA_UUID
import com.example.applenotes.testing.noteBodyB64
import com.example.applenotes.testing.noteRecord
import com.example.applenotes.proto.NoteAppender
import com.example.applenotes.proto.NoteBodyEditor
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

    /**
     * A note with [text] whose U+FFFCs are real attachments (an attribute run
     * with attachment_info), unless [realAttachments] is false (a stray char).
     */
    private fun vmWith(text: String, realAttachments: Boolean = true): Pair<NoteDetailViewModel, FakeNotesApi> {
        val api = FakeNotesApi()
        val spans = text.map { c ->
            if (c == obj && realAttachments) {
                NoteBodyEditor.AttrSpan(
                    1, NoteBodyEditor.ParagraphStyle.BODY, isAttachment = true,
                    attachmentRecordName = "4629BD72-48F7-48C9-8080-1334094B5DCC", attachmentType = "public.png",
                )
            } else {
                NoteBodyEditor.AttrSpan(1, NoteBodyEditor.ParagraphStyle.BODY)
            }
        }
        val b64 = NoteAppender.setBodyBase64(noteBodyB64(text), REPLICA_UUID, text, 1, spans)
        api.put(noteRecord("NOTE-A", text, textB64 = b64))
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

    @Test
    fun theTestNoteReallyHasAnAttachment() = runTest(main.dispatcher) {
        val (vm, _) = vmWith("Title\nbefore\n$obj\nafter")
        assertEquals(1, vm.spansDraft.count { it.isAttachment })
    }

    /** A U+FFFC without an attachment run is just a character: it can be deleted. */
    @Test
    fun aStrayObjectCharWithoutAnAttachmentCanBeDeleted() = runTest(main.dispatcher) {
        val (vm, _) = vmWith("Title\nbefore\n$obj\nafter", realAttachments = false)
        vm.onBodyChange(TextFieldValue("before\n\nafter", TextRange(7)))
        assertEquals("before\n\nafter", vm.bodyDraft.text)
        assertNull(vm.status.value.message)
    }

    /** Pasted text can't bring in an attachment: its U+FFFCs are dropped. */
    @Test
    fun pastedObjectCharsAreDropped() = runTest(main.dispatcher) {
        val (vm, _) = vmWith("Title\nbefore\n$obj\nafter")
        // Paste "x￼y" at the end of "before".
        vm.onBodyChange(TextFieldValue("beforex${obj}y\n$obj\nafter", TextRange(9)))
        assertEquals("beforexy\n$obj\nafter", vm.bodyDraft.text)
        assertEquals(TextRange(8), vm.bodyDraft.selection)
        assertEquals(1, vm.spansDraft.count { it.isAttachment })
        assertEquals(vm.fullBodyForSave.length, vm.spansDraft.sumOf { it.length })
        // Into the title as well.
        vm.onTitleChange("Ti${obj}tle")
        assertEquals("Title", vm.titleDraft)
    }
}
