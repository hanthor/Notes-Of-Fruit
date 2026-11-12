package com.example.applenotes.proto

/**
 * Independent reader for the `versioned_document.Document → Version →
 * topotext.String` body proto, plus the invariant checker every write-path
 * test runs after each operation. Also used by `nof-cli check`.
 *
 * Deliberately does NOT reuse NoteAppender's private parsing so a bug there
 * can't hide itself. Field numbers are the ones documented in
 * docs/protocol.md ("The body proto: topotext.String").
 *
 * Failures are thrown as [AssertionError] (what JUnit reports as a test
 * failure), with the same message format JUnit's asserts use, so this lives
 * in main code without depending on JUnit.
 */
object TopotextInvariants {
    const val SENTINEL_CLOCK = 0xFFFFFFFFL

    data class CharId(val replica: Long, val clock: Long)

    data class Sub(
        val arrayIdx: Int,
        val charId: CharId,
        val length: Int,
        val ts: CharId,
        val tombstone: Boolean,
        val children: List<Int>,
        val raw: ByteArray,
    ) {
        val isDocStart get() = charId.replica == 0L && charId.clock == 0L && length == 0
        val isSentinel get() = charId.replica == 0L && charId.clock == SENTINEL_CLOCK
    }

    data class Clock(val uuid: ByteArray, val counters: List<Long>, val raw: ByteArray)

    data class Run(val length: Int, val raw: ByteArray) {
        val fields: List<ProtobufWire.Field> get() = ProtobufWire.decode(raw)
    }

    data class Doc(
        val topFields: List<ProtobufWire.Field>,
        val versionFields: List<ProtobufWire.Field>,
        /** All topotext.String fields in wire order. */
        val stringFields: List<ProtobufWire.Field>,
        val flat: String,
        val subs: List<Sub>,
        val clocks: List<Clock>,
        val runs: List<Run>,
    ) {
        /** Array indices visited by following child[0] from doc-start. */
        fun chain(): List<Int> {
            val out = ArrayList<Int>()
            val seen = BooleanArray(subs.size)
            var cur = 0
            while (cur in subs.indices && !seen[cur]) {
                seen[cur] = true
                out.add(cur)
                cur = subs[cur].children.firstOrNull() ?: break
            }
            return out
        }

        /** Offset of each substring's chars inside [flat] (array order, live only). */
        fun flatOffsets(): IntArray {
            val offs = IntArray(subs.size)
            var off = 0
            for (s in subs) {
                offs[s.arrayIdx] = off
                if (!s.tombstone) off += s.length
            }
            return offs
        }

        /** (char, (replica UUID hex, clock)) for every visible char, in visible order. */
        fun visibleCharIds(): List<Pair<Char, Pair<String, Long>>> {
            val offs = flatOffsets()
            val out = ArrayList<Pair<Char, Pair<String, Long>>>()
            for (i in chain()) {
                val s = subs[i]
                if (s.tombstone || s.isSentinel || s.length == 0) continue
                val uuid = hex(clocks[(s.charId.replica - 1).toInt()].uuid)
                for (k in 0 until s.length) out.add(flat[offs[i] + k] to (uuid to s.charId.clock + k))
            }
            return out
        }

        /** Raw visible text (no U+2028/U+2029 normalisation): tree walk, live chars only. */
        fun rawVisible(): String {
            val offs = flatOffsets()
            val sb = StringBuilder()
            for (i in chain()) {
                val s = subs[i]
                if (s.tombstone || s.isSentinel || s.length == 0) continue
                sb.append(flat, offs[i], offs[i] + s.length)
            }
            return sb.toString()
        }

        fun liveLength(): Int = subs.filter { !it.tombstone }.sumOf { it.length }

        fun unknownStringFields(): List<ProtobufWire.Field> =
            stringFields.filter { it.fieldNumber !in setOf(2, 3, 4, 5) }
    }

    private fun ld(fields: List<ProtobufWire.Field>, n: Int) =
        fields.filter { it.fieldNumber == n && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM }

