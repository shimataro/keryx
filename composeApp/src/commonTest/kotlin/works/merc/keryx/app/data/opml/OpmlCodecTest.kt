package works.merc.keryx.app.data.opml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
    fun importReadsEnclosingFolderNameAndLeavesTopLevelFeedsUnfoldered() {
        val xml = """
            <opml version="2.0"><body>
              <outline text="Tech">
                <outline type="rss" text="A" xmlUrl="https://a.com/feed"/>
              </outline>
              <outline type="rss" text="B" xmlUrl="https://b.com/rss"/>
            </body></opml>
        """.trimIndent()

        val feeds = OpmlCodec.import(xml)

        assertEquals("Tech", feeds[0].folderName)
        assertNull(feeds[1].folderName)
    }

    @Test
    fun importFallsBackToTextWhenTitleIsEmpty() {
        val xml = """
            <opml version="2.0"><body>
              <outline title="" text="Tech">
                <outline type="rss" text="A" xmlUrl="https://a.com/feed"/>
              </outline>
            </body></opml>
        """.trimIndent()

        val feeds = OpmlCodec.import(xml)

        assertEquals("Tech", feeds.single().folderName)
    }

    @Test
    fun importCollapsesNestedFoldersToTheInnermostName() {
        // Keryx feeds belong to at most one folder, so the innermost wrapper wins.
        val xml = """
            <opml version="2.0"><body>
              <outline text="Outer">
                <outline text="Inner">
                  <outline type="rss" text="A" xmlUrl="https://a.com/feed"/>
                </outline>
                <outline type="rss" text="B" xmlUrl="https://b.com/rss"/>
              </outline>
            </body></opml>
        """.trimIndent()

        val feeds = OpmlCodec.import(xml)

        assertEquals("Inner", feeds.single { it.xmlUrl == "https://a.com/feed" }.folderName)
        assertEquals("Outer", feeds.single { it.xmlUrl == "https://b.com/rss" }.folderName)
    }

    @Test
    fun importParsesCategoryIntoTagsTrimmingAndDroppingEmptyEntries() {
        val xml = """
            <opml version="2.0"><body>
              <outline type="rss" text="A" xmlUrl="https://a.com/feed" category=" kotlin , , news "/>
              <outline type="rss" text="B" xmlUrl="https://b.com/rss"/>
            </body></opml>
        """.trimIndent()

        val feeds = OpmlCodec.import(xml)

        assertEquals(listOf("kotlin", "news"), feeds[0].tags)
        assertEquals(emptyList<String>(), feeds[1].tags)
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
        val groups = listOf(
            null to listOf(
                OpmlCodec.ExportFeed(title = "A & B <feed>", xmlUrl = "https://a.com/feed?x=1&y=2", htmlUrl = "https://a.com"),
            ),
        )
        val xml = OpmlCodec.export(groups)
        assertTrue(xml.contains("&amp;"))
        assertTrue(xml.contains("&lt;feed&gt;"))

        val reimported = OpmlCodec.import(xml)
        assertEquals(1, reimported.size)
        assertEquals("https://a.com/feed?x=1&y=2", reimported[0].xmlUrl)
        assertEquals("A & B <feed>", reimported[0].title)
    }

    @Test
    fun exportOfFlatUnfolderedFeedsEmitsThemAtTopLevelWithoutAnyFolderOutline() {
        val groups = listOf(
            null to listOf(
                OpmlCodec.ExportFeed(title = "A", xmlUrl = "https://a.com/feed", htmlUrl = "https://a.com"),
                OpmlCodec.ExportFeed(title = "B", xmlUrl = "https://b.com/rss", htmlUrl = null),
            ),
        )

        val xml = OpmlCodec.export(groups)

        assertFalse(xml.contains("</outline>")) // no wrapper element at all
        assertTrue(xml.contains("""<outline type="rss" text="A" title="A" xmlUrl="https://a.com/feed" htmlUrl="https://a.com"/>"""))
        val reimported = OpmlCodec.import(xml)
        assertEquals(listOf("https://a.com/feed", "https://b.com/rss"), reimported.map { it.xmlUrl })
        assertTrue(reimported.all { it.folderName == null && it.tags.isEmpty() })
    }

    @Test
    fun exportNestsFolderGroupsAndWritesTagsAsCategory() {
        val groups = listOf(
            "Tech" to listOf(
                OpmlCodec.ExportFeed("A", "https://a.com/feed", "https://a.com", tags = listOf("kotlin", "news")),
            ),
            null to listOf(OpmlCodec.ExportFeed("B", "https://b.com/rss", null)),
        )

        val xml = OpmlCodec.export(groups)

        assertTrue(xml.contains("""<outline text="Tech">"""))
        assertTrue(xml.contains("""category="kotlin,news""""))
        // Folder group comes before the unfoldered feed, matching the caller's group order.
        assertTrue(xml.indexOf("""xmlUrl="https://a.com/feed"""") < xml.indexOf("""xmlUrl="https://b.com/rss""""))

        val reimported = OpmlCodec.import(xml)
        assertEquals(2, reimported.size)
        assertEquals("Tech", reimported[0].folderName)
        assertEquals(listOf("kotlin", "news"), reimported[0].tags)
        assertNull(reimported[1].folderName)
    }

    @Test
    fun exportOmitsCategoryAttributeForAFeedWithoutTags() {
        val groups = listOf("Tech" to listOf(OpmlCodec.ExportFeed("A", "https://a.com/feed", null)))

        val xml = OpmlCodec.export(groups)

        assertFalse(xml.contains("category"))
    }

    @Test
    fun exportRoundTripsFolderAndTagStructureAcrossMultipleGroups() {
        val groups = listOf(
            "Tech" to listOf(
                OpmlCodec.ExportFeed("A", "https://a.com/feed", null, tags = listOf("kotlin")),
                OpmlCodec.ExportFeed("B", "https://b.com/feed", null),
            ),
            "News" to listOf(OpmlCodec.ExportFeed("C", "https://c.com/feed", null, tags = listOf("kotlin", "daily"))),
            null to listOf(OpmlCodec.ExportFeed("D", "https://d.com/feed", null)),
        )

        val reimported = OpmlCodec.import(OpmlCodec.export(groups))

        assertEquals(
            listOf(
                Triple("https://a.com/feed", "Tech", listOf("kotlin")),
                Triple("https://b.com/feed", "Tech", emptyList<String>()),
                Triple("https://c.com/feed", "News", listOf("kotlin", "daily")),
                Triple("https://d.com/feed", null, emptyList<String>()),
            ),
            reimported.map { Triple(it.xmlUrl, it.folderName, it.tags) },
        )
    }
}
