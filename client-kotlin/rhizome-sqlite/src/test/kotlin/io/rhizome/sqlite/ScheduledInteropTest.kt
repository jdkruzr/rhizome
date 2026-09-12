package io.rhizome.sqlite

import io.rhizome.core.*
import io.rhizome.http.HttpAssetTransport
import io.rhizome.http.HttpUrlTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64
import kotlin.test.*

/** Two actual row/asset clients, automatic discovery/fair scheduling, actual UB, restart all three. */
class ScheduledInteropTest {
    @get:Rule val temp = TemporaryFolder()
    private val fixture = BoundedInteropTest()
    private val auth = "Basic " + Base64.getEncoder().encodeToString("assetlab:assetlab".toByteArray())
    private val aSite = "0000000000000000000000000A"
    private val bSite = "0000000000000000000000000B"
    private val events = mutableListOf<String>()
    private var now = 0L

    private suspend fun publish(lib: AssetLibrary, store: SqliteStorageAdapter, id: String, name: String) = lib.onWriter {
        lib.db.execute("INSERT INTO notebook(id,name,sort_order,created_at) VALUES(?,?,0,1)", listOf(id, name))
        store.capture("notebook", id)
    }

    private suspend fun worker(lib: AssetLibrary, store: SqliteStorageAdapter, url: String, label: String): Pair<SharedLibrarySync, TransferQueue> {
        // Fixture-only reference projection through a field in the real accepted
        // registry. This is NOT the future reader schema or a production encoding.
        lib.sql("""CREATE VIEW IF NOT EXISTS fixture_reader_assets AS SELECT DISTINCT
            substr(name,7,64) AS asset_id, CAST(substr(name,72) AS INTEGER) AS byte_length
            FROM notebook WHERE name LIKE 'asset:%'""")
        val provider = SqliteAssetReferences(lib.db, lib.dispatcher, "fixture_reader_assets")
        val session = BoundedSyncSession(store, HttpUrlTransport("$url/sync/v1", auth), fixture.schema)
        val rows = object : ScheduledRows {
            override suspend fun admission() = lib.onWriter { session.admission() }
            override suspend fun exchange() = lib.onWriter { events += "$label:row"; session.exchange() }
        }
        val http = HttpAssetTransport("$url/sync/assets/v1", auth)
        val remote = object : AssetAccess by http {
            override suspend fun writeChunk(id: String, index: Long, bytes: ByteArray, digest: String) {
                events += "$label:up:$id:$index"; http.writeChunk(id, index, bytes, digest)
            }
            override suspend fun readChunk(id: String, index: Long): AssetChunk {
                events += "$label:down:$id:$index"; return http.readChunk(id, index)
            }
        }
        val q = lib.queue()
        return SharedLibrarySync(rows, provider, lib.store, remote, q,
            TransferPolicy(pollMillis = 60_000, retryBaseMillis = 1), { now }) to q
    }

    @Test(timeout = 120_000) fun discoversFromRowsSchedulesBothDirectionsAndResumesAfterRestart() = runBlocking {
        val binary = System.getenv("RHIZOME_ASSET_TEST_SERVER")
        assumeTrue("Set RHIZOME_ASSET_TEST_SERVER for scheduled real UB interoperability", !binary.isNullOrBlank())
        val serverFile = File(temp.root, "ub.db")
        val aFile = File(temp.root, "a.forestnote"); val bFile = File(temp.root, "b.forestnote")
        val aBytes = ByteArray(ASSET_CHUNK_BYTES * 2 + 7) { 41 }
        val bBytes = ByteArray(ASSET_CHUNK_BYTES * 2 + 11) { 42 }
        lateinit var aBook: AssetDescriptor; lateinit var bBook: AssetDescriptor
        AssetInteropTest.Server(binary!!, serverFile).use { server ->
            AssetLibrary(aFile).use { a -> AssetLibrary(bFile).use { b ->
                val aStore = fixture.setup(a, aSite); val bStore = fixture.setup(b, bSite)
                aBook = a.store.importBytes(aBytes); bBook = b.store.importBytes(bBytes)
                publish(a, aStore, "00000000000000000000000001", "asset:${aBook.id}:${aBook.byteLength}")
                publish(b, bStore, "00000000000000000000000002", "asset:${bBook.id}:${bBook.byteLength}")
                val (aSync, aQueue) = worker(a, aStore, server.url, "A")
                val (bSync, _) = worker(b, bStore, server.url, "B")
                aSync.step(); aSync.step() // metadata then first upload chunk
                bSync.step(); bSync.step()
                publish(a, aStore, "00000000000000000000000003", "Ordinary note while two books are moving")
                aSync.metadataChanged(); aSync.step()
                bSync.metadataChanged(); bSync.step()
                b.onWriter {
                    assertEquals("Ordinary note while two books are moving", b.db.query(
                        "SELECT name FROM notebook WHERE id='00000000000000000000000003'") { it.getString("name") }.single())
                }
                assertFalse(aQueue.job(aBook.id)!!.serverReady)
                assertEquals(ASSET_CHUNK_BYTES.toLong(), aQueue.job(aBook.id)!!.verifiedBytes)
            } }
        }
        AssetInteropTest.Server(binary, serverFile).use { server ->
            AssetLibrary(aFile).use { a -> AssetLibrary(bFile).use { b ->
                val aStore = fixture.setup(a, aSite); val bStore = fixture.setup(b, bSite)
                val (aSync, aQueue) = worker(a, aStore, server.url, "A")
                val (bSync, bQueue) = worker(b, bStore, server.url, "B")
                var done = false
                for (turn in 0 until 150) {
                    aSync.step(); bSync.step(); now += 100
                    val jobs = aQueue.page(null, 256) + bQueue.page(null, 256)
                    if (jobs.size == 4 && jobs.all { it.phase == TransferPhase.READY }) { done = true; break }
                }
                assertTrue(done, "Schedulers failed to finish: ${aQueue.page(null, 256)} ${bQueue.page(null, 256)}")
                for ((d, bytes) in listOf(aBook to aBytes, bBook to bBytes)) {
                    for (lib in listOf(a, b)) {
                        var offset = 0
                        for (index in 0 until d.chunkCount) {
                            val chunk = lib.store.readChunk(d.id, index).bytes
                            assertContentEquals(bytes.copyOfRange(offset, offset + chunk.size), chunk)
                            offset += chunk.size
                        }
                        assertEquals(bytes.size, offset)
                    }
                }
                a.onWriter { aStore.backfillUntracked(); assertFalse(aStore.hasPending()) }
                b.onWriter { bStore.backfillUntracked(); assertFalse(bStore.hasPending()) }
                assertEquals(1, events.count { it == "A:up:${aBook.id}:0" })
                assertEquals(1, events.count { it == "B:up:${bBook.id}:0" })
                for (label in listOf("A", "B")) {
                    val own = events.filter { it.startsWith("$label:") }
                    for ((left, right) in own.withIndex().filter { !it.value.endsWith(":row") }.zipWithNext())
                        assertTrue("$label:row" in own.subList(left.index + 1, right.index))
                }
            } }
        }
    }
}
