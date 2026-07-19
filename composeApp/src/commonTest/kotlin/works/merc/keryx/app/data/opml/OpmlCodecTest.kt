package works.merc.keryx.app.data.opml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpmlCodecTest {
    @Test
    fun importsFlatAndNestedOutlines() {
        val xml = """
            <opml version="2.0"><head><title>Subs</title></head><body>
              <outline text="Tech" title="Tech">
                <outline type="rss" text="A" xmlUrl="https://a.com/feed" htmlUrl="https://a.com"/>
              </outline>
              <outline type="rss" text="B" xmlUrl="https://b.com/rss"/>
            </body></opml>
        """.trimIndent()
        val feeds = OpmlCodec.import(xml)
        assertEquals(2, feeds.size)
        assertEquals("https://a.com/feed", feeds[0].xmlUrl)
        assertEquals("A", feeds[0].title)
        assertEquals("https://b.com/rss", feeds[1].xmlUrl)
    }

    @Test
    fun importDeduplicatesByUrl() {
        val xml = """
            <opml><body>
              <outline xmlUrl="https://a.com/feed"/>
              <outline xmlUrl="https://a.com/feed"/>
            </body></opml>
        """.trimIndent()
        assertEquals(1, OpmlCodec.import(xml).size)
    }

    @Test
    fun exportEscapesAndRoundTrips() {
        val feeds = listOf(
            OpmlCodec.ExportFeed(title = "A & B <feed>", xmlUrl = "https://a.com/feed?x=1&y=2", htmlUrl = "https://a.com"),
        )
        val xml = OpmlCodec.export(feeds)
        assertTrue(xml.contains("&amp;"))
        assertTrue(xml.contains("&lt;feed&gt;"))

        val reimported = OpmlCodec.import(xml)
        assertEquals(1, reimported.size)
        assertEquals("https://a.com/feed?x=1&y=2", reimported[0].xmlUrl)
        assertEquals("A & B <feed>", reimported[0].title)
    }
}
