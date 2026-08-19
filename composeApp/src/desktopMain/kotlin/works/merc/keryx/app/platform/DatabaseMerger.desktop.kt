package works.merc.keryx.app.platform

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.sqlite.SQLiteException
import works.merc.keryx.app.core.CloudDataIncompatibleException
import works.merc.keryx.app.core.Log
import works.merc.keryx.app.core.SQLITE_BUSY_TIMEOUT_MS
import works.merc.keryx.app.core.SchemaVersionException
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.domain.MergeFailureClassifier
import works.merc.keryx.app.domain.MergeSchema
import works.merc.keryx.app.domain.SqliteFailureCategory
import java.sql.DriverManager
import java.util.Properties

actual object DatabaseMerger {
    /**
     * Merges cloud database changes into a local SQLite database.
     *
     * @param localDbPath The path to the local database.
     * @param cloudDbPath The path to the cloud database.
     * @param localSchemaVersion The schema version supported by the local database.
     * @param mergeStatements SQL statements to apply during the merge.
     * @throws SchemaVersionException If the cloud database schema is newer than the local schema.
     * @throws CloudDataIncompatibleException If the cloud database is corrupt, or its data
     * violates this app's schema constraints (classified from SQLite's error code — see
     * [classifyMergeFailure]). Any other failure is rethrown unchanged (transient / an app bug).
     */
    actual fun merge(
        localDbPath: String,
        cloudDbPath: String,
        localSchemaVersion: Long,
        mergeStatements: List<String>,
    ) {
        try {
            mergeUnclassified(localDbPath, cloudDbPath, localSchemaVersion, mergeStatements)
        } catch (e: SchemaVersionException) {
            // Already classified — must not fall into the Throwable catch-all below, which would
            // reclassify it as CloudDataIncompatibleException (the wrong recovery action: this
            // needs an app update, not a cloud-data reset).
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
        // Bring an older cloud DB up to the local schema first, so the merge statements can
        // reference columns added in newer versions without a "no such column" failure.
        migrateCloudIfOlder(cloudDbPath, localSchemaVersion)

        val connection = DriverManager.getConnection("jdbc:sqlite:$localDbPath")
        try {
            connection.autoCommit = true
            connection.createStatement().use { st ->
                st.execute("PRAGMA foreign_keys=ON")
                st.execute("PRAGMA busy_timeout=$SQLITE_BUSY_TIMEOUT_MS")
                // ATTACH must run outside a transaction.
                st.execute("ATTACH DATABASE '${cloudDbPath.replace("'", "''")}' AS cloud")
            }

            val cloudVersion = connection.createStatement().use { st ->
                st.executeQuery("PRAGMA cloud.user_version").use { rs ->
                    if (rs.next()) rs.getLong(1) else 0L
                }
            }
            if (cloudVersion > localSchemaVersion) {
                throw SchemaVersionException(localVersion = localSchemaVersion, cloudVersion = cloudVersion)
            }

            connection.autoCommit = false
            try {
                connection.createStatement().use { st ->
                    for (sql in mergeStatements) st.execute(sql)
                }
                connection.commit()
            } catch (e: Throwable) {
                // A rollback failure must not replace (and hide) the failure being classified.
                runCatching { connection.rollback() }.exceptionOrNull()?.let(e::addSuppressed)
                throw e
            } finally {
                connection.autoCommit = true
            }
        } finally {
            runCatching {
                connection.createStatement().use { it.execute("DETACH DATABASE cloud") }
            }
            connection.close()
        }
    }

    /**
     * Classifies a merge failure as a permanently-unusable cloud DB
     * ([CloudDataIncompatibleException]) or leaves it unchanged (transient / an app bug).
     *
     * This side only talks to the JDBC driver: it walks the cause chain for a [SQLiteException]
     * and reduces its error code — not message text, which is locale- and driver-version-fragile —
     * to a driver-independent [SqliteFailureCategory]. The classification policy itself lives in
     * [MergeFailureClassifier] (`commonMain`), so another target's driver only has to produce the
     * same category.
     */
    private fun classifyMergeFailure(e: Throwable, cloudDbPath: String, localSchemaVersion: Long): Throwable {
        val sqliteCause = e.findSqliteCause() ?: return e
        val category = sqliteCause.failureCategory()
        val classified = MergeFailureClassifier.classify(
            category = category,
            errorCodeName = sqliteCause.resultCode.name,
            validateCloudSchema = { validateSchema(cloudDbPath, localSchemaVersion) },
        ) ?: return e
        // The classified exception's own message is the diagnosis; the original failure's message
        // carries the SQL-level detail behind it.
        Log.warn(TAG, "${classified.message}: ${e.message}")
        return classified
    }

    /** Reduces this JDBC exception's SQLite *primary* result code to a platform-independent category. */
    private fun SQLiteException.failureCategory(): SqliteFailureCategory =
        when (resultCode.code and 0xFF) {
            SQLITE_NOTADB, SQLITE_CORRUPT, SQLITE_FORMAT, SQLITE_EMPTY, SQLITE_CONSTRAINT ->
                SqliteFailureCategory.CORRUPT_OR_CONSTRAINT
            SQLITE_ERROR -> SqliteFailureCategory.STATEMENT_ERROR
            else -> SqliteFailureCategory.OTHER
        }

    /**
     * Walks the cause chain looking for the [SQLiteException] that explains [this], since
     * SQLDelight's `JdbcSqliteDriver` (used by [migrateCloudIfOlder]) may wrap it before it
     * reaches [merge]'s catch. Bounded so a (theoretical) cause cycle cannot loop forever.
     */
    private fun Throwable.findSqliteCause(): SQLiteException? {
        var current: Throwable? = this
        repeat(CAUSE_CHAIN_MAX_DEPTH) {
            val c = current ?: return null
            if (c is SQLiteException) return c
            current = c.cause
        }
        return null
    }

    /**
     * Validates that the database at [dbPath] contains the required tables and columns for [schemaVersion].
     *
     * @param dbPath The path to the database to validate.
     * @param schemaVersion The schema version whose structure is required.
     * @return `true` if the database contains all required tables and columns, `false` if it does
     * not, or `null` if [schemaVersion] has no registered expectation in
     * [MergeSchema.EXPECTED_SCHEMAS].
     */
    actual fun validateSchema(dbPath: String, schemaVersion: Long): Boolean? {
        val expectedTables = MergeSchema.EXPECTED_SCHEMAS[schemaVersion] ?: return null
        return try {
            DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
                expectedTables.all { (tableName, requiredColumns) ->
                    val actualColumns = connection.createStatement().use { statement ->
                        statement.executeQuery("PRAGMA table_info($tableName)").use { rs ->
                            buildSet {
                                while (rs.next()) {
                                    add(rs.getString("name").lowercase())
                                }
                            }
                        }
                    }
                    requiredColumns.all { it.lowercase() in actualColumns }
                }
            }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * If the downloaded cloud DB is older than the local schema, run the SQLDelight migrations on
     * it (in place, on the temp file) so it matches the local schema before merging.
     * A cloud DB newer than the local schema is rejected (the caller must update the app).
     */
    private fun migrateCloudIfOlder(cloudDbPath: String, localSchemaVersion: Long) {
        val cloudVersion = DriverManager.getConnection("jdbc:sqlite:$cloudDbPath").use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("PRAGMA user_version").use { rs ->
                    if (rs.next()) rs.getLong(1) else 0L
                }
            }
        }
        if (cloudVersion > localSchemaVersion) {
            throw SchemaVersionException(localVersion = localSchemaVersion, cloudVersion = cloudVersion)
        }
        if (cloudVersion in 1 until localSchemaVersion) {
            val driver = JdbcSqliteDriver("jdbc:sqlite:$cloudDbPath", Properties())
            try {
                KeryxDatabase.Schema.migrate(driver, cloudVersion, localSchemaVersion)
                driver.execute(null, "PRAGMA user_version=$localSchemaVersion;", 0)
            } finally {
                driver.close()
            }
        }
    }

    private const val TAG = "DatabaseMerger"

    /** Bounds [findSqliteCause]'s cause-chain walk against a theoretical cause cycle. */
    private const val CAUSE_CHAIN_MAX_DEPTH = 8

    // SQLite *primary* result codes (an extended code's low byte, i.e. `code and 0xFF`), named
    // locally so the classification `when` above reads without a lookup table. See
    // org.sqlite.SQLiteErrorCode for the full list.
    private const val SQLITE_ERROR = 1
    private const val SQLITE_CORRUPT = 11
    private const val SQLITE_EMPTY = 16
    private const val SQLITE_CONSTRAINT = 19
    private const val SQLITE_FORMAT = 24
    private const val SQLITE_NOTADB = 26
}
