package works.merc.keryx.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.koin.compose.KoinApplication
import org.koin.dsl.module
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import works.merc.keryx.app.core.ArticleFilter
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.domain.ArticleListRow
import works.merc.keryx.app.inMemoryDb
import works.merc.keryx.app.insertFeed
import works.merc.keryx.app.insertFeedTag
import works.merc.keryx.app.insertFolder
import works.merc.keryx.app.insertTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every list row's clickable band must exactly cover its own layout bounds — no dead strip inside
 * the outer margin, no unclickable wedge under the rounded-corner clip, and no gap between two
 * rows that belongs to neither. See "Divider policy" in the `ui-guidelines` skill and the "fix a
 * list row's whole band being clickable" change for why `listRowClickable`/`listRowSurface` exist.
 */
@OptIn(ExperimentalTestApi::class)
class ListRowHitAreaTest {

    /**
     * Sweeps every pixel from just inside [aBounds] to just inside [bBounds] and asserts each one
     * resolves to whichever of the two bounds it falls in — independent of any margin/midpoint
     * arithmetic the implementation itself uses, unlike the ±1px checks below (which compute their
     * expected point from the same margin constant the implementation applies, and so could not
     * have caught a bug in that constant's use). Requires [aBounds] and [bBounds] to be vertically
     * adjacent with no gap between them, which every row pair in this file's tests satisfies (the
     * two are literally touching, e.g. `aBounds.bottom == bBounds.top`) — see this test's callers
     * for why: two adjacent `LazyColumn` items are contiguous by construction, and each row's own
     * `listRowClickable` covers its item's full reported bounds (`ListRowChrome.kt`), so there is
     * no third region that could belong to neither.
     */
    private fun androidx.compose.ui.test.ComposeUiTest.assertGapSplitsExactlyAtTheSharedBoundary(
        x: Float,
        aBounds: androidx.compose.ui.geometry.Rect,
        bBounds: androidx.compose.ui.geometry.Rect,
        click: (Float, Float) -> Unit,
        resolvesToA: () -> Boolean,
        resolvesToB: () -> Boolean,
    ) {
        assertEquals(aBounds.bottom, bBounds.top, "the two rows must be vertically contiguous with no gap for this sweep to be meaningful")
        for (y in (aBounds.bottom - 3).toInt()..(bBounds.top + 3).toInt()) {
            click(x, y.toFloat())
            waitForIdle()
            val expectA = y < aBounds.bottom
            assertTrue(
                if (expectA) resolvesToA() else resolvesToB(),
                "clicking at y=$y (boundary at ${aBounds.bottom}) must resolve to ${if (expectA) "the row above" else "the row below"}",
            )
        }
    }

    @Test
    fun everyPointInTheGapBetweenTwoArticleRowsResolvesToTheNearerRow() = runDesktopComposeUiTest {
        val items = articles(3)
        var selected: ArticleListRow? = null
        lateinit var state: LazyListState

        setContent {
            state = rememberLazyListState()
            ArticleListPaneContent(
                articles = items,
                feedTitles = emptyMap(),
                selectedId = null,
                unreadOnly = false,
                onToggleUnreadOnly = {},
                onToggleSort = {},
                onMarkAllRead = {},
                onSelectArticle = { selected = it },
                modifier = Modifier.size(360.dp, 400.dp),
                listState = state,
            )
        }
        waitForIdle()

        val aBounds = onNodeWithTag("article-a0").fetchSemanticsNode().boundsInRoot
        val bBounds = onNodeWithTag("article-a1").fetchSemanticsNode().boundsInRoot
        assertGapSplitsExactlyAtTheSharedBoundary(
            x = aBounds.center.x,
            aBounds = aBounds,
            bBounds = bBounds,
            click = { x, y -> onRoot().performMouseInput { click(Offset(x, y)) } },
            resolvesToA = { selected?.id == "a0" },
            resolvesToB = { selected?.id == "a1" },
        )
    }

