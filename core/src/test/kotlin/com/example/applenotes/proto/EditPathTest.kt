package com.example.applenotes.proto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Append and mid-text splice paths, on SYNTHETIC fixtures built with
 * NoteCreator and then edited by several replica UUIDs, plus the one real
 * Mac-created fixture (fmt-baseline-1). Every write is followed by the full
 * invariant check and a CRDT monotonicity check.
 */
class EditPathTest {

    private val a = testUuid(1)
    private val b = testUuid(2)
    private val c = testUuid(3)

    private fun append(bytes: ByteArray, who: ByteArray, text: String, expected: String): ByteArray {
        val out = NoteAppender.appendBytes(bytes, who, text, 0)
        TopotextInvariants.checkInvariants(out, expected, who)
        TopotextInvariants.checkMonotonic(bytes, out, who)
        return out
    }

    private fun splice(bytes: ByteArray, who: ByteArray, body: String, expected: String = body): ByteArray {
        val out = NoteAppender.setBodySpliceBytes(bytes, who, body, 0)
        TopotextInvariants.checkInvariants(out, expected, who)
        TopotextInvariants.checkMonotonic(bytes, out, who)
        return out
    }

    @Test
    fun realMacFixtureSatisfiesInvariants() {
        // The checker itself must accept a note Apple wrote.
        TopotextInvariants.checkInvariants(loadFixture("fmt-baseline-1.proto"))
    }

    @Test
    fun appendBySameReplicaExtendsItsClock() {
        val n0 = Fx.create("Hello", a)
        val n1 = append(n0, a, " world", "Hello world")
        val d = TopotextInvariants.parse(n1)
        assertEquals(1, d.clocks.size)
        assertEquals(11L, d.clocks[0].counters[0])
        // New substring took the sentinel's slot; sentinel moved to the end.
        assertEquals(4, d.subs.size)
        assertEquals(TopotextInvariants.CharId(1, 5), d.subs[2].charId)
        assertEquals(6, d.subs[2].length)
        // Simple append extends the last run instead of adding one.
        assertEquals(1, d.runs.size)
        assertEquals(11, d.runs[0].length)
    }

    @Test
    fun appendByNewReplicaPromotesItToSlotOne() {
        val n0 = Fx.create("Hello", a)
        val n1 = append(n0, b, "!", "Hello!")
        val d = TopotextInvariants.parse(n1)
        assertEquals(2, d.clocks.size)
        assertArrayEquals(b, d.clocks[0].uuid)
        assertArrayEquals(a, d.clocks[1].uuid)
        // a's chars were remapped from slot 1 to slot 2 (charID and style ts).
        assertEquals(TopotextInvariants.CharId(2, 0), d.subs[1].charId)
        assertEquals(TopotextInvariants.CharId(2, 0), d.subs[1].ts)
        // b allocates after the doc-wide max clock.
        assertEquals(TopotextInvariants.CharId(1, 5), d.subs[2].charId)
        // counter2 = doc-wide max style-timestamp clock (0 here). Note this
        // differs from NoteCreator, which writes max+1 (=1) for its own
        // counter2 — pinned as-is; see README's counter2 open question.
        assertEquals(listOf(6L, 0L), d.clocks[0].counters)
    }

    @Test
    fun existingReplicaAtSlotThreeIsRotatedToFront() {
        var n = Fx.create("abc", a)
        n = append(n, b, "d", "abcd")
        n = append(n, c, "e", "abcde") // slots: c, b, a
        assertArrayEquals(a, TopotextInvariants.parse(n).clocks[2].uuid)
        n = append(n, a, "f", "abcdef") // a back to slot 1: a, c, b
        val d = TopotextInvariants.parse(n)
        assertEquals(listOf(a, c, b).map { TopotextInvariants.hex(it) }, d.clocks.map { TopotextInvariants.hex(it.uuid) })
        // a re-uses its own counter (3 → past the doc max) rather than restarting at 0.
        assertTrue(d.subs.any { it.charId == TopotextInvariants.CharId(1, 5) && it.length == 1 })
    }

