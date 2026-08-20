package works.merc.keryx.app.platform

import works.merc.keryx.app.core.SQLITE_BUSY_TIMEOUT_MS
import java.io.File
import java.sql.DriverManager

actual object DatabaseSnapshot {
    /**
     * Creates a consistent SQLite database snapshot for upload and removes the derived full-text index.
     *
     * @param localDbPath Path to the live SQLite database.
     * @param destPath Path where the snapshot should be written.
     */
    actual fun exportForUpload(localDbPath: String, destPath: String) {
        // VACUUM INTO refuses to write to an existing file.
        File(destPath).delete()

        // Consistent snapshot of the whole DB (preserves user_version). Concurrent writes on the
        // SQLDelight driver's connections wait via their busy_timeout while VACUUM INTO holds its
        // read transaction; busy_timeout here likewise lets VACUUM INTO wait out (rather than error
        // on) a mark-as-read write mid-commit (those run outside the sync mutex).
        DriverManager.getConnection("jdbc:sqlite:$localDbPath").use { conn ->
            conn.createStatement().use { st ->
                st.execute("PRAGMA busy_timeout=$SQLITE_BUSY_TIMEOUT_MS")
                st.execute("VACUUM INTO '${destPath.replace("'", "''")}'")
            }
        }

        // Exclude the derived FTS index from the uploaded file — on the copy, never the live DB.
        // Dropping the virtual table also removes its shadow tables (_data/_idx/_docsize/_config).
        // The four idx_articles_* indexes are also dropped: DatabaseMerger's merge SQL never looks
        // up rows on the attached `cloud.*` side by anything but its own NOT EXISTS/EXISTS guards
        // against `main.*`, so they serve no purpose in an uploaded snapshot.
        //
        // `sync_state` goes too. It is device-local bookkeeping (last_synced_at, the cloud file's
        // rev, the uploaded-snapshot digest), declared a non-sync table in db-schema.md, and it
        // appears in neither MergeSql nor MergeSchema.EXPECTED_SCHEMAS — so no receiving
        // device ever reads it out of this file. Removing it also makes the snapshot a pure
        // function of the synced data: last_synced_at is rewritten on every successful sync, so
        // leaving it in would change the bytes on every cycle and defeat SyncRepository's
        // "identical to what we last uploaded" check.
        //
        // DROP TABLE/INDEX alone does not shrink the file — the freed pages just join SQLite's
        // internal freelist — so a plain VACUUM follows to actually reclaim that space before the
        // bytes are read for upload. VACUUM (unlike VACUUM INTO) operates in place on this
        // already-created copy, and preserves PRAGMA user_version, so DatabaseMerger's schema check
        // on the receiving device is unaffected.
        DriverManager.getConnection("jdbc:sqlite:$destPath").use { conn ->
            conn.createStatement().use { st ->
                st.execute("DROP TABLE IF EXISTS articles_fts")
                st.execute("DROP TABLE IF EXISTS sync_state")
                st.execute("DROP INDEX IF EXISTS idx_articles_feed_id")
                st.execute("DROP INDEX IF EXISTS idx_articles_is_read")
                st.execute("DROP INDEX IF EXISTS idx_articles_is_starred")
                st.execute("DROP INDEX IF EXISTS idx_articles_published")
                st.execute("VACUUM")
            }
        }
    }
}
