package com.example.applenotes.export

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.IOException

/** Storage Access Framework reads/writes used by export and import. */
internal object SafIo {

    fun writeBytes(resolver: ContentResolver, uri: Uri, bytes: ByteArray) {
        // "wt" truncates when overwriting an existing document.
        val out = resolver.openOutputStream(uri, "wt") ?: throw IOException("Couldn't open the file for writing")
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
            ?: throw IOException("Couldn't create $displayName")
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
        val input = resolver.openInputStream(uri) ?: throw IOException("Couldn't open the file")
        val bytes = input.use { s ->
            val buf = s.readNBytesCompat(maxBytes + 1)
            if (buf.size > maxBytes) throw IOException("The file is too large to import (over ${maxBytes / 1024 / 1024} MB)")
            buf
        }
        if (bytes.any { it == 0.toByte() }) throw IOException("That doesn't look like a text file")
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
