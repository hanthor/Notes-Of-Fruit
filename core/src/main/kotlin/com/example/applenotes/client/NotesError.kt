package com.example.applenotes.client

import com.example.applenotes.auth.LENIENT_JSON
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.IOException

/**
 * Typed failures from the CloudKit client (issue #11). Callers branch on the
 * type (re-authenticate, back off, merge, tell the user they're offline)
 * instead of string-matching an `error("HTTP …")` message.
 *
 * [message] is short and safe to log: it never contains note text, response
 * bodies or account data. The raw server text, when there is any, is in
 * [details], which the UI only shows behind a "Details" action and which is
 * never logged outside the verbose debug level.
 */
sealed class NotesError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Raw server response / low-level error text for the "Details" dialog. */
    abstract val details: String?

    /**
     * The iCloud web session is no longer valid (HTTP 401 / 421 / 450, or a
     * serverErrorCode of AUTHENTICATION_FAILED / AUTHENTICATION_REQUIRED).
     * A fresh bootstrap from the cookie jar may fix it; otherwise the user
     * has to sign in again.
     */
    class AuthExpired(override val details: String? = null) :
        NotesError("iCloud session expired")

    /** The request never got an HTTP answer: no network, DNS failure, timeout, reset. */
    class Offline(cause: Throwable? = null) :
        NotesError("Network unreachable (${cause?.javaClass?.simpleName ?: "offline"})", cause) {
        override val details: String? = cause?.toString()
    }

    /**
     * The record changed on the server since the `recordChangeTag` we sent
     * (CloudKit oplock, serverErrorCode CONFLICT). [fresh] is the server's
     * current record when the client already has it; otherwise the caller
     * looks it up.
     */
    class Conflict(
        val fresh: NoteRecord? = null,
        override val details: String? = null,
    ) : NotesError("Record changed on the server (CONFLICT)")

    /** CloudKit asked us to slow down. [retryAfterMs] comes from `retryAfter` / Retry-After when given. */
    class Throttled(
        val retryAfterMs: Long?,
        override val details: String? = null,
    ) : NotesError("Throttled by iCloud" + (retryAfterMs?.let { " (retry after ${it}ms)" } ?: ""))

    /** Any other HTTP or per-record error. [status] is 200 for per-record errors in a 200 response. */
    class Server(
        val status: Int,
        val code: String?,
        override val details: String? = null,
    ) : NotesError("CloudKit error HTTP $status" + (code?.let { " ($it)" } ?: ""))

    /** The response arrived but wasn't what we expected (bad JSON, missing record). */
    class Decode(
        what: String,
        override val details: String? = null,
        cause: Throwable? = null,
    ) : NotesError("Unexpected response: $what", cause)
}

/** Maps CloudKit / transport failures onto [NotesError]. Pure, so it is unit tested directly. */
object CloudKitErrors {
    /**
     * HTTP statuses that mean "the web session is gone". 401 is CloudKit's
     * AUTHENTICATION_REQUIRED; iCloud's web services answer 421 (and
     * historically 450) when the session cookies have expired.
     */
    private val AUTH_STATUSES = setOf(401, 421, 450)
    private val AUTH_CODES = setOf("AUTHENTICATION_FAILED", "AUTHENTICATION_REQUIRED")
    private val THROTTLE_CODES = setOf("THROTTLED", "REQUEST_RATE_LIMITED", "TRY_AGAIN_LATER")
    private const val MAX_DETAILS = 4000

    /** Null for a 2xx status; otherwise the typed error for this response. */
    fun fromHttp(status: Int, body: String, retryAfterHeader: String? = null): NotesError? {
        if (status in 200..299) return null
        val details = body.take(MAX_DETAILS).ifEmpty { null }
        val json = runCatching { LENIENT_JSON.parseToJsonElement(body) as? JsonObject }.getOrNull()
        val code = json?.get("serverErrorCode")?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
        val retryAfterSec = json?.get("retryAfter")?.let { runCatching { it.jsonPrimitive.longOrNull }.getOrNull() }
            ?: retryAfterHeader?.trim()?.toLongOrNull()
        return when {
            status in AUTH_STATUSES || code in AUTH_CODES -> NotesError.AuthExpired(details)
            status == 429 || code in THROTTLE_CODES || (status == 503 && retryAfterSec != null) ->
                NotesError.Throttled(retryAfterSec?.times(1000), details)
            status == 409 || code == "CONFLICT" -> NotesError.Conflict(details = details)
            else -> NotesError.Server(status, code, details)
        }
    }

    /** A per-record `serverErrorCode` inside an otherwise successful response. */
    fun fromRecord(code: String, reason: String?, retryAfterSec: Long? = null): NotesError {
        val details = "serverErrorCode=$code" + (reason?.let { " reason=$it" } ?: "")
        return when {
            code == "CONFLICT" || reason?.contains("oplock", ignoreCase = true) == true ->
                NotesError.Conflict(details = details)
            code in AUTH_CODES -> NotesError.AuthExpired(details)
            code in THROTTLE_CODES -> NotesError.Throttled(retryAfterSec?.times(1000), details)
            else -> NotesError.Server(200, code, details)
        }
    }

    /**
     * Transport-level exceptions: anything I/O (UnknownHost, Connect,
     * SocketTimeout, connection reset, Ktor's request timeout, which are all
     * IOExceptions) becomes [NotesError.Offline]; JSON problems become
     * [NotesError.Decode]. Everything else is returned unchanged.
     */
    fun fromThrowable(t: Throwable): Throwable = when (t) {
        is NotesError -> t
        is IOException -> NotesError.Offline(t)
        is java.nio.channels.UnresolvedAddressException -> NotesError.Offline(t)
        is SerializationException -> NotesError.Decode("malformed JSON", cause = t)
        else -> t
    }
}
