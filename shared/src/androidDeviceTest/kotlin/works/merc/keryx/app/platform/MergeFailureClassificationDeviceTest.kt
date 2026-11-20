package works.merc.keryx.app.platform

import android.database.sqlite.SQLiteAccessPermException
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteMisuseException
import android.database.sqlite.SQLiteOutOfMemoryException
import android.database.sqlite.SQLiteReadOnlyDatabaseException
import android.database.sqlite.SQLiteTableLockedException
import io.requery.android.database.sqlite.SQLiteDatabase
import works.merc.keryx.app.core.CloudDataIncompatibleException
import works.merc.keryx.app.createSchemaDbFile
import works.merc.keryx.app.deleteDbFiles
import works.merc.keryx.app.domain.MergeSql
import works.merc.keryx.app.domain.SqliteFailureCategory
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies the Android-specific halves of [DatabaseMerger.merge]'s failure handling: classifying a
 * thrown exception's *class* (Android's `SQLiteException` carries no numeric result code, unlike
 * the desktop actual's JDBC driver — see `app-architecture.md`'s `DatabaseMerger` section), and the
 * [NoOpDatabaseErrorHandler] regression this app relies on to keep a merge failure from silently
 * deleting the database it failed against. The classification *policy* itself
 * ([works.merc.keryx.app.domain.MergeFailureClassifier]) is already covered by `commonTest`'s
 * `MergeFailureClassifierTest`.
 */
class MergeFailureClassificationDeviceTest {
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

    @Test
    fun constraintViolationIsClassifiedAsCloudDataIncompatible() {
        val localFile = createSchemaDbFile().also { cleanup += it }
        val cloudFile = createSchemaDbFile().also { cleanup += it }
        // Same id on both sides, merged via a plain (unguarded) INSERT — MergeSql's real
        // statements always guard with NOT EXISTS; this deliberately skips that guard to force
        // the PRIMARY KEY violation this test is targeting.
        seedFolder(localFile.absolutePath, "dup", "Local")
        seedFolder(cloudFile.absolutePath, "dup", "CloudDup")
        val unguardedInsert =
            "INSERT INTO folders (id, name, sort_order, deleted_at, updated_at, created_at) " +
                "SELECT id, name, sort_order, deleted_at, updated_at, created_at FROM cloud.folders"

        assertFailsWith<CloudDataIncompatibleException> {
            DatabaseMerger.merge(
                localDbPath = localFile.absolutePath,
                cloudDbPath = cloudFile.absolutePath,
                localSchemaVersion = SCHEMA_VERSION,
                mergeStatements = listOf(unguardedInsert),
            )
        }
    }

    /** An article whose feed does not exist — insertable only with foreign keys OFF (the default here). */
    private fun seedOrphanArticle(dbPath: String) {
        SQLiteDatabase.openOrCreateDatabase(dbPath, null, NoOpDatabaseErrorHandler).use { db ->
            db.execSQL(
                "INSERT INTO articles (id, feed_id, guid, url, title, cached_at, updated_at, created_at) " +
                    "VALUES ('orphan', 'no-such-feed', 'g', 'https://example.com/a', 'Orphan', 0, 0, 0)",
            )
        }
    }

    private val unguardedFolderInsert =
        "INSERT INTO folders (id, name, sort_order, deleted_at, updated_at, created_at) " +
            "SELECT id, name, sort_order, deleted_at, updated_at, created_at FROM cloud.folders"

    @Test
    fun cloudPrimaryKeyCollisionIsClassifiedDespiteALocalForeignKeyViolation() {
        val localFile = createSchemaDbFile().also { cleanup += it }
        val cloudFile = createSchemaDbFile().also { cleanup += it }
        seedFolder(localFile.absolutePath, "dup", "Local")
        seedFolder(cloudFile.absolutePath, "dup", "CloudDup")
        seedOrphanArticle(localFile.absolutePath)

        // The re-run with foreign keys deferred fails on the same collision, pinning it on the cloud.
        assertFailsWith<CloudDataIncompatibleException> {
            DatabaseMerger.merge(
                localDbPath = localFile.absolutePath,
                cloudDbPath = cloudFile.absolutePath,
                localSchemaVersion = SCHEMA_VERSION,
                mergeStatements = listOf(unguardedFolderInsert),
            )
        }
    }

