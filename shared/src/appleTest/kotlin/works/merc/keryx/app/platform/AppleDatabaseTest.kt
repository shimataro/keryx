package works.merc.keryx.app.platform

import app.cash.sqldelight.db.SqlDriver
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import works.merc.keryx.app.core.ArticleFilter
import works.merc.keryx.app.data.local.DatabaseDriverFactory
import works.merc.keryx.app.data.local.DatabaseTooNewException
import works.merc.keryx.app.data.local.FtsManager
import works.merc.keryx.app.data.local.FtsSearch
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.domain.MergeSql
import works.merc.keryx.app.tempFilePath
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Apple database stack against the real system sqlite3: the NativeSqliteDriver setup, FTS5 with
 * the trigram tokenizer and the ATTACH-DATABASE merge — the pieces the
 * JVM tests cover through JDBC. Everything lives in a throwaway directory; nothing here touches
 * [AppDirs.appDataDir].
 */
@OptIn(ExperimentalForeignApi::class)
class AppleDatabaseTest {

    private val dir = tempFilePath("keryx-apple-db-", "").also {
        NSFileManager.defaultManager.createDirectoryAtPath(it, withIntermediateDirectories = true, attributes = null, error = null)
    }
    private val drivers = mutableListOf<SqlDriver>()

    @AfterTest
    fun tearDown() {
        drivers.forEach { it.close() }
        FileSystemExtras.deleteRecursively(dir)
    }

    private fun open(name: String): SqlDriver = DatabaseDriverFactory().createDriver(dir, name).also { drivers += it }

    private fun path(name: String) = FileIO.join(dir, name)

    private fun SqlDriver.insertFeed(id: String) = execute(
        null,
        "INSERT INTO feeds (id, url, title, updated_at, created_at) VALUES ('$id', 'https://ex.com/$id', 'Feed $id', 1, 1)",
        0,
    )

    private fun SqlDriver.insertArticle(id: String, feedId: String, title: String, text: String) = execute(
        null,
        "INSERT INTO articles (id, feed_id, guid, url, title, cached_at, search_text, updated_at, created_at) " +
            "VALUES ('$id', '$feedId', '$id', 'https://ex.com/$id', '$title', 1, '$text', 1, 1)",
        0,
    )

    @Test
    fun aFreshDatabaseIsCreatedAtTheCurrentSchemaVersion() {
        open("fresh.db")
        assertEquals(KeryxDatabase.Schema.version, RawSqliteConnection.userVersionOf(path("fresh.db")))
    }

    @Test
    fun aDatabaseMigratedByANewerBuildIsRefusedUntouched() {
        RawSqliteConnection.open(path("newer.db"), create = true).use { it.exec("PRAGMA user_version = 99") }

        assertFailsWith<DatabaseTooNewException> { DatabaseDriverFactory().createDriver(dir, "newer.db") }
        assertEquals(99, RawSqliteConnection.userVersionOf(path("newer.db")))
    }

    @Test
    fun textWithAnEmbeddedNulIsReadWholeLikeTheJvmDrivers() {
        val expected = "abc\u0000def"
        RawSqliteConnection.open(path("nul.db"), create = true).use { db ->
            val sql = "SELECT 'abc' || char(0) || 'def' AS v"
            assertEquals(listOf(listOf<String?>(expected)), db.queryRowsStrict(sql))
            assertEquals(7, db.queryRowsStrict(sql).single().single()!!.length)
            assertEquals(listOf(expected), db.queryFirstColumnStrict(sql))
            assertEquals(listOf(expected), db.queryColumn(sql, "v"))
        }
    }

    @Test
    fun multibyteTextRoundTripsAndNullStaysNull() {
        val text = "日本語🙂"
        RawSqliteConnection.open(path("text.db"), create = true).use { db ->
            val sql = "SELECT '$text' AS v, NULL AS n, '' AS e"
            assertEquals(listOf(listOf(text, null, "")), db.queryRowsStrict(sql))
            assertEquals(listOf(text), db.queryFirstColumnStrict(sql))
            assertEquals(listOf(text), db.queryColumn(sql, "v"))
            // queryFirstColumnStrict reads NULL as "", and queryColumn skips it.
            assertEquals(listOf(""), db.queryFirstColumnStrict("SELECT NULL"))
            assertEquals(emptyList(), db.queryColumn("SELECT NULL AS n", "n"))
            assertNull(db.queryRowsStrict("SELECT NULL").single().single())
        }
    }