    private fun varint(fields: List<ProtobufWire.Field>, n: Int): Long? =
        fields.firstOrNull { it.fieldNumber == n && it.wireType == ProtobufWire.WIRE_VARINT }
            ?.let { ProtobufWire.decodeVarint(it) }

    private fun charId(payload: ByteArray?): CharId {
        if (payload == null) return CharId(0, 0)
        val f = ProtobufWire.decode(payload)
        return CharId(varint(f, 1) ?: 0L, varint(f, 2) ?: 0L)
    }

    fun parse(bytes: ByteArray): Doc {
        val top = ProtobufWire.decode(bytes)
        val versions = ld(top, 2)
        assertEquals("exactly one Version", 1, versions.size)
        val vf = ProtobufWire.decode(versions[0].payload)
        val data = ld(vf, 3).single()
        val sf = ProtobufWire.decode(data.payload)
        val flat = ld(sf, 2).singleOrNull()?.payload?.decodeToString() ?: ""
        val subs = ld(sf, 3).mapIndexed { i, f ->
            val s = ProtobufWire.decode(f.payload)
            Sub(
                arrayIdx = i,
                charId = charId(ld(s, 1).firstOrNull()?.payload),
                length = (varint(s, 2) ?: 0L).toInt(),
                ts = charId(ld(s, 3).firstOrNull()?.payload),
                tombstone = (varint(s, 4) ?: 0L) != 0L,
                children = s.filter { it.fieldNumber == 5 && it.wireType == ProtobufWire.WIRE_VARINT }
                    .map { ProtobufWire.decodeVarint(it).toInt() },
                raw = f.payload,
            )
        }
        val vt = ld(sf, 4).singleOrNull()
        val clocks = if (vt == null) emptyList() else ld(ProtobufWire.decode(vt.payload), 1).map { c ->
            val cf = ProtobufWire.decode(c.payload)
            Clock(
                uuid = ld(cf, 1).firstOrNull()?.payload ?: ByteArray(0),
                counters = ld(cf, 2).map { varint(ProtobufWire.decode(it.payload), 1) ?: 0L },
                raw = c.payload,
            )
        }
        val runs = ld(sf, 5).map { Run((varint(ProtobufWire.decode(it.payload), 1) ?: 0L).toInt(), it.payload) }
        return Doc(top, vf, sf, flat, subs, clocks, runs)
    }

    fun dump(bytes: ByteArray): String = buildString {
        val d = parse(bytes)
        append("flat=").append(d.flat.replace("\n", "\\n")).append('\n')
        for (s in d.subs) {
            append("[${s.arrayIdx}] id=(${s.charId.replica},${s.charId.clock}) len=${s.length} ")
            append("ts=(${s.ts.replica},${s.ts.clock}) tomb=${s.tombstone} children=${s.children}\n")
        }
        for ((i, c) in d.clocks.withIndex()) append("clock#${i + 1} ${hex(c.uuid).take(8)} ${c.counters}\n")
        for (r in d.runs) append("run len=${r.length} fields=${r.fields.map { it.fieldNumber }}\n")
    }

    fun hex(b: ByteArray) = b.joinToString("") { ((it.toInt() and 0xFF) + 0x100).toString(16).substring(1) }

    /**
     * Check every structural invariant we rely on. [strictChain] additionally
     * requires that the doc is a single linear chain in array order (what our
     * own writers always produce); real Apple notes only need the weaker form.
     *
     * Units: substring lengths and attribute-run lengths are UTF-16 code
     * units (NSString length) everywhere in the code base — NoteAppender uses
     * `String.length`, NoteBodyEditor.utf16Length documents it — so all sums
     * here are in UTF-16 code units too.
     */
    fun checkInvariants(
        bytes: ByteArray,
        expectedRawVisible: String? = null,
        ourUuid: ByteArray? = null,
        strictChain: Boolean = true,
    ): Doc = try {
        checkInvariantsImpl(bytes, expectedRawVisible, ourUuid, strictChain)
    } catch (e: AssertionError) {
        // Attach a readable dump of the offending proto (built only on failure).
        throw AssertionError(e.message + "\n" + runCatching { dump(bytes) }.getOrElse { "(dump failed: $it)" }, e)
    }

