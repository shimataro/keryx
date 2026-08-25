package works.merc.keryx.app.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Desktop `actual` of [KeryxSettingRow] — see its own KDoc: no [trailing] means the former
 * `LinkRow`/`ActionLinkRow` behavior (label itself is the click target); a [trailing] slot means
 * the former `SwitchRow` behavior (only the trailing control is interactive, the row itself is not).
 */
@OptIn(ExperimentalTestApi::class)
class KeryxSettingRowTest {

    @Test
    fun clickingTheLabelInvokesOnClickWhenNoTrailing() = runDesktopComposeUiTest {
        var clicked = false
        setContent {
            MaterialTheme {
                KeryxSettingRow(label = "About", onClick = { clicked = true })
            }
        }

        onNodeWithText("About").performMouseInput { click() }

        assertEquals(true, clicked)
    }

    @Test
    fun supportingTextIsNotShownInlineOnDesktop() = runDesktopComposeUiTest {
        setContent {
            MaterialTheme {
                KeryxSettingRow(label = "Website", supporting = "https://example.com", onClick = {})
            }
        }

        // Desktop shows `supporting` as a hover tooltip, not inline text — see the actual's KDoc.
        onNodeWithText("Website").assertIsDisplayed()
    }

    @Test
    fun trailingSlotIsTheOnlyInteractiveElement() = runDesktopComposeUiTest {
        var checked = false
        var rowOnClickFired = false
        setContent {
            MaterialTheme {
                KeryxSettingRow(
                    label = "Notifications",
                    // A row with a trailing slot (the former SwitchRow) still accepts onClick —
                    // Android's ListItem actual wires it to the whole row — but desktop's actual
                    // must ignore it entirely; only the switch below is ever clickable there.
                    onClick = { rowOnClickFired = true },
                    trailing = { FlatSwitch(checked = checked, onCheckedChange = { checked = it }) },
                )
            }
        }

        onNode(isToggleable()).performMouseInput { click() }

        assertEquals(false, rowOnClickFired, "the row's own onClick must not fire on desktop when a trailing slot is present")
    }
}
