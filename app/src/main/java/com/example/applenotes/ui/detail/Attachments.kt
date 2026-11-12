package com.example.applenotes.ui.detail

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.applenotes.client.NoteRecord
import com.example.applenotes.data.AssetRef
import com.example.applenotes.data.ImageSource
import com.example.applenotes.data.PreviewLocation
import com.example.applenotes.data.assetCacheKey
import com.example.applenotes.data.bestPreview
import com.example.applenotes.data.firstAsset
import com.example.applenotes.data.parseAsset
import com.example.applenotes.data.previewLocations
import com.example.applenotes.proto.NoteBodyEditor

/** Holds decoded payload for an attachment record (table grid, image sources, etc). */
sealed class AttachmentContent {
    data class Table(val grid: com.example.applenotes.proto.MergeableDataDecoder.TableGrid) : AttachmentContent()

    /**
     * An image. Nothing is downloaded yet: [preview] (Apple's `PreviewImages`
     * thumbnail, shown inline) and [original] (the full image behind the
     * `Media` reference, shown on tap and used for export) are fetched on
     * demand through [AttachmentImageLoader] and cached on disk. At least one
     * of them is non-null.
     */
    data class Image(
        val preview: ImageSource?,
        val original: ImageSource?,
        val mimeHint: String?,
    ) : AttachmentContent() {
        init {
            require(preview != null || original != null) { "an image needs a preview or an original" }
        }

        /** What to show inline: the preview when there is one. */
        val inline: ImageSource get() = preview ?: original!!

        /** What to show full screen / export: the original when there is one. */
        val full: ImageSource get() = original ?: preview!!
    }

    /** Sketches, scans, audio, maps, files…: a labelled card, with Apple's preview image when there is one. */
    data class Generic(
        val title: String?,
        val mime: String?,
        val preview: ImageSource? = null,
    ) : AttachmentContent()
}

private fun decodeB64Field(rec: NoteRecord, field: String): String? = rec.stringField(field)?.let {
    runCatching { kotlin.io.encoding.Base64.decode(it).decodeToString() }.getOrNull()
}

/** How many separate preview records to look up per attachment, at most. */
private const val MAX_PREVIEW_LOOKUPS = 3

/**
 * Turn an Attachment record into something to render. Follows references
 * (the Media record, preview records) through [fetchRecord], but downloads no
 * image bytes: that happens when the image is shown (see [AttachmentImage]).
 */
internal suspend fun decodeAttachment(
    rec: NoteRecord,
    fetchRecord: suspend (String) -> NoteRecord? = { null },
): AttachmentContent? {
    val uti = decodeB64Field(rec, "UTI") ?: decodeB64Field(rec, "UTIEncrypted")
    val title = decodeB64Field(rec, "TitleEncrypted")
    val mergB64 = rec.stringField("MergeableDataEncrypted")
    if (mergB64 != null && (uti?.contains("table", ignoreCase = true) == true || title?.equals("Table", ignoreCase = true) == true)) {
        val grid = com.example.applenotes.proto.MergeableDataDecoder.decodeTableBase64(mergB64)
        if (grid != null) return AttachmentContent.Table(grid)
    }
    val preview = resolvePreview(rec, fetchRecord)
    if (isImageUti(uti)) {
        // The Attachment record's "Media" is a CKReference to a Media record
        // which actually carries the binary as a CKAsset. Follow the reference.
        val mediaField = rec.rawFields["Media"]
        val mediaRefName = mediaField?.let { com.example.applenotes.client.extractReferenceRecordName(it) }
        val original = mediaRefName?.let { fetchRecord(it) }?.let { mediaRec ->
            firstAsset(mediaRec)?.let { asset ->
                ImageSource(assetCacheKey(mediaRec.recordName, "original", asset, mediaRec.recordChangeTag), asset.downloadUrl)
            }
        } ?: parseAsset(mediaField)?.let { asset ->
            // Direct asset on this record (rare, but check).
            ImageSource(assetCacheKey(rec.recordName, "original", asset, rec.recordChangeTag), asset.downloadUrl)
        }
        if (original != null || preview != null) return AttachmentContent.Image(preview, original, uti)
    }
    return AttachmentContent.Generic(title, uti, preview)
}

private fun isImageUti(uti: String?): Boolean = uti?.let {
    it.startsWith("public.png", ignoreCase = true) ||
        it.startsWith("public.jpeg", ignoreCase = true) ||
        it.startsWith("public.heic", ignoreCase = true) ||
        it.startsWith("public.image", ignoreCase = true) ||
        it.contains("gallery", ignoreCase = true)
} == true

