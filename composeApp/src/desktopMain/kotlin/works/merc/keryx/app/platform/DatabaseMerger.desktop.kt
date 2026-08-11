package works.merc.keryx.app.platform

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.sqlite.SQLiteException
import works.merc.keryx.app.core.CloudDataIncompatibleException
import works.merc.keryx.app.core.Log
import works.merc.keryx.app.core.SQLITE_BUSY_TIMEOUT_MS
import works.merc.keryx.app.core.SchemaVersionException
import works.merc.keryx.app.data.local.db.KeryxDatabase
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
     * ([CloudDataIncompatibleException]) or leaves it unchanged (transient / an app bug), using
     * SQLite's error code — not message text, which is locale- and driver-version-fragile — found
     * by walking the cause chain for a [SQLiteException].
     *
     * Deliberately conservative: an error this cannot recognize is returned unchanged, so a miss
     * never regresses behavior — the caller's own catch-all already reports it as a transient
     * [works.merc.keryx.app.core.CloudStorageException].
     */
    private fun classifyMergeFailure(e: Throwable, cloudDbPath: String, localSchemaVersion: Long): Throwable {
        val sqliteCause = e.findSqliteCause() ?: return e
        val primaryCode = sqliteCause.resultCode.code and 0xFF
        return when (primaryCode) {
            // Not a database / corrupt / bad format / empty-but-expected-populated, or a
            // UNIQUE/NOT NULL/FOREIGN KEY violation: MergeSql's NOT EXISTS/EXISTS guards already
            // rule out every collision with main's own rows, so the only way a merge statement can
            // still hit a constraint is the cloud DB's own row set violating it (e.g. a duplicate
            // url inside the cloud DB, or a NULL where the cloud's schema allowed one) — data this
            // app's schema cannot represent, i.e. exactly what CloudDataIncompatibleException means.
            SQLITE_NOTADB, SQLITE_CORRUPT, SQLITE_FORMAT, SQLITE_EMPTY, SQLITE_CONSTRAINT -> {
                Log.warn(TAG, "Cloud DB unusable (${sqliteCause.resultCode.name}): ${e.message}")
                CloudDataIncompatibleException("Cloud DB unusable (${sqliteCause.resultCode.name})")
            }
            // Ambiguous: "no such table"/"no such column" is what a foreign/legacy cloud schema
            // looks like, but it is also what a broken *local* schema looks like (a dropped table
            // from an unrelated app bug). Disambiguate against the downloaded cloud file itself.
            SQLITE_ERROR -> when (validateSchema(cloudDbPath, localSchemaVersion)) {
                false -> {
                    Log.warn(TAG, "Cloud DB schema is incompatible: ${e.message}")
                    CloudDataIncompatibleException("Cloud DB schema is incompatible")
                }
                // true (cloud schema looks fine) or null (undetermined) both mean this cannot be
                // confidently pinned on the cloud — leave it as a transient/app-bug failure.
                true, null -> e
            }
            else -> e
        }
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
     * not, or `null` if [schemaVersion] has no registered expectation in [EXPECTED_SCHEMAS].
     */
    actual fun validateSchema(dbPath: String, schemaVersion: Long): Boolean? {
        val expectedTables = EXPECTED_SCHEMAS[schemaVersion] ?: return null
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
     * Expected tables and columns for schema version 2.
     * Keep in sync with [MergeSql] and the `.sq` schema files.
     */
    private val EXPECTED_SCHEMA_V2 = mapOf(
        "folders" to setOf(
            "id", "name", "sort_order", "deleted_at", "updated_at", "created_at",
        ),
        "feeds" to setOf(
            "id", "url", "site_url", "title", "description", "favicon_url", "etag",
            "last_modified", "error_count", "last_error", "custom_title", "folder_id",
            "deleted_at", "updated_at", "created_at", "sort_order",
            "folder_updated_at", "sort_order_updated_at", "custom_title_updated_at", "deleted_updated_at",
        ),
        "tags" to setOf(
            "id", "name", "color", "sort_order", "deleted_at", "updated_at", "created_at",
        ),
        "articles" to setOf(
            "id", "feed_id", "guid", "url", "title", "summary", "content", "author",
            "published_at", "thumbnail_url", "is_read", "read_at", "is_starred",
            "starred_at", "cached_at", "search_text", "updated_at", "created_at",
            "deleted_at", "deleted_updated_at",
        ),
        "feed_tags" to setOf(
            "feed_id", "tag_id", "deleted_at", "updated_at",
        ),
        "global_settings" to setOf(
            "key", "value", "updated_at",
        ),
    )

    /**
     * Registered expected schemas by [works.merc.keryx.app.data.local.db.KeryxDatabase.Schema.version].
     * A version missing here makes [validateSchema] return `null` (undetermined) rather than
     * `false` — an unregistered version must never be treated as "definitely incompatible", which
     * would offer a destructive cloud-data reset for what is really just a forgotten registration.
     */
    private val EXPECTED_SCHEMAS: Map<Long, Map<String, Set<String>>> = mapOf(2L to EXPECTED_SCHEMA_V2)

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
