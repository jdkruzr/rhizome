# Conformance vectors

These language-neutral JSON vectors are the **single source of truth** for the RhizomeSync
contract. Both `client-kotlin` and `server-go` load and execute every vector; a vector is edited
once, here, and both sides re-run it. Drift between the two implementations is the historical
failure mode this directory exists to prevent.

## Field naming note (`op_ts`)

These vectors were migrated from the ForestNote↔UltraBridge `sync-vectors`, where the ordering
timestamp field was named `wall_ts`. RhizomeSync renames it to **`op_ts`** (it now carries a
Hybrid Logical Clock — see `spec/hlc.md`). The rename is purely nominal for the merge vectors:
the merge treats `op_ts` as an opaque int64 and compares it the same way, so the migrated values
are unchanged. (A vector named e.g. `lww-wall-ts` keeps its historical name but uses `op_ts`.)

## Vector format

Each `*.vector.json` has a top-level `category` selecting how a runner interprets it:

```json
{
  "category": "merge",
  "name": "lww-op-ts",
  "description": "human-readable intent",
  "ops": [ { "table","pk","site_id","op_seq","op_ts","cols": {…} }, … ],
  "expected_state": { "<table>": [ { "pk","site_id","op_seq","op_ts","cols": {…} }, … ], … }
}
```

### Categories

- **`merge`** — feed `ops` through `normalize` + `merge` (row-level LWW). The surviving op per
  `(table, pk)` must equal `expected_state[table]` (compared as a set keyed by `pk`). Tables that
  appear as empty arrays in `expected_state` assert "no surviving rows." This is the bulk of the
  suite today (the 23 migrated vectors).
- **`hlc`** *(asserted both sides)* — millisecond-unit HLC stamping (spec/hlc.md). A single clock
  seeded at `initial` processes an ordered `steps` list; each step sets the injected wall clock to
  `wall`, runs `op` (`"local"` or `"receive"` with a `remote` timestamp), and must yield `expect`:
  ```json
  { "category": "hlc", "name": "…", "initial": 0,
    "steps": [ { "op": "local",   "wall": 1000, "expect": 1000 },
               { "op": "receive", "wall": 1000, "remote": 5000, "expect": 5001 } ] }
  ```
  Covers wall-tracking, same-ms ticking, receive-jumps-past-remote, backward-clock monotonicity,
  and legacy raw-`wall_ts` interop.
- **`wire-codec`** *(asserted both sides)* — per column type, assert `encode(decode(wire)) == wire`
  via a `cases` list of `{ "type", "wire" }` (notably `ColorInt` unsigned↔signed and `Blob` base64).
- **`schema-hash`** *(asserted as guard tests, not vectors)* — each side has a registry guard test
  reproducing ForestNote's live v3 hash `724411eb…` and the canonical string (not a JSON vector).
- **`compaction`** *(asserted in Go; deferred in Kotlin — compaction is server-only)* — given a
  sequenced op `log`, a `tombstone_cols` map (`table` → tombstone column), and a `watermark`, assert
  the surviving entries equal `expected_log` exactly (same seqs, in order, matching op identity and
  cols). Covers rule 1 collapse-superseded (winner kept at its original seq, no renumber), rule 2
  watermark-gated tombstone purge (kept above the watermark = no-zombie; reclaimed at/below it), and
  the mix of all rules. Shape:
  ```json
  { "category": "compaction", "name": "…",
    "tombstone_cols": { "notebook": "deleted_at" }, "watermark": 4,
    "log":          [ { "seq": 1, "op": { "table","pk","site_id","op_seq","op_ts","cols": {…} } }, … ],
    "expected_log": [ { "seq": 4, "op": { … } }, … ] }
  ```
- **`schema-evolution`** *(asserted both sides)* — the §I.9 reconcile rule (`schemaevo.Reconcile` /
  `SchemaEvolution.reconcile`): given a stored vs current synced-schema hash and a cursor, assert the
  resulting cursor (reset to 0 on a change — incl. a null/empty stored marker, the post-cutover case —
  else unchanged) and that the stored hash always advances to current (so the reset fires at most
  once). The cursor reset itself is applied app-side against the host's own store (the adapter holds
  no schema generation); this category pins the rule both implementations agree on. Shape:
  ```json
  { "category": "schema-evolution", "name": "…",
    "stored_hash": "…" | null, "current_hash": "…", "cursor": 42,
    "expected_cursor": 0, "expected_stored_hash": "…" }
  ```

## Loader contract

A proposed, unimplemented capability belongs in `pending/`, not `vectors/`. The
[assets-v1 pending catalog](pending/assets-v1.json) is a Stage 1 acceptance definition only;
current Kotlin/Go runners do not execute it. See [pending policy](pending/README.md). A successful
fixture-integrity check must never be reported as behavioral conformance.

A runner discovers `vectors/*.vector.json`, parses each, dispatches on `category`, and fails with
the vector `name` on mismatch. Unknown categories are skipped with a logged notice (so adding a
new category doesn't break an implementation that hasn't caught up yet — but CI for a release
requires every category to be handled on both sides).
