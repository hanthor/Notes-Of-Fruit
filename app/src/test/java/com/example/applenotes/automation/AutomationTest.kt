package com.example.applenotes.automation

import com.example.applenotes.data.NotesRepository
import com.example.applenotes.proto.NoteBodyEditor
import com.example.applenotes.proto.NoteBodyEditor.AttrSpan
import com.example.applenotes.proto.NoteBodyEditor.ParagraphStyle
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

class AutomationTest {
    @get:Rule
    val main = MainDispatcherRule()

    private lateinit var api: FakeNotesApi
    private lateinit var auth: FakeAuthGateway
    private lateinit var repo: NotesRepository
    private var enabled = true
    private val gate = object : AutomationGate {
        override fun isEnabled() = enabled
    }

    @Before
    fun setUp() {
        api = FakeNotesApi()
        api.put(noteRecord("NOTE-A", "Shopping\nmilk"))
        api.put(noteRecord("NOTE-B", "In folder F\nbody", folder = "FOLDER-F"))
        api.put(noteRecord("NOTE-T", "Trashed\nbody", folder = "TrashFolder-CloudKit"))
        api.put(noteRecord("NOTE-D1", "Dup\none"))
        api.put(noteRecord("NOTE-D2", "Dup\ntwo"))
        auth = FakeAuthGateway(api)
        repo = NotesRepository(auth, { REPLICA_UUID }, CoroutineScope(SupervisorJob() + main.dispatcher))
    }

    private fun processor() = AutomationProcessor(gate, repo)

    // ----- Parsing / validation -----

    private fun parse(action: String, e: AutomationExtras) = parseAutomationRequest(action, e)

    @Test
    fun createNeedsTitleOrText() {
        assertTrue(parse(AutomationApi.ACTION_CREATE_NOTE, AutomationExtras()) is Parsed.Invalid)
        assertTrue(parse(AutomationApi.ACTION_CREATE_NOTE, AutomationExtras(text = "  ")) is Parsed.Invalid)
        assertTrue(parse(AutomationApi.ACTION_CREATE_NOTE, AutomationExtras(title = "a\nb")) is Parsed.Invalid)
        assertEquals(
            Parsed.Ok(AutomationRequest.Create("T", "body", "Work")),
            parse(AutomationApi.ACTION_CREATE_NOTE, AutomationExtras(title = " T ", text = "body\n", folder = " Work ")),
        )
        assertEquals(
            Parsed.Ok(AutomationRequest.Create("", "only text", null)),
            parse(AutomationApi.ACTION_CREATE_NOTE, AutomationExtras(text = "only text", folder = "")),
        )
    }

    @Test
    fun appendAndOpenNeedExactlyOneTarget() {
        val append = AutomationApi.ACTION_APPEND_TO_NOTE
        assertTrue(parse(append, AutomationExtras(text = "x")) is Parsed.Invalid)
        assertTrue(parse(append, AutomationExtras(text = "x", title = "a", recordName = "B")) is Parsed.Invalid)
        assertTrue(parse(append, AutomationExtras(title = "a")) is Parsed.Invalid)
        assertTrue(parse(append, AutomationExtras(text = "x", recordName = "bad name/")) is Parsed.Invalid)
        assertEquals(
            Parsed.Ok(AutomationRequest.Append("NOTE-A", null, "x")),
            parse(append, AutomationExtras(text = "x", recordName = "NOTE-A")),
        )
        assertTrue(parse(AutomationApi.ACTION_OPEN_NOTE, AutomationExtras()) is Parsed.Invalid)
        assertEquals(
            Parsed.Ok(AutomationRequest.Open(null, "Shopping")),
            parse(AutomationApi.ACTION_OPEN_NOTE, AutomationExtras(title = "Shopping")),
        )
    }

    @Test
    fun limitsAndUnknownActions() {
        val big = "x".repeat(AutomationApi.MAX_TEXT_CHARS + 1)
        assertTrue(parse(AutomationApi.ACTION_CREATE_NOTE, AutomationExtras(text = big)) is Parsed.Invalid)
        assertTrue(parse("io.github.hanthor.notesoffruit.action.DELETE_NOTE", AutomationExtras(recordName = "A")) is Parsed.Invalid)
        assertTrue(parse("io.github.hanthor.notesoffruit.action.SET_BODY", AutomationExtras(recordName = "A", text = "x")) is Parsed.Invalid)
    }

    // ----- Gate -----

    @Test
    fun disabledByDefault() {
        assertFalse(AutomationSettings.DEFAULT_ENABLED)
    }

