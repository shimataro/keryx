package works.merc.keryx.app.platform

import io.requery.android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Android implementation, mirroring the desktop actual's structure exactly (same statements, same
 * tables/indexes dropped, same ordering) so the uploaded snapshot's shape does not depend on which
 * platform produced it. Opens two dedicated `io.requery.android.database.sqlite.SQLiteDatabase`
 * connections directly — like the desktop actual's raw JDBC connections, this bypasses the
 * SQLDelight driver, which here means `AndroidSqliteDriver`. [NoOpDatabaseErrorHandler] is
 * required on every connection opened this way — see its own KDoc for why the default handler is
 * unsafe.
 */
actual object DatabaseSnapshot {
    actual fun exportForUpload(localDbPath: String, destPath: String) {
        // VACUUM INTO refuses to write to an existing file.
        File(destPath).delete()

        SQLiteDatabase.openOrCreateDatabase(localDbPath, null, NoOpDatabaseErrorHandler).use { db ->
            db.setBusyTimeout()
            db.execSQL("VACUUM INTO '${destPath.replace("'", "''")}'")
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
        SQLiteDatabase.openOrCreateDatabase(destPath, null, NoOpDatabaseErrorHandler).use { db ->
            db.execSQL("DROP TABLE IF EXISTS articles_fts")
            db.execSQL("DROP TABLE IF EXISTS sync_state")
            db.execSQL("DROP INDEX IF EXISTS idx_articles_feed_id")
            db.execSQL("DROP INDEX IF EXISTS idx_articles_is_read")
            db.execSQL("DROP INDEX IF EXISTS idx_articles_is_starred")
            db.execSQL("DROP INDEX IF EXISTS idx_articles_published")
            db.execSQL("VACUUM")
        }
    }
}
