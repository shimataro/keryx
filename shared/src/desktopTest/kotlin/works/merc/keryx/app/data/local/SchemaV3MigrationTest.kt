package works.merc.keryx.app.data.local

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import works.merc.keryx.app.createSchemaV2
import works.merc.keryx.app.data.local.db.KeryxDatabase
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers `2.sqm` (schema version 2 -> 3): a migrated database must end up with exactly the schema a
 * fresh `Schema.create` builds, with the existing data moved where version 3 keeps it.
 */
class SchemaV3MigrationTest {

    private fun tempFile(): File = File.createTempFile("keryx-v3-", ".db").apply { deleteOnExit() }

    private fun <T> withConnection(file: File, block: (Connection) -> T): T =
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use(block)

    private fun Connection.rows(sql: String): List<List<Any?>> = createStatement().use { st ->
        st.executeQuery(sql).use { rs ->
            val columns = rs.metaData.columnCount
            buildList { while (rs.next()) add((1..columns).map { rs.getObject(it) }) }
        }
    }

    /** Collapses whitespace, so a statement compares equal however it was laid out. */
    private fun String?.normalized(): String? = this?.replace(Regex("\\s+"), " ")?.trim()

    /**
     * Everything that defines the schema, in a form a created and a migrated database can be compared
     * in: per table its columns (`table_info`), foreign keys and indexes; the explicit indexes' and
     * the triggers' SQL. A table's own `CREATE TABLE` text is left out on purpose — `ALTER TABLE`
     * rewrites it differently from how the `.sq` file spells the same table.
     */
    private fun schemaOf(file: File): Map<String, Any?> = withConnection(file) { conn ->
        val tables = conn.rows(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
        ).map { it[0] as String }
        buildMap {
            put("tables", tables)
            for (table in tables) {
                put("$table.columns", conn.rows("SELECT cid, name, type, \"notnull\", dflt_value, pk FROM pragma_table_info('$table')"))
                put("$table.fks", conn.rows("SELECT \"table\", \"from\", \"to\" FROM pragma_foreign_key_list('$table') ORDER BY \"from\""))
                put("$table.indexes", conn.rows("SELECT name, \"unique\", origin, partial FROM pragma_index_list('$table') ORDER BY name"))
            }
            put(
                "indexes",
                conn.rows("SELECT name, sql FROM sqlite_master WHERE type = 'index' AND sql IS NOT NULL ORDER BY name")
                    .map { listOf(it[0], (it[1] as String?).normalized()) },
            )
            put(
                "triggers",
                conn.rows("SELECT name, tbl_name, sql FROM sqlite_master WHERE type = 'trigger' ORDER BY name")
                    .map { listOf(it[0], it[1], (it[2] as String?).normalized()) },
            )
        }
    }

    private fun createdDb(): File {
        val file = tempFile()
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        try {
            KeryxDatabase.Schema.create(driver)
        } finally {
            driver.close()
        }
        return file
    }

    /** A version-2 database, seeded by [seed] (raw SQL on the v2 schema), then migrated to 3. */
    private fun migratedDb(seed: List<String> = emptyList()): File {
        val file = tempFile()
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        try {
            driver.createSchemaV2()
            seed.forEach { driver.execute(null, it, 0) }
            KeryxDatabase.Schema.migrate(driver, 2, 3)
        } finally {
            driver.close()
        }
        return file
    }

    @Test
    fun aMigratedSchemaIsIdenticalToACreatedOne() {
        val created = schemaOf(createdDb())
        val migrated = schemaOf(migratedDb())
        // Compared key by key first, so a failure names the object that differs.
        assertEquals(created.keys, migrated.keys)
        for (key in created.keys) assertEquals(created[key], migrated[key], "schema differs at $key")
        assertTrue("feed_fetch_state" in (created["tables"] as List<*>))
        assertEquals(12, (created["triggers"] as List<*>).size)
    }

    @Test
    fun syncCaptureIsSeededDisabledByCreateAndByMigrate() {
        for (file in listOf(createdDb(), migratedDb())) {
            assertEquals(
                listOf(listOf<Any?>(1, 0, 0)),
                withConnection(file) { it.rows("SELECT id, enabled, full_pending FROM sync_capture") },
            )
        }
    }

    @Test
    fun migrationMovesEachFeedsFetchStateAndDropsTheColumns() {
        val file = migratedDb(
            listOf(
                """INSERT INTO feeds (id, url, title, etag, last_modified, error_count, last_error, updated_at, created_at)
                   VALUES ('f1', 'https://a', 'A', '"e1"', 'Mon, 01 Jan 2026 00:00:00 GMT', 3, 'boom', 10, 1)""",
                """INSERT INTO feeds (id, url, title, last_error, updated_at, created_at)
                   VALUES ('f2', 'https://b', 'B', 'gone', 20, 2)""",
                """INSERT INTO feeds (id, url, title, updated_at, created_at) VALUES ('f3', 'https://c', 'C', 30, 3)""",
            ),
        )
        withConnection(file) { conn ->
            assertEquals(
                listOf(
                    listOf<Any?>("f1", "\"e1\"", "Mon, 01 Jan 2026 00:00:00 GMT", 3, "boom"),
                    listOf<Any?>("f2", null, null, 0, "gone"),
                    listOf<Any?>("f3", null, null, 0, null),
                ),
                conn.rows("SELECT feed_id, etag, last_modified, error_count, last_error FROM feed_fetch_state ORDER BY feed_id"),
            )
            val feedColumns = conn.rows("SELECT name FROM pragma_table_info('feeds')").map { it[0] }
            for (dropped in listOf("etag", "last_modified", "error_count", "last_error")) {
                assertFalse(dropped in feedColumns, "$dropped must be dropped from feeds")
            }
            // The synced columns and their values are untouched.
            assertEquals(
                listOf(listOf<Any?>("f1", "https://a", "A", 10, 1)),
                conn.rows("SELECT id, url, title, updated_at, created_at FROM feeds WHERE id = 'f1'"),
            )
        }
    }

