package works.merc.keryx.app.platform

import io.requery.android.database.sqlite.SQLiteDatabase
import works.merc.keryx.app.domain.SnapshotSql
import java.io.File

/**
 * Android implementation, mirroring the desktop actual's structure exactly (same statements, same
 * tables/indexes dropped, same ordering — both run [SnapshotSql]'s shared statement list) so the
 * uploaded snapshot's shape does not depend on which platform produced it. Opens two dedicated
 * `io.requery.android.database.sqlite.SQLiteDatabase` connections directly — like the desktop
 * actual's raw JDBC connections, this bypasses the SQLDelight driver, which here means
 * `AndroidSqliteDriver`. [NoOpDatabaseErrorHandler] is required on every connection opened this
 * way — see its own KDoc for why the default handler is unsafe.
 */
actual object DatabaseSnapshot {
    actual fun exportForUpload(localDbPath: String, destPath: String) {
        // VACUUM INTO refuses to write to an existing file.
        File(destPath).delete()

        SQLiteDatabase.openOrCreateDatabase(localDbPath, null, NoOpDatabaseErrorHandler).use { db ->
            db.setBusyTimeout()
            db.execSQL(SnapshotSql.vacuumIntoStatement(destPath))
        }

        // See SnapshotSql's own KDoc for why each of the shared statements is dropped/run, and in
        // this order. android_metadata is this platform's own addition, passed as an extra drop
        // table so it lands in the same position (after sync_state, before the idx_articles_*
        // indexes) regardless of which platform produced the snapshot:
        //
        // requery's SQLiteConnection.setLocaleFromConfiguration() creates this table (confirmed
        // by disassembling the AAR) the moment any non-read-only connection opens the file — the
        // very "open ... db ->" line above already did, on both this copy and the live DB the
        // VACUUM INTO read from. It is never read by this app (no query anywhere selects from
        // it) and desktop's actual has no equivalent, so leaving it in would make the uploaded
        // snapshot's shape depend on which platform produced it, contradicting this file's own
        // KDoc — and since it stores the device's locale, merely changing the Android system
        // language would otherwise change the snapshot's bytes with no data actually changed,
        // defeating SyncRepository's "identical to what we last uploaded" digest check.
        SQLiteDatabase.openOrCreateDatabase(destPath, null, NoOpDatabaseErrorHandler).use { db ->
            SnapshotSql.cleanupStatements(extraDropTables = listOf("android_metadata")).forEach { db.execSQL(it) }
        }
    }
}
