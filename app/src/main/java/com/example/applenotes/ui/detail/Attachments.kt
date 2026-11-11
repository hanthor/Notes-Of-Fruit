package com.example.applenotes.ui.detail

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.applenotes.client.NoteRecord
import com.example.applenotes.proto.NoteBodyEditor

/** Holds decoded payload for an attachment record (table grid, image bytes, etc). */
sealed class AttachmentContent {
    data class Table(val grid: com.example.applenotes.proto.MergeableDataDecoder.TableGrid) : AttachmentContent()
    data class Image(val bytes: ByteArray, val mimeHint: String?) : AttachmentContent()
    data class Generic(val title: String?, val mime: String?) : AttachmentContent()
}

internal suspend fun decodeAttachment(
    rec: NoteRecord,
    fetchAssetBytes: suspend (String) -> ByteArray?,
    fetchRecord: suspend (String) -> NoteRecord? = { null },
): AttachmentContent? {
    val uti = rec.stringField("UTI")?.let {
        runCatching { kotlin.io.encoding.Base64.decode(it).decodeToString() }.getOrNull()
    } ?: rec.stringField("UTIEncrypted")?.let {
        runCatching { kotlin.io.encoding.Base64.decode(it).decodeToString() }.getOrNull()
    }
    val title = rec.stringField("TitleEncrypted")?.let {
        runCatching { kotlin.io.encoding.Base64.decode(it).decodeToString() }.getOrNull()
    }
    val mergB64 = rec.stringField("MergeableDataEncrypted")
    if (mergB64 != null && (uti?.contains("table", ignoreCase = true) == true || title?.equals("Table", ignoreCase = true) == true)) {
        val grid = com.example.applenotes.proto.MergeableDataDecoder.decodeTableBase64(mergB64)
        if (grid != null) return AttachmentContent.Table(grid)
    }
    // Image attachment — fetch the Media CKAsset.
    val isImage = uti?.let {
        it.startsWith("public.png", ignoreCase = true) ||
            it.startsWith("public.jpeg", ignoreCase = true) ||
            it.startsWith("public.heic", ignoreCase = true) ||
            it.startsWith("public.image", ignoreCase = true) ||
            it.contains("gallery", ignoreCase = true)
    } == true
    if (isImage) {
        // The Attachment record's "Media" is a CKReference to a Media record
        // which actually carries the binary as a CKAsset. Follow the reference.
        val mediaField = rec.rawFields["Media"]
        val mediaRefName = mediaField?.let {
            com.example.applenotes.client.extractReferenceRecordName(it)
        }
        android.util.Log.i("AppleNotesUI", "Image attachment ${rec.recordName}: mediaRef=$mediaRefName uti=$uti")
        if (mediaRefName != null) {
            val mediaRec = fetchRecord(mediaRefName)
            // Media record's asset typically lives in field "MediaEncrypted" or "Media".
            val candidateFields = listOf("MediaEncrypted", "Media", "Asset", "MediaURL")
            for (fname in candidateFields) {
                val candidate = mediaRec?.rawFields?.get(fname) ?: continue
                val downloadUrl = com.example.applenotes.client.extractAssetDownloadUrl(candidate)
                if (downloadUrl != null) {
                    val bytes = fetchAssetBytes(downloadUrl)
                    if (bytes != null) return AttachmentContent.Image(bytes, uti)
                }
            }
        }
        // Direct asset URL on this record (rare, but check).
        val direct = mediaField?.let { com.example.applenotes.client.extractAssetDownloadUrl(it) }
        if (direct != null) {
            val bytes = fetchAssetBytes(direct)
            if (bytes != null) return AttachmentContent.Image(bytes, uti)
        }
        // Fall back to the first preview if Media isn't downloadable.
        val previews = rec.rawFields["PreviewImages"] as? kotlinx.serialization.json.JsonObject
        val firstPreviewUrl = previews?.get("value")?.let {
            (it as? kotlinx.serialization.json.JsonArray)?.firstOrNull()
                ?.let { ele -> com.example.applenotes.client.extractAssetDownloadUrl(ele) }
        }
        if (firstPreviewUrl != null) {
            val bytes = fetchAssetBytes(firstPreviewUrl)
            if (bytes != null) return AttachmentContent.Image(bytes, uti)
        }
    }
    return AttachmentContent.Generic(title, uti)
}

@Composable
internal fun AttachmentBlock(span: NoteBodyEditor.AttrSpan, content: AttachmentContent?) {
    when (content) {
        is AttachmentContent.Table -> TableGridView(content.grid)
        is AttachmentContent.Image -> ImageView(content)
        else -> AttachmentPlaceholder(span)
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

@Composable
private fun ImageView(content: AttachmentContent.Image) {
    val bitmap = remember(content.bytes) {
        runCatching {
            android.graphics.BitmapFactory.decodeByteArray(content.bytes, 0, content.bytes.size)
        }.getOrNull()
    }
    if (bitmap != null) {
        androidx.compose.foundation.Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Image attachment",
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                ),
        )
    } else {
        AttachmentPlaceholder(NoteBodyEditor.AttrSpan(
            length = 1,
            style = NoteBodyEditor.ParagraphStyle.BODY,
            isAttachment = true,
            attachmentType = content.mimeHint,
        ))
    }
}

@Composable
private fun AttachmentPlaceholder(span: NoteBodyEditor.AttrSpan) {
    val (icon, label) = labelForAttachmentType(span.attachmentType)
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
                val subtitle = span.attachmentType ?: "attachment"
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun labelForAttachmentType(uti: String?): Pair<String, String> = when {
    uti == null -> "📎" to "Attachment"
    uti.contains("table", ignoreCase = true) -> "📋" to "Table"
    uti.contains("gallery", ignoreCase = true) ||
        uti.contains("image", ignoreCase = true) ||
        uti.startsWith("public.png", ignoreCase = true) ||
        uti.startsWith("public.jpeg", ignoreCase = true) ||
        uti.startsWith("public.heic", ignoreCase = true) -> "🖼" to "Image"
    uti.contains("sketch", ignoreCase = true) ||
        uti.contains("drawing", ignoreCase = true) -> "✏️" to "Drawing"
    uti.contains("map", ignoreCase = true) -> "🗺" to "Map"
    uti.startsWith("public.url", ignoreCase = true) ||
        uti.contains("link", ignoreCase = true) -> "🔗" to "Link preview"
    uti.contains("audio", ignoreCase = true) -> "🎵" to "Audio"
    uti.contains("video", ignoreCase = true) -> "🎞" to "Video"
    uti.contains("pdf", ignoreCase = true) -> "📄" to "PDF"
    else -> "📎" to "Attachment"
}