    private fun checkInvariantsImpl(
        bytes: ByteArray,
        expectedRawVisible: String?,
        ourUuid: ByteArray?,
        strictChain: Boolean,
    ): Doc {
        val d = parse(bytes)
        assertTrue("need doc-start + sentinel", d.subs.size >= 2)

        // Doc-start first, sentinel last.
        val first = d.subs.first()
        assertEquals("doc-start charID", CharId(0, 0), first.charId)
        assertEquals("doc-start length", 0, first.length)
        val last = d.subs.last()
        assertEquals("sentinel charID", CharId(0, SENTINEL_CLOCK), last.charId)
        assertEquals("sentinel length", 0, last.length)
        assertTrue("sentinel has no children", last.children.isEmpty())
        assertEquals("exactly one sentinel", 1, d.subs.count { it.isSentinel })

        // Children are forward links within range.
        for (s in d.subs) for (c in s.children) {
            assertTrue("child $c of [${s.arrayIdx}] out of range", c in d.subs.indices)
            assertTrue("child $c of [${s.arrayIdx}] is not a forward link", c > s.arrayIdx)
        }

        // Walk reaches the sentinel.
        val chain = d.chain()
        assertEquals("walk ends at sentinel", d.subs.lastIndex, chain.last())
        if (strictChain) {
            assertEquals("walk visits every substring", d.subs.size, chain.size)
            assertEquals("chain is array order", d.subs.indices.toList(), chain)
            for (s in d.subs) assertTrue("at most one child", s.children.size <= 1)
        }

        // String.string holds exactly the live chars.
        assertEquals("flat length == live substring lengths", d.liveLength(), d.flat.length)
        // No live substring boundary splits a surrogate pair.
        run {
            val offs = d.flatOffsets()
            for (s in d.subs) {
                if (s.tombstone || s.length == 0) continue
                val chars = d.flat.substring(offs[s.arrayIdx], offs[s.arrayIdx] + s.length)
                assertTrue("substring [${s.arrayIdx}] starts mid surrogate pair", !chars.first().isLowSurrogate())
                assertTrue("substring [${s.arrayIdx}] ends mid surrogate pair", !chars.last().isHighSurrogate())
            }
        }
        val raw = d.rawVisible()
        if (expectedRawVisible != null) assertEquals("visible text", expectedRawVisible, raw)
        // The production reader must agree with our independent walk.
        assertEquals(
            "NoteBodyEditor.readVisibleText disagrees",
            normalise(raw), NoteBodyEditor.readVisibleText(bytes),
        )
        assertEquals(NoteProtoKind.NOTE_STORE_PROTO, NoteBodyEditor.probe(bytes))

        // Attribute runs cover the visible text exactly (UTF-16 units).
        if (raw.isNotEmpty() || d.runs.isNotEmpty()) {
            assertEquals("attribute-run lengths sum to visible length", raw.length, d.runs.sumOf { it.length })
        }
        for (r in d.runs) assertTrue("zero/negative run length", r.length > 0)
        // An attachment run (f12) must cover exactly one U+FFFC.
        run {
            var p = 0
            for (r in d.runs) {
                val hasAttachment = r.fields.any { it.fieldNumber == 12 }
                if (hasAttachment) {
                    assertEquals("attachment run length", 1, r.length)
                    assertEquals("attachment run covers U+FFFC", '\uFFFC', raw[p])
                }
                p += r.length
            }
        }

        // Replica registry.
        assertTrue("at least one replica", d.clocks.isNotEmpty())
        val uuidKeys = d.clocks.map { hex(it.uuid) }
        assertEquals("replica UUIDs unique", uuidKeys.size, uuidKeys.toSet().size)
        for (c in d.clocks) {
            assertEquals("replica UUID is 16 bytes", 16, c.uuid.size)
            assertEquals("two ReplicaClocks per replica", 2, c.counters.size)
        }
        if (ourUuid != null) {
            assertArrayEquals("our replica must be slot 1 after an edit", ourUuid, d.clocks[0].uuid)
        }
        for (s in d.subs) {
            if (s.isDocStart || s.isSentinel) continue
            assertTrue("charID replica ${s.charId.replica} not registered", s.charId.replica in 1..d.clocks.size.toLong())
            assertTrue("ts replica ${s.ts.replica} not registered", s.ts.replica in 0..d.clocks.size.toLong())
        }

        // CharIDs are globally unique: per replica, [clock, clock+len) ranges don't overlap,
        // and the replica's next-to-allocate counter is past every range it owns.
        val byReplica = d.subs.filter { !it.isDocStart && !it.isSentinel && it.length > 0 }.groupBy { it.charId.replica }
        for ((rep, list) in byReplica) {
            val sorted = list.sortedBy { it.charId.clock }
            for (k in 1 until sorted.size) {
                val prev = sorted[k - 1]
                assertTrue(
                    "charID overlap in replica $rep: $prev / ${sorted[k]}",
                    prev.charId.clock + prev.length <= sorted[k].charId.clock,
                )
            }
            val counter1 = d.clocks[(rep - 1).toInt()].counters[0]
            val maxEnd = sorted.maxOf { it.charId.clock + it.length }
            assertTrue("replica $rep counter1=$counter1 < used $maxEnd", counter1 >= maxEnd)
        }
        return d
    }

