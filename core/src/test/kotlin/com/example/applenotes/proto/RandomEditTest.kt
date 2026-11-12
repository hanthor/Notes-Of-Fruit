package com.example.applenotes.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Seeded, deterministic random edit sequences through the real write paths
 * (appendBytes / setBodySpliceBytes), by three replicas, starting from
 * SYNTHETIC notes. After every step: full invariant check, CRDT
 * monotonicity, and the expected visible text.
 *
 * A failing seed prints its number in the assertion message; re-run just
 * that seed by narrowing SEEDS.
 */
class RandomEditTest {

    private val replicas = (1..3).map { testUuid(100 + it) }

    /** Code-point "atoms" we type: ASCII, accents, CJK, astral emoji, ZWJ/skin-tone sequences, separators. */
    private val atoms = listOf(
        "a", "b", "c", "Z", " ", " ", "\n", "\n", "\t", "é", "ß", "日", "本",
        "😀", "😁", "👍🏽", "👩‍💻",
        " ", " ", "￼",
    )

    private fun randomText(r: Random, maxAtoms: Int, allowObj: Boolean = true): String {
        val sb = StringBuilder()
        repeat(r.nextInt(maxAtoms + 1)) {
            var a = atoms[r.nextInt(atoms.size)]
            while (!allowObj && a == "￼") a = atoms[r.nextInt(atoms.size)]
            sb.append(a)
        }
        return sb.toString()
    }

    /** Valid cut points: never between a surrogate pair. */
    private fun cutPoints(s: String): List<Int> =
        (0..s.length).filter { it == 0 || it == s.length || !s[it - 1].isHighSurrogate() }

    private data class Edit(val from: Int, val to: Int, val insert: String)

    /**
     * One save's worth of changes: usually a single edit, sometimes two
     * disjoint ones (the editor batches keystrokes between saves, so a save
     * can change text in two places — e.g. on both sides of an attachment).
     * Returned right-to-left so they can be applied in order.
     */
    private fun randomEdits(r: Random, s: String, allowObj: Boolean = true): List<Edit> {
        val cuts = cutPoints(s)
        fun pick() = cuts[r.nextInt(cuts.size)]
        var i = pick()
        var j = pick()
        if (i > j) { val t = i; i = j; j = t }
        return when (r.nextInt(5)) {
            0 -> listOf(Edit(i, i, randomText(r, 6, allowObj).ifEmpty { "x" })) // insert
            1 -> listOf(Edit(i, j, "")) // delete
            2 -> listOf(Edit(i, j, randomText(r, 4, allowObj))) // replace
            3 -> listOf(Edit(s.length, s.length, randomText(r, 5, allowObj).ifEmpty { "y" })) // append
            else -> listOf( // two inserts, at j and at i
                Edit(j, j, randomText(r, 3, allowObj).ifEmpty { "q" }),
                Edit(i, i, randomText(r, 3, allowObj).ifEmpty { "p" }),
            )
        }
    }

    private fun apply(s: String, edits: List<Edit>) =
        edits.fold(s) { acc, e -> acc.substring(0, e.from) + e.insert + acc.substring(e.to) }

