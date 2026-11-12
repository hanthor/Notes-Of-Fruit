package com.example.applenotes.data

import com.example.applenotes.R
import com.example.applenotes.client.CloudKitErrors
import com.example.applenotes.client.NotesError
import com.example.applenotes.ui.common.UiText
import com.example.applenotes.ui.common.uiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals(uiText(R.string.error_server_with_code, 500, "INTERNAL_ERROR"), userMessage(e))
        assertFalse(userMessage(e).toString().contains("Shopping"))
        assertTrue(errorDetails(e)!!.contains("Shopping"))
    }

    @Test
    fun offlineMessage() {
        assertEquals(UiText.Res(R.string.error_offline), userMessage(NotesError.Offline()))
    }

    @Test
    fun untypedErrorsKeepTheirMessageAndHaveNoDetails() {
        assertEquals(UiText.Plain("HTTP 503"), userMessage(RuntimeException("HTTP 503")))
        assertNull(errorDetails(RuntimeException("HTTP 503")))
    }

    @Test
    fun userFacingErrorsShowTheirTranslatedText() {
        val text = UiText.Res(R.string.import_not_text)
        assertEquals(text, userMessage(UserFacingIOException(text, "binary file")))
    }

    @Test
    fun throttledMessageMentionsTheWait() {
        // "iCloud is busy right now. Try again in 5 seconds." / "… in 2 minutes."
        assertEquals(
            uiText(R.string.error_throttled, UiText.Plural(R.plurals.retry_hint_seconds, 5)),
            userMessage(NotesError.Throttled(4_200)),
        )
        assertEquals(
            uiText(R.string.error_throttled, UiText.Plural(R.plurals.retry_hint_minutes, 2)),
            userMessage(NotesError.Throttled(90_000)),
        )
        assertEquals(
            uiText(R.string.error_throttled, UiText.Res(R.string.retry_hint_moment)),
            userMessage(NotesError.Throttled(null)),
        )
    }
}