    @Test
    fun spliceReplaceInsertDeleteByThreeReplicas() {
        var n = Fx.create("Hello", a)
        n = append(n, b, "!", "Hello!")
        n = splice(n, c, "Hippo!")
        var d = TopotextInvariants.parse(n)
        assertEquals(listOf(c, b, a).map { TopotextInvariants.hex(it) }, d.clocks.map { TopotextInvariants.hex(it.uuid) })
        // The deleted "ell" is a tombstone signed by the editor (slot 1) with a fresh ts clock.
        val tomb = d.subs.single { it.tombstone }
        assertEquals(3, tomb.length)
        assertEquals(1L, tomb.ts.replica)
        assertTrue(tomb.ts.clock > 0)

        n = splice(n, a, "Hip hippo!") // insert in the middle
        n = splice(n, b, "Hip!") // delete across several substrings and replicas
        n = splice(n, c, "") // delete everything
        d = TopotextInvariants.parse(n)
        assertTrue(d.subs.filter { !it.isDocStart && !it.isSentinel }.all { it.tombstone })
        assertTrue(d.runs.isEmpty())
        n = splice(n, a, "again") // type into an emptied note
        n = append(n, b, " and again", "again and again")
        n = splice(n, c, "Start: again and again")
    }

    @Test
    fun spliceAtDocumentEdges() {
        var n = Fx.create("middle", a)
        n = splice(n, b, ">> middle")
        n = splice(n, a, ">> middle <<")
        n = splice(n, c, "middle <<")
        n = splice(n, b, "middle")
        n = splice(n, a, "MIDDLE")
    }

    @Test
    fun noOpSpliceReturnsInputUnchanged() {
        val n = Fx.create("same text", a)
        assertSame(n, NoteAppender.setBodySpliceBytes(n, b, "same text", 0))
    }

    @Test
    fun softLineBreaksSurviveAnEditThroughTheDisplayText() {
        // Regression: readVisibleText shows U+2028/U+2029 as '\n'. The UI
        // saves that display text back, which used to be diffed against the
        // raw text and rewrote every U+2028 into a paragraph break.
        val raw = "item one continued\nitem two para"
        val n0 = Fx.create(raw, a)
        val shown = NoteBodyEditor.readVisibleText(n0)!!
        assertEquals("item one\ncontinued\nitem two\npara", shown)

        // Saving the unchanged display text is a no-op.
        assertSame(n0, NoteAppender.setBodySpliceBytes(n0, b, shown, 0))

        // Editing elsewhere keeps the separators.
        val n1 = splice(n0, b, shown.replace("item two", "item 2"), "item one continued\nitem 2 para")
        // Editing right next to a separator keeps it too.
        splice(n1, a, NoteBodyEditor.readVisibleText(n1)!!.replace("one\ncontinued", "one\ncontinued!"),
            "item one continued!\nitem 2 para")
        // Pure append path with display text.
        append(n0, b, "\nmore", raw + "\nmore")
    }

    @Test
    fun cjkAndEmojiHeavyNotesAreEditable() {
        // Regression: probe() counted multi-byte chars once against the byte
        // total, so text that is mostly 3–4-byte UTF-8 was classified as
        // MERGABLE_DATA and every edit was refused.
        for (text in listOf("日本語のテキストです", "😀😁🎉", "中文笔记\n第二行")) {
            val n = Fx.create(text, a)
            assertEquals(NoteProtoKind.NOTE_STORE_PROTO, NoteBodyEditor.probe(n))
            val n1 = append(n, b, "追加", text + "追加")
            splice(n1, a, "前" + text + "追加")
        }
    }

    @Test
    fun spliceNeverSplitsASurrogatePair() {
        var n = Fx.create("x😀y", a)
        n = splice(n, b, "x😁y") // shares the high surrogate
        n = splice(n, a, "x😁😂y")
        n = splice(n, c, "x😂y") // shares the low surrogate? (DE01 vs DE02 differ) — remove one emoji
        n = splice(n, b, "x👍🏽y") // emoji + skin-tone modifier
        n = splice(n, a, "xy")
        // Attribute-run lengths are in UTF-16 units, matching String.length.
        val d = TopotextInvariants.parse(splice(n, c, "😀"))
        assertEquals(2, d.runs.sumOf { it.length })
    }

