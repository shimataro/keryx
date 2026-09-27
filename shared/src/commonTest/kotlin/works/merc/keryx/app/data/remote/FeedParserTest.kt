package works.merc.keryx.app.data.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeedParserTest {
    @Test
    fun parsesRss2() {
        val xml = """
            <?xml version="1.0"?>
            <rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/"
                 xmlns:dc="http://purl.org/dc/elements/1.1/">
              <channel>
                <title>My Blog</title>
                <link>https://blog.example.com</link>
                <description>A blog</description>
                <item>
                  <title>First Post</title>
                  <link>https://blog.example.com/1</link>
                  <guid>post-1</guid>
                  <description>summary text</description>
                  <content:encoded>full content</content:encoded>
                  <dc:creator>Alice</dc:creator>
                  <pubDate>Wed, 02 Oct 2002 08:00:00 GMT</pubDate>
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val feed = FeedParser.detectAndParse(xml)
        assertNotNull(feed)
        assertEquals("My Blog", feed.title)
        assertEquals("https://blog.example.com", feed.siteUrl)
        assertEquals(1, feed.articles.size)
        val a = feed.articles.first()
        assertEquals("post-1", a.guid)
        assertEquals("First Post", a.title)
        assertEquals("https://blog.example.com/1", a.url)
        assertEquals("full content", a.content)
        assertEquals("Alice", a.author)
        assertEquals(1033545600000L, a.publishedAtMillis)
    }

    @Test
    fun parsesAtom() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Atom Feed</title>
              <subtitle>sub</subtitle>
              <link href="https://atom.example.com"/>
              <entry>
                <title>Entry One</title>
                <id>urn:uuid:1</id>
                <link href="https://atom.example.com/1"/>
                <updated>2003-12-13T18:30:02Z</updated>
                <author><name>Bob</name></author>
                <summary>the summary</summary>
                <content>the content</content>
              </entry>
            </feed>
        """.trimIndent()

        val feed = FeedParser.detectAndParse(xml)
        assertNotNull(feed)
        assertEquals("Atom Feed", feed.title)
        assertEquals(1, feed.articles.size)
        val a = feed.articles.first()
        assertEquals("urn:uuid:1", a.guid)
        assertEquals("Entry One", a.title)
        assertEquals("https://atom.example.com/1", a.url)
        assertEquals("Bob", a.author)
        assertEquals("the content", a.content)
        assertEquals(1071340202000L, a.publishedAtMillis)
    }

    @Test
    fun parsesAtom03IssuedAsPublishedDate() {
        // Regression: Atom 0.3 (still emitted by livedoor Blog, e.g. itainews.com/atom.xml) names the
        // dates <issued>/<modified>, and writes <modified> first — so the publication date is only
        // picked when the tag list is a real priority list rather than document order.
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed version="0.3" xmlns="http://purl.org/atom/ns#" xml:lang="ja">
              <title>Atom 0.3 Feed</title>
              <link rel="alternate" type="text/html" href="https://old.example.com/"/>
              <modified>2026-08-01T10:03:06Z</modified>
              <tagline>the tagline</tagline>
              <entry>
                <title>Entry One</title>
                <link rel="alternate" type="text/html" href="https://old.example.com/1.html"/>
                <modified>2026-08-01T10:03:04Z</modified>
                <issued>2026-08-01T19:02:50+09:00</issued>
                <id>tag:example.com,2026:1</id>
                <summary type="text/plain">the summary</summary>
                <content type="text/html" mode="escaped">the content</content>
              </entry>
            </feed>
        """.trimIndent()

        val feed = FeedParser.detectAndParse(xml)
        assertNotNull(feed)
        assertEquals("Atom 0.3 Feed", feed.title)
        assertEquals("the tagline", feed.description)
        assertEquals("https://old.example.com/", feed.siteUrl)
        assertEquals(1, feed.articles.size)
        val a = feed.articles.first()
        assertEquals("tag:example.com,2026:1", a.guid)
        assertEquals("https://old.example.com/1.html", a.url)
        assertEquals("the content", a.content)
        // <issued>, not the <modified> that precedes it (1785578584000L).
        assertEquals(1785578570000L, a.publishedAtMillis)
    }

    @Test
    fun prefersPublishedOverUpdatedRegardlessOfDocumentOrder() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Atom Feed</title>
              <entry>
                <title>Entry One</title>
                <id>urn:uuid:1</id>
                <updated>2003-12-13T18:30:02Z</updated>
                <published>2003-12-10T00:00:00Z</published>
              </entry>
            </feed>
        """.trimIndent()

        val feed = FeedParser.detectAndParse(xml)
        assertNotNull(feed)
        // <published>, not the <updated> that precedes it (1071340202000L).
        assertEquals(1071014400000L, feed.articles.first().publishedAtMillis)
    }

    @Test
    fun parsesRss10Rdf() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                     xmlns="http://purl.org/rss/1.0/"
                     xmlns:dc="http://purl.org/dc/elements/1.1/">
              <channel rdf:about="https://rdf.example.com/">
                <title>RDF Feed</title>
                <link>https://rdf.example.com/</link>
                <description>rdf desc</description>
              </channel>
              <item rdf:about="https://rdf.example.com/1">
                <title>Entry One</title>
                <link>https://rdf.example.com/1</link>
                <description>the summary</description>
                <dc:creator>Bob</dc:creator>
                <dc:date>2003-12-13T18:30:02Z</dc:date>
              </item>
            </rdf:RDF>
        """.trimIndent()

        val feed = FeedParser.detectAndParse(xml)
        assertNotNull(feed)
        assertEquals("RDF Feed", feed.title)
        assertEquals("rdf desc", feed.description)
        assertEquals(1, feed.articles.size)
        val a = feed.articles.first()
        assertEquals("https://rdf.example.com/1", a.guid)
        assertEquals("Entry One", a.title)
        assertEquals("the summary", a.summary)
        assertEquals("Bob", a.author)
        assertEquals(1071340202000L, a.publishedAtMillis)
    }

    @Test
    fun channelTitleNotOverriddenByItem() {
        // Regression: getElementsByTag would pick up the item's <title>.
        val xml = """
            <rss version="2.0"><channel>
              <title>Channel Title</title>
              <link>https://x.com</link>
              <item><title>Item Title</title><link>https://x.com/1</link></item>
            </channel></rss>
        """.trimIndent()
        val feed = FeedParser.detectAndParse(xml)
        assertEquals("Channel Title", feed?.title)
        assertEquals("Item Title", feed?.articles?.first()?.title)
    }

    @Test
    fun returnsNullForNonFeedHtml() {
        assertNull(FeedParser.detectAndParse("<html><body>hi</body></html>"))
    }
}
