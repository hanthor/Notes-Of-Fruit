package com.example.applenotes.ui.common

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

/**
 * A one-shot message for a snackbar, optionally with an action (e.g. Retry).
 * [id] distinguishes two messages with the same text. The screen calls back
 * with the message when it was acted on or dismissed, so the ViewModel can
 * clear it (and it is not shown again after rotation).
 */
data class UiMessage<A>(
    val id: Long,
    val text: String,
    val action: A? = null,
    val actionLabel: String? = if (action != null) "Retry" else null,
)

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
            message = m.text,
            actionLabel = m.actionLabel,
            withDismissAction = m.action != null,
            duration = if (m.action != null) SnackbarDuration.Indefinite else SnackbarDuration.Short,
        )
        when (result) {
            SnackbarResult.ActionPerformed -> currentOnAction(m)
            SnackbarResult.Dismissed -> currentOnDismiss(m)
        }
    }
}
