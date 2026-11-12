package com.example.applenotes.widget

import com.example.applenotes.client.NoteSummary
import com.example.applenotes.integrations.LocalSurfaces
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class WidgetContentTest {
    private fun note(rn: String, title: String?, ts: Long, folder: String? = "DefaultFolder-CloudKit", deleted: Boolean = false) =
        NoteSummary(rn, title, "secret snippet", ts, deleted, folder)

    @Test
    fun appLockHidesEverythingEvenWhenSignedIn() {
        assertEquals(WidgetContent.Locked, WidgetContent.of(appLockOn = true, signedIn = true, notes = listOf(note("a", "Bank PINs", 1))))
        assertEquals(WidgetContent.Locked, WidgetContent.of(appLockOn = true, signedIn = false, notes = emptyList()))
    }

    @Test
    fun signedOutShowsNoContent() {
        // Even if a stale list is still around (sign-out clears the store asynchronously).
        assertEquals(WidgetContent.SignedOut, WidgetContent.of(false, signedIn = false, notes = listOf(note("a", "x", 1))))
    }

    @Test
    fun newestFirstAtMostEightTitlesOnlyNoTrash() {
        val notes = (1..12).map { note("n$it", "Note $it", it.toLong()) } +
            note("trash", "Deleted one", 100, folder = "TrashFolder-CloudKit") +
            note("gone", "Gone", 101, deleted = true) +
            note("blank", "   ", 50)
        val c = WidgetContent.of(false, true, notes) as WidgetContent.Notes
        assertEquals(WidgetContent.MAX_NOTES, c.notes.size)
        assertEquals(listOf("blank", "n12", "n11", "n10", "n9", "n8", "n7", "n6"), c.notes.map { it.recordName })
        assertEquals(null, c.notes.first().title)
        assertEquals("Note 12", c.notes[1].title)
    }

    @Test
    fun longTitlesAreCut() {
        val c = WidgetContent.of(false, true, listOf(note("a", "x".repeat(500), 1))) as WidgetContent.Notes
        assertEquals(80, c.notes.single().title!!.length)
    }

    @Test
    fun surfacesRefreshOnSignInOutAndListChangesButNotOnSnippetChurn() {
        val list = listOf(note("a", "A", 1))
        val signedIn = LocalSurfaces.snapshot(true, list)
        assertNotEquals(signedIn, LocalSurfaces.snapshot(false, list))
        assertNotEquals(signedIn, LocalSurfaces.snapshot(true, list + note("b", "B", 2)))
        assertNotEquals(signedIn, LocalSurfaces.snapshot(true, listOf(note("a", "A renamed", 1))))
        assertEquals(signedIn, LocalSurfaces.snapshot(true, listOf(note("a", "A", 1).copy(snippet = "other"))))
    }
}
