package io.rhizome.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Bumped only on a breaking wire change. */
const val PROTOCOL_VERSION: Int = 1

/** `POST /sync/v1` request body (spec/protocol.md). */
@Serializable
data class SyncRequest(
    @SerialName("protocol_version") val protocolVersion: Int = PROTOCOL_VERSION,
    @SerialName("schema_hash") val schemaHash: String,
    @SerialName("site_id") val siteId: String,
    val cursor: Long,
    val ops: List<WireOp>,
)

/** A permanently-rejected op (quarantined; counted as settled by accepted_through). */
@Serializable
data class RejectedOp(
    @SerialName("site_id") val siteId: String,
    @SerialName("op_seq") val opSeq: Long,
    val reason: String = "",
)

/** `POST /sync/v1` response body. */
@Serializable
data class SyncResponse(
    @SerialName("protocol_version") val protocolVersion: Int = PROTOCOL_VERSION,
    @SerialName("accepted_through") val acceptedThrough: Long,
    val rejected: List<RejectedOp> = emptyList(),
    val ops: List<WireOp> = emptyList(),
    val cursor: Long,
    @SerialName("has_more") val hasMore: Boolean = false,
)
