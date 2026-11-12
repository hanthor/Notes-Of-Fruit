package com.example.applenotes.integrations

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * Process-wide hand-off from the entry points ([IntegrationEntryActivity],
 * [com.example.applenotes.automation.AutomationActivity], the import picker)
 * to the UI. MainActivity never parses request extras itself, so its exported
 * launcher entry can't be used to inject requests.
 *
 * One slot: a newer request replaces one that hasn't been picked up yet.
 * Once taken, [com.example.applenotes.ui.AppViewModel] keeps it (also in its
 * SavedStateHandle) until the user is signed in and the list is showing.
 */
internal object PendingIncoming {
    private val slot = MutableStateFlow<IncomingRequest?>(null)

    val request: StateFlow<IncomingRequest?> = slot.asStateFlow()

    fun post(request: IncomingRequest) {
        slot.value = request
    }

    /** Remove and return the waiting request, if any. */
    fun take(): IncomingRequest? = slot.getAndUpdate { null }
}
