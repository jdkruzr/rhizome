package io.rhizome.core

/**
 * The §I.9 schema-evolution reconcile rule, mirroring the Go `schemaevo` package. It is the
 * canonical reference for a multi-master replica's response to a synced-schema-hash change; a host's
 * local store applies the same rule against its own cursor (ForestNote does this app-side in
 * `NotebookRepository.resetCursorIfSchemaChanged`, since its adapter holds no schema generation).
 */
object SchemaEvolution {

    /** The outcome of [reconcile]: the cursor to adopt and the schema hash to store. */
    data class Outcome(val cursor: Long, val storedHash: String)

    /**
     * Decide the §I.9 outcome for a replica whose stored synced-schema hash is [storedHash] (null =
     * never reconciled, e.g. first launch after a cutover) and whose current schema hash is
     * [currentHash]. When they differ, the cursor resets to 0 so the next session re-pulls the whole
     * relay log once and re-materializes every row under the new schema; otherwise the cursor is
     * unchanged. The stored hash always advances to [currentHash], so the reset fires AT MOST ONCE
     * per generation (the re-pull is idempotent under LWW).
     */
    fun reconcile(storedHash: String?, currentHash: String, cursor: Long): Outcome =
        if (storedHash == currentHash) Outcome(cursor, currentHash) else Outcome(0L, currentHash)
}
