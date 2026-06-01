package io.rhizome.sqlite

/**
 * The minimal synchronous SQLite access seam the [SqliteStorageAdapter] codes against. Keeping the
 * adapter on this tiny interface (rather than a concrete driver) is what lets `rhizome-sqlite` stay
 * pure-JVM and unit-testable: the library's tests and the toy example bind it to JDBC
 * ([io.rhizome.sqlite] JDBC handle); ForestNote binds it to `SupportSQLiteDatabase` at cutover.
 *
 * Implementations need not be thread-safe. Per [io.rhizome.core.SyncLocalStore], every call already
 * lands on the host app's single DB-writer thread, so the adapter never synchronizes itself.
 */
interface SqliteHandle {
    /**
     * Execute one DDL/DML statement with positional `?` bindings. Supported bind types:
     * `String`, `Long`, `Double`, `ByteArray`, `Boolean`, and `null`.
     */
    fun execute(sql: String, args: List<Any?> = emptyList())

    /** Run a query with positional `?` bindings; map each result row via [map] into a list. */
    fun <T> query(sql: String, args: List<Any?> = emptyList(), map: (SqliteRow) -> T): List<T>

    /** Run [body] inside a transaction: commit on normal return, roll back if it throws. */
    fun <T> transaction(body: () -> T): T
}

/**
 * One result row, columns addressed by name. Each getter returns `null` for a SQL NULL; pick the
 * getter matching the column's [io.rhizome.core.ColumnType] SQLite affinity (the adapter does).
 */
interface SqliteRow {
    fun getString(column: String): String?
    fun getLong(column: String): Long?
    fun getDouble(column: String): Double?
    fun getBlob(column: String): ByteArray?
}
