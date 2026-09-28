package works.merc.keryx.app.ui.home

import works.merc.keryx.app.core.ArticleFilter
import works.merc.keryx.app.data.local.db.Feeds
import works.merc.keryx.app.data.local.db.Folders
import works.merc.keryx.app.data.local.db.Tags
import works.merc.keryx.app.domain.ArticleListRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import works.merc.keryx.app.presentation.home.FeedListRowSelection
import works.merc.keryx.app.presentation.home.FeedListSelectionTarget
import works.merc.keryx.app.presentation.home.ReorderTarget
import works.merc.keryx.app.presentation.home.articleListTitle
import works.merc.keryx.app.presentation.home.buildOrderedFeedListRows
import works.merc.keryx.app.presentation.home.feedsForTag
import works.merc.keryx.app.presentation.home.groupFeedsByFolder
import works.merc.keryx.app.presentation.home.hasHideableRead
import works.merc.keryx.app.presentation.home.hasUsableUrl
import works.merc.keryx.app.presentation.home.nextFeedListRow
import works.merc.keryx.app.presentation.home.renameHomeKey
import works.merc.keryx.app.presentation.home.reorderTargetWithinScope
import works.merc.keryx.app.presentation.home.resolveFeedListSelectionTarget
import works.merc.keryx.app.presentation.home.DropBoundary
import works.merc.keryx.app.presentation.home.buildFeedListDropIndex

class HomeCommonTest {

    @Test
    fun renameKeyMatchesTheSharedRenameHomeKey() {
        for (isMacOs in listOf(true, false)) {
            assertEquals(renameHomeKey(isMacOs), renameKey(isMacOs).toHomeKey(), "isMacOs=$isMacOs")
        }
    }

    @Test
    fun pullRefreshAvailableOnlyOnTouchOutsideSearchWithFeeds() {
        for (touch in listOf(false, true)) for (search in listOf(false, true)) for (noFeeds in listOf(false, true)) {
            assertEquals(
                touch && !search && !noFeeds,
                pullRefreshAvailable(isTouchPrimary = touch, searchActive = search, hasNoFeeds = noFeeds),
                "touch=$touch search=$search noFeeds=$noFeeds",
            )
        }
    }

    // --- FeedListRowSelection.canonicalFor ---

    @Test
    fun feedListRowIndexReturnsNullForSidebarRows() {
        // All and Starred are rendered outside the LazyColumn entirely as fixed SidebarRows, so
        // they never correspond to a LazyColumn item and must not trigger a scroll.
        val folders = listOf(folder("d1"))
        val feeds = listOf(feed("f1", folderId = "d1"))
        val tags = listOf(tag("t1"))

        assertNull(feedListRowIndex(FeedListRowSelection.Starred, feeds, folders, tags, emptySet()))
        assertNull(feedListRowIndex(FeedListRowSelection.All, feeds, folders, tags, emptySet()))
    }

    @Test
    fun feedListRowIndexWithNoFoldersOrTagsStartsFeedsRightAfterHeaders() {
        // No folders => no NoFolderHeader row, so index 1 (after "Folders" header) is the first feed.
        val feeds = listOf(feed("f1"), feed("f2"))

        assertEquals(
            1,
            feedListRowIndex(FeedListRowSelection.FeedInFolderGroup("f1"), feeds, emptyList(), emptyList(), emptySet()),
        )
        assertEquals(
            2,
            feedListRowIndex(FeedListRowSelection.FeedInFolderGroup("f2"), feeds, emptyList(), emptyList(), emptySet()),
        )
    }

