package com.example.applenotes.automation

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.example.applenotes.MainActivity
import com.example.applenotes.integrations.IncomingRequest
import com.example.applenotes.integrations.PendingIncoming

/**
 * Automation OPEN_NOTE: shows a note in the app (an Activity, because a
 * broadcast receiver may not start activities from the background).
 * Same protection as [AutomationReceiver]: the AUTOMATE permission in the
 * manifest plus the in-app toggle. Opening is user-visible and read-only;
 * nothing about the note is returned to the caller beyond success/failure.
 */
class AutomationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val replyTo = resultPendingIntent(intent)
        val result = open()
        deliver(this, replyTo, result)
        finish()
    }

    private fun open(): AutomationResult {
        if (!AutomationSettings(this).isEnabled()) return AutomationResult.fail(AutomationProcessor.DISABLED)
        if (intent.action != AutomationApi.ACTION_OPEN_NOTE) return AutomationResult.fail("unknown action")
        val request = when (val p = parseAutomationRequest(intent.action, automationExtras(intent))) {
            is Parsed.Invalid -> return AutomationResult.fail(p.error)
            is Parsed.Ok -> p.request as? AutomationRequest.Open ?: return AutomationResult.fail("unknown action")
        }
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
