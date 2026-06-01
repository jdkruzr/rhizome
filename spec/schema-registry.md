# Schema registry

The registry is RhizomeSync's core abstraction: **one declarative description of your synced
data shape**, the single source of truth from which the wire codec, merge `knownCols`, capture,
apply, backfill, and the schema-hash are all derived — identically on the client and the server.

## Why

Without it, the synced shape is hand-encoded in many places per table (a wire encoder, a wire
decoder, a capture dispatch, an apply dispatch, a `knownCols` entry, SQL queries — on *each*
side). That is the coupling that makes sync hard to reuse and easy to break. The registry
collapses all of it to one declaration.

## A table descriptor

```
table "stroke" {
  pk        = "id"             // primary key column; client-minted ULID (TEXT)
  tombstone = "deleted_at"     // nullable Timestamp column; non-null = soft-deleted
  serverAuthoredOnly = false   // if true: clients decode/apply but never capture (single-writer)

  column "page_id"       Text       notNull
  column "color"         ColorInt   notNull
  column "points"        Blob       notNull
  column "z"             Int        notNull
  column "pen_width_min" Int        notNull
  column "pen_width_max" Int        notNull
  column "created_at"    Timestamp  notNull
  column "deleted_at"    Timestamp  null
}
```

A registry is an **ordered set of table descriptors**. Order does not affect the schema hash
(tables are sorted for canonicalization) but is used for deterministic backfill.

## Column types

Each column has a type carrying (a) a **wire codec** (`encode(value) -> JSON`,
`decode(JSON) -> value`) and (b) a **SQLite affinity** (for the optional server mirror and the
client adapter's dynamic SQL). v1 ships:

| Type | Wire form | Notes |
|---|---|---|
| `Text` | JSON string / null | |
| `Int` | JSON number (int64) / null | |
| `Real` | JSON number / null | |
| `Bool` | JSON bool / null | |
| `Timestamp` | JSON number (int64 ms) / null | for `op_ts` it carries an HLC; data columns are plain ms |
| `Blob` | JSON string (standard padded base64) / null | |
| `ColorInt` | JSON number (unsigned int64) / null | signed ARGB Int ⇄ `value and 0xFFFFFFFF`; decode sign-extends the low 32 bits back to the stored signed value |

Apps may register **custom column types** by supplying the codec + affinity.

## What is derived

- **`knownCols`** — for each table, its column names sorted alphabetically (tables sorted
  alphabetically too). Drives `normalize` (drop unknown columns) and the schema hash.
- **`SCHEMA_HASH`** — `sha256(canonical)`, where
  `canonical = join(";", for each table in alpha order: table + ":" + join(",", cols in alpha order))`.
  This is **byte-identical to UltraBridge's `canonicalSchema()`**, so declaring ForestNote's seven
  tables reproduces its live v3 hash `724411eb845ad3487393a77cb5559690e69332c35fdb5ee3e85c1767bf71f3fe`.
  A conformance vector guards this.
- **Wire codec** — generic encode/decode over the column types (replaces hand-written per-table
  encoders/decoders).
- **Capture** — read a row as a `Map<String, value>`, encode via the registry, enqueue an op.
- **Apply** — a generic dynamic `INSERT … ON CONFLICT(pk) DO UPDATE SET …` built from the column
  list (see `spec/protocol.md` §server and the Kotlin `SqliteStorageAdapter`).
- **Backfill** — iterate declared tables in order, enqueue an op per existing PK.

## `serverAuthoredOnly`

A table flagged `serverAuthoredOnly` is decoded and applied by clients but **never captured** by
them — the structural guarantee that only the server authors those rows (e.g. server-side OCR
output). It still participates in the schema hash (its shape is part of the contract).
