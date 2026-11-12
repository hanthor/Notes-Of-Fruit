package com.example.applenotes.automation

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.applenotes.log.AppLog
import com.example.applenotes.data.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

private const val TAG = "NotesAutomation"

/**
 * Headless automation API: CREATE_NOTE and APPEND_TO_NOTE (docs/automation.md).
 *
 * Protected in the manifest by the app's AUTOMATE permission (dangerous: the
 * calling app has to request it and the user has to grant it), and by the
 * in-app toggle (off by default), checked in [AutomationProcessor.handle].
 * Results go to the caller's optional `result_pending_intent`, never to logcat.
 */
class AutomationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != AutomationApi.ACTION_CREATE_NOTE && action != AutomationApi.ACTION_APPEND_TO_NOTE) return
        val extras = automationExtras(intent)
        val replyTo = resultPendingIntent(intent)
        val ordered = isOrderedBroadcast
        val pending = goAsync()
        val app = context.applicationContext
        SCOPE.launch {
            val result = try {
                withTimeout(TIMEOUT_MS) {
                    AutomationProcessor(AutomationSettings(app), AppGraph.notesRepository(app)) { it.resolve(app.resources) }
                        .handle(action, extras)
                }
            } catch (e: TimeoutCancellationException) {
                // The save itself keeps going in the repository's scope.
                AutomationResult.fail("Timed out waiting for iCloud; the change may still be saved")
            }
            deliver(app, replyTo, result)
            if (ordered) {
                // Also visible to callers that send an ordered broadcast.
                pending.resultCode = if (result.success) Activity.RESULT_OK else Activity.RESULT_CANCELED
                pending.resultData = result.recordName ?: result.error
            }
            pending.finish()
        }
    }

    companion object {
        /** Broadcast receivers get ~60 s in the background before an ANR; stay well inside it. */
        private const val TIMEOUT_MS = 45_000L

        // Main, like the editor's writes (AppGraph's externalScope).
        private val SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}

internal fun automationExtras(intent: Intent) = AutomationExtras(
    title = runCatching { intent.getStringExtra(AutomationApi.EXTRA_TITLE) }.getOrNull(),
    text = runCatching { intent.getStringExtra(AutomationApi.EXTRA_TEXT) }.getOrNull(),
    folder = runCatching { intent.getStringExtra(AutomationApi.EXTRA_FOLDER) }.getOrNull(),
    recordName = runCatching { intent.getStringExtra(AutomationApi.EXTRA_RECORD_NAME) }.getOrNull(),
)

internal fun resultPendingIntent(intent: Intent): PendingIntent? = runCatching {
    if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableExtra(AutomationApi.EXTRA_RESULT_PENDING_INTENT, PendingIntent::class.java)
    } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra(AutomationApi.EXTRA_RESULT_PENDING_INTENT)
    }
}.getOrNull()

/** Send [result] to the caller. The caller's PendingIntent must be FLAG_MUTABLE for the extras to arrive. */
internal fun deliver(context: Context, replyTo: PendingIntent?, result: AutomationResult) {
    if (replyTo == null) return
    val fillIn = Intent()
        .putExtra(AutomationApi.RESULT_SUCCESS, result.success)
        .apply {
            result.recordName?.let { putExtra(AutomationApi.RESULT_RECORD_NAME, it) }
            result.error?.let { putExtra(AutomationApi.RESULT_ERROR, it) }
        }
    try {
        replyTo.send(context, if (result.success) Activity.RESULT_OK else Activity.RESULT_CANCELED, fillIn)
    } catch (e: PendingIntent.CanceledException) {
        AppLog.w(TAG, "caller's result PendingIntent was cancelled")
    }
}
