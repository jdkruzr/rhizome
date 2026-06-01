package io.rhizome.http

import com.sun.net.httpserver.HttpServer
import io.rhizome.core.SyncOutcome
import io.rhizome.core.SyncRequest
import io.rhizome.core.SyncResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HttpUrlTransportTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun req() = SyncRequest(schemaHash = "H", siteId = "S", cursor = 0, ops = emptyList())

    @Test
    fun malformedEndpointFoldsToTransportError() = runBlocking {
        val out = HttpUrlTransport("ht!tp://nope/sync/v1", "Basic x").post(req())
        assertTrue(out is SyncOutcome.TransportError, "got $out")
    }

    @Test
    fun postsAndParsesOkAndMapsHttpError() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/sync/v1") { ex ->
            ex.requestBody.readBytes() // drain
            val ok = json.encodeToString(
                SyncResponse.serializer(),
                SyncResponse(acceptedThrough = 5, cursor = 9, hasMore = false),
            ).toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, ok.size.toLong())
            ex.responseBody.use { it.write(ok) }
        }
        server.createContext("/mismatch") { ex ->
            val body = "nope".toByteArray()
            ex.sendResponseHeaders(409, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val ok = HttpUrlTransport("$base/sync/v1", "Basic x").post(req())
            assertTrue(ok is SyncOutcome.Ok, "got $ok")
            assertEquals(9, ok.response.cursor)
            assertEquals(5, ok.response.acceptedThrough)

            val err = HttpUrlTransport("$base/mismatch", "Basic x").post(req())
            assertTrue(err is SyncOutcome.HttpError, "got $err")
            assertEquals(409, err.code)
            assertEquals("nope", err.body)
        } finally {
            server.stop(0)
        }
    }
}
