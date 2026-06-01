package io.rhizome.core

/**
 * The engine's view of persistence — implemented by the consuming app (ForestNote does so over
 * its NotebookStore; the SQLite adapter in rhizome-sqlite provides a registry-driven default).
 *
 * All calls are `suspend` and MUST land on the app's single DB-writer thread; the engine never
 * touches storage directly. [pendingOps]/[applyRelayed] carry domain [Op]s (wire mapping is the
 * engine's job).
 */
interface SyncLocalStore {
    /** This replica's site ULID, or null if sync is not enabled (then syncOnce is a no-op). */
    suspend fun siteId(): String?

    /** Last global relay seq this replica has adopted ("seen through"). 0 = nothing yet. */
    suspend fun cursor(): Long

    /** Outbound ops not yet acked by the server, in op_seq order. */
    suspend fun pendingOps(): List<Op>

    /** Merge relayed ops into local storage transactionally (row-level LWW). */
    suspend fun applyRelayed(ops: List<Op>)

    /** Advance the ack high-water; prune settled (applied + quarantined) ops from the outbox. */
    suspend fun markAckedThrough(through: Long)

    /** Adopt the server cursor as authoritative (even on rollback). */
    suspend fun setCursor(cursor: Long)
}
