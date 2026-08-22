package works.merc.keryx.app.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.compose.KoinApplication
import org.koin.dsl.koinConfiguration
import org.koin.dsl.module
import works.merc.keryx.app.core.ArticleFilter
import works.merc.keryx.app.data.cloud.OAuthTokens
import works.merc.keryx.app.data.cloud.TokenStorage
import works.merc.keryx.app.domain.ActivityCenter
import works.merc.keryx.app.inMemoryDb
import works.merc.keryx.app.insertFeed
import works.merc.keryx.app.insertFeedTag
import works.merc.keryx.app.insertFolder
import works.merc.keryx.app.insertTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end Compose UI tests for `FeedListPane`'s scroll-to-selection behavior (`feedListRowIndex`
 * + `scrollToIndexIfNeeded(Int)` in `HomeCommon.kt`), driven against the real rendered pane
 * exactly like `FeedListDragTest.kt`/`FeedListInlineRenameTest.kt` — `FeedListPane` (unlike
 * `ArticleListPaneContent`) does not expose its `LazyListState`, so row/host positions are read
 * from the semantics tree instead.
 */
@OptIn(ExperimentalTestApi::class)
class FeedListPaneTest {

    @Composable
    private fun FeedListPaneTestHost(vm: HomeViewModel, height: Dp) {
        KoinApplication(configuration = koinConfiguration { modules(module { single { testMenuController } }) }) {
            Box(Modifier.testTag(ROOT_TEST_TAG).size(320.dp, height)) {
                FeedListPane(
                    vm = vm,
                    focused = true,
                    dragOverlay = remember { FeedDragOverlayState() },
                    onActivated = {},
                )
            }
        }
    }

    @Test
    fun nudgesAPartiallyVisibleTagNestedFeedRowFullyIntoView() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertFolder("d1", "Folder One")
        db.insertFeed("f-tag", folderId = "d1")
        db.insertTag("t1", "Tag One")
        db.insertFeedTag("f-tag", "t1")
        val fixture = newHomeViewModel(driver, db)
        val vm = fixture.vm
        try {
            // The feed's only rendered row is the TagFeedRow: its folder is collapsed (hiding the
            // folder-group row) while the tag it's attached to is expanded.
            vm.toggleFolderCollapsed("d1")
            vm.toggleTagExpanded("t1")

            // First frame: tall enough that the row is never clipped, so its natural (unclipped)
            // position and size can be measured (mirrors
            // ArticleListPaneTest.scrollsJustEnoughToRevealPartiallyClippedSelection).
            var height by mutableStateOf(2000.dp)
            setContent { FeedListPaneTestHost(vm, height) }
            waitForIdle()

            // FeedListPane exposes no LazyListState to calibrate against directly (unlike
            // ArticleListPaneContent), so this reads positions from the semantics tree instead —
            // note `boundsInRoot` reports the *clipped* extent of a node cut off by an ancestor,
            // not an unclipped rect overhanging past the container, so a partially-visible row's
            // measured height shrinks rather than its bottom edge extending past the viewport.
            // Content above the row doesn't reflow as the host shrinks, so its measured position
            // (relative to the host box, not the window root — the box need not start at the
            // root's origin) stays valid. Shrinking to top + half the row's natural height puts
            // its bottom half past the viewport edge.
            val tallHostBounds = onNodeWithTag(ROOT_TEST_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val tallBounds = onNodeWithText("Feed f-tag", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val rowTopInHost = tallBounds.top - tallHostBounds.top
            height = with(density) { (rowTopInHost + tallBounds.height * 0.5f).toDp() }
            waitForIdle()

            val boundsBefore = onNodeWithText("Feed f-tag", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue(boundsBefore.height > 0f, "row must still be (partially) visible")
            assertTrue(boundsBefore.height < tallBounds.height, "row must be clipped, not fully visible")

            // Exactly what TagFeedRow's own onClick now passes: the tag-nested instance, not the
            // canonical folder-group one. With a bare `selectFilter(ArticleFilter.Feed("f-tag"))`
            // this test would pass for the wrong reason — the canonical instance is hidden by the
            // collapsed folder, so its index is simply unresolvable and no scroll could happen
            // regardless of the behavior under test.
            vm.selectFilter(ArticleFilter.Feed("f-tag"), FeedListRowSelection.FeedInTag("f-tag", "t1"))
            waitForIdle()

            // Now that the selection names one exact row, the scroll is the ordinary
            // `scrollToIndexIfNeeded(Int)` nudge: the clipped row is brought fully into view,
            // rather than left clipped by the old "some instance is already visible, do nothing"
            // heuristic that existed only because a selection couldn't say which row it meant.
            val boundsAfter = onNodeWithText("Feed f-tag", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue(boundsAfter.height > boundsBefore.height, "the clipped row must be revealed, not left clipped")
            assertEquals(
                tallBounds.height,
                boundsAfter.height,
                absoluteTolerance = 1f,
                message = "the row must end up fully visible, at its natural height",
            )
        } finally {
            fixture.close()
            driver.close()
        }
    }

    @Test
    fun scrollsToTheSelectedTagNestedRowRatherThanTheSameFeedsFolderGroupRow() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertFolder("d1", "Folder One")
        db.insertFeed("f-tag", folderId = "d1", sortOrder = 0L)
        repeat(30) { i -> db.insertFeed("f$i", sortOrder = (i + 1).toLong()) }
        db.insertTag("t1", "Tag One")
        db.insertFeedTag("f-tag", "t1")
        val fixture = newHomeViewModel(driver, db)
        val vm = fixture.vm
        try {
            // The feed now renders twice: once near the top under its (expanded) folder, and once
            // under the expanded tag, far below the viewport.
            vm.toggleTagExpanded("t1")
            setContent { FeedListPaneTestHost(vm, 400.dp) }
            waitForIdle()
            onNodeWithText("Feed f-tag", useUnmergedTree = true).assertIsDisplayed()

            vm.selectFilter(ArticleFilter.Feed("f-tag"), FeedListRowSelection.FeedInTag("f-tag", "t1"))
            waitForIdle()

            // Resolving the canonical (folder-group) instance instead would leave the list exactly
            // where it is — that row is already fully visible — so reaching the tags section is
            // what proves the *selected* instance is the one scrolled to.
            onNodeWithText("Tag One", useUnmergedTree = true).assertIsDisplayed()
            onNodeWithText("Feed f-tag", useUnmergedTree = true).assertIsDisplayed()
        } finally {
            fixture.close()
            driver.close()
        }
    }

    @Test
    fun stillScrollsToAnOffscreenSelection() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        repeat(30) { i -> db.insertFeed("f$i", sortOrder = i.toLong()) }
        val fixture = newHomeViewModel(driver, db)
        val vm = fixture.vm
        try {
            setContent { FeedListPaneTestHost(vm, 400.dp) }
            waitForIdle()

            // The fix only skips the scroll when the target is already visible; a genuinely
            // off-screen selection (the last of many feeds, in a short host) must still scroll.
            vm.selectFilter(ArticleFilter.Feed("f29"))
            waitForIdle()

            onNodeWithText("Feed f29", useUnmergedTree = true).assertIsDisplayed()
        } finally {
            fixture.close()
            driver.close()
        }
    }

    @Test
    fun refreshButtonStaysDisabledThroughRefreshAllsSyncPhase() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        val testScope = CoroutineScope(Dispatchers.Unconfined)
        val activityCenter = ActivityCenter(testScope)
        val fixture = newHomeViewModel(driver, db, activityCenter = activityCenter)
        val vm = fixture.vm
        try {
            setContent { FeedListPaneTestHost(vm, 300.dp) }
            waitForIdle()
            onNodeWithContentDescription("更新").assertIsEnabled()

            // Reproduces refreshAll()'s own sequencing: the feed-fetch phase (trackFeedRefresh)
            // completes, then the chained sync phase (trackSync) is still running.
            val syncGate = CompletableDeferred<Unit>()
            testScope.launch {
                activityCenter.trackFeedRefresh { }
                activityCenter.trackSync { syncGate.await() }
            }
            waitForIdle()

            onNodeWithContentDescription("更新").assertIsNotEnabled()

            syncGate.complete(Unit)
            waitForIdle()
            onNodeWithContentDescription("更新").assertIsEnabled()
        } finally {
            testScope.cancel()
            fixture.close()
            driver.close()
        }
    }

