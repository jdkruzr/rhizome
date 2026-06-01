package io.rhizome.core

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncEngineTest {

    private fun op(pk: String, opSeq: Long, opTs: Long) =
        Op("notebook", pk, "SITE", opSeq, opTs, JsonObject(emptyMap()))

    private class FakeStore(
        var site: String? = "SITE",
        var cur: Long = 0,
        val pending: MutableList<Op> = mutableListOf(),
    ) : SyncLocalStore {
        val applied = mutableListOf<Op>()
        var ackedThrough: Long = -1
        val cursorsSet = mutableListOf<Long>()
        override suspend fun siteId() = site
        override suspend fun cursor() = cur
        override suspend fun pendingOps() = pending.toList()
        override suspend fun applyRelayed(ops: List<Op>) { applied += ops }
        override suspend fun markAckedThrough(through: Long) { ackedThrough = through }
        override suspend fun setCursor(cursor: Long) { cur = cursor; cursorsSet += cursor }
    }

    private class FakeTransport(outcomes: List<SyncOutcome>) : SyncTransport {
        val queue = ArrayDeque(outcomes)
        val requests = mutableListOf<SyncRequest>()
        override suspend fun post(request: SyncRequest): SyncOutcome {
            requests += request
            return queue.removeFirst()
        }
    }

    private fun engine(store: SyncLocalStore, transport: SyncTransport, rejected: (List<RejectedOp>) -> Unit = {}) =
        SyncEngine(store, transport, schemaHash = "TESTHASH", clock = { 42L }, onRejected = rejected)

    @Test
    fun notEnabledWhenNoSiteId() = runTest {
        val store = FakeStore(site = null)
        val transport = FakeTransport(emptyList())
        assertEquals(SyncResult.NotEnabled, engine(store, transport).syncOnce())
        assertTrue(transport.requests.isEmpty(), "nothing should be sent when not enabled")
    }

    @Test
    fun drainsHasMoreApplyingAndAdvancingCursor() = runTest {
        val relayed = op("NB-remote", 7, 700).toWire()
        val transport = FakeTransport(
            listOf(
                SyncOutcome.Ok(SyncResponse(acceptedThrough = 5, ops = listOf(relayed), cursor = 10, hasMore = true)),
                SyncOutcome.Ok(SyncResponse(acceptedThrough = 5, ops = emptyList(), cursor = 20, hasMore = false)),
            ),
        )
        val store = FakeStore(cur = 0, pending = mutableListOf(op("NB-local", 5, 500)))
        val result = engine(store, transport).syncOnce()

        assertEquals(SyncResult.Success, result)
        assertEquals(2, transport.requests.size, "should loop while has_more")
        assertEquals(listOf("NB-remote"), store.applied.map { it.pk }, "relayed op applied")
        assertEquals(5, store.ackedThrough)
        assertEquals(listOf(10L, 20L), store.cursorsSet, "adopts each server cursor in order")
        assertEquals(20, store.cur)
        assertEquals(0, transport.requests[0].cursor)
        assertEquals(10, transport.requests[1].cursor, "second request carries the adopted cursor")
    }

    @Test
    fun surfacesRejectedOps() = runTest {
        var seen: List<RejectedOp> = emptyList()
        val transport = FakeTransport(
            listOf(
                SyncOutcome.Ok(
                    SyncResponse(
                        acceptedThrough = 3,
                        rejected = listOf(RejectedOp("SITE", 2, "bad schema")),
                        cursor = 1,
                        hasMore = false,
                    ),
                ),
            ),
        )
        engine(FakeStore(), transport, rejected = { seen = it }).syncOnce()
        assertEquals(listOf(2L), seen.map { it.opSeq })
    }

    @Test
    fun mapsEnvelopeErrors() = runTest {
        suspend fun resultFor(code: Int): SyncResult {
            val t = FakeTransport(listOf(SyncOutcome.HttpError(code, "body")))
            return engine(FakeStore(), t).syncOnce()
        }
        assertEquals(SyncResult.AuthRequired, resultFor(401))
        assertEquals(SyncResult.SchemaMismatch, resultFor(409))
        assertTrue(resultFor(503) is SyncResult.Retryable)
        assertTrue(resultFor(400) is SyncResult.Failed)
    }

    @Test
    fun transportErrorIsRetryable() = runTest {
        val t = FakeTransport(listOf(SyncOutcome.TransportError(java.io.IOException("dns"))))
        assertTrue(engine(FakeStore(), t).syncOnce() is SyncResult.Retryable)
    }
}
