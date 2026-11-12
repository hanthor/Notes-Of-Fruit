package com.example.applenotes.automation

import kotlinx.serialization.Serializable

/**
 * The per-app allow-list for the automation API (issue #21). An app is
 * trusted by package name AND the SHA-256 digests of its signing
 * certificates, so a different app installed later under the same package
 * name is not trusted.
 *
 * Pending entries are callers that asked but haven't been approved. They hold
 * only who asked and when, never the request (no note text, no title): an
 * approval never replays anything, the user re-runs their task.
 */
@Serializable
internal data class AllowedApp(
    val packageName: String,
    /** Lower-case hex SHA-256 of each signing certificate, sorted. */
    val certSha256: List<String>,
    val label: String,
    val approvedAtMs: Long,
)

@Serializable
internal data class PendingApp(
    val packageName: String,
    /** The certificates seen when it asked; approving trusts exactly these. */
    val certSha256: List<String>,
    val label: String,
    val firstSeenMs: Long,
)

@Serializable
internal data class AllowListState(
    val approved: List<AllowedApp> = emptyList(),
    val pending: List<PendingApp> = emptyList(),
)

/** A caller whose package was identified and looked up in PackageManager. */
internal data class VerifiedCaller(val packageName: String, val certSha256: List<String>, val label: String)

internal sealed interface AccessDecision {
    data object Allowed : AccessDecision

    /**
     * Not approved (yet). [firstRequest] is true when this call created the
     * pending entry (or refreshed it after a certificate change): that's when
     * the user is notified. [certificateChanged] means the package was
     * approved, but under a different signing certificate.
     */
    data class NeedsApproval(
        val pending: PendingApp,
        val firstRequest: Boolean,
        val certificateChanged: Boolean,
    ) : AccessDecision
}

internal object AllowListRules {
    /** Oldest pending requests are dropped beyond this, so a noisy caller can't grow the store. */
    const val MAX_PENDING = 20

    fun normalize(certs: List<String>): List<String> = certs.map { it.lowercase() }.distinct().sorted()

    /** Whether [caller] may run requests, and the state to store afterwards. */
    fun decide(state: AllowListState, caller: VerifiedCaller, nowMs: Long): Pair<AccessDecision, AllowListState> {
        val certs = normalize(caller.certSha256)
        val approved = state.approved.firstOrNull { it.packageName == caller.packageName }
        if (approved != null && certs.isNotEmpty() && normalize(approved.certSha256) == certs) {
            return AccessDecision.Allowed to state
        }
        val existing = state.pending.firstOrNull { it.packageName == caller.packageName }
        val sameAsExisting = existing != null && normalize(existing.certSha256) == certs
        val pending = if (sameAsExisting) {
            existing!!.copy(label = caller.label)
        } else {
            PendingApp(caller.packageName, certs, caller.label, nowMs)
        }
        val others = state.pending.filter { it.packageName != caller.packageName }
        val newPending = (others + pending).sortedBy { it.firstSeenMs }.takeLast(MAX_PENDING)
        val decision = AccessDecision.NeedsApproval(
            pending = pending,
            firstRequest = !sameAsExisting,
            certificateChanged = approved != null,
        )
        return decision to state.copy(pending = newPending)
    }

    /**
     * Approve the pending request of [packageName], trusting the certificates
     * it was seen with. No-op if there is no such request.
     */
    fun approve(state: AllowListState, packageName: String, nowMs: Long): AllowListState {
        val p = state.pending.firstOrNull { it.packageName == packageName } ?: return state
        if (p.certSha256.isEmpty()) return state
        val entry = AllowedApp(p.packageName, normalize(p.certSha256), p.label, nowMs)
        return AllowListState(
            approved = state.approved.filter { it.packageName != packageName } + entry,
            pending = state.pending.filter { it.packageName != packageName },
        )
    }

    /** Forget [packageName]: revokes an approval and/or dismisses a pending request. */
    fun remove(state: AllowListState, packageName: String): AllowListState = AllowListState(
        approved = state.approved.filter { it.packageName != packageName },
        pending = state.pending.filter { it.packageName != packageName },
    )
}
