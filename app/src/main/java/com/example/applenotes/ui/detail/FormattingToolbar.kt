package com.example.applenotes.ui.detail

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.applenotes.proto.NoteBodyEditor

@Composable
internal fun FormattingToolbar(
    titlePrefixLength: Int,
    bodyValue: androidx.compose.ui.text.input.TextFieldValue,
    fullBody: String,
    spans: List<NoteBodyEditor.AttrSpan>,
    onSpansChange: (List<NoteBodyEditor.AttrSpan>) -> Unit,
) {
    // Map body-relative selection → full-body coordinates.
    val sel = bodyValue.selection
    val rangeStart = (sel.min + titlePrefixLength).coerceIn(0, fullBody.length)
    val rangeEnd = (sel.max + titlePrefixLength).coerceIn(rangeStart, fullBody.length)
    val effectiveEnd = if (rangeEnd == rangeStart) {
        // No selection — apply to the paragraph of the cursor for paragraph
        // styles, and to a one-char neighborhood for inline (handled via
        // selectionExtended below).
        rangeEnd
    } else rangeEnd

    val perChar = remember(fullBody, spans) { NoteBodyEditor.expandSpansToChars(fullBody, spans) }
    val curSpan = if (rangeStart < perChar.size) perChar[rangeStart]
        else if (perChar.isNotEmpty()) perChar.last() else null
    // For "is this format on right now?" indicators: collapsed cursor uses the
    // span at the cursor; selection uses "is every char in selection on?".
    fun allHave(predicate: (NoteBodyEditor.AttrSpan) -> Boolean): Boolean {
        if (rangeStart >= rangeEnd) return curSpan?.let(predicate) == true
        for (i in rangeStart until rangeEnd) if (i in perChar.indices && !predicate(perChar[i])) return false
        return true
    }
    val isBold = allHave { it.bold }
    val isItalic = allHave { it.italic }
    val isUnderline = allHave { it.underline }
    val isStrike = allHave { it.strikethrough }
    val curStyle = curSpan?.style ?: NoteBodyEditor.ParagraphStyle.BODY

    val context = LocalContext.current

    // Style picker dropdown state.
    var styleMenuOpen by remember { mutableStateOf(false) }
    var linkDialogOpen by remember { mutableStateOf(false) }

    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 0.dp,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Paragraph-style picker.
                ToolbarPill(
                    label = paragraphStyleLabel(curStyle),
                    icon = "Aa",
                    selected = curStyle != NoteBodyEditor.ParagraphStyle.BODY,
                    onClick = { styleMenuOpen = true },
                )
                DropdownMenu(
                    expanded = styleMenuOpen,
                    onDismissRequest = { styleMenuOpen = false },
                ) {
                    listOf(
                        NoteBodyEditor.ParagraphStyle.TITLE,
                        NoteBodyEditor.ParagraphStyle.HEADING,
                        NoteBodyEditor.ParagraphStyle.SUBHEADING,
                        NoteBodyEditor.ParagraphStyle.BODY,
                        NoteBodyEditor.ParagraphStyle.MONOSPACED,
                    ).forEach { style ->
                        DropdownMenuItem(
                            text = { Text(paragraphStyleLabel(style)) },
                            trailingIcon = {
                                if (curStyle == style) Icon(Icons.Default.Check, null)
                            },
                            onClick = {
                                styleMenuOpen = false
                                onSpansChange(NoteBodyEditor.setParagraphStyle(
                                    fullBody, spans, rangeStart, rangeEnd.coerceAtLeast(rangeStart + 1), style,
                                ))
                            },
                        )
                    }
                }

                ToolbarDivider()

                // List paragraph styles.
                ToolbarIconButton(
                    icon = Icons.AutoMirrored.Filled.FormatListBulleted,
                    contentDescription = "Bulleted list",
                    selected = curStyle == NoteBodyEditor.ParagraphStyle.BULLETED_LIST,
                    onClick = {
                        val target = if (curStyle == NoteBodyEditor.ParagraphStyle.BULLETED_LIST)
                            NoteBodyEditor.ParagraphStyle.BODY
                        else NoteBodyEditor.ParagraphStyle.BULLETED_LIST
                        onSpansChange(NoteBodyEditor.setParagraphStyle(
                            fullBody, spans, rangeStart, rangeEnd.coerceAtLeast(rangeStart + 1), target,
                        ))
                    },
                )
                ToolbarIconButton(
                    icon = Icons.Filled.HorizontalRule,
                    contentDescription = "Dashed list",
                    selected = curStyle == NoteBodyEditor.ParagraphStyle.DASHED_LIST,
                    onClick = {
                        val target = if (curStyle == NoteBodyEditor.ParagraphStyle.DASHED_LIST)
                            NoteBodyEditor.ParagraphStyle.BODY
                        else NoteBodyEditor.ParagraphStyle.DASHED_LIST
                        onSpansChange(NoteBodyEditor.setParagraphStyle(
                            fullBody, spans, rangeStart, rangeEnd.coerceAtLeast(rangeStart + 1), target,
                        ))
                    },
                )
                ToolbarIconButton(
                    icon = Icons.Filled.FormatListNumbered,
                    contentDescription = "Numbered list",
                    selected = curStyle == NoteBodyEditor.ParagraphStyle.NUMBERED_LIST,
                    onClick = {
                        val target = if (curStyle == NoteBodyEditor.ParagraphStyle.NUMBERED_LIST)
                            NoteBodyEditor.ParagraphStyle.BODY
                        else NoteBodyEditor.ParagraphStyle.NUMBERED_LIST
                        onSpansChange(NoteBodyEditor.setParagraphStyle(
                            fullBody, spans, rangeStart, rangeEnd.coerceAtLeast(rangeStart + 1), target,
                        ))
                    },
                )
                ToolbarIconButton(
                    icon = Icons.Filled.CheckBoxOutlineBlank,
                    contentDescription = "Checkbox",
                    selected = curStyle == NoteBodyEditor.ParagraphStyle.CHECKBOX_LIST,
                    onClick = {
                        val target = if (curStyle == NoteBodyEditor.ParagraphStyle.CHECKBOX_LIST)
                            NoteBodyEditor.ParagraphStyle.BODY
                        else NoteBodyEditor.ParagraphStyle.CHECKBOX_LIST
                        onSpansChange(NoteBodyEditor.setParagraphStyle(
                            fullBody, spans, rangeStart, rangeEnd.coerceAtLeast(rangeStart + 1), target,
                        ))
                    },
                )

                ToolbarDivider()

                // Inline format toggles.
                ToolbarIconButton(
                    icon = Icons.Filled.FormatBold,
                    contentDescription = "Bold",
                    selected = isBold,
                    onClick = {
                        if (rangeStart < rangeEnd) {
                            onSpansChange(NoteBodyEditor.toggleInline(
                                fullBody, spans, rangeStart, rangeEnd, NoteBodyEditor.InlineKind.BOLD,
                            ))
                        }
                    },
                )
                ToolbarIconButton(
                    icon = Icons.Filled.FormatItalic,
                    contentDescription = "Italic",
                    selected = isItalic,
                    onClick = {
                        if (rangeStart < rangeEnd) {
                            onSpansChange(NoteBodyEditor.toggleInline(
                                fullBody, spans, rangeStart, rangeEnd, NoteBodyEditor.InlineKind.ITALIC,
                            ))
                        }
                    },
                )
                ToolbarIconButton(
                    icon = Icons.Filled.FormatUnderlined,
                    contentDescription = "Underline",
                    selected = isUnderline,
                    onClick = {
                        if (rangeStart < rangeEnd) {
                            onSpansChange(NoteBodyEditor.toggleInline(
                                fullBody, spans, rangeStart, rangeEnd, NoteBodyEditor.InlineKind.UNDERLINE,
                            ))
                        }
                    },
                )
                ToolbarIconButton(
                    icon = Icons.Filled.FormatStrikethrough,
                    contentDescription = "Strikethrough",
                    selected = isStrike,
                    onClick = {
                        if (rangeStart < rangeEnd) {
                            onSpansChange(NoteBodyEditor.toggleInline(
                                fullBody, spans, rangeStart, rangeEnd, NoteBodyEditor.InlineKind.STRIKETHROUGH,
                            ))
                        }
                    },
                )

                ToolbarDivider()

                // Link.
                ToolbarIconButton(
                    icon = Icons.Filled.Link,
                    contentDescription = "Link",
                    selected = curSpan?.linkUrl != null,
                    onClick = { linkDialogOpen = true },
                )
            }
        }
    }

    if (linkDialogOpen) {
        var urlText by remember { mutableStateOf(curSpan?.linkUrl ?: "https://") }
        AlertDialog(
            onDismissRequest = { linkDialogOpen = false },
            title = { Text("Add link") },
            text = {
                OutlinedTextField(
                    value = urlText,
                    onValueChange = { urlText = it },
                    label = { Text("URL") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    linkDialogOpen = false
                    if (rangeStart < rangeEnd) {
                        onSpansChange(NoteBodyEditor.setLink(
                            fullBody, spans, rangeStart, rangeEnd, urlText.takeIf { it.isNotBlank() },
                        ))
                    }
                }) { Text("Apply") }
            },
            dismissButton = {
                TextButton(onClick = {
                    linkDialogOpen = false
                    if (rangeStart < rangeEnd && curSpan?.linkUrl != null) {
                        onSpansChange(NoteBodyEditor.setLink(fullBody, spans, rangeStart, rangeEnd, null))
                    }
                }) { Text(if (curSpan?.linkUrl != null) "Remove" else "Cancel") }
            },
        )
    }
}

