package works.merc.keryx.app.platform

import io.requery.android.database.sqlite.SQLiteDatabase
import works.merc.keryx.app.core.SchemaVersionException
import works.merc.keryx.app.createSchemaDbFile
import works.merc.keryx.app.deleteDbFiles
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
        // Roll the cloud DB back to a genuinely v1-shaped `articles` table (see db/1.sqm) so
        // migrateCloudIfOlder's real KeryxDatabase.Schema.migrate(driver, 1, 2) call has actual
        // work to do, rather than re-adding columns that already exist (which would fail).
        SQLiteDatabase.openOrCreateDatabase(cloudFile.absolutePath, null, NoOpDatabaseErrorHandler).use { db ->
            db.execSQL("ALTER TABLE articles DROP COLUMN deleted_at")
            db.execSQL("ALTER TABLE articles DROP COLUMN deleted_updated_at")
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

    private companion object {
        /** Must stay a version actually registered in [works.merc.keryx.app.domain.MergeSchema.EXPECTED_SCHEMAS]. */
        const val SCHEMA_VERSION = 2L
    }
}
