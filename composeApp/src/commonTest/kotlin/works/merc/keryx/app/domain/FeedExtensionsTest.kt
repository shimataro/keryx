package works.merc.keryx.app.domain

import works.merc.keryx.app.data.local.db.Feeds
import kotlin.test.Test
import kotlin.test.assertEquals

class FeedExtensionsTest {

    @Test
    fun displayTitleUsesCustomTitleWhenPresent() {
        assertEquals("My Feed", feed(title = "Parsed Title", customTitle = "My Feed").displayTitle())
    }

    @Test
    fun displayTitleFallsBackToParsedTitleWhenCustomIsNull() {
        assertEquals("Parsed Title", feed(title = "Parsed Title", customTitle = null).displayTitle())
    }

    @Test
    fun displayTitleFallsBackToParsedTitleWhenCustomIsBlank() {
        assertEquals("Parsed Title", feed(title = "Parsed Title", customTitle = "   ").displayTitle())
    }

    private fun feed(title: String, customTitle: String?): Feeds = Feeds(
        id = "id",
        url = "https://example.com/feed",
        site_url = null,
        title = title,
        description = null,
        favicon_url = null,
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
