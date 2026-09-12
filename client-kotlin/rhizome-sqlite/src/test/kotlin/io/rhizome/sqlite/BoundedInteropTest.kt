package io.rhizome.sqlite

import io.rhizome.core.*
import io.rhizome.http.HttpUrlTransport
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64
import kotlin.test.*

class BoundedInteropTest {
    @get:Rule val temp = TemporaryFolder()
    private val a = "0000000000000000000000000A"
    private val b = "0000000000000000000000000B"
    // A legacy registry accepted by the actual UB host; no speculative reader hash.
    internal val schema = "74e6b5d790c919290d0e1fca3462800a5dc4abb288042dda2b48d4eb0482bbf2"
    private val registry = Registry(listOf(TableDef("notebook", "id", "deleted_at", listOf(
        ColumnDef("name", ColumnType.Text), ColumnDef("sort_order", ColumnType.Int),
        ColumnDef("created_at", ColumnType.Timestamp), ColumnDef("deleted_at", ColumnType.Timestamp, true),
        ColumnDef("folder_id", ColumnType.Text, true), ColumnDef("aspect_long_axis", ColumnType.Int, true),
        ColumnDef("page_width", ColumnType.Int, true), ColumnDef("page_height", ColumnType.Int, true),
    ))))

    internal suspend fun setup(library: AssetLibrary, site: String, enabled: Boolean = true,
        policies: List<IncomingRowPolicy> = emptyList()) = library.onWriter {
        library.db.execute("""CREATE TABLE IF NOT EXISTS notebook(id TEXT PRIMARY KEY,name TEXT,sort_order INTEGER,created_at INTEGER,
            deleted_at INTEGER,folder_id TEXT,aspect_long_axis INTEGER,page_width INTEGER,page_height INTEGER)""")
        SqliteStorageAdapter(library.db, registry, incomingPolicies = policies).also { if (enabled) it.enableSync(site) else it.bindLocalAuthor(site) }
    }

    @Test(timeout = 120_000) fun policyCommitRollbackAndRetryAgainstActualUB() = runBlocking<Unit> {
        val binary = System.getenv("RHIZOME_ASSET_TEST_SERVER")
        assumeTrue("Set RHIZOME_ASSET_TEST_SERVER for real UB interoperability", !binary.isNullOrBlank())
        val auth = "Basic " + Base64.getEncoder().encodeToString("assetlab:assetlab".toByteArray())
        AssetInteropTest.Server(binary!!, File(temp.root, "policy-ub.db")).use { server ->
            val transport = HttpUrlTransport(server.url + "/sync/v1", auth)
            AssetLibrary(File(temp.root, "sender.db")).use { source -> AssetLibrary(File(temp.root, "receiver.db")).use { target ->
                val policy = object : IncomingRowPolicy {
                    override val tables = setOf("notebook")
                    override suspend fun prepare(ops: List<Op>) = PreparedIncomingRows { db ->
                        for (op in ops) db.execute("INSERT OR IGNORE INTO deferred VALUES(?,?,?)", listOf(op.siteId, op.opSeq, op.cols.toString()))
                    }
                }
                val send = setup(source, a)
                target.onWriter { target.db.execute("CREATE TABLE deferred(site TEXT,seq INTEGER,cols TEXT,PRIMARY KEY(site,seq))") }
                val receive = setup(target, b, policies = listOf(policy))
                val remoteId = "00000000000000000000000001"; val localId = "00000000000000000000000002"
                source.onWriter {
                    source.db.execute("INSERT INTO notebook(id,name,sort_order,created_at) VALUES(?,'remote',0,1)", listOf(remoteId))
                    send.capture("notebook", remoteId)
                    assertIs<RowExchange.Page>(BoundedSyncSession(send, transport, schema).exchange())
                }
                target.onWriter {
                    target.db.execute("INSERT INTO notebook(id,name,sort_order,created_at) VALUES(?,'local',0,1)", listOf(localId))
                    receive.capture("notebook", localId)
                    val session = BoundedSyncSession(receive, transport, schema)
                    target.db.execute("CREATE TRIGGER fail_policy_cursor BEFORE UPDATE OF cursor ON rhizome_sync_state BEGIN SELECT RAISE(ABORT,'failure'); END")
                    assertFails { session.exchange() } // storage failure propagates; no successful response commit
                    assertEquals(0L, receive.cursor()); assertTrue(receive.hasPending())
                    assertTrue(target.db.query("SELECT seq FROM deferred") { true }.isEmpty())
                    target.db.execute("DROP TRIGGER fail_policy_cursor")
                    assertIs<RowExchange.Page>(session.exchange())
                    assertTrue(receive.cursor() > 0); assertFalse(receive.hasPending())
                    assertEquals(1, target.db.query("SELECT seq FROM deferred") { true }.size)
                    assertTrue(target.db.query("SELECT id FROM notebook WHERE id=?", listOf(remoteId)) { true }.isEmpty())
                }
            } }
        }
    }

