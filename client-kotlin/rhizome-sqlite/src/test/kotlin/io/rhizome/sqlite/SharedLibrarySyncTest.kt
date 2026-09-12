package io.rhizome.sqlite

import io.rhizome.core.*
import kotlinx.coroutines.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import kotlin.test.*

internal class TestRows(private val events: MutableList<String> = mutableListOf()) : ScheduledRows {
    var allowed: RowAdmission = RowAdmission.Allowed(RowLimits(), ASSET_PAGE_ENTRIES)
    var result: RowExchange = RowExchange.Page(SyncResponse(acceptedThrough = 0, rejected = emptyList(), ops = emptyList(), cursor = 0, hasMore = false), false)
    override suspend fun admission() = allowed
    override suspend fun exchange(): RowExchange { events += "row"; return result }
}

internal suspend fun AssetLibrary.queue(scope: String = "fixture-server/account") =
    SqliteTransferQueue(db, dispatcher, scope).also { it.createSchema() }

internal suspend fun AssetLibrary.references(descriptors: List<AssetDescriptor>): AssetReferenceProvider {
    onWriter {
        db.execute("CREATE TABLE IF NOT EXISTS fixture_required_asset(asset_id TEXT PRIMARY KEY,byte_length INTEGER)")
        for (d in descriptors) db.execute("INSERT OR IGNORE INTO fixture_required_asset VALUES(?,?)", listOf(d.id, d.byteLength))
    }
    return SqliteAssetReferences(db, dispatcher, "fixture_required_asset")
}

class SharedLibrarySyncTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun rotatesAssetsAndDirectionsWithRowsBetweenChunks() = runBlocking {
        AssetLibrary(File(temp.root, "local.db")).use { a -> AssetLibrary(File(temp.root, "remote.db")).use { b ->
            val events = mutableListOf<String>()
            val uploads = (1..2).map { a.store.importBytes(ByteArray(ASSET_CHUNK_BYTES * 2 + 1) { _ -> it.toByte() }) }
            val downloads = (3..4).map { b.store.importBytes(ByteArray(ASSET_CHUNK_BYTES * 2 + 1) { _ -> it.toByte() }) }
            val local = object : AssetAccess by a.store {
                override suspend fun writeChunk(id: String, index: Long, bytes: ByteArray, digest: String) {
                    events += "download:$id:$index"; a.store.writeChunk(id, index, bytes, digest)
                }
            }
            val remote = object : AssetAccess by b.store {
                override suspend fun writeChunk(id: String, index: Long, bytes: ByteArray, digest: String) {
                    events += "upload:$id:$index"; b.store.writeChunk(id, index, bytes, digest)
                }
            }
            val queue = a.queue()
            var now = 0L
            val sync = SharedLibrarySync(TestRows(events), a.references(uploads + downloads), local, remote, queue,
                TransferPolicy(pollMillis = 60_000, retryBaseMillis = 1), { now })
            repeat(40) { sync.step(); now++ }
            val chunks = events.filter { it != "row" }
            assertEquals(12, chunks.size)
            assertEquals(listOf("upload", "download", "upload", "download"), chunks.take(4).map { it.substringBefore(':') })
            assertEquals(4, chunks.take(4).map { it.split(':')[1] }.distinct().size)
            for ((left, right) in events.withIndex().filter { it.value != "row" }.zipWithNext()) {
                assertTrue("row" in events.subList(left.index + 1, right.index), "No metadata turn between chunks")
            }
            for (job in queue.page(null, 256)) {
                assertEquals(TransferPhase.READY, job.phase)
                assertTrue(job.localReady && job.serverReady)
                assertEquals(job.descriptor.byteLength, job.verifiedBytes)
                for (index in 0 until job.descriptor.chunkCount)
                    assertContentEquals(a.store.readChunk(job.descriptor.id, index).bytes, b.store.readChunk(job.descriptor.id, index).bytes)
            }
        } }
    }

    @Test fun lostAckAndRetryDeadlineSurviveClientReopenWithoutDuplicateChunkUpload() = runBlocking {
        val file = File(temp.root, "local.db")
        var now = 100L
        val writes = mutableListOf<Long>()
        AssetLibrary(File(temp.root, "remote.db")).use { b ->
            val remote = object : AssetAccess by b.store {
                override suspend fun writeChunk(id: String, index: Long, bytes: ByteArray, digest: String) {
                    writes += index; b.store.writeChunk(id, index, bytes, digest)
                    if (writes.size == 1) throw IOException("lost ACK after commit")
                }
            }
            val d = AssetLibrary(file).use { a ->
                val d = a.store.importBytes(ByteArray(ASSET_CHUNK_BYTES + 1) { 5 })
                val q = a.queue()
                val sync = SharedLibrarySync(TestRows(), a.references(listOf(d)), a.store, remote, q, clock = { now })
                sync.step()
                val failed = assertIs<LibraryStep.Asset>(sync.step()).job
                assertEquals(TransferPhase.WAITING, failed.phase)
                assertEquals(1100, failed.retryAt)
                assertEquals(0, failed.verifiedBytes)
                d
            }
            AssetLibrary(file).use { a ->
                val q = a.queue()
                val sync = SharedLibrarySync(TestRows(), a.references(listOf(d)), a.store, remote, q, clock = { now })
                repeat(5) { sync.step() }
                assertEquals(listOf(0L), writes)
                assertEquals(1100, q.job(d.id)!!.retryAt)
                now = 1100
                repeat(3) { sync.step() }
                assertEquals(listOf(0L, 1L), writes)
                now = 2100
                repeat(3) { sync.step() }
                assertEquals(TransferPhase.READY, q.job(d.id)!!.phase)
            }
        }
    }

    @Test fun authPauseIsDurableAndAccountScopesDoNotShareObservations() = runBlocking {
        AssetLibrary(File(temp.root, "local.db")).use { a -> AssetLibrary(File(temp.root, "remote.db")).use { b ->
            val d = a.store.importBytes(byteArrayOf(9))
            val q = a.queue()
            var denied = true
            var calls = 0
            val remote = object : AssetAccess by b.store {
                override suspend fun describe(id: String): AssetInfo {
                    calls++; if (denied) throw AssetException(401, "unauthorized")
                    return b.store.describe(id)
                }
            }
            val refs = a.references(listOf(d))
            var sync = SharedLibrarySync(TestRows(), refs, a.store, remote, q)
            sync.step(); sync.step()
            assertEquals("auth_required", q.schedule().pause)
            sync = SharedLibrarySync(TestRows(), refs, a.store, remote, q)
            repeat(3) { assertIs<LibraryStep.Paused>(sync.step()) }
            assertEquals(1, calls)
            assertNull(a.queue("different-account").job(d.id))
            denied = false
            sync.resume()
            repeat(3) { sync.step() }
            assertTrue(calls > 1)
            assertNull(q.schedule().pause)
        } }
    }

    @Test fun corruptChunkIsActionableAndDoesNotLoopOrClaimLocalReadiness() = runBlocking {
        AssetLibrary(File(temp.root, "local.db")).use { a -> AssetLibrary(File(temp.root, "remote.db")).use { b ->
            val d = b.store.importBytes(byteArrayOf(9))
            var reads = 0
            val remote = object : AssetAccess by b.store {
                override suspend fun readChunk(id: String, index: Long): AssetChunk {
                    reads++; return AssetChunk(byteArrayOf(8), assetDigest(byteArrayOf(9)))
                }
            }
            val q = a.queue()
            val sync = SharedLibrarySync(TestRows(), a.references(listOf(d)), a.store, remote, q)
            repeat(8) { sync.step() }
            assertEquals(1, reads)
            val job = q.job(d.id)!!
            assertEquals(TransferPhase.FAILED, job.phase)
            assertEquals("chunk_hash_mismatch", job.error)
            assertFalse(job.localReady)
            assertEquals(0, job.verifiedBytes)
            assertNull(a.store.listChunks(d.id, 0).entries.single().sha256)
            sync.retryAsset(d.id)
            repeat(3) { sync.step() }
            assertEquals(2, reads)
        } }
    }

    @Test fun discoveryIsBoundedAndCursorInsertionRollsBackTogether() = runBlocking {
        AssetLibrary(File(temp.root, "local.db")).use { a ->
            a.sql("CREATE TABLE fixture_required_asset(asset_id TEXT PRIMARY KEY,byte_length INTEGER)")
            a.sql("""WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM n WHERE i<100000)
                INSERT INTO fixture_required_asset SELECT printf('%064x',i),1 FROM n""")
            var fetched = 0
            val counted = object : SqliteHandle by a.db {
                override fun <T> query(sql: String, args: List<Any?>, map: (SqliteRow) -> T): List<T> =
                    a.db.query(sql, args) { fetched++; map(it) }
            }
            val provider = SqliteAssetReferences(counted, a.dispatcher, "fixture_required_asset")
            val page = provider.pageRequiredAssets(null, 64)
            assertEquals(64, page.assets.size); assertEquals(65, fetched); assertTrue(page.hasMore)
            val q = a.queue()
            a.sql("CREATE TRIGGER fail_scan BEFORE UPDATE ON rhizome_transfer_schedule BEGIN SELECT RAISE(ABORT,'disk full'); END")
            assertFails { q.discover(page.assets.map { TransferJob(it, TransferDirection.DOWNLOAD) },
                LibrarySchedule(discoveryAfter = page.assets.last().id)) }
            assertTrue(q.page(null, 256).isEmpty()); assertNull(q.schedule().discoveryAfter)
            a.sql("DROP TRIGGER fail_scan")
            q.discover(page.assets.map { TransferJob(it, TransferDirection.DOWNLOAD) }, LibrarySchedule(discoveryAfter = page.assets.last().id))
            assertEquals(64, q.page(null, 256).size)
            assertEquals(page.assets.last().id, q.schedule().discoveryAfter)
        }
    }

    @Test fun restartRechecksReadyServerAndRepairsMetadataOnlyRestore() = runBlocking {
        AssetLibrary(File(temp.root, "local.db")).use { a ->
            AssetLibrary(File(temp.root, "old-server.db")).use { old ->
                AssetLibrary(File(temp.root, "restored-server.db")).use { restored ->
                    val d = a.store.importBytes(byteArrayOf(7))
                    old.store.importBytes(byteArrayOf(7))
                    val q = a.queue(); val refs = a.references(listOf(d))
                    var now = 0L
                    val first = SharedLibrarySync(TestRows(), refs, a.store, old.store, q, clock = { now })
                    first.step(); first.step()
                    assertTrue(q.job(d.id)!!.serverReady)
                    val second = SharedLibrarySync(TestRows(), refs, a.store, restored.store, q, clock = { now })
                    second.step() // includes session invalidation, then a row/asset turn
                    assertFalse(q.job(d.id)!!.serverReady)
                    repeat(3) { second.step() }
                    now = 1000
                    repeat(5) { second.step() }
                    assertTrue(q.job(d.id)!!.serverReady)
                    assertContentEquals(byteArrayOf(7), restored.store.readChunk(d.id, 0).bytes)
                }
            }
        }
    }

    @Test fun cancellationAndConcurrentTicksNeverOverlapChunks() = runBlocking {
        AssetLibrary(File(temp.root, "local.db")).use { a -> AssetLibrary(File(temp.root, "remote.db")).use { b ->
            val d = a.store.importBytes(ByteArray(ASSET_CHUNK_BYTES + 1) { 10 })
            val entered = CompletableDeferred<Unit>()
            var active = 0; var maximum = 0; var cancelFirst = true
            val remote = object : AssetAccess by b.store {
                override suspend fun writeChunk(id: String, index: Long, bytes: ByteArray, digest: String) {
                    active++; maximum = maxOf(maximum, active)
                    try {
                        if (cancelFirst) { cancelFirst = false; entered.complete(Unit); awaitCancellation() }
                        yield()
                        b.store.writeChunk(id, index, bytes, digest)
                    } finally { active-- }
                }
            }
            val q = a.queue()
            val sync = SharedLibrarySync(TestRows(), a.references(listOf(d)), a.store, remote, q)
            sync.step()
            val cancelled = async { sync.step() }
            entered.await(); cancelled.cancelAndJoin()
            assertFalse(q.job(d.id)!!.serverReady)
            assertEquals(0, q.job(d.id)!!.verifiedBytes)
            coroutineScope { (1..12).map { async { sync.step() } }.awaitAll() }
            assertEquals(1, maximum)
            assertEquals(0, active)
            assertNotNull(b.store.listChunks(d.id, 0).entries.first().sha256)
            Unit
        } }
    }

    @Test fun failedProgressCheckpointIsNotReportedAsSuccessAndRetryReconcilesCommittedChunk() = runBlocking {
        AssetLibrary(File(temp.root, "local.db")).use { a -> AssetLibrary(File(temp.root, "remote.db")).use { b ->
            val d = a.store.importBytes(byteArrayOf(13)); val q = a.queue()
            var writes = 0
            val remote = object : AssetAccess by b.store {
                override suspend fun writeChunk(id: String, index: Long, bytes: ByteArray, digest: String) {
                    writes++; b.store.writeChunk(id, index, bytes, digest)
                }
            }
            val sync = SharedLibrarySync(TestRows(), a.references(listOf(d)), a.store, remote, q)
            a.sql("""CREATE TRIGGER fail_progress BEFORE UPDATE ON rhizome_transfer_job WHEN NEW.verified_bytes>0
                BEGIN SELECT RAISE(ABORT,'disk full'); END""")
            sync.step()
            assertEquals("scheduler_storage", assertIs<LibraryStep.Paused>(sync.step()).reason)
            assertEquals(0, q.job(d.id)!!.verifiedBytes)
            assertFalse(q.job(d.id)!!.serverReady)
            assertEquals(1, writes)
            a.sql("DROP TRIGGER fail_progress")
            repeat(3) { sync.step() }
            assertEquals(1, writes)
            assertTrue(q.job(d.id)!!.serverReady)
        } }
    }

    @Test fun missingReplicaDoesNotStarveHealthyAssetAndCapabilitiesGateAssetTraffic() = runBlocking {
        AssetLibrary(File(temp.root, "local.db")).use { a -> AssetLibrary(File(temp.root, "remote.db")).use { b ->
            val d = a.store.importBytes(byteArrayOf(11))
            val absent = AssetDescriptor(assetDigest(byteArrayOf(12)), 1)
            val refs = a.references(listOf(d, absent)); val q = a.queue(); val rows = TestRows()
            var now = 0L; var calls = 0
            val remote = object : AssetAccess by b.store {
                override suspend fun describe(id: String): AssetInfo { calls++; return b.store.describe(id) }
            }
            rows.allowed = RowAdmission.Stopped(SyncResult.Failed("legacy"))
            val sync = SharedLibrarySync(rows, refs, a.store, remote, q, TransferPolicy(retryBaseMillis = 1), { now })
            sync.step(); assertIs<LibraryStep.Paused>(sync.step()); assertEquals(0, calls)
            rows.allowed = RowAdmission.Allowed(RowLimits(), ASSET_PAGE_ENTRIES)
            sync.resume()
            repeat(20) { sync.step(); now++ }
            assertEquals(TransferPhase.READY, q.job(d.id)!!.phase)
            assertEquals(TransferPhase.WAITING, q.job(absent.id)!!.phase)
            assertFalse(q.job(absent.id)!!.localReady)
            assertFalse(q.job(absent.id)!!.serverReady)
            assertTrue(q.job(absent.id)!!.retryAt >= now)
            // Disappearing references never reclaim original bytes or transfer history.
            a.sql("DELETE FROM fixture_required_asset")
            sync.referencesChanged(); repeat(3) { sync.step() }
            assertNotNull(q.job(d.id)); assertEquals(AssetState.READY, a.store.describe(d.id).state)
        } }
    }

    @Test fun verificationPollingBacksOffAndAllChunksDoesNotMeanReady() = runBlocking {
        AssetLibrary(File(temp.root, "local.db")).use { a -> AssetLibrary(File(temp.root, "remote.db")).use { b ->
            val d = a.store.importBytes(byteArrayOf()); val q = a.queue()
            var now = 0L; var completed = 0
            val remote = object : AssetAccess by b.store {
                override suspend fun complete(id: String): AssetInfo {
                    completed++
                    return if (completed < 3) AssetInfo(d, AssetState.VERIFYING) else b.store.complete(id)
                }
            }
            val sync = SharedLibrarySync(TestRows(), a.references(listOf(d)), a.store, remote, q, clock = { now })
            sync.step(); sync.step()
            assertEquals(TransferPhase.VERIFYING, q.job(d.id)!!.phase)
            assertFalse(q.job(d.id)!!.serverReady)
            repeat(5) { sync.step() }; assertEquals(1, completed)
            now = 1000; repeat(3) { sync.step() }
            assertEquals(2, completed); assertEquals(3000, q.job(d.id)!!.retryAt)
            assertFalse(q.job(d.id)!!.serverReady)
            now = 3000; repeat(3) { sync.step() }
            assertEquals(TransferPhase.READY, q.job(d.id)!!.phase)
            assertTrue(q.job(d.id)!!.serverReady)
        } }
    }

    @Test fun finishedLocalChunksRecoverWhenProcessDiesBeforeVerificationAndServerLosesBytes() = runBlocking {
        AssetLibrary(File(temp.root, "local.db")).use { a -> AssetLibrary(File(temp.root, "remote.db")).use { b ->
            val bytes = byteArrayOf(14); val d = AssetDescriptor(assetDigest(bytes), 1)
            a.store.stage(d); a.store.writeChunk(d.id, 0, bytes, d.id) // no complete or progress checkpoint
            val q = a.queue(); var now = 0L
            val sync = SharedLibrarySync(TestRows(), a.references(listOf(d)), a.store, b.store, q, clock = { now })
            sync.step(); sync.step()
            assertTrue(q.job(d.id)!!.localReady); assertFalse(q.job(d.id)!!.serverReady)
            now = 1000; repeat(3) { sync.step() }
            now = 2000; repeat(3) { sync.step() }
            assertEquals(TransferPhase.READY, q.job(d.id)!!.phase)
            assertContentEquals(bytes, b.store.readChunk(d.id, 0).bytes)
        } }
    }
}
