package com.example.applenotes.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Table decoding from a SYNTHETIC MergeableData graph, built by the small
 * encoder below following the shape documented in the README ("Table
 * attachments") and MergeableDataDecoder's header. Not captured from iCloud.
 */
@OptIn(ExperimentalEncodingApi::class)
class MergeableDataDecoderTest {

    /** Minimal builder for MergeableDataObjectData. Object indices are list positions. */
    private class Graph {
        val keys = mutableListOf("self", "crRows", "crColumns", "cellColumns", "UUIDIndex")
        val types = mutableListOf("com.apple.notes.ICTable", "com.apple.CRDT.NSUUID", "com.apple.CRDT.NSString")
        val uuids = mutableListOf<ByteArray>()
        val objects = mutableListOf<ByteArray?>()

        fun key(k: String) = keys.indexOf(k).also { require(it >= 0) }
        fun type(t: String) = types.indexOf(t).also { require(it >= 0) }
        fun reserve(): Int { objects.add(null); return objects.size - 1 }
        fun add(obj: ProtobufWire.Field): Int { objects.add(ProtobufWire.encode(listOf(obj))); return objects.size - 1 }
        fun set(i: Int, obj: ProtobufWire.Field) { objects[i] = ProtobufWire.encode(listOf(obj)) }

        fun objRef(i: Int) = Fx.varint(6, i.toLong())

        fun uuid(seed: Int): Int {
            uuids.add(ByteArray(16) { (seed * 16 + it).toByte() })
            return uuids.size - 1
        }

        /** NSUUID wrapper: CustomMap{type NSUUID, entry{UUIDIndex → unsignedIntegerValue(uuidIdx)}}. */
        fun wrapper(uuidIdx: Int) = add(Fx.msg(13,
            Fx.varint(1, type("com.apple.CRDT.NSUUID").toLong()),
            Fx.msg(3, Fx.varint(1, key("UUIDIndex").toLong()), Fx.msg(2, Fx.varint(2, uuidIdx.toLong()))),
        ))

        fun string(text: String) = add(Fx.msg(10, Fx.str(2, text)))

        fun dict(pairs: List<Pair<Int, Int>>) = add(ProtobufWire.Field(6, ProtobufWire.WIRE_LENGTH_DELIM,
            ProtobufWire.encode(pairs.map { (k, v) -> Fx.msg(1, Fx.msg(1, objRef(k)), Fx.msg(2, objRef(v))) })))

        /** OrderedSet whose visual order is [orderUuids]; [contents] maps key wrapper → value wrapper. */
        fun orderedSet(orderUuids: List<Int>, contents: List<Pair<Int, Int>>, elements: List<Int>): Int {
            val array = Fx.msg(1, *orderUuids.mapIndexed { i, u ->
                Fx.msg(2, Fx.varint(1, i.toLong()), Fx.bytes(2, uuids[u]))
            }.toTypedArray())
            val contentsDict = Fx.msg(2, *contents.map { (k, v) -> Fx.msg(1, Fx.msg(1, objRef(k)), Fx.msg(2, objRef(v))) }.toTypedArray())
            val elementsDict = Fx.msg(2, *elements.map { e -> Fx.msg(1, Fx.msg(1, objRef(e)), Fx.msg(2, objRef(e))) }.toTypedArray())
            return add(Fx.msg(16, Fx.msg(1, array, contentsDict), elementsDict))
        }

        fun table(entries: List<Pair<String, Int>>) = add(Fx.msg(13,
            Fx.varint(1, type("com.apple.notes.ICTable").toLong()),
            *entries.map { (k, ref) -> Fx.msg(3, Fx.varint(1, key(k).toLong()), Fx.msg(2, objRef(ref))) }.toTypedArray(),
        ))

        fun encode(): ByteArray {
            val od = objects.map { Fx.bytes(3, it!!) } +
                keys.map { Fx.str(4, it) } + types.map { Fx.str(5, it) } + uuids.map { Fx.bytes(6, it) }
            val wrapper = ProtobufWire.encode(listOf(Fx.varint(1, 0), Fx.varint(2, 0), Fx.bytes(3, ProtobufWire.encode(od))))
            return ProtobufWire.encode(listOf(Fx.varint(1, 0), Fx.bytes(2, wrapper)))
        }
    }

    /**
     * A 3x2 table whose visual order is rows R0,R1,R2 / columns C0,C1 but
     * whose cellColumns dict lists C1 before C0 and rows shuffled. Row R1 is
     * referenced from cellColumns through a second UUID (R1b) linked to R1
     * by the OrderedSet's `contents` dictionary, as the README describes.
     */
    private fun buildTable(withOrder: Boolean, breakRowUuid: Boolean = false): ByteArray {
        val g = Graph()
        val tableIdx = g.reserve()
        val r0 = g.uuid(1); val r1 = g.uuid(2); val r2 = g.uuid(3); val r1b = g.uuid(4)
        val c0 = g.uuid(5); val c1 = g.uuid(6)
        val wR0 = g.wrapper(r0); val wR1 = g.wrapper(r1); val wR2 = g.wrapper(r2); val wR1b = g.wrapper(r1b)
        val wC0 = g.wrapper(c0); val wC1 = g.wrapper(c1)
        fun cell(s: String) = g.string(s)
        // cellColumns: C1 first; rows in order R2, R1b, R0.
        val col1 = g.dict(listOf(wR2 to cell("r2c1"), wR1b to cell("r1c1"), wR0 to cell("r0c1")))
        val col0 = g.dict(listOf(wR2 to cell("r2c0"), wR0 to cell("r0c0"), wR1b to cell("r1c0")))
        val cellColumns = g.dict(listOf(wC1 to col1, wC0 to col0))
        val entries = mutableListOf("cellColumns" to cellColumns)
        if (withOrder) {
            if (breakRowUuid) g.uuids[r2] = ByteArray(16) { 0x77 } // array still names the old bytes → unresolvable
            val rowsOrder = listOf(r0, r1, r2)
            val crRows = g.orderedSet(rowsOrder, contents = listOf(wR1 to wR1b), elements = listOf(wR0, wR1, wR2))
            // Rebuild the array with the ORIGINAL r2 bytes when breaking (so lookup fails).
            if (breakRowUuid) {
                val good = ByteArray(16) { (3 * 16 + it).toByte() }
                val array = Fx.msg(1, Fx.msg(2, Fx.varint(1, 0), Fx.bytes(2, g.uuids[r0])),
                    Fx.msg(2, Fx.varint(1, 1), Fx.bytes(2, g.uuids[r1])), Fx.msg(2, Fx.varint(1, 2), Fx.bytes(2, good)))
                g.set(crRows, Fx.msg(16, Fx.msg(1, array)))
            }
            val crColumns = g.orderedSet(listOf(c0, c1), contents = emptyList(), elements = listOf(wC0, wC1))
            entries.add(0, "crRows" to crRows)
            entries.add(1, "crColumns" to crColumns)
        }
        g.set(tableIdx, Fx.msg(13,
            Fx.varint(1, g.type("com.apple.notes.ICTable").toLong()),
            *entries.map { (k, ref) -> Fx.msg(3, Fx.varint(1, g.key(k).toLong()), Fx.msg(2, g.objRef(ref))) }.toTypedArray(),
        ))
        return g.encode()
    }

    @Test
    fun ordersRowsAndColumnsByCrRowsAndCrColumns() {
        val grid = MergeableDataDecoder.decodeTable(buildTable(withOrder = true))!!
        assertEquals(3, grid.rows)
        assertEquals(2, grid.cols)
        assertEquals(
            listOf(listOf("r0c0", "r0c1"), listOf("r1c0", "r1c1"), listOf("r2c0", "r2c1")),
            grid.cells,
        )
    }

    @Test
    fun fallsBackToCellColumnsOrderWithoutOrderedSets() {
        val grid = MergeableDataDecoder.decodeTable(buildTable(withOrder = false))!!
        // Old behaviour: columns in cellColumns order (C1, C0); rows in first-appearance order (R2, R1b, R0).
        assertEquals(
            listOf(listOf("r2c1", "r2c0"), listOf("r1c1", "r1c0"), listOf("r0c1", "r0c0")),
            grid.cells,
        )
    }

    @Test
    fun fallsBackWhenOrderedSetCannotBeResolved() {
        val grid = MergeableDataDecoder.decodeTable(buildTable(withOrder = true, breakRowUuid = true))!!
        assertEquals(
            listOf(listOf("r2c1", "r2c0"), listOf("r1c1", "r1c0"), listOf("r0c1", "r0c0")),
            grid.cells,
        )
    }

    @Test
    fun emptyRowsInTheOrderAreKept() {
        // A row present in crRows but with no cells renders as an empty row in place.
        val g = Graph()
        val t = g.reserve()
        val r0 = g.uuid(1); val r1 = g.uuid(2); val c0 = g.uuid(3)
        val wR0 = g.wrapper(r0); val wR1 = g.wrapper(r1); val wC0 = g.wrapper(c0)
        val col = g.dict(listOf(wR1 to g.string("second")))
        val cellColumns = g.dict(listOf(wC0 to col))
        val crRows = g.orderedSet(listOf(r0, r1), emptyList(), listOf(wR0, wR1))
        val crCols = g.orderedSet(listOf(c0), emptyList(), listOf(wC0))
        g.set(t, Fx.msg(13, Fx.varint(1, 0),
            Fx.msg(3, Fx.varint(1, g.key("crRows").toLong()), Fx.msg(2, g.objRef(crRows))),
            Fx.msg(3, Fx.varint(1, g.key("crColumns").toLong()), Fx.msg(2, g.objRef(crCols))),
            Fx.msg(3, Fx.varint(1, g.key("cellColumns").toLong()), Fx.msg(2, g.objRef(cellColumns))),
        ))
        val grid = MergeableDataDecoder.decodeTable(g.encode())!!
        assertEquals(listOf(listOf(""), listOf("second")), grid.cells)
    }

    @Test
    fun base64EntryPointAcceptsZlibAndGzip() {
        val raw = buildTable(withOrder = true)
        for (fmt in listOf(CompressionFormat.ZLIB, CompressionFormat.GZIP)) {
            val grid = MergeableDataDecoder.decodeTableBase64(Base64.encode(Gzip.compress(raw, fmt)))!!
            assertEquals("r1c1", grid.cells[1][1])
        }
    }

    @Test
    fun nonTableInputYieldsNull() {
        assertNull(MergeableDataDecoder.decodeTableBase64(Base64.encode(byteArrayOf(1, 2, 3))))
        assertNull(MergeableDataDecoder.decodeTable(ByteArray(0)))
        // A graph with no table type and no strings.
        val g = Graph()
        g.wrapper(g.uuid(1))
        assertNull(MergeableDataDecoder.decodeTable(g.encode()))
    }

    @Test
    fun fallbackListsStringsWhenTableRootIsMissing() {
        val g = Graph()
        g.types.removeAt(0) // no ICTable type at all
        g.string("alpha"); g.string("beta")
        val grid = MergeableDataDecoder.decodeTable(g.encode())!!
        assertEquals(listOf(listOf("alpha"), listOf("beta")), grid.cells)
    }
}
