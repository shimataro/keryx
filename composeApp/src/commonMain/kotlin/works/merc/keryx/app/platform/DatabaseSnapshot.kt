package works.merc.keryx.app.platform

/**
 * Produces the SQLite file that gets uploaded to the cloud: a consistent copy of
 * the local DB with the `articles_fts` FTS5 index excluded.
 *
 * The index is derived data and must not be synced, but it must NOT be dropped
 * from the *live* database — doing so opens a window where a concurrent search
 * hits `no such table: articles_fts`. So the exclusion happens on a throwaway
 * copy instead: `VACUUM INTO` makes a transactionally consistent snapshot (a raw
 * file copy could tear under the mark-as-read writes that run outside the sync
 * mutex) that preserves `PRAGMA user_version`, and the copy — never the live DB —
 * has `articles_fts` dropped before upload.
 *
 * Like [DatabaseMerger], this uses a dedicated raw connection rather than the
 * SQLDelight driver.
 */
expect object DatabaseSnapshot {
    /**
     * Writes a consistent copy of the DB at [localDbPath] to [destPath] via
     * `VACUUM INTO`, then drops `articles_fts` (and its shadow tables) from the
     * copy. [destPath] must not already exist. Throws on SQL/IO error.
     */
    fun exportForUpload(localDbPath: String, destPath: String)
}