    @Test
    fun disabledGateRefusesEverythingWithoutTouchingICloud() = runTest {
        enabled = false
        for (action in listOf(AutomationApi.ACTION_CREATE_NOTE, AutomationApi.ACTION_APPEND_TO_NOTE, AutomationApi.ACTION_OPEN_NOTE)) {
            val r = processor().handle(action, AutomationExtras(title = "Shopping", text = "x"))
            assertFalse(r.success)
            assertEquals(AutomationProcessor.DISABLED, r.error)
        }
        assertEquals(0, auth.openSessionCalls)
        assertTrue(api.calls.isEmpty())
    }

    // ----- Execution through the repository's save path -----

    @Test
    fun createGoesThroughTheDraftCreatePath() = runTest {
        val r = processor().handle(AutomationApi.ACTION_CREATE_NOTE, AutomationExtras(title = "From Tasker", text = "line 1\nline 2"))
        assertTrue(r.error, r.success)
        assertEquals(1, api.creates.size)
        assertEquals("From Tasker", api.creates[0].title)
        assertEquals("line 1\nline 2", api.creates[0].body)
        assertEquals(r.recordName, repo.notes.value.first().recordName)
        assertTrue(api.modifies.isEmpty())
    }

    @Test
    fun createInANamedFolder() = runTest {
        val ok = processor().handle(AutomationApi.ACTION_CREATE_NOTE, AutomationExtras(text = "x", folder = "folder folder-f"))
        assertTrue(ok.error, ok.success)
        assertTrue(api.creates[0].folderRef.toString().contains("FOLDER-F"))

        val missing = processor().handle(AutomationApi.ACTION_CREATE_NOTE, AutomationExtras(text = "x", folder = "Nope"))
        assertFalse(missing.success)
        assertEquals(1, api.creates.size)
    }

    @Test
    fun appendByTitleUsesTheModifyPath() = runTest {
        val r = processor().handle(AutomationApi.ACTION_APPEND_TO_NOTE, AutomationExtras(title = "Shopping", text = "eggs"))
        assertTrue(r.error, r.success)
        assertEquals("NOTE-A", r.recordName)
        assertEquals(1, api.modifies.size)
        assertEquals("Shopping\nmilk\neggs", api.records.getValue("NOTE-A").visibleText())
        // Same pass-through the editor's save does.
        assertEquals("cmVwbGljYS12ZXJzaW9u", api.modifies[0].replicaVersion)
    }

    @Test
    fun appendRefusesAmbiguousMissingAndTrashedNotes() = runTest {
        val p = processor()
        assertFalse(p.handle(AutomationApi.ACTION_APPEND_TO_NOTE, AutomationExtras(title = "Dup", text = "x")).success)
        assertFalse(p.handle(AutomationApi.ACTION_APPEND_TO_NOTE, AutomationExtras(title = "Nope", text = "x")).success)
        assertFalse(p.handle(AutomationApi.ACTION_APPEND_TO_NOTE, AutomationExtras(recordName = "NOTE-T", text = "x")).success)
        assertFalse(p.handle(AutomationApi.ACTION_APPEND_TO_NOTE, AutomationExtras(recordName = "MISSING", text = "x")).success)
        assertTrue(api.modifies.isEmpty())
    }

    @Test
    fun notSignedInIsAnError() = runTest {
        auth.savedLogin = false
        val r = processor().handle(AutomationApi.ACTION_CREATE_NOTE, AutomationExtras(text = "x"))
        assertFalse(r.success)
        assertTrue(api.creates.isEmpty())
    }

    @Test
    fun appendedTextIsPlainBodyAfterTheLastParagraph() {
        val original = "T\nitem"
        val spans = listOf(AttrSpan(2, ParagraphStyle.TITLE), AttrSpan(4, ParagraphStyle.CHECKBOX_LIST, bold = true))
        val (body, newSpans) = appendPlainText(original, spans, "new")
        assertEquals("T\nitem\nnew", body)
        val chars = NoteBodyEditor.expandSpansToChars(body, newSpans)
        assertEquals(ParagraphStyle.CHECKBOX_LIST, chars[6].style) // the '\n' ends the checklist item
        assertFalse(chars[6].bold)
        assertEquals(ParagraphStyle.BODY, chars[7].style)
        assertEquals(body.length, newSpans.sumOf { it.length })
        assertEquals("x", appendPlainText("", emptyList(), "x").first)
        assertEquals("a\nx", appendPlainText("a\n", emptyList(), "x").first)
    }
}
