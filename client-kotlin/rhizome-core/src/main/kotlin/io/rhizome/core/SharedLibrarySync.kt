package io.rhizome.core

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Keyset pages of DISTINCT descriptors, ordered by asset ID; retained trash is required too. */
fun interface AssetReferenceProvider {
    suspend fun pageRequiredAssets(after: String?, limit: Int): RequiredAssetPage
}
data class RequiredAssetPage(val assets: List<AssetDescriptor>, val hasMore: Boolean)
enum class TransferDirection { UPLOAD, DOWNLOAD }
enum class TransferPhase { QUEUED, TRANSFERRING, VERIFYING, WAITING, READY, FAILED }

/** Local observations, NOT metadata ACKs or a permanent promise about remote durability. */
data class TransferJob(
    val descriptor: AssetDescriptor,
    val direction: TransferDirection,
    val phase: TransferPhase = TransferPhase.QUEUED,
    val nextIndex: Long = 0,
    val verifiedBytes: Long = 0,
    val localReady: Boolean = false,
    val serverReady: Boolean = false,
    val checkedAt: Long? = null,
    val failures: Int = 0,
    val retryAt: Long = 0,
    val error: String? = null,
)

data class LibrarySchedule(
    val discoveryAfter: String? = null, val discoveryAt: Long = 0,
    val rowTurn: Boolean = true, val direction: TransferDirection = TransferDirection.UPLOAD,
    val lastUpload: String = "", val lastDownload: String = "",
    val rowAt: Long = 0, val rowFailures: Int = 0, val rowError: String? = null,
    val retryAt: Long = 0, val failures: Int = 0, val pause: String? = null,
)

/** One instance/scope per server-account-library, owned by one coordinator at a time. */
interface TransferQueue {
    suspend fun schedule(): LibrarySchedule
    suspend fun saveSchedule(state: LibrarySchedule)
    /** Descriptors, idempotent job insertion and scan cursor commit together. Never deletes assets. */
    suspend fun discover(jobs: List<TransferJob>, state: LibrarySchedule)
    suspend fun next(direction: TransferDirection, after: String, now: Long): TransferJob?
    suspend fun save(job: TransferJob)
    suspend fun job(id: String): TransferJob?
    suspend fun page(after: String?, limit: Int): List<TransferJob>
    suspend fun nextWake(): Long?
    /** Restart invalidates remote-ready observations, not errors/backoff or original bytes. */
    suspend fun beginSession()
}

data class TransferPolicy(
    val discoveryPageSize: Int = 64,
    val pollMillis: Long = 30_000,
    val retryBaseMillis: Long = 1_000,
    val retryMaxMillis: Long = 300_000,
) {
    init {
        require(discoveryPageSize in 1..256 && pollMillis > 0 && retryBaseMillis > 0 && retryMaxMillis >= retryBaseMillis)
    }
    fun retryDelay(failures: Int): Long {
        var delay = retryBaseMillis
        repeat((failures - 1).coerceIn(0, 30)) { delay = if (delay > retryMaxMillis / 2) retryMaxMillis else delay * 2 }
        return minOf(delay, retryMaxMillis)
    }
}

sealed interface LibraryStep {
    data class Rows(val result: RowExchange) : LibraryStep
    data class Asset(val job: TransferJob) : LibraryStep
    data class Idle(val wakeAt: Long?) : LibraryStep
    data class Paused(val reason: String, val retryAt: Long?) : LibraryStep
}

/**
 * Headless, explicitly driven coordinator. One step services a row page OR at most
 * one asset chunk/manifest page (plus bounded reference discovery). No timer, UI,
 * Android service, global "backed up" flag, GC, or implicit sync opt-in.
 *
 * The host owns lifecycle/network policy and must await step(), then yield/schedule
 * its next invocation. Never run a second coordinator for the same scope. Calls
 * on this instance serialize, including resume/retry; cancellation propagates.
 */
