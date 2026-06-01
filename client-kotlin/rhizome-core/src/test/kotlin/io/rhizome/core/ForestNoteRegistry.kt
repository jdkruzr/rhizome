package io.rhizome.core

import io.rhizome.core.ColumnType.Blob
import io.rhizome.core.ColumnType.ColorInt
import io.rhizome.core.ColumnType.Int as IntCol
import io.rhizome.core.ColumnType.Text
import io.rhizome.core.ColumnType.Timestamp

/**
 * ForestNote's synced data shape, declared as a RhizomeSync [Registry]. This is the canonical
 * worked example AND the live-cutover guard: its [Registry.schemaHash] MUST equal ForestNote's
 * production v3 hash (see [SchemaHashTest]). At Phase 8 this declaration moves into ForestNote.
 *
 * Columns are the NON-pk columns (pk "id" rides in the op's pk field). Types are best-effort for
 * the later codec phase; only column NAMES affect the schema hash.
 */
object ForestNoteRegistry {

    private fun ts(name: String, nullable: Boolean = false) = ColumnDef(name, Timestamp, nullable)

    val registry = Registry(
        listOf(
            TableDef(
                name = "folder", pk = "id", tombstone = "deleted_at",
                columns = listOf(
                    ColumnDef("name", Text),
                    ColumnDef("sort_order", IntCol),
                    ts("created_at"),
                    ts("deleted_at", nullable = true),
                    ColumnDef("parent_folder_id", Text, nullable = true),
                ),
            ),
            TableDef(
                name = "notebook", pk = "id", tombstone = "deleted_at",
                columns = listOf(
                    ColumnDef("name", Text),
                    ColumnDef("sort_order", IntCol),
                    ts("created_at"),
                    ts("deleted_at", nullable = true),
                    ColumnDef("folder_id", Text, nullable = true),
                ),
            ),
            TableDef(
                name = "page", pk = "id", tombstone = "deleted_at",
                columns = listOf(
                    ColumnDef("notebook_id", Text),
                    ColumnDef("sort_order", IntCol),
                    ts("created_at"),
                    ts("deleted_at", nullable = true),
                    ColumnDef("template", Text, nullable = true),
                    ColumnDef("template_pitch_mm", IntCol, nullable = true),
                ),
            ),
            TableDef(
                name = "stroke", pk = "id", tombstone = "deleted_at",
                columns = listOf(
                    ColumnDef("page_id", Text),
                    ColumnDef("color", ColorInt),
                    ColumnDef("pen_width_min", IntCol),
                    ColumnDef("pen_width_max", IntCol),
                    ColumnDef("points", Blob),
                    ColumnDef("z", IntCol),
                    ts("created_at"),
                    ts("deleted_at", nullable = true),
                ),
            ),
            TableDef(
                name = "text_box", pk = "id", tombstone = "deleted_at",
                columns = listOf(
                    ColumnDef("page_id", Text),
                    ColumnDef("x", IntCol),
                    ColumnDef("y", IntCol),
                    ColumnDef("width", IntCol),
                    ColumnDef("height", IntCol),
                    ColumnDef("text", Text),
                    ColumnDef("font_name", Text),
                    ColumnDef("font_size", IntCol),
                    ColumnDef("color", ColorInt),
                    ColumnDef("weight", IntCol),
                    ColumnDef("border_width", IntCol),
                    ColumnDef("z", IntCol),
                    ts("created_at"),
                    ts("deleted_at", nullable = true),
                ),
            ),
            TableDef(
                name = "page_text_from_server", pk = "id", tombstone = "deleted_at",
                serverAuthoredOnly = true,
                columns = listOf(
                    ColumnDef("text", Text),
                    ts("ocr_at"),
                    ColumnDef("model", Text, nullable = true),
                    ts("created_at"),
                    ts("deleted_at", nullable = true),
                ),
            ),
            TableDef(
                name = "page_text_from_client", pk = "id", tombstone = "deleted_at",
                columns = listOf(
                    ColumnDef("text", Text),
                    ts("ocr_at"),
                    ColumnDef("model", Text, nullable = true),
                    ts("created_at"),
                    ts("deleted_at", nullable = true),
                ),
            ),
        ),
    )
}
