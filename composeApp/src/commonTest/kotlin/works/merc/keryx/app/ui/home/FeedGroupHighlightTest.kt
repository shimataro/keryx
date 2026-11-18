package works.merc.keryx.app.ui.home

import works.merc.keryx.app.data.local.db.Feeds
import works.merc.keryx.app.presentation.home.DropBoundary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeedGroupHighlightTest {

    // --- highlightsFeedGroup ---

    @Test
    fun beforeFeedHighlightsTheGroupContainingThatFeed() {
        assertTrue(DropBoundary.BeforeFeed("a").highlightsFeedGroup("f1", setOf("a", "b")))
        assertFalse(DropBoundary.BeforeFeed("z").highlightsFeedGroup("f1", setOf("a", "b")))
    }

    @Test
    fun appendFeedsHighlightsOnlyTheGroupOfThatFolder() {
        assertTrue(DropBoundary.AppendFeeds("f1").highlightsFeedGroup("f1", emptySet()))
        assertFalse(DropBoundary.AppendFeeds("f2").highlightsFeedGroup("f1", emptySet()))
    }

    @Test
    fun appendFeedsWithNoFolderHighlightsTheNoFolderGroupOnly() {
        assertTrue(DropBoundary.AppendFeeds(null).highlightsFeedGroup(null, emptySet()))
        assertFalse(DropBoundary.AppendFeeds(null).highlightsFeedGroup("f1", emptySet()))
        assertFalse(DropBoundary.AppendFeeds("f1").highlightsFeedGroup(null, emptySet()))
    }

    @Test
    fun folderLevelBoundariesAndNoBoundaryHighlightNoFeedGroup() {
        assertFalse(DropBoundary.BeforeFolder("f1").highlightsFeedGroup("f1", setOf("a")))
        assertFalse(DropBoundary.AppendFolders.highlightsFeedGroup(null, setOf("a")))
        val none: DropBoundary? = null
        assertFalse(none.highlightsFeedGroup(null, setOf("a")))
    }

    // --- readerByline ---

    @Test
    fun bylineIsTheFeedsDisplayTitleAndFavicon() {
        val feeds = mapOf("a" to feed("a", title = "Title", customTitle = null, favicon = "https://ex.com/a.ico"))
        assertEquals(ReaderByline("Title", "https://ex.com/a.ico"), feeds.readerByline("a"))
    }

    @Test
    fun bylinePrefersACustomTitle() {
        val feeds = mapOf("a" to feed("a", title = "Title", customTitle = "Mine", favicon = null))
        assertEquals(ReaderByline("Mine", null), feeds.readerByline("a"))
    }

    @Test
    fun bylineIgnoresABlankCustomTitle() {
        val feeds = mapOf("a" to feed("a", title = "Title", customTitle = "  ", favicon = null))
        assertEquals("Title", feeds.readerByline("a").feedName)
    }

    @Test
    fun bylineIsEmptyForANullOrUnknownFeedId() {
        val feeds = mapOf("a" to feed("a", title = "Title", customTitle = null, favicon = null))
        assertEquals(ReaderByline(null, null), feeds.readerByline(null))
        assertEquals(ReaderByline(null, null), feeds.readerByline("missing"))
        assertNull(emptyMap<String, Feeds>().readerByline("a").faviconUrl)
    }

    private fun feed(id: String, title: String, customTitle: String?, favicon: String?): Feeds = Feeds(
        id = id,
        url = "https://example.com/$id",
        site_url = null,
        title = title,
        description = null,
        favicon_url = favicon,
        etag = null,
        last_modified = null,
        error_count = 0L,
        last_error = null,
        custom_title = customTitle,
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
}
