package io.rhizome.sqlite

import io.rhizome.core.ColumnDef
import io.rhizome.core.ColumnType
import io.rhizome.core.Registry
import io.rhizome.core.TableDef
import kotlinx.coroutines.test.runTest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Phase-2 verify gate: the registry-driven capture encoder reproduces ForestNote's legacy
 * hand-written `SyncWire` column JSON byte-for-byte (oracle = [LegacySyncWire]). Covers the special
 * encodings (ColorInt unsigned mask, Blob base64), nullable columns, and the alphabetical key order,
 * across stroke / text_box / folder — which between them exercise every v1 column type.
 */
class SqliteParityTest {

    private val stroke = TableDef(
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
    private val textBox = TableDef(
        name = "text_box", pk = "id", tombstone = "deleted_at",
        columns = listOf(
            ColumnDef("page_id", ColumnType.Text),
            ColumnDef("x", ColumnType.Int), ColumnDef("y", ColumnType.Int),
            ColumnDef("width", ColumnType.Int), ColumnDef("height", ColumnType.Int),
            ColumnDef("text", ColumnType.Text),
            ColumnDef("font_name", ColumnType.Text),
            ColumnDef("font_size", ColumnType.Int),
            ColumnDef("color", ColumnType.ColorInt),
            ColumnDef("weight", ColumnType.Int),
            ColumnDef("border_width", ColumnType.Int),
            ColumnDef("z", ColumnType.Int),
            ColumnDef("created_at", ColumnType.Timestamp),
            ColumnDef("deleted_at", ColumnType.Timestamp, nullable = true),
        ),
    )
    private val folder = TableDef(
        name = "folder", pk = "id", tombstone = "deleted_at",
        columns = listOf(
            ColumnDef("name", ColumnType.Text),
            ColumnDef("sort_order", ColumnType.Int),
            ColumnDef("created_at", ColumnType.Timestamp),
            ColumnDef("deleted_at", ColumnType.Timestamp, nullable = true),
            ColumnDef("parent_folder_id", ColumnType.Text, nullable = true),
        ),
    )
    private val registry = Registry(listOf(stroke, textBox, folder))

    private val points = byteArrayOf(0, 1, 2, 3, 4, 5, -1, -2, 127, -128)

    private fun newDb(): JdbcSqliteHandle {
        val db = JdbcSqliteHandle.inMemory()
        db.execute(
            "CREATE TABLE stroke (id TEXT PRIMARY KEY, page_id TEXT, color INTEGER, pen_width_min INTEGER, " +
                "pen_width_max INTEGER, points BLOB, z INTEGER, created_at INTEGER, deleted_at INTEGER)",
        )
        db.execute(
            "CREATE TABLE text_box (id TEXT PRIMARY KEY, page_id TEXT, x INTEGER, y INTEGER, width INTEGER, " +
                "height INTEGER, text TEXT, font_name TEXT, font_size INTEGER, color INTEGER, weight INTEGER, " +
                "border_width INTEGER, z INTEGER, created_at INTEGER, deleted_at INTEGER)",
        )
        db.execute(
            "CREATE TABLE folder (id TEXT PRIMARY KEY, name TEXT, sort_order INTEGER, created_at INTEGER, " +
                "deleted_at INTEGER, parent_folder_id TEXT)",
        )
        return db
    }

    private suspend fun capturedCols(db: JdbcSqliteHandle, table: String, pk: String): String {
        val adapter = SqliteStorageAdapter(db, registry, clock = { 1L })
        adapter.enableSync("siteA")
        adapter.capture(table, pk)
        return db.query("SELECT cols FROM rhizome_outbox WHERE tbl = ? AND pk = ?", listOf(table, pk)) {
            it.getString("cols")!!
        }.single()
    }

    @Test
    fun strokeColsMatchLegacy() = runTest {
        val db = newDb()
        val color = -16777216L // opaque black, signed ARGB Long
        db.execute(
            "INSERT INTO stroke (id, page_id, color, pen_width_min, pen_width_max, points, z, created_at, deleted_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            listOf("S1", "P1", color, 2L, 8L, points, 5L, 100L, null),
        )
        val expected = LegacySyncWire.strokeCols("P1", color, 2, 8, points, 5, 100, null)
        assertEquals(expected, capturedCols(db, "stroke", "S1"))
    }

    @Test
    fun textBoxColsMatchLegacy() = runTest {
        val db = newDb()
        val color = -1L // 0xFFFFFFFF
        db.execute(
            "INSERT INTO text_box (id, page_id, x, y, width, height, text, font_name, font_size, color, weight, " +
                "border_width, z, created_at, deleted_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            listOf("T1", "P1", 10L, 20L, 300L, 40L, "hello \"world\"", "Sans", 18L, color, 700L, 2L, 9L, 100L, null),
        )
        val expected = LegacySyncWire.textBoxCols("P1", 10, 20, 300, 40, "hello \"world\"", "Sans", 18, color, 700, 2, 9, 100, null)
        assertEquals(expected, capturedCols(db, "text_box", "T1"))
    }

    @Test
    fun folderColsMatchLegacyWithNullsAndNonNulls() = runTest {
        val db = newDb()
        // root folder: parent + deleted both null
        db.execute(
            "INSERT INTO folder (id, name, sort_order, created_at, deleted_at, parent_folder_id) VALUES (?, ?, ?, ?, ?, ?)",
            listOf("F1", "Inbox", 3L, 100L, null, null),
        )
        assertEquals(
            LegacySyncWire.folderCols("Inbox", 3, 100, null, null),
            capturedCols(db, "folder", "F1"),
        )

        // nested + tombstoned folder: both non-null
        db.execute(
            "INSERT INTO folder (id, name, sort_order, created_at, deleted_at, parent_folder_id) VALUES (?, ?, ?, ?, ?, ?)",
            listOf("F2", "Archive", 7L, 100L, 555L, "F1"),
        )
        assertEquals(
            LegacySyncWire.folderCols("Archive", 7, 100, 555, "F1"),
            capturedCols(db, "folder", "F2"),
        )
    }

    @Test
    fun blobBase64IsStandardPadded() = runTest {
        // Guard the exact base64 alphabet/padding the legacy used (StrokeSerializer BLOB).
        val db = newDb()
        db.execute(
            "INSERT INTO stroke (id, page_id, color, pen_width_min, pen_width_max, points, z, created_at, deleted_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            listOf("S2", "P1", 0L, 1L, 1L, points, 1L, 1L, null),
        )
        val cols = capturedCols(db, "stroke", "S2")
        val expectedB64 = Base64.getEncoder().encodeToString(points)
        assertEquals(true, cols.contains("\"points\":\"$expectedB64\""))
    }
}
