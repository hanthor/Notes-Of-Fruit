package com.example.applenotes.data

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** In-memory [CacheStore] with explicit modification times. */
private class MemoryStore : CacheStore {
    val files = linkedMapOf<String, ByteArray>()
    val mtimes = mutableMapOf<String, Long>()
    var failWrites = false

    override fun read(name: String) = files[name]
    override fun write(name: String, bytes: ByteArray) {
        if (failWrites) error("disk full")
        files[name] = bytes
    }
    override fun touch(name: String, timeMs: Long) {
        if (name in files) mtimes[name] = timeMs
    }
    override fun delete(name: String) {
        files.remove(name)
        mtimes.remove(name)
    }
    override fun list() = files.map { (n, b) -> CacheStore.Entry(n, b.size.toLong(), mtimes[n] ?: 0L) }
}

class AttachmentCacheTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_000L
    private fun key(rn: String, version: String = "v1", variant: String = "preview") =
        AttachmentCacheKey(rn, variant, version)

    private fun bytes(n: Int, fill: Int = 1) = ByteArray(n) { fill.toByte() }

    @Test
    fun putThenGetReturnsTheBytes() {
        val cache = AttachmentCache(MemoryStore(), maxBytes = 100, clock = { now })
        cache.put(key("A"), bytes(10, 7))
        assertArrayEquals(bytes(10, 7), cache.get(key("A")))
        assertNull(cache.get(key("B")))
    }

    @Test
    fun keyIncludesVersionAndVariant() {
        val cache = AttachmentCache(MemoryStore(), maxBytes = 100, clock = { now })
        cache.put(key("A", version = "tag-1"), bytes(10, 1))
        // A new recordChangeTag / checksum is a miss: the image changed.
        assertNull(cache.get(key("A", version = "tag-2")))
        assertNull(cache.get(key("A", version = "tag-1", variant = "original")))
        assertNotEquals(key("A", "1").fileName, key("A", "2").fileName)
    }

    @Test
    fun fileNamesAreSafe() {
        val k = AttachmentCacheKey("../../etc/passwd", "preview", "sum:a/b+c==")
        assertTrue(k.fileName.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun evictsLeastRecentlyUsedOverTheCap() {
        val store = MemoryStore()
        val cache = AttachmentCache(store, maxBytes = 30, clock = { now })
        cache.put(key("A"), bytes(10)); now += 10
        cache.put(key("B"), bytes(10)); now += 10
        cache.put(key("C"), bytes(10)); now += 10
        // Reading A makes it recently used; B is now the oldest.
        cache.get(key("A")); now += 10
        cache.put(key("D"), bytes(10))
        assertNull(cache.get(key("B")))
        assertTrue(cache.get(key("A")) != null)
        assertTrue(cache.get(key("C")) != null)
        assertTrue(cache.get(key("D")) != null)
        assertEquals(30L, cache.sizeBytes())
    }

    @Test
    fun aNewEntryIsNeverEvictedByItsOwnTrim() {
        val cache = AttachmentCache(MemoryStore(), maxBytes = 30, clock = { now })
        cache.put(key("A"), bytes(20)); now += 10
        cache.put(key("B"), bytes(25))
        assertNull(cache.get(key("A")))
        assertTrue(cache.get(key("B")) != null)
    }

    @Test
    fun entriesLargerThanTheCapAreNotStored() {
        val store = MemoryStore()
        val cache = AttachmentCache(store, maxBytes = 30, clock = { now })
        cache.put(key("A"), bytes(10))
        cache.put(key("HUGE"), bytes(31))
        assertNull(cache.get(key("HUGE")))
        assertTrue(cache.get(key("A")) != null)
    }

    @Test
    fun writeFailuresAreSwallowed() {
        val store = MemoryStore().apply { failWrites = true }
        val cache = AttachmentCache(store, maxBytes = 30, clock = { now })
        cache.put(key("A"), bytes(10))
        assertNull(cache.get(key("A")))
    }

    @Test
    fun fileStoreRoundTripsAndEvictsByMtime() {
        val dir = File(tmp.root, "attachments")
        val cache = AttachmentCache(FileCacheStore(dir), maxBytes = 25, clock = { now })
        cache.put(key("A"), bytes(10, 1)); now += 10_000
        cache.put(key("B"), bytes(10, 2)); now += 10_000
        assertArrayEquals(bytes(10, 1), cache.get(key("A"))); now += 10_000
        cache.put(key("C"), bytes(10, 3))
        // B was the least recently used file.
        assertNull(cache.get(key("B")))
        assertArrayEquals(bytes(10, 1), cache.get(key("A")))
        assertArrayEquals(bytes(10, 3), cache.get(key("C")))
        assertFalse(dir.listFiles()!!.any { it.name.endsWith(".tmp") })
        cache.clear()
        assertEquals(0L, cache.sizeBytes())
    }

    @Test
    fun sizedLruEvictsEldestAccessed() {
        val lru = SizedLruCache<String, ByteArray>(maxSize = 20) { it.size.toLong() }
        lru.put("a", ByteArray(10))
        lru.put("b", ByteArray(10))
        lru.get("a")
        lru.put("c", ByteArray(10))
        assertNull(lru.get("b"))
        assertEquals(listOf("a", "c"), lru.keys())
        assertEquals(20L, lru.currentSize())
        // Too big to hold at all.
        lru.put("d", ByteArray(21))
        assertNull(lru.get("d"))
        // Replacing a key doesn't double-count it.
        lru.put("a", ByteArray(5))
        assertEquals(15L, lru.currentSize())
    }
}
