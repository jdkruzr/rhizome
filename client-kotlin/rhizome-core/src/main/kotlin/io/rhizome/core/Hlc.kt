package io.rhizome.core

/**
 * A Hybrid Logical Clock for `op_ts` (spec/hlc.md). **Millisecond-unit**: the value is wall-clock
 * milliseconds dragged strictly forward on every event. Two properties fall out:
 *
 *  - **Causality never inverts** — absorbing a remote op drags [last] strictly past it, so any op
 *    authored afterward sorts strictly after it (the silent-revert failure of raw wall-clock LWW).
 *  - **Legacy raw-`wall_ts` values interoperate with no flag** — they are already in millisecond
 *    units, so a counter-less legacy value compares correctly against an HLC value. Mixed-version
 *    devices sync correctly during the ForestNote/UB cutover with no data migration.
 *
 * Not thread-safe; callers serialize on the single DB-writer thread (like the rest of the adapter).
 * [last] is the durable state — persist it (the SQLite adapter keeps it in `rhizome_sync_state`).
 */
class Hlc(
    last: Long = 0,
    private val wallClock: () -> Long = { System.currentTimeMillis() },
) {
    var last: Long = last
        private set

    /** Stamp a locally-authored op: `last = max(wallNow, last + 1)`. Returns the new `op_ts`. */
    fun localEvent(): Long {
        last = maxOf(wallClock(), last + 1)
        return last
    }

    /**
     * Absorb a remote op's timestamp so future local events sort strictly after it:
     * `last = max(wallNow, last + 1, remote + 1)`. Returns the new [last] (for persistence).
     */
    fun receiveEvent(remote: Long): Long {
        last = maxOf(wallClock(), last + 1, remote + 1)
        return last
    }
}
