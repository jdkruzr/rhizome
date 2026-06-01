package io.rhizome.sqlite

import io.rhizome.core.ColumnDef
import io.rhizome.core.ColumnType
import io.rhizome.core.Op
import io.rhizome.core.Registry
import io.rhizome.core.TableDef
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drives the registry-driven dynamic apply (the R1 de-risk): a relayed stroke op — including the
 * tricky `ColorInt` (unsigned wire ↔ signed ARGB Long) and `Blob` columns — UPSERTs into a real
 * SQLite `stroke` table created outside the adapter (as SQLDelight does in ForestNote).
 */
class SqliteApplyTest {

    private val strokeTable = TableDef(
        name = "stroke", pk = "id", tombstone = "deleted_at",
        columns = listOf(
            ColumnDef("page_id", ColumnType.Text),
            ColumnDef("color", ColumnType.ColorInt),
            ColumnDef("pen_width_min", ColumnType.Int),
            ColumnDef("pen_width_max", ColumnType.Int),
            ColumnDef("points", ColumnType.Blob),
            ColumnDef("z", ColumnType.Int),
            ColumnDef("created_at", ColumnType.Timestamp),
            ColumnDef("deleted_at", ColumnType.Timestamp, nullable = true),
        ),
    )
    private val registry = Registry(listOf(strokeTable))

    private fun newDb(): JdbcSqliteHandle {
        val db = JdbcSqliteHandle.inMemory()
        db.execute(
            """
            CREATE TABLE stroke (
              id            TEXT PRIMARY KEY,
              page_id       TEXT NOT NULL,
              color         INTEGER NOT NULL,
              pen_width_min INTEGER NOT NULL,
              pen_width_max INTEGER NOT NULL,
              points        BLOB NOT NULL,
              z             INTEGER NOT NULL,
              created_at    INTEGER NOT NULL,
              deleted_at    INTEGER
            )
            """.trimIndent(),
        )
        return db
    }

    private val points = byteArrayOf(1, 2, 3, -1, 127, -128)

    private fun strokeOp(siteId: String, opSeq: Long, opTs: Long, z: Long) = Op(
        table = "stroke", pk = "S1", siteId = siteId, opSeq = opSeq, opTs = opTs,
        cols = buildJsonObject {
            put("page_id", "P1")
            put("color", 4278190080L) // 0xFF000000 as unsigned int64 on the wire
            put("pen_width_min", 2L)
            put("pen_width_max", 8L)
            put("points", Base64.getEncoder().encodeToString(points))
            put("z", z)
            put("created_at", 100L)
            put("deleted_at", JsonNull)
        },
    )

    @Test
    fun applyRelayedUpsertsStrokeRowDecodingColorAndBlob() = runTest {
        val db = newDb()
        val adapter = SqliteStorageAdapter(db, registry)

        adapter.applyRelayed(listOf(strokeOp(siteId = "siteA", opSeq = 1, opTs = 100, z = 5)))

        val rows = db.query("SELECT * FROM stroke WHERE id = ?", listOf("S1")) { r ->
            mapOf(
                "page_id" to r.getString("page_id"),
                "color" to r.getLong("color"),
                "points" to r.getBlob("points"),
                "z" to r.getLong("z"),
                "deleted_at" to r.getLong("deleted_at"),
            )
        }
        assertEquals(1, rows.size, "stroke row should be inserted")
        val row = rows.single()
        assertEquals("P1", row["page_id"])
        assertEquals(-16777216L, row["color"], "unsigned wire color decodes to the signed ARGB Long")
        assertTrue(points.contentEquals(row["points"] as ByteArray), "blob survives base64 round-trip")
        assertEquals(5L, row["z"])
        assertNull(row["deleted_at"], "null tombstone stays null")
    }

    private fun zOf(db: JdbcSqliteHandle): Long =
        db.query("SELECT z FROM stroke WHERE id = ?", listOf("S1")) { it.getLong("z")!! }.single()

    private fun rowCount(db: JdbcSqliteHandle): Long =
        db.query("SELECT COUNT(*) AS c FROM stroke") { it.getLong("c")!! }.single()

    @Test
    fun lowerKeyOpDoesNotOverwriteHigherKeyWinner() = runTest {
        val db = newDb()
        val adapter = SqliteStorageAdapter(db, registry)
        adapter.applyRelayed(listOf(strokeOp(siteId = "siteA", opSeq = 9, opTs = 200, z = 9)))
        // A strictly-older op for the same row must be discarded by LWW-vs-stored.
        adapter.applyRelayed(listOf(strokeOp(siteId = "siteA", opSeq = 1, opTs = 100, z = 5)))
        assertEquals(9L, zOf(db), "older op must not clobber the stored winner")
    }

    @Test
    fun higherKeyOpOverwrites() = runTest {
        val db = newDb()
        val adapter = SqliteStorageAdapter(db, registry)
        adapter.applyRelayed(listOf(strokeOp(siteId = "siteA", opSeq = 1, opTs = 100, z = 5)))
        adapter.applyRelayed(listOf(strokeOp(siteId = "siteA", opSeq = 9, opTs = 200, z = 9)))
        assertEquals(9L, zOf(db), "newer op wins")
        assertEquals(1L, rowCount(db), "still one row (UPSERT, not duplicate insert)")
    }

    @Test
    fun reDeliveryOfSameOpIsIdempotent() = runTest {
        val db = newDb()
        val adapter = SqliteStorageAdapter(db, registry)
        val op = strokeOp(siteId = "siteA", opSeq = 1, opTs = 100, z = 5)
        adapter.applyRelayed(listOf(op))
        adapter.applyRelayed(listOf(op))
        assertEquals(1L, rowCount(db))
        assertEquals(5L, zOf(db))
    }

    @Test
    fun tombstoneOpStoresDeletedAt() = runTest {
        val db = newDb()
        val adapter = SqliteStorageAdapter(db, registry)
        val dead = Op(
            table = "stroke", pk = "S1", siteId = "siteA", opSeq = 2, opTs = 300,
            cols = buildJsonObject {
                put("page_id", "P1")
                put("color", 4278190080L)
                put("pen_width_min", 2L)
                put("pen_width_max", 8L)
                put("points", Base64.getEncoder().encodeToString(points))
                put("z", 5L)
                put("created_at", 100L)
                put("deleted_at", 300L) // non-null tombstone
            },
        )
        adapter.applyRelayed(listOf(dead))
        val deletedAt = db.query("SELECT deleted_at FROM stroke WHERE id = ?", listOf("S1")) {
            it.getLong("deleted_at")
        }.single()
        assertEquals(300L, deletedAt, "tombstone column persists; sync never hard-deletes")
    }
}
