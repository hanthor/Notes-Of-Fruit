package com.example.applenotes.security

import com.example.applenotes.security.AppLockPolicy.State
import org.junit.Assert.assertEquals
import org.junit.Test

class AppLockPolicyTest {
    private var now = 1_000_000L
    private val policy = AppLockPolicy(clock = { now }, timeoutMs = 60_000)

    private fun state() = policy.state.value

    @Test
    fun unknownUntilTheSettingIsRead() {
        assertEquals(State.UNKNOWN, state())
        // Lifecycle events before the setting is known change nothing.
        policy.onForeground()
        policy.onBackground()
        assertEquals(State.UNKNOWN, state())
    }

    @Test
    fun offByDefaultNeverLocks() {
        policy.setEnabled(false)
        assertEquals(State.UNLOCKED, state())
        policy.onBackground()
        now += 10 * 60_000
        policy.onForeground()
        assertEquals(State.UNLOCKED, state())
    }

    @Test
    fun coldStartWithTheLockOnIsLocked() {
        policy.onForeground()
        policy.setEnabled(true)
        assertEquals(State.LOCKED, state())
        policy.onAuthenticated()
        assertEquals(State.UNLOCKED, state())
    }

    @Test
    fun shortTripsToTheBackgroundDontLock() {
        policy.setEnabled(true)
        policy.onAuthenticated()
        policy.onBackground()
        now += 60_000 // exactly one minute: not "more than"
        policy.onForeground()
        assertEquals(State.UNLOCKED, state())
    }

    @Test
    fun moreThanAMinuteInTheBackgroundLocks() {
        policy.setEnabled(true)
        policy.onAuthenticated()
        policy.onBackground()
        now += 60_001
        policy.onForeground()
        assertEquals(State.LOCKED, state())
    }

    @Test
    fun theTimerRestartsOnEveryTripToTheBackground() {
        policy.setEnabled(true)
        policy.onAuthenticated()
        repeat(5) {
            policy.onBackground()
            now += 50_000
            policy.onForeground()
            now += 50_000
        }
        assertEquals(State.UNLOCKED, state())
    }

    @Test
    fun rotationDoesNotLock() {
        // Activity recreation = onStop + onStart within milliseconds.
        policy.setEnabled(true)
        policy.onAuthenticated()
        policy.onBackground()
        now += 50
        policy.onForeground()
        assertEquals(State.UNLOCKED, state())
    }

    @Test
    fun turningTheLockOnInSettingsDoesNotLockImmediately() {
        policy.setEnabled(false)
        policy.setEnabled(true)
        assertEquals(State.UNLOCKED, state())
        // …but it applies from the next long trip to the background.
        policy.onBackground()
        now += 120_000
        policy.onForeground()
        assertEquals(State.LOCKED, state())
    }

    @Test
    fun turningTheLockOffUnlocks() {
        policy.setEnabled(true)
        assertEquals(State.LOCKED, state())
        policy.setEnabled(false)
        assertEquals(State.UNLOCKED, state())
    }

    @Test
    fun staysLockedWhileLockedInTheBackground() {
        policy.setEnabled(true)
        policy.onBackground()
        now += 5_000
        policy.onForeground()
        assertEquals(State.LOCKED, state())
        policy.onAuthenticated()
        // The earlier background period doesn't count after unlocking.
        policy.onForeground()
        assertEquals(State.UNLOCKED, state())
    }
}
