# Architecture & design rationale

This is the "why" behind the contract. The other `spec/` files define *what* RhizomeSync does;
this one explains the choices, places them in the broader sync-systems landscape, and names the
trade-offs we consciously accept. It is reference reading — you do not need it to integrate (see
the [integration guide](integrating.md)), only to understand or extend.

## The shape in one paragraph

Every change is a **full-row snapshot op** appended to a per-device outbox and pushed to a server
that does nothing but **sequence** ops into one global, ordered log and hand each device the ops it
hasn't seen. Devices resolve conflicts locally and identically with **row-level Last-Writer-Wins**
keyed on a **Hybrid Logical Clock**, so every replica that has seen the same ops converges to the
same state with no coordination. Deletes are **tombstones** (a flagged op), not removals. The synced
shape is one **declarative registry** that derives the wire codec, the merge's `knownCols`, capture,
apply, backfill, and a **schema-hash** the server gates on. The server optionally **compacts** the
log; nothing else is unbounded.

That paragraph is the whole system. The rest of this doc is why each clause is the way it is.

## The load-bearing decision: the server sequences but never adjudicates

The single most important property is that **conflict resolution lives in the merge, not in the
server.** The server assigns a monotonic global `seq` to each accepted op and relays ops between
devices. It never decides who wins a conflict — every replica runs the *same* deterministic
[merge](merge.md) over the *same* ops and therefore reaches the *same* state.

This is what lets the server be a dumb relay: no schema-aware merge logic, no per-row locking, no
materialized authoritative copy on the sync path. A relay is easy to write (the reference one is
~100 lines — see `examples/example-server-go`), easy to self-host on a single user's box, and hard
to get subtly wrong, because it makes no semantic decisions. The correctness-critical code is the
merge, and the merge is pinned by language-neutral [conformance vectors](conformance.md) that both
implementations must pass — so "the two sides agree" is a test, not a hope.

The cost of this choice is that the server cannot answer "what is the current value of row X?" from
the sync path alone (the log is a history, not a snapshot). Apps that need server-side queries over
current state run the **optional mirror** — a materialized copy built from the *same* registry via
generic UPSERT, off the sync path (ForestNote uses it for server-side OCR). Apps that only move data
between devices omit it entirely.

## Where RhizomeSync sits in the landscape

Researched and cited 2026-06-01. RhizomeSync is not exotic; it is a mainstream point in the
sync-design space, closest to **PowerSync minus buckets**.

**The conflict-model axis:**

- **Wall-clock / server-time LWW** — RhizomeSync, PowerSync, Firebase. Simple, snapshot-based, a
  bounded amount of metadata. Loses concurrent edits to the *same row* (the loser is discarded). We
  harden the classic wall-clock weakness with an [HLC](hlc.md).
- **CRDTs** — cr-sqlite, Automerge, Yjs, ElectricSQL. Conflict-*free* (concurrent edits merge
  field-by-field) but carry per-value version/tombstone metadata that grows over time — the bloat
  our snapshot+compaction model avoids by discarding losers instead of reconciling them.
- **Mutation-log + server replay** — Replicache / Zero. The client sends intents the server
  replays authoritatively.
- **OT / server-authoritative** — classic operational transform (and the deprecated Realm sync).

