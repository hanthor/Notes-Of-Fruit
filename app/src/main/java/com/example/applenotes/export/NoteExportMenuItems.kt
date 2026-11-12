package com.example.applenotes.export

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Code
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/** Detail overflow menu entries for export / print / pin. */
@Composable
internal fun NoteExportMenuItems(
    actions: NoteExportActions,
    hasImages: Boolean,
    canPin: Boolean,
    onPin: () -> Unit,
    onDismiss: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text("Export as Markdown…") },
        leadingIcon = { Icon(Icons.Default.Description, contentDescription = null) },
        onClick = { onDismiss(); actions.exportAs(ExportFormat.MARKDOWN) },
    )
    DropdownMenuItem(
        text = { Text("Export as HTML…") },
        leadingIcon = { Icon(Icons.Default.Code, contentDescription = null) },
        onClick = { onDismiss(); actions.exportAs(ExportFormat.HTML) },
    )
    if (hasImages) {
        DropdownMenuItem(
            text = { Text("Export with images to folder…") },
            leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null) },
            onClick = { onDismiss(); actions.exportWithImages() },
        )
    }
    DropdownMenuItem(
        text = { Text("Print / save as PDF") },
        leadingIcon = { Icon(Icons.Default.Print, contentDescription = null) },
        onClick = { onDismiss(); actions.print() },
    )
    if (canPin) {
        DropdownMenuItem(
            text = { Text("Pin to home screen") },
            leadingIcon = { Icon(Icons.Default.Home, contentDescription = null) },
            onClick = { onDismiss(); onPin() },
        )
    }
}
