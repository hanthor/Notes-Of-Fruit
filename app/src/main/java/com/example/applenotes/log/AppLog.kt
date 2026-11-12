package com.example.applenotes.log

import android.util.Log
import com.example.applenotes.BuildConfig

/**
 * The app's only door to logcat (issue #6). Every other file logs through
 * this object; `AppLogGuardTest` fails the build if `android.util.Log` shows
 * up anywhere else.
 *
 * Levels and what they may contain:
 *
 * - [d] / [i]: structural diagnostics (record names, lengths, HTTP status,
 *   timings). **No-ops in release builds.** Messages are lambdas so release
 *   builds don't even build the string.
 * - [w] / [e]: problems. In release builds only the message and the
 *   exception's class name are logged, never the exception's message or
 *   stack trace (which can carry server text). Messages passed here must
 *   never contain note text, response bodies or account data.
 * - [v]: "wire-shape" dumps: note text, titles/snippets, proto summaries,
 *   substring trees, replica dumps, raw response bodies. Off by default even
 *   in debug builds; [verbose] turns it on (the debug-only `DebugReceiver`
 *   does, for its adb-driven sessions). Never compiled into a useful state
 *   in release: [v] checks [debugBuild] first.
 *
 * Never log cookies, dsids, Apple IDs or full names at any level.
 */
object AppLog {
    /** True in debug builds. A var only so tests can exercise the release path. */
    @Volatile
    var debugBuild: Boolean = BuildConfig.DEBUG

    /**
     * Wire-shape dumps ([v]). Off by default, also in debug builds. Only
     * honoured when [debugBuild] is true.
     */
    @Volatile
    var verbose: Boolean = false

    /** Whether [v] messages are emitted right now. */
    val verboseEnabled: Boolean get() = debugBuild && verbose

    /** Test hook: where lines go. Defaults to logcat. */
    @Volatile
    internal var sink: Sink = LogcatSink

    fun interface Sink {
        fun write(priority: Int, tag: String, message: String, t: Throwable?)
    }

    private object LogcatSink : Sink {
        override fun write(priority: Int, tag: String, message: String, t: Throwable?) {
            when (priority) {
                Log.VERBOSE -> Log.v(tag, message, t)
                Log.DEBUG -> Log.d(tag, message, t)
                Log.INFO -> Log.i(tag, message, t)
                Log.WARN -> Log.w(tag, message, t)
                else -> Log.e(tag, message, t)
            }
        }
    }

    inline fun d(tag: String, message: () -> String) {
        if (debugBuild) emit(Log.DEBUG, tag, message(), null)
    }

    inline fun i(tag: String, message: () -> String) {
        if (debugBuild) emit(Log.INFO, tag, message(), null)
    }

    /** Wire-shape / note-content dump. See the class docs. */
    inline fun v(tag: String, message: () -> String) {
        if (verboseEnabled) emit(Log.DEBUG, tag, message(), null)
    }

    fun w(tag: String, message: String, t: Throwable? = null) = problem(Log.WARN, tag, message, t)

    fun e(tag: String, message: String, t: Throwable? = null) = problem(Log.ERROR, tag, message, t)

    private fun problem(priority: Int, tag: String, message: String, t: Throwable?) {
        if (debugBuild) {
            emit(priority, tag, message, t)
        } else {
            val suffix = t?.let { " (${it.javaClass.simpleName})" }.orEmpty()
            emit(priority, tag, message + suffix, null)
        }
    }

    @PublishedApi
    internal fun emit(priority: Int, tag: String, message: String, t: Throwable?) {
        sink.write(priority, tag, message, t)
    }
}
