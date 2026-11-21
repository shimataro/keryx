package works.merc.keryx.app.presentation.home

import works.merc.keryx.app.core.ArticleFilter
import works.merc.keryx.app.data.local.db.Feeds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SelectedFeedForTest {

    private val feeds = listOf(testFeed("f1"), testFeed("f2"))

    @Test
    fun returnsTheFeedAFeedFilterSelects() {
        assertEquals(feeds[1], selectedFeedFor(ArticleFilter.Feed("f2"), feeds))
    }

    @Test
    fun returnsNullForAFeedNoLongerInTheList() {
        assertNull(selectedFeedFor(ArticleFilter.Feed("gone"), feeds))
        assertNull(selectedFeedFor(ArticleFilter.Feed("f1"), emptyList()))
    }

    @Test
    fun returnsNullForFiltersThatAreNotASingleFeed() {
        assertNull(selectedFeedFor(ArticleFilter.All, feeds))
        assertNull(selectedFeedFor(ArticleFilter.Starred, feeds))
        // Same id as a feed, but a folder/tag filter never resolves to a feed.
        assertNull(selectedFeedFor(ArticleFilter.Folder("f1"), feeds))
        assertNull(selectedFeedFor(ArticleFilter.Tag("f1"), feeds))
    }

    private fun testFeed(id: String) = Feeds(
        id = id, url = "https://example.com/$id", site_url = null, title = "Feed $id", description = null,
        favicon_url = null, custom_title = null, folder_id = null, deleted_at = null, updated_at = 0L, created_at = 0L,
        sort_order = 0L, folder_updated_at = null, sort_order_updated_at = null,
        custom_title_updated_at = null, deleted_updated_at = null,
    )
}
