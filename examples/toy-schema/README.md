# Toy schema — `note` + `tag`

The two-table data shape both example apps sync. It is deliberately tiny: a flat note with a
title/body, and a tag that points at a note. It exercises everything the contract cares about —
two tables, a foreign-key-shaped reference, timestamps, and soft-delete tombstones — without any
app-specific column types (no `Blob`/`ColorInt`; those live in ForestNote's real registry).

This directory is the **shared definition**: the Go server (`examples/example-server-go`) and the
Kotlin client (`examples/example-client-kotlin`) each declare a registry from the table below, and
a guard test on each side asserts it reproduces the schema hash here. Equal hashes are the whole
point — they are what lets the two independently-written implementations agree that they are
syncing the same shape (a mismatch makes the server answer `409`, per `spec/protocol.md`).

## Tables

Both tables use a client-minted ULID `id` as the primary key (the `id` rides in the op's `pk`
field, never in `cols`) and `deleted_at` as the tombstone column (non-null = soft-deleted).

| table  | pk   | tombstone    | columns (non-pk)                                  |
|--------|------|--------------|---------------------------------------------------|
| `note` | `id` | `deleted_at` | `title` Text, `body` Text, `created_at` Timestamp, `deleted_at` Timestamp? |
| `tag`  | `id` | `deleted_at` | `note_id` Text, `label` Text, `created_at` Timestamp, `deleted_at` Timestamp? |

## Canonical string & schema hash

Per `spec/schema-registry.md`, the canonical string sorts tables alphabetically and columns
alphabetically within each table, as `table:col,col,…` joined by `;`:

```
note:body,created_at,deleted_at,title;tag:created_at,deleted_at,label,note_id
```

```
schema_hash = sha256(canonical)
            = 099b9cbab8ce15f934ccf27e7638af84a84cd13d2c9cdf8df840cf98307b4ff9
```