    @Test(timeout = 120_000) fun offlineHistoryGetsContiguousAcknowledgementsAfterOptInAgainstActualUB() = runBlocking<Unit> {
        val binary = System.getenv("RHIZOME_ASSET_TEST_SERVER")
        assumeTrue("Set RHIZOME_ASSET_TEST_SERVER for real UB interoperability", !binary.isNullOrBlank())
        val auth = "Basic " + Base64.getEncoder().encodeToString("assetlab:assetlab".toByteArray())
        AssetInteropTest.Server(binary!!, File(temp.root, "offline-ub.db")).use { server ->
            val transport = HttpUrlTransport(server.url + "/sync/v1", auth)
            val file = File(temp.root, "offline.forestnote")
            val id = "00000000000000000000000001"
            AssetLibrary(file).use { lib ->
                val s = setup(lib, a, enabled = false)
                lib.onWriter {
                    lib.db.execute("INSERT INTO notebook(id,name,sort_order,created_at) VALUES(?,'first',0,1)", listOf(id))
                    for (i in 1..6) {
                        lib.db.execute("UPDATE notebook SET name=? WHERE id=?", listOf("offline-$i", id))
                        s.captureAuthored("notebook", id, a)
                    }
                    assertNull(s.siteId()); assertFalse(s.hasPending()); assertTrue(s.pendingOps().isEmpty())
                }
            }
            AssetLibrary(file).use { lib -> AssetLibrary(File(temp.root, "destination.forestnote")).use { destination ->
                val s = setup(lib, a, enabled = false); val target = setup(destination, b)
                val authored = lib.onWriter {
                    s.enableSync(a); s.backfillUntracked(); s.pendingOps().also { assertEquals((1L..6L).toList(), it.map { op -> op.opSeq }) }
                }
                val session = BoundedSyncSession(s, transport, schema, localLimits = RowLimits(maxOps = 2))
                lib.onWriter {
                    var ack = 0L
                    do {
                        val page = assertIs<RowExchange.Page>(session.exchange())
                        assertTrue(page.response.acceptedThrough >= ack); ack = page.response.acceptedThrough
                    } while (page.hasMore)
                    assertEquals(6L, ack); assertFalse(s.hasPending())
                }
                destination.onWriter {
                    val download = BoundedSyncSession(target, transport, schema)
                    do { val page = assertIs<RowExchange.Page>(download.exchange()) } while (page.hasMore)
                    assertEquals("offline-6", destination.db.query("SELECT name FROM notebook WHERE id=?", listOf(id)) { it.getString("name") }.single())
                    assertEquals(authored.last().opTs, destination.db.query("SELECT op_ts FROM rhizome_row_meta WHERE tbl='notebook' AND pk=?", listOf(id)) { it.getLong("op_ts") }.single())
                    target.backfillUntracked(); assertFalse(target.hasPending())
                }
            } }
        }
    }

