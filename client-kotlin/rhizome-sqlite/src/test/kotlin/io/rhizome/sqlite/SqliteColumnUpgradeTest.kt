package io.rhizome.sqlite

import io.rhizome.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class SqliteColumnUpgradeTest {
    private val old = Registry(listOf(TableDef("note","id",null,listOf(ColumnDef("text",ColumnType.Text)))))
    private val current = Registry(listOf(old.tables.single().copy(columns=old.tables.single().columns+
        listOf(ColumnDef("size",ColumnType.Int),ColumnDef("ink",ColumnType.Blob,nullable=true)))))
    private fun op(seq:Long=1, site:String="B", ts:Long=100) = Op("note","n",site,seq,ts,buildJsonObject {
        put("text","original");put("size",17);put("ink","AAH/")
    })
    private fun db()=JdbcSqliteHandle.inMemory().also {it.execute("CREATE TABLE note(id TEXT PRIMARY KEY,text TEXT,size INTEGER NOT NULL DEFAULT 0,ink BLOB)")}
    private suspend fun seed(db:SqliteHandle,remote:Op=op()):SqliteStorageAdapter {
        val previous=SqliteStorageAdapter(db,old);previous.enableSync("A");previous.applyRelayed(listOf(remote));previous.setCursor(42)
        return SqliteStorageAdapter(db,current)
    }
    private fun size(db:SqliteHandle)=db.query("SELECT size FROM note") {it.getLong("size")!!}.single()
    private fun meta(db:SqliteHandle)=db.query("SELECT op_ts||':'||op_seq||':'||site_id AS v FROM rhizome_row_meta ORDER BY tbl,pk") {it.getString("v")!!}

    @Test fun equalVersionRepairsOnlyDeclaredFieldsWithoutReauthoringAndSurvivesRestart()=runBlocking {
        val db=db();var store=seed(db);val version=meta(db)
        store.applyRelayed(listOf(op()));assertEquals(0,size(db),"Ordinary equal replay remains inert")
        assertTrue(store.prepareColumnUpgrade(old));assertEquals(0,store.cursor());assertEquals(1,store.pendingColumnRepairs())
        store.setCursor(7);store=SqliteStorageAdapter(db,current)
        assertFalse(store.prepareColumnUpgrade(old));assertEquals(7,store.cursor())
        val replay=op().copy(cols=JsonObject(op().cols+ ("text" to JsonPrimitive("must not overwrite known field"))))
        store.applyRelayed(listOf(replay))
        assertEquals(17,size(db));assertEquals("original",db.query("SELECT text FROM note") {it.getString("text")}.single())
        assertContentEquals(byteArrayOf(0,1,-1),db.query("SELECT ink FROM note") {it.getBlob("ink")}.single())
        assertEquals(version,meta(db));assertTrue(store.pendingOps().isEmpty());assertEquals(0,store.pendingColumnRepairs())
        store.applyRelayed(listOf(op().copy(cols=JsonObject(op().cols+("size" to JsonPrimitive(999))))))
        assertEquals(17,size(db))
    }

    @Test fun olderOrWrongSiteVersionCannotRepairAndNewerLocalCaptureCancelsTicket()=runBlocking {
        val db=db();val store=seed(db);store.prepareColumnUpgrade(old)
        store.applyRelayed(listOf(op(ts=99),op(site="A")))
        assertEquals(0,size(db));assertEquals(1,store.pendingColumnRepairs())
        db.execute("UPDATE note SET text='local edit',size=23")
        store.capture("note","n");val pending=store.pendingOps();val version=meta(db)
        assertEquals(0,store.pendingColumnRepairs());store.applyRelayed(listOf(op()))
        assertEquals(23,size(db));assertEquals(pending,store.pendingOps());assertEquals(version,meta(db))
    }

    @Test fun newerRemoteWinnerSupersedesRepairAndPurgedRowsAreNeverResurrected()=runBlocking {
        val db=db();val store=seed(db);store.prepareColumnUpgrade(old)
        store.applyRelayed(listOf(op(ts=101).copy(cols=JsonObject(op().cols+("size" to JsonPrimitive(22))))))
        assertEquals(22,size(db));assertEquals(0,store.pendingColumnRepairs())
        val other=db();val purged=seed(other);purged.prepareColumnUpgrade(old);other.execute("DELETE FROM note")
        purged.applyRelayed(listOf(op()));assertTrue(other.query("SELECT id FROM note") {true}.isEmpty())
        assertEquals(0,purged.pendingColumnRepairs())
    }

    @Test fun missingFieldsRemainPendingAndInvalidDecodeRollsBackResponseAcknowledgement()=runBlocking {
        val db=db();val store=seed(db);store.prepareColumnUpgrade(old)
        store.applyRelayed(listOf(op().copy(cols=JsonObject(op().cols-"ink"))))
        assertEquals(0,size(db));assertEquals(1,store.pendingColumnRepairs())
        db.execute("INSERT INTO note VALUES('local','queued',1,NULL)");store.capture("note","local")
        val pending=store.pendingOps();store.setCursor(7)
        val invalid=op().copy(cols=JsonObject(op().cols+("ink" to JsonPrimitive("not base64!"))))
        assertFails {store.acceptResponse(SyncResponse(acceptedThrough=pending.last().opSeq,cursor=99,ops=listOf(invalid.toWire())))}
        assertEquals(pending,store.pendingOps());assertEquals(7,store.cursor());assertEquals(1,store.pendingColumnRepairs())
        assertEquals(0,db.query("SELECT size FROM note WHERE id='n'") {it.getLong("size")}.single())
        store.acceptResponse(SyncResponse(acceptedThrough=pending.last().opSeq,cursor=99,ops=listOf(op().toWire())))
        assertTrue(store.pendingOps().isEmpty());assertEquals(0,store.pendingColumnRepairs())
    }

    @Test fun schedulingFailureRollsBackTicketsCursorAndLedgerForRetry()=runBlocking {
        val db=db();val store=seed(db)
        db.execute("CREATE TRIGGER fail_upgrade BEFORE INSERT ON rhizome_column_upgrade BEGIN SELECT RAISE(ABORT,'injected'); END")
        assertFails {store.prepareColumnUpgrade(old)}
        assertEquals(42,store.cursor());assertEquals(0,store.pendingColumnRepairs())
        db.execute("DROP TRIGGER fail_upgrade");assertTrue(store.prepareColumnUpgrade(old));assertEquals(1,store.pendingColumnRepairs())
    }

    @Test fun selfAuthoredRowsKeepMigrationDefaultsWithoutWaitingForExcludedOwnRelay()=runBlocking {
        val db=db();val store=seed(db,op(site="A"))
        assertTrue(store.prepareColumnUpgrade(old));assertEquals(0,store.pendingColumnRepairs())
        store.applyRelayed(listOf(op(site="A")));assertEquals(0,size(db))
    }

    @Test fun unsupportedRegistryChangesAndUnboundAuthorFailBeforeMutation()=runBlocking {
        val db=db();val store=seed(db)
        assertFails {store.prepareColumnUpgrade(Registry(listOf(old.tables.single().copy(pk="different"))))}
        assertFails {store.prepareColumnUpgrade(Registry(listOf(old.tables.single().copy(columns=listOf(ColumnDef("text",ColumnType.Blob))))))}
        assertEquals(42,store.cursor());assertEquals(0,store.pendingColumnRepairs())
        val offline=SqliteStorageAdapter(db(),current)
        assertFails {offline.prepareColumnUpgrade(old)}
        assertEquals(0,offline.pendingColumnRepairs())
    }
}
