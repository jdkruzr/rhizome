package io.rhizome.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Opt-in limits; byte counts are UTF-8 wire JSON, not characters or decoded BLOB sizes. */
@Serializable
data class RowLimits(
    @SerialName("max_ops") val maxOps: Int = 500,
    @SerialName("target_page_bytes") val targetPageBytes: Int = 4 * 1024 * 1024,
    @SerialName("max_row_bytes") val maxRowBytes: Int = 8 * 1024 * 1024,
    @SerialName("max_body_bytes") val maxBodyBytes: Int = 16 * 1024 * 1024,
) {
    init {
        require(maxOps in 1..500 && maxBodyBytes in 256..16 * 1024 * 1024)
        require(targetPageBytes in 1..maxBodyBytes && maxRowBytes in 1..8 * 1024 * 1024)
    }
    fun intersect(other: RowLimits): RowLimits {
        val body = minOf(maxBodyBytes, other.maxBodyBytes)
        return RowLimits(minOf(maxOps, other.maxOps), minOf(targetPageBytes, other.targetPageBytes, body),
            minOf(maxRowBytes, other.maxRowBytes), body)
    }
}

@Serializable
data class AssetLimits(
    @SerialName("chunk_bytes") val chunkBytes: Int,
    @SerialName("manifest_page_entries") val manifestPageEntries: Int,
)

@Serializable
data class SyncCapabilities(
    @SerialName("capabilities_version") val version: Int,
    val features: Set<String>,
    @SerialName("accepted_schema_hashes") val acceptedSchemaHashes: Set<String>,
    val rows: RowLimits? = null,
    val assets: AssetLimits? = null,
)

sealed interface CapabilityOutcome {
    data class Available(val capabilities: SyncCapabilities) : CapabilityOutcome
    data object Legacy : CapabilityOutcome
    data class HttpError(val code: Int) : CapabilityOutcome
    data class Failed(val cause: Exception) : CapabilityOutcome
    data class Invalid(val message: String) : CapabilityOutcome
}

interface BoundedRowTransport : SyncTransport {
    suspend fun capabilities(): CapabilityOutcome
    suspend fun postBounded(request: SyncRequest, limits: RowLimits): SyncOutcome
}

data class RowPageBudget(val limits: RowLimits, val envelopeBytes: Int)
data class OversizedOp(val siteId: String, val opSeq: Long, val encodedBytes: Long)
sealed interface PendingRowPage {
    data class Page(val ops: List<Op>, val hasMore: Boolean) : PendingRowPage
    data class Oversized(val op: OversizedOp) : PendingRowPage
}

/** No default implementation that materializes pendingOps(): consumers must supply bounded SQL. */
interface BoundedSyncLocalStore : SyncLocalStore {
    suspend fun pendingPage(budget: RowPageBudget): PendingRowPage
    suspend fun hasPending(): Boolean
    /** Merge, acknowledge and adopt cursor atomically; failed apply must retain the outbox. */
    suspend fun acceptResponse(response: SyncResponse)
}

object RowWire {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun bytes(op: WireOp): Int = json.encodeToString(WireOp.serializer(), op).toByteArray(Charsets.UTF_8).size
    fun envelope(request: SyncRequest): Int = json.encodeToString(SyncRequest.serializer(), request.copy(ops = emptyList()))
        .toByteArray(Charsets.UTF_8).size
}

sealed interface RowExchange {
    data class Page(val response: SyncResponse, val hasMore: Boolean) : RowExchange
    data class Stopped(val reason: SyncResult, val oversized: OversizedOp? = null) : RowExchange
}

sealed interface RowAdmission {
    data class Allowed(val rows: RowLimits, val manifestEntries: Int, val assetsEnabled: Boolean = true) : RowAdmission
    data class Stopped(val reason: SyncResult) : RowAdmission
}

/** Host binds calls to its library writer dispatcher; neither method drains a whole queue. */
interface ScheduledRows {
    suspend fun admission(): RowAdmission
    suspend fun exchange(): RowExchange
}

/**
 * One bounded exchange, for the host's future row/asset scheduler. Explicitly
 * separate from the legacy session: no silent opt-in or fake-schema fallback.
 * Host owns the DB dispatcher; the transport owns its network/encoding dispatcher.
 * Capabilities are revalidated per exchange, never cached across endpoint/accounts.
 */
