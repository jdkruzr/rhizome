# The merge rule (row-level Last-Writer-Wins)

The merge is deterministic, side-effect-free, and **independent of arrival order**. Every replica
that has seen the same set of ops converges to the same state without any coordination. It MUST be
implemented identically in every language; the `merge`-category conformance vectors are the
executable definition.

## The op

```
Op { table, pk, site_id, op_seq, op_ts, cols: Map<String, JsonValue> }
```

Identity is `(site_id, op_seq)`, globally unique. `cols` is the full row snapshot, already
wire-encoded per the registry column types.

## The total order

For two ops `a`, `b`, define `less(a, b)` lexicographically:

```
less(a, b):
  if a.op_ts  != b.op_ts  : return a.op_ts  < b.op_ts
  if a.op_seq != b.op_seq : return a.op_seq < b.op_seq
  return a.site_id < b.site_id          // compared as ASCII/ULID string
```

`op_ts` is the primary key (a Hybrid Logical Clock int64 — see `hlc.md`). `op_seq` and `site_id`
are deterministic tie-breakers so the order is total even for identical timestamps.

## normalize

```
normalize(op):
  keep only the cols whose key is in knownCols[op.table]   // drop unknown columns (forward-compat)
  (leave table, pk, site_id, op_seq, op_ts unchanged)
```

A replica that doesn't know a column drops it; a replica that doesn't know a table drops the op
entirely on apply. This is what makes mixed-version operation safe at the merge layer (see
`schema-evolution.md` for the cursor-side completion of that story).

## merge

```
merge(ops):
  winners = {}                      // keyed by (table, pk)
  for op in ops:
    n = normalize(op)
    k = (n.table, n.pk)
    if k not in winners or less(winners[k], n): winners[k] = n
  return winners
```

The winner for a `(table, pk)` is the op with the greatest key. Applying the same op twice is a
no-op; re-pulling already-seen ops is therefore safe and idempotent (this property is what lets
the schema-evolution cursor-reset and a fresh `cursor=0` join both work).

## Tombstones

Deletion is not special-cased here. A delete is an ordinary op whose registry-designated tombstone
column (`deleted_at`) is non-null. It competes under the same total order as any other op — so a
later edit can resurrect a row and a later delete can re-bury it, purely by timestamp. "Live"
reads filter `deleted_at IS NULL` above this layer.