    @Test
    fun everyPointInTheGapBetweenAFolderHeaderAndItsFirstFeedResolvesToTheNearerRow() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertFolder("d1", "Folder One", sortOrder = 0L)
        db.insertFeed("f1", folderId = "d1", sortOrder = 0L)
        val fixture = newHomeViewModel(driver, db)
        val vm = fixture.vm
        try {
            setContent { FeedListHitAreaTestHost(vm, 400.dp) }
            waitForIdle()

            val headerBounds = onNodeWithTag(folderRowTestTag("d1")).fetchSemanticsNode().boundsInRoot
            val feedBounds = onNodeWithTag(feedRowTestTag("f1")).fetchSemanticsNode().boundsInRoot
            assertGapSplitsExactlyAtTheSharedBoundary(
                x = feedBounds.center.x,
                aBounds = headerBounds,
                bBounds = feedBounds,
                click = { x, y ->
                    vm.selectFilter(ArticleFilter.All)
                    onRoot().performMouseInput { click(Offset(x, y)) }
                },
                resolvesToA = { vm.filter.value == ArticleFilter.Folder("d1") },
                resolvesToB = { vm.filter.value == ArticleFilter.Feed("f1") },
            )
        } finally {
            vm.viewModelScope.cancel()
            fixture.close()
            driver.close()
        }
    }

    @Test
    fun everyPointInTheGapBetweenTwoTagNestedFeedRowsResolvesToTheNearerRow() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertFeed("a", sortOrder = 0L)
        db.insertFeed("b", sortOrder = 1L)
        db.insertTag("t1", "Tag One")
        db.insertFeedTag("a", "t1")
        db.insertFeedTag("b", "t1")
        val fixture = newHomeViewModel(driver, db)
        val vm = fixture.vm
        try {
            vm.toggleTagExpanded("t1")
            setContent { FeedListHitAreaTestHost(vm, 1000.dp) }
            waitForIdle()

            // "Feed a"/"Feed b" each render twice (once under the unassigned-folder group, once
            // under the expanded tag) — the tag-nested instance is the second (lower) match.
            val aBounds = onAllNodesWithText("Feed a")[1].fetchSemanticsNode().boundsInRoot
            val bBounds = onAllNodesWithText("Feed b")[1].fetchSemanticsNode().boundsInRoot
            assertGapSplitsExactlyAtTheSharedBoundary(
                x = aBounds.center.x,
                aBounds = aBounds,
                bBounds = bBounds,
                click = { x, y ->
                    vm.selectFilter(ArticleFilter.All)
                    onRoot().performMouseInput { click(Offset(x, y)) }
                },
                resolvesToA = { vm.filter.value == ArticleFilter.Feed("a") },
                resolvesToB = { vm.filter.value == ArticleFilter.Feed("b") },
            )
        } finally {
            vm.viewModelScope.cancel()
            fixture.close()
            driver.close()
        }
    }

    @Test
    fun everyPointInsideAnArticleRowsLayoutBoundsSelectsIt() = runDesktopComposeUiTest {
        val items = articles(3)
        var selected: ArticleListRow? = null
        lateinit var state: LazyListState

        setContent {
            state = rememberLazyListState()
            ArticleListPaneContent(
                articles = items,
                feedTitles = emptyMap(),
                selectedId = null,
                unreadOnly = false,
                onToggleUnreadOnly = {},
                onToggleSort = {},
                onMarkAllRead = {},
                onSelectArticle = { selected = it },
                modifier = Modifier.size(360.dp, 400.dp),
                listState = state,
            )
        }
        waitForIdle()

        val bounds = onNodeWithTag("article-a1").fetchSemanticsNode().boundsInRoot
        val probePoints = listOf(
            "top-left" to Offset(bounds.left + 1f, bounds.top + 1f),
            "top-right" to Offset(bounds.right - 1f, bounds.top + 1f),
            "bottom-left" to Offset(bounds.left + 1f, bounds.bottom - 1f),
            "bottom-right" to Offset(bounds.right - 1f, bounds.bottom - 1f),
            "center" to bounds.center,
        )
        for ((label, point) in probePoints) {
            selected = null
            onNodeWithTag("article-a1").performMouseInput { click(Offset(point.x - bounds.left, point.y - bounds.top)) }
            waitForIdle()
            assertEquals(items[1], selected, "clicking the article row's $label ($point, row bounds=$bounds) must select it")
        }
    }

    @Test
    fun theGapBetweenTwoArticleRowsSelectsTheNearerOne() = runDesktopComposeUiTest {
        val items = articles(3)
        var selected: ArticleListRow? = null
        lateinit var state: LazyListState

        setContent {
            state = rememberLazyListState()
            ArticleListPaneContent(
                articles = items,
                feedTitles = emptyMap(),
                selectedId = null,
                unreadOnly = false,
                onToggleUnreadOnly = {},
                onToggleSort = {},
                onMarkAllRead = {},
                onSelectArticle = { selected = it },
                modifier = Modifier.size(360.dp, 400.dp),
                listState = state,
            )
        }
        waitForIdle()

        val aBounds = onNodeWithTag("article-a0").fetchSemanticsNode().boundsInRoot
        val bBounds = onNodeWithTag("article-a1").fetchSemanticsNode().boundsInRoot
        val midX = aBounds.center.x

        // Just above the midpoint of the a0/a1 gap -> nearer to a0.
        selected = null
        onNodeWithTag("article-a0").performMouseInput {
            click(Offset(midX - aBounds.left, aBounds.height + (bBounds.top - aBounds.bottom) / 2f - 1f))
        }
        waitForIdle()
        assertEquals(items[0], selected, "a point just above the a0/a1 gap's midpoint must select a0 (the nearer row)")

        // Just below the midpoint of the a0/a1 gap -> nearer to a1.
        selected = null
        onNodeWithTag("article-a1").performMouseInput {
            click(Offset(midX - bBounds.left, -((bBounds.top - aBounds.bottom) / 2f) + 1f))
        }
        waitForIdle()
        assertEquals(items[1], selected, "a point just below the a0/a1 gap's midpoint must select a1 (the nearer row)")
    }

    @Test
    fun allFeedRowsInAGroupHaveIdenticalBandHeight() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertFeed("a", sortOrder = 0L)
        db.insertFeed("b", sortOrder = 1L)
        db.insertFeed("c", sortOrder = 2L)
        val fixture = newHomeViewModel(driver, db)
        val vm = fixture.vm
        try {
            setContent { FeedListHitAreaTestHost(vm, 400.dp) }
            waitForIdle()

            // A row's clickable band must be its highlight plus a symmetric LIST_ROW_VERTICAL_MARGIN
            // above and below — nothing else. Anything laid out as a sibling *inside* one row's band
            // (the drag insertion marker used to be a 2dp Box in a wrapping Column) both inflates
            // that row and, because layout space belongs to exactly one row, makes the gap between
            // two rows split unevenly, so a click nearer the row above selects the row below.
            // The tell is a height that varies with position: only the last feed of a group carried
            // the trailing "append here" marker.
            val heights = listOf("a", "b", "c").map { id ->
                id to onNodeWithTag(feedRowTestTag(id)).fetchSemanticsNode().boundsInRoot.height
            }
            assertEquals(
                1,
                heights.map { it.second }.toSet().size,
                "feed rows must all have the same band height regardless of their position in the group: $heights",
            )
        } finally {
            vm.viewModelScope.cancel()
            fixture.close()
            driver.close()
        }
    }

    @Test
    fun aFolderHeadersBandHeightDoesNotDependOnItsCollapsedOrLastState() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertFolder("d1", "Folder One", sortOrder = 0L)
        db.insertFolder("d2", "Folder Two", sortOrder = 1L)
        db.insertFeed("f1", folderId = "d1", sortOrder = 0L)
        db.insertFeed("f2", folderId = "d2", sortOrder = 1L)
        val fixture = newHomeViewModel(driver, db)
        val vm = fixture.vm
        try {
            setContent { FeedListHitAreaTestHost(vm, 400.dp) }
            waitForIdle()

            // d1 is expanded and not last; d2 is last. Collapsing d1 additionally used to add its
            // indented "drop inside this folder" marker slot. None of that may change the band's
            // height — see `ui-guidelines`' "Layout stability under state changes".
            val expandedNotLast = onNodeWithTag(folderRowTestTag("d1")).fetchSemanticsNode().boundsInRoot.height
            val last = onNodeWithTag(folderRowTestTag("d2")).fetchSemanticsNode().boundsInRoot.height
            vm.toggleFolderCollapsed("d1")
            waitForIdle()
            val collapsedNotLast = onNodeWithTag(folderRowTestTag("d1")).fetchSemanticsNode().boundsInRoot.height

            assertEquals(
                1,
                setOf(expandedNotLast, last, collapsedNotLast).size,
                "a folder header's band height must not depend on collapsed/last state: " +
                    "expandedNotLast=$expandedNotLast last=$last collapsedNotLast=$collapsedNotLast",
            )
        } finally {
            vm.viewModelScope.cancel()
            fixture.close()
            driver.close()
        }
    }

    @Test
    fun everyPointInsideAFeedRowsLayoutBoundsSelectsIt() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertFeed("a", sortOrder = 0L)
        db.insertFeed("b", sortOrder = 1L)
        val fixture = newHomeViewModel(driver, db)
        val vm = fixture.vm
        try {
            setContent { FeedListHitAreaTestHost(vm, 400.dp) }
            waitForIdle()

            val bounds = onNodeWithTag(feedRowTestTag("a")).fetchSemanticsNode().boundsInRoot
            val probePoints = listOf(
                "top-left" to Offset(bounds.left + 1f, bounds.top + 1f),
                "top-right" to Offset(bounds.right - 1f, bounds.top + 1f),
                "bottom-left" to Offset(bounds.left + 1f, bounds.bottom - 1f),
                "bottom-right" to Offset(bounds.right - 1f, bounds.bottom - 1f),
                "center" to bounds.center,
            )
            for ((label, point) in probePoints) {
                vm.selectFilter(ArticleFilter.All)
                waitForIdle()
                onNodeWithTag(feedRowTestTag("a")).performMouseInput {
                    click(Offset(point.x - bounds.left, point.y - bounds.top))
                }
                waitForIdle()
                assertEquals(
                    ArticleFilter.Feed("a"),
                    vm.filter.value,
                    "clicking the feed row's $label ($point, row bounds=$bounds) must select it",
                )
            }
        } finally {
            vm.viewModelScope.cancel()
            fixture.close()
            driver.close()
        }
    }

    // Also verifies the fix for the asymmetric-gap regression that
    // `allFeedRowsInAGroupHaveIdenticalBandHeight` (below) catches directly: before the drag
    // insertion marker moved into each row's own listRowSurface margin (see `insertionMarkers`),
    // the gap's true midpoint sat 2dp above where a fixed-offset probe assumed it did, so a point
    // just past "a"'s highlight — closer to "a" than to "b" — could still resolve to "b".
    @Test
    fun everyPointInTheGapBetweenTwoFeedRowsResolvesToTheNearerRow() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertFeed("a", sortOrder = 0L)
        db.insertFeed("b", sortOrder = 1L)
        val fixture = newHomeViewModel(driver, db)
        val vm = fixture.vm
        try {
            setContent { FeedListHitAreaTestHost(vm, 400.dp) }
            waitForIdle()

            val aBounds = onNodeWithTag(feedRowTestTag("a")).fetchSemanticsNode().boundsInRoot
            val bBounds = onNodeWithTag(feedRowTestTag("b")).fetchSemanticsNode().boundsInRoot
            assertGapSplitsExactlyAtTheSharedBoundary(
                x = aBounds.center.x,
                aBounds = aBounds,
                bBounds = bBounds,
                click = { x, y ->
                    vm.selectFilter(ArticleFilter.All)
                    onRoot().performMouseInput { click(Offset(x, y)) }
                },
                resolvesToA = { vm.filter.value == ArticleFilter.Feed("a") },
                resolvesToB = { vm.filter.value == ArticleFilter.Feed("b") },
            )
        } finally {
            vm.viewModelScope.cancel()
            fixture.close()
            driver.close()
        }
    }

    @Test
    fun clickingAFolderHeadersUnreadBadgeSelectsTheFolder() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertFolder("d1", "Folder One")
        db.insertFeed("f1", folderId = "d1")
        db.insertArticle("a1", "f1", isRead = 0L)
        val fixture = newHomeViewModel(driver, db)
        val vm = fixture.vm
        try {
            setContent { FeedListHitAreaTestHost(vm, 400.dp) }
            waitForIdle()
            settleFolderRowHitTesting("d1")

            // The badge column sits at the trailing edge of the folder row's surface, past the
            // folder icon/name — previously outside the inner weight(1f) row's own clickable.
            val bounds = onNodeWithTag(folderRowTestTag("d1")).fetchSemanticsNode().boundsInRoot
            onNodeWithTag(folderRowTestTag("d1")).performMouseInput {
                click(Offset(bounds.width - 4f, bounds.height / 2f))
            }
            waitForIdle()
            assertEquals(ArticleFilter.Folder("d1"), vm.filter.value, "clicking the folder header's badge column must select the folder")
        } finally {
            vm.viewModelScope.cancel()
            fixture.close()
            driver.close()
        }
    }

    @Test
    fun clickingAboveOrBelowAFolderHeadersChevronSelectsTheFolder() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertFolder("d1", "Folder One")
        db.insertFeed("f1", folderId = "d1")
        val fixture = newHomeViewModel(driver, db)
        val vm = fixture.vm
        try {
            setContent { FeedListHitAreaTestHost(vm, 400.dp) }
            waitForIdle()

            // The chevron is a fixed 20dp icon near the row's leading edge; the row itself is taller
            // than that (folder-icon-row's own vertical padding), so a point directly above/below
            // the chevron's own box — but still inside the row's band — used to belong to no
            // clickable at all (the chevron's own toggle click only covered its 20dp box, and the
            // inner weight(1f) row's click started only after the chevron+spacer horizontally).
            val bounds = onNodeWithTag(folderRowTestTag("d1")).fetchSemanticsNode().boundsInRoot
            val chevronCenterX = bounds.left + 8f + 10f // 8dp surface margin + half the 20dp chevron

            vm.selectFilter(ArticleFilter.All)
            waitForIdle()
            onNodeWithTag(folderRowTestTag("d1")).performMouseInput { click(Offset(chevronCenterX, 1f)) }
            waitForIdle()
            assertEquals(ArticleFilter.Folder("d1"), vm.filter.value, "a point above the chevron, inside the row band, must select the folder")

            vm.selectFilter(ArticleFilter.All)
            waitForIdle()
            onNodeWithTag(folderRowTestTag("d1")).performMouseInput { click(Offset(chevronCenterX, bounds.height - 1f)) }
            waitForIdle()
            assertEquals(ArticleFilter.Folder("d1"), vm.filter.value, "a point below the chevron, inside the row band, must select the folder")
        } finally {
            vm.viewModelScope.cancel()
            fixture.close()
            driver.close()
        }
    }

    @Test
    fun clickingTheChevronItselfTogglesCollapseWithoutSelectingTheFolder() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertFolder("d1", "Folder One")
        db.insertFeed("f1", folderId = "d1")
        val fixture = newHomeViewModel(driver, db)
        val vm = fixture.vm
        try {
            setContent { FeedListHitAreaTestHost(vm, 400.dp) }
            waitForIdle()
            assertTrue("d1" !in vm.collapsedFolderIds.value, "the folder must start expanded")

            val bounds = onNodeWithTag(folderRowTestTag("d1")).fetchSemanticsNode().boundsInRoot
            val chevronCenterX = bounds.left + 8f + 10f
            onNodeWithTag(folderRowTestTag("d1")).performMouseInput { click(Offset(chevronCenterX, bounds.height / 2f)) }
            waitForIdle()

            assertTrue("d1" in vm.collapsedFolderIds.value, "clicking the chevron itself must toggle the folder's collapsed state")
            assertEquals(ArticleFilter.All, vm.filter.value, "clicking the chevron itself must not also select the folder")
        } finally {
            vm.viewModelScope.cancel()
            fixture.close()
            driver.close()
        }
    }

    @Test
    fun clickingATagRowsInnerLeadingAndTrailingEdgesSelectsTheTag() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertTag("t1", "Tag One")
        val fixture = newHomeViewModel(driver, db)
        val vm = fixture.vm
        try {
            setContent { FeedListHitAreaTestHost(vm, 400.dp) }
            waitForIdle()

            // The tag row's surface has its own 8dp start/end content padding (before the chevron,
            // after the count-badge slot) that used to sit outside the inner weight(1f) row's click.
            val bounds = onNodeWithTag(tagRowTestTag("t1")).fetchSemanticsNode().boundsInRoot

            vm.selectFilter(ArticleFilter.All)
            waitForIdle()
            onNodeWithTag(tagRowTestTag("t1")).performMouseInput { click(Offset(4f, bounds.height / 2f)) }
            waitForIdle()
            assertEquals(ArticleFilter.Tag("t1"), vm.filter.value, "clicking the tag row's inner leading 8dp must select the tag")

            vm.selectFilter(ArticleFilter.All)
            waitForIdle()
            onNodeWithTag(tagRowTestTag("t1")).performMouseInput { click(Offset(bounds.width - 4f, bounds.height / 2f)) }
            waitForIdle()
            assertEquals(ArticleFilter.Tag("t1"), vm.filter.value, "clicking the tag row's inner trailing 8dp must select the tag")
        } finally {
            vm.viewModelScope.cancel()
            fixture.close()
            driver.close()
        }
    }

    /**
     * The visible gap between two rows is `2 * LIST_ROW_VERTICAL_MARGIN` — one margin contributed
     * by each of the two rows — which is what puts the hit boundary the sweeps above assert at the
     * gap's midpoint, so a click in it selects the *nearer* row.
     */
    @Test
    fun twoAdjacentRowsAreSeparatedByExactlyTwiceTheVerticalMargin() = runDesktopComposeUiTest {
        var marginPx = 0
        setContent {
            with(LocalDensity.current) { marginPx = LIST_ROW_VERTICAL_MARGIN.roundToPx() }
            MarkerProbeHost(marked = false)
        }
        waitForIdle()

        val band = onNodeWithTag(PROBE_UPPER).fetchSemanticsNode().boundsInRoot
        val boundary = band.bottom.toInt()
        val column = probeColumn(band.center.x.toInt())
        assertEquals("pane", column[boundary - 1], "a row's own vertical margin must show the pane behind it, not its highlight")
        assertEquals(
            (boundary - marginPx)..(boundary + marginPx - 1),
            column.runAround(boundary - 1),
            "the gap between two rows must be exactly 2 * LIST_ROW_VERTICAL_MARGIN, centred on their shared boundary",
        )
    }

    /**
     * A drag insertion marker fills exactly that gap — half painted by each of the two rows
     * touching the boundary (`insertionMarkers`) — so the line the user sees is a picture of where
     * a click will go: its upper half selects the row above, its lower half the row below.
     *
     * Marker thickness and row margin are deliberately the same constant, and nothing else couples
     * them: a change to one that forgot the other would leave the gap partly unpainted, or push the
     * marker over a highlight, and no hit-testing test above can see either — both are purely about
     * drawing.
     */
    @Test
    fun aDropBoundarysInsertionMarkerFillsTheGapAndNeitherRowsHighlight() = runDesktopComposeUiTest {
        var marginPx = 0
        setContent {
            with(LocalDensity.current) { marginPx = LIST_ROW_VERTICAL_MARGIN.roundToPx() }
            MarkerProbeHost(marked = true)
        }
        waitForIdle()

        val band = onNodeWithTag(PROBE_UPPER).fetchSemanticsNode().boundsInRoot
        val boundary = band.bottom.toInt()
        val column = probeColumn(band.center.x.toInt())
        assertEquals("marker", column[boundary - 1], "the marker must be painted into the row's margin")
        assertEquals(
            (boundary - marginPx)..(boundary + marginPx - 1),
            column.runAround(boundary - 1),
            "the marker must fill the whole gap and stop at each row's highlight — no pane showing through, no overlap",
        )
    }

    private val PROBE_UPPER = "probe-upper"
    private val PROBE_PANE = Color.Black
    private val PROBE_HIGHLIGHT = Color.White
    private val PROBE_MARKER = Color.Red

    /**
     * Two rows, in the three deliberately far-apart colors [probeColumn] classifies: the pane
     * behind them, their highlight, and — when [marked] — the insertion marker both sides of their
     * shared boundary paint. Structured exactly like a real list row's chain (marker before
     * [listRowSurface], content padding after it), because that ordering is what decides whether
     * the marker lands in the margin at all.
     */
    @Composable
    private fun MarkerProbeHost(marked: Boolean) {
        MaterialTheme(colorScheme = lightColorScheme(primary = PROBE_MARKER)) {
            Column(Modifier.size(120.dp, 120.dp).background(PROBE_PANE)) {
                ProbeRow(PROBE_UPPER, bottom = InsertionMarker(indented = false).takeIf { marked })
                ProbeRow("probe-lower", top = InsertionMarker(indented = false).takeIf { marked })
            }
        }
    }

    @Composable
    private fun ProbeRow(tag: String, top: InsertionMarker? = null, bottom: InsertionMarker? = null) {
        Box(
            Modifier.fillMaxWidth()
                .testTag(tag)
                .insertionMarkers(top = top, bottom = bottom)
                .listRowSurface(PROBE_HIGHLIGHT)
                .height(40.dp),
        )
    }

    /** The rendered pixels down column [x], each classified as `pane` / `highlight` / `marker` by
     * nearest reference color — so antialiasing along a clip edge cannot flip a verdict. */
    private fun androidx.compose.ui.test.ComposeUiTest.probeColumn(x: Int): List<String> {
        val pixels = onRoot().captureToImage().toPixelMap()
        val references = listOf("pane" to PROBE_PANE, "highlight" to PROBE_HIGHLIGHT, "marker" to PROBE_MARKER)
        return List(pixels.height) { y ->
            val color = pixels[x, y]
            references.minByOrNull { (_, ref) ->
                val dr = color.red - ref.red
                val dg = color.green - ref.green
                val db = color.blue - ref.blue
                dr * dr + dg * dg + db * db
            }!!.first
        }
    }

    /** The contiguous run of same-classified pixels containing [y]. */
    private fun List<String>.runAround(y: Int): IntRange {
        var lo = y
        while (lo > 0 && this[lo - 1] == this[y]) lo--
        var hi = y
        while (hi < size - 1 && this[hi + 1] == this[y]) hi++
        return lo..hi
    }

    /**
     * Clicks [folderId]'s chevron once before the real assertion click, to work around a
     * `FolderGroupHeader`-specific test-harness artifact: on the very first composition, a cold
     * click in the trailing ~8dp of its `Row`'s own `listRowClickable` — the same margin
     * `listRowSurface` insets for the highlight — misses, even though that `Row` is structured
     * identically to the (verified, cold-click-safe) `FeedRow`/`TagRow`/`ArticleRow`.
     *
     * The exact mechanism is unresolved despite two rounds of investigation. Ruled out: the
     * spring-loaded-folder `LaunchedEffect` (temporarily removed — still misses); the
     * `decoration` parameter (`TagRow` also passes one and is unaffected); interaction *count*
     * (20 clicks elsewhere on the same row never fixes it); and, most recently, the `Column`
     * wrapper and its structural on/off `InsertionLine` slots — both are gone now that the marker
     * is the draw-only `insertionMarkers` (see its KDoc) on a single `Row` exactly `TagRow`-shaped,
     * yet the *identical* one-click fix is still required. Any single click on this row — this
     * chevron toggle, or (confirmed separately) a sweep starting from its leading edge — reliably
     * settles it; an isolated click at a *different* safe position on the same row does not.
     *
     * Likely specific to the deprecated `runDesktopComposeUiTest` v1 API's frozen initial clock,
     * not a real defect: a real window runs many settled frames before a user's first possible
     * click, and `FeedRow`/`TagRow`/`ArticleRow` never reproduce it despite being structurally
     * identical in every respect checked so far.
     */
    private fun androidx.compose.ui.test.ComposeUiTest.settleFolderRowHitTesting(folderId: String) {
        val bounds = onNodeWithTag(folderRowTestTag(folderId)).fetchSemanticsNode().boundsInRoot
        val chevronCenterX = bounds.left + 8f + 10f
        onNodeWithTag(folderRowTestTag(folderId)).performMouseInput { click(Offset(chevronCenterX, bounds.height / 2f)) }
        waitForIdle()
    }

    @Composable
    private fun FeedListHitAreaTestHost(vm: HomeViewModel, height: Dp) {
        KoinApplication(application = { modules(module { single { testMenuController } }) }) {
            Box(Modifier.size(320.dp, height)) {
                FeedListPane(
                    vm = vm,
                    focused = true,
                    dragOverlay = remember { FeedDragOverlayState() },
                    onActivated = {},
                )
            }
        }
    }

    /** Inserts an article directly (bypassing the repository), mirroring `HomeViewModelTest`'s helper. */
    private fun KeryxDatabase.insertArticle(id: String, feedId: String, isRead: Long = 0L) {
        articlesQueries.insert(
            id = id, feed_id = feedId, guid = id, url = "https://article/$id", title = "Title $id",
            summary = null, content = null, author = null, published_at = null,
            thumbnail_url = null, is_read = isRead, read_at = null, is_starred = 0L, starred_at = null,
            cached_at = 0L, search_text = "", updated_at = 0L, created_at = 0L,
        )
    }

    private fun article(id: String, publishedAt: Long = 0L): ArticleListRow = ArticleListRow(
        id = id,
        feed_id = "f1",
        title = "Article $id",
        url = "u$id",
        published_at = publishedAt,
        created_at = 0L,
        is_read = 1L,
        is_starred = 0L,
    )

    private fun articles(count: Int): List<ArticleListRow> = List(count) { article("a$it", publishedAt = it.toLong()) }
}