class BoundedSyncSession(
    private val store: BoundedSyncLocalStore,
    private val transport: BoundedRowTransport,
    private val schemaHash: String,
    private val requireAssets: Boolean = true,
    private val localLimits: RowLimits = RowLimits(),
) : ScheduledRows {
    private val gate = Mutex()
    override suspend fun exchange(): RowExchange = gate.withLock { exchangeOnce() }

    override suspend fun admission(): RowAdmission {
        store.siteId() ?: return RowAdmission.Stopped(SyncResult.NotEnabled)
        val caps = when (val discovery = transport.capabilities()) {
            is CapabilityOutcome.Available -> discovery.capabilities
            CapabilityOutcome.Legacy -> return RowAdmission.Stopped(SyncResult.Failed("server lacks bounded sync capabilities"))
            is CapabilityOutcome.HttpError -> return RowAdmission.Stopped(stopHttp(discovery.code).reason)
            is CapabilityOutcome.Failed -> return RowAdmission.Stopped(SyncResult.Retryable(discovery.cause.message ?: "capability discovery failed"))
            is CapabilityOutcome.Invalid -> return RowAdmission.Stopped(SyncResult.Failed(discovery.message))
        }
        if (caps.version != 1 || "bounded-rows-v1" !in caps.features || caps.rows == null ||
            (requireAssets && ("assets-v1" !in caps.features || caps.assets?.chunkBytes != ASSET_CHUNK_BYTES ||
                caps.assets.manifestPageEntries !in 1..ASSET_PAGE_ENTRIES))) {
            return RowAdmission.Stopped(SyncResult.Failed("server lacks required sync capabilities"))
        }
        if (schemaHash !in caps.acceptedSchemaHashes) return RowAdmission.Stopped(SyncResult.SchemaMismatch)
        return RowAdmission.Allowed(localLimits.intersect(caps.rows), caps.assets?.manifestPageEntries ?: ASSET_PAGE_ENTRIES,
            "assets-v1" in caps.features && caps.assets?.chunkBytes == ASSET_CHUNK_BYTES &&
                caps.assets.manifestPageEntries in 1..ASSET_PAGE_ENTRIES)
    }

    private suspend fun exchangeOnce(): RowExchange {
        val limits = when (val allowed = admission()) {
            is RowAdmission.Allowed -> allowed.rows
            is RowAdmission.Stopped -> return RowExchange.Stopped(allowed.reason)
        }
        val site = store.siteId() ?: return RowExchange.Stopped(SyncResult.NotEnabled)
        val empty = SyncRequest(schemaHash = schemaHash, siteId = site, cursor = store.cursor(), ops = emptyList())
        val envelopeBytes = RowWire.envelope(empty)
        if (envelopeBytes > limits.maxBodyBytes) return RowExchange.Stopped(SyncResult.Failed("sync envelope exceeds body limit"))
        val page = when (val pending = store.pendingPage(RowPageBudget(limits, envelopeBytes))) {
            is PendingRowPage.Page -> pending
            is PendingRowPage.Oversized -> return RowExchange.Stopped(
                SyncResult.Failed("oversized_op ${pending.op.siteId}/${pending.op.opSeq}: ${pending.op.encodedBytes} bytes"), pending.op)
        }
        return when (val outcome = transport.postBounded(empty.copy(ops = page.ops.map { it.toWire() }), limits)) {
            is SyncOutcome.Ok -> {
                store.acceptResponse(outcome.response)
                RowExchange.Page(outcome.response, outcome.response.hasMore || store.hasPending())
            }
            is SyncOutcome.HttpError -> stopHttp(outcome.code)
            is SyncOutcome.TransportError -> RowExchange.Stopped(SyncResult.Retryable(outcome.cause.message ?: "transport failed"))
        }
    }

    private fun stopHttp(code: Int) = RowExchange.Stopped(when (code) {
        401, 403 -> SyncResult.AuthRequired
        409 -> SyncResult.SchemaMismatch
        in 500..599 -> SyncResult.Retryable("server $code")
        else -> SyncResult.Failed("http $code")
    })
}
