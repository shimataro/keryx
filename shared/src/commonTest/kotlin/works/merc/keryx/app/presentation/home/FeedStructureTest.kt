package works.merc.keryx.app.presentation.home

import works.merc.keryx.app.core.FEED_ERROR_REASON_GONE
import works.merc.keryx.app.data.local.db.Feeds
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Covers [feedsStructurallyEqual], which gates `HomeViewModel.structuralFeeds`' emissions. */
class FeedStructureTest {

    @Test
    fun onlyTheRefreshBookkeepingFieldsDifferingIsStillEqual() {
        val base = listOf(feed("f1"), feed("f2"))
        val refreshed = listOf(
            feed("f1").copy(etag = "\"v2\"", last_modified = "Wed, 30 Sep 2026 00:00:00 GMT", updated_at = 1_000L),
            feed("f2").copy(updated_at = 2_000L),
        )

        assertTrue(feedsStructurallyEqual(base, refreshed))
        assertTrue(feedsStructurallyEqual(emptyList(), emptyList()))
    }

    @Test
    fun anyOtherFieldDifferingIsNotEqual() {
        val base = feed("f1")
        val changed = listOf(
            base.copy(title = "Renamed"),
            base.copy(custom_title = "Mine"),
            base.copy(favicon_url = "https://example.com/f.ico"),
            base.copy(error_count = 1L),
            base.copy(last_error = FEED_ERROR_REASON_GONE),
            base.copy(site_url = "https://example.com"),
            base.copy(url = "https://example.com/moved"),
            base.copy(folder_id = "d1"),
            base.copy(sort_order = 5L),
            base.copy(deleted_at = 1L),
            feed("f2"),
        )

        for (other in changed) {
            assertFalse(feedsStructurallyEqual(listOf(base), listOf(other)), "expected a difference: $other")
        }
    }

    @Test
    fun sizeOrOrderDifferingIsNotEqual() {
        val f1 = feed("f1")
        val f2 = feed("f2")

        assertFalse(feedsStructurallyEqual(listOf(f1), listOf(f1, f2)))
        assertFalse(feedsStructurallyEqual(listOf(f1, f2), emptyList()))
        // The sidebar is built in list order, so a reorder is a structural change.
        assertFalse(feedsStructurallyEqual(listOf(f1, f2), listOf(f2, f1)))
    }
}

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