We chose snapshot LWW because ForestNote's data (strokes, text boxes, folders) has a natural
single-writer-per-row grain in practice, so whole-row-wins almost never destroys real concurrent
work — and the model's simplicity and bounded metadata fit a locked-down e-ink device far better
than a CRDT runtime would. The two costs of that choice are named explicitly under
[Owned trade-offs](#owned-trade-offs).

### PowerSync mapping (the corroboration)

Piece for piece, RhizomeSync lines up with PowerSync:

| PowerSync | RhizomeSync |
|---|---|
| Bucket op-log | The global relay log |
| Per-bucket checkpoint | The cursor |
| Local SQLite apply | `applyRelayed` |
| Daily collapse-to-latest compaction | [Compaction](compaction.md) rule 1 |

Two deliberate differences:

1. **Server-authoritative-cache vs. multi-master.** PowerSync front-ends an authoritative Postgres
   and resolves by "last write to land at the server." RhizomeSync has **no database behind the
   log** — the log *is* the truth — and every peer runs the identical deterministic merge; the
   server only sequences. That is precisely why our server can be dumb.
2. **Buckets.** PowerSync's buckets are partial replication — the feature we scoped out, because in
   the single-user / multi-device target every device holds everything. PowerSync also has PATCH
   (partial-column) ops; we do full-row PUT only.

The near-perfect alignment with a production system is strong evidence the architecture is sound,
and it doubles as a roadmap: buckets, PATCH, and server-time stamping are the documented upgrade
paths if those needs ever arrive.

### The real-world e-ink contrast: Onyx Boox KSync

The dominant e-ink-notes incumbent, Onyx Boox, ships **KSync** — and the decompiled Onyx SDK (from
`~/booxreverse`, verified 2026-06-01; no public docs exist) shows it is built on **Couchbase Lite
2.x + Sync Gateway**: `.cblite2` databases, BLIP-over-WebSocket replication to region-sharded
hosts, a stateful Sync Gateway, CouchDB-style document/revision ids. It chose the **opposite** of
RhizomeSync on every axis:

| Axis | KSync (Couchbase) | RhizomeSync |
|---|---|---|
| Unit | Schemaless JSON **documents** | Schema-gated **rows** |
| Conflict resolution | Deterministic **rev-tree** (longest history, ties by `_rev` hash), *preserves* conflicts | Wall-clock/HLC **LWW**, *discards* losers |
| Server | Heavyweight stateful Sync Gateway, region-locked cloud | Dumb, self-hostable, single-user relay |

That a shipping product proves the Couchbase lineage is *viable* on these devices is useful
corroboration. That it is document-granular, schemaless, and cloud-locked is exactly why we didn't
adopt it. (Note: CouchDB/Couchbase ≤3.x conflict resolution is *not* wall-clock LWW despite the
schema-gate "vibe" — the internals are genuinely different from ours.)

## Why an HLC, not a raw wall clock

Raw wall-clock LWW has one nasty, *silent* failure: causal inversion. Edit a row on device A; sync
to B; refine it further on B. The B edit is causally later but, if B's clock is slow, gets an
earlier timestamp — and LWW discards the refinement in favor of the edit it was based on. No error,
no conflict marker, just a lost edit.

An [HLC](hlc.md) fixes this by dragging the clock strictly forward whenever a replica observes a
remote op, so anything authored after seeing an op sorts strictly after it. We use a
**millisecond-unit** HLC (not a bit-packed `physical<<16 | counter` layout) specifically so HLC
values share one number line with legacy raw-`wall_ts` values — letting ForestNote/UB cut over with
**no schema-hash bump and no data migration** while a mixed fleet straggles across the upgrade. The
full rationale, including why the packed layout would have caused silent data loss during rollout,
is in [hlc.md](hlc.md).

The HLC does *not* cure a *persistently* wrong clock — only server-stamped time would, and that
would make the server adjudicate. For one user's handful of devices, a persistently-skewed clock is
both unlikely and the user's own to notice; we accept it.

## Why compaction is server-side and lazy

The relay log is the one structure that grows without bound — one op per edit, forever — because
ops are full-row snapshots and the loser of every LWW contest becomes dead weight.
[Compaction](compaction.md) reclaims it with three rules: collapse superseded versions (always
safe, no watermark), purge tombstones only once **every live device's cursor has passed them**
(watermark-gated, so a long-offline device can't resurrect a deleted row), and never renumber `seq`
(renumbering would invalidate every cursor). It is purely server-side — no client or protocol change
— and it shrinks *churn* (repeated edits of the same rows), not *accumulation* (many distinct living
rows are legitimately current state). Devices need no compaction at all: a device holds current
state plus a draining outbox, so editing a row N times still leaves one row.

## Owned trade-offs

These are real and we state them up front rather than hide them:

1. **Client-clock LWW caveat.** Resolution depends on device clocks. The HLC removes causal
   inversion and keeps timestamps near real time, but a *persistently* wrong clock still wins during
   its skew window. Mitigated, not eliminated; acceptable for single-user / few-device use. (A
   server-time stamp would cure it but would make the server adjudicate — the one thing the
   architecture refuses to do.)
2. **Whole-row granularity.** A row is the unit of conflict. Two devices editing *different fields*
   of the *same* row concurrently: the later write wins the whole row, silently dropping the other's
   field change. A non-issue for ForestNote's data shape (rows have a natural single writer in
   practice); documented for general adopters. cr-sqlite's per-column CRDTs are the alternative we
   consciously skipped, trading metadata growth and a heavier runtime for field-level merge.

## Why roll our own at all

The short version (full analysis in `ForestNote/docs/research/sync-decision-2026-05-25.md`):
licensing headroom for possible commercial hosting, viability on a locked-down e-ink device, and
the fact that the clean-license alternatives failed on Android-viability or maturity. The
correctness-critical core turned out to be small, library-shaped, and — as the PowerSync mapping
shows — a well-trodden design, which is what made extracting it into RhizomeSync a bounded effort
rather than a product build.

## See also

- [protocol.md](protocol.md) — the `POST /sync/v1` round-trip and server responsibilities
- [merge.md](merge.md) — the LWW total order and `normalize`
- [hlc.md](hlc.md) — the Hybrid Logical Clock and migration compatibility
- [schema-registry.md](schema-registry.md) — the one declaration everything derives from
- [compaction.md](compaction.md) — server-side log reclamation
- [schema-evolution.md](schema-evolution.md) — mixed-version sync and the cursor-reset
- [conformance.md](conformance.md) — the executable, dual-language contract
