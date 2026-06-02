# Hybrid Logical Clock (`op_ts`)

`op_ts` is the merge's primary ordering key. RhizomeSync stamps it with a **Hybrid Logical Clock
(HLC)** rather than a raw wall clock, to kill one specific, nasty failure of wall-clock LWW.

## The failure HLC fixes: causal inversion

Raw wall-clock LWW can lose data silently. Device B's clock is slow. You edit a row on device A;
it syncs to B; on B you refine that edit further. Your B edit is **causally later** — made with
knowledge of the A edit — but because B's clock is slow it gets an *earlier* timestamp and LWW
discards it in favor of the edit it was based on. A silent revert.

A pure logical (Lamport) clock fixes causality but loses real-time meaning. An HLC is both.

## Representation — millisecond-unit

`op_ts` is a single `int64` **in wall-clock-millisecond units**, dragged strictly forward on every
event. There is no bit-packing and no separate counter field: the logical "tick" is just `+1` on
the same millisecond. Comparison is plain `int64` comparison — exactly the existing merge key, so
`op_ts` interoperates with the legacy raw-`wall_ts` field unchanged (see Migration below).

> **Why not a packed `physical<<16 | counter` layout?** A packed value (`~ms × 65536`) is ~5 orders
> of magnitude larger than a raw-ms `wall_ts`, so during a *staged* rollout (devices upgrade on
> independent timelines — see schema-evolution.md) every HLC op would beat every not-yet-upgraded
> device's op regardless of real time: silent data loss. Worse, `op_ts` carries no version marker
> and the shift doesn't change the schema hash, so there's nothing to gate a clean cutover on
> without a magnitude heuristic. Millisecond units avoid the whole problem — legacy and HLC values
> share one number line and always compare correctly. The only cost is that under a burst the value
> can run slightly ahead of real time (see Limits); irrelevant for single-user/few-device use.

## Update rules

State per replica: `last` (the last `op_ts` it issued), persisted durably.

**Local event (authoring an op):**
```
last = max(wallClockNow(), last + 1)
return last
```
`+1` guarantees a replica never issues the same `op_ts` twice within a millisecond; once the wall
clock advances past `last`, the value tracks real time again.

**On receiving / applying any remote op with timestamp `remote`:**
```
last = max(wallClockNow(), last + 1, remote + 1)
```
This is what guarantees causality: absorbing an op drags the clock **strictly past** it, so any op
authored afterward sorts strictly after it. (Receiving updates `last` only; it does not author an
op. The client bumps once per `applyRelayed` batch using the greatest received `op_ts`.)

Cross-replica ties on an identical `op_ts` (two replicas authoring at the same ms without having
seen each other) fall through to the merge's secondary keys `(op_seq, site_id)` — see merge.md.

## Guarantees and limits

- **Causality never inverts.** (The property raw wall-clock lacks.)
- **Stays within bounded skew of real time** in practice, so "recent" remains meaningful and values
  are human-interpretable (≈ ms since epoch). The value runs ahead of real time only by the number
  of events stamped within a single real millisecond; you would need >1000 edits *in one ms*,
  sustained, to drift even one second — impossible for a human authoring by hand.
- **Limit:** a *persistently* wrong clock still wins during its skew window; only server-stamped
  time fully cures that. Accepted for the single-user / few-devices target.

## Migration compatibility

Because `op_ts` stays in millisecond units, a legacy raw-`wall_ts` value (plain ms, no logical
tick) is **already a valid `op_ts`** — it compares correctly against an HLC value with no flag, no
version marker, and no data migration. Old and new ops therefore interoperate for as long as a
mixed fleet exists during the ForestNote / UB cutover, and the schema hash is unaffected (the field
is still an `int64`). On adopting RhizomeSync a replica seeds `last` from `max(persisted last_hlc,
greatest op_ts already in its outbox / row-meta)`, so it never reissues or regresses a timestamp it
or the server has already seen. The `hlc`-category conformance vectors pin both the local/receive
arithmetic and this legacy-value interop.

## Persistence (correctness requirement)

`last` MUST survive process death, or monotonicity can break across restarts. Persist it (the
client SQLite adapter keeps it in `rhizome_sync_state.last_hlc`, written inside the same
transaction as the op it stamps); on load, seed from `max(stored, greatest op_ts on hand)`.
