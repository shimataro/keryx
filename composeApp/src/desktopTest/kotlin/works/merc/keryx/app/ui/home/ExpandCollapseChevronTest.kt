package works.merc.keryx.app.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [ExpandCollapseChevron] backs the folder/tag row expand toggle — see its own KDoc for why the
 * touch target grows on a touch-primary platform while the icon's own *reported layout size* stays
 * [EXPAND_CHEVRON_SLOT] (`20.dp`) regardless, via [Modifier.layoutAs].
 */
@OptIn(ExperimentalTestApi::class)
class ExpandCollapseChevronTest {

    /** Wraps the chevron with enough padding that a touch target extending past its own reported
     * bounds (see [layoutAs]) still resolves to positive on-screen coordinates. */
    @androidx.compose.runtime.Composable
    private fun ChevronTestHost(expanded: Boolean, onToggle: () -> Unit, isTouchPrimary: Boolean) {
        MaterialTheme {
            Box(Modifier.padding(32.dp)) {
                Box(Modifier.testTag("chevron")) {
                    ExpandCollapseChevron(expanded = expanded, onToggle = onToggle, isTouchPrimary = isTouchPrimary)
                }
            }
        }
    }

    @Test
    fun clickTogglesRegardlessOfPlatform() = runDesktopComposeUiTest {
        var expanded = false
        setContent { ChevronTestHost(expanded, { expanded = !expanded }, isTouchPrimary = false) }

        onNodeWithTag("chevron").performMouseInput { click() }

        assertTrue(expanded, "expected onToggle to flip the expanded flag")
    }

    @Test
    fun clickTogglesOnTouchPrimaryToo() = runDesktopComposeUiTest {
        var expanded = false
        setContent { ChevronTestHost(expanded, { expanded = !expanded }, isTouchPrimary = true) }

        onNodeWithTag("chevron").performMouseInput { click() }

        assertTrue(expanded, "expected onToggle to flip the expanded flag on a touch-primary platform too")
    }

    /**
     * The chevron's *reported* layout size — what [feedRowIndent] in `FeedListDragAndDrop.kt` is
     * built on — must stay [EXPAND_CHEVRON_SLOT] (20dp) on every platform. Before [layoutAs]
     * existed, the touch-primary case reported a full 48dp box here, which is exactly what let the
     * feed list's hierarchy indent depend on touch density.
     */
    @Test
    fun reportedSizeStays20dpOnNonTouchPrimary() = runDesktopComposeUiTest {
        setContent { ChevronTestHost(expanded = false, onToggle = {}, isTouchPrimary = false) }

        val bounds = onNodeWithTag("chevron", useUnmergedTree = true).getBoundsInRoot()
        assertEquals(20.dp, bounds.width)
        assertEquals(20.dp, bounds.height)
    }

    @Test
    fun reportedSizeStays20dpOnTouchPrimary() = runDesktopComposeUiTest {
        setContent { ChevronTestHost(expanded = false, onToggle = {}, isTouchPrimary = true) }

        val bounds = onNodeWithTag("chevron", useUnmergedTree = true).getBoundsInRoot()
        assertEquals(20.dp, bounds.width)
        assertEquals(20.dp, bounds.height)
    }

    /**
     * On a touch-primary platform, the actual Material touch target reaches well past the
     * reported 20dp box (up to [TOUCH_TARGET_MIN_SIZE], 48dp, centered on it) — a click 20dp from
     * the chevron's own center is outside the reported box but still inside that enlarged target,
     * and must still resolve.
     */
    @Test
    fun touchClickReachesPastTheReportedBoundsOnTouchPrimary() = runDesktopComposeUiTest {
        var expanded = false
        setContent { ChevronTestHost(expanded, { expanded = !expanded }, isTouchPrimary = true) }

        onNodeWithTag("chevron", useUnmergedTree = true).performMouseInput {
            click(center + Offset(20.dp.toPx(), 0f))
        }

        assertTrue(expanded, "expected a click 20dp from center to still land inside the enlarged touch target")
    }

    /** The same click, offset the same way, must NOT resolve on a non-touch-primary platform —
     * there the touch target never grows past the 20dp icon itself. */
    @Test
    fun theSameOffsetClickMissesOnNonTouchPrimary() = runDesktopComposeUiTest {
        var expanded = false
        setContent { ChevronTestHost(expanded, { expanded = !expanded }, isTouchPrimary = false) }

        onNodeWithTag("chevron", useUnmergedTree = true).performMouseInput {
            click(center + Offset(20.dp.toPx(), 0f))
        }

        assertTrue(!expanded, "a click 20dp from center should miss the plain 20dp icon on a non-touch-primary platform")
    }

    @Test
    fun onClickLabelReflectsCurrentState() = runDesktopComposeUiTest {
        setContent { ChevronTestHost(expanded = false, onToggle = {}, isTouchPrimary = false) }

        // The click action's semantics live on the inner clickable node, not on the outer
        // testTag'd Box — this scene has exactly one clickable node, so match on that directly.
        val action = onNode(hasClickAction(), useUnmergedTree = true)
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsActions.OnClick)

        assertEquals("展開する", action?.label)
    }

    // --- feedRowIndent (a pure constant, no composition needed) ---

    /**
     * Pinned to the value the feed list's hierarchy indent has always visually read as on desktop
     * (`FEED_LIST_ROW_START_PADDING` 8dp + `EXPAND_CHEVRON_SLOT` 20dp + `CHEVRON_MARKER_GAP` 4dp +
     * the 12dp hierarchy step) — and, since none of those four is touch-density-dependent any
     * more, the same value applies on Android too. See `feedRowIndent`'s own KDoc in
     * `FeedListDragAndDrop.kt` for why this is a plain constant rather than a function of
     * `isTouchPrimary`.
     */
    @Test
    fun feedRowIndentIs44dp() {
        assertEquals(44.dp, feedRowIndent())
    }

    /**
     * Asserts the relationship itself, not just the pinned value above, so a change to any of the
     * three shared column constants can't silently desync from [feedRowIndent] without a test
     * noticing.
     */
    @Test
    fun feedRowIndentIsTheSumOfTheSharedColumnConstantsPlusTheHierarchyStep() {
        assertEquals(
            FEED_LIST_ROW_START_PADDING + EXPAND_CHEVRON_SLOT + CHEVRON_MARKER_GAP + 12.dp,
            feedRowIndent(),
            "feedRowIndent must track FEED_LIST_ROW_START_PADDING/EXPAND_CHEVRON_SLOT/CHEVRON_MARKER_GAP",
        )
    }
}
