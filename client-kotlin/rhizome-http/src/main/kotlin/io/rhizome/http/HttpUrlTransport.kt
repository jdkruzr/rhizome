package io.rhizome.http

import io.rhizome.core.SyncConfig
import io.rhizome.core.SyncOutcome
import io.rhizome.core.SyncRequest
import io.rhizome.core.SyncResponse
import io.rhizome.core.SyncTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/**
 * Production [SyncTransport]: `POST /sync/v1` over [HttpURLConnection] — dependency-light, fits a
 * locked-device ethos (spec). Every failure (DNS, timeout, non-200, malformed body) folds into a
 * [SyncOutcome]; this class never throws, so the engine's status mapping stays in one place.
 */
class HttpUrlTransport(
    private val endpoint: String,
    private val authHeader: String,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
    private val log: (String) -> Unit = {},
) : SyncTransport {

    constructor(
        config: SyncConfig,
        json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
        log: (String) -> Unit = {},
    ) : this(config.endpoint, config.authHeader, json, log = log)

    override suspend fun post(request: SyncRequest): SyncOutcome = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", authHeader)
            }
            conn.outputStream.use {
                it.write(json.encodeToString(SyncRequest.serializer(), request).toByteArray(Charsets.UTF_8))
            }
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_OK) {
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                SyncOutcome.Ok(json.decodeFromString(SyncResponse.serializer(), body))
            } else {
                val body = (conn.errorStream ?: conn.inputStream)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                SyncOutcome.HttpError(code, body)
            }
        } catch (e: Throwable) {
            log("transport error: $e")
            SyncOutcome.TransportError(e)
        } finally {
            conn?.disconnect()
        }
    }
}
