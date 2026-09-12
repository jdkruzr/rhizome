package io.rhizome.sqlite

import io.rhizome.core.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * Original bytes live inside the host library, never in the row outbox. The host
 * supplies its single DB-writer dispatcher. Hashing is off that dispatcher and
 * each chunk is a short work unit; no network or whole-book transaction here.
 * createSchema() is explicit, so construction cannot migrate a user's library.
 */
class SqliteAssetStore(private val db: SqliteHandle, private val dbDispatcher: CoroutineDispatcher) : AssetAccess {
    suspend fun createSchema() = withContext(dbDispatcher) {
        db.execute("""CREATE TABLE IF NOT EXISTS rhizome_asset (
            asset_id TEXT PRIMARY KEY, byte_length INTEGER NOT NULL CHECK(byte_length >= 0),
            chunk_bytes INTEGER NOT NULL CHECK(chunk_bytes = 262144),
            state TEXT NOT NULL CHECK(state IN ('staging','verifying','ready','invalid')),
            generation INTEGER NOT NULL DEFAULT 0)""")
        db.execute("""CREATE TABLE IF NOT EXISTS rhizome_asset_chunk (
            asset_id TEXT NOT NULL, chunk_index INTEGER NOT NULL CHECK(chunk_index >= 0),
            sha256 TEXT NOT NULL, bytes BLOB NOT NULL CHECK(length(bytes) BETWEEN 1 AND 262144),
            PRIMARY KEY(asset_id,chunk_index))""")
    }

    private fun stored(id: String): Pair<AssetInfo, Long> {
        assetCheck(isAssetDigest(id), 400, "invalid_asset_id")
        return db.query("SELECT * FROM rhizome_asset WHERE asset_id=?", listOf(id)) { row ->
            AssetInfo(AssetDescriptor(id, row.getLong("byte_length")!!, row.getLong("chunk_bytes")!!.toInt()),
                AssetState.valueOf(row.getString("state")!!.uppercase())) to row.getLong("generation")!!
        }.singleOrNull() ?: throw AssetException(404, "asset_not_found")
    }

    override suspend fun describe(id: String) = withContext(dbDispatcher) { stored(id).first }

    override suspend fun stage(descriptor: AssetDescriptor) = withContext(dbDispatcher) {
        db.transaction {
            db.execute("INSERT OR IGNORE INTO rhizome_asset(asset_id,byte_length,chunk_bytes,state) VALUES(?,?,?,'staging')",
                listOf(descriptor.id, descriptor.byteLength, descriptor.chunkBytes.toLong()))
            stored(descriptor.id).first.also { assetCheck(it.descriptor == descriptor, 409, "descriptor_conflict") }
        }
    }

    override suspend fun listChunks(id: String, start: Long, limit: Int): AssetChunkPage = withContext(dbDispatcher) {
        val d = stored(id).first.descriptor
        assetCheck(start >= 0 && start <= d.chunkCount && limit in 1..ASSET_PAGE_ENTRIES, 400, "invalid_page")
        val end = minOf(start + limit, d.chunkCount)
        val present = db.query("SELECT chunk_index,sha256 FROM rhizome_asset_chunk WHERE asset_id=? AND chunk_index>=? AND chunk_index<? ORDER BY chunk_index",
            listOf(id, start, end)) { it.getLong("chunk_index")!! to it.getString("sha256")!! }.toMap()
        AssetChunkPage((start until end).map { AssetChunkEntry(it, present[it], d.chunkLength(it)) }, if (end == d.chunkCount) null else end)
    }

    private fun storedChunk(id: String, index: Long): AssetChunk = db.query(
        "SELECT sha256,bytes FROM rhizome_asset_chunk WHERE asset_id=? AND chunk_index=?", listOf(id, index)
    ) { AssetChunk(it.getBlob("bytes")!!, it.getString("sha256")!!) }.singleOrNull() ?: throw AssetException(409, "missing_chunks")

