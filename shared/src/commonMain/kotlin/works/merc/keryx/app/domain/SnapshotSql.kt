package works.merc.keryx.app.domain

/**
 * The SQL statements [works.merc.keryx.app.platform.DatabaseSnapshot.exportForUpload] runs against
 * the snapshot copy (never the live DB) after `VACUUM INTO` creates it. Kept as plain data in
 * `commonMain` — like [MergeSql] — so both platform actuals run byte-identical statements in the
 * same order without re-deriving (and risking drifting) the same list independently; only the
 * connection each actual opens the copy through differs (JDBC vs. requery's bundled SQLite).
 *
 * Exclude the derived FTS index from the uploaded file — on the copy, never the live DB. Dropping
 * the virtual table also removes its shadow tables (`_data`/`_idx`/`_docsize`/`_config`). The four
 * `idx_articles_*` indexes are also dropped: [DatabaseMerger]'s merge SQL never looks up rows on
 * the attached `cloud.*` side by anything but its own NOT EXISTS/EXISTS guards against `main.*`, so
 * they serve no purpose in an uploaded snapshot.
 *
 * `sync_state` goes too. It is device-local bookkeeping (`last_synced_at`, the cloud file's rev,
 * the uploaded-snapshot digest), declared a non-sync table in `db-schema.md`, and it appears in
 * neither [MergeSql] nor [MergeSchema.EXPECTED_SCHEMAS] — so no receiving device ever reads it out
 * of this file. Removing it also makes the snapshot a pure function of the synced data:
 * `last_synced_at` is rewritten on every successful sync, so leaving it in would change the
 * snapshot's bytes on every cycle and defeat [SyncRepository]'s "identical to what we last
 * uploaded" digest check.
 *
 * `DROP TABLE`/`DROP INDEX` alone does not shrink the file — the freed pages just join SQLite's
 * internal freelist — so a plain `VACUUM` follows to actually reclaim that space before the bytes
 * are read for upload. `VACUUM` (unlike `VACUUM INTO`) operates in place on this already-created
 * copy, and preserves `PRAGMA user_version`, so [DatabaseMerger]'s schema check on the receiving
 * device is unaffected.
 */
internal object SnapshotSql {

    /** `VACUUM INTO` refuses to write to an existing file, so callers must delete [destPath]'s
     *  file first. Single quotes in the path are escaped since this is interpolated directly into
     *  the SQL text (SQLite has no bind-parameter form for `VACUUM INTO`'s target). */
    fun vacuumIntoStatement(destPath: String): String =
        "VACUUM INTO '${destPath.replace("'", "''")}'"

    /**
     * The cleanup statements run against the snapshot copy, in order. [extraDropTables] are
     * platform-specific tables the copy's own connection leaves behind (e.g. Android's
     * `android_metadata`) — inserted after the shared DROP TABLEs and before the DROP INDEXes, so
     * their position never depends on which platform is running. The trailing `VACUUM` always
     * comes last, regardless of how many extra tables were dropped.
     */
    fun cleanupStatements(extraDropTables: List<String> = emptyList()): List<String> = buildList {
        add("DROP TABLE IF EXISTS articles_fts")
        add("DROP TABLE IF EXISTS sync_state")
        extraDropTables.forEach { add("DROP TABLE IF EXISTS $it") }
        add("DROP INDEX IF EXISTS idx_articles_feed_id")
        add("DROP INDEX IF EXISTS idx_articles_is_read")
        add("DROP INDEX IF EXISTS idx_articles_is_starred")
        add("DROP INDEX IF EXISTS idx_articles_published")
        add("VACUUM")
    }
}
