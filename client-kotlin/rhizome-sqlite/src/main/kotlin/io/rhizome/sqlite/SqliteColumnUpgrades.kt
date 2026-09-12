package io.rhizome.sqlite

import io.rhizome.core.*
import kotlinx.serialization.json.*

/** Explicit additive-column repair, not a relaxation of row-level LWW.
 * Host supplies the actual previous registry at its migration boundary. All
 * calls belong to the shared writer/transaction, like SqliteStorageAdapter.
 */
internal class SqliteColumnUpgrades(private val db: SqliteHandle, private val registry: Registry) {
    init {
        db.execute("CREATE TABLE IF NOT EXISTS rhizome_column_upgrade(source_hash TEXT NOT NULL,target_hash TEXT NOT NULL,plan TEXT NOT NULL,PRIMARY KEY(source_hash,target_hash))")
        db.execute("CREATE TABLE IF NOT EXISTS rhizome_column_repair(source_hash TEXT NOT NULL,target_hash TEXT NOT NULL,tbl TEXT NOT NULL,pk TEXT NOT NULL,op_ts INTEGER NOT NULL,op_seq INTEGER NOT NULL,site_id TEXT NOT NULL,columns_json TEXT NOT NULL,PRIMARY KEY(source_hash,target_hash,tbl,pk))")
        db.execute("CREATE INDEX IF NOT EXISTS rhizome_column_repair_row ON rhizome_column_repair(tbl,pk)")
    }

    fun prepare(previous: Registry, actor: String?, policyTables: Set<String>): Boolean = db.transaction {
        val additions = previous.tables.sortedBy { it.name }.mapNotNull { old ->
            val current = requireNotNull(registry.byName[old.name]) { "Column repair requires an additive registry" }
            require(old.pk == current.pk && old.tombstone == current.tombstone && old.serverAuthoredOnly == current.serverAuthoredOnly)
            require(old.columns.all { it in current.columns }) { "Changed or removed columns need an explicit migration" }
            val added = current.columns.filter { it.name !in old.columns.map { c -> c.name } }.sortedBy { it.name }
            if (added.isEmpty()) null else {
                require(old.name !in policyTables) { "Policy-managed tables require their own upgrade policy" }
                old.name to added
            }
        }.toMap()
        if (additions.isEmpty()) return@transaction false
        val source = previous.schemaHash(); val target = registry.schemaHash()
        val plan = additions.entries.joinToString(";") { (table, columns) -> "$table:${columns.joinToString { "${it.name}/${it.type}/${it.nullable}" }}" }
        val existing = db.query("SELECT plan FROM rhizome_column_upgrade WHERE source_hash=? AND target_hash=?",listOf(source,target)) { it.getString("plan")!! }.singleOrNull()
        if (existing != null) {
            check(existing == plan) { "Column upgrade identity reused with different types or fields" }
            return@transaction false
        }
        require(actor != null) { "Column replay requires an established author identity" }
        for ((table,columns) in additions) {
            val definition = registry.byName.getValue(table)
            val names = JsonArray(columns.map { JsonPrimitive(it.name) }).toString()
            // SQL-side enumeration keeps large libraries off the JVM heap.
            // Same-site rows cannot be recovered from an own-site-excluding relay.
            db.execute("INSERT INTO rhizome_column_repair SELECT ?,?,m.tbl,m.pk,m.op_ts,m.op_seq,m.site_id,? FROM rhizome_row_meta m " +
                "JOIN $table r ON r.${definition.pk}=m.pk WHERE m.tbl=? AND m.site_id<>?",
                listOf(source,target,names,table,actor))
        }
        db.execute("UPDATE rhizome_sync_state SET cursor=0 WHERE id=0")
        db.execute("INSERT INTO rhizome_column_upgrade VALUES(?,?,?)",listOf(source,target,plan))
        true
    }

    fun forget(table: String, pk: String) = db.execute("DELETE FROM rhizome_column_repair WHERE tbl=? AND pk=?",listOf(table,pk))

    fun pending(): Long = db.query("SELECT count(*) AS n FROM rhizome_column_repair") { it.getLong("n")!! }.single()

    /** Only exact-version tickets can update only their listed columns. No INSERT,
     * provenance change, outbox capture, or overwrite of previously modeled fields.
     * Missing wire fields retain the ticket for explicit recovery, not false success.
     */
    fun repair(table: TableDef, op: Op) {
        val tickets = db.query("SELECT source_hash,target_hash,columns_json FROM rhizome_column_repair p WHERE p.tbl=? AND p.pk=? " +
            "AND p.op_ts=? AND p.op_seq=? AND p.site_id=? AND EXISTS(SELECT 1 FROM rhizome_row_meta m WHERE m.tbl=p.tbl AND m.pk=p.pk " +
            "AND m.op_ts=p.op_ts AND m.op_seq=p.op_seq AND m.site_id=p.site_id)",listOf(op.table,op.pk,op.opTs,op.opSeq,op.siteId)) {
                Triple(it.getString("source_hash")!!,it.getString("target_hash")!!,it.getString("columns_json")!!)
            }
        for ((source,target,encoded) in tickets) {
            val columns = Json.parseToJsonElement(encoded).jsonArray.map { name ->
                table.columns.single { it.name == name.jsonPrimitive.content }
            }
            if (columns.any { it.name !in op.cols }) continue
            val values = columns.map { column ->
                WireCodec.decode(column.type,op.cols.getValue(column.name)).also {
                    require(it != null || column.nullable) { "Null repair for non-null column ${column.name}" }
                }
            }
            db.execute("UPDATE ${table.name} SET ${columns.joinToString { "${it.name}=?" }} WHERE ${table.pk}=?",values+op.pk)
            db.execute("DELETE FROM rhizome_column_repair WHERE source_hash=? AND target_hash=? AND tbl=? AND pk=?",listOf(source,target,op.table,op.pk))
        }
    }
}
