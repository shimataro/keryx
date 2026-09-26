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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Apple database stack against the real system sqlite3: the NativeSqliteDriver setup, FTS5 with
 * the trigram tokenizer, the VACUUM INTO snapshot and the ATTACH-DATABASE merge — the pieces the
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
    fun trigramFullTextSearchWorksOnTheSystemSqlite() = runTest {
        val driver = open("fts.db")
        driver.insertFeed("f1")
        driver.insertArticle("a1", "f1", "Keryx release notes", "新しいフィードリーダーを公開しました")
        FtsManager(driver).ensureIndexed()

        val hits = FtsSearch(driver).search("フィードリーダー", ArticleFilter.All)

        assertEquals(listOf("a1"), hits.map { it.id })
    }

    @Test
    fun theUploadSnapshotLeavesOutTheLocalOnlyTables() = runTest {
        val driver = open("local.db")
        FtsManager(driver).ensureIndexed()
        driver.close()
        drivers -= driver

        DatabaseSnapshot.exportForUpload(path("local.db"), path("snapshot.db"))

        val tables = RawSqliteConnection.open(path("snapshot.db")).use {
            it.queryColumn("SELECT name FROM sqlite_master WHERE type = 'table'", "name")
        }
        assertTrue("feeds" in tables)
        assertFalse("articles_fts" in tables)
        assertFalse("sync_state" in tables)
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

    @Test
    fun theSchemaCheckRecognisesTheAppsOwnSchema() {
        open("schema.db").close()
        drivers.clear()
        assertEquals(true, DatabaseMerger.validateSchema(path("schema.db"), KeryxDatabase.Schema.version))
    }
}
