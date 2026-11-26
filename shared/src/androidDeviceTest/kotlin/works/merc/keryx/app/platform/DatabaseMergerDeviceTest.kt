package works.merc.keryx.app.platform

import io.requery.android.database.sqlite.SQLiteDatabase
import works.merc.keryx.app.SCHEMA_V2_STATEMENTS
import works.merc.keryx.app.core.SchemaVersionException
import works.merc.keryx.app.createSchemaDbFile
import works.merc.keryx.app.deleteDbFiles
import works.merc.keryx.app.domain.IdGenerator
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Instrumented counterpart of `desktopTest`'s `SyncMergerTest`/`DatabaseMergerTest` — exercises
 * [DatabaseMerger] against the real bundled SQLite (ATTACH/transaction/DETACH via a dedicated
 * `io.requery.android.database.sqlite.SQLiteDatabase` connection), which cannot be exercised from
 * a plain JVM unit test.
 *
 * Deliberately does not re-verify the merge SQL semantics [MergeSql] already covers in
 * `desktopTest` — that logic is pure SQL, identical on every platform. This only exercises the
 * parts that are genuinely Android-specific: the schema-version guard, the migration path, and
 * (in [MergeFailureClassificationDeviceTest]) exception-class-based failure classification.
 */
class DatabaseMergerDeviceTest {
    private val cleanup = mutableListOf<File>()

    @AfterTest
    fun tearDown() {
        cleanup.forEach { it.deleteDbFiles() }
    }

    private fun seedFolder(dbPath: String, id: String, name: String) {
        SQLiteDatabase.openOrCreateDatabase(dbPath, null, NoOpDatabaseErrorHandler).use { db ->
            db.execSQL(
                "INSERT INTO folders (id, name, sort_order, deleted_at, updated_at, created_at) VALUES (?, ?, 0, NULL, 0, 0)",
                arrayOf<Any?>(id, name),
            )
        }
    }

    private fun folderNames(dbPath: String): Set<String> =
        SQLiteDatabase.openOrCreateDatabase(dbPath, null, NoOpDatabaseErrorHandler).use { db ->
            db.rawQuery("SELECT name FROM folders", null).use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
        }

    /** A minimal, realistic stand-in for one of `MergeSql`'s real OR-merge statements. */
    private val folderMergeStatement =
        "INSERT INTO folders (id, name, sort_order, deleted_at, updated_at, created_at) " +
            "SELECT id, name, sort_order, deleted_at, updated_at, created_at FROM cloud.folders " +
            "WHERE NOT EXISTS (SELECT 1 FROM folders WHERE folders.id = cloud.folders.id)"

    @Test
    fun normalMergeCommitsCloudOnlyRows() {
        val localFile = createSchemaDbFile().also { cleanup += it }
        val cloudFile = createSchemaDbFile().also { cleanup += it }
        seedFolder(cloudFile.absolutePath, "cloud-folder", "From Cloud")

        DatabaseMerger.merge(
            localDbPath = localFile.absolutePath,
            cloudDbPath = cloudFile.absolutePath,
            localSchemaVersion = SCHEMA_VERSION,
            mergeStatements = listOf(folderMergeStatement),
        )

        assertEquals(setOf("From Cloud"), folderNames(localFile.absolutePath))
    }

    @Test
    fun newerCloudSchemaThrowsSchemaVersionException() {
        val localFile = createSchemaDbFile().also { cleanup += it }
        val cloudFile = createSchemaDbFile().also { cleanup += it }
        SQLiteDatabase.openOrCreateDatabase(cloudFile.absolutePath, null, NoOpDatabaseErrorHandler).use { db ->
            db.execSQL("PRAGMA user_version=${SCHEMA_VERSION + 1}")
        }

        assertFailsWith<SchemaVersionException> {
            DatabaseMerger.merge(
                localDbPath = localFile.absolutePath,
                cloudDbPath = cloudFile.absolutePath,
                localSchemaVersion = SCHEMA_VERSION,
                mergeStatements = listOf(folderMergeStatement),
            )
        }
    }

