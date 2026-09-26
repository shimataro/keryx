package works.merc.keryx.app.platform

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.JournalMode
import co.touchlab.sqliter.interop.SQLiteExceptionErrorCode
import works.merc.keryx.app.core.CloudDataIncompatibleException
import works.merc.keryx.app.core.Log
import works.merc.keryx.app.core.SQLITE_BUSY_TIMEOUT_MS
import works.merc.keryx.app.core.SchemaVersionException
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.domain.MergeFailureClassifier
import works.merc.keryx.app.domain.MergeSchema
import works.merc.keryx.app.domain.SqliteFailureCategory

/**
 * Apple ATTACH-DATABASE merge: the same steps as the desktop and Android actuals (see
 * `.claude/rules/sync-merge.md`), on one dedicated [RawSqliteConnection] so the `ATTACH` survives to
 * every merge statement.
 */
actual object DatabaseMerger {
    actual fun merge(
        localDbPath: String,
        cloudDbPath: String,
        localSchemaVersion: Long,
        mergeStatements: List<String>,
    ) {
        try {
            mergeUnclassified(localDbPath, cloudDbPath, localSchemaVersion, mergeStatements)
        } catch (e: SchemaVersionException) {
            // Already classified — must not fall into the catch-all below (it needs an app
            // update, not a cloud-data reset).
            throw e
        } catch (e: Throwable) {
            throw classifyMergeFailure(e, cloudDbPath, localSchemaVersion)
        }
    }

    private fun mergeUnclassified(
        localDbPath: String,
        cloudDbPath: String,
        localSchemaVersion: Long,
        mergeStatements: List<String>,
    ) {
        migrateCloudIfOlder(cloudDbPath, localSchemaVersion)

        RawSqliteConnection.open(localDbPath).use { db ->
            db.exec("PRAGMA foreign_keys=ON")
            db.busyTimeout(SQLITE_BUSY_TIMEOUT_MS)
            db.exec("ATTACH DATABASE '${cloudDbPath.replace("'", "''")}' AS cloud")
            try {
                val cloudVersion = db.queryLong("PRAGMA cloud.user_version")
                if (cloudVersion > localSchemaVersion) {
                    throw SchemaVersionException(localVersion = localSchemaVersion, cloudVersion = cloudVersion)
                }
                db.exec("BEGIN")
                try {
                    for (sql in mergeStatements) db.exec(sql)
                    db.exec("COMMIT")
                } catch (e: Throwable) {
                    runCatching { db.exec("ROLLBACK") }
                    throw e
                }
            } finally {
                runCatching { db.exec("DETACH DATABASE cloud") }
            }
        }
    }

    /**
     * Classifies a merge failure as a permanently-unusable cloud DB
     * ([CloudDataIncompatibleException]) or leaves it unchanged (transient / an app bug), from the
     * SQLite result code found in its cause chain — the same policy as the desktop actual. A failure
     * with no SQLite result code behind it is rethrown unchanged.
     */
    private fun classifyMergeFailure(e: Throwable, cloudDbPath: String, localSchemaVersion: Long): Throwable {
        val code = e.findSqliteResultCode() ?: return e
        val category = failureCategory(code)
        val codeName = sqliteResultCodeName(code)
        val classified: CloudDataIncompatibleException = MergeFailureClassifier.classify(
            category = category,
            errorCodeName = codeName,
            validateCloudSchema = { validateSchema(cloudDbPath, localSchemaVersion) },
        ) ?: return e
        Log.warn(TAG, "${classified.message} (category=$category, code=$code $codeName): ${e.message}")
        return classified
    }

    /**
     * Walks the cause chain for a SQLite result code: this file's own [SqliteException] (from
     * [RawSqliteConnection]), or SQLiter's [SQLiteExceptionErrorCode], which is what
     * [NativeSqliteDriver] throws while [migrateCloudIfOlder] opens and migrates the cloud file.
     * SQLiter keeps the raw code private and exposes only its primary code, through `errorType`;
     * that getter throws for a code it has no enum entry for, which is treated as "no code found".
     * Bounded so a (theoretical) cause cycle cannot loop forever.
     */
    private fun Throwable.findSqliteResultCode(): Int? {
        var current: Throwable? = this
        repeat(CAUSE_CHAIN_MAX_DEPTH) {
            val c = current ?: return null
            when (c) {
                is SqliteException -> return c.resultCode
                is SQLiteExceptionErrorCode -> return runCatching { c.errorType.code }.getOrNull()
            }
            current = c.cause
        }
        return null
    }

    /** Same mapping as the desktop actual, on the primary result code (an extended code's low byte). */
    private fun failureCategory(resultCode: Int): SqliteFailureCategory =
        when (resultCode and 0xFF) {
            SQLITE_NOTADB, SQLITE_CORRUPT, SQLITE_FORMAT, SQLITE_EMPTY, SQLITE_CONSTRAINT ->
                SqliteFailureCategory.CORRUPT_OR_CONSTRAINT
            SQLITE_ERROR -> SqliteFailureCategory.STATEMENT_ERROR
            else -> SqliteFailureCategory.OTHER
        }

    actual fun validateSchema(dbPath: String, schemaVersion: Long): Boolean? {
        val expectedTables = MergeSchema.EXPECTED_SCHEMAS[schemaVersion] ?: return null
        return try {
            RawSqliteConnection.open(dbPath).use { db ->
                expectedTables.all { (tableName, requiredColumns) ->
                    val actualColumns = db.queryColumn("PRAGMA table_info($tableName)", "name").map { it.lowercase() }.toSet()
                    requiredColumns.all { it.lowercase() in actualColumns }
                }
            }
        } catch (_: SqliteException) {
            null
        }
    }

    /**
     * Brings an older cloud file up to [localSchemaVersion] with the app's own migrations before it
     * is attached. Opened through [NativeSqliteDriver] only for that migration, in rollback-journal
     * mode so the downloaded file is not switched to WAL.
     *
     * The driver opens (and so migrates) the file lazily, on its first statement — see
     * `DatabaseDriverFactory.createDriver` — so one trivial query is run before it is closed.
     *
     * @throws IllegalStateException if the file is still not at [localSchemaVersion] afterwards.
     * Deliberately carries no SQLite result code, so [merge] leaves it unclassified (transient),
     * never offering a destructive cloud-data reset for what may be an app-side migration fault.
     */
    private fun migrateCloudIfOlder(cloudDbPath: String, localSchemaVersion: Long) {
        val cloudVersion = RawSqliteConnection.userVersionOf(cloudDbPath)
        if (cloudVersion > localSchemaVersion) {
            throw SchemaVersionException(localVersion = localSchemaVersion, cloudVersion = cloudVersion)
        }
        if (cloudVersion in 1 until localSchemaVersion) {
            val dir = cloudDbPath.substringBeforeLast('/')
            val name = cloudDbPath.substringAfterLast('/')
            val driver = NativeSqliteDriver(
                schema = KeryxDatabase.Schema,
                name = name,
                onConfiguration = { config ->
                    config.copy(
                        journalMode = JournalMode.DELETE,
                        extendedConfig = config.extendedConfig.copy(basePath = dir),
                    )
                },
            )
            try {
                driver.executeQuery(null, "SELECT 1", { QueryResult.Unit }, 0)
            } finally {
                driver.close()
            }
            val migratedVersion = RawSqliteConnection.userVersionOf(cloudDbPath)
            check(migratedVersion == localSchemaVersion) {
                "Cloud DB migration did not reach schema version $localSchemaVersion " +
                    "(from $cloudVersion, now $migratedVersion)"
            }
        }
    }

    private const val TAG = "DatabaseMerger"

    /** Bounds [findSqliteResultCode]'s cause-chain walk against a theoretical cause cycle. */
    private const val CAUSE_CHAIN_MAX_DEPTH = 8
    private const val SQLITE_ERROR = 1
    private const val SQLITE_CORRUPT = 11
    private const val SQLITE_EMPTY = 16
    private const val SQLITE_CONSTRAINT = 19
    private const val SQLITE_FORMAT = 24
    private const val SQLITE_NOTADB = 26
}
