package io.rhizome.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where one sync attempt landed. The trigger layer decides scheduling (e.g. backoff on Retryable). */
sealed interface SyncResult {
    /** The session drained successfully (all pages applied, cursor adopted). */
    data object Success : SyncResult
    /** Sync is not enabled on this device (no site_id) — nothing was sent. */
    data object NotEnabled : SyncResult
    /** 401: credentials missing/invalid. Stop looping; prompt for credentials. */
    data object AuthRequired : SyncResult
    /** 409: the server does not accept our schema_hash. Needs a coordinated bump. */
    data object SchemaMismatch : SyncResult
    /** 5xx or transport failure: safe to retry the whole batch with backoff. */
    data class Retryable(val reason: String) : SyncResult
    /** A non-retryable client error (400/413): surface, do not loop. */
    data class Failed(val reason: String) : SyncResult
}

/** Coarse status for a UI indicator. */
sealed interface SyncStatus {
    data object Idle : SyncStatus
    data object Syncing : SyncStatus
    data class Synced(val at: Long) : SyncStatus
    data class Error(val message: String) : SyncStatus
}

/**
 * Drives the device↔server sync round-trip (spec/protocol.md §I.6). One [syncOnce] is a full
 * session: it repeats `POST /sync/v1` while the server reports `has_more`, draining the relay
 * backlog. Pure orchestration over an injected [SyncTransport] (network) and [SyncLocalStore]
 * (the DB single-writer) — no timers, no backoff; the trigger layer owns scheduling and reacts
 * to the returned [SyncResult].
 */
class SyncEngine(
    private val store: SyncLocalStore,
    private val transport: SyncTransport,
    private val schemaHash: String,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val onRejected: (List<RejectedOp>) -> Unit = {},
    /**
     * Max outbound ops per `POST /sync/v1`. The download is server-paged (has_more); the UPLOAD
     * must be client-paged too, or a large outbox — e.g. a fresh device's full backfill — serializes
     * into one giant request body and OOMs a memory-constrained client. Each round sends the lowest
     * [pushBatchLimit] op_seqs (pendingOps is op_seq-ordered); the server's contiguous accepted_through
     * prunes exactly those, so the next round carries the following page. The session loops until BOTH
     * the pull (has_more) and the push (pendingOps) are drained.
     */
    private val pushBatchLimit: Int = 500,
    private val log: (String) -> Unit = {},
) {
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    suspend fun syncOnce(): SyncResult {
        val site = store.siteId() ?: run {
            log("syncOnce: not enabled (no site_id) — nothing sent")
            return SyncResult.NotEnabled
        }
        _status.value = SyncStatus.Syncing
        while (true) {
            // Page the upload: send at most pushBatchLimit ops (the lowest op_seqs) this round, so a
            // large backfill never builds one oversized request body. The server prunes them via
            // accepted_through and the loop sends the next page.
            val request = SyncRequest(
                schemaHash = schemaHash,
                siteId = site,
                cursor = store.cursor(),
                ops = store.pendingOps().take(pushBatchLimit).map { it.toWire() },
            )
            log("POST /sync/v1 site=$site cursor=${request.cursor} ops=${request.ops.size} schema=${schemaHash.take(8)}…")
            when (val outcome = transport.post(request)) {
                is SyncOutcome.Ok -> {
                    val resp = outcome.response
                    log("← 200 accepted_through=${resp.acceptedThrough} relayed=${resp.ops.size} rejected=${resp.rejected.size} cursor=${resp.cursor} has_more=${resp.hasMore}")
                    if (resp.rejected.isNotEmpty()) {
                        resp.rejected.forEach { log("  rejected (${it.siteId},${it.opSeq}): ${it.reason}") }
                        onRejected(resp.rejected)
                    }
                    // accepted_through settles applied AND quarantined ops, so pruning here also
                    // drops permanently-rejected ops from the outbox.
                    store.markAckedThrough(resp.acceptedThrough)
                    if (resp.ops.isNotEmpty()) {
                        store.applyRelayed(resp.ops.map { it.toOp() })
                    }
                    store.setCursor(resp.cursor) // authoritative, even on rollback
                    // Done only when the server has nothing more to relay AND our outbox is drained.
                    // (markAckedThrough just pruned the acked page, so pendingOps reflects what's left.)
                    val morePending = store.pendingOps().isNotEmpty()
                    if (!resp.hasMore && !morePending) {
                        _status.value = SyncStatus.Synced(clock())
                        return SyncResult.Success
                    }
                    // Loop: server has_more, or we still have queued ops to push.
                }
                is SyncOutcome.HttpError -> {
                    log("← HTTP ${outcome.code}${outcome.body?.let { " body=${it.take(200)}" } ?: ""}")
                    return fail(
                        when (outcome.code) {
                            401 -> SyncResult.AuthRequired
                            409 -> SyncResult.SchemaMismatch
                            in 500..599 -> SyncResult.Retryable("server ${outcome.code}")
                            else -> SyncResult.Failed("http ${outcome.code}")
                        },
                        "sync failed: HTTP ${outcome.code}",
                    )
                }
                is SyncOutcome.TransportError -> {
                    log("← transport error: ${outcome.cause}")
                    return fail(
                        SyncResult.Retryable(outcome.cause.message ?: "transport error"),
                        "sync failed: ${outcome.cause.message ?: outcome.cause::class.simpleName}",
                    )
                }
            }
        }
    }

    private fun fail(result: SyncResult, message: String): SyncResult {
        log(message)
        _status.value = SyncStatus.Error(message)
        return result
    }
}