    @Test
    fun randomPlainTextEdits() {
        for (seed in SEEDS) {
            val r = Random(seed.toLong())
            var raw = randomText(r, 12)
            var bytes = NoteCreator.buildEmptyNoteBytes(replicas[0], raw)
            TopotextInvariants.checkInvariants(bytes, raw, replicas[0])
            for (step in 0 until STEPS) {
                val ctx = "seed=$seed step=$step"
                val who = replicas[r.nextInt(replicas.size)]
                val e = randomEdits(r, raw)
                val target = apply(raw, e)
                if (TopotextInvariants.normalise(target) == TopotextInvariants.normalise(raw)) continue
                val before = bytes
                val usedAppend = e.size == 1 && e[0].from == raw.length && e[0].to == raw.length && r.nextBoolean()
                bytes = if (usedAppend) {
                    NoteAppender.appendBytes(bytes, who, e[0].insert, 0)
                } else {
                    // Like the UI: half the time we hand back display text
                    // (U+2028/U+2029 shown as '\n').
                    val body = if (r.nextBoolean()) TopotextInvariants.normalise(target) else target
                    NoteAppender.setBodySpliceBytes(bytes, who, body, 0)
                }
                val d = try {
                    TopotextInvariants.checkInvariants(bytes, if (usedAppend) target else null, who)
                } catch (t: AssertionError) {
                    throw AssertionError("$ctx edit=$e: ${t.message}", t)
                }
                TopotextInvariants.checkMonotonic(before, bytes, who)
                val got = d.rawVisible()
                // The diff can't tell U+2028/U+2029 from '\n' (by design — see
                // NoteAppender), so compare in display space.
                assertEquals("$ctx edit=$e", TopotextInvariants.normalise(target), TopotextInvariants.normalise(got))
                raw = got
            }
        }
    }