    fun normalise(s: String) = s.replace('\u2028', '\n').replace('\u2029', '\n')

    /** Every char ever inserted, as (replica UUID hex, clock) \u2192 live?, resolved through the slot table. */
    fun charIds(d: Doc): Map<Pair<String, Long>, Boolean> {
        val out = HashMap<Pair<String, Long>, Boolean>()
        for (s in d.subs) {
            if (s.isDocStart || s.isSentinel) continue
            val uuid = hex(d.clocks[(s.charId.replica - 1).toInt()].uuid)
            for (k in 0 until s.length) {
                val key = uuid to (s.charId.clock + k)
                assertTrue("duplicate char id $key", key !in out)
                out[key] = !s.tombstone
            }
        }
        return out
    }

    /**
     * CRDT monotonicity across one edit by [editor]: no char id disappears,
     * no tombstone comes back to life, and every new char id belongs to
     * [editor]. Catches slot-remap bugs (a char silently re-attributed to a
     * different replica shows up as a vanished id + a foreign new id).
     */
    fun checkMonotonic(before: ByteArray, after: ByteArray, editor: ByteArray) {
        val a = charIds(parse(before))
        val b = charIds(parse(after))
        val editorHex = hex(editor)
        for ((id, live) in a) {
            assertTrue("char id $id vanished", id in b)
            if (!live) assertTrue("tombstone $id resurrected", b[id] == false)
        }
        for (id in b.keys - a.keys) {
            assertEquals("new char id $id not owned by the editor", editorHex, id.first)
            assertEquals("new char $id must be live", true, b[id])
        }
        // Clock entries of other replicas are carried over byte-for-byte.
        val beforeClocks = parse(before).clocks.associateBy { hex(it.uuid) }
        for (c in parse(after).clocks) {
            val key = hex(c.uuid)
            if (key == editorHex) continue
            assertArrayEquals("foreign clock $key changed", beforeClocks[key]!!.raw, c.raw)
        }
    }

    private fun assertTrue(message: String, condition: Boolean) {
        if (!condition) throw AssertionError(message)
    }

    private fun assertEquals(message: String, expected: Any?, actual: Any?) {
        if (expected != actual) throw AssertionError("$message expected:<$expected> but was:<$actual>")
    }

    private fun assertEquals(expected: Any?, actual: Any?) {
        if (expected != actual) throw AssertionError("expected:<$expected> but was:<$actual>")
    }

    private fun assertArrayEquals(message: String, expected: ByteArray, actual: ByteArray) {
        if (!expected.contentEquals(actual)) {
            throw AssertionError("$message: expected:<${hex(expected)}> but was:<${hex(actual)}>")
        }
    }
}
