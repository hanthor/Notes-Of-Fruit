package com.example.applenotes.ui.detail

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import androidx.annotation.RequiresApi
import com.example.applenotes.export.imageExtension
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/** Result of decoding an attachment image. */
internal sealed interface DecodedImage {
    data class Ok(val bitmap: Bitmap) : DecodedImage

    /** A format this Android version can't decode (HEIC before Android 9). */
    data object Unsupported : DecodedImage

    /** Corrupt bytes, unknown format, out of memory, or the download failed. */
    data object Failed : DecodedImage
}

/** Never decode more than this many pixels, whatever the requested size (~48 MB at ARGB_8888). */
internal const val MAX_DECODE_PIXELS = 12_000_000L

/**
 * The largest power-of-two `inSampleSize` that keeps the decoded image at
 * least [reqWidth] × [reqHeight] (so it's never blurrier than needed), then
 * doubled further while it would exceed [maxPixels].
 */
internal fun computeInSampleSize(
    srcWidth: Int,
    srcHeight: Int,
    reqWidth: Int,
    reqHeight: Int,
    maxPixels: Long = MAX_DECODE_PIXELS,
): Int {
    if (srcWidth <= 0 || srcHeight <= 0) return 1
    val rw = reqWidth.coerceAtLeast(1)
    val rh = reqHeight.coerceAtLeast(1)
    var sample = 1
    while (srcWidth / (sample * 2) >= rw && srcHeight / (sample * 2) >= rh) sample *= 2
    while ((srcWidth.toLong() / sample) * (srcHeight.toLong() / sample) > maxPixels) sample *= 2
    return sample
}

/**
 * Size to decode a [srcWidth] × [srcHeight] image to so it fits within
 * [maxWidth] × [maxHeight] (and [maxPixels]), keeping the aspect ratio.
 * Never scales up.
 */
internal fun fitWithin(
    srcWidth: Int,
    srcHeight: Int,
    maxWidth: Int,
    maxHeight: Int,
    maxPixels: Long = MAX_DECODE_PIXELS,
): Pair<Int, Int> {
    if (srcWidth <= 0 || srcHeight <= 0) return srcWidth to srcHeight
    var scale = minOf(1.0, maxWidth.coerceAtLeast(1).toDouble() / srcWidth, maxHeight.coerceAtLeast(1).toDouble() / srcHeight)
    val pixels = srcWidth.toDouble() * srcHeight * scale * scale
    if (pixels > maxPixels) scale *= kotlin.math.sqrt(maxPixels / pixels)
    val w = (srcWidth * scale).roundToInt().coerceAtLeast(1)
    val h = (srcHeight * scale).roundToInt().coerceAtLeast(1)
    return w to h
}

/**
 * Decode [bytes] down-sampled to fit [maxWidth] × [maxHeight]. CPU-bound:
 * call it on `Dispatchers.Default`, never on the main thread.
 *
 * Android 9+ uses [ImageDecoder] (decodes HEIC/HEIF where the device has the
 * codec, and scales while decoding). Older versions use [BitmapFactory] with
 * a bounds pass and `inSampleSize`; it can't decode HEIC, which is reported
 * as [DecodedImage.Unsupported] so the UI can say so instead of showing a
 * generic error.
 */
internal fun decodeImage(bytes: ByteArray, maxWidth: Int, maxHeight: Int): DecodedImage = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        decodeWithImageDecoder(bytes, maxWidth, maxHeight)
    } else {
        decodeWithBitmapFactory(bytes, maxWidth, maxHeight)
    }
} catch (_: OutOfMemoryError) {
    DecodedImage.Failed
} catch (_: Exception) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P && imageExtension(bytes) == "heic") {
        DecodedImage.Unsupported
    } else {
        DecodedImage.Failed
    }
}

@RequiresApi(Build.VERSION_CODES.P)
private fun decodeWithImageDecoder(bytes: ByteArray, maxWidth: Int, maxHeight: Int): DecodedImage {
    val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
    val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        val (w, h) = fitWithin(info.size.width, info.size.height, maxWidth, maxHeight)
        if (w != info.size.width || h != info.size.height) decoder.setTargetSize(w, h)
    }
    return DecodedImage.Ok(bitmap)
}

private fun decodeWithBitmapFactory(bytes: ByteArray, maxWidth: Int, maxHeight: Int): DecodedImage {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        return if (imageExtension(bytes) == "heic") DecodedImage.Unsupported else DecodedImage.Failed
    }
    val (w, h) = fitWithin(bounds.outWidth, bounds.outHeight, maxWidth, maxHeight)
    val opts = BitmapFactory.Options().apply {
        inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, w, h)
    }
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return DecodedImage.Failed
    return DecodedImage.Ok(bitmap)
}
