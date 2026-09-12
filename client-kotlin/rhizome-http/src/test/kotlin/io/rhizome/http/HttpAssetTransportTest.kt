package io.rhizome.http

import com.sun.net.httpserver.HttpServer
import io.rhizome.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class HttpAssetTransportTest {
    @Test fun acceptsStateOnlyRepliesAndDoesNotTreatAsyncVerificationAsReady() = runBlocking {
        val d = AssetDescriptor(assetDigest(byteArrayOf()), 0)
        var state = "staging"
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            ex.requestBody.use { it.readBytes() }
            val (status, body) = when (ex.requestMethod) {
                "PUT" -> 201 to """{"state":"staging"}"""
                "POST" -> {
                    if (state == "staging") { state = "verifying"; 202 to """{"state":"verifying"}""" }
                    else { state = "ready"; 200 to """{"state":"ready"}""" }
                }
                else -> 200 to """{"asset_id":"${d.id}","byte_length":"0","chunk_bytes":262144,"state":"$state"}"""
            }
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val transport = HttpAssetTransport("http://127.0.0.1:${server.address.port}/sync/assets/v1", "Basic fixture")
            assertEquals(AssetState.STAGING, transport.stage(d).state)
            assertEquals(AssetState.VERIFYING, transport.complete(d.id).state)
            assertEquals(AssetState.READY, transport.complete(d.id).state)
        } finally { server.stop(0) }
    }

    @Test fun verifiesDownloadBytesInsteadOfTrustingTheHeader() = runBlocking {
        val good = "right".toByteArray()
        val id = assetDigest(good)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/sync/assets/v1/$id/chunks/0") { ex ->
            ex.responseHeaders.add("X-Rhizome-Chunk-SHA256", id)
            ex.sendResponseHeaders(200, 5)
            ex.responseBody.use { it.write("wrong".toByteArray()) }
        }
        server.start()
        try {
            val transport = HttpAssetTransport("http://127.0.0.1:${server.address.port}/sync/assets/v1", "Basic fixture")
            assertEquals("chunk_hash_mismatch", assertFailsWith<AssetException> { transport.readChunk(id, 0) }.code)
        } finally { server.stop(0) }
    }

    @Test fun capsChunkedResponsesAndErrorBodies() = runBlocking {
        val id = assetDigest("large".toByteArray())
        for (status in listOf(200, 401, 403, 503)) {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { ex ->
                ex.sendResponseHeaders(status, 0) // unknown-length body
                runCatching { ex.responseBody.use { it.write(ByteArray(ASSET_CHUNK_BYTES + 1)) } }
                ex.close()
            }
            server.start()
            try {
                val transport = HttpAssetTransport("http://127.0.0.1:${server.address.port}/sync/assets/v1", "Basic fixture")
                val error = assertFailsWith<AssetException> { transport.readChunk(id, 0) }
                assertEquals("response_too_large", error.code)
                assertEquals(if (status == 200) 413 else status, error.status)
            } finally { server.stop(0) }
        }
    }

    @Test fun redirectsNeverForwardCredentialsAndAuthenticationIsNotLegacy() = runBlocking {
        val targetHits = AtomicInteger()
        val target = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        target.createContext("/") { ex -> targetHits.incrementAndGet(); ex.sendResponseHeaders(200, -1); ex.close() }
        target.start()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/redirect") { ex ->
            ex.responseHeaders.add("Location", "http://127.0.0.1:${target.address.port}/stolen")
            ex.sendResponseHeaders(307, -1); ex.close()
        }
        server.createContext("/auth") { ex -> ex.sendResponseHeaders(401, -1); ex.close() }
        server.start()
        try {
            val root = "http://127.0.0.1:${server.address.port}"
            val id = assetDigest(byteArrayOf())
            assertEquals(307, assertFailsWith<AssetException> { HttpAssetTransport("$root/redirect", "Basic secret").describe(id) }.status)
            assertEquals(0, targetHits.get())
            assertEquals(401, assertFailsWith<AssetException> { HttpAssetTransport("$root/auth", "Basic secret").describe(id) }.status)
        } finally { server.stop(0); target.stop(0) }
    }
}
