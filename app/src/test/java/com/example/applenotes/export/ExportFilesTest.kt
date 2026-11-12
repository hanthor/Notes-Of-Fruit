package com.example.applenotes.export

import com.example.applenotes.client.NoteSummary
import com.example.applenotes.testing.noteRecord
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportFilesTest {

    @Test
    fun fileNamesAreSanitizedAndUnique() {
        val names = ExportFileNames()
        assertEquals("a_b_c_.md", names.next("a/b\\c:", "md"))
        assertEquals("Untitled note.md", names.next("   ", "md"))
        assertEquals("Untitled note (2).md", names.next(null, "md"))
        assertEquals("Todo.md", names.next("Todo", "md"))
        assertEquals("todo (2).md", names.next("todo", "md"))
        assertEquals("_con.md", names.next("CON".lowercase(), "md"))
        assertEquals("tab x.html", names.next("tab\tx\n", "html"))
        assertTrue(names.next("x".repeat(300), "md").length <= 80 + 3)
    }

    @Test
    fun imageExtensionFromMagicBytes() {
        assertEquals("png", imageExtension(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0, 0, 0, 0)))
        assertEquals("jpg", imageExtension(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
        assertEquals("heic", imageExtension("\u0000\u0000\u0000\u0018ftypheic".toByteArray()))
        assertEquals("bin", imageExtension(byteArrayOf(1, 2, 3)))
    }

    private fun summary(rn: String, title: String?) = NoteSummary(rn, title, null, null, false, "DefaultFolder-CloudKit")

    @Test
    fun bulkExportFetchesSequentiallyAndSkipsFailures() = runTest {
        val records = mapOf(
            "A" to noteRecord("A", "Same\nbody a"),
            "B" to noteRecord("B", "Same\nbody b"),
            "C" to noteRecord("C", null), // no body: read-only, nothing to export
        )
        val lookups = mutableListOf<String>()
        val written = mutableListOf<Pair<String, String>>()
        val progress = mutableListOf<Int>()
        val notes = listOf(summary("A", "Same"), summary("B", "Same"), summary("C", "c"), summary("MISSING", "m"))
        val result = bulkExport(
            notes = notes,
            format = ExportFormat.MARKDOWN,
            lookup = { lookups += it; records[it] ?: error("not found") },
            write = { name, mime, bytes -> assertEquals("text/markdown", mime); written += name to bytes.decodeToString() },
            onProgress = { done, _ -> progress += done },
        )
        assertEquals(listOf("A", "B", "C", "MISSING"), lookups)
        assertEquals(BulkExportResult(written = 2, failed = 2), result)
        assertEquals(listOf("Same.md", "Same (2).md"), written.map { it.first })
        assertEquals("Same\n\nbody a\n", written[0].second)
        assertEquals(listOf(0, 1, 2, 3, 4), progress)
    }
}
