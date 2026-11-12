package com.example.applenotes.export

import android.content.ContentResolver
import android.net.Uri
import com.example.applenotes.ui.detail.DraftPrefill

/** MIME types offered in the import picker (.md files are often octet-stream). */
internal val IMPORT_MIME_TYPES = arrayOf("text/plain", "text/markdown", "text/x-markdown", "application/octet-stream")

internal fun isMarkdownFile(displayName: String?, mimeType: String?): Boolean {
    val name = displayName.orEmpty().lowercase()
    return name.endsWith(".md") || name.endsWith(".markdown") || name.endsWith(".mdown") ||
        mimeType == "text/markdown" || mimeType == "text/x-markdown"
}

/**
 * Turn file content into a new-draft prefill: Markdown is mapped to native
 * paragraph styles / inline spans ([MarkdownImport.markdown]); anything else
 * is plain text. An empty file gets its file name as the title line.
 */
internal fun importPrefill(content: String, displayName: String?, mimeType: String?): DraftPrefill {
    val imported = if (isMarkdownFile(displayName, mimeType)) {
        MarkdownImport.markdown(content)
    } else {
        MarkdownImport.plainText(content)
    }
    if (imported.text.isBlank()) {
        val title = displayName?.substringBeforeLast('.')?.trim().orEmpty()
        return DraftPrefill(title)
    }
    return DraftPrefill.of(imported.text, imported.spans)
}

/** Reads [uri] (blocking; call off the main thread) and builds the prefill. */
internal fun readImport(resolver: ContentResolver, uri: Uri): DraftPrefill {
    val text = SafIo.readText(resolver, uri)
    return importPrefill(text, SafIo.displayName(resolver, uri), resolver.getType(uri))
}
