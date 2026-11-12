package com.example.applenotes.automation

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.applenotes.log.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

private const val TAG = "NotesAutomation"

/**
 * Headless automation API over broadcasts: CREATE_NOTE and APPEND_TO_NOTE
 * (docs/automation.md). [AutomationActivity] accepts the same actions for
 * apps that can't attach a PendingIntent.
 *
 * Exported without a permission; protected by the in-app toggle (off by
 * default) and the per-app allow-list, both checked in
 * [AutomationProcessor.authorize]. A broadcast carries no sender identity
 * (except on Android 14+ when the sender opts in), so the caller must pass a
 * `result_pending_intent` it created: its creator package can't be forged.
 * Results go to that PendingIntent (and, for ordered broadcasts, the
 * broadcast result), never to logcat.
 */
class AutomationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != AutomationApi.ACTION_CREATE_NOTE && action != AutomationApi.ACTION_APPEND_TO_NOTE) return
        val extras = automationExtras(intent)
        val replyTo = resultPendingIntent(intent)
        val caller = CallerResolver.resolve(receiverCallerSignals(this, replyTo))
        val ordered = isOrderedBroadcast
        val pending = goAsync()
        val app = context.applicationContext
        AUTOMATION_SCOPE.launch {
            val result = try {
                withTimeout(AUTOMATION_TIMEOUT_MS) {
                    automationProcessor(app).handle(action, extras, caller)
                }
            } catch (e: TimeoutCancellationException) {
                // The save itself keeps going in the repository's scope.
                AutomationResult.fail(TIMED_OUT)
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
}

/** Broadcast receivers get ~60 s in the background before an ANR; stay well inside it. */
internal const val AUTOMATION_TIMEOUT_MS = 45_000L

// Main, like the editor's writes (AppGraph's externalScope).
internal val AUTOMATION_SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

internal const val TIMED_OUT = "Timed out waiting for iCloud; the change may still be saved"

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

/** The result extras (docs/automation.md "Results"). */
internal fun resultFillIn(result: AutomationResult): Intent = Intent()
    .putExtra(AutomationApi.RESULT_SUCCESS, result.success)
    .apply {
        result.recordName?.let { putExtra(AutomationApi.RESULT_RECORD_NAME, it) }
        result.error?.let { putExtra(AutomationApi.RESULT_ERROR, it) }
    }

/** Send [result] to the caller. The caller's PendingIntent must be FLAG_MUTABLE for the extras to arrive. */
internal fun deliver(context: Context, replyTo: PendingIntent?, result: AutomationResult) {
    if (replyTo == null) return
    val fillIn = resultFillIn(result)
    try {
        replyTo.send(context, if (result.success) Activity.RESULT_OK else Activity.RESULT_CANCELED, fillIn)
    } catch (e: PendingIntent.CanceledException) {
        AppLog.w(TAG, "caller's result PendingIntent was cancelled")
    }
}
