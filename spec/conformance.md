# Conformance

The contract is defined by executable vectors in `/conformance/vectors/`, not by either
implementation. Both `client-kotlin` and `server-go` load and pass every vector. See
`/conformance/README.md` for the vector JSON format, the `category` dispatch, and the loader
contract; this file records the *policy*.

## Policy

- **Single source of truth.** A behavior in dispute is settled by adding or reading a vector, not
  by reading one side's code. Edit a vector once; both sides re-run it.
- **Both sides, every category, for a release.** A runner may skip an unknown `category` during
  development (so a not-yet-caught-up side still builds), but a tagged release requires every
  category handled and green on both Kotlin and Go in CI.
- **The hash guard.** A `schema-hash` vector pins ForestNote's registry to its live v3 hash
  `724411eb845ad3487393a77cb5559690e69332c35fdb5ee3e85c1767bf71f3fe`. This is the tripwire that
  protects the live cutover: if a refactor changes how the canonical string is built, this vector
  goes red before any device is affected.
- **No silent coverage gaps.** If a runner bounds work (e.g. skips a category), it logs what it
  skipped. Silent skips read as "covered" when they aren't.

## Current categories

| Category | Status | What it asserts |
|---|---|---|
| `merge` | **present** (23 vectors) | `normalize` + LWW `merge` → `expected_state` |
| `schema-hash` | planned (P1/P3) | registry → canonical string → `SCHEMA_HASH` (incl. the v3 guard) |
| `wire-codec` | planned (P1) | `decode(encode(v)) == v` + exact JSON per column type (ColorInt, Blob) |
| `hlc` | planned (P4) | stamp/bump/compare; mixed legacy-`wall_ts`/HLC; causal-inversion |
| `compaction` | planned (P5) | collapse-superseded; watermark tombstone purge; `cursor=0` rebuild |
| `schema-evolution` | planned (P8) | one-shot cursor reset on hash change |

## Provenance

The 23 `merge` vectors were migrated verbatim (values unchanged) from the ForestNote↔UltraBridge
`sync-vectors` suite, with the ordering field renamed `wall_ts`→`op_ts`. They already encode the
LWW total order (op_ts > op_seq > site_id), tombstone restore/re-delete, stroke union, shuffled
and duplicate ops, unknown-column dropping, multi-table, folder/notebook/page/text_box, and the
server-authored page-text round-trip.
