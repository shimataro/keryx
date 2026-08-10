package works.merc.keryx.app.platform

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import works.merc.keryx.app.data.local.FtsSearch
import works.merc.keryx.app.fileDb
import works.merc.keryx.app.ftsManagerIndexed
import works.merc.keryx.app.insertFeed
import java.io.File
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies [DatabaseSnapshot.exportForUpload] both excludes `articles_fts` (and the
 * now-unnecessary `idx_articles_*` indexes) from the uploaded copy, and actually reclaims the
 * space they used — `DROP TABLE`/`DROP INDEX` alone leaves the freed pages in the file (only a
 * subsequent `VACUUM` does), which is the bug this export previously had.
 */
class DatabaseSnapshotTest {

    private fun tableNames(dbPath: String): Set<String> =
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT name FROM sqlite_master WHERE type IN ('table', 'index')").use { rs ->
                    buildSet {
                        while (rs.next()) add(rs.getString("name"))
                    }
                }
            }
        }

    private fun freelistCount(dbPath: String): Long =
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("PRAGMA freelist_count").use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
        }

    @Test
    fun exportedCopyExcludesFtsAndArticleIndexesAndReclaimsTheirSpace() {
        val (file, driver, db) = fileDb()
        try {
            db.insertFeed("f1")
            db.articlesQueries.insert(
                id = "a1", feed_id = "f1", guid = "g1", url = "https://article/a1", title = "Title",
                summary = null, content = "body text".repeat(500), author = null, published_at = null,
                thumbnail_url = null, is_read = 0, read_at = null, is_starred = 0, starred_at = null,
                cached_at = 0L, search_text = "body text".repeat(500), updated_at = 0L, created_at = 0L,
            )
            ftsManagerIndexed(driver)
        } finally {
            driver.close() // release the SQLDelight connection before DatabaseSnapshot opens its own
        }

        val destPath = File.createTempFile("keryx-export-test-", ".db").absolutePath
        try {
            DatabaseSnapshot.exportForUpload(file.absolutePath, destPath)

            val copyTables = tableNames(destPath)
            assertFalse(copyTables.contains("articles_fts"), "articles_fts must be excluded from the upload copy")
            for (index in listOf(
                "idx_articles_feed_id", "idx_articles_is_read", "idx_articles_is_starred", "idx_articles_published",
            )) {
                assertFalse(copyTables.contains(index), "$index must be excluded from the upload copy")
            }
            assertEquals(0L, freelistCount(destPath), "dropped tables' space must be reclaimed, not left as free pages")

            // The live DB is untouched: articles_fts and its data are still there.
            val liveTables = tableNames(file.absolutePath)
            assertTrue(liveTables.contains("articles_fts"), "the live DB's articles_fts must never be dropped")
            val liveDriver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
            try {
                assertEquals(listOf("a1"), FtsSearch(liveDriver).search("body").map { it.id })
            } finally {
                liveDriver.close()
            }
        } finally {
            File(destPath).delete()
            file.delete()
        }
    }
}
