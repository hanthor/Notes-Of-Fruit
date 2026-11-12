package com.example.applenotes.data

import java.io.File
import java.security.MessageDigest

/**
 * Identifies one downloaded attachment binary (issue #15).
 *
 * CloudKit's asset download URLs are signed and expire, so they can't be the
 * key. Instead: the record that owns the asset, which [variant] of it
 * (inline preview vs. the original), and a [version] that changes whenever
 * the bytes can change — the asset's `fileChecksum` when the CKAsset JSON
 * carries one, otherwise the owning record's `recordChangeTag`.
 */
data class AttachmentCacheKey(
    val recordName: String,
    val variant: String,
    val version: String,
) {
    /** File-system-safe, fixed-length name for this key. */
    val fileName: String by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$recordName\u0000$variant\u0000$version".toByteArray())
        digest.joinToString("") { "%02x".format(it) }
    }
}

/**
 * The file layer under [AttachmentCache], separated so the cache's policy
 * (LRU by modification time, size cap) can be unit-tested without a disk.
 */
interface CacheStore {
    data class Entry(val name: String, val size: Long, val lastModified: Long)

    fun read(name: String): ByteArray?
    fun write(name: String, bytes: ByteArray)

    /** Mark [name] as recently used. */
    fun touch(name: String, timeMs: Long)
    fun delete(name: String)
    fun list(): List<Entry>
}

/** [CacheStore] in a directory (in the app, `cacheDir/attachments`). */
class FileCacheStore(private val dir: File) : CacheStore {
    private fun file(name: String) = File(dir, name)

    override fun read(name: String): ByteArray? =
        file(name).takeIf { it.isFile }?.let { runCatching { it.readBytes() }.getOrNull() }

    override fun write(name: String, bytes: ByteArray) {
        dir.mkdirs()
        // Write to a temp file and rename, so a crash or a concurrent reader
        // never sees half a file.
        val tmp = File(dir, "$name.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file(name))) {
            file(name).delete()
            if (!tmp.renameTo(file(name))) {
                tmp.delete()
                error("couldn't move cache file into place")
            }
        }
    }

    override fun touch(name: String, timeMs: Long) {
        file(name).setLastModified(timeMs)
    }

    override fun delete(name: String) {
        file(name).delete()
    }

    override fun list(): List<CacheStore.Entry> =
        dir.listFiles().orEmpty()
            .filter { it.isFile && !it.name.endsWith(".tmp") }
            .map { CacheStore.Entry(it.name, it.length(), it.lastModified()) }
}

/**
 * Disk cache for attachment binaries (images and their previews), so opening
 * a note doesn't download every image again.
 *
 * Bounded at [maxBytes]: after each write the least recently used entries
 * (oldest modification time; reads [CacheStore.touch] an entry) are deleted
 * until the total fits. Lives in the app's cache directory, so Android may
 * also clear it under storage pressure; that only costs a re-download.
 *
 * Thread-safe (one lock); intended to be called from an IO dispatcher.
 */
class AttachmentCache(
    private val store: CacheStore,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()

    fun get(key: AttachmentCacheKey): ByteArray? = synchronized(lock) {
        val bytes = store.read(key.fileName) ?: return null
        store.touch(key.fileName, clock())
        bytes
    }

    fun put(key: AttachmentCacheKey, bytes: ByteArray) = synchronized(lock) {
        // A single file larger than the whole cache isn't kept.
        if (bytes.size > maxBytes) return
        runCatching { store.write(key.fileName, bytes) }.onFailure { return }
        store.touch(key.fileName, clock())
        trim(keep = key.fileName)
    }

    /** Total bytes currently stored. */
    fun sizeBytes(): Long = synchronized(lock) { store.list().sumOf { it.size } }

    fun clear() = synchronized(lock) { store.list().forEach { store.delete(it.name) } }

    private fun trim(keep: String) {
        val entries = store.list()
        var total = entries.sumOf { it.size }
        if (total <= maxBytes) return
        // Oldest first; never evict the entry that was just written.
        for (e in entries.sortedBy { it.lastModified }) {
            if (total <= maxBytes) break
            if (e.name == keep) continue
            store.delete(e.name)
            total -= e.size
        }
    }

    companion object {
        const val DEFAULT_MAX_BYTES: Long = 100L * 1024 * 1024
    }
}

/**
 * A small size-bounded LRU map (used for decoded bitmaps). Plain
 * `LinkedHashMap` in access order, so it runs in JVM unit tests where
 * `android.util.LruCache` is a stub.
 */
class SizedLruCache<K : Any, V : Any>(
    private val maxSize: Long,
    private val sizeOf: (V) -> Long,
) {
    private val map = LinkedHashMap<K, V>(16, 0.75f, true)
    private var size = 0L

    @Synchronized
    fun get(key: K): V? = map[key]

    @Synchronized
    fun put(key: K, value: V) {
        val itemSize = sizeOf(value)
        map.remove(key)?.let { size -= sizeOf(it) }
        if (itemSize > maxSize) return
        map[key] = value
        size += itemSize
        val it = map.entries.iterator()
        while (size > maxSize && it.hasNext()) {
            val eldest = it.next()
            if (eldest.key == key) continue
            size -= sizeOf(eldest.value)
            it.remove()
        }
    }

    @Synchronized
    fun currentSize(): Long = size

    @Synchronized
    fun keys(): List<K> = map.keys.toList()
}
