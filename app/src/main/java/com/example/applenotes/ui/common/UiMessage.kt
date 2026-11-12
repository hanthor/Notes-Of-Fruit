package com.example.applenotes.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily

/**
 * A one-shot message for a snackbar, optionally with an action (e.g. Retry).
 * [id] distinguishes two messages with the same text. The screen calls back
 * with the message when it was acted on or dismissed, so the ViewModel can
 * clear it (and it is not shown again after rotation).
 *
 * [details] is the raw error text (server response, exception) shown only
 * behind a "Details" button, never in the main message.
 */
data class UiMessage<A>(
    val id: Long,
    val text: String,
    val action: A? = null,
    val actionLabel: String? = if (action != null) "Retry" else null,
    val details: String? = null,
)

/** Snackbar visuals that also carry the "Details" text (see [AppSnackbarHost]). */
private class DetailedVisuals(
    override val message: String,
    override val actionLabel: String?,
    override val withDismissAction: Boolean,
    override val duration: SnackbarDuration,
    val details: String?,
) : SnackbarVisuals

/** Shows [message] in [hostState] and reports the outcome. */
@Composable
fun <A> MessageSnackbarEffect(
    message: UiMessage<A>?,
    hostState: SnackbarHostState,
    onAction: (UiMessage<A>) -> Unit,
    onDismiss: (UiMessage<A>) -> Unit,
) {
    val currentOnAction by rememberUpdatedState(onAction)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(message?.id) {
        val m = message ?: return@LaunchedEffect
        val result = hostState.showSnackbar(
            DetailedVisuals(
                message = m.text,
                actionLabel = m.actionLabel,
                withDismissAction = m.action != null || m.details != null,
                duration = if (m.action != null || m.details != null) {
                    SnackbarDuration.Indefinite
                } else {
                    SnackbarDuration.Short
                },
                details = m.details,
            ),
        )
        when (result) {
            SnackbarResult.ActionPerformed -> currentOnAction(m)
            SnackbarResult.Dismissed -> currentOnDismiss(m)
        }
    }
}

/**
 * [SnackbarHost] that adds a "Details" button (opening a dialog with the raw
 * error text) to messages that have [UiMessage.details].
 */
@Composable
fun AppSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    var shownDetails by remember { mutableStateOf<String?>(null) }
    SnackbarHost(hostState, modifier) { data ->
        val details = (data.visuals as? DetailedVisuals)?.details
        if (details == null) {
            Snackbar(data)
        } else {
            Snackbar(
                action = {
                    Row {
                        TextButton(onClick = { shownDetails = details }) { Text("Details") }
                        data.visuals.actionLabel?.let { label ->
                            TextButton(onClick = { data.performAction() }) { Text(label) }
                        }
                    }
                },
                dismissAction = {
                    TextButton(onClick = { data.dismiss() }) { Text("Dismiss") }
                },
            ) {
                Text(data.visuals.message)
            }
        }
    }
    shownDetails?.let { text -> ErrorDetailsDialog(text, onDismiss = { shownDetails = null }) }
}

/** The raw error text, monospaced and scrollable. */
@Composable
fun ErrorDetailsDialog(details: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Error details") },
        text = {
            Text(
                details,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
