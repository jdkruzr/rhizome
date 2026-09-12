package io.rhizome.sqlite

import io.rhizome.core.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Bounded reader over a HOST-OWNED table/view with asset_id and byte_length.
 * The host supplies one row per required immutable asset (including retained
 * trash), excludes unpublished imports, and indexes underlying IDs. No schema
 * is invented or migrated here; constructing this adapter does not enable sync.
 */
class SqliteAssetReferences(
    private val db: SqliteHandle,
    private val dispatcher: CoroutineDispatcher,
    private val relation: String,
) : AssetReferenceProvider {
    init { require(Regex("[A-Za-z_][A-Za-z0-9_]*").matches(relation)) }

    override suspend fun pageRequiredAssets(after: String?, limit: Int): RequiredAssetPage = withContext(dispatcher) {
        require(limit in 1..256 && (after == null || isAssetDigest(after)))
        val descriptors = db.query("SELECT asset_id,byte_length FROM \"$relation\" WHERE asset_id>? ORDER BY asset_id LIMIT ?",
            listOf(after ?: "", limit.toLong() + 1)) { r ->
            AssetDescriptor(r.getString("asset_id")!!, r.getLong("byte_length")!!)
        }
        var previous = after ?: ""
        for (d in descriptors) {
            assetCheck(d.id > previous, 409, "duplicate_asset_reference")
            previous = d.id
        }
        RequiredAssetPage(descriptors.take(limit), descriptors.size > limit)
    }
}
