package works.merc.keryx.app.ui.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import works.merc.keryx.app.data.local.db.Articles

/**
 * [ArticleContentCache] is deliberately self-contained (no `HomeViewModel`), so its
 * clear-while-loading race is verified directly here rather than through
 * `HomeViewModelTest`'s DB-backed suite.
 */
class ArticleContentCacheTest {

    private fun articleRow(id: String, content: String) = Articles(
        id = id,
        feed_id = "f1",
        guid = id,
        url = "https://example.com/$id",
        title = id,
        summary = null,
        content = content,
        author = null,
        published_at = null,
        thumbnail_url = null,
        is_read = 0L,
        read_at = null,
        is_starred = 0L,
        starred_at = null,
        cached_at = 0L,
        search_text = "",
        updated_at = 0L,
        created_at = 0L,
        deleted_at = null,
        deleted_updated_at = null,
    )

    // UnconfinedTestDispatcher for the same reason ArticleReturnRippleTest uses it: request()'s
    // scope.launch must run eagerly up to its first real suspension point (the CompletableDeferred
    // await below) so the test can clear() while it is still pending, rather than merely queuing it.
    @Test
    fun clearDiscardsARowThatFinishesLoadingAfterwards() = runTest(UnconfinedTestDispatcher()) {
        val pending = CompletableDeferred<Articles?>()
        val cache = ArticleContentCache(
            scope = this,
            dispatcher = UnconfinedTestDispatcher(),
            load = { pending.await() },
        )

        cache.request("a1")
        cache.clear()
        pending.complete(articleRow("a1", "<p>stale</p>"))
        testScheduler.advanceUntilIdle()

        // clear() must actually win: inserting the stale row here is exactly the bug this test
        // guards — the reader has already been told to forget everything.
        assertTrue(cache.rows.value.isEmpty())
    }

    @Test
    fun aRequestAfterClearIsNotBlockedByTheDisownedLookupItReplaced() = runTest(UnconfinedTestDispatcher()) {
        val firstLoad = CompletableDeferred<Articles?>()
        val secondLoad = CompletableDeferred<Articles?>()
        val pendingLoads = mutableListOf(firstLoad, secondLoad)
        val cache = ArticleContentCache(
            scope = this,
            dispatcher = UnconfinedTestDispatcher(),
            load = { pendingLoads.removeAt(0).await() },
        )

        cache.request("a1")
        cache.clear()
        // The reader remounted and asked for the same article again. If clear() had left "a1" in
        // `inFlight` (rather than resetting it), this would be silently dropped and the article
        // would never load.
        cache.request("a1")
        firstLoad.complete(articleRow("a1", "<p>stale</p>"))
        testScheduler.advanceUntilIdle()

        assertFalse("a1" in cache.rows.value)

        secondLoad.complete(articleRow("a1", "<p>fresh</p>"))
        testScheduler.advanceUntilIdle()

        assertEquals("<p>fresh</p>", cache.rows.value["a1"]?.content)
    }
}
