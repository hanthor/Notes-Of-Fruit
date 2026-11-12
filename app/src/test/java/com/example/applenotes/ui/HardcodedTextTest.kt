package com.example.applenotes.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Issue #17: user-visible text lives in res/values/strings.xml. Android
 * lint's HardcodedText only looks at XML, so this catches the Compose /
 * Kotlin equivalents: a string literal (with letters in it) passed straight
 * to Text(), a contentDescription, a placeholder/label, or a Toast.
 *
 * String templates that start with a value ("$marker  ", "$icon $label")
 * are not flagged: they only arrange already-translated parts or symbols.
 */
class HardcodedTextTest {
    private val sourceRoot = File("src/main/java")

    /** file name → reason. Keep empty unless there is a real exception (e.g. a @Preview). */
    private val allowlist = emptyMap<String, String>()

    private val patterns = listOf(
        // Text("Literal …") / Text(text = "Literal …"), also across a line break.
        Regex("""\bText\(\s*(?:text\s*=\s*)?"[^"$\\]*[A-Za-z]"""),
        Regex("""\bcontentDescription\s*=\s*"[^"]*[A-Za-z]"""),
        Regex("""\b(?:placeholder|label)\s*=\s*\{\s*Text\(\s*"[^"$]*[A-Za-z]"""),
        Regex("""Toast\.makeText\([^,]+,\s*"[^"]*[A-Za-z]"""),
        Regex("""\b(?:setShortLabel|setLongLabel|setContentTitle|setContentText)\(\s*"[^"]*[A-Za-z]"""),
    )

    @Test
    fun noHardcodedUserVisibleTextInKotlin() {
        assertTrue("run from the app module", sourceRoot.isDirectory)
        val offenders = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name !in allowlist }
            .flatMap { file ->
                val text = file.readText()
                patterns.flatMap { p ->
                    p.findAll(text).map { m ->
                        val line = text.substring(0, m.range.first).count { it == '\n' } + 1
                        "${file.relativeTo(sourceRoot)}:$line: ${m.value.replace('\n', ' ')}"
                    }
                }
            }
            .toList()
        assertTrue(
            "Move these strings to res/values/strings.xml (stringResource / UiText):\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun theCheckCatchesALiteral() {
        val sample = "Row {\n    Text(\n        \"Hello\",\n    )\n    Icon(x, contentDescription = \"Close\")\n}"
        assertTrue(patterns[0].containsMatchIn(sample))
        assertTrue(patterns[1].containsMatchIn(sample))
        // A template that only arranges translated parts is fine.
        assertTrue(!patterns[0].containsMatchIn("Text(\"\$marker  \")"))
    }
}