    @Test
    fun newForeignKeyViolationFromTheCloudRowsIsClassifiedDespiteALocalOne() {
        val localFile = createSchemaDbFile().also { cleanup += it }
        val cloudFile = createSchemaDbFile().also { cleanup += it }
        seedOrphanArticle(localFile.absolutePath)
        SQLiteDatabase.openOrCreateDatabase(cloudFile.absolutePath, null, NoOpDatabaseErrorHandler).use { db ->
            db.execSQL("INSERT INTO feeds (id, url, title, updated_at, created_at) VALUES ('f1', 'https://example.com/f1', 'Feed', 0, 0)")
            db.execSQL(
                "INSERT INTO articles (id, feed_id, guid, url, title, cached_at, updated_at, created_at) " +
                    "VALUES ('cloud-article', 'f1', 'c', 'https://example.com/c', 'Cloud', 0, 0, 0)",
            )
        }

        // The cloud article's feed exists nowhere in main: the re-run with foreign keys deferred
        // leaves a violation main did not hold before.
        assertFailsWith<CloudDataIncompatibleException> {
            DatabaseMerger.merge(
                localDbPath = localFile.absolutePath,
                cloudDbPath = cloudFile.absolutePath,
                localSchemaVersion = SCHEMA_VERSION,
                mergeStatements = listOf(
                    "INSERT INTO articles (id, feed_id, guid, url, title, cached_at, updated_at, created_at) " +
                        "SELECT id, feed_id, guid, url, title, cached_at, updated_at, created_at FROM cloud.articles",
                ),
            )
        }
    }

    @Test
    fun foreignKeyViolationAlreadyInTheLocalDbIsNotBlamedOnTheCloud() {
        val localFile = createSchemaDbFile().also { cleanup += it }
        val cloudFile = createSchemaDbFile().also { cleanup += it }
        seedOrphanArticle(localFile.absolutePath)

        // Re-assigning the FK column makes SQLite check the orphan's missing parent; the re-run with
        // foreign keys deferred adds no violation main did not already hold.
        assertFailsWith<SQLiteConstraintException> {
            DatabaseMerger.merge(
                localDbPath = localFile.absolutePath,
                cloudDbPath = cloudFile.absolutePath,
                localSchemaVersion = SCHEMA_VERSION,
                mergeStatements = listOf("UPDATE main.articles SET feed_id = feed_id"),
            )
        }
        // The merge transaction was rolled back, not committed: main still holds just the orphan.
        SQLiteDatabase.openOrCreateDatabase(localFile.absolutePath, null, NoOpDatabaseErrorHandler).use { db ->
            db.rawQuery("SELECT id FROM articles", null).use { cursor ->
                assertEquals(listOf("orphan"), buildList { while (cursor.moveToNext()) add(cursor.getString(0)) })
            }
        }
    }

    /**
     * SQLite itself can end the merge transaction on a failure (here a statement's own `OR ROLLBACK`).
     * A `foreign_key_check` run then would read a `main` other writers may already have changed, so
     * the verdict must be undetermined and the failure stays unclassified though the local DB is sound.
     */
    @Test
    fun constraintFailureAfterSqliteEndedTheTransactionIsLeftUnclassified() {
        val localFile = createSchemaDbFile().also { cleanup += it }
        val cloudFile = createSchemaDbFile().also { cleanup += it }
        seedFolder(localFile.absolutePath, "dup", "Local")
        seedFolder(cloudFile.absolutePath, "dup", "CloudDup")

        assertFailsWith<SQLiteConstraintException> {
            DatabaseMerger.merge(
                localDbPath = localFile.absolutePath,
                cloudDbPath = cloudFile.absolutePath,
                localSchemaVersion = SCHEMA_VERSION,
                mergeStatements = listOf(unguardedFolderInsert.replace("INSERT INTO", "INSERT OR ROLLBACK INTO")),
            )
        }
    }

