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

This is safe and cheap because the merge is idempotent LWW: re-applying already-seen ops is a
no-op, and ops for the newly-modeled tables/columns now materialize. Gate it like a run-once
generation bump (the mechanism ForestNote already uses for `SYNC_BACKFILL_VERSION`) so it fires
exactly on the transition, not on every launch.

```
on startup / enableSync:
  current = SCHEMA_HASH(registry)
  if stored_schema_hash != current:
     pull_cursor = 0                 // one-shot full re-pull
     stored_schema_hash = current
```

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
