package io.rhizome.sqlite

import io.rhizome.core.Op

/** Application-owned disposition for declared tables, shared by every host/domain.
 * prepare runs BEFORE the transaction and may dispatch CPU work elsewhere, but must not mutate
 * storage or perform external effects. It receives all operations, not only LWW winners.
 */
interface IncomingRowPolicy {
    val tables: Set<String>
    suspend fun prepare(ops: List<Op>): PreparedIncomingRows
}

/** Called on the adapter's caller/DB-writer thread INSIDE the response transaction, with its exact
 * connection. Durably retain every supplied operation (applied, pending or quarantined), or throw.
 * No suspension, I/O beyond this DB, or hidden transaction commit. A later policy failure rolls
 * this work back too. Rhizome alone owns cursor/ACK/HLC bookkeeping.
 */
fun interface PreparedIncomingRows {
    fun commit(db: SqliteHandle)
}
