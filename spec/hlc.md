# Hybrid Logical Clock (`op_ts`)

`op_ts` is the merge's primary ordering key. RhizomeSync stamps it with a **Hybrid Logical Clock
(HLC)** rather than a raw wall clock, to kill one specific, nasty failure of wall-clock LWW.

## The failure HLC fixes: causal inversion

Raw wall-clock LWW can lose data silently. Device B's clock is slow. You edit a row on device A;
it syncs to B; on B you refine that edit further. Your B edit is **causally later** — made with
knowledge of the A edit — but because B's clock is slow it gets an *earlier* timestamp and LWW
discards it in favor of the edit it was based on. A silent revert.

A pure logical (Lamport) clock fixes causality but loses real-time meaning. An HLC is both.

## Representation

A single int64: high bits = physical milliseconds, low bits = a logical counter.
v1 uses **48 bits physical ms + 16 bits counter** (counter saturates at 65535; on overflow,
bump physical by 1ms — vanishingly rare). Comparison is plain unsigned int64 comparison
(physical dominates; counter breaks same-ms ties).

## Update rules

State per replica: `last` (the last HLC it issued), persisted durably.

**Local event (authoring an op):**
```
pt = max(physical(last), wallClockNow())
counter = (pt == physical(last)) ? counter(last) + 1 : 0
last = pack(pt, counter)
return last
```

**On receiving / applying any remote op with timestamp `remote`:**
```
pt = max(physical(last), physical(remote), wallClockNow())
counter =
   pt == physical(last) == physical(remote) -> max(counter(last), counter(remote)) + 1
   pt == physical(last)                     -> counter(last) + 1
   pt == physical(remote)                   -> counter(remote) + 1
   else                                     -> 0
last = pack(pt, counter)
```

The receive rule is what guarantees causality: absorbing an op drags the clock strictly past it,
so any op authored afterward sorts strictly after it.

## Guarantees and limits

- **Causality never inverts.** (The property raw wall-clock lacks.)
- **Stays within bounded skew of real time**, so "recent" remains meaningful and timestamps are
  human-interpretable. (The property a Lamport clock lacks.)
- **Limit:** a *persistently* wrong clock still wins during its skew window; only server-stamped
  time fully cures that. Accepted for the single-user / few-devices target.

## Migration compatibility

Because an HLC's high bits *are* wall-clock milliseconds, an HLC value compares correctly against
a legacy raw-`wall_ts` value with no flag or version marker — a counter-less legacy value behaves
exactly like an HLC with counter 0. Old and new ops therefore interoperate during the ForestNote
/ UB cutover, and the schema hash is unaffected (the field is still an int64). A `hlc`-category
conformance vector pins the mixed-ordering behavior.

## Persistence (correctness requirement)

`last` MUST survive process death, or monotonicity can break across restarts. Persist it (e.g. in
the client's `sync_state`); on load, seed from `max(stored, pack(wallClockNow(), 0))`.