    @Test
    fun trigramFullTextSearchWorksOnTheSystemSqlite() = runTest {
        val driver = open("fts.db")
        driver.insertFeed("f1")
        driver.insertArticle("a1", "f1", "Keryx release notes", "新しいフィードリーダーを公開しました")
        FtsManager(driver).ensureIndexed()

        val hits = FtsSearch(driver).search("フィードリーダー", ArticleFilter.All)

        assertEquals(listOf("a1"), hits.map { it.id })
    }

    @Test
    fun theMergeBringsInCloudOnlyRows() {
        val local = open("main.db")
        val cloud = open("cloud.db")
        cloud.insertFeed("cloud-feed")
        cloud.insertArticle("cloud-article", "cloud-feed", "From the cloud", "")
        local.close()
        cloud.close()
        drivers.clear()

        DatabaseMerger.merge(path("main.db"), path("cloud.db"), KeryxDatabase.Schema.version, MergeSql.all)

        RawSqliteConnection.open(path("main.db")).use { db ->
            assertEquals(1, db.queryLong("SELECT count(*) FROM feeds WHERE id = 'cloud-feed'"))
            assertEquals(1, db.queryLong("SELECT count(*) FROM articles WHERE id = 'cloud-article'"))
        }
    }

    @Test
    fun aCloudFileThatIsNotADatabaseIsClassifiedAsIncompatible() {
        open("main.db").close()
        drivers.clear()
        FileIO.writeText(path("garbage.db"), "this is not a sqlite file, just enough bytes to be read as a header")

        assertFailsWith<works.merc.keryx.app.core.CloudDataIncompatibleException> {
            DatabaseMerger.merge(path("main.db"), path("garbage.db"), KeryxDatabase.Schema.version, MergeSql.all)
        }
    }

    /**
     * A schema-version-1 cloud file: the current schema with `2.sqm` undone and `1.sqm`'s two
     * columns dropped, `user_version` set back to 1, holding one feed and one article.
     */
    private fun writeVersion1CloudFile(name: String) {
        val cloud = open(name)
        cloud.insertFeed("cloud-feed")
        cloud.insertArticle("cloud-article", "cloud-feed", "From an older build", "")
        cloud.close()
        drivers -= cloud
        RawSqliteConnection.open(path(name)).use { db ->
            VERSION_3_TRIGGERS.forEach { db.exec("DROP TRIGGER $it") }
            VERSION_3_TABLES.forEach { db.exec("DROP TABLE $it") }
            db.exec("ALTER TABLE feeds ADD COLUMN etag TEXT")
            db.exec("ALTER TABLE feeds ADD COLUMN last_modified TEXT")
            db.exec("ALTER TABLE feeds ADD COLUMN error_count INTEGER NOT NULL DEFAULT 0")
            db.exec("ALTER TABLE feeds ADD COLUMN last_error TEXT")
            listOf("name_updated_at", "color_updated_at", "sort_order_updated_at").forEach { db.exec("ALTER TABLE tags DROP COLUMN $it") }
            listOf("name_updated_at", "sort_order_updated_at").forEach { db.exec("ALTER TABLE folders DROP COLUMN $it") }
            db.exec("ALTER TABLE articles DROP COLUMN deleted_at")
            db.exec("ALTER TABLE articles DROP COLUMN deleted_updated_at")
            db.exec("ALTER TABLE articles DROP COLUMN body_updated_at")
            db.exec("PRAGMA user_version = 1")
        }
        assertEquals(1, RawSqliteConnection.userVersionOf(path(name)))
    }

    @Test
    fun anOlderCloudFileIsMigratedBeforeTheMerge() {
        open("main.db").close()
        drivers.clear()
        writeVersion1CloudFile("cloud-v1.db")

        DatabaseMerger.merge(path("main.db"), path("cloud-v1.db"), KeryxDatabase.Schema.version, MergeSql.all)

        assertEquals(KeryxDatabase.Schema.version, RawSqliteConnection.userVersionOf(path("cloud-v1.db")))
        val cloudColumns = RawSqliteConnection.open(path("cloud-v1.db")).use {
            it.queryColumn("PRAGMA table_info(articles)", "name")
        }
        assertTrue("deleted_at" in cloudColumns)
        assertTrue("deleted_updated_at" in cloudColumns)
        RawSqliteConnection.open(path("main.db")).use { db ->
            assertEquals(1, db.queryLong("SELECT count(*) FROM articles WHERE id = 'cloud-article' AND deleted_at IS NULL"))
        }
    }

