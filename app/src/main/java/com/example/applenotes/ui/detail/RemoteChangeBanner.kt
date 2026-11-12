package com.example.applenotes.ui.detail

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.applenotes.R
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle

/**
 * Runs [NoteDetailViewModel.watchForRemoteChanges] while the screen is at
 * least STARTED (visible) and shows the "Updated on another device" banner
 * when it finds a newer version (issue #16).
 *
 * Wording is distinct from the save-time conflict dialog ("This note changed
 * on another device" — Keep both / Discard): this banner only informs and
 * offers Reload when there's nothing unsaved to lose.
 */
@Composable
internal fun RemoteChangeIndicator(vm: NoteDetailViewModel, recordName: String) {
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, recordName) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.watchForRemoteChanges() }
    }
    val newer by vm.remoteChange.collectAsStateWithLifecycle()
    if (newer == null) return
    RemoteChangeBanner(
        hasUnsavedChanges = vm.isModified,
        onReload = vm::reloadRemoteChange,
        onDismiss = vm::dismissRemoteChange,
    )
}

@Composable
private fun RemoteChangeBanner(hasUnsavedChanges: Boolean, onReload: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.padding(end = 12.dp).size(18.dp))
            Text(
                stringResource(
                    if (hasUnsavedChanges) R.string.remote_change_banner_unsaved else R.string.remote_change_banner,
                ),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            if (!hasUnsavedChanges) {
                TextButton(onClick = onReload) { Text(stringResource(R.string.action_reload)) }
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.remote_change_dismiss))
            }
        }
    }
}
