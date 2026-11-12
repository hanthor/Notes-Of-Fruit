package com.example.applenotes.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.applenotes.client.FolderSummary
import com.example.applenotes.client.NoteSummary
import com.example.applenotes.data.TRASH_RECORD_NAME

@Composable
internal fun FoldersDrawer(
    folders: List<FolderSummary>,
    allNotes: List<NoteSummary>,
    selectedFolder: String?,
    onSelect: (String?) -> Unit,
    onSignOut: () -> Unit,
    /** Extra drawer entries (import / export / settings); empty = none. */
    actions: List<DrawerAction> = emptyList(),
) {
    // Per-folder note counts (excluding deleted=true outliers, which we
    // already filter at fetch time).
    val countByFolder = remember(allNotes) {
        allNotes.groupingBy { it.folderRecordName }.eachCount()
    }
    val nonTrashCount = remember(allNotes) {
        allNotes.count { it.folderRecordName != TRASH_RECORD_NAME }
    }
    ModalDrawerSheet {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                "Notes of Fruit",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 24.dp, top = 24.dp, bottom = 8.dp),
            )
            // "All notes" pseudo-folder
            DrawerFolderRow(
                label = "All notes",
                count = nonTrashCount,
                selected = selectedFolder == null,
                onClick = { onSelect(null) },
            )
            // User-visible folders (not trash)
            for (folder in folders.filter { !it.isTrash }) {
                val display = folder.displayName ?: when {
                    folder.isDefault -> "Notes"
                    else -> folder.recordName.take(8)
                }
                DrawerFolderRow(
                    label = display,
                    count = countByFolder[folder.recordName] ?: 0,
                    selected = selectedFolder == folder.recordName,
                    onClick = { onSelect(folder.recordName) },
                )
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            DrawerFolderRow(
                label = "Recently Deleted",
                count = countByFolder[TRASH_RECORD_NAME] ?: 0,
                selected = selectedFolder == TRASH_RECORD_NAME,
                onClick = { onSelect(TRASH_RECORD_NAME) },
                icon = Icons.Default.Delete,
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            for (a in actions) {
                DrawerFolderRow(label = a.label, count = 0, selected = false, onClick = a.onClick, icon = a.icon)
            }
            if (actions.isNotEmpty()) HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onSignOut)
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Logout,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    "Sign out",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A non-folder drawer entry. */
internal class DrawerAction(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val onClick: () -> Unit,
)

@Composable
private fun DrawerFolderRow(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent
    val fg = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .background(bg, MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg)
            Spacer(Modifier.width(12.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = fg,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        if (count > 0) {
            Text(
                count.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) fg else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
