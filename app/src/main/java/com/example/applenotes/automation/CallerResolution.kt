package com.example.applenotes.automation

/**
 * Who sent an automation request, worked out from what Android tells the
 * receiving component. docs/automation.md ("How the caller is identified")
 * explains each source and on which Android versions it can be trusted.
 *
 * Collected by [AutomationActivity] / [AutomationReceiver] (Android glue in
 * CallerSignalsAndroid.kt) and resolved here, in plain Kotlin, so the rules
 * are unit-tested per API level.
 */
internal data class CallerSignals(
    val entry: Entry,
    val sdkInt: Int,
    /** Activity.getLaunchedFromPackage() (API 34+; only set when the caller opted in to sharing its identity). */
    val launchedFromPackage: String? = null,
    /** Activity.getCallingPackage(): set only for startActivityForResult. */
    val callingPackage: String? = null,
    /**
     * The system-supplied referrer (Activity.getReferrer() with an
     * android-app: scheme), or null when the intent carried its own
     * EXTRA_REFERRER / EXTRA_REFERRER_NAME (which the caller can set to anything).
     */
    val systemReferrerPackage: String? = null,
    /** BroadcastReceiver.getSentFromPackage() (API 34+; only set when the sender opted in). */
    val sentFromPackage: String? = null,
    /** PendingIntent.getCreatorPackage() of `result_pending_intent`. */
    val resultPendingIntentCreator: String? = null,
) {
    enum class Entry { ACTIVITY, RECEIVER }
}

/** How a caller was identified (for diagnostics and docs; all sources are equally trusted once used). */
internal enum class CallerSource { LAUNCHED_FROM, CALLING_PACKAGE, REFERRER, SENT_FROM, PENDING_INTENT_CREATOR }

internal sealed interface CallerResolution {
    data class Identified(val packageName: String, val source: CallerSource) : CallerResolution

    /** No trustworthy identity. The request is refused; nothing is recorded. */
    data class Unidentified(val error: String) : CallerResolution
}

internal object CallerResolver {
    /**
     * Android 11 (API 30) is the first version where the system checks the
     * calling package an app passes to startActivity against its uid
     * (ActivityTaskManagerService.assertPackageMatchesCallingUid), so the
     * referrer derived from it can't be spoofed through that path. Before
     * that an app could claim another app's package name.
     */
    const val MIN_SDK_TRUSTED_REFERRER = 30

    const val UNIDENTIFIED_ACTIVITY =
        "Notes of Fruit couldn't tell which app sent this request. On Android 10 and older, start the activity " +
            "for a result or pass a result_pending_intent your app created. See docs/automation.md."
    const val UNIDENTIFIED_RECEIVER =
        "Broadcasts must include a result_pending_intent your app created, so Notes of Fruit can tell which " +
            "app sent them. Apps that can't do that (Tasker, MacroDroid, Automate) should start the " +
            "AutomationActivity instead. See docs/automation.md."
    const val CONFLICT =
        "result_pending_intent was created by a different app than the one that sent the request"

    fun resolve(s: CallerSignals): CallerResolution {
        val creator = s.resultPendingIntentCreator?.takeIf { it.isNotBlank() }
        val direct: Pair<String, CallerSource>? = when (s.entry) {
            CallerSignals.Entry.ACTIVITY ->
                s.launchedFromPackage.nonBlank()?.let { it to CallerSource.LAUNCHED_FROM }
                    ?: s.callingPackage.nonBlank()?.let { it to CallerSource.CALLING_PACKAGE }
                    ?: s.systemReferrerPackage.nonBlank()
                        ?.takeIf { s.sdkInt >= MIN_SDK_TRUSTED_REFERRER }
                        ?.let { it to CallerSource.REFERRER }
            CallerSignals.Entry.RECEIVER ->
                s.sentFromPackage.nonBlank()?.takeIf { s.sdkInt >= 34 }?.let { it to CallerSource.SENT_FROM }
        }
        if (direct != null) {
            // A PendingIntent can be passed on to other apps: one created by
            // app A arriving from app B must not let B act as A.
            if (creator != null && creator != direct.first) return CallerResolution.Unidentified(CONFLICT)
            return CallerResolution.Identified(direct.first, direct.second)
        }
        if (creator != null) return CallerResolution.Identified(creator, CallerSource.PENDING_INTENT_CREATOR)
        return CallerResolution.Unidentified(
            if (s.entry == CallerSignals.Entry.ACTIVITY) UNIDENTIFIED_ACTIVITY else UNIDENTIFIED_RECEIVER,
        )
    }

    private fun String?.nonBlank(): String? = this?.takeIf { it.isNotBlank() }
}
