package works.merc.keryx.app

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import works.merc.keryx.app.data.local.db.KeryxDatabase
import java.io.File
import java.util.UUID

/** The instrumentation target app's real [Context] — the same one production code resolves via `AndroidAppContext`. */
internal fun testContext(): Context = InstrumentationRegistry.getInstrumentation().targetContext

/**
 * Creates a fresh, schema-installed SQLite file at a unique path under the same directory
 * production's `AndroidSqliteDriver`-backed local DB lives in (`Context.getDatabasePath`,
 * per-name), then closes the driver so the returned [File] has no open connection — exactly the
 * state `DatabaseMerger`/`DatabaseSnapshot` expect to find a "local" or "cloud" DB file in, since
 * both always open their own dedicated connection by path (see their own KDoc for why).
 *
 * Schema installation goes through the *real* `AndroidSqliteDriver` (bundled `requery` SQLite,
 * `RequerySQLiteOpenHelperFactory` — the same factory `data/local/DatabaseDriverFactory.android.kt`
 * uses) rather than calling `KeryxDatabase.Schema.create(driver)` directly: `AndroidSqliteDriver`'s
 * constructor already wires `Schema.create`/`migrate` into the underlying
 * `SupportSQLiteOpenHelper`'s `onCreate`/`onUpgrade` callbacks, which fire the first time the
 * lazily-opened database is actually touched — calling `Schema.create` a second time on the same
 * driver would then fail with "table already exists". [forceOpen] is what triggers that first touch
 * deterministically, rather than leaving it to whichever caller happens to run first.
 */
internal fun createSchemaDbFile(): File {
    val name = "keryx-devicetest-${UUID.randomUUID()}.db"
    val driver = AndroidSqliteDriver(
        schema = KeryxDatabase.Schema,
        context = testContext(),
        name = name,
        factory = RequerySQLiteOpenHelperFactory(),
    )
    try {
        forceOpen(driver)
    } finally {
        driver.close()
    }
    return testContext().getDatabasePath(name)
}

/** Runs a harmless statement that forces the driver's lazily-opened connection to actually open. */
private fun forceOpen(driver: AndroidSqliteDriver) {
    driver.execute(null, "PRAGMA foreign_keys=ON;", 0)
}

/** Deletes [this] and its `-journal`/`-wal`/`-shm` siblings, ignoring failures — test cleanup only. */
internal fun File.deleteDbFiles() {
    for (suffix in listOf("", "-journal", "-wal", "-shm")) {
        File(path + suffix).delete()
    }
}
