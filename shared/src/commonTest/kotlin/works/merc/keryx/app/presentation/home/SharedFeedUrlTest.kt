package works.merc.keryx.app.presentation.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [extractSharedFeedUrl] turns text another app shared to Keryx into the URL the Add feed dialog is
 * pre-filled with. The text is untrusted, so only an http(s) URL may ever come out of it.
 */
class SharedFeedUrlTest {

    @Test
    fun aBareUrlIsReturnedAsIs() {
        assertEquals("https://example.com/feed.xml", extractSharedFeedUrl("https://example.com/feed.xml"))
        assertEquals("http://example.com/rss", extractSharedFeedUrl("http://example.com/rss"))
    }

    @Test
    fun surroundingWhitespaceIsIgnored() {
        assertEquals("https://example.com/", extractSharedFeedUrl("  \n https://example.com/ \t"))
    }

    @Test
    fun nothingUsableYieldsNull() {
        assertNull(extractSharedFeedUrl(null))
        assertNull(extractSharedFeedUrl(""))
        assertNull(extractSharedFeedUrl("   "))
        assertNull(extractSharedFeedUrl("just some words"))
        assertNull(extractSharedFeedUrl("example.com/feed"))
    }

    @Test
    fun otherSchemesAreNeverReturned() {
        assertNull(extractSharedFeedUrl("javascript:alert(1)"))
        assertNull(extractSharedFeedUrl("file:///etc/passwd"))
        assertNull(extractSharedFeedUrl("ftp://example.com/feed"))
        assertNull(extractSharedFeedUrl("intent://scan/#Intent;scheme=zxing;end"))
        assertNull(extractSharedFeedUrl("keryx://oauth2/callback?code=x"))
        assertNull(extractSharedFeedUrl("content://media/external/1"))
    }

    @Test
    fun theUrlIsPickedOutOfSurroundingProse() {
        assertEquals(
            "https://example.com/post/1",
            extractSharedFeedUrl("Interesting article — Example Blog https://example.com/post/1 via @someone"),
        )
        assertEquals("https://example.com/", extractSharedFeedUrl("Example Blog\nhttps://example.com/"))
    }

    @Test
    fun theFirstHttpUrlWins() {
        assertEquals(
            "https://first.example/",
            extractSharedFeedUrl("see https://first.example/ and http://second.example/"),
        )
        assertEquals(
            "http://first.example/",
            extractSharedFeedUrl("see http://first.example/ and https://second.example/"),
        )
    }

    @Test
    fun aNonHttpUrlBeforeTheHttpOneIsSkipped() {
        assertEquals("https://example.com/feed", extractSharedFeedUrl("ftp://files.example/x https://example.com/feed"))
    }

    @Test
    fun trailingSentencePunctuationIsDropped() {
        assertEquals("https://example.com/feed", extractSharedFeedUrl("Subscribe to https://example.com/feed."))
        assertEquals("https://example.com/feed", extractSharedFeedUrl("Try https://example.com/feed, it's good"))
        assertEquals("https://example.com/feed", extractSharedFeedUrl("Is it https://example.com/feed?"))
        assertEquals("https://example.com/feed", extractSharedFeedUrl("(https://example.com/feed)"))
        assertEquals("https://example.com/feed", extractSharedFeedUrl("[link](https://example.com/feed)"))
        assertEquals("https://example.com/feed", extractSharedFeedUrl("'https://example.com/feed';"))
        assertEquals("https://example.com/feed", extractSharedFeedUrl("「https://example.com/feed」"))
        assertEquals("https://example.com/feed", extractSharedFeedUrl("フィードはhttps://example.com/feed。"))
    }

    @Test
    fun angleBracketsAndQuotesEndTheUrl() {
        assertEquals("https://example.com/feed", extractSharedFeedUrl("<https://example.com/feed>"))
        assertEquals("https://example.com/feed", extractSharedFeedUrl("href=\"https://example.com/feed\" title"))
        assertEquals("https://example.com/feed", extractSharedFeedUrl("`https://example.com/feed`"))
    }

    @Test
    fun aClosingBracketTheUrlItselfOpenedIsKept() {
        assertEquals(
            "https://en.wikipedia.org/wiki/Keryx_(herald)",
            extractSharedFeedUrl("https://en.wikipedia.org/wiki/Keryx_(herald)"),
        )
        assertEquals(
            "https://en.wikipedia.org/wiki/Keryx_(herald)",
            extractSharedFeedUrl("(see https://en.wikipedia.org/wiki/Keryx_(herald))"),
        )
    }

    @Test
    fun queryAndFragmentAreKept() {
        assertEquals(
            "https://example.com/feed?format=rss&lang=en#top",
            extractSharedFeedUrl("https://example.com/feed?format=rss&lang=en#top"),
        )
    }

    @Test
    fun theSchemeMatchesCaseInsensitivelyAndIsLowercased() {
        assertEquals("https://Example.com/Feed", extractSharedFeedUrl("HTTPS://Example.com/Feed"))
        assertEquals("http://example.com/", extractSharedFeedUrl("Http://example.com/"))
    }

    @Test
    fun aSchemeWithNoHostIsSkipped() {
        assertNull(extractSharedFeedUrl("https://"))
        assertNull(extractSharedFeedUrl("http:///path"))
        assertNull(extractSharedFeedUrl("https://."))
        assertEquals("https://example.com/", extractSharedFeedUrl("https:// broken, then https://example.com/"))
    }

    @Test
    fun aPortStaysPartOfTheUrl() {
        assertEquals("https://example.com:8443/feed", extractSharedFeedUrl("https://example.com:8443/feed"))
    }
}
