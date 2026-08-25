package works.merc.keryx.app.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
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
 * click target grows on touch while the icon itself stays 20dp.
 */
@OptIn(ExperimentalTestApi::class)
class ExpandCollapseChevronTest {

    @Test
    fun clickTogglesRegardlessOfPlatform() = runDesktopComposeUiTest {
        var expanded = false
        setContent {
            MaterialTheme {
                Box(Modifier.testTag("chevron")) {
                    ExpandCollapseChevron(
                        expanded = expanded,
                        onToggle = { expanded = !expanded },
                        isTouchPrimary = false,
                    )
                }
            }
        }

        onNodeWithTag("chevron").performMouseInput { click() }

        assertTrue(expanded, "expected onToggle to flip the expanded flag")
    }

    @Test
    fun touchTargetGrowsTo48dpOnTouchPrimary() = runDesktopComposeUiTest {
        setContent {
            MaterialTheme {
                Box(Modifier.testTag("chevron")) {
                    ExpandCollapseChevron(expanded = false, onToggle = {}, isTouchPrimary = true)
                }
            }
        }

        val bounds = onNodeWithTag("chevron", useUnmergedTree = true).getBoundsInRoot()
        assertEquals(48.dp, bounds.width)
        assertEquals(48.dp, bounds.height)
    }

    @Test
    fun clickTargetStays20dpWhenNotTouchPrimary() = runDesktopComposeUiTest {
        setContent {
            MaterialTheme {
                Box(Modifier.testTag("chevron")) {
                    ExpandCollapseChevron(expanded = false, onToggle = {}, isTouchPrimary = false)
                }
            }
        }

        val bounds = onNodeWithTag("chevron", useUnmergedTree = true).getBoundsInRoot()
        assertEquals(20.dp, bounds.width)
        assertEquals(20.dp, bounds.height)
    }

    @Test
    fun onClickLabelReflectsCurrentState() = runDesktopComposeUiTest {
        setContent {
            MaterialTheme {
                Box(Modifier.testTag("chevron")) {
                    ExpandCollapseChevron(expanded = false, onToggle = {}, isTouchPrimary = false)
                }
            }
        }

        // The click action's semantics live on the inner clickable node, not on the outer
        // testTag'd Box — this scene has exactly one clickable node, so match on that directly.
        val action = onNode(hasClickAction(), useUnmergedTree = true)
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsActions.OnClick)

        assertEquals("展開する", action?.label)
    }
}