/** The sharpest of the record's `PreviewImages` (see [previewLocations]), or null. */
private suspend fun resolvePreview(
    rec: NoteRecord,
    fetchRecord: suspend (String) -> NoteRecord?,
): ImageSource? {
    val candidates = mutableListOf<Pair<AssetRef, ImageSource>>()
    var lookups = 0
    for (loc in previewLocations(rec)) {
        when (loc) {
            is PreviewLocation.Direct -> candidates += loc.asset to
                ImageSource(assetCacheKey(rec.recordName, "preview", loc.asset, rec.recordChangeTag), loc.asset.downloadUrl)
            is PreviewLocation.Reference -> {
                if (lookups++ >= MAX_PREVIEW_LOOKUPS) continue
                val pr = fetchRecord(loc.recordName) ?: continue
                val asset = firstAsset(pr) ?: continue
                candidates += asset to
                    ImageSource(assetCacheKey(pr.recordName, "preview", asset, pr.recordChangeTag), asset.downloadUrl)
            }
        }
    }
    val best = bestPreview(candidates.map { it.first }) ?: return null
    return candidates.first { it.first === best }.second
}

@Composable
internal fun AttachmentBlock(span: NoteBodyEditor.AttrSpan, content: AttachmentContent?) {
    when (content) {
        is AttachmentContent.Table -> TableGridView(content.grid)
        is AttachmentContent.Image -> ImageAttachment(content)
        is AttachmentContent.Generic -> {
            val uti = span.attachmentType ?: content.mime
            val preview = content.preview
            if (preview != null) PreviewAttachment(preview, uti) else AttachmentPlaceholder(uti)
        }
        null -> AttachmentPlaceholder(span.attachmentType)
    }
}

@Composable
private fun TableGridView(grid: com.example.applenotes.proto.MergeableDataDecoder.TableGrid) {
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val headerBg = MaterialTheme.colorScheme.surfaceVariant
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .background(
                color = MaterialTheme.colorScheme.surface,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            )
            .androidxBorder(
                borderColor = borderColor,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            ),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            for ((rIdx, row) in grid.cells.withIndex()) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    for ((cIdx, cell) in row.withIndex()) {
                        val cellModifier = Modifier
                            .weight(1f)
                            .background(if (rIdx == 0) headerBg else MaterialTheme.colorScheme.surface)
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                        Text(
                            text = cell,
                            style = if (rIdx == 0)
                                MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                            else
                                MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = cellModifier,
                        )
                        if (cIdx < row.size - 1) {
                            androidx.compose.foundation.layout.Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .fillMaxHeight()
                                    .background(borderColor),
                            )
                        }
                    }
                }
                if (rIdx < grid.cells.size - 1) {
                    HorizontalDivider(color = borderColor)
                }
            }
        }
    }
}

private fun Modifier.androidxBorder(
    borderColor: Color,
    shape: androidx.compose.ui.graphics.Shape,
): Modifier = this.border(
    width = 1.dp,
    color = borderColor,
    shape = shape,
)

/** An image attachment: preview inline, the original full screen on tap. */
@Composable
private fun ImageAttachment(content: AttachmentContent.Image) {
    var viewerOpen by rememberSaveable { mutableStateOf(false) }
    AttachmentImage(
        source = content.inline,
        contentDescription = "Image attachment. Tap to view full size.",
        uti = content.mimeHint,
        onClick = { viewerOpen = true },
    )
    if (viewerOpen) ImageViewerDialog(content.full, onDismiss = { viewerOpen = false })
}

