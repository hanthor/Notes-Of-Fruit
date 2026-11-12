package com.example.applenotes.ui.detail

import android.content.Context
import android.graphics.Bitmap
import com.example.applenotes.data.AppGraph
import com.example.applenotes.data.AttachmentCache
import com.example.applenotes.data.FileCacheStore
import com.example.applenotes.data.ImageSource
import com.example.applenotes.data.SizedLruCache
import com.example.applenotes.data.runCatchingCancellable
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Loads attachment images for display (issue #15): memory cache of decoded
 * bitmaps → disk cache of downloaded bytes ([AttachmentCache]) → download.
 * Disk work runs on [io], decoding on [decode]; nothing here touches the
 * main thread.
 *
 * One per process ([get]), so the caches survive leaving and reopening a note.
 */
internal class AttachmentImageLoader(
    private val diskCache: AttachmentCache,
    private val fetch: suspend (downloadUrl: String) -> ByteArray?,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val decode: CoroutineDispatcher = Dispatchers.Default,
    memoryCacheBytes: Long = Runtime.getRuntime().maxMemory() / 8,
) {
    private val bitmaps = SizedLruCache<String, Bitmap>(memoryCacheBytes) { it.allocationByteCount.toLong() }

    /** One download per key at a time (an inline preview and the viewer may ask together). */
    private val locks = HashMap<String, Mutex>()

    private fun lockFor(name: String): Mutex = synchronized(locks) { locks.getOrPut(name) { Mutex() } }

    /** The bytes behind [source], from the disk cache or downloaded (and then cached). */
    suspend fun bytes(source: ImageSource): ByteArray? = withContext(io) {
        lockFor(source.key.fileName).withLock {
            diskCache.get(source.key)?.let { return@withLock it }
            val downloaded = runCatchingCancellable { fetch(source.downloadUrl) }.getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?: return@withLock null
            diskCache.put(source.key, downloaded)
            downloaded
        }
    }

    /** [source] decoded to fit [maxWidth] × [maxHeight] pixels. */
    suspend fun load(source: ImageSource, maxWidth: Int, maxHeight: Int): DecodedImage {
        // Bucket the requested width so small layout differences share a bitmap.
        val w = bucket(maxWidth)
        val h = bucket(maxHeight)
        val memKey = "${source.key.fileName}@${w}x$h"
        bitmaps.get(memKey)?.let { return DecodedImage.Ok(it) }
        val data = bytes(source) ?: return DecodedImage.Failed
        val decoded = withContext(decode) { decodeImage(data, w, h) }
        if (decoded is DecodedImage.Ok) bitmaps.put(memKey, decoded.bitmap)
        return decoded
    }

    companion object {
        private const val BUCKET = 256

        internal fun bucket(px: Int): Int = ((px.coerceAtLeast(1) + BUCKET - 1) / BUCKET) * BUCKET

        @Volatile
        private var instance: AttachmentImageLoader? = null

        fun get(context: Context): AttachmentImageLoader =
            instance ?: synchronized(this) {
                instance ?: create(context.applicationContext).also { instance = it }
            }

        private fun create(app: Context): AttachmentImageLoader {
            val repository = AppGraph.notesRepository(app)
            return AttachmentImageLoader(
                diskCache = AttachmentCache(FileCacheStore(File(app.cacheDir, "attachments"))),
                fetch = { url -> repository.fetchAssetBytes(url) },
            )
        }
    }
}
