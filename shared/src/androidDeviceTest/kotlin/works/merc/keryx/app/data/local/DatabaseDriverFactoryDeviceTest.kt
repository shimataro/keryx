package works.merc.keryx.app.data.local

import android.app.Application
import io.requery.android.database.sqlite.SQLiteDatabase
import works.merc.keryx.app.SCHEMA_V2_STATEMENTS
import works.merc.keryx.app.core.DB_FILE_NAME
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.deleteDbFiles
import works.merc.keryx.app.domain.IdGenerator
import works.merc.keryx.app.platform.AndroidAppContext
import works.merc.keryx.app.platform.NoOpDatabaseErrorHandler
import works.merc.keryx.app.testContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Instrumented counterpart of `desktopTest`'s `SchemaV3MigrationTest`: a schema-version-2 file
 * opened through the real Android [DatabaseDriverFactory] (requery's bundled SQLite, driven by
 * `AndroidSqliteDriver`'s own `onUpgrade`) is migrated to version 3 with the `AfterVersion(2)`
 * re-key (`KERYX_SCHEMA_CALLBACKS`) applied, which a plain JVM test cannot show for this driver.
 */
class DatabaseDriverFactoryDeviceTest {
    private val dbFile get() = testContext().getDatabasePath(DB_FILE_NAME)

    @BeforeTest
    fun setUp() {
        AndroidAppContext.init(testContext().applicationContext as Application)
        dbFile.deleteDbFiles()
        dbFile.parentFile?.mkdirs()
    }

    @AfterTest
    fun tearDown() {
        dbFile.deleteDbFiles()
    }

    private fun SQLiteDatabase.rows(sql: String): List<List<String?>> = rawQuery(sql, null).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add((0 until cursor.columnCount).map { cursor.getString(it) })
        }
    }

    @Test
    fun aVersionTwoFileIsRekeyedOntoTheDerivedIdsWhenOpened() {
        SQLiteDatabase.openOrCreateDatabase(dbFile.absolutePath, null, NoOpDatabaseErrorHandler).use { db ->
            SCHEMA_V2_STATEMENTS.forEach { db.execSQL(it) }
            db.execSQL("INSERT INTO folders (id, name, sort_order, updated_at, created_at) VALUES ('d1', 'D', 0, 10, 1)")
            db.execSQL("INSERT INTO tags (id, name, updated_at, created_at) VALUES ('t1', 'T', 10, 1)")
            db.execSQL("INSERT INTO feeds (id, url, title, folder_id, updated_at, created_at) VALUES ('f1', 'https://a', 'A', 'd1', 10, 1)")
            db.execSQL("INSERT INTO feed_tags (feed_id, tag_id, updated_at) VALUES ('f1', 't1', 10)")
            db.execSQL("PRAGMA user_version=2")
        }

        // AndroidSqliteDriver opens (and so migrates) the file lazily, on its first statement.
        val driver = DatabaseDriverFactory().create()
        try {
            driver.execute(null, "PRAGMA foreign_keys=ON;", 0)
        } finally {
            driver.close()
        }

        val dId = IdGenerator.folderId("D")
        val tId = IdGenerator.tagId("T")
        SQLiteDatabase.openOrCreateDatabase(dbFile.absolutePath, null, NoOpDatabaseErrorHandler).use { db ->
            assertEquals(KeryxDatabase.Schema.version, db.version.toLong())
            assertEquals(listOf(listOf<String?>(dId, "D", "d1")), db.rows("SELECT id, name, v2_id FROM folders"))
            assertEquals(listOf(listOf<String?>(tId, "T", "t1")), db.rows("SELECT id, name, v2_id FROM tags"))
            assertEquals(listOf(listOf<String?>("f1", dId)), db.rows("SELECT id, folder_id FROM feeds"))
            assertEquals(listOf(listOf<String?>("f1", tId)), db.rows("SELECT feed_id, tag_id FROM feed_tags"))
            assertEquals(
                setOf(listOf<String?>("folder", "d1", dId), listOf<String?>("tag", "t1", tId)),
                db.rows("SELECT kind, old_id, new_id FROM ks_id_remap").toSet(),
            )
            assertEquals(
                setOf(listOf<String?>("folder", "d1", dId), listOf<String?>("tag", "t1", tId)),
                db.rows("SELECT kind, v2_id, id FROM ks_v2_edge").toSet(),
            )
        }
    }
}