class SharedLibrarySync(
    private val rows: ScheduledRows,
    private val references: AssetReferenceProvider,
    private val local: AssetAccess,
    private val remote: AssetAccess,
    private val queue: TransferQueue,
    private val policy: TransferPolicy = TransferPolicy(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val gate = Mutex()
    private var started = false

    /** After credentials/capabilities are corrected; never resets invalid asset bytes. */
    suspend fun resume() = gate.withLock {
        val s = queue.schedule()
        queue.saveSchedule(s.copy(pause = null, retryAt = 0, failures = 0, rowAt = 0, rowFailures = 0, rowError = null))
    }

    /** Host signals new imports/metadata; make the next bounded scan page eligible now. */
    suspend fun referencesChanged() = gate.withLock {
        queue.saveSchedule(queue.schedule().copy(discoveryAt = 0))
    }

    /** A locally captured edit should not wait for the idle polling deadline. */
    suspend fun metadataChanged() = gate.withLock {
        val s = queue.schedule()
        if (s.rowFailures == 0 && s.rowAt != Long.MAX_VALUE) queue.saveSchedule(s.copy(rowAt = 0, rowTurn = true))
    }

    /** Explicit repair retry; resetting an INVALID staging asset is a separate user action. */
    suspend fun retryAsset(id: String) = gate.withLock {
        val job = queue.job(id) ?: return@withLock
        queue.save(job.copy(phase = TransferPhase.QUEUED, nextIndex = 0, verifiedBytes = 0,
            failures = 0, retryAt = 0, error = null, serverReady = false, checkedAt = null))
    }

    suspend fun step(): LibraryStep = try { gate.withLock {
        if (!started) { queue.beginSession(); started = true }
        var state = queue.schedule()
        val now = clock().coerceAtLeast(0)
        if (state.pause != null) return@withLock LibraryStep.Paused(state.pause, null)
        if (state.retryAt > now) return@withLock LibraryStep.Paused("retry_wait", state.retryAt)

        if (state.rowTurn && state.rowAt <= now) {
            // Persist the turn BEFORE I/O: an interrupted asset is always followed
            // by a row opportunity (beginSession also starts with rows).
            state = state.copy(rowTurn = false)
            queue.saveSchedule(state)
            val result = try { rows.exchange() } catch (e: CancellationException) { throw e
            } catch (e: Exception) { RowExchange.Stopped(SyncResult.Retryable("row_storage_or_transport")) }
            state = when (result) {
                is RowExchange.Page -> state.copy(rowAt = if (result.hasMore) now else later(now, policy.pollMillis),
                    rowFailures = 0, rowError = if (result.response.rejected.isEmpty()) null else "metadata_rejected",
                    discoveryAt = if (result.response.ops.isNotEmpty()) now else state.discoveryAt)
                is RowExchange.Stopped -> when (val reason = result.reason) {
                    SyncResult.AuthRequired, SyncResult.SchemaMismatch, SyncResult.NotEnabled -> state.copy(pause = code(reason))
                    is SyncResult.Retryable -> state.copy(rowFailures = increment(state.rowFailures), rowError = "metadata_retry",
                        rowAt = later(now, policy.retryDelay(increment(state.rowFailures))))
                    else -> state.copy(rowAt = Long.MAX_VALUE, rowError = code(reason))
                }
            }
            queue.saveSchedule(state)
            return@withLock LibraryStep.Rows(result)
        }

        // Discover local references even when there is no asset job yet. The
        // provider does bounded host SQL; failure does not advance its cursor.
        if (state.discoveryAt <= now) {
            try {
                val page = references.pageRequiredAssets(state.discoveryAfter, policy.discoveryPageSize)
                assetCheck(page.assets.size <= policy.discoveryPageSize && (!page.hasMore || page.assets.isNotEmpty()), 400, "invalid_reference_page")
                var after = state.discoveryAfter ?: ""
                val jobs = page.assets.map { d ->
                    assetCheck(d.id > after, 400, "unordered_reference_page"); after = d.id
                    val info = optional(local, d)
                    TransferJob(d, if (info?.state == AssetState.READY) TransferDirection.UPLOAD else TransferDirection.DOWNLOAD,
                        localReady = info?.state == AssetState.READY)
                }
                state = state.copy(discoveryAfter = if (page.hasMore) after else null,
                    discoveryAt = if (page.hasMore) now else later(now, policy.pollMillis))
                queue.discover(jobs, state)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                return@withLock pause(state, if (e is AssetException) e.code else "discovery_storage", now, e !is AssetException)
            }
        }

        val preferred = state.direction
        val job = queue.next(preferred, last(state, preferred), now)
            ?: queue.next(other(preferred), last(state, other(preferred)), now)
        if (job == null) {
            queue.saveSchedule(state.copy(rowTurn = true))
            // A pending discovery page needs another step now, but an idle library
            // returns a deadline so the host need not spin.
            return@withLock LibraryStep.Idle(listOfNotNull(state.rowAt, state.discoveryAt, queue.nextWake()).minOrNull())
        }
        val admitted = try { rows.admission() } catch (e: CancellationException) { throw e
        } catch (e: Exception) { RowAdmission.Stopped(SyncResult.Retryable("capability_transport")) }
        if (admitted is RowAdmission.Stopped) {
            return@withLock pause(state, code(admitted.reason), now, admitted.reason is SyncResult.Retryable)
        }
        admitted as RowAdmission.Allowed
        if (!admitted.assetsEnabled || admitted.manifestEntries !in 1..ASSET_PAGE_ENTRIES) {
            return@withLock pause(state, "assets_unsupported", now, false)
        }
        state = state.copy(rowTurn = true, direction = other(job.direction), failures = 0, retryAt = 0,
            lastUpload = if (job.direction == TransferDirection.UPLOAD) job.descriptor.id else state.lastUpload,
            lastDownload = if (job.direction == TransferDirection.DOWNLOAD) job.descriptor.id else state.lastDownload,
            // Service ordinary edits between chunks, even when the last row page
            // was empty. Do not override a real error's backoff/blocked status.
            rowAt = if (state.rowError == null) now else state.rowAt)
        queue.saveSchedule(state)
        var current = job.copy(serverReady = false, checkedAt = null)
        try {
            val localInfo = optional(local, job.descriptor)
            val remoteInfo = optional(remote, job.descriptor)
            assetCheck(localInfo?.state != AssetState.INVALID && remoteInfo?.state != AssetState.INVALID, 409, "asset_invalid")
            val localReady = localInfo?.state == AssetState.READY
            val serverReady = remoteInfo?.state == AssetState.READY
            val direction = if (localReady) TransferDirection.UPLOAD else TransferDirection.DOWNLOAD
            val targetExists = if (direction == TransferDirection.UPLOAD) remoteInfo != null else localInfo != null
            val resume = direction == job.direction && targetExists
            current = current.copy(direction = direction, localReady = localReady, serverReady = serverReady, checkedAt = now,
                nextIndex = if (resume) job.nextIndex else 0,
                verifiedBytes = if (resume) job.verifiedBytes else 0)
            // Persist observations before transferring; UI never carries an old
            // server-ready claim through a newly observed missing/staging state.
            queue.save(current)
            if (!localReady && !serverReady) {
                // A process may have died after the last chunk, before either
                // checkpoint or verifier started. Salvage complete local staging
                // even if the server has since lost its bytes. Missing chunks
                // simply remain waiting with backoff; readiness is never assumed.
                if (localInfo?.state in setOf(AssetState.STAGING, AssetState.VERIFYING)) {
                    val verified = local.complete(job.descriptor.id)
                    current = current.copy(localReady = verified.state == AssetState.READY)
                }
                throw AssetException(409, "asset_not_ready")
            }
            val progress = if (localReady && serverReady)
                AssetTransfer.Progress(job.descriptor.byteLength, job.descriptor.byteLength, true, job.descriptor.chunkCount)
            else AssetTransfer(if (direction == TransferDirection.UPLOAD) local else remote,
                if (direction == TransferDirection.UPLOAD) remote else local, job.descriptor.id,
                current.nextIndex, admitted.manifestEntries).step()
            val verifying = !progress.ready && progress.nextIndex == job.descriptor.chunkCount
            val verificationPolls = if (verifying) {
                if (current.phase == TransferPhase.VERIFYING) increment(current.failures) else 1
            } else 0
            current = current.copy(phase = when {
                progress.ready -> TransferPhase.READY
                progress.nextIndex == job.descriptor.chunkCount -> TransferPhase.VERIFYING
                else -> TransferPhase.TRANSFERRING
            }, nextIndex = progress.nextIndex, verifiedBytes = progress.verifiedBytes,
                localReady = localReady || (direction == TransferDirection.DOWNLOAD && progress.ready),
                serverReady = serverReady || (direction == TransferDirection.UPLOAD && progress.ready),
                failures = verificationPolls, error = null, retryAt = when {
                    progress.ready -> later(now, policy.pollMillis)
                    verifying -> later(now, policy.retryDelay(verificationPolls))
                    else -> now
                })
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            val failure = if (e is AssetException) e.code else if (e is IOException) "transport" else "storage"
            val auth = e is AssetException && e.status in setOf(401, 403)
            val retry = e !is AssetException || e.status >= 500 || e.status == 404 ||
                (e.status == 409 && e.code in setOf("asset_not_ready", "missing_chunks", "verification_changed"))
            val failures = increment(current.failures)
            current = current.copy(phase = if (retry || auth) TransferPhase.WAITING else TransferPhase.FAILED,
                nextIndex = 0, verifiedBytes = 0, serverReady = false, checkedAt = null,
                failures = failures, error = failure,
                retryAt = if (retry) later(now, policy.retryDelay(failures)) else if (auth) now else Long.MAX_VALUE)
            if (auth) queue.saveSchedule(state.copy(pause = "auth_required"))
        }
        queue.save(current)
        LibraryStep.Asset(current)
    } } catch (e: CancellationException) { throw e
    } catch (e: Exception) {
        // The queue itself may be full/unavailable, so a durable retry marker
        // cannot be promised. Return a host backoff deadline; never report an
        // unpersisted progress update as a successful transfer. A restart resumes
        // from the destination's durable chunks, not this failed UI checkpoint.
        LibraryStep.Paused("scheduler_storage", later(clock().coerceAtLeast(0), policy.retryBaseMillis))
    }

    private suspend fun optional(access: AssetAccess, descriptor: AssetDescriptor): AssetInfo? = try {
        access.describe(descriptor.id).also { assetCheck(it.descriptor == descriptor, 409, "descriptor_conflict") }
    } catch (e: AssetException) { if (e.status == 404) null else throw e }

    private suspend fun pause(state: LibrarySchedule, reason: String, now: Long, retry: Boolean): LibraryStep {
        val failures = increment(state.failures)
        val at = if (retry) later(now, policy.retryDelay(failures)) else 0
        queue.saveSchedule(state.copy(failures = failures, retryAt = at, pause = if (retry) null else reason))
        return LibraryStep.Paused(reason, if (retry) at else null)
    }

    private fun code(reason: SyncResult): String = when (reason) {
        SyncResult.AuthRequired -> "auth_required"
        SyncResult.SchemaMismatch -> "schema_mismatch"
        SyncResult.NotEnabled -> "not_enabled"
        is SyncResult.Retryable -> "transport_retry"
        is SyncResult.Failed -> reason.reason.take(256)
        SyncResult.Success -> "ok"
    }
    private fun last(s: LibrarySchedule, d: TransferDirection) = if (d == TransferDirection.UPLOAD) s.lastUpload else s.lastDownload
    private fun other(d: TransferDirection) = if (d == TransferDirection.UPLOAD) TransferDirection.DOWNLOAD else TransferDirection.UPLOAD
    private fun increment(n: Int) = if (n == Int.MAX_VALUE) n else n + 1
    private fun later(now: Long, delay: Long) = if (delay > Long.MAX_VALUE - now) Long.MAX_VALUE else now + delay
}
