package com.example.applenotes.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun CenteredSpinner(msg: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text(msg, style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ErrorScreen(
    message: String,
    onRetry: () -> Unit,
    onSignOut: () -> Unit,
    details: String? = null,
) {
    var showDetails by remember { mutableStateOf(false) }
    Scaffold(topBar = { TopAppBar(title = { Text("Notes of Fruit") }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Something went wrong", style = MaterialTheme.typography.titleMedium)
            Text(message, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onRetry) { Text("Retry") }
                Button(onClick = onSignOut) { Text("Sign in again") }
            }
            if (details != null) {
                TextButton(onClick = { showDetails = true }) { Text("Details") }
            }
        }
    }
    if (showDetails && details != null) {
        ErrorDetailsDialog(details, onDismiss = { showDetails = false })
    }
}

/**
 * Small "you're offline" strip for the top of the list and detail screens.
 * Edits stay in the editor (and its saved state) while it shows.
 */
@Composable
internal fun OfflineBanner(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.CloudOff,
                contentDescription = null,
                modifier = Modifier.padding(end = 8.dp).size(16.dp),
            )
            Text("Offline", style = MaterialTheme.typography.labelMedium)
        }
    }
}

internal fun humanRelative(epochMs: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - epochMs
    val s = diff / 1000
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60}m"
        s < 86_400 -> "${s / 3600}h"
        s < 604_800 -> "${s / 86_400}d"
        else -> {
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = epochMs }
            val month = cal.getDisplayName(java.util.Calendar.MONTH, java.util.Calendar.SHORT, java.util.Locale.getDefault()) ?: "?"
            val day = cal.get(java.util.Calendar.DAY_OF_MONTH)
            "$month $day"
        }
    }
}