/** A non-image attachment (sketch, scan, map, PDF…) shown as Apple's preview image plus its type. */
@Composable
private fun PreviewAttachment(preview: ImageSource, uti: String?) {
    val (icon, label) = labelForAttachmentType(uti)
    var viewerOpen by rememberSaveable { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        AttachmentImage(
            source = preview,
            contentDescription = "$label preview. Tap to enlarge.",
            uti = uti,
            onClick = { viewerOpen = true },
            verticalPadding = 0,
        )
        Text(
            "$icon $label",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    if (viewerOpen) ImageViewerDialog(preview, onDismiss = { viewerOpen = false })
}

/**
 * One attachment image, decoded off the main thread at the width it's laid
 * out at (issue #15). Shows a spinner while loading and a placeholder card
 * when the image can't be shown.
 */
@Composable
internal fun AttachmentImage(
    source: ImageSource,
    contentDescription: String,
    uti: String?,
    onClick: (() -> Unit)?,
    verticalPadding: Int = 6,
) {
    val context = LocalContext.current
    val loader = remember(context) { AttachmentImageLoader.get(context) }
    val windowWidth = LocalWindowInfo.current.containerSize.width
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(vertical = verticalPadding.dp)) {
        val widthPx = constraints.maxWidth
            .takeIf { it != Constraints.Infinity && it > 0 }
            ?: windowWidth.coerceAtLeast(1)
        val state by produceState<DecodedImage?>(initialValue = null, source, widthPx) {
            // Tall images (receipts, screenshots) may be up to 4 widths high.
            value = loader.load(source, maxWidth = widthPx, maxHeight = widthPx * 4)
        }
        when (val s = state) {
            null -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            is DecodedImage.Ok -> {
                val bitmap = remember(s.bitmap) { s.bitmap.asImageBitmap() }
                Image(
                    bitmap = bitmap,
                    contentDescription = contentDescription,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
                )
            }
            DecodedImage.Unsupported -> AttachmentPlaceholder(uti, note = "Can't show this image on this Android version")
            DecodedImage.Failed -> AttachmentPlaceholder(uti, note = "Couldn't load this image")
        }
    }
}

/** Full-screen viewer: pinch to zoom, drag to pan, double-tap to reset. */
@Composable
private fun ImageViewerDialog(source: ImageSource, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val context = LocalContext.current
        val loader = remember(context) { AttachmentImageLoader.get(context) }
        val window = LocalWindowInfo.current.containerSize
        // Decode at up to twice the screen size so zooming in stays sharp,
        // capped to keep the bitmap a sane size.
        val maxDim = (maxOf(window.width, window.height) * 2).coerceIn(1, 4096)
        val state by produceState<DecodedImage?>(initialValue = null, source, maxDim) {
            value = loader.load(source, maxWidth = maxDim, maxHeight = maxDim)
        }
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            when (val s = state) {
                null -> CircularProgressIndicator()
                is DecodedImage.Ok -> {
                    val bitmap = remember(s.bitmap) { s.bitmap.asImageBitmap() }
                    Image(
                        bitmap = bitmap,
                        contentDescription = "Image attachment, full size",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    scale = (scale * zoom).coerceIn(1f, 6f)
                                    offset = if (scale == 1f) Offset.Zero else offset + pan
                                }
                            }
                            .pointerInput(Unit) {
                                detectTapGestures(onDoubleTap = {
                                    scale = 1f
                                    offset = Offset.Zero
                                })
                            }
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offset.x,
                                translationY = offset.y,
                            ),
                    )
                }
                DecodedImage.Unsupported -> Text(
                    "Can't show this image on this Android version",
                    color = Color.White,
                    modifier = Modifier.padding(24.dp),
                )
                DecodedImage.Failed -> Text(
                    "Couldn't load this image",
                    color = Color.White,
                    modifier = Modifier.padding(24.dp),
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp),
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
            }
        }
    }
}

@Composable
internal fun AttachmentPlaceholder(uti: String?, note: String? = null) {
    val (icon, label) = labelForAttachmentType(uti)
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 12.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                icon,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(end = 10.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    note ?: uti ?: "attachment",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (note != null) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

internal fun labelForAttachmentType(uti: String?): Pair<String, String> = when {
    uti == null -> "📎" to "Attachment"
    uti.contains("table", ignoreCase = true) -> "📋" to "Table"
    uti.contains("gallery", ignoreCase = true) ||
        uti.contains("image", ignoreCase = true) ||
        uti.startsWith("public.png", ignoreCase = true) ||
        uti.startsWith("public.jpeg", ignoreCase = true) ||
        uti.startsWith("public.heic", ignoreCase = true) -> "🖼" to "Image"
    uti.contains("sketch", ignoreCase = true) ||
        uti.contains("drawing", ignoreCase = true) ||
        uti.contains("paper", ignoreCase = true) -> "✏️" to "Drawing"
    uti.contains("scan", ignoreCase = true) -> "📄" to "Scanned document"
    uti.contains("map", ignoreCase = true) -> "🗺" to "Map"
    uti.startsWith("public.url", ignoreCase = true) ||
        uti.contains("link", ignoreCase = true) -> "🔗" to "Link preview"
    uti.contains("audio", ignoreCase = true) ||
        uti.contains("mp3", ignoreCase = true) ||
        uti.contains("m4a", ignoreCase = true) -> "🎵" to "Audio"
    uti.contains("video", ignoreCase = true) || uti.contains("movie", ignoreCase = true) -> "🎞" to "Video"
    uti.contains("pdf", ignoreCase = true) -> "📄" to "PDF"
    else -> "📎" to "File"
}
