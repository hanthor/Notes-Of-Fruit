package com.example.applenotes.automation

import com.example.applenotes.log.AppLog

/** Tells the user an app asked to use automation (a notification). Faked in tests. */
internal fun interface ApprovalNotifier {
    fun notifyPending(app: PendingApp)
}

internal sealed interface AccessOutcome {
    data class Allowed(val caller: VerifiedCaller) : AccessOutcome
    data class Refused(val error: String) : AccessOutcome
}

/**
 * The allow-list check for one request: identify the caller, look up its
 * signing certificates, and compare with the approved apps. An unknown
 * caller becomes a pending request (package, label, certificates, first-seen
 * time; never the request itself) and the user gets a notification.
 */
internal class AutomationAccess(
    private val store: AllowListStore,
    private val packages: PackageLookup,
    private val notifier: ApprovalNotifier,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun check(resolution: CallerResolution): AccessOutcome {
        val identified = when (resolution) {
            is CallerResolution.Unidentified -> return AccessOutcome.Refused(resolution.error)
            is CallerResolution.Identified -> resolution
        }
        val caller = packages.verify(identified.packageName)
            ?: return AccessOutcome.Refused(notVisible(identified.packageName))
        // The common case (an approved app) only reads the store.
        if (AllowListRules.decide(store.current(), caller, clock()).first == AccessDecision.Allowed) {
            return AccessOutcome.Allowed(caller)
        }
        var decision: AccessDecision = AccessDecision.Allowed
        store.update { state ->
            val (d, next) = AllowListRules.decide(state, caller, clock())
            decision = d
            next
        }
        return when (val d = decision) {
            AccessDecision.Allowed -> AccessOutcome.Allowed(caller)
            is AccessDecision.NeedsApproval -> {
                AppLog.i(TAG) { "automation request from unapproved ${caller.packageName} via ${identified.source}" }
                if (d.firstRequest) notifier.notifyPending(d.pending)
                AccessOutcome.Refused(if (d.certificateChanged) certChanged(caller.label) else notApproved(caller.label))
            }
        }
    }

    companion object {
        private const val TAG = "NotesAutomation"

        fun notApproved(label: String) =
            "$label isn't allowed to use Notes of Fruit automation yet. Approve it in Notes of Fruit → Settings → " +
                "Allowed apps, then run the task again."

        fun certChanged(label: String) =
            "$label is signed with a different certificate than the one you approved. Approve it again in " +
                "Notes of Fruit → Settings → Allowed apps if you trust it."

        fun notVisible(packageName: String) =
            "Notes of Fruit can't look up the calling app ($packageName), so it can't be approved. See docs/automation.md."
    }
}
