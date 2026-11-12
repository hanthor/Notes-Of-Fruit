package com.example.applenotes.ui.detail

import com.example.applenotes.proto.NoteBodyEditor
import com.example.applenotes.proto.NoteBodyEditor.AttrSpan
import com.example.applenotes.proto.NoteBodyEditor.ParagraphStyle
import com.example.applenotes.proto.NoteCreator
import com.example.applenotes.proto.NoteAppender
import com.example.applenotes.proto.TopotextInvariants
import com.example.applenotes.testing.REPLICA_UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpanSyncTest {
    private val att = AttrSpan(
        length = 1,
        style = ParagraphStyle.BODY,
        isAttachment = true,
        attachmentRecordName = "4629BD72-48F7-48C9-8080-1334094B5DCC",
        attachmentType = "com.apple.notes.table",
    )

    private fun perChar(text: String, spans: List<AttrSpan>) = NoteBodyEditor.expandSpansToChars(text, spans)

    @Test
    fun typingRightAfterAnAttachmentDoesNotCopyItsIdentity() {
        val old = "Table:\n￼\nafter"
        val spans = listOf(AttrSpan(7, ParagraphStyle.HEADING, bold = true), att, AttrSpan(6, ParagraphStyle.BODY))
        val new = "Table:\n￼XY\nafter"
        val chars = perChar(new, syncSpansToText(old, spans, new))
        assertTrue(chars[7].isAttachment)
        for (i in listOf(8, 9)) {
            assertFalse("char $i", chars[i].isAttachment)
            assertEquals(null, chars[i].attachmentRecordName)
            assertEquals(null, chars[i].attachmentType)
        }
        assertEquals(1, chars.count { it.isAttachment })
    }

    @Test
    fun typingBeforeAnAttachmentAtTheStartDoesNotCopyItsIdentity() {
        val old = "￼\nafter"
        val spans = listOf(att, AttrSpan(6, ParagraphStyle.BODY))
        val new = "XY￼\nafter"
        val chars = perChar(new, syncSpansToText(old, spans, new))
        assertEquals(listOf(false, false, true), chars.take(3).map { it.isAttachment })
        assertEquals(1, chars.count { it.isAttachment })
    }

    /** End to end through the encoder: one attachment run, not three. */
    @Test
    fun theSavedProtoHasOneAttachmentRun() {
        val old = "Table:\n￼\nafter"
        val base = NoteCreator.buildEmptyNoteB64(REPLICA_UUID, old)
        val spans = listOf(AttrSpan(7, ParagraphStyle.HEADING), att, AttrSpan(6, ParagraphStyle.BODY))
        val withAtt = NoteAppender.setBodyBase64(base, REPLICA_UUID, old, 1, spans)
        val new = "Table:\n￼XY\nafter"
        val out = NoteAppender.setBodyBase64(withAtt, REPLICA_UUID, new, 2, syncSpansToText(old, spans, new))
        val bytes = com.example.applenotes.proto.Gzip.decompress(java.util.Base64.getDecoder().decode(out))
        TopotextInvariants.checkInvariants(bytes, new, REPLICA_UUID)
        assertEquals(1, NoteBodyEditor.parseAttributeRunsFromBase64(out).count { it.isAttachment })
    }
}
