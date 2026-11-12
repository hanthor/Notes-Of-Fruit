package com.example.applenotes.diagnostics

import com.example.applenotes.testing.REPLICA_UUID
import com.example.applenotes.testing.noteRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticReportTest {
    private val title = "Secret plans"
    private val body = "buy a volcano lair\nfeed the sharks"
    private val record = noteRecord("RECORD-XYZZY-1234", "$title\n$body")

    private fun info(shape: NoteShape?) = DiagnosticInfo(
        appVersion = "0.0.3",
        buildType = "release",
        appId = "io.github.hanthor.notesoffruit",
        androidRelease = "15",
        sdkInt = 35,
        deviceManufacturer = "Google",
        deviceModel = "Pixel 9",
        signedIn = true,
        cachedNotes = 12,
        cachedFolders = 3,
        pendingDrafts = 1,
        lastNote = shape,
    )

    @Test
    fun noteShapeIsCountsOnly() {
        val shape = NoteShape.of(record)
        assertNotNull(shape)
        shape!!
        assertEquals("NOTE_STORE_PROTO", shape.kind)
        assertFalse(shape.locked)
        assertTrue(shape.substrings > 0)
        assertTrue(shape.replicas >= 1)
        assertTrue(shape.attributeRuns >= 1)
        assertEquals(0, shape.attachments)
        assertTrue(shape.compressedBytes > 0)
    }

    @Test
    fun reportHasVersionsAndCountsButNoNoteContent() {
        val report = buildDiagnosticReport(info(NoteShape.of(record)))
        assertTrue(report.contains("0.0.3 (release)"))
        assertTrue(report.contains("Pixel 9"))
        assertTrue(report.contains("Signed in: yes"))
        assertTrue(report.contains("12 notes, 3 folders"))
        assertTrue(report.contains("Unsent drafts: 1"))
        assertTrue(report.contains("kind=NOTE_STORE_PROTO"))
        val forbidden = listOf("Secret", "plans", "volcano", "lair", "sharks", "RECORD-XYZZY")
        for (word in forbidden) assertFalse("report leaks '$word':\n$report", report.contains(word, ignoreCase = true))
        // Not even the replica UUID's first bytes (NoteBodyEditor.summarize prints those).
        val uuidHex = REPLICA_UUID.joinToString("") { "%02x".format(it) }
        assertFalse(report.contains(uuidHex.take(8), ignoreCase = true))
    }

    @Test
    fun noOpenNoteSaysSo() {
        val report = buildDiagnosticReport(info(null).copy(signedIn = false))
        assertTrue(report.contains("Signed in: no"))
        assertTrue(report.contains("Last opened note: none"))
    }

    @Test
    fun aNoteWithoutABodyHasNoShape() {
        assertNull(NoteShape.of(noteRecord("R", text = null)))
    }
}
