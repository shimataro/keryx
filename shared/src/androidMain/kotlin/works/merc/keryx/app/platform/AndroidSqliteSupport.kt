package works.merc.keryx.app.platform

import io.requery.android.database.DatabaseErrorHandler
import io.requery.android.database.sqlite.SQLiteDatabase
import works.merc.keryx.app.core.SQLITE_BUSY_TIMEOUT_MS

/**
 * Small helpers shared by [DatabaseMerger] and [DatabaseSnapshot]'s Android actuals, which both
 * open a dedicated `io.requery.android.database.sqlite.SQLiteDatabase` connection directly
 * (bypassing the SQLDelight driver — see each class's own KDoc for why) rather than going through
 * `data/local/DatabaseDriverFactory`.
 */

/**
 * A [DatabaseErrorHandler] that does nothing.
 *
 * Confirmed by disassembling the bundled `sqlite-android` AAR: any `SQLiteDatabase` opened with a
 * `null` error handler (e.g. the 2-arg `openOrCreateDatabase(path, factory)` overload) gets a
 * `DefaultDatabaseErrorHandler` substituted in its place, and that handler's `onCorruption()`
 * **deletes the database file** once it is closed. That is the right behavior for the app's own
 * long-lived database (better to start fresh than serve corrupt data), but it is never the right
 * behavior here: [DatabaseMerger] runs against the downloaded cloud DB (a temp file whose deletion
 * would just make an ambiguous merge failure look like a successful no-op) and against the local
 * DB mid-merge (whose deletion would destroy the user's subscriptions and read state), and
 * [DatabaseSnapshot] runs against a throwaway upload copy. Every connection opened here must pass
 * this explicit no-op instead of relying on the 2-arg overload's default.
 */
internal object NoOpDatabaseErrorHandler : DatabaseErrorHandler {
    override fun onCorruption(dbObj: SQLiteDatabase) {
        // Deliberately empty — see the KDoc above for why the default (file deletion) is unsafe here.
    }
}

/**
 * Sets `busy_timeout` on this connection. Must go through [SQLiteDatabase.rawQuery] rather than
 * [SQLiteDatabase.execSQL]: `PRAGMA busy_timeout=N` returns the new timeout as a result row (per
 * SQLite's own docs), which requery's `execSQL` rejects at runtime ("Queries can be performed
 * using SQLiteDatabase query or rawQuery methods only") — the same constraint already documented
 * on `DatabaseDriverFactory.android.kt`'s `onConfigure`.
 */
internal fun SQLiteDatabase.setBusyTimeout(millis: Long = SQLITE_BUSY_TIMEOUT_MS) {
    rawQuery("PRAGMA busy_timeout=$millis;", null).use { }
}

/** Reads `PRAGMA user_version` from this connection. */
internal fun SQLiteDatabase.userVersion(): Long =
    rawQuery("PRAGMA user_version;", null).use { cursor ->
        if (cursor.moveToFirst()) cursor.getLong(0) else 0L
    }