    private val orphanTouchingUpdate = "UPDATE main.articles SET feed_id = feed_id"

    /**
     * The failing statement is not the first one. Statement 0 (an unguarded cloud-folder INSERT)
     * succeeded and is not idempotent; statement 1 touches the local orphan and fails on its FOREIGN
     * KEY. The re-run must continue from statement 1: starting again at statement 0 would collide with
     * the folder it already inserted (a PRIMARY KEY failure that reads as the cloud's).
     */
    @Test
    fun failureAtALaterStatementRerunsOnlyFromThatStatementAndStaysUnclassified() {
        val localFile = createSchemaDbFile().also { cleanup += it }
        val cloudFile = createSchemaDbFile().also { cleanup += it }
        seedOrphanArticle(localFile.absolutePath)
        seedFolder(cloudFile.absolutePath, "cloud-only", "Cloud")

        assertFailsWith<SQLiteConstraintException> {
            DatabaseMerger.merge(
                localDbPath = localFile.absolutePath,
                cloudDbPath = cloudFile.absolutePath,
                localSchemaVersion = SCHEMA_VERSION,
                mergeStatements = listOf(unguardedFolderInsert, orphanTouchingUpdate),
            )
        }
    }

    /**
     * A non-constraint failure during the deferred re-run (a missing table) says nothing about the cloud
     * rows: the original failure is rethrown unclassified, with the re-run's error attached as suppressed.
     */
    @Test
    fun nonConstraintFailureDuringTheRerunIsUnclassifiedAndAttachedAsSuppressed() {
        val localFile = createSchemaDbFile().also { cleanup += it }
        val cloudFile = createSchemaDbFile().also { cleanup += it }
        seedOrphanArticle(localFile.absolutePath)

        val thrown = assertFailsWith<SQLiteConstraintException> {
            DatabaseMerger.merge(
                localDbPath = localFile.absolutePath,
                cloudDbPath = cloudFile.absolutePath,
                localSchemaVersion = SCHEMA_VERSION,
                // Statement 1 is only reached by the re-run: statement 0 fails first originally.
                mergeStatements = listOf(orphanTouchingUpdate, "INSERT INTO main.no_such_table SELECT * FROM cloud.folders"),
            )
        }
        assertTrue(
            thrown.suppressedExceptions.any { it::class == SQLiteException::class },
            "the re-run's failure must be attached as suppressed, got ${thrown.suppressedExceptions}",
        )
    }

    /** `PRAGMA main.foreign_key_check` itself fails (a parent key that is no longer UNIQUE). */
    @Test
    fun failingForeignKeyCheckLeavesTheFailureUnclassifiedAndAttachedAsSuppressed() {
        val localFile = createSchemaDbFile().also { cleanup += it }
        val cloudFile = createSchemaDbFile().also { cleanup += it }
        seedOrphanArticle(localFile.absolutePath)

        val thrown = assertFailsWith<SQLiteConstraintException> {
            DatabaseMerger.merge(
                localDbPath = localFile.absolutePath,
                cloudDbPath = cloudFile.absolutePath,
                localSchemaVersion = SCHEMA_VERSION,
                mergeStatements = listOf(
                    // feeds.folder_id references folders(id); a parent without a UNIQUE id is a mismatch
                    // that makes foreign_key_check itself fail (DDL is transactional).
                    "DROP TABLE main.folders",
                    "CREATE TABLE main.folders (id TEXT)",
                    orphanTouchingUpdate,
                ),
            )
        }
        assertTrue(
            thrown.suppressedExceptions.any { "foreign key mismatch" in it.message.orEmpty() },
            "the failed check must be attached as suppressed, got ${thrown.suppressedExceptions}",
        )
    }