private fun paragraphStyleLabel(style: NoteBodyEditor.ParagraphStyle): String = when (style) {
    NoteBodyEditor.ParagraphStyle.TITLE -> "Title"
    NoteBodyEditor.ParagraphStyle.HEADING -> "Heading"
    NoteBodyEditor.ParagraphStyle.SUBHEADING -> "Subheading"
    NoteBodyEditor.ParagraphStyle.BODY -> "Body"
    NoteBodyEditor.ParagraphStyle.MONOSPACED -> "Monospaced"
    NoteBodyEditor.ParagraphStyle.BULLETED_LIST -> "Bulleted"
    NoteBodyEditor.ParagraphStyle.DASHED_LIST -> "Dashed"
    NoteBodyEditor.ParagraphStyle.NUMBERED_LIST -> "Numbered"
    NoteBodyEditor.ParagraphStyle.CHECKBOX_LIST -> "Checkbox"
}

@Composable
private fun ToolbarPill(
    label: String,
    icon: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bg by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        label = "pillBg",
    )
    val fg by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurface,
        label = "pillFg",
    )
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                icon,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = fg,
                modifier = Modifier.padding(end = 8.dp),
            )
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = fg,
            )
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun ToolbarIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bg by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        label = "iconBg",
    )
    val fg by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurface,
        label = "iconFg",
    )
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = fg,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun ToolbarDivider() {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .width(1.dp)
            .height(24.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}
