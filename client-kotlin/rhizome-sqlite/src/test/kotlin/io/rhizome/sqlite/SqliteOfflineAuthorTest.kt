package io.rhizome.sqlite

import io.rhizome.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class SqliteOfflineAuthorTest {
    private val registry = Registry(listOf(TableDef("note", "id", null, listOf(ColumnDef("text", ColumnType.Text)))))
    private fun db() = JdbcSqliteHandle.inMemory().also { it.execute("CREATE TABLE note(id TEXT PRIMARY KEY,text TEXT)") }

    @Test fun explicitOfflineCaptureIsHiddenUntilOptInAndDoesNotChangeLegacyCapture() = runBlocking<Unit> {
        val db = db(); val store = SqliteStorageAdapter(db, registry, clock = { 1000 })
        db.execute("INSERT INTO note VALUES('n','first')")
        store.capture("note", "n")
        assertTrue(db.query("SELECT op_seq FROM rhizome_outbox") { true }.isEmpty())
        store.captureAuthored("note", "n", "siteA")
        db.execute("UPDATE note SET text='second'"); store.captureAuthored("note", "n", "siteA")
        assertNull(store.siteId()); assertTrue(store.pendingOps().isEmpty()); assertFalse(store.hasPending())
        assertEquals(0, assertIs<PendingRowPage.Page>(store.pendingPage(RowPageBudget(RowLimits(), 100))).ops.size)
        assertFails { store.markAckedThrough(100) }
        assertFails { store.enableSync("siteB") }
        store.enableSync("siteA")
        assertEquals(listOf(1L, 2L), store.pendingOps().map { it.opSeq })
        assertEquals(listOf(1000L, 1001L), store.pendingOps().map { it.opTs })
        assertEquals(listOf("first", "second"), store.pendingOps().map { it.cols.getValue("text").jsonPrimitive.content })
        assertFails { store.backfill() }
        store.backfillUntracked(); assertEquals(2, store.pendingOps().size)
    }

    @Test fun restartClockRollbackAndPulledVersionsNeverChangeTheOriginalAuthorTimeline() = runBlocking<Unit> {
        val db = db()
        val first = SqliteStorageAdapter(db, registry, clock = { 1000 })
        db.execute("INSERT INTO note VALUES('n','first')"); first.captureAuthored("note", "n", "siteA")
        val second = SqliteStorageAdapter(db, registry, clock = { 1 }) // models exclusive ownership after restart
        db.execute("UPDATE note SET text='second'"); second.captureAuthored("note", "n", "siteA")
        val remote = Op("note", "remote", "siteB", 1, 9000, buildJsonObject { put("text", "foreign") })
        second.applyRelayed(listOf(remote))
        db.execute("UPDATE note SET text='third' WHERE id='n'"); second.captureAuthored("note", "n", "siteA")
        second.enableSync("siteA"); second.backfillUntracked()
        val ops = second.pendingOps()
        assertEquals(listOf(1000L, 1001L, 9002L), ops.map { it.opTs })
        assertTrue(ops.all { it.pk == "n" }); assertEquals(listOf(1L, 2L, 3L), ops.map { it.opSeq })
        second.markAckedThrough(3); second.backfillUntracked(); assertTrue(second.pendingOps().isEmpty())
    }

    @Test fun rollbackIncludesDomainWriteOfflineJournalProvenanceAndAuthorBinding() = runBlocking<Unit> {
        val db = db(); val s = SqliteStorageAdapter(db, registry, clock = { 1000 })
        assertFails {
            db.transaction {
                db.execute("INSERT INTO note VALUES('n','failed')")
                runBlocking { s.captureAuthored("note", "n", "siteA") }
                error("after capture")
            }
        }
        assertTrue(db.query("SELECT id FROM note") { true }.isEmpty())
        assertTrue(db.query("SELECT tbl FROM rhizome_row_meta") { true }.isEmpty())
        assertTrue(db.query("SELECT op_seq FROM rhizome_outbox") { true }.isEmpty())
        assertTrue(db.query("SELECT site_id FROM rhizome_local_author") { true }.isEmpty())
        db.execute("INSERT INTO note VALUES('n','retry')"); s.captureAuthored("note", "n", "siteA")
        s.enableSync("siteA"); assertEquals(1L, s.pendingOps().single().opSeq)
    }
}
