package com.example.applenotes.log

/**
 * Routes `:core`'s [CoreLog] through [AppLog], so the protocol code obeys the
 * same release-build rules as the rest of the app (no d/i lines, no
 * exception messages, no wire-shape dumps). Installed once at process start
 * by `NotesOfFruitApp`.
 */
object CoreLogBridge : CoreLog.Sink {
    override val debugEnabled: Boolean get() = AppLog.debugBuild
    override val verboseEnabled: Boolean get() = AppLog.verboseEnabled

    override fun write(level: CoreLog.Level, tag: String, message: String, t: Throwable?) {
        when (level) {
            CoreLog.Level.VERBOSE -> AppLog.v(tag) { message }
            CoreLog.Level.DEBUG -> AppLog.d(tag) { message }
            CoreLog.Level.INFO -> AppLog.i(tag) { message }
            CoreLog.Level.WARN -> AppLog.w(tag, message, t)
            CoreLog.Level.ERROR -> AppLog.e(tag, message, t)
        }
    }

    fun install() {
        CoreLog.sink = this
    }
}
