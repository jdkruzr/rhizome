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

/**
 * The HLC wiring (spec/hlc.md): capture stamps op_ts from the clock, applyRelayed absorbs remote
 * timestamps so a later local edit sorts strictly after them, and the clock state survives an
 * adapter (process) restart.
 */
class SqliteHlcTest {

    private val note = TableDef(
        name = "note", pk = "id", tombstone = "deleted_at",
        columns = listOf(
            ColumnDef("text", ColumnType.Text),
            ColumnDef("created_at", ColumnType.Timestamp),
            ColumnDef("deleted_at", ColumnType.Timestamp, nullable = true),
        ),
    )
    private val registry = Registry(listOf(note))

    private fun newDb(): JdbcSqliteHandle {
        val db = JdbcSqliteHandle.inMemory()
        db.execute("CREATE TABLE note (id TEXT PRIMARY KEY, text TEXT, created_at INTEGER, deleted_at INTEGER)")
        return db
    }

    private fun insertNote(db: JdbcSqliteHandle, id: String) =
        db.execute("INSERT INTO note (id, text, created_at, deleted_at) VALUES (?, ?, ?, ?)", listOf(id, "t", 100L, null))

    private fun opTsOf(adapter: SqliteStorageAdapter, pk: String): Long =
        // pendingOps reflects the captured op_ts.
        kotlinx.coroutines.runBlocking { adapter.pendingOps() }.first { it.pk == pk }.opTs

    @Test
    fun captureStampsStrictlyMonotonicOpTsWithinAMillisecond() = runTest {
        val db = newDb()
        listOf("N1", "N2", "N3").forEach { insertNote(db, it) }
        val adapter = SqliteStorageAdapter(db, registry, clock = { 1000L }) // clock frozen
        adapter.enableSync("siteA")
        listOf("N1", "N2", "N3").forEach { adapter.capture("note", it) }

        val tss = adapter.pendingOps().map { it.opTs }
        assertEquals(listOf(1000L, 1001L, 1002L), tss, "same-ms captures tick the HLC, never collide")
    }

    @Test
    fun applyRelayedBumpsClockSoLaterCaptureBeatsRelayedOp() = runTest {
        val db = newDb()
        insertNote(db, "LOCAL")
        val adapter = SqliteStorageAdapter(db, registry, clock = { 1000L })
        adapter.enableSync("siteA")

        // A remote op arrives stamped far in the future (e.g. a peer with a fast clock).
        val relayed = Op(
            table = "note", pk = "REMOTE", siteId = "siteB", opSeq = 1, opTs = 5_000,
            cols = buildJsonObject { put("text", "from B"); put("created_at", 5_000L) },
        )
        adapter.applyRelayed(listOf(relayed))

        // A subsequent local edit must sort strictly AFTER the absorbed remote, despite wall=1000.
        adapter.capture("note", "LOCAL")
        val localTs = opTsOf(adapter, "LOCAL")
        assertTrue(localTs > 5_000, "local op_ts ($localTs) must exceed the absorbed remote (5000)")
    }

    @Test
    fun lastHlcPersistsAcrossAdapterRestart() = runTest {
        val db = newDb()
        insertNote(db, "N1")
        insertNote(db, "N2")

        val first = SqliteStorageAdapter(db, registry, clock = { 1000L })
        first.enableSync("siteA")
        first.capture("note", "N1") // op_ts 1000

        // New adapter on the SAME db (simulates a process restart), same frozen wall.
        val second = SqliteStorageAdapter(db, registry, clock = { 1000L })
        second.capture("note", "N2")

        val n2 = second.pendingOps().first { it.pk == "N2" }.opTs
        assertEquals(1001L, n2, "the clock was seeded from persisted last_hlc, not reset to wall")
    }
}
