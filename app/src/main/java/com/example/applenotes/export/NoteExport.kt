package com.example.applenotes.export

import com.example.applenotes.proto.NoteBodyEditor.AttrSpan
import kotlin.io.encoding.Base64

internal enum class ExportFormat(val extension: String, val mimeType: String) {
    MARKDOWN("md", "text/markdown"),
    HTML("html", "text/html"),
}

/** An image attachment whose bytes the editor already downloaded. */
internal class ExportImage(val bytes: ByteArray) {
    val extension: String = imageExtension(bytes)
    val mimeType: String = when (extension) {
        "png" -> "image/png"
        "jpg" -> "image/jpeg"
        "gif" -> "image/gif"
        "heic" -> "image/heic"
        "webp" -> "image/webp"
        else -> "application/octet-stream"
    }
}

/** File extension from the image's magic bytes (the UTI/mime hint is unreliable). */
internal fun imageExtension(b: ByteArray): String = when {
    b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() -> "png"
    b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "jpg"
    b.size >= 6 && b.copyOfRange(0, 3).decodeToString() == "GIF" -> "gif"
    b.size >= 12 && b.copyOfRange(4, 8).decodeToString() == "ftyp" &&
        b.copyOfRange(8, 12).decodeToString().lowercase().let { it.startsWith("hei") || it.startsWith("mif") } -> "heic"
    b.size >= 12 && b.copyOfRange(0, 4).decodeToString() == "RIFF" && b.copyOfRange(8, 12).decodeToString() == "WEBP" -> "webp"
    else -> "bin"
}

/**
 * One note rendered for export.
 *
 * @param imageRefs attachment recordName → how to reference its image:
 *  a relative file name (written next to the note) or a `data:` URI.
 */
internal fun renderNoteForExport(
    format: ExportFormat,
    title: String,
    text: String,
    spans: List<AttrSpan>,
    imageRefs: Map<String, String> = emptyMap(),
): String {
    fun ref(span: AttrSpan) = span.attachmentRecordName?.let { imageRefs[it] }
    return when (format) {
        ExportFormat.HTML -> NoteHtml.document(title, text, spans) { span ->
            ref(span)?.let { "<img src=\"${NoteHtml.escape(it)}\" alt=\"image\">" }
        }
        ExportFormat.MARKDOWN -> NoteMarkdown.render(text, spans) { span ->
            ref(span)?.let { "![image](${it.replace(" ", "%20")})" }
        }
    }
}

/** `data:` URI for embedding an image in a self-contained HTML file. */
internal fun dataUri(image: ExportImage): String =
    "data:${image.mimeType};base64," + Base64.encode(image.bytes)

/** Title for a file name: the note's first line. */
internal fun exportTitleOf(text: String): String = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
