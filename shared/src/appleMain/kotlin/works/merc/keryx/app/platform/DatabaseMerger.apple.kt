package works.merc.keryx.app.platform

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.JournalMode
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
            throw e
        } catch (e: SqliteException) {
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

    private fun classifyMergeFailure(e: SqliteException, cloudDbPath: String, localSchemaVersion: Long): Throwable {
        val category = e.failureCategory()
        val classified: CloudDataIncompatibleException = MergeFailureClassifier.classify(
            category = category,
            errorCodeName = e.resultCodeName,
            validateCloudSchema = { validateSchema(cloudDbPath, localSchemaVersion) },
        ) ?: return e
        Log.warn(TAG, "${classified.message} (category=$category, code=${e.resultCode}): ${e.message}")
        return classified
    }

    /** Same mapping as the desktop actual, on the primary result code. */
    private fun SqliteException.failureCategory(): SqliteFailureCategory =
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
     */
    private fun migrateCloudIfOlder(cloudDbPath: String, localSchemaVersion: Long) {
        val cloudVersion = RawSqliteConnection.userVersionOf(cloudDbPath)
        if (cloudVersion > localSchemaVersion) {
            throw SchemaVersionException(localVersion = localSchemaVersion, cloudVersion = cloudVersion)
        }
        if (cloudVersion in 1 until localSchemaVersion) {
            val dir = cloudDbPath.substringBeforeLast('/')
            val name = cloudDbPath.substringAfterLast('/')
            NativeSqliteDriver(
                schema = KeryxDatabase.Schema,
                name = name,
                onConfiguration = { config ->
                    config.copy(
                        journalMode = JournalMode.DELETE,
                        extendedConfig = config.extendedConfig.copy(basePath = dir),
                    )
                },
            ).close()
        }
    }

    private const val TAG = "DatabaseMerger"
    private const val SQLITE_ERROR = 1
    private const val SQLITE_CORRUPT = 11
    private const val SQLITE_EMPTY = 16
    private const val SQLITE_CONSTRAINT = 19
    private const val SQLITE_FORMAT = 24
    private const val SQLITE_NOTADB = 26
}
