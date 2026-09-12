package io.rhizome.http

import io.rhizome.core.SyncConfig
import io.rhizome.core.SyncOutcome
import io.rhizome.core.SyncRequest
import io.rhizome.core.SyncResponse
import io.rhizome.core.SyncTransport
import io.rhizome.core.BoundedRowTransport
import io.rhizome.core.CapabilityOutcome
import io.rhizome.core.SyncCapabilities
import io.rhizome.core.RowLimits
import io.rhizome.core.RowWire
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Production [SyncTransport]: `POST /sync/v1` over [HttpURLConnection] — dependency-light, fits a
 * locked-device ethos (spec). Every failure (DNS, timeout, non-200, malformed body) folds into a
 * [SyncOutcome]; coroutine cancellation still propagates to the caller.
 */
class HttpUrlTransport(
    private val endpoint: String,
    private val authHeader: String,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
    private val log: (String) -> Unit = {},
) : BoundedRowTransport {

    constructor(
        config: SyncConfig,
        json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
        log: (String) -> Unit = {},
    ) : this(config.endpoint, config.authHeader, json, log = log)

    override suspend fun post(request: SyncRequest): SyncOutcome = postInternal(request, null)

    override suspend fun postBounded(request: SyncRequest, limits: RowLimits): SyncOutcome = postInternal(request, limits)

    private suspend fun postInternal(request: SyncRequest, limits: RowLimits?): SyncOutcome = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            if (limits != null && (request.ops.size > limits.maxOps || request.ops.any { RowWire.bytes(it) > limits.maxRowBytes })) {
                return@withContext SyncOutcome.HttpError(413, "row/count limit exceeded")
            }
            val encoded = json.encodeToString(SyncRequest.serializer(), request).toByteArray(Charsets.UTF_8)
            if (limits != null && encoded.size > limits.maxBodyBytes) return@withContext SyncOutcome.HttpError(413, "body limit exceeded")
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", authHeader)
                if (limits != null) {
                    instanceFollowRedirects = false
                    setFixedLengthStreamingMode(encoded.size)
                    setRequestProperty("Accept-Encoding", "identity")
                    setRequestProperty("X-Rhizome-Bounded-Rows", "1")
                    setRequestProperty("X-Rhizome-Max-Response-Bytes", limits.maxBodyBytes.toString())
                    setRequestProperty("X-Rhizome-Max-Row-Bytes", limits.maxRowBytes.toString())
                }
            }
            conn.outputStream.use {
                it.write(encoded)
            }
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_OK) {
                val body = if (limits == null) conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    else conn.inputStream.use { readBounded(it, limits.maxBodyBytes) }.toString(Charsets.UTF_8)
                if (limits != null) {
                    val ops = json.parseToJsonElement(body).jsonObject["ops"]?.jsonArray
                    if (ops != null && (ops.size > 500 || ops.any { it.toString().toByteArray(Charsets.UTF_8).size > limits.maxRowBytes })) {
                        return@withContext SyncOutcome.HttpError(413, "response row/count limit exceeded")
                    }
                }
                val response = json.decodeFromString(SyncResponse.serializer(), body)
                if (limits != null && (response.protocolVersion != 1 || response.cursor < 0 || response.acceptedThrough < 0)) {
                    return@withContext SyncOutcome.HttpError(400, "invalid response envelope")
                }
                SyncOutcome.Ok(response)
            } else {
                // Error text is optional. An empty, broken or oversized error body
                // must not turn a known 401/403 into a retryable transport failure.
                val body = try {
                    (conn.errorStream ?: if (code < 400) conn.inputStream else null)
                        ?.use { readBounded(it, 65536) }?.toString(Charsets.UTF_8)
                } catch (e: ResponseTooLarge) { null
                } catch (e: IOException) { null }
                SyncOutcome.HttpError(code, body)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ResponseTooLarge) {
            SyncOutcome.HttpError(413, "response body limit exceeded")
        } catch (e: Exception) {
            log("transport error: $e")
            SyncOutcome.TransportError(e)
        } finally {
            conn?.disconnect()
        }
    }

    override suspend fun capabilities(): CapabilityOutcome = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            require(endpoint.endsWith("/sync/v1")) { "Capability discovery requires a /sync/v1 endpoint" }
            connection = (URL(endpoint.removeSuffix("/v1") + "/capabilities").openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("Authorization", authHeader)
                setRequestProperty("Accept-Encoding", "identity")
            }
            when (val code = connection.responseCode) {
                404, 405 -> CapabilityOutcome.Legacy
                200 -> {
                    val body = connection.inputStream.use { readBounded(it, 65536) }.toString(Charsets.UTF_8)
                    CapabilityOutcome.Available(json.decodeFromString(SyncCapabilities.serializer(), body))
                }
                else -> CapabilityOutcome.HttpError(code)
            }
        } catch (e: CancellationException) { throw e
        } catch (e: IOException) { CapabilityOutcome.Failed(e)
        } catch (e: Exception) { CapabilityOutcome.Invalid("invalid capability response: ${e::class.simpleName}")
        } finally { connection?.disconnect() }
    }

    private class ResponseTooLarge : Exception()
    private fun readBounded(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
            if (count < 0) return output.toByteArray()
            output.write(buffer, 0, count)
            if (output.size() > limit) throw ResponseTooLarge()
        }
    }
}
