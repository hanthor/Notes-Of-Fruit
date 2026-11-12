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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.applenotes.R

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
                stringResource(R.string.alpha_title),
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            // Scrolls at large font sizes instead of pushing the button off screen.
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text(
                    stringResource(R.string.alpha_intro),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.alpha_it_might),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                )
                for (risk in listOf(R.string.alpha_risk_corrupt, R.string.alpha_risk_formatting, R.string.alpha_risk_api, R.string.alpha_risk_collab)) {
                    Text(stringResource(R.string.alpha_bullet, stringResource(risk)), style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    stringResource(R.string.alpha_own_risk),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onAcknowledge) {
                Text(stringResource(R.string.alpha_continue))
            }
        },
    )
}
