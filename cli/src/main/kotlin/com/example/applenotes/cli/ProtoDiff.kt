package com.example.applenotes.cli

import com.example.applenotes.proto.NoteBodyEditor
import com.example.applenotes.proto.TopotextInvariants

/**
 * Structural diff of two topotext bodies, keyed by CRDT char IDs rather than
 * positions: what a remote or local edit actually did to the document.
 *
 * Char IDs are resolved through each document's replica table, so the same
 * character keeps its identity even when a writer re-orders the slot table.
 */
object ProtoDiff {
    /** One char ever inserted: its replica UUID (hex), clock, and the char if live. */
    private data class CharInfo(val uuid: String, val clock: Long, val live: Boolean, val ch: Char?)

    data class Range(val uuid: String, val fromClock: Long, val count: Int, val text: String?)

    data class AttrChange(val text: String, val before: String, val after: String)

    data class ClockChange(val uuid: String, val before: List<Long>?, val after: List<Long>?)

    data class Result(
        val textBefore: String,
        val textAfter: String,
        val substringsBefore: Int,
        val substringsAfter: Int,
        val added: List<Range>,
        val tombstoned: List<Range>,
        /** Should always be empty: a CRDT never forgets a char or revives a tombstone. */
        val vanished: List<Range>,
        val resurrected: List<Range>,
        val runsBefore: Int,
        val runsAfter: Int,
        /** Null when the attribute runs couldn't be lined up with the chars. */
        val attrChanges: List<AttrChange>?,
        val clocks: List<ClockChange>,
    ) {
        val identical: Boolean
            get() = added.isEmpty() && tombstoned.isEmpty() && vanished.isEmpty() && resurrected.isEmpty() &&
                attrChanges.isNullOrEmpty() && clocks.all { it.before == it.after } &&
                runsBefore == runsAfter && textBefore == textAfter
    }

    fun diff(a: ByteArray, b: ByteArray): Result {
        val da = TopotextInvariants.parse(a)
        val db = TopotextInvariants.parse(b)
        val ca = chars(da)
        val cb = chars(db)
        val keyA = ca.associateBy { it.uuid to it.clock }
        val keyB = cb.associateBy { it.uuid to it.clock }

        val added = ranges(cb.filter { (it.uuid to it.clock) !in keyA && it.live })
        // Inserted and deleted between the two captures: never visible in either.
        val addedDead = ranges(cb.filter { (it.uuid to it.clock) !in keyA && !it.live })
        val tombstoned = ranges(
            ca.filter { it.live && keyB[it.uuid to it.clock]?.live == false },
        ) + addedDead
        val vanished = ranges(ca.filter { (it.uuid to it.clock) !in keyB })
        val resurrected = ranges(
            ca.filter { !it.live }.mapNotNull { c -> keyB[c.uuid to c.clock]?.takeIf { it.live } },
        )

        val clocksA = da.clocks.associate { TopotextInvariants.hex(it.uuid) to it.counters }
        val clocksB = db.clocks.associate { TopotextInvariants.hex(it.uuid) to it.counters }
        val clocks = (clocksA.keys + clocksB.keys).map { ClockChange(it, clocksA[it], clocksB[it]) }

        return Result(
            textBefore = NoteBodyEditor.readVisibleText(a).orEmpty(),
            textAfter = NoteBodyEditor.readVisibleText(b).orEmpty(),
            substringsBefore = da.subs.size,
            substringsAfter = db.subs.size,
            added = added,
            tombstoned = tombstoned,
            vanished = vanished,
            resurrected = resurrected,
            runsBefore = da.runs.size,
            runsAfter = db.runs.size,
            attrChanges = attrChanges(a, da, b, db),
            clocks = clocks,
        )
    }