    @Test
    fun aCorruptCloudFileThatFailsDuringMigrationIsClassifiedAsIncompatible() {
        open("main.db").close()
        drivers.clear()
        writeVersion1CloudFile("corrupt-v1.db")
        // Garble page 1's b-tree (everything after the 100-byte file header, which keeps
        // user_version readable): the version check still sees 1, so the failure surfaces only once
        // NativeSqliteDriver opens the file to migrate it.
        assertPageSize(path("corrupt-v1.db"))
        val bytes = FileIO.readBytes(path("corrupt-v1.db"))!!
        for (i in 100 until minOf(bytes.size, PAGE_SIZE)) bytes[i] = 0xFF.toByte()
        FileIO.writeBytes(path("corrupt-v1.db"), bytes)
        assertEquals(1, RawSqliteConnection.userVersionOf(path("corrupt-v1.db")))

        assertFailsWith<works.merc.keryx.app.core.CloudDataIncompatibleException> {
            DatabaseMerger.merge(path("main.db"), path("corrupt-v1.db"), KeryxDatabase.Schema.version, MergeSql.all)
        }
    }

    /** Asserts the file's page size is [PAGE_SIZE], the offset the corruption helpers assume. */
    private fun assertPageSize(file: String) {
        assertEquals(PAGE_SIZE.toLong(), RawSqliteConnection.open(file).use { it.queryLong("PRAGMA page_size") }, "the corruption helpers assume this page size")
    }

    /**
     * A corrupt *local* database raises SQLITE_CORRUPT during the merge, but the downloaded cloud file
     * passes its quick_check, so the corruption is local: the original failure is rethrown, never a
     * cloud-data reset.
     */
    @Test
    fun aCorruptLocalDatabaseIsNotBlamedOnAnIntactCloudFile() {
        for (name in listOf("main.db", "cloud.db")) {
            val driver = open(name)
            driver.insertFeed("f1")
            driver.close()
            drivers -= driver
        }
        assertPageSize(path("main.db"))
        val bytes = FileIO.readBytes(path("main.db"))!!
        assertTrue(bytes.size > PAGE_SIZE, "the file must have a page past the first to corrupt")
        for (i in PAGE_SIZE until bytes.size) bytes[i] = bytes[i].inc()
        FileIO.writeBytes(path("main.db"), bytes)

        val thrown = assertFailsWith<SqliteException> {
            DatabaseMerger.merge(path("main.db"), path("cloud.db"), KeryxDatabase.Schema.version, MergeSql.all)
        }
        assertEquals(SQLITE_CORRUPT, thrown.resultCode and 0xFF, "got ${thrown.resultCode}: ${thrown.message}")
    }

    /**
     * Two schema files, `main.db` and `cloud.db`, each holding a folder with id `dup`; [seedLocal]
     * then runs on a raw connection to `main.db` with foreign keys OFF (so it can plant a violation).
     */
    private fun writeFolderCollision(seedLocal: (RawSqliteConnection) -> Unit = {}) {
        for (name in listOf("main.db", "cloud.db")) {
            open(name).close()
        }
        drivers.clear()
        for (name in listOf("main.db", "cloud.db")) {
            RawSqliteConnection.open(path(name)).use { db ->
                db.exec("INSERT INTO folders (id, name, sort_order, deleted_at, updated_at, created_at) VALUES ('dup', '$name', 0, NULL, 0, 0)")
            }
        }
        RawSqliteConnection.open(path("main.db")).use(seedLocal)
    }

    private fun RawSqliteConnection.insertOrphanArticle() = exec(
        "INSERT INTO articles (id, feed_id, guid, url, title, cached_at, updated_at, created_at) " +
            "VALUES ('orphan', 'no-such-feed', 'g', 'https://ex.com/a', 'Orphan', 0, 0, 0)",
    )

    @Test
    fun aConstraintFailureWithASoundLocalDatabaseIsClassifiedAsIncompatible() {
        writeFolderCollision()

        assertFailsWith<works.merc.keryx.app.core.CloudDataIncompatibleException> {
            DatabaseMerger.merge(path("main.db"), path("cloud.db"), KeryxDatabase.Schema.version, listOf(UNGUARDED_FOLDER_INSERT))
        }
    }

