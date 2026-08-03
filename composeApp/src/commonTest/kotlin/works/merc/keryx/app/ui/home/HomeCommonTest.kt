package works.merc.keryx.app.ui.home

import works.merc.keryx.app.core.ArticleFilter
import works.merc.keryx.app.data.local.db.Feeds
import works.merc.keryx.app.data.local.db.Folders
import works.merc.keryx.app.data.local.db.Tags
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HomeCommonTest {

    @Test
    fun buildOrderedFiltersPutsSidebarRowsFirstThenUnassignedFeedsThenTagsWhenNoFolders() {
        val tags = listOf(tag("t1"), tag("t2"))
        val feeds = listOf(feed("f1"), feed("f2"))

        val ordered = buildOrderedFilters(tags, emptyList(), feeds, emptySet())

        assertEquals(
            listOf(
                ArticleFilter.All,
                ArticleFilter.Starred,
                ArticleFilter.Search,
                ArticleFilter.Feed("f1"),
                ArticleFilter.Feed("f2"),
                ArticleFilter.Tag("t1"),
                ArticleFilter.Tag("t2"),
            ),
            ordered,
        )
    }

    @Test
    fun buildOrderedFiltersPutsFolderGroupsBeforeUnassignedFeedsAndTags() {
        val tags = listOf(tag("t1"))
        val folders = listOf(folder("d1"))
        val feeds = listOf(feed("f1", folderId = "d1"), feed("f2"))

        val ordered = buildOrderedFilters(tags, folders, feeds, emptySet())

        assertEquals(
            listOf(
                ArticleFilter.All,
                ArticleFilter.Starred,
                ArticleFilter.Search,
                ArticleFilter.Folder("d1"),
                ArticleFilter.Feed("f1"),
                ArticleFilter.Feed("f2"),
                ArticleFilter.Tag("t1"),
            ),
            ordered,
        )
    }

    @Test
    fun buildOrderedFiltersSkipsFeedsUnderCollapsedFolders() {
        val folders = listOf(folder("d1"))
        val feeds = listOf(feed("f1", folderId = "d1"))

        val ordered = buildOrderedFilters(emptyList(), folders, feeds, setOf("d1"))

        assertEquals(
            listOf(ArticleFilter.All, ArticleFilter.Starred, ArticleFilter.Search, ArticleFilter.Folder("d1")),
            ordered,
        )
    }

    @Test
    fun buildOrderedFiltersWithNoTagsOrFeedsHasOnlySidebarRows() {
        val ordered = buildOrderedFilters(emptyList(), emptyList(), emptyList(), emptySet())

        assertEquals(listOf(ArticleFilter.All, ArticleFilter.Starred, ArticleFilter.Search), ordered)
    }

    @Test
    fun nextFeedFilterMovesForwardAndBackwardWithinBounds() {
        val ordered = buildOrderedFilters(listOf(tag("t1")), emptyList(), listOf(feed("f1")), emptySet())

        assertEquals(ArticleFilter.Starred, nextFeedFilter(ArticleFilter.All, ordered, 1))
        assertEquals(ArticleFilter.Tag("t1"), nextFeedFilter(ArticleFilter.Feed("f1"), ordered, 1))
        assertEquals(ArticleFilter.All, nextFeedFilter(ArticleFilter.Starred, ordered, -1))
    }

    @Test
    fun nextFeedFilterAtTopBoundaryReturnsNullInsteadOfReselectingCurrent() {
        val ordered = buildOrderedFilters(emptyList(), emptyList(), emptyList(), emptySet())

        assertNull(nextFeedFilter(ArticleFilter.All, ordered, -1))
    }

    @Test
    fun nextFeedFilterAtBottomBoundaryReturnsNullInsteadOfReselectingCurrent() {
        val ordered = buildOrderedFilters(emptyList(), emptyList(), listOf(feed("f1")), emptySet())

        assertNull(nextFeedFilter(ArticleFilter.Feed("f1"), ordered, 1))
    }

    @Test
    fun nextFeedFilterFallsBackToFirstEntryWhenCurrentIsNotInOrderedList() {
        val ordered = buildOrderedFilters(emptyList(), emptyList(), listOf(feed("f1"), feed("f2")), emptySet())

        // A filter for a feed that's already been unsubscribed (stale/defensive case): treated as
        // if currently at index 0 (`All`), so moving forward by 1 lands on the next entry, `Starred`.
        assertEquals(ArticleFilter.Starred, nextFeedFilter(ArticleFilter.Feed("gone"), ordered, 1))
    }

    @Test
    fun groupFeedsByFolderReturnsOnePairPerFolderInOrderPlusUnassignedLast() {
        val folders = listOf(folder("d1"), folder("d2"))
        val feeds = listOf(feed("f1", folderId = "d1"), feed("f2"))

        val groups = groupFeedsByFolder(feeds, folders)

        assertEquals(3, groups.size)
        assertEquals("d1", groups[0].first?.id)
        assertEquals(listOf("f1"), groups[0].second.map { it.id })
        assertEquals("d2", groups[1].first?.id)
        assertEquals(emptyList(), groups[1].second)
        assertNull(groups[2].first)
        assertEquals(listOf("f2"), groups[2].second.map { it.id })
    }

    @Test
    fun groupFeedsByFolderKeepsZeroFeedFolderAsEmptyGroup() {
        val folders = listOf(folder("d1"))

        val groups = groupFeedsByFolder(emptyList(), folders)

        assertEquals(2, groups.size)
        assertEquals("d1", groups[0].first?.id)
        assertEquals(emptyList(), groups[0].second)
        assertNull(groups[1].first)
        assertEquals(emptyList(), groups[1].second)
    }

    @Test
    fun groupFeedsByFolderPutsFeedWithMissingOrDeletedFolderInUnassignedBucket() {
        // Only "d1" is a live folder; "d-gone" isn't in the list (either non-existent or soft-deleted).
        val folders = listOf(folder("d1"))
        val feeds = listOf(feed("f1", folderId = "d1"), feed("f2", folderId = "d-gone"), feed("f3", folderId = null))

        val groups = groupFeedsByFolder(feeds, folders)

        assertEquals(listOf("f1"), groups[0].second.map { it.id })
        assertNull(groups[1].first)
        assertEquals(listOf("f2", "f3"), groups[1].second.map { it.id })
    }

    @Test
    fun groupFeedsByFolderPreservesInputFeedOrderEvenWhenNotAlphabetical() {
        // Feed titles/ids here are deliberately out of alphabetical order — grouping must
        // preserve whatever order the caller's list already has (a manually-arranged sort_order),
        // not silently re-sort by name.
        val folders = listOf(folder("d1"))
        val feeds = listOf(
            feed("zzz", folderId = "d1"),
            feed("mmm", folderId = "d1"),
            feed("aaa", folderId = "d1"),
        )

        val groups = groupFeedsByFolder(feeds, folders)

        assertEquals(listOf("zzz", "mmm", "aaa"), groups[0].second.map { it.id })
    }

    @Test
    fun groupFeedsByFolderPreservesInputFeedOrderWithinEachGroup() {
        val folders = listOf(folder("d1"))
        val feeds = listOf(
            feed("f3", folderId = "d1"),
            feed("f1", folderId = "d1"),
            feed("f2", folderId = "d1"),
        )

        val groups = groupFeedsByFolder(feeds, folders)

        assertEquals(listOf("f3", "f1", "f2"), groups[0].second.map { it.id })
    }

    @Test
    fun feedListItemIndexAlwaysReturnsZeroForSidebarRows() {
        // All, Starred, and Search are rendered outside the LazyColumn entirely; 0 means "scroll
        // to top", since "folders-header" is now the first LazyColumn item.
        val folders = listOf(folder("d1"))
        val feeds = listOf(feed("f1", folderId = "d1"))
        val tags = listOf(tag("t1"))

        assertEquals(0, feedListItemIndex(ArticleFilter.Starred, feeds, folders, tags, emptySet()))
        assertEquals(0, feedListItemIndex(ArticleFilter.All, feeds, folders, tags, emptySet()))
        assertEquals(0, feedListItemIndex(ArticleFilter.Search, feeds, folders, tags, emptySet()))
    }

    @Test
    fun feedListItemIndexWithNoFoldersOrTagsStartsFeedsRightAfterHeaders() {
        // No folders => no NoFolderHeader row, so index 1 (after "Folders" header) is the first feed.
        val feeds = listOf(feed("f1"), feed("f2"))

        assertEquals(1, feedListItemIndex(ArticleFilter.Feed("f1"), feeds, emptyList(), emptyList(), emptySet()))
        assertEquals(2, feedListItemIndex(ArticleFilter.Feed("f2"), feeds, emptyList(), emptyList(), emptySet()))
    }

    @Test
    fun feedListItemIndexWalksFoldersUnassignedFeedsDividerAndTagsInOrder() {
        val folders = listOf(folder("d1"), folder("d2"))
        val tags = listOf(tag("t1"), tag("t2"))
        val feeds = listOf(
            feed("f1", folderId = "d1"),
            feed("f2", folderId = "d2"),
            feed("f3"),
            feed("f4"),
        )

        // index 0: "Folders" header
        // index 1: folder d1 header
        // index 2: feed f1 (under d1)
        // index 3: folder d2 header
        // index 4: feed f2 (under d2)
        // index 5: NoFolderHeader (folders is non-empty)
        // index 6: feed f3
        // index 7: feed f4
        // index 8: divider
        // index 9: "Tags" header
        // index 10: tag t1
        // index 11: tag t2
        assertEquals(1, feedListItemIndex(ArticleFilter.Folder("d1"), feeds, folders, tags, emptySet()))
        assertEquals(2, feedListItemIndex(ArticleFilter.Feed("f1"), feeds, folders, tags, emptySet()))
        assertEquals(3, feedListItemIndex(ArticleFilter.Folder("d2"), feeds, folders, tags, emptySet()))
        assertEquals(4, feedListItemIndex(ArticleFilter.Feed("f2"), feeds, folders, tags, emptySet()))
        assertEquals(6, feedListItemIndex(ArticleFilter.Feed("f3"), feeds, folders, tags, emptySet()))
        assertEquals(7, feedListItemIndex(ArticleFilter.Feed("f4"), feeds, folders, tags, emptySet()))
        assertEquals(10, feedListItemIndex(ArticleFilter.Tag("t1"), feeds, folders, tags, emptySet()))
        assertEquals(11, feedListItemIndex(ArticleFilter.Tag("t2"), feeds, folders, tags, emptySet()))
    }

    @Test
    fun feedListItemIndexReturnsNullForFeedUnderCollapsedFolder() {
        val folders = listOf(folder("d1"))
        val feeds = listOf(feed("f1", folderId = "d1"))

        assertNull(feedListItemIndex(ArticleFilter.Feed("f1"), feeds, folders, emptyList(), setOf("d1")))
    }

    @Test
    fun feedListItemIndexStillReturnsFolderHeaderRowWhenCollapsed() {
        val folders = listOf(folder("d1"))
        val feeds = listOf(feed("f1", folderId = "d1"))

        assertEquals(1, feedListItemIndex(ArticleFilter.Folder("d1"), feeds, folders, emptyList(), setOf("d1")))
    }

    // --- feedsForTag ---

    @Test
    fun feedsForTagIsEmptyWhenNoFeedCarriesTheTag() {
        val feeds = listOf(feed("f1"), feed("f2"))

        assertEquals(emptyList(), feedsForTag(feeds, emptyMap(), "t1"))
    }

    @Test
    fun feedsForTagPreservesInputFeedOrder() {
        val feeds = listOf(feed("f3"), feed("f1"), feed("f2"))
        val feedTagMap = mapOf("f1" to setOf("t1"), "f2" to setOf("t1"), "f3" to setOf("t1"))

        assertEquals(listOf("f3", "f1", "f2"), feedsForTag(feeds, feedTagMap, "t1").map { it.id })
    }

    @Test
    fun feedsForTagIgnoresOtherTagsAttachments() {
        val feeds = listOf(feed("f1"), feed("f2"), feed("f3"))
        val feedTagMap = mapOf(
            "f1" to setOf("t1", "t2"),
            "f2" to setOf("t2"),
            // f3 has no entry at all.
        )

        assertEquals(listOf("f1"), feedsForTag(feeds, feedTagMap, "t1").map { it.id })
        assertEquals(listOf("f1", "f2"), feedsForTag(feeds, feedTagMap, "t2").map { it.id })
    }

    // --- feedListItemIndex, expanded tags ---

    @Test
    fun feedListItemIndexIgnoresTagFeedRowsWhenNoTagIsExpanded() {
        val tags = listOf(tag("t1"), tag("t2"))
        val feeds = listOf(feed("f1"), feed("f2"))
        val feedTagMap = mapOf("f1" to setOf("t1"), "f2" to setOf("t1"))

        // 0: "Folders" header, 1: f1, 2: f2, 3: divider, 4: "Tags" header
        assertEquals(5, feedListItemIndex(ArticleFilter.Tag("t1"), feeds, emptyList(), tags, emptySet(), feedTagMap, emptySet()))
        assertEquals(6, feedListItemIndex(ArticleFilter.Tag("t2"), feeds, emptyList(), tags, emptySet(), feedTagMap, emptySet()))
    }

    @Test
    fun feedListItemIndexShiftsLaterTagsByAnExpandedTagsFeedRows() {
        val tags = listOf(tag("t1"), tag("t2"), tag("t3"))
        val feeds = listOf(feed("f1"), feed("f2"), feed("f3"))
        val feedTagMap = mapOf("f1" to setOf("t1"), "f2" to setOf("t1"), "f3" to setOf("t2"))

        // 0: "Folders" header, 1-3: f1..f3, 4: divider, 5: "Tags" header,
        // 6: tag t1, 7: f1 (under t1), 8: f2 (under t1), 9: tag t2, 10: f3 (under t2), 11: tag t3
        val expanded = setOf("t1", "t2")
        assertEquals(6, feedListItemIndex(ArticleFilter.Tag("t1"), feeds, emptyList(), tags, emptySet(), feedTagMap, expanded))
        assertEquals(9, feedListItemIndex(ArticleFilter.Tag("t2"), feeds, emptyList(), tags, emptySet(), feedTagMap, expanded))
        assertEquals(11, feedListItemIndex(ArticleFilter.Tag("t3"), feeds, emptyList(), tags, emptySet(), feedTagMap, expanded))
    }

    @Test
    fun feedListItemIndexResolvesFeedToItsFolderRowNotItsTagNestedRow() {
        // A feed nested under an expanded tag is a duplicate view; the primary row under its
        // folder group is what a selection scrolls to.
        val tags = listOf(tag("t1"))
        val feeds = listOf(feed("f1"))
        val feedTagMap = mapOf("f1" to setOf("t1"))

        assertEquals(
            1,
            feedListItemIndex(ArticleFilter.Feed("f1"), feeds, emptyList(), tags, emptySet(), feedTagMap, setOf("t1")),
        )
    }

    @Test
    fun feedListItemIndexDefaultsToNoExpandedTagsWhenTagArgumentsAreOmitted() {
        val tags = listOf(tag("t1"), tag("t2"))
        val feeds = listOf(feed("f1"))

        assertEquals(4, feedListItemIndex(ArticleFilter.Tag("t1"), feeds, emptyList(), tags, emptySet()))
        assertEquals(5, feedListItemIndex(ArticleFilter.Tag("t2"), feeds, emptyList(), tags, emptySet()))
    }

    @Test
    fun feedListItemIndexReturnsNullForNonexistentIds() {
        val folders = listOf(folder("d1"))
        val tags = listOf(tag("t1"))
        val feeds = listOf(feed("f1", folderId = "d1"))

        assertNull(feedListItemIndex(ArticleFilter.Feed("gone"), feeds, folders, tags, emptySet()))
        assertNull(feedListItemIndex(ArticleFilter.Tag("gone"), feeds, folders, tags, emptySet()))
        assertNull(feedListItemIndex(ArticleFilter.Folder("gone"), feeds, folders, tags, emptySet()))
    }

    // --- autoScrollVelocityPxPerSec ---

    @Test
    fun autoScrollVelocityIsZeroBetweenTheTwoEdgeZones() {
        // Viewport 0..1000 with 100px edge zones: everything in 100..900 is the dead zone.
        assertEquals(0f, autoScrollVelocity(pointerY = 500f))
        assertEquals(0f, autoScrollVelocity(pointerY = 100f))
        assertEquals(0f, autoScrollVelocity(pointerY = 900f))
    }

    @Test
    fun autoScrollVelocityRampsTowardTheListStartInsideTheTopEdgeZone() {
        // Negative = scroll back toward the start of the list, matching LazyListState.scrollBy.
        assertEquals(-450f, autoScrollVelocity(pointerY = 50f))
        assertEquals(-90f, autoScrollVelocity(pointerY = 90f))
    }

    @Test
    fun autoScrollVelocityRampsTowardTheListEndInsideTheBottomEdgeZone() {
        assertEquals(450f, autoScrollVelocity(pointerY = 950f))
        assertEquals(90f, autoScrollVelocity(pointerY = 910f))
    }

    @Test
    fun autoScrollVelocityIsFullSpeedAtTheViewportEdges() {
        assertEquals(-900f, autoScrollVelocity(pointerY = 0f))
        assertEquals(900f, autoScrollVelocity(pointerY = 1000f))
    }

    @Test
    fun autoScrollVelocityStaysAtFullSpeedOutsideTheViewport() {
        assertEquals(-900f, autoScrollVelocity(pointerY = -300f))
        assertEquals(900f, autoScrollVelocity(pointerY = 1300f))
    }

    @Test
    fun autoScrollVelocityHalvesTheEdgeZonesWhenTheViewportIsTooShortForBoth() {
        // Viewport 0..60 with a 100px edge zone: each zone shrinks to 30px instead of overlapping,
        // so the midpoint stays a dead zone and each half still ramps to full speed at its edge.
        fun velocity(pointerY: Float) = autoScrollVelocityPxPerSec(
            pointerY = pointerY,
            viewportTop = 0f,
            viewportBottom = 60f,
            edgeZonePx = 100f,
            maxSpeedPxPerSec = 900f,
        )

        assertEquals(0f, velocity(30f))
        assertEquals(-900f, velocity(0f))
        assertEquals(900f, velocity(60f))
        assertEquals(-450f, velocity(15f))
        assertEquals(450f, velocity(45f))
    }

    @Test
    fun autoScrollVelocityIsZeroForADegenerateViewport() {
        assertEquals(
            0f,
            autoScrollVelocityPxPerSec(
                pointerY = 0f,
                viewportTop = 100f,
                viewportBottom = 100f,
                edgeZonePx = 50f,
                maxSpeedPxPerSec = 900f,
            ),
        )
    }

    // --- buildFeedListDropIndex ---

    @Test
    fun buildFeedListDropIndexMapsFeedsToTheirOwningFolderAndNextSibling() {
        val folders = listOf(folder("d1"), folder("d2"))
        val feeds = listOf(
            feed("f1", folderId = "d1"),
            feed("f2", folderId = "d1"),
            feed("f3", folderId = "d2"),
            feed("f4"),
        )

        val index = buildFeedListDropIndex(feeds, folders)

        assertEquals("d1", index.folderIdOfFeed["f1"])
        assertEquals("d1", index.folderIdOfFeed["f2"])
        assertEquals("d2", index.folderIdOfFeed["f3"])
        assertNull(index.folderIdOfFeed["f4"])
        assertEquals("f2", index.nextFeedInGroup["f1"])
        assertNull(index.nextFeedInGroup["f2"])
        assertNull(index.nextFeedInGroup["f3"])
        assertNull(index.nextFeedInGroup["f4"])
    }

    @Test
    fun buildFeedListDropIndexTracksFirstFeedPerGroupIncludingEmptyOnes() {
        val folders = listOf(folder("d1"), folder("d2"))
        val feeds = listOf(feed("f1", folderId = "d1"), feed("f2"))

        val index = buildFeedListDropIndex(feeds, folders)

        assertEquals("f1", index.firstFeedIdOfGroup["d1"])
        assertNull(index.firstFeedIdOfGroup["d2"])
        assertEquals("f2", index.firstFeedIdOfGroup[null])
    }

    @Test
    fun buildFeedListDropIndexTracksNextFolderInOrder() {
        val folders = listOf(folder("d1"), folder("d2"), folder("d3"))

        val index = buildFeedListDropIndex(emptyList(), folders)

        assertEquals("d2", index.nextFolderId["d1"])
        assertEquals("d3", index.nextFolderId["d2"])
        assertNull(index.nextFolderId["d3"])
    }

    @Test
    fun feedZoneBoundaryForPointsAtTheFirstFeedWhenGroupIsNonEmpty() {
        val index = buildFeedListDropIndex(listOf(feed("f1", folderId = "d1")), listOf(folder("d1")))

        assertEquals(DropBoundary.BeforeFeed("f1"), index.feedZoneBoundaryFor("d1"))
    }

    @Test
    fun feedZoneBoundaryForFallsBackToAppendFeedsWhenGroupIsEmpty() {
        val index = buildFeedListDropIndex(emptyList(), listOf(folder("d1")))

        assertEquals(DropBoundary.AppendFeeds("d1"), index.feedZoneBoundaryFor("d1"))
        assertEquals(DropBoundary.AppendFeeds(null), index.feedZoneBoundaryFor(null))
    }

    @Test
    fun belowBoundaryForFeedPointsAtTheNextFeedOrFallsBackToAppendFeeds() {
        val folders = listOf(folder("d1"))
        val feeds = listOf(feed("f1", folderId = "d1"), feed("f2", folderId = "d1"))
        val index = buildFeedListDropIndex(feeds, folders)

        assertEquals(DropBoundary.BeforeFeed("f2"), index.belowBoundaryForFeed("f1"))
        assertEquals(DropBoundary.AppendFeeds("d1"), index.belowBoundaryForFeed("f2"))
    }

    @Test
    fun belowBoundaryForFolderPointsAtTheNextFolderOrFallsBackToAppendFolders() {
        val index = buildFeedListDropIndex(emptyList(), listOf(folder("d1"), folder("d2")))

        assertEquals(DropBoundary.BeforeFolder("d2"), index.belowBoundaryForFolder("d1"))
        assertEquals(DropBoundary.AppendFolders, index.belowBoundaryForFolder("d2"))
    }

    // --- parseFeedListRowKey ---

    @Test
    fun parseFeedListRowKeyRecognizesFolderFeedTagAndNoFolderHeaderKeys() {
        assertEquals(FeedListRowKey.Folder("d1"), parseFeedListRowKey("folder-d1"))
        assertEquals(FeedListRowKey.Feed("f1"), parseFeedListRowKey("feed-f1"))
        assertEquals(FeedListRowKey.Tag("t1"), parseFeedListRowKey("tag-t1"))
        assertEquals(FeedListRowKey.NoFolderHeader, parseFeedListRowKey("no-folder-header"))
    }

    @Test
    fun parseFeedListRowKeyDoesNotMistakeATagFeedRowForItsTagsOwnRow() {
        assertEquals(FeedListRowKey.Other, parseFeedListRowKey("tag-t1-feed-f1"))
    }

    @Test
    fun parseFeedListRowKeyTreatsUngroupedAndNonStringKeysAsOther() {
        assertEquals(FeedListRowKey.Other, parseFeedListRowKey("sidebar"))
        assertEquals(FeedListRowKey.Other, parseFeedListRowKey("folders-header"))
        assertEquals(FeedListRowKey.Other, parseFeedListRowKey("tags-divider"))
        assertEquals(FeedListRowKey.Other, parseFeedListRowKey("tags-header"))
        assertEquals(FeedListRowKey.Other, parseFeedListRowKey(null))
        assertEquals(FeedListRowKey.Other, parseFeedListRowKey(42))
    }

    @Test
    fun parseFeedListRowKeyHandlesIdsContainingDashes() {
        // Real ids are UUIDs, which contain dashes themselves — the prefix must only be stripped
        // once, not confused by dashes inside the id.
        assertEquals(FeedListRowKey.Folder("aaaa-bbbb-cccc"), parseFeedListRowKey("folder-aaaa-bbbb-cccc"))
        assertEquals(FeedListRowKey.Feed("aaaa-bbbb-cccc"), parseFeedListRowKey("feed-aaaa-bbbb-cccc"))
    }

    // --- resolveHitBand / resolveRowHalf ---

    @Test
    fun resolveHitBandFindsTheBandContainingLocalY() {
        val bands = listOf(
            FeedListRowBand("a", offsetPx = 0, sizePx = 40),
            FeedListRowBand("b", offsetPx = 40, sizePx = 40),
            FeedListRowBand("c", offsetPx = 80, sizePx = 40),
        )

        assertEquals("a", resolveHitBand(0f, bands)?.key)
        assertEquals("a", resolveHitBand(39f, bands)?.key)
        assertEquals("b", resolveHitBand(40f, bands)?.key)
        assertEquals("c", resolveHitBand(119f, bands)?.key)
    }

    @Test
    fun resolveHitBandReturnsNullOutsideEveryBandOrWhenBandsIsEmpty() {
        val bands = listOf(FeedListRowBand("a", offsetPx = 0, sizePx = 40))

        assertNull(resolveHitBand(-1f, bands))
        assertNull(resolveHitBand(40f, bands))
        assertNull(resolveHitBand(0f, emptyList()))
    }

    @Test
    fun resolveRowHalfSplitsABandAtItsMidpoint() {
        val band = FeedListRowBand("a", offsetPx = 100, sizePx = 40)

        assertEquals(RowHalf.TOP, resolveRowHalf(100f, band))
        assertEquals(RowHalf.TOP, resolveRowHalf(119f, band))
        assertEquals(RowHalf.BOTTOM, resolveRowHalf(120f, band))
        assertEquals(RowHalf.BOTTOM, resolveRowHalf(139f, band))
    }
}

/** [autoScrollVelocityPxPerSec] over a 0..1000 viewport with 100px edge zones and 900px/s max. */
private fun autoScrollVelocity(pointerY: Float): Float = autoScrollVelocityPxPerSec(
    pointerY = pointerY,
    viewportTop = 0f,
    viewportBottom = 1000f,
    edgeZonePx = 100f,
    maxSpeedPxPerSec = 900f,
)

private fun tag(id: String): Tags = Tags(
    id = id,
    name = "Tag $id",
    color = null,
    sort_order = 0L,
    deleted_at = null,
    updated_at = 0L,
    created_at = 0L,
)

private fun folder(id: String): Folders = Folders(
    id = id,
    name = "Folder $id",
    sort_order = 0L,
    deleted_at = null,
    updated_at = 0L,
    created_at = 0L,
)

private fun feed(id: String, folderId: String? = null, sortOrder: Long = 0L): Feeds = Feeds(
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
    folder_id = folderId,
    deleted_at = null,
    updated_at = 0L,
    created_at = 0L,
    sort_order = sortOrder,
    folder_updated_at = null,
    sort_order_updated_at = null,
    custom_title_updated_at = null,
    deleted_updated_at = null,
)
