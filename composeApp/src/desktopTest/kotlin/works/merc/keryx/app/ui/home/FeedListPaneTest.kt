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
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.koin.compose.KoinApplication
import org.koin.dsl.module
import works.merc.keryx.app.core.ArticleFilter
import works.merc.keryx.app.inMemoryDb
import works.merc.keryx.app.insertFeed
import works.merc.keryx.app.insertFeedTag
import works.merc.keryx.app.insertFolder
import works.merc.keryx.app.insertTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end Compose UI tests for `FeedListPane`'s scroll-to-selection behavior
 * (`scrollToIndexIfNeeded(List<Int>)` in `HomeCommon.kt`), driven against the real rendered pane
 * exactly like `FeedListDragTest.kt`/`FeedListInlineRenameTest.kt` — `FeedListPane` (unlike
 * `ArticleListPaneContent`) does not expose its `LazyListState`, so row/host positions are read
 * from the semantics tree instead.
 */
@OptIn(ExperimentalTestApi::class)
class FeedListPaneTest {

    @Composable
    private fun FeedListPaneTestHost(vm: HomeViewModel, height: Dp) {
        KoinApplication(application = { modules(module { single { testMenuController } }) }) {
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
    fun doesNotScrollWhenSelectingAPartiallyVisibleTagNestedFeedRow() = runDesktopComposeUiTest {
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

            vm.selectFilter(ArticleFilter.Feed("f-tag"))
            waitForIdle()

            val boundsAfter = onNodeWithText("Feed f-tag", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertEquals(boundsBefore, boundsAfter, "selecting an already (partially) visible row must not scroll the list")
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
}

private const val ROOT_TEST_TAG = "feed-list-pane-test-root"
