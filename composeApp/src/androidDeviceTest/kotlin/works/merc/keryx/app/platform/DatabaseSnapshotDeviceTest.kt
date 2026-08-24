package works.merc.keryx.app.platform

import io.requery.android.database.sqlite.SQLiteDatabase
import works.merc.keryx.app.createSchemaDbFile
import works.merc.keryx.app.deleteDbFiles
import works.merc.keryx.app.testContext
import java.io.File
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Instrumented counterpart of `desktopTest`'s `DatabaseSnapshotTest` — verifies
 * [DatabaseSnapshot.exportForUpload] against the real bundled SQLite (`VACUUM INTO` needs SQLite
 * ≥3.27, unavailable through AOSP's own build; see `.claude/rules/android-sqlite-bundling.md`),
 * which cannot be exercised from a plain JVM unit test.
 */
class DatabaseSnapshotDeviceTest {
    private val cleanup = mutableListOf<File>()

    @AfterTest
    fun tearDown() {
        cleanup.forEach { it.deleteDbFiles() }
    }

    private fun tableAndIndexNames(dbPath: String): Set<String> =
        SQLiteDatabase.openOrCreateDatabase(dbPath, null, NoOpDatabaseErrorHandler).use { db ->
            db.rawQuery("SELECT name FROM sqlite_master WHERE type IN ('table', 'index')", null).use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
            }
        }

    @Test
    fun exportedCopyExcludesFtsSyncStateAndArticleIndexesButPreservesUserVersion() {
        val localFile = createSchemaDbFile().also { cleanup += it }
        SQLiteDatabase.openOrCreateDatabase(localFile.absolutePath, null, NoOpDatabaseErrorHandler).use { db ->
            // articles_fts is never part of the SQLDelight schema (FtsManager creates it via raw
            // SQL at runtime — see db-schema.md), so this test creates it by hand to exercise the
            // exact exclusion DatabaseSnapshot must perform.
            db.execSQL(
                "CREATE VIRTUAL TABLE articles_fts USING fts5(" +
                    "title, search_text, content='articles', content_rowid='rowid', tokenize='trigram')",
            )
            db.execSQL("PRAGMA user_version=2")
        }

        val destFile = File(testContext().cacheDir, "keryx-devicetest-export-${UUID.randomUUID()}.db")
            .also { cleanup += it }

        DatabaseSnapshot.exportForUpload(localFile.absolutePath, destFile.absolutePath)

        val copyEntries = tableAndIndexNames(destFile.absolutePath)
        assertFalse(copyEntries.contains("articles_fts"), "articles_fts must be excluded from the upload copy")
        assertFalse(copyEntries.contains("sync_state"), "sync_state must be excluded from the upload copy")
        for (index in listOf(
            "idx_articles_feed_id", "idx_articles_is_read", "idx_articles_is_starred", "idx_articles_published",
        )) {
            assertFalse(copyEntries.contains(index), "$index must be excluded from the upload copy")
        }
        val copyVersion = SQLiteDatabase.openOrCreateDatabase(destFile.absolutePath, null, NoOpDatabaseErrorHandler)
            .use { it.userVersion() }
        assertEquals(2L, copyVersion, "user_version must survive the export")

        // The live DB is untouched.
        val liveEntries = tableAndIndexNames(localFile.absolutePath)
        assertTrue(liveEntries.contains("articles_fts"), "the live DB's articles_fts must never be dropped")
        assertTrue(liveEntries.contains("sync_state"), "the live DB's sync_state must never be dropped")
    }
}
