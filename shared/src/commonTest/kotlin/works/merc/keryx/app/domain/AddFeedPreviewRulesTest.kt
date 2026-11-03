package works.merc.keryx.app.domain

import works.merc.keryx.app.core.DiscoveredFeedLink
import works.merc.keryx.app.core.FeedNotFoundException
import works.merc.keryx.app.data.local.db.Feeds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The pure top-level rules of `AddFeedPreviewResolver.kt` the add-feed dialog (both UIs) consults. */
class AddFeedPreviewRulesTest {
    private fun feed(url: String) = Feeds(
        id = "id-$url", url = url, site_url = null, title = "t", description = null, favicon_url = null,
        etag = null, last_modified = null, error_count = 0L, last_error = null, custom_title = null,
        folder_id = null, deleted_at = null, updated_at = 0L, created_at = 0L, sort_order = 0L,
        folder_updated_at = null, sort_order_updated_at = null, custom_title_updated_at = null,
        deleted_updated_at = null,
    )

    private val single = AddFeedPreview.Single("https://ex.com/feed", "Feed", 1)
    private val multiple = AddFeedPreview.Multiple(listOf(DiscoveredFeedLink("https://ex.com/a.xml")))

    // --- addFeedCanSubscribe ---

    @Test
    fun canSubscribeIsFalseWithoutAPreview() {
        assertFalse(addFeedCanSubscribe(null, emptySet()))
        assertFalse(addFeedCanSubscribe(null, setOf("https://ex.com/a.xml")))
    }

    @Test
    fun canSubscribeIsTrueForASingleFeedWhateverIsSelected() {
        assertTrue(addFeedCanSubscribe(single, emptySet()))
        assertTrue(addFeedCanSubscribe(single, setOf("ignored")))
    }

    @Test
    fun canSubscribeToMultipleOnlyOnceACandidateIsSelected() {
        assertFalse(addFeedCanSubscribe(multiple, emptySet()))
        assertTrue(addFeedCanSubscribe(multiple, setOf("https://ex.com/a.xml")))
    }

    @Test
    fun canSubscribeIsFalseForAFailedPreviewEvenWithASelection() {
        val failed = AddFeedPreview.Failed(FeedNotFoundException("missing"))
        assertFalse(addFeedCanSubscribe(failed, emptySet()))
        assertFalse(addFeedCanSubscribe(failed, setOf("https://ex.com/a.xml")))
    }

    // --- addFeedAlreadySubscribed (List<Feeds> and Set<String> overloads) ---

    @Test
    fun alreadySubscribedMatchesAnExactUrlInBothOverloads() {
        val feeds = listOf(feed("https://ex.com/feed"))
        assertTrue(addFeedAlreadySubscribed("https://ex.com/feed", feeds))
        assertTrue(addFeedAlreadySubscribed("https://ex.com/feed", setOf("https://ex.com/feed")))
        assertFalse(addFeedAlreadySubscribed("https://ex.com/other", feeds))
        assertFalse(addFeedAlreadySubscribed("https://ex.com/other", setOf("https://ex.com/feed")))
    }

    @Test
    fun alreadySubscribedNormalizesASchemelessInputToHttps() {
        val feeds = listOf(feed("https://ex.com/feed"))
        assertTrue(addFeedAlreadySubscribed("ex.com/feed", feeds))
        assertTrue(addFeedAlreadySubscribed("  ex.com/feed  ", setOf("https://ex.com/feed")))
    }

    @Test
    fun alreadySubscribedDoesNotUpgradeAnExplicitHttpScheme() {
        assertFalse(addFeedAlreadySubscribed("http://ex.com/feed", listOf(feed("https://ex.com/feed"))))
        assertTrue(addFeedAlreadySubscribed("http://ex.com/feed", listOf(feed("http://ex.com/feed"))))
        // A schemeless entry is looked up as https, so an http-only subscription is not a match.
        assertFalse(addFeedAlreadySubscribed("ex.com/feed", setOf("http://ex.com/feed")))
    }

    @Test
    fun alreadySubscribedIsFalseForABlankUrlAndForNoSubscriptions() {
        assertFalse(addFeedAlreadySubscribed("", listOf(feed("https://"))))
        assertFalse(addFeedAlreadySubscribed("   ", setOf("https://")))
        assertFalse(addFeedAlreadySubscribed("https://ex.com/feed", emptyList<Feeds>()))
        assertFalse(addFeedAlreadySubscribed("https://ex.com/feed", emptySet<String>()))
    }

    @Test
    fun theTwoOverloadsAgree() {
        val urls = listOf("https://ex.com/a", "http://ex.com/b")
        val feeds = urls.map(::feed)
        for (input in listOf("https://ex.com/a", "ex.com/a", "http://ex.com/b", "ex.com/b", "https://nope", "")) {
            assertEquals(addFeedAlreadySubscribed(input, feeds), addFeedAlreadySubscribed(input, urls.toSet()), input)
        }
    }
}
