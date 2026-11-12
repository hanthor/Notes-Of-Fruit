package com.example.applenotes.ui.detail

import com.example.applenotes.client.NoteRecord
import com.example.applenotes.data.AttachmentCache
import com.example.applenotes.data.AttachmentCacheKey
import com.example.applenotes.data.CacheStore
import com.example.applenotes.data.ImageSource
import com.example.applenotes.data.PreviewLocation
import com.example.applenotes.data.parseAsset
import com.example.applenotes.data.previewLocations
import kotlin.io.encoding.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentImagesTest {
    private fun str(value: String): JsonElement = buildJsonObject {
        put("type", "STRING")
        put("value", Base64.encode(value.encodeToByteArray()))
    }

    private fun assetObj(url: String, size: Long? = null, checksum: String? = null) = buildJsonObject {
        put("downloadURL", url)
        size?.let { put("size", it) }
        checksum?.let { put("fileChecksum", it) }
    }

    private fun assetField(url: String, size: Long? = null, checksum: String? = null): JsonElement = buildJsonObject {
        put("type", "ASSETID")
        put("value", assetObj(url, size, checksum))
    }

    private fun ref(recordName: String): JsonElement = buildJsonObject {
        put("type", "REFERENCE")
        put("value", buildJsonObject { put("recordName", recordName); put("action", "NONE") })
    }

    private fun record(name: String, tag: String = "t-$name", fields: Map<String, JsonElement>) =
        NoteRecord(name, "Attachment", tag, fields)

    // ----- CKAsset JSON -----

    @Test
    fun parsesFieldAndBareAssetShapes() {
        val a = parseAsset(assetField("https://x/1", size = 12, checksum = "abc"))!!
        assertEquals("https://x/1", a.downloadUrl)
        assertEquals(12L, a.size)
        assertEquals("abc", a.fileChecksum)
        val b = parseAsset(assetObj("https://x/2"))!!
        assertEquals("https://x/2", b.downloadUrl)
        assertNull(b.fileChecksum)
        assertNull(parseAsset(ref("R")))
        assertNull(parseAsset(JsonPrimitive("nope")))
        assertNull(parseAsset(null))
    }

    @Test
    fun previewImagesAcceptsAssetListsAndReferenceLists() {
        val assets = record("A", fields = mapOf("PreviewImages" to buildJsonObject {
            put("type", "ASSETID_LIST")
            put("value", buildJsonArray { add(assetObj("https://p/1", 10)); add(assetObj("https://p/2", 20)) })
        }))
        assertEquals(
            listOf("https://p/1", "https://p/2"),
            previewLocations(assets).map { (it as PreviewLocation.Direct).asset.downloadUrl },
        )
        val refs = record("B", fields = mapOf("PreviewImages" to buildJsonObject {
            put("type", "REFERENCE_LIST")
            put("value", buildJsonArray {
                add(buildJsonObject { put("recordName", "PREVIEW-1"); put("action", "NONE") })
            })
        }))
        assertEquals(listOf(PreviewLocation.Reference("PREVIEW-1")), previewLocations(refs))
        assertTrue(previewLocations(record("C", fields = emptyMap())).isEmpty())
    }

    // ----- decodeAttachment -----

    @Test
    fun imageUsesPreviewInlineAndMediaOriginalFull() = runTest {
        val media = record("MEDIA-1", tag = "mt", fields = mapOf("Asset" to assetField("https://media/orig", 5000)))
        val att = record("ATT-1", fields = mapOf(
            "UTI" to str("public.jpeg"),
            "Media" to ref("MEDIA-1"),
            "PreviewImages" to buildJsonObject {
                put("type", "ASSETID_LIST")
                put("value", buildJsonArray {
                    add(assetObj("https://p/small", 100, "s1"))
                    add(assetObj("https://p/large", 900, "s2"))
                })
            },
        ))
        val c = decodeAttachment(att) { rn -> media.takeIf { rn == "MEDIA-1" } } as AttachmentContent.Image
        assertEquals("https://p/large", c.inline.downloadUrl)
        assertEquals("https://media/orig", c.full.downloadUrl)
        // Preview keyed by its checksum; original (no checksum) by the media record's tag.
        assertEquals(AttachmentCacheKey("ATT-1", "preview", "sum:s2"), c.inline.key)
        assertEquals(AttachmentCacheKey("MEDIA-1", "original", "tag:mt"), c.full.key)
    }

    @Test
    fun imageWithoutPreviewShowsTheOriginalInline() = runTest {
        val media = record("MEDIA-1", fields = mapOf("MediaEncrypted" to assetField("https://media/orig")))
        val att = record("ATT-1", fields = mapOf("UTI" to str("public.heic"), "Media" to ref("MEDIA-1")))
        val c = decodeAttachment(att) { media } as AttachmentContent.Image
        assertNull(c.preview)
        assertEquals("https://media/orig", c.inline.downloadUrl)
    }

    @Test
    fun sketchWithPreviewIsGenericWithPreview() = runTest {
        val previewRec = record("PREVIEW-1", fields = mapOf("Asset" to assetField("https://p/sketch")))
        val att = record("ATT-2", fields = mapOf(
            "UTI" to str("com.apple.drawing.2"),
            "PreviewImages" to buildJsonObject {
                put("type", "REFERENCE_LIST")
                put("value", buildJsonArray { add(buildJsonObject { put("recordName", "PREVIEW-1") }) })
            },
        ))
        val c = decodeAttachment(att) { rn -> previewRec.takeIf { rn == "PREVIEW-1" } } as AttachmentContent.Generic
        assertEquals("com.apple.drawing.2", c.mime)
        assertEquals("https://p/sketch", c.preview?.downloadUrl)
        assertEquals("PREVIEW-1", c.preview?.key?.recordName)
    }

    @Test
    fun attachmentWithoutPreviewIsAPlainPlaceholder() = runTest {
        val att = record("ATT-3", fields = mapOf("UTI" to str("public.mp3")))
        val c = decodeAttachment(att) as AttachmentContent.Generic
        assertNull(c.preview)
        assertEquals("Audio", labelForAttachmentType(c.mime).second)
    }

    // ----- Sizing -----

    @Test
    fun inSampleSizeKeepsAtLeastTheRequestedSize() {
        assertEquals(1, computeInSampleSize(1000, 800, 1000, 800))
        assertEquals(1, computeInSampleSize(1000, 800, 600, 480))
        assertEquals(2, computeInSampleSize(4000, 3000, 1080, 810))
        // 12 MP phone photo at a 1080 px wide column → 1/2 (2000 px ≥ 1080, 1000 < 1080).
        assertEquals(2, computeInSampleSize(4032, 3024, 1080, 810))
        assertEquals(4, computeInSampleSize(8000, 6000, 1080, 810))
        assertEquals(1, computeInSampleSize(0, 0, 100, 100))
        // Pixel cap wins even if the requested size is huge.
        assertEquals(2, computeInSampleSize(6000, 4000, 6000, 4000, maxPixels = 12_000_000))
    }

    @Test
    fun fitWithinKeepsAspectAndNeverUpscales() {
        assertEquals(1080 to 810, fitWithin(4032, 3024, 1080, 4320))
        assertEquals(300 to 200, fitWithin(300, 200, 1080, 4320))
        // A very tall image is bounded by the height.
        assertEquals(250 to 4320, fitWithin(1000, 17280, 1080, 4320))
        val (w, h) = fitWithin(10_000, 10_000, 10_000, 10_000, maxPixels = 1_000_000)
        assertTrue(w.toLong() * h <= 1_000_000)
    }

    @Test
    fun bucketsRoundUp() {
        assertEquals(256, AttachmentImageLoader.bucket(1))
        assertEquals(1280, AttachmentImageLoader.bucket(1080))
        assertEquals(256, AttachmentImageLoader.bucket(0))
    }

    // ----- Loader: disk cache in front of the network -----

    private class MapStore : CacheStore {
        val files = mutableMapOf<String, ByteArray>()
        override fun read(name: String) = files[name]
        override fun write(name: String, bytes: ByteArray) { files[name] = bytes }
        override fun touch(name: String, timeMs: Long) = Unit
        override fun delete(name: String) { files.remove(name) }
        override fun list() = files.map { CacheStore.Entry(it.key, it.value.size.toLong(), 0) }
    }

    @Test
    fun loaderDownloadsOnceThenServesFromDisk() = runTest {
        val fetched = mutableListOf<String>()
        val store = MapStore()
        val loader = AttachmentImageLoader(
            diskCache = AttachmentCache(store),
            fetch = { url -> fetched += url; byteArrayOf(1, 2, 3) },
            io = Dispatchers.Unconfined,
            decode = Dispatchers.Unconfined,
            memoryCacheBytes = 1,
        )
        val source = ImageSource(AttachmentCacheKey("ATT", "preview", "v1"), "https://signed/1")
        assertArrayEquals(byteArrayOf(1, 2, 3), loader.bytes(source))
        // Same key, a fresh (re-signed) URL: no second download.
        assertArrayEquals(byteArrayOf(1, 2, 3), loader.bytes(source.copy(downloadUrl = "https://signed/2")))
        assertEquals(listOf("https://signed/1"), fetched)
        // A new version of the attachment downloads again.
        loader.bytes(ImageSource(AttachmentCacheKey("ATT", "preview", "v2"), "https://signed/3"))
        assertEquals(2, fetched.size)
    }

    @Test
    fun failedDownloadsAreNotCached() = runTest {
        var calls = 0
        val loader = AttachmentImageLoader(
            diskCache = AttachmentCache(MapStore()),
            fetch = { calls++; if (calls == 1) error("offline") else byteArrayOf(9) },
            io = Dispatchers.Unconfined,
            decode = Dispatchers.Unconfined,
            memoryCacheBytes = 1,
        )
        val source = ImageSource(AttachmentCacheKey("ATT", "original", "v1"), "https://x")
        assertNull(loader.bytes(source))
        assertNotNull(loader.bytes(source))
        assertEquals(2, calls)
    }
}
