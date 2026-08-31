package works.merc.keryx.app.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.getString
import org.koin.compose.KoinApplication
import org.koin.dsl.koinConfiguration
import org.koin.dsl.module
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.inMemoryDb
import works.merc.keryx.app.insertFeed
import works.merc.keryx.app.insertFeedTag
import works.merc.keryx.app.insertFolder
import works.merc.keryx.app.insertTag
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.home_all_feeds
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A touch-primary list row's painted highlight must floor at [listRowMinHeight] (56dp, M3's own
 * `NavigationDrawerItem` minimum) — as an *outer* height, so every row kind lands on the same band
 * height ([LIST_ROW_VERTICAL_MARGIN] on each side) regardless of how much inner content padding it
 * happens to carry. Before the fix this test guards, `heightIn(min = listRowMinHeight())` sat
 * *inside* each row's own content padding, so the floor applied to the content alone and the
 * padding stacked on top of it — `SidebarRow` (8dp top/bottom padding) rendered at 76dp,
 * `FeedRow`/`TagFeedRow` (4dp top/bottom) at 68dp, and only `FolderGroupHeader`/`TagRow` (no
 * vertical padding of their own) landed on the intended 60dp. See `listRowMinHeight`'s own KDoc.
 *
 * The Compose test here runs at desktop density with `isTouchPrimary` forced to `true` by
 * parameter — `ExpandCollapseChevron`'s own content height therefore differs from a real Android
 * device (20dp here vs. 48dp there), but the 56dp floor dominates either way, so the asserted band
 * height (60dp) holds in both environments.
 */
@OptIn(ExperimentalTestApi::class)
class ListRowHeightTest {

    /** The band height every touch-primary row must share: the 56dp floor plus its own margin. */
    private val expectedTouchBandHeight: Dp = 56.dp + LIST_ROW_VERTICAL_MARGIN * 2

    /** One folder, one folder feed, one tag with an unfoldered feed attached — one of each row kind. */
    private fun KeryxDatabase.seedOneOfEachRowKind() {
        insertFolder("d1", "Folder One", sortOrder = 0L)
        insertFeed("f1", folderId = "d1", sortOrder = 0L)
        insertFeed("f2", sortOrder = 1L)
        insertTag("t1", "Tag One")
        insertFeedTag("f2", "t1")
    }

    @Composable
    private fun ListRowHeightTestHost(vm: HomeViewModel, isTouchPrimary: Boolean) {
        KoinApplication(configuration = koinConfiguration { modules(module { single { testMenuController } }) }) {
            // Tall enough that every row this test asserts on (3 sidebar rows, the folders
            // section, and the tags section with its expanded tag) is composed by the LazyColumn
            // without scrolling — LazyColumn only composes items inside the viewport.
            Box(Modifier.size(360.dp, 1200.dp)) {
                FeedListPane(
                    vm = vm,
                    focused = true,
                    dragOverlay = remember { FeedDragOverlayState() },
                    onActivated = {},
                    isTouchPrimary = isTouchPrimary,
                )
            }
        }
    }

    @Test
    fun everyTouchPrimaryRowKindSharesTheSameBandHeight() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.seedOneOfEachRowKind()
        useHomeViewModel(driver, db) { fixture ->
            val vm = fixture.vm
            vm.toggleTagExpanded("t1")
            setContent { ListRowHeightTestHost(vm, isTouchPrimary = true) }
            waitForIdle()

            val allFeedsLabel = getString(Res.string.home_all_feeds)
            onNodeWithText(allFeedsLabel).assertHeightIsEqualTo(expectedTouchBandHeight)
            onNodeWithTag(folderRowTestTag("d1")).assertHeightIsEqualTo(expectedTouchBandHeight)
            onNodeWithTag(feedRowTestTag("f1")).assertHeightIsEqualTo(expectedTouchBandHeight)
            onNodeWithTag(tagRowTestTag("t1")).assertHeightIsEqualTo(expectedTouchBandHeight)
            // "Feed f2" renders twice: once as a plain FeedRow under the "no folder" group, once as
            // a TagFeedRow nested under the expanded tag (see ListRowHitAreaTest's own tag-nested
            // test for the same duplication). The tag-nested instance is the second (lower) match.
            onAllNodesWithText("Feed f2")[1].assertHeightIsEqualTo(expectedTouchBandHeight)
        }
    }

    @Test
    fun theTouchFloorDoesNotApplyOnANonTouchPrimaryPlatform() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.seedOneOfEachRowKind()
        useHomeViewModel(driver, db) { fixture ->
            val vm = fixture.vm
            setContent { ListRowHeightTestHost(vm, isTouchPrimary = false) }
            waitForIdle()

            val allFeedsLabel = getString(Res.string.home_all_feeds)
            val actual = onNodeWithText(allFeedsLabel).fetchSemanticsNode().boundsInRoot.height
            val expected = with(density) { expectedTouchBandHeight.toPx() }
            assertTrue(
                actual < expected,
                "a non-touch-primary row must not be floored to the touch band height (expected < ${expected}px, was ${actual}px)",
            )
        }
    }
}