    @Test
    fun feedListRowIndexWalksFoldersUnassignedFeedsDividerAndTagsInOrder() {
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
        assertEquals(1, feedListRowIndex(FeedListRowSelection.Folder("d1"), feeds, folders, tags, emptySet()))
        assertEquals(2, feedListRowIndex(FeedListRowSelection.FeedInFolderGroup("f1"), feeds, folders, tags, emptySet()))
        assertEquals(3, feedListRowIndex(FeedListRowSelection.Folder("d2"), feeds, folders, tags, emptySet()))
        assertEquals(4, feedListRowIndex(FeedListRowSelection.FeedInFolderGroup("f2"), feeds, folders, tags, emptySet()))
        assertEquals(6, feedListRowIndex(FeedListRowSelection.FeedInFolderGroup("f3"), feeds, folders, tags, emptySet()))
        assertEquals(7, feedListRowIndex(FeedListRowSelection.FeedInFolderGroup("f4"), feeds, folders, tags, emptySet()))
        assertEquals(10, feedListRowIndex(FeedListRowSelection.Tag("t1"), feeds, folders, tags, emptySet()))
        assertEquals(11, feedListRowIndex(FeedListRowSelection.Tag("t2"), feeds, folders, tags, emptySet()))
    }

    @Test
    fun feedListRowIndexReturnsNullForFeedUnderCollapsedFolder() {
        val folders = listOf(folder("d1"))
        val feeds = listOf(feed("f1", folderId = "d1"))

        assertNull(
            feedListRowIndex(FeedListRowSelection.FeedInFolderGroup("f1"), feeds, folders, emptyList(), setOf("d1")),
        )
    }

    @Test
    fun feedListRowIndexStillReturnsFolderHeaderRowWhenCollapsed() {
        val folders = listOf(folder("d1"))
        val feeds = listOf(feed("f1", folderId = "d1"))

        assertEquals(
            1,
            feedListRowIndex(FeedListRowSelection.Folder("d1"), feeds, folders, emptyList(), setOf("d1")),
        )
    }

    @Test
    fun feedListRowIndexResolvesTheTagNestedRowOfAFeedWhoseFolderRowIsHidden() {
        // The folder-group instance is hidden by the collapsed folder, but the tag-nested instance
        // is a different row and still rendered — each resolves independently.
        val folders = listOf(folder("d1"))
        val tags = listOf(tag("t1"))
        val feeds = listOf(feed("f1", folderId = "d1"))
        val feedTagMap = mapOf("f1" to setOf("t1"))

        // 0: Folders header, 1: FolderGroupHeader d1 (collapsed, feed row hidden),
        // 2: NoFolderHeader (folders.isNotEmpty(), even though the unassigned group is empty here),
        // 3: divider, 4: Tags header, 5: tag t1, 6: f1 (under t1)
        assertNull(
            feedListRowIndex(
                FeedListRowSelection.FeedInFolderGroup("f1"),
                feeds,
                folders,
                tags,
                setOf("d1"),
                feedTagMap,
                setOf("t1"),
            ),
        )
        assertEquals(
            6,
            feedListRowIndex(
                FeedListRowSelection.FeedInTag("f1", "t1"),
                feeds,
                folders,
                tags,
                setOf("d1"),
                feedTagMap,
                setOf("t1"),
            ),
        )
    }

    // --- feedsForTag ---

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

    // --- feedListRowIndex, expanded tags ---

    @Test
    fun feedListRowIndexIgnoresTagFeedRowsWhenNoTagIsExpanded() {
        val tags = listOf(tag("t1"), tag("t2"))
        val feeds = listOf(feed("f1"), feed("f2"))
        val feedTagMap = mapOf("f1" to setOf("t1"), "f2" to setOf("t1"))

        // 0: "Folders" header, 1: f1, 2: f2, 3: divider, 4: "Tags" header
        assertEquals(
            5,
            feedListRowIndex(FeedListRowSelection.Tag("t1"), feeds, emptyList(), tags, emptySet(), feedTagMap, emptySet()),
        )
        assertEquals(
            6,
            feedListRowIndex(FeedListRowSelection.Tag("t2"), feeds, emptyList(), tags, emptySet(), feedTagMap, emptySet()),
        )
        // A tag-nested instance under a collapsed tag isn't rendered at all.
        assertNull(
            feedListRowIndex(
                FeedListRowSelection.FeedInTag("f1", "t1"),
                feeds,
                emptyList(),
                tags,
                emptySet(),
                feedTagMap,
                emptySet(),
            ),
        )
    }

