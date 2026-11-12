package com.example.applenotes.proto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Random

class ProtobufWireTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun assertDecodeThrows(input: ByteArray, what: String) {
        try {
            ProtobufWire.decode(input)
            fail("expected decode to throw for $what")
        } catch (e: IllegalStateException) {
            // expected: error(...) from ProtobufWire
        }
    }

    @Test
    fun varintEncodingMatchesSpec() {
        assertArrayEquals(bytes(0x00), ProtobufWire.encodeVarint(0))
        assertArrayEquals(bytes(0x01), ProtobufWire.encodeVarint(1))
        assertArrayEquals(bytes(0x7F), ProtobufWire.encodeVarint(127))
        assertArrayEquals(bytes(0xAC, 0x02), ProtobufWire.encodeVarint(300))
        assertArrayEquals(bytes(0xFF, 0xFF, 0xFF, 0xFF, 0x0F), ProtobufWire.encodeVarint(0xFFFFFFFFL))
        // Negative Long → 10-byte two's-complement varint.
        val neg = ProtobufWire.encodeVarint(-1L)
        assertEquals(10, neg.size)
        assertEquals(-1L, ProtobufWire.readVarint(neg, 0).first)
    }

    @Test
    fun varintRoundTripsAcrossRange() {
        val r = Random(42)
        val values = mutableListOf(0L, 1L, 127L, 128L, 16383L, 16384L, 0xFFFFFFFFL, Long.MAX_VALUE, Long.MIN_VALUE)
        repeat(500) { values.add(r.nextLong()) }
        for (v in values) {
            val enc = ProtobufWire.encodeVarint(v)
            val (dec, end) = ProtobufWire.readVarint(enc, 0)
            assertEquals(v, dec)
            assertEquals(enc.size, end)
        }
    }

    @Test
    fun allWireTypesRoundTripByteIdentically() {
        val fields = listOf(
            ProtobufWire.encodeVarintField(1, 150),
            ProtobufWire.encodeString(2, "héllo 😀   ￼"),
            ProtobufWire.Field(3, ProtobufWire.WIRE_FIXED32, bytes(1, 2, 3, 4)),
            ProtobufWire.Field(4, ProtobufWire.WIRE_FIXED64, bytes(1, 2, 3, 4, 5, 6, 7, 8)),
            ProtobufWire.Field(5, ProtobufWire.WIRE_LENGTH_DELIM, ByteArray(0)),
            ProtobufWire.encodeVarintField(536870911, 7), // max field number
            ProtobufWire.encodeVarintField(1, 3), // repeated field number, order kept
        )
        val enc = ProtobufWire.encode(fields)
        val dec = ProtobufWire.decode(enc)
        assertEquals(fields.size, dec.size)
        for ((a, b) in fields.zip(dec)) {
            assertEquals(a.fieldNumber, b.fieldNumber)
            assertEquals(a.wireType, b.wireType)
            assertArrayEquals(a.payload, b.payload)
        }
        assertArrayEquals(enc, ProtobufWire.encode(dec))
        assertEquals("héllo 😀   ￼", ProtobufWire.decodeString(dec[1]))
        assertEquals(150L, ProtobufWire.decodeVarint(dec[0]))
    }

    @Test
    fun nonCanonicalVarintsSurviveRoundTrip() {
        // 0x80 0x00 is a valid (over-long) encoding of 0. We store raw varint
        // bytes, so re-encoding must not canonicalise it away.
        val input = bytes(0x08, 0x80, 0x00)
        val dec = ProtobufWire.decode(input)
        assertEquals(0L, ProtobufWire.decodeVarint(dec[0]))
        assertArrayEquals(input, ProtobufWire.encode(dec))
    }

    @Test
    fun fixtureRoundTripsByteIdentically() {
        val b = loadFixture("fmt-baseline-1.proto")
        assertArrayEquals(b, ProtobufWire.encode(ProtobufWire.decode(b)))
    }

    @Test
    fun emptyInputDecodesToNoFields() {
        assertTrue(ProtobufWire.decode(ByteArray(0)).isEmpty())
    }

    @Test
    fun truncatedVarintThrows() {
        assertDecodeThrows(bytes(0x08, 0x80), "truncated varint value")
        assertDecodeThrows(bytes(0x80), "truncated tag")
        try {
            ProtobufWire.readVarint(bytes(0xFF, 0xFF), 0)
            fail("expected throw")
        } catch (e: IllegalStateException) { }
    }

    @Test
    fun overlongVarintThrowsInsteadOfLooping() {
        assertDecodeThrows(ByteArray(11) { 0xFF.toByte() }, "11-byte varint tag")
        assertDecodeThrows(bytes(0x08) + ByteArray(20) { 0x80.toByte() } + bytes(0x00), "20-byte varint value")
    }

    @Test
    fun lengthPastEndThrows() {
        assertDecodeThrows(bytes(0x12, 0x05, 0x61, 0x62), "length 5 with 2 bytes left")
    }

    @Test
    fun hugeLengthThrowsWithoutAllocating() {
        // Length = 2^31 + 3: toInt() would wrap negative. Must throw, not OOM / IOOBE.
        val len = ProtobufWire.encodeVarint((1L shl 31) + 3)
        assertDecodeThrows(bytes(0x12) + len + bytes(0x61), "2^31+3 length")
        // Length = 2^63 + x (10-byte varint, negative as Long).
        assertDecodeThrows(bytes(0x12) + ProtobufWire.encodeVarint(Long.MIN_VALUE + 5), "negative length")
        // Length = 0xFFFFFFFF.
        assertDecodeThrows(bytes(0x12) + ProtobufWire.encodeVarint(0xFFFFFFFFL), "4G length")
    }

    @Test
    fun truncatedFixedWidthThrows() {
        assertDecodeThrows(bytes(0x0D, 1, 2, 3), "fixed32 with 3 bytes")
        assertDecodeThrows(bytes(0x09, 1, 2, 3, 4, 5, 6, 7), "fixed64 with 7 bytes")
    }

    @Test
    fun groupWireTypesAndFieldZeroAreRejected() {
        assertDecodeThrows(bytes(0x0B), "start-group wire type 3")
        assertDecodeThrows(bytes(0x0C), "end-group wire type 4")
        assertDecodeThrows(bytes(0x0E), "wire type 6")
        assertDecodeThrows(bytes(0x0F), "wire type 7")
        assertDecodeThrows(bytes(0x00, 0x01), "field number 0")
    }

    @Test
    fun randomGarbageNeverHangsOrEscapesWithUnexpectedException() {
        val r = Random(7)
        repeat(5000) {
            val buf = ByteArray(r.nextInt(64)).also { r.nextBytes(it) }
            try {
                val fields = ProtobufWire.decode(buf)
                // Anything that decodes must survive encode → decode unchanged.
                // (Not necessarily byte-identical: tags are re-emitted canonically.)
                val again = ProtobufWire.decode(ProtobufWire.encode(fields))
                assertEquals(fields.size, again.size)
                for ((a, b) in fields.zip(again)) {
                    assertEquals(a.fieldNumber, b.fieldNumber)
                    assertEquals(a.wireType, b.wireType)
                    assertArrayEquals(a.payload, b.payload)
                }
            } catch (e: IllegalStateException) {
                // fine: malformed input rejected with a clear error
            }
        }
    }

    @Test
    fun truncatingAValidMessageAnywhereEitherThrowsOrDecodesAPrefix() {
        val b = loadFixture("fmt-baseline-1.proto")
        for (cut in 0 until b.size step 7) {
            val part = b.copyOfRange(0, cut)
            try {
                val f = ProtobufWire.decode(part)
                assertArrayEquals(part, ProtobufWire.encode(f))
            } catch (e: IllegalStateException) { }
        }
    }
}
