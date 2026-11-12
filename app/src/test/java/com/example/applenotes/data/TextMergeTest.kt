package com.example.applenotes.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TextMergeTest {

    private fun clean(base: String, local: String, remote: String): TextMerge.Clean {
        val r = TextMerge.merge(base, local, remote)
        assertTrue("expected a clean merge, got $r", r is TextMerge.Clean)
        return (r as TextMerge.Clean).also { checkOrigins(it, base, local, remote) }
    }

    private fun conflict(base: String, local: String, remote: String) {
        assertSame(TextMerge.Conflict, TextMerge.merge(base, local, remote))
    }

    /** Every char of the merge really is the char its origin indices point at. */
    private fun checkOrigins(m: TextMerge.Clean, base: String, local: String, remote: String) {
        assertEquals(m.text.length, m.baseIndex.size)
        assertEquals(m.text.length, m.localIndex.size)
        assertEquals(m.text.length, m.remoteIndex.size)
        for (k in m.text.indices) {
            val c = m.text[k]
            assertTrue(m.localIndex[k] >= 0 || m.remoteIndex[k] >= 0)
            if (m.baseIndex[k] >= 0) assertEquals(c, base[m.baseIndex[k]])
            if (m.localIndex[k] >= 0) assertEquals(c, local[m.localIndex[k]])
            if (m.remoteIndex[k] >= 0) assertEquals(c, remote[m.remoteIndex[k]])
        }
        // Steps rebuild the merge from remote, ending exactly at it.
        if (m.text == remote) {
            assertTrue(m.steps.isEmpty())
        } else {
            assertEquals(m.text, m.steps.last())
        }
    }

    private val base = "Shopping\nmilk\neggs\nbread\nbutter"

    @Test
    fun nonOverlappingInsertionsBothLand() {
        val local = "Shopping\nmilk\noat milk\neggs\nbread\nbutter"
        val remote = "Shopping\nmilk\neggs\nbread\nbutter\njam"
        val m = clean(base, local, remote)
        assertEquals("Shopping\nmilk\noat milk\neggs\nbread\nbutter\njam", m.text)
        // One local change → one step, which is the final text.
        assertEquals(listOf(m.text), m.steps)
    }

    @Test
    fun editsToDifferentLinesMergeEvenWhenAdjacent() {
        val local = "Shopping\nMILK\neggs\nbread\nbutter"
        val remote = "Shopping\nmilk\n6 eggs\nbread\nbutter"
        assertEquals("Shopping\nMILK\n6 eggs\nbread\nbutter", clean(base, local, remote).text)
    }

    @Test
    fun overlappingEditsAreAConflict() {
        conflict(base, "Shopping\nmilk\nbrown eggs\nbread\nbutter", "Shopping\nmilk\n6 eggs\nbread\nbutter")
    }

    @Test
    fun deleteVersusEditOfTheSameLineIsAConflict() {
        conflict(base, "Shopping\nmilk\nbread\nbutter", "Shopping\nmilk\n6 eggs\nbread\nbutter")
    }

    @Test
    fun insertionsAtTheSamePointAreAConflict() {
        conflict(base, "Shopping\nmilk\ncheese\neggs\nbread\nbutter", "Shopping\nmilk\nham\neggs\nbread\nbutter")
    }

    @Test
    fun bothAppendingToTheLastLineIsAConflict() {
        // The order of the two appends would be a guess.
        conflict(base, "$base\njam", "$base\nhoney")
    }

    @Test
    fun insertionInsideTheOtherSidesRangeIsAConflict() {
        // Remote replaced lines 2-3; local inserted between them.
        conflict(base, "Shopping\nmilk\nX\neggs\nbread\nbutter", "Shopping\nM\nE\nbread\nbutter")
    }

    @Test
    fun remoteOnlyChangeGivesRemote() {
        val remote = "Shopping list\nmilk\neggs\nbread"
        val m = clean(base, base, remote)
        assertEquals(remote, m.text)
        assertTrue(m.steps.isEmpty())
    }

    @Test
    fun localOnlyChangeGivesLocal() {
        val local = "Shopping\nmilk\nbread\nbutter\ncoffee"
        assertEquals(local, clean(base, local, base).text)
    }

    @Test
    fun postCreateTagBumpIsTrivial() {
        // Mac bumped the record tag without touching the text: remote == base.
        val local = "$base\nmore"
        val m = clean(base, local, base)
        assertEquals(local, m.text)
        assertEquals(listOf(local), m.steps)
    }

    @Test
    fun identicalEditsAreTakenOnce() {
        val both = "Shopping\nmilk\n6 eggs\nbread\nbutter"
        val m = clean(base, both, both)
        assertEquals(both, m.text)
        assertTrue(m.steps.isEmpty())
    }

    @Test
    fun identicalEditPlusSeparateLocalEdit() {
        val local = "Shopping!\nmilk\n6 eggs\nbread\nbutter"
        val remote = "Shopping\nmilk\n6 eggs\nbread\nbutter"
        assertEquals("Shopping!\nmilk\n6 eggs\nbread\nbutter", clean(base, local, remote).text)
    }

    @Test
    fun emojiAndCjkLinesMergeWhole() {
        val b = "旅行 ✈️\n東京\n😀 smile\n大阪"
        val local = "旅行 ✈️\n東京タワー\n😀 smile\n大阪"
        val remote = "旅行 ✈️\n東京\n😀 smile\n大阪 🏯\n京都"
        val m = clean(b, local, remote)
        assertEquals("旅行 ✈️\n東京タワー\n😀 smile\n大阪 🏯\n京都", m.text)
        // No lone surrogates anywhere.
        for (i in m.text.indices) {
            if (m.text[i].isHighSurrogate()) assertTrue(m.text[i + 1].isLowSurrogate())
            if (m.text[i].isLowSurrogate()) assertTrue(m.text[i - 1].isHighSurrogate())
        }
        conflict(b, "旅行 ✈️\n東京\n😁 smile\n大阪", "旅行 ✈️\n東京\n😀 smiles\n大阪")
    }

    @Test
    fun editsNearAnAttachment() {
        val b = "Trip\nphoto:\n￼\nnotes"
        // Local edits the line above the attachment, remote the line below.
        val m = clean(b, "Trip\nphoto of the beach:\n￼\nnotes", "Trip\nphoto:\n￼\nnotes and more")
        assertEquals("Trip\nphoto of the beach:\n￼\nnotes and more", m.text)
        assertEquals(1, m.text.count { it == '￼' })
        // The attachment char comes from all three versions (it is stable).
        val k = m.text.indexOf('￼')
        assertTrue(m.baseIndex[k] >= 0 && m.localIndex[k] >= 0 && m.remoteIndex[k] >= 0)
        // Typing next to the attachment on both sides is a conflict.
        conflict(b, "Trip\nphoto:\n￼ caption\nnotes", "Trip\nphoto:\n￼!\nnotes")
        // Remote deleting the attachment while local edits elsewhere is fine.
        assertEquals("Trip!\nphoto:\nnotes", clean(b, "Trip!\nphoto:\n￼\nnotes", "Trip\nphoto:\nnotes").text)
    }

    @Test
    fun multipleLocalHunksProduceOneStepEach() {
        val b = "t\na\nb\nc\nd\ne"
        val local = "t\nA\nb\nc\nd\nE"
        val remote = "t\na\nb\nC\nd\ne"
        val m = clean(b, local, remote)
        assertEquals("t\nA\nb\nC\nd\nE", m.text)
        assertEquals(listOf("t\nA\nb\nC\nd\ne", "t\nA\nb\nC\nd\nE"), m.steps)
    }

    @Test
    fun emptyInputs() {
        assertEquals("hello", clean("", "hello", "").text)
        assertEquals("", clean("x", "", "x").text)
        conflict("", "a", "b")
        assertEquals("same", clean("", "same", "same").text)
    }

    /** A side that drops the base's final '\n' must not glue the other side's appended line onto it. */
    @Test
    fun droppingTheFinalNewlineNeverJoinsTwoLines() {
        // Remote edits the last line and drops its '\n'; local appends a line.
        conflict("a\nb\n", "a\nb\nc", "a\nB")
        // Mirror image: local drops it, remote appends.
        conflict("a\nb\n", "a\nB", "a\nb\nc")
        // Only the '\n' removed (no other change) still collides with an append.
        conflict("a\nb\n", "a\nb\nc", "a\nb")
        conflict("a\nb\n", "a\nb", "a\nb\nc")
    }

    @Test
    fun trailingNewlineEdgeCasesThatStillMerge() {
        // Appending after a terminated last line while the other side edits an earlier line.
        assertEquals("A\nb\nc", clean("a\nb\n", "a\nb\nc", "A\nb\n").text)
        // Remote drops the final '\n', local edits the first line: no line follows the unterminated one.
        assertEquals("A\nb", clean("a\nb\n", "A\nb\n", "a\nb").text)
        // Both add the same trailing newline.
        assertEquals("a\nb\n", clean("a\nb", "a\nb\n", "a\nb\n").text)
        // Local adds a trailing newline, remote edits the first line.
        assertEquals("A\nb\n", clean("a\nb", "a\nb\n", "A\nb").text)
    }

    /** Property test with and without trailing newlines: a clean merge never creates a line neither side has. */
    @Test
    fun randomEditsNeverJoinLines() {
        val rnd = Random(11)
        repeat(2000) { iter ->
            val baseLines = List(rnd.nextInt(0, 5)) { "b$it" }
            fun mutate(lines: List<String>, tag: String): List<String> {
                val out = lines.toMutableList()
                repeat(rnd.nextInt(0, 3)) {
                    when (rnd.nextInt(3)) {
                        0 -> out.add(rnd.nextInt(0, out.size + 1), "$tag${rnd.nextInt(1000)}")
                        1 -> if (out.isNotEmpty()) out.removeAt(rnd.nextInt(out.size))
                        else -> if (out.isNotEmpty()) {
                            val i = rnd.nextInt(out.size)
                            out[i] = out[i] + tag
                        }
                    }
                }
                return out
            }
            fun join(lines: List<String>, trailing: Boolean) =
                lines.joinToString("\n") + if (trailing && lines.isNotEmpty()) "\n" else ""
            val b = join(baseLines, rnd.nextBoolean())
            val l = join(mutate(baseLines, "L"), rnd.nextBoolean())
            val r = join(mutate(baseLines, "R"), rnd.nextBoolean())
            val res = TextMerge.merge(b, l, r)
            if (res is TextMerge.Clean) {
                checkOrigins(res, b, l, r)
                val known = (b.split("\n") + l.split("\n") + r.split("\n")).toSet()
                for (line in res.text.split("\n")) {
                    assertTrue("iter $iter made up '$line': b=$b l=$l r=$r m=${res.text}", line in known)
                }
            }
        }
    }

    @Test
    fun tokenizeKeepsNewlines() {
        assertEquals(listOf("a\n", "b"), TextMerge.tokenize("a\nb"))
        assertEquals(listOf("a\n", "\n", "b\n"), TextMerge.tokenize("a\n\nb\n"))
        assertEquals(emptyList<String>(), TextMerge.tokenize(""))
    }

    /**
     * Property test: for random line edits, a clean merge always
     * (a) has correct origins, (b) equals local when remote is untouched and
     * remote when local is untouched, (c) contains every line the user
     * added and every line the other device added.
     */
    @Test
    fun randomEditsNeverLoseText() {
        val rnd = Random(4)
        val words = listOf("alpha", "beta", "γάμμα", "デルタ", "😀", "￼", "", "eps")
        repeat(500) { iter ->
            val baseLines = List(rnd.nextInt(0, 8)) { words.random(rnd) + it }
            fun mutate(lines: List<String>, tag: String): List<String> {
                val out = lines.toMutableList()
                repeat(rnd.nextInt(0, 3)) {
                    when (rnd.nextInt(3)) {
                        0 -> out.add(rnd.nextInt(0, out.size + 1), "$tag${rnd.nextInt(1000)}")
                        1 -> if (out.isNotEmpty()) out.removeAt(rnd.nextInt(out.size))
                        else -> if (out.isNotEmpty()) {
                            val i = rnd.nextInt(out.size)
                            out[i] = out[i] + tag
                        }
                    }
                }
                return out
            }
            val b = baseLines.joinToString("\n")
            val l = mutate(baseLines, "L").joinToString("\n")
            val r = mutate(baseLines, "R").joinToString("\n")
            val res = TextMerge.merge(b, l, r)
            if (res is TextMerge.Clean) {
                checkOrigins(res, b, l, r)
                if (r == b) assertEquals("iter $iter", l, res.text)
                if (l == b) assertEquals("iter $iter", r, res.text)
                val merged = res.text.split("\n").toSet()
                for (line in l.split("\n") + r.split("\n")) {
                    if (line.contains('L') || line.contains('R')) {
                        assertTrue("iter $iter lost '$line': b=$b l=$l r=$r m=${res.text}", line in merged)
                    }
                }
            }
        }
    }
}
