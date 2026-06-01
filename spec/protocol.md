# The protocol — `POST /sync/v1`

One **session** (`syncOnce`) is a full round-trip loop: repeat the POST while the server reports
`has_more`, draining the relay backlog. The engine contains no timers or backoff — scheduling is
the consumer's job; it reacts to the returned result.

## Request

```json
{
  "protocol_version": 1,
  "schema_hash": "<hex sha256 of the registry canonical>",
  "site_id": "<this replica's ULID>",
  "cursor": <int64, last global seq this replica has seen>,
  "ops": [ <WireOp>, … ]            // this replica's pending (unacked) ops
}
```

A **WireOp** is the snake_case wire form of an `Op`:
`{ "table","pk","site_id","op_seq","op_ts","cols": { … } }`. The client maps domain⇄wire at the
transport boundary so snake_case never leaks into storage. `cols` values are already wire-encoded
per the registry column types.

## Response

```json
{
  "protocol_version": 1,
  "accepted_through": <int64, this site's contiguous acked op_seq high-water>,
  "rejected": [ { "site_id","op_seq","reason" }, … ],
  "ops": [ <WireOp>, … ],          // ops authored by OTHER sites, after `cursor`
  "cursor": <int64, new authoritative cursor>,
  "has_more": <bool>
}
```

## Client handling (per round, on HTTP 200)

1. Surface `rejected` (callback); they are permanently settled.
2. `markAckedThrough(accepted_through)` — prune acked **and** quarantined (rejected) ops from the
   outbox. (`accepted_through` counts both applied and permanently-rejected ops as settled.)
3. If `ops` non-empty: `applyRelayed(ops)` — `normalize` + LWW `merge` into local storage, in one
   transaction; bump the HLC past every absorbed op.
4. Adopt `cursor` as authoritative — even if it rolled back.
5. If `has_more`, loop (pending ops / cursor now reflect the just-applied page); else `Success`.

## Envelope errors (never apply anything)

| HTTP | Result | Meaning |
|---|---|---|
| 401 | `AuthRequired` | credentials missing/invalid — stop, prompt |
| 409 | `SchemaMismatch` | server doesn't accept this `schema_hash` — needs a coordinated bump |
| 5xx / transport | `Retryable(reason)` | safe to retry the whole batch with backoff |
| 400 / 413 | `Failed(reason)` | non-retryable client error — surface, don't loop |

## Auth

Single-account HTTP Basic (optional bearer token), pluggable. No tenancy: one server instance
serves one user's devices. The client builds the auth header from `(serverUrl, username,
password)`; the endpoint is `<serverUrl>/sync/v1`.

## Server responsibilities (summary; see `compaction.md`, `schema-evolution.md`)

- **Relay log** — append each accepted op to a global ordered log keyed
  `(seq, site_id, op_seq, table, pk, op_ts, payload)`; `payload` is the opaque op JSON.
- **Sequencing** — assign the monotonic global `seq`; compute each site's contiguous
  `accepted_through`; serve `OpsSince(cursor, excludeSite, limit)` with `has_more`.
- **Schema-hash gate** — accept a configured set of hashes (current + grace window), else 409.
- **Optional mirror** — a materialized queryable copy of current state for apps that process
  content server-side; built from the *same registry* via generic dynamic UPSERT; **not on the
  sync path** (relay pulls read the log, never the mirror). Omit it for pure device sync.

The server **sequences but never adjudicates**: conflict resolution is the deterministic merge,
run identically by every replica. That is what lets the server stay a dumb relay.
