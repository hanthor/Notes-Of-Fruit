package com.example.applenotes.automation

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.security.MessageDigest

/** What an [Activity] knows about who started it. Call from onCreate. */
internal fun activityCallerSignals(activity: Activity, replyTo: PendingIntent?): CallerSignals {
    val intent = activity.intent
    return CallerSignals(
        entry = CallerSignals.Entry.ACTIVITY,
        sdkInt = Build.VERSION.SDK_INT,
        launchedFromPackage = if (Build.VERSION.SDK_INT >= 34) {
            runCatching { activity.launchedFromPackage }.getOrNull()
        } else {
            null
        },
        callingPackage = runCatching { activity.callingPackage }.getOrNull(),
        systemReferrerPackage = systemReferrer(activity, intent),
        resultPendingIntentCreator = replyTo?.let { runCatching { it.creatorPackage }.getOrNull() },
    )
}

/**
 * The referrer the system attached (the launching package), but only when
 * the intent has no EXTRA_REFERRER / EXTRA_REFERRER_NAME: getReferrer()
 * returns those as-is, and any caller can set them.
 */
private fun systemReferrer(activity: Activity, intent: Intent?): String? = runCatching {
    if (intent != null && (intent.hasExtra(Intent.EXTRA_REFERRER) || intent.hasExtra(Intent.EXTRA_REFERRER_NAME))) {
        return null
    }
    val uri = activity.referrer ?: return null
    if (uri.scheme != "android-app") return null
    uri.host?.takeIf { it.isNotBlank() }
}.getOrNull()

/** What a [BroadcastReceiver] knows about who sent the broadcast. Call from onReceive. */
internal fun receiverCallerSignals(receiver: BroadcastReceiver, replyTo: PendingIntent?): CallerSignals =
    CallerSignals(
        entry = CallerSignals.Entry.RECEIVER,
        sdkInt = Build.VERSION.SDK_INT,
        sentFromPackage = if (Build.VERSION.SDK_INT >= 34) {
            runCatching { receiver.sentFromPackage }.getOrNull()
        } else {
            null
        },
        resultPendingIntentCreator = replyTo?.let { runCatching { it.creatorPackage }.getOrNull() },
    )

/** Looks up an identified package: its label and signing certificates. Faked in tests. */
internal interface PackageLookup {
    /** null if the package isn't installed or isn't visible to this app. */
    fun verify(packageName: String): VerifiedCaller?
}

internal class AndroidPackageLookup(context: Context) : PackageLookup {
    private val pm = context.applicationContext.packageManager

    override fun verify(packageName: String): VerifiedCaller? = runCatching {
        val signatures: Array<Signature> = if (Build.VERSION.SDK_INT >= 28) {
            val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            // The certificates the APK is signed with now (all of them for
            // multi-signer APKs); a key rotation needs a new approval.
            info.signingInfo?.apkContentsSigners ?: emptyArray()
        } else {
            @Suppress("DEPRECATION")
            @android.annotation.SuppressLint("PackageManagerGetSignatures")
            val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
            info.signatures ?: emptyArray()
        }
        val certs = signatures.map { sha256Hex(it.toByteArray()) }
        if (certs.isEmpty()) return null
        val label = runCatching { pm.getApplicationInfo(packageName, 0).loadLabel(pm).toString() }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: packageName
        VerifiedCaller(packageName, AllowListRules.normalize(certs), label)
    }.getOrNull()
}

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
