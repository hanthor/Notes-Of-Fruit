package com.example.applenotes.update

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckTest {
    private class FakeStore : UpdateStore {
        override var enabled = true
        override var lastCheckMs: Long? = null
        override var latestTag: String? = null
        override var latestUrl: String? = null
        override var dismissedTag: String? = null
    }

    private val day = UpdateCheck.INTERVAL_MS

    @Test
    fun versionComparison() {
        assertTrue(UpdateCheck.isNewer("v0.0.3", "0.0.2"))
        assertTrue(UpdateCheck.isNewer("v0.1.0", "0.0.9"))
        assertTrue(UpdateCheck.isNewer("v1.0.0", "0.10.10"))
        assertTrue(UpdateCheck.isNewer("v0.10.0", "0.9.0")) // numeric, not lexical
        assertTrue(UpdateCheck.isNewer("v0.0.2.1", "0.0.2"))
        assertFalse(UpdateCheck.isNewer("v0.0.2", "0.0.2"))
        assertFalse(UpdateCheck.isNewer("v0.0.2", "0.0.2.0"))
        assertFalse(UpdateCheck.isNewer("v0.0.1", "0.0.2"))
        assertFalse(UpdateCheck.isNewer("nightly", "0.0.2"))
        assertFalse(UpdateCheck.isNewer("v0.0.3", "garbage"))
        // A suffix on either side is ignored for ordering.
        assertTrue(UpdateCheck.isNewer("v0.0.3-beta", "0.0.2"))
        assertEquals(listOf(0, 0, 2), UpdateCheck.parseVersion("0.0.2-debug"))
        assertNull(UpdateCheck.parseVersion("v"))
        assertNull(UpdateCheck.parseVersion("1..2"))
    }

    @Test
    fun onceADayGate() {
        val now = 10 * day
        assertTrue(UpdateCheck.isDue(enabled = true, versionName = "0.0.2", lastCheckMs = null, nowMs = now))
        assertFalse(UpdateCheck.isDue(true, "0.0.2", lastCheckMs = now - day + 1, nowMs = now))
        assertTrue(UpdateCheck.isDue(true, "0.0.2", lastCheckMs = now - day, nowMs = now))
        // Clock moved backwards: check rather than wait forever.
        assertTrue(UpdateCheck.isDue(true, "0.0.2", lastCheckMs = now + day, nowMs = now))
        // Off by default / switched off, and never in debug builds.
        assertFalse(UpdateCheck.isDue(false, "0.0.2", null, now))
        assertFalse(UpdateCheck.isDue(true, "0.0.2-debug", null, now))
    }

    @Test
    fun checksAtMostOnceADayAndShowsANewerRelease() = runTest {
        val store = FakeStore()
        var now = 100 * day
        var calls = 0
        val checker = UpdateChecker(store, "0.0.2", fetchLatest = {
            calls++
            """{"tag_name":"v0.0.3","html_url":"https://github.com/hanthor/Notes-Of-Fruit/releases/tag/v0.0.3","body":"…"}"""
        }, clock = { now })

        val first = checker.checkIfDue()
        assertEquals(AvailableUpdate("0.0.3", "https://github.com/hanthor/Notes-Of-Fruit/releases/tag/v0.0.3"), first)
        assertEquals(1, calls)

        now += day / 2
        // Within the day: no request, but the known update is still shown.
        assertEquals(first, checker.checkIfDue())
        assertEquals(1, calls)

        now += day
        checker.checkIfDue()
        assertEquals(2, calls)
    }

    @Test
    fun dismissedVersionStaysHiddenUntilANewerOne() = runTest {
        val store = FakeStore()
        var tag = "v0.0.3"
        var now = 0L
        val checker = UpdateChecker(store, "0.0.2", fetchLatest = { """{"tag_name":"$tag"}""" }, clock = { now })
        val update = checker.checkIfDue()!!
        // No html_url: the generic releases page.
        assertEquals(UpdateCheck.RELEASES_PAGE, update.url)
        checker.dismiss(update)
        assertNull(checker.storedUpdate())
        tag = "v0.0.4"
        now += day
        assertEquals("0.0.4", checker.checkIfDue()!!.version)
    }

    @Test
    fun nothingWhenOffDebugOlderOrFailing() = runTest {
        val off = FakeStore().apply { enabled = false }
        var calls = 0
        val fetch: suspend () -> String = { calls++; """{"tag_name":"v9.0.0"}""" }
        assertNull(UpdateChecker(off, "0.0.2", fetch).checkIfDue())
        assertNull(UpdateChecker(FakeStore(), "0.0.2-debug", fetch).checkIfDue())
        assertEquals(0, calls)

        assertNull(UpdateChecker(FakeStore(), "0.0.2", { """{"tag_name":"v0.0.2"}""" }).checkIfDue())

        val failing = FakeStore()
        assertNull(UpdateChecker(failing, "0.0.2", { error("HTTP 403") }, clock = { 5L }).checkIfDue())
        // The failed attempt still counts for the daily limit.
        assertEquals(5L, failing.lastCheckMs)
    }

    @Test
    fun onlyLinksToThisProjectsReleases() {
        assertEquals(UpdateCheck.RELEASES_PAGE, UpdateCheck.safeReleaseUrl("https://evil.example/releases/v1"))
        assertEquals(UpdateCheck.RELEASES_PAGE, UpdateCheck.safeReleaseUrl(null))
        val ok = "https://github.com/hanthor/Notes-Of-Fruit/releases/tag/v1.0.0"
        assertEquals(ok, UpdateCheck.safeReleaseUrl(ok))
    }
}
