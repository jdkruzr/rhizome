package io.rhizome.example

import io.rhizome.core.ColumnDef
import io.rhizome.core.ColumnType
import io.rhizome.core.Registry
import io.rhizome.core.TableDef

/**
 * The Kotlin half of the shared toy schema (examples/toy-schema/README.md): a `note` and a `tag`,
 * each keyed by a ULID `id` with a `deleted_at` tombstone. Its [Registry.schemaHash] MUST equal the
 * Go server's — that agreement is what lets the two sides sync (asserted in ToyRegistryTest).
 */
fun toyRegistry(): Registry = Registry(
    listOf(
        TableDef(
            name = "note", pk = "id", tombstone = "deleted_at",
            columns = listOf(
                ColumnDef("title", ColumnType.Text),
                ColumnDef("body", ColumnType.Text),
                ColumnDef("created_at", ColumnType.Timestamp),
                ColumnDef("deleted_at", ColumnType.Timestamp, nullable = true),
            ),
        ),
        TableDef(
            name = "tag", pk = "id", tombstone = "deleted_at",
            columns = listOf(
                ColumnDef("note_id", ColumnType.Text),
                ColumnDef("label", ColumnType.Text),
                ColumnDef("created_at", ColumnType.Timestamp),
                ColumnDef("deleted_at", ColumnType.Timestamp, nullable = true),
            ),
        ),
    ),
)

/** The data-table DDL the host app owns (the adapter owns only its `rhizome_*` bookkeeping tables). */
val TOY_DATA_DDL = listOf(
    "CREATE TABLE IF NOT EXISTS note (id TEXT PRIMARY KEY, title TEXT, body TEXT, created_at INTEGER, deleted_at INTEGER)",
    "CREATE TABLE IF NOT EXISTS tag (id TEXT PRIMARY KEY, note_id TEXT, label TEXT, created_at INTEGER, deleted_at INTEGER)",
)
