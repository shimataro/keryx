package works.merc.keryx.app.data.local

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import works.merc.keryx.app.core.DB_FILE_NAME
import works.merc.keryx.app.core.SQLITE_BUSY_TIMEOUT_MS
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.platform.AppDirs
import works.merc.keryx.app.platform.FileIO
import works.merc.keryx.app.platform.RawSqliteConnection

/**
 * Apple driver: SQLDelight's [NativeSqliteDriver] over the system sqlite3 (FTS5 with the trigram
 * tokenizer and `VACUUM INTO` are both present from macOS 14 / iOS 17, SQLite 3.43), which drives
 * create/migrate off `PRAGMA user_version` itself. `busy_timeout` and `foreign_keys` apply to every
 * connection it opens, as on the other platforms.
 */
actual class DatabaseDriverFactory {
    actual fun create(): SqlDriver = createDriver(AppDirs.appDataDir())

    /**
     * Opens (creating or migrating) the database file [name] in [dir].
     *
     * @throws DatabaseTooNewException before touching the file if a newer build migrated it.
     */
    internal fun createDriver(dir: String, name: String = DB_FILE_NAME): SqlDriver {
        // Refused before the driver opens (and would migrate or stamp) the file — see
        // DatabaseSchemaGuard.kt.
        requireSupportedSchemaVersion(RawSqliteConnection.userVersionOf(FileIO.join(dir, name)), KeryxDatabase.Schema.version)
        return NativeSqliteDriver(
            schema = KeryxDatabase.Schema,
            name = name,
            onConfiguration = { config ->
                config.copy(
                    extendedConfig = config.extendedConfig.copy(
                        basePath = dir,
                        foreignKeyConstraints = true,
                        busyTimeout = SQLITE_BUSY_TIMEOUT_MS.toInt(),
                    ),
                )
            },
        ).also { driver ->
            // NativeSqliteDriver opens (and so creates/migrates) the file lazily, on its first
            // statement; open it now, as the other platforms' factories do, so the schema is in
            // place before anything else — FtsManager.ensureIndexed, a merge — reaches the file.
            driver.executeQuery(null, "SELECT 1", { QueryResult.Unit }, 0)
        }
    }
}
