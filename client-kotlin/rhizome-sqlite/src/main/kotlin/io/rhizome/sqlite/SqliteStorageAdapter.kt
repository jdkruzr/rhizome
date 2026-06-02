package io.rhizome.sqlite

import io.rhizome.core.ColumnDef
import io.rhizome.core.ColumnType
import io.rhizome.core.Hlc
import io.rhizome.core.Merge
import io.rhizome.core.Op
import io.rhizome.core.Registry
import io.rhizome.core.SyncLocalStore
import io.rhizome.core.TableDef
import io.rhizome.core.WireCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * The registry-driven [SyncLocalStore] over a raw [SqliteHandle]: generic capture, apply, and
 * backfill plus the sync bookkeeping (site id, cursor, outbox), all derived from a [Registry]
 * (spec/schema-registry.md). The host app owns its data tables (SQLDelight in ForestNote's case);
 * this adapter reads/writes them dynamically and owns its `rhizome_*` bookkeeping tables.
 *
 * Table and column names interpolated into SQL come from the [Registry] (developer-declared), never
 * from synced data, so the dynamic SQL is not an injection surface.
 */
class SqliteStorageAdapter(
    private val db: SqliteHandle,
    private val registry: Registry,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : SyncLocalStore {

    /**
     * The op_ts Hybrid Logical Clock (spec/hlc.md). Seeded from the persisted `last_hlc` and the
     * greatest op_ts already in the outbox/row_meta, so a fresh process never reissues or regresses
     * an op_ts — and so a ForestNote cutover inherits its existing (legacy wall_ts) timeline.
     */
    private val hlc: Hlc

    init {
        ensureSchema()
        hlc = Hlc(last = seedHlc(), wallClock = clock)
    }

    /** Create the adapter's own bookkeeping tables (the host owns the registry's data tables). */
    private fun ensureSchema() {
        db.execute(
            """
            CREATE TABLE IF NOT EXISTS rhizome_sync_state (
              id          INTEGER PRIMARY KEY CHECK (id = 0),
              site_id     TEXT,
              cursor      INTEGER NOT NULL DEFAULT 0,
              next_op_seq INTEGER NOT NULL DEFAULT 1,
              last_hlc    INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execute(
            """
            CREATE TABLE IF NOT EXISTS rhizome_outbox (
              op_seq INTEGER PRIMARY KEY,
              tbl    TEXT NOT NULL,
              pk     TEXT NOT NULL,
              op_ts  INTEGER NOT NULL,
              cols   TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execute(
            """
            CREATE TABLE IF NOT EXISTS rhizome_row_meta (
              tbl     TEXT NOT NULL,
              pk      TEXT NOT NULL,
              op_ts   INTEGER NOT NULL,
              op_seq  INTEGER NOT NULL,
              site_id TEXT NOT NULL,
              PRIMARY KEY (tbl, pk)
            )
            """.trimIndent(),
        )
    }

    /**
     * Turn on sync for this replica by recording its stable [siteId]. Idempotent: a previously set
     * site id is never re-minted. Until this is called, [capture] is a no-op (sync ships dormant).
     */
    suspend fun enableSync(siteId: String) {
        db.execute(
            "INSERT OR IGNORE INTO rhizome_sync_state (id, site_id, cursor, next_op_seq) VALUES (0, ?, 0, 1)",
            listOf(siteId),
        )
        // If the state row pre-existed (e.g. a cursor write before enable), adopt the id once.
        db.execute("UPDATE rhizome_sync_state SET site_id = ? WHERE id = 0 AND site_id IS NULL", listOf(siteId))
    }

    /**
     * Capture the current state of one row as an outbound op (read row → encode → enqueue). No-op
     * when sync is dormant, the table is unknown, the table is server-authored-only, or the row is
     * absent. Allocates the next per-site op_seq and stamps op_ts from the clock.
     */
    suspend fun capture(table: String, pk: String) {
        val site = currentSiteId() ?: return // dormant
        val def = registry.byName[table] ?: return
        if (def.serverAuthoredOnly) return
        db.transaction {
            val cols = readRowAsCols(def, pk) ?: return@transaction // row gone (e.g. hard-deleted)
            val seq = nextOpSeqAndBump()
            val opTs = hlc.localEvent()
            db.execute(
                "INSERT INTO rhizome_outbox (op_seq, tbl, pk, op_ts, cols) VALUES (?, ?, ?, ?, ?)",
                listOf(seq, table, pk, opTs, cols.toString()),
            )
            // Record this local write as the row's current LWW winner. The HLC guarantees its op_ts
            // exceeds every op this device has applied (applyRelayed bumps the clock on receive), so
            // the device's own latest write IS the winner until a strictly-greater relayed op
            // arrives. Without this, a later strictly-older relayed op would clobber the local row —
            // and since the relay never echoes an author's op back to itself, the author would never
            // re-establish its value, so two devices editing the same row would diverge.
            upsertRowMeta(Op(table, pk, site, seq, opTs, EMPTY_COLS))
            persistLastHlc()
        }
    }

    /**
     * Enqueue a capture op for every existing row of every capturable (non-server-authored) table.
     * Used on first enable and on a schema-generation reset to seed the server with pre-sync data.
     * No-op when dormant. Idempotent under LWW (re-uploading a row the server already has is harmless).
     */
    suspend fun backfill() {
        currentSiteId() ?: return // dormant
        for (def in registry.tables) {
            if (def.serverAuthoredOnly) continue
            val pks = db.query("SELECT ${def.pk} AS pk FROM ${def.name}") { it.getString("pk")!! }
            for (pk in pks) capture(def.name, pk)
        }
    }

    override suspend fun siteId(): String? = currentSiteId()

    override suspend fun cursor(): Long =
        db.query("SELECT cursor FROM rhizome_sync_state WHERE id = 0") { it.getLong("cursor")!! }.firstOrNull() ?: 0

    /** Outbound ops not yet acked, in op_seq order. site_id is this replica's (the outbox is ours). */
    override suspend fun pendingOps(): List<Op> {
        val site = currentSiteId() ?: return emptyList()
        return db.query(
            "SELECT op_seq, tbl, pk, op_ts, cols FROM rhizome_outbox ORDER BY op_seq",
        ) { r ->
            Op(
                table = r.getString("tbl")!!,
                pk = r.getString("pk")!!,
                siteId = site,
                opSeq = r.getLong("op_seq")!!,
                opTs = r.getLong("op_ts")!!,
                cols = Json.parseToJsonElement(r.getString("cols")!!).jsonObject,
            )
        }
    }

    /**
     * Merge relayed ops into the host tables under row-level LWW (spec/merge.md). The batch is first
     * collapsed to one winner per (table, pk); each winner is applied only if it beats the version
     * recorded in `rhizome_row_meta`, so re-delivery of a seen op is a no-op. All in one transaction.
     */
    override suspend fun applyRelayed(ops: List<Op>) {
        if (ops.isEmpty()) return
        val winners = Merge.merge(ops, registry.knownCols)
        db.transaction {
            for ((key, op) in winners) {
                val table = registry.byName[key.table] ?: continue // unknown table: drop
                if (winsOverStored(op)) {
                    upsertRow(table, op)
                    upsertRowMeta(op)
                }
            }
            // Absorb the relayed ops' timestamps so a later local capture sorts strictly after them
            // (the causality guarantee — spec/hlc.md). Bump on every received op, winner or not.
            hlc.receiveEvent(ops.maxOf { it.opTs })
            persistLastHlc()
        }
    }

    /** Prune settled (applied + quarantined) ops: drop every outbox op with op_seq ≤ [through]. */
    override suspend fun markAckedThrough(through: Long) {
        db.execute("DELETE FROM rhizome_outbox WHERE op_seq <= ?", listOf(through))
    }

    /** Adopt the server cursor as authoritative. Tolerates being called before enableSync. */
    override suspend fun setCursor(cursor: Long) {
        db.execute("INSERT OR IGNORE INTO rhizome_sync_state (id, site_id, cursor, next_op_seq) VALUES (0, NULL, 0, 1)")
        db.execute("UPDATE rhizome_sync_state SET cursor = ? WHERE id = 0", listOf(cursor))
    }

    // -- apply helpers -----------------------------------------------------------

    /** True if [incoming] has no stored predecessor, or strictly beats it under the LWW order. */
    private fun winsOverStored(incoming: Op): Boolean {
        val stored = db.query(
            "SELECT op_ts, op_seq, site_id FROM rhizome_row_meta WHERE tbl = ? AND pk = ?",
            listOf(incoming.table, incoming.pk),
        ) { r -> Op(incoming.table, incoming.pk, r.getString("site_id")!!, r.getLong("op_seq")!!, r.getLong("op_ts")!!, EMPTY_COLS) }
            .firstOrNull() ?: return true
        return Merge.less(stored, incoming)
    }

    /** Dynamic `INSERT … ON CONFLICT(pk) DO UPDATE` of [op]'s decoded columns into [table]. */
    private fun upsertRow(table: TableDef, op: Op) {
        val colNames = listOf(table.pk) + table.columns.map { it.name }
        val placeholders = colNames.joinToString(", ") { "?" }
        val updates = table.columns.joinToString(", ") { "${it.name} = excluded.${it.name}" }
        val sql = "INSERT INTO ${table.name} (${colNames.joinToString(", ")}) VALUES ($placeholders) " +
            "ON CONFLICT(${table.pk}) DO UPDATE SET $updates"
        val args = ArrayList<Any?>(colNames.size)
        args.add(op.pk)
        for (c in table.columns) args.add(decodedValue(c, op.cols))
        db.execute(sql, args)
    }

    private fun upsertRowMeta(op: Op) {
        db.execute(
            "INSERT INTO rhizome_row_meta (tbl, pk, op_ts, op_seq, site_id) VALUES (?, ?, ?, ?, ?) " +
                "ON CONFLICT(tbl, pk) DO UPDATE SET op_ts = excluded.op_ts, op_seq = excluded.op_seq, site_id = excluded.site_id",
            listOf(op.table, op.pk, op.opTs, op.opSeq, op.siteId),
        )
    }

    /** Decode one column's wire value to its native SQLite-bindable form; absent key ⇒ null. */
    private fun decodedValue(col: ColumnDef, cols: JsonObject): Any? {
        val element = cols[col.name] ?: return null
        return WireCodec.decode(col.type, element)
    }

    // -- capture helpers ---------------------------------------------------------

    private fun currentSiteId(): String? =
        db.query("SELECT site_id FROM rhizome_sync_state WHERE id = 0") { it.getString("site_id") }.firstOrNull()

    /** Seed the HLC: never below the persisted value nor any op_ts already issued/applied. */
    private fun seedHlc(): Long {
        val stored = db.query("SELECT last_hlc FROM rhizome_sync_state WHERE id = 0") { it.getLong("last_hlc")!! }.firstOrNull() ?: 0
        val maxOutbox = db.query("SELECT COALESCE(MAX(op_ts), 0) AS m FROM rhizome_outbox") { it.getLong("m")!! }.firstOrNull() ?: 0
        val maxMeta = db.query("SELECT COALESCE(MAX(op_ts), 0) AS m FROM rhizome_row_meta") { it.getLong("m")!! }.firstOrNull() ?: 0
        return maxOf(stored, maxOutbox, maxMeta)
    }

    /** Persist the HLC state so monotonicity survives process death (spec/hlc.md). */
    private fun persistLastHlc() {
        db.execute("INSERT OR IGNORE INTO rhizome_sync_state (id, site_id, cursor, next_op_seq, last_hlc) VALUES (0, NULL, 0, 1, 0)")
        db.execute("UPDATE rhizome_sync_state SET last_hlc = ? WHERE id = 0", listOf(hlc.last))
    }

    /** Read one row's synced columns, encoded to wire JSON in alphabetical key order, or null if absent. */
    private fun readRowAsCols(def: TableDef, pk: String): JsonObject? {
        val cols = def.columns.sortedBy { it.name } // alphabetical: matches knownCols + legacy SyncWire
        val selectList = cols.joinToString(", ") { it.name }
        return db.query("SELECT $selectList FROM ${def.name} WHERE ${def.pk} = ?", listOf(pk)) { r ->
            buildJsonObject { for (c in cols) put(c.name, encodeColumn(c, r)) }
        }.firstOrNull()
    }

    /** Read column [c] from [r] by its SQLite affinity, then wire-encode it per its [ColumnType]. */
    private fun encodeColumn(c: ColumnDef, r: SqliteRow): JsonElement {
        val native: Any? = when (c.type) {
            ColumnType.Text -> r.getString(c.name)
            ColumnType.Int, ColumnType.Timestamp, ColumnType.ColorInt -> r.getLong(c.name)
            ColumnType.Real -> r.getDouble(c.name)
            ColumnType.Bool -> r.getLong(c.name)?.let { it != 0L }
            ColumnType.Blob -> r.getBlob(c.name)
        }
        return WireCodec.encode(c.type, native)
    }

    /** Read and increment the per-site op_seq counter, returning the value to use for this op. */
    private fun nextOpSeqAndBump(): Long {
        val seq = db.query("SELECT next_op_seq FROM rhizome_sync_state WHERE id = 0") { it.getLong("next_op_seq")!! }.single()
        db.execute("UPDATE rhizome_sync_state SET next_op_seq = ? WHERE id = 0", listOf(seq + 1))
        return seq
    }

    private companion object {
        val EMPTY_COLS = JsonObject(emptyMap())
    }
}
