package com.example.applenotes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Note
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.applenotes.R

/**
 * Material's "expanded" width class starts at 840dp: tablets in landscape,
 * large foldables open, desktop windows (issue #16).
 */
internal const val TWO_PANE_MIN_WIDTH_DP = 840f

/** Whether a window [widthDp] wide shows the list and the open note side by side. */
internal fun isTwoPaneWidth(widthDp: Float): Boolean = widthDp >= TWO_PANE_MIN_WIDTH_DP

/** The screens that are drawn as list + detail when the window is wide. */
internal fun showsListAndDetail(screen: AppScreen): Boolean = when (screen) {
    AppScreen.NotesList, is AppScreen.Detail -> true
    is AppScreen.Loading -> screen.inDetailPane
    else -> false
}

/** The current window width (dp) decides between one and two panes. */
@Composable
internal fun rememberTwoPane(): Boolean {
    val widthPx = LocalWindowInfo.current.containerSize.width
    val widthDp = with(LocalDensity.current) { widthPx.toDp() }.value
    return isTwoPaneWidth(widthDp)
}

/** List on the left (stays composed, so its scroll and search survive), note on the right. */
@Composable
internal fun TwoPaneLayout(list: @Composable () -> Unit, detail: @Composable () -> Unit) {
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(0.4f).fillMaxHeight()) { list() }
        VerticalDivider()
        Box(Modifier.weight(0.6f).fillMaxHeight()) { detail() }
    }
}

/** The right pane with no note open. */
@Composable
internal fun NoNoteSelectedPane() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.Note,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.two_pane_no_note),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