    private fun chars(d: TopotextInvariants.Doc): List<CharInfo> {
        val offs = d.flatOffsets()
        val out = ArrayList<CharInfo>()
        for (s in d.subs) {
            if (s.isDocStart || s.isSentinel || s.length == 0) continue
            val slot = (s.charId.replica - 1).toInt()
            val uuid = d.clocks.getOrNull(slot)?.let { TopotextInvariants.hex(it.uuid) } ?: "slot${s.charId.replica}"
            for (k in 0 until s.length) {
                val ch = if (s.tombstone) null else d.flat.getOrNull(offs[s.arrayIdx] + k)
                out.add(CharInfo(uuid, s.charId.clock + k, !s.tombstone, ch))
            }
        }
        return out
    }

    /** Groups chars into runs of consecutive clocks of one replica. */
    private fun ranges(chars: List<CharInfo>): List<Range> {
        val sorted = chars.sortedWith(compareBy({ it.uuid }, { it.clock }))
        val out = ArrayList<Range>()
        var i = 0
        while (i < sorted.size) {
            var j = i + 1
            while (j < sorted.size && sorted[j].uuid == sorted[i].uuid && sorted[j].clock == sorted[j - 1].clock + 1) j++
            val group = sorted.subList(i, j)
            val text = if (group.all { it.ch != null }) group.joinToString("") { it.ch.toString() } else null
            out.add(Range(sorted[i].uuid, sorted[i].clock, group.size, text))
            i = j
        }
        return out
    }

    /**
     * Chars visible in both documents whose formatting changed, grouped into
     * runs of consecutive visible chars with the same before/after.
     */
    private fun attrChanges(
        a: ByteArray,
        da: TopotextInvariants.Doc,
        b: ByteArray,
        db: TopotextInvariants.Doc,
    ): List<AttrChange>? {
        val beforeById = (perCharAttrs(a, da) ?: return null).associate { it.first to it.third }
        val perB = perCharAttrs(b, db) ?: return null
        val out = ArrayList<AttrChange>()
        var cur: Triple<StringBuilder, String, String>? = null
        var lastIndexInB = -2
        for ((idx, entry) in perB.withIndex()) {
            val (id, ch, after) = entry
            val before = beforeById[id]
            if (before == null || before == after) continue
            val c = cur
            if (c != null && c.second == before && c.third == after && idx == lastIndexInB + 1) {
                c.first.append(ch)
            } else {
                c?.let { out.add(AttrChange(it.first.toString(), it.second, it.third)) }
                cur = Triple(StringBuilder().append(ch), before, after)
            }
            lastIndexInB = idx
        }
        cur?.let { out.add(AttrChange(it.first.toString(), it.second, it.third)) }
        return out
    }

    /** (char id, char, formatting description) for every visible char, or null if they don't line up. */
    private fun perCharAttrs(bytes: ByteArray, d: TopotextInvariants.Doc): List<Triple<Pair<String, Long>, Char, String>>? {
        val ids = d.visibleCharIds()
        val text = NoteBodyEditor.readVisibleText(bytes) ?: return null
        if (text.length != ids.size) return null
        val spans = NoteBodyEditor.parseAttributeRuns(bytes)
        if (spans.isEmpty()) return ids.mapIndexed { i, (_, id) -> Triple(id, text[i], "(no runs)") }
        if (spans.sumOf { it.length } != text.length) return null
        val per = NoteBodyEditor.expandSpansToChars(text, spans)
        return ids.mapIndexed { i, (_, id) -> Triple(id, text[i], describe(per[i])) }
    }

    /** Compact formatting description, e.g. `HEADING bold link=https://…`. */
    fun describe(span: NoteBodyEditor.AttrSpan): String = buildList {
        add(span.style.name)
        if (span.style == NoteBodyEditor.ParagraphStyle.CHECKBOX_LIST) add(if (span.checked) "checked" else "unchecked")
        if (span.bold) add("bold")
        if (span.italic) add("italic")
        if (span.underline) add("underline")
        if (span.strikethrough) add("strikethrough")
        span.linkUrl?.let { add("link=$it") }
        if (span.isAttachment) add("attachment=${span.attachmentType ?: "?"}")
    }.joinToString(" ")
}
