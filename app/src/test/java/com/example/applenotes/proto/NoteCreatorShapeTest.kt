package com.example.applenotes.proto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Pins the exact shape of a freshly created note (issue #9) so it can't
 * change silently. This is NOT a claim that the shape is what Apple wants —
 * the README lists open hypotheses (multi-substring trees split around line
 * breaks, attribute runs split at trailing '\n', non-trivial counter2) that
 * can only be settled with a Mac round trip. If you change NoteCreator on
 * purpose, update the golden bytes here in the same commit and say why.
 */
@OptIn(ExperimentalEncodingApi::class)
class NoteCreatorShapeTest {

    private val uuid = ByteArray(16) { (0x10 + it).toByte() }.also {
        it[6] = 0x46; it[8] = 0x98.toByte() // v4 / RFC 4122 variant
    }

    @Test
    fun goldenBytesForTwoLineNote() {
        val bytes = NoteCreator.buildEmptyNoteBytes(uuid, "Hi\nyo")
        assertEquals(GOLDEN_HI_YO, Topotext.hex(bytes))
    }

    @Test
    fun structureOfNonEmptyNote() {
        val text = "Title\nbody 😀"
        val n = text.length // UTF-16 units: the emoji counts as 2
        assertEquals(13, n)
        val bytes = NoteCreator.buildEmptyNoteBytes(uuid, text)
        val d = Topotext.checkInvariants(bytes, expectedRawVisible = text, ourUuid = uuid)

        // Document: only Version (f2). Mac's own notes also carry a top-level
        // f1=0 and Version.f1=0 (see fmt-baseline-1); we don't emit those.
        assertEquals(listOf(2), d.topFields.map { it.fieldNumber })
        assertEquals(listOf(2 to 0L), d.versionFields.filter { it.wireType == 0 }.map { it.fieldNumber to ProtobufWire.decodeVarint(it) })
        assertEquals(listOf(2, 3), d.versionFields.map { it.fieldNumber })

        // topotext.String field order: string, 3 substrings, timestamp, 1 run.
        assertEquals(listOf(2, 3, 3, 3, 4, 5), d.stringFields.map { it.fieldNumber })
        assertEquals(text, d.flat)

        // ONE content substring (iCloud.com splits around line breaks; we don't).
        assertEquals(3, d.subs.size)
        with(d.subs[0]) {
            assertEquals(Topotext.CharId(0, 0), charId); assertEquals(0, length)
            assertEquals(Topotext.CharId(0, 0), ts); assertEquals(listOf(1), children)
        }
        with(d.subs[1]) {
            assertEquals(Topotext.CharId(1, 0), charId); assertEquals(n, length)
            assertEquals(Topotext.CharId(1, 0), ts); assertEquals(listOf(2), children)
            assertEquals(false, tombstone)
        }
        with(d.subs[2]) {
            assertEquals(Topotext.CharId(0, Topotext.SENTINEL_CLOCK), charId)
            assertEquals(Topotext.CharId(0, Topotext.SENTINEL_CLOCK), ts)
            assertEquals(emptyList<Int>(), children)
        }
        // No substring carries an explicit tombstone=false field.
        for (s in d.subs) assertTrue(ProtobufWire.decode(s.raw).none { it.fieldNumber == 4 })

        // One replica: us, counter1 = n (next char clock), counter2 = 1.
        assertEquals(1, d.clocks.size)
        assertArrayEquals(uuid, d.clocks[0].uuid)
        assertEquals(listOf(n.toLong(), 1L), d.clocks[0].counters)

        // ONE attribute run, length only (no paragraph style, no font), in UTF-16 units.
        assertEquals(1, d.runs.size)
        assertEquals(n, d.runs[0].length)
        assertEquals(listOf(1), d.runs[0].fields.map { it.fieldNumber })
    }

    @Test
    fun structureOfEmptyNote() {
        val bytes = NoteCreator.buildEmptyNoteBytes(uuid, "")
        val d = Topotext.checkInvariants(bytes, expectedRawVisible = "", ourUuid = uuid)
        assertEquals(2, d.subs.size)
        assertEquals(listOf(1), d.subs[0].children) // doc-start links straight to the sentinel
        assertEquals(listOf(0L, 0L), d.clocks[0].counters)
        assertTrue(d.runs.isEmpty())
        assertEquals("", NoteBodyEditor.readVisibleText(bytes))
    }

    @Test
    fun b64IsGzippedCreatorBytes() {
        val text = "hello"
        val b64 = NoteCreator.buildEmptyNoteB64(uuid, text)
        val gz = Base64.decode(b64)
        assertEquals(CompressionFormat.GZIP, Gzip.detect(gz))
        assertArrayEquals(NoteCreator.buildEmptyNoteBytes(uuid, text), Gzip.decompress(gz))
    }

    @Test
    fun replicaUuidIsCopiedVerbatim() {
        // NoteCreator doesn't mint UUIDs: whatever DeviceIdentity hands it
        // (which must be v4 — see deviceIdentityGeneratesV4Uuids) lands in
        // the VectorTimestamp unchanged.
        for (seed in 1..20) {
            val u = testUuid(seed)
            val d = Topotext.parse(NoteCreator.buildEmptyNoteBytes(u, "x"))
            assertArrayEquals(u, d.clocks.single().uuid)
        }
    }

    @Test
    fun deviceIdentityGeneratesV4Uuids() {
        // DeviceIdentity.generateV4Uuid is private; reach it reflectively so
        // the README's "replica UUID must be RFC 4122 v4" rule is pinned.
        val cls = Class.forName("com.example.applenotes.auth.DeviceIdentity")
        val instance = cls.getField("INSTANCE").get(null)
        val m = cls.getDeclaredMethod("generateV4Uuid").apply { isAccessible = true }
        repeat(200) {
            val b = m.invoke(instance) as ByteArray
            assertEquals(16, b.size)
            assertEquals("version nibble", 4, (b[6].toInt() ushr 4) and 0xF)
            assertEquals("variant bits", 0b10, (b[8].toInt() ushr 6) and 0x3)
        }
    }

    @Test
    fun creatorOutputIsEditable() {
        val bytes = NoteCreator.buildEmptyNoteBytes(uuid, "Title\nbody")
        val appended = NoteAppender.appendBytes(bytes, uuid, "\nmore", 0)
        Topotext.checkInvariants(appended, "Title\nbody\nmore", uuid)
        val spliced = NoteAppender.setBodySpliceBytes(appended, uuid, "Title\nnew body\nmore", 0)
        Topotext.checkInvariants(spliced, "Title\nnew body\nmore", uuid)
        // Editing an empty note works too.
        val empty = NoteCreator.buildEmptyNoteBytes(uuid, "")
        Topotext.checkInvariants(NoteAppender.appendBytes(empty, uuid, "first", 0), "first", uuid)
        Topotext.checkInvariants(NoteAppender.setBodySpliceBytes(empty, uuid, "first", 0), "first", uuid)
    }

    companion object {
        // Golden output of NoteCreator.buildEmptyNoteBytes(uuid, "Hi\nyo").
        // Assembled independently from the README's field layout (not by
        // running NoteCreator), annotated:
        //   12 69                    Document.version (f2)
        //     10 00                    Version.f2 = 0
        //     1a 65                    Version.data (f3) = topotext.String
        //       12 05 "Hi\nyo"           string
        //       1a 10 ...                doc-start  (0,0) len 0 ts (0,0) child [1]
        //       1a 10 ...                content    (1,0) len 5 ts (1,0) child [2]
        //       1a 16 ...                sentinel   (0,2^32-1) len 0 ts (0,2^32-1)
        //       22 1c 0a 1a              VectorTimestamp { Clock { uuid,
        //         0a 10 <uuid>             ReplicaClock{5}, ReplicaClock{1} } }
        //         12 02 08 05  12 02 08 01
        //       2a 02 08 05              AttributeRun { length 5 }
        const val GOLDEN_HI_YO =
            "126910001a65120548690a796f" +
                "1a100a040800100010001a04080010002801" +
                "1a100a040801100010051a04080110002802" +
                "1a160a08080010ffffffff0f10001a08080010ffffffff0f" +
                "221c0a1a0a10101112131415461798191a1b1c1d1e1f1202080512020801" +
                "2a020805"
    }
}
