package io.rhizome.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * The deterministic, side-effect-free row-level Last-Writer-Wins merge (spec/merge.md). It MUST
 * agree with the Go server's reconcile and pass every `merge` vector in /conformance/vectors.
 *
 * The winner for a (table, pk) is the op with the greatest key (opTs, opSeq, siteId). Because the
 * order is total and arrival-order-independent, every replica that has seen the same ops converges.
 */
object Merge {

    /** True iff a's LWW key is strictly less than b's: compare (opTs, opSeq, siteId). */
    fun less(a: Op, b: Op): Boolean {
        if (a.opTs != b.opTs) return a.opTs < b.opTs
        if (a.opSeq != b.opSeq) return a.opSeq < b.opSeq
        return a.siteId < b.siteId
    }

    /** Drop any cols key not known for the op's table (forward-compat). Unknown table ⇒ empty cols. */
    fun normalize(op: Op, knownCols: Map<String, List<String>>): Op {
        val known = knownCols[op.table] ?: emptyList()
        val filtered: JsonObject = buildJsonObject {
            for (k in known) op.cols[k]?.let { put(k, it) }
        }
        return op.copy(cols = filtered)
    }

    /** Reduce ops to the winning (normalized) op per (table, pk) under the LWW total order. */
    fun merge(ops: List<Op>, knownCols: Map<String, List<String>>): Map<TablePK, Op> {
        val winners = HashMap<TablePK, Op>()
        for (raw in ops) {
            val n = normalize(raw, knownCols)
            val cur = winners[n.key]
            if (cur == null || less(cur, n)) winners[n.key] = n
        }
        return winners
    }
}