    /**
     * SQLite ends the transaction by itself during the re-run (an `INSERT OR ROLLBACK` only reached
     * there, hitting a duplicate key): the re-run's constraint failure would read as the cloud's, but
     * the merge savepoint can no longer be released, so the verdict is dropped and the failure stays
     * unclassified. Ignoring the failed RELEASE (the "no such savepoint" branch of `mainRuledOut`)
     * would classify it, so the exception type alone guards that branch; requery's message text is
     * deliberately not asserted.
     */
    @Test
    fun transactionEndedBySqliteDuringTheRerunLeavesTheFailureUnclassified() {
        val localFile = createSchemaDbFile().also { cleanup += it }
        val cloudFile = createSchemaDbFile().also { cleanup += it }
        seedFolder(localFile.absolutePath, "dup", "Local")
        seedFolder(cloudFile.absolutePath, "dup", "CloudDup")
        seedOrphanArticle(localFile.absolutePath)

        assertFailsWith<SQLiteConstraintException> {
            DatabaseMerger.merge(
                localDbPath = localFile.absolutePath,
                cloudDbPath = cloudFile.absolutePath,
                localSchemaVersion = SCHEMA_VERSION,
                mergeStatements = listOf(orphanTouchingUpdate, unguardedFolderInsert.replace("INSERT INTO", "INSERT OR ROLLBACK INTO")),
            )
        }
    }

    /**
     * The real merge statements against a cloud DB whose own rows break this app's schema (two feeds
     * sharing a url, which only the cloud file's laxer feeds table allowed) are still classified while
     * main holds an unrelated FOREIGN KEY violation.
     */
    @Test
    fun cloudRowsViolatingTheAppSchemaAreClassifiedDespiteALocalForeignKeyViolation() {
        val localFile = createSchemaDbFile().also { cleanup += it }
        val cloudFile = createSchemaDbFile().also { cleanup += it }
        seedOrphanArticle(localFile.absolutePath)
        SQLiteDatabase.openOrCreateDatabase(cloudFile.absolutePath, null, NoOpDatabaseErrorHandler).use { db ->
            db.execSQL("DROP TABLE feeds")
            db.execSQL(LAX_FEEDS_TABLE)
            for (id in listOf("f-dup-1", "f-dup-2")) {
                db.execSQL("INSERT INTO feeds (id, url, title, updated_at, created_at) VALUES ('$id', 'https://dup.example/feed', '$id', 0, 0)")
            }
        }

        assertFailsWith<CloudDataIncompatibleException> {
            DatabaseMerger.merge(
                localDbPath = localFile.absolutePath,
                cloudDbPath = cloudFile.absolutePath,
                localSchemaVersion = SCHEMA_VERSION,
                mergeStatements = MergeSql.all,
            )
        }
    }

    private fun seedFeed(dbPath: String) {
        SQLiteDatabase.openOrCreateDatabase(dbPath, null, NoOpDatabaseErrorHandler).use { db ->
            db.execSQL("INSERT INTO feeds (id, url, title, updated_at, created_at) VALUES ('f1', 'https://example.com/f1', 'Feed', 0, 0)")
        }
    }