    @Test
    fun syncButtonStaysDisabledThroughRefreshAllsFetchPhase() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        val testScope = CoroutineScope(Dispatchers.Unconfined)
        val activityCenter = ActivityCenter(testScope)
        val tokenStorage = FeedListPaneTestTokenStorage().apply { save(OAuthTokens("AT", "RT")) }
        val fixture = newHomeViewModel(
            driver, db, activityCenter = activityCenter, tokenStorage = tokenStorage, appKey = "test-app-key",
        )
        val vm = fixture.vm
        try {
            setContent { FeedListPaneTestHost(vm, 300.dp) }
            waitForIdle()
            onNodeWithContentDescription("同期").assertIsEnabled()

            // Counterpart to refreshButtonStaysDisabledThroughRefreshAllsSyncPhase: proves the sync
            // button stays disabled through refreshAll()'s fetch phase (trackFeedRefresh), before the
            // chained sync phase (trackSync) starts. (Not during trackSync itself: syncing == true
            // swaps this button's content to a bare SmallSpinner with no content description, so it
            // can no longer be located by "同期" at that point — the fetch phase is the assertion
            // window where the button is both disabled and still identifiable this way.)
            val fetchGate = CompletableDeferred<Unit>()
            testScope.launch {
                activityCenter.trackFeedRefresh { fetchGate.await() }
                activityCenter.trackSync { }
            }
            waitForIdle()

            onNodeWithContentDescription("同期").assertIsNotEnabled()

            fetchGate.complete(Unit)
            waitForIdle()
            onNodeWithContentDescription("同期").assertIsEnabled()
        } finally {
            testScope.cancel()
            fixture.close()
            driver.close()
        }
    }
}

private class FeedListPaneTestTokenStorage : TokenStorage {
    private var stored: OAuthTokens? = null
    override fun save(tokens: OAuthTokens) { stored = tokens }
    override fun load(): OAuthTokens? = stored
    override fun clear() { stored = null }
}

private const val ROOT_TEST_TAG = "feed-list-pane-test-root"
