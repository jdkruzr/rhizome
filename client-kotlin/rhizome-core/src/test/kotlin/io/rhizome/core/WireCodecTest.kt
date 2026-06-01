package io.rhizome.core

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WireCodecTest {

    @Test
    fun roundTripsScalars() {
        assertEquals("hi", WireCodec.decode(ColumnType.Text, WireCodec.encode(ColumnType.Text, "hi")))
        assertEquals(1000L, WireCodec.decode(ColumnType.Timestamp, WireCodec.encode(ColumnType.Timestamp, 1000L)))
        assertEquals(-5L, WireCodec.decode(ColumnType.Int, WireCodec.encode(ColumnType.Int, -5L)))
        assertEquals(3.5, WireCodec.decode(ColumnType.Real, WireCodec.encode(ColumnType.Real, 3.5)))
        assertEquals(true, WireCodec.decode(ColumnType.Bool, WireCodec.encode(ColumnType.Bool, true)))
    }

    @Test
    fun nullRoundTrips() {
        val enc = WireCodec.encode(ColumnType.Text, null)
        assertEquals(JsonNull, enc)
        assertEquals(null, WireCodec.decode(ColumnType.Text, enc))
    }

    @Test
    fun colorIntMapsSignedArgbToUnsignedWireAndBack() {
        // opaque black = 0xFF000000, stored as the signed Int -16777216 (sign-extended Long).
        val black = -16777216L
        val wire = WireCodec.encode(ColumnType.ColorInt, black)
        assertEquals(JsonPrimitive(4278190080L), wire) // 0xFF000000 as unsigned int64
        assertEquals(black, WireCodec.decode(ColumnType.ColorInt, wire))

        // round-trip the full signed range edges
        for (c in listOf(0L, -1L, 1L, Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong(), -16777216L)) {
            assertEquals(c, WireCodec.decode(ColumnType.ColorInt, WireCodec.encode(ColumnType.ColorInt, c)), "color $c")
        }
    }

    @Test
    fun blobRoundTripsViaBase64() {
        val bytes = byteArrayOf(0, 1, 2, 127, -1, -128)
        val wire = WireCodec.encode(ColumnType.Blob, bytes)
        val back = WireCodec.decode(ColumnType.Blob, wire) as ByteArray
        assertTrue(bytes.contentEquals(back), "blob bytes survive base64 round-trip")
    }
}