    @Test
    fun aForeignKeyViolationAlreadyInTheLocalDatabaseIsNotBlamedOnTheCloud() {
        writeFolderCollision { it.insertOrphanArticle() }

        val thrown = assertFailsWith<SqliteException> {
            // Re-assigning the FK column makes SQLite check the orphan's missing parent.
            DatabaseMerger.merge(
                path("main.db"),
                path("cloud.db"),
                KeryxDatabase.Schema.version,
                listOf("UPDATE main.articles SET feed_id = feed_id"),
            )
        }
        assertEquals(SQLITE_CONSTRAINT_FOREIGNKEY, thrown.resultCode)
    }

    @Test
    fun aCloudPrimaryKeyCollisionIsClassifiedAsIncompatibleDespiteALocalForeignKeyViolation() {
        writeFolderCollision { it.insertOrphanArticle() }

        // The re-run with foreign keys deferred fails on the same collision, pinning it on the cloud.
        assertFailsWith<works.merc.keryx.app.core.CloudDataIncompatibleException> {
            DatabaseMerger.merge(path("main.db"), path("cloud.db"), KeryxDatabase.Schema.version, listOf(UNGUARDED_FOLDER_INSERT))
        }
    }

    @Test
    fun aNewForeignKeyViolationFromTheCloudRowsIsClassifiedAsIncompatibleDespiteALocalOne() {
        writeFolderCollision { it.insertOrphanArticle() }
        RawSqliteConnection.open(path("cloud.db")).use { db ->
            db.exec("INSERT INTO feeds (id, url, title, updated_at, created_at) VALUES ('f1', 'https://ex.com/f1', 'Feed f1', 1, 1)")
            db.exec(
                "INSERT INTO articles (id, feed_id, guid, url, title, cached_at, updated_at, created_at) " +
                    "VALUES ('cloud-article', 'f1', 'c', 'https://ex.com/c', 'Cloud', 0, 0, 0)",
            )
        }

        // The cloud article's feed exists nowhere in main: the re-run with foreign keys deferred
        // leaves a violation main did not hold before.
        assertFailsWith<works.merc.keryx.app.core.CloudDataIncompatibleException> {
            DatabaseMerger.merge(path("main.db"), path("cloud.db"), KeryxDatabase.Schema.version, listOf(UNGUARDED_ARTICLE_INSERT))
        }
    }

    @Test
    fun aConstraintFailureAfterSqliteEndedTheTransactionIsNotClassified() {
        writeFolderCollision()

        // The statement's own OR ROLLBACK ends the merge transaction, so a foreign_key_check could no
        // longer run inside it: undetermined, never classified, though the local database is sound.
        val thrown = assertFailsWith<SqliteException> {
            DatabaseMerger.merge(
                path("main.db"),
                path("cloud.db"),
                KeryxDatabase.Schema.version,
                listOf(UNGUARDED_FOLDER_INSERT.replace("INSERT INTO", "INSERT OR ROLLBACK INTO")),
            )
        }
        assertEquals(SQLITE_CONSTRAINT_PRIMARYKEY, thrown.resultCode)
    }

    /** Two empty schema files, `main.db` and `cloud.db`, then [seedLocal] / [seedCloud] on raw connections (foreign keys OFF). */
    private fun writeSchemaFiles(
        seedLocal: (RawSqliteConnection) -> Unit = {},
        seedCloud: (RawSqliteConnection) -> Unit = {},
    ) {
        for (name in listOf("main.db", "cloud.db")) {
            open(name).close()
        }
        drivers.clear()
        RawSqliteConnection.open(path("main.db")).use(seedLocal)
        RawSqliteConnection.open(path("cloud.db")).use(seedCloud)
    }

    @Test
    fun aFailureAtALaterStatementRerunsOnlyFromThatStatementAndStaysUnclassified() {
        writeSchemaFiles(
            seedLocal = { it.insertOrphanArticle() },
            seedCloud = { it.exec("INSERT INTO folders (id, name, sort_order, deleted_at, updated_at, created_at) VALUES ('cloud-only', 'Cloud', 0, NULL, 0, 0)") },
        )

        // Statement 0 succeeds and is not idempotent; the re-run must start at statement 1. Starting at
        // statement 0 again would hit the folder's PRIMARY KEY and read as the cloud's failure.
        val thrown = assertFailsWith<SqliteException> {
            DatabaseMerger.merge(
                path("main.db"),
                path("cloud.db"),
                KeryxDatabase.Schema.version,
                listOf(UNGUARDED_FOLDER_INSERT, ORPHAN_TOUCHING_UPDATE),
            )
        }
        assertEquals(SQLITE_CONSTRAINT_FOREIGNKEY, thrown.resultCode)
    }

