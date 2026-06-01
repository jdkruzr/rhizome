package io.rhizome.core

/** Where a single `POST /sync/v1` landed. Every failure folds into an outcome — the transport
 *  never throws, so the engine's status mapping (spec/protocol.md) stays in one place. */
sealed interface SyncOutcome {
    data class Ok(val response: SyncResponse) : SyncOutcome
    data class HttpError(val code: Int, val body: String?) : SyncOutcome
    data class TransportError(val cause: Throwable) : SyncOutcome
}

/** The network seam. Production is rhizome-http's HttpUrlTransport; tests inject fakes. */
fun interface SyncTransport {
    suspend fun post(request: SyncRequest): SyncOutcome
}
