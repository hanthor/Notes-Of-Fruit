package com.example.applenotes.automation

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.example.applenotes.MainActivity
import com.example.applenotes.integrations.IncomingRequest
import com.example.applenotes.integrations.PendingIncoming
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Automation entry point for apps that start activities: OPEN_NOTE, and
 * CREATE_NOTE / APPEND_TO_NOTE for apps that can't attach a PendingIntent to
 * a broadcast (Tasker, MacroDroid, Automate). Invisible (Theme.NoDisplay):
 * it decides, hands the work off and finishes in onCreate.
 *
 * Exported without a permission; protected by the in-app toggle and the
 * per-app allow-list. The caller is identified by what the system tells an
 * activity about who launched it (see [CallerResolver] and
 * docs/automation.md); an unidentified caller is refused.
 *
 * Results: `result_pending_intent` when present (also with the outcome of a
 * write), and the activity result for startActivityForResult callers. For a
 * write, the activity result only says the request was accepted
 * ([AutomationApi.RESULT_ACCEPTED]): the save outlives the activity.
 */
class AutomationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val replyTo = resultPendingIntent(intent)
        // Read before finish(): the launch identity belongs to this activity instance.
        val caller = CallerResolver.resolve(activityCallerSignals(this, replyTo))
        val processor = automationProcessor(this)
        // Local reads only (toggle, allow-list); a NoDisplay activity must
        // finish before onResume, and OPEN_NOTE must start the app while
        // this activity is still in front.
        val authorization = runBlocking { processor.authorize(intent.action, automationExtras(intent), caller) }
        when (authorization) {
            is AutomationProcessor.Authorization.Refused -> reply(replyTo, authorization.result)
            is AutomationProcessor.Authorization.Ok -> when (val request = authorization.request) {
                is AutomationRequest.Open -> reply(replyTo, open(request))
                is AutomationRequest.Create, is AutomationRequest.Append -> {
                    setResult(RESULT_OK, Intent().putExtra(AutomationApi.RESULT_ACCEPTED, true))
                    val app = applicationContext
                    AUTOMATION_SCOPE.launch {
                        val result = try {
                            withTimeout(AUTOMATION_TIMEOUT_MS) { processor.run(request) }
                        } catch (e: TimeoutCancellationException) {
                            AutomationResult.fail(TIMED_OUT)
                        }
                        deliver(app, replyTo, result)
                    }
                }
            }
        }
        finish()
    }

    private fun reply(replyTo: android.app.PendingIntent?, result: AutomationResult) {
        setResult(if (result.success) RESULT_OK else RESULT_CANCELED, resultFillIn(result))
        deliver(this, replyTo, result)
    }

    private fun open(request: AutomationRequest.Open): AutomationResult {
        PendingIncoming.post(
            request.recordName?.let { IncomingRequest.OpenNote(it) }
                ?: IncomingRequest.OpenNoteByTitle(request.title!!),
        )
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
        )
        // "Opened" means the request was handed to the app; a title that
        // matches no note shows a message in the app instead.
        return AutomationResult.ok(request.recordName)
    }
}