    @Test
    fun aNonConstraintFailureDuringTheRerunIsUnclassifiedAndAttachedAsSuppressed() {
        writeSchemaFiles(seedLocal = { it.insertOrphanArticle() })

        val thrown = assertFailsWith<SqliteException> {
            DatabaseMerger.merge(
                path("main.db"),
                path("cloud.db"),
                KeryxDatabase.Schema.version,
                // Statement 1 is only reached by the re-run: statement 0 fails first originally.
                listOf(ORPHAN_TOUCHING_UPDATE, "INSERT INTO main.no_such_table SELECT * FROM cloud.folders"),
            )
        }
        assertEquals(SQLITE_CONSTRAINT_FOREIGNKEY, thrown.resultCode)
        assertTrue(
            thrown.suppressedExceptions.any { it is SqliteException && (it.resultCode and 0xFF) == SQLITE_ERROR },
            "the re-run's failure must be attached as suppressed, got ${thrown.suppressedExceptions}",
        )
    }

    @Test
    fun aFailingForeignKeyCheckLeavesTheFailureUnclassifiedAndAttachedAsSuppressed() {
        writeSchemaFiles(seedLocal = { it.insertOrphanArticle() })

        val thrown = assertFailsWith<SqliteException> {
            DatabaseMerger.merge(
                path("main.db"),
                path("cloud.db"),
                KeryxDatabase.Schema.version,
                listOf(
                    // feeds.folder_id references folders(id); a parent without a UNIQUE id is a mismatch
                    // that makes foreign_key_check itself fail (DDL is transactional).
                    "DROP TABLE main.folders",
                    "CREATE TABLE main.folders (id TEXT)",
                    ORPHAN_TOUCHING_UPDATE,
                ),
            )
        }
        assertEquals(SQLITE_CONSTRAINT_FOREIGNKEY, thrown.resultCode)
        assertTrue(
            thrown.suppressedExceptions.any { "foreign key mismatch" in it.message.orEmpty() },
            "the failed check must be attached as suppressed, got ${thrown.suppressedExceptions}",
        )
    }

    /**
     * A PRIMARY KEY violation (RawSqliteConnection's extended code says it is not a FOREIGN KEY one)
     * rules main out without reading main's FOREIGN KEY violations at all: the statements before it
     * make `PRAGMA main.foreign_key_check` itself fail ("foreign key mismatch"), and main holds an
     * orphan so that check would otherwise have found a violation and led to the re-run. Read, it
     * would fail and leave the failure unclassified; skipped, the cloud's colliding tag is classified.
     */
    @Test
    fun aNonForeignKeyConstraintFailureIsClassifiedWithoutReadingMainsViolations() {
        writeSchemaFiles(
            seedLocal = { db ->
                db.insertTag("dup", "Local")
                db.insertOrphanArticle()
            },
            seedCloud = { db -> db.insertTag("dup", "Cloud") },
        )

        assertFailsWith<works.merc.keryx.app.core.CloudDataIncompatibleException> {
            DatabaseMerger.merge(
                path("main.db"),
                path("cloud.db"),
                KeryxDatabase.Schema.version,
                listOf(
                    // feeds.folder_id references folders(id); a parent without a UNIQUE id is a mismatch
                    // that makes foreign_key_check itself fail (DDL is transactional).
                    "DROP TABLE main.folders",
                    "CREATE TABLE main.folders (id TEXT)",
                    UNGUARDED_TAG_INSERT,
                ),
            )
        }
    }

    private fun RawSqliteConnection.insertTag(id: String, name: String) =
        exec("INSERT INTO tags (id, name, sort_order, deleted_at, updated_at, created_at) VALUES ('$id', '$name', 0, NULL, 0, 0)")

    @Test
    fun aTransactionEndedBySqliteDuringTheRerunLeavesTheFailureUnclassified() {
        writeFolderCollision { it.insertOrphanArticle() }

        // The INSERT OR ROLLBACK is only reached by the re-run, where it ends the merge transaction by
        // itself: its duplicate-key failure would read as the cloud's, but the verdict is dropped.
        val thrown = assertFailsWith<SqliteException> {
            DatabaseMerger.merge(
                path("main.db"),
                path("cloud.db"),
                KeryxDatabase.Schema.version,
                listOf(ORPHAN_TOUCHING_UPDATE, UNGUARDED_FOLDER_INSERT.replace("INSERT INTO", "INSERT OR ROLLBACK INTO")),
            )
        }
        assertEquals(SQLITE_CONSTRAINT_FOREIGNKEY, thrown.resultCode)
    }

