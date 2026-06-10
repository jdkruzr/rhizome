package io.rhizome.sqlite

import io.rhizome.core.ColumnDef
import io.rhizome.core.ColumnType
import io.rhizome.core.Op
import io.rhizome.core.Registry
import io.rhizome.core.TableDef
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Drives registry-driven backfill: every capturable row enqueued once; server-authored skipped. */
class SqliteBackfillTest {

    private val note = TableDef(
        name = "note", pk = "id", tombstone = "deleted_at",
        columns = listOf(
            ColumnDef("text", ColumnType.Text),
            ColumnDef("created_at", ColumnType.Timestamp),
            ColumnDef("deleted_at", ColumnType.Timestamp, nullable = true),
        ),
    )
    private val serverText = TableDef(
        name = "server_text", pk = "id", tombstone = "deleted_at", serverAuthoredOnly = true,
        columns = listOf(
            ColumnDef("text", ColumnType.Text),
            ColumnDef("created_at", ColumnType.Timestamp),
            ColumnDef("deleted_at", ColumnType.Timestamp, nullable = true),
        ),
    )
    private val registry = Registry(listOf(note, serverText))

    private fun newDb(): JdbcSqliteHandle {
        val db = JdbcSqliteHandle.inMemory()
        db.execute("CREATE TABLE note (id TEXT PRIMARY KEY, text TEXT, created_at INTEGER, deleted_at INTEGER)")
        db.execute("CREATE TABLE server_text (id TEXT PRIMARY KEY, text TEXT, created_at INTEGER, deleted_at INTEGER)")
        return db
    }

    private fun outboxPks(db: JdbcSqliteHandle): List<String> =
        db.query("SELECT pk FROM rhizome_outbox ORDER BY op_seq") { it.getString("pk")!! }

    @Test
    fun backfillEnqueuesEveryCapturableRowAndSkipsServerAuthored() = runTest {
        val db = newDb()
        listOf("N1", "N2", "N3").forEach { db.execute("INSERT INTO note (id, text, created_at) VALUES (?, ?, ?)", listOf(it, "t", 1L)) }
        db.execute("INSERT INTO server_text (id, text, created_at) VALUES (?, ?, ?)", listOf("S1", "ocr", 1L))
        val adapter = SqliteStorageAdapter(db, registry, clock = { 1L })
        adapter.enableSync("siteA")

        adapter.backfill()

        assertEquals(setOf("N1", "N2", "N3"), outboxPks(db).toSet(), "every note row enqueued, server_text skipped")
        assertEquals(3, outboxPks(db).size, "no duplicates, no server-authored op")
    }

    @Test
    fun backfillIsNoOpWhenDisabled() = runTest {
        val db = newDb()
        db.execute("INSERT INTO note (id, text, created_at) VALUES (?, ?, ?)", listOf("N1", "t", 1L))
        val adapter = SqliteStorageAdapter(db, registry)
        adapter.backfill()
        assertTrue(outboxPks(db).isEmpty())
    }

    @Test
    fun backfillUntrackedCapturesOnlyRowsWithoutProvenance() = runTest {
        val db = newDb()
        db.execute("INSERT INTO note (id, text, created_at) VALUES (?, ?, ?)", listOf("local", "t", 1L))
        db.execute("INSERT INTO server_text (id, text, created_at) VALUES (?, ?, ?)", listOf("server", "ocr", 1L))
        val adapter = SqliteStorageAdapter(db, registry, clock = { 3000L })
        adapter.enableSync("siteA")
        adapter.applyRelayed(
            listOf(
                Op(
                    table = "note",
                    pk = "remote",
                    siteId = "siteB",
                    opSeq = 1,
                    opTs = 2000L,
                    cols = buildJsonObject {
                        put("text", "from server")
                        put("created_at", 2000L)
                        put("deleted_at", null as String?)
                    },
                ),
            ),
        )

        adapter.backfillUntracked()

        assertEquals(listOf("local"), outboxPks(db), "remote row has provenance; only local untracked row is captured")
    }

    @Test
    fun backfillUntrackedIsNoOpWhenDisabled() = runTest {
        val db = newDb()
        db.execute("INSERT INTO note (id, text, created_at) VALUES (?, ?, ?)", listOf("N1", "t", 1L))
        val adapter = SqliteStorageAdapter(db, registry)
        adapter.backfillUntracked()
        assertTrue(outboxPks(db).isEmpty())
    }
}
