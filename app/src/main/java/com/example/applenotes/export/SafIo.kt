package com.example.applenotes.export

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.example.applenotes.R
import com.example.applenotes.data.UserFacingIOException
import com.example.applenotes.ui.common.UiText
import com.example.applenotes.ui.common.uiText

/** Storage Access Framework reads/writes used by export and import. */
internal object SafIo {

    fun writeBytes(resolver: ContentResolver, uri: Uri, bytes: ByteArray) {
        // "wt" truncates when overwriting an existing document.
        val out = resolver.openOutputStream(uri, "wt")
            ?: throw UserFacingIOException(UiText.Res(R.string.file_open_for_writing_failed), "openOutputStream returned null")
        out.use { it.write(bytes) }
    }

    fun writeText(resolver: ContentResolver, uri: Uri, text: String) = writeBytes(resolver, uri, text.toByteArray())

    /** The document of a tree picked with ACTION_OPEN_DOCUMENT_TREE. */
    fun treeDocument(treeUri: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))

    /** Create [displayName] in the tree directory [parentDocument] and write [bytes] to it. */
    fun createFile(
        resolver: ContentResolver,
        parentDocument: Uri,
        displayName: String,
        mimeType: String,
        bytes: ByteArray,
    ): Uri {
        val uri = DocumentsContract.createDocument(resolver, parentDocument, mimeType, displayName)
            ?: throw UserFacingIOException(uiText(R.string.file_create_failed, displayName), "createDocument returned null")
        writeBytes(resolver, uri, bytes)
        return uri
    }

    fun displayName(resolver: ContentResolver, uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    /** Read a text file, refusing anything over [maxBytes] or anything that looks binary. */
    fun readText(resolver: ContentResolver, uri: Uri, maxBytes: Int = 2 * 1024 * 1024): String {
        val input = resolver.openInputStream(uri)
            ?: throw UserFacingIOException(UiText.Res(R.string.file_open_failed), "openInputStream returned null")
        val bytes = input.use { s ->
            val buf = s.readNBytesCompat(maxBytes + 1)
            if (buf.size > maxBytes) {
                throw UserFacingIOException(uiText(R.string.import_too_large, maxBytes / 1024 / 1024), "file too large")
            }
            buf
        }
        if (bytes.any { it == 0.toByte() }) throw UserFacingIOException(UiText.Res(R.string.import_not_text), "binary file")
        return bytes.decodeToString()
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < limit) {
            val n = read(buf, 0, minOf(buf.size, limit - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
