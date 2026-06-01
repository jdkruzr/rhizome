package io.rhizome.sqlite

import io.rhizome.core.ColumnDef
import io.rhizome.core.ColumnType
import io.rhizome.core.Registry
import io.rhizome.core.TableDef
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives generic capture: read a data row via the registry, encode each column by its [ColumnType],
 * and enqueue an outbox op. Exercises every column type, the dormant-when-disabled rule, the
 * server-authored-only skip, and monotonic op_seq allocation.
 */
class SqliteCaptureTest {

    private val widget = TableDef(
        name = "widget", pk = "id", tombstone = "deleted_at",
        columns = listOf(
            ColumnDef("name", ColumnType.Text),
            ColumnDef("count", ColumnType.Int),
            ColumnDef("ratio", ColumnType.Real),
            ColumnDef("flag", ColumnType.Bool),
            ColumnDef("color", ColumnType.ColorInt),
            ColumnDef("blob", ColumnType.Blob),
            ColumnDef("created_at", ColumnType.Timestamp),
            ColumnDef("deleted_at", ColumnType.Timestamp, nullable = true),
        ),
    )

    // A server-authored sibling the client must never capture.
    private val serverText = TableDef(
        name = "server_text", pk = "id", tombstone = "deleted_at", serverAuthoredOnly = true,
        columns = listOf(
            ColumnDef("text", ColumnType.Text),
            ColumnDef("created_at", ColumnType.Timestamp),
            ColumnDef("deleted_at", ColumnType.Timestamp, nullable = true),
        ),
    )

    private val registry = Registry(listOf(widget, serverText))
    private val blob = byteArrayOf(9, 8, 7, -1)

    private fun newDb(): JdbcSqliteHandle {
        val db = JdbcSqliteHandle.inMemory()
        db.execute(
            """
            CREATE TABLE widget (
              id TEXT PRIMARY KEY, name TEXT, count INTEGER, ratio REAL, flag INTEGER,
              color INTEGER, blob BLOB, created_at INTEGER, deleted_at INTEGER
            )
            """.trimIndent(),
        )
        db.execute("CREATE TABLE server_text (id TEXT PRIMARY KEY, text TEXT, created_at INTEGER, deleted_at INTEGER)")
        return db
    }

    private fun insertWidget(db: JdbcSqliteHandle, id: String, color: Long = -16777216L) {
        db.execute(
            "INSERT INTO widget (id, name, count, ratio, flag, color, blob, created_at, deleted_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            listOf(id, "hi", 42L, 3.5, 1L, color, blob, 100L, null),
        )
    }

    private fun outbox(db: JdbcSqliteHandle): List<Map<String, Any?>> =
        db.query("SELECT op_seq, tbl, pk, op_ts, cols FROM rhizome_outbox ORDER BY op_seq") { r ->
            mapOf(
                "op_seq" to r.getLong("op_seq"),
                "tbl" to r.getString("tbl"),
                "pk" to r.getString("pk"),
                "op_ts" to r.getLong("op_ts"),
                "cols" to r.getString("cols"),
            )
        }

    @Test
    fun captureEncodesEveryColumnTypeIntoOutbox() = runTest {
        val db = newDb()
        insertWidget(db, "W1")
        val adapter = SqliteStorageAdapter(db, registry, clock = { 1234L })
        adapter.enableSync("siteA")

        adapter.capture("widget", "W1")

        val rows = outbox(db)
        assertEquals(1, rows.size)
        val op = rows.single()
        assertEquals(1L, op["op_seq"])
        assertEquals("widget", op["tbl"])
        assertEquals("W1", op["pk"])
        assertEquals(1234L, op["op_ts"], "op_ts comes from the injected clock")

        val cols = Json.parseToJsonElement(op["cols"] as String).jsonObject
        assertEquals(JsonPrimitive("hi"), cols["name"])
        assertEquals(JsonPrimitive(42L), cols["count"])
        assertEquals(JsonPrimitive(3.5), cols["ratio"])
        assertEquals(JsonPrimitive(true), cols["flag"], "INTEGER 1 → Bool true")
        assertEquals(JsonPrimitive(4278190080L), cols["color"], "signed ARGB Long → unsigned wire")
        assertEquals(JsonPrimitive(Base64.getEncoder().encodeToString(blob)), cols["blob"])
        assertEquals(JsonPrimitive(100L), cols["created_at"])
        assertEquals(JsonNull, cols["deleted_at"], "null column encodes to JSON null")
    }

    @Test
    fun capturedColsAreInAlphabeticalKeyOrder() = runTest {
        val db = newDb()
        insertWidget(db, "W1")
        val adapter = SqliteStorageAdapter(db, registry, clock = { 1L })
        adapter.enableSync("siteA")
        adapter.capture("widget", "W1")

        val colsJson = outbox(db).single()["cols"] as String
        val keys = (Json.parseToJsonElement(colsJson) as JsonObject).keys.toList()
        assertEquals(keys.sorted(), keys, "cols keys are emitted alphabetically (matches knownCols + legacy SyncWire)")
    }

    @Test
    fun captureIsNoOpWhenSyncDisabled() = runTest {
        val db = newDb()
        insertWidget(db, "W1")
        val adapter = SqliteStorageAdapter(db, registry)
        // no enableSync → dormant
        adapter.capture("widget", "W1")
        assertTrue(outbox(db).isEmpty(), "no site_id ⇒ capture is a no-op")
    }

    @Test
    fun captureSkipsServerAuthoredOnlyTables() = runTest {
        val db = newDb()
        db.execute("INSERT INTO server_text (id, text, created_at, deleted_at) VALUES (?, ?, ?, ?)", listOf("T1", "ocr", 1L, null))
        val adapter = SqliteStorageAdapter(db, registry, clock = { 1L })
        adapter.enableSync("siteA")
        adapter.capture("server_text", "T1")
        assertTrue(outbox(db).isEmpty(), "client never authors a server-authored-only table")
    }

    @Test
    fun captureAllocatesMonotonicOpSeq() = runTest {
        val db = newDb()
        insertWidget(db, "W1")
        insertWidget(db, "W2")
        val adapter = SqliteStorageAdapter(db, registry, clock = { 1L })
        adapter.enableSync("siteA")
        adapter.capture("widget", "W1")
        adapter.capture("widget", "W2")
        val seqs = outbox(db).map { it["op_seq"] }
        assertEquals(listOf(1L, 2L), seqs, "op_seq is per-site monotonic")
    }
}