    /**
     * UI-style saves: every save carries a full span list (as the editor's
     * spansDraft does), interleaved with format-only changes, on a note that
     * starts with real attachment runs (the U+FFFC chars carry f12).
     */
    @Test
    fun randomEditsWithSpansAndAttachments() {
        val styles = NoteBodyEditor.ParagraphStyle.values()
        for (seed in SEEDS) {
            val r = Random(seed * 31L + 5)
            // Start: text with two attachments, formatted by a synthetic run list.
            var raw = "Title\n" + randomText(r, 6, allowObj = false) + "\n￼" + randomText(r, 4, allowObj = false) +
                "￼\n" + randomText(r, 6, allowObj = false)
            var bytes = run {
                val base = NoteCreator.buildEmptyNoteBytes(replicas[0], raw)
                val runs = ArrayList<ByteArray>()
                var att = 0
                var i = 0
                while (i < raw.length) {
                    if (raw[i] == '￼') {
                        runs.add(Fx.run(1, Fx.attachment("ATT-${att++}")))
                        i++
                    } else {
                        var j = i
                        while (j < raw.length && raw[j] != '￼') j++
                        runs.add(Fx.run(j - i, Fx.style(3), Fx.varint(13, 1234)))
                        i = j
                    }
                }
                Fx.withRuns(base, runs)
            }
            TopotextInvariants.checkInvariants(bytes, raw)
            var model = NoteBodyEditor.expandSpansToChars(raw, Fx.spans(bytes)).toMutableList()
            val allIds = model.mapNotNull { it.attachmentRecordName }.toSet()
            fun objCharIds(b: ByteArray) =
                TopotextInvariants.parse(b).visibleCharIds().filter { it.first == '￼' }.map { it.second }.toSet()
            val originalObjChars = objCharIds(bytes)

            for (step in 0 until STEPS) {
                val ctx = "seed=$seed step=$step"
                val who = replicas[r.nextInt(replicas.size)]
                val before = bytes
                val shown = TopotextInvariants.normalise(raw)
                if (r.nextInt(3) == 0 && raw.isNotEmpty()) {
                    // Format-only change.
                    val cuts = cutPoints(raw)
                    var i = cuts[r.nextInt(cuts.size)]
                    var j = cuts[r.nextInt(cuts.size)]
                    if (i > j) { val t = i; i = j; j = t }
                    val spans = NoteBodyEditor.compactCharsToSpans(model.toTypedArray())
                    val newSpans = when (r.nextInt(4)) {
                        0 -> NoteBodyEditor.toggleInline(shown, spans, i, maxOf(j, i + 1).coerceAtMost(raw.length), NoteBodyEditor.InlineKind.values()[r.nextInt(4)])
                        1 -> NoteBodyEditor.setParagraphStyle(shown, spans, i, maxOf(j, i + 1), styles[r.nextInt(styles.size)])
                        2 -> NoteBodyEditor.toggleCheckbox(shown, spans, i.coerceAtMost(raw.length - 1))
                        else -> NoteBodyEditor.setLink(shown, spans, i, maxOf(j, i + 1).coerceAtMost(raw.length), if (r.nextBoolean()) "https://e.x/$step" else null)
                    }
                    model = NoteBodyEditor.expandSpansToChars(raw, newSpans).toMutableList()
                    bytes = NoteAppender.setBodySpliceBytes(bytes, who, shown, 0, newSpans)
                    TopotextInvariants.checkInvariants(bytes, raw)
                } else {
                    val e = randomEdits(r, raw, allowObj = false)
                    val target = apply(raw, e)
                    if (TopotextInvariants.normalise(target) == shown) continue
                    // Model: inserted chars copy the span before them (minus attachment), like the editor.
                    for (ed in e) {
                        val ins = (0 until ed.insert.length).map {
                            val left = model.getOrNull(ed.from - 1) ?: model.getOrNull(ed.to)
                            (left ?: NoteBodyEditor.AttrSpan(1, NoteBodyEditor.ParagraphStyle.BODY))
                                .copy(length = 1, isAttachment = false, attachmentRecordName = null, attachmentType = null)
                        }
                        model = (model.subList(0, ed.from) + ins + model.subList(ed.to, model.size)).toMutableList()
                    }
                    val newSpans = NoteBodyEditor.compactCharsToSpans(model.toTypedArray())
                    bytes = NoteAppender.setBodySpliceBytes(bytes, who, TopotextInvariants.normalise(target), 0, newSpans)
                    val d = try {
                        TopotextInvariants.checkInvariants(bytes, null, who)
                    } catch (t: AssertionError) {
                        throw AssertionError("$ctx edit=$e: ${t.message}", t)
                    }
                    TopotextInvariants.checkMonotonic(before, bytes, who)
                    assertEquals("$ctx edit=$e", TopotextInvariants.normalise(target), TopotextInvariants.normalise(d.rawVisible()))
                    raw = d.rawVisible()
                }

                // Formatting on the wire == the model we sent, char by char.
                val got = NoteBodyEditor.expandSpansToChars(raw, Fx.spans(bytes))
                assertEquals(ctx, raw.length, got.size)
                for (k in raw.indices) {
                    val want = model[k]
                    val have = got[k]
                    assertEquals("$ctx char $k style", want.style, have.style)
                    assertEquals("$ctx char $k bold", want.bold, have.bold)
                    assertEquals("$ctx char $k italic", want.italic, have.italic)
                    assertEquals("$ctx char $k underline", want.underline, have.underline)
                    assertEquals("$ctx char $k strike", want.strikethrough, have.strikethrough)
                    assertEquals("$ctx char $k link", want.linkUrl, have.linkUrl)
                    if (want.style == NoteBodyEditor.ParagraphStyle.CHECKBOX_LIST) {
                        assertEquals("$ctx char $k checked", want.checked, have.checked)
                    }
                    assertEquals("$ctx char $k attachment", raw[k] == '￼', have.isAttachment)
                }
                // Every surviving U+FFFC still carries one of the original attachments, each at most once.
                val ids = got.filter { it.isAttachment }.map { it.attachmentRecordName }
                assertEquals(ctx, raw.count { it == '￼' }, ids.size)
                assertEquals(ctx, ids.size, ids.toSet().size)
                assertTrue(ctx, allIds.containsAll(ids.filterNotNull()))
                // ...and is still the ORIGINAL character (same CRDT char id): an
                // attachment must never be tombstoned and re-typed as a new U+FFFC.
                assertTrue("$ctx attachment char re-created", originalObjChars.containsAll(objCharIds(bytes)))
                model = got.toMutableList()
            }
        }
    }

    companion object {
        private val SEEDS = 1..60
        private const val STEPS = 30
    }
}
