package com.example.applenotes.integrations

import android.content.Intent
import com.example.applenotes.ui.detail.DraftPrefill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EntryIntentParserTest {
    private val token = "secret-token"

    private fun parse(e: EntryIntent) = parseEntryIntent(e, token)

    private fun draftText(r: IncomingRequest?) = (r as IncomingRequest.NewDraft).prefill.text

    @Test
    fun sendTextBecomesADraftWithSubjectAsTitle() {
        assertEquals(
            "Subject\nhello world",
            draftText(parse(EntryIntent(Intent.ACTION_SEND, "text/plain", text = "hello world", subject = "Subject"))),
        )
        assertEquals("just text", draftText(parse(EntryIntent(Intent.ACTION_SEND, "text/plain", text = "just text\n"))))
        // A subject the text already starts with isn't duplicated.
        assertEquals(
            "Title\nmore",
            draftText(parse(EntryIntent(Intent.ACTION_SEND, "text/plain", text = "Title\nmore", subject = "Title"))),
        )
        assertEquals("Only subject", draftText(parse(EntryIntent(Intent.ACTION_SEND, "text/plain", subject = "Only subject"))))
    }

    @Test
    fun sendIgnoresEmptyAndNonText() {
        assertNull(parse(EntryIntent(Intent.ACTION_SEND, "text/plain", text = "  ")))
        assertNull(parse(EntryIntent(Intent.ACTION_SEND, "image/png", text = "x")))
        assertNull(parse(EntryIntent(Intent.ACTION_SEND, null, text = "x")))
    }

    @Test
    fun sendMultipleJoinsTexts() {
        assertEquals(
            "a\n\nb",
            draftText(parse(EntryIntent(Intent.ACTION_SEND_MULTIPLE, "text/plain", textList = listOf("a", " ", "b")))),
        )
        assertNull(parse(EntryIntent(Intent.ACTION_SEND_MULTIPLE, "text/plain", textList = emptyList())))
    }

    @Test
    fun processTextBecomesADraft() {
        assertEquals("selected", draftText(parse(EntryIntent(Intent.ACTION_PROCESS_TEXT, "text/plain", processText = "selected"))))
        assertNull(parse(EntryIntent(Intent.ACTION_PROCESS_TEXT, "text/plain", processText = "")))
    }

    @Test
    fun newNoteActionsOpenAnEmptyDraft() {
        val empty = IncomingRequest.NewDraft(DraftPrefill(""))
        assertEquals(empty, parse(EntryIntent(EntryActions.NEW_NOTE)))
        assertEquals(empty, parse(EntryIntent(EntryActions.CREATE_NOTE)))
    }

    @Test
    fun openNoteShortcutNeedsTheToken() {
        val ok = EntryIntent(EntryActions.OPEN_NOTE_SHORTCUT, recordName = "ABC-123", token = token)
        assertEquals(IncomingRequest.OpenNote("ABC-123"), parse(ok))
        assertNull(parse(ok.copy(token = "wrong")))
        assertNull(parse(ok.copy(token = null)))
        assertNull(parse(ok.copy(recordName = "../etc")))
        assertNull(parseEntryIntent(ok.copy(token = ""), ""))
    }

    @Test
    fun unknownActionsAreIgnored() {
        assertNull(parse(EntryIntent(Intent.ACTION_VIEW)))
        assertNull(parse(EntryIntent(null)))
        // The automation actions are NOT accepted by the unprotected entry point.
        assertNull(parse(EntryIntent("io.github.hanthor.notesoffruit.action.CREATE_NOTE", text = "x")))
        assertNull(parse(EntryIntent("io.github.hanthor.notesoffruit.action.OPEN_NOTE", recordName = "A")))
    }

    @Test
    fun requestsSurviveSerialisation() {
        val r = IncomingRequest.NewDraft(DraftPrefill.ofText("body", "title"))
        assertEquals(r, IncomingRequest.decodeOrNull(IncomingRequest.encode(r)))
        assertEquals(IncomingRequest.OpenNote("X"), IncomingRequest.decodeOrNull(IncomingRequest.encode(IncomingRequest.OpenNote("X"))))
        assertNull(IncomingRequest.decodeOrNull("{not json"))
    }
}
