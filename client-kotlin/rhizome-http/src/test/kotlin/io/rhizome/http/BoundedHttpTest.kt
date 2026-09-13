package io.rhizome.http

import com.sun.net.httpserver.HttpServer
import io.rhizome.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.net.InetSocketAddress
import kotlin.test.*

class BoundedHttpTest {
    @Test fun explicitConnectionRouteIsUsedForDiscoveryAndBoundedPost() = runBlocking {
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        val routes=java.util.Collections.synchronizedList(mutableListOf<String>())
        server.createContext("/sync/capabilities") { ex -> ex.sendResponseHeaders(401,-1);ex.close() }
        server.createContext("/sync/v1") { ex -> ex.requestBody.close();ex.sendResponseHeaders(403,-1);ex.close() }
        server.start()
        try {
            val transport=HttpUrlTransport("https://unresolved.invalid/sync/v1","Bearer fixture",openConnection={url ->
                routes.add(url.toString())
                java.net.URL("http://127.0.0.1:${server.address.port}${url.path}").openConnection() as java.net.HttpURLConnection
            })
            assertEquals(CapabilityOutcome.HttpError(401),transport.capabilities())
            assertEquals(403,assertIs<SyncOutcome.HttpError>(transport.postBounded(
                SyncRequest(schemaHash="schema",siteId="A",cursor=0,ops=emptyList()),RowLimits())).code)
            assertEquals(listOf("https://unresolved.invalid/sync/capabilities","https://unresolved.invalid/sync/v1"),routes)
        } finally {server.stop(0)}
    }
    @Test fun emptyAndOversizedErrorBodiesPreserveHttpStatus() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var status = 401
        var oversized = false
        server.createContext("/sync/v1") { ex ->
            ex.requestBody.use { it.readBytes() }
            if (oversized) {
                ex.sendResponseHeaders(status, 0)
                ex.responseBody.use { it.write(ByteArray(65537) { 88 }) }
            } else {
                ex.sendResponseHeaders(status, -1)
                ex.close()
            }
        }
        server.start()
        try {
            val transport = HttpUrlTransport("http://127.0.0.1:${server.address.port}/sync/v1", "Basic fixture")
            val request = SyncRequest(schemaHash = "schema", siteId = "A", cursor = 0, ops = emptyList())
            for (code in listOf(401, 403, 409, 413, 503)) {
                status = code
                for (large in listOf(false, true)) {
                    oversized = large
                    assertEquals(code, assertIs<SyncOutcome.HttpError>(transport.postBounded(request, RowLimits())).code)
                }
            }
        } finally { server.stop(0) }
    }

    @Test fun discoveryDistinguishesLegacyAndAuthWithoutCachingAcrossCalls() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var status = 404
        server.createContext("/sync/capabilities") { ex -> ex.sendResponseHeaders(status, -1); ex.close() }
        server.start()
        try {
            val transport = HttpUrlTransport("http://127.0.0.1:${server.address.port}/sync/v1", "Basic fixture")
            assertEquals(CapabilityOutcome.Legacy, transport.capabilities())
            status = 401; assertEquals(CapabilityOutcome.HttpError(401), transport.capabilities())
            status = 403; assertEquals(CapabilityOutcome.HttpError(403), transport.capabilities())
            status = 405; assertEquals(CapabilityOutcome.Legacy, transport.capabilities())
        } finally { server.stop(0) }
    }

    @Test fun oversizedResponseWithAnAckIsRejectedBeforeItCanReachStorage() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/sync/v1") { ex ->
            ex.requestBody.use { it.readBytes() }
            assertEquals("1", ex.requestHeaders.getFirst("X-Rhizome-Bounded-Rows"))
            assertEquals("512", ex.requestHeaders.getFirst("X-Rhizome-Max-Response-Bytes"))
            val body = """{"protocol_version":1,"accepted_through":99,"cursor":100,"rejected":[],"has_more":false,"ops":[],"padding":"${"X".repeat(600)}"}""".toByteArray()
            ex.sendResponseHeaders(200, 0) // chunked: cannot trust a Content-Length guard
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val transport = HttpUrlTransport("http://127.0.0.1:${server.address.port}/sync/v1", "Basic fixture")
            val result = transport.postBounded(SyncRequest(schemaHash = "schema", siteId = "A", cursor = 42, ops = emptyList()),
                RowLimits(targetPageBytes = 512, maxRowBytes = 512, maxBodyBytes = 512))
            assertEquals(413, assertIs<SyncOutcome.HttpError>(result).code)
        } finally { server.stop(0) }
    }
}