    @Test
    fun replayingMergeWithSameCloudSnapshotMakesNoFurtherChanges() {
        val localFile = createSchemaDbFile().also { cleanup += it }
        val cloudFile = createSchemaDbFile().also { cleanup += it }
        seedFolder(cloudFile.absolutePath, "cloud-folder", "From Cloud")

        repeat(2) {
            DatabaseMerger.merge(
                localDbPath = localFile.absolutePath,
                cloudDbPath = cloudFile.absolutePath,
                localSchemaVersion = SCHEMA_VERSION,
                mergeStatements = listOf(folderMergeStatement),
            )
        }

        assertEquals(setOf("From Cloud"), folderNames(localFile.absolutePath))
    }

    @Test
    fun olderCloudSchemaIsMigratedInPlaceThenMerged() {
        val localFile = createSchemaDbFile().also { cleanup += it }
        val cloudFile = createSchemaDbFile().also { cleanup += it }
        seedFolder(cloudFile.absolutePath, "cloud-folder", "From Old Cloud")
        // Roll the cloud DB back to a genuinely v1-shaped schema (undoing db/2.sqm, then db/1.sqm's
        // `articles` columns) so migrateCloudIfOlder's real KeryxDatabase.Schema.migrate(driver, 1, 3)
        // call has actual work to do, rather than re-creating objects that already exist (which
        // would fail).
        SQLiteDatabase.openOrCreateDatabase(cloudFile.absolutePath, null, NoOpDatabaseErrorHandler).use { db ->
            VERSION_3_TRIGGERS.forEach { db.execSQL("DROP TRIGGER $it") }
            VERSION_3_VIEWS.forEach { db.execSQL("DROP VIEW $it") }
            VERSION_3_TABLES.forEach { db.execSQL("DROP TABLE $it") }
            db.execSQL("ALTER TABLE feeds ADD COLUMN etag TEXT")
            db.execSQL("ALTER TABLE feeds ADD COLUMN last_modified TEXT")
            db.execSQL("ALTER TABLE feeds ADD COLUMN error_count INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE feeds ADD COLUMN last_error TEXT")
            listOf("name_updated_at", "color_updated_at", "sort_order_updated_at").forEach {
                db.execSQL("ALTER TABLE tags DROP COLUMN $it")
            }
            listOf("name_updated_at", "sort_order_updated_at").forEach { db.execSQL("ALTER TABLE folders DROP COLUMN $it") }
            db.execSQL("ALTER TABLE articles DROP COLUMN deleted_at")
            db.execSQL("ALTER TABLE articles DROP COLUMN deleted_updated_at")
            db.execSQL("ALTER TABLE articles DROP COLUMN body_updated_at")
            db.execSQL("PRAGMA user_version=1")
        }

        DatabaseMerger.merge(
            localDbPath = localFile.absolutePath,
            cloudDbPath = cloudFile.absolutePath,
            localSchemaVersion = SCHEMA_VERSION,
            mergeStatements = listOf(folderMergeStatement),
        )

        assertEquals(setOf("From Old Cloud"), folderNames(localFile.absolutePath))
        val cloudVersionAfter = SQLiteDatabase.openOrCreateDatabase(cloudFile.absolutePath, null, NoOpDatabaseErrorHandler)
            .use { it.userVersion() }
        assertEquals(SCHEMA_VERSION, cloudVersionAfter, "the cloud file itself is migrated in place, matching the desktop actual")
    }

