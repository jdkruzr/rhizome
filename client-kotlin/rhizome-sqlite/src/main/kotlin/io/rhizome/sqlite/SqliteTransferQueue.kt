package io.rhizome.sqlite

import io.rhizome.core.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Local-only scheduler tables in the host library; never capture these into the row outbox.
 * Scope is an opaque, stable server/account identity, NOT a credential or a device site ID.
 * The host must construct a new scope/bound transports when switching server or account.
 */
class SqliteTransferQueue(
    private val db: SqliteHandle,
    private val dispatcher: CoroutineDispatcher,
    private val scope: String,
) : TransferQueue {
    init { require(scope.isNotBlank() && scope.length <= 256) }

    suspend fun createSchema() = withContext(dispatcher) {
        db.transaction {
            db.execute("""CREATE TABLE IF NOT EXISTS rhizome_transfer_schedule (
                scope TEXT PRIMARY KEY, discovery_after TEXT, discovery_at INTEGER NOT NULL DEFAULT 0,
                row_turn INTEGER NOT NULL DEFAULT 1, direction TEXT NOT NULL DEFAULT 'UPLOAD',
                last_upload TEXT NOT NULL DEFAULT '', last_download TEXT NOT NULL DEFAULT '',
                row_at INTEGER NOT NULL DEFAULT 0, row_failures INTEGER NOT NULL DEFAULT 0, row_error TEXT,
                retry_at INTEGER NOT NULL DEFAULT 0, failures INTEGER NOT NULL DEFAULT 0, pause TEXT)""")
            db.execute("""CREATE TABLE IF NOT EXISTS rhizome_transfer_job (
                scope TEXT NOT NULL, asset_id TEXT NOT NULL, byte_length INTEGER NOT NULL CHECK(byte_length>=0),
                direction TEXT NOT NULL, phase TEXT NOT NULL, next_index INTEGER NOT NULL DEFAULT 0,
                verified_bytes INTEGER NOT NULL DEFAULT 0, local_ready INTEGER NOT NULL DEFAULT 0,
                server_ready INTEGER NOT NULL DEFAULT 0, checked_at INTEGER,
                failures INTEGER NOT NULL DEFAULT 0, retry_at INTEGER NOT NULL DEFAULT 0, error TEXT,
                PRIMARY KEY(scope,asset_id))""")
            db.execute("CREATE INDEX IF NOT EXISTS rhizome_transfer_direction ON rhizome_transfer_job(scope,direction,asset_id)")
            db.execute("CREATE INDEX IF NOT EXISTS rhizome_transfer_wake ON rhizome_transfer_job(scope,retry_at) WHERE phase<>'FAILED'")
            db.execute("INSERT OR IGNORE INTO rhizome_transfer_schedule(scope) VALUES(?)", listOf(scope))
        }
    }

    override suspend fun schedule(): LibrarySchedule = withContext(dispatcher) {
        db.query("SELECT * FROM rhizome_transfer_schedule WHERE scope=?", listOf(scope)) { r ->
            LibrarySchedule(r.getString("discovery_after"), r.getLong("discovery_at")!!,
                r.getLong("row_turn") == 1L, TransferDirection.valueOf(r.getString("direction")!!),
                r.getString("last_upload")!!, r.getString("last_download")!!,
                r.getLong("row_at")!!, r.getLong("row_failures")!!.toInt(), r.getString("row_error"),
                r.getLong("retry_at")!!, r.getLong("failures")!!.toInt(), r.getString("pause"))
        }.single()
    }

    override suspend fun saveSchedule(state: LibrarySchedule) = withContext(dispatcher) { saveState(state) }
    private fun saveState(s: LibrarySchedule) {
        db.execute("""UPDATE rhizome_transfer_schedule SET discovery_after=?,discovery_at=?,row_turn=?,direction=?,
            last_upload=?,last_download=?,row_at=?,row_failures=?,row_error=?,retry_at=?,failures=?,pause=? WHERE scope=?""",
            listOf(s.discoveryAfter, s.discoveryAt, if (s.rowTurn) 1L else 0L, s.direction.name,
                s.lastUpload, s.lastDownload, s.rowAt, s.rowFailures.toLong(), s.rowError,
                s.retryAt, s.failures.toLong(), s.pause, scope))
    }

    override suspend fun discover(jobs: List<TransferJob>, state: LibrarySchedule) = withContext(dispatcher) {
        require(jobs.size <= 256)
        db.transaction {
            for (job in jobs) {
                db.execute("""INSERT OR IGNORE INTO rhizome_transfer_job
                    (scope,asset_id,byte_length,direction,phase,local_ready) VALUES(?,?,?,?,'QUEUED',?)""",
                    listOf(scope, job.descriptor.id, job.descriptor.byteLength, job.direction.name, if (job.localReady) 1L else 0L))
                val length = db.query("SELECT byte_length FROM rhizome_transfer_job WHERE scope=? AND asset_id=?",
                    listOf(scope, job.descriptor.id)) { it.getLong("byte_length")!! }.single()
                assetCheck(length == job.descriptor.byteLength, 409, "descriptor_conflict")
            }
            saveState(state)
        }
    }

    override suspend fun next(direction: TransferDirection, after: String, now: Long): TransferJob? = withContext(dispatcher) {
        fun select(comparison: String) = db.query("""SELECT * FROM rhizome_transfer_job
            WHERE scope=? AND direction=? AND asset_id $comparison ? AND retry_at<=? AND phase<>'FAILED'
            ORDER BY asset_id LIMIT 1""", listOf(scope, direction.name, after, now), ::readJob).singleOrNull()
        select(">") ?: select("<=")
    }

    override suspend fun save(job: TransferJob) = withContext(dispatcher) {
        require(job.nextIndex in 0..job.descriptor.chunkCount && job.verifiedBytes in 0..job.descriptor.byteLength)
        db.execute("""UPDATE rhizome_transfer_job SET direction=?,phase=?,next_index=?,verified_bytes=?,local_ready=?,
            server_ready=?,checked_at=?,failures=?,retry_at=?,error=? WHERE scope=? AND asset_id=? AND byte_length=?""",
            listOf(job.direction.name, job.phase.name, job.nextIndex, job.verifiedBytes, if (job.localReady) 1L else 0L,
                if (job.serverReady) 1L else 0L, job.checkedAt, job.failures.toLong(), job.retryAt, job.error?.take(256),
                scope, job.descriptor.id, job.descriptor.byteLength))
    }

    override suspend fun job(id: String): TransferJob? = withContext(dispatcher) {
        db.query("SELECT * FROM rhizome_transfer_job WHERE scope=? AND asset_id=?", listOf(scope, id), ::readJob).singleOrNull()
    }

    override suspend fun page(after: String?, limit: Int): List<TransferJob> = withContext(dispatcher) {
        require(limit in 1..256)
        db.query("SELECT * FROM rhizome_transfer_job WHERE scope=? AND asset_id>? ORDER BY asset_id LIMIT ?",
            listOf(scope, after ?: "", limit.toLong()), ::readJob)
    }

    override suspend fun nextWake(): Long? = withContext(dispatcher) {
        db.query("SELECT MIN(retry_at) AS wake FROM rhizome_transfer_job WHERE scope=? AND phase<>'FAILED'",
            listOf(scope)) { it.getLong("wake") }.single()
    }

    override suspend fun beginSession() = withContext(dispatcher) {
        db.transaction {
            db.execute("""UPDATE rhizome_transfer_job SET server_ready=0,checked_at=NULL,
                retry_at=CASE WHEN phase='READY' THEN 0 ELSE retry_at END,
                phase=CASE WHEN phase='READY' THEN 'QUEUED' ELSE phase END WHERE scope=?""", listOf(scope))
            // Keep retry/error state, fair rotation and discovery cursor across restarts.
            db.execute("""UPDATE rhizome_transfer_schedule SET row_turn=1,
                row_at=CASE WHEN row_error IS NULL OR row_error='metadata_rejected' THEN 0 ELSE row_at END
                WHERE scope=?""", listOf(scope))
        }
    }

    private fun readJob(r: SqliteRow) = TransferJob(
        AssetDescriptor(r.getString("asset_id")!!, r.getLong("byte_length")!!),
        TransferDirection.valueOf(r.getString("direction")!!), TransferPhase.valueOf(r.getString("phase")!!),
        r.getLong("next_index")!!, r.getLong("verified_bytes")!!, r.getLong("local_ready") == 1L,
        r.getLong("server_ready") == 1L, r.getLong("checked_at"), r.getLong("failures")!!.toInt(),
        r.getLong("retry_at")!!, r.getString("error"))
}
