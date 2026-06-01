package io.rhizome.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * A full-row snapshot — one change, self-contained, never a diff. Identity is (siteId, opSeq),
 * globally unique. [cols] is the full row state, wire-encoded per the registry column types.
 *
 * [opTs] is the ordering timestamp (a Hybrid Logical Clock int64 — see spec/hlc.md). The merge
 * treats it as an opaque int64, so legacy raw-wall_ts values interoperate during migration.
 *
 * This is the in-memory domain type; [WireOp] is its snake_case JSON form on the wire. They are
 * kept separate so wire names never leak into storage (spec/protocol.md).
 */
data class Op(
    val table: String,
    val pk: String,
    val siteId: String,
    val opSeq: Long,
    val opTs: Long,
    val cols: JsonObject,
) {
    val key: TablePK get() = TablePK(table, pk)
}

/** Keys a row across the synced data set. */
data class TablePK(val table: String, val pk: String)

/** The JSON wire form of an [Op] (protocol §3). `@SerialName` pins the snake_case field names. */
@Serializable
data class WireOp(
    val table: String,
    val pk: String,
    @SerialName("site_id") val siteId: String,
    @SerialName("op_seq") val opSeq: Long,
    @SerialName("op_ts") val opTs: Long,
    val cols: JsonObject = JsonObject(emptyMap()),
)

fun WireOp.toOp(): Op = Op(table, pk, siteId, opSeq, opTs, cols)

fun Op.toWire(): WireOp = WireOp(table, pk, siteId, opSeq, opTs, cols)
