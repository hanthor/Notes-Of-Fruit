package com.example.applenotes.data

import com.example.applenotes.client.CloudKitErrors
import com.example.applenotes.client.NotesError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The user-facing side of the typed errors. `userMessage` / `errorDetails`
 * live in the app; the error mapping itself is tested in `:core`
 * (CloudKitErrorsTest).
 */
class ErrorMessagesTest {
    @Test
    fun serverErrorShowsAFriendlyLineWithTheBodyBehindDetails() {
        val body = """{"serverErrorCode":"INTERNAL_ERROR","reason":"Shopping list: milk"}"""
        val e = CloudKitErrors.fromHttp(500, body)!!
        assertEquals("iCloud returned an error (HTTP 500, INTERNAL_ERROR).", userMessage(e))
        assertTrue(errorDetails(e)!!.contains("Shopping"))
    }

    @Test
    fun offlineMessage() {
        assertEquals("You're offline. Check your connection and try again.", userMessage(NotesError.Offline()))
    }

    @Test
    fun untypedErrorsKeepTheirMessageAndHaveNoDetails() {
        assertEquals("HTTP 503", userMessage(RuntimeException("HTTP 503")))
        assertNull(errorDetails(RuntimeException("HTTP 503")))
    }

    @Test
    fun throttledMessageMentionsTheWait() {
        assertEquals("iCloud is busy right now. Try again in 5s.", userMessage(NotesError.Throttled(4_200)))
        assertEquals("iCloud is busy right now. Try again in 2 min.", userMessage(NotesError.Throttled(90_000)))
    }
}
