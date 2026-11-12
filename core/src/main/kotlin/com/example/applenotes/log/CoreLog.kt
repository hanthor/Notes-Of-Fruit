package com.example.applenotes.log

/**
 * Logging for the pure-Kotlin `:core` module (proto + client code).
 *
 * `:core` can't see logcat, so it logs through a [Sink] the host installs.
 * The default is [Sink.NONE]: nothing is built, nothing is written. The
 * Android app installs a sink backed by `AppLog` at startup
 * (`NotesOfFruitApp`), which keeps every rule of `AppLog` in force:
 * [d] / [i] are no-ops in release builds, [w] / [e] drop exception messages
 * in release builds, [v] (wire-shape dumps: note text, proto summaries,
 * response bodies) only runs when a debug build turned verbose logging on.
 * The CLI installs a stderr sink when asked for `--verbose`.
 *
 * Same contract as `AppLog`: messages are lambdas for the levels that can
 * carry content, so a disabled level never even builds the string. Never
 * log cookies, dsids, Apple IDs or full names at any level.
 */
object CoreLog {
    enum class Level { VERBOSE, DEBUG, INFO, WARN, ERROR }

    interface Sink {
        /** Whether [d] / [i] messages are wanted (false in release builds). */
        val debugEnabled: Boolean

        /** Whether [v] wire-shape dumps are wanted. */
        val verboseEnabled: Boolean

        fun write(level: Level, tag: String, message: String, t: Throwable?)

        companion object {
            /** Default: drop everything. */
            val NONE: Sink = object : Sink {
                override val debugEnabled get() = false
                override val verboseEnabled get() = false
                override fun write(level: Level, tag: String, message: String, t: Throwable?) = Unit
            }
        }
    }

    @Volatile
    var sink: Sink = Sink.NONE

    /** Whether [v] messages are emitted right now. */
    val verboseEnabled: Boolean get() = sink.verboseEnabled

    inline fun d(tag: String, message: () -> String) {
        val s = sink
        if (s.debugEnabled) s.write(Level.DEBUG, tag, message(), null)
    }

    inline fun i(tag: String, message: () -> String) {
        val s = sink
        if (s.debugEnabled) s.write(Level.INFO, tag, message(), null)
    }

    /** Wire-shape / note-content dump. See the class docs. */
    inline fun v(tag: String, message: () -> String) {
        val s = sink
        if (s.verboseEnabled) s.write(Level.VERBOSE, tag, message(), null)
    }

    /** A problem. [message] must never contain note text, response bodies or account data. */
    fun w(tag: String, message: String, t: Throwable? = null) = sink.write(Level.WARN, tag, message, t)

    /** A problem. [message] must never contain note text, response bodies or account data. */
    fun e(tag: String, message: String, t: Throwable? = null) = sink.write(Level.ERROR, tag, message, t)
}
