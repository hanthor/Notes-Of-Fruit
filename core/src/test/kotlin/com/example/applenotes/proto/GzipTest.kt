package com.example.applenotes.proto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Random
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
class GzipTest {

    private val payload = loadFixture("fmt-baseline-1.proto")

    @Test
    fun gzipRoundTrip() {
        val gz = Gzip.compress(payload, CompressionFormat.GZIP)
        assertEquals(0x1f.toByte(), gz[0])
        assertEquals(0x8b.toByte(), gz[1])
        assertEquals(CompressionFormat.GZIP, Gzip.detect(gz))
        assertArrayEquals(payload, Gzip.decompress(gz))
    }

    @Test
    fun defaultFormatIsGzip() {
        assertEquals(CompressionFormat.GZIP, Gzip.detect(Gzip.compress(payload)))
    }

    @Test
    fun zlibRoundTrip() {
        val z = Gzip.compress(payload, CompressionFormat.ZLIB)
        assertEquals(0x78.toByte(), z[0])
        assertEquals(CompressionFormat.ZLIB, Gzip.detect(z))
        assertArrayEquals(payload, Gzip.decompress(z))
    }

    @Test
    fun zlibDetectedForEveryCompressionLevelHeader() {
        // 78 01 (level 0-1), 78 5e (2-5), 78 9c (6, default), 78 da (7-9).
        for ((level, second) in listOf(1 to 0x01, 3 to 0x5e, 6 to 0x9c, 9 to 0xda)) {
            val out = ByteArrayOutputStream()
            DeflaterOutputStream(out, Deflater(level)).use { it.write(payload) }
            val z = out.toByteArray()
            assertEquals(second.toByte(), z[1])
            assertEquals("level $level", CompressionFormat.ZLIB, Gzip.detect(z))
            assertArrayEquals(payload, Gzip.decompress(z))
        }
    }

    @Test
    fun rawProtoIsPassedThrough() {
        // The topotext Document starts with 0x08 or 0x12 — never a compression magic.
        assertEquals(CompressionFormat.NONE, Gzip.detect(payload))
        assertArrayEquals(payload, Gzip.decompress(payload))
        assertArrayEquals(payload, Gzip.compress(payload, CompressionFormat.NONE))
    }

    @Test
    fun shortInputsAreNotMisdetected() {
        assertEquals(CompressionFormat.NONE, Gzip.detect(ByteArray(0)))
        assertEquals(CompressionFormat.NONE, Gzip.detect(byteArrayOf(0x1f)))
        assertEquals(CompressionFormat.NONE, Gzip.detect(byteArrayOf(0x78)))
        // 0x78 followed by a non-zlib FLG byte is not zlib.
        assertEquals(CompressionFormat.NONE, Gzip.detect(byteArrayOf(0x78, 0x00)))
    }

    @Test
    fun appendPreservesInputCompressionFormat() {
        val uuid = testUuid(1)
        val gzB64 = NoteCreator.buildEmptyNoteB64(uuid, "hello")
        val zlibB64 = kotlin.io.encoding.Base64.encode(
            Gzip.compress(Gzip.decompress(kotlin.io.encoding.Base64.decode(gzB64)), CompressionFormat.ZLIB),
        )
        val outGz = NoteAppender.appendBase64(gzB64, uuid, " world", 0)
        val outZ = NoteAppender.appendBase64(zlibB64, uuid, " world", 0)
        assertEquals(CompressionFormat.GZIP, Gzip.detect(kotlin.io.encoding.Base64.decode(outGz)))
        assertEquals(CompressionFormat.ZLIB, Gzip.detect(kotlin.io.encoding.Base64.decode(outZ)))
        assertEquals("hello world", NoteBodyEditor.readVisibleTextFromBase64(outGz))
        assertEquals("hello world", NoteBodyEditor.readVisibleTextFromBase64(outZ))
    }

    @Test
    fun randomPayloadsRoundTripInBothFormats() {
        val r = Random(3)
        repeat(50) {
            val p = ByteArray(r.nextInt(5000)).also { r.nextBytes(it) }
            for (fmt in listOf(CompressionFormat.GZIP, CompressionFormat.ZLIB)) {
                val c = Gzip.compress(p, fmt)
                assertEquals(fmt, Gzip.detect(c))
                assertArrayEquals(p, Gzip.decompress(c))
            }
        }
    }

    @Test
    fun truncatedGzipThrows() {
        val gz = Gzip.compress(payload, CompressionFormat.GZIP)
        val cut = gz.copyOfRange(0, gz.size / 2)
        val threw = runCatching { Gzip.decompress(cut) }.isFailure
        assertTrue("truncated gzip must throw, not return partial data", threw)
    }
}
