package works.merc.keryx.app.presentation.home

import works.merc.keryx.app.data.local.db.Feeds
import works.merc.keryx.app.domain.ArticleListRow
import kotlin.test.Test
import kotlin.test.assertEquals

/** Covers [articleListBody], which decides the article list's whole-pane states for both UIs. */
class ArticleListBodyTest {

    @Test
    fun isPendingUntilBothListsHaveLoaded() {
        assertEquals(ArticleListBody.Pending, articleListBody(feeds = null, articles = null))
        assertEquals(ArticleListBody.Pending, articleListBody(feeds = listOf(feed("f1")), articles = null))
        assertEquals(ArticleListBody.Pending, articleListBody(feeds = null, articles = listOf(row("a1"))))
    }

    @Test
    fun noFeedsOnceLoadedWithoutAnyFeed() {
        assertEquals(ArticleListBody.NoFeeds, articleListBody(feeds = emptyList(), articles = emptyList()))
    }

    @Test
    fun contentOnceLoadedWithFeedsWhetherOrNotTheListIsEmpty() {
        // "No articles" is decided from the list a UI renders, not here.
        assertEquals(ArticleListBody.Content, articleListBody(feeds = listOf(feed("f1")), articles = emptyList()))
        assertEquals(ArticleListBody.Content, articleListBody(feeds = listOf(feed("f1")), articles = listOf(row("a1"))))
    }
}

private fun row(id: String) = ArticleListRow(
    id = id, feed_id = "f1", title = "Title $id", url = "https://example.com/$id",
    published_at = null, created_at = 0L, is_read = 0L, is_starred = 0L,
)

private fun feed(id: String): Feeds = Feeds(
    id = id,
    url = "https://example.com/$id",
    site_url = null,
    title = "Feed $id",
    description = null,
    favicon_url = null,
    etag = null,
    last_modified = null,
    error_count = 0L,
    last_error = null,
    custom_title = null,
    folder_id = null,
    deleted_at = null,
    updated_at = 0L,
    created_at = 0L,
    sort_order = 0L,
    folder_updated_at = null,
    sort_order_updated_at = null,
    custom_title_updated_at = null,
    deleted_updated_at = null,
)
