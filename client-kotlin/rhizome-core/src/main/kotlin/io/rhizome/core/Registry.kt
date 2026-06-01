package io.rhizome.core

import java.security.MessageDigest

/**
 * The wire/SQLite type of a synced column. Each type defines how a value is encoded to / decoded
 * from the JSON `cols` map (the codec lands with the wire-codec work) and its SQLite affinity
 * (for the client adapter's dynamic SQL and the optional server mirror).
 */
enum class ColumnType { Text, Int, Real, Bool, Timestamp, Blob, ColorInt }

/** A synced non-PK column. The PK and the tombstone column are named on [TableDef], not here. */
data class ColumnDef(val name: String, val type: ColumnType, val nullable: Boolean = false)

/**
 * One synced table. [columns] are the NON-pk columns that travel in an op's `cols` map (the pk
 * rides in the op's `pk` field, never in `cols` — matching ForestNote/UB). [tombstone] names the
 * nullable column whose non-null value marks a soft delete. [serverAuthoredOnly] tables are
 * decoded/applied by clients but never captured by them (structural single-writer guarantee).
 */
data class TableDef(
    val name: String,
    val pk: String,
    val tombstone: String?,
    val columns: List<ColumnDef>,
    val serverAuthoredOnly: Boolean = false,
)

/**
 * The single source of truth for a synced data shape. Everything else — merge `knownCols`, the
 * schema hash, the wire codec, capture, apply, backfill — is derived from this (spec/schema-registry.md).
 */
class Registry(val tables: List<TableDef>) {

    init {
        require(tables.map { it.name }.toSet().size == tables.size) { "duplicate table names in registry" }
        for (t in tables) {
            require(t.columns.map { it.name }.toSet().size == t.columns.size) { "duplicate columns in table ${t.name}" }
            t.tombstone?.let { tomb ->
                require(t.columns.any { it.name == tomb }) { "tombstone column $tomb not declared in table ${t.name}" }
            }
        }
    }

    val byName: Map<String, TableDef> = tables.associateBy { it.name }

    /** Per table, its column names sorted alphabetically. Basis for normalize and the hash. */
    val knownCols: Map<String, List<String>> =
        tables.associate { it.name to it.columns.map { c -> c.name }.sorted() }

    /**
     * The canonical schema string: tables alphabetical; within each, columns alphabetical;
     * `table:col,col,…` joined by `;`. Byte-identical to UltraBridge's canonicalSchema().
     */
    fun canonical(): String =
        tables.sortedBy { it.name }.joinToString(";") { t ->
            t.name + ":" + t.columns.map { it.name }.sorted().joinToString(",")
        }

    /** Lowercase hex SHA-256 of [canonical] — the schema-hash gate (spec/protocol.md §I.6). */
    fun schemaHash(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
