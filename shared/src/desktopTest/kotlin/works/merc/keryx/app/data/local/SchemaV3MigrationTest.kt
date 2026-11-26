package works.merc.keryx.app.data.local

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import works.merc.keryx.app.SCHEMA_V2_STATEMENTS
import works.merc.keryx.app.createSchemaV2
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.domain.KERYX_SCHEMA_CALLBACKS
import works.merc.keryx.app.domain.IdGenerator
import works.merc.keryx.app.domain.rekeyTagAndFolderIds
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
     * in: per table its columns (`table_info`), foreign keys and indexes; the explicit indexes', the
     * views' and the triggers' SQL. A table's own `CREATE TABLE` text is left out on purpose — `ALTER TABLE`
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
                "views",
                conn.rows("SELECT name, sql FROM sqlite_master WHERE type = 'view' ORDER BY name")
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
        // The twelve capture triggers plus the raw folder row's capture trigger, the two identity edge
        // capture triggers (ks_v2_edge, ks_feed_edge), and the eight that keep
        // feeds.folder_id resolved from ks_feed_folder_raw (its creation with the feed, and resolution
        // on a raw row's insert/update, a folder's insert and liveness change, and a folder entry of
        // ks_id_remap's insert/update/delete); the one view is ks_feed_folder_resolved.
        assertEquals(23, (created["triggers"] as List<*>).size)
        assertEquals(listOf("ks_feed_folder_resolved"), (created["views"] as List<*>).map { (it as List<*>)[0] })
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

    /**
     * The v2 -> v3 rebuild of tags and folders, run through the production factory so the
     * AfterVersion(2) re-key ([rekeyTagAndFolderIds]) runs as it does on every platform: UNIQUE(name)
     * is dropped (same-named rows are allowed from here on), the per-field stamps arrive with the
     * rebuild, every row's id becomes the one its name derives from, the children are re-attached
     * under the new ids, and each move is recorded in ks_id_remap. A link whose tag was never there
     * (written before foreign keys were enforced) survives untouched — it was already a violation,
     * and nothing here deletes a row it points at — while a link whose feed is gone is the only row
     * lost: it cannot be restored, because feeds is not rebuilt. A feed's folder reference naming no
     * folder becomes a NULL raw assignment, and the resolved feeds.folder_id follows. Seeded with foreign
     * keys off (as the v2 app left them) and migrated through the production factory, whose
     * connections enforce them.
     */
    @Test
    fun theRebuildDropsNameUniquenessRekeysTheIdsAndKeepsTheChildren() {
        val file = tempFile()
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        try {
            driver.createSchemaV2()
            listOf(
                "INSERT INTO folders (id, name, sort_order, updated_at, created_at) VALUES ('d1', 'D', 0, 10, 1)",
                "INSERT INTO folders (id, name, sort_order, updated_at, created_at, deleted_at) VALUES ('d2', 'Gone', 1, 20, 2, 20)",
                "INSERT INTO feeds (id, url, title, folder_id, updated_at, created_at) VALUES ('f1', 'https://a', 'A', 'd1', 10, 1)",
                // An orphan: d-orphan does not exist (data from before foreign keys were enforced).
                "INSERT INTO feeds (id, url, title, folder_id, updated_at, created_at) VALUES ('f2', 'https://b', 'B', 'd-orphan', 10, 1)",
                "INSERT INTO feeds (id, url, title, updated_at, created_at) VALUES ('f3', 'https://c', 'C', 10, 1)",
                // Assigned to the soft-deleted folder (a v2 sync could leave a feed there).
                "INSERT INTO feeds (id, url, title, folder_id, folder_updated_at, updated_at, created_at) VALUES ('f4', 'https://d', 'D', 'd2', 30, 10, 1)",
                "INSERT INTO tags (id, name, updated_at, created_at) VALUES ('t1', 'T', 10, 1)",
                "INSERT INTO tags (id, name, updated_at, created_at, deleted_at) VALUES ('t2', 'Gone', 20, 2, 20)",
                "INSERT INTO feed_tags (feed_id, tag_id, updated_at) VALUES ('f1', 't1', 10)",
                "INSERT INTO feed_tags (feed_id, tag_id, updated_at) VALUES ('f1', 't2', 10)",
                // A link whose feed does not exist: evacuated with the valid ones, not restorable.
                "INSERT INTO feed_tags (feed_id, tag_id, updated_at) VALUES ('f-missing', 't1', 10)",
                // A link whose tag does not exist: never evacuated, stays as it was.
                "INSERT INTO feed_tags (feed_id, tag_id, updated_at) VALUES ('f1', 't-orphan', 10)",
            ).forEach { driver.execute(null, it, 0) }
        } finally {
            driver.close()
        }

        fun violations(): List<List<Any?>> = withConnection(file) { conn ->
            conn.rows("SELECT \"table\", parent FROM pragma_foreign_key_check ORDER BY \"table\", parent")
        }
        val violationsBefore = violations()

        DatabaseDriverFactory().createDriver(file).close()

        val dId = IdGenerator.folderId("D")
        val dGoneId = IdGenerator.folderId("Gone")
        val tId = IdGenerator.tagId("T")
        val tGoneId = IdGenerator.tagId("Gone")
        withConnection(file) { conn ->
            // Both tables are re-keyed onto the name-derived ids, values and stamps carried over.
            assertEquals(
                setOf(
                    listOf<Any?>(dId, "D", 10, 1),
                    listOf<Any?>(dGoneId, "Gone", 20, 2),
                ),
                conn.rows("SELECT id, name, updated_at, created_at FROM folders ORDER BY created_at").toSet(),
            )
            assertEquals(
                setOf(
                    listOf<Any?>(tId, "T", 10, 1),
                    listOf<Any?>(tGoneId, "Gone", 20, 2),
                ),
                conn.rows("SELECT id, name, updated_at, created_at FROM tags ORDER BY created_at").toSet(),
            )
            // Each re-keyed row keeps its pre-upgrade id in v2_id, which the sync merge reconciles by.
            assertEquals(
                setOf(listOf<Any?>(dId, "d1"), listOf<Any?>(dGoneId, "d2")),
                conn.rows("SELECT id, v2_id FROM folders").toSet(),
            )
            assertEquals(
                setOf(listOf<Any?>(tId, "t1"), listOf<Any?>(tGoneId, "t2")),
                conn.rows("SELECT id, v2_id FROM tags").toSet(),
            )
            // The raw folder assignments are filled from the feeds and moved to the new ids with the
            // folders. The orphan's reference names no folder row, so its raw assignment is NULL (a raw
            // row only ever names an existing folder — every send's closure relies on it); the feed
            // assigned to the soft-deleted folder keeps that assignment.
            assertEquals(
                listOf(
                    listOf<Any?>("f1", null, dId),
                    listOf<Any?>("f2", null, null),
                    listOf<Any?>("f3", null, null),
                    listOf<Any?>("f4", 30, dGoneId),
                ),
                conn.rows("SELECT feed_id, folder_updated_at, folder_id FROM ks_feed_folder_raw ORDER BY feed_id"),
            )
            // feeds.folder_id is resolved from them: the live folder under its new id, and "no folder"
            // for the soft-deleted folder and for the orphan, whose dangling reference is cleared.
            assertEquals(
                listOf(
                    listOf<Any?>("f1", dId),
                    listOf<Any?>("f2", null),
                    listOf<Any?>("f3", null),
                    listOf<Any?>("f4", null),
                ),
                conn.rows("SELECT id, folder_id FROM feeds ORDER BY id"),
            )
            assertEquals(
                setOf(
                    listOf<Any?>("f1", "t-orphan"),
                    listOf<Any?>("f1", tGoneId),
                    listOf<Any?>("f1", tId),
                ),
                conn.rows("SELECT feed_id, tag_id FROM feed_tags").toSet(),
            )
            // Each move is recorded, so SettingsRepository can rewrite the same ids in local_settings.
            assertEquals(
                setOf(
                    listOf<Any?>("folder", "d1", dId),
                    listOf<Any?>("folder", "d2", dGoneId),
                    listOf<Any?>("tag", "t1", tId),
                    listOf<Any?>("tag", "t2", tGoneId),
                ),
                conn.rows("SELECT kind, old_id, new_id FROM ks_id_remap").toSet(),
            )
            // Each re-keyed row's identity edge (its pre-upgrade id, its new id) is recorded too.
            assertEquals(
                setOf(
                    listOf<Any?>("folder", "d1", dId),
                    listOf<Any?>("folder", "d2", dGoneId),
                    listOf<Any?>("tag", "t1", tId),
                    listOf<Any?>("tag", "t2", tGoneId),
                ),
                conn.rows("SELECT kind, v2_id, id FROM ks_v2_edge").toSet(),
            )
            // UNIQUE(name) is gone: a second row of a name inserts fine, on both tables.
            conn.createStatement().use { st ->
                st.execute("INSERT INTO tags (id, name, updated_at, created_at) VALUES ('t3', 'T', 30, 3)")
                st.execute("INSERT INTO folders (id, name, updated_at, created_at) VALUES ('d3', 'D', 30, 3)")
            }
            // The name indexes are there, and not unique ("unique" = 0; a UNIQUE(name) autoindex
            // would fail both queries).
            assertEquals(
                listOf(listOf<Any?>("tags_name")),
                conn.rows("SELECT name FROM pragma_index_list('tags') WHERE name = 'tags_name' AND \"unique\" = 0"),
            )
            assertEquals(
                listOf(listOf<Any?>("folders_name")),
                conn.rows("SELECT name FROM pragma_index_list('folders') WHERE name = 'folders_name' AND \"unique\" = 0"),
            )
            // The tag-less link was a violation before and still is; the violations that went away are
            // the dropped feed-less link and the feed's dangling folder reference, which the resolution
            // cleared. Nothing new appeared.
            assertEquals(
                listOf(
                    listOf<Any?>("feed_tags", "feeds"),
                    listOf<Any?>("feed_tags", "tags"),
                    listOf<Any?>("feeds", "folders"),
                ),
                violationsBefore,
            )
            assertEquals(listOf(listOf<Any?>("feed_tags", "tags")), violations())
        }
    }

    /** Running the re-key again changes nothing: every id is already the derived one. */
    @Test
    fun theRekeyIsIdempotent() {
        val file = tempFile()
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        try {
            driver.createSchemaV2()
            listOf(
                "INSERT INTO folders (id, name, updated_at, created_at) VALUES ('d1', 'D', 10, 1)",
                "INSERT INTO feeds (id, url, title, folder_id, updated_at, created_at) VALUES ('f1', 'https://a', 'A', 'd1', 10, 1)",
                "INSERT INTO tags (id, name, updated_at, created_at) VALUES ('t1', 'T', 10, 1)",
                "INSERT INTO feed_tags (feed_id, tag_id, updated_at) VALUES ('f1', 't1', 10)",
            ).forEach { driver.execute(null, it, 0) }
            KeryxDatabase.Schema.migrate(driver, 2, 3, *KERYX_SCHEMA_CALLBACKS)
            rekeyTagAndFolderIds(driver)
        } finally {
            driver.close()
        }
        withConnection(file) { conn ->
            assertEquals(
                setOf(
                    listOf<Any?>("folder", "d1", IdGenerator.folderId("D")),
                    listOf<Any?>("tag", "t1", IdGenerator.tagId("T")),
                ),
                conn.rows("SELECT kind, old_id, new_id FROM ks_id_remap").toSet(),
            )
            assertEquals(
                listOf(
                    listOf<Any?>("folder", "d1", IdGenerator.folderId("D")),
                    listOf<Any?>("tag", "t1", IdGenerator.tagId("T")),
                ),
                conn.rows("SELECT kind, v2_id, id FROM ks_v2_edge ORDER BY kind"),
            )
        }
    }

    /**
     * A row already at its derived id that carries a `v2_id` (a re-key interrupted after the copy, say)
     * gets its identity edge recorded as well, without being moved.
     */
    @Test
    fun theRekeyRecordsTheEdgeOfARowAlreadyAtItsDerivedId() {
        val file = tempFile()
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        try {
            driver.createSchemaV2()
            KeryxDatabase.Schema.migrate(driver, 2, 3, *KERYX_SCHEMA_CALLBACKS)
            val tId = IdGenerator.tagId("T")
            driver.execute(null, "INSERT INTO tags (id, name, updated_at, created_at, v2_id) VALUES ('$tId', 'T', 10, 1, 'old-t')", 0)
            rekeyTagAndFolderIds(driver)
        } finally {
            driver.close()
        }
        withConnection(file) { conn ->
            assertEquals(listOf(listOf<Any?>("tag", "old-t", IdGenerator.tagId("T"))), conn.rows("SELECT kind, v2_id, id FROM ks_v2_edge"))
            assertEquals(emptyList(), conn.rows("SELECT kind, old_id, new_id FROM ks_id_remap"))
        }
    }

    /**
     * The callback fires for a 1 -> 3 migration too (1.sqm then 2.sqm, then the re-key): a v1 file's
     * tags and folders are re-keyed exactly as a v2 file's are.
     */
    @Test
    fun theRekeyAlsoRunsForAOneToThreeMigration() {
        val file = tempFile()
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        try {
            // The version-1 schema is version 2 without the two article-deletion columns 1.sqm adds.
            SCHEMA_V2_STATEMENTS.map { sql ->
                if (sql.contains("CREATE TABLE articles")) {
                    sql.replace(Regex("deleted_at\\s+INTEGER,\\s*deleted_updated_at\\s+INTEGER,\\s*"), "")
                } else {
                    sql
                }
            }.forEach { driver.execute(null, it, 0) }
            driver.execute(null, "PRAGMA user_version = 1", 0)
            driver.execute(
                null,
                "INSERT INTO tags (id, name, updated_at, created_at) VALUES ('t1', 'T', 10, 1)",
                0,
            )
        } finally {
            driver.close()
        }

        DatabaseDriverFactory().createDriver(file).close()

        withConnection(file) { conn ->
            assertEquals(listOf(listOf<Any?>(3)), conn.rows("PRAGMA user_version"))
            assertEquals(
                listOf(listOf<Any?>("tag", "t1", IdGenerator.tagId("T"))),
                conn.rows("SELECT kind, old_id, new_id FROM ks_id_remap"),
            )
            assertEquals(
                listOf(listOf<Any?>(IdGenerator.tagId("T"), "T")),
                conn.rows("SELECT id, name FROM tags"),
            )
        }
    }

    @Test
    fun migrationInitializesEachArticlesBodyStampFromItsUpdatedAt() {
        val file = migratedDb(
            listOf(
                "INSERT INTO feeds (id, url, title, updated_at, created_at) VALUES ('f1', 'https://a', 'A', 0, 0)",
                "INSERT INTO articles (id, feed_id, guid, url, title, content, cached_at, updated_at, created_at) " +
                    "VALUES ('a1', 'f1', 'g1', 'u', 'T', 'c', 0, 900, 0)",
                "INSERT INTO articles (id, feed_id, guid, url, title, cached_at, updated_at, created_at) " +
                    "VALUES ('a2', 'f1', 'g2', 'u', 'T', 0, 300, 0)",
            ),
        )
        withConnection(file) { conn ->
            assertEquals(
                listOf(listOf<Any?>("a1", 900), listOf<Any?>("a2", 300)),
                conn.rows("SELECT id, body_updated_at FROM articles ORDER BY id"),
            )
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

    /** `ks_feed_edge(a, b)` with the composite primary key and its capture trigger, created and migrated alike. */
    @Test
    fun theFeedEdgeTableAndItsTriggerExistInACreatedAndInAMigratedDatabase() {
        for (file in listOf(createdDb(), migratedDb())) {
            withConnection(file) { conn ->
                assertEquals(
                    listOf(listOf<Any?>("a", "TEXT", 1, 1), listOf<Any?>("b", "TEXT", 1, 2)),
                    conn.rows("SELECT name, type, \"notnull\", pk FROM pragma_table_info('ks_feed_edge') ORDER BY cid"),
                )
                assertEquals(
                    listOf(listOf<Any?>("ks_capture_feed_edge_insert")),
                    conn.rows("SELECT name FROM sqlite_master WHERE type = 'trigger' AND tbl_name = 'ks_feed_edge'"),
                )
                conn.createStatement().use { it.execute("INSERT INTO ks_feed_edge (a, b) VALUES ('x', 'y')") }
                assertFailsWith<java.sql.SQLException> {
                    conn.createStatement().use { it.execute("INSERT INTO ks_feed_edge (a, b) VALUES ('x', 'y')") }
                }
                assertEquals(listOf(listOf<Any?>("x", "y")), conn.rows("SELECT a, b FROM ks_feed_edge"))
            }
        }
    }
}
