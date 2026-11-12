package com.example.applenotes.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.example.applenotes.proto.NoteBodyEditor

/**
 * Formatted preview of a note's body. Walks the visible text paragraph by
 * paragraph, applies the matching attribute_run style (body / bullet /
 * checkbox / numbered / heading / title), and renders each paragraph as
 * a styled Text or Row composable. Title (the first line) is rendered as
 * a large heading; subsequent lines get their own attribute-run style.
 *
 * Tap anywhere to switch the screen into the plain-text editor.
 */
@Composable
internal fun FormattedNoteBody(
    fullBody: String,
    titlePrefixLength: Int,
    attrSpans: List<NoteBodyEditor.AttrSpan>,
    onTap: (() -> Unit)?,
    modifier: Modifier = Modifier,
    attachmentContent: Map<String, AttachmentContent> = emptyMap(),
    onToggleCheckbox: ((paragraphCharStart: Int) -> Unit)? = null,
    emptyPlaceholder: String = "Start typing…",
) {
    // Map each char index -> (style, checked). Walk attrSpans in order and
    // assign each char its span's style. Total span lengths usually match
    // fullBody.length; if they don't (which can happen if Apple emits trailing
    // ARs covering chars we don't render), we tolerate the mismatch by
    // clamping.
    val styleAt = remember(fullBody, attrSpans) {
        val arr = arrayOfNulls<NoteBodyEditor.AttrSpan>(fullBody.length)
        var pos = 0
        for (span in attrSpans) {
            val end = (pos + span.length).coerceAtMost(fullBody.length)
            for (i in pos until end) arr[i] = span
            pos = end
            if (pos >= fullBody.length) break
        }
        arr
    }

    // Split into paragraphs. Each paragraph is the chars between consecutive
    // newlines (the newline goes WITH its preceding paragraph).
    val paragraphs = remember(fullBody) {
        val out = mutableListOf<IntRange>()
        var start = 0
        for ((i, c) in fullBody.withIndex()) {
            if (c == '\n') {
                out.add(start..i)
                start = i + 1
            }
        }
        if (start < fullBody.length) out.add(start until fullBody.length)
        else if (start == fullBody.length && fullBody.endsWith('\n')) {
            out.add(start until start) // trailing empty paragraph
        }
        out
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .then(if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier),
    ) {
        var listCounter = 0
        for (range in paragraphs) {
            // Detect an "attachment paragraph" — one whose only visible char is
            // U+FFFC and whose span carries f12 (attachment_info). Render those
            // as a labeled card (Table / Image / etc.) instead of inline text.
            // Mac/iCloud also lay these out as block elements.
            val attachmentSpan = run {
                val firstNonNl = (range.first..range.last).firstOrNull {
                    it < fullBody.length && fullBody[it] != '\n'
                } ?: return@run null
                val s = if (firstNonNl < styleAt.size) styleAt[firstNonNl] else null
                if (s?.isAttachment == true && fullBody[firstNonNl] == '￼') s else null
            }
            if (attachmentSpan != null) {
                val content = attachmentSpan.attachmentRecordName
                    ?.let { attachmentContent[it] }
                AttachmentBlock(attachmentSpan, content)
                continue
            }

            // The dominant style of a paragraph = the style of its first char.
            val firstSpan = if (range.first < styleAt.size) styleAt[range.first] else null
            val effectiveStyle = when {
                range.first < titlePrefixLength -> NoteBodyEditor.ParagraphStyle.TITLE
                firstSpan != null -> firstSpan.style
                else -> NoteBodyEditor.ParagraphStyle.BODY
            }
            // Reset numbered-list counter when the previous para wasn't part of
            // the same list.
            if (effectiveStyle != NoteBodyEditor.ParagraphStyle.NUMBERED_LIST) {
                listCounter = 0
            }

            // Build an AnnotatedString for the paragraph's text, applying
            // per-character bold/italic/underline/strike/link/monospace styling
            // from each AttrSpan that intersects the paragraph. The newline at
            // the end of the range is dropped — paragraph spacing is the
            // wrapper's job.
            val annotated = buildParagraphAnnotated(fullBody, range, styleAt)

            when (effectiveStyle) {
                NoteBodyEditor.ParagraphStyle.TITLE -> {
                    if (annotated.isNotEmpty()) {
                        Text(
                            annotated,
                            style = MaterialTheme.typography.headlineLarge.copy(
                                fontWeight = FontWeight.Bold,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                }
                NoteBodyEditor.ParagraphStyle.HEADING -> {
                    Text(
                        annotated,
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    )
                }
                NoteBodyEditor.ParagraphStyle.SUBHEADING -> {
                    Text(
                        annotated,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                    )
                }
                NoteBodyEditor.ParagraphStyle.MONOSPACED -> {
                    Text(
                        annotated,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                NoteBodyEditor.ParagraphStyle.BULLETED_LIST -> {
                    BulletRow("•", annotated)
                }
                NoteBodyEditor.ParagraphStyle.DASHED_LIST -> {
                    BulletRow("–", annotated)
                }
                NoteBodyEditor.ParagraphStyle.CHECKBOX_LIST -> {
                    val checked = firstSpan?.checked ?: false
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            if (checked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                            contentDescription = if (checked) "Checked" else "Unchecked",
                            tint = if (checked) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(end = 8.dp, top = 2.dp)
                                .size(28.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(enabled = onToggleCheckbox != null) {
                                    onToggleCheckbox?.invoke(range.first)
                                },
                        )
                        Text(
                            annotated,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                color = if (checked) MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.onSurface,
                            ),
                        )
                    }
                }
                NoteBodyEditor.ParagraphStyle.NUMBERED_LIST -> {
                    listCounter++
                    BulletRow("$listCounter.", annotated)
                }
                NoteBodyEditor.ParagraphStyle.BODY -> {
                    if (annotated.isEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                    } else {
                        Text(
                            annotated,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
        if (paragraphs.isEmpty()) {
            Text(
                emptyPlaceholder,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BulletRow(marker: String, content: AnnotatedString) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            "$marker  ",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            content,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Build an AnnotatedString for the chars at [range] in [fullBody], applying
 * inline styles (bold/italic/underline/strike/link/monospace) from the
 * AttrSpan covering each char. Coalesces consecutive chars that share an
 * AttrSpan so we only emit one styled span per run. Drops the trailing
 * newline if present (paragraphs include their newline; we don't want it
 * inside the visible text).
 */
private fun buildParagraphAnnotated(
    fullBody: String,
    range: IntRange,
    styleAt: Array<NoteBodyEditor.AttrSpan?>,
): AnnotatedString = buildAnnotatedString {
    var i = range.first
    val end = (range.last + 1).coerceAtMost(fullBody.length)
    while (i < end) {
        // Skip the trailing newline — only the very last char of the
        // paragraph range can be '\n', and we don't want it in the rendered
        // text.
        if (i == end - 1 && fullBody[i] == '\n') break
        val span = if (i < styleAt.size) styleAt[i] else null
        var j = i + 1
        while (j < end &&
            !(j == end - 1 && fullBody[j] == '\n') &&
            (if (j < styleAt.size) styleAt[j] else null) === span
        ) j++
        val sub = fullBody.substring(i, j)
        // U+FFFC stays inline only when the surrounding paragraph isn't
        // already being rendered as an attachment card (the caller checks
        // that and skips this codepath); show a small marker so the position
        // isn't invisible.
        val display = if (span?.isAttachment == true) sub.replace('￼', '⧉') else sub
        val style = inlineSpanStyle(span)
        if (span?.linkUrl != null) {
            val link = LinkAnnotation.Url(
                url = span.linkUrl.orEmpty(),
                styles = TextLinkStyles(style = style ?: SpanStyle()),
            )
            withLink(link) { append(display) }
        } else if (style != null) {
            withStyle(style) { append(display) }
        } else {
            append(display)
        }
        i = j
    }
}

internal fun inlineSpanStyle(s: NoteBodyEditor.AttrSpan?): SpanStyle? {
    if (s == null) return null
    val decorations = mutableListOf<TextDecoration>()
    if (s.underline) decorations.add(TextDecoration.Underline)
    if (s.strikethrough) decorations.add(TextDecoration.LineThrough)
    val hasInline = s.bold || s.italic || s.underline || s.strikethrough ||
        s.linkUrl != null || s.style == NoteBodyEditor.ParagraphStyle.MONOSPACED
    if (!hasInline) return null
    return SpanStyle(
        fontWeight = if (s.bold) FontWeight.Bold else null,
        fontStyle = if (s.italic) FontStyle.Italic else null,
        textDecoration = when (decorations.size) {
            0 -> if (s.linkUrl != null) TextDecoration.Underline else null
            1 -> decorations[0]
            else -> TextDecoration.combine(decorations)
        },
        color = if (s.linkUrl != null) Color(0xFF1A73E8) else Color.Unspecified,
        fontFamily = if (s.style == NoteBodyEditor.ParagraphStyle.MONOSPACED) FontFamily.Monospace else null,
    )
}
