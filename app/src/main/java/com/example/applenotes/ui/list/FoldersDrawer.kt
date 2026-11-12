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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.example.applenotes.R
import com.example.applenotes.ui.common.UiText
import com.example.applenotes.ui.common.asString

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
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 24.dp, top = 24.dp, bottom = 8.dp).semantics { heading() },
            )
            // "All notes" pseudo-folder
            DrawerFolderRow(
                label = stringResource(R.string.folder_all_notes),
                count = nonTrashCount,
                selected = selectedFolder == null,
                onClick = { onSelect(null) },
            )
            // User-visible folders (not trash)
            for (folder in folders.filter { !it.isTrash }) {
                val display = folder.displayName ?: when {
                    folder.isDefault -> stringResource(R.string.folder_default)
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
                label = stringResource(R.string.folder_recently_deleted),
                count = countByFolder[TRASH_RECORD_NAME] ?: 0,
                selected = selectedFolder == TRASH_RECORD_NAME,
                onClick = { onSelect(TRASH_RECORD_NAME) },
                icon = Icons.Default.Delete,
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            for (a in actions) {
                DrawerFolderRow(
                    label = a.label.asString(),
                    count = 0,
                    selected = false,
                    onClick = a.onClick,
                    icon = a.icon,
                    isFolder = false,
                )
            }
            if (actions.isNotEmpty()) HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(onClick = onSignOut, role = Role.Button)
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
                    stringResource(R.string.drawer_sign_out),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A non-folder drawer entry. */
internal class DrawerAction(
    val label: UiText,
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
    /** A folder (announced as selected / not selected) rather than an action. */
    isFolder: Boolean = true,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent
    val fg = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    val countDescription = pluralStringResource(R.plurals.note_count, count, count)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .minimumInteractiveComponentSize()
            .background(bg, MaterialTheme.shapes.medium)
            .then(
                if (isFolder) {
                    Modifier.selectable(selected = selected, onClick = onClick, role = Role.Tab)
                } else {
                    Modifier.clickable(onClick = onClick, role = Role.Button)
                },
            )
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
                modifier = Modifier.semantics { contentDescription = countDescription },
            )
        }
    }
}