    @Test(timeout = 120_000) fun boundedDiscoveryPagingAndRollbackAgainstActualUB() = runBlocking {
        val binary = System.getenv("RHIZOME_ASSET_TEST_SERVER")
        assumeTrue("Set RHIZOME_ASSET_TEST_SERVER for real UB interoperability", !binary.isNullOrBlank())
        val auth = "Basic " + Base64.getEncoder().encodeToString("assetlab:assetlab".toByteArray())
        AssetInteropTest.Server(binary!!, File(temp.root, "ub.db")).use { server ->
            val transport = HttpUrlTransport(server.url + "/sync/v1", auth)
            val caps = assertIs<CapabilityOutcome.Available>(transport.capabilities()).capabilities
            assertTrue(schema in caps.acceptedSchemaHashes)
            assertTrue(caps.features.containsAll(setOf("assets-v1", "bounded-rows-v1")))
            AssetLibrary(File(temp.root, "a.forestnote")).use { libA ->
                AssetLibrary(File(temp.root, "b.forestnote")).use { libB ->
                    val storeA = setup(libA, a); val storeB = setup(libB, b)
                    libA.onWriter {
                        for (i in 1..6) {
                            val id = i.toString().padStart(26, '0')
                            libA.db.execute("INSERT INTO notebook(id,name,sort_order,created_at) VALUES(?,?,0,1)", listOf(id, "漢字 🙂 <&> $i"))
                            storeA.capture("notebook", id)
                        }
                    }
                    val limits = RowLimits(targetPageBytes = 700, maxRowBytes = 2000, maxBodyBytes = 2500)
                    val upload = BoundedSyncSession(storeA, transport, schema, localLimits = limits)
                    var uploadPages = 0
                    libA.onWriter {
                        do { val result = assertIs<RowExchange.Page>(upload.exchange()); uploadPages++ } while (result.hasMore)
                    }
                    assertTrue(uploadPages >= 3)
                    val download = BoundedSyncSession(storeB, transport, schema, localLimits = limits)
                    libB.onWriter { do { val result = assertIs<RowExchange.Page>(download.exchange()) } while (result.hasMore) }
                    libB.onWriter {
                        assertEquals(6, libB.db.query("SELECT id FROM notebook") { it.getString("id") }.size)
                        assertFalse(storeB.hasPending()) // applying foreign rows does not re-author them
                        storeB.backfillUntracked()
                        assertFalse(storeB.hasPending()) // preserves the pull-first join boundary
                    }
                    // Seed an oversized remote row using the legacy path, as an older client can.
                    val big = WireOp("notebook", "00000000000000000000000009", a, 7, 900000000000000000L,
                        buildJsonObject {
                            put("name", "X".repeat(5000)); put("sort_order", 0); put("created_at", 1)
                            put("deleted_at", JsonNull); put("folder_id", JsonNull); put("aspect_long_axis", JsonNull)
                        })
                    assertIs<SyncOutcome.Ok>(transport.post(SyncRequest(schemaHash = schema, siteId = a, cursor = 0, ops = listOf(big))))
                    libB.onWriter {
                        val cursor = storeB.cursor()
                        val id = "00000000000000000000000010"
                        libB.db.execute("INSERT INTO notebook(id,name,sort_order,created_at) VALUES(?,'pending',0,1)", listOf(id))
                        storeB.capture("notebook", id)
                        val failed = assertIs<RowExchange.Stopped>(download.exchange())
                        assertEquals(SyncResult.Failed("http 413"), failed.reason)
                        assertEquals(cursor, storeB.cursor()); assertTrue(storeB.hasPending())
                        // Retry with a sufficient response budget. The former 413 must
                        // not have acknowledged or lost the queued local change.
                        val retry = BoundedSyncSession(storeB, transport, schema)
                        val recovered = assertIs<RowExchange.Page>(retry.exchange())
                        assertEquals(1, recovered.response.acceptedThrough)
                        assertFalse(storeB.hasPending()); assertTrue(storeB.cursor() > cursor)
                    }
                }
            }
        }
    }
}
