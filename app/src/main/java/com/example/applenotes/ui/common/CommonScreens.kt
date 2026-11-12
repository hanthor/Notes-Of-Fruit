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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.applenotes.R

@Composable
internal fun CenteredSpinner(msg: String, modifier: Modifier = Modifier) {
    Column(
        // One announcement ("Loading note…") instead of an unlabeled spinner.
        modifier = modifier.fillMaxSize().semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text(msg, style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ErrorScreen(
    message: UiText,
    onRetry: () -> Unit,
    onSignOut: () -> Unit,
    details: String? = null,
) {
    var showDetails by remember { mutableStateOf(false) }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.error_screen_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(message.asString(), style = MaterialTheme.typography.bodySmall)
            // FlowRow: the two buttons wrap instead of clipping at large font sizes.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
                Button(onClick = onSignOut) { Text(stringResource(R.string.action_sign_in_again)) }
            }
            if (details != null) {
                TextButton(onClick = { showDetails = true }) { Text(stringResource(R.string.action_details)) }
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
            Text(stringResource(R.string.offline_banner), style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** "just now", "5m", "3h", "2d", else a short localized date ("Mar 4"). */
@Composable
internal fun humanRelative(epochMs: Long): String = relativeTime(epochMs).asString()

internal fun relativeTime(epochMs: Long, nowMs: Long = System.currentTimeMillis()): UiText {
    val s = (nowMs - epochMs) / 1000
    return when {
        s < 60 -> UiText.Res(R.string.time_just_now)
        s < 3600 -> UiText.Res(R.string.time_minutes_short, listOf((s / 60).toInt()))
        s < 86_400 -> UiText.Res(R.string.time_hours_short, listOf((s / 3600).toInt()))
        s < 604_800 -> UiText.Res(R.string.time_days_short, listOf((s / 86_400).toInt()))
        else -> {
            val format = android.text.format.DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), "MMMd")
                ?: "MMM d"
            UiText.Plain(java.text.SimpleDateFormat(format, java.util.Locale.getDefault()).format(java.util.Date(epochMs)))
        }
    }
}
