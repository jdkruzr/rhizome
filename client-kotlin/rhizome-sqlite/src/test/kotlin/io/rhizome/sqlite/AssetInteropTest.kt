package io.rhizome.sqlite

import io.rhizome.core.*
import io.rhizome.http.HttpAssetTransport
import io.rhizome.http.HttpUrlTransport
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.Closeable
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import kotlin.test.*

/** Real Kotlin/JDBC -> UB Go/SQLite/HTTP -> second Kotlin/JDBC library. */
class AssetInteropTest {
    @get:Rule val temp = TemporaryFolder()
    private val auth = "Basic " + Base64.getEncoder().encodeToString("assetlab:assetlab".toByteArray())
    private val schema = "74e6b5d790c919290d0e1fca3462800a5dc4abb288042dda2b48d4eb0482bbf2" // accepted legacy v4

    internal class Server(binary: String, db: File) : Closeable {
        private val process = ProcessBuilder(binary, "--db", db.absolutePath).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        val url: String = run {
            val executor = Executors.newSingleThreadExecutor()
            try {
                val line = executor.submit<String?> { process.inputStream.bufferedReader().readLine() }.get(10, TimeUnit.SECONDS)
                check(line != null && line.startsWith("http://127.0.0.1:")) { "assetlab failed to start" }
                line
            } catch (e: Exception) {
                process.destroyForcibly()
                throw e
            } finally { executor.shutdownNow() }
        }
        override fun close() {
            process.outputStream.close()
            if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) }
        }
    }

    @Test(timeout = 120_000) fun roundTripResumesBothDirectionsAndServicesAnOrdinaryNote() = runBlocking {
        val binary = System.getenv("RHIZOME_ASSET_TEST_SERVER")
        assumeTrue("Build UB cmd/assetlab and set RHIZOME_ASSET_TEST_SERVER to run interoperability", !binary.isNullOrBlank())
        val serverFile = File(temp.root, "ub.db")
        val aFile = File(temp.root, "a.forestnote")
        val bFile = File(temp.root, "b.forestnote")
        val bytes = ByteArray(ASSET_CHUNK_BYTES + 5) { 65 }
        val descriptor = AssetLibrary(aFile).use { it.store.importBytes(bytes) }
        // Upload one durable chunk, then lose both client job and server process.
        Server(binary!!, serverFile).use { server ->
            AssetLibrary(aFile).use { a ->
                val remote = HttpAssetTransport(server.url + "/sync/assets/v1", auth)
                val progress = AssetTransfer(a.store, remote, descriptor.id).step()
                assertEquals(ASSET_CHUNK_BYTES.toLong(), progress.verifiedBytes)
                assertFalse(progress.ready)
                val rows = HttpUrlTransport(server.url + "/sync/v1", auth)
                val op = WireOp("notebook", "00000000000000000000000NB1", "0000000000000000000000000A", 1, 1000, buildJsonObject {
                    put("name", "Notes need not wait for the book")
                    put("sort_order", 0); put("created_at", 1000); put("deleted_at", JsonNull)
                    put("folder_id", JsonNull); put("aspect_long_axis", JsonNull)
                })
                val pushed = assertIs<SyncOutcome.Ok>(rows.post(SyncRequest(schemaHash = schema, siteId = op.siteId, cursor = 0, ops = listOf(op))))
                assertEquals(1, pushed.response.acceptedThrough)
                assertTrue(pushed.response.rejected.isEmpty())
                assertEquals(AssetState.STAGING, remote.describe(descriptor.id).state)
            }
        }
        Server(binary, serverFile).use { server ->
            val remote = HttpAssetTransport(server.url + "/sync/assets/v1", auth)
            AssetLibrary(aFile).use { a ->
                val transfer = AssetTransfer(a.store, remote, descriptor.id)
                assertFalse(transfer.step().ready) // skips chunk zero, writes only chunk one
                assertTrue(transfer.step().ready)
            }
            AssetLibrary(bFile).use { b ->
                val progress = AssetTransfer(remote, b.store, descriptor.id).step()
                assertEquals(ASSET_CHUNK_BYTES.toLong(), progress.verifiedBytes)
                assertFalse(progress.ready)
                assertEquals(AssetState.STAGING, b.store.describe(descriptor.id).state)
            }
        }
        Server(binary, serverFile).use { server ->
            val remote = HttpAssetTransport(server.url + "/sync/assets/v1", auth)
            AssetLibrary(bFile).use { b ->
                val transfer = AssetTransfer(remote, b.store, descriptor.id)
                assertFalse(transfer.step().ready)
                assertTrue(transfer.step().ready)
                val restored = b.store.readChunk(descriptor.id, 0).bytes + b.store.readChunk(descriptor.id, 1).bytes
                assertContentEquals(bytes, restored)
                assertEquals(descriptor.id, assetDigest(restored))
            }
            val rows = HttpUrlTransport(server.url + "/sync/v1", auth)
            val pulled = assertIs<SyncOutcome.Ok>(rows.post(SyncRequest(schemaHash = schema, siteId = "0000000000000000000000000B", cursor = 0, ops = emptyList())))
            assertEquals("Notes need not wait for the book", pulled.response.ops.single().cols["name"]!!.jsonPrimitive.content)
            val badAuth = HttpAssetTransport(server.url + "/sync/assets/v1", "Basic wrong")
            assertEquals(401, assertFailsWith<AssetException> { badAuth.describe(descriptor.id) }.status)
            // Optional real/generated EPUB or MOBI supplied by the FN runner.
            System.getenv("RHIZOME_ASSET_TEST_BOOKS")?.split(File.pathSeparator)?.forEach { path ->
                val book = File(path).readBytes()
                AssetLibrary(aFile).use { a -> AssetLibrary(bFile).use { b ->
                    val d = a.store.importBytes(book)
                    val upload = AssetTransfer(a.store, remote, d.id)
                    while (!upload.step().ready) { /* host can service rows between steps */ }
                    val download = AssetTransfer(remote, b.store, d.id)
                    while (!download.step().ready) { }
                    var offset = 0
                    for (index in 0 until d.chunkCount) {
                        val chunk = b.store.readChunk(d.id, index).bytes
                        assertContentEquals(book.copyOfRange(offset, offset + chunk.size), chunk)
                        offset += chunk.size
                    }
                    assertEquals(book.size, offset)
                } }
            }
        }
        Unit
    }
}
