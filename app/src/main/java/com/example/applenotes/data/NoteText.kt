package com.example.applenotes.data

import com.example.applenotes.proto.NoteBodyEditor


/**
 * Apple Notes derives Title and Snippet from the body's first non-empty line
 * (= title) and what follows (= snippet preview). The CloudKit `TitleEncrypted`
 * and `SnippetEncrypted` fields are server-cached copies of these — Apple's
 * clients (Mac, iCloud.com list view) treat them as the canonical visible
 * preview. Updating them along with TextDataEncrypted on every edit keeps
 * lists fresh and gives Mac a clean signal that the note actually changed.
 */
internal fun computeTitleAndSnippet(body: String): Pair<String, String> {
    val lines = body.split("\n")
    val title = lines.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    val titleIdx = lines.indexOfFirst { it.isNotBlank() }
    val rest = if (titleIdx >= 0) lines.drop(titleIdx + 1) else emptyList()
    val snippet = rest.firstOrNull { it.isNotBlank() }?.trim()?.take(120).orEmpty()
    return title to snippet
}

/**
 * True when [spans] (over [text]) carry anything beyond plain Body text and a
 * Title-styled first line.
 */
internal fun hasRichFormatting(text: String, spans: List<NoteBodyEditor.AttrSpan>): Boolean {
    val firstLineEnd = text.indexOf('\n').let { if (it < 0) text.length else it }
    var offset = 0
    for (s in spans) {
        val styled = when (s.style) {
            NoteBodyEditor.ParagraphStyle.BODY -> false
            // Title on the first line is what every note has; elsewhere it's formatting.
            NoteBodyEditor.ParagraphStyle.TITLE -> offset + s.length > firstLineEnd + 1
            else -> true
        }
        if (styled || s.bold || s.italic || s.underline || s.strikethrough || s.linkUrl != null) return true
        offset += s.length
    }
    return false
}