    @Test
    fun feedListRowIndexShiftsLaterTagsByAnExpandedTagsFeedRows() {
        val tags = listOf(tag("t1"), tag("t2"), tag("t3"))
        val feeds = listOf(feed("f1"), feed("f2"), feed("f3"))
        val feedTagMap = mapOf("f1" to setOf("t1"), "f2" to setOf("t1"), "f3" to setOf("t2"))

        // 0: "Folders" header, 1-3: f1..f3, 4: divider, 5: "Tags" header,
        // 6: tag t1, 7: f1 (under t1), 8: f2 (under t1), 9: tag t2, 10: f3 (under t2), 11: tag t3
        val expanded = setOf("t1", "t2")
        assertEquals(
            6,
            feedListRowIndex(FeedListRowSelection.Tag("t1"), feeds, emptyList(), tags, emptySet(), feedTagMap, expanded),
        )
        assertEquals(
            7,
            feedListRowIndex(FeedListRowSelection.FeedInTag("f1", "t1"), feeds, emptyList(), tags, emptySet(), feedTagMap, expanded),
        )
        assertEquals(
            8,
            feedListRowIndex(FeedListRowSelection.FeedInTag("f2", "t1"), feeds, emptyList(), tags, emptySet(), feedTagMap, expanded),
        )
        assertEquals(
            9,
            feedListRowIndex(FeedListRowSelection.Tag("t2"), feeds, emptyList(), tags, emptySet(), feedTagMap, expanded),
        )
        assertEquals(
            10,
            feedListRowIndex(FeedListRowSelection.FeedInTag("f3", "t2"), feeds, emptyList(), tags, emptySet(), feedTagMap, expanded),
        )
        assertEquals(
            11,
            feedListRowIndex(FeedListRowSelection.Tag("t3"), feeds, emptyList(), tags, emptySet(), feedTagMap, expanded),
        )
    }

    @Test
    fun feedListRowIndexResolvesTheSameFeedsFolderAndTagRowsToDifferentIndices() {
        // The same feed id renders twice here — the instance is what decides which row is meant.
        val tags = listOf(tag("t1"), tag("t2"))
        val feeds = listOf(feed("f1"))
        val feedTagMap = mapOf("f1" to setOf("t1", "t2"))

        // 0: "Folders" header, 1: f1 (no folder), 2: divider, 3: "Tags" header,
        // 4: tag t1, 5: f1 (under t1), 6: tag t2, 7: f1 (under t2)
        val expanded = setOf("t1", "t2")
        assertEquals(
            1,
            feedListRowIndex(FeedListRowSelection.FeedInFolderGroup("f1"), feeds, emptyList(), tags, emptySet(), feedTagMap, expanded),
        )
        assertEquals(
            5,
            feedListRowIndex(FeedListRowSelection.FeedInTag("f1", "t1"), feeds, emptyList(), tags, emptySet(), feedTagMap, expanded),
        )
        assertEquals(
            7,
            feedListRowIndex(FeedListRowSelection.FeedInTag("f1", "t2"), feeds, emptyList(), tags, emptySet(), feedTagMap, expanded),
        )
    }

    @Test
    fun feedListRowIndexResolvesOnlyTheFolderGroupRowWhileTheFeedsTagIsCollapsed() {
        // Expanded folder + collapsed tag: only the folder-group instance resolves.
        val folders = listOf(folder("d1"))
        val tags = listOf(tag("t1"))
        val feeds = listOf(feed("f1", folderId = "d1"))
        val feedTagMap = mapOf("f1" to setOf("t1"))

        // 0: "Folders" header, 1: folder d1, 2: f1 (under d1), 3: NoFolderHeader,
        // 4: divider, 5: "Tags" header, 6: tag t1
        assertEquals(
            2,
            feedListRowIndex(FeedListRowSelection.FeedInFolderGroup("f1"), feeds, folders, tags, emptySet(), feedTagMap, emptySet()),
        )
        assertNull(
            feedListRowIndex(FeedListRowSelection.FeedInTag("f1", "t1"), feeds, folders, tags, emptySet(), feedTagMap, emptySet()),
        )
    }

