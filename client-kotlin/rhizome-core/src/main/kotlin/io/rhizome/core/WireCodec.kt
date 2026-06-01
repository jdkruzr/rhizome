package io.rhizome.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.util.Base64

/**
 * Encodes/decodes a single column value to/from its JSON wire form, per the column's [ColumnType]
 * (spec/schema-registry.md). The registry composes these per row; the SQLite adapter (Phase 2)
 * supplies/consumes the native values. MUST match the Go codec — pinned by `wire-codec` vectors.
 *
 * Native value types: Text→String, Int/Timestamp→Long, Real→Double, Bool→Boolean, Blob→ByteArray,
 * ColorInt→Long (a sign-extended 32-bit ARGB). A null value encodes to JSON null and back.
 */
object WireCodec {

    fun encode(type: ColumnType, value: Any?): JsonElement {
        if (value == null) return JsonNull
        return when (type) {
            ColumnType.Text -> JsonPrimitive(value as String)
            ColumnType.Int, ColumnType.Timestamp -> JsonPrimitive(value as Long)
            ColumnType.Real -> JsonPrimitive(value as Double)
            ColumnType.Bool -> JsonPrimitive(value as Boolean)
            // signed ARGB Int (sign-extended into a Long) -> unsigned int64 on the wire
            ColumnType.ColorInt -> JsonPrimitive((value as Long) and 0xFFFFFFFFL)
            ColumnType.Blob -> JsonPrimitive(Base64.getEncoder().encodeToString(value as ByteArray))
        }
    }

    fun decode(type: ColumnType, element: JsonElement): Any? {
        if (element is JsonNull) return null
        val p = element.jsonPrimitive
        return when (type) {
            ColumnType.Text -> p.content
            ColumnType.Int, ColumnType.Timestamp -> p.long
            ColumnType.Real -> p.double
            ColumnType.Bool -> p.boolean
            // unsigned int64 wire color -> low 32 bits as signed Int, sign-extended back to the Long
            ColumnType.ColorInt -> p.long.toInt().toLong()
            ColumnType.Blob -> Base64.getDecoder().decode(p.content)
        }
    }
}