    /**
     * Flips every byte from the second page up to [untilByte], leaving the header page intact. First
     * asserts the file's real page size is the offset the corruption starts at, so a changed page size
     * cannot silently turn this into a header corruption (or a no-op).
     */
    private fun corruptPastFirstPage(file: File, untilByte: Int) {
        val pageSize = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY, NoOpDatabaseErrorHandler).use { db ->
            db.rawQuery("PRAGMA page_size", null).use { it.moveToFirst(); it.getInt(0) }
        }
        assertEquals(ASSUMED_PAGE_SIZE, pageSize, "the corruption helper assumes this page size")
        val bytes = file.readBytes()
        assertTrue(bytes.size > ASSUMED_PAGE_SIZE, "the file must have a page past the first to corrupt")
        for (i in ASSUMED_PAGE_SIZE until minOf(bytes.size, untilByte)) {
            bytes[i] = bytes[i].inc()
        }
        file.writeBytes(bytes)
    }

    /** A cloud file corrupt past its header is still classified: its own quick_check fails. */
    @Test
    fun corruptCloudFileIsClassifiedAsCloudDataIncompatible() {
        val localFile = createSchemaDbFile().also { cleanup += it }
        val cloudFile = createSchemaDbFile().also { cleanup += it }
        seedFeed(cloudFile.absolutePath)
        corruptPastFirstPage(cloudFile, untilByte = Int.MAX_VALUE)

        assertFailsWith<CloudDataIncompatibleException> {
            DatabaseMerger.merge(
                localDbPath = localFile.absolutePath,
                cloudDbPath = cloudFile.absolutePath,
                localSchemaVersion = SCHEMA_VERSION,
                mergeStatements = MergeSql.all,
            )
        }
    }

    /**
     * A corrupt *local* DB raises the same corruption during the merge, but the downloaded cloud file
     * passes its quick_check, so the corruption is local and the original failure is rethrown.
     */
    @Test
    fun corruptLocalDbIsNotBlamedOnAnIntactCloudFile() {
        val localFile = createSchemaDbFile().also { cleanup += it }
        val cloudFile = createSchemaDbFile().also { cleanup += it }
        seedFeed(localFile.absolutePath)
        seedFeed(cloudFile.absolutePath)
        corruptPastFirstPage(localFile, untilByte = Int.MAX_VALUE)

        assertFailsWith<SQLiteDatabaseCorruptException> {
            DatabaseMerger.merge(
                localDbPath = localFile.absolutePath,
                cloudDbPath = cloudFile.absolutePath,
                localSchemaVersion = SCHEMA_VERSION,
                mergeStatements = MergeSql.all,
            )
        }
    }

    @Test
    fun statementErrorAgainstAStructurallyValidCloudDbIsLeftUnclassified() {
        val localFile = createSchemaDbFile().also { cleanup += it }
        // A structurally normal (valid v2 schema) cloud DB — validateSchema must find it fine,
        // so MergeFailureClassifier must NOT escalate this to CloudDataIncompatibleException
        // (that would offer a destructive cloud-data reset for what is really a bad merge
        // statement / app bug, not a bad cloud file).
        val cloudFile = createSchemaDbFile().also { cleanup += it }

        // A plain SQLiteException ("no such table") is exactly what a foreign/legacy cloud schema
        // looks like — ambiguous by design, which is why MergeFailureClassifier re-checks it
        // against the actual cloud file instead of trusting the exception class alone.
        assertFailsWith<SQLiteException> {
            DatabaseMerger.merge(
                localDbPath = localFile.absolutePath,
                cloudDbPath = cloudFile.absolutePath,
                localSchemaVersion = SCHEMA_VERSION,
                mergeStatements = listOf("INSERT INTO nonexistent_table SELECT * FROM cloud.folders"),
            )
        }
    }

    @Test
    fun missingCloudFileFailsInsteadOfBeingSilentlyCreatedEmpty() {
        val localFile = createSchemaDbFile().also { cleanup += it }
        // Deliberately never created — migrateCloudIfOlder/validateSchema must open this
        // non-creating, so a genuinely-missing cloud file surfaces as a failure instead of
        // silently becoming an empty (version 0) database that the merge then fails against with
        // a misleading "no such table", which validateSchema would in turn misclassify as
        // CloudDataIncompatibleException (offering a destructive cloud-data reset for what is
        // really just a missing/transient file).
        val missingCloudPath = File(localFile.parentFile, "keryx-devicetest-missing-${UUID.randomUUID()}.db").absolutePath

        assertFailsWith<SQLiteException> {
            DatabaseMerger.merge(
                localDbPath = localFile.absolutePath,
                cloudDbPath = missingCloudPath,
                localSchemaVersion = SCHEMA_VERSION,
                mergeStatements = listOf("INSERT INTO folders SELECT * FROM cloud.folders"),
            )
        }
        assertFalse(File(missingCloudPath).exists(), "a non-creating open must not leave a fresh empty database file behind")
    }

    @Test
    fun noOpDatabaseErrorHandlerDoesNotDeleteACorruptFile() {
        val file = createSchemaDbFile().also { cleanup += it }
        // Corrupt the header ("SQLite format 3\0") so SQLite's own integrity checks flag this
        // as SQLITE_NOTADB/SQLITE_CORRUPT the next time it's opened and queried.
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.write(ByteArray(16))
        }

        runCatching {
            SQLiteDatabase.openOrCreateDatabase(file.absolutePath, null, NoOpDatabaseErrorHandler).use { db ->
                db.rawQuery("SELECT name FROM sqlite_master", null).use { it.moveToFirst() }
            }
        }

        assertTrue(file.exists(), "NoOpDatabaseErrorHandler must never delete the database file — see its own KDoc")
    }

    /**
     * Review finding #13 (`v0.11.0..HEAD`): [DatabaseMerger.failureCategory]'s catch-all used to
     * route *every* unrecognized `SQLiteException` subclass into [SqliteFailureCategory.STATEMENT_ERROR]
     * — the ambiguous "no such table" bucket that can escalate to a destructive
     * [CloudDataIncompatibleException] via `validateCloudSchema` — rather than
     * [SqliteFailureCategory.OTHER], contradicting both its own KDoc and the desktop actual's
     * equivalent `else -> OTHER` branch. Constructed directly rather than triggered through a real
     * merge: exercising each of these specific subclasses through a genuine ATTACH/merge failure
     * would be far more fragile than asserting the classification function's own mapping.
     */
    @Test
    fun unrecognizedSqliteExceptionSubclassesAreClassifiedAsOtherNotStatementError() {
        val unrecognizedSubclasses = listOf(
            SQLiteTableLockedException("locked"),
            SQLiteReadOnlyDatabaseException("read-only"),
            SQLiteAccessPermException("no permission"),
            SQLiteMisuseException("misuse"),
            SQLiteOutOfMemoryException("oom"),
        )
        for (exception in unrecognizedSubclasses) {
            assertEquals(
                SqliteFailureCategory.OTHER,
                with(DatabaseMerger) { exception.failureCategory() },
                "${exception::class.simpleName} must be classified as OTHER, not escalated to STATEMENT_ERROR",
            )
        }
    }

    @Test
    fun aPlainSqliteExceptionIsClassifiedAsStatementError() {
        assertEquals(
            SqliteFailureCategory.STATEMENT_ERROR,
            with(DatabaseMerger) { SQLiteException("no such table: foo").failureCategory() },
        )
    }

    @Test
    fun recognizedCorruptOrConstraintSubclassesKeepTheirOwnCategory() {
        assertEquals(
            SqliteFailureCategory.CONSTRAINT,
            with(DatabaseMerger) { SQLiteConstraintException("constraint").failureCategory() },
        )
        assertEquals(
            SqliteFailureCategory.CORRUPT,
            with(DatabaseMerger) { SQLiteDatabaseCorruptException("corrupt").failureCategory() },
        )
    }

    @Test
    fun aNonSqliteThrowableIsNotClassifiedAtAll() {
        assertEquals(null, with(DatabaseMerger) { IllegalStateException("app bug").failureCategory() })
    }

    private companion object {
        const val SCHEMA_VERSION = 2L

        /** The app's feeds table without its UNIQUE(url), so a cloud file can hold a duplicate url. */
        const val LAX_FEEDS_TABLE =
            "CREATE TABLE feeds (id TEXT NOT NULL PRIMARY KEY, url TEXT NOT NULL, site_url TEXT, " +
                "title TEXT NOT NULL, description TEXT, favicon_url TEXT, etag TEXT, last_modified TEXT, " +
                "error_count INTEGER NOT NULL DEFAULT 0, last_error TEXT, custom_title TEXT, folder_id TEXT, " +
                "deleted_at INTEGER, updated_at INTEGER NOT NULL, created_at INTEGER NOT NULL, " +
                "sort_order INTEGER NOT NULL DEFAULT 0, folder_updated_at INTEGER, " +
                "sort_order_updated_at INTEGER, custom_title_updated_at INTEGER, deleted_updated_at INTEGER)"

        /** The page size [corruptPastFirstPage] starts corrupting at; asserted against the file. */
        const val ASSUMED_PAGE_SIZE = 1024
    }
}
