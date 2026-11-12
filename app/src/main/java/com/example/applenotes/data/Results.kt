package com.example.applenotes.data

import com.example.applenotes.client.NotesError
import kotlin.coroutines.cancellation.CancellationException

/**
 * Like [runCatching], but rethrows [CancellationException] so a cancelled
 * coroutine is not mistaken for a failed network call.
 */
internal inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

/**
 * The message shown to the user for [e]. Typed [NotesError]s get a plain,
 * friendly sentence; the raw server text is behind [errorDetails] instead.
 * Other exceptions (bugs, proto refusals) keep their own message.
 */
internal fun userMessage(e: Throwable): String = when (e) {
    is NotesError.AuthExpired -> "Your iCloud session has expired. Sign in again to continue."
    is NotesError.Offline -> "You're offline. Check your connection and try again."
    is NotesError.Throttled -> "iCloud is busy right now." + retryHint(e.retryAfterMs)
    is NotesError.Conflict -> "This note changed on another device."
    is NotesError.Server -> "iCloud returned an error (HTTP ${e.status}" + (e.code?.let { ", $it" } ?: "") + ")."
    is NotesError.Decode -> "iCloud sent a response this app doesn't understand."
    else -> e.message ?: e.toString()
}

internal fun retryHint(retryAfterMs: Long?): String = when {
    retryAfterMs == null -> " Try again in a moment."
    retryAfterMs < 60_000 -> " Try again in ${(retryAfterMs + 999) / 1000}s."
    else -> " Try again in ${(retryAfterMs + 59_999) / 60_000} min."
}

/**
 * Text for a "Details" dialog: the raw server response or low-level error
 * for a [NotesError], null for everything else (whose [userMessage] already
 * is the raw text).
 */
internal fun errorDetails(e: Throwable): String? =
    (e as? NotesError)?.let { err -> err.details?.let { "${err.message}\n\n$it" } ?: err.message }
