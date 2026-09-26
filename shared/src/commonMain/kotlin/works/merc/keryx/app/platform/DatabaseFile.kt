package works.merc.keryx.app.platform

/**
 * Resolves the absolute path of the live `keryx.db` file — the file [DatabaseMerger] and
 * [DatabaseSnapshot] operate on directly (both work by path, not through the SQLDelight driver;
 * see their own KDoc for why).
 *
 * This is deliberately not [AppDirs.appDataDir] plus [works.merc.keryx.app.core.DB_FILE_NAME]:
 * that composition is desktop's actual value (the JDBC driver opens exactly that path), but on
 * Android the SQLDelight `AndroidSqliteDriver` hands the file's placement to the OS
 * (`Context.getDatabasePath`), which resolves to `<dataDir>/databases/keryx.db` — a different
 * directory than `AppDirs.appDataDir()` (`Context.filesDir`, i.e. `<dataDir>/files`). Composing
 * the two paths independently is exactly the bug this `expect` exists to prevent: before it
 * existed, `SyncRepository`'s default `localDbPath` silently pointed at a file the driver had
 * never created, so a merge/snapshot would have operated on a nonexistent path.
 */
expect fun databaseFilePath(): String
