package io.rhizome.sqlite

import io.rhizome.core.*
import kotlinx.coroutines.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.Closeable
import java.io.File
import java.sql.DriverManager
import java.util.concurrent.Executors
import kotlin.test.*

internal class AssetLibrary(file: File) : Closeable {
    private val connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
    val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "asset-library-writer") }.asCoroutineDispatcher()
    val db = JdbcSqliteHandle(connection)
    val store = SqliteAssetStore(db, dispatcher)
    init {
        runBlocking { withContext(dispatcher) {
            db.execute("PRAGMA journal_mode=WAL")
            db.execute("PRAGMA synchronous=FULL")
            store.createSchema()
        } }
    }
    suspend fun sql(statement: String) = withContext(dispatcher) { db.execute(statement) }
    suspend fun <T> onWriter(block: suspend () -> T): T = withContext(dispatcher) { block() }
    override fun close() { runBlocking { withContext(dispatcher) { connection.close() } }; dispatcher.close() }
}

internal suspend fun AssetAccess.importBytes(bytes: ByteArray): AssetDescriptor {
    val d = AssetDescriptor(assetDigest(bytes), bytes.size.toLong())
    stage(d)
    for (i in 0 until d.chunkCount) {
        val chunk = bytes.copyOfRange((i * ASSET_CHUNK_BYTES).toInt(), minOf(bytes.size, ((i + 1) * ASSET_CHUNK_BYTES).toInt()))
        writeChunk(d.id, i, chunk, assetDigest(chunk))
    }
    assertEquals(AssetState.READY, complete(d.id).state)
    return d
}

class SqliteAssetStoreTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun resumedManifestScanYieldsAfterOneNegotiatedPage() = runBlocking {
        AssetLibrary(File(temp.root, "library.forestnote")).use { lib ->
            val d = AssetDescriptor(assetDigest(byteArrayOf(1)), ASSET_CHUNK_BYTES.toLong() * 1000)
            var pages = 0
            val source = object : AssetAccess by lib.store {
                override suspend fun describe(id: String) = AssetInfo(d, AssetState.READY)
            }
            val target = object : AssetAccess by lib.store {
                override suspend fun stage(descriptor: AssetDescriptor) = AssetInfo(d, AssetState.STAGING)
                override suspend fun listChunks(id: String, start: Long, limit: Int): AssetChunkPage {
                    pages++
                    return AssetChunkPage((start until start + limit).map { AssetChunkEntry(it, d.id, ASSET_CHUNK_BYTES) }, start + limit)
                }
                override suspend fun complete(id: String): AssetInfo = error("Must yield before full scan")
            }
            val first = AssetTransfer(source, target, d.id).step()
            assertEquals(1, pages); assertEquals(256, first.nextIndex); assertFalse(first.ready)
            val next = AssetTransfer(source, target, d.id, first.nextIndex, manifestLimit = 8).step()
            assertEquals(2, pages); assertEquals(264, next.nextIndex); assertFalse(next.ready)
        }
    }

    @Test fun resumeVerifyAndNeverRewriteReady() = runBlocking {
        val file = File(temp.root, "library.forestnote")
        val bytes = ByteArray(ASSET_CHUNK_BYTES + 5) { 65 }
        val d = AssetDescriptor(assetDigest(bytes), bytes.size.toLong())
        assertEquals("27b977cd4cd686fa69afab5a0d46a4aa2d3dba56e5f459b63ef5db53062fbe77", d.id)
        val first = bytes.copyOfRange(0, ASSET_CHUNK_BYTES)
        AssetLibrary(file).use { lib ->
            lib.store.stage(d)
            assertEquals("asset_not_ready", assertFailsWith<AssetException> { lib.store.readChunk(d.id, 0) }.code)
            assertEquals("missing_chunks", assertFailsWith<AssetException> { lib.store.complete(d.id) }.code)
            assertEquals(AssetState.STAGING, lib.store.describe(d.id).state)
            lib.store.writeChunk(d.id, 0, first, assetDigest(first))
        }
        AssetLibrary(file).use { lib ->
            lib.store.writeChunk(d.id, 0, first, assetDigest(first)) // lost ACK retry
            assertEquals(listOf(assetDigest(first), null), lib.store.listChunks(d.id, 0).entries.map { it.sha256 })
            val last = bytes.copyOfRange(ASSET_CHUNK_BYTES, bytes.size)
            lib.store.writeChunk(d.id, 1, last, assetDigest(last))
            lib.sql("UPDATE rhizome_asset SET state='verifying'") // killed verifier
        }
        AssetLibrary(file).use { lib ->
            assertEquals(AssetState.READY, lib.store.complete(d.id).state)
            assertContentEquals(bytes, lib.store.readChunk(d.id, 0).bytes + lib.store.readChunk(d.id, 1).bytes)
            assertEquals(409, assertFailsWith<AssetException> { lib.store.resetInvalid(d.id) }.status)
            assertEquals(409, assertFailsWith<AssetException> { lib.store.stage(d.copy(byteLength = d.byteLength + 1)) }.status)
        }
    }

    @Test fun corruptChunkAndRootNeedExplicitRepair() = runBlocking {
        AssetLibrary(File(temp.root, "library.forestnote")).use { lib ->
            val right = "right".toByteArray(); val wrong = "wrong".toByteArray()
            val d = AssetDescriptor(assetDigest(right), 5)
            lib.store.stage(d)
            assertEquals(422, assertFailsWith<AssetException> { lib.store.writeChunk(d.id, 0, wrong, assetDigest(right)) }.status)
            assertNull(lib.store.listChunks(d.id, 0).entries.single().sha256)
            lib.store.writeChunk(d.id, 0, wrong, assetDigest(wrong))
            assertEquals(409, assertFailsWith<AssetException> { lib.store.writeChunk(d.id, 0, right, assetDigest(right)) }.status)
            assertEquals(422, assertFailsWith<AssetException> { lib.store.complete(d.id) }.status)
            assertEquals(AssetState.INVALID, lib.store.describe(d.id).state)
            lib.store.resetInvalid(d.id)
            assertNull(lib.store.listChunks(d.id, 0).entries.single().sha256)
            lib.store.writeChunk(d.id, 0, right, assetDigest(right))
            assertEquals(AssetState.READY, lib.store.complete(d.id).state)
        }
    }

    @Test fun failedTransactionCannotLeaveVerifiedBytes() = runBlocking {
        AssetLibrary(File(temp.root, "library.forestnote")).use { lib ->
            val b = "right".toByteArray(); val d = AssetDescriptor(assetDigest(b), 5)
            lib.store.stage(d)
            lib.sql("CREATE TRIGGER disk_failure BEFORE INSERT ON rhizome_asset_chunk BEGIN SELECT RAISE(ABORT,'injected storage failure'); END")
            assertFails { lib.store.writeChunk(d.id, 0, b, assetDigest(b)) }
            assertNull(lib.store.listChunks(d.id, 0).entries.single().sha256)
            assertEquals(AssetState.STAGING, lib.store.describe(d.id).state)
            lib.sql("DROP TRIGGER disk_failure")
            lib.store.writeChunk(d.id, 0, b, assetDigest(b))
            assertEquals(AssetState.READY, lib.store.complete(d.id).state)
        }
    }

    @Test fun emptyAndInt64LengthsAndBoundedManifests() = runBlocking {
        AssetLibrary(File(temp.root, "library.forestnote")).use { lib ->
            val empty = lib.store.importBytes(byteArrayOf())
            assertEquals(0, empty.chunkCount)
            assertEquals(emptyList(), lib.store.listChunks(empty.id, 0).entries)
            val d = AssetDescriptor(assetDigest("large".toByteArray()), Long.MAX_VALUE)
            assertEquals(35184372088832L, d.chunkCount)
            assertEquals(262143, d.chunkLength(d.chunkCount - 1))
            lib.store.stage(d)
            val page = lib.store.listChunks(d.id, 0)
            assertEquals(256, page.entries.size)
            assertEquals(256, page.nextStart)
            assertEquals(400, assertFailsWith<AssetException> { lib.store.listChunks(d.id, 0, 257) }.status)
            assertEquals(400, assertFailsWith<AssetException> { lib.store.readChunk(d.id, d.chunkCount) }.status)
        }
    }

    @Test fun aLostChunkResponseReconcilesWithoutReuploadingAcknowledgedBytes() = runBlocking {
        AssetLibrary(File(temp.root, "a.forestnote")).use { a ->
            AssetLibrary(File(temp.root, "b.forestnote")).use { b ->
                val bytes = ByteArray(ASSET_CHUNK_BYTES + 5) { 65 }
                val d = a.store.importBytes(bytes)
                val writes = mutableListOf<Long>()
                val flaky = object : AssetAccess by b.store {
                    override suspend fun writeChunk(id: String, index: Long, bytes: ByteArray, digest: String) {
                        writes += index
                        b.store.writeChunk(id, index, bytes, digest)
                        if (writes.size == 1) throw java.io.IOException("injected lost response AFTER commit")
                    }
                }
                val transfer = AssetTransfer(a.store, flaky, d.id)
                assertFailsWith<java.io.IOException> { transfer.step() }
                assertNotNull(b.store.listChunks(d.id, 0).entries.first().sha256)
                assertFalse(transfer.step().ready)
                assertTrue(transfer.step().ready)
                assertEquals(listOf(0L, 1L), writes)
                assertContentEquals(bytes, b.store.readChunk(d.id, 0).bytes + b.store.readChunk(d.id, 1).bytes)
            }
        }
    }
}