    /**
     * A schema-version-2 cloud file migrated in place by the merge gets the `AfterVersion(2)` re-key
     * (`KERYX_SCHEMA_CALLBACKS`) too: its folder and tag move onto the ids their names derive, the
     * feed's folder and the tag link follow, the moves are recorded in `ks_id_remap` / `ks_v2_edge`,
     * and the merge then brings the folder into the local file under that derived id.
     */
    @Test
    fun aVersionTwoCloudFileIsRekeyedOntoTheDerivedIdsThenMerged() {
        val localFile = createSchemaDbFile().also { cleanup += it }
        val cloudFile = createSchemaDbFile().also { cleanup += it }
        SQLiteDatabase.openOrCreateDatabase(cloudFile.absolutePath, null, NoOpDatabaseErrorHandler).use { db ->
            // Replace the current-schema file with a genuinely version-2 one.
            val tables = db.rows("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'")
            val triggers = db.rows("SELECT name FROM sqlite_master WHERE type = 'trigger'")
            val views = db.rows("SELECT name FROM sqlite_master WHERE type = 'view'")
            triggers.forEach { db.execSQL("DROP TRIGGER $it") }
            views.forEach { db.execSQL("DROP VIEW $it") }
            tables.forEach { db.execSQL("DROP TABLE $it") }
            SCHEMA_V2_STATEMENTS.forEach { db.execSQL(it) }
            db.execSQL("INSERT INTO folders (id, name, sort_order, updated_at, created_at) VALUES ('d1', 'D', 0, 10, 1)")
            db.execSQL("INSERT INTO tags (id, name, updated_at, created_at) VALUES ('t1', 'T', 10, 1)")
            db.execSQL("INSERT INTO feeds (id, url, title, folder_id, updated_at, created_at) VALUES ('f1', 'https://a', 'A', 'd1', 10, 1)")
            db.execSQL("INSERT INTO feed_tags (feed_id, tag_id, updated_at) VALUES ('f1', 't1', 10)")
            db.execSQL("PRAGMA user_version=2")
        }

        DatabaseMerger.merge(
            localDbPath = localFile.absolutePath,
            cloudDbPath = cloudFile.absolutePath,
            localSchemaVersion = SCHEMA_VERSION,
            mergeStatements = listOf(folderMergeStatement),
        )

        val dId = IdGenerator.folderId("D")
        val tId = IdGenerator.tagId("T")
        assertEquals(setOf("D"), folderNames(localFile.absolutePath))
        SQLiteDatabase.openOrCreateDatabase(localFile.absolutePath, null, NoOpDatabaseErrorHandler).use { db ->
            assertEquals(listOf(dId), db.rows("SELECT id FROM folders"))
        }
        SQLiteDatabase.openOrCreateDatabase(cloudFile.absolutePath, null, NoOpDatabaseErrorHandler).use { db ->
            assertEquals(SCHEMA_VERSION, db.userVersion())
            assertEquals(listOf(dId, tId), db.rows("SELECT id FROM folders UNION ALL SELECT id FROM tags"))
            assertEquals(listOf(dId), db.rows("SELECT folder_id FROM feeds"))
            assertEquals(listOf(tId), db.rows("SELECT tag_id FROM feed_tags"))
            assertEquals(
                setOf("folder:d1:$dId", "tag:t1:$tId"),
                db.rows("SELECT kind || ':' || old_id || ':' || new_id FROM ks_id_remap").toSet(),
            )
            assertEquals(
                setOf("folder:d1:$dId", "tag:t1:$tId"),
                db.rows("SELECT kind || ':' || v2_id || ':' || id FROM ks_v2_edge").toSet(),
            )
        }
    }

    private fun SQLiteDatabase.rows(sql: String): List<String> =
        rawQuery(sql, null).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    private companion object {
        /**
         * The tables `2.sqm` creates (schema version 3), dropped to roll a file back to version 1. A
         * table added to `2.sqm` must be added here too, or the rolled-back file still holds it and
         * the migration's `CREATE TABLE` fails.
         */
        val VERSION_3_TABLES = listOf(
            "feed_fetch_state", "sync_outbox", "sync_capture", "sync_merging", "sync_applied", "sync_cursor", "ks_search_pending",
            "ks_search_awaiting_body", "ks_feed_folder_raw", "ks_id_remap", "ks_v2_edge",
        )

        /** The triggers `2.sqm` creates, dropped before the tables they read and write. */
        val VERSION_3_TRIGGERS = listOf("feeds", "articles", "tags", "folders", "feed_tags", "global_settings")
            .flatMap { listOf("ks_capture_${it}_insert", "ks_capture_${it}_update") } + listOf(
            "ks_capture_feed_folder_raw_update", "ks_feed_folder_raw_init", "ks_feed_folder_resolve_raw_insert",
            "ks_feed_folder_resolve_raw_update", "ks_feed_folder_resolve_folder",
            "ks_feed_folder_resolve_folder_insert", "ks_feed_folder_resolve_remap_insert",
            "ks_feed_folder_resolve_remap_update", "ks_feed_folder_resolve_remap_delete",
        )

        /** The views `2.sqm` creates, dropped before the tables they read. */
        val VERSION_3_VIEWS = listOf("ks_feed_folder_resolved")

        /** Must stay a version actually registered in [works.merc.keryx.app.domain.MergeSchema.EXPECTED_SCHEMAS]. */
        val SCHEMA_VERSION = works.merc.keryx.app.data.local.db.KeryxDatabase.Schema.version
    }
}
