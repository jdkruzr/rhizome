package io.rhizome.sqlite

import io.rhizome.core.ColumnDef
import io.rhizome.core.ColumnType
import io.rhizome.core.Op
import io.rhizome.core.Registry
import io.rhizome.core.SyncEngine
import io.rhizome.core.SyncOutcome
import io.rhizome.core.SyncRequest
import io.rhizome.core.SyncResponse
import io.rhizome.core.SyncResult
import io.rhizome.core.SyncTransport
import io.rhizome.core.TableDef
import io.rhizome.core.WireOp
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drives the [io.rhizome.core.SyncLocalStore] reads — site id, cursor, the outbox round-trip,
 * ack pruning — and proves a real [SyncEngine] can push captured ops and apply relayed ones
 * through the adapter against a live SQLite database with only the transport faked.
 */
class SqliteStoreTest {

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

    private fun insertNote(db: JdbcSqliteHandle, id: String, text: String) =
        db.execute("INSERT INTO note (id, text, created_at, deleted_at) VALUES (?, ?, ?, ?)", listOf(id, text, 100L, null))

    @Test
    fun siteIdNullWhenDisabled() = runTest {
        val adapter = SqliteStorageAdapter(newDb(), registry)
        assertNull(adapter.siteId())
    }

    @Test
    fun enableSyncSetsSiteIdAndIsIdempotent() = runTest {
        val adapter = SqliteStorageAdapter(newDb(), registry)
        adapter.enableSync("siteA")
        adapter.enableSync("siteB") // must NOT re-mint a stable id
        assertEquals("siteA", adapter.siteId())
    }

    @Test
    fun cursorRoundTrips() = runTest {
        val adapter = SqliteStorageAdapter(newDb(), registry)
        adapter.enableSync("siteA")
        assertEquals(0L, adapter.cursor())
        adapter.setCursor(42)
        assertEquals(42L, adapter.cursor())
    }

    @Test
    fun pendingOpsReturnsCapturedOpsInOrder() = runTest {
        val db = newDb()
        insertNote(db, "N1", "first")
        insertNote(db, "N2", "second")
        val adapter = SqliteStorageAdapter(db, registry, clock = { 7L })
        adapter.enableSync("siteA")
        adapter.capture("note", "N1")
        adapter.capture("note", "N2")

        val ops = adapter.pendingOps()
        assertEquals(listOf("N1", "N2"), ops.map { it.pk })
        assertEquals(listOf(1L, 2L), ops.map { it.opSeq })
        assertTrue(ops.all { it.siteId == "siteA" && it.table == "note" })
        // Both captured in the same wall-ms; the HLC ticks so op_ts is strictly monotonic (7, 8).
        assertEquals(listOf(7L, 8L), ops.map { it.opTs })
        assertEquals("first", ops.first().cols["text"]!!.let { it.toString().trim('"') })
    }

    @Test
    fun markAckedThroughPrunesSettledOps() = runTest {
        val db = newDb()
        listOf("N1", "N2", "N3").forEach { insertNote(db, it, "t") }
        val adapter = SqliteStorageAdapter(db, registry)
        adapter.enableSync("siteA")
        listOf("N1", "N2", "N3").forEach { adapter.capture("note", it) }

        adapter.markAckedThrough(2)
        assertEquals(listOf(3L), adapter.pendingOps().map { it.opSeq }, "ops with op_seq ≤ through are pruned")
    }

    private fun textOf(db: JdbcSqliteHandle, id: String): String? =
        db.query("SELECT text FROM note WHERE id = ?", listOf(id)) { it.getString("text") }.single()

    @Test
    fun localCaptureBeatsStrictlyOlderRelayedOp() = runTest {
        // Capturing a local edit records it as the row's current LWW winner (via rhizome_row_meta),
        // so a later-arriving STRICTLY-OLDER relayed op cannot clobber it. Without this the author
        // diverges: it never receives its own op back to re-establish the winner.
        val db = newDb()
        insertNote(db, "N1", "local-latest")
        val adapter = SqliteStorageAdapter(db, registry, clock = { 1000L })
        adapter.enableSync("siteA")
        adapter.capture("note", "N1") // local write, op_ts = 1000

        val older = Op(
            table = "note", pk = "N1", siteId = "siteB", opSeq = 1, opTs = 500,
            cols = buildJsonObject { put("text", "stale-remote"); put("created_at", 500L) },
        )
        adapter.applyRelayed(listOf(older))

        assertEquals("local-latest", textOf(db, "N1"), "older relayed op must not overwrite the newer local write")
    }

    @Test
    fun strictlyNewerRelayedOpBeatsLocalCapture() = runTest {
        val db = newDb()
        insertNote(db, "N1", "local")
        val adapter = SqliteStorageAdapter(db, registry, clock = { 1000L })
        adapter.enableSync("siteA")
        adapter.capture("note", "N1") // op_ts = 1000

        val newer = Op(
            table = "note", pk = "N1", siteId = "siteB", opSeq = 1, opTs = 2000,
            cols = buildJsonObject { put("text", "remote-newer"); put("created_at", 2000L) },
        )
        adapter.applyRelayed(listOf(newer))

        assertEquals("remote-newer", textOf(db, "N1"), "strictly-newer relayed op overwrites the local write")
    }

    /** A fake transport: records the request, replies once with a relayed op then drains. */
    private class FakeTransport(private val reply: SyncResponse) : SyncTransport {
        var lastRequest: SyncRequest? = null
        override suspend fun post(request: SyncRequest): SyncOutcome {
            lastRequest = request
            return SyncOutcome.Ok(reply)
        }
    }

    @Test
    fun enginePushesPendingAndAppliesRelayed() = runTest {
        val db = newDb()
        insertNote(db, "N1", "local")
        val adapter = SqliteStorageAdapter(db, registry, clock = { 50L })
        adapter.enableSync("siteA")
        adapter.capture("note", "N1")

        val relayed = WireOp(
            table = "note", pk = "N2", siteId = "siteB", opSeq = 1, opTs = 500,
            cols = buildJsonObject {
                put("text", "from B")
                put("created_at", 500L)
            },
        )
        val transport = FakeTransport(
            SyncResponse(acceptedThrough = 1, ops = listOf(relayed), cursor = 7, hasMore = false),
        )
        val engine = SyncEngine(adapter, transport, registry.schemaHash(), clock = { 0L })

        val result = engine.syncOnce()

        assertEquals(SyncResult.Success, result)
        // pushed our captured op
        assertEquals(listOf("N1"), transport.lastRequest!!.ops.map { it.pk })
        // applied the relayed op into the live table
        val n2 = db.query("SELECT text FROM note WHERE id = ?", listOf("N2")) { it.getString("text") }.singleOrNull()
        assertEquals("from B", n2, "relayed op materialized in the data table")
        // settled our outbox and adopted the cursor
        assertTrue(adapter.pendingOps().isEmpty(), "accepted op pruned from outbox")
        assertEquals(7L, adapter.cursor())
    }
}
