package works.merc.keryx.app.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.koin.compose.KoinApplication
import org.koin.dsl.koinConfiguration
import org.koin.dsl.module
import works.merc.keryx.app.inMemoryDb
import works.merc.keryx.app.insertFeed
import works.merc.keryx.app.insertFeedTag
import works.merc.keryx.app.insertFolder
import works.merc.keryx.app.insertTag
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Verifies the feed list's hierarchy geometry actually lines up, end to end — the regression this
 * guards against is exactly what motivated `feedRowIndent`/`FEED_LIST_MARKER_SLOT`/
 * `CHEVRON_MARKER_GAP` becoming shared, touch-density-independent constants (see their own KDoc in
 * `ListRowChrome.kt` and `FeedListDragAndDrop.kt`): before that change, a folder row's icon and a
 * tag row's color dot sat at different x positions (the folder header's own leading padding was
 * `0.dp` where every other row used `8.dp`), and on a touch-primary platform the tag color dot's
 * `48.dp` Material touch target widened its row's own layout, pushing a tag-nested feed row's
 * favicon to the *left* of the tag name it nests under.
 *
 * These tests render real rows through [FeedListPane] (not the lower-level pure functions covered
 * by `ExpandCollapseChevronTest`) so a regression in how the pieces compose together — not just in
 * one constant's value — would be caught. Positions are read off the rendered label `Text` nodes,
 * with `useUnmergedTree = true` — every row is a `listRowClickable`/`selectable` node that merges
 * its descendants' semantics into itself by default, so the *merged* tree would report a `Text`
 * node's bounds as its ancestor row's whole-width bounds instead of the label's own. Each label's
 * position is then converted back to its row's own marker-column (icon/color-dot) position by
 * subtracting the known trailing gap — see the per-test comments for the arithmetic.
 */
@OptIn(ExperimentalTestApi::class)
class FeedListIndentAlignmentTest {

    /** [FEED_LIST_MARKER_SLOT] (18dp) plus the 8dp gap every folder/tag row places between its
     * marker (folder icon / tag color dot) and its label — `FolderGroupHeader`'s
     * `Spacer(Modifier.width(8.dp))` after the icon, and `TagRow`'s color-dot click target, whose
     * own trailing `padding(end = 8.dp)` bundles the same gap into its footprint
     * (`TAG_COLOR_DOT_FOOTPRINT` in `FeedListPane.kt`, private there). */
    private val MARKER_TO_LABEL_GAP = FEED_LIST_MARKER_SLOT + 8.dp

    /** [FEED_LIST_MARKER_SLOT] plus `FeedRow`/`TagFeedRow`'s own trailing `Spacer(12.dp)` between
     * the favicon and the feed title. */
    private val FAVICON_TO_TITLE_GAP = FEED_LIST_MARKER_SLOT + 12.dp

    @Composable
    private fun FeedListHost(vm: HomeViewModel) {
        KoinApplication(configuration = koinConfiguration { modules(module { single { testMenuController } }) }) {
            Box(Modifier.size(320.dp, 600.dp)) {
                FeedListPane(vm = vm, focused = true, dragOverlay = remember { FeedDragOverlayState() }, onActivated = {})
            }
        }
    }

    private fun ComposeUiTest.dpToPx(dp: Dp): Float = with(density) { dp.toPx() }

    /** The left edge of the [index]-th node showing [text], read from the *unmerged* semantics
     * tree — see this class's own KDoc for why the merged tree would report the wrong bounds. */
    private fun ComposeUiTest.textLeft(text: String, index: Int = 0): Float =
        onAllNodesWithText(text, useUnmergedTree = true)[index].fetchSemanticsNode().boundsInRoot.left

    @Test
    fun folderIconAndTagColorDotStartAtTheSameXPosition() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertFolder("d1", "Folder One")
        db.insertTag("t1", "Tag One")
        useHomeViewModel(driver, db) { fixture ->
            setContent { FeedListHost(fixture.vm) }
            waitForIdle()

            val folderMarkerLeft = textLeft("Folder One") - dpToPx(MARKER_TO_LABEL_GAP)
            val tagMarkerLeft = textLeft("Tag One") - dpToPx(MARKER_TO_LABEL_GAP)

            assertApproxEquals(
                folderMarkerLeft,
                tagMarkerLeft,
                message = "a folder's icon and a tag's color dot must start at the same x position",
            )
        }
    }

    @Test
    fun feedNestedUnderAFolderIsIndentedExactlyTheHierarchyStepPastTheFolderIcon() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertFolder("d1", "Folder One")
        db.insertFeed("f1", folderId = "d1")
        useHomeViewModel(driver, db) { fixture ->
            setContent { FeedListHost(fixture.vm) }
            waitForIdle()

            val folderMarkerLeft = textLeft("Folder One") - dpToPx(MARKER_TO_LABEL_GAP)
            val feedAvatarLeft = textLeft("Feed f1") - dpToPx(FAVICON_TO_TITLE_GAP)

            assertApproxEquals(
                folderMarkerLeft + dpToPx(12.dp),
                feedAvatarLeft,
                message = "a feed nested under a folder must sit exactly the 12dp hierarchy step past its folder's own icon",
            )
        }
    }

    @Test
    fun feedNestedUnderATagIsIndentedExactlyTheHierarchyStepPastTheTagColorDot() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertFeed("f1")
        db.insertTag("t1", "Tag One")
        db.insertFeedTag("f1", "t1")
        useHomeViewModel(driver, db) { fixture ->
            val vm = fixture.vm
            vm.toggleTagExpanded("t1")
            setContent { FeedListHost(vm) }
            waitForIdle()

            val tagMarkerLeft = textLeft("Tag One") - dpToPx(MARKER_TO_LABEL_GAP)
            // "Feed f1" renders twice: once un-indented under "No Folder", once nested under the
            // expanded tag — the tag-nested instance is the second (lower) match, exactly like
            // ListRowHitAreaTest's equivalent lookup.
            val feedAvatarLeft = textLeft("Feed f1", index = 1) - dpToPx(FAVICON_TO_TITLE_GAP)

            assertApproxEquals(
                tagMarkerLeft + dpToPx(12.dp),
                feedAvatarLeft,
                message = "a feed nested under a tag must sit exactly the 12dp hierarchy step past its tag's own color dot",
            )
        }
    }

    /** [kotlin.test.assertEquals] has no float-tolerance overload; layout math above can be off by
     * a fraction of a pixel from rounding, so compare with a small tolerance instead of exact
     * equality. */
    private fun assertApproxEquals(expected: Float, actual: Float, message: String) {
        assertTrue(
            kotlin.math.abs(expected - actual) <= 0.5f,
            "$message (expected=$expected, actual=$actual)",
        )
    }
}
