package works.merc.keryx.app.domain

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import works.merc.keryx.app.core.ArticleFilter
import works.merc.keryx.app.data.local.FtsManager
import works.merc.keryx.app.data.local.FtsSearch
import works.merc.keryx.app.domain.TransportFixtures.DEV_A
import works.merc.keryx.app.domain.TransportFixtures.DEV_B
import works.merc.keryx.app.domain.TransportFixtures.article
import works.merc.keryx.app.domain.TransportFixtures.deltaName
import works.merc.keryx.app.domain.TransportFixtures.feed
import works.merc.keryx.app.domain.TransportFixtures.localDb
import works.merc.keryx.app.domain.TransportFixtures.merge
import works.merc.keryx.app.domain.TransportFixtures.transportFile
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The search index after a merge brings in an article without its body — a light row (a read or star
 * change) that arrives before the file carrying the body, as an eventually consistent listing allows.
 * Such an article must not be indexed with `''`: `indexMissing()` never revisits an indexed row, so
 * its body would stay unsearchable once it arrived (the cause of `SyncSimulationTest`'s seed 37).
 */
class SearchIndexAfterMergeTest {

    private fun lightStar(feedId: String = "f1") = transportFile(
        deltaName(DEV_B, 1),
        feed(feedId),
        article("a1", feedId, title = "Quokka title", isStarred = 1, starredAt = 200),
    )

    private fun body(feedId: String = "f1") = transportFile(
        deltaName(DEV_A, 1),
        feed(feedId),
        article("a1", feedId, title = "Quokka title", content = "<p>Body pangolin</p>"),
    )

    private fun withLocal(block: (File, SqlDriver, FtsManager) -> Unit) {
        val local = localDb(feed("f1"))
        val driver = JdbcSqliteDriver("jdbc:sqlite:${local.absolutePath}")
        try {
            val fts = FtsManager(driver).also { it.createTable() }
            block(local, driver, fts)
        } finally {
            driver.close()
            local.delete()
        }
    }

    @Test
    fun anArticleMergedWithoutItsBodyIsIndexedWithTheBodyOnceItArrives() = withLocal { local, driver, fts ->
        merge(local, lightStar())
        runBlocking { fts.indexMissing() }

        assertTrue(driver.isAwaitingBody("a1"))
        assertFalse(driver.isIndexed("a1"), "an article still waiting for its body must not be indexed with ''")

        merge(local, body())
        runBlocking { fts.indexMissing() }

        assertEquals(listOf("a1"), FtsSearch(driver).search("pangolin", ArticleFilter.All).map { it.id })
        assertEquals(listOf("a1"), FtsSearch(driver).search("Quokka", ArticleFilter.All).map { it.id })
        assertFalse(driver.isAwaitingBody("a1"))
        driver.execute(null, "INSERT INTO articles_fts(articles_fts, rank) VALUES('integrity-check', 1);", 0)
    }

    /** One whose feed item genuinely has no body is indexed by its title at the daily rebuild. */
    @Test
    fun theDailyRebuildIndexesAnArticleStillWaitingForItsBody() = withLocal { local, driver, fts ->
        merge(local, lightStar())
        runBlocking { fts.indexMissing() }

        runBlocking { fts.rebuildIndex() }

        assertEquals(listOf("a1"), FtsSearch(driver).search("Quokka", ArticleFilter.All).map { it.id })
        assertFalse(driver.isAwaitingBody("a1"))
        driver.execute(null, "INSERT INTO articles_fts(articles_fts, rank) VALUES('integrity-check', 1);", 0)
    }

    /** This device's own refresh can supply the body too (with its `search_text`), not only a merge. */
    @Test
    fun aBodyFromThisDevicesOwnRefreshAlsoReleasesTheArticle() = withLocal { local, driver, fts ->
        merge(local, lightStar())
        runBlocking { fts.indexMissing() }

        driver.execute(null, "UPDATE articles SET content = '<p>Body wombat</p>', search_text = 'Body wombat' WHERE id = 'a1';", 0)
        runBlocking { fts.indexMissing() }

        assertEquals(listOf("a1"), FtsSearch(driver).search("wombat", ArticleFilter.All).map { it.id })
        assertFalse(driver.isAwaitingBody("a1"))
    }

    /** A light row for an article this device already holds changes nothing about its indexing. */
    @Test
    fun aLightRowForAnArticleAlreadyHeldIsNotMarked() = withLocal { local, driver, fts ->
        merge(local, body())
        runBlocking { fts.indexMissing() }
        assertTrue(driver.isIndexed("a1"))

        merge(local, lightStar())

        assertFalse(driver.isAwaitingBody("a1"))
        assertEquals(listOf("a1"), FtsSearch(driver).search("pangolin", ArticleFilter.All).map { it.id })
    }
}

private fun SqlDriver.countOf(sql: String): Long =
    executeQuery(null, sql, { c -> QueryResult.Value(if (c.next().value) c.getLong(0)!! else 0L) }, 0).value

private fun SqlDriver.isAwaitingBody(articleId: String): Boolean =
    countOf("SELECT COUNT(*) FROM ks_search_awaiting_body WHERE id = '$articleId'") > 0

private fun SqlDriver.isIndexed(articleId: String): Boolean =
    countOf("SELECT COUNT(*) FROM articles_fts_docsize d JOIN articles a ON a.rowid = d.id WHERE a.id = '$articleId'") > 0