    @Test
    fun unknownFieldsSurviveAppendAndSplice() {
        val base = Fx.create("Hello", a)
        // Add fields we don't model at every level (shapes seen on Mac notes,
        // plus made-up field numbers).
        val fieldTop = Fx.varint(1, 0) // Document.f1 = 0, as on Mac notes
        val fieldVersion = Fx.varint(1, 0)
        val fieldString6 = Fx.msg(6, Fx.str(1, "attachment-ish"), Fx.varint(2, 7))
        val fieldString99 = Fx.bytes(99, byteArrayOf(1, 2, 3))
        var n = ProtobufWire.encode(listOf(fieldTop) + ProtobufWire.decode(base))
        n = Fx.mapVersion(n) { listOf(fieldVersion) + it }
        n = Fx.mapString(n) { sf ->
            sf.map { f ->
                if (f.fieldNumber == 4) {
                    // Unknown field inside our Clock entry and an extra
                    // VectorTimestamp-level unknown field.
                    val vt = ProtobufWire.decode(f.payload).map { clk ->
                        clk.copy(payload = clk.payload + ProtobufWire.encode(listOf(Fx.varint(7, 42))))
                    } + Fx.varint(9, 5)
                    f.copy(payload = ProtobufWire.encode(vt))
                } else f
            } + fieldString6 + fieldString99
        }
        TopotextInvariants.checkInvariants(n, "Hello")

        fun assertPreserved(out: ByteArray) {
            val d = TopotextInvariants.parse(out)
            assertEquals(TopotextInvariants.hex(ProtobufWire.encode(listOf(fieldTop))), TopotextInvariants.hex(ProtobufWire.encode(d.topFields.filter { it.fieldNumber == 1 })))
            assertEquals(TopotextInvariants.hex(ProtobufWire.encode(listOf(fieldVersion))), TopotextInvariants.hex(ProtobufWire.encode(d.versionFields.filter { it.fieldNumber == 1 })))
            assertEquals(
                TopotextInvariants.hex(ProtobufWire.encode(listOf(fieldString6, fieldString99))),
                TopotextInvariants.hex(ProtobufWire.encode(d.unknownStringFields())),
            )
            val vt = ProtobufWire.decode(d.stringFields.single { it.fieldNumber == 4 }.payload)
            assertTrue("VectorTimestamp unknown field kept", vt.any { it.fieldNumber == 9 })
            // a's clock entry keeps its unknown field (whatever slot it is in now).
            val aClock = d.clocks.single { it.uuid.contentEquals(a) }
            assertTrue("clock unknown field kept", ProtobufWire.decode(aClock.raw).any { it.fieldNumber == 7 })
        }

        val appendedByA = append(n, a, " there", "Hello there")
        assertPreserved(appendedByA)
        val appendedByB = append(n, b, " there", "Hello there")
        assertPreserved(appendedByB)
        val splicedByA = splice(n, a, "Jello")
        assertPreserved(splicedByA)
        val splicedByB = splice(appendedByB, b, "Hey there")
        assertPreserved(splicedByB)
    }

    @Test
    fun macFixtureCanBeAppendedAndSpliced() {
        val f = loadFixture("fmt-baseline-1.proto")
        val shown = NoteBodyEditor.readVisibleText(f)!!
        val raw = TopotextInvariants.parse(f).rawVisible()
        val n1 = append(f, a, "appended", raw + "appended")
        splice(f, a, shown.replace("Subheading", "Sub-heading"), raw.replace("Subheading", "Sub-heading"))
        splice(n1, b, shown.replace("dot item 2", "dot item two") + "appended",
            raw.replace("dot item 2", "dot item two") + "appended")
    }
}