    @Test
    fun feedListRowIndexDefaultsToNoExpandedTagsWhenTagArgumentsAreOmitted() {
        val tags = listOf(tag("t1"), tag("t2"))
        val feeds = listOf(feed("f1"))

        assertEquals(4, feedListRowIndex(FeedListRowSelection.Tag("t1"), feeds, emptyList(), tags, emptySet()))
        assertEquals(5, feedListRowIndex(FeedListRowSelection.Tag("t2"), feeds, emptyList(), tags, emptySet()))
    }

    @Test
    fun feedListRowIndexReturnsNullForNonexistentIds() {
        val folders = listOf(folder("d1"))
        val tags = listOf(tag("t1"))
        val feeds = listOf(feed("f1", folderId = "d1"))

        assertNull(feedListRowIndex(FeedListRowSelection.FeedInFolderGroup("gone"), feeds, folders, tags, emptySet()))
        assertNull(feedListRowIndex(FeedListRowSelection.Tag("gone"), feeds, folders, tags, emptySet()))
        assertNull(feedListRowIndex(FeedListRowSelection.Folder("gone"), feeds, folders, tags, emptySet()))
        assertNull(
            feedListRowIndex(
                FeedListRowSelection.FeedInTag("f1", "gone"),
                feeds,
                folders,
                tags,
                emptySet(),
                mapOf("f1" to setOf("t1")),
                setOf("t1"),
            ),
        )
    }

    // --- resolveFeedListSelectionTarget ---

    @Test
    fun resolveFeedListSelectionTargetReturnsNullForSidebarRows() {
        val feeds = listOf(feed("f1"))
        val folders = listOf(folder("d1"))
        val tags = listOf(tag("t1"))

        assertNull(resolveFeedListSelectionTarget(ArticleFilter.All, feeds, folders, tags))
        assertNull(resolveFeedListSelectionTarget(ArticleFilter.Starred, feeds, folders, tags))
    }

    // --- toInlineEditTarget ---

    @Test
    fun toInlineEditTargetKeepsEachSelectionKindAndRoundTripsBackToItsFilter() {
        // The inline editor keys off an id + kind rather than the row value, and the pane scrolls
        // the row into view via the filter this hands back, so both directions must line up. Folder
        // and Tag selections carry no tag context, so any rowInstance is passed through unused here.
        val cases = listOf(
            Triple(FeedListSelectionTarget.Feed(feed("f1")), FeedListRowSelection.FeedInFolderGroup("f1"), InlineEditTarget.Feed("f1") to ArticleFilter.Feed("f1")),
            Triple(FeedListSelectionTarget.Folder(folder("d1")), FeedListRowSelection.All, InlineEditTarget.Folder("d1") to ArticleFilter.Folder("d1")),
            Triple(FeedListSelectionTarget.Tag(tag("t1")), FeedListRowSelection.All, InlineEditTarget.Tag("t1") to ArticleFilter.Tag("t1")),
        )
        for ((selection, rowInstance, expected) in cases) {
            val (expectedTarget, expectedFilter) = expected
            val target = selection.toInlineEditTarget(rowInstance)
            assertEquals(expectedTarget, target)
            assertEquals(expectedFilter, target.filter)
        }
    }

    @Test
    fun toInlineEditTargetForFeedUsesTheFolderGroupRowInstanceWhenSelectedThatWay() {
        val target = FeedListSelectionTarget.Feed(feed("f1"))
            .toInlineEditTarget(FeedListRowSelection.FeedInFolderGroup("f1"))
        assertEquals(InlineEditTarget.Feed("f1", tagId = null), target)
        assertEquals(FeedListRowSelection.FeedInFolderGroup("f1"), target.rowInstance)
    }

