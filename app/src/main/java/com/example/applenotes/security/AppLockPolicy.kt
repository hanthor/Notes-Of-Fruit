package com.example.applenotes.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * When the optional app lock (issue #16) hides the app behind a biometric /
 * device-credential prompt. Pure logic, driven by MainActivity:
 *
 * - [setEnabled] each time the stored setting is read or changes. The first
 *   value read in a process is the cold start: if the lock is on, lock.
 * - [onBackground] / [onForeground] from the activity's onStop / onStart.
 *   Coming back after more than [timeoutMs] in the background locks again.
 * - [onAuthenticated] after a successful prompt.
 *
 * One instance per process ([AppLock]), so recreating the activity
 * (rotation, dark mode) neither locks nor unlocks anything.
 */
class AppLockPolicy(
    private val clock: () -> Long,
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    enum class State {
        /** The setting hasn't been read yet: show nothing. */
        UNKNOWN,
        UNLOCKED,
        LOCKED,
    }

    private val _state = MutableStateFlow(State.UNKNOWN)
    val state: StateFlow<State> = _state.asStateFlow()

    private var enabled: Boolean? = null
    private var backgroundedAt: Long? = null

    fun setEnabled(value: Boolean) {
        val first = enabled == null
        enabled = value
        _state.value = when {
            !value -> State.UNLOCKED
            // Cold start with the lock on.
            first -> State.LOCKED
            // Turned on from Settings (after a successful prompt): no relock now.
            else -> _state.value.takeIf { it != State.UNKNOWN } ?: State.LOCKED
        }
    }

    fun onBackground() {
        // Already locked: stays locked whatever the time.
        if (_state.value == State.UNLOCKED) backgroundedAt = clock()
    }

    fun onForeground() {
        val since = backgroundedAt ?: return
        backgroundedAt = null
        if (enabled == true && clock() - since > timeoutMs) _state.value = State.LOCKED
    }

    fun onAuthenticated() {
        if (enabled != null) {
            backgroundedAt = null
            _state.value = State.UNLOCKED
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 60_000L
    }
}

/** The process-wide [AppLockPolicy]. */
object AppLock {
    val policy = AppLockPolicy(clock = { android.os.SystemClock.elapsedRealtime() })
}