    @Test
    fun migrationInitializesTheTagAndFolderStampsFromUpdatedAt() {
        val file = migratedDb(
            listOf(
                "INSERT INTO tags (id, name, color, sort_order, updated_at, created_at) VALUES ('t1', 'T', '#f00', 2, 700, 1)",
                "INSERT INTO folders (id, name, sort_order, updated_at, created_at) VALUES ('d1', 'D', 1, 800, 1)",
            ),
        )
        withConnection(file) { conn ->
            assertEquals(
                listOf(listOf<Any?>(700, 700, 700)),
                conn.rows("SELECT name_updated_at, color_updated_at, sort_order_updated_at FROM tags"),
            )
            assertEquals(listOf(listOf<Any?>(800, 800)), conn.rows("SELECT name_updated_at, sort_order_updated_at FROM folders"))
        }
    }

    @Test
    fun migrationRegistersOnlyArticlesWhoseSearchTextIsStillTheRawBody() {
        fun article(id: String, summary: String?, content: String?, searchText: String) =
            """INSERT INTO articles (id, feed_id, guid, url, title, summary, content, cached_at, search_text, updated_at, created_at)
               VALUES ('$id', 'f1', '$id', 'u', 'T', ${summary?.let { "'$it'" } ?: "NULL"}, ${content?.let { "'$it'" } ?: "NULL"},
                       0, '$searchText', 0, 0)"""
        val file = migratedDb(
            listOf(
                "INSERT INTO feeds (id, url, title, updated_at, created_at) VALUES ('f1', 'https://a', 'A', 0, 0)",
                article("raw-content", summary = "s", content = "<p>x</p>", searchText = "<p>x</p>"),
                article("stripped-content", summary = null, content = "<p>x</p>", searchText = "x"),
                article("raw-summary", summary = "<b>s</b>", content = null, searchText = "<b>s</b>"),
                article("stripped-summary", summary = "<b>s</b>", content = null, searchText = "s"),
                // content wins over summary: a search text equal to the summary is not "raw" here.
                article("content-and-summary", summary = "s", content = "<p>x</p>", searchText = "s"),
                article("empty", summary = null, content = null, searchText = ""),
            ),
        )
        withConnection(file) { conn ->
            assertEquals(
                listOf<Any?>("raw-content", "raw-summary"),
                conn.rows(
                    "SELECT a.id FROM ks_search_pending p JOIN articles a ON a.rowid = p.rowid ORDER BY a.id",
                ).map { it[0] },
            )
        }
    }

    /**
     * The desktop factory runs a migration and its `user_version` stamp in one transaction: a migration
     * that fails halfway leaves the file at version 2 with none of its statements applied, so the next
     * launch can run it again.
     */
    @Test
    fun aFailedMigrationRollsBackWholly() {
        val file = tempFile()
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        try {
            driver.createSchemaV2()
            driver.execute(null, "INSERT INTO feeds (id, url, title, updated_at, created_at) VALUES ('f1', 'https://a', 'A', 0, 0)", 0)
            // A trigger naming feeds.etag makes 2.sqm's `ALTER TABLE feeds DROP COLUMN etag` fail —
            // after feed_fetch_state was already created and filled.
            driver.execute(
                null,
                "CREATE TRIGGER blocks_drop AFTER UPDATE ON feeds BEGIN SELECT NEW.etag; END",
                0,
            )
        } finally {
            driver.close()
        }

        assertFailsWith<Exception> { DatabaseDriverFactory().createDriver(file).close() }

        withConnection(file) { conn ->
            assertEquals(listOf(listOf<Any?>(2)), conn.rows("PRAGMA user_version"))
            val tables = conn.rows("SELECT name FROM sqlite_master WHERE type = 'table'").map { it[0] }
            assertFalse("feed_fetch_state" in tables, "the migration's first statements must be rolled back too")
            assertTrue("etag" in conn.rows("SELECT name FROM pragma_table_info('feeds')").map { it[0] })
        }
    }

    @Test
    fun theDesktopFactoryMigratesAVersionTwoFile() {
        val file = tempFile()
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        try {
            driver.createSchemaV2()
        } finally {
            driver.close()
        }

        DatabaseDriverFactory().createDriver(file).close()

        withConnection(file) { conn ->
            assertEquals(listOf(listOf<Any?>(3)), conn.rows("PRAGMA user_version"))
        }
        assertEquals(schemaOf(createdDb()), schemaOf(file))
    }
}
