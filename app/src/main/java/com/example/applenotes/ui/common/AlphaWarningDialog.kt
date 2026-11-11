package com.example.applenotes.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun AlphaWarningDialog(onAcknowledge: () -> Unit) {
    AlertDialog(
        onDismissRequest = { /* mandatory: must tap continue */ },
        icon = {
            Icon(
                imageVector = androidx.compose.material.icons.Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(40.dp),
            )
        },
        title = {
            Text(
                "Alpha — read this first",
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Notes of Fruit is an unofficial, reverse-engineered, alpha-quality " +
                        "Android client for iCloud Notes. It is not affiliated with Apple.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "It might:",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                )
                Text("• corrupt, duplicate, or delete notes in your iCloud account",
                    style = MaterialTheme.typography.bodyMedium)
                Text("• drop formatting in subtle ways when round-tripping through Mac",
                    style = MaterialTheme.typography.bodyMedium)
                Text("• stop working at any time if Apple changes their API",
                    style = MaterialTheme.typography.bodyMedium)
                Text("• fail to handle modern collaborative notes (read-only at best)",
                    style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Use at your own risk. If you have notes you can't afford to lose, " +
                        "back them up first or just don't use this app.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onAcknowledge) {
                Text("I understand — continue")
            }
        },
    )
}
