package com.example.applenotes.ui.common

import com.example.applenotes.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiTextTest {
    private val now = 1_700_000_000_000L

    @Test
    fun relativeTimeUsesStringResources() {
        assertEquals(UiText.Res(R.string.time_just_now), relativeTime(now - 30_000, now))
        assertEquals(UiText.Res(R.string.time_minutes_short, listOf(5)), relativeTime(now - 5 * 60_000, now))
        assertEquals(UiText.Res(R.string.time_hours_short, listOf(3)), relativeTime(now - 3 * 3_600_000, now))
        assertEquals(UiText.Res(R.string.time_days_short, listOf(2)), relativeTime(now - 2 * 86_400_000L, now))
        // Older: a formatted date, not ours to translate.
        assertTrue(relativeTime(now - 30 * 86_400_000L, now) is UiText.Plain)
    }

    @Test
    fun uiTextShorthandKeepsArgumentsInOrder() {
        val inner = UiText.Plain("HTTP 502")
        assertEquals(UiText.Res(R.string.list_refresh_failed, listOf(inner)), uiText(R.string.list_refresh_failed, inner))
        assertEquals(listOf(3, "Shopping"), UiText.Plural(R.plurals.list_notes_titled, 3, listOf(3, "Shopping")).args)
        assertEquals(listOf(4), UiText.Plural(R.plurals.unsent_changes, 4).args)
    }
}
