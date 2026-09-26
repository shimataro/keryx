package works.merc.keryx.app.data.remote

import works.merc.keryx.app.core.DiscoveredFeedType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeedDiscoveryTest {

    @Test
    fun returnsMultipleCandidatesInDocumentOrder() {
        val html = """<html><head>
            <link rel="alternate" type="application/rss+xml" href="/rss.xml" title="RSS"/>
            <link rel="alternate" type="application/atom+xml" href="/atom.xml" title="Atom"/>
            </head><body></body></html>"""
        val result = FeedDiscovery.discover(html, "https://ex.com/")
        assertEquals(2, result.size)
        assertEquals("https://ex.com/rss.xml", result[0].url)
        assertEquals("https://ex.com/atom.xml", result[1].url)
    }

    @Test
    fun deduplicatesIdenticalResolvedUrls() {
        val html = """<html><head>
            <link rel="alternate" type="application/rss+xml" href="/feed.xml" title="A"/>
            <link rel="alternate" type="application/atom+xml" href="https://ex.com/feed.xml" title="B"/>
            </head><body></body></html>"""
        val result = FeedDiscovery.discover(html, "https://ex.com/")
        assertEquals(1, result.size)
        assertEquals("https://ex.com/feed.xml", result.single().url)
    }

    @Test
    fun recognizesBothMimeTypesAndSkipsUnsupported() {
        val html = """<html><head>
            <link rel="alternate" type="application/rss+xml" href="/rss.xml"/>
            <link rel="alternate" type="application/atom+xml" href="/atom.xml"/>
            <link rel="alternate" type="application/json" href="/feed.json"/>
            </head><body></body></html>"""
        val result = FeedDiscovery.discover(html, "https://ex.com/")
        assertEquals(2, result.size)
        assertEquals(DiscoveredFeedType.Rss, result[0].type)
        assertEquals(DiscoveredFeedType.Atom, result[1].type)
    }

    @Test
    fun malformedHtmlDoesNotThrow() {
        // ksoup is very tolerant of broken markup; this asserts discover() never propagates
        // a parse exception regardless of how pathological the input is.
        val html = "<html><head><link rel=alternate type=application/rss+xml href=/feed.xml<<<>>>" +
            "<<<unterminated"
        val result = FeedDiscovery.discover(html, "https://ex.com/")
        // No crash is the actual assertion; the returned list may or may not be empty
        // depending on how ksoup recovers from the malformed markup.
        assertTrue(result.isEmpty() || result.isNotEmpty())
    }

    @Test
    fun resolvesRelativeHrefAgainstBaseUrl() {
        val html = """<link rel="alternate" type="application/rss+xml" href="/feed.xml"/>"""
        val result = FeedDiscovery.discover(html, "https://ex.com/blog/index.html")
        assertEquals("https://ex.com/feed.xml", result.single().url)
    }

    @Test
    fun blankTitleResultsInNullTitle() {
        val html = """<link rel="alternate" type="application/rss+xml" href="/feed.xml" title=""/>"""
        val result = FeedDiscovery.discover(html, "https://ex.com/")
        assertNull(result.single().title)
    }
}
