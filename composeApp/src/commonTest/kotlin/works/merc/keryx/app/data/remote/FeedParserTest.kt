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
