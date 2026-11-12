package com.example.applenotes.proto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Formatting / attachment fidelity across writes (issue #14, proto part).
 * Fixtures are SYNTHETIC (NoteCreator output with hand-assembled attribute
 * runs following the README's field table) unless they load
 * fmt-baseline-1.proto (real, Mac-created).
 */
class AttributeRunFidelityTest {

    private val a = testUuid(11)
    private val b = testUuid(12)
    private val fixture = loadFixture("fmt-baseline-1.proto")

    private fun splice(bytes: ByteArray, who: ByteArray, body: String, spans: List<NoteBodyEditor.AttrSpan>? = null): ByteArray {
        val out = NoteAppender.setBodySpliceBytes(bytes, who, body, 0, spans)
        // A format-only change rewrites attribute runs only: no substring or
        // VectorTimestamp change, hence no slot promotion to check.
        val textChanged = TopotextInvariants.normalise(TopotextInvariants.parse(bytes).rawVisible()) != TopotextInvariants.normalise(body)
        TopotextInvariants.checkInvariants(out, ourUuid = if (textChanged) who else null)
        if (!textChanged) {
            assertArrayEquals(
                "format-only change must not touch substrings/clocks",
                ProtobufWire.encode(TopotextInvariants.parse(bytes).stringFields.filter { it.fieldNumber != 5 }),
                ProtobufWire.encode(TopotextInvariants.parse(out).stringFields.filter { it.fieldNumber != 5 }),
            )
        }
        return out
    }

    /** Per-char run bytes of the unchanged prefix/suffix must be identical before/after. */
    private fun assertRunsKeptOutside(before: ByteArray, after: ByteArray, prefix: Int, suffix: Int) {
        val rb = Fx.runBytesPerChar(before)
        val ra = Fx.runBytesPerChar(after)
        for (i in 0 until prefix) assertEquals("prefix char $i run changed", rb[i], ra[i])
        for (k in 1..suffix) assertEquals("suffix char -$k run changed", rb[rb.size - k], ra[ra.size - k])
    }

    // ---------------------------------------------------------------- plain-text edits

    @Test
    fun plainTextEditKeepsEveryRunOfTheMacFixture() {
        // The splice doc comment used to say runs "collapse to ONE run". They
        // don't: with newSpans == null every surviving char keeps its run.
        val raw = TopotextInvariants.parse(fixture).rawVisible()
        val shown = NoteBodyEditor.readVisibleText(fixture)!!
        val runsBefore = TopotextInvariants.parse(fixture).runs.size

        // Replace inside a numbered-list paragraph.
        val at = shown.indexOf("numbered item 1") + "numbered item ".length
        val edited = shown.substring(0, at) + "one" + shown.substring(at + 1)
        val out = splice(fixture, a, edited)
        assertEquals(edited, NoteBodyEditor.readVisibleText(out))
        assertRunsKeptOutside(fixture, out, prefix = at, suffix = raw.length - at - 1)
        // Typed text took the run of the paragraph it was typed into, so the run
        // count is unchanged (no default-body fragment inside the list item).
        val d = TopotextInvariants.parse(out)
        assertEquals(runsBefore, d.runs.size)
        val per = Fx.perChar(out)
        for (i in at until at + 3) assertEquals(NoteBodyEditor.ParagraphStyle.NUMBERED_LIST, per[i].style)
    }

    @Test
    fun plainTextEditsAtSeveralPlacesInTheFixture() {
        val shown = NoteBodyEditor.readVisibleText(fixture)!!
        for (word in listOf("Heading", "bold", "monospace", "dot item 2", "check 2 - checked", "https://google.com")) {
            val at = shown.indexOf(word)
            assertTrue(word, at >= 0)
            val edited = shown.substring(0, at) + word.uppercase() + "!" + shown.substring(at + word.length)
            val out = splice(fixture, a, edited)
            assertEquals(edited, NoteBodyEditor.readVisibleText(out))
            assertRunsKeptOutside(fixture, out, prefix = at, suffix = shown.length - at - word.length)
            // Inserted chars carry the formatting they were typed into.
            val before = NoteBodyEditor.expandSpansToChars(shown, Fx.spans(fixture))[at]
            val after = Fx.perChar(out)[at]
            assertEquals(word, before.copy(length = 1), after.copy(length = 1))
        }
    }

    @Test
    fun insertedTextInheritsTheParagraphItIsTypedInto() {
        val heading = Fx.run(8, Fx.style(1)) // "Heading\n"
        val body = Fx.run(4, Fx.style(3)) // "body"
        val n = Fx.withRuns(Fx.create("Heading\nbody", a), listOf(heading, body))
        TopotextInvariants.checkInvariants(n, "Heading\nbody")

        // Mid-run insertion: still two runs, heading grew.
        val n1 = splice(n, b, "Head-ing\nbody")
        assertEquals(listOf(9, 4), TopotextInvariants.parse(n1).runs.map { it.length })
        // At the start of the document: joins the first paragraph.
        val n2 = splice(n1, a, "My Head-ing\nbody")
        assertEquals(listOf(12, 4), TopotextInvariants.parse(n2).runs.map { it.length })
        // At the start of a later paragraph: joins that paragraph.
        val n3 = splice(n2, b, "My Head-ing\nsome body")
        assertEquals(listOf(12, 9), TopotextInvariants.parse(n3).runs.map { it.length })
        // A new paragraph of its own gets a default body run, not a copy of
        // its neighbour's paragraph attributes.
        val n4 = splice(n3, a, "My Head-ing\nnew para\nsome body")
        val runs = TopotextInvariants.parse(n4).runs
        assertEquals(listOf(12, 9, 9), runs.map { it.length })
        assertEquals(listOf(1), runs[1].fields.map { it.fieldNumber })
    }

    // ---------------------------------------------------------------- unknown run fields

    private val font = Fx.msg(3, Fx.str(1, "Helvetica"), Fx.varint(2, 12))
    private val colour = Fx.msg(10, Fx.varint(1, 0x3f800000), Fx.varint(2, 7))
    private val stamp = Fx.varint(13, 1700000000)
    private val mystery = Fx.bytes(99, byteArrayOf(9, 8, 7))
    private val psExtra = Fx.bytes(9, ByteArray(16) { 0x5a }) // paragraph UUID inside f2
    private val psIndent = Fx.varint(4, 2) // indent-ish varint we don't model

    private fun richNote(): ByteArray {
        val run = Fx.run(11, Fx.style(3, psIndent, psExtra), font, colour, stamp, mystery)
        return Fx.withRuns(Fx.create("Hello world", a), listOf(run))
    }

    private fun assertUnknownsKept(out: ByteArray) {
        for (r in TopotextInvariants.parse(out).runs) {
            val f = r.fields
            for (want in listOf(font, colour, stamp, mystery)) {
                assertTrue("run lost field ${want.fieldNumber}", f.any { it.fieldNumber == want.fieldNumber && it.payload.contentEquals(want.payload) })
            }
            val ps = ProtobufWire.decode(f.single { it.fieldNumber == 2 }.payload)
            assertTrue("paragraph uuid lost", ps.any { it.fieldNumber == 9 && it.payload.contentEquals(psExtra.payload) })
            assertTrue("indent lost", ps.any { it.fieldNumber == 4 })
        }
    }

    @Test
    fun formatOnlyChangeKeepsUnknownRunFields() {
        val n = richNote()
        val text = "Hello world"
        val spans = NoteBodyEditor.toggleInline(text, Fx.spans(n), 6, 11, NoteBodyEditor.InlineKind.BOLD)
        val out = splice(n, b, text, spans)
        assertUnknownsKept(out)
        val per = Fx.perChar(out)
        assertFalse(per[0].bold)
        assertTrue(per[6].bold)
        assertEquals(2, TopotextInvariants.parse(out).runs.size)
        // Untouched first half is byte-identical to the original run (only length differs).
        assertEquals(Fx.runBytesPerChar(n)[0], Fx.runBytesPerChar(out)[0])
    }

    @Test
    fun allModelledAttributesCanBeSetAndClearedOverATemplate() {
        val n = richNote()
        val text = "Hello world"
        var spans = Fx.spans(n)
        spans = NoteBodyEditor.setParagraphStyle(text, spans, 0, 1, NoteBodyEditor.ParagraphStyle.HEADING)
        spans = NoteBodyEditor.toggleInline(text, spans, 0, 5, NoteBodyEditor.InlineKind.ITALIC)
        spans = NoteBodyEditor.toggleInline(text, spans, 0, 5, NoteBodyEditor.InlineKind.UNDERLINE)
        spans = NoteBodyEditor.toggleInline(text, spans, 6, 11, NoteBodyEditor.InlineKind.STRIKETHROUGH)
        spans = NoteBodyEditor.setLink(text, spans, 6, 11, "https://example.com")
        val out = splice(n, b, text, spans)
        assertUnknownsKept(out)
        val per = Fx.perChar(out)
        assertEquals(NoteBodyEditor.ParagraphStyle.HEADING, per[0].style)
        assertTrue(per[0].italic && per[0].underline && !per[0].strikethrough)
        assertTrue(per[6].strikethrough && per[6].linkUrl == "https://example.com")

        // And back to the original: run bytes return to the original encoding.
        val back = splice(out, a, text, Fx.spans(n))
        assertUnknownsKept(back)
        assertEquals(Fx.runBytesPerChar(n), Fx.runBytesPerChar(back))
    }

    @Test
    fun textEditWithSpansKeepsUnknownRunFields() {
        val n = richNote()
        // UI-style save: new text + spans for it (inserted chars copy their neighbour).
        val newText = "Hello big world"
        val spans = listOf(NoteBodyEditor.AttrSpan(length = newText.length, style = NoteBodyEditor.ParagraphStyle.BODY))
        val out = splice(n, b, newText, spans)
        assertEquals(newText, NoteBodyEditor.readVisibleText(out))
        assertUnknownsKept(out)
        assertEquals(1, TopotextInvariants.parse(out).runs.size)
    }

    @Test
    fun formatOnlySaveWithUnchangedSpansIsANoOp() {
        val shown = NoteBodyEditor.readVisibleText(fixture)!!
        assertSame(fixture, NoteAppender.setBodySpliceBytes(fixture, a, shown, 0, Fx.spans(fixture)))
    }

    @Test
    fun formatChangeOnFixtureLeavesOtherRunsByteIdentical() {
        val shown = NoteBodyEditor.readVisibleText(fixture)!!
        val at = shown.indexOf("Heading")
        val spans = NoteBodyEditor.toggleInline(shown, Fx.spans(fixture), at, at + 7, NoteBodyEditor.InlineKind.BOLD)
        val out = splice(fixture, a, shown, spans)
        val rb = Fx.runBytesPerChar(fixture)
        val ra = Fx.runBytesPerChar(out)
        for (i in rb.indices) if (i !in at until at + 7) assertEquals("char $i", rb[i], ra[i])
        // Apple's explicit Body code (f2.f1 = 3) and per-paragraph list runs
        // survive; the only new boundary is the heading run splitting into
        // "Heading" (bold) + its '\n' (not bold).
        assertEquals(TopotextInvariants.parse(fixture).runs.size + 1, TopotextInvariants.parse(out).runs.size)
    }

    // ---------------------------------------------------------------- checkboxes

    /** Checklist UUID (f2.f5.f1) of every checkbox run, decoded independently of production code. */
    private fun checklistUuids(bytes: ByteArray): List<String> =
        TopotextInvariants.parse(bytes).runs.mapNotNull { r ->
            val ps = r.fields.firstOrNull { it.fieldNumber == 2 } ?: return@mapNotNull null
            val cl = ProtobufWire.decode(ps.payload).firstOrNull { it.fieldNumber == 5 } ?: return@mapNotNull null
            TopotextInvariants.hex(ProtobufWire.decode(cl.payload).first { it.fieldNumber == 1 }.payload)
        }

    @Test
    fun checkboxUuidsSurviveFormattingAndToggling() {
        val before = checklistUuids(fixture)
        assertEquals(2, before.size)
        val shown = NoteBodyEditor.readVisibleText(fixture)!!

        // Bold elsewhere: checklist UUIDs untouched (used to be re-randomised).
        val at = shown.indexOf("bold")
        val bolded = splice(fixture, a, shown, NoteBodyEditor.toggleInline(shown, Fx.spans(fixture), at, at + 4, NoteBodyEditor.InlineKind.BOLD))
        assertEquals(before, checklistUuids(bolded))

        // Toggle the first checkbox: same UUID, flag flipped.
        val cb = shown.indexOf("check 1 - unchecked")
        val toggled = splice(bolded, a, shown, NoteBodyEditor.toggleCheckbox(shown, Fx.spans(bolded), cb))
        assertEquals(before, checklistUuids(toggled))
        assertTrue(Fx.perChar(toggled)[cb].checked)

        // Bold a word inside a checkbox line: the line's runs share its UUID.
        val inLine = shown.indexOf("unchecked")
        val split = splice(toggled, a, shown, NoteBodyEditor.toggleInline(shown, Fx.spans(toggled), inLine, inLine + 9, NoteBodyEditor.InlineKind.BOLD))
        assertEquals(before.toSet(), checklistUuids(split).toSet())

        // Typing into a checkbox line keeps the line's UUID (text edit with spans).
        val typed = shown.substring(0, inLine) + "still " + shown.substring(inLine)
        val typedSpans = NoteBodyEditor.expandSpansToChars(shown, Fx.spans(fixture)).toMutableList()
        repeat(6) { typedSpans.add(inLine, typedSpans[inLine - 1]) }
        val t = splice(fixture, a, typed, NoteBodyEditor.compactCharsToSpans(typedSpans.toTypedArray()))
        assertEquals(before, checklistUuids(t))
    }

    @Test
    fun newCheckboxParagraphsGetOneUuidEach() {
        val text = "one\ntwo\nthree"
        val n = Fx.create(text, a)
        val spans = NoteBodyEditor.setParagraphStyle(text, Fx.spans(n), 0, text.length, NoteBodyEditor.ParagraphStyle.CHECKBOX_LIST)
        assertEquals(1, spans.size) // one coalesced span over three paragraphs
        val out = splice(n, b, text, spans)
        val uuids = checklistUuids(out)
        assertEquals(listOf(4, 4, 5), TopotextInvariants.parse(out).runs.map { it.length })
        assertEquals(3, uuids.toSet().size)
        // Toggling one leaves every UUID alone.
        val toggled = splice(out, a, text, NoteBodyEditor.toggleCheckbox(text, Fx.spans(out), 5))
        assertEquals(uuids, checklistUuids(toggled))
        assertEquals(listOf(false, true, false), listOf(0, 5, 9).map { Fx.perChar(toggled)[it].checked })
        // Leaving the checklist drops the checklist message.
        val plain = splice(toggled, a, text, NoteBodyEditor.setParagraphStyle(text, Fx.spans(toggled), 0, text.length, NoteBodyEditor.ParagraphStyle.BODY))
        assertTrue(checklistUuids(plain).isEmpty())
    }

    // ---------------------------------------------------------------- attachments

    private val attInfo = Fx.msg(12, Fx.str(1, "4629BD72-48F7-48C9-8080-1334094B5DCC"), Fx.str(2, "com.apple.notes.table"), Fx.varint(77, 1))
    private val attRun = ProtobufWire.encode(listOf(Fx.varint(1, 1), attInfo))

    /** "Table:\n￼\nafter" with a heading run, the attachment run, and a body run. */
    private fun attachmentNote(): ByteArray {
        val n = Fx.withRuns(
            Fx.create("Table:\n\uFFFC\nafter", a),
            listOf(Fx.run(7, Fx.style(1)), attRun, Fx.run(6, Fx.style(3))),
        )
        TopotextInvariants.checkInvariants(n, "Table:\n\uFFFC\nafter")
        return n
    }

    private fun attachmentRuns(bytes: ByteArray) =
        TopotextInvariants.parse(bytes).runs.filter { r -> r.fields.any { it.fieldNumber == 12 } }

    private fun assertAttachmentIntact(bytes: ByteArray) {
        val runs = attachmentRuns(bytes)
        assertEquals(1, runs.size)
        assertEquals(1, runs[0].length)
        val f12 = runs[0].fields.single { it.fieldNumber == 12 }
        assertArrayEquals("attachment_info must be byte-identical", attInfo.payload, f12.payload)
        val span = Fx.spans(bytes).single { it.isAttachment }
        assertEquals("4629BD72-48F7-48C9-8080-1334094B5DCC", span.attachmentRecordName)
        assertEquals("com.apple.notes.table", span.attachmentType)
    }

    @Test
    fun attachmentSurvivesAppend() {
        val out = NoteAppender.appendBytes(attachmentNote(), b, "\nmore", 0)
        TopotextInvariants.checkInvariants(out, "Table:\n\uFFFC\nafter\nmore", b)
        assertAttachmentIntact(out)
    }

    @Test
    fun appendAfterATrailingAttachmentDoesNotGrowTheAttachmentRun() {
        // Regression: appendBytes extended the LAST run, which here is the
        // attachment's, so the appended text became part of the attachment.
        val n = Fx.withRuns(Fx.create("Pic:\n\uFFFC", a), listOf(Fx.run(5, Fx.style(3)), attRun))
        val out = NoteAppender.appendBytes(n, b, "\ncaption", 0)
        TopotextInvariants.checkInvariants(out, "Pic:\n\uFFFC\ncaption", b)
        assertAttachmentIntact(out)
        val runs = TopotextInvariants.parse(out).runs
        assertEquals(listOf(5, 1, 8), runs.map { it.length })
        assertTrue(runs[2].fields.none { it.fieldNumber == 12 })
    }

    @Test
    fun attachmentSurvivesSplicesBeforeAfterAndAroundIt() {
        val n = attachmentNote()
        for (edited in listOf(
            "My table:\n\uFFFC\nafter", // before
            "Table:\n\uFFFC\nafterwards", // after
            "Table:\nX\uFFFCY\nafter", // immediately around
            "T:\n\uFFFC\n", // deletions on both sides
            "\uFFFC", // everything but the attachment
        )) {
            val out = splice(n, b, edited)
            assertEquals(edited, NoteBodyEditor.readVisibleText(out))
            assertAttachmentIntact(out)
            // A chain of edits by another replica keeps it too.
            val again = splice(out, a, edited + " (edited)")
            assertAttachmentIntact(again)
        }
    }

    @Test
    fun deletingTheAttachmentCharRemovesItsRun() {
        val out = splice(attachmentNote(), b, "Table:\n\nafter")
        assertTrue(attachmentRuns(out).isEmpty())
    }

    @Test
    fun attachmentSurvivesFormatOnlyChanges() {
        val n = attachmentNote()
        val text = "Table:\n\uFFFC\nafter"
        var spans = NoteBodyEditor.toggleInline(text, Fx.spans(n), 0, text.length, NoteBodyEditor.InlineKind.BOLD)
        spans = NoteBodyEditor.setParagraphStyle(text, spans, 8, 9, NoteBodyEditor.ParagraphStyle.BULLETED_LIST)
        val out = splice(n, b, text, spans)
        assertAttachmentIntact(out)
        // Text edit + spans in one save.
        val text2 = "Table 1:\n\uFFFC\nafter"
        val spans2 = NoteBodyEditor.expandSpansToChars(text, spans).toMutableList()
        repeat(2) { spans2.add(5, spans2[4]) }
        val out2 = splice(out, a, text2, NoteBodyEditor.compactCharsToSpans(spans2.toTypedArray()))
        assertEquals(text2, NoteBodyEditor.readVisibleText(out2))
        assertAttachmentIntact(out2)
    }

    @Test
    fun fixtureTableAttachmentSurvivesEditsAroundIt() {
        val shown = NoteBodyEditor.readVisibleText(fixture)!!
        val at = shown.indexOf('\uFFFC')
        val origInfo = attachmentRuns(fixture).single().fields.single { it.fieldNumber == 12 }.payload
        val edited = shown.substring(0, at) + "Before " + "\uFFFC" + " after" + shown.substring(at + 1)
        val out = splice(fixture, a, edited)
        val info = attachmentRuns(out).single().fields.single { it.fieldNumber == 12 }.payload
        assertArrayEquals(origInfo, info)
        val bolded = splice(out, b, edited, NoteBodyEditor.toggleInline(edited, Fx.spans(out), 0, edited.length, NoteBodyEditor.InlineKind.BOLD))
        assertArrayEquals(origInfo, attachmentRuns(bolded).single().fields.single { it.fieldNumber == 12 }.payload)
        assertNotNull(Fx.spans(bolded).single { it.isAttachment }.attachmentRecordName)
    }
}
