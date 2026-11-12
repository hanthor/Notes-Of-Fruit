package com.example.applenotes.log

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class AppLogTest {
    private data class Line(val priority: Int, val tag: String, val message: String, val t: Throwable?)

    private val lines = mutableListOf<Line>()
    private var savedDebug = false
    private var savedVerbose = false
    private lateinit var savedSink: AppLog.Sink

    @Before
    fun setUp() {
        savedDebug = AppLog.debugBuild
        savedVerbose = AppLog.verbose
        savedSink = AppLog.sink
        AppLog.sink = AppLog.Sink { p, tag, m, t -> lines += Line(p, tag, m, t) }
    }

    @After
    fun tearDown() {
        AppLog.debugBuild = savedDebug
        AppLog.verbose = savedVerbose
        AppLog.sink = savedSink
    }

    @Test
    fun releaseBuildDropsDebugAndInfoWithoutEvaluatingThem() {
        AppLog.debugBuild = false
        var evaluated = false
        AppLog.d("T") { evaluated = true; "secret note text" }
        AppLog.i("T") { evaluated = true; "secret note text" }
        assertFalse(evaluated)
        assertTrue(lines.isEmpty())
    }

    @Test
    fun releaseBuildLogsOnlyMessageAndExceptionClassForProblems() {
        AppLog.debugBuild = false
        AppLog.e("T", "save failed", IllegalStateException("body: Shopping milk eggs"))
        AppLog.w("T", "no throwable")
        val e = lines[0]
        assertEquals("save failed (IllegalStateException)", e.message)
        assertNull("the throwable (and its message / stack) must not reach logcat", e.t)
        assertFalse(e.message.contains("Shopping"))
        assertEquals("no throwable", lines[1].message)
    }

    @Test
    fun verboseIsOffByDefaultEvenInDebugAndNeverOnInRelease() {
        AppLog.debugBuild = true
        AppLog.verbose = false
        AppLog.v("T") { "dump" }
        assertTrue(lines.isEmpty())

        AppLog.verbose = true
        AppLog.v("T") { "dump" }
        assertEquals(1, lines.size)

        AppLog.debugBuild = false
        AppLog.v("T") { "dump" }
        assertEquals(1, lines.size)
        assertFalse(AppLog.verboseEnabled)
    }

    @Test
    fun verboseFlagDefaultsToOff() {
        // The compiled-in default, not whatever another test left behind.
        val src = sourceRoot().resolve("main/java/com/example/applenotes/log/AppLog.kt").readText()
        assertTrue(src.contains("var verbose: Boolean = false"))
    }

    @Test
    fun debugBuildPassesThrowableThrough() {
        AppLog.debugBuild = true
        val t = RuntimeException("x")
        AppLog.e("T", "m", t)
        assertEquals(t, lines.single().t)
    }

    /**
     * Guard for issue #6: nothing but AppLog may talk to android.util.Log,
     * so release builds can't leak note text through a stray Log.i.
     */
    @Test
    fun noDirectAndroidLogUsesOutsideTheWrapper() {
        val root = sourceRoot()
        // The pure-Kotlin modules (:core, :cli) are scanned too: they can't
        // link android.util.Log, but a println of note text would be the
        // same leak, so they must stay on CoreLog as well.
        val projectRoot = root.absoluteFile.parentFile.parentFile
        val otherModules = listOf("core", "cli").map { File(projectRoot, "$it/src/main") }
        assertTrue("can't find core/src/main from $projectRoot", otherModules[0].isDirectory)
        val dirs = listOf("main", "debug", "release").map { root.resolve(it) } + otherModules
        val offenders = dirs.flatMap { dir ->
            dir.walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                .filter { it.name != "AppLog.kt" }
                .filter { f ->
                    val text = f.readText()
                    val inCore = f.absolutePath.contains("${File.separator}core${File.separator}src")
                    text.contains("android.util.Log") ||
                        Regex("""(^|[^A-Za-z0-9_.])Log\.[a-z]+\(""", RegexOption.MULTILINE).containsMatchIn(text) ||
                        // :core is a library: console output there would bypass CoreLog.
                        (inCore && Regex("""(^|[^A-Za-z0-9_.])(println|print)\(""", RegexOption.MULTILINE).containsMatchIn(text))
                }
                .map { it.path }
                .toList()
        }
        assertTrue("Use com.example.applenotes.log.AppLog instead of android.util.Log in: $offenders", offenders.isEmpty())
    }

    /** :core logs through CoreLog; the app's bridge must keep AppLog's release rules. */
    @Test
    fun coreLogBridgeFollowsAppLogRules() {
        val saved = CoreLog.sink
        try {
            CoreLogBridge.install()
            AppLog.debugBuild = false
            var evaluated = false
            CoreLog.i("T") { evaluated = true; "note text" }
            CoreLog.v("T") { evaluated = true; "note text" }
            assertFalse(evaluated)
            CoreLog.e("T", "decode failed", IllegalStateException("body: Shopping"))
            assertEquals("decode failed (IllegalStateException)", lines.single().message)
            assertNull(lines.single().t)

            AppLog.debugBuild = true
            AppLog.verbose = true
            CoreLog.v("T") { "dump" }
            assertEquals("dump", lines.last().message)
        } finally {
            CoreLog.sink = saved
        }
    }

    @Test
    fun coreLogDefaultsToNoOutput() {
        assertFalse(CoreLog.Sink.NONE.debugEnabled)
        assertFalse(CoreLog.Sink.NONE.verboseEnabled)
    }

    private fun sourceRoot(): File {
        // Gradle runs unit tests from the module directory; IDEs sometimes
        // from the project root.
        val candidates = listOf(File("src"), File("app/src"))
        return candidates.firstOrNull { File(it, "main/java").isDirectory }
            ?: error("can't find app/src from ${File(".").absolutePath}")
    }
}
