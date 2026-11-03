package works.merc.keryx.app.presentation.home

import works.merc.keryx.app.core.ArticleFilter
import works.merc.keryx.app.data.local.db.Feeds
import kotlin.test.Test
import kotlin.test.assertEquals

class NewFeedPlacementTest {
    private val feeds = listOf(
        placementFeed("a2", folderId = "f1", sortOrder = 5L),
        placementFeed("a1", folderId = "f1", sortOrder = 2L),
        placementFeed("u2", folderId = null, sortOrder = 9L),
        placementFeed("u1", folderId = null, sortOrder = 3L),
        placementFeed("b1", folderId = "f2", sortOrder = 7L),
    )

    @Test
    fun folderFilterFilesIntoThatFolderBeforeItsFirstFeed() {
        val p = newFeedPlacement(ArticleFilter.Folder("f1"), FeedListRowSelection.Folder("f1"), feeds)
        assertEquals(NewFeedPlacement("f1", null, "a1", null), p)
    }

    @Test
    fun folderFilterWithEmptyFolderHasNoBeforeFeed() {
        val p = newFeedPlacement(ArticleFilter.Folder("empty"), FeedListRowSelection.Folder("empty"), feeds)
        assertEquals(NewFeedPlacement("empty", null, null, null), p)
    }

    @Test
    fun filedFeedFilterUsesItsFolderAndInsertsAfterIt() {
        val p = newFeedPlacement(ArticleFilter.Feed("a2"), FeedListRowSelection.FeedInFolderGroup("a2"), feeds)
        assertEquals(NewFeedPlacement("f1", "a2", "a1", null), p)
    }

    @Test
    fun unfiledFeedFilterTargetsNoFolderGroup() {
        val p = newFeedPlacement(ArticleFilter.Feed("u2"), FeedListRowSelection.FeedInFolderGroup("u2"), feeds)
        assertEquals(NewFeedPlacement(null, "u2", "u1", null), p)
    }

    @Test
    fun unknownFeedFilterFallsBackToNoFolderGroupButKeepsAfterFeedId() {
        val p = newFeedPlacement(ArticleFilter.Feed("ghost"), FeedListRowSelection.FeedInFolderGroup("ghost"), feeds)
        assertEquals(NewFeedPlacement(null, "ghost", "u1", null), p)
    }

    @Test
    fun allFilterTargetsNoFolderGroup() {
        val p = newFeedPlacement(ArticleFilter.All, FeedListRowSelection.All, feeds)
        assertEquals(NewFeedPlacement(null, null, "u1", null), p)
    }

    @Test
    fun starredFilterTargetsNoFolderGroup() {
        val p = newFeedPlacement(ArticleFilter.Starred, FeedListRowSelection.Starred, feeds)
        assertEquals(NewFeedPlacement(null, null, "u1", null), p)
    }

    @Test
    fun tagSelectionYieldsTagIdAndNoFolderGroup() {
        val p = newFeedPlacement(ArticleFilter.Tag("t1"), FeedListRowSelection.Tag("t1"), feeds)
        assertEquals(NewFeedPlacement(null, null, "u1", "t1"), p)
    }

    @Test
    fun feedInTagSelectionKeepsFeedPlacementAndAddsTagId() {
        val p = newFeedPlacement(ArticleFilter.Feed("a1"), FeedListRowSelection.FeedInTag("a1", "t1"), feeds)
        assertEquals(NewFeedPlacement("f1", "a1", "a1", "t1"), p)
    }

    @Test
    fun emptyNoFolderGroupHasNoBeforeFeed() {
        val onlyFiled = feeds.filter { it.folder_id != null }
        val p = newFeedPlacement(ArticleFilter.All, FeedListRowSelection.All, onlyFiled)
        assertEquals(NewFeedPlacement(null, null, null, null), p)
    }

    @Test
    fun emptyFeedListYieldsNoBeforeFeed() {
        val p = newFeedPlacement(ArticleFilter.All, FeedListRowSelection.All, emptyList())
        assertEquals(NewFeedPlacement(null, null, null, null), p)
    }
}

private fun placementFeed(id: String, folderId: String?, sortOrder: Long): Feeds = Feeds(
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
    sort_order = sortOrder,
    folder_updated_at = null,
    sort_order_updated_at = null,
    custom_title_updated_at = null,
    deleted_updated_at = null,
)
