package com.example.applenotes.data.local

import com.example.applenotes.client.NoteSummary
import com.example.applenotes.testing.noteRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Issues #12 / #13: the pure parts of the Room store, on the JVM. */
class StoreCodecTest {
    @Test
    fun recordRoundTripsWithEveryField() {
        val rec = noteRecord("NOTE-A", "Shopping\nmilk")
        assertEquals(rec, StoreCodec.decodeRecord(StoreCodec.encodeRecord(rec)))
        val untagged = rec.copy(recordChangeTag = null, recordType = null)
        assertEquals(untagged, StoreCodec.decodeRecord(StoreCodec.encodeRecord(untagged)))
    }

    @Test
    fun corruptRowsDecodeToNull() {
        assertNull(StoreCodec.decodeRecord(null))
        assertNull(StoreCodec.decodeRecord("{"))
        assertNull(StoreCodec.decodeRecord("""{"fields":{}}"""))
    }

    @Test
    fun draftEntityRoundTrips() {
        val d = PendingDraft(
            key = "NOTE-A",
            baseRecord = noteRecord("NOTE-A", "x"),
            folderRefJson = null,
            fullText = "x y",
            spansSignature = "sig",
            snapshotJson = "{}",
            updatedAtMs = 5,
            conflict = true,
        )
        assertEquals(d, d.toEntity().toDraft())
        val newNote = d.copy(key = "draft-1", baseRecord = null, folderRefJson = """{"type":"REFERENCE"}""")
        assertEquals(newNote, newNote.toEntity().toDraft())
    }

    @Test
    fun summaryEntityRoundTrips() {
        val n = NoteSummary("N", "t", null, 3L, deleted = false, folderRecordName = "F")
        assertEquals(n, NoteSummaryEntity.of(n).toSummary())
    }

    @Test
    fun likePatternEscapesWildcards() {
        assertEquals("%milk%", likePattern("milk"))
        assertEquals("%50\\%\\_off\\\\%", likePattern("50%_off\\"))
    }
}
