package works.merc.keryx.app.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import works.merc.keryx.app.core.DB_FILE_NAME
import works.merc.keryx.app.core.SQLITE_BUSY_TIMEOUT_MS
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.platform.AndroidAppContext

/**
 * Android implementation of [DatabaseDriverFactory].
 *
 * Uses the bundled SQLite from `com.github.requery:sqlite-android` — AOSP's own SQLite build
 * omits FTS5 entirely, so `articles_fts`'s `tokenize='trigram'` cannot work against the device's
 * system SQLite at any API level. See `.claude/rules/android-sqlite-bundling.md` for the full
 * rationale and the (currently unmet) exit criteria for dropping this dependency. Confining the
 * [RequerySQLiteOpenHelperFactory] choice to this single call site is what that rule requires: the
 * eventual switch to `androidx.sqlite:sqlite-framework`'s `FrameworkSQLiteOpenHelperFactory` is a
 * one-line change here, with no other file (`DatabaseMerger`, `DatabaseSnapshot`, `FtsManager`)
 * caring which factory produced the [SqlDriver].
 *
 * Unlike the desktop actual, this does not drive `KeryxDatabase.Schema.create`/`migrate` manually
 * off `PRAGMA user_version` — [AndroidSqliteDriver] already does that in its `onCreate`/`onUpgrade`
 * callbacks, the way a normal `SQLiteOpenHelper` would.
 */
actual class DatabaseDriverFactory {
    actual fun create(): SqlDriver {
        val callback = object : AndroidSqliteDriver.Callback(KeryxDatabase.Schema) {
            // Desktop's JDBC driver reapplies these on every connection it opens (see
            // sqliteConnectionProperties() in the desktop actual) because its ThreadedConnectionManager
            // closes and reopens connections between statements. AndroidSqliteDriver holds one
            // long-lived SupportSQLiteDatabase, so onConfigure — called once per connection open,
            // before onCreate/onUpgrade — is the equivalent hook here.
            override fun onConfigure(db: SupportSQLiteDatabase) {
                super.onConfigure(db)
                db.setForeignKeyConstraintsEnabled(true)
                // PRAGMA busy_timeout returns the new timeout as a result row (per SQLite's own
                // docs), so requery's execSQL rejects it ("Queries can be performed using
                // SQLiteDatabase query or rawQuery methods only") — confirmed on-device. query()
                // is the same rawQuery-style path db.execSQL uses for a plain PRAGMA read.
                db.query("PRAGMA busy_timeout=$SQLITE_BUSY_TIMEOUT_MS;").use { }
            }
        }
        return AndroidSqliteDriver(
            schema = KeryxDatabase.Schema,
            context = AndroidAppContext.application,
            name = DB_FILE_NAME,
            factory = RequerySQLiteOpenHelperFactory(),
            callback = callback,
        )
    }
}
