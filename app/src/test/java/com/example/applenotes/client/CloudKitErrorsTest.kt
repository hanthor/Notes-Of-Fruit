package com.example.applenotes.client

import com.example.applenotes.R
import com.example.applenotes.ui.common.UiText
import com.example.applenotes.ui.common.uiText
import com.example.applenotes.data.errorDetails
import com.example.applenotes.data.userMessage
import com.example.applenotes.data.UserFacingIOException
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class CloudKitErrorsTest {
    @Test
    fun successIsNotAnError() {
        assertNull(CloudKitErrors.fromHttp(200, "{}"))
        assertNull(CloudKitErrors.fromHttp(204, ""))
    }

    @Test
    fun authExpiryByStatus() {
        for (status in listOf(401, 421, 450)) {
            val e = CloudKitErrors.fromHttp(status, "Misdirected Request")
            assertTrue("$status -> $e", e is NotesError.AuthExpired)
            assertEquals("Misdirected Request", e!!.details)
        }
    }

    @Test
    fun authExpiryByServerErrorCode() {
        val body = """{"uuid":"x","serverErrorCode":"AUTHENTICATION_FAILED","reason":"no auth method found"}"""
        assertTrue(CloudKitErrors.fromHttp(400, body) is NotesError.AuthExpired)
        assertTrue(
            CloudKitErrors.fromHttp(403, """{"serverErrorCode":"AUTHENTICATION_REQUIRED"}""") is NotesError.AuthExpired,
        )
        assertTrue(CloudKitErrors.fromRecord("AUTHENTICATION_FAILED", null) is NotesError.AuthExpired)
        assertTrue(CloudKitErrors.fromRecord("AUTHENTICATION_REQUIRED", "x") is NotesError.AuthExpired)
    }

    @Test
    fun throttlingCarriesRetryAfter() {
        val header = CloudKitErrors.fromHttp(429, "", retryAfterHeader = "5") as NotesError.Throttled
        assertEquals(5_000L, header.retryAfterMs)
        val json = CloudKitErrors.fromHttp(503, """{"serverErrorCode":"TRY_AGAIN_LATER","retryAfter":12}""")
            as NotesError.Throttled
        assertEquals(12_000L, json.retryAfterMs)
        val none = CloudKitErrors.fromHttp(429, "slow down") as NotesError.Throttled
        assertNull(none.retryAfterMs)
        val perRecord = CloudKitErrors.fromRecord("THROTTLED", null, retryAfterSec = 3) as NotesError.Throttled
        assertEquals(3_000L, perRecord.retryAfterMs)
        assertTrue(CloudKitErrors.fromRecord("REQUEST_RATE_LIMITED", null) is NotesError.Throttled)
    }

    @Test
    fun conflictByCodeOrOplockReason() {
        assertTrue(CloudKitErrors.fromRecord("CONFLICT", "oplock error") is NotesError.Conflict)
        assertTrue(CloudKitErrors.fromRecord("UNKNOWN_ERROR", "record oplock failed") is NotesError.Conflict)
        assertTrue(CloudKitErrors.fromHttp(409, "{}") is NotesError.Conflict)
    }

    @Test
    fun otherErrorsAreServerErrorsWithTheirCode() {
        val e = CloudKitErrors.fromHttp(500, """{"serverErrorCode":"INTERNAL_ERROR","reason":"boom"}""")
            as NotesError.Server
        assertEquals(500, e.status)
        assertEquals("INTERNAL_ERROR", e.code)
        val r = CloudKitErrors.fromRecord("NOT_FOUND", "gone") as NotesError.Server
        assertEquals(200, r.status)
        assertEquals("NOT_FOUND", r.code)
        // Plain-text bodies (HTML error pages) still map.
        assertTrue(CloudKitErrors.fromHttp(502, "<html>Bad gateway</html>") is NotesError.Server)
    }

    @Test
    fun messageNeverCarriesTheBody() {
        val body = """{"serverErrorCode":"INTERNAL_ERROR","reason":"Shopping list: milk"}"""
        val e = CloudKitErrors.fromHttp(500, body)!!
        assertFalse(e.message!!.contains("Shopping"))
        assertTrue(e.details!!.contains("Shopping"))
        // ... and the user sees a friendly line, with the raw text behind Details.
        assertEquals(uiText(R.string.error_server_with_code, 500, "INTERNAL_ERROR"), userMessage(e))
        assertFalse(userMessage(e).toString().contains("Shopping"))
        assertTrue(errorDetails(e)!!.contains("Shopping"))
    }

    @Test
    fun ioFailuresAreOffline() {
        for (t in listOf(
            UnknownHostException("p123-ckdatabasews.icloud.com"),
            ConnectException("refused"),
            SocketTimeoutException("timeout"),
            java.io.IOException("connection reset"),
        )) {
            val mapped = CloudKitErrors.fromThrowable(t)
            assertTrue("$t", mapped is NotesError.Offline)
            assertSame(t, mapped.cause)
        }
        assertEquals(UiText.Res(R.string.error_offline), userMessage(NotesError.Offline()))
    }

    @Test
    fun malformedJsonIsDecodeAndOtherThrowablesPassThrough() {
        assertTrue(CloudKitErrors.fromThrowable(SerializationException("bad")) is NotesError.Decode)
        val bug = IllegalStateException("bug")
        assertSame(bug, CloudKitErrors.fromThrowable(bug))
        val typed = NotesError.AuthExpired()
        assertSame(typed, CloudKitErrors.fromThrowable(typed))
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
