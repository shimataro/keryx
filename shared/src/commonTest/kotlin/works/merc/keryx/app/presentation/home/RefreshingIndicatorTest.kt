package works.merc.keryx.app.presentation.home

import works.merc.keryx.app.data.local.db.Feeds
import kotlin.test.Test
import kotlin.test.assertEquals

class RefreshingIndicatorTest {

    @Test
    fun onlyCollapsedFoldersHoldingARefreshingFeedAreReturned() {
        val feeds = listOf(feed("f1", "folderA"), feed("f2", "folderB"), feed("f3", "folderC"))
        val result = collapsedFoldersRefreshing(
            feeds = feeds,
            refreshingFeedIds = setOf("f1", "f2"),
            collapsedFolderIds = setOf("folderA", "folderC"),
        )
        // folderB is expanded; folderC is collapsed but nothing in it refreshes.
        assertEquals(setOf("folderA"), result)
    }

    @Test
    fun folderlessRefreshingFeedsAreExcluded() {
        val result = collapsedFoldersRefreshing(
            feeds = listOf(feed("f1", null)),
            refreshingFeedIds = setOf("f1"),
            collapsedFolderIds = setOf("folderA"),
        )
        assertEquals(emptySet(), result)
    }

    @Test
    fun noRefreshingFeedsOrNoCollapsedFoldersYieldsNothing() {
        val feeds = listOf(feed("f1", "folderA"))
        assertEquals(emptySet(), collapsedFoldersRefreshing(feeds, emptySet(), setOf("folderA")))
        assertEquals(emptySet(), collapsedFoldersRefreshing(feeds, setOf("f1"), emptySet()))
    }

    @Test
    fun aRefreshingIdWithoutAMatchingFeedIsIgnored() {
        val result = collapsedFoldersRefreshing(listOf(feed("f1", "folderA")), setOf("ghost"), setOf("folderA"))
        assertEquals(emptySet(), result)
    }

    @Test
    fun severalRefreshingFeedsInOneFolderYieldItOnce() {
        val result = collapsedFoldersRefreshing(
            feeds = listOf(feed("f1", "folderA"), feed("f2", "folderA")),
            refreshingFeedIds = setOf("f1", "f2"),
            collapsedFolderIds = setOf("folderA"),
        )
        assertEquals(setOf("folderA"), result)
    }
}

private fun feed(id: String, folderId: String?): Feeds = Feeds(
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
    folder_id = folderId,
    deleted_at = null,
    updated_at = 0L,
    created_at = 0L,
    sort_order = 0L,
    folder_updated_at = null,
    sort_order_updated_at = null,
    custom_title_updated_at = null,
    deleted_updated_at = null,
)
