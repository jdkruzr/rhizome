package io.rhizome.sqlite

import io.rhizome.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class IncomingPolicyTest {
    private val registry = Registry(listOf("plain", "notes", "reader").map {
        TableDef(it, "id", null, listOf(ColumnDef("text", ColumnType.Text)))
    })
    private fun database() = JdbcSqliteHandle.inMemory().also { db ->
        for (table in listOf("plain", "notes", "reader")) db.execute("CREATE TABLE $table(id TEXT PRIMARY KEY,text TEXT)")
        db.execute("CREATE TABLE inbox(tbl TEXT,seq INTEGER,body TEXT,PRIMARY KEY(tbl,seq))")
    }
    private fun op(table: String, seq: Long) = Op(table, "remote", "B", seq, 5000 + seq, buildJsonObject { put("text", "value-$seq") })
    private fun policy(table: String) = object : IncomingRowPolicy {
        override val tables = setOf(table)
        override suspend fun prepare(ops: List<Op>) = PreparedIncomingRows { db ->
            for (o in ops) db.execute("INSERT OR IGNORE INTO inbox VALUES(?,?,?)", listOf(o.table, o.opSeq, o.cols.toString()))
        }
    }
    @Test fun twoDomainsShareOneCommitAndPlainRowsStillMergeWithoutCollapsingDomainHistory() = runBlocking<Unit> {
        val db = database(); val s = SqliteStorageAdapter(db, registry, clock = { 1 }, incomingPolicies = listOf(policy("notes"), policy("reader")))
        s.enableSync("A")
        db.execute("INSERT INTO plain VALUES('local','outgoing')"); s.capture("plain", "local")
        val ops = listOf(op("notes", 1), op("notes", 2), op("reader", 3), op("plain", 4))
        s.acceptResponse(SyncResponse(acceptedThrough = 1, cursor = 40, ops = ops.map { it.toWire() }))
        assertEquals(3, db.query("SELECT seq FROM inbox") { true }.size)
        assertTrue(db.query("SELECT id FROM notes") { true }.isEmpty())
        assertEquals("value-4", db.query("SELECT text FROM plain WHERE id='remote'") { it.getString("text") }.single())
        assertEquals(40L, s.cursor()); assertFalse(s.hasPending())
        s.capture("plain", "local"); assertTrue(s.pendingOps().single().opTs > 5004)
    }

    @Test fun cursorFailureRollsBackBothPoliciesPlainRowsOutboxAndPersistedClock() = runBlocking<Unit> {
        val db = database(); val s = SqliteStorageAdapter(db, registry, clock = { 1 }, incomingPolicies = listOf(policy("notes"), policy("reader")))
        s.enableSync("A"); db.execute("INSERT INTO plain VALUES('local','outgoing')"); s.capture("plain", "local")
        val before = s.pendingOps()
        val response = SyncResponse(acceptedThrough = 1, cursor = 40, ops = listOf(op("notes", 1), op("reader", 2), op("plain", 3)).map { it.toWire() })
        db.execute("CREATE TRIGGER fail_cursor BEFORE UPDATE OF cursor ON rhizome_sync_state BEGIN SELECT RAISE(ABORT,'cursor failure'); END")
        assertFails { s.acceptResponse(response) }
        assertEquals(before, s.pendingOps()); assertEquals(0L, s.cursor())
        assertTrue(db.query("SELECT seq FROM inbox") { true }.isEmpty())
        assertTrue(db.query("SELECT id FROM plain WHERE id='remote'") { true }.isEmpty())
        assertEquals(1L, db.query("SELECT last_hlc FROM rhizome_sync_state") { it.getLong("last_hlc") }.single())
        db.execute("DROP TRIGGER fail_cursor"); s.acceptResponse(response)
        assertEquals(40L, s.cursor()); assertFalse(s.hasPending())
    }

    @Test fun preparationCancellationAndCommitFailureNeverSettleTheResponse() = runBlocking<Unit> {
        for (cancel in listOf(true, false)) {
            val db = database()
            val failing = object : IncomingRowPolicy {
                override val tables = setOf("reader")
                override suspend fun prepare(ops: List<Op>): PreparedIncomingRows {
                    if (cancel) throw CancellationException("cancel prepare")
                    return PreparedIncomingRows { error("commit failure") }
                }
            }
            val s = SqliteStorageAdapter(db, registry, incomingPolicies = listOf(policy("notes"), failing))
            s.enableSync("A")
            assertFails { s.acceptResponse(SyncResponse(acceptedThrough = 0, cursor = 40, ops = listOf(op("notes", 1), op("reader", 2)).map { it.toWire() })) }
            assertTrue(db.query("SELECT seq FROM inbox") { true }.isEmpty()); assertEquals(0L, s.cursor())
        }
    }

    @Test fun overlappingOrUnknownRoutesFailAtConstruction() {
        assertFails { SqliteStorageAdapter(database(), registry, incomingPolicies = listOf(policy("notes"), policy("notes"))) }
        assertFails { SqliteStorageAdapter(database(), registry, incomingPolicies = listOf(policy("unknown"))) }
    }
}
