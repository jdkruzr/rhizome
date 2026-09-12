package io.rhizome.http

import io.rhizome.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Bounded raw-byte asset transport. baseUrl is the full /sync/assets/v1 URL.
 * Redirects are deliberately forbidden (never forward credentials to another
 * origin). Hosts must negotiate assets + bounded rows + schema BEFORE scheduling
 * reader sync. This low-level transport does not opt an existing client in.
 */
class HttpAssetTransport(
    private val baseUrl: String,
    private val authHeader: String,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) : AssetAccess {
    private val json = Json { ignoreUnknownKeys = true }
    private data class Response(val code: Int, val body: ByteArray, val digest: String?, val length: Long)

    private suspend fun request(method: String, path: String, body: ByteArray? = null, digest: String? = null): Response = withContext(Dispatchers.IO) {
        val c = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.instanceFollowRedirects = false
            c.connectTimeout = connectTimeoutMs
            c.readTimeout = readTimeoutMs
            c.setRequestProperty("Authorization", authHeader)
            c.setRequestProperty("Accept-Encoding", "identity")
            if (body != null) {
                c.doOutput = true
                c.setFixedLengthStreamingMode(body.size)
                c.setRequestProperty("Content-Type", if (digest == null) "application/json" else "application/octet-stream")
                if (digest != null) c.setRequestProperty("X-Rhizome-Chunk-SHA256", digest)
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            val binary = method == "GET" && path.contains("/chunks/") && code == 200
            val cap = if (binary) ASSET_CHUNK_BYTES else 65536
            val bytes = try {
                assetCheck(c.contentLengthLong <= cap, 413, "response_too_large")
                (if (code >= 400) c.errorStream else c.inputStream)?.use { readBounded(it, cap) } ?: byteArrayOf()
            } catch (e: AssetException) {
                // Optional error text cannot hide auth/retry status behind a 413.
                if (code !in 200..299) throw AssetException(code, e.code)
                throw e
            } catch (e: IOException) {
                if (code !in 200..299) throw AssetException(code, "http_error")
                throw e
            }
            if (code !in 200..299) {
                // Never log arbitrary remote HTML/error text or credentials.
                val errorCode = runCatching { json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content }.getOrNull()
                throw AssetException(code, errorCode?.takeIf { it.matches(Regex("[a-z_]{1,80}")) } ?: "http_error")
            }
            Response(code, bytes, c.getHeaderField("X-Rhizome-Chunk-SHA256"), c.contentLengthLong)
        } finally { c.disconnect() }
    }

    private fun readBounded(input: InputStream, cap: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer, 0, minOf(buffer.size, cap + 1 - out.size()))
            if (n < 0) return out.toByteArray()
            out.write(buffer, 0, n)
            assetCheck(out.size() <= cap, 413, "response_too_large")
        }
    }

    private fun path(id: String): String {
        assetCheck(isAssetDigest(id), 400, "invalid_asset_id")
        return "/$id"
    }
    private fun parse(r: Response): JsonObject = json.parseToJsonElement(r.body.toString(Charsets.UTF_8)).jsonObject
    private suspend fun info(r: Response, id: String): AssetInfo = withContext(Dispatchers.Default) {
        assetCheck(r.code == 200, 400, "unexpected_status")
        val o = parse(r)
        val length = o.getValue("byte_length").jsonPrimitive
        assetCheck(length.isString && length.content.matches(Regex("0|[1-9][0-9]*")), 400, "invalid_descriptor")
        val d = AssetDescriptor(o.getValue("asset_id").jsonPrimitive.content, length.content.toLong(), o.getValue("chunk_bytes").jsonPrimitive.int)
        assetCheck(d.id == id, 400, "invalid_descriptor")
        AssetInfo(d, responseState(r))
    }

    private suspend fun responseState(r: Response): AssetState = withContext(Dispatchers.Default) {
        when (parse(r).getValue("state").jsonPrimitive.content) {
            "staging" -> AssetState.STAGING
            "verifying" -> AssetState.VERIFYING
            "ready" -> AssetState.READY
            "invalid" -> AssetState.INVALID
            else -> throw AssetException(400, "invalid_state")
        }
    }

    override suspend fun describe(id: String) = info(request("GET", path(id)), id)
    override suspend fun stage(descriptor: AssetDescriptor): AssetInfo {
        val body = buildJsonObject {
            put("asset_id", descriptor.id); put("byte_length", descriptor.byteLength.toString()); put("chunk_bytes", descriptor.chunkBytes)
        }.toString().toByteArray(Charsets.UTF_8)
        val response = request("PUT", path(descriptor.id), body)
        assetCheck(response.code == 200 || response.code == 201, 400, "unexpected_status")
        responseState(response) // Stage's wire response may contain only state.
        return describe(descriptor.id).also {
            assetCheck(it.descriptor == descriptor, 409, "descriptor_conflict")
        }
    }
    override suspend fun listChunks(id: String, start: Long, limit: Int): AssetChunkPage = withContext(Dispatchers.Default) {
        assetCheck(start >= 0 && limit in 1..ASSET_PAGE_ENTRIES, 400, "invalid_page")
        val response = request("GET", path(id) + "/chunks?start=$start&limit=$limit")
        assetCheck(response.code == 200, 400, "unexpected_status")
        val o = parse(response)
        val entries = o.getValue("entries").jsonArray
        assetCheck(entries.size <= limit, 400, "invalid_manifest")
        AssetChunkPage(entries.map {
            val e = it.jsonObject
            AssetChunkEntry(e.getValue("index").jsonPrimitive.long, e["sha256"]?.jsonPrimitive?.contentOrNull, e.getValue("byte_length").jsonPrimitive.int)
        }, o["next_start"]?.jsonPrimitive?.longOrNull)
    }
    override suspend fun readChunk(id: String, index: Long): AssetChunk {
        assetCheck(index >= 0, 400, "invalid_index")
        val r = request("GET", path(id) + "/chunks/$index")
        assetCheck(r.code == 200 && r.length == r.body.size.toLong() && r.body.isNotEmpty(), 400, "invalid_chunk_length")
        val digest = r.digest ?: throw AssetException(400, "invalid_chunk_digest")
        assetCheck(isAssetDigest(digest), 400, "invalid_chunk_digest")
        assetCheck(withContext(Dispatchers.Default) { assetDigest(r.body) } == digest, 422, "chunk_hash_mismatch")
        return AssetChunk(r.body, digest)
    }
    override suspend fun writeChunk(id: String, index: Long, bytes: ByteArray, digest: String) {
        assetCheck(index >= 0 && isAssetDigest(digest), 400, "invalid_chunk")
        assetCheck(bytes.size <= ASSET_CHUNK_BYTES, 413, "chunk_too_large")
        assetCheck(request("PUT", path(id) + "/chunks/$index", bytes, digest).code == 204, 400, "unexpected_status")
    }
    override suspend fun complete(id: String): AssetInfo {
        val response = request("POST", path(id) + "/complete", byteArrayOf())
        // The wire only requires {state} here, unlike descriptor GET. Query the
        // descriptor rather than assuming all hosts echo it as our Go host does.
        assetCheck(response.code == 200 || response.code == 202, 400, "unexpected_status")
        assetCheck(responseState(response) == (if (response.code == 202) AssetState.VERIFYING else AssetState.READY), 400, "invalid_state")
        return describe(id).also {
            assetCheck(it.state == AssetState.READY || (response.code == 202 && it.state == AssetState.VERIFYING), 409, "asset_not_ready")
        }
    }
    override suspend fun resetInvalid(id: String) {
        assetCheck(request("POST", path(id) + "/reset-invalid", byteArrayOf()).code == 204, 400, "unexpected_status")
    }
}
