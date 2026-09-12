package io.rhizome.core

import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

const val ASSET_CHUNK_BYTES = 262144
const val ASSET_PAGE_ENTRIES = 256
private val assetDigestPattern = Regex("[0-9a-f]{64}")
fun isAssetDigest(value: String) = assetDigestPattern.matches(value)
fun assetDigest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }

class AssetException(val status: Int, val code: String) : Exception("Asset: $code ($status)")
fun assetCheck(condition: Boolean, status: Int, code: String) {
    if (!condition) throw AssetException(status, code)
}

data class AssetDescriptor(val id: String, val byteLength: Long, val chunkBytes: Int = ASSET_CHUNK_BYTES) {
    init { assetCheck(isAssetDigest(id) && byteLength >= 0 && chunkBytes == ASSET_CHUNK_BYTES, 400, "invalid_descriptor") }
    val chunkCount: Long get() = byteLength / chunkBytes + if (byteLength % chunkBytes == 0L) 0 else 1
    fun chunkLength(index: Long): Int {
        assetCheck(index >= 0 && index < chunkCount, 400, "invalid_index")
        return minOf(chunkBytes.toLong(), byteLength - index * chunkBytes).toInt()
    }
}
enum class AssetState { STAGING, VERIFYING, READY, INVALID }
data class AssetInfo(val descriptor: AssetDescriptor, val state: AssetState)
data class AssetChunkEntry(val index: Long, val sha256: String?, val byteLength: Int)
data class AssetChunkPage(val entries: List<AssetChunkEntry>, val nextStart: Long?)
data class AssetChunk(val bytes: ByteArray, val sha256: String)

/** Same bounded operations over local storage or authenticated transport. Not row-sync state. */
interface AssetAccess {
    suspend fun describe(id: String): AssetInfo
    suspend fun stage(descriptor: AssetDescriptor): AssetInfo
    suspend fun listChunks(id: String, start: Long, limit: Int = ASSET_PAGE_ENTRIES): AssetChunkPage
    suspend fun readChunk(id: String, index: Long): AssetChunk
    suspend fun writeChunk(id: String, index: Long, bytes: ByteArray, digest: String)
    suspend fun complete(id: String): AssetInfo
    suspend fun resetInvalid(id: String)
}

/**
 * Explicit, single-asset stepping primitive, NOT an automatic sync scheduler.
 * A host services row sync and rotates jobs after each step. At most one chunk
 * is copied per step. A new instance resumes using durable destination manifests.
 * Metadata acknowledgement / capability negotiation remain the host's gates.
 */
class AssetTransfer(
    private val source: AssetAccess, private val destination: AssetAccess, val id: String,
    private val resumeIndex: Long = 0,
    private val manifestLimit: Int = ASSET_PAGE_ENTRIES,
) {
    private var descriptor: AssetDescriptor? = null
    private var next = 0L
    private var verifiedBytes = 0L
    private val gate = Mutex()

    data class Progress(val verifiedBytes: Long, val totalBytes: Long, val ready: Boolean, val nextIndex: Long = 0)

    suspend fun step(): Progress = gate.withLock {
        try {
            withContext(Dispatchers.Default) { stepInternal() }
        } catch (e: Exception) {
            // Lost responses, cancellation, or a restored server invalidate all
            // volatile progress. Retry reconciles from durable manifests.
            descriptor = null
            next = 0
            verifiedBytes = 0
            throw e
        }
    }

    private suspend fun stepInternal(): Progress {
        val d = descriptor ?: source.describe(id).also {
            assetCheck(it.state == AssetState.READY, 409, "asset_not_ready")
        }.descriptor.also {
            assetCheck(resumeIndex in 0..it.chunkCount && manifestLimit in 1..ASSET_PAGE_ENTRIES, 400, "invalid_checkpoint")
            // A checkpoint is only a resume hint. Completion always verifies the
            // destination; missing chunks after restore force reconciliation.
            if (!started) {
                next = resumeIndex
                verifiedBytes = if (next == it.chunkCount) it.byteLength else next * it.chunkBytes
                started = true
            }
            descriptor = it
        }
        // Never trust an earlier server ACK after a server restore/reset.
        val target = destination.stage(d)
        assetCheck(target.descriptor == d, 409, "descriptor_conflict")
        assetCheck(target.state != AssetState.INVALID, 409, "asset_invalid")
        if (target.state == AssetState.READY) return Progress(d.byteLength, d.byteLength, true, d.chunkCount)
        // A restart/reset can remove previously observed chunks. Reconciliation
        // belongs to a fresh job after a failed step; see run-to-completion harness.
        if (next < d.chunkCount) {
            val page = destination.listChunks(id, next, manifestLimit)
            val expectedCount = minOf(manifestLimit.toLong(), d.chunkCount - next).toInt()
            assetCheck(page.entries.isNotEmpty() && page.entries.size <= expectedCount, 400, "invalid_manifest")
            for (entry in page.entries) {
                assetCheck(entry.index == next && entry.byteLength == d.chunkLength(next), 400, "invalid_manifest")
                if (entry.sha256 == null) {
                    val chunk = source.readChunk(id, next)
                    // Transport headers are claims, not proof. Verify before passing
                    // to ANY destination (including a third-party store implementation).
                    assetCheck(chunk.bytes.size == d.chunkLength(next), 400, "invalid_chunk_length")
                    assetCheck(isAssetDigest(chunk.sha256) && assetDigest(chunk.bytes) == chunk.sha256, 422, "chunk_hash_mismatch")
                    destination.writeChunk(id, next, chunk.bytes, chunk.sha256)
                    verifiedBytes += chunk.bytes.size
                    next++
                    return Progress(verifiedBytes, d.byteLength, false, next)
                }
                assetCheck(isAssetDigest(entry.sha256), 400, "invalid_manifest")
                verifiedBytes += entry.byteLength
                next++
            }
            assetCheck(page.nextStart == (if (next == d.chunkCount) null else next), 400, "invalid_manifest")
            // A resumed, already-populated asset must also yield after a bounded
            // manifest page, not monopolize the scheduler scanning its entire file.
            if (next < d.chunkCount) return Progress(verifiedBytes, d.byteLength, false, next)
        }
        val result = destination.complete(id)
        assetCheck(result.descriptor == d && result.state in setOf(AssetState.READY, AssetState.VERIFYING), 409, "asset_not_ready")
        return Progress(d.byteLength, d.byteLength, result.state == AssetState.READY, next)
    }

    private var started = false
}
