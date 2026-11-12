package com.example.applenotes.data

import com.example.applenotes.R
import com.example.applenotes.client.NotesError
import com.example.applenotes.ui.common.UiText
import com.example.applenotes.ui.common.uiText
import java.io.IOException
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
 * An exception whose message is meant for the user (e.g. "Couldn't open the
 * file"), so [userMessage] shows [uiText] instead of the raw [Throwable.message].
 */
internal interface UserFacingError {
    val uiText: UiText
}

/** An [IOException] with a translated message for the user (see [UserFacingError]). */
internal class UserFacingIOException(
    override val uiText: UiText,
    /** English text for logs and [Throwable.message]. */
    logMessage: String,
) : IOException(logMessage), UserFacingError

/**
 * The message shown to the user for [e]. Typed [NotesError]s get a plain,
 * friendly sentence; the raw server text is behind [errorDetails] instead.
 * Other exceptions (bugs, proto refusals) keep their own message.
 */
internal fun userMessage(e: Throwable): UiText = when (e) {
    is NotesError.AuthExpired -> uiText(R.string.error_session_expired)
    is NotesError.Offline -> uiText(R.string.error_offline)
    is NotesError.Throttled -> uiText(R.string.error_throttled, retryHint(e.retryAfterMs))
    is NotesError.Conflict -> uiText(R.string.error_conflict)
    // Normally handled (a retried create that had gone through); shown only if it escapes.
    is NotesError.AlreadyExists -> uiText(R.string.error_conflict)
    is NotesError.Server -> e.code?.let { uiText(R.string.error_server_with_code, e.status, it) }
        ?: uiText(R.string.error_server, e.status)
    is NotesError.Decode -> uiText(R.string.error_decode)
    is UserFacingError -> e.uiText
    else -> UiText.Plain(e.message ?: e.toString())
}

/** "Try again in a moment." / "Try again in 5 s." / "Try again in 2 min." */
internal fun retryHint(retryAfterMs: Long?): UiText = when {
    retryAfterMs == null -> uiText(R.string.retry_hint_moment)
    retryAfterMs < 60_000 -> ((retryAfterMs + 999) / 1000).toInt().let { UiText.Plural(R.plurals.retry_hint_seconds, it) }
    else -> ((retryAfterMs + 59_999) / 60_000).toInt().let { UiText.Plural(R.plurals.retry_hint_minutes, it) }
}

/**
 * Text for a "Details" dialog: the raw server response or low-level error
 * for a [NotesError], null for everything else (whose [userMessage] already
 * is the raw text).
 */
internal fun errorDetails(e: Throwable): String? =
    (e as? NotesError)?.let { err -> err.details?.let { "${err.message}\n\n$it" } ?: err.message }
