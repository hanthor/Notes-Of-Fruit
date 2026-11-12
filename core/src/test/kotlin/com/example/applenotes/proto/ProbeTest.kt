package com.example.applenotes.proto

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Random

/**
 * NoteBodyEditor.probe decides whether a body is the legacy topotext shape
 * we can edit (NOTE_STORE_PROTO) or something else we must leave alone. The
 * UI opens anything else read-only and NoteAppender refuses to write it, so
 * misclassifying in either direction matters.
 */
class ProbeTest {

    /** Wrap [noteField2] as Document.f2 → Version.f3 → String.f2 (plus a substring). */
    private fun bodyWithNoteText(noteField2: ByteArray): ByteArray {
        val note = ProtobufWire.encode(listOf(
            ProtobufWire.Field(2, ProtobufWire.WIRE_LENGTH_DELIM, noteField2),
            Fx.msg(3, Fx.msg(1, Fx.varint(1, 0), Fx.varint(2, 0))),
        ))
        val version = ProtobufWire.encode(listOf(Fx.varint(2, 0), ProtobufWire.Field(3, ProtobufWire.WIRE_LENGTH_DELIM, note)))
        return ProtobufWire.encode(listOf(ProtobufWire.Field(2, ProtobufWire.WIRE_LENGTH_DELIM, version)))
    }

    @Test
    fun multiByteTextIsEditable() {
        // Regression: the UTF-8 heuristic counted one printable per multi-byte
        // CHARACTER against the BYTE total, so these were MERGABLE_DATA.
        for (text in listOf(
            "你好世界\n今天天气很好",
            "Shopping 🛒🛒\nmilk",
            "😀😀😀",
            "日本語のテキストです",
            "Привет, мир",
            "Pic:\n￼", // short note with one attachment
            "￼￼",
            "a b c",
        )) {
            assertEquals(text, NoteProtoKind.NOTE_STORE_PROTO, NoteBodyEditor.probe(NoteCreator.buildEmptyNoteBytes(testUuid(1), text)))
            assertEquals(text, NoteProtoKind.NOTE_STORE_PROTO, NoteBodyEditor.probe(bodyWithNoteText(text.encodeToByteArray())))
        }
    }

    @Test
    fun realMacFixtureIsEditable() {
        assertEquals(NoteProtoKind.NOTE_STORE_PROTO, NoteBodyEditor.probe(loadFixture("fmt-baseline-1.proto")))
    }

    @Test
    fun mergeableDataShapesAreNotText() {
        // MergeableDataObjectData: no field 2 at all, starts at field 3 (entries).
        val mergeable = ProtobufWire.encode(listOf(
            ProtobufWire.Field(2, ProtobufWire.WIRE_LENGTH_DELIM, ProtobufWire.encode(listOf(
                Fx.varint(2, 0),
                Fx.msg(3, Fx.msg(3, Fx.msg(13, Fx.varint(1, 0))), Fx.str(4, "crRows"), Fx.str(5, "com.apple.notes.ICTable")),
            ))),
        ))
        assertEquals(NoteProtoKind.MERGABLE_DATA, NoteBodyEditor.probe(mergeable))

        // Field 2 holding a nested protobuf message (varint tags/control bytes), not text.
        val nested = ProtobufWire.encode(listOf(
            Fx.varint(1, 1), Fx.varint(2, 300), Fx.bytes(6, ByteArray(16) { (it * 17).toByte() }), Fx.varint(3, 2),
        ))
        assertEquals(NoteProtoKind.MERGABLE_DATA, NoteBodyEditor.probe(bodyWithNoteText(nested)))

        // Binary blobs: mostly invalid UTF-8 or control bytes.
        val r = Random(9)
        repeat(200) {
            val blob = ByteArray(16 + r.nextInt(64)).also { r.nextBytes(it) }
            assertEquals(NoteProtoKind.MERGABLE_DATA, NoteBodyEditor.probe(bodyWithNoteText(blob)))
        }
        // Valid UTF-8 but dominated by C0 control chars.
        val controls = ByteArray(40) { (it % 8).toByte() } + "ab".encodeToByteArray()
        assertEquals(NoteProtoKind.MERGABLE_DATA, NoteBodyEditor.probe(bodyWithNoteText(controls)))
    }

    @Test
    fun garbageIsUnknown() {
        assertEquals(NoteProtoKind.UNKNOWN, NoteBodyEditor.probe(byteArrayOf(0x0b, 0x01)))
        assertEquals(NoteProtoKind.UNKNOWN, NoteBodyEditor.probe(ByteArray(0)))
    }
}
