package works.merc.keryx.app.android

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsToggleable
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import works.merc.keryx.app.ui.common.FlatSwitch
import works.merc.keryx.app.ui.common.KeryxSettingRow

/**
 * Regression coverage for the Android [KeryxSettingRow] actual's toggle semantics — see review
 * finding #2 (`v0.11.0..HEAD`): before this, a toggle row exposed no [Role.Switch]/checked state
 * to accessibility services (TalkBack announced it as a bare button with no on/off state) and the
 * row plus its trailing [FlatSwitch] merged into two separately-focusable stops instead of one.
 * Lives in androidApp for the same reason [NativeMenuAndroidGestureTest] does — Compose
 * Multiplatform's Android instrumented runner needs a real Android application module to host
 * [createComposeRule].
 */
class KeryxSettingRowAndroidGestureTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val roleSwitch = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)

    @Test
    fun toggleRowExposesRoleSwitchAndCheckedState() {
        composeTestRule.setContent {
            var checked by remember { mutableStateOf(false) }
            KeryxSettingRow(
                label = "Notifications",
                modifier = Modifier.testTag("setting-row").size(width = 300.dp, height = 56.dp),
                onClick = { checked = !checked },
                trailing = { FlatSwitch(checked = checked, onCheckedChange = { checked = it }) },
                toggled = checked,
            )
        }

        composeTestRule.onNodeWithTag("setting-row")
            .assert(roleSwitch) { "expected the row itself to carry Role.Switch" }
            .assertIsToggleable()
            .assertIsOff()
    }

    @Test
    fun clickingTheRowFlipsTheExposedCheckedState() {
        composeTestRule.setContent {
            var checked by remember { mutableStateOf(false) }
            KeryxSettingRow(
                label = "Notifications",
                modifier = Modifier.testTag("setting-row").size(width = 300.dp, height = 56.dp),
                onClick = { checked = !checked },
                trailing = { FlatSwitch(checked = checked, onCheckedChange = { checked = it }) },
                toggled = checked,
            )
        }

        composeTestRule.onNodeWithTag("setting-row").performClick()

        composeTestRule.onNodeWithTag("setting-row").assertIsOn()
    }

    @Test
    fun rowAndTrailingSwitchDoNotProduceTwoSeparatelyFocusableNodes() {
        composeTestRule.setContent {
            var checked by remember { mutableStateOf(false) }
            KeryxSettingRow(
                label = "Notifications",
                modifier = Modifier.testTag("setting-row").size(width = 300.dp, height = 56.dp),
                onClick = { checked = !checked },
                trailing = { FlatSwitch(checked = checked, onCheckedChange = { checked = it }) },
                toggled = checked,
            )
        }

        // Before this fix, the row's own clickable and the trailing FlatSwitch's own M3 Switch
        // each surfaced their own click action to the merged (default) semantics tree — two
        // separately-focusable stops for accessibility services to land on for what is visually
        // one control. clearAndSetSemantics on the trailing slot's container makes the inner
        // Switch's own merge group contribute empty semantics instead, so only the row's own click
        // action remains in the merged tree (the query default; the raw node still physically
        // exists underneath, which is why this deliberately does not pass useUnmergedTree = true).
        composeTestRule.onAllNodes(hasClickAction()).assertCountEquals(1)
    }
}
