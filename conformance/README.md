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
- **`compaction`** *(planned, Phase 5)* — given an op log + per-site cursors, assert the post-sweep
  log (collapse-superseded; watermark-gated tombstone purge).
- **`schema-evolution`** *(planned, Phase 8)* — given a schema-hash change, assert the one-shot
  cursor reset to 0.

## Loader contract

A runner discovers `vectors/*.vector.json`, parses each, dispatches on `category`, and fails with
the vector `name` on mismatch. Unknown categories are skipped with a logged notice (so adding a
new category doesn't break an implementation that hasn't caught up yet — but CI for a release
requires every category to be handled on both sides).
