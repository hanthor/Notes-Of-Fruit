package com.example.applenotes.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.Button
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.example.applenotes.R
import com.example.applenotes.data.AppGraph
import com.example.applenotes.integrations.EntryActions
import com.example.applenotes.integrations.IntegrationEntryActivity
import com.example.applenotes.integrations.ShortcutToken
import com.example.applenotes.log.AppLog
import com.example.applenotes.security.AppLockSettings
import kotlinx.coroutines.flow.first

/**
 * Home-screen "Recent notes" widget (issue #20): the titles of the most
 * recently modified notes from the local cache (never the network) and a
 * New note button.
 *
 * Both open the app through [IntegrationEntryActivity], like the launcher
 * shortcuts: New note opens an empty, unsaved draft; a title opens that note,
 * carrying the per-install [ShortcutToken]. While the app lock is on the
 * widget shows "Locked" and the button only; after sign-out it shows no
 * content. It is refreshed by [LocalSurfaces] when the cache, the sign-in
 * state or the lock setting changes.
 */
class RecentNotesWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val content = runCatching { load(context.applicationContext) }
            .onFailure { AppLog.w(TAG, "couldn't load widget content", it) }
            // Fail closed: show nothing rather than risk showing titles.
            .getOrDefault(WidgetContent.SignedOut)
        val token = if (content is WidgetContent.Notes) ShortcutToken.get(context) else ""
        provideContent {
            GlanceTheme {
                WidgetBody(context, content, token)
            }
        }
    }

    companion object {
        private const val TAG = "NotesWidget"

        /** Reads the cache only; [AppLockSettings] reads as ON when it can't be read. */
        internal suspend fun load(app: Context): WidgetContent {
            if (AppLockSettings(app).enabled.first()) return WidgetContent.Locked
            val repo = AppGraph.notesRepository(app)
            if (!repo.hasSavedLogin()) return WidgetContent.SignedOut
            repo.loadCache()
            return WidgetContent.of(appLockOn = false, signedIn = repo.hasSavedLogin(), notes = repo.notes.value)
        }

        /** Re-render every placed widget. Safe to call when none is placed. */
        suspend fun refresh(context: Context) {
            runCatching { RecentNotesWidget().updateAll(context) }
                .onFailure { AppLog.w(TAG, "couldn't update widgets", it) }
        }
    }
}

/** Registered in the manifest; Glance does the AppWidgetProvider work. */
class RecentNotesWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RecentNotesWidget()
}

private fun newNoteIntent(context: Context): Intent =
    Intent(context, IntegrationEntryActivity::class.java)
        .setAction(EntryActions.NEW_NOTE)

private fun openNoteIntent(context: Context, recordName: String, token: String): Intent =
    Intent(context, IntegrationEntryActivity::class.java)
        .setAction(EntryActions.OPEN_NOTE_SHORTCUT)
        // A distinct data URI per note keeps the list items' PendingIntents
        // apart (extras alone don't make intents distinct). The entry activity
        // ignores it.
        .setData(Uri.Builder().scheme("nof-widget").authority("note").appendPath(recordName).build())
        .putExtra(EntryActions.EXTRA_RECORD_NAME, recordName)
        .putExtra(EntryActions.EXTRA_TOKEN, token)

@Composable
private fun WidgetBody(context: Context, content: WidgetContent, token: String) {
    val colors = GlanceTheme.colors
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(colors.widgetBackground)
            .cornerRadius(16.dp)
            .padding(12.dp),
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = context.getString(R.string.widget_title),
                style = TextStyle(color = colors.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold),
                modifier = GlanceModifier.defaultWeight(),
            )
            Button(
                text = context.getString(R.string.widget_new_note),
                onClick = actionStartActivity(newNoteIntent(context)),
            )
        }
        Spacer(modifier = GlanceModifier.height(8.dp))
        val message = when (content) {
            WidgetContent.Locked -> R.string.widget_locked
            WidgetContent.SignedOut -> R.string.widget_signed_out
            is WidgetContent.Notes -> if (content.notes.isEmpty()) R.string.widget_empty else null
        }
        if (message != null || content !is WidgetContent.Notes) {
            Text(
                text = context.getString(message ?: R.string.widget_signed_out),
                style = TextStyle(color = colors.onSurfaceVariant, fontSize = 14.sp),
            )
        } else {
            LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                items(content.notes, itemId = { it.recordName.hashCode().toLong() }) { entry ->
                    Text(
                        text = entry.title ?: context.getString(R.string.widget_untitled_note),
                        maxLines = 1,
                        style = TextStyle(color = colors.onSurface, fontSize = 14.sp),
                        modifier = GlanceModifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .clickable(actionStartActivity(openNoteIntent(context, entry.recordName, token))),
                    )
                }
            }
        }
    }
}