    @Test
    fun toInlineEditTargetForFeedCarriesTheTagIdWhenSelectedViaItsTagNestedRow() {
        val target = FeedListSelectionTarget.Feed(feed("f1"))
            .toInlineEditTarget(FeedListRowSelection.FeedInTag("f1", "t1"))
        assertEquals(InlineEditTarget.Feed("f1", tagId = "t1"), target)
        assertEquals(FeedListRowSelection.FeedInTag("f1", "t1"), target.rowInstance)
    }

    @Test
    fun toInlineEditTargetForFeedIgnoresATagInstanceBelongingToAnotherFeed() {
        // rowInstance and the resolved selection are always set together by
        // HomeViewModel.selectFilter, so this should never actually happen — a defensive guard
        // against misattributing another feed's tag context rather than a reachable production case.
        val target = FeedListSelectionTarget.Feed(feed("f1"))
            .toInlineEditTarget(FeedListRowSelection.FeedInTag("other-feed", "t1"))
        assertEquals(InlineEditTarget.Feed("f1", tagId = null), target)
    }

    // --- autoScrollVelocityPxPerSec ---

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

    // --- parseFeedListDragSourceKey ---

    @Test
    fun parseFeedListDragSourceKeyRecognizesFeedAndFolderRows() {
        assertEquals(FeedListDragSourceKey.Feed("f1"), parseFeedListDragSourceKey("feed-f1"))
        assertEquals(FeedListDragSourceKey.Folder("d1"), parseFeedListDragSourceKey("folder-d1"))
    }

    @Test
    fun parseFeedListDragSourceKeyDragsTheFeedItselfFromATagAttachedFeedRow() {
        // Unlike parseFeedListRowKey (which resolves this row to Other, since dropping *onto* it
        // means nothing), dragging it drags the feed.
        assertEquals(FeedListDragSourceKey.Feed("f1"), parseFeedListDragSourceKey("tag-t1-feed-f1"))
    }

    @Test
    fun parseFeedListDragSourceKeyRejectsNonDraggableRows() {
        assertNull(parseFeedListDragSourceKey("tag-t1"))
        assertNull(parseFeedListDragSourceKey("no-folder-header"))
        assertNull(parseFeedListDragSourceKey("folders-header"))
        assertNull(parseFeedListDragSourceKey("tags-header"))
        assertNull(parseFeedListDragSourceKey("tags-divider"))
        assertNull(parseFeedListDragSourceKey(null))
        assertNull(parseFeedListDragSourceKey(42))
    }

    @Test
    fun parseFeedListDragSourceKeyHandlesIdsContainingDashes() {
        // Real ids are UUIDs, which contain dashes themselves.
        assertEquals(
            FeedListDragSourceKey.Folder("aaaa-bbbb-cccc"),
            parseFeedListDragSourceKey("folder-aaaa-bbbb-cccc"),
        )
        assertEquals(
            FeedListDragSourceKey.Feed("aaaa-bbbb-cccc"),
            parseFeedListDragSourceKey("feed-aaaa-bbbb-cccc"),
        )
        assertEquals(
            FeedListDragSourceKey.Feed("dddd-eeee-ffff"),
            parseFeedListDragSourceKey("tag-aaaa-bbbb-cccc-feed-dddd-eeee-ffff"),
        )
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

    // --- hasHideableRead ---

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

}
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

/** [autoScrollVelocityPxPerSec] over a 0..1000 viewport with 100px edge zones and 900px/s max. */
private fun autoScrollVelocity(pointerY: Float): Float = autoScrollVelocityPxPerSec(
    pointerY = pointerY,
    viewportTop = 0f,
    viewportBottom = 1000f,
    edgeZonePx = 100f,
    maxSpeedPxPerSec = 900f,
)
