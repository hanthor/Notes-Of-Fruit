package com.example.applenotes.data

import com.example.applenotes.client.NoteRecord
import com.example.applenotes.proto.Gzip
import com.example.applenotes.proto.NoteBodyEditor
import com.example.applenotes.proto.NoteProtoKind
import com.example.applenotes.proto.ProtobufWire
import com.example.applenotes.testing.noteBodyB64
import com.example.applenotes.testing.noteRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
class NoteBodyTest {

    private fun msg(fieldNumber: Int, vararg children: ProtobufWire.Field) =
        ProtobufWire.Field(fieldNumber, ProtobufWire.WIRE_LENGTH_DELIM, ProtobufWire.encode(children.toList()))

    private fun bytes(fieldNumber: Int, payload: ByteArray) =
        ProtobufWire.Field(fieldNumber, ProtobufWire.WIRE_LENGTH_DELIM, payload)

    private fun b64(vararg top: ProtobufWire.Field): String =
        Base64.encode(Gzip.compress(ProtobufWire.encode(top.toList())))

    /** NoteStoreProto → Document(2) → Note(3) with the given fields. */
    private fun noteStore(vararg noteFields: ProtobufWire.Field) = b64(msg(2, msg(3, *noteFields)))

    @Test
    fun cleanNoteIsEditable() {
        val body = decodeNoteBody(noteRecord("A", "Title\nbody text"))
        body as NoteBody.Editable
        assertEquals("Title\nbody text", body.text)
    }

    @Test
    fun realICloudNoteIsEditable() {
        // fmt-baseline-1: a real note pulled off iCloud (see FormatRoundTripTest).
        val proto = javaClass.classLoader!!.getResourceAsStream("fmt-baseline-1.proto")!!.readBytes()
        val b64 = Base64.encode(Gzip.compress(proto))
        val body = decodeNoteBody(noteRecord("A", text = null, textB64 = b64))
        body as NoteBody.Editable
        assertEquals(NoteBodyEditor.readVisibleTextFromBase64(b64), body.text)
        assertEquals(NoteBodyEditor.parseAttributeRunsFromBase64(b64), body.spans)
    }

    @Test
    fun emptyNoteIsEditableAndDistinctFromAFailure() {
        val b64 = noteBodyB64("")
        assertEquals(NoteProtoKind.NOTE_STORE_PROTO, NoteBodyEditor.probeBase64(b64))
        val body = decodeNoteBody(noteRecord("A", text = null, textB64 = b64))
        assertEquals(NoteBody.Editable("", body.spans), body)
    }

    @Test
    fun modernMergeableNoteIsReadOnly() {
        // Note message without note_text (field 2), as MergableDataProto.
        val b64 = noteStore(bytes(3, byteArrayOf(0x08, 0x01)))
        assertEquals(NoteProtoKind.MERGABLE_DATA, NoteBodyEditor.probeBase64(b64))
        val body = decodeNoteBody(noteRecord("A", text = null, textB64 = b64))
        assertEquals(NoteBody.ReadOnly(ReadOnlyReason.MODERN_FORMAT, text = null), body)
    }

    @Test
    fun unrecognisedBodyIsReadOnly() {
        for (garbage in listOf("not base64!!", Base64.encode("plain bytes, not gzip".encodeToByteArray()))) {
            val body = decodeNoteBody(noteRecord("A", text = null, textB64 = garbage))
            assertEquals(garbage, NoteBody.ReadOnly(ReadOnlyReason.UNRECOGNIZED_FORMAT, text = null), body)
        }
    }

    @Test
    fun noteStoreProtoWhoseSubstringsCantBeWalkedIsReadOnlyWithRawText() {
        // note_text present but no substrings: probe says NOTE_STORE_PROTO,
        // the visible-text walk fails. Before #3 this opened as editable.
        val b64 = noteStore(bytes(2, "raw text".encodeToByteArray()))
        assertEquals(NoteProtoKind.NOTE_STORE_PROTO, NoteBodyEditor.probeBase64(b64))
        assertNull(NoteBodyEditor.readVisibleTextFromBase64(b64))
        val body = decodeNoteBody(noteRecord("A", text = null, textB64 = b64))
        assertEquals(NoteBody.ReadOnly(ReadOnlyReason.DECODE_FAILED, "raw text"), body)
    }

    @Test
    fun brandNewDraftWithoutBodyIsEditable() {
        val draft = NoteRecord("draft-1", "Note", recordChangeTag = null, rawFields = emptyMap())
        assertEquals(NoteBody.Editable("", emptyList()), decodeNoteBody(draft))
    }

    @Test
    fun existingNoteWithoutBodyIsReadOnly() {
        val rec = noteRecord("A", text = null, tag = "tag-1")
        assertEquals(NoteBody.ReadOnly(ReadOnlyReason.MISSING_BODY, text = null), decodeNoteBody(rec))
    }
}
