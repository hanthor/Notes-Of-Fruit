package com.example.applenotes.automation

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.applenotes.MainActivity
import com.example.applenotes.R
import com.example.applenotes.data.AppGraph
import com.example.applenotes.integrations.IncomingRequest
import com.example.applenotes.integrations.PendingIncoming
import com.example.applenotes.log.AppLog

/**
 * "‹App› wants to add notes — Review": posted the first time an unapproved
 * app calls the automation API. Tapping it opens Settings → Allowed apps.
 * Without the notification permission (Android 13+) nothing is shown; the
 * request still appears in Allowed apps.
 */
internal class AndroidApprovalNotifier(context: Context) : ApprovalNotifier {
    private val ctx = context.applicationContext

    override fun notifyPending(app: PendingApp) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            AppLog.i(TAG) { "notification permission not granted; pending request only listed in Settings" }
            return
        }
        val manager = NotificationManagerCompat.from(ctx)
        ctx.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                ctx.getString(R.string.automation_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = ctx.getString(R.string.automation_channel_description) },
        )
        val review = PendingIntent.getActivity(
            ctx,
            0,
            Intent(ctx, AutomationReviewActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shortcut_note)
            .setContentTitle(ctx.getString(R.string.automation_request_title, app.label))
            .setContentText(ctx.getString(R.string.automation_request_text))
            .setContentIntent(review)
            .addAction(0, ctx.getString(R.string.automation_request_review), review)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .build()
        try {
            manager.notify(app.packageName, NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            AppLog.w(TAG, "couldn't post the automation request notification", e)
        }
    }

    companion object {
        private const val TAG = "NotesAutomation"
        const val CHANNEL_ID = "automation_requests"
        private const val NOTIFICATION_ID = 21

        /** Remove [packageName]'s request notification once the user decided. */
        fun cancel(context: Context, packageName: String) {
            NotificationManagerCompat.from(context).cancel(packageName, NOTIFICATION_ID)
        }
    }
}

/**
 * Target of the request notification: opens Settings → Allowed apps.
 * Not exported; only our own notification's PendingIntent starts it.
 */
class AutomationReviewActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PendingIncoming.post(IncomingRequest.ReviewAutomationApps)
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
        )
        finish()
    }
}

/** The production wiring of [AutomationProcessor]. */
internal fun automationProcessor(context: Context): AutomationProcessor {
    val app = context.applicationContext
    return AutomationProcessor(
        gate = AutomationSettings(app),
        repository = AppGraph.notesRepository(app),
        access = AutomationAccess(DataStoreAllowListStore(app), AndroidPackageLookup(app), AndroidApprovalNotifier(app)),
    ) { it.resolve(app.resources) }
}
