package com.example.applenotes.ui.detail

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.res.stringResource
import com.example.applenotes.R
import com.example.applenotes.ui.common.humanRelative

/**
 * "Restore unsaved changes?" (issue #13): an unsent draft of this note from
 * an earlier session is on disk. Not dismissable — the editor holds off
 * writing until the user picks, so the draft can't be overwritten.
 */
@Composable
internal fun RestoreDraftDialog(
    prompt: NoteDetailViewModel.SavedDraftPrompt,
    onRestore: () -> Unit,
    onDiscard: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.restore_draft_title)) },
        text = {
            Text(
                stringResource(
                    if (prompt.conflict) R.string.restore_draft_text_conflict else R.string.restore_draft_text,
                    humanRelative(prompt.savedAtMs),
                ),
            )
        },
        confirmButton = { TextButton(onClick = onRestore) { Text(stringResource(R.string.action_restore)) } },
        dismissButton = { TextButton(onClick = onDiscard) { Text(stringResource(R.string.action_discard)) } },
    )
}

/** The note was opened from the copy saved on this device, because there is no network (issue #12). */
@Composable
internal fun OfflineCopyBanner(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.CloudOff, contentDescription = null, modifier = Modifier.padding(end = 8.dp).size(16.dp))
            Text(stringResource(R.string.offline_copy_banner), style = MaterialTheme.typography.labelMedium)
        }
    }
}
