package io.rhizome.sqlite

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * Verbatim copy of ForestNote `core:format` `SyncWire`'s column encoders (the alphabetical
 * `buildJsonObject` blocks), used as the byte-parity ORACLE for [SqliteParityTest]. ForestNote's
 * `SyncWire` is `internal` in an Android module and cannot be depended on from this pure-JVM repo,
 * so the legacy format is pinned here. If [SqliteStorageAdapter]'s registry-driven encoder ever
 * diverges from this hand-written format, the parity test fails — which is the whole point.
 *
 * Source: ForestNote core/format/.../SyncWire.kt (wire schema v3, SCHEMA_HASH 724411eb…).
 */
object LegacySyncWire {

    fun folderCols(name: String, sortOrder: Long, createdAt: Long, deletedAt: Long?, parentFolderId: String?): String =
        buildJsonObject {
            put("created_at", createdAt)
            put("deleted_at", deletedAt)
            put("name", name)
            put("parent_folder_id", parentFolderId)
            put("sort_order", sortOrder)
        }.toString()

    fun strokeCols(
        pageId: String,
        color: Long,
        penWidthMin: Long,
        penWidthMax: Long,
        points: ByteArray,
        z: Long,
        createdAt: Long,
        deletedAt: Long?,
    ): String =
        buildJsonObject {
            put("color", color and 0xFFFFFFFFL) // signed ARGB Int (sign-extended Long) -> unsigned int64
            put("created_at", createdAt)
            put("deleted_at", deletedAt)
            put("page_id", pageId)
            put("pen_width_max", penWidthMax)
            put("pen_width_min", penWidthMin)
            put("points", Base64.getEncoder().encodeToString(points))
            put("z", z)
        }.toString()

    fun textBoxCols(
        pageId: String,
        x: Long,
        y: Long,
        width: Long,
        height: Long,
        text: String,
        fontName: String,
        fontSize: Long,
        color: Long,
        weight: Long,
        borderWidth: Long,
        z: Long,
        createdAt: Long,
        deletedAt: Long?,
    ): String =
        buildJsonObject {
            put("border_width", borderWidth)
            put("color", color and 0xFFFFFFFFL) // signed ARGB Int (sign-extended Long) -> unsigned int64
            put("created_at", createdAt)
            put("deleted_at", deletedAt)
            put("font_name", fontName)
            put("font_size", fontSize)
            put("height", height)
            put("page_id", pageId)
            put("text", text)
            put("weight", weight)
            put("width", width)
            put("x", x)
            put("y", y)
            put("z", z)
        }.toString()
}
