package com.example.applenotes.client

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
    }

    @Test
    fun malformedJsonIsDecodeAndOtherThrowablesPassThrough() {
        assertTrue(CloudKitErrors.fromThrowable(SerializationException("bad")) is NotesError.Decode)
        val bug = IllegalStateException("bug")
        assertSame(bug, CloudKitErrors.fromThrowable(bug))
        val typed = NotesError.AuthExpired()
        assertSame(typed, CloudKitErrors.fromThrowable(typed))
    }
}
