package io.rhizome.sqlite

import io.rhizome.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class BoundedRowsTest {
    private val site = "0000000000000000000000000A"
    private val schema = "test-registry"
    private val registry = Registry(listOf(TableDef("note", "id", null, listOf(ColumnDef("text", ColumnType.Text)))))
    private val caps = SyncCapabilities(1, setOf("assets-v1", "bounded-rows-v1"), setOf(schema), RowLimits(), AssetLimits(262144, 256))

    private class CountingDb(val delegate: SqliteHandle) : SqliteHandle by delegate {
        var payloadRows = 0
        var wholeOutboxReads = 0
        override fun <T> query(sql: String, args: List<Any?>, map: (SqliteRow) -> T): List<T> {
            if (sql.contains("FROM rhizome_outbox") && sql.contains("cols") && !sql.contains("LIMIT 1")) wholeOutboxReads++
            return delegate.query(sql, args) { row ->
                map(object : SqliteRow by row {
                    override fun getString(column: String): String? = row.getString(column).also {
                        if (column == "cols" && it != null) payloadRows++
                    }
                })
            }
        }
    }
    private fun database(): CountingDb = CountingDb(JdbcSqliteHandle.inMemory()).also {
        it.execute("CREATE TABLE note(id TEXT PRIMARY KEY,text TEXT NOT NULL)")
    }
    private fun enqueue(db: SqliteHandle, seq: Long, text: String) {
        db.execute("INSERT INTO rhizome_outbox(op_seq,tbl,pk,op_ts,cols) VALUES(?,'note','N',1,?)",
            listOf(seq, buildJsonObject { put("text", text) }.toString()))
    }

    @Test fun hundredThousandRowsLoadsOnlyOnePageOfPayloads() = runBlocking {
        val db = database(); val store = SqliteStorageAdapter(db, registry); store.enableSync(site)
        val emptySize = RowWire.bytes(WireOp("note", "N", site, 1, 1, buildJsonObject { put("text", "") }))
        db.execute("""WITH RECURSIVE numbers(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM numbers WHERE n<100000)
            INSERT INTO rhizome_outbox(op_seq,tbl,pk,op_ts,cols)
            SELECT n,'note','N',1,'{"text":"' || substr(?,1,1024-?+1-length(CAST(n AS TEXT))) || '"}' FROM numbers""",
            listOf("A".repeat(1024), emptySize.toLong()))
        val result = assertIs<PendingRowPage.Page>(store.pendingPage(RowPageBudget(RowLimits(), 200)))
        assertEquals(500, result.ops.size); assertTrue(result.hasMore)
        assertEquals(1, result.ops.first().opSeq); assertEquals(500, result.ops.last().opSeq)
        assertTrue(result.ops.all { RowWire.bytes(it.toWire()) == 1024 })
        assertTrue(db.payloadRows <= 501, "loaded ${db.payloadRows}")
        assertEquals(0, db.wholeOutboxReads)
    }

    @Test fun oversizedOperationIsNotLoadedSkippedPrunedOrPosted() = runBlocking {
        val db = database(); val store = SqliteStorageAdapter(db, registry); store.enableSync(site); store.setCursor(42)
        enqueue(db, 1, "first"); enqueue(db, 2, "X".repeat(9_000_000)); enqueue(db, 3, "third")
        val posted = mutableListOf<Long>()
        val transport = object : BoundedRowTransport {
            override suspend fun capabilities() = CapabilityOutcome.Available(caps)
            override suspend fun post(request: SyncRequest): SyncOutcome = error("legacy fallback")
            override suspend fun postBounded(request: SyncRequest, limits: RowLimits): SyncOutcome {
                posted += request.ops.map { it.opSeq }
                return SyncOutcome.Ok(SyncResponse(acceptedThrough = 1, cursor = 42))
            }
        }
        val session = BoundedSyncSession(store, transport, schema)
        assertTrue(assertIs<RowExchange.Page>(session.exchange()).hasMore)
        val result = assertIs<RowExchange.Stopped>(session.exchange())
        assertEquals(2, result.oversized!!.opSeq)
        assertEquals(listOf(1L), posted); assertEquals(42, store.cursor())
        assertEquals(listOf(2L, 3L), db.query("SELECT op_seq FROM rhizome_outbox ORDER BY op_seq") { it.getLong("op_seq")!! })
        assertEquals(1, db.payloadRows) // only op 1; the 9 MB TEXT never crosses the DB seam
        assertEquals(0, db.wholeOutboxReads)
    }

    @Test fun exactUtf8EnvelopeSoftTargetAndSingleLargeRow() = runBlocking {
        val db = database(); val store = SqliteStorageAdapter(db, registry); store.enableSync(site)
        enqueue(db, 1, "🙂<&>漢字".repeat(50)); enqueue(db, 2, "next")
        val limits = RowLimits(targetPageBytes = 300, maxRowBytes = 2000, maxBodyBytes = 2500)
        val empty = SyncRequest(schemaHash = schema, siteId = site, cursor = 0, ops = emptyList())
        val page = assertIs<PendingRowPage.Page>(store.pendingPage(RowPageBudget(limits, RowWire.envelope(empty))))
        assertEquals(listOf(1L), page.ops.map { it.opSeq }); assertTrue(page.hasMore)
        val encoded = RowWire.json.encodeToString(SyncRequest.serializer(), empty.copy(ops = page.ops.map { it.toWire() })).toByteArray()
        assertTrue(encoded.size > limits.targetPageBytes && encoded.size <= limits.maxBodyBytes)
        assertEquals(RowWire.envelope(empty) + RowWire.bytes(page.ops.single().toWire()), encoded.size)
    }

    @Test fun capabilityFailuresNeverReadOrPostTheOutbox() = runBlocking {
        for ((discovery, expected) in listOf(
            CapabilityOutcome.Legacy to SyncResult.Failed::class,
            CapabilityOutcome.HttpError(401) to SyncResult.AuthRequired::class,
            CapabilityOutcome.HttpError(403) to SyncResult.AuthRequired::class,
            CapabilityOutcome.Available(caps.copy(version = 2)) to SyncResult.Failed::class,
            CapabilityOutcome.Available(caps.copy(features = setOf("assets-v1"))) to SyncResult.Failed::class,
            CapabilityOutcome.Available(caps.copy(acceptedSchemaHashes = setOf("other"))) to SyncResult.SchemaMismatch::class,
        )) {
            val db = database(); val store = SqliteStorageAdapter(db, registry); store.enableSync(site); store.setCursor(42); enqueue(db, 1, "queued")
            val transport = object : BoundedRowTransport {
                override suspend fun capabilities() = discovery
                override suspend fun post(request: SyncRequest): SyncOutcome = error("legacy fallback")
                override suspend fun postBounded(request: SyncRequest, limits: RowLimits): SyncOutcome = error("must not post")
            }
            val result = assertIs<RowExchange.Stopped>(BoundedSyncSession(store, transport, schema).exchange())
            assertEquals(expected, result.reason::class)
            assertEquals(0, db.payloadRows); assertEquals(42, store.cursor()); assertTrue(store.hasPending())
        }
    }

    @Test fun failedLocalApplyCannotAcknowledgeOrAdvanceCursor() = runBlocking {
        val db = database(); val store = SqliteStorageAdapter(db, registry); store.enableSync(site); store.setCursor(42); enqueue(db, 1, "queued")
        db.execute("CREATE TRIGGER fail_apply BEFORE INSERT ON note BEGIN SELECT RAISE(ABORT,'injected apply failure'); END")
        val response = SyncResponse(acceptedThrough = 1, cursor = 99, ops = listOf(WireOp("note", "remote", "B", 1, 100,
            buildJsonObject { put("text", "incoming") })))
        assertFails { store.acceptResponse(response) }
        assertTrue(store.hasPending()); assertEquals(42, store.cursor())
    }
}
