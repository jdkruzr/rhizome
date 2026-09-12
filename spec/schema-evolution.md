# Schema evolution

With one user's devices on independent upgrade timelines, **mixed-version sync is the normal
case, not an edge case**: device A updates to a new app version (new registry) before B and C.
This is where the schema-hash gate alone is insufficient and a known data-loss hole lives.

## The hole

A replica on an *old* registry pulls ops for tables/columns it doesn't model. `normalize` drops
them (unknown columns) or the apply skips them (unknown table). Either way the replica **advances
its cursor past those ops** — and after it upgrades to the new registry, it starts pulling from
the advanced cursor and **never re-pulls the stranded ops**. The newly-modeled data is silently
missing on that device.

## The fix: one-shot cursor reset on schema-hash change

A replica persists a `schema_generation` marker alongside its current `SCHEMA_HASH`. When it
**first advertises a new `SCHEMA_HASH`** (i.e. the app upgraded and its registry changed), it
**resets its pull cursor to 0 exactly once**, forcing a full re-pull of the relay backlog.

For newly modeled tables this works because merge is idempotent LWW: re-applying already-seen
ops is a no-op, and previously unknown tables now materialize. Existing-table columns require the
targeted repair below: their row versions may already match and normal LWW must remain strict.
Gate the cursor reset like a run-once
generation bump (the mechanism ForestNote already uses for `SYNC_BACKFILL_VERSION`) so it fires
exactly on the transition, not on every launch.

```
on startup / enableSync:
  current = SCHEMA_HASH(registry)
  if stored_schema_hash != current:
     pull_cursor = 0                 // one-shot full re-pull
     stored_schema_hash = current
```

Cursor reset and marker write must share one real transaction. The marker means replay was
scheduled, not admitted or completed; the durable response cursor resumes partial replay.

## Explicit additive-column recovery (Kotlin SQLite adapter)

At a known forward-migration boundary call `SqliteStorageAdapter.prepareColumnUpgrade(previousRegistry)`
on the shared writer, inside the same real transaction as the host's DDL/version/marker changes.
Never guess the previous registry from NULL/default row values or blindly pass an old registry to
an already-current library. Removed/changed columns, PK/tombstone/ownership changes, and additions
to incoming-policy-managed tables are rejected; those need dedicated migrations.

The adapter records a durable one-shot source/target plan and per-row repair tickets containing
the original `(op_ts, op_seq, site_id)` and only the newly added columns. Ticket enumeration stays
in SQL. Cursor zero, tickets and the plan ledger commit atomically. Repeating the same plan leaves
an in-progress cursor intact. Ordinary equal-version replay remains inert without a matching ticket.

For an exact-version ticket, replay updates only its listed fields. It does not rewrite known
columns, metadata or queued operations. A newer remote winner or local capture cancels tickets
for the previous version. Repair uses UPDATE, not INSERT, so it cannot resurrect a locally purged
row. Repair and ticket deletion join the response transaction; failed decoding/apply cannot ACK
the outbox or advance its cursor through `acceptResponse`.

Every requested field must be present; an explicit null is accepted only for a nullable column.
Missing fields leave the ticket pending. Hosts must check `pendingColumnRepairs()` after replay
and report incomplete recovery rather than success. An unavailable source requires explicit
recovery policy; this API does not silently clear tickets or repeatedly rewind forever.

Self-authored rows are excluded: the normal relay omits the requester's operations, and in a
forward upgrade that author's old writes did not contain these columns. Keep their deterministic
migration defaults and original outbox payloads. This does not qualify downgrade/restore/clone
histories in which a same-site row once had a richer shape, nor repair missing provenance.

This mechanism is implemented/tested in the working Kotlin adapter. It is not automatically
activated by an app registry change, and requires host migration/lifecycle integration before use.

## Server side: hash grace window

The server accepts a **configured set** of schema hashes — the current one plus a grace-window
predecessor — so that while devices straggle across an upgrade, both old- and new-registry
replicas can keep syncing. A request carrying an unaccepted hash gets `409 SchemaMismatch`. When
the rollout completes, the old hash is dropped from the accepted set.

Note this composes with compaction: a device doing a `cursor=0` re-pull after a schema bump reads
the (possibly compacted) log, which still reconstructs correct current state.

## Why this is the one genuinely-hard mechanism

Every other part of the system fails *loudly* (a 409, a transport error) or *safely* (LWW just
picks a winner). This is the one place a bug means **silent** data loss on one device. It gets
dedicated tests against mixed-hash replicas, and it must ship before any post-cutover registry
change in ForestNote/UB.