    override suspend fun readChunk(id: String, index: Long) = withContext(dbDispatcher) {
        val info = stored(id).first
        info.descriptor.chunkLength(index)
        assetCheck(info.state == AssetState.READY, 409, "asset_not_ready")
        storedChunk(id, index)
    }

    override suspend fun writeChunk(id: String, index: Long, bytes: ByteArray, digest: String) {
        assetCheck(bytes.size <= ASSET_CHUNK_BYTES, 413, "chunk_too_large")
        assetCheck(isAssetDigest(digest), 400, "invalid_chunk_digest")
        // Own the buffer across suspension; callers may reuse their input later.
        val owned = bytes.copyOf()
        assetCheck(withContext(Dispatchers.Default) { assetDigest(owned) } == digest, 422, "chunk_hash_mismatch")
        withContext(dbDispatcher) {
            db.transaction {
                db.execute("UPDATE rhizome_asset SET generation=generation WHERE asset_id=?", listOf(id))
                val info = stored(id).first
                assetCheck(owned.size == info.descriptor.chunkLength(index), 400, "invalid_chunk_length")
                val old = db.query("SELECT sha256 FROM rhizome_asset_chunk WHERE asset_id=? AND chunk_index=?", listOf(id,index)) { it.getString("sha256")!! }.singleOrNull()
                if (old != null) {
                    assetCheck(old == digest, 409, "chunk_conflict")
                    assetCheck(info.state != AssetState.INVALID, 409, "asset_invalid")
                } else {
                    assetCheck(info.state == AssetState.STAGING, 409, "asset_not_staging")
                    db.execute("INSERT INTO rhizome_asset_chunk(asset_id,chunk_index,sha256,bytes) VALUES(?,?,?,?)", listOf(id,index,digest,owned))
                }
            }
        }
    }

    override suspend fun complete(id: String): AssetInfo = withContext(Dispatchers.Default) {
        val (info, generation) = withContext(dbDispatcher) {
            assetCheck(isAssetDigest(id), 400, "invalid_asset_id")
            db.execute("UPDATE rhizome_asset SET state='verifying' WHERE asset_id=? AND state='staging'", listOf(id))
            stored(id)
        }
        if (info.state == AssetState.READY) return@withContext info
        assetCheck(info.state == AssetState.VERIFYING, 409, "asset_invalid")
        val hash = MessageDigest.getInstance("SHA-256")
        var valid = true
        try {
            for (index in 0 until info.descriptor.chunkCount) {
                val c = withContext(dbDispatcher) { storedChunk(id, index) }
                if (c.bytes.size != info.descriptor.chunkLength(index) || assetDigest(c.bytes) != c.sha256) { valid = false; break }
                hash.update(c.bytes)
            }
        } catch (e: AssetException) {
            if (e.code == "missing_chunks") withContext(dbDispatcher) {
                db.execute("UPDATE rhizome_asset SET state='staging' WHERE asset_id=? AND state='verifying' AND generation=?", listOf(id,generation))
            }
            throw e
        }
        valid = valid && hash.digest().toHex() == id
        withContext(dbDispatcher) {
            db.transaction {
                db.execute("UPDATE rhizome_asset SET state=? WHERE asset_id=? AND state='verifying' AND generation=?",
                    listOf(if (valid) "ready" else "invalid",id,generation))
                val result = stored(id).first
                // Keep the invalid state durable: throw AFTER committing below.
                result
            }
        }.also {
            assetCheck(it.state != AssetState.INVALID, 422, "asset_hash_mismatch")
            assetCheck(it.state == AssetState.READY, 409, "verification_changed")
        }
    }

    override suspend fun resetInvalid(id: String) = withContext(dbDispatcher) {
        db.transaction {
            db.execute("UPDATE rhizome_asset SET generation=generation WHERE asset_id=?", listOf(id))
            assetCheck(stored(id).first.state == AssetState.INVALID, 409, "asset_not_invalid")
            db.execute("UPDATE rhizome_asset SET state='staging',generation=generation+1 WHERE asset_id=?", listOf(id))
            db.execute("DELETE FROM rhizome_asset_chunk WHERE asset_id=?", listOf(id))
        }
    }
}
