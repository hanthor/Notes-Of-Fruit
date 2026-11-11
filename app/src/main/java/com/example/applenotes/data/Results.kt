package com.example.applenotes.data

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

/** The message shown to the user for [e]. */
internal fun userMessage(e: Throwable): String = e.message ?: e.toString()
