package com.example.applenotes.ui.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.applenotes.R

/**
 * The list's sync status line (issues #12 / #13): a subtle "Updating…"
 * while a background sync runs, and "N unsent changes" (tap to retry) while
 * the outbox holds drafts iCloud hasn't confirmed.
 */
@Composable
internal fun SyncStatusRow(syncing: Boolean, unsentCount: Int, onRetryUnsent: () -> Unit) {
    if (!syncing && unsentCount == 0) return
    Column(modifier = Modifier.fillMaxWidth()) {
        if (syncing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
        }
        val retryLabel = stringResource(R.string.unsent_changes_retry)
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (syncing) {
                Text(
                    stringResource(R.string.sync_updating),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (unsentCount > 0) {
                AssistChip(
                    onClick = onRetryUnsent,
                    // "3 unsent changes, button, double-tap to send them again".
                    modifier = Modifier.semantics { onClick(label = retryLabel) { onRetryUnsent(); true } },
                    label = { Text(pluralStringResource(R.plurals.unsent_changes, unsentCount, unsentCount)) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.CloudUpload,
                            contentDescription = null,
                            modifier = Modifier.size(AssistChipDefaults.IconSize),
                        )
                    },
                )
            }
        }
    }
}