    @Test
    fun cloudRowsViolatingTheAppSchemaAreClassifiedAsIncompatibleDespiteALocalForeignKeyViolation() {
        writeSchemaFiles(
            seedLocal = { it.insertOrphanArticle() },
            seedCloud = { db ->
                db.exec("DROP TABLE feeds")
                db.exec(LAX_FEEDS_TABLE)
                db.exec("INSERT INTO feeds (id, url, title, updated_at, created_at) VALUES ('f-null-title', 'https://lax.example/feed', NULL, 0, 0)")
            },
        )

        // A cloud feed has a NULL title, which only the cloud file's laxer feeds table allowed.
        assertFailsWith<works.merc.keryx.app.core.CloudDataIncompatibleException> {
            DatabaseMerger.merge(path("main.db"), path("cloud.db"), KeryxDatabase.Schema.version, MergeSql.all)
        }
    }

    @Test
    fun theSchemaCheckRecognisesTheAppsOwnSchema() {
        open("schema.db").close()
        drivers.clear()
        assertEquals(true, DatabaseMerger.validateSchema(path("schema.db"), KeryxDatabase.Schema.version))
    }

    private companion object {
        /** The tables `2.sqm` creates (schema version 3), dropped to roll a file back to version 1. */
        val VERSION_3_TABLES = listOf(
            "feed_fetch_state", "sync_outbox", "sync_capture", "sync_merging", "sync_applied", "sync_cursor", "ks_search_pending",
            "ks_feed_folder_raw",
        )

        /** The capture triggers `2.sqm` creates, dropped before the tables they write to. */
        val VERSION_3_TRIGGERS = listOf("feeds", "articles", "tags", "folders", "feed_tags", "global_settings")
            .flatMap { listOf("ks_capture_${it}_insert", "ks_capture_${it}_update") }

        /** Copies every cloud folder without MergeSql's guards, so a shared id hits the PRIMARY KEY. */
        const val UNGUARDED_FOLDER_INSERT =
            "INSERT INTO main.folders (id, name, sort_order, deleted_at, updated_at, created_at) " +
                "SELECT id, name, sort_order, deleted_at, updated_at, created_at FROM cloud.folders"

        /** Copies every cloud tag without MergeSql's guards, so a shared id (under different names) hits the PRIMARY KEY. */
        const val UNGUARDED_TAG_INSERT =
            "INSERT INTO main.tags (id, name, sort_order, deleted_at, updated_at, created_at) " +
                "SELECT id, name, sort_order, deleted_at, updated_at, created_at FROM cloud.tags"

        /** Copies every cloud article without its feed, so one whose feed main lacks breaks the FOREIGN KEY. */
        const val UNGUARDED_ARTICLE_INSERT =
            "INSERT INTO main.articles (id, feed_id, guid, url, title, cached_at, updated_at, created_at) " +
                "SELECT id, feed_id, guid, url, title, cached_at, updated_at, created_at FROM cloud.articles"

        /** Re-assigning the FK column makes SQLite check the (missing) parent of the orphan main holds. */
        const val ORPHAN_TOUCHING_UPDATE = "UPDATE main.articles SET feed_id = feed_id"

        /** The app's feeds table without its UNIQUE(url) and with a nullable title, so a cloud file can hold rows the app's schema forbids. */
        const val LAX_FEEDS_TABLE =
            "CREATE TABLE feeds (id TEXT NOT NULL PRIMARY KEY, url TEXT NOT NULL, site_url TEXT, " +
                "title TEXT, description TEXT, favicon_url TEXT, custom_title TEXT, folder_id TEXT, " +
                "deleted_at INTEGER, updated_at INTEGER NOT NULL, created_at INTEGER NOT NULL, " +
                "sort_order INTEGER NOT NULL DEFAULT 0, folder_updated_at INTEGER, " +
                "sort_order_updated_at INTEGER, custom_title_updated_at INTEGER, deleted_updated_at INTEGER)"

        /** The page size the corruption helpers start at; asserted against the real file. */
        const val PAGE_SIZE = 4096
        const val SQLITE_ERROR = 1
        const val SQLITE_CORRUPT = 11

        /** Extended result codes (`SQLITE_CONSTRAINT | n << 8`). */
        const val SQLITE_CONSTRAINT_FOREIGNKEY = 787
        const val SQLITE_CONSTRAINT_PRIMARYKEY = 1555
    }
}
