# Compaction (server-side)

The relay log grows by one row per edit, forever — the only unbounded structure in the system.
Because ops are full-row snapshots under LWW, superseded ops are dead weight. Compaction reclaims
them. It is **server-side only**: no client change, no protocol change.

Devices need no compaction: a device stores current state + a *draining* outbox + one provenance
row per live row. Editing a row N times leaves one row, not N. Device footprint ≈ data size.

## Three rules

### 1. Collapse superseded live versions — no watermark needed
For each `(table, pk)`, keep the op with the greatest LWW key **at its original `seq`**; delete
the older ops; leave `seq` holes (do not renumber). Safe for *every* replica regardless of how
far behind: a replica only ever skips a version that LWW would have discarded anyway, and its
`seq > cursor` pull still finds the surviving latest op. This is the cheap, always-safe win.

### 2. Reclaim tombstones — watermark-gated
A tombstone (a surviving op with the table's tombstone column non-null) may be **purged entirely**
only once **every known site's cursor has passed its `seq`**:
```
watermark = min(last_pull_seq) over all known sites
purge a tombstone only if its seq <= watermark
```
Otherwise a stale, long-offline replica that never saw the death still believes the row is alive
and can **re-upload it — a zombie/resurrection.** This is the classic distributed tombstone-GC
problem (≈ Cassandra `gc_grace_seconds`). The server already records `last_pull_seq` per site, so
the watermark is computable.

### 3. No `seq` renumbering
Compressing the holes would invalidate every replica's cursor. Not worth it; live with sparse
`seq`.

## Stale-site eviction

One dead device must not pin the watermark forever. A site not seen for longer than a configured
horizon is **evicted** from the watermark calculation (and a notice is logged — silent caps are
forbidden). An evicted device that later returns simply does a correct full `cursor=0` re-pull
(safe and idempotent under LWW), reconstructing state from the compacted log.

## Resulting bound

After compaction the log is ≈ *current live data size* + *recent churn tail* + *un-reclaimed
tombstones* — the same order of magnitude each device already holds. Compaction shrinks **churn**
(repeated edits/moves/deletes of the same rows), not **accumulation** (many distinct living rows
are legitimately part of current state, not bloat).

## Correctness obligations (tested by `compaction` vectors)

- A compacted log still reconstructs the exact current state for a fresh `cursor=0` replica.
- A tombstone is NOT purged while any non-evicted site's cursor is below it (no-zombie test).
- The sweep is idempotent and safe to run concurrently with `appendOp`.
