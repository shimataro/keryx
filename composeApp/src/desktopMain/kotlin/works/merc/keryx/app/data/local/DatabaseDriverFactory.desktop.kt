package works.merc.keryx.app.data.local

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import works.merc.keryx.app.core.DB_FILE_NAME
import works.merc.keryx.app.core.SQLITE_BUSY_TIMEOUT_MS
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.platform.AppDirs
import java.io.File
import java.util.Properties

actual class DatabaseDriverFactory {
    /**
     * Creates and configures the SQLite database driver.
     *
     * @return The configured and migrated SQLDelight database driver.
     */
    actual fun create(): SqlDriver {
        val dbFile = File(AppDirs.appDataDir(), DB_FILE_NAME)
        // busy_timeout goes through connection properties, not a one-off PRAGMA: this JVM driver opens
        // a fresh connection per statement for file DBs, so it must apply to every connection. It lets
        // a search wait out (rather than error on SQLITE_BUSY -> zero hits) the brief write lock held
        // by an incremental FTS insert or the rare full index rebuild on another connection.
        val props = Properties().apply { setProperty("busy_timeout", SQLITE_BUSY_TIMEOUT_MS.toString()) }
        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}", props)
        // Foreign keys are off by default in SQLite; the schema declares them.
        driver.execute(null, "PRAGMA foreign_keys=ON;", 0)
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
