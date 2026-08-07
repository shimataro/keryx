package works.merc.keryx.app.data.local

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import works.merc.keryx.app.core.DB_FILE_NAME
import works.merc.keryx.app.core.SQLITE_BUSY_TIMEOUT_MS
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.platform.AppDirs
import java.io.File
import java.util.Properties

/**
 * Connection properties applied to *every* connection the driver opens.
 *
 * This JVM driver opens a fresh connection per statement for file DBs (SQLDelight's
 * `ThreadedConnectionManager` closes it again outside a transaction), so a PRAGMA issued once via
 * `driver.execute` is gone by the next statement. Anything that must hold for all statements has to
 * go here, where sqlite-jdbc replays it on each new connection.
 *
 * - `busy_timeout` lets a search wait out (rather than error on SQLITE_BUSY -> zero hits) the brief
 *   write lock held by an incremental FTS insert or the rare full index rebuild on another connection.
 * - `foreign_keys` is off by default in SQLite; the schema declares them.
 */
internal fun sqliteConnectionProperties(): Properties = Properties().apply {
    setProperty("busy_timeout", SQLITE_BUSY_TIMEOUT_MS.toString())
    setProperty("foreign_keys", "true")
}

actual class DatabaseDriverFactory {
    /**
     * Creates and configures the SQLite database driver.
     *
     * @return The configured and migrated SQLDelight database driver.
     */
    actual fun create(): SqlDriver {
        val dbFile = File(AppDirs.appDataDir(), DB_FILE_NAME)
        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}", sqliteConnectionProperties())
        migrateIfNeeded(driver)
        return driver
    }

    /**
     * SQLDelight's JVM driver does not track the schema version automatically,
     * so we drive create/migrate manually off `PRAGMA user_version`.
     */
    private fun migrateIfNeeded(driver: SqlDriver) {
        val schema = KeryxDatabase.Schema
        val current = currentVersion(driver)
        val target = schema.version
        when {
            current == 0L -> {
                schema.create(driver)
                setVersion(driver, target)
            }
            current < target -> {
                schema.migrate(driver, current, target)
                setVersion(driver, target)
            }
        }
    }

    private fun currentVersion(driver: SqlDriver): Long =
        driver.executeQuery(
            identifier = null,
            sql = "PRAGMA user_version;",
            mapper = { cursor ->
                app.cash.sqldelight.db.QueryResult.Value(
                    if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L,
                )
            },
            parameters = 0,
        ).value

    private fun setVersion(driver: SqlDriver, version: Long) {
        driver.execute(null, "PRAGMA user_version=$version;", 0)
    }
}
