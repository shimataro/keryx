package works.merc.keryx.app.platform

import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteException
import works.merc.keryx.app.core.CloudDataIncompatibleException
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.domain.MergeSchema
import works.merc.keryx.app.domain.MergeSql
import works.merc.keryx.app.fileDb
import works.merc.keryx.app.insertFeed
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies [DatabaseMerger.validateSchema] tells apart "inspection completed and found the schema
 * invalid" ([Boolean] `false`) from "inspection itself failed" ([Boolean]`?` `null`) — conflating
 * the two would let a transient open/IO failure trigger the same destructive cloud-data-reset path
 * as a genuinely incompatible schema (see [works.merc.keryx.app.domain.MergeFailureClassifier]).
 */
class DatabaseMergerTest {

    @Test
    fun validSchemaReturnsTrue() {
        val (file, driver, _) = fileDb()
        try {
            assertTrue(DatabaseMerger.validateSchema(file.absolutePath, SCHEMA_VERSION) == true)
        } finally {
            driver.close()
            file.delete()
        }
    }

    /**
     * Guards against `MergeSchema.EXPECTED_SCHEMAS` drifting from the actual `.sq` schema: if the
     * currently-running schema version has no registered expectation, [validateSchema] silently
     * returns `null` (undetermined) for every real cloud file this build ever downloads, instead
     * of `true`/`false` — a maintainer forgetting to update `EXPECTED_SCHEMAS` after bumping the
     * schema would only be caught here, not by [validSchemaReturnsTrue] above (which would keep
     * exercising whatever version [SCHEMA_VERSION] happens to hardcode).
     */
    @Test
    fun currentSchemaVersionIsRegisteredInExpectedSchemas() {
        assertTrue(MergeSchema.EXPECTED_SCHEMAS.containsKey(KeryxDatabase.Schema.version))
    }

