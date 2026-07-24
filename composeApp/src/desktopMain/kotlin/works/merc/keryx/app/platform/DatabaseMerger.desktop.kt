package works.merc.keryx.app.platform

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import works.merc.keryx.app.core.SchemaVersionException
import works.merc.keryx.app.data.local.db.KeryxDatabase
import java.sql.DriverManager
import java.util.Properties

actual object DatabaseMerger {
    actual fun merge(
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
                st.execute("PRAGMA busy_timeout=5000")
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
                connection.rollback()
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
     * Validates that the database at [dbPath] contains the required tables and columns for [schemaVersion].
     *
     * @param dbPath The path to the database to validate.
     * @param schemaVersion The schema version whose structure is required.
     * @return `true` if the database contains all required tables and columns, `false` otherwise.
     */
    actual fun validateSchema(dbPath: String, schemaVersion: Long): Boolean {
        val expectedTables = when (schemaVersion) {
            2L -> EXPECTED_SCHEMA_V2
            else -> return false
        }
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
}
