package works.merc.keryx.app.ui.common

import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class KeryxDialogTabsTest {

    private val tabs = listOf(
        KeryxDialogTab("general", "General", KeryxIcons.Tune),
        KeryxDialogTab("notifications", "Notifications", KeryxIcons.Notifications),
    )

    @Test
    fun rendersAllTabLabels() = runDesktopComposeUiTest {
        setContent {
            SecondaryScrollableTabRow(selectedTabIndex = 0) {
                KeryxDialogTabs(
                    tabs = tabs,
                    selectedTabId = "general",
                    onSelectTab = {},
                )
            }
        }
        waitForIdle()

        onNodeWithText("General").assertExists()
        onNodeWithText("Notifications").assertExists()
    }

    @Test
    fun selectedTabIsMarkedSelected() = runDesktopComposeUiTest {
        setContent {
            SecondaryScrollableTabRow(selectedTabIndex = 0) {
                KeryxDialogTabs(
                    tabs = tabs,
                    selectedTabId = "general",
                    onSelectTab = {},
                )
            }
        }
        waitForIdle()

        onNodeWithText("General").assertIsSelected()
    }

    @Test
    fun clickInvokesOnSelectTab() = runDesktopComposeUiTest {
        var selectedId: String? = null
        setContent {
            SecondaryScrollableTabRow(selectedTabIndex = 0) {
                KeryxDialogTabs(
                    tabs = tabs,
                    selectedTabId = "general",
                    onSelectTab = { selectedId = it },
                )
            }
        }
        waitForIdle()

        onNodeWithText("Notifications").performClick()
        waitForIdle()

        assertEquals("notifications", selectedId)
    }
}