    @Test
    fun missingTableReturnsFalseWhenInspectionCompletes() {
        // An empty file is a valid (empty) SQLite database: opening it succeeds, but none of the
        // expected tables exist, so PRAGMA table_info returns no rows for any of them.
        val file = File.createTempFile("keryx-merger-test-", ".db").apply { deleteOnExit() }
        try {
            assertFalse(DatabaseMerger.validateSchema(file.absolutePath, SCHEMA_VERSION) ?: error("expected false"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun failedInspectionReturnsNullRatherThanFalse() {
        // A directory cannot be opened as a SQLite database, so the connection attempt itself
        // throws — this must be undetermined (null), not "confirmed invalid" (false).
        val directory = File.createTempFile("keryx-merger-test-dir-", "").apply {
            delete()
            mkdir()
            deleteOnExit()
        }
        try {
            assertNull(DatabaseMerger.validateSchema(directory.absolutePath, SCHEMA_VERSION))
        } finally {
            directory.delete()
        }
    }

    @Test
    fun unregisteredSchemaVersionReturnsNull() {
        assertEquals(null, DatabaseMerger.validateSchema("irrelevant", schemaVersion = Long.MAX_VALUE))
    }

    // --- Merge failure classification: pinning a constraint/corruption failure on the cloud ---

    /**
     * A FOREIGN KEY violation already present in the local DB (an orphan article, left by data
     * from before foreign keys were enforced) surfaces once a merge statement touches it. The
     * merge's in-transaction `foreign_key_check` finds it, so the failure must not be blamed on the
     * cloud — it would offer the destructive cloud-data reset for a local fault.
     */
    @Test
    fun preExistingLocalForeignKeyViolationExposedByTheMergeIsNotBlamedOnTheCloud() {
        val cloudFile = soundDbFile()
        val localFile = soundDbFile { driver -> insertOrphanArticle(driver) }

        val thrown = assertFailsWith<SQLiteException> {
            DatabaseMerger.merge(
                localFile.absolutePath,
                cloudFile.absolutePath,
                SCHEMA_VERSION,
                // Re-assigning the FK column makes SQLite check the (missing) parent of the
                // orphan main already holds.
                listOf("UPDATE main.articles SET feed_id = feed_id"),
            )
        }
        assertEquals(SQLiteErrorCode.SQLITE_CONSTRAINT_FOREIGNKEY, thrown.resultCode)
    }

    /**
     * A FOREIGN KEY violation the local DB already holds (an orphan article) has nothing to do with a
     * PRIMARY KEY collision the cloud rows cause: the re-run with foreign keys deferred fails on the
     * same collision, which pins the failure on the cloud.
     */
    @Test
    fun cloudPrimaryKeyViolationIsCloudDataIncompatibleDespiteALocalForeignKeyViolation() {
        val cloudFile = soundDbFile { driver -> insertFolder(driver, "dup", "Cloud") }
        val localFile = soundDbFile { driver ->
            insertFolder(driver, "dup", "Local")
            insertOrphanArticle(driver)
        }

        assertFailsWith<CloudDataIncompatibleException> {
            DatabaseMerger.merge(localFile.absolutePath, cloudFile.absolutePath, SCHEMA_VERSION, listOf(UNGUARDED_FOLDER_INSERT))
        }
    }

    /**
     * A cloud article whose feed exists nowhere in main is a FOREIGN KEY violation of the cloud rows'
     * own, even while main holds an unrelated one: the re-run with foreign keys deferred leaves a
     * violation that was not there before.
     */
    @Test
    fun newForeignKeyViolationFromTheCloudRowsIsCloudDataIncompatibleDespiteALocalOne() {
        val cloudFile = soundDbFile { driver ->
            insertFeedRow(driver)
            insertArticle(driver, id = "cloud-article", feedId = "f1")
        }
        val localFile = soundDbFile { driver -> insertOrphanArticle(driver) }

        assertFailsWith<CloudDataIncompatibleException> {
            DatabaseMerger.merge(localFile.absolutePath, cloudFile.absolutePath, SCHEMA_VERSION, listOf(UNGUARDED_ARTICLE_INSERT))
        }
    }

    /**
     * The real merge statements against a cloud DB whose own rows break this app's schema (two feeds
     * sharing a url, which only the cloud file's laxer feeds table allowed) are still classified while
     * main holds an unrelated FOREIGN KEY violation.
     */
    @Test
    fun cloudRowsViolatingTheAppSchemaAreCloudDataIncompatibleDespiteALocalForeignKeyViolation() {
        val cloudFile = soundDbFile { driver ->
            driver.execute(null, "DROP TABLE feeds", 0)
            driver.execute(null, LAX_FEEDS_TABLE, 0)
            for (id in listOf("f-dup-1", "f-dup-2")) {
                driver.execute(
                    null,
                    "INSERT INTO feeds (id, url, title, updated_at, created_at) VALUES ('$id', 'https://dup.example/feed', '$id', 0, 0)",
                    0,
                )
            }
        }
        val localFile = soundDbFile { driver -> insertOrphanArticle(driver) }

        assertFailsWith<CloudDataIncompatibleException> {
            DatabaseMerger.merge(localFile.absolutePath, cloudFile.absolutePath, SCHEMA_VERSION, MergeSql.all)
        }
    }

    /** With a sound local DB, a constraint failure from the cloud rows is still the cloud's. */
    @Test
    fun constraintFailureWithASoundLocalDbIsCloudDataIncompatible() {
        val cloudFile = soundDbFile { driver -> insertFolder(driver, "dup", "Cloud") }
        val localFile = soundDbFile { driver -> insertFolder(driver, "dup", "Local") }

        assertFailsWith<CloudDataIncompatibleException> {
            DatabaseMerger.merge(localFile.absolutePath, cloudFile.absolutePath, SCHEMA_VERSION, listOf(UNGUARDED_FOLDER_INSERT))
        }
    }

    /**
     * SQLite itself can end the merge transaction on a failure (here a statement's own
     * `OR ROLLBACK`). A `foreign_key_check` run then would read a `main` other writers may already
     * have changed, so the verdict must be undetermined — the failure stays unclassified even
     * though the local DB is sound. Checking JDBC's `autoCommit` flag alone (still `false` here)
     * would miss this and classify it.
     */
    @Test
    fun constraintFailureAfterSqliteEndedTheTransactionIsLeftUnclassified() {
        val cloudFile = soundDbFile { driver -> insertFolder(driver, "dup", "Cloud") }
        val localFile = soundDbFile { driver -> insertFolder(driver, "dup", "Local") }

        val thrown = assertFailsWith<SQLiteException> {
            DatabaseMerger.merge(
                localFile.absolutePath,
                cloudFile.absolutePath,
                SCHEMA_VERSION,
                listOf(UNGUARDED_FOLDER_INSERT.replace("INSERT INTO", "INSERT OR ROLLBACK INTO")),
            )
        }
        assertEquals(SQLiteErrorCode.SQLITE_CONSTRAINT_PRIMARYKEY, thrown.resultCode)
    }

    /**
     * The failing statement is not the first one. Statement 0 (an unguarded cloud-folder INSERT)
     * succeeded and is not idempotent; statement 1 touches the local orphan and fails on its FOREIGN
     * KEY. The re-run must continue from statement 1: starting again at statement 0 would collide with
     * the folder it already inserted (a PRIMARY KEY failure that reads as the cloud's) and classify a
     * failure that is the local orphan's.
     */
    @Test
    fun failureAtALaterStatementRerunsOnlyFromThatStatementAndStaysUnclassified() {
        val cloudFile = soundDbFile { driver -> insertFolder(driver, "cloud-only", "Cloud") }
        val localFile = soundDbFile { driver -> insertOrphanArticle(driver) }

        val thrown = assertFailsWith<SQLiteException> {
            DatabaseMerger.merge(
                localFile.absolutePath,
                cloudFile.absolutePath,
                SCHEMA_VERSION,
                listOf(UNGUARDED_FOLDER_INSERT, ORPHAN_TOUCHING_UPDATE),
            )
        }
        assertEquals(SQLiteErrorCode.SQLITE_CONSTRAINT_FOREIGNKEY, thrown.resultCode)
    }

    /**
     * A non-constraint failure during the deferred re-run (here a missing table) says nothing about the
     * cloud rows: the verdict is undetermined, the original FOREIGN KEY failure is rethrown, and the
     * re-run's own error is attached to it as suppressed.
     */
    @Test
    fun nonConstraintFailureDuringTheRerunIsUnclassifiedAndAttachedAsSuppressed() {
        val cloudFile = soundDbFile()
        val localFile = soundDbFile { driver -> insertOrphanArticle(driver) }

        val thrown = assertFailsWith<SQLiteException> {
            DatabaseMerger.merge(
                localFile.absolutePath,
                cloudFile.absolutePath,
                SCHEMA_VERSION,
                // Statement 1 is only reached by the re-run: statement 0 fails first originally.
                listOf(ORPHAN_TOUCHING_UPDATE, "INSERT INTO main.no_such_table SELECT * FROM cloud.folders"),
            )
        }
        assertEquals(SQLiteErrorCode.SQLITE_CONSTRAINT_FOREIGNKEY, thrown.resultCode)
        assertTrue(
            thrown.suppressed.any { it is SQLiteException && it.resultCode == SQLiteErrorCode.SQLITE_ERROR },
            "the re-run's failure must be attached as suppressed, got ${thrown.suppressed.toList()}",
        )
    }

    /**
     * `PRAGMA main.foreign_key_check` itself fails — a parent key that is no longer UNIQUE ("foreign key
     * mismatch"), which the merge statements before the failing one created inside the transaction (DDL
     * is transactional). The verdict is undetermined, the original FOREIGN KEY failure is rethrown, and
     * the check's own error is attached as suppressed.
     */
    @Test
    fun failingForeignKeyCheckLeavesTheFailureUnclassifiedAndAttachedAsSuppressed() {
        val cloudFile = soundDbFile()
        val localFile = soundDbFile { driver -> insertOrphanArticle(driver) }

        val thrown = assertFailsWith<SQLiteException> {
            DatabaseMerger.merge(
                localFile.absolutePath,
                cloudFile.absolutePath,
                SCHEMA_VERSION,
                listOf(
                    // feeds.folder_id references folders(id); a parent without a UNIQUE id is a mismatch.
                    "DROP TABLE main.folders",
                    "CREATE TABLE main.folders (id TEXT)",
                    ORPHAN_TOUCHING_UPDATE,
                ),
            )
        }
        assertEquals(SQLiteErrorCode.SQLITE_CONSTRAINT_FOREIGNKEY, thrown.resultCode)
        assertTrue(
            thrown.suppressed.any { it is SQLiteException && "foreign key mismatch" in it.message.orEmpty() },
            "the failed check must be attached as suppressed, got ${thrown.suppressed.toList()}",
        )
    }

    /**
     * SQLite ends the transaction by itself during the re-run (an `INSERT OR ROLLBACK` that is only
     * reached there, and hits a duplicate key): the re-run's constraint failure would read as the
     * cloud's, but the evidence was not all gathered inside the merge transaction, so the verdict is
     * dropped and the failure stays unclassified.
     */
    @Test
    fun transactionEndedBySqliteDuringTheRerunLeavesTheFailureUnclassified() {
        val cloudFile = soundDbFile { driver -> insertFolder(driver, "dup", "Cloud") }
        val localFile = soundDbFile { driver ->
            insertFolder(driver, "dup", "Local")
            insertOrphanArticle(driver)
        }

        val thrown = assertFailsWith<SQLiteException> {
            DatabaseMerger.merge(
                localFile.absolutePath,
                cloudFile.absolutePath,
                SCHEMA_VERSION,
                listOf(ORPHAN_TOUCHING_UPDATE, UNGUARDED_FOLDER_INSERT.replace("INSERT INTO", "INSERT OR ROLLBACK INTO")),
            )
        }
        assertEquals(SQLiteErrorCode.SQLITE_CONSTRAINT_FOREIGNKEY, thrown.resultCode)
    }

    /**
     * A constraint failure thrown while migrating the cloud file touched only the downloaded cloud
     * temp file — main is not involved — so it is the cloud's and stays classified, with no local
     * FOREIGN KEY check needed (and even though main itself holds a violation here).
     *
     * The real migration (1.sqm's ALTER TABLE ADD COLUMN) cannot be driven to a constraint failure —
     * SQLite reports ADD COLUMN's re-check of existing rows as SQLITE_ERROR — so the cloud-only phase
     * is supplied here: it runs a genuinely constraint-violating statement against the cloud file.
     */
    @Test
    fun constraintFailureDuringTheCloudMigrationIsCloudDataIncompatible() {
        val cloudFile = soundDbFile { driver -> insertFolder(driver, "dup", "Cloud") }
        val localFile = soundDbFile { driver -> insertOrphanArticle(driver) }

        val thrown = assertFailsWith<CloudDataIncompatibleException> {
            DatabaseMerger.merge(localFile.absolutePath, cloudFile.absolutePath, SCHEMA_VERSION, MergeSql.all) { cloudPath, _ ->
                java.sql.DriverManager.getConnection("jdbc:sqlite:$cloudPath").use { conn ->
                    conn.createStatement().use { st ->
                        st.execute(
                            "INSERT INTO folders (id, name, sort_order, deleted_at, updated_at, created_at) " +
                                "VALUES ('dup', 'Again', 0, NULL, 0, 0)",
                        )
                    }
                }
            }
        }
        assertTrue("SQLITE_CONSTRAINT" in thrown.messageText, "expected a constraint failure, got ${thrown.messageText}")
    }

    /** A cloud file corrupt past its header is still classified: its own quick_check fails. */
    @Test
    fun corruptCloudFileIsCloudDataIncompatible() {
        val cloudFile = soundDbFile { driver -> insertFeedRow(driver) }
        corruptPastFirstPage(cloudFile, untilByte = 2 * ASSUMED_PAGE_SIZE)
        val localFile = soundDbFile()

        assertFailsWith<CloudDataIncompatibleException> {
            DatabaseMerger.merge(localFile.absolutePath, cloudFile.absolutePath, SCHEMA_VERSION, MergeSql.all)
        }
    }

    /**
     * A corrupt *local* DB raises the same SQLITE_CORRUPT during the merge, but the downloaded cloud
     * file passes its quick_check, so the corruption is local and must not drive a cloud-data reset.
     */
    @Test
    fun corruptLocalDbIsNotBlamedOnAnIntactCloudFile() {
        val cloudFile = soundDbFile { driver -> insertFeedRow(driver) }
        val localFile = soundDbFile { driver -> insertFeedRow(driver) }
        corruptPastFirstPage(localFile, untilByte = Int.MAX_VALUE)

        val thrown = assertFailsWith<SQLiteException> {
            DatabaseMerger.merge(localFile.absolutePath, cloudFile.absolutePath, SCHEMA_VERSION, MergeSql.all)
        }
        // The original SQLite failure is rethrown unchanged, and it really is corruption (not some
        // other failure that merely was not blamed on the cloud).
        assertEquals(SQLiteErrorCode.SQLITE_CORRUPT.code, thrown.resultCode.code and 0xFF, "got ${thrown.resultCode}")
    }

    /**
     * A file DB with this app's schema; [seed] runs on a driver with foreign keys OFF (so it can plant
     * a violation), and the driver is closed before the merge opens the file.
     */
    private fun soundDbFile(seed: (app.cash.sqldelight.db.SqlDriver) -> Unit = {}): File {
        val (file, driver, _) = fileDb()
        try {
            seed(driver)
        } finally {
            driver.close()
        }
        return file
    }

    private fun insertFolder(driver: app.cash.sqldelight.db.SqlDriver, id: String, name: String) {
        driver.execute(
            null,
            "INSERT INTO folders (id, name, sort_order, deleted_at, updated_at, created_at) VALUES ('$id', '$name', 0, NULL, 0, 0)",
            0,
        )
    }

    /** An article whose feed does not exist — only insertable with foreign keys OFF. */
    private fun insertOrphanArticle(driver: app.cash.sqldelight.db.SqlDriver) {
        driver.execute(
            null,
            "INSERT INTO articles (id, feed_id, guid, url, title, cached_at, updated_at, created_at) " +
                "VALUES ('orphan', 'no-such-feed', 'g', 'https://example.com/a', 'Orphan', 0, 0, 0)",
            0,
        )
    }

    private fun insertArticle(driver: app.cash.sqldelight.db.SqlDriver, id: String, feedId: String) {
        driver.execute(
            null,
            "INSERT INTO articles (id, feed_id, guid, url, title, cached_at, updated_at, created_at) " +
                "VALUES ('$id', '$feedId', '$id', 'https://example.com/$id', '$id', 0, 0, 0)",
            0,
        )
    }

    private fun insertFeedRow(driver: app.cash.sqldelight.db.SqlDriver) {
        KeryxDatabase(driver).insertFeed("f1", now = 0)
    }

    /**
     * Flips every byte from the second page up to [untilByte], leaving the header page intact. First
     * asserts the file's real page size is the offset the corruption starts at, so a changed page size
     * cannot silently turn this into a header corruption (or a no-op).
     */
    private fun corruptPastFirstPage(file: File, untilByte: Int) {
        val pageSize = java.sql.DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { conn ->
            conn.createStatement().use { st -> st.executeQuery("PRAGMA page_size").use { it.next(); it.getInt(1) } }
        }
        assertEquals(ASSUMED_PAGE_SIZE, pageSize, "the corruption helper assumes this page size")
        val bytes = file.readBytes()
        assertTrue(bytes.size > ASSUMED_PAGE_SIZE, "the file must have a page past the first to corrupt")
        for (i in ASSUMED_PAGE_SIZE until minOf(bytes.size, untilByte)) {
            bytes[i] = bytes[i].inc()
        }
        file.writeBytes(bytes)
    }

    private companion object {
        /** The page size [corruptPastFirstPage] starts corrupting at; asserted against the file. */
        const val ASSUMED_PAGE_SIZE = 4096

        /**
         * The schema `validateSchema` is actually called with in production (always the running
         * build's own version — see `DatabaseMerger.desktop.kt`'s `validateCloudSchema`), so these
         * tests exercise the real path rather than a version [MergeSchema.EXPECTED_SCHEMAS] might
         * no longer have registered.
         */
        val SCHEMA_VERSION = KeryxDatabase.Schema.version

        /**
         * Copies every cloud folder without MergeSql's NOT EXISTS guards, so a folder id present on
         * both sides hits the PRIMARY KEY — a constraint failure caused by the cloud's rows.
         */
        const val UNGUARDED_FOLDER_INSERT =
            "INSERT INTO main.folders (id, name, sort_order, deleted_at, updated_at, created_at) " +
                "SELECT id, name, sort_order, deleted_at, updated_at, created_at FROM cloud.folders"

        /** Re-assigning the FK column makes SQLite check the (missing) parent of the orphan main holds. */
        const val ORPHAN_TOUCHING_UPDATE = "UPDATE main.articles SET feed_id = feed_id"

        /**
         * Copies every cloud article without its feed, so an article whose feed main lacks breaks
         * the FOREIGN KEY — a violation caused by the cloud's rows.
         */
        const val UNGUARDED_ARTICLE_INSERT =
            "INSERT INTO main.articles (id, feed_id, guid, url, title, cached_at, updated_at, created_at) " +
                "SELECT id, feed_id, guid, url, title, cached_at, updated_at, created_at FROM cloud.articles"

        /** The app's feeds table without its UNIQUE(url), so a cloud file can hold a duplicate url. */
        const val LAX_FEEDS_TABLE =
            "CREATE TABLE feeds (id TEXT NOT NULL PRIMARY KEY, url TEXT NOT NULL, site_url TEXT, " +
                "title TEXT NOT NULL, description TEXT, favicon_url TEXT, etag TEXT, last_modified TEXT, " +
                "error_count INTEGER NOT NULL DEFAULT 0, last_error TEXT, custom_title TEXT, folder_id TEXT, " +
                "deleted_at INTEGER, updated_at INTEGER NOT NULL, created_at INTEGER NOT NULL, " +
                "sort_order INTEGER NOT NULL DEFAULT 0, folder_updated_at INTEGER, " +
                "sort_order_updated_at INTEGER, custom_title_updated_at INTEGER, deleted_updated_at INTEGER)"
    }
}
